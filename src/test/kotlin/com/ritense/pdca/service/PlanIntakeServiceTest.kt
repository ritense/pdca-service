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

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.ritense.pdca.domain.ContactmomentDetails
import com.ritense.pdca.domain.DoelDetails
import com.ritense.pdca.domain.InvolvedParty
import com.ritense.pdca.domain.PdcaUrn
import com.ritense.pdca.domain.PhaseConfig
import com.ritense.pdca.domain.PlanDetails
import com.ritense.pdca.domain.PlanIntake
import com.ritense.pdca.domain.PlanIntakeStatus
import com.ritense.pdca.domain.UitvoeringsStatus
import com.ritense.pdca.registers.Register
import com.ritense.pdca.registers.RegisterErrors
import com.ritense.pdca.registers.openplan.Doel
import com.ritense.pdca.registers.openplan.DoelCategorie
import com.ritense.pdca.registers.openplan.DoelType
import com.ritense.pdca.registers.openplan.NewDoel
import com.ritense.pdca.registers.openplan.NewPlan
import com.ritense.pdca.registers.openplan.OpenPlanGateway
import com.ritense.pdca.registers.openplan.OverkoepelendPlan
import com.ritense.pdca.registers.openplan.Persoon
import com.ritense.pdca.registers.openplan.Plan
import com.ritense.pdca.registers.openplan.PlanType
import com.ritense.pdca.repository.ContactmomentDetailsRepository
import com.ritense.pdca.repository.DoelDetailsRepository
import com.ritense.pdca.repository.InvolvedPartyRepository
import com.ritense.pdca.repository.PlanDetailsRepository
import com.ritense.pdca.repository.PlanIntakeRepository
import com.ritense.pdca.web.rest.dto.IntakeBetrokkene
import com.ritense.pdca.web.rest.dto.IntakeContactmoment
import com.ritense.pdca.web.rest.dto.IntakeDoel
import com.ritense.pdca.web.rest.dto.IntakeInstrument
import com.ritense.pdca.web.rest.dto.PlanIntakeRequest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlanIntakeServiceTest {

    private val gzacClient = mock(GzacClient::class.java)
    private val phaseConfigService = mock(PhaseConfigService::class.java)
    private val dossierLinkService = mock(DossierLinkService::class.java)
    private val instrumentAanvraagService = mock(InstrumentAanvraagService::class.java)
    private val planOverlayService = mock(PlanOverlayService::class.java)
    private val openPlan = mock(OpenPlanGateway::class.java)

    private val intakes = InMemoryRepository(PlanIntake::class, "intakeDossierId")
    private val planDetails = InMemoryRepository(PlanDetails::class, "planUuid")
    private val doelDetails = InMemoryRepository(DoelDetails::class, "doelUuid")
    private val involvedParties = InMemoryRepository(InvolvedParty::class, "id")
    private val contactmomentDetails = InMemoryRepository(ContactmomentDetails::class, "contactmomentUuid")
    private val klantcontactService = mock(KlantcontactService::class.java)

    /** Open Klant's klantcontacten as the fake register keeps them. */
    private val klantcontacten = mutableListOf<Klantcontact>()

    /** Open Plan's state as the fake register keeps it. */
    private val plannen = linkedMapOf<UUID, Plan>()
    private val doelen = mutableListOf<Doel>()
    private val verzoeken = mutableListOf<InstrumentAanvraagService.VerzoekRecord>()

    /** Step names that fail (once) with an unavailable register. */
    private val falen = mutableSetOf<String>()

    private val werk = DoelType(UUID.randomUUID(), "Werk")
    private val taal = DoelType(UUID.randomUUID(), "Taal")
    private val hoofddoel = DoelType(UUID.randomUUID(), "Werken", listOf(DoelCategorie(naam = DoelType.HOOFDDOEL_CATEGORIE)))
    private val persoonUuid = UUID.randomUUID()

    private lateinit var service: PlanIntakeService

    @BeforeEach
    fun setUp() {
        service = PlanIntakeService(
            gzacClient = gzacClient,
            dossierLinkService = dossierLinkService,
            phaseConfigService = phaseConfigService,
            planIntakeRepository = intakes.proxy(
                PlanIntakeRepository::class,
                // Each statement answers the row after it, or null when its WHERE does not match.
                mapOf<String, (Array<Any?>) -> PlanIntake?>(
                    "reserve" to { args ->
                        val id = args[0] as String
                        if (intakes.rows.containsKey(id)) null else PlanIntake(intakeDossierId = id)
                    },
                    "claim" to { args ->
                        val nu = args[2] as LocalDateTime
                        matching(args[0]) { it.status == PlanIntakeStatus.IN_AANMAAK && (it.claimedUntil?.isBefore(nu) ?: true) }
                            ?.let { it.copy(claimToken = args[1] as String, claimedUntil = args[3] as LocalDateTime) }
                    },
                    "renew" to { args ->
                        matching(args[0]) { it.claimToken == args[1] && it.status == PlanIntakeStatus.IN_AANMAAK }
                            ?.let { it.copy(claimedUntil = args[3] as LocalDateTime) }
                    },
                    "recordPlan" to { args ->
                        matching(args[0]) { it.claimToken == args[1] && it.status == PlanIntakeStatus.IN_AANMAAK }
                            ?.let { it.copy(planUuid = args[2] as UUID) }
                    },
                    "complete" to { args ->
                        matching(args[0]) { it.claimToken == args[1] && it.status == PlanIntakeStatus.IN_AANMAAK }
                            ?.let { it.copy(status = PlanIntakeStatus.GEREED, claimToken = null, claimedUntil = null) }
                    },
                    "release" to { args ->
                        matching(args[0]) { it.claimToken == args[1] }?.let { it.copy(claimToken = null, claimedUntil = null) }
                    },
                    "reopen" to { args ->
                        matching(args[0]) { it.status == PlanIntakeStatus.GEREED && it.planUuid == args[1] }
                            ?.let { it.copy(status = PlanIntakeStatus.IN_AANMAAK, planUuid = null) }
                    },
                    "reopenWithoutPlan" to { args ->
                        matching(args[0]) { it.status == PlanIntakeStatus.GEREED && it.planUuid == null }
                            ?.let { it.copy(status = PlanIntakeStatus.IN_AANMAAK) }
                    }
                ).mapValues { (_, update) -> { args: Array<Any?> -> update(args)?.let { intakes.rows[it.intakeDossierId] = it; 1 } ?: 0 } }
            ),
            planDetailsRepository = planDetails.proxy(PlanDetailsRepository::class),
            doelDetailsRepository = doelDetails.proxy(DoelDetailsRepository::class),
            contactmomentDetailsRepository = contactmomentDetails.proxy(ContactmomentDetailsRepository::class),
            involvedPartyRepository = involvedParties.proxy(InvolvedPartyRepository::class),
            klantcontactService = klantcontactService,
            instrumentAanvraagService = instrumentAanvraagService,
            planOverlayService = planOverlayService,
            openPlanGateway = openPlan,
            transactionTemplate = noOpTransactions(),
            objectMapper = jacksonObjectMapper()
        )

        `when`(gzacClient.getDocument(INTAKE)).thenReturn(mapOf("definitionId" to mapOf("name" to "intake-werk-participatie")))
        `when`(phaseConfigService.findByCaseDefinitionKey("intake-werk-participatie")).thenReturn(
            PhaseConfig(caseDefinitionKey = "intake-werk-participatie", evaluationTypes = "[]", planCaseDefinitionKey = "inwonerplan")
        )
        `when`(phaseConfigService.getPositieTypen("inwonerplan")).thenReturn(null)
        `when`(phaseConfigService.getPlanStatussen("inwonerplan")).thenReturn(null)

        `when`(openPlan.listDoeltypen()).thenReturn(listOf(werk, taal, hoofddoel))
        `when`(openPlan.findPersoonByBsn(BSN)).thenReturn(Persoon(persoonUuid, BSN))
        `when`(openPlan.listPlantypen()).thenReturn(listOf(PlanType(UUID.randomUUID(), "Werk en inkomen")))
        `when`(openPlan.firstOverkoepelendPlan()).thenReturn(OverkoepelendPlan(uuid = UUID.randomUUID()))
        `when`(openPlan.findPlan(anyValue())).thenAnswer { plannen[it.arguments[0]] }
        `when`(openPlan.listPlannenOver(anyValue())).thenAnswer { inv -> plannen.values.filter { it.domeinregister == inv.arguments[0] } }
        `when`(openPlan.createPlan(anyValue())).thenAnswer {
            val nieuw = it.arguments[0] as NewPlan
            val plan = Plan(uuid = UUID.randomUUID(), status = "actief", titel = nieuw.titel, domeinregister = nieuw.domeinregister)
            plannen[plan.uuid] = plan
            faal("plan-antwoord")
            plan
        }
        `when`(openPlan.listDoelen(anyValue())).thenAnswer { doelen.toList() }
        `when`(openPlan.createDoel(anyValue())).thenAnswer {
            val nieuw = it.arguments[0] as NewDoel
            faal("doel-${nieuw.titel}")
            Doel(
                uuid = UUID.randomUUID(),
                doeltype = DoelType(nieuw.doeltypeUuid, nieuw.titel),
                status = nieuw.status ?: "actief",
                titel = nieuw.titel,
                hoofdDoel = nieuw.hoofdDoel
            ).also { doel -> doelen += doel }
        }
        `when`(dossierLinkService.ensurePlanDossier(anyValue(), anyValue())).thenAnswer {
            faal("dossier")
            val planUuid = it.arguments[0] as UUID
            planDetails.rows[planUuid]!!.dossierId = "plan-dossier"
            "plan-dossier" to true
        }
        `when`(klantcontactService.ofPlan(anyValue())).thenAnswer { inv -> klantcontacten.filter { it.planUuid == inv.arguments[0] } }
        `when`(klantcontactService.evaluatieTypeOf(anyValue())).thenCallRealMethod()
        `when`(klantcontactService.create(anyValue())).thenAnswer {
            val nieuw = it.arguments[0] as NewContactmoment
            val label = mapOf("EVALUATION" to "Evaluatie")[nieuw.evaluatieType] ?: nieuw.evaluatieType
            klantcontacten += Klantcontact(
                uuid = UUID.randomUUID(),
                planUuid = nieuw.planUuid,
                onderwerp = "$label: Plan",
                indicatieContactGelukt = if (nieuw.afgerond) true else null,
                plaatsgevondenOp = nieuw.plaatsgevondenOp
            )
            faal("klantcontact-antwoord")
            klantcontacten.last().uuid
        }
        `when`(instrumentAanvraagService.verzoekenVoor(anyValue())).thenAnswer { inv -> verzoeken.filter { it.doelUuid == inv.arguments[0] } }
        `when`(instrumentAanvraagService.startByCode(anyValue(), anyValue())).thenAnswer {
            val doelUuid = it.arguments[0] as UUID
            val code = it.arguments[1] as String
            faal("verzoek-$code")
            verzoeken += InstrumentAanvraagService.VerzoekRecord(
                null, null, doelUuid, PdcaUrn.producttype(code).toString(), code, OffsetDateTime.now()
            )
            InstrumentAanvraagService.Verzoek("http://objecten/${verzoeken.size}", code)
        }
    }

    @Test
    fun `the whole plan is built and the intake is GEREED`() {
        val response = service.createPlanFromIntake(request(IntakeInstrument("TAALCOACH", "Taal"), IntakeInstrument("JOBCOACH", "Werk")))

        assertEquals(1, plannen.size)
        assertEquals("plan-dossier", response.planDossierId)
        assertEquals(2, response.doelen)
        assertEquals(2, response.instrumentenAangevraagd)
        assertFalse(response.bestaand)
        assertFalse(response.hervat)
        assertTrue(response.warnings.isEmpty())
        assertEquals(3, doelen.size)
        val hoofddoelDoel = doelen.single { it.doeltype?.uuid == hoofddoel.uuid }
        assertTrue(doelen.filter { it != hoofddoelDoel }.all { it.hoofdDoel == hoofddoelDoel.uuid })
        assertEquals(UitvoeringsStatus.GESTART, doelDetails.rows[hoofddoelDoel.uuid]!!.uitvoeringsStatus)
        assertEquals(INTAKE, planDetails.rows.values.single().intakeDossierId)
        assertEquals(1, involvedParties.rows.size)
        val reservering = intakes.rows[INTAKE]!!
        assertEquals(PlanIntakeStatus.GEREED, reservering.status)
        assertEquals(null, reservering.claimedUntil)
    }

    @Test
    fun `a retry after the plan dossier failed resumes without a second plan or doel`() {
        falen += "dossier"
        assertFailsWith<ResponseStatusException> { service.createPlanFromIntake(request(IntakeInstrument("JOBCOACH", "Werk"))) }
        assertEquals(PlanIntakeStatus.IN_AANMAAK, intakes.rows[INTAKE]!!.status)
        assertEquals(null, intakes.rows[INTAKE]!!.claimedUntil, "a failed attempt releases its claim")
        verify(instrumentAanvraagService, never()).startByCode(anyValue(), anyValue())

        val response = service.createPlanFromIntake(request(IntakeInstrument("JOBCOACH", "Werk")))

        assertTrue(response.hervat)
        assertEquals("plan-dossier", response.planDossierId)
        assertEquals(1, plannen.size)
        assertEquals(3, doelen.size)
        assertEquals(1, involvedParties.rows.size)
        assertEquals(1, response.instrumentenAangevraagd)
        assertEquals(PlanIntakeStatus.GEREED, intakes.rows[INTAKE]!!.status)
    }

    @Test
    fun `a retry after the second subdoel failed creates only that subdoel`() {
        falen += "doel-Taal"
        assertFailsWith<ResponseStatusException> { service.createPlanFromIntake(request()) }
        assertEquals(2, doelen.size)

        service.createPlanFromIntake(request())

        assertEquals(1, doelen.count { it.titel == "Werk" })
        assertEquals(1, doelen.count { it.titel == "Taal" })
        assertEquals(1, plannen.size)
    }

    @Test
    fun `a retry after a failed verzoek files only the missing one`() {
        falen += "verzoek-JOBCOACH"
        assertFailsWith<ResponseStatusException> {
            service.createPlanFromIntake(request(IntakeInstrument("TAALCOACH", "Taal"), IntakeInstrument("JOBCOACH", "Werk")))
        }
        assertEquals(PlanIntakeStatus.IN_AANMAAK, intakes.rows[INTAKE]!!.status)

        val response = service.createPlanFromIntake(request(IntakeInstrument("TAALCOACH", "Taal"), IntakeInstrument("JOBCOACH", "Werk")))

        assertEquals(2, response.instrumentenAangevraagd)
        verify(instrumentAanvraagService, times(1)).startByCode(anyValue(), eqValue("TAALCOACH"))
        assertEquals(2, verzoeken.size)
    }

    @Test
    fun `a plan whose creation answer got lost is adopted instead of created again`() {
        falen += "plan-antwoord"
        assertFailsWith<ResponseStatusException> { service.createPlanFromIntake(request()) }
        assertEquals(1, plannen.size)

        service.createPlanFromIntake(request())

        assertEquals(1, plannen.size)
        assertEquals(plannen.keys.single(), planDetails.rows.keys.single())
    }

    @Test
    fun `a second request while one is working gets a 409`() {
        intakes.rows[INTAKE] = PlanIntake(intakeDossierId = INTAKE, claimedUntil = LocalDateTime.now().plusMinutes(5))

        val fout = assertFailsWith<ResponseStatusException> { service.createPlanFromIntake(request()) }

        assertEquals(HttpStatus.CONFLICT, fout.statusCode)
        assertEquals("Het plan wordt al aangemaakt; wacht even en probeer het dan opnieuw", fout.reason)
        verify(openPlan, never()).createPlan(anyValue())
    }

    @Test
    fun `only a GEREED plan is reported as existing`() {
        val planUuid = UUID.randomUUID()
        plannen[planUuid] = Plan(uuid = planUuid, status = "actief", titel = "Plan")
        planDetails.rows[planUuid] = PlanDetails(planUuid = planUuid, persoonUuid = persoonUuid, intakeDossierId = INTAKE)
        intakes.rows[INTAKE] = PlanIntake(intakeDossierId = INTAKE, planUuid = planUuid)

        // IN_AANMAAK: the plan is resumed (and completed), not reported.
        val hervat = service.createPlanFromIntake(request())
        assertFalse(hervat.bestaand)
        assertTrue(hervat.hervat)
        assertEquals(planUuid.toString(), hervat.planUuid)

        // GEREED: reported as existing, nothing is done.
        val bestaand = service.createPlanFromIntake(request())
        assertTrue(bestaand.bestaand)
        assertEquals("plan-dossier", bestaand.planDossierId)
        verify(dossierLinkService, times(1)).ensurePlanDossier(anyValue(), anyValue())
    }

    @Test
    fun `everything is validated before the first write`() {
        val fout = assertFailsWith<ResponseStatusException> {
            service.createPlanFromIntake(
                request().copy(
                    startdatum = "1-2-2026",
                    doelen = listOf(IntakeDoel(doelType = "Onbekend"), IntakeDoel(doelType = "Werk", status = "bezig")),
                    betrokkenen = listOf(IntakeBetrokkene(naam = " ", rol = "Coach"))
                )
            )
        }

        assertEquals(HttpStatus.BAD_REQUEST, fout.statusCode)
        val reden = fout.reason!!
        assertTrue(reden.contains("startdatum '1-2-2026'"), reden)
        assertTrue(reden.contains("subdoel 'Onbekend' is onbekend"), reden)
        assertTrue(reden.contains("onbekende status 'bezig'"), reden)
        assertTrue(reden.contains("betrokkene 1 heeft geen naam"), reden)
        assertTrue(intakes.rows.isEmpty(), "nothing is reserved")
        verify(openPlan, never()).createPlan(anyValue())
        verify(openPlan, never()).createPersoon(anyValue())
    }

    @Test
    fun `an instrument row with an ambiguous or unknown subdoel becomes a warning`() {
        val response = service.createPlanFromIntake(
            request(IntakeInstrument("JOBCOACH", "Werk"), IntakeInstrument("TAALCOACH", "Rekenen")).copy(
                doelen = listOf(IntakeDoel(doelType = "Werk"), IntakeDoel(doelType = "Werk"))
            )
        )

        assertEquals(1, response.instrumentenAangevraagd)
        assertEquals(
            listOf(
                "Instrument 'JOBCOACH': het plan heeft 2 subdoelen 'Werk'; aangevraagd onder het eerste",
                "Instrument 'TAALCOACH' niet aangevraagd: subdoel 'Rekenen' komt niet voor in het plan"
            ),
            response.warnings
        )
        assertEquals(2, doelen.count { it.titel == "Werk" })
    }

    @Test
    fun `a refused verzoek is a warning and the plan is still GEREED`() {
        doThrow(ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Producttype 'Zonder actie' kan niet worden aangevraagd"))
            .`when`(instrumentAanvraagService).startByCode(anyValue(), eqValue("ZONDERACTIE"))

        val response = service.createPlanFromIntake(request(IntakeInstrument("ZONDERACTIE", "Werk")))

        assertEquals(listOf("Instrument 'ZONDERACTIE' niet aangevraagd: Producttype 'Zonder actie' kan niet worden aangevraagd"), response.warnings)
        assertEquals(PlanIntakeStatus.GEREED, intakes.rows[INTAKE]!!.status)
    }

    @Test
    fun `two rows for the same subdoel and product are requested once, with a warning`() {
        val response = service.createPlanFromIntake(request(IntakeInstrument("JOBCOACH", "Werk"), IntakeInstrument("JOBCOACH", "Werk")))

        assertEquals(1, response.instrumentenAangevraagd)
        assertEquals(listOf("Instrument 'JOBCOACH' staat meer dan eens onder subdoel 'Werk'; één keer aangevraagd"), response.warnings)
        verify(instrumentAanvraagService, times(1)).startByCode(anyValue(), eqValue("JOBCOACH"))
    }

    @Test
    fun `a retry finds the contactmoment of a configured evaluation type instead of creating it again`() {
        val contactmoment = IntakeContactmoment(datum = "2026-03-02", evaluatieType = "NAZORG", status = "gepland")
        falen += "klantcontact-antwoord"
        assertFailsWith<ResponseStatusException> { service.createPlanFromIntake(request().copy(contactmomenten = listOf(contactmoment))) }
        assertEquals(1, klantcontacten.size)
        assertTrue(contactmomentDetails.rows.isEmpty(), "the attempt failed before the overlay row")

        service.createPlanFromIntake(request().copy(contactmomenten = listOf(contactmoment)))

        assertEquals(1, klantcontacten.size)
        assertEquals("NAZORG", contactmomentDetails.rows.values.single().evaluatieType)
        assertEquals(klantcontacten.single().uuid, contactmomentDetails.rows.keys.single())
    }

    @Test
    fun `a request whose claim was taken over stops without completing or releasing the new holder`() {
        doAnswer {
            // The lease ran out meanwhile and another request claimed the intake.
            intakes.rows[INTAKE] = intakes.rows[INTAKE]!!.copy(claimToken = "ander", claimedUntil = LocalDateTime.now().plusMinutes(10))
            "plan-dossier" to true
        }.`when`(dossierLinkService).ensurePlanDossier(anyValue(), anyValue())

        val fout = assertFailsWith<ResponseStatusException> { service.createPlanFromIntake(request(IntakeInstrument("JOBCOACH", "Werk"))) }

        assertEquals(HttpStatus.CONFLICT, fout.statusCode)
        val reservering = intakes.rows[INTAKE]!!
        assertEquals(PlanIntakeStatus.IN_AANMAAK, reservering.status)
        assertEquals("ander", reservering.claimToken, "the new holder keeps its claim")
        verify(instrumentAanvraagService, never()).startByCode(anyValue(), anyValue())
    }

    /** The row [id] when it matches [condition] (the WHERE of a conditional UPDATE); null when it does not. */
    private fun matching(id: Any?, condition: (PlanIntake) -> Boolean): PlanIntake? =
        intakes.rows[id]?.takeIf(condition)

    private fun faal(stap: String) {
        if (falen.remove(stap)) throw RegisterErrors.unavailable(Register.OPENPLAN)
    }

    private fun request(vararg instrumenten: IntakeInstrument) = PlanIntakeRequest(
        documentId = INTAKE,
        subjectType = "PERSON",
        subjectId = BSN,
        titel = "Plan",
        hoofddoel = "Werken",
        dienstverlening = "Werk en inkomen",
        doelen = listOf(IntakeDoel(doelType = "Werk"), IntakeDoel(doelType = "Taal")),
        instrumenten = instrumenten.toList(),
        betrokkenen = listOf(IntakeBetrokkene(naam = "M. de Boer", rol = "Procesbegeleider", procesbegeleider = true))
    )

    private companion object {
        const val BSN = "999993653"
        const val INTAKE = "intake-dossier"
    }
}
