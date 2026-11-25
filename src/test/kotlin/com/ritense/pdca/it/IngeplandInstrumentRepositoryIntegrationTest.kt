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

import com.ritense.pdca.domain.IngeplandInstrument
import com.ritense.pdca.repository.IngeplandInstrumentRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals

/**
 * The ingeplande instrumenten (`ingepland_instrument`) on PostgreSQL: the
 * due query takes what has come and is neither refused nor being filed, a
 * claim holds against a second one until its lease runs out, and the
 * database keeps one row per doel and product.
 */
class IngeplandInstrumentRepositoryIntegrationTest : PostgresIntegrationTest() {

    @Autowired private lateinit var repository: IngeplandInstrumentRepository

    private val vandaag = LocalDate.now()

    private fun now(): LocalDateTime = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS)

    private fun rij(startdatum: LocalDate, doelUuid: UUID = UUID.randomUUID(), productUrn: String = "urn:pdca:openproduct:producttype:CV") =
        repository.saveAndFlush(
            IngeplandInstrument(
                planUuid = UUID.randomUUID(), doelUuid = doelUuid, producttypeUuid = UUID.randomUUID(),
                productUrn = productUrn, startdatum = startdatum
            )
        )

    @Test
    fun `only rows whose startdatum has come, without refusal and not being filed, are due`() {
        val gisteren = rij(vandaag.minusDays(1))
        val nu = rij(vandaag)
        val morgen = rij(vandaag.plusDays(1))
        val geweigerd = rij(vandaag)
        repository.markeerFout(geweigerd.id, "geen actie")
        val geclaimd = rij(vandaag)
        repository.claim(geclaimd.id, now(), now().plusMinutes(5))

        val eigen = setOf(gisteren.id, nu.id, morgen.id, geweigerd.id, geclaimd.id)
        val due = repository.findAanTeVragen(vandaag, now()).map { it.id }.filter { it in eigen }

        assertEquals(listOf(gisteren.id, nu.id), due)
    }

    @Test
    fun `a claim holds against a second one until its lease runs out`() {
        val rij = rij(vandaag)

        assertEquals(1, repository.claim(rij.id, now(), now().plusMinutes(5)))
        assertEquals(0, repository.claim(rij.id, now(), now().plusMinutes(5)))
        assertEquals(1, repository.claim(rij.id, now().plusMinutes(6), now().plusMinutes(11)))
        repository.release(rij.id)
        assertEquals(1, repository.claim(rij.id, now(), now().plusMinutes(5)))
    }

    @Test
    fun `a product is ingepland once per doel`() {
        val doelUuid = UUID.randomUUID()
        rij(vandaag, doelUuid)
        rij(vandaag, doelUuid, productUrn = "urn:pdca:openproduct:producttype:TAAL")

        assertThrows<DataIntegrityViolationException> { rij(vandaag.plusDays(3), doelUuid) }
        assertEquals(2, repository.verwijderVanDoel(doelUuid))
    }
}
