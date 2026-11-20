package com.ritense.pdca.devsetup

import com.fasterxml.jackson.databind.JsonNode
import java.io.File

/**
 * The four register plugins on the stack's plugin host: upload the built
 * packages, wait for GZAC to discover them and create or update their
 * configurations (Admin → Plugins → Upload plugin / Add configuration).
 */
class RegisterPlugins(private val api: GzacApi, private val values: DevValues, private val repoDir: File) {
    private val plugins = ExternalPlugins(api)

    /** Uploads and configures every register plugin; returns the configuration id per plugin id. */
    fun apply(): Map<String, String> {
        val host = pluginHost()
        values.pluginHost.configurations.forEach { upload(host, it.pluginId) }
        return values.pluginHost.configurations.associate { configuration ->
            val definition = plugins.awaitDefinition(host, configuration.pluginId, version(configuration.pluginId), timeoutSeconds = 90)
            configuration.pluginId to configure(definition, configuration)
        }
    }

    /** The configuration id per plugin id, without changing anything (for the contract bindings). */
    fun existingConfigurations(): Map<String, String> = currentConfigurations().mapValues { (pluginId, id) ->
        id ?: throw SetupException("$pluginId ${version(pluginId)} has no configuration on the plugin host; run ./gradlew gzacDevSetup")
    }

    /** The configuration id per plugin id as GZAC has it now; null where the plugin version or its configuration is missing. */
    fun currentConfigurations(): Map<String, String?> {
        val host = pluginHost()
        return values.pluginHost.configurations.associate { configuration ->
            val definition = plugins.definition(host.text("id")!!, configuration.pluginId, version(configuration.pluginId))
            configuration.pluginId to definition?.let { find(it, configuration) }?.text("id")
        }
    }

    private fun pluginHost(): JsonNode {
        val hosts = plugins.hosts().filter { it.text("kind") == "PLUGIN_HOST" }
        val host = hosts.firstOrNull { ExternalPlugins.sameUrl(it.text("baseUrl"), values.pluginHost.baseUrl) }
            ?: hosts.singleOrNull()
            ?: throw SetupException(
                "GZAC has no plugin host at ${values.pluginHost.baseUrl} (the Valtimo dev app registers 'Local plugin host' there " +
                    "when started with its docker stack)"
            )
        if (host.text("status") != "CONNECTED") {
            Console.warn("plugin host '${host.text("name")}' (${host.text("baseUrl")}) is ${host.text("status")}")
        }
        return host
    }

    private fun version(pluginId: String): String =
        Json.mapper.readTree(repoDir.resolve("plugins/$pluginId/manifest.json")).path("version").asText()

    private fun upload(host: JsonNode, pluginId: String) {
        val version = version(pluginId)
        val zip = repoDir.resolve("plugins/$pluginId/dist/$pluginId-$version.zip")
        if (!zip.isFile) throw SetupException("${zip.relativeTo(repoDir)} is not built; run ./gradlew buildPlugins")
        val path = "${ExternalPlugins.PATH}/host/${host.text("id")}/upload"
        val part = listOf(Part("file", zip.readBytes(), "application/zip", zip.name))
        val response = api.multipart(path, part)
        val answer = response.json()
        when {
            response.ok -> Console.info("$pluginId $version: uploaded to the plugin host")
            response.status == 409 && answer.text("code") == "PLUGIN_VERSION_EXISTS" ->
                if (answer.text("currentContentHash") == answer.text("uploadedContentHash")) {
                    Console.info("$pluginId $version: already on the plugin host")
                } else {
                    api.multipart("$path?overwrite=true", part).orThrow("Replacing $pluginId $version on the plugin host")
                    Console.info("$pluginId $version: replaced on the plugin host (the package changed)")
                }
            response.status == 409 && answer.path("incompatible").asBoolean() -> throw SetupException(
                "$pluginId $version does not support this GZAC ${answer.text("currentGzacVersion")} " +
                    "(supports ${answer.text("minGzacVersion")} – ${answer.text("maxGzacVersion")})"
            )
            else -> response.orThrow("Uploading ${zip.name}")
        }
    }

    private fun find(definition: JsonNode, configuration: RegisterConfiguration): JsonNode? {
        val existing = plugins.configurations(definition.text("id")!!)
        return existing.firstOrNull { it.text("title") == configuration.title } ?: existing.singleOrNull()
    }

    /**
     * Creates the configuration, or updates the existing one with all its
     * properties: GZAC never returns secrets, so they are always sent, which
     * also stores them encrypted with the discovered manifest.
     */
    private fun configure(definition: JsonNode, configuration: RegisterConfiguration): String {
        val label = "${configuration.pluginId} ${definition.text("version")}"
        val existing = find(definition, configuration)
        if (existing != null) {
            val id = existing.text("id")!!
            api.put(
                "${ExternalPlugins.PATH}/configuration/$id",
                mapOf("title" to configuration.title, "properties" to configuration.properties),
            ).orThrow("Updating the configuration of $label")
            Console.info("$label: configuration '${configuration.title}' updated ($id)")
            return id
        }
        val created = api.post(
            "${ExternalPlugins.PATH}/configuration",
            mapOf(
                "definitionId" to definition.text("id"),
                "title" to configuration.title,
                "properties" to configuration.properties,
                "grantedEndpoints" to emptyList<Any>(),
                "grantedCapabilities" to configuration.grantedCapabilities,
            ),
        ).orThrow("Creating the configuration of $label").json()
        val id = created.text("id")!!
        Console.info("$label: configuration '${configuration.title}' created ($id)")
        return id
    }
}
