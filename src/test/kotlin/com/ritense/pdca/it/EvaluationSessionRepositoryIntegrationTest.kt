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

package com.ritense.pdca.it

import com.ritense.pdca.domain.EvaluationSession
import com.ritense.pdca.domain.EvaluationSessionStatus
import com.ritense.pdca.repository.EvaluationSessionRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The evaluation sessions (`evaluation_session`) on PostgreSQL: recording a
 * locked change and cancelling are conditional on each other, and saving a
 * session never resets the record of a locked change.
 */
class EvaluationSessionRepositoryIntegrationTest : PostgresIntegrationTest() {

    @Autowired private lateinit var repository: EvaluationSessionRepository

    private fun sessie(status: EvaluationSessionStatus = EvaluationSessionStatus.RUNNING) = repository.save(
        EvaluationSession(dossierId = "dossier-${UUID.randomUUID()}", planUuid = UUID.randomUUID(), userLogin = "user-${UUID.randomUUID()}", status = status)
    )

    private fun gelezen(id: UUID) = repository.findById(id).orElseThrow()

    @Test
    fun `a running session without locked changes is cancelled`() {
        val sessie = sessie()

        assertEquals(1, repository.annuleer(sessie.id, LocalDateTime.now()))

        assertEquals(EvaluationSessionStatus.CANCELLED, gelezen(sessie.id).status)
    }

    @Test
    fun `a session with a locked change is not cancelled`() {
        val sessie = sessie()

        assertEquals(1, repository.markeerVastgesteldGewijzigd(sessie.id))
        assertEquals(0, repository.annuleer(sessie.id, LocalDateTime.now()))

        val gelezen = gelezen(sessie.id)
        assertEquals(EvaluationSessionStatus.RUNNING, gelezen.status)
        assertTrue(gelezen.vastgesteldGewijzigd)
    }

    @Test
    fun `a locked change is not recorded on a session that ended`() {
        val sessie = sessie(EvaluationSessionStatus.CANCELLED)

        assertEquals(0, repository.markeerVastgesteldGewijzigd(sessie.id))
    }

    @Test
    fun `saving a session it was loaded before keeps the record of a locked change`() {
        val geladen = sessie()
        repository.markeerVastgesteldGewijzigd(geladen.id)

        geladen.verslag = "Besproken: de nieuwe werkplek"
        repository.save(geladen)

        val gelezen = gelezen(geladen.id)
        assertEquals("Besproken: de nieuwe werkplek", gelezen.verslag)
        assertTrue(gelezen.vastgesteldGewijzigd)
    }
}
