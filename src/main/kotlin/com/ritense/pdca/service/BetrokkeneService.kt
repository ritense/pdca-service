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

import com.ritense.pdca.domain.InvolvedParty
import com.ritense.pdca.domain.PdcaUrn
import com.ritense.pdca.registers.openplan.OpenPlanGateway
import com.ritense.pdca.repository.InvolvedPartyRepository
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Betrokkenen of a plan and its procesbegeleider. The procesbegeleider is the
 * betrokkene with `isPrimary`; a plan has at most one (also enforced by a
 * partial unique index). Open Plan knows the procesbegeleider as
 * `plan.medewerker` ([PdcaUrn.medewerker]). A change of procesbegeleider is
 * written to Open Plan first, so a refusal there changes nothing; the
 * overlay follows in its own transaction, and when that fails Open Plan is
 * set back to the previous procesbegeleider. No transaction spans the
 * register call.
 */
@Service
class BetrokkeneService(
    private val involvedPartyRepository: InvolvedPartyRepository,
    private val openPlanGateway: OpenPlanGateway,
    private val transactionTemplate: TransactionTemplate
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun list(planUuid: UUID): List<InvolvedParty> = involvedPartyRepository.findByPlanUuid(planUuid)

    /** Adds a betrokkene; when it is primary it becomes the plan's procesbegeleider. */
    fun create(party: InvolvedParty): InvolvedParty {
        if (!party.isPrimary) return involvedPartyRepository.save(party)
        return withMedewerker(party.planUuid, party.name) {
            unsetProcesbegeleider(party.planUuid, except = party.id)
            involvedPartyRepository.saveAndFlush(party)
        }
    }

    /** Makes this betrokkene the plan's procesbegeleider; returns all betrokkenen of the plan. */
    fun makeProcesbegeleider(id: UUID): List<InvolvedParty> {
        val party = involvedPartyRepository.findById(id).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Betrokkene niet gevonden")
        }
        return withMedewerker(party.planUuid, party.name) {
            unsetProcesbegeleider(party.planUuid, except = party.id)
            if (!party.isPrimary) {
                party.isPrimary = true
                involvedPartyRepository.saveAndFlush(party)
            }
            involvedPartyRepository.findByPlanUuid(party.planUuid)
        }
    }

    /** Removes a betrokkene; removing the procesbegeleider leaves the plan without one. */
    fun delete(id: UUID) {
        val party = involvedPartyRepository.findById(id).orElse(null) ?: return
        if (!party.isPrimary) {
            involvedPartyRepository.delete(party)
            return
        }
        withMedewerker(party.planUuid, null) { involvedPartyRepository.delete(party) }
    }

    /**
     * Sets Open Plan's medewerker to [naam], then runs [overlay] in a
     * transaction; if the overlay fails, Open Plan gets the previous
     * procesbegeleider back (best effort) and the failure is rethrown.
     */
    private fun <T> withMedewerker(planUuid: UUID, naam: String?, overlay: () -> T): T {
        val vorige = involvedPartyRepository.findByPlanUuid(planUuid).firstOrNull { it.isPrimary }?.name
        openPlanGateway.updatePlanMedewerker(planUuid, PdcaUrn.medewerker(naam))
        return try {
            transactionTemplate.execute { overlay() }!!
        } catch (e: Exception) {
            try {
                openPlanGateway.updatePlanMedewerker(planUuid, PdcaUrn.medewerker(vorige))
            } catch (revert: Exception) {
                log.error("Could not set the procesbegeleider of plan {} in Open Plan back after a failed change", planUuid, revert)
            }
            throw e
        }
    }

    /** Clears the flag on the plan's other primary betrokkenen, flushed before a new one is set. */
    private fun unsetProcesbegeleider(planUuid: UUID, except: UUID) {
        val others = involvedPartyRepository.findByPlanUuid(planUuid).filter { it.isPrimary && it.id != except }
        if (others.isEmpty()) return
        others.forEach { it.isPrimary = false }
        involvedPartyRepository.saveAllAndFlush(others)
    }
}
