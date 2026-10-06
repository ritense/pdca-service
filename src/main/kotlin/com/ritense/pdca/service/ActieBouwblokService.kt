/*
 * Copyright 2015-2024 Ritense BV, the Netherlands.
 *
 * Licensed under EUPL, Version 1.2 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ritense.pdca.service

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.ritense.pdca.domain.ActieBouwblokKoppeling
import com.ritense.pdca.domain.BouwblokSoort
import com.ritense.pdca.domain.Action
import com.ritense.pdca.domain.ActionStatus
import com.ritense.pdca.domain.Uitvoering
import com.ritense.pdca.domain.Uitvoeringsvorm
import com.ritense.pdca.plugin.ConfigurationStore
import com.ritense.pdca.plugin.PluginHostController
import com.ritense.pdca.repository.ActieBouwblokKoppelingRepository
import com.ritense.pdca.repository.ActionRepository
import com.ritense.pdca.repository.PlanDetailsRepository
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.client.RestClient
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/** Error in a plugin action invocation; the controller turns this into a 422 response for GZAC. */
class PluginActionException(val errorCode: String, override val message: String) : RuntimeException(message)

/**
 * Bouwblok/dossier koppelingen (PDCA Beheer) of GZAC processes per plan case
 * type + doeltype(s), starting them from the plan page, and processing the
 * update-actie callbacks. Two kinds:
 *
 *  - ACTIE: the app creates the action first and the building block reports
 *    progress back through the plugin action update-actie;
 *  - PRODUCT: the app only starts the request process ("product X
 *    aanvragen"); all product logic lives in the process itself, which
 *    manages the instrument in Open Plan through the plugin actions
 *    aanmaak-instrument/update-instrument (see [InstrumentActionService]).
 *
 * A PRODUCT koppeling has an uitvoeringsvorm:
 *
 *  - BOUWBLOK — a building block on the plan dossier (lightweight, tasks in
 *    the plan dossier's task list);
 *  - DOSSIER — a full case of its own (`productCaseDefinitionKey`): own
 *    dossier and task list, own PBAC, documents, and optionally a zaak via
 *    GZAC's zaaktype-link ("Automatically create for each case"). The start
 *    payload is the new dossier's document content; on granting, the
 *    process passes the dossier's URN (`doc:/dossierUrn`) to
 *    aanmaak-instrument, which stores it in the instrument's `zaak` field
 *    in Open Plan — the register carries the plan→dossier link, no overlay
 *    data involved.
 *
 * The app and the building block communicate through the **building block
 * document**: the start payload is submitted to the dossier document (under
 * `/bouwblokStart`), the building-block link's input mappings copy it into
 * the building block document, task forms and plugin-action result mappings
 * write into that document, and the plugin-action properties read from it
 * (`doc:` references) — all state is visible to administrators in the
 * dossier's Building blocks panel and the contract is the building block's
 * own document schema.
 *
 * The case-definition zips ship a CaseDefinitionBuildingBlockLink per plan
 * case type with startableByUser=false: the building block runs with the
 * normal runtime behaviour (building block instance, its own document,
 * business-key rewrite) but does not appear in the dossier's start menu. The
 * process links keep their portable BUILDING_BLOCK plugin reference; the
 * concrete PDCA plugin configuration is resolved at runtime through the
 * link's pluginConfigurationMappings, which this service maintains (see
 * [ensurePluginConfigurationMapping]).
 */
@Service
class ActieBouwblokService(
    private val koppelingRepository: ActieBouwblokKoppelingRepository,
    private val actionRepository: ActionRepository,
    private val planDetailsRepository: PlanDetailsRepository,
    private val configurationStore: ConfigurationStore,
    private val gzacClient: GzacClient,
    private val openPlanRestClient: RestClient,
    private val objectMapper: ObjectMapper
) {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        const val PDCA_PLUGIN_ID = "pdca"
        private const val BB_VERSION_TAG_PREFIX = "BB:"

        /**
         * URN of a GZAC dossier, stored in the instrument's `zaak` register
         * field: `urn:pdca:gzac:dossier:<caseDefinitionKey>:<documentId>`.
         * Carries both parts the GZAC UI route needs
         * (`/cases/{caseDefinitionKey}/document/{documentId}`), so the plan
         * page can link to the dossier purely from the register value.
         */
        const val DOSSIER_URN_PREFIX = "urn:pdca:gzac:dossier:"
    }

    data class BouwblokProcess(
        val bouwblokKey: String,
        val bouwblokVersie: String,
        val processDefinitionKey: String,
        val naam: String?
    )

    /** Reference to a created aanvraagdossier (DOSSIER products). */
    data class DossierRef(val caseDefinitionKey: String, val documentId: String, val dossierUrn: String)

    /** Outcome of a start: the created action (ACTIE) and/or the created aanvraagdossier (DOSSIER product). */
    data class StartResult(val actie: Action?, val dossier: DossierRef?)

    // ------------------------------------------------------------- koppelingen

    fun getKoppelingen(caseDefinitionKey: String?): List<ActieBouwblokKoppeling> =
        caseDefinitionKey?.let { koppelingRepository.findByCaseDefinitionKey(it) }
            ?: koppelingRepository.findAll()

    fun create(koppeling: ActieBouwblokKoppeling): ActieBouwblokKoppeling {
        validate(koppeling)
        ensureKoppelingConfiguration(koppeling)
        return koppelingRepository.save(koppeling)
    }

    fun update(id: UUID, changes: ActieBouwblokKoppeling): ActieBouwblokKoppeling {
        val existing = getKoppeling(id)
        val updated = existing.copy(
            soort = changes.soort,
            uitvoeringsvorm = changes.uitvoeringsvorm,
            naam = changes.naam,
            omschrijving = changes.omschrijving,
            buildingBlockKey = changes.buildingBlockKey,
            buildingBlockVersion = changes.buildingBlockVersion,
            processDefinitionKey = changes.processDefinitionKey,
            productCaseDefinitionKey = changes.productCaseDefinitionKey,
            pluginConfigId = changes.pluginConfigId,
            doeltypeUuids = changes.doeltypeUuids,
            updatedAt = LocalDateTime.now()
        )
        validate(updated, ignoreId = id)
        ensureKoppelingConfiguration(updated)
        return koppelingRepository.save(updated)
    }

    /** The plugin configuration lives in GZAC, per uitvoeringsvorm in its own place. */
    private fun ensureKoppelingConfiguration(koppeling: ActieBouwblokKoppeling) = when (koppeling.uitvoeringsvorm) {
        Uitvoeringsvorm.BOUWBLOK -> ensurePluginConfigurationMapping(koppeling)
        Uitvoeringsvorm.DOSSIER -> ensureDossierPluginConfiguration(koppeling)
    }

    fun delete(id: UUID) = koppelingRepository.delete(getKoppeling(id))

    fun getKoppeling(id: UUID): ActieBouwblokKoppeling = koppelingRepository.findById(id).orElseThrow {
        ResponseStatusException(HttpStatus.NOT_FOUND, "Geen actie-bouwblok-koppeling met id $id")
    }

    /**
     * One plugin configuration per process, scoped to where the
     * configuration lives: for BOUWBLOK in the pluginConfigurationMappings
     * of the plan case type's building-block link (per bouwblok per plan
     * case type), for DOSSIER in the product case's FIXED process links
     * (per product case type, across all plan case types).
     */
    private fun validate(koppeling: ActieBouwblokKoppeling, ignoreId: UUID? = null) {
        if (koppeling.naam.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Naam is verplicht")
        }
        if (configurationStore.get(koppeling.pluginConfigId) == null) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Onbekende pluginconfiguratie '${koppeling.pluginConfigId}' — GZAC heeft deze (nog) niet naar de app gepusht"
            )
        }
        when (koppeling.uitvoeringsvorm) {
            Uitvoeringsvorm.BOUWBLOK -> {
                val processKey = koppeling.processDefinitionKey?.takeIf { it.isNotBlank() }
                    ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Proceskey van het bouwblok is verplicht")
                koppelingRepository.findByProcessDefinitionKey(processKey)
                    .firstOrNull {
                        it.id != ignoreId &&
                            it.caseDefinitionKey == koppeling.caseDefinitionKey &&
                            it.pluginConfigId != koppeling.pluginConfigId
                    }
                    ?.let {
                        throw ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Bouwblokproces '$processKey' is binnen dossiertype " +
                                "'${koppeling.caseDefinitionKey}' al gekoppeld met pluginconfiguratie " +
                                "'${it.pluginConfigId}' (koppeling '${it.naam}'); één configuratie per bouwblok per dossiertype"
                        )
                    }
            }

            Uitvoeringsvorm.DOSSIER -> {
                if (koppeling.soort != BouwblokSoort.PRODUCT) {
                    throw ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Uitvoeringsvorm DOSSIER is alleen beschikbaar voor producten"
                    )
                }
                val caseKey = koppeling.productCaseDefinitionKey?.takeIf { it.isNotBlank() }
                    ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Productdossiertype is verplicht")
                koppelingRepository.findAll()
                    .firstOrNull {
                        it.id != ignoreId &&
                            it.productCaseDefinitionKey == caseKey &&
                            it.pluginConfigId != koppeling.pluginConfigId
                    }
                    ?.let {
                        throw ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Productdossiertype '$caseKey' is al gekoppeld met pluginconfiguratie " +
                                "'${it.pluginConfigId}' (koppeling '${it.naam}'); één configuratie per productdossiertype " +
                                "(de proceskoppelingen van het dossiertype verwijzen vast naar één configuratie)"
                        )
                    }
            }
        }
    }

    // ------------------------------------------------------ bouwblok dropdown

    /**
     * The imported building block processes, recognised by version tag
     * "BB:<key>:<version>" on the deployed process definitions.
     */
    fun getBouwblokProcesses(): List<BouwblokProcess> =
        gzacClient.processDefinitions().mapNotNull { def ->
            val versionTag = def["versionTag"] as? String ?: return@mapNotNull null
            if (!versionTag.startsWith(BB_VERSION_TAG_PREFIX)) return@mapNotNull null
            val parts = versionTag.removePrefix(BB_VERSION_TAG_PREFIX).split(':')
            if (parts.size != 2) return@mapNotNull null
            BouwblokProcess(
                bouwblokKey = parts[0],
                bouwblokVersie = parts[1],
                processDefinitionKey = def["key"] as? String ?: return@mapNotNull null,
                naam = def["name"] as? String
            )
        }.sortedBy { it.bouwblokKey }

    /** The plugin configurations pushed by GZAC, for the configuration dropdown. */
    fun getPluginConfigurations(): List<Map<String, String>> =
        configurationStore.getAll().values.map {
            mapOf(
                "configId" to it.configId,
                // The configuration name from GZAC (pushed along); only very
                // old pushes without a title fall back to properties/uuid.
                "titel" to (it.title
                    ?: (it.properties["title"] as? String)?.takeIf { t -> t.isNotBlank() }
                    ?: it.configId)
            )
        }

    /**
     * Case types usable as product dossier, recognised by the
     * product-dossier contract in their document schema (dossierUrn +
     * instrumentUuid + doelUuid) — read from GZAC's case definitions, no
     * marker administration needed.
     */
    fun getProductDossiertypes(): List<String> =
        gzacClient.caseDefinitionKeys().filter { key ->
            try {
                gzacClient.documentSchemaPaths(key)
                    .containsAll(listOf("/dossierUrn", "/instrumentUuid", "/doelUuid"))
            } catch (e: Exception) {
                false
            }
        }

    // ------------------------------------------- plugin configuration mapping

    /**
     * Ensures the case type's building-block link maps the PDCA plugin to
     * the koppeling's configuration: pluginConfigurationMappings key
     * `external-plugin:pdca@<version>` on the CaseDefinitionBuildingBlockLink
     * — the standard GZAC route, so the building block's process links keep
     * their portable BUILDING_BLOCK reference and the runtime resolves the
     * configuration per case type. The zips deliberately ship the mapping
     * empty (configuration UUIDs are environment-specific) and a re-import
     * resets it, hence this also runs before every start (self-healing).
     * Idempotent; other mapping keys and the input/output mappings on the
     * link are preserved (the startable-item API replaces all properties at
     * once).
     */
    fun ensurePluginConfigurationMapping(koppeling: ActieBouwblokKoppeling) {
        val bouwblokKey = koppeling.buildingBlockKey?.takeIf { it.isNotBlank() }
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Bouwblok ontbreekt op de koppeling")
        val bouwblokVersion = koppeling.buildingBlockVersion
            ?: getBouwblokProcesses().firstOrNull { it.processDefinitionKey == koppeling.processDefinitionKey }
                ?.bouwblokVersie
            ?: throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Bouwblokversie van '$bouwblokKey' is onbekend; is het bouwblok geïmporteerd?"
            )
        val caseVersionTag = gzacClient.caseDefinitionVersionTag(koppeling.caseDefinitionKey)
        val link = gzacClient.buildingBlockLink(
            koppeling.caseDefinitionKey, caseVersionTag, bouwblokKey, bouwblokVersion
        ) ?: throw ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "Dossiertype '${koppeling.caseDefinitionKey}' heeft geen bouwbloklink voor " +
                "'$bouwblokKey' ($bouwblokVersion); importeer de nieuwste dossier-zip"
        )

        val mappingKey = "external-plugin:$PDCA_PLUGIN_ID@${PluginHostController.PLUGIN_VERSION}"
        val mappings = (link["pluginConfigurationMappings"] as? Map<*, *>)
            ?.entries?.associate { it.key.toString() to it.value } ?: emptyMap()
        if (mappings[mappingKey] == koppeling.pluginConfigId) {
            return
        }

        gzacClient.updateBuildingBlockLink(
            koppeling.caseDefinitionKey, caseVersionTag, bouwblokKey, bouwblokVersion,
            mapOf(
                "inputMappings" to (link["inputMappings"] ?: emptyList<Any>()),
                "outputMappings" to (link["outputMappings"] ?: emptyList<Any>()),
                "pluginConfigurationMappings" to (mappings + (mappingKey to koppeling.pluginConfigId)),
                "startableByUser" to link["startableByUser"]
            )
        )
        log.info(
            "Mapped plugin configuration ${koppeling.pluginConfigId} for building block " +
                "'$bouwblokKey' ($bouwblokVersion) on case type '${koppeling.caseDefinitionKey}' " +
                "($caseVersionTag) under key '$mappingKey'"
        )
    }

    /**
     * DOSSIER products: the product case's pdca process links are FIXED and
     * ship **dangling** in the zip (configuration UUIDs are
     * environment-specific). Repairs them to the koppeling's configuration
     * through GZAC's plugin-configuration-mappings repair — dangling links
     * keyed by link id, re-targeted links by their current configuration id
     * — which also clears the case-configuration issue banner. Runs on save
     * and before every start (self-healing after re-import). By repo
     * convention the product case's process key equals its case definition
     * key.
     */
    fun ensureDossierPluginConfiguration(koppeling: ActieBouwblokKoppeling) {
        val caseKey = koppeling.productCaseDefinitionKey?.takeIf { it.isNotBlank() }
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Productdossiertype ontbreekt op de koppeling")
        val processDefinitionId = gzacClient.latestProcessDefinitionId(caseKey)
        val pdcaLinks = gzacClient.processLinks(processDefinitionId).filter {
            it["processLinkType"] == "external_plugin" && it["pluginDefinitionKey"] == PDCA_PLUGIN_ID
        }
        if (pdcaLinks.isEmpty()) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Productdossiertype '$caseKey' heeft geen pdca-proceskoppelingen; importeer de nieuwste dossier-zip"
            )
        }

        val mappings = buildMap {
            pdcaLinks.forEach { link ->
                when (val current = link["externalPluginConfigurationId"] as? String) {
                    null -> put(link["id"] as String, koppeling.pluginConfigId)
                    koppeling.pluginConfigId -> Unit
                    else -> put(current, koppeling.pluginConfigId)
                }
            }
        }
        if (mappings.isEmpty()) {
            return
        }

        gzacClient.resolvePluginConfigurationMappings(
            caseKey, gzacClient.caseDefinitionVersionTag(caseKey), mappings
        )
        log.info(
            "Repaired ${mappings.size} pdca process link(s) of product case type '$caseKey' " +
                "to configuration ${koppeling.pluginConfigId}"
        )
    }

    // ------------------------------------------------------------------ start

    /**
     * Starts the koppeling's process for a doel.
     *
     * BOUWBLOK: ensures the plugin configuration mapping (self-healing) and
     * submits the building block's **start form** on the plan dossier — the
     * route GZAC's own UI also uses, and the only one that starts a
     * blueprint-owned (BB:-tagged) process. The form's fields land on the
     * dossier document under `/bouwblokStart`; the building-block link's
     * input mappings copy them into the building block document before the
     * process runs, so every plugin action can read them as `doc:`
     * references. For an ACTIE koppeling the action is created first and
     * removed again if the start fails — the start is atomic. For a PRODUCT
     * koppeling nothing is created locally.
     *
     * DOSSIER (PRODUCT only): creates a **new dossier** of the product case
     * type whose document content is the start payload (see
     * [startProductDossier]).
     *
     * Deliberately not transactional as a whole: the GESTART callback from
     * the process arrives within the start call on its own transaction; the
     * action must already exist by then and must not be overwritten with a
     * stale entity afterwards. That callback also delivers the process
     * instance id (the form submission itself does not return one).
     */
    fun start(koppelingId: UUID, doelUuid: UUID): StartResult {
        val koppeling = getKoppeling(koppelingId)

        val doel = fetchDoel(doelUuid)
        validateDoeltype(koppeling, doel)

        val planUuid = ((doel["plannen"] as? List<*>)?.firstOrNull() as? Map<*, *>)?.get("uuid") as? String
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Doel $doelUuid hangt niet onder een plan")
        val planDetails = planDetailsRepository.findById(UUID.fromString(planUuid)).orElseThrow {
            ResponseStatusException(HttpStatus.BAD_REQUEST, "Plan $planUuid heeft geen PDCA-plandetails")
        }
        if (planDetails.caseDefinitionKey != null && planDetails.caseDefinitionKey != koppeling.caseDefinitionKey) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Actie '${koppeling.naam}' hoort bij dossiertype '${koppeling.caseDefinitionKey}', " +
                    "dit plan is van '${planDetails.caseDefinitionKey}'"
            )
        }

        if (koppeling.uitvoeringsvorm == Uitvoeringsvorm.DOSSIER) {
            // The aanvraag lives in its own dossier; the plan dossier plays no role.
            return StartResult(actie = null, dossier = startProductDossier(koppeling, planUuid, doelUuid, doel))
        }

        val processKey = koppeling.processDefinitionKey
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Koppeling '${koppeling.naam}' heeft geen bouwblokproces")
        val dossierId = planDetails.dossierId
            ?: throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Plan $planUuid heeft geen gekoppeld plan-dossier; de taak van de actie kan nergens landen"
            )

        // Self-healing: make sure the case type's building-block link maps
        // the PDCA plugin to this koppeling's configuration (a re-import
        // resets the mapping), then find the start-form link used to start
        // the process.
        ensurePluginConfigurationMapping(koppeling)
        val processDefinitionId = gzacClient.latestProcessDefinitionId(processKey)
        val links = gzacClient.processLinks(processDefinitionId)
        val startFormLinkId = links.firstOrNull {
            it["processLinkType"] == "form" && it["activityType"] == "bpmn:StartEvent:start"
        }?.get("id") as? String
            ?: throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Bouwblokproces '$processKey' heeft geen startformulier-koppeling; " +
                    "importeer de nieuwste bouwblok-zip (het startformulier draagt de startgegevens)"
            )

        if (koppeling.soort == BouwblokSoort.PRODUCT) {
            // Product: no local bookkeeping — the building block IS the
            // request and manages the instrument in Open Plan itself through
            // the plugin actions aanmaak-instrument/update-instrument.
            val formData = mapOf(
                "bouwblokStart" to linkedMapOf(
                    "planUuid" to planUuid,
                    "doelUuid" to doelUuid.toString(),
                    "subjectId" to (subjectIdOfPlan(planUuid)
                        ?: (doel["persoon"] as? Map<*, *>)?.get("bsn") as? String)
                ).filterValues { it != null }
            )
            gzacClient.submitStartForm(startFormLinkId, dossierId, formData)
            log.info("Product building block '${koppeling.naam}' ($processKey) started on dossier $dossierId")
            return StartResult(actie = null, dossier = null)
        }

        // Create the action first, so the GESTART callback (which fires
        // within the start call) already finds it.
        val actie = actionRepository.save(
            Action(
                doelUuid = doelUuid,
                title = koppeling.naam,
                description = koppeling.omschrijving,
                status = ActionStatus.PLANNED,
                uitvoering = Uitvoering.BOUWBLOK,
                bouwblokKoppelingId = koppeling.id
            )
        )

        try {
            // Form.io keys with dots ("bouwblokStart.actieId") are read as a
            // nested path on submission (JsonPointer /bouwblokStart/actieId)
            // — so nest them.
            val formData = mapOf(
                "bouwblokStart" to startPayload(actie, planUuid, doelUuid, doel)
                    .filterValues { it != null }
            )
            gzacClient.submitStartForm(startFormLinkId, dossierId, formData)
        } catch (e: Exception) {
            actionRepository.deleteById(actie.id)
            log.warn("Starting actie building block '${koppeling.naam}' failed; action ${actie.id} rolled back: ${e.message}")
            throw e
        }

        // Re-read: the GESTART callback has usually already set the status
        // and process instance (synchronous execution up to the user task).
        val refreshed = actionRepository.findById(actie.id).orElse(actie)
        if (refreshed.gzacProcessInstanceId == null) {
            log.info(
                "Action ${actie.id} started but no GESTART callback received (yet) — " +
                    "does the building block have an update-actie service task before the first wait state?"
            )
        }
        log.info("Action ${actie.id} ('${koppeling.naam}') started via building block '$processKey' on dossier $dossierId")
        return StartResult(actie = refreshed, dossier = null)
    }

    /**
     * PRODUCT via DOSSIER: creates a dossier of the product case type; the
     * start payload IS the new dossier's document content, and the case
     * process takes it from there (assessment user tasks, on granting
     * aanmaak-instrument — with the dossier's URN in the instrument's
     * `zaak` field, which is how the plan page finds the dossier — and
     * update-instrument on completion, all with `doc:` references).
     *
     * The dossier's own URN is written into the document right after
     * creation; the created document id is only known after the create
     * call, and the process's first activity is a user task, so nothing
     * reads the document before the URN is there. Whether the dossier also
     * becomes a **zaak** is the case type's own configuration (GZAC
     * zaaktype-link with "create with dossier") — optional and invisible to
     * this app.
     */
    private fun startProductDossier(
        koppeling: ActieBouwblokKoppeling,
        planUuid: String,
        doelUuid: UUID,
        doel: Map<*, *>
    ): DossierRef {
        val caseKey = koppeling.productCaseDefinitionKey
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Koppeling '${koppeling.naam}' heeft geen productdossiertype")

        // Self-healing: repair the product case's pdca process links to this
        // koppeling's configuration (a re-import leaves them dangling).
        ensureDossierPluginConfiguration(koppeling)

        val content = linkedMapOf<String, Any?>(
            "planUuid" to planUuid,
            "doelUuid" to doelUuid.toString(),
            "subjectId" to (subjectIdOfPlan(planUuid) ?: (doel["persoon"] as? Map<*, *>)?.get("bsn") as? String),
            "productTitel" to koppeling.naam
        ).filterValues { it != null }

        val dossier = gzacClient.createDossier(caseKey, content)
        val dossierUrn = "$DOSSIER_URN_PREFIX$caseKey:${dossier.documentId}"
        gzacClient.modifyDocument(dossier.documentId, content + ("dossierUrn" to dossierUrn))

        log.info("Product dossier '$caseKey' (${dossier.documentId}) started for doel $doelUuid ('${koppeling.naam}')")
        return DossierRef(caseKey, dossier.documentId, dossierUrn)
    }

    private fun fetchDoel(doelUuid: UUID): Map<*, *> = try {
        openPlanRestClient.get()
            .uri("/plannen/api/v0/doel/$doelUuid")
            .retrieve()
            .body(Map::class.java)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Doel $doelUuid niet gevonden in Open Plan")
    } catch (e: ResponseStatusException) {
        throw e
    } catch (e: Exception) {
        throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "Doel $doelUuid ophalen uit Open Plan mislukt: ${e.message}")
    }

    private fun validateDoeltype(koppeling: ActieBouwblokKoppeling, doel: Map<*, *>) {
        val allowed = parseDoeltypeUuids(koppeling.doeltypeUuids)
        if (allowed.isEmpty()) return
        val doeltypeUuid = (doel["doeltype"] as? Map<*, *>)?.get("uuid") as? String
        if (doeltypeUuid == null || doeltypeUuid !in allowed) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Actie '${koppeling.naam}' is niet beschikbaar onder dit doeltype"
            )
        }
    }

    private fun parseDoeltypeUuids(raw: String?): List<String> = try {
        raw?.let { objectMapper.readValue(it, object : TypeReference<List<String>>() {}) } ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    /**
     * The fixed start payload: submitted as `bouwblokStart.*` fields of the
     * start form, it lands on the dossier document under `/bouwblokStart`
     * and flows into the building block document through the link's input
     * mappings. subjectId is the subject of the plan — a bsn (persoon) or
     * object id, read from `plan.domeinregister`
     * (urn:pdca:<register>:<resource>:<id>), falling back to the doel's
     * subject.
     */
    private fun startPayload(
        actie: Action,
        planUuid: String,
        doelUuid: UUID,
        doel: Map<*, *>
    ): Map<String, Any?> = linkedMapOf(
        "actieId" to actie.id.toString(),
        "actieTitel" to actie.title,
        "actieOmschrijving" to (actie.description ?: ""),
        "planUuid" to planUuid,
        "doelUuid" to doelUuid.toString(),
        "subjectId" to (subjectIdOfPlan(planUuid) ?: (doel["persoon"] as? Map<*, *>)?.get("bsn") as? String)
    )

    /** The subject id (bsn or object id) from plan.domeinregister; null when the plan carries none. */
    private fun subjectIdOfPlan(planUuid: String): String? = try {
        val plan = openPlanRestClient.get()
            .uri("/plannen/api/v0/plan/$planUuid")
            .retrieve()
            .body(Map::class.java)
        (plan?.get("domeinregister") as? String)?.split(':')?.getOrNull(4)?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        log.warn("Could not fetch the subject of plan $planUuid from Open Plan: ${e.message}")
        null
    }

    // ------------------------------------------------------------- callbacks

    /**
     * Handles the update-actie plugin action from the building block process.
     * Idempotent; the first callback of a process instance claims the action
     * (gzacProcessInstanceId), subsequent callbacks must carry the same
     * instance.
     */
    @Transactional
    fun handleUpdateActie(
        properties: Map<String, Any?>,
        processInstanceId: String?
    ): Action {
        val actieIdRaw = properties["actieId"] as? String
            ?: throw PluginActionException("ACTIE_ID_ONTBREEKT", "Property 'actieId' ontbreekt of is geen tekst")
        val actieId = try {
            UUID.fromString(actieIdRaw.trim())
        } catch (e: IllegalArgumentException) {
            throw PluginActionException("ACTIE_ID_ONGELDIG", "Property 'actieId' is geen uuid: '$actieIdRaw'")
        }
        val actie = actionRepository.findById(actieId).orElseThrow {
            PluginActionException("ACTIE_ONBEKEND", "Geen actie met id $actieId")
        }

        if (!processInstanceId.isNullOrBlank()) {
            when (actie.gzacProcessInstanceId) {
                null -> actie.gzacProcessInstanceId = processInstanceId
                processInstanceId -> Unit
                else -> throw PluginActionException(
                    "PROCES_MISMATCH",
                    "Actie $actieId hoort bij procesinstantie ${actie.gzacProcessInstanceId}, niet bij $processInstanceId"
                )
            }
        }

        val gebeurtenis = (properties["gebeurtenis"] as? String)?.trim()?.uppercase()
            ?: throw PluginActionException("GEBEURTENIS_ONTBREEKT", "Property 'gebeurtenis' ontbreekt")
        val resultaat = (properties["resultaat"] as? String)?.takeIf { it.isNotBlank() }
        val toelichting = (properties["toelichting"] as? String)?.takeIf { it.isNotBlank() }
        val completed = actie.status == ActionStatus.COMPLETED || actie.status == ActionStatus.REJECTED

        when (gebeurtenis) {
            "GESTART" -> if (actie.status == ActionStatus.PLANNED) {
                actie.status = ActionStatus.IN_PROGRESS
            }
            "TER_BEOORDELING" -> if (!completed) {
                actie.status = ActionStatus.PENDING_REVIEW
            }
            "AFGEROND" -> {
                actie.status = ActionStatus.COMPLETED
                if (actie.completedDate == null) actie.completedDate = LocalDate.now()
                resultaat?.let { actie.result = it }
            }
            "AFGEWEZEN" -> {
                actie.status = ActionStatus.REJECTED
                (toelichting ?: resultaat)?.let { actie.result = it }
            }
            else -> throw PluginActionException(
                "GEBEURTENIS_ONBEKEND",
                "Onbekende gebeurtenis '$gebeurtenis' (verwacht: GESTART, TER_BEOORDELING, AFGEROND of AFGEWEZEN)"
            )
        }
        actie.updatedAt = LocalDateTime.now()
        val saved = actionRepository.save(actie)
        log.info("update-actie $gebeurtenis processed for action $actieId (status ${saved.status})")
        return saved
    }
}
