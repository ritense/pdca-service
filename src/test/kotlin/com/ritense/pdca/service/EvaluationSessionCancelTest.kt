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

import com.ritense.pdca.domain.EvaluationSession
import com.ritense.pdca.domain.EvaluationSessionStatus
import com.ritense.pdca.repository.ContactmomentDetailsRepository
import com.ritense.pdca.repository.EvaluationChangeRepository
import com.ritense.pdca.repository.EvaluationSessionRepository
import com.ritense.pdca.repository.PlanDetailsRepository
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatus
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import java.util.Optional
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Cancelling an evaluation: one that changed a locked part of the plan
 * (the plan lock) can only be completed, also when the change is let
 * through while the cancel runs.
 */
class EvaluationSessionCancelTest {

    private val sessions = mock(EvaluationSessionRepository::class.java)
    private val service = EvaluationSessionService(
        sessions, mock(EvaluationChangeRepository::class.java), mock(PlanDetailsRepository::class.java),
        mock(ContactmomentDetailsRepository::class.java), mock(PhaseConfigService::class.java),
        mock(KlantcontactService::class.java), mock(DocumentAanvraagService::class.java), mock(TransactionTemplate::class.java)
    )

    private fun sessie(vastgesteldGewijzigd: Boolean = false, status: EvaluationSessionStatus = EvaluationSessionStatus.RUNNING) =
        EvaluationSession(
            dossierId = "d", planUuid = UUID.randomUUID(), userLogin = "jan", status = status, vastgesteldGewijzigd = vastgesteldGewijzigd
        )

    private fun loopt(sessie: EvaluationSession, daarna: EvaluationSession = sessie) {
        `when`(sessions.findByUserLoginAndStatus("jan", EvaluationSessionStatus.RUNNING)).thenReturn(sessie)
        `when`(sessions.findById(sessie.id)).thenReturn(Optional.of(daarna))
    }

    @Test
    fun `an evaluation without locked changes is cancelled`() {
        val sessie = sessie()
        loopt(sessie, sessie.copy(status = EvaluationSessionStatus.CANCELLED))
        `when`(sessions.annuleer(eqValue(sessie.id), anyValue())).thenReturn(1)

        assertEquals(EvaluationSessionStatus.CANCELLED, service.cancel("jan").status)
    }

    @Test
    fun `an evaluation that changed a locked part of the plan is not cancelled`() {
        val sessie = sessie(vastgesteldGewijzigd = true)
        loopt(sessie)

        val fout = assertFailsWith<ResponseStatusException> { service.cancel("jan") }

        assertEquals(HttpStatus.CONFLICT, fout.statusCode)
        verify(sessions, never()).annuleer(anyValue(), anyValue())
    }

    @Test
    fun `a locked change let through while cancelling keeps the evaluation running`() {
        val sessie = sessie()
        loopt(sessie, sessie.copy(vastgesteldGewijzigd = true))
        `when`(sessions.annuleer(eqValue(sessie.id), anyValue())).thenReturn(0)

        assertEquals(HttpStatus.CONFLICT, assertFailsWith<ResponseStatusException> { service.cancel("jan") }.statusCode)
    }

    @Test
    fun `an evaluation that ended meanwhile is not there to cancel`() {
        val sessie = sessie()
        loopt(sessie, sessie.copy(status = EvaluationSessionStatus.COMPLETED))
        `when`(sessions.annuleer(eqValue(sessie.id), anyValue())).thenReturn(0)

        assertEquals(HttpStatus.NOT_FOUND, assertFailsWith<ResponseStatusException> { service.cancel("jan") }.statusCode)
    }
}
