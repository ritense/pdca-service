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

import com.ritense.pdca.domain.PlanDetails
import com.ritense.pdca.domain.PlanIntake
import com.ritense.pdca.domain.PlanIntakeStatus
import com.ritense.pdca.repository.PlanDetailsRepository
import com.ritense.pdca.repository.PlanIntakeRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The intake reservation (`plan_intake`) on PostgreSQL: the reservation and
 * the claim are single statements, so of concurrent requests exactly one
 * wins; every later write is conditional on the holder's claim token, so a
 * request that lost its claim changes nothing; and the database keeps one
 * plan per intake.
 */
class PlanIntakeRepositoryIntegrationTest : PostgresIntegrationTest() {

    @Autowired private lateinit var repository: PlanIntakeRepository
    @Autowired private lateinit var planDetailsRepository: PlanDetailsRepository
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    private fun now(): LocalDateTime = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS)

    private fun intakeId() = "intake-${UUID.randomUUID()}"

    private fun token() = UUID.randomUUID().toString()

    @Test
    fun `a reservation inserts the intake once`() {
        val id = intakeId()

        assertEquals(1, repository.reserve(id, now()))
        assertEquals(0, repository.reserve(id, now()))

        val intake = repository.findById(id).orElseThrow()
        assertEquals(PlanIntakeStatus.IN_AANMAAK, intake.status)
        assertNull(intake.claimedUntil)
        assertNull(intake.planUuid)
    }

    @Test
    fun `of two concurrent reservations exactly one inserts`() {
        repeat(ROUNDS) {
            val id = intakeId()
            assertEquals(listOf(0, 1), concurrently { repository.reserve(id, now()) }.sorted())
        }
    }

    @Test
    fun `of two concurrent claims exactly one wins`() {
        repeat(ROUNDS) {
            val id = intakeId()
            repository.reserve(id, now())
            assertEquals(listOf(0, 1), concurrently { repository.claim(id, token(), now(), now().plusMinutes(5)) }.sorted())
        }
    }

    @Test
    fun `a live claim holds the intake until it expires or its holder releases it`() {
        val id = intakeId()
        val holder = token()
        repository.reserve(id, now())

        assertEquals(1, repository.claim(id, holder, now(), now().plusMinutes(5)))
        assertEquals(0, repository.claim(id, token(), now(), now().plusMinutes(5)))

        assertEquals(0, repository.release(id, token()), "only the holder releases")
        assertEquals(holder, repository.findById(id).orElseThrow().claimToken)

        assertEquals(1, repository.release(id, holder))
        val released = repository.findById(id).orElseThrow()
        assertNull(released.claimedUntil)
        assertNull(released.claimToken)
        assertEquals(1, repository.claim(id, token(), now(), now().plusMinutes(5)))
    }

    @Test
    fun `an expired claim can be taken over`() {
        val id = intakeId()
        repository.reserve(id, now())
        assertEquals(1, repository.claim(id, token(), now().minusMinutes(10), now().minusMinutes(5)))

        val until = now().plusMinutes(5)
        assertEquals(1, repository.claim(id, token(), now(), until))
        assertEquals(until, repository.findById(id).orElseThrow().claimedUntil)
    }

    @Test
    fun `the holder renews its lease, records the plan and completes the intake`() {
        val id = intakeId()
        val holder = token()
        val planUuid = UUID.randomUUID()
        repository.reserve(id, now())
        repository.claim(id, holder, now(), now().plusMinutes(1))

        val until = now().plusMinutes(10)
        assertEquals(1, repository.renew(id, holder, now(), until))
        assertEquals(until, repository.findById(id).orElseThrow().claimedUntil)
        assertEquals(1, repository.recordPlan(id, holder, planUuid, now()))
        assertEquals(1, repository.complete(id, holder, now()))

        val done = repository.findById(id).orElseThrow()
        assertEquals(PlanIntakeStatus.GEREED, done.status)
        assertEquals(planUuid, done.planUuid)
        assertNull(done.claimToken)
        assertNull(done.claimedUntil)
        assertEquals(0, repository.release(id, holder), "completing ended the claim")
    }

    @Test
    fun `a holder whose expired claim was taken over can no longer change the intake`() {
        val id = intakeId()
        val stale = token()
        val current = token()
        repository.reserve(id, now())
        repository.claim(id, stale, now().minusMinutes(10), now().minusMinutes(5))
        assertEquals(1, repository.claim(id, current, now(), now().plusMinutes(5)))

        assertEquals(0, repository.renew(id, stale, now(), now().plusMinutes(10)))
        assertEquals(0, repository.recordPlan(id, stale, UUID.randomUUID(), now()))
        assertEquals(0, repository.complete(id, stale, now()))
        assertEquals(0, repository.release(id, stale))

        val intake = repository.findById(id).orElseThrow()
        assertEquals(PlanIntakeStatus.IN_AANMAAK, intake.status)
        assertEquals(current, intake.claimToken)
        assertNull(intake.planUuid)
        assertEquals(1, repository.complete(id, current, now()))
    }

    @Test
    fun `reopening a completed intake only changes the row with the plan that was read`() {
        val id = intakeId()
        val planUuid = UUID.randomUUID()
        repository.saveAndFlush(PlanIntake(intakeDossierId = id, status = PlanIntakeStatus.GEREED, planUuid = planUuid))

        assertEquals(0, repository.reopen(id, UUID.randomUUID(), now()))
        assertEquals(0, repository.reopenWithoutPlan(id, now()))
        assertEquals(1, repository.reopen(id, planUuid, now()))
        assertEquals(0, repository.reopen(id, planUuid, now()), "already reopened")

        val reopened = repository.findById(id).orElseThrow()
        assertEquals(PlanIntakeStatus.IN_AANMAAK, reopened.status)
        assertNull(reopened.planUuid)
    }

    @Test
    fun `a completed intake cannot be claimed or reserved again`() {
        val id = intakeId()
        repository.saveAndFlush(PlanIntake(intakeDossierId = id, status = PlanIntakeStatus.GEREED, planUuid = UUID.randomUUID()))

        assertEquals(0, repository.claim(id, token(), now(), now().plusMinutes(5)))
        assertEquals(0, repository.reserve(id, now()))
        assertEquals(PlanIntakeStatus.GEREED, repository.findById(id).orElseThrow().status)
    }

    @Test
    fun `the database keeps one reservation and one plan per intake`() {
        val id = intakeId()
        repository.reserve(id, now())

        assertThrows<DataIntegrityViolationException> {
            jdbcTemplate.update(
                "INSERT INTO plan_intake (intake_dossier_id, status, created_at, updated_at) VALUES (?, 'IN_AANMAAK', ?, ?)",
                id, now(), now()
            )
        }

        planDetailsRepository.saveAndFlush(PlanDetails(planUuid = UUID.randomUUID(), persoonUuid = UUID.randomUUID(), intakeDossierId = id))
        assertThrows<DataIntegrityViolationException> {
            planDetailsRepository.saveAndFlush(PlanDetails(planUuid = UUID.randomUUID(), persoonUuid = UUID.randomUUID(), intakeDossierId = id))
        }
    }

    /** Runs [action] on two threads released at the same moment; their results. */
    private fun <T> concurrently(action: () -> T): List<T> {
        val barrier = CyclicBarrier(2)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = List(2) {
                executor.submit<T> {
                    barrier.await(10, TimeUnit.SECONDS)
                    action()
                }
            }
            return futures.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }

    private companion object {
        const val ROUNDS = 20
    }
}
