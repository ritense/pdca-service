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

import com.ritense.pdca.domain.EvaluationSession
import com.ritense.pdca.domain.EvaluationSessionStatus
import com.ritense.pdca.domain.PlanDetails
import com.ritense.pdca.repository.EvaluationSessionRepository
import com.ritense.pdca.repository.PlanDetailsRepository
import com.ritense.pdca.service.PhaseConfigService
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.util.Optional
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PlanSlotTest {

    private val planUuid = UUID.randomUUID()
    private val planDetails = mock(PlanDetailsRepository::class.java)
    private val sessions = mock(EvaluationSessionRepository::class.java)
    private val phaseConfig = mock(PhaseConfigService::class.java)
    private val slot = PlanSlot(planDetails, sessions, phaseConfig)
    private val wijziging = listOf(SlotWijziging(planUuid))

    init {
        `when`(phaseConfig.getPlanStatussen("inwonerplan")).thenReturn(listOf("Ontwerp", "Vastgesteld", "In uitvoering"))
    }

    private fun plan(weergaveStatus: String?, caseDefinitionKey: String? = "inwonerplan") {
        `when`(planDetails.findById(planUuid)).thenReturn(
            Optional.of(PlanDetails(planUuid, UUID.randomUUID(), caseDefinitionKey = caseDefinitionKey, weergaveStatus = weergaveStatus))
        )
    }

    private fun evalueert(login: String, plan: UUID) {
        `when`(sessions.findByUserLoginAndStatus(login, EvaluationSessionStatus.RUNNING)).thenReturn(
            EvaluationSession(dossierId = "d", planUuid = plan, userLogin = login, status = EvaluationSessionStatus.RUNNING)
        )
    }

    private fun geweigerd(login: String, wijzigingen: List<SlotWijziging>) {
        val fout = assertFailsWith<ResponseStatusException> { slot.controleer(login, wijzigingen) }
        assertEquals(HttpStatus.CONFLICT, fout.statusCode)
        assertEquals(PlanSlot.VERGRENDELD, fout.reason)
    }

    @Test
    fun `a plan in its concept status changes freely`() {
        plan("Ontwerp")
        slot.controleer("jan", wijziging)

        plan(null)
        slot.controleer("jan", wijziging)
    }

    @Test
    fun `a case type without display statuses has Concept as concept status`() {
        plan("Concept", caseDefinitionKey = "zonder-config")
        slot.controleer("jan", wijziging)

        plan("Vastgesteld", caseDefinitionKey = "zonder-config")
        geweigerd("jan", wijziging)
    }

    @Test
    fun `a plan past concept changes only in the user's own evaluation of it`() {
        plan("Vastgesteld")
        geweigerd("jan", wijziging)

        evalueert("jan", UUID.randomUUID())
        geweigerd("jan", wijziging)

        evalueert("piet", planUuid)
        geweigerd("jan", wijziging)

        evalueert("jan", planUuid)
        slot.controleer("jan", wijziging)
    }

    @Test
    fun `only moving the display status back to concept is locked`() {
        plan("Vastgesteld")
        slot.controleer("jan", listOf(SlotWijziging(planUuid, weergaveStatus = "In uitvoering")))
        geweigerd("jan", listOf(SlotWijziging(planUuid, weergaveStatus = "ontwerp")))

        evalueert("jan", planUuid)
        slot.controleer("jan", listOf(SlotWijziging(planUuid, weergaveStatus = "Ontwerp")))
    }

    @Test
    fun `nothing to check without changes or without overlay row`() {
        slot.controleer("jan", emptyList())
        `when`(planDetails.findById(planUuid)).thenReturn(Optional.empty())
        slot.controleer("jan", wijziging)
    }
}
