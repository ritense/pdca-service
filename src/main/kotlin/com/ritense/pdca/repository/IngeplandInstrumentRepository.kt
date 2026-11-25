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

import com.ritense.pdca.domain.IngeplandInstrument
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * The ingeplande instrumenten. Filing one is guarded by a claim: [claim] is
 * one conditional UPDATE, so of two replicas (or the job and a user) only
 * one files a row at a time.
 */
@Repository
interface IngeplandInstrumentRepository : JpaRepository<IngeplandInstrument, UUID> {

    fun findByPlanUuidOrderByStartdatumAscAangemaaktOpAsc(planUuid: UUID): List<IngeplandInstrument>

    fun findByDoelUuid(doelUuid: UUID): List<IngeplandInstrument>

    fun existsByDoelUuidAndProductUrn(doelUuid: UUID, productUrn: String): Boolean

    /** The rows whose startdatum has come, without a refusal and not being filed; oldest date first. */
    @Query(
        "SELECT i FROM IngeplandInstrument i WHERE i.startdatum <= :vandaag AND i.fout IS NULL " +
            "AND (i.geclaimdTot IS NULL OR i.geclaimdTot < :nu) ORDER BY i.startdatum, i.aangemaaktOp"
    )
    fun findAanTeVragen(@Param("vandaag") vandaag: LocalDate, @Param("nu") nu: LocalDateTime): List<IngeplandInstrument>

    /** Claims the row until [tot] unless another claim holds it; 1 when claimed. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query(
        "UPDATE IngeplandInstrument i SET i.geclaimdTot = :tot " +
            "WHERE i.id = :id AND (i.geclaimdTot IS NULL OR i.geclaimdTot < :nu)"
    )
    fun claim(@Param("id") id: UUID, @Param("nu") nu: LocalDateTime, @Param("tot") tot: LocalDateTime): Int

    /** Ends the claim on the row. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("UPDATE IngeplandInstrument i SET i.geclaimdTot = NULL WHERE i.id = :id")
    fun release(@Param("id") id: UUID): Int

    /** Records why the registers refused the row and ends the claim. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("UPDATE IngeplandInstrument i SET i.fout = :fout, i.geclaimdTot = NULL WHERE i.id = :id")
    fun markeerFout(@Param("id") id: UUID, @Param("fout") fout: String): Int

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM IngeplandInstrument i WHERE i.id = :id")
    fun verwijder(@Param("id") id: UUID): Int

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM IngeplandInstrument i WHERE i.doelUuid = :doelUuid")
    fun verwijderVanDoel(@Param("doelUuid") doelUuid: UUID): Int

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM IngeplandInstrument i WHERE i.planUuid = :planUuid")
    fun verwijderVanPlan(@Param("planUuid") planUuid: UUID): Int

    @Query("SELECT i.planUuid FROM IngeplandInstrument i WHERE i.id = :id")
    fun findPlanUuidById(@Param("id") id: UUID): UUID?
}
