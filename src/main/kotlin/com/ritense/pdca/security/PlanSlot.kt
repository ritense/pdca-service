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

package com.ritense.pdca.security

import com.ritense.pdca.domain.EvaluationSessionStatus
import com.ritense.pdca.repository.EvaluationSessionRepository
import com.ritense.pdca.service.PlanConcept
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * A change a `/data` call makes to a locked part of a plan (see
 * [PlanSlot]). With [weergaveStatus] the call only sets the plan's display
 * status to that value, which is locked only when it moves the plan back to
 * its concept status.
 */
data class SlotWijziging(val planUuid: UUID, val weergaveStatus: String? = null)

/**
 * The plan lock (ARCHITECTURE §6.7): once a plan has left its concept status,
 * its subdoelen (adding, removing, aborting, completing, their toelichting,
 * startdatum and beoogde einddatum), the plan's toelichting bij het hoofddoel and
 * beoogde einddatum, its hoofddoel and its instrument requests (ingeplande
 * instrumenten included) change only in an evaluation the user runs on that
 * plan, so the inwoner is informed and the change is recorded with its
 * reason. Moving the display status
 * back to concept is locked as well, else the lock would be one click away.
 *
 * Whether a plan is in concept is [PlanConcept]'s rule. [DataAccessPolicy]
 * marks the routes that make such changes; `/data` checks them after
 * authorization.
 *
 * An evaluation that lets such a change through records that it did; it
 * can then only be completed, so the change is not left without the
 * conversation that informs the inwoner.
 */
@Component
class PlanSlot(
    private val planConcept: PlanConcept,
    private val evaluationSessionRepository: EvaluationSessionRepository
) {

    /**
     * Throws 409 unless [login] may make every one of [wijzigingen] now. A
     * change the user's evaluation unlocks is recorded on that evaluation
     * first; when it has just ended, the change is refused after all.
     */
    fun controleer(login: String, wijzigingen: List<SlotWijziging>) {
        if (wijzigingen.isEmpty()) return
        val evaluatie by lazy { evaluationSessionRepository.findByUserLoginAndStatus(login, EvaluationSessionStatus.RUNNING) }
        var ontgrendeldDoor: UUID? = null
        wijzigingen.forEach { wijziging ->
            val concept = planConcept.van(wijziging.planUuid) ?: return@forEach
            if (wijziging.weergaveStatus != null && !wijziging.weergaveStatus.equals(concept.status, ignoreCase = true)) return@forEach
            if (concept.inConcept) return@forEach
            val sessie = evaluatie?.takeIf { it.planUuid == wijziging.planUuid }
                ?: throw ResponseStatusException(HttpStatus.CONFLICT, VERGRENDELD)
            ontgrendeldDoor = sessie.id
        }
        ontgrendeldDoor?.let { sessieId ->
            if (evaluationSessionRepository.markeerVastgesteldGewijzigd(sessieId) == 0) {
                throw ResponseStatusException(HttpStatus.CONFLICT, VERGRENDELD)
            }
        }
    }

    companion object {
        const val VERGRENDELD =
            "Dit deel van het plan is vastgesteld en verandert alleen tijdens een evaluatie; start eerst een evaluatie op het tabblad Evaluaties"
    }
}
