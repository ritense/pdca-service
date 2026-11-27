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

import com.ritense.pdca.repository.PlanDetailsRepository
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Whether a plan is still in concept (ARCHITECTURE §6.7): the concept status
 * is the first display status of the plan's case type (the status a new
 * plan gets), [STANDAARD_CONCEPT] when the case type has none; a plan
 * without display status is in concept. Concept decides the plan lock and
 * whether instruments may be requested (§6.2).
 */
@Component
class PlanConcept(
    private val planDetailsRepository: PlanDetailsRepository,
    private val phaseConfigService: PhaseConfigService
) {

    /** A plan's concept status and whether the plan is in it. */
    data class Concept(val status: String, val inConcept: Boolean)

    /** The plan's concept status and whether it is in it; null for a plan without overlay row. */
    fun van(planUuid: UUID): Concept? {
        val details = planDetailsRepository.findById(planUuid).orElse(null) ?: return null
        val status = details.caseDefinitionKey?.let { phaseConfigService.getPlanStatussen(it)?.firstOrNull() } ?: STANDAARD_CONCEPT
        val huidig = details.weergaveStatus?.takeIf { it.isNotBlank() }
        return Concept(status, huidig == null || huidig.equals(status, ignoreCase = true))
    }

    /** Whether the plan is in concept; a plan without overlay row is not. */
    fun inConcept(planUuid: UUID): Boolean = van(planUuid)?.inConcept == true

    companion object {
        /** The concept status of a case type without configured display statuses (also the UI's fallback). */
        const val STANDAARD_CONCEPT = "Concept"
    }
}
