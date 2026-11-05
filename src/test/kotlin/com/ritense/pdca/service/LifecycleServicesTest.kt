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
import com.ritense.pdca.domain.PlanDetails
import com.ritense.pdca.domain.UitvoeringsStatus
import com.ritense.pdca.registers.openplan.Doel
import com.ritense.pdca.registers.openplan.DoelCategorie
import com.ritense.pdca.registers.openplan.DoelRef
import com.ritense.pdca.registers.openplan.DoelStatusUpdate
import com.ritense.pdca.registers.openplan.DoelType
import com.ritense.pdca.registers.openplan.Instrument
import com.ritense.pdca.registers.openplan.NewDoel
import com.ritense.pdca.registers.openplan.OpenPlanGateway
import com.ritense.pdca.registers.openplan.Plan
import com.ritense.pdca.registers.openplan.PlanStatusUpdate
import com.ritense.pdca.repository.DoelDetailsRepository
import com.ritense.pdca.repository.InstrumentDetailsRepository
import com.ritense.pdca.repository.PlanDetailsRepository
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The lifecycle use cases of the plan tabs: doel cascades, instrument steps, hoofddoel switch, plan close and reopen. */
class LifecycleServicesTest {

    private val openPlan = mock(OpenPlanGateway::class.java)
    private val planOverlayService = mock(PlanOverlayService::class.java)
    private val instrumentDetails = InMemoryRepository(InstrumentDetails::class, "instrumentUuid")
    private val doelDetails = InMemoryRepository(DoelDetails::class, "doelUuid")
    private val planDetails = InMemoryRepository(PlanDetails::class, "planUuid")
    private val instrumentLifecycle = InstrumentLifecycleService(
        openPlan, instrumentDetails.proxy(InstrumentDetailsRepository::class), doelDetails.proxy(DoelDetailsRepository::class), noOpTransactions()
    )

    private val planUuid = UUID.randomUUID()
    private val doelUuid = UUID.randomUUID()
    private val dossierZaak = "urn:pdca:gzac:dossier:instrument-verzoek:dossier-1"

    private fun instrument(status: String = "actief", zaak: String? = null, titel: String = "Jobcoaching", doelen: List<UUID> = listOf(doelUuid)) =
        Instrument(uuid = UUID.randomUUID(), titel = titel, status = status, zaak = zaak, doelen = doelen.map { DoelRef(it, "actief") })

    @Nested
    inner class Doelen {
        private val service = DoelLifecycleService(openPlan, instrumentDetails.proxy(InstrumentDetailsRepository::class), instrumentLifecycle, planOverlayService)

        init {
            `when`(openPlan.findDoel(doelUuid)).thenReturn(Doel(uuid = doelUuid, status = "actief", titel = "Werk"))
            doelDetails.rows[doelUuid] = DoelDetails(doelUuid = doelUuid, planUuid = planUuid)
        }

        @Test
        fun `removing a doel is refused while an instrument dossier of it is open`() {
            `when`(openPlan.listInstrumenten(doelUuid)).thenReturn(listOf(instrument(zaak = dossierZaak)))

            val fout = assertFailsWith<ResponseStatusException> { service.verwijderen(doelUuid) }

            assertEquals(HttpStatus.CONFLICT, fout.statusCode)
            assertEquals("Sluit eerst de instrumentdossiers af: Jobcoaching", fout.reason)
            verify(openPlan, never()).deleteDoel(anyValue())
        }

        @Test
        fun `aborting a doel is refused while an instrument dossier of it is open`() {
            `when`(openPlan.listInstrumenten(doelUuid)).thenReturn(listOf(instrument(zaak = dossierZaak)))

            val fout = assertFailsWith<ResponseStatusException> { service.afbreken(doelUuid, "Niet meer nodig") }

            assertEquals("Sluit eerst de instrumentdossiers af: Jobcoaching", fout.reason)
            verify(openPlan, never()).updateDoelStatus(anyValue(), anyValue())
        }

        @Test
        fun `removing a doel takes its own instruments first and the doel last`() {
            val eigen = instrument(status = "afgerond", zaak = dossierZaak)
            val gedeeld = instrument(doelen = listOf(doelUuid, UUID.randomUUID()))
            `when`(openPlan.listInstrumenten(doelUuid)).thenReturn(listOf(eigen, gedeeld))

            service.verwijderen(doelUuid)

            val volgorde = inOrder(openPlan, planOverlayService)
            volgorde.verify(openPlan).deleteInstrument(eigen.uuid)
            volgorde.verify(openPlan).deleteDoel(doelUuid)
            volgorde.verify(planOverlayService).deleteDoelDetails(doelUuid)
            verify(openPlan, never()).deleteInstrument(gedeeld.uuid)
        }

        @Test
        fun `aborting a doel cancels its running instruments with the reason, then the doel`() {
            val lopend = instrument()
            `when`(openPlan.listInstrumenten(doelUuid)).thenReturn(listOf(lopend, instrument(status = "afgerond")))
            `when`(openPlan.findInstrument(lopend.uuid)).thenReturn(lopend)

            val resultaat = service.afbreken(doelUuid, "Niet meer nodig")

            assertEquals(1, resultaat.instrumenten)
            assertEquals("Afgebroken met doel: Niet meer nodig", instrumentDetails.rows[lopend.uuid]!!.afbreekReden)
            val volgorde = inOrder(openPlan)
            volgorde.verify(openPlan).closeInstrument(eq(lopend.uuid) ?: lopend.uuid, anyValue())
            volgorde.verify(openPlan).updateDoelStatus(eq(doelUuid) ?: doelUuid, anyValue())
        }
    }

    @Nested
    inner class Instrumenten {

        @Test
        fun `completing or aborting a dossier-managed instrument from the plan page is refused`() {
            val beheerd = instrument(zaak = dossierZaak)
            `when`(openPlan.findInstrument(beheerd.uuid)).thenReturn(beheerd)

            val afronden = assertFailsWith<ResponseStatusException> { instrumentLifecycle.afronden(beheerd.uuid, null) }
            val afbreken = assertFailsWith<ResponseStatusException> { instrumentLifecycle.afbreken(beheerd.uuid, "reden") }

            assertEquals(HttpStatus.CONFLICT, afronden.statusCode)
            assertTrue(afbreken.reason!!.contains("instrumentdossier"))
            verify(openPlan, never()).closeInstrument(anyValue(), anyValue())
        }

        @Test
        fun `starting an instrument starts its gepland subdoel and reports it`() {
            val gepland = instrument()
            `when`(openPlan.findInstrument(gepland.uuid)).thenReturn(gepland)
            doelDetails.rows[doelUuid] = DoelDetails(doelUuid = doelUuid, planUuid = planUuid, uitvoeringsStatus = UitvoeringsStatus.GEPLAND)
            instrumentDetails.rows[gepland.uuid] = InstrumentDetails(gepland.uuid, planUuid, UitvoeringsStatus.GEPLAND)

            val resultaat = instrumentLifecycle.starten(gepland.uuid)

            assertEquals(listOf(doelUuid), resultaat.gestarteSubdoelen.map { it.uuid })
            assertEquals(UitvoeringsStatus.GESTART, instrumentDetails.rows[gepland.uuid]!!.uitvoeringsStatus)
            assertEquals(UitvoeringsStatus.GESTART, doelDetails.rows[doelUuid]!!.uitvoeringsStatus)
        }

        @Test
        fun `completing an instrument that is afgerond already still starts its gepland subdoel`() {
            val afgerond = instrument(status = "afgerond")
            `when`(openPlan.findInstrument(afgerond.uuid)).thenReturn(afgerond)
            doelDetails.rows[doelUuid] = DoelDetails(doelUuid = doelUuid, planUuid = planUuid, uitvoeringsStatus = UitvoeringsStatus.GEPLAND)

            val resultaat = instrumentLifecycle.afronden(afgerond.uuid, null)

            assertEquals(listOf(doelUuid), resultaat.gestarteSubdoelen.map { it.uuid })
            assertEquals(UitvoeringsStatus.GESTART, doelDetails.rows[doelUuid]!!.uitvoeringsStatus)
            verify(openPlan, never()).closeInstrument(anyValue(), anyValue())
        }
    }

    @Nested
    inner class Plannen {
        private val instrumentAanvraagService = mock(InstrumentAanvraagService::class.java)
        private val service = PlanLifecycleService(
            openPlan, planDetails.proxy(PlanDetailsRepository::class), doelDetails.proxy(DoelDetailsRepository::class), instrumentAanvraagService
        )
        private val persoonUuid = UUID.randomUUID()
        private val werken = DoelType(UUID.randomUUID(), "Werken", listOf(DoelCategorie(naam = "Hoofddoel")))
        private val participeren = DoelType(UUID.randomUUID(), "Participeren", listOf(DoelCategorie(naam = "Hoofddoel")))
        private val werk = DoelType(UUID.randomUUID(), "Werk")

        init {
            planDetails.rows[planUuid] = PlanDetails(planUuid = planUuid, persoonUuid = persoonUuid, caseDefinitionKey = "inwonerplan")
            `when`(openPlan.listDoeltypen()).thenReturn(listOf(werken, participeren, werk))
        }

        @Test
        fun `switching the hoofddoel creates the new one, completes the old one and re-points the subdoelen`() {
            val oud = Doel(uuid = UUID.randomUUID(), doeltype = DoelType(werken.uuid), status = "actief", titel = "Werken")
            val sub = Doel(uuid = doelUuid, doeltype = DoelType(werk.uuid), status = "actief", titel = "Werk", hoofdDoel = oud.uuid)
            `when`(openPlan.getPlan(planUuid)).thenReturn(Plan(uuid = planUuid, status = "actief", notitie = "Toelichting"))
            `when`(openPlan.listDoelen(planUuid)).thenReturn(listOf(oud, sub))
            val nieuwUuid = UUID.randomUUID()
            `when`(openPlan.createDoel(anyValue())).thenAnswer {
                val nieuw = it.arguments[0] as NewDoel
                Doel(uuid = nieuwUuid, doeltype = DoelType(nieuw.doeltypeUuid), status = "actief", titel = nieuw.titel)
            }

            val wissel = service.wisselHoofddoel(planUuid, participeren.uuid)

            assertTrue(wissel.gewijzigd)
            assertEquals("Werken", wissel.van)
            assertEquals("Participeren", wissel.naar)
            verify(openPlan).updateDoelHoofddoel(doelUuid, nieuwUuid)
            assertEquals(UitvoeringsStatus.GESTART, doelDetails.rows[nieuwUuid]!!.uitvoeringsStatus)
            assertEquals(PlanIntakeService.HOOFDDOEL_SORTERING, doelDetails.rows[nieuwUuid]!!.sortering)
            val captor = org.mockito.ArgumentCaptor.forClass(DoelStatusUpdate::class.java)
            verify(openPlan).updateDoelStatus(eq(oud.uuid) ?: oud.uuid, captor.capture() ?: DoelStatusUpdate(""))
            assertEquals("afgerond", captor.value.status)
        }

        @Test
        fun `switching to the hoofddoel the plan already has changes nothing`() {
            val huidig = Doel(uuid = UUID.randomUUID(), doeltype = DoelType(werken.uuid), status = "actief", titel = "Werken")
            val sub = Doel(uuid = doelUuid, doeltype = DoelType(werk.uuid), status = "actief", hoofdDoel = huidig.uuid)
            `when`(openPlan.getPlan(planUuid)).thenReturn(Plan(uuid = planUuid, status = "actief"))
            `when`(openPlan.listDoelen(planUuid)).thenReturn(listOf(huidig, sub))

            val wissel = service.wisselHoofddoel(planUuid, werken.uuid)

            assertFalse(wissel.gewijzigd)
            verify(openPlan, never()).createDoel(anyValue())
            verify(openPlan, never()).updateDoelHoofddoel(anyValue(), anyValue())
        }

        @Test
        fun `reopening is refused while the inwoner has another active plan of the type`() {
            val ander = UUID.randomUUID()
            planDetails.rows[ander] = PlanDetails(planUuid = ander, persoonUuid = persoonUuid, caseDefinitionKey = "inwonerplan")
            `when`(openPlan.getPlan(planUuid)).thenReturn(Plan(uuid = planUuid, status = "afgerond", domeinregister = "urn:pdca:brp:persoon:999993653"))
            `when`(openPlan.findPlan(ander)).thenReturn(Plan(uuid = ander, status = "actief", titel = "Nieuw plan"))

            val fout = assertFailsWith<ResponseStatusException> { service.heractiveren(planUuid) }

            assertEquals(HttpStatus.CONFLICT, fout.statusCode)
            assertTrue(fout.reason!!.contains("Nieuw plan"))
            verify(openPlan, never()).updatePlanStatus(anyValue(), anyValue())
        }

        @Test
        fun `reopening a plan without another active one clears its end`() {
            `when`(openPlan.getPlan(planUuid)).thenReturn(Plan(uuid = planUuid, status = "afgerond", domeinregister = "urn:pdca:brp:persoon:999993653"))

            service.heractiveren(planUuid)

            verify(openPlan).updatePlanStatus(eq(planUuid) ?: planUuid, eq(PlanStatusUpdate.heropend()) ?: PlanStatusUpdate.heropend())
        }

        @Test
        fun `closing is refused while an instrument dossier is open, and warns about the rest`() {
            `when`(openPlan.getPlan(planUuid)).thenReturn(Plan(uuid = planUuid, status = "actief"))
            `when`(openPlan.listDoelen(planUuid)).thenReturn(listOf(Doel(uuid = doelUuid, doeltype = DoelType(werk.uuid), status = "actief")))
            `when`(openPlan.listInstrumentenVanDoelen(listOf(doelUuid))).thenReturn(listOf(instrument(zaak = dossierZaak), instrument(titel = "Eigen")))
            `when`(instrumentAanvraagService.openVerzoeken(planUuid)).thenReturn(emptyList())

            val check = service.afsluitCheck(planUuid)
            val fout = assertFailsWith<ResponseStatusException> { service.afsluiten(planUuid, "afgerond", null) }

            assertEquals(1, check.openSubdoelen)
            assertEquals(1, check.openInstrumenten)
            assertEquals(listOf("Jobcoaching"), check.openInstrumentdossiers)
            assertEquals(0, check.openVerzoeken)
            assertEquals("Sluit eerst de instrumentdossiers af: Jobcoaching", fout.reason)
            verify(openPlan, never()).updatePlanStatus(anyValue(), anyValue())
        }
    }
}
