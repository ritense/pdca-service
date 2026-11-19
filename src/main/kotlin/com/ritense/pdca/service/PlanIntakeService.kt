/*
 * Copyright 2015-2026 Ritense BV, the Netherlands.
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

import com.fasterxml.jackson.databind.ObjectMapper
import com.ritense.pdca.domain.ContactmomentDetails
import com.ritense.pdca.domain.DoelDetails
import com.ritense.pdca.domain.InvolvedParty
import com.ritense.pdca.domain.PdcaUrn
import com.ritense.pdca.domain.PlanDetails
import com.ritense.pdca.domain.PlanIntake
import com.ritense.pdca.domain.PlanIntakeStatus
import com.ritense.pdca.domain.UitvoeringsStatus
import com.ritense.pdca.registers.openplan.Doel
import com.ritense.pdca.registers.openplan.DoelType
import com.ritense.pdca.registers.openplan.NewDoel
import com.ritense.pdca.registers.openplan.NewPersoon
import com.ritense.pdca.registers.openplan.NewPlan
import com.ritense.pdca.registers.openplan.OpenPlanGateway
import com.ritense.pdca.registers.openplan.STATUS_ACTIEF
import com.ritense.pdca.registers.openplan.STATUS_AFGEROND
import com.ritense.pdca.repository.ContactmomentDetailsRepository
import com.ritense.pdca.repository.DoelDetailsRepository
import com.ritense.pdca.repository.InvolvedPartyRepository
import com.ritense.pdca.repository.PlanDetailsRepository
import com.ritense.pdca.repository.PlanIntakeRepository
import com.ritense.pdca.web.rest.dto.IntakeBetrokkene
import com.ritense.pdca.web.rest.dto.IntakeContactmoment
import com.ritense.pdca.web.rest.dto.PlanIntakeRequest
import com.ritense.pdca.web.rest.dto.PlanIntakeResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * Creates the plan of an intake dossier (`POST /plan-intake`, ARCHITECTURE
 * §6.1): plan + doelen in Open Plan, the contactmomenten as klantcontacten in
 * Open Klant, the overlay rows and betrokkenen, the plan dossier of the plan
 * case type the intake's case configuration names, and then one verzoek per
 * intake instrument through [InstrumentAanvraagService] (each gets its own
 * instrument dossier, which creates it in the plan).
 *
 * The call is safe to repeat and never reports a half-built plan as done:
 *
 *  1. **Validate first.** Everything that can be checked is checked before
 *     the first write ([valideer]): doeltypen, dates, field lengths against
 *     Open Plan's and Open Klant's limits, statuses, evaluation types,
 *     betrokkenen, the subject and the one-active-plan rule. A refusal is a
 *     400 listing every problem.
 *  2. **Reserve.** A [PlanIntake] row keyed by the intake dossier is
 *     reserved and claimed before any register write. A second request
 *     while one works on it gets 409 "Het plan wordt al aangemaakt". The
 *     claim is owned (a random token) and its lease is renewed between the
 *     steps; every write of the reservation is conditional on that token,
 *     so a request that lost its claim stops with the same 409 instead of
 *     overwriting the request that took over.
 *  3. **Build, resuming.** Each step looks up what an earlier attempt left
 *     before it creates anything (the persoon by bsn, the plan from the
 *     reservation, doelen per doeltype, contactmomenten per date/type/state,
 *     betrokkenen, the plan dossier through
 *     [DossierLinkService.ensurePlanDossier], verzoeken per doel and
 *     product), so a retry after a failure completes the missing steps.
 *  4. **Done.** Only then is the reservation [PlanIntakeStatus.GEREED];
 *     only a GEREED plan is reported as existing (`bestaand`).
 *
 * A temporary failure (a register or GZAC unavailable) fails the call and
 * leaves the reservation IN_AANMAAK for the retry; an instrument that
 * cannot be requested for a business reason becomes a warning.
 */
@Service
class PlanIntakeService(
    private val gzacClient: GzacClient,
    private val dossierLinkService: DossierLinkService,
    private val phaseConfigService: PhaseConfigService,
    private val planIntakeRepository: PlanIntakeRepository,
    private val planDetailsRepository: PlanDetailsRepository,
    private val doelDetailsRepository: DoelDetailsRepository,
    private val contactmomentDetailsRepository: ContactmomentDetailsRepository,
    private val involvedPartyRepository: InvolvedPartyRepository,
    private val klantcontactService: KlantcontactService,
    private val instrumentAanvraagService: InstrumentAanvraagService,
    private val planOverlayService: PlanOverlayService,
    private val openPlanGateway: OpenPlanGateway,
    private val transactionTemplate: TransactionTemplate,
    private val objectMapper: ObjectMapper
) {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /** Object plans get this administrative contact person (doelen require a persoon). */
        const val OBJECT_CONTACT_BSN = "234567892"

        /** Sortering of the hoofddoel's overlay row: before every subdoel (0 and up). */
        const val HOOFDDOEL_SORTERING = -1

        /**
         * How long a claim on an intake lasts without renewal; renewed between
         * the steps, so a crashed request blocks a retry at most this long.
         */
        val CLAIM_LEASE: Duration = Duration.ofMinutes(10)

        const val TITEL_MAX = 255
        const val TEKST_MAX = 1000
    }

    /** A validated intake: the request with everything resolved that the steps need. */
    data class Intake(
        val request: PlanIntakeRequest,
        val planCaseDefinitionKey: String,
        val hoofddoelType: DoelType?,
        val subdoelTypen: List<DoelType>,
        val doelStatussen: List<IntakeStatus>,
        val startdatum: LocalDate,
        val streefEinddatum: LocalDate?,
        val contactmomentDatums: List<LocalDate>,
        val plantypeUuid: UUID,
        val overkoepelendPlanUuid: UUID,
        val bsn: String,
        /** Per instrument row: the index of its subdoel, or null when it cannot be requested. */
        val instrumentDoelen: List<Int?>,
        val warnings: List<String>
    )

    /** Status of an intake doel: register status plus overlay uitvoeringsstatus. */
    enum class IntakeStatus(val registerStatus: String, val uitvoeringsStatus: UitvoeringsStatus) {
        GEPLAND(STATUS_ACTIEF, UitvoeringsStatus.GEPLAND),
        GESTART(STATUS_ACTIEF, UitvoeringsStatus.GESTART),
        AFGEROND(STATUS_AFGEROND, UitvoeringsStatus.GESTART)
    }

    /** This request's claim on an intake: [token] is the condition of every write of the reservation. */
    data class Claim(val intakeDossierId: String, val token: String, val hervat: Boolean)

    fun createPlanFromIntake(request: PlanIntakeRequest): PlanIntakeResponse {
        val intakeDocumentId = request.documentId
        bestaand(intakeDocumentId)?.let { return it }

        val planCaseDefinitionKey = planCaseType(intakeDocumentId)
        val intake = valideer(request, planCaseDefinitionKey)

        val claim = reserveer(intakeDocumentId) ?: return bestaand(intakeDocumentId)
            ?: throw inAanmaak()
        try {
            val response = bouw(intake, claim)
            if (planIntakeRepository.complete(intakeDocumentId, claim.token, LocalDateTime.now()) != 1) throw claimLost(claim)
            return response
        } finally {
            planIntakeRepository.release(intakeDocumentId, claim.token)
        }
    }

    // ---------------------------------------------------------- reservation

    /**
     * The plan of a GEREED intake whose plan still exists, as the response
     * the task form completes on; null otherwise. A GEREED plan that is gone
     * from Open Plan (demo reset) frees the intake for a new plan.
     */
    private fun bestaand(intakeDocumentId: String): PlanIntakeResponse? {
        val reservering = planIntakeRepository.findById(intakeDocumentId).orElse(null) ?: return null
        if (reservering.status != PlanIntakeStatus.GEREED) return null
        val planUuid = reservering.planUuid
        val details = planUuid?.let { planDetailsRepository.findById(it).orElse(null) }
        val planDossierId = details?.dossierId
        if (planUuid == null || planDossierId == null || openPlanGateway.findPlan(planUuid) == null) {
            log.info("Plan {} of intake {} no longer exists; the intake can create a new plan", planUuid, intakeDocumentId)
            planUuid?.let { planOverlayService.deletePlanDetails(it) }
            // Only the GEREED row this request read: one another request
            // reopened (and maybe rebuilt) meanwhile stays as it is.
            if (planUuid == null) planIntakeRepository.reopenWithoutPlan(intakeDocumentId, LocalDateTime.now())
            else planIntakeRepository.reopen(intakeDocumentId, planUuid, LocalDateTime.now())
            return null
        }
        log.info("Intake {} already has plan {} (plan dossier {})", intakeDocumentId, planUuid, planDossierId)
        return PlanIntakeResponse(
            planUuid = planUuid.toString(),
            documentId = intakeDocumentId,
            planDossierId = planDossierId,
            doelen = 0,
            instrumentenAangevraagd = 0,
            contactmomenten = 0,
            betrokkenen = 0,
            warnings = emptyList(),
            bestaand = true
        )
    }

    /**
     * Reserves and claims the intake. Returns the claim (whether it resumes
     * an earlier attempt included), null when the intake turned out to be
     * GEREED meanwhile; a claim held by another request is a 409.
     */
    private fun reserveer(intakeDocumentId: String): Claim? {
        val nu = LocalDateTime.now()
        val nieuw = planIntakeRepository.reserve(intakeDocumentId, nu) == 1
        val token = UUID.randomUUID().toString()
        if (planIntakeRepository.claim(intakeDocumentId, token, nu, nu.plus(CLAIM_LEASE)) == 1) {
            return Claim(intakeDocumentId, token, hervat = !nieuw)
        }
        val reservering = planIntakeRepository.findById(intakeDocumentId).orElse(null)
        if (reservering?.status == PlanIntakeStatus.GEREED) return null
        throw inAanmaak()
    }

    /** Renews the lease of [claim] before the next step; 409 when another request took the intake over. */
    private fun renewClaim(claim: Claim) {
        val nu = LocalDateTime.now()
        if (planIntakeRepository.renew(claim.intakeDossierId, claim.token, nu, nu.plus(CLAIM_LEASE)) != 1) throw claimLost(claim)
    }

    private fun claimLost(claim: Claim): ResponseStatusException {
        log.warn("Intake {}: the claim of this request ran out and was taken over; it stops", claim.intakeDossierId)
        return inAanmaak()
    }

    private fun inAanmaak() = ResponseStatusException(
        HttpStatus.CONFLICT,
        "Het plan wordt al aangemaakt; wacht even en probeer het dan opnieuw"
    )

    // ----------------------------------------------------------- validation

    private fun planCaseType(intakeDocumentId: String): String {
        val intakeCaseDefinitionKey = (gzacClient.getDocument(intakeDocumentId)["definitionId"] as? Map<*, *>)?.get("name") as? String
        return intakeCaseDefinitionKey
            ?.let { phaseConfigService.findByCaseDefinitionKey(it) }
            ?.planCaseDefinitionKey?.takeIf { it.isNotBlank() }
            ?: throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Dossiertype '${intakeCaseDefinitionKey ?: "onbekend"}' is geen intake-dossiertype: " +
                    "kies in PDCA Beheer het plan-dossiertype waarvoor deze intake een plan aanmaakt"
            )
    }

    /**
     * Checks everything that can be checked before the first write (reads
     * only) and resolves what the steps need. Every problem is collected;
     * any problem refuses the request with one 400.
     */
    fun valideer(request: PlanIntakeRequest, planCaseDefinitionKey: String): Intake {
        val fouten = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        if (request.titel.trim().length > TITEL_MAX) fouten += "de titel is langer dan $TITEL_MAX tekens"
        if ((request.notitie?.length ?: 0) > TEKST_MAX) fouten += "de toelichting is langer dan $TEKST_MAX tekens"
        request.beginPositie?.takeIf { it.isNotBlank() }?.let { positie ->
            val register = phaseConfigService.getPositieTypen(planCaseDefinitionKey)
            if (register != null && positie !in register) fouten += "positie '$positie' komt niet voor in het positietype-register"
        }
        request.weergaveStatus?.takeIf { it.isNotBlank() }?.let { status ->
            val statussen = phaseConfigService.getPlanStatussen(planCaseDefinitionKey)
            if (statussen != null && status !in statussen) fouten += "planstatus '$status' is niet ingesteld voor '$planCaseDefinitionKey'"
        }
        val startdatum = datum(request.startdatum, "startdatum", fouten) ?: LocalDate.now()
        val streefEinddatum = datum(request.streefEinddatum, "streefdatum", fouten)

        val doeltypen = openPlanGateway.listDoeltypen()
        val perNaam = doeltypen.associateBy { it.doelType.orEmpty() }
        val hoofddoelType = request.hoofddoel?.takeIf { it.isNotBlank() }?.let { naam ->
            perNaam[naam] ?: null.also { fouten += "hoofddoel '$naam' is onbekend in het doeltype-register" }
        }
        val subdoelTypen = request.doelen.mapNotNull { doel ->
            perNaam[doel.doelType] ?: null.also { fouten += "subdoel '${doel.doelType}' is onbekend in het doeltype-register" }
        }
        val doelStatussen = request.doelen.map { doel ->
            if ((doel.beschrijving?.length ?: 0) > TEKST_MAX) fouten += "de toelichting bij subdoel '${doel.doelType}' is langer dan $TEKST_MAX tekens"
            val resultaat = doel.resultaat?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            if (resultaat != null && resultaat !in InstrumentLifecycleService.RESULTATEN) {
                fouten += "onbekend resultaat '$resultaat' bij subdoel '${doel.doelType}' (verwacht: behaald of gefaald)"
            }
            doelStatus(doel.status) ?: IntakeStatus.GEPLAND.also {
                fouten += "onbekende status '${doel.status}' bij subdoel '${doel.doelType}' (verwacht: gepland, gestart of afgerond)"
            }
        }

        val evaluatieTypen = phaseConfigService.findByCaseDefinitionKey(planCaseDefinitionKey)
            ?.let { phaseConfigService.getEvaluationTypes(planCaseDefinitionKey) }.orEmpty()
        val contactmomentDatums = request.contactmomenten.map { cm ->
            val type = evaluatieType(cm)
            if (evaluatieTypen.isNotEmpty() && type !in evaluatieTypen) {
                fouten += "evaluatietype '$type' is niet ingesteld voor '$planCaseDefinitionKey' (kies uit ${evaluatieTypen.joinToString()})"
            }
            if (cm.status?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { it !in setOf("gepland", "afgerond") } == true) {
                fouten += "onbekende status '${cm.status}' bij een evaluatie (verwacht: gepland of afgerond)"
            }
            val verslag = cm.notitie?.trim().orEmpty()
            if (verslag.length > KlantcontactService.INHOUD_MAX_LENGTH) {
                fouten += "de notitie bij een evaluatie is langer dan ${KlantcontactService.INHOUD_MAX_LENGTH} tekens"
            }
            datum(cm.datum, "evaluatiedatum", fouten) ?: LocalDate.now()
        }
        request.betrokkenen.forEachIndexed { i, b ->
            if (b.naam.isBlank()) fouten += "betrokkene ${i + 1} heeft geen naam"
            if (b.rol.isBlank()) fouten += "betrokkene '${b.naam.ifBlank { "${i + 1}" }}' heeft geen rol"
        }
        if (request.subjectType == "OBJECT" && request.subjectId.isBlank()) fouten += "het object-ID ontbreekt"

        // Every instrument row is matched to a subdoel up front; the row
        // names it by doeltype name, which is ambiguous when two subdoelen
        // share a doeltype. A product is requested once per subdoel: a
        // second row for the same subdoel and product is dropped with a
        // warning (the open verzoek of the first would refuse it anyway).
        val requested = mutableSetOf<Pair<Int, String>>()
        val instrumentDoelen = request.instrumenten.map { instrument ->
            val code = instrument.product?.trim()?.takeIf { it.isNotEmpty() }
            val naam = instrument.doel?.trim()?.takeIf { it.isNotEmpty() }
            val kandidaten = request.doelen.indices.filter { naam == null || request.doelen[it].doelType == naam }
            when {
                code == null -> null.also { warnings += "Instrument zonder instrumenttype niet aangevraagd" }
                request.doelen.isEmpty() -> null.also { warnings += "Instrument '$code' niet aangevraagd: het plan heeft geen subdoel om het aan te koppelen" }
                naam != null && kandidaten.isEmpty() ->
                    null.also { warnings += "Instrument '$code' niet aangevraagd: subdoel '$naam' komt niet voor in het plan" }
                naam == null && request.doelen.size > 1 -> kandidaten.first().also {
                    warnings += "Instrument '$code' noemt geen subdoel; aangevraagd onder het eerste subdoel '${request.doelen[it].doelType}'"
                }
                kandidaten.size > 1 -> kandidaten.first().also {
                    warnings += "Instrument '$code': het plan heeft ${kandidaten.size} subdoelen '$naam'; aangevraagd onder het eerste"
                }
                else -> kandidaten.first()
            }?.takeIf { doelIndex ->
                requested.add(doelIndex to code!!).also { unique ->
                    if (!unique) {
                        warnings += "Instrument '$code' staat meer dan eens onder subdoel '${request.doelen[doelIndex].doelType}'; " +
                            "één keer aangevraagd"
                    }
                }
            }
        }

        val bsn = if (request.subjectType == "PERSON") request.subjectId.trim() else OBJECT_CONTACT_BSN
        if (request.subjectType == "PERSON") {
            heeftAlActiefPlan(bsn, planCaseDefinitionKey, request.documentId)?.let { fouten += it }
        }
        val plantypeUuid = resolvePlantype(request.dienstverlening, warnings)
        val overkoepelendUuid = openPlanGateway.firstOverkoepelendPlan()?.uuid
            ?: throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "Open Plan mist een overkoepelend plan (draai de register-init)")

        if (fouten.isNotEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Het plan kan niet worden aangemaakt: ${fouten.joinToString("; ")}")
        }
        return Intake(
            request = request,
            planCaseDefinitionKey = planCaseDefinitionKey,
            hoofddoelType = hoofddoelType,
            subdoelTypen = subdoelTypen,
            doelStatussen = doelStatussen,
            startdatum = startdatum,
            streefEinddatum = streefEinddatum,
            contactmomentDatums = contactmomentDatums,
            plantypeUuid = plantypeUuid,
            overkoepelendPlanUuid = overkoepelendUuid,
            bsn = bsn,
            instrumentDoelen = instrumentDoelen,
            warnings = warnings
        )
    }

    /**
     * W&P rule: an inwoner has at most one active plan per plan case type.
     * Overlay rows give the candidates, the register status decides; the
     * plan this intake is building does not count.
     */
    private fun heeftAlActiefPlan(bsn: String, planCaseDefinitionKey: String, intakeDocumentId: String): String? {
        val persoon = openPlanGateway.findPersoonByBsn(bsn) ?: return null
        val actief = planDetailsRepository.findByPersoonUuidAndCaseDefinitionKey(persoon.uuid, planCaseDefinitionKey)
            .filter { it.intakeDossierId != intakeDocumentId }
            .any { openPlanGateway.findPlan(it.planUuid)?.status == STATUS_ACTIEF }
        return if (actief) {
            "deze inwoner heeft al een actief plan van het type '$planCaseDefinitionKey'; rond dat plan eerst af of breek het af"
        } else {
            null
        }
    }

    private fun datum(waarde: String?, veld: String, fouten: MutableList<String>): LocalDate? {
        val tekst = waarde?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return try {
            LocalDate.parse(tekst)
        } catch (e: DateTimeParseException) {
            fouten += "$veld '$tekst' is geen geldige datum (jjjj-mm-dd)"
            null
        }
    }

    private fun doelStatus(status: String?): IntakeStatus? {
        val value = status?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return IntakeStatus.GEPLAND
        return IntakeStatus.entries.firstOrNull { it.name.lowercase() == value }
    }

    private fun evaluatieType(cm: IntakeContactmoment) = cm.evaluatieType?.takeIf { it.isNotBlank() } ?: "EVALUATION"

    private fun resolvePlantype(dienstverlening: String?, warnings: MutableList<String>): UUID {
        val plantypen = openPlanGateway.listPlantypen()
        if (plantypen.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "Open Plan mist plantypen (draai de register-init)")
        }
        val gekozen = dienstverlening?.let { naam -> plantypen.firstOrNull { it.type == naam } }
        if (dienstverlening != null && gekozen == null) {
            warnings += "Dienstverlening '$dienstverlening' onbekend; '${plantypen.first().type}' gebruikt"
        }
        return (gekozen ?: plantypen.first()).uuid
    }

    // ---------------------------------------------------------------- steps

    /** The steps, each resuming what an earlier attempt left; the claim's lease is renewed before every step. */
    private fun bouw(intake: Intake, claim: Claim): PlanIntakeResponse {
        val request = intake.request
        val hervat = claim.hervat
        val warnings = intake.warnings.toMutableList()
        val procesbegeleider = request.betrokkenen.firstOrNull { it.procesbegeleider }

        val persoonUuid = ensurePersoon(intake.bsn)
        renewClaim(claim)
        val planUuid = ensurePlan(intake, procesbegeleider?.naam, claim)
        renewClaim(claim)
        ensurePlanDetails(intake, planUuid, persoonUuid)
        val doelen = openPlanGateway.listDoelen(planUuid).toMutableList()
        val hoofddoelUuid = intake.hoofddoelType?.let { ensureHoofddoel(intake, it, planUuid, persoonUuid, doelen) }
        renewClaim(claim)
        val subdoelUuids = ensureSubdoelen(intake, planUuid, persoonUuid, hoofddoelUuid, doelen)
        renewClaim(claim)
        ensureContactmomenten(intake, planUuid)
        renewClaim(claim)
        ensureBetrokkenen(intake, planUuid, procesbegeleider)
        renewClaim(claim)
        val planDossierId = dossierLinkService.ensurePlanDossier(planUuid, request.subjectNaam).first
        val aangevraagd = ensureVerzoeken(intake, subdoelUuids, warnings) { renewClaim(claim) }

        log.info(
            "Plan {} ({}) {} from intake {} (plan dossier {}): {} subdoelen, {} instrumenten requested, {} contactmomenten",
            planUuid, intake.planCaseDefinitionKey, if (hervat) "completed" else "created", request.documentId,
            planDossierId, subdoelUuids.size, aangevraagd, request.contactmomenten.size
        )
        return PlanIntakeResponse(
            planUuid = planUuid.toString(),
            documentId = request.documentId,
            planDossierId = planDossierId,
            doelen = subdoelUuids.size,
            instrumentenAangevraagd = aangevraagd,
            contactmomenten = request.contactmomenten.size,
            betrokkenen = request.betrokkenen.size,
            warnings = warnings,
            hervat = hervat
        )
    }

    /** Get-or-create of an Open Plan persoon by bsn. */
    private fun ensurePersoon(bsn: String): UUID =
        openPlanGateway.findPersoonByBsn(bsn)?.uuid
            ?: openPlanGateway.createPersoon(
                NewPersoon(
                    bsn = bsn,
                    klant = PdcaUrn.klant(bsn).toString(),
                    persoonsprofiel = PdcaUrn.persoonsprofiel(bsn).toString()
                )
            ).uuid

    /**
     * The plan of the reservation when it still exists; otherwise a plan of
     * this subject and titel that no overlay row knows (one whose creation
     * answer got lost), otherwise a new one. The uuid is recorded in the
     * reservation right away, under [claim].
     */
    private fun ensurePlan(intake: Intake, procesbegeleider: String?, claim: Claim): UUID {
        val request = intake.request
        val reservering = planIntakeRepository.findById(request.documentId).orElseThrow()
        reservering.planUuid?.let { bekend ->
            if (openPlanGateway.findPlan(bekend) != null) return bekend
            planOverlayService.deletePlanDetails(bekend)
        }
        val domeinregister = if (request.subjectType == "PERSON") {
            PdcaUrn.persoon(request.subjectId.trim()).toString()
        } else {
            PdcaUrn.registerObject(request.subjectId.trim()).toString()
        }
        val wees = openPlanGateway.listPlannenOver(domeinregister).firstOrNull { plan ->
            plan.titel == request.titel.trim() && plan.status == STATUS_ACTIEF &&
                planDetailsRepository.findById(plan.uuid).isEmpty
        }
        val planUuid = wees?.uuid?.also { log.info("Intake {} adopts plan {} from an earlier attempt", request.documentId, it) }
            ?: openPlanGateway.createPlan(
                NewPlan(
                    titel = request.titel.trim(),
                    notitie = request.notitie ?: "",
                    startdatum = "${intake.startdatum}T00:00:00Z",
                    plantypeUuid = intake.plantypeUuid,
                    overkoepelendPlanUuid = intake.overkoepelendPlanUuid,
                    domeinregister = domeinregister,
                    medewerker = PdcaUrn.medewerker(procesbegeleider)
                )
            ).uuid
        if (planIntakeRepository.recordPlan(request.documentId, claim.token, planUuid, LocalDateTime.now()) != 1) throw claimLost(claim)
        return planUuid
    }

    private fun ensurePlanDetails(intake: Intake, planUuid: UUID, persoonUuid: UUID) {
        if (planDetailsRepository.existsById(planUuid)) return
        val request = intake.request
        planDetailsRepository.save(
            PlanDetails(
                planUuid = planUuid,
                persoonUuid = persoonUuid,
                caseDefinitionKey = intake.planCaseDefinitionKey,
                intakeDossierId = request.documentId,
                weergaveStatus = request.weergaveStatus?.takeIf { it.isNotBlank() } ?: "Concept",
                beginPositie = request.beginPositie?.takeIf { it.isNotBlank() },
                subdoelgroep = request.subdoelgroep?.takeIf { it.isNotBlank() },
                streefEinddatum = intake.streefEinddatum
            )
        )
    }

    /**
     * The hoofddoel as a real Doel (W&P hierarchy: plan -> 1 actief hoofddoel
     * -> subdoelen): the actief doel of that type when an earlier attempt
     * created it, otherwise a new one. It runs from the start.
     */
    private fun ensureHoofddoel(intake: Intake, type: DoelType, planUuid: UUID, persoonUuid: UUID, doelen: MutableList<Doel>): UUID {
        val uuid = doelen.firstOrNull { it.doeltype?.uuid == type.uuid && it.status == STATUS_ACTIEF }?.uuid
            ?: openPlanGateway.createDoel(
                NewDoel(
                    plannenUuids = listOf(planUuid),
                    persoonUuid = persoonUuid,
                    doeltypeUuid = type.uuid,
                    titel = type.doelType ?: intake.request.hoofddoel.orEmpty(),
                    beschrijving = intake.request.notitie ?: "",
                    startdatum = "${intake.startdatum}T00:00:00Z"
                )
            ).also { doelen += it }.uuid
        if (!doelDetailsRepository.existsById(uuid)) {
            doelDetailsRepository.save(
                DoelDetails(doelUuid = uuid, planUuid = planUuid, uitvoeringsStatus = UitvoeringsStatus.GESTART, sortering = HOOFDDOEL_SORTERING)
            )
        }
        return uuid
    }

    /**
     * The subdoelen in request order (titel = name of the doeltype), linked
     * to the hoofddoel. Doelen an earlier attempt created are taken per
     * doeltype in creation order, so two subdoelen of one doeltype stay two.
     */
    private fun ensureSubdoelen(
        intake: Intake,
        planUuid: UUID,
        persoonUuid: UUID,
        hoofddoelUuid: UUID?,
        doelen: List<Doel>
    ): List<UUID> {
        val beschikbaar = doelen.filter { it.uuid != hoofddoelUuid }.groupBy { it.doeltype?.uuid }
            .mapValues { (_, lijst) -> lijst.toMutableList() }.toMutableMap()
        return intake.request.doelen.mapIndexed { index, doel ->
            val type = intake.subdoelTypen[index]
            val status = intake.doelStatussen[index]
            val afgerond = status == IntakeStatus.AFGEROND
            val uuid = beschikbaar[type.uuid]?.removeFirstOrNull()?.uuid
                ?: openPlanGateway.createDoel(
                    NewDoel(
                        plannenUuids = listOf(planUuid),
                        persoonUuid = persoonUuid,
                        doeltypeUuid = type.uuid,
                        titel = doel.doelType,
                        beschrijving = doel.beschrijving ?: "",
                        startdatum = "${intake.startdatum}T00:00:00Z",
                        status = status.registerStatus,
                        hoofdDoel = hoofddoelUuid,
                        resultaat = if (afgerond) doel.resultaat?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: "behaald" else null,
                        einddatum = if (afgerond) "${LocalDate.now()}T00:00:00Z" else null
                    )
                ).uuid
            if (!doelDetailsRepository.existsById(uuid)) {
                doelDetailsRepository.save(
                    DoelDetails(
                        doelUuid = uuid,
                        planUuid = planUuid,
                        uitvoeringsStatus = status.uitvoeringsStatus,
                        voortgangToelichting = doel.toelichting,
                        sortering = index
                    )
                )
            }
            uuid
        }
    }

    /**
     * Contactmomenten (klantcontacten in Open Klant) + their overlay rows.
     * Those an earlier attempt created are recognised by date, type and
     * whether they took place, and counted per that key. The type is the
     * overlay row's, or (when the attempt failed before writing that row)
     * the one the klantcontact's onderwerp was written with.
     */
    private fun ensureContactmomenten(intake: Intake, planUuid: UUID) {
        val request = intake.request
        if (request.contactmomenten.isEmpty()) return
        val overlayTypes = contactmomentDetailsRepository.findByPlanUuid(planUuid)
            .associate { it.contactmomentUuid to it.evaluatieType }
        val bestaand = klantcontactService.ofPlan(planUuid).groupBy { kc ->
            Triple(
                kc.plaatsgevondenOp?.atZoneSameInstant(ZoneId.systemDefault())?.toLocalDate(),
                overlayTypes[kc.uuid] ?: klantcontactService.evaluatieTypeOf(kc.onderwerp),
                !kc.gepland
            )
        }.mapValues { (_, lijst) -> lijst.toMutableList() }
        request.contactmomenten.forEachIndexed { index, cm ->
            val datum = intake.contactmomentDatums[index]
            val evaluatieType = evaluatieType(cm)
            val afgerond = cm.status?.trim()?.lowercase() == "afgerond"
            val uuid = bestaand[Triple(datum, evaluatieType, afgerond)]?.removeFirstOrNull()?.uuid
                ?: klantcontactService.create(
                    NewContactmoment(
                        planUuid = planUuid,
                        evaluatieType = evaluatieType,
                        plaatsgevondenOp = ContactmomentService.plaatsgevondenOp(datum),
                        afgerond = afgerond,
                        verslag = cm.notitie
                    )
                )
            if (!contactmomentDetailsRepository.existsById(uuid)) {
                contactmomentDetailsRepository.save(
                    ContactmomentDetails(
                        contactmomentUuid = uuid,
                        planUuid = planUuid,
                        evaluatieType = evaluatieType,
                        geplandeDatum = datum,
                        deelnemers = cm.deelnemers,
                        actiepunten = cm.actiepunten?.takeIf { it.isNotEmpty() }?.let { objectMapper.writeValueAsString(it) }
                    )
                )
            }
        }
    }

    /** Betrokkenen (local contact cards), all in one transaction; only the first flagged one is the procesbegeleider. */
    private fun ensureBetrokkenen(intake: Intake, planUuid: UUID, procesbegeleider: IntakeBetrokkene?) {
        val betrokkenen = intake.request.betrokkenen
        if (betrokkenen.isEmpty()) return
        transactionTemplate.executeWithoutResult {
            if (involvedPartyRepository.findByPlanUuid(planUuid).isEmpty()) {
                involvedPartyRepository.saveAll(
                    betrokkenen.map { b ->
                        InvolvedParty(
                            planUuid = planUuid,
                            name = b.naam.trim(),
                            role = b.rol,
                            email = b.email,
                            organization = b.organisatie,
                            isPrimary = b === procesbegeleider
                        )
                    }
                )
            }
        }
    }

    /**
     * One verzoek per intake instrument row, now that plan and plan dossier
     * exist. Per subdoel and product only the rows not yet covered by a
     * verzoek (from an earlier attempt) are filed, so a retry never files
     * twice. A business refusal becomes a warning; a temporary failure fails
     * the call, and the retry files what is missing. [renewClaim] renews the
     * claim before each verzoek. Returns the number of rows covered by a
     * verzoek.
     */
    private fun ensureVerzoeken(intake: Intake, subdoelUuids: List<UUID>, warnings: MutableList<String>, renewClaim: () -> Unit): Int {
        val rijen = intake.request.instrumenten.mapIndexedNotNull { i, instrument ->
            val doelIndex = intake.instrumentDoelen[i] ?: return@mapIndexedNotNull null
            subdoelUuids[doelIndex] to instrument.product!!.trim()
        }
        var gedekt = 0
        rijen.groupBy { it }.forEach { (sleutel, groep) ->
            val (doelUuid, code) = sleutel
            val urn = PdcaUrn.producttype(code).toString()
            val al = instrumentAanvraagService.verzoekenVoor(doelUuid).count { it.productUrn == urn }
            gedekt += minOf(al, groep.size)
            repeat((groep.size - al).coerceAtLeast(0)) {
                renewClaim()
                try {
                    instrumentAanvraagService.startByCode(doelUuid, code)
                    gedekt++
                } catch (e: ResponseStatusException) {
                    if (e.statusCode.is5xxServerError) throw e
                    warnings += "Instrument '$code' niet aangevraagd: ${e.reason ?: e.statusCode}"
                }
            }
        }
        return gedekt
    }
}
