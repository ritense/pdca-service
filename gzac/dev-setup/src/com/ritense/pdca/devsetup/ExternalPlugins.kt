package com.ritense.pdca.devsetup

import com.fasterxml.jackson.databind.JsonNode

/** GZAC's external plugin management API (Admin → Plugins). */
class ExternalPlugins(private val api: GzacApi) {

    fun hosts(): List<JsonNode> = api.get("$PATH/host").orThrow("Listing the external plugin hosts").json().items()

    fun definitions(): List<JsonNode> = api.get("$PATH/definition").orThrow("Listing the external plugin definitions").json().items()

    fun configurations(definitionId: String): List<JsonNode> =
        api.get("$PATH/configuration?definitionId=$definitionId").orThrow("Listing the external plugin configurations").json().items()

    fun configuration(id: String): JsonNode = api.get("$PATH/configuration/$id").orThrow("Reading configuration $id").json()

    /** The definition of [pluginId]@[version] on [hostId], if GZAC knows it. */
    fun definition(hostId: String, pluginId: String, version: String): JsonNode? = definitions().firstOrNull {
        it.text("hostId") == hostId && it.text("pluginId") == pluginId && it.text("version") == version
    }

    /** Asks GZAC to poll [host] now; the event-queue update with the current values changes nothing else. */
    fun discover(host: JsonNode) {
        api.patch(
            "$PATH/host/${host.text("id")}/event-queue",
            mapOf("eventQueueMode" to host.text("eventQueueMode"), "eventQueueTtlMs" to host.get("eventQueueTtlMs")?.takeUnless { it.isNull }?.asLong()),
        ).orThrow("Starting a discovery of host '${host.text("name")}'")
    }

    /**
     * Waits until GZAC has discovered [pluginId]@[version] on [host] with its
     * manifest, accepting a changed manifest of that version the way an
     * administrator does in the admin. Returns the definition.
     */
    fun awaitDefinition(host: JsonNode, pluginId: String, version: String, timeoutSeconds: Long): JsonNode {
        val hostId = host.text("id")!!
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000
        var nextDiscovery = 0L
        while (true) {
            val definition = definition(hostId, pluginId, version)
            if (definition != null && definition.path("requiresReacceptance").asBoolean()) {
                api.post("$PATH/definition/${definition.text("id")}/accept-content", mapOf("contentHash" to definition.text("pendingContentHash")))
                    .orThrow("Accepting the changed manifest of $pluginId $version")
                Console.info("$pluginId $version: accepted its changed manifest")
                continue
            }
            if (definition != null && isReady(definition)) return definition
            if (System.currentTimeMillis() > deadline) {
                val state = definition?.let { "status ${it.text("status")}, awaiting discovery ${it.path("awaitingDiscovery").asBoolean()}" } ?: "unknown to GZAC"
                throw SetupException(
                    "GZAC has not discovered $pluginId $version on host '${host.text("name")}' within ${timeoutSeconds}s ($state). " +
                        "Check the host in Admin → Plugins and GZAC's log, then re-run ./gradlew gzacDevSetup"
                )
            }
            if (System.currentTimeMillis() >= nextDiscovery) {
                runCatching { discover(host) }
                nextDiscovery = System.currentTimeMillis() + 10_000
            }
            Thread.sleep(2_000)
        }
    }

    private fun isReady(definition: JsonNode) = definition.text("status") == "AVAILABLE" &&
        !definition.path("awaitingDiscovery").asBoolean() &&
        definition.get("configurationSchema")?.isObject == true

    companion object {
        const val PATH = "/api/management/v1/external-plugin"

        /** Base URLs compare without a trailing slash. */
        fun sameUrl(a: String?, b: String?) = a != null && b != null && a.trimEnd('/') == b.trimEnd('/')
    }
}
