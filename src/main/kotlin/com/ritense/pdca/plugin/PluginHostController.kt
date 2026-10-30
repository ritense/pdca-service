package com.ritense.pdca.plugin

import com.ritense.pdca.registers.Register
import com.ritense.pdca.service.InstrumentAanvraagService
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
    private val configurationStore: ConfigurationStore,
    private val instrumentAanvraagService: InstrumentAanvraagService
) {

    private val logger = LoggerFactory.getLogger(PluginHostController::class.java)

    companion object {
        const val PLUGIN_ID = "pdca"
        // Deliberately keep the same version on manifest changes. This app
        // ships no contentHash, so GZAC pins a hash derived from the
        // manifest: an in-place change freezes the definition (no pushes,
        // no calls) until the administrator reviews and accepts the new
        // footprint, after which the EXISTING configuration, its case tabs
        // and process links keep working. On acceptance GZAC binds a newly
        // declared contract itself when exactly one configuration provides
        // it; otherwise the administrator binds it in the configuration's
        // edit screen. A version bump would instead create a parallel
        // definition that needs a completely new configuration (and
        // tabs/links to be moved over).
        const val PLUGIN_VERSION = "0.2.0"

        /** The registers are reached through these contracts (see `RegisterContractRequestFactory`). */
        val CONTRACTS = Register.entries.map { it.contract }

        /**
         * This app's GZAC API footprint: capability gzac_api plus the
         * endpoint allowlist for the serviceToken, as the single source for
         * the manifest below. Capability frontend_data declares the `/data`
         * route ([PluginDataController]); the user tokens GZAC forwards there
         * are bounded by the same allowlist intersected with the user's PBAC.
         * Note: granted endpoints also work under /api/management (the token
         * carries ADMIN authority for that; only GZAC's hard denylist stays
         * closed) — this app deliberately sticks to /api/v1 endpoints.
         */
        val CAPABILITIES = listOf("gzac_api", "frontend_data")
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
            // permission rules (e.g. a freshly imported case type); the
            // beheer dropdowns need the full register.
            mapOf(
                "method" to "GET",
                "pattern" to "/api/management/v1/document-definition"
            ),
            // Read a dossier for the intake prefill, to link a planId passed
            // via the start form under water (direct link in the overlay),
            // and to learn the dossier type that calls aanmaak-instrument.
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
            // Instrument dossier (§9): look up the latest version of its
            // process and the pdca process links on it, and repair those
            // dangling links to the pushed configuration (GZAC's
            // plugin-configuration repair; zips ship no environment-specific
            // configuration UUIDs).
            mapOf(
                "method" to "GET",
                "pattern" to "/api/v1/process/definition/*"
            ),
            mapOf(
                "method" to "GET",
                "pattern" to "/api/v1/process-link"
            ),
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
                "action.doelUuid.label" to "Doel",
                "action.doelUuid.help" to "UUID of the doel in Open Plan the instrument belongs to (typically doc:/doelUuid).",
                "action.titel.label" to "Title",
                "action.titel.help" to "Title of the instrument in the plan (typically doc:/productTitel).",
                "action.product.label" to "Product",
                "action.product.help" to "URN of the producttype in Open Product (typically doc:/productUrn).",
                "action.status.label" to "Status",
                "action.status.help" to "On creation: actief (default) or gepland; on update: afgerond or geannuleerd.",
                "action.planUuid.label" to "Plan",
                "action.planUuid.help" to "UUID of the plan in Open Plan (typically doc:/planUuid); needed for the gepland start status and for an explanation.",
                "action.instrumentUuid.label" to "Instrument",
                "action.instrumentUuid.help" to "UUID of the instrument in Open Plan, as returned by aanmaak-instrument (typically doc:/instrumentUuid).",
                "action.resultaat.label" to "Result",
                "action.resultaat.help" to "behaald or gefaald when the instrument is completed (e.g. doc:/productResultaat).",
                "action.toelichting.label" to "Explanation",
                "action.toelichting.help" to "Optional explanation, stored in the PDCA overlay of the instrument."
            ),
            "nl" to mapOf(
                "name" to "PDCA Planbeheer",
                "description" to "Planbeheer met de PDCA-cyclus, op basis van de registers Open Plan en Open Product.",
                "pdca-admin.title" to "PDCA Beheer",
                "action.doelUuid.label" to "Doel",
                "action.doelUuid.help" to "UUID van het doel in Open Plan waar het instrument onder komt (doorgaans doc:/doelUuid).",
                "action.titel.label" to "Titel",
                "action.titel.help" to "Titel van het instrument in het plan (doorgaans doc:/productTitel).",
                "action.product.label" to "Product",
                "action.product.help" to "URN van het producttype in Open Product (doorgaans doc:/productUrn).",
                "action.status.label" to "Status",
                "action.status.help" to "Bij aanmaken: actief (standaard) of gepland; bij bijwerken: afgerond of geannuleerd.",
                "action.planUuid.label" to "Plan",
                "action.planUuid.help" to "UUID van het plan in Open Plan (doorgaans doc:/planUuid); nodig voor de startstatus gepland en voor een toelichting.",
                "action.instrumentUuid.label" to "Instrument",
                "action.instrumentUuid.help" to "UUID van het instrument in Open Plan, zoals aanmaak-instrument die teruggaf (doorgaans doc:/instrumentUuid).",
                "action.resultaat.label" to "Resultaat",
                "action.resultaat.help" to "behaald of gefaald bij het afronden van het instrument (bv. doc:/productResultaat).",
                "action.toelichting.label" to "Toelichting",
                "action.toelichting.help" to "Optionele toelichting; komt in de PDCA-overlay van het instrument."
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
        // creating or re-accepting the configuration. `contracts` is what the
        // app calls through GZAC's contract broker; the administrator binds
        // each one to a providing configuration (the register plugins).
        "permissions" to mapOf(
            "capabilities" to CAPABILITIES,
            "endpoints" to GRANTED_ENDPOINTS,
            "contracts" to CONTRACTS
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
            ),
            mapOf(
                "type" to "side-panel",
                "key" to "evaluation",
                "path" to "/bundles/evaluation-panel.html"
            )
        ),
        // BPMN-bindable action handlers, executed by PluginActionController.
        // The instrument dossier (gzac/case-definitions/instrument-verzoek,
        // §9) uses these to manage the instrument in Open Plan itself.
        "actions" to listOf(
            mapOf(
                "key" to "aanmaak-instrument",
                "title" to "Instrument aanmaken",
                "description" to "Maakt een instrument onder een doel aan in Open Plan, met het " +
                    "aanroepende dossier als zaakverwijzing, en geeft de instrumentUuid terug als " +
                    "resultaat (via een result mapping in het dossierdocument te zetten).",
                "activityTypes" to listOf("SERVICE_TASK_START"),
                "properties" to listOf(
                    mapOf("key" to "doelUuid", "type" to "string", "required" to true),
                    mapOf("key" to "titel", "type" to "string", "required" to true),
                    mapOf("key" to "product", "type" to "string", "required" to false),
                    mapOf("key" to "status", "type" to "string", "required" to false),
                    mapOf("key" to "planUuid", "type" to "string", "required" to false)
                ),
                "outputs" to listOf("instrumentUuid")
            ),
            mapOf(
                "key" to "update-instrument",
                "title" to "Instrument bijwerken",
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
            "eventSubscriptions" to it.eventSubscriptions,
            "grantedContracts" to it.grantedContracts
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
        val grantedContracts = (body["grantedContracts"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()

        val configuration = PluginConfiguration(
            configId = configId,
            title = body["title"] as? String,
            properties = properties,
            serviceToken = serviceToken,
            gzacBaseUrl = gzacBaseUrl,
            eventSubscriptions = eventSubscriptions,
            grantedContracts = grantedContracts
        )

        configurationStore.store(configId, configuration)

        // The instrument dossier's pdca process links ship dangling; point
        // them at the configuration that just arrived. Best effort: without
        // the dossier type (not imported yet) or without the endpoint grant
        // the next product request repeats the repair.
        runCatching { instrumentAanvraagService.ensureProductCaseConfiguration() }
            .onFailure { logger.info("Instrument dossier not configured on push of {}: {}", configId, it.message) }

        return ResponseEntity.ok().build()
    }

    @DeleteMapping("/api/host/configurations/{configId}")
    fun removeConfiguration(@PathVariable configId: String): ResponseEntity<Void> {
        configurationStore.remove(configId)
        return ResponseEntity.noContent().build()
    }
}
