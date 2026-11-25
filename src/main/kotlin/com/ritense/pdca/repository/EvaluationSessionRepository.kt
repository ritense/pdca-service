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

package com.ritense.pdca.repository

import com.ritense.pdca.domain.EvaluationSession
import com.ritense.pdca.domain.EvaluationSessionStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

/** The session statuses as JPQL literals, for the conditional updates below. */
private const val RUNNING = "com.ritense.pdca.domain.EvaluationSessionStatus.RUNNING"
private const val CANCELLED = "com.ritense.pdca.domain.EvaluationSessionStatus.CANCELLED"

@Repository
interface EvaluationSessionRepository : JpaRepository<EvaluationSession, UUID> {
    fun findByUserLoginAndStatus(userLogin: String, status: EvaluationSessionStatus): EvaluationSession?

    fun findByPlanUuidAndStatus(planUuid: UUID, status: EvaluationSessionStatus): List<EvaluationSession>
    fun findByPlanUuid(planUuid: UUID): List<EvaluationSession>

    /**
     * Records that running session [id] changed a locked part of its plan;
     * 1 when this call recorded it, 0 when the session no longer runs or
     * had it recorded already.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query(
        "UPDATE EvaluationSession s SET s.vastgesteldGewijzigd = true " +
            "WHERE s.id = :id AND s.status = $RUNNING AND s.vastgesteldGewijzigd = false"
    )
    fun markVastgesteldGewijzigd(@Param("id") id: UUID): Int

    /** Takes back the record of [markVastgesteldGewijzigd] while session [id] runs. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("UPDATE EvaluationSession s SET s.vastgesteldGewijzigd = false WHERE s.id = :id AND s.status = $RUNNING")
    fun unmarkVastgesteldGewijzigd(@Param("id") id: UUID): Int

    /**
     * Cancels running session [id] unless it changed a locked part of its
     * plan, in one statement, so a change that is let through at the same
     * moment either keeps the session running or is refused; 1 when
     * cancelled.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query(
        "UPDATE EvaluationSession s SET s.status = $CANCELLED, s.endedAt = :endedAt " +
            "WHERE s.id = :id AND s.status = $RUNNING AND s.vastgesteldGewijzigd = false"
    )
    fun annuleer(@Param("id") id: UUID, @Param("endedAt") endedAt: LocalDateTime): Int
}
