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

import com.fasterxml.jackson.databind.ObjectMapper
import com.ritense.pdca.domain.InvolvedParty
import com.ritense.pdca.repository.InvolvedPartyRepository
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Betrokkenen of a plan and its procesbegeleider. The procesbegeleider is the
 * betrokkene with `isPrimary`; a plan has at most one (also enforced by a
 * partial unique index). Open Plan knows the procesbegeleider as
 * `plan.medewerker` ([medewerkerUrn]); every change of procesbegeleider is
 * written there in the same transaction, so a refusal by Open Plan rolls the
 * change back.
 */
@Service
@Transactional
class BetrokkeneService(
    private val involvedPartyRepository: InvolvedPartyRepository,
    private val openPlanRestClient: RestClient,
    private val objectMapper: ObjectMapper
) {

    companion object {
        /**
         * The Open Plan medewerker reference for a procesbegeleider:
         * `urn:pdca:medewerkers:medewerker:<name, whitespace and dots collapsed
         * to single dots, lowercase>` ("M. de Boer" → `m.de.boer`); empty when
         * there is none.
         */
        fun medewerkerUrn(naam: String?): String {
            val id = naam?.trim()?.replace(Regex("[\\s.]+"), ".")?.trim('.')?.lowercase()
            return if (id.isNullOrEmpty()) "" else "urn:pdca:medewerkers:medewerker:$id"
        }
    }

    @Transactional(readOnly = true)
    fun list(planUuid: UUID): List<InvolvedParty> = involvedPartyRepository.findByPlanUuid(planUuid)

    /** Adds a betrokkene; when it is primary it becomes the plan's procesbegeleider. */
    fun create(party: InvolvedParty): InvolvedParty {
        if (!party.isPrimary) return involvedPartyRepository.save(party)
        unsetProcesbegeleider(party.planUuid, except = party.id)
        val saved = involvedPartyRepository.saveAndFlush(party)
        syncMedewerker(saved.planUuid, saved.name)
        return saved
    }

    /** Makes this betrokkene the plan's procesbegeleider; returns all betrokkenen of the plan. */
    fun makeProcesbegeleider(id: UUID): List<InvolvedParty> {
        val party = involvedPartyRepository.findById(id).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Betrokkene niet gevonden")
        }
        unsetProcesbegeleider(party.planUuid, except = party.id)
        if (!party.isPrimary) {
            party.isPrimary = true
            involvedPartyRepository.saveAndFlush(party)
        }
        syncMedewerker(party.planUuid, party.name)
        return involvedPartyRepository.findByPlanUuid(party.planUuid)
    }

    /** Removes a betrokkene; removing the procesbegeleider leaves the plan without one. */
    fun delete(id: UUID) {
        val party = involvedPartyRepository.findById(id).orElse(null) ?: return
        involvedPartyRepository.delete(party)
        if (party.isPrimary) {
            involvedPartyRepository.flush()
            syncMedewerker(party.planUuid, null)
        }
    }

    /** Clears the flag on the plan's other primary betrokkenen, flushed before a new one is set. */
    private fun unsetProcesbegeleider(planUuid: UUID, except: UUID) {
        val others = involvedPartyRepository.findByPlanUuid(planUuid).filter { it.isPrimary && it.id != except }
        if (others.isEmpty()) return
        others.forEach { it.isPrimary = false }
        involvedPartyRepository.saveAllAndFlush(others)
    }

    private fun syncMedewerker(planUuid: UUID, naam: String?) {
        try {
            openPlanRestClient.patch()
                .uri("/plannen/api/v0/plan/$planUuid")
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsBytes(mapOf("medewerker" to medewerkerUrn(naam))))
                .retrieve()
                .toBodilessEntity()
        } catch (e: RestClientResponseException) {
            throw ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "Procesbegeleider bijwerken in Open Plan mislukt (HTTP ${e.statusCode.value()})"
            )
        } catch (e: Exception) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "Procesbegeleider bijwerken in Open Plan mislukt: ${e.message}")
        }
    }
}
