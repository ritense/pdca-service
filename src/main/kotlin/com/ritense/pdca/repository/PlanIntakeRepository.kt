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

package com.ritense.pdca.repository

import com.ritense.pdca.domain.PlanIntake
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

/**
 * The intake reservations (`plan_intake`). Apart from [reserve] every write
 * is one conditional UPDATE answering the number of rows it changed: 1 when
 * the row was in the expected state, 0 otherwise. A holder's writes are
 * conditional on its claim token, so of two requests only the one whose
 * claim is current can change the row.
 */
@Repository
interface PlanIntakeRepository : JpaRepository<PlanIntake, String> {

    /** Reserves the intake (status IN_AANMAAK, unclaimed) unless it has a row already; 1 when this call inserted it. */
    @Transactional
    @Modifying
    @Query(
        nativeQuery = true,
        value = "INSERT INTO plan_intake (intake_dossier_id, status, created_at, updated_at) " +
            "VALUES (:id, 'IN_AANMAAK', :now, :now) ON CONFLICT (intake_dossier_id) DO NOTHING"
    )
    fun reserve(@Param("id") intakeDossierId: String, @Param("now") now: LocalDateTime): Int

    /**
     * Claims an intake that is still IN_AANMAAK and not held by another
     * request (no lease, or an expired one) with [token] until [until]; 1
     * when claimed. One atomic statement, so of two concurrent requests only
     * one wins.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query(
        "UPDATE PlanIntake p SET p.claimToken = :token, p.claimedUntil = :until, p.updatedAt = :now " +
            "WHERE p.intakeDossierId = :id AND p.status = com.ritense.pdca.domain.PlanIntakeStatus.IN_AANMAAK " +
            "AND (p.claimedUntil IS NULL OR p.claimedUntil < :now)"
    )
    fun claim(
        @Param("id") intakeDossierId: String,
        @Param("token") token: String,
        @Param("now") now: LocalDateTime,
        @Param("until") until: LocalDateTime
    ): Int

    /** Extends the lease of the claim [token] until [until]; 0 when another request took the intake over. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query(
        "UPDATE PlanIntake p SET p.claimedUntil = :until, p.updatedAt = :now " +
            "WHERE p.intakeDossierId = :id AND p.claimToken = :token " +
            "AND p.status = com.ritense.pdca.domain.PlanIntakeStatus.IN_AANMAAK"
    )
    fun renew(
        @Param("id") intakeDossierId: String,
        @Param("token") token: String,
        @Param("now") now: LocalDateTime,
        @Param("until") until: LocalDateTime
    ): Int

    /** Records the plan created for the intake, by the holder of the claim [token]. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query(
        "UPDATE PlanIntake p SET p.planUuid = :planUuid, p.updatedAt = :now " +
            "WHERE p.intakeDossierId = :id AND p.claimToken = :token " +
            "AND p.status = com.ritense.pdca.domain.PlanIntakeStatus.IN_AANMAAK"
    )
    fun recordPlan(
        @Param("id") intakeDossierId: String,
        @Param("token") token: String,
        @Param("planUuid") planUuid: UUID,
        @Param("now") now: LocalDateTime
    ): Int

    /** Marks the intake GEREED and ends the claim, by the holder of the claim [token]. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query(
        "UPDATE PlanIntake p SET p.status = com.ritense.pdca.domain.PlanIntakeStatus.GEREED, " +
            "p.claimToken = NULL, p.claimedUntil = NULL, p.updatedAt = :now " +
            "WHERE p.intakeDossierId = :id AND p.claimToken = :token " +
            "AND p.status = com.ritense.pdca.domain.PlanIntakeStatus.IN_AANMAAK"
    )
    fun complete(@Param("id") intakeDossierId: String, @Param("token") token: String, @Param("now") now: LocalDateTime): Int

    /** Ends the claim [token]; 0 when that claim no longer holds the intake. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query(
        "UPDATE PlanIntake p SET p.claimToken = NULL, p.claimedUntil = NULL " +
            "WHERE p.intakeDossierId = :id AND p.claimToken = :token"
    )
    fun release(@Param("id") intakeDossierId: String, @Param("token") token: String): Int

    /** Puts a GEREED intake whose plan [planUuid] is gone back to IN_AANMAAK without a plan; 0 when the row changed meanwhile. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query(
        "UPDATE PlanIntake p SET p.status = com.ritense.pdca.domain.PlanIntakeStatus.IN_AANMAAK, " +
            "p.planUuid = NULL, p.updatedAt = :now " +
            "WHERE p.intakeDossierId = :id AND p.status = com.ritense.pdca.domain.PlanIntakeStatus.GEREED " +
            "AND p.planUuid = :planUuid"
    )
    fun reopen(@Param("id") intakeDossierId: String, @Param("planUuid") planUuid: UUID, @Param("now") now: LocalDateTime): Int

    /** [reopen] for a GEREED intake that never recorded a plan. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query(
        "UPDATE PlanIntake p SET p.status = com.ritense.pdca.domain.PlanIntakeStatus.IN_AANMAAK, p.updatedAt = :now " +
            "WHERE p.intakeDossierId = :id AND p.status = com.ritense.pdca.domain.PlanIntakeStatus.GEREED " +
            "AND p.planUuid IS NULL"
    )
    fun reopenWithoutPlan(@Param("id") intakeDossierId: String, @Param("now") now: LocalDateTime): Int
}
