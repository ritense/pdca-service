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

import com.ritense.pdca.domain.DocumentAanvraag
import com.ritense.pdca.domain.DocumentAanvraagStatus
import com.ritense.pdca.domain.DocumentSoort
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

/** A [DocumentAanvraag] without its data snapshot and document, for listings. */
data class DocumentAanvraagSummary(
    val id: UUID,
    val planUuid: UUID,
    val soort: DocumentSoort,
    val bronUuid: UUID,
    val status: DocumentAanvraagStatus,
    val aangevraagdOp: LocalDateTime,
    val bijgewerktOp: LocalDateTime,
    val bestandsnaam: String?,
    val foutmelding: String?
)

/**
 * The document requests (`document_aanvraag`). Writes that race with a
 * document process are single conditional statements answering the number
 * of rows they changed.
 */
@Repository
interface DocumentAanvraagRepository : JpaRepository<DocumentAanvraag, UUID> {

    /** The request of GZAC process [processInstanceId], locked for the rest of the transaction. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM DocumentAanvraag d WHERE d.processInstanceId = :processInstanceId")
    fun findForProcess(@Param("processInstanceId") processInstanceId: String): DocumentAanvraag?

    /**
     * For a process whose request is not recorded yet: the oldest open
     * request of [dossierId] that no process has and that was requested
     * after [since], locked for the rest of the transaction. Rows another
     * transaction holds are skipped, so two processes claiming at once each
     * get their own.
     */
    @Query(
        nativeQuery = true,
        value = "SELECT * FROM document_aanvraag WHERE dossier_id = :dossierId AND status = 'AANGEVRAAGD' " +
            "AND process_instance_id IS NULL AND aangevraagd_op > :since " +
            "ORDER BY aangevraagd_op LIMIT 1 FOR UPDATE SKIP LOCKED"
    )
    fun findClaimable(@Param("dossierId") dossierId: String, @Param("since") since: LocalDateTime): DocumentAanvraag?

    /** The open request (AANGEVRAAGD or BEZIG) of a document, if any. */
    fun findFirstBySoortAndBronUuidAndStatusIn(
        soort: DocumentSoort,
        bronUuid: UUID,
        statuses: Collection<DocumentAanvraagStatus>
    ): DocumentAanvraag?

    /**
     * Records [processInstanceId], the process GZAC started for request
     * [id], unless a process claimed the request already or claimed another
     * request under that id; 1 when recorded.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query(
        "UPDATE DocumentAanvraag d SET d.processInstanceId = :processInstanceId " +
            "WHERE d.id = :id AND d.processInstanceId IS NULL " +
            "AND NOT EXISTS (SELECT o.id FROM DocumentAanvraag o WHERE o.processInstanceId = :processInstanceId)"
    )
    fun recordProcess(@Param("id") id: UUID, @Param("processInstanceId") processInstanceId: String): Int

    /** Ends request [id] as MISLUKT with [reason] while no process has it; 1 when ended. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query(
        "UPDATE DocumentAanvraag d SET d.status = com.ritense.pdca.domain.DocumentAanvraagStatus.MISLUKT, " +
            "d.foutmelding = :reason, d.gegevens = NULL, d.bijgewerktOp = :now " +
            "WHERE d.id = :id AND d.status = com.ritense.pdca.domain.DocumentAanvraagStatus.AANGEVRAAGD " +
            "AND d.processInstanceId IS NULL"
    )
    fun failUnclaimed(@Param("id") id: UUID, @Param("reason") reason: String, @Param("now") now: LocalDateTime): Int

    /** Ends every request still open since before [before] as MISLUKT with [reason]; the number ended. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query(
        "UPDATE DocumentAanvraag d SET d.status = com.ritense.pdca.domain.DocumentAanvraagStatus.MISLUKT, " +
            "d.foutmelding = :reason, d.gegevens = NULL, d.bijgewerktOp = :now " +
            "WHERE d.status IN (com.ritense.pdca.domain.DocumentAanvraagStatus.AANGEVRAAGD, " +
            "com.ritense.pdca.domain.DocumentAanvraagStatus.BEZIG) AND d.bijgewerktOp < :before"
    )
    fun expireOpen(@Param("before") before: LocalDateTime, @Param("reason") reason: String, @Param("now") now: LocalDateTime): Int

    /** Deletes the ended requests of a document that were requested before [before], other than [keep]. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query(
        "DELETE FROM DocumentAanvraag d WHERE d.soort = :soort AND d.bronUuid = :bronUuid AND d.id <> :keep " +
            "AND d.aangevraagdOp < :before AND d.status IN (com.ritense.pdca.domain.DocumentAanvraagStatus.GEREED, " +
            "com.ritense.pdca.domain.DocumentAanvraagStatus.MISLUKT)"
    )
    fun deleteEndedBefore(
        @Param("soort") soort: DocumentSoort,
        @Param("bronUuid") bronUuid: UUID,
        @Param("keep") keep: UUID,
        @Param("before") before: LocalDateTime
    ): Int

    /** Deletes every request of a plan, documents included, without loading them. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM DocumentAanvraag d WHERE d.planUuid = :planUuid")
    fun deleteAllOfPlan(@Param("planUuid") planUuid: UUID): Int

    /** The requests of a plan without snapshot and document: the listing is polled while a document is made. */
    @Query(
        "SELECT new com.ritense.pdca.repository.DocumentAanvraagSummary(d.id, d.planUuid, d.soort, d.bronUuid, " +
            "d.status, d.aangevraagdOp, d.bijgewerktOp, d.bestandsnaam, d.foutmelding) " +
            "FROM DocumentAanvraag d WHERE d.planUuid = :planUuid"
    )
    fun findSummariesByPlanUuid(@Param("planUuid") planUuid: UUID): List<DocumentAanvraagSummary>

    /** The plan of a request, without loading its document. */
    @Query("SELECT d.planUuid FROM DocumentAanvraag d WHERE d.id = :id")
    fun findPlanUuidById(@Param("id") id: UUID): UUID?
}
