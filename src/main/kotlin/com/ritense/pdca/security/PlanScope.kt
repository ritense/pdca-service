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

import com.ritense.pdca.registers.openplan.OpenPlanGateway
import com.ritense.pdca.repository.ActionRepository
import com.ritense.pdca.repository.ContactmomentDetailsRepository
import com.ritense.pdca.repository.DoelDetailsRepository
import com.ritense.pdca.repository.InstrumentDetailsRepository
import com.ritense.pdca.repository.InvolvedPartyRepository
import com.ritense.pdca.repository.PlanDetailsRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/** What a `/data` call needs from the user (see [DataAccessPolicy]). */
sealed interface Requirement {
    /** Reference data, or the user's own data (the handler filters by the verified user). */
    data object AnyUser : Requirement

    /** GZAC administrators only (`ROLE_ADMIN`). */
    data object Admin : Requirement

    /** The user must be able to read every one of these GZAC dossiers. */
    data class Dossiers(val documentIds: Set<String>) : Requirement
}

/**
 * Resolves the plan data a call addresses to the GZAC dossier(s) that guard
 * it: plan → its plan dossier (`plan_details.dossier_id`); doel, instrument,
 * contactmoment, actie and betrokkene → their plan(s) through the overlay,
 * falling back to Open Plan for a doel or instrument without overlay row. A
 * target that does not resolve to a dossier is refused (403): no dossier,
 * no access.
 */
@Component
class PlanScope(
    private val planDetailsRepository: PlanDetailsRepository,
    private val doelDetailsRepository: DoelDetailsRepository,
    private val instrumentDetailsRepository: InstrumentDetailsRepository,
    private val contactmomentDetailsRepository: ContactmomentDetailsRepository,
    private val actionRepository: ActionRepository,
    private val involvedPartyRepository: InvolvedPartyRepository,
    private val openPlanGateway: OpenPlanGateway
) {

    fun dossier(documentId: String?): Requirement.Dossiers =
        Requirement.Dossiers(setOf(documentId?.takeIf { it.isNotBlank() } ?: throw denied()))

    fun plans(planUuids: Collection<UUID>): Requirement.Dossiers {
        if (planUuids.isEmpty()) throw denied()
        return Requirement.Dossiers(
            planUuids.toSet().map { planDetailsRepository.findById(it).orElse(null)?.dossierId ?: throw denied() }.toSet()
        )
    }

    fun plansOfDoelen(doelUuids: Collection<UUID>): Set<UUID> {
        if (doelUuids.isEmpty()) throw denied()
        return doelUuids.toSet().flatMap { doelUuid ->
            doelDetailsRepository.findById(doelUuid).orElse(null)?.let { listOf(it.planUuid) }
                ?: openPlanGateway.findDoel(doelUuid)?.plannen?.map { it.uuid }?.takeIf { it.isNotEmpty() }
                ?: throw denied()
        }.toSet()
    }

    fun plansOfInstrument(instrumentUuid: UUID): Set<UUID> =
        instrumentDetailsRepository.findById(instrumentUuid).orElse(null)?.let { setOf(it.planUuid) }
            ?: openPlanGateway.findInstrument(instrumentUuid)?.doelen?.map { it.uuid }?.takeIf { it.isNotEmpty() }
                ?.let { plansOfDoelen(it) }
            ?: throw denied()

    /**
     * A new doel of [planUuid] must belong to that plan's persoon (the
     * overlay's `persoonUuid`), and its [hoofdDoel], if any, must be a doel
     * of the same plan; 403 otherwise.
     */
    fun requireNewDoelFits(planUuid: UUID, persoonUuid: UUID, hoofdDoel: UUID?) {
        val details = planDetailsRepository.findById(planUuid).orElse(null) ?: throw denied()
        if (details.persoonUuid != persoonUuid) throw denied()
        if (hoofdDoel != null && plansOfDoelen(listOf(hoofdDoel)) != setOf(planUuid)) throw denied()
    }

    /** The contactmoment's plan from its overlay row; null when it has none yet. */
    fun planOfContactmoment(contactmomentUuid: UUID): UUID? =
        contactmomentDetailsRepository.findById(contactmomentUuid).orElse(null)?.planUuid

    fun plansOfActie(actieId: UUID): Set<UUID> =
        plansOfDoelen(listOf(actionRepository.findById(actieId).orElse(null)?.doelUuid ?: throw denied()))

    fun planOfBetrokkene(betrokkeneId: UUID): UUID =
        involvedPartyRepository.findById(betrokkeneId).orElse(null)?.planUuid ?: throw denied()

    companion object {
        fun denied() = ResponseStatusException(HttpStatus.FORBIDDEN, GEEN_TOEGANG)

        const val GEEN_TOEGANG = "Geen toegang"
    }
}
