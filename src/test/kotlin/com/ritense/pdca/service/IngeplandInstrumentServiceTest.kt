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

import com.ritense.pdca.domain.IngeplandInstrument
import com.ritense.pdca.registers.Register
import com.ritense.pdca.registers.RegisterErrors
import com.ritense.pdca.registers.openplan.Doel
import com.ritense.pdca.registers.openplan.Plan
import com.ritense.pdca.registers.openplan.PlanRef
import com.ritense.pdca.registers.openproduct.OpenProductGateway
import com.ritense.pdca.registers.openproduct.ProductType
import com.ritense.pdca.repository.IngeplandInstrumentRepository
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IngeplandInstrumentServiceTest {

    private val aanvragen = mock(InstrumentAanvraagService::class.java)
    private val openProduct = mock(OpenProductGateway::class.java)
    private val planConcept = mock(PlanConcept::class.java)
    private val rijen = InMemoryRepository(IngeplandInstrument::class, "id")
    private val service = IngeplandInstrumentService(repository(), aanvragen, openProduct, planConcept)

    private val planUuid = UUID.randomUUID()
    private val doelUuid = UUID.randomUUID()
    private val producttypeUuid = UUID.randomUUID()
    private val cv = ProductType(producttypeUuid, "CV", "Up to date CV")
    private val urn = "urn:pdca:openproduct:producttype:CV"
    private val vandaag = LocalDate.now()

    init {
        `when`(aanvragen.lopend(doelUuid)).thenReturn(
            Doel(uuid = doelUuid, status = "actief", plannen = listOf(PlanRef(planUuid, "actief"))) to Plan(uuid = planUuid, status = "actief")
        )
        `when`(aanvragen.productUrn(anyValue(), anyValue())).thenAnswer { "urn:pdca:openproduct:producttype:${(it.arguments[1] as ProductType).code}" }
        `when`(aanvragen.start(anyValue(), anyValue(), anyValue(), anyValue())).thenReturn(InstrumentAanvraagService.Verzoek("http://objecten/1", "Up to date CV"))
        `when`(openProduct.getProducttype(producttypeUuid)).thenReturn(cv)
    }

    @Test
    fun `a request on a plan in concept is ingepland, also for today`() {
        `when`(planConcept.inConcept(planUuid)).thenReturn(true)

        val aanvraag = service.vraagAan(doelUuid, producttypeUuid, null)

        assertNull(aanvraag.verzoek)
        assertEquals(vandaag.toString(), aanvraag.ingepland!!.startdatum)
        assertEquals(urn, rijen.rows.values.single().productUrn)
        verify(aanvragen).aanvraagbaar(producttypeUuid, cv)
        verify(aanvragen, never()).start(anyValue(), anyValue(), anyValue(), anyValue())
    }

    @Test
    fun `a request for today on a plan past concept is filed now, a later one is ingepland`() {
        val nu = service.vraagAan(doelUuid, producttypeUuid, vandaag)
        val later = service.vraagAan(doelUuid, producttypeUuid, vandaag.plusDays(14))

        assertNotNull(nu.verzoek)
        assertNull(nu.ingepland)
        verify(aanvragen).start(doelUuid, producttypeUuid, vandaag, null)
        assertEquals(vandaag.plusDays(14).toString(), later.ingepland!!.startdatum)
        assertEquals(1, rijen.rows.size)
    }

    @Test
    fun `a startdatum in the past, a product planned twice and one that cannot be requested are refused`() {
        val verleden = assertFailsWith<ResponseStatusException> { service.vraagAan(doelUuid, producttypeUuid, vandaag.minusDays(1)) }
        service.vraagAan(doelUuid, producttypeUuid, vandaag.plusDays(7))
        val dubbel = assertFailsWith<ResponseStatusException> { service.vraagAan(doelUuid, producttypeUuid, vandaag.plusDays(9)) }
        val ander = ProductType(UUID.randomUUID(), "ZONDER", "Zonder actie")
        `when`(openProduct.getProducttype(ander.uuid)).thenReturn(ander)
        doThrow(ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "geen actie")).`when`(aanvragen).aanvraagbaar(ander.uuid, ander)
        val onmogelijk = assertFailsWith<ResponseStatusException> { service.vraagAan(doelUuid, ander.uuid, vandaag.plusDays(7)) }

        assertEquals(HttpStatus.BAD_REQUEST, verleden.statusCode)
        assertEquals(HttpStatus.CONFLICT, dubbel.statusCode)
        assertEquals("'Up to date CV' is voor dit doel al ingepland", dubbel.reason)
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, onmogelijk.statusCode)
        assertEquals(1, rijen.rows.size)
    }

    @Test
    fun `the job requests what is due on plans past concept and deletes the row`() {
        val vandaagRij = rij(vandaag.minusDays(2))
        val toekomst = rij(vandaag.plusDays(3), doel = UUID.randomUUID())
        val conceptPlan = UUID.randomUUID()
        val inConcept = rij(vandaag, plan = conceptPlan, doel = UUID.randomUUID())
        `when`(planConcept.inConcept(conceptPlan)).thenReturn(true)

        service.vraagAanWatKlaarStaat()

        verify(aanvragen).start(doelUuid, producttypeUuid, vandaag.minusDays(2), vandaagRij.id)
        assertEquals(setOf<Any>(toekomst.id, inConcept.id), rijen.rows.keys)
    }

    @Test
    fun `a refusal stays on the row and the job leaves it alone, a temporary failure is retried`() {
        val geweigerd = rij(vandaag)
        val tijdelijk = rij(vandaag, doel = UUID.randomUUID())
        doThrow(ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Producttype 'CV' heeft geen actie 'StartInstrument'"))
            .`when`(aanvragen).start(doelUuid, producttypeUuid, vandaag, geweigerd.id)
        doThrow(RegisterErrors.unavailable(Register.OBJECTEN))
            .`when`(aanvragen).start(tijdelijk.doelUuid, producttypeUuid, vandaag, tijdelijk.id)

        service.vraagAanWatKlaarStaat()

        assertEquals("Producttype 'CV' heeft geen actie 'StartInstrument'", rijen.rows[geweigerd.id]!!.fout)
        assertNull(rijen.rows[geweigerd.id]!!.geclaimdTot)
        assertNull(rijen.rows[tijdelijk.id]!!.fout)
        assertNull(rijen.rows[tijdelijk.id]!!.geclaimdTot)

        service.vraagAanWatKlaarStaat()

        verify(aanvragen, org.mockito.Mockito.times(1)).start(doelUuid, producttypeUuid, vandaag, geweigerd.id)
        verify(aanvragen, org.mockito.Mockito.times(2)).start(tijdelijk.doelUuid, producttypeUuid, vandaag, tijdelijk.id)

        service.wijzigStartdatum(geweigerd.id, vandaag)
        assertNull(rijen.rows[geweigerd.id]!!.fout, "changing the date (or retrying) clears the refusal")
    }

    @Test
    fun `a row whose verzoek was filed by an earlier attempt is only deleted, and a claimed row is skipped`() {
        val ingediend = rij(vandaag)
        val geclaimd = rij(vandaag, doel = UUID.randomUUID()).also { it.geclaimdTot = LocalDateTime.now().plusMinutes(3) }
        `when`(aanvragen.isIngediend(ingediend.id)).thenReturn(true)

        service.vraagAanWatKlaarStaat()

        assertEquals(setOf<Any>(geclaimd.id), rijen.rows.keys)
        verify(aanvragen, never()).start(anyValue(), anyValue(), anyValue(), anyValue())
    }

    @Test
    fun `requesting now is refused in concept and otherwise files with today as startdatum`() {
        val rij = rij(vandaag.plusDays(30))
        `when`(planConcept.inConcept(planUuid)).thenReturn(true)
        val fout = assertFailsWith<ResponseStatusException> { service.vraagNuAan(rij.id) }
        assertEquals(HttpStatus.CONFLICT, fout.statusCode)

        `when`(planConcept.inConcept(planUuid)).thenReturn(false)
        val verzoek = service.vraagNuAan(rij.id)

        assertEquals("http://objecten/1", verzoek.objectUrl)
        verify(aanvragen).start(doelUuid, producttypeUuid, vandaag, rij.id)
        assertTrue(rijen.rows.isEmpty())
    }

    private fun rij(startdatum: LocalDate, plan: UUID = planUuid, doel: UUID = doelUuid) = IngeplandInstrument(
        planUuid = plan, doelUuid = doel, producttypeUuid = producttypeUuid, productUrn = urn, startdatum = startdatum
    ).also { rijen.rows[it.id] = it }

    private fun repository(): IngeplandInstrumentRepository {
        fun bijwerken(id: Any?, wijzig: (IngeplandInstrument) -> Unit): Int = rijen.rows[id]?.let { wijzig(it); 1 } ?: 0
        return rijen.proxy(
            IngeplandInstrumentRepository::class,
            mapOf(
                "existsByDoelUuidAndProductUrn" to { a -> rijen.rows.values.any { it.doelUuid == a[0] && it.productUrn == a[1] } },
                "findAanTeVragen" to { a ->
                    val nu = a[1] as LocalDateTime
                    rijen.rows.values.filter {
                        !it.startdatum.isAfter(a[0] as LocalDate) && it.fout == null && (it.geclaimdTot == null || it.geclaimdTot!!.isBefore(nu))
                    }.sortedBy { it.startdatum }
                },
                "claim" to { a ->
                    val nu = a[1] as LocalDateTime
                    rijen.rows[a[0]]?.takeIf { it.geclaimdTot == null || it.geclaimdTot!!.isBefore(nu) }
                        ?.let { it.geclaimdTot = a[2] as LocalDateTime; 1 } ?: 0
                },
                "release" to { a -> bijwerken(a[0]) { it.geclaimdTot = null } },
                "markeerFout" to { a -> bijwerken(a[0]) { it.fout = a[1] as String; it.geclaimdTot = null } },
                "verwijder" to { a -> if (rijen.rows.remove(a[0]) != null) 1 else 0 },
                "verwijderVanDoel" to { a -> rijen.rows.values.filter { it.doelUuid == a[0] }.onEach { rijen.rows.remove(it.id) }.size },
                "verwijderVanPlan" to { a -> rijen.rows.values.filter { it.planUuid == a[0] }.onEach { rijen.rows.remove(it.id) }.size }
            )
        )
    }
}
