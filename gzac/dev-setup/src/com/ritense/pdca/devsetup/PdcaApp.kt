package com.ritense.pdca.devsetup

import com.fasterxml.jackson.databind.JsonNode

/**
 * The PDCA app as external app (URL app) in GZAC: its host registration,
 * which needs nothing from the app, and its configuration with grants and
 * contract bindings, which needs the manifest GZAC discovers from the
 * running app.
 */
class PdcaApp(private val api: GzacApi, private val values: DevValues) {
    private val plugins = ExternalPlugins(api)
    private val app = values.app

    val healthUrl = "${app.baseUrl.trimEnd('/')}/health"

    /** Waits up to [seconds] for the app's health check; false when it does not answer. */
    fun awaitHealth(seconds: Long): Boolean {
        val deadline = System.currentTimeMillis() + seconds * 1000
        while (true) {
            if (api.isUp(healthUrl)) return true
            if (System.currentTimeMillis() >= deadline) return false
            Thread.sleep(3_000)
        }
    }

    /** Registers the app with GZAC, or re-applies its connection settings; returns the host. */
    fun registerHost(): JsonNode {
        val existing = host()
        if (existing == null) {
            val response = api.post(
                "${ExternalPlugins.PATH}/host",
                mapOf(
                    "name" to app.name,
                    "kind" to "APP",
                    "baseUrl" to app.baseUrl,
                    "secret" to app.secret,
                    "gzacCallbackBaseUrl" to app.gzacCallbackBaseUrl,
                    "frontendOrigins" to app.frontendOrigins,
                ),
            )
            if (response.status == 409) {
                throw SetupException(
                    "GZAC already has plugin ${values.app.configuration.pluginId} ${values.app.configuration.pluginVersion} " +
                        "on another host: ${response.excerpt()}. Remove that host in Admin → Plugins and re-run"
                )
            }
            val host = response.orThrow("Registering the PDCA app").json()
            Console.info("'${app.name}' registered at ${app.baseUrl} (${host.text("id")}); GZAC polls it until the app answers")
            return host
        }
        val id = existing.text("id")!!
        // The secret is never returned; sending the same one again is no rotation for GZAC.
        val host = api.patch(
            "${ExternalPlugins.PATH}/host/$id/connection",
            mapOf("name" to app.name, "baseUrl" to app.baseUrl, "secret" to app.secret, "gzacCallbackBaseUrl" to app.gzacCallbackBaseUrl),
        ).orThrow("Updating the connection of '${app.name}'").json()
        if (existing.path("frontendOrigins").map { it.asText() }.toSet() != app.frontendOrigins.toSet()) {
            api.patch("${ExternalPlugins.PATH}/host/$id/frontend-origins", mapOf("frontendOrigins" to app.frontendOrigins))
                .orThrow("Updating the frontend origins of '${app.name}'")
        }
        Console.info("'${app.name}' present at ${app.baseUrl} ($id); connection settings applied")
        return host
    }

    /**
     * Creates or updates the PDCA configuration with exactly the grants of the
     * app's manifest and the contract bindings to [registerConfigurations]
     * (configuration id per register plugin id); returns its id.
     */
    fun configure(registerConfigurations: Map<String, String>): String {
        val configuration = app.configuration
        val host = host() ?: throw SetupException("'${app.name}' is not registered in GZAC; run ./gradlew gzacDevSetup")
        val definition = plugins.awaitDefinition(host, configuration.pluginId, configuration.pluginVersion, timeoutSeconds = 120)
        val bindings = configuration.contractBindings.map { binding ->
            mapOf(
                "contract" to binding.contract,
                "targetConfigurationId" to (registerConfigurations[binding.pluginId]
                    ?: throw SetupException("no configuration of ${binding.pluginId} for contract ${binding.contract}")),
            )
        }
        val endpoints = configuration.grantedEndpoints.map { mapOf("method" to it.method, "pattern" to it.pattern) }
        val label = "${configuration.pluginId} ${configuration.pluginVersion}"

        val existing = findConfiguration(definition)
        if (existing == null) {
            val created = api.post(
                "${ExternalPlugins.PATH}/configuration",
                mapOf(
                    "definitionId" to definition.text("id"),
                    "title" to configuration.title,
                    "properties" to configuration.properties,
                    "grantedEndpoints" to endpoints,
                    "grantedCapabilities" to configuration.grantedCapabilities,
                    "contractBindings" to bindings,
                ),
            ).orThrow("Creating the configuration of $label").json()
            Console.info("$label: configuration '${configuration.title}' created (${created.text("id")}) with ${bindings.size} contract bindings")
            return created.text("id")!!
        }

        val id = existing.text("id")!!
        api.put(
            "${ExternalPlugins.PATH}/configuration/$id",
            mapOf(
                "title" to configuration.title,
                "properties" to configuration.properties,
                "grantedEndpoints" to endpoints,
                "contractBindings" to bindings,
            ),
        ).orThrow("Updating the configuration of $label")
        val capabilities = plugins.configuration(id).path("grantedCapabilities").map { it.text("capability") }.toSet()
        if (capabilities != configuration.grantedCapabilities.toSet()) {
            Console.warn(
                "$label: configuration $id has capabilities $capabilities instead of ${configuration.grantedCapabilities}; " +
                    "GZAC cannot change them, delete the configuration in Admin → Plugins and re-run"
            )
        }
        Console.info("$label: configuration '${configuration.title}' updated ($id) with ${bindings.size} contract bindings")
        return id
    }

    /**
     * What the app's registration and configuration lack, without changing
     * anything; [registerConfigurations] is the configuration id per register
     * plugin id as GZAC has it now.
     */
    fun missing(registerConfigurations: Map<String, String?>): List<String> {
        val configuration = app.configuration
        val host = host() ?: return listOf("PDCA app registration")
        val definition = plugins.definition(host.text("id")!!, configuration.pluginId, configuration.pluginVersion)
            ?: return listOf("PDCA configuration (${configuration.pluginId} ${configuration.pluginVersion} not discovered yet)")
        val existing = findConfiguration(definition) ?: return listOf("PDCA configuration")
        val desired = configuration.contractBindings.associate { it.contract to registerConfigurations[it.pluginId] }
        return missingBindings(desired, plugins.configuration(existing.text("id")!!).path("contractBindings"))
    }

    private fun findConfiguration(definition: JsonNode): JsonNode? {
        val candidates = plugins.configurations(definition.text("id")!!)
        return candidates.firstOrNull { it.text("title") == app.configuration.title } ?: candidates.singleOrNull()
    }

    private fun host(): JsonNode? {
        val apps = plugins.hosts().filter { it.text("kind") == "APP" }
        return apps.firstOrNull { ExternalPlugins.sameUrl(it.text("baseUrl"), app.baseUrl) }
            ?: apps.firstOrNull { it.text("name") == app.name }
    }

    companion object {
        /**
         * The contracts of [desired] (contract → target configuration id,
         * null when that configuration is missing) that [current], GZAC's
         * contract bindings, does not bind to that target.
         */
        fun missingBindings(desired: Map<String, String?>, current: JsonNode): List<String> {
            val bound = current.items().associate { it.text("contract") to it.text("targetConfigurationId") }
            return desired.filter { (contract, target) -> target == null || bound[contract] != target }.keys.map { "contract binding $it" }
        }
    }
}
