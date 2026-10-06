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

package com.ritense.pdca.web.rest

import com.ritense.pdca.domain.ContactmomentDetails
import com.ritense.pdca.domain.DoelDetails
import com.ritense.pdca.domain.InstrumentDetails
import com.ritense.pdca.domain.PlanDetails
import com.ritense.pdca.domain.UitvoeringsStatus
import com.ritense.pdca.repository.ActionRepository
import com.ritense.pdca.repository.ContactmomentDetailsRepository
import com.ritense.pdca.repository.DoelDetailsRepository
import com.ritense.pdca.repository.InstrumentDetailsRepository
import com.ritense.pdca.repository.InvolvedPartyRepository
import com.ritense.pdca.repository.PlanDetailsRepository
import com.ritense.pdca.service.PhaseConfigService
import com.ritense.pdca.web.rest.dto.ContactmomentDetailsRequest
import com.ritense.pdca.web.rest.dto.DoelDetailsRequest
import com.ritense.pdca.web.rest.dto.InstrumentDetailsRequest
import com.ritense.pdca.web.rest.dto.PlanDetailsRequest
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDateTime
import java.util.UUID

/**
 * PDCA process overlay keyed by Open Plan uuids. Plans, doelen, instrumenten,
 * contactmomenten, personen, doeltypen, relatietypen and producttypen are
 * consumed directly from Open Plan / Open Product through the
 * [com.ritense.pdca.registers.RegisterProxyController]; this resource only
 * stores what those registers do not model (uitvoeringsstatus, voortgang,
 * weergavestatus, uren/effectiviteit, evaluatietype, doelvoortgang,
 * actiepunten) plus the GZAC case link.
 *
 * The DELETE endpoints clean up local overlay rows after the caller removed
 * the register resource through the proxy.
 */
@RestController
@Transactional
@RequestMapping("/api/v1/pdca", produces = [MediaType.APPLICATION_JSON_VALUE])
class PdcaResource(
    private val planDetailsRepository: PlanDetailsRepository,
    private val doelDetailsRepository: DoelDetailsRepository,
    private val contactmomentDetailsRepository: ContactmomentDetailsRepository,
    private val instrumentDetailsRepository: InstrumentDetailsRepository,
    private val actionRepository: ActionRepository,
    private val involvedPartyRepository: InvolvedPartyRepository,
    private val phaseConfigService: PhaseConfigService
) {

    // --------------------------------------------------------- plandetails

    @GetMapping("/plandetails")
    fun listPlanDetails(
        @RequestParam(name = "caseDefinitionKey", required = false) caseDefinitionKey: String?
    ): List<PlanDetails> =
        if (caseDefinitionKey != null) planDetailsRepository.findByCaseDefinitionKey(caseDefinitionKey)
        else planDetailsRepository.findAll()

    @GetMapping("/plandetails/{planUuid}")
    fun getPlanDetails(@PathVariable(name = "planUuid") planUuid: UUID): PlanDetails =
        planDetailsRepository.findById(planUuid).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "No plandetails for plan $planUuid")
        }

    @PutMapping("/plandetails/{planUuid}")
    fun upsertPlanDetails(
        @PathVariable(name = "planUuid") planUuid: UUID,
        @Valid @RequestBody request: PlanDetailsRequest
    ): PlanDetails {
        request.caseDefinitionKey?.let { key ->
            phaseConfigService.findByCaseDefinitionKey(key)
                ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "No PhaseConfig found for caseDefinitionKey: $key")
        }
        val existing = planDetailsRepository.findById(planUuid).orElse(null)
        val configKey = existing?.caseDefinitionKey ?: request.caseDefinitionKey
        request.weergaveStatus?.let { validateWeergaveStatus(configKey, it) }
        request.beginPositie?.let { validatePositie(configKey, "beginPositie", it) }

        val details = if (existing == null) {
            PlanDetails(
                planUuid = planUuid,
                persoonUuid = request.persoonUuid
                    ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "persoonUuid is required when creating plandetails"),
                caseDefinitionKey = request.caseDefinitionKey,
                weergaveStatus = request.weergaveStatus,
                beginPositie = request.beginPositie,
                subdoelgroep = request.subdoelgroep,
                hoofddoelTypeUuid = request.hoofddoelTypeUuid,
                streefEinddatum = request.streefEinddatum
            )
        } else {
            existing.apply {
                caseDefinitionKey = request.caseDefinitionKey ?: caseDefinitionKey
                weergaveStatus = request.weergaveStatus ?: weergaveStatus
                beginPositie = request.beginPositie ?: beginPositie
                subdoelgroep = request.subdoelgroep ?: subdoelgroep
                hoofddoelTypeUuid = request.hoofddoelTypeUuid ?: hoofddoelTypeUuid
                streefEinddatum = request.streefEinddatum ?: streefEinddatum
                updatedAt = LocalDateTime.now()
            }
        }
        return planDetailsRepository.save(details)
    }

    /** Removes all local overlay data of a plan (call after deleting the plan in Open Plan). */
    @DeleteMapping("/plandetails/{planUuid}")
    fun deletePlanDetails(@PathVariable(name = "planUuid") planUuid: UUID): ResponseEntity<Unit> {
        val doelUuids = doelDetailsRepository.findByPlanUuid(planUuid).map { it.doelUuid }
        actionRepository.deleteAll(actionRepository.findByDoelUuidIn(doelUuids))
        doelDetailsRepository.deleteByPlanUuid(planUuid)
        contactmomentDetailsRepository.deleteByPlanUuid(planUuid)
        instrumentDetailsRepository.deleteByPlanUuid(planUuid)
        involvedPartyRepository.deleteByPlanUuid(planUuid)
        planDetailsRepository.deleteById(planUuid)
        return ResponseEntity.noContent().build()
    }

    // --------------------------------------------------------- doeldetails

    @GetMapping("/doeldetails")
    fun listDoelDetails(@RequestParam(name = "planUuid") planUuid: UUID): List<DoelDetails> =
        doelDetailsRepository.findByPlanUuid(planUuid).sortedBy { it.sortering }

    @PutMapping("/doeldetails/{doelUuid}")
    fun upsertDoelDetails(
        @PathVariable(name = "doelUuid") doelUuid: UUID,
        @Valid @RequestBody request: DoelDetailsRequest
    ): DoelDetails {
        val existing = doelDetailsRepository.findById(doelUuid).orElse(null)
        val planUuid = existing?.planUuid ?: request.planUuid
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "planUuid is required when creating doeldetails")

        val details = if (existing == null) {
            val sortering = request.sortering
                ?: ((doelDetailsRepository.findByPlanUuid(planUuid).maxOfOrNull { it.sortering } ?: -1) + 1)
            DoelDetails(
                doelUuid = doelUuid,
                planUuid = planUuid,
                uitvoeringsStatus = request.uitvoeringsStatus ?: UitvoeringsStatus.GEPLAND,
                voortgangScore = request.voortgangScore,
                voortgangToelichting = request.voortgangToelichting,
                sortering = sortering
            )
        } else {
            existing.apply {
                uitvoeringsStatus = request.uitvoeringsStatus ?: uitvoeringsStatus
                voortgangScore = request.voortgangScore ?: voortgangScore
                voortgangToelichting = request.voortgangToelichting ?: voortgangToelichting
                sortering = request.sortering ?: sortering
                updatedAt = LocalDateTime.now()
            }
        }
        return doelDetailsRepository.save(details)
    }

    /** Removes local overlay data of a doel (call after deleting the doel in Open Plan). */
    @DeleteMapping("/doeldetails/{doelUuid}")
    fun deleteDoelDetails(@PathVariable(name = "doelUuid") doelUuid: UUID): ResponseEntity<Unit> {
        actionRepository.deleteAll(actionRepository.findByDoelUuid(doelUuid))
        doelDetailsRepository.deleteById(doelUuid)
        return ResponseEntity.noContent().build()
    }

    // --------------------------------------------------- instrumentdetails

    @GetMapping("/instrumentdetails")
    fun listInstrumentDetails(@RequestParam(name = "planUuid") planUuid: UUID): List<InstrumentDetails> =
        instrumentDetailsRepository.findByPlanUuid(planUuid)

    @PutMapping("/instrumentdetails/{instrumentUuid}")
    fun upsertInstrumentDetails(
        @PathVariable(name = "instrumentUuid") instrumentUuid: UUID,
        @Valid @RequestBody request: InstrumentDetailsRequest
    ): InstrumentDetails {
        val existing = instrumentDetailsRepository.findById(instrumentUuid).orElse(null)
        val planUuid = existing?.planUuid ?: request.planUuid
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "planUuid is required when creating instrumentdetails")

        val details = if (existing == null) {
            InstrumentDetails(
                instrumentUuid = instrumentUuid,
                planUuid = planUuid,
                urenBesteed = request.urenBesteed,
                effectiviteitScore = request.effectiviteitScore,
                effectiviteitToelichting = request.effectiviteitToelichting,
                afbreekReden = request.afbreekReden
            )
        } else {
            existing.apply {
                urenBesteed = request.urenBesteed ?: urenBesteed
                effectiviteitScore = request.effectiviteitScore ?: effectiviteitScore
                effectiviteitToelichting = request.effectiviteitToelichting ?: effectiviteitToelichting
                afbreekReden = request.afbreekReden ?: afbreekReden
                updatedAt = LocalDateTime.now()
            }
        }
        return instrumentDetailsRepository.save(details)
    }

    @DeleteMapping("/instrumentdetails/{instrumentUuid}")
    fun deleteInstrumentDetails(@PathVariable(name = "instrumentUuid") instrumentUuid: UUID): ResponseEntity<Unit> {
        instrumentDetailsRepository.deleteById(instrumentUuid)
        return ResponseEntity.noContent().build()
    }

    // ------------------------------------------------ contactmomentdetails

    @GetMapping("/contactmomentdetails")
    fun listContactmomentDetails(@RequestParam(name = "planUuid") planUuid: UUID): List<ContactmomentDetails> =
        contactmomentDetailsRepository.findByPlanUuid(planUuid)

    @PutMapping("/contactmomentdetails/{contactmomentUuid}")
    fun upsertContactmomentDetails(
        @PathVariable(name = "contactmomentUuid") contactmomentUuid: UUID,
        @Valid @RequestBody request: ContactmomentDetailsRequest
    ): ContactmomentDetails {
        val existing = contactmomentDetailsRepository.findById(contactmomentUuid).orElse(null)
        val planUuid = existing?.planUuid ?: request.planUuid
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "planUuid is required when creating contactmomentdetails")
        request.evaluatieType?.let { validateEvaluatieType(planUuid, it) }

        val details = if (existing == null) {
            ContactmomentDetails(
                contactmomentUuid = contactmomentUuid,
                planUuid = planUuid,
                evaluatieType = request.evaluatieType
                    ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "evaluatieType is required when creating contactmomentdetails"),
                geplandeDatum = request.geplandeDatum,
                deelnemers = request.deelnemers,
                doelVoortgang = request.doelVoortgang,
                actiepunten = request.actiepunten
            )
        } else {
            existing.apply {
                evaluatieType = request.evaluatieType ?: evaluatieType
                geplandeDatum = request.geplandeDatum ?: geplandeDatum
                deelnemers = request.deelnemers ?: deelnemers
                doelVoortgang = request.doelVoortgang ?: doelVoortgang
                actiepunten = request.actiepunten ?: actiepunten
                updatedAt = LocalDateTime.now()
            }
        }
        return contactmomentDetailsRepository.save(details)
    }

    @DeleteMapping("/contactmomentdetails/{contactmomentUuid}")
    fun deleteContactmomentDetails(@PathVariable(name = "contactmomentUuid") contactmomentUuid: UUID): ResponseEntity<Unit> {
        contactmomentDetailsRepository.deleteById(contactmomentUuid)
        return ResponseEntity.noContent().build()
    }

    // ------------------------------------------------------------- helpers

    private fun validateWeergaveStatus(caseDefinitionKey: String?, weergaveStatus: String) {
        if (caseDefinitionKey == null) return
        val validStatussen = phaseConfigService.getPlanStatussen(caseDefinitionKey) ?: return
        if (weergaveStatus !in validStatussen) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Invalid weergaveStatus '$weergaveStatus'. Valid: $validStatussen"
            )
        }
    }

    /** Positions are typed: the value must come from the domein's positietype register. */
    private fun validatePositie(caseDefinitionKey: String?, field: String, positie: String) {
        if (caseDefinitionKey == null) return
        val validPosities = phaseConfigService.getPositieTypen(caseDefinitionKey) ?: return
        if (positie !in validPosities) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Invalid $field '$positie'. Valid positietypen: $validPosities"
            )
        }
    }

    private fun validateEvaluatieType(planUuid: UUID, evaluatieType: String) {
        val caseDefinitionKey = planDetailsRepository.findById(planUuid).orElse(null)?.caseDefinitionKey ?: return
        val validTypes = phaseConfigService.getEvaluationTypes(caseDefinitionKey)
        if (evaluatieType !in validTypes) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid evaluatieType '$evaluatieType'. Valid types: $validTypes")
        }
    }
}
