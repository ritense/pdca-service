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
        // Deliberately keep the same version on manifest changes: this app
        // ships no contentHash, so GZAC refreshes the manifest of an
        // existing version in place at the next discovery poll. The
        // administrator accepts new endpoints by updating the endpoint
        // grants on the EXISTING configuration (edit screen). A version bump
        // would instead create a parallel definition that needs a completely
        // new configuration (and tabs/links to be moved over).
        const val PLUGIN_VERSION = "0.2.0"

        /**
         * This app's GZAC API footprint: capability gzac_api plus the
         * endpoint allowlist for the serviceToken, as the single source for
         * the manifest below.
         * Note: granted endpoints also work under /api/management (the token
         * carries ADMIN authority for that; only GZAC's hard denylist stays
         * closed) — this app deliberately sticks to /api/v1 endpoints.
         */
        val CAPABILITIES = listOf("gzac_api")
        val GRANTED_ENDPOINTS: List<Map<String, String>> = listOf(
            // Create a dossier for a plan (plan = dossier 1:1, standalone and intake route).
            mapOf(
                "method" to "POST",
                "pattern" to "/api/v1/process-document/operation/new-document-and-start-process"
            ),
            // Look up the caseDefinitionVersionTag (blueprint of the document definition).
            mapOf(
                "method" to "GET",
                "pattern" to "/api/v1/document-definition/*"
            ),
            // List case definitions for the case-type dropdowns in PDCA
            // Beheer (the list path falls outside the wildcard pattern above).
            mapOf(
                "method" to "GET",
                "pattern" to "/api/v1/document-definition"
            ),
            // Management variant of that list: the /api/v1 list is
            // PBAC-filtered per caller and hides case types without document
            // permission rules (e.g. a freshly imported product dossier
            // type); the beheer dropdowns need the full register.
            mapOf(
                "method" to "GET",
                "pattern" to "/api/management/v1/document-definition"
            ),
            // Read a dossier for the intake prefill and to link a planId
            // passed via the start form under water (direct link in the overlay).
            mapOf(
                "method" to "GET",
                "pattern" to "/api/v1/document/*"
            ),
            // Fallback for the dossier content: /api/v1/document/* omits it
            // unless GZAC runs with valtimo.includeDocumentContentInResponse;
            // the case inspection endpoint always includes it.
            mapOf(
                "method" to "GET",
                "pattern" to "/api/management/v1/case/*"
            ),
            // Building blocks: list process definitions (bouwblok dropdown,
            // recognised by versionTag "BB:<key>:<version>") and look up the
            // latest version per key to find the start-form link.
            mapOf(
                "method" to "GET",
                "pattern" to "/api/v1/process/definition"
            ),
            mapOf(
                "method" to "GET",
                "pattern" to "/api/v1/process/definition/*"
            ),
            // Start a building block process on the plan dossier via the
            // building block's start-form link. The start-by-key endpoint
            // cannot start blueprint-owned (BB:-tagged) processes; the form
            // submission starts by process definition id and delivers the
            // start payload to the dossier document (/bouwblokStart), from
            // where the building-block link's input mappings copy it into
            // the building block document.
            mapOf(
                "method" to "POST",
                "pattern" to "/api/v1/process-link/*/form/submission"
            ),
            mapOf(
                "method" to "GET",
                "pattern" to "/api/v1/process-link"
            ),
            // Maintain the pluginConfigurationMappings on the case type's
            // building-block link (startable-item management API): the
            // configuration chosen in PDCA Beheer is mapped there, so the
            // building block's process links keep their portable
            // BUILDING_BLOCK reference.
            mapOf(
                "method" to "GET",
                "pattern" to "/api/management/v1/case-definition/*/version/*/startable-item/*/version/*/properties"
            ),
            mapOf(
                "method" to "PUT",
                "pattern" to "/api/management/v1/case-definition/*/version/*/startable-item/*/version/*"
            ),
            // DOSSIER products: write the aanvraagdossier's own URN into its
            // document right after creation (the process passes it to
            // aanmaak-instrument as doc:/dossierUrn).
            mapOf(
                "method" to "PUT",
                "pattern" to "/api/v1/document"
            ),
            // DOSSIER products: repair the product case's dangling pdca
            // process links to the configuration chosen in PDCA Beheer
            // (GZAC's plugin-configuration repair; zips ship no
            // environment-specific configuration UUIDs).
            mapOf(
                "method" to "PUT",
                "pattern" to "/api/management/v1/case-definition/*/version/*/plugin-configuration-mappings"
            )
        )
    }

    private val manifest: Map<String, Any> = mapOf(
        "pluginId" to PLUGIN_ID,
        "version" to PLUGIN_VERSION,
        "provider" to "Ritense",
        "translations" to mapOf(
            "en" to mapOf(
                "name" to "PDCA Plan Manager",
                "description" to "Plan management with the PDCA cycle, backed by the Open Plan and Open Product registers.",
                "pdca-admin.title" to "PDCA Management",
                "action.actieId.label" to "Action id",
                "action.actieId.help" to "UUID of the PDCA action (from the building block document, typically doc:/actieId).",
                "action.gebeurtenis.label" to "Event",
                "action.gebeurtenis.help" to "GESTART, TER_BEOORDELING, AFGEROND or AFGEWEZEN.",
                "action.resultaat.label" to "Result",
                "action.resultaat.help" to "Result text stored on the action (e.g. doc:/resultaat).",
                "action.toelichting.label" to "Explanation",
                "action.toelichting.help" to "Optional explanation, stored as result on AFGEWEZEN."
            ),
            "nl" to mapOf(
                "name" to "PDCA Planbeheer",
                "description" to "Planbeheer met de PDCA-cyclus, op basis van de registers Open Plan en Open Product.",
                "pdca-admin.title" to "PDCA Beheer",
                "action.actieId.label" to "Actie-id",
                "action.actieId.help" to "UUID van de PDCA-actie (uit het bouwblokdocument, doorgaans doc:/actieId).",
                "action.gebeurtenis.label" to "Gebeurtenis",
                "action.gebeurtenis.help" to "GESTART, TER_BEOORDELING, AFGEROND of AFGEWEZEN.",
                "action.resultaat.label" to "Resultaat",
                "action.resultaat.help" to "Resultaattekst die op de actie komt (bv. doc:/resultaat).",
                "action.toelichting.label" to "Toelichting",
                "action.toelichting.help" to "Optionele toelichting; bij AFGEWEZEN als resultaat vastgelegd."
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
        // The app calls GZAC's API with the pushed serviceToken (capability
        // gzac_api). For service tokens only this endpoint allowlist applies
        // (PBAC is skipped); the administrator confirms this footprint when
        // creating or re-accepting the configuration.
        "permissions" to mapOf(
            "capabilities" to CAPABILITIES,
            "endpoints" to GRANTED_ENDPOINTS
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
        // BPMN-bindable action handlers, executed by PluginActionController.
        // Building block processes use these to report action progress and —
        // for product building blocks — to manage the instrument in Open
        // Plan themselves (see the building blocks in gzac/bouwblokken).
        "actions" to listOf(
            mapOf(
                "key" to "update-actie",
                "title" to "Actie bijwerken",
                "description" to "Werkt een PDCA-actie bij vanuit een bouwblokproces: " +
                    "GESTART, TER_BEOORDELING, AFGEROND (met resultaat) of AFGEWEZEN.",
                "activityTypes" to listOf("SERVICE_TASK_START"),
                "properties" to listOf(
                    mapOf("key" to "actieId", "type" to "string", "required" to true),
                    mapOf("key" to "gebeurtenis", "type" to "string", "required" to true),
                    mapOf("key" to "resultaat", "type" to "string", "required" to false),
                    mapOf("key" to "toelichting", "type" to "string", "required" to false)
                )
            ),
            mapOf(
                "key" to "aanmaak-instrument",
                "title" to "Instrument/voorziening aanmaken",
                "description" to "Maakt een instrument onder een doel aan in Open Plan " +
                    "(bijv. na toekenning van een productaanvraag) en geeft de instrumentUuid " +
                    "terug als resultaat (via een result mapping in het bouwblokdocument te zetten).",
                "activityTypes" to listOf("SERVICE_TASK_START"),
                "properties" to listOf(
                    mapOf("key" to "doelUuid", "type" to "string", "required" to true),
                    mapOf("key" to "titel", "type" to "string", "required" to true),
                    mapOf("key" to "product", "type" to "string", "required" to false),
                    mapOf("key" to "zaak", "type" to "string", "required" to false),
                    mapOf("key" to "status", "type" to "string", "required" to false)
                ),
                "outputs" to listOf("instrumentUuid")
            ),
            mapOf(
                "key" to "update-instrument",
                "title" to "Instrument/voorziening bijwerken",
                "description" to "Zet de status van een instrument in Open Plan op afgerond " +
                    "(met resultaat behaald/gefaald) of geannuleerd; een toelichting komt in " +
                    "de PDCA-overlay.",
                "activityTypes" to listOf("SERVICE_TASK_START"),
                "properties" to listOf(
                    mapOf("key" to "instrumentUuid", "type" to "string", "required" to true),
                    mapOf("key" to "status", "type" to "string", "required" to true),
                    mapOf("key" to "resultaat", "type" to "string", "required" to false),
                    mapOf("key" to "toelichting", "type" to "string", "required" to false),
                    mapOf("key" to "planUuid", "type" to "string", "required" to false)
                )
            )
        )
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
            title = body["title"] as? String,
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
