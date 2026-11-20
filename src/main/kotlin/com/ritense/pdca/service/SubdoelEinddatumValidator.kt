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

import com.ritense.pdca.registers.openplan.OpenPlanGateway
import com.ritense.pdca.repository.DoelDetailsRepository
import com.ritense.pdca.repository.PlanDetailsRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * The rules for the beoogde einddatum of a subdoel
 * ([com.ritense.pdca.domain.DoelDetails.streefEinddatum]): when it is set or
 * changed it may not lie in the past, and it never lies after the plan's
 * beoogde einddatum ([com.ritense.pdca.domain.PlanDetails.streefEinddatum]).
 * A plan without a beoogde einddatum puts no upper bound on its subdoelen.
 *
 * The rule holds from both sides: the plan's date cannot move before the
 * date of a subdoel that is still actief in Open Plan. Closed subdoelen
 * (afgerond, geannuleerd) no longer count.
 */
@Service
class SubdoelEinddatumValidator(
    private val planDetailsRepository: PlanDetailsRepository,
    private val doelDetailsRepository: DoelDetailsRepository,
    private val openPlanGateway: OpenPlanGateway
) {

    /** Checks a new or changed beoogde einddatum of a subdoel of [planUuid]. */
    fun validateSubdoel(planUuid: UUID, streefEinddatum: LocalDate) {
        val planEinddatum = planDetailsRepository.findById(planUuid).orElse(null)?.streefEinddatum
        violation(streefEinddatum, planEinddatum, LocalDate.now())?.let {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, it)
        }
    }

    /**
     * Checks a new beoogde einddatum of plan [planUuid] against the dates of
     * its subdoelen that are still actief: it may not come before any of
     * them. Only subdoelen with a later date are looked up in Open Plan.
     */
    fun validatePlan(planUuid: UUID, planEinddatum: LocalDate) {
        val later = doelDetailsRepository.findByPlanUuid(planUuid)
            .filter { it.streefEinddatum?.isAfter(planEinddatum) == true }
            .sortedByDescending { it.streefEinddatum }
        val blokkerend = later.firstOrNull { openPlanGateway.getDoel(it.doelUuid).status == "actief" } ?: return
        throw ResponseStatusException(
            HttpStatus.CONFLICT,
            "De beoogde einddatum van het plan kan niet vóór die van een lopend subdoel liggen " +
                "(${format(blokkerend.streefEinddatum!!)}); pas eerst de subdoelen aan"
        )
    }

    companion object {
        private val DATUM = DateTimeFormatter.ofPattern("dd-MM-yyyy")

        private fun format(datum: LocalDate): String = DATUM.format(datum)

        /**
         * The user-facing reason why [streefEinddatum] is not allowed, or
         * null when it is: not before [vandaag], not after [planEinddatum]
         * (no upper bound without one).
         */
        fun violation(streefEinddatum: LocalDate, planEinddatum: LocalDate?, vandaag: LocalDate): String? = when {
            streefEinddatum.isBefore(vandaag) ->
                "De beoogde einddatum van een subdoel kan niet in het verleden liggen"
            planEinddatum != null && streefEinddatum.isAfter(planEinddatum) ->
                "De beoogde einddatum van een subdoel mag niet na de beoogde einddatum van het plan " +
                    "(${format(planEinddatum)}) liggen"
            else -> null
        }
    }
}
