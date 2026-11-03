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

import com.ritense.pdca.domain.ContactmomentDetails
import com.ritense.pdca.domain.DoelDetails
import com.ritense.pdca.domain.InstrumentDetails
import com.ritense.pdca.domain.PlanDetails
import com.ritense.pdca.domain.UitvoeringsStatus
import com.ritense.pdca.domain.VoortgangStatusOptie
import com.ritense.pdca.registers.openplan.OpenPlanGateway
import com.ritense.pdca.repository.ActionRepository
import com.ritense.pdca.repository.ContactmomentDetailsRepository
import com.ritense.pdca.repository.DoelDetailsRepository
import com.ritense.pdca.repository.EvaluationChangeRepository
import com.ritense.pdca.repository.EvaluationSessionRepository
import com.ritense.pdca.repository.InstrumentDetailsRepository
import com.ritense.pdca.repository.InvolvedPartyRepository
import com.ritense.pdca.repository.PlanDetailsRepository
import com.ritense.pdca.web.rest.dto.DoelDetailsRequest
import com.ritense.pdca.web.rest.dto.InstrumentDetailsRequest
import com.ritense.pdca.web.rest.dto.PlanDetailsRequest
import com.ritense.pdca.web.rest.dto.patch
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDateTime
import java.util.UUID

/**
 * The PDCA overlay rows per register object (plan, doel, instrument,
 * contactmoment): partial updates (absent fields keep their value, an
 * explicit null clears it), the checks against the plan's case
 * configuration, and the cleanup after a register object is deleted.
 *
 * A new doel, instrument or contactmoment row names its plan; that plan is
 * checked against the register object itself (the doel's plannen, the
 * instrument's doel, the klantcontact's onderwerpobject), so a row can never
 * be filed under another plan. Those reads, and the check that a doel still
 * runs before it gets a voortgangsstatus, happen before — not inside — the
 * transaction that writes the row. Plan rows are only created by the intake
 * (ARCHITECTURE §6.1); here they are edited.
 */
@Service
class PlanOverlayService(
    private val planDetailsRepository: PlanDetailsRepository,
    private val doelDetailsRepository: DoelDetailsRepository,
    private val contactmomentDetailsRepository: ContactmomentDetailsRepository,
    private val instrumentDetailsRepository: InstrumentDetailsRepository,
    private val actionRepository: ActionRepository,
    private val involvedPartyRepository: InvolvedPartyRepository,
    private val evaluationSessionRepository: EvaluationSessionRepository,
    private val evaluationChangeRepository: EvaluationChangeRepository,
    private val phaseConfigService: PhaseConfigService,
    private val openPlanGateway: OpenPlanGateway,
    private val transactionTemplate: TransactionTemplate
) {

    // --------------------------------------------------------- plandetails

    fun getPlanDetails(planUuid: UUID): PlanDetails =
        planDetailsRepository.findById(planUuid).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Geen plandetails voor plan $planUuid")
        }

    @Transactional
    fun updatePlanDetails(planUuid: UUID, request: PlanDetailsRequest): PlanDetails {
        val details = getPlanDetails(planUuid)
        val configKey = details.caseDefinitionKey
        request.weergaveStatus?.orElse(null)?.let { validateWeergaveStatus(configKey, it) }
        request.beginPositie?.orElse(null)?.let { validatePositie(configKey, "positie", it) }
        details.weergaveStatus = request.weergaveStatus.patch(details.weergaveStatus)
        details.beginPositie = request.beginPositie.patch(details.beginPositie)
        details.subdoelgroep = request.subdoelgroep.patch(details.subdoelgroep)?.takeIf { it.isNotBlank() }
        details.streefEinddatum = request.streefEinddatum.patch(details.streefEinddatum)
        details.updatedAt = LocalDateTime.now()
        return planDetailsRepository.save(details)
    }

    /**
     * Removes all local overlay data of a plan (called after the plan is
     * deleted in Open Plan): the rows per doel, instrument and contactmoment,
     * acties, betrokkenen, and the evaluations run on the plan with their
     * recorded changes.
     */
    @Transactional
    fun deletePlanDetails(planUuid: UUID) {
        val doelUuids = doelDetailsRepository.findByPlanUuid(planUuid).map { it.doelUuid }
        actionRepository.deleteAll(actionRepository.findByDoelUuidIn(doelUuids))
        doelDetailsRepository.deleteByPlanUuid(planUuid)
        contactmomentDetailsRepository.deleteByPlanUuid(planUuid)
        instrumentDetailsRepository.deleteByPlanUuid(planUuid)
        involvedPartyRepository.deleteByPlanUuid(planUuid)
        val sessies = evaluationSessionRepository.findByPlanUuid(planUuid)
        if (sessies.isNotEmpty()) {
            evaluationChangeRepository.deleteBySessionIdIn(sessies.map { it.id })
            evaluationSessionRepository.deleteAll(sessies)
        }
        planDetailsRepository.deleteById(planUuid)
    }

    // --------------------------------------------------------- doeldetails

    /** The voortgangsstatussen a doel of this plan can get (its case type's list, or the standard list). */
    fun voortgangStatussen(planUuid: UUID?): List<VoortgangStatusOptie> =
        phaseConfigService.getVoortgangStatussen(planUuid?.let { caseDefinitionKeyVan(it) })

    fun listDoelDetails(planUuid: UUID): List<DoelDetails> =
        doelDetailsRepository.findByPlanUuid(planUuid).sortedBy { it.sortering }

    fun upsertDoelDetails(doelUuid: UUID, request: DoelDetailsRequest): DoelDetails {
        val existing = doelDetailsRepository.findById(doelUuid).orElse(null)
        val planUuid = existing?.planUuid ?: request.planUuid
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "planUuid is verplicht bij nieuwe doeldetails")
        if (existing == null) {
            val doel = openPlanGateway.findDoel(doelUuid)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Doel niet gevonden in Open Plan")
            requireSamePlan(planUuid, doel.plannen.map { it.uuid }, "dit doel")
        }
        request.voortgangStatus?.orElse(null)
            ?.takeIf { it != existing?.voortgangStatus }
            ?.let { validateVoortgangStatus(planUuid, doelUuid, it) }

        return transactionTemplate.execute {
            val current = doelDetailsRepository.findById(doelUuid).orElse(null)
                ?: DoelDetails(
                    doelUuid = doelUuid,
                    planUuid = planUuid,
                    uitvoeringsStatus = UitvoeringsStatus.GEPLAND,
                    sortering = request.sortering
                        ?: ((doelDetailsRepository.findByPlanUuid(planUuid).maxOfOrNull { it.sortering } ?: -1) + 1)
                )
            current.apply {
                uitvoeringsStatus = request.uitvoeringsStatus ?: uitvoeringsStatus
                voortgangToelichting = request.voortgangToelichting.patch(voortgangToelichting)
                voortgangStatus = request.voortgangStatus.patch(voortgangStatus)
                interneNotitie = request.interneNotitie.patch(interneNotitie)
                externeNotitie = request.externeNotitie.patch(externeNotitie)
                sortering = request.sortering ?: sortering
                updatedAt = LocalDateTime.now()
            }
            doelDetailsRepository.save(current)
        }!!
    }

    /** Removes local overlay data of a doel (called after the doel is deleted in Open Plan). */
    @Transactional
    fun deleteDoelDetails(doelUuid: UUID) {
        actionRepository.deleteAll(actionRepository.findByDoelUuid(doelUuid))
        doelDetailsRepository.deleteById(doelUuid)
    }

    // --------------------------------------------------- instrumentdetails

    fun listInstrumentDetails(planUuid: UUID): List<InstrumentDetails> = instrumentDetailsRepository.findByPlanUuid(planUuid)

    fun upsertInstrumentDetails(instrumentUuid: UUID, request: InstrumentDetailsRequest): InstrumentDetails {
        request.effectiviteitScore?.orElse(null)?.let {
            if (it !in 1..5) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "De effectiviteit is een score van 1 tot en met 5")
        }
        request.urenBesteed?.orElse(null)?.let {
            if (it < 0) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Het aantal bestede uren kan niet negatief zijn")
        }
        val existing = instrumentDetailsRepository.findById(instrumentUuid).orElse(null)
        val planUuid = existing?.planUuid ?: request.planUuid
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "planUuid is verplicht bij nieuwe instrumentdetails")
        if (existing == null) {
            val instrument = openPlanGateway.findInstrument(instrumentUuid)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Instrument niet gevonden in Open Plan")
            val plannen = instrument.doelen.flatMap { ref ->
                doelDetailsRepository.findById(ref.uuid).orElse(null)?.planUuid?.let { listOf(it) }
                    ?: openPlanGateway.getDoel(ref.uuid).plannen.map { it.uuid }
            }
            requireSamePlan(planUuid, plannen, "dit instrument")
        }

        return transactionTemplate.execute {
            val current = instrumentDetailsRepository.findById(instrumentUuid).orElse(null)
                ?: InstrumentDetails(instrumentUuid = instrumentUuid, planUuid = planUuid)
            current.apply {
                urenBesteed = request.urenBesteed.patch(urenBesteed)
                effectiviteitScore = request.effectiviteitScore.patch(effectiviteitScore)
                effectiviteitToelichting = request.effectiviteitToelichting.patch(effectiviteitToelichting)
                interneNotitie = request.interneNotitie.patch(interneNotitie)
                externeNotitie = request.externeNotitie.patch(externeNotitie)
                updatedAt = LocalDateTime.now()
            }
            instrumentDetailsRepository.save(current)
        }!!
    }

    @Transactional
    fun deleteInstrumentDetails(instrumentUuid: UUID) {
        instrumentDetailsRepository.deleteById(instrumentUuid)
    }

    // ------------------------------------------------ contactmomentdetails

    fun listContactmomentDetails(planUuid: UUID): List<ContactmomentDetails> =
        contactmomentDetailsRepository.findByPlanUuid(planUuid)

    // ------------------------------------------------------------- checks

    /** The plan a client names for a new row must be the plan of the register object. */
    private fun requireSamePlan(planUuid: UUID, registerPlannen: List<UUID>, wat: String) {
        if (planUuid !in registerPlannen) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Plan $planUuid is niet het plan van $wat")
        }
    }

    fun caseDefinitionKeyVan(planUuid: UUID): String? =
        planDetailsRepository.findById(planUuid).orElse(null)?.caseDefinitionKey

    /** The evaluation type must be one of the plan's case type; a plan without case type accepts any. */
    fun validateEvaluatieType(planUuid: UUID, evaluatieType: String) {
        val caseDefinitionKey = caseDefinitionKeyVan(planUuid) ?: return
        val validTypes = phaseConfigService.getEvaluationTypes(caseDefinitionKey)
        if (evaluatieType !in validTypes) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST, "Onbekend evaluatietype '$evaluatieType'. Kies uit: ${validTypes.joinToString()}"
            )
        }
    }

    private fun validateWeergaveStatus(caseDefinitionKey: String?, weergaveStatus: String) {
        if (caseDefinitionKey == null) return
        val validStatussen = phaseConfigService.getPlanStatussen(caseDefinitionKey) ?: return
        if (weergaveStatus !in validStatussen) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST, "Onbekende planstatus '$weergaveStatus'. Kies uit: ${validStatussen.joinToString()}"
            )
        }
    }

    /** Positions are typed: the value must come from the domein's positietype register. */
    private fun validatePositie(caseDefinitionKey: String?, field: String, positie: String) {
        if (caseDefinitionKey == null) return
        val validPosities = phaseConfigService.getPositieTypen(caseDefinitionKey) ?: return
        if (positie !in validPosities) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST, "Onbekende $field '$positie'. Kies uit: ${validPosities.joinToString()}"
            )
        }
    }

    /**
     * A voortgangsstatus comes from the plan's list and only applies while the
     * doel runs: once Open Plan has it afgerond or geannuleerd, the register
     * status says how it ended.
     */
    private fun validateVoortgangStatus(planUuid: UUID, doelUuid: UUID, code: String) {
        val opties = phaseConfigService.getVoortgangStatussen(caseDefinitionKeyVan(planUuid))
        if (opties.none { it.code == code }) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Onbekende voortgangsstatus '$code'. Kies uit: ${opties.joinToString { it.naam }}"
            )
        }
        val registerStatus = openPlanGateway.getDoel(doelUuid).status
        if (registerStatus != "actief") {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "Voortgang kan alleen worden vastgelegd bij een lopend doel (status in Open Plan: ${registerStatus ?: "onbekend"})"
            )
        }
    }
}
