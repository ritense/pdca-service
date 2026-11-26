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

import com.ritense.pdca.domain.Action
import com.ritense.pdca.domain.ActionStatus
import com.ritense.pdca.domain.isTaakBeheerd
import com.ritense.pdca.registers.openplan.OpenPlanGateway
import com.ritense.pdca.repository.ActionRepository
import com.ritense.pdca.repository.DoelDetailsRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * The acties of a plan (ARCHITECTURE §6.8). A new actie becomes a GZAC task
 * ([ActieTaakService]); from then on that task ends it, and its status and
 * what the task shows (title, description, deadline) no longer change here.
 * Acties from before that have no task keep the status steps of the plan
 * page.
 */
@Service
@Transactional
class ActionService(
    private val actionRepository: ActionRepository,
    private val doelDetailsRepository: DoelDetailsRepository,
    private val openPlanGateway: OpenPlanGateway,
    private val actieTaakService: ActieTaakService
) {

    fun getById(id: UUID): Action {
        return actionRepository.findById(id)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Actie niet gevonden") }
    }

    fun findByDoelUuid(doelUuid: UUID): List<Action> {
        return actionRepository.findByDoelUuid(doelUuid)
    }

    /** The acties under the doelen of a plan (as far as the overlay knows them). */
    fun findByPlan(planUuid: UUID): List<Action> =
        actionRepository.findByDoelUuidIn(doelDetailsRepository.findByPlanUuid(planUuid).map { it.doelUuid })

    /**
     * Saves a new actie and starts its GZAC task; the instrument it supports,
     * when it names one, must be an instrument of its doel (400 otherwise).
     * When the task cannot be started the actie is removed again and the
     * failure stands. The register and GZAC calls run outside a transaction;
     * the save has its own.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun create(action: Action): Action {
        action.instrumentUuid?.let { instrumentUuid ->
            val instrument = openPlanGateway.findInstrument(instrumentUuid)
            if (instrument == null || instrument.doelen.none { it.uuid == action.doelUuid }) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Het gekozen instrument hoort niet bij het doel van de actie")
            }
        }
        return actieTaakService.start(actionRepository.save(action))
    }

    /**
     * Saves the edited fields of an actie. A status change through an edit
     * only moves the work forward (PLANNED → IN_PROGRESS → PENDING_REVIEW);
     * leaving PENDING_REVIEW is [approve] or [reject], and a completed or
     * rejected actie keeps its status. An actie with a GZAC task keeps its
     * status, result and what its task shows (409): GZAC cannot rename a
     * task or follow such an edit.
     */
    fun update(id: UUID, updated: Action): Action {
        val existing = getById(id)
        if (existing.isTaakBeheerd && taakVelden(existing) != taakVelden(updated)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, TAAK_VELDEN_VAST)
        }
        if (updated.status != existing.status && existing.status to updated.status !in EDIT_TRANSITIONS) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                when (existing.status) {
                    ActionStatus.PENDING_REVIEW -> "Een actie ter beoordeling wordt goedgekeurd of afgekeurd"
                    ActionStatus.COMPLETED, ActionStatus.REJECTED, ActionStatus.CANCELLED -> "Een afgeronde, afgekeurde of vervallen actie verandert niet meer van status"
                    else -> "Een actie gaat van gepland naar gestart naar ter beoordeling"
                }
            )
        }
        existing.title = updated.title
        existing.description = updated.description
        existing.status = updated.status
        existing.assigneeName = updated.assigneeName
        existing.priority = updated.priority
        existing.startDate = updated.startDate
        existing.dueDate = updated.dueDate
        existing.completedDate = updated.completedDate
        existing.result = updated.result
        existing.updatedAt = LocalDateTime.now()
        return actionRepository.save(existing)
    }

    fun approve(id: UUID): Action {
        val action = getById(id)
        weigerBijTaak(action)
        if (action.status != ActionStatus.PENDING_REVIEW) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Alleen een actie ter beoordeling kan worden goedgekeurd")
        }
        action.status = ActionStatus.COMPLETED
        action.completedDate = LocalDate.now()
        action.updatedAt = LocalDateTime.now()
        return actionRepository.save(action)
    }

    fun reject(id: UUID): Action {
        val action = getById(id)
        weigerBijTaak(action)
        if (action.status != ActionStatus.PENDING_REVIEW) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Alleen een actie ter beoordeling kan worden afgekeurd")
        }
        action.status = ActionStatus.REJECTED
        action.updatedAt = LocalDateTime.now()
        return actionRepository.save(action)
    }

    private fun weigerBijTaak(action: Action) {
        if (action.isTaakBeheerd) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Deze actie is een taak in GZAC en wordt afgerond door die taak af te ronden")
        }
    }

    /** What the GZAC task of an actie shows or decides. */
    private fun taakVelden(action: Action) =
        listOf(action.title, action.description, action.dueDate, action.status, action.completedDate, action.result)

    private companion object {
        const val TAAK_VELDEN_VAST =
            "Deze actie is een taak in GZAC: titel, beschrijving, deadline, status en resultaat liggen vast in die taak"

        /** The status moves an edit may make; the review outcome has its own endpoints. */
        val EDIT_TRANSITIONS = setOf(
            ActionStatus.PLANNED to ActionStatus.IN_PROGRESS,
            ActionStatus.IN_PROGRESS to ActionStatus.PENDING_REVIEW
        )
    }
}
