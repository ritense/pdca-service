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
import com.ritense.pdca.domain.EvaluationChange
import com.ritense.pdca.domain.EvaluationSession
import com.ritense.pdca.domain.EvaluationSessionStatus
import com.ritense.pdca.domain.EvaluationSubjectType
import com.ritense.pdca.domain.InvolvedParty
import com.ritense.pdca.registers.Adres
import com.ritense.pdca.registers.BrpPersoon
import com.ritense.pdca.registers.StubRegisterService
import com.ritense.pdca.registers.openplan.OpenPlanGateway
import com.ritense.pdca.registers.openplan.Plan
import com.ritense.pdca.repository.ContactmomentDetailsRepository
import com.ritense.pdca.repository.InvolvedPartyRepository
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DocumentGegevensServiceTest {

    private val openPlan = mock(OpenPlanGateway::class.java)
    private val stubs = mock(StubRegisterService::class.java)
    private val betrokkenen = InMemoryRepository(InvolvedParty::class, "id")
    private val contactmomenten = InMemoryRepository(ContactmomentDetails::class, "contactmomentUuid")
    private val gzac = mock(GzacClient::class.java)
    private val service = DocumentGegevensService(
        openPlanGateway = openPlan,
        stubRegisterService = stubs,
        involvedPartyRepository = betrokkenen.proxy(InvolvedPartyRepository::class),
        contactmomentDetailsRepository = contactmomenten.proxy(ContactmomentDetailsRepository::class),
        gzacClient = gzac,
        objectMapper = jacksonObjectMapper()
    )

    private val planUuid = UUID.randomUUID()
    private val contactmomentUuid = UUID.randomUUID()
    private val session = EvaluationSession(
        dossierId = "plan-dossier", planUuid = planUuid, userLogin = "jan", status = EvaluationSessionStatus.COMPLETED,
        startedAt = LocalDateTime.of(2026, 11, 11, 10, 30), deelnemers = "Erika, Jan",
        verslag = "Het gaat goed.", contactmomentUuid = contactmomentUuid
    )

    private fun change(
        type: EvaluationSubjectType, subject: UUID, titel: String?, soort: String,
        van: String? = null, naar: String? = null, toelichting: String? = null
    ) = EvaluationChange(
        sessionId = session.id, subjectType = type, subjectUuid = subject, subjectTitel = titel, soort = soort,
        vanWaarde = van, naarWaarde = naar, toelichting = toelichting
    )

    @Test
    fun `the evaluatieverslag carries plan, evaluation, changes per onderdeel and actiepunten`() {
        `when`(openPlan.findPlan(planUuid)).thenReturn(Plan(uuid = planUuid, titel = "Plan Erika", domeinregister = "urn:pdca:brp:persoon:111222333"))
        `when`(stubs.persoon("111222333")).thenReturn(
            BrpPersoon("111222333", "Erika de Goede", "", "", "", Adres("", "", "", ""), "", emptyList())
        )
        betrokkenen.rows[UUID.randomUUID()] = InvolvedParty(planUuid = planUuid, name = "Collega", role = "Werkcoach")
        InvolvedParty(planUuid = planUuid, name = "Mila de Boer", role = "Procesbegeleider", isPrimary = true).let { betrokkenen.rows[it.id] = it }
        contactmomenten.rows[contactmomentUuid] = ContactmomentDetails(
            contactmomentUuid = contactmomentUuid, planUuid = planUuid, actiepunten = """["Cv opsturen", ""]"""
        )
        val subdoel = UUID.randomUUID()
        val instrument = UUID.randomUUID()

        val gegevens = service.evaluatieverslag(
            session,
            listOf(
                change(EvaluationSubjectType.SUBDOEL, subdoel, "Werk", "VOORTGANG", "Op koers", "Let op", "Ziek geweest"),
                change(EvaluationSubjectType.INSTRUMENT, instrument, null, "INSTRUMENT_GESTART"),
                change(EvaluationSubjectType.SUBDOEL, subdoel, "Werk vinden", "INTERNE_NOTITIE", naar = "Alleen voor collega's"),
                change(EvaluationSubjectType.SUBDOEL, subdoel, "Werk vinden", "EXTERNE_NOTITIE", "oud", "Gesprek gepland")
            )
        )

        assertEquals(1, gegevens["schemaVersie"])
        assertEquals("Evaluatieverslag", gegevens["titel"])
        assertEquals(DocumentGegevensService.datum(LocalDate.now()), gegevens["gemaaktOp"])
        assertEquals(mapOf("titel" to "Plan Erika", "inwoner" to "Erika de Goede", "procesbegeleider" to "Mila de Boer"), gegevens["plan"])
        assertEquals(
            mapOf(
                "datum" to "11 november 2026", "uitgevoerdDoor" to "jan",
                "deelnemers" to "Erika, Jan", "verslag" to "Het gaat goed."
            ),
            gegevens["evaluatie"]
        )
        assertEquals(
            listOf(
                mapOf("onderdeel" to "Subdoel: Werk vinden", "omschrijving" to "Voortgang: Op koers → Let op", "toelichting" to "Ziek geweest"),
                mapOf("onderdeel" to "Subdoel: Werk vinden", "omschrijving" to "Externe notitie: Gesprek gepland", "toelichting" to ""),
                mapOf("onderdeel" to "Instrument", "omschrijving" to "Instrument gestart", "toelichting" to "")
            ),
            gegevens["wijzigingen"]
        )
        assertEquals(listOf(mapOf("tekst" to "Cv opsturen")), gegevens["actiepunten"])
    }

    @Test
    fun `an inwoner the registers have no record of is named as in the plan dossier`() {
        `when`(openPlan.findPlan(planUuid)).thenReturn(Plan(uuid = planUuid, titel = "Plan Erika", domeinregister = "urn:pdca:brp:persoon:342357955"))
        `when`(gzac.getDocument("plan-dossier")).thenReturn(mapOf("content" to mapOf("naam" to "Erika de Goede")))

        val plan = service.evaluatieverslag(session, emptyList())["plan"] as Map<*, *>

        assertEquals("Erika de Goede", plan["inwoner"])
    }

    @Test
    fun `internal notes never reach the document`() {
        `when`(openPlan.findPlan(planUuid)).thenReturn(null)
        val gegevens = service.evaluatieverslag(
            session,
            listOf(change(EvaluationSubjectType.PLAN, planUuid, "Plan", "INTERNE_NOTITIE", naar = "Vertrouwelijk"))
        )

        assertEquals(emptyList<Any>(), gegevens["wijzigingen"])
        assertTrue("Vertrouwelijk" !in gegevens.toString())
    }

    @Test
    fun `without data or with failing registers every field is still there, as an empty text`() {
        `when`(openPlan.findPlan(planUuid)).thenThrow(RuntimeException("Open Plan onbereikbaar"))
        val leeg = EvaluationSession(dossierId = "d", planUuid = planUuid, userLogin = "jan", startedAt = LocalDateTime.of(2026, 1, 5, 9, 0))

        val gegevens = service.evaluatieverslag(leeg, emptyList())

        assertEquals(mapOf("titel" to "", "inwoner" to "", "procesbegeleider" to ""), gegevens["plan"])
        assertEquals(
            mapOf("datum" to "5 januari 2026", "uitgevoerdDoor" to "jan", "deelnemers" to "", "verslag" to ""),
            gegevens["evaluatie"]
        )
        assertEquals(emptyList<Any>(), gegevens["wijzigingen"])
        assertEquals(emptyList<Any>(), gegevens["actiepunten"])
        assertEquals("evaluatieverslag-2026-01-05.pdf", service.bestandsnaam(leeg))
    }

    @Test
    fun `a change is described as in the side panel`() {
        fun omschrijving(soort: String, van: String?, naar: String?) = DocumentGegevensService.omschrijving(soort, van, naar)

        assertEquals("Voortgang: Op koers → Let op", omschrijving("VOORTGANG", "Op koers", "Let op"))
        assertEquals("Subdoel toegevoegd: Werk", omschrijving("SUBDOEL_TOEGEVOEGD", null, "Werk"))
        assertEquals("Procesbegeleider: Mila → leeg", omschrijving("PROCESBEGELEIDER", "Mila", ""))
        assertEquals("Planstatus: Vastgesteld", omschrijving("WEERGAVESTATUS", "Vastgesteld", "Vastgesteld"))
        assertEquals("Toelichting bij hoofddoel: Nieuw", omschrijving("HOOFDDOEL_TOELICHTING", "Oud", "Nieuw"))
        assertEquals("Externe notitie verwijderd", omschrijving("EXTERNE_NOTITIE", "Oud", null))
        assertEquals("Subdoel afgerond", omschrijving("SUBDOEL_AFGEROND", null, null))
        assertEquals("ONBEKEND: x", omschrijving("ONBEKEND", null, "x"))
    }
}
