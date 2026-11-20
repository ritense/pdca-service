package com.ritense.pdca.devsetup

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * The verzoek route (ARCHITECTURE §6.2) in GZAC: an Objecttypen API
 * configuration that names the objecttype the way the stack's containers do,
 * the object management of InstrumentVerzoek, the Verzoek plugin
 * configuration and the callback URL Open Notificaties can reach. The zaak
 * type link goes in with the case definition ([CaseDefinitions]).
 */
class VerzoekRoute(private val api: GzacApi, private val values: DevValues) {
    private val classic = ClassicPlugins(api)
    private val verzoek = values.verzoek

    fun apply() {
        val objecttypenApiChanged = classic.createOrUpdate(
            verzoek.objecttypenApi.id, "objecttypenapi", verzoek.objecttypenApi.title, objecttypenApiProperties(), "Objecttypen API configuration"
        )
        val (objectManagementId, objectManagementChanged) = objectManagement(verzoek.objecttypenApi.id)
        val notificaties = classic.existing("notificatiesapi", "Notificaties API")
        val verzoekChanged = verzoekPlugin(objectManagementId, notificaties.text("id")!!)
        val callbackChanged = notificatiesCallback(notificaties)
        // GZAC registers its abonnement again whenever a Notificaties API or Verzoek configuration
        // is saved; a changed objecttype address alone would wait for GZAC's next start.
        if ((objecttypenApiChanged || objectManagementChanged) && !verzoekChanged && !callbackChanged) {
            classic.update(verzoek.plugin.id, verzoek.plugin.title, verzoekPluginProperties(objectManagementId, notificaties.text("id")!!), "the Verzoek plugin configuration")
            Console.info("Verzoek plugin configuration saved again, so GZAC renews its abonnement with the new objecttype address")
        }
    }

    /** What the verzoek route lacks in GZAC, without changing anything. */
    fun missing(): List<String> = buildList {
        if (classic.list("verzoek").none { it.text("id") == verzoek.plugin.id }) add("Verzoek plugin configuration")
        val callbackUrl = classic.existing("notificatiesapi", "Notificaties API").path("properties").text("callbackUrl")
        if (callbackUrl != verzoek.notificatiesApiCallbackUrl) add("Notificaties API callback URL (is $callbackUrl)")
    }

    /**
     * The Objecten API renders objecttype URLs with the address the stack's
     * containers use (host.docker.internal), and Open Notificaties matches the
     * Verzoek plugin's objecttype filter literally, so the object management
     * gets its own Objecttypen API configuration at that address. It reuses
     * the token configuration of the dev app's Objecttypen API.
     */
    private fun objecttypenApiProperties(): ObjectNode {
        val devConfiguration = classic.existing("objecttypenapi", "Objecttypen API", excludedId = verzoek.objecttypenApi.id)
        return Json.mapper.createObjectNode()
            .put("url", verzoek.objecttypenApi.url)
            .put("authenticationPluginConfiguration", devConfiguration.path("properties").text("authenticationPluginConfiguration"))
    }

    /** Returns the object management's id and whether it changed. */
    private fun objectManagement(objecttypenApiId: String): Pair<String, Boolean> {
        val values = verzoek.objectManagement
        val objectenApiId = classic.existing("objectenapi", "Objecten API").text("id")!!
        val all = api.get(OBJECT_MANAGEMENT_LIST).orThrow("Listing the object management configurations").json().items()
        val current = all.firstOrNull { it.text("id") == values.id } ?: all.firstOrNull { it.text("title") == values.title }
        val id = current?.text("id") ?: values.id
        val desired = mapOf(
            "id" to id,
            "title" to values.title,
            "objecttypenApiPluginConfigurationId" to objecttypenApiId,
            "objecttypeId" to values.objecttypeId,
            "objecttypeVersion" to values.objecttypeVersion,
            "objectenApiPluginConfigurationId" to objectenApiId,
            "showInDataMenu" to values.showInDataMenu,
        )
        val changed = when {
            current == null -> {
                api.post(OBJECT_MANAGEMENT, desired).orThrow("Creating object management ${values.title}")
                Console.info("object management ${values.title}: created ($id)")
                true
            }
            desired.all { (key, value) -> current.get(key)?.asText() == value.toString() } -> {
                Console.info("object management ${values.title}: up to date ($id)")
                false
            }
            else -> {
                val update = (current.deepCopy<JsonNode>() as ObjectNode).setAll<ObjectNode>(Json.mapper.valueToTree<ObjectNode>(desired))
                api.put(OBJECT_MANAGEMENT, update).orThrow("Updating object management ${values.title}")
                Console.info("object management ${values.title}: updated ($id)")
                true
            }
        }
        return id to changed
    }

    private fun verzoekPlugin(objectManagementId: String, notificatiesId: String): Boolean = classic.createOrUpdate(
        verzoek.plugin.id, "verzoek", verzoek.plugin.title, verzoekPluginProperties(objectManagementId, notificatiesId), "Verzoek plugin configuration"
    )

    private fun verzoekPluginProperties(objectManagementId: String, notificatiesId: String): ObjectNode {
        val plugin = verzoek.plugin
        val catalogiUrl = classic.existing("catalogiapi", "Catalogi API").path("properties").text("url")!!
        val properties = Json.mapper.createObjectNode()
            .put("notificatiesApiPluginConfiguration", notificatiesId)
            .put("processToStart", plugin.processToStart)
            .put("rsin", plugin.rsin)
        val types = properties.putArray("verzoekProperties")
        plugin.verzoekProperties.forEach { type ->
            types.addObject()
                .put("type", type.type)
                .put("caseDefinitionKey", type.caseDefinitionKey)
                .put("processDefinitionKey", type.processDefinitionKey)
                .put("objectManagementId", objectManagementId)
                .put("initiatorRoltypeUrl", "${catalogiUrl.trimEnd('/')}/roltypen/${type.initiatorRoltypeUuid}")
                .put("initiatorRolDescription", type.initiatorRolDescription)
                .put("copyStrategy", type.copyStrategy)
        }
        return properties
    }

    /**
     * Open Notificaties checks a callback URL by calling it from its
     * container, so the dev app's Notificaties API configuration gets the
     * address the containers reach GZAC on. Saving it makes GZAC register its
     * abonnement again, now including the Verzoek plugin's kanaal.
     */
    private fun notificatiesCallback(notificaties: JsonNode): Boolean {
        val id = notificaties.text("id")!!
        val properties = notificaties.path("properties") as ObjectNode
        if (properties.text("callbackUrl") == verzoek.notificatiesApiCallbackUrl) {
            Console.info("Notificaties API callback URL: up to date (${verzoek.notificatiesApiCallbackUrl})")
            return false
        }
        classic.update(id, notificaties.text("title")!!, properties.deepCopy().put("callbackUrl", verzoek.notificatiesApiCallbackUrl), "the Notificaties API configuration")
        Console.info("Notificaties API callback URL: set to ${verzoek.notificatiesApiCallbackUrl} (was ${properties.text("callbackUrl")})")
        return true
    }

    private companion object {
        const val OBJECT_MANAGEMENT = "/api/v1/object/management/configuration"
        const val OBJECT_MANAGEMENT_LIST = "/api/management/v1/object/management/configuration"
    }
}
