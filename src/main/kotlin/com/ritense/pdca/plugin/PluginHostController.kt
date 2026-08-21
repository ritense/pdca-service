package com.ritense.pdca.plugin

import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * Implements the Valtimo "URL app" contract: this app is a remote service,
 * added to GZAC by URL, that behaves like a plugin-host-plus-single-plugin.
 * GZAC discovers it via `GET /api/host/plugins`, which must return
 * `[{ pluginId, version, manifest }]` — the same shape a plugin host serves
 * for uploaded plugins (see plugin-host/sample-apps/demo-app in the Valtimo
 * repo for the reference implementation).
 *
 * Frontend bundle paths in the manifest resolve against
 * `/plugins/{pluginId}/{version}` (served by [BundleController]).
 */
@RestController
class PluginHostController(
    private val configurationStore: ConfigurationStore
) {

    private val logger = LoggerFactory.getLogger(PluginHostController::class.java)

    companion object {
        const val PLUGIN_ID = "pdca"
        const val PLUGIN_VERSION = "0.1.0"
    }

    private val manifest: Map<String, Any> = mapOf(
        "pluginId" to PLUGIN_ID,
        "version" to PLUGIN_VERSION,
        "provider" to "Ritense",
        "translations" to mapOf(
            "en" to mapOf(
                "name" to "PDCA Plan Manager",
                "description" to "Plan management with the PDCA cycle, backed by the Open Plan and Open Product registers.",
                "pdca-admin.title" to "PDCA Management"
            ),
            "nl" to mapOf(
                "name" to "PDCA Planbeheer",
                "description" to "Planbeheer met de PDCA-cyclus, op basis van de registers Open Plan en Open Product.",
                "pdca-admin.title" to "PDCA Beheer"
            )
        ),
        "configurationSchema" to mapOf(
            "\$schema" to "https://json-schema.org/draft/2020-12/schema",
            "type" to "object",
            "properties" to mapOf(
                "title" to mapOf("type" to "string", "title" to "Configuration name")
            ),
            "additionalProperties" to false
        ),
        // De app roept GZAC's API aan met de gepushte serviceToken (capability
        // gzac_api). Voor service-tokens geldt uitsluitend deze endpoint-
        // allowlist (PBAC wordt overgeslagen); de beheerder bevestigt deze
        // footprint bij het aanmaken of heraccepteren van de configuratie.
        "permissions" to mapOf(
            "capabilities" to listOf("gzac_api"),
            "endpoints" to listOf(
                // Dossier aanmaken voor een plan (plan = dossier 1:1, losse route).
                mapOf(
                    "method" to "POST",
                    "pattern" to "/api/v1/process-document/operation/new-document-and-start-process"
                ),
                // caseDefinitionVersionTag opzoeken (blueprint van de documentdefinitie);
                // /api/management/** is voor service-tokens niet bereikbaar.
                mapOf(
                    "method" to "GET",
                    "pattern" to "/api/v1/document-definition/*"
                ),
                // Dossier lezen om een via het startformulier meegegeven planId
                // onderwater aan het plan te koppelen (plan.zaak zetten).
                mapOf(
                    "method" to "GET",
                    "pattern" to "/api/v1/document/*"
                )
            )
        ),
        "frontendBundles" to listOf(
            mapOf(
                "type" to "case-tab",
                "key" to "plan-overview",
                "title" to "Planoverzicht",
                "path" to "/bundles/plan-overview.html"
            ),
            mapOf(
                "type" to "case-tab",
                "key" to "plan-goals",
                "title" to "Doelen & Acties",
                "path" to "/bundles/plan-goals.html"
            ),
            mapOf(
                "type" to "case-tab",
                "key" to "plan-evaluations",
                "title" to "Evaluaties",
                "path" to "/bundles/plan-evaluations.html"
            ),
            mapOf(
                "type" to "page",
                "key" to "pdca-admin",
                "title" to "pdca-admin.title",
                "path" to "/bundles/pdca-admin.html"
            ),
            mapOf(
                "type" to "task-form",
                "key" to "create-plan",
                "path" to "/bundles/create-plan.html"
            ),
            mapOf(
                "type" to "task-form",
                "key" to "update-goals",
                "path" to "/bundles/update-goals.html"
            ),
            mapOf(
                "type" to "task-form",
                "key" to "evaluate",
                "path" to "/bundles/evaluate.html"
            )
        ),
        // No backend actions: the app has no BPMN-bindable action handlers.
        "actions" to emptyList<Any>()
    )

    @GetMapping("/health")
    fun health(): Map<String, String> {
        return mapOf("status" to "UP")
    }

    /** Discovery: GZAC polls this and expects the plugin list with nested manifests. */
    @GetMapping("/api/host/plugins")
    fun getPlugins(): List<Map<String, Any>> = listOf(
        mapOf(
            "pluginId" to PLUGIN_ID,
            "version" to PLUGIN_VERSION,
            "manifest" to manifest
        )
    )

    /** Public manifest endpoint the GZAC frontend fetches to render surfaces. */
    @GetMapping("/plugins/{pluginId}/{version}/plugin-manifest")
    fun getPluginManifest(
        @PathVariable(name = "pluginId") pluginId: String,
        @PathVariable(name = "version") version: String
    ): Map<String, Any> = manifest

    /** Pushed configurations (without the service token — this endpoint is unauthenticated here). */
    @GetMapping("/api/host/configurations")
    fun listConfigurations(): List<Map<String, Any>> = configurationStore.getAll().values.map {
        mapOf(
            "configurationId" to it.configId,
            "pluginId" to PLUGIN_ID,
            "pluginVersion" to PLUGIN_VERSION,
            "properties" to it.properties,
            "eventSubscriptions" to it.eventSubscriptions
        )
    }

    // TODO: Add HMAC signature verification for production use.
    //  GZAC signs every request with the shared secret entered at registration;
    //  the signature should be validated here.
    @PostMapping("/api/host/configurations/{configId}")
    fun pushConfiguration(
        @PathVariable configId: String,
        @RequestBody body: Map<String, Any>
    ): ResponseEntity<Void> {
        logger.info("Received configuration push for configId={}: {}", configId, body)

        val serviceToken = body["serviceToken"] as? String ?: ""
        val gzacBaseUrl = body["gzacBaseUrl"] as? String ?: ""
        val properties = body["properties"] as? Map<String, Any> ?: emptyMap()

        @Suppress("UNCHECKED_CAST")
        val eventSubscriptions = body["eventSubscriptions"] as? List<String> ?: emptyList()

        val configuration = PluginConfiguration(
            configId = configId,
            properties = properties,
            serviceToken = serviceToken,
            gzacBaseUrl = gzacBaseUrl,
            eventSubscriptions = eventSubscriptions
        )

        configurationStore.store(configId, configuration)

        return ResponseEntity.ok().build()
    }

    @DeleteMapping("/api/host/configurations/{configId}")
    fun removeConfiguration(@PathVariable configId: String): ResponseEntity<Void> {
        configurationStore.remove(configId)
        return ResponseEntity.noContent().build()
    }
}
