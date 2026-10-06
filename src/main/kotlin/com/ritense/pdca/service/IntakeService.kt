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
import com.ritense.pdca.domain.ContactmomentDetails
import com.ritense.pdca.domain.DoelDetails
import com.ritense.pdca.domain.InstrumentDetails
import com.ritense.pdca.domain.InvolvedParty
import com.ritense.pdca.domain.PlanDetails
import com.ritense.pdca.domain.UitvoeringsStatus
import com.ritense.pdca.repository.ContactmomentDetailsRepository
import com.ritense.pdca.repository.DoelDetailsRepository
import com.ritense.pdca.repository.InstrumentDetailsRepository
import com.ritense.pdca.repository.InvolvedPartyRepository
import com.ritense.pdca.repository.PlanDetailsRepository
import com.ritense.pdca.web.rest.dto.PlanIntakeRequest
import com.ritense.pdca.web.rest.dto.PlanIntakeResponse
import com.ritense.pdca.web.rest.dto.PlanPrefillResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Plan creation through a user task, fed by the intake in the dossier
 * content:
 *
 *  - [prefill] reads the dossier via GZAC and translates the content into a
 *    proposal for the create-plan task form, using the document paths from
 *    the case type's prefill mapping (PhaseConfig.prefillMapping,
 *    configurable in PDCA Beheer; [DEFAULT_PREFILL_MAPPING] when nothing is
 *    configured). Minimal content (just a bsn) yields a minimal prefill; a
 *    full intake prefills the whole plan.
 *  - [createPlanFromIntake] creates the (edited) plan structure: plan +
 *    doelen + instrumenten + contactmomenten in Open Plan, the matching
 *    overlay rows and betrokkenen, and links the plan to the dossier.
 */
@Service
class IntakeService(
    private val gzacClient: GzacClient,
    private val dossierLinkService: DossierLinkService,
    private val phaseConfigService: PhaseConfigService,
    private val planDetailsRepository: PlanDetailsRepository,
    private val doelDetailsRepository: DoelDetailsRepository,
    private val instrumentDetailsRepository: InstrumentDetailsRepository,
    private val contactmomentDetailsRepository: ContactmomentDetailsRepository,
    private val involvedPartyRepository: InvolvedPartyRepository,
    private val openPlanRestClient: RestClient,
    private val objectMapper: ObjectMapper
) {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /** Object plans get this administrative contact person (doelen require a persoon). */
        const val OBJECT_CONTACT_BSN = "234567892"

        /**
         * Sane default document paths (JSON pointers in the dossier
         * content). Same convention as the bundled intake case types; a case
         * type with a different content structure configures its own mapping
         * in PDCA Beheer.
         */
        val DEFAULT_PREFILL_MAPPING: Map<String, String> = linkedMapOf(
            "subjectIdPersoon" to "/bsn",
            "subjectIdObject" to "/objectId",
            "naam" to "/naam",
            "objectNaam" to "/objectNaam",
            "titel" to "/planTitel",
            "notitie" to "/planNotitie",
            "dienstverlening" to "/dienstverlening",
            "hoofddoel" to "/hoofddoel",
            "subdoelgroep" to "/subdoelgroep",
            "weergaveStatus" to "/weergaveStatus",
            "beginPositie" to "/beginPositie",
            "startdatum" to "/startdatum",
            "streefEinddatum" to "/streefEinddatum",
            "doelen" to "/doelen",
            "instrumenten" to "/instrumenten",
            "contactmomenten" to "/contactmomenten",
            "betrokkenen" to "/betrokkenen"
        )
    }

    // ------------------------------------------------------------- prefill

    fun prefill(documentId: String): PlanPrefillResponse {
        val document = gzacClient.getDocument(documentId)
        val content = document["content"] as? Map<*, *> ?: emptyMap<Any, Any>()
        val caseDefinitionKey = definitionName(document)
        val config = caseDefinitionKey?.let { phaseConfigService.findByCaseDefinitionKey(it) }
        val mapping = caseDefinitionKey?.let { prefillMapping(it) } ?: DEFAULT_PREFILL_MAPPING

        val prefill = mutableMapOf<String, Any?>()
        mapping.forEach { (veld, pad) ->
            resolvePointer(content, pad)?.let { prefill[veld] = it }
        }
        // Subject resolution: persoon wins; otherwise object.
        when {
            prefill["subjectIdPersoon"] != null -> {
                prefill["subjectType"] = "PERSON"
                prefill["subjectId"] = prefill.remove("subjectIdPersoon")
                prefill.remove("subjectIdObject")
                prefill.remove("objectNaam")
            }
            prefill["subjectIdObject"] != null -> {
                prefill["subjectType"] = "OBJECT"
                prefill["subjectId"] = prefill.remove("subjectIdObject")
                prefill["naam"] = prefill.remove("objectNaam") ?: prefill["naam"]
            }
        }
        return PlanPrefillResponse(
            documentId = documentId,
            caseDefinitionKey = caseDefinitionKey,
            planCaseDefinitionKey = config?.planCaseDefinitionKey?.takeIf { it.isNotBlank() }
                ?: caseDefinitionKey,
            mapping = mapping,
            prefill = prefill
        )
    }

    private fun prefillMapping(caseDefinitionKey: String): Map<String, String> {
        val configured = phaseConfigService.findByCaseDefinitionKey(caseDefinitionKey)
            ?.prefillMapping?.takeIf { it.isNotBlank() } ?: return DEFAULT_PREFILL_MAPPING
        return try {
            objectMapper.readValue(configured, object : TypeReference<LinkedHashMap<String, String>>() {})
        } catch (e: Exception) {
            log.warn("Ongeldige prefill mapping voor '$caseDefinitionKey', standaardpaden gebruikt: ${e.message}")
            DEFAULT_PREFILL_MAPPING
        }
    }

    private fun definitionName(document: Map<*, *>): String? =
        (document["definitionId"] as? Map<*, *>)?.get("name") as? String
            ?: document["definitionName"] as? String

    /** Minimal JSON-pointer resolution ("/a/b/0") over maps and lists. */
    private fun resolvePointer(root: Any?, pointer: String): Any? {
        var huidig: Any? = root
        for (segment in pointer.trim('/').split('/')) {
            if (segment.isEmpty()) continue
            huidig = when (huidig) {
                is Map<*, *> -> huidig[segment]
                is List<*> -> segment.toIntOrNull()?.let { huidig.getOrNull(it) }
                else -> null
            } ?: return null
        }
        return when (huidig) {
            is String -> huidig.takeIf { it.isNotBlank() }
            is List<*> -> huidig.takeIf { it.isNotEmpty() }
            else -> huidig
        }
    }

    // ------------------------------------------------ plan from the intake

    fun createPlanFromIntake(request: PlanIntakeRequest): PlanIntakeResponse {
        val warnings = mutableListOf<String>()

        // Mode is decided by the dossier the task runs in:
        //  - plan case type: the plan is linked to THIS dossier (which must
        //    not have a plan yet, plan = dossier 1:1);
        //  - intake case type (config with planCaseDefinitionKey): the plan
        //    gets its own plan dossier of that type after creation;
        //  - no documentId (standalone route): same, its own plan dossier.
        // This way plan and plan dossier always come into existence
        // together, in a single call.
        var linkDocumentId: String? = null
        var intakeDocumentId: String? = null
        var planCaseDefinitionKey = request.caseDefinitionKey
        request.documentId?.let { documentId ->
            val dossierCaseDef = definitionName(gzacClient.getDocument(documentId))
            val intakeDoelType = dossierCaseDef
                ?.let { phaseConfigService.findByCaseDefinitionKey(it) }
                ?.planCaseDefinitionKey?.takeIf { it.isNotBlank() }
            if (intakeDoelType != null) {
                planCaseDefinitionKey = intakeDoelType
                intakeDocumentId = documentId
                // Idempotent per intake: the create-plan task form can be
                // submitted more than once for the same intake (a double
                // click, a task submission that failed after the plan was
                // created, a reopened task). Creating a second plan is never
                // what is meant, and refusing outright would leave the task
                // uncompletable — the one-active-plan-per-inwoner rule below
                // would reject every retry. So hand back the plan this intake
                // already produced and let the task complete on it.
                bestaandIntakePlan(documentId)?.let { bestaand -> return bestaand }
            } else {
                dossierLinkService.findByDossier(documentId)?.let {
                    throw ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Dossier $documentId heeft al een gekoppeld plan (${it.planUuid})"
                    )
                }
                linkDocumentId = documentId
            }
        }
        validatePositie(planCaseDefinitionKey, "beginPositie", request.beginPositie)

        val doeltypen = listRegister("/plannen/api/v0/doeltype")
        val doeltypePerNaam = doeltypen.associateBy { (it["doelType"] as? String).orEmpty() }
        val hoofddoelType = request.hoofddoel?.let {
            doeltypePerNaam[it] ?: throw ResponseStatusException(
                HttpStatus.BAD_REQUEST, "Hoofddoel '$it' is onbekend in het doeltype-register"
            )
        }

        val bsn = if (request.subjectType == "PERSON") request.subjectId else OBJECT_CONTACT_BSN
        val persoonUuid = ensurePersoon(bsn)

        // W&P rule: an inwoner has at most one active plan per plan case type.
        // Overlay rows give the candidates; the register status decides.
        if (request.subjectType == "PERSON" && planCaseDefinitionKey != null) {
            val heeftActiefPlan = planDetailsRepository
                .findByPersoonUuidAndCaseDefinitionKey(persoonUuid, planCaseDefinitionKey)
                .any { (getRegister("/plannen/api/v0/plan/${it.planUuid}")?.get("status") as? String) == "actief" }
            if (heeftActiefPlan) {
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Deze inwoner heeft al een actief plan van het type '$planCaseDefinitionKey'; " +
                        "rond dat plan eerst af of breek het af"
                )
            }
        }
        val plantypeUuid = resolvePlantype(request.dienstverlening, warnings)
        val overkoepelendUuid = (listRegister("/plannen/api/v0/overkoepelendplan").firstOrNull()?.get("uuid") as? String)
            ?: throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "Open Plan mist een overkoepelend plan (draai de register-init)")

        val domeinregister = if (request.subjectType == "PERSON") {
            "urn:pdca:brp:persoon:${request.subjectId}"
        } else {
            "urn:pdca:objecten:object:${request.subjectId}"
        }
        val startdatum = request.startdatum?.takeIf { it.isNotBlank() }
            ?: java.time.LocalDate.now().toString()

        // 1. The plan in Open Plan.
        val plan = postRegister(
            "/plannen/api/v0/plan", buildMap {
                put("titel", request.titel)
                put("notitie", request.notitie ?: "")
                put("startdatum", "${startdatum}T00:00:00Z")
                put("plantypeUuid", plantypeUuid)
                put("overkoepelendPlanUuid", overkoepelendUuid)
                put("domeinregister", domeinregister)
                put("medewerker", "")
            }
        )
        val planUuid = UUID.fromString(plan["uuid"] as String)

        // 2. Overlay: plan details (positions, subdoelgroep, hoofddoel, status, target date).
        planDetailsRepository.save(
            PlanDetails(
                planUuid = planUuid,
                persoonUuid = persoonUuid,
                caseDefinitionKey = planCaseDefinitionKey,
                intakeDossierId = intakeDocumentId,
                weergaveStatus = request.weergaveStatus ?: "Concept",
                beginPositie = request.beginPositie,
                subdoelgroep = request.subdoelgroep?.takeIf { it.isNotBlank() },
                hoofddoelTypeUuid = (hoofddoelType?.get("uuid") as? String)?.let { UUID.fromString(it) },
                streefEinddatum = request.streefEinddatum?.takeIf { it.isNotBlank() }
                    ?.let { java.time.LocalDate.parse(it) }
            )
        )

        // 3. The hoofddoel as a real Doel (W&P hierarchy: plan -> 1 actief
        //    hoofddoel -> subdoelen); the subdoelen reference it through the
        //    register field hoofdDoel.
        val hoofddoelDoelUuid = hoofddoelType?.let { type ->
            val hoofddoelDoel = postRegister(
                "/plannen/api/v0/doel", buildMap {
                    put("plannenUuids", listOf(planUuid.toString()))
                    put("persoonUuid", persoonUuid.toString())
                    put("doeltypeUuid", type["uuid"])
                    put("titel", request.hoofddoel ?: "")
                    put("beschrijving", request.notitie ?: "")
                    put("startdatum", "${startdatum}T00:00:00Z")
                }
            )
            hoofddoelDoel["uuid"] as String
        }

        // 4. Subdoelen (register driven: titel = name of the doeltype),
        //    linked to the hoofddoel.
        val doelUuidPerType = mutableMapOf<String, UUID>()
        request.doelen.forEachIndexed { index, doel ->
            val doeltype = doeltypePerNaam[doel.doelType] ?: throw ResponseStatusException(
                HttpStatus.BAD_REQUEST, "Doel '${doel.doelType}' is onbekend in het doeltype-register"
            )
            val status = doel.status?.takeIf { it.isNotBlank() } ?: "actief"
            val aangemaakt = postRegister(
                "/plannen/api/v0/doel", buildMap {
                    put("plannenUuids", listOf(planUuid.toString()))
                    put("persoonUuid", persoonUuid.toString())
                    put("doeltypeUuid", doeltype["uuid"])
                    put("titel", doel.doelType)
                    put("beschrijving", doel.beschrijving ?: "")
                    put("startdatum", "${startdatum}T00:00:00Z")
                    put("status", status)
                    hoofddoelDoelUuid?.let { put("hoofdDoel", it) }
                    if (status == "afgerond") {
                        put("resultaat", doel.resultaat?.takeIf { it.isNotBlank() } ?: "behaald")
                        put("einddatum", "${java.time.LocalDate.now()}T00:00:00Z")
                    }
                }
            )
            val doelUuid = UUID.fromString(aangemaakt["uuid"] as String)
            doelUuidPerType[doel.doelType] = doelUuid
            doelDetailsRepository.save(
                DoelDetails(
                    doelUuid = doelUuid,
                    planUuid = planUuid,
                    uitvoeringsStatus = if (status == "actief" && (doel.voortgang ?: 0) > 0) {
                        UitvoeringsStatus.GESTART
                    } else {
                        UitvoeringsStatus.GEPLAND
                    },
                    voortgangScore = doel.voortgang,
                    voortgangToelichting = doel.toelichting,
                    sortering = index
                )
            )
        }

        // 5. Instrumenten, linked to their doel (reference = doeltype name).
        val instrumenttypeUuid = listRegister("/plannen/api/v0/instrumenttype").firstOrNull()?.get("uuid") as? String
        var instrumentCount = 0
        request.instrumenten.forEach { instrument ->
            val doelUuid = instrument.doel?.let { doelUuidPerType[it] } ?: doelUuidPerType.values.firstOrNull()
            if (doelUuid == null) {
                warnings += "Instrument '${instrument.titel}' overgeslagen: geen doel om aan te koppelen"
                return@forEach
            }
            val status = instrument.status?.takeIf { it.isNotBlank() } ?: "actief"
            val aangemaakt = postRegister(
                "/plannen/api/v0/instrument", buildMap {
                    put("titel", instrument.titel)
                    put("startdatum", "${startdatum}T00:00:00Z")
                    put("doelenUuids", listOf(doelUuid.toString()))
                    put("ontwikkelwensenUuids", emptyList<String>())
                    instrumenttypeUuid?.let { put("instrumenttypeUuid", it) }
                    instrument.product?.takeIf { it.isNotBlank() }?.let {
                        put("product", "urn:pdca:openproduct:producttype:$it")
                    }
                    put("status", status)
                    if (status == "afgerond") {
                        put("resultaat", instrument.resultaat?.takeIf { it.isNotBlank() } ?: "behaald")
                        put("einddatum", "${java.time.LocalDate.now()}T00:00:00Z")
                    }
                }
            )
            val instrumentUuid = UUID.fromString(aangemaakt["uuid"] as String)
            if (instrument.urenBesteed != null || instrument.effectiviteit != null) {
                instrumentDetailsRepository.save(
                    InstrumentDetails(
                        instrumentUuid = instrumentUuid,
                        planUuid = planUuid,
                        urenBesteed = instrument.urenBesteed,
                        effectiviteitScore = instrument.effectiviteit
                    )
                )
            }
            instrumentCount++
        }

        // 6. Contactmomenten + evaluation overlay.
        request.contactmomenten.forEach { cm ->
            val datum = cm.datum?.takeIf { it.isNotBlank() } ?: java.time.LocalDate.now().toString()
            val aangemaakt = postRegister(
                "/plannen/api/v0/contactmoment", buildMap {
                    put("planUuid", planUuid.toString())
                    put("datum", "${datum}T10:00:00Z")
                    put("status", cm.status?.takeIf { it.isNotBlank() } ?: "actief")
                    put("notitie", cm.notitie ?: "")
                }
            )
            contactmomentDetailsRepository.save(
                ContactmomentDetails(
                    contactmomentUuid = UUID.fromString(aangemaakt["uuid"] as String),
                    planUuid = planUuid,
                    evaluatieType = cm.evaluatieType?.takeIf { it.isNotBlank() } ?: "EVALUATION",
                    geplandeDatum = java.time.LocalDate.parse(datum),
                    deelnemers = cm.deelnemers,
                    actiepunten = cm.actiepunten?.takeIf { it.isNotEmpty() }
                        ?.let { objectMapper.writeValueAsString(it) }
                )
            )
        }

        // 7. Betrokkenen (local contact cards).
        request.betrokkenen.forEach { b ->
            involvedPartyRepository.save(
                InvolvedParty(
                    planUuid = planUuid,
                    name = b.naam,
                    role = b.rol,
                    email = b.email,
                    organization = b.organisatie,
                    isPrimary = b.hoofdverantwoordelijke
                )
            )
        }

        // 8. Plan = dossier (1:1): link to the current plan dossier, or
        // create the plan dossier (intake and standalone route) — always both.
        val planDossierId = linkDocumentId?.also { dossierLinkService.link(planUuid, it) }
            ?: dossierLinkService.ensurePlanDossier(planUuid).first
        log.info(
            "Plan $planUuid ($planCaseDefinitionKey) aangemaakt uit intake " +
                "(taak-dossier ${request.documentId ?: "-"}, plan-dossier $planDossierId): " +
                "${doelUuidPerType.size} doelen, $instrumentCount instrumenten, " +
                "${request.contactmomenten.size} contactmomenten"
        )

        return PlanIntakeResponse(
            planUuid = planUuid.toString(),
            documentId = request.documentId,
            planDossierId = planDossierId,
            doelen = doelUuidPerType.size,
            instrumenten = instrumentCount,
            contactmomenten = request.contactmomenten.size,
            betrokkenen = request.betrokkenen.size,
            warnings = warnings
        )
    }

    /**
     * The plan this intake dossier already produced, as a response the task
     * form can complete on, or null when it has none yet. A plan whose
     * register entry is gone (demo reset) does not count — the intake may
     * then create a new one.
     */
    private fun bestaandIntakePlan(intakeDocumentId: String): PlanIntakeResponse? {
        val bestaand = planDetailsRepository.findByIntakeDossierId(intakeDocumentId)
            .firstOrNull { getRegister("/plannen/api/v0/plan/${it.planUuid}") != null }
            ?: return null
        log.info(
            "Intake-dossier $intakeDocumentId heeft al plan ${bestaand.planUuid} " +
                "(plan-dossier ${bestaand.dossierId}); geen tweede plan aangemaakt"
        )
        return PlanIntakeResponse(
            planUuid = bestaand.planUuid.toString(),
            documentId = intakeDocumentId,
            planDossierId = bestaand.dossierId,
            doelen = 0,
            instrumenten = 0,
            contactmomenten = 0,
            betrokkenen = 0,
            warnings = emptyList(),
            bestaand = true
        )
    }

    private fun validatePositie(caseDefinitionKey: String, veld: String, waarde: String?) {
        if (waarde.isNullOrBlank()) return
        val register = phaseConfigService.getPositieTypen(caseDefinitionKey) ?: return
        if (waarde !in register) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "$veld '$waarde' komt niet voor in het positietype-register van '$caseDefinitionKey'"
            )
        }
    }

    private fun resolvePlantype(dienstverlening: String?, warnings: MutableList<String>): String {
        val plantypen = listRegister("/plannen/api/v0/plantype")
        if (plantypen.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "Open Plan mist plantypen (draai de register-init)")
        }
        val gekozen = dienstverlening?.let { naam -> plantypen.firstOrNull { it["type"] == naam } }
        if (dienstverlening != null && gekozen == null) {
            warnings += "Dienstverlening '$dienstverlening' onbekend; '${plantypen.first()["type"]}' gebruikt"
        }
        return (gekozen ?: plantypen.first())["uuid"] as String
    }

    /** Get-or-create of an Open Plan persoon by bsn (the register has no bsn filter). */
    private fun ensurePersoon(bsn: String): UUID {
        val bestaand = listRegister("/plannen/api/v0/persoon").firstOrNull { it["bsn"] == bsn }
        if (bestaand != null) return UUID.fromString(bestaand["uuid"] as String)
        val aangemaakt = postRegister(
            "/plannen/api/v0/persoon", mapOf(
                "bsn" to bsn,
                "klant" to "urn:pdca:openklant:klant:$bsn",
                "persoonsprofiel" to "urn:pdca:profielen:persoonsprofiel:$bsn"
            )
        )
        return UUID.fromString(aangemaakt["uuid"] as String)
    }

    // ---------------------------------------------- Open Plan REST helpers

    private fun listRegister(path: String): List<Map<*, *>> {
        val resultaten = mutableListOf<Map<*, *>>()
        var page = 1
        while (page <= 20) {
            val body = openPlanRestClient.get()
                .uri("$path?page=$page")
                .retrieve()
                .body(Map::class.java) ?: break
            (body["results"] as? List<*>)?.filterIsInstance<Map<*, *>>()?.let { resultaten += it }
            if (body["next"] == null) break
            page++
        }
        return resultaten
    }

    /** Single register object, or null when unreachable/missing (best-effort checks). */
    private fun getRegister(path: String): Map<*, *>? = try {
        openPlanRestClient.get().uri(path).retrieve().body(Map::class.java)
    } catch (e: Exception) {
        null
    }

    private fun postRegister(path: String, body: Map<String, Any?>): Map<*, *> = try {
        openPlanRestClient.post()
            .uri(path)
            .contentType(MediaType.APPLICATION_JSON)
            .body(objectMapper.writeValueAsBytes(body))
            .retrieve()
            .body(Map::class.java)
            ?: throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "Open Plan gaf geen antwoord op POST $path")
    } catch (e: ResponseStatusException) {
        throw e
    } catch (e: Exception) {
        throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "Open Plan POST $path mislukt: ${e.message}")
    }
}
