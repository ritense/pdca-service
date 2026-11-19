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

import com.ritense.pdca.domain.DoelDetails
import com.ritense.pdca.domain.InstrumentDetails
import com.ritense.pdca.domain.UitvoeringsStatus
import com.ritense.pdca.registers.Register
import com.ritense.pdca.registers.RegisterErrors
import com.ritense.pdca.registers.openplan.Doel
import com.ritense.pdca.registers.openplan.DoelRef
import com.ritense.pdca.registers.openplan.Instrument
import com.ritense.pdca.registers.openplan.InstrumentEinde
import com.ritense.pdca.registers.openplan.InstrumentType
import com.ritense.pdca.registers.openplan.NewInstrument
import com.ritense.pdca.registers.openplan.OpenPlanGateway
import com.ritense.pdca.registers.openplan.PlanRef
import com.ritense.pdca.repository.DoelDetailsRepository
import com.ritense.pdca.repository.InstrumentDetailsRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstrumentActionServiceTest {

    private val openPlan = mock(OpenPlanGateway::class.java)
    private val gzacClient = mock(GzacClient::class.java)
    private val instrumentDetails = InMemoryRepository(InstrumentDetails::class, "instrumentUuid")
    private val doelDetails = InMemoryRepository(DoelDetails::class, "doelUuid")
    private lateinit var service: InstrumentActionService

    private val planUuid = UUID.randomUUID()
    private val doelUuid = UUID.randomUUID()
    private val zaak = "urn:pdca:gzac:dossier:instrument-verzoek:dossier-1"
    private val training = InstrumentType(UUID.randomUUID(), "training")
    private val coaching = InstrumentType(UUID.randomUUID(), "coaching")

    @BeforeEach
    fun setUp() {
        val transactions = noOpTransactions()
        val instrumentRepo = instrumentDetails.proxy(InstrumentDetailsRepository::class)
        val doelRepo = doelDetails.proxy(DoelDetailsRepository::class)
        service = InstrumentActionService(
            instrumentDetailsRepository = instrumentRepo,
            openPlanGateway = openPlan,
            gzacClient = gzacClient,
            instrumentLifecycleService = InstrumentLifecycleService(openPlan, instrumentRepo, doelRepo, transactions),
            transactionTemplate = transactions
        )
        `when`(gzacClient.documentDefinitionName("dossier-1")).thenReturn("instrument-verzoek")
        `when`(openPlan.findDoel(doelUuid)).thenReturn(doel("actief"))
        `when`(openPlan.listInstrumenttypen()).thenReturn(listOf(training, coaching))
        `when`(openPlan.listInstrumenten(doelUuid, zaak)).thenReturn(emptyList())
        `when`(openPlan.createInstrument(anyValue())).thenAnswer {
            val nieuw = it.arguments[0] as NewInstrument
            Instrument(uuid = UUID.randomUUID(), titel = nieuw.titel, status = "actief", zaak = nieuw.zaak, doelen = listOf(DoelRef(doelUuid, "actief")))
        }
        doelDetails.rows[doelUuid] = DoelDetails(doelUuid = doelUuid, planUuid = planUuid, uitvoeringsStatus = UitvoeringsStatus.GEPLAND)
    }

    @Test
    fun `aanmaak-instrument returns the instrument a retry finds instead of creating a second`() {
        val bestaand = Instrument(uuid = UUID.randomUUID(), status = "actief", zaak = zaak, doelen = listOf(DoelRef(doelUuid, "actief")))
        `when`(openPlan.listInstrumenten(doelUuid, zaak)).thenReturn(listOf(bestaand))

        val result = service.handleAanmaakInstrument(properties(), "dossier-1")

        assertEquals(bestaand.uuid.toString(), result["instrumentUuid"])
        verify(openPlan, never()).createInstrument(anyValue())
    }

    @Test
    fun `aanmaak-instrument creates under the doel with the dossier as zaak and starts the gepland subdoel`() {
        val result = service.handleAanmaakInstrument(properties(), "dossier-1")

        val captor = ArgumentCaptor.forClass(NewInstrument::class.java)
        verify(openPlan).createInstrument(captor.capture() ?: NewInstrument("", "", emptyList(), status = ""))
        assertEquals(zaak, captor.value.zaak)
        assertEquals(coaching.uuid, captor.value.instrumenttypeUuid)
        assertTrue((result["instrumentUuid"] as String).isNotBlank())
        assertEquals(UitvoeringsStatus.GESTART, doelDetails.rows[doelUuid]!!.uitvoeringsStatus)
    }

    @Test
    fun `a gepland instrument leaves its subdoel gepland`() {
        val result = service.handleAanmaakInstrument(properties() + ("status" to "gepland"), "dossier-1")

        val uuid = UUID.fromString(result["instrumentUuid"] as String)
        assertEquals(UitvoeringsStatus.GEPLAND, instrumentDetails.rows[uuid]!!.uitvoeringsStatus)
        assertEquals(UitvoeringsStatus.GEPLAND, doelDetails.rows[doelUuid]!!.uitvoeringsStatus)
    }

    @Test
    fun `without a known instrumenttype the first one is used`() {
        service.handleAanmaakInstrument(properties() + ("instrumenttype" to "onbekend"), "dossier-1")

        val captor = ArgumentCaptor.forClass(NewInstrument::class.java)
        verify(openPlan).createInstrument(captor.capture() ?: NewInstrument("", "", emptyList(), status = ""))
        assertEquals(training.uuid, captor.value.instrumenttypeUuid)
    }

    @Test
    fun `aanmaak-instrument refuses a doel or plan that no longer runs`() {
        `when`(openPlan.findDoel(doelUuid)).thenReturn(doel("afgerond"))
        val doelDicht = assertFailsWith<PluginActionException> { service.handleAanmaakInstrument(properties(), "dossier-1") }
        assertEquals("DOEL_NIET_ACTIEF", doelDicht.errorCode)
        assertFalse(doelDicht.retryable)

        `when`(openPlan.findDoel(doelUuid)).thenReturn(doel("actief", planStatus = "geannuleerd"))
        val planDicht = assertFailsWith<PluginActionException> { service.handleAanmaakInstrument(properties(), "dossier-1") }
        assertEquals("PLAN_NIET_ACTIEF", planDicht.errorCode)
        verify(openPlan, never()).createInstrument(anyValue())
    }

    @Test
    fun `an unavailable register is a retryable failure`() {
        `when`(openPlan.findDoel(doelUuid)).thenThrow(RegisterErrors.unavailable(Register.OPENPLAN))

        val fout = assertFailsWith<PluginActionException> { service.handleAanmaakInstrument(properties(), "dossier-1") }

        assertEquals("REGISTER_ONBEREIKBAAR", fout.errorCode)
        assertTrue(fout.retryable)
    }

    @Test
    fun `update-instrument refuses an instrument that is no longer actief`() {
        val uuid = UUID.randomUUID()
        `when`(openPlan.findInstrument(uuid)).thenReturn(Instrument(uuid = uuid, status = "geannuleerd", doelen = listOf(DoelRef(doelUuid))))

        val fout = assertFailsWith<PluginActionException> {
            service.handleUpdateInstrument(mapOf("instrumentUuid" to uuid.toString(), "status" to "afgerond"))
        }

        assertEquals("INSTRUMENT_NIET_ACTIEF", fout.errorCode)
        verify(openPlan, never()).closeInstrument(anyValue(), anyValue())
    }

    @Test
    fun `update-instrument in the requested end state already is a success without a write`() {
        val uuid = UUID.randomUUID()
        `when`(openPlan.findInstrument(uuid)).thenReturn(Instrument(uuid = uuid, status = "geannuleerd", doelen = listOf(DoelRef(doelUuid))))

        service.handleUpdateInstrument(
            mapOf("instrumentUuid" to uuid.toString(), "status" to "geannuleerd", "toelichting" to "Inwoner verhuisd")
        )

        verify(openPlan, never()).closeInstrument(anyValue(), anyValue())
        assertEquals("Inwoner verhuisd", instrumentDetails.rows[uuid]!!.afbreekReden)
    }

    @Test
    fun `update-instrument completes an actief instrument with its resultaat`() {
        val uuid = UUID.randomUUID()
        `when`(openPlan.findInstrument(uuid)).thenReturn(Instrument(uuid = uuid, status = "actief", doelen = listOf(DoelRef(doelUuid, "actief"))))

        service.handleUpdateInstrument(mapOf("instrumentUuid" to uuid.toString(), "status" to "afgerond", "resultaat" to "gefaald"))

        val captor = ArgumentCaptor.forClass(InstrumentEinde::class.java)
        verify(openPlan).closeInstrument(org.mockito.ArgumentMatchers.eq(uuid) ?: uuid, captor.capture() ?: InstrumentEinde("", ""))
        assertEquals("afgerond", captor.value.status)
        assertEquals("gefaald", captor.value.resultaat)
        assertEquals(UitvoeringsStatus.GESTART, doelDetails.rows[doelUuid]!!.uitvoeringsStatus)
    }

    private fun doel(status: String, planStatus: String = "actief") =
        Doel(uuid = doelUuid, status = status, titel = "Werk", plannen = listOf(PlanRef(uuid = planUuid, status = planStatus)))

    private fun properties() = mapOf<String, Any?>(
        "doelUuid" to doelUuid.toString(),
        "titel" to "Jobcoaching",
        "product" to "urn:pdca:openproduct:producttype:JOBCOACHING",
        "status" to "actief",
        "instrumenttype" to "coaching",
        "planUuid" to planUuid.toString()
    )
}
