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
import com.ritense.pdca.domain.DocumentAanvraag
import com.ritense.pdca.domain.DocumentAanvraagStatus
import com.ritense.pdca.domain.DocumentSoort
import com.ritense.pdca.domain.EvaluationChange
import com.ritense.pdca.domain.EvaluationSession
import com.ritense.pdca.domain.EvaluationSessionStatus
import com.ritense.pdca.plugin.GzacAccess
import com.ritense.pdca.plugin.PluginConfiguration
import com.ritense.pdca.repository.DocumentAanvraagRepository
import com.ritense.pdca.repository.DocumentAanvraagSummary
import com.ritense.pdca.repository.EvaluationChangeRepository
import com.ritense.pdca.repository.EvaluationSessionRepository
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDateTime
import java.util.Base64
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DocumentAanvraagServiceTest {

    private val objectMapper = jacksonObjectMapper()
    private val aanvragen = InMemoryRepository(DocumentAanvraag::class, "id")
    private val sessions = InMemoryRepository(EvaluationSession::class, "id")
    private val changes = InMemoryRepository(EvaluationChange::class, "id")
    private val gegevensService = mock(DocumentGegevensService::class.java)
    private val gzacClient = mock(GzacClient::class.java)
    private val gzacAccess = mock(GzacAccess::class.java)

    private val open = setOf(DocumentAanvraagStatus.AANGEVRAAGD, DocumentAanvraagStatus.BEZIG)
    private fun rows() = aanvragen.rows.values

    /** The repository's conditional statements, answered from the in-memory rows like PostgreSQL would. */
    private val repository = aanvragen.proxy(
        DocumentAanvraagRepository::class,
        mapOf(
            "findForProcess" to { args -> rows().singleOrNull { it.processInstanceId == args[0] } },
            "findClaimable" to { args ->
                rows().filter {
                    it.dossierId == args[0] && it.status == DocumentAanvraagStatus.AANGEVRAAGD &&
                        it.processInstanceId == null && it.aangevraagdOp.isAfter(args[1] as LocalDateTime)
                }.minByOrNull { it.aangevraagdOp }
            },
            "findFirstBySoortAndBronUuidAndStatusIn" to { args ->
                rows().firstOrNull { it.soort == args[0] && it.bronUuid == args[1] && it.status in (args[2] as Collection<*>) }
            },
            "recordProcess" to { args ->
                val row = rows().firstOrNull { it.id == args[0] && it.processInstanceId == null }
                if (row == null || rows().any { it.processInstanceId == args[1] }) 0
                else { row.processInstanceId = args[1] as String; 1 }
            },
            "failUnclaimed" to { args ->
                val row = rows().firstOrNull {
                    it.id == args[0] && it.status == DocumentAanvraagStatus.AANGEVRAAGD && it.processInstanceId == null
                }
                row?.apply { status = DocumentAanvraagStatus.MISLUKT; foutmelding = args[1] as String; gegevens = null }
                if (row == null) 0 else 1
            },
            "expireOpen" to { args ->
                val stale = rows().filter { it.status in open && it.bijgewerktOp.isBefore(args[0] as LocalDateTime) }
                stale.forEach { it.status = DocumentAanvraagStatus.MISLUKT; it.foutmelding = args[1] as String; it.gegevens = null }
                stale.size
            },
            "deleteEndedBefore" to { args ->
                val ended = rows().filter {
                    it.soort == args[0] && it.bronUuid == args[1] && it.id != args[2] &&
                        it.aangevraagdOp.isBefore(args[3] as LocalDateTime) && it.status !in open
                }
                ended.forEach { aanvragen.rows.remove(it.id) }
                ended.size
            },
            "findSummariesByPlanUuid" to { args ->
                rows().filter { it.planUuid == args[0] }.map {
                    DocumentAanvraagSummary(
                        it.id, it.planUuid, it.soort, it.bronUuid, it.status, it.aangevraagdOp, it.bijgewerktOp,
                        it.bestandsnaam, it.foutmelding
                    )
                }
            }
        )
    )

    private val service = DocumentAanvraagService(
        repository = repository,
        evaluationSessionRepository = sessions.proxy(EvaluationSessionRepository::class),
        evaluationChangeRepository = changes.proxy(EvaluationChangeRepository::class),
        documentGegevensService = gegevensService,
        gzacClient = gzacClient,
        gzacAccess = gzacAccess,
        objectMapper = objectMapper,
        transactionTemplate = noOpTransactions()
    )

    private val planUuid = UUID.randomUUID()
    private val dossier = "plan-dossier"
    private val pdf = "%PDF-1.7\nverslag".toByteArray()

    private fun configuratie(properties: Map<String, Any>) {
        `when`(gzacAccess.active()).thenReturn(
            GzacAccess.Access("http://gzac", "token", PluginConfiguration("config", "PDCA", properties, "token", "http://gzac"))
        )
    }

    private fun afgerond(contactmoment: UUID? = null): EvaluationSession {
        val session = EvaluationSession(
            dossierId = dossier, planUuid = planUuid, userLogin = "jan", status = EvaluationSessionStatus.COMPLETED,
            startedAt = LocalDateTime.of(2026, 11, 11, 10, 0), contactmomentUuid = contactmoment
        )
        sessions.rows[session.id] = session
        `when`(gegevensService.evaluatieverslag(session, emptyList())).thenReturn(mapOf("titel" to "Evaluatieverslag"))
        `when`(gegevensService.bestandsnaam(session)).thenReturn("evaluatieverslag-2026-11-11.pdf")
        return session
    }

    private fun aanvraag(
        status: DocumentAanvraagStatus,
        aangevraagdOp: LocalDateTime = LocalDateTime.now(),
        bronUuid: UUID = UUID.randomUUID(),
        processInstanceId: String? = null
    ) = DocumentAanvraag(
        planUuid = planUuid, dossierId = dossier, soort = DocumentSoort.EVALUATIEVERSLAG, bronUuid = bronUuid, status = status,
        processInstanceId = processInstanceId, gegevens = """{"titel":"Evaluatieverslag"}""", bestandsnaam = "evaluatieverslag.pdf",
        aangevraagdOp = aangevraagdOp, bijgewerktOp = aangevraagdOp
    ).also { aanvragen.rows[it.id] = it }

    private fun gereed(aanvraag: DocumentAanvraag, vararg properties: Pair<String, Any?>, proces: String? = aanvraag.processInstanceId) =
        service.recordOutcome(dossier, proces, mapOf("aanvraagId" to aanvraag.id.toString(), *properties))

    private fun pdfBase64() = Base64.getEncoder().encodeToString(pdf)

    @Test
    fun `the document process defaults to pdca-document and a blank one turns generation off`() {
        configuratie(emptyMap())
        assertEquals("pdca-document", service.documentProcess())
        assertTrue(service.enabled())

        configuratie(mapOf("documentProces" to "eigen-proces"))
        assertEquals("eigen-proces", service.documentProcess())

        configuratie(mapOf("documentProces" to " "))
        assertNull(service.documentProcess())
        assertTrue(!service.enabled())
        val session = afgerond()
        val fout = assertFailsWith<ResponseStatusException> { service.requestEvaluatieverslag(session.id, "jan") }
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, fout.statusCode)
        assertNull(service.prepareEvaluatieverslag(session, null))
    }

    @Test
    fun `completing prepares the request with its data, which is recorded and then started on the plan dossier`() {
        configuratie(emptyMap())
        val session = afgerond(UUID.randomUUID())
        `when`(gzacClient.startProcessOnDossier("pdca-document", dossier)).thenReturn("proces-1")

        val prepared = service.prepareEvaluatieverslag(session, session.contactmomentUuid)!!
        assertTrue(aanvragen.rows.isEmpty(), "preparing records nothing")
        val started = service.startProcess(service.record(prepared))

        assertEquals(DocumentAanvraagStatus.AANGEVRAAGD, started.status)
        assertEquals("proces-1", started.processInstanceId)
        assertEquals(session.id, started.bronUuid)
        assertEquals(dossier, started.dossierId)
        assertEquals("jan", started.aangevraagdDoor)
        assertEquals("evaluatieverslag-2026-11-11.pdf", started.bestandsnaam)
        assertEquals(mapOf("titel" to "Evaluatieverslag"), objectMapper.readValue(started.gegevens, Map::class.java))
    }

    @Test
    fun `a request whose process cannot be started fails, unless a process claimed it meanwhile`() {
        configuratie(emptyMap())
        `when`(gzacClient.startProcessOnDossier("pdca-document", dossier))
            .thenThrow(ResponseStatusException(HttpStatus.BAD_GATEWAY, "GZAC kon het documentproces 'pdca-document' niet starten"))

        val unclaimed = service.startProcess(aanvraag(DocumentAanvraagStatus.AANGEVRAAGD))
        assertEquals(DocumentAanvraagStatus.MISLUKT, unclaimed.status)
        assertEquals("Het documentproces kon niet worden gestart", unclaimed.foutmelding)
        assertNull(unclaimed.gegevens)

        val claimed = service.startProcess(aanvraag(DocumentAanvraagStatus.BEZIG, processInstanceId = "proces-1"))
        assertEquals(DocumentAanvraagStatus.BEZIG, claimed.status)
    }

    @Test
    fun `making a verslag again returns the request still being made instead of a second one`() {
        configuratie(emptyMap())
        val session = afgerond()
        val bezig = aanvraag(DocumentAanvraagStatus.BEZIG, bronUuid = session.id, processInstanceId = "proces-1")

        assertSame(bezig, service.requestEvaluatieverslag(session.id, "piet"))
        assertEquals(1, aanvragen.rows.size)
        verify(gzacClient, never()).startProcessOnDossier(anyValue(), anyValue())
    }

    @Test
    fun `only a completed evaluation gets a document`() {
        configuratie(emptyMap())
        val lopend = EvaluationSession(dossierId = dossier, planUuid = planUuid, userLogin = "jan")
        sessions.rows[lopend.id] = lopend

        assertEquals(HttpStatus.BAD_REQUEST, assertFailsWith<ResponseStatusException> { service.requestEvaluatieverslag(lopend.id, "jan") }.statusCode)
        assertEquals(HttpStatus.NOT_FOUND, assertFailsWith<ResponseStatusException> { service.requestEvaluatieverslag(UUID.randomUUID(), "jan") }.statusCode)
    }

    @Test
    fun `a process claims the request its start recorded, also when an older one is open`() {
        aanvraag(DocumentAanvraagStatus.AANGEVRAAGD, LocalDateTime.now().minusMinutes(2))
        val eigen = aanvraag(DocumentAanvraagStatus.AANGEVRAAGD, LocalDateTime.now().minusMinutes(1), processInstanceId = "proces-2")

        val variables = variables(service.claim(dossier, "proces-2"))

        assertEquals(eigen.id.toString(), variables["pdcaAanvraagId"])
        assertEquals("evaluatieverslag.pdf", variables["pdcaBestandsnaam"])
        assertEquals(mapOf("titel" to "Evaluatieverslag"), variables["pdcaGegevens"])
        assertEquals(DocumentAanvraagStatus.BEZIG, eigen.status)
    }

    @Test
    fun `without a recorded request a process claims the oldest young unclaimed one, and the same one on a retry`() {
        val oud = aanvraag(DocumentAanvraagStatus.AANGEVRAAGD, LocalDateTime.now().minusMinutes(2))
        val nieuw = aanvraag(DocumentAanvraagStatus.AANGEVRAAGD, LocalDateTime.now().minusMinutes(1))
        aanvraag(DocumentAanvraagStatus.AANGEVRAAGD, LocalDateTime.now().minusMinutes(11))
        aanvraag(DocumentAanvraagStatus.AANGEVRAAGD, LocalDateTime.now().minusMinutes(3), processInstanceId = "proces-elders")

        assertEquals(oud.id.toString(), variables(service.claim(dossier, "proces-1"))["pdcaAanvraagId"])
        assertEquals("proces-1", oud.processInstanceId)
        assertEquals(oud.id.toString(), variables(service.claim(dossier, "proces-1"))["pdcaAanvraagId"])
        assertEquals(nieuw.id.toString(), variables(service.claim(dossier, "proces-2"))["pdcaAanvraagId"])

        assertEquals("GEEN_AANVRAAG", assertFailsWith<PluginActionException> { service.claim(dossier, "proces-3") }.errorCode)
        assertEquals("GEEN_AANVRAAG", assertFailsWith<PluginActionException> { service.claim("ander-dossier", "proces-4") }.errorCode)
    }

    @Test
    fun `a start answering after the process claimed another request records nothing`() {
        configuratie(emptyMap())
        val eerste = aanvraag(DocumentAanvraagStatus.AANGEVRAAGD, LocalDateTime.now().minusMinutes(2))
        val tweede = aanvraag(DocumentAanvraagStatus.AANGEVRAAGD, LocalDateTime.now().minusMinutes(1))
        service.claim(dossier, "proces-2")
        `when`(gzacClient.startProcessOnDossier("pdca-document", dossier)).thenReturn("proces-2")

        service.startProcess(tweede)

        assertEquals("proces-2", eerste.processInstanceId)
        assertNull(tweede.processInstanceId, "still claimable by the other process")
    }

    @Test
    fun `a request of an ended or foreign process is not handed out`() {
        aanvraag(DocumentAanvraagStatus.GEREED, processInstanceId = "proces-1")
        aanvraag(DocumentAanvraagStatus.BEZIG, processInstanceId = "proces-2")

        assertEquals("GEEN_AANVRAAG", assertFailsWith<PluginActionException> { service.claim(dossier, "proces-1") }.errorCode)
        assertEquals("AANVRAAG_HOORT_NIET_BIJ_PROCES", assertFailsWith<PluginActionException> { service.claim("ander-dossier", "proces-2") }.errorCode)
        assertEquals("GEEN_AANVRAAG", assertFailsWith<PluginActionException> { service.claim(dossier, null) }.errorCode)
    }

    @Test
    fun `a finished document is stored and replaces the older ended ones of the same evaluation`() {
        val bron = UUID.randomUUID()
        val eerder = aanvraag(DocumentAanvraagStatus.GEREED, LocalDateTime.now().minusDays(1), bron)
        val eerderMislukt = aanvraag(DocumentAanvraagStatus.MISLUKT, LocalDateTime.now().minusHours(1), bron)
        val anderVerslag = aanvraag(DocumentAanvraagStatus.GEREED, LocalDateTime.now().minusDays(1))
        val aanvraag = aanvraag(DocumentAanvraagStatus.BEZIG, bronUuid = bron, processInstanceId = "proces-1")

        gereed(aanvraag, "uitkomst" to "GEREED", "inhoud" to pdfBase64(), "bestandsnaam" to "verslag.pdf")

        assertEquals(DocumentAanvraagStatus.GEREED, aanvraag.status)
        assertTrue(pdf.contentEquals(service.content(aanvraag.id).inhoud))
        assertEquals("application/pdf", aanvraag.mediatype)
        assertEquals("verslag.pdf", aanvraag.bestandsnaam)
        assertNull(aanvraag.gegevens)
        assertEquals(setOf(aanvraag.id, anderVerslag.id), aanvragen.rows.keys.toSet(), "older ended requests ${eerder.id}, ${eerderMislukt.id} removed")

        // A repeat changes nothing.
        gereed(aanvraag, "uitkomst" to "MISLUKT")
        assertEquals(DocumentAanvraagStatus.GEREED, aanvraag.status)
    }

    @Test
    fun `an outcome is only taken from the process of the request`() {
        val aanvraag = aanvraag(DocumentAanvraagStatus.BEZIG, processInstanceId = "proces-1")
        val nietGeclaimd = aanvraag(DocumentAanvraagStatus.AANGEVRAAGD)

        assertEquals("AANVRAAG_HOORT_NIET_BIJ_PROCES", assertFailsWith<PluginActionException> {
            gereed(aanvraag, "uitkomst" to "GEREED", "inhoud" to pdfBase64(), proces = "proces-2")
        }.errorCode)
        assertEquals("AANVRAAG_HOORT_NIET_BIJ_PROCES", assertFailsWith<PluginActionException> {
            service.recordOutcome("ander-dossier", "proces-1", mapOf("aanvraagId" to "${aanvraag.id}", "uitkomst" to "MISLUKT"))
        }.errorCode)
        assertEquals("AANVRAAG_HOORT_NIET_BIJ_PROCES", assertFailsWith<PluginActionException> {
            gereed(nietGeclaimd, "uitkomst" to "MISLUKT", proces = "proces-1")
        }.errorCode)
        assertEquals(DocumentAanvraagStatus.BEZIG, aanvraag.status)
    }

    @Test
    fun `a document must be a PDF, and an unknown request or outcome is refused`() {
        val aanvraag = aanvraag(DocumentAanvraagStatus.BEZIG, processInstanceId = "proces-1")
        fun fout(vararg properties: Pair<String, Any?>) =
            assertFailsWith<PluginActionException> { service.recordOutcome(dossier, "proces-1", mapOf(*properties)) }.errorCode

        assertEquals("INHOUD_ONGELDIG", fout("aanvraagId" to "${aanvraag.id}", "uitkomst" to "GEREED", "inhoud" to Base64.getEncoder().encodeToString("<html>".toByteArray())))
        assertEquals("INHOUD_ONGELDIG", fout("aanvraagId" to "${aanvraag.id}", "uitkomst" to "GEREED", "inhoud" to "geen base64!"))
        assertEquals("INHOUD_ONTBREEKT", fout("aanvraagId" to "${aanvraag.id}", "uitkomst" to "GEREED"))
        assertEquals("UITKOMST_ONGELDIG", fout("aanvraagId" to "${aanvraag.id}", "uitkomst" to "KLAAR"))
        assertEquals("AANVRAAG_ONBEKEND", fout("aanvraagId" to "${UUID.randomUUID()}", "uitkomst" to "MISLUKT"))
        assertEquals("AANVRAAGID_ONTBREEKT", fout("uitkomst" to "MISLUKT"))
        assertEquals(DocumentAanvraagStatus.BEZIG, aanvraag.status)
        assertEquals(HttpStatus.NOT_FOUND, assertFailsWith<ResponseStatusException> { service.content(aanvraag.id) }.statusCode)
    }

    @Test
    fun `a failed document shows a general reason, never the generator's own text`() {
        val aanvraag = aanvraag(DocumentAanvraagStatus.BEZIG, processInstanceId = "proces-1")

        gereed(aanvraag, "uitkomst" to "MISLUKT", "foutmelding" to "TemplateRenderException at line 12")

        assertEquals(DocumentAanvraagStatus.MISLUKT, aanvraag.status)
        assertEquals("Het document kon niet worden gemaakt", aanvraag.foutmelding)
        assertNull(aanvraag.gegevens)
    }

    @Test
    fun `a request open too long is ended, and still takes the late outcome of its own process`() {
        val vastgelopen = aanvraag(DocumentAanvraagStatus.BEZIG, LocalDateTime.now().minusMinutes(11), processInstanceId = "proces-1")
        val vers = aanvraag(DocumentAanvraagStatus.AANGEVRAAGD, LocalDateTime.now().minusMinutes(1))

        service.expireOpenRequests()

        assertEquals(DocumentAanvraagStatus.MISLUKT, vastgelopen.status)
        assertEquals("Het document is niet op tijd gemaakt", vastgelopen.foutmelding)
        assertNull(vastgelopen.gegevens)
        assertEquals(DocumentAanvraagStatus.AANGEVRAAGD, vers.status)

        gereed(vastgelopen, "uitkomst" to "GEREED", "inhoud" to pdfBase64())
        assertEquals(DocumentAanvraagStatus.GEREED, vastgelopen.status)
        assertNull(vastgelopen.foutmelding)
    }

    @Test
    fun `the listing shows the latest request per document, newest first`() {
        val bron = UUID.randomUUID()
        aanvraag(DocumentAanvraagStatus.GEREED, LocalDateTime.now().minusDays(1), bron)
        val laatste = aanvraag(DocumentAanvraagStatus.BEZIG, LocalDateTime.now().minusMinutes(1), bron)
        val ander = aanvraag(DocumentAanvraagStatus.MISLUKT, LocalDateTime.now().minusMinutes(20))

        assertEquals(listOf(laatste.id, ander.id), service.list(planUuid).map { it.id })
    }

    private fun variables(answer: Map<String, Any?>): Map<*, *> = answer["variables"] as Map<*, *>
}
