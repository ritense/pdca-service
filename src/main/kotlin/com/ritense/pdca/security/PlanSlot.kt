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
import com.ritense.pdca.repository.PlanDetailsRepository
import com.ritense.pdca.service.PhaseConfigService
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
 * its subdoelen (adding, removing, aborting, completing, their toelichting
 * and beoogde einddatum), the plan's toelichting bij het hoofddoel and
 * beoogde einddatum, its hoofddoel and its instrument requests change only
 * in an evaluation the user runs on that plan, so the inwoner is informed
 * and the change is recorded with its reason. Moving the display status
 * back to concept is locked as well, else the lock would be one click away.
 *
 * The concept status is the first display status of the plan's case type
 * (the status a new plan gets), "Concept" when the case type has none; a
 * plan without display status is in concept. [DataAccessPolicy] marks the
 * routes that make such changes; `/data` checks them after authorization.
 */
@Component
class PlanSlot(
    private val planDetailsRepository: PlanDetailsRepository,
    private val evaluationSessionRepository: EvaluationSessionRepository,
    private val phaseConfigService: PhaseConfigService
) {

    /** Throws 409 unless [login] may make every one of [wijzigingen] now. */
    fun controleer(login: String, wijzigingen: List<SlotWijziging>) {
        if (wijzigingen.isEmpty()) return
        val evaluatiePlan by lazy {
            evaluationSessionRepository.findByUserLoginAndStatus(login, EvaluationSessionStatus.RUNNING)?.planUuid
        }
        wijzigingen.forEach { wijziging ->
            val concept = conceptStatus(wijziging.planUuid) ?: return@forEach
            if (wijziging.weergaveStatus != null && !wijziging.weergaveStatus.equals(concept.status, ignoreCase = true)) return@forEach
            if (concept.inConcept || evaluatiePlan == wijziging.planUuid) return@forEach
            throw ResponseStatusException(HttpStatus.CONFLICT, VERGRENDELD)
        }
    }

    private data class Concept(val status: String, val inConcept: Boolean)

    /** The plan's concept status and whether it is in it; null for a plan without overlay row (nothing to lock). */
    private fun conceptStatus(planUuid: UUID): Concept? {
        val details = planDetailsRepository.findById(planUuid).orElse(null) ?: return null
        val status = details.caseDefinitionKey?.let { phaseConfigService.getPlanStatussen(it)?.firstOrNull() } ?: STANDAARD_CONCEPT
        val huidig = details.weergaveStatus?.takeIf { it.isNotBlank() }
        return Concept(status, huidig == null || huidig.equals(status, ignoreCase = true))
    }

    companion object {
        /** The concept status of a case type without configured display statuses (also the UI's fallback). */
        const val STANDAARD_CONCEPT = "Concept"

        const val VERGRENDELD =
            "Dit deel van het plan is vastgesteld en verandert alleen tijdens een evaluatie; start eerst een evaluatie op het tabblad Evaluaties"
    }
}
