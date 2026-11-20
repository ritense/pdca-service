package com.ritense.pdca.devsetup

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * GZAC's own (non-external) plugin configurations: `/api/v1/plugin/configuration`.
 * The dev app brings the ZGW ones (Notificaties, Objecten, Objecttypen, Zaken,
 * Catalogi API); the setup looks them up instead of assuming their ids.
 */
class ClassicPlugins(private val api: GzacApi) {

    fun list(definitionKey: String): List<JsonNode> =
        api.get("$PATH?pluginDefinitionKey=$definitionKey")
            .orThrow("Listing the $definitionKey plugin configurations").json().items()

    /** The dev app's configuration of [definitionKey] other than [excludedId]; with several, the first by title. */
    fun existing(definitionKey: String, what: String, excludedId: String? = null): JsonNode {
        val configurations = list(definitionKey).filter { it.text("id") != excludedId }
        val chosen = configurations.firstOrNull()
            ?: throw SetupException("GZAC has no $what plugin configuration ($definitionKey); the Valtimo dev app creates one at start")
        if (configurations.size > 1) {
            Console.info("several $what configurations; using '${chosen.text("title")}' (${chosen.text("id")})")
        }
        return chosen
    }

    /**
     * Creates or updates the configuration [id] of [definitionKey]; returns
     * whether anything changed. Updates send every property, since GZAC
     * replaces the non-secret ones as a whole.
     */
    fun createOrUpdate(id: String, definitionKey: String, title: String, properties: ObjectNode, what: String): Boolean {
        val current = list(definitionKey).firstOrNull { it.text("id") == id }
        return when {
            current == null -> {
                api.post(PATH, mapOf("id" to id, "title" to title, "definitionKey" to definitionKey, "properties" to properties))
                    .orThrow("Creating $what")
                Console.info("$what: created ($id)")
                true
            }
            current.text("title") == title && current.path("properties") == properties -> {
                Console.info("$what: up to date ($id)")
                false
            }
            else -> {
                update(id, title, properties, what)
                Console.info("$what: updated ($id)")
                true
            }
        }
    }

    fun update(id: String, title: String, properties: JsonNode, what: String) {
        api.put("$PATH/$id", mapOf("title" to title, "properties" to properties)).orThrow("Updating $what")
    }

    private companion object {
        const val PATH = "/api/v1/plugin/configuration"
    }
}
