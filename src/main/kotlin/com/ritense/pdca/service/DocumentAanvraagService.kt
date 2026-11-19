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

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.ritense.pdca.domain.DocumentAanvraag
import com.ritense.pdca.domain.DocumentAanvraagStatus
import com.ritense.pdca.domain.DocumentSoort
import com.ritense.pdca.domain.EvaluationSession
import com.ritense.pdca.domain.EvaluationSessionStatus
import com.ritense.pdca.plugin.GzacAccess
import com.ritense.pdca.repository.DocumentAanvraagRepository
import com.ritense.pdca.repository.DocumentAanvraagSummary
import com.ritense.pdca.repository.EvaluationChangeRepository
import com.ritense.pdca.repository.EvaluationSessionRepository
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import java.time.Duration
import java.time.LocalDateTime
import java.util.Base64
import java.util.UUID

/**
 * Documents made by GZAC's document process: PDCA records a request with a
 * data snapshot ([DocumentGegevensService]) and starts the process
 * configured as `documentProces` on the plan dossier. The process fetches
 * the data with plugin action `document-aanvraag-ophalen` ([claim]),
 * generates the document and hands it back with `document-gereed`
 * ([recordOutcome]); the bundles list the requests and show a finished
 * document.
 *
 * A request belongs to one process: the one whose id the start answered
 * with, or, while that answer is not recorded yet, the one that claims it
 * first. A request no process ends within [OPEN_TIMEOUT] is ended as
 * MISLUKT ([expireOpenRequests]), so it is never picked up by a later
 * process. A document has at most one open request (a database rule).
 *
 * Completing an evaluation records its evaluatieverslag request in the
 * completion's own transaction ([prepareEvaluatieverslag], [record]) and
 * starts the process after the commit ([startProcess]). Document
 * generation is off when the configuration's `documentProces` is blank.
 */
@Service
class DocumentAanvraagService(
    private val repository: DocumentAanvraagRepository,
    private val evaluationSessionRepository: EvaluationSessionRepository,
    private val evaluationChangeRepository: EvaluationChangeRepository,
    private val documentGegevensService: DocumentGegevensService,
    private val gzacClient: GzacClient,
    private val gzacAccess: GzacAccess,
    private val objectMapper: ObjectMapper,
    private val transactionTemplate: TransactionTemplate
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Key of the GZAC process that makes documents, from the active
     * configuration: [DEFAULT_DOCUMENT_PROCESS] when the configuration does
     * not set it, null (document generation off) when it is blank.
     */
    fun documentProcess(): String? {
        val value = gzacAccess.active().configuration.properties[DOCUMENT_PROCESS_PROPERTY] ?: return DEFAULT_DOCUMENT_PROCESS
        return value.toString().trim().takeIf { it.isNotEmpty() }
    }

    /** Whether documents can be made at all: an active configuration with a document process. */
    fun enabled(): Boolean = runCatching { documentProcess() }.getOrNull() != null

    /**
     * The evaluatieverslag request of evaluation [session], whose
     * contactmoment is [contactmomentUuid], not yet saved: the snapshot is
     * taken outside any transaction (it reads the registers), the caller
     * records it with [record] in the completion's transaction. Null when
     * document generation is off or the snapshot could not be taken; never
     * throws, so the completion never fails on it.
     */
    fun prepareEvaluatieverslag(session: EvaluationSession, contactmomentUuid: UUID?): DocumentAanvraag? = try {
        documentProcess()?.let { newRequest(session.copy(contactmomentUuid = contactmomentUuid), session.userLogin) }
    } catch (e: Exception) {
        log.warn("Could not prepare the evaluatieverslag of evaluation {}", session.id, e)
        null
    }

    /** Saves a prepared request; call it in the transaction that makes the request final. */
    fun record(aanvraag: DocumentAanvraag): DocumentAanvraag = repository.save(aanvraag)

    /**
     * Starts the document process for a recorded request and records the id
     * GZAC answers with. A process that cannot be started ends the request
     * as MISLUKT, unless a process claimed it meanwhile. Never throws;
     * returns the request as it is now.
     */
    fun startProcess(aanvraag: DocumentAanvraag): DocumentAanvraag {
        try {
            val process = documentProcess()
            if (process == null) {
                repository.failUnclaimed(aanvraag.id, NOT_CONFIGURED, LocalDateTime.now())
            } else {
                val processInstanceId = gzacClient.startProcessOnDossier(process, aanvraag.dossierId)
                processInstanceId?.let { recordProcess(aanvraag.id, it) }
                log.info("Requested document {} (process {}, instance {})", aanvraag.id, process, processInstanceId)
            }
        } catch (e: Exception) {
            log.warn("Starting the document process for document {} failed", aanvraag.id, e)
            repository.failUnclaimed(aanvraag.id, NOT_STARTED, LocalDateTime.now())
        }
        return repository.findById(aanvraag.id).orElse(aanvraag)
    }

    /**
     * Requests the evaluatieverslag of the completed evaluation [sessionId]
     * again, from the current data. While one is being made that request is
     * returned instead. 422 when document generation is off.
     */
    fun requestEvaluatieverslag(sessionId: UUID, requestedBy: String): DocumentAanvraag {
        if (documentProcess() == null) {
            throw ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, NOT_CONFIGURED)
        }
        val session = evaluationSessionRepository.findById(sessionId).orElse(null)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Onbekende evaluatie")
        if (session.status != EvaluationSessionStatus.COMPLETED) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "De evaluatie is nog niet afgerond")
        }
        openRequest(sessionId)?.let { return it }
        val recorded = try {
            transactionTemplate.execute { record(newRequest(session, requestedBy)) }!!
        } catch (e: DataIntegrityViolationException) {
            // Another request for this evaluation was recorded just now.
            return openRequest(sessionId) ?: throw e
        }
        return startProcess(recorded)
    }

    /**
     * Plugin action `document-aanvraag-ophalen`: the request of process
     * [processInstanceId] on dossier [documentId], marked BEZIG, with its
     * data as process variables. That is the request whose start answered
     * with this process id, or else the oldest request of the dossier that
     * no process has (younger than [OPEN_TIMEOUT]). A retry gets the same
     * request. 422 GEEN_AANVRAAG when there is none.
     */
    @Transactional
    fun claim(documentId: String?, processInstanceId: String?): Map<String, Any?> {
        if (documentId.isNullOrBlank() || processInstanceId.isNullOrBlank()) {
            throw PluginActionException("GEEN_AANVRAAG", "De aanroep noemt geen dossier of proces")
        }
        val aanvraag = repository.findForProcess(processInstanceId)?.also { own ->
            if (own.dossierId != documentId) throw notOfProcess(own)
            if (own.status != DocumentAanvraagStatus.AANGEVRAAGD && own.status != DocumentAanvraagStatus.BEZIG) {
                throw PluginActionException("GEEN_AANVRAAG", "De documentaanvraag van dit proces is al beëindigd")
            }
        } ?: repository.findClaimable(documentId, LocalDateTime.now().minus(OPEN_TIMEOUT))
            ?.also { it.processInstanceId = processInstanceId }
            ?: throw PluginActionException("GEEN_AANVRAAG", "Er staat voor dossier $documentId geen document klaar om te maken")
        aanvraag.status = DocumentAanvraagStatus.BEZIG
        aanvraag.bijgewerktOp = LocalDateTime.now()
        repository.save(aanvraag)

        val gegevens = aanvraag.gegevens?.let { objectMapper.readValue(it, object : TypeReference<Map<String, Any?>>() {}) }
            ?: emptyMap()
        return mapOf(
            "variables" to mapOf(
                "pdcaAanvraagId" to aanvraag.id.toString(),
                "pdcaBestandsnaam" to aanvraag.bestandsnaam,
                "pdcaGegevens" to gegevens
            )
        )
    }

    /**
     * Plugin action `document-gereed`: the outcome of request `aanvraagId`,
     * accepted only from the process that has it ([processInstanceId] on
     * dossier [documentId]). GEREED stores the PDF (`inhoud`, base64) and
     * removes the older ended requests of the same document; MISLUKT
     * records `foutmelding`. Either way the data snapshot is cleared. A
     * request ended because it took too long still takes the outcome of its
     * own process; otherwise a request that ended stays as it is.
     */
    @Transactional
    fun recordOutcome(documentId: String?, processInstanceId: String?, properties: Map<String, Any?>) {
        val aanvraagId = (properties["aanvraagId"] as? String)?.takeIf { it.isNotBlank() }
            ?: throw PluginActionException("AANVRAAGID_ONTBREEKT", "Property 'aanvraagId' ontbreekt of is leeg")
        val aanvraag = runCatching { UUID.fromString(aanvraagId) }.getOrNull()
            ?.let { repository.findById(it).orElse(null) }
            ?: throw PluginActionException("AANVRAAG_ONBEKEND", "Documentaanvraag $aanvraagId bestaat niet (meer)")
        if (aanvraag.processInstanceId == null || aanvraag.processInstanceId != processInstanceId || aanvraag.dossierId != documentId) {
            throw notOfProcess(aanvraag)
        }
        val outcome = Outcome.entries.firstOrNull { it.name == (properties["uitkomst"] as? String)?.trim()?.uppercase() }
            ?: throw PluginActionException(
                "UITKOMST_ONGELDIG", "Onbekende uitkomst '${properties["uitkomst"]}' (verwacht: GEREED of MISLUKT)"
            )
        val timedOut = aanvraag.status == DocumentAanvraagStatus.MISLUKT && aanvraag.foutmelding == NOT_IN_TIME
        if (aanvraag.status != DocumentAanvraagStatus.BEZIG && !timedOut) {
            log.info("Document {} already ended as {}; outcome {} ignored", aanvraag.id, aanvraag.status, outcome)
            return
        }

        when (outcome) {
            Outcome.GEREED -> {
                aanvraag.inhoud = pdf(properties["inhoud"])
                aanvraag.mediatype = MEDIATYPE_PDF
                aanvraag.foutmelding = null
                (properties["bestandsnaam"] as? String)?.takeIf { it.isNotBlank() }
                    ?.let { aanvraag.bestandsnaam = it.take(MAX_FILE_NAME) }
                aanvraag.status = DocumentAanvraagStatus.GEREED
            }
            Outcome.MISLUKT -> {
                // Epistola's own reason is for the log, not for the user.
                log.warn("Document {} failed: {}", aanvraag.id, properties["foutmelding"])
                aanvraag.foutmelding = NOT_MADE
                aanvraag.status = DocumentAanvraagStatus.MISLUKT
            }
        }
        aanvraag.gegevens = null
        aanvraag.bijgewerktOp = LocalDateTime.now()
        repository.save(aanvraag)
        if (aanvraag.status == DocumentAanvraagStatus.GEREED) {
            repository.deleteEndedBefore(aanvraag.soort, aanvraag.bronUuid, aanvraag.id, aanvraag.aangevraagdOp)
        }
        log.info("Document {} ended as {}", aanvraag.id, aanvraag.status)
    }

    /** The documents of a plan: the latest request per document (soort + bron), newest first. */
    fun list(planUuid: UUID): List<DocumentAanvraagSummary> =
        repository.findSummariesByPlanUuid(planUuid)
            .groupBy { it.soort to it.bronUuid }
            .values
            .map { aanvragen -> aanvragen.maxBy { it.aangevraagdOp } }
            .sortedByDescending { it.aangevraagdOp }

    /** A finished document; 404 while it is not (yet) GEREED. */
    fun content(id: UUID): DocumentAanvraag =
        repository.findById(id).orElse(null)?.takeIf { it.status == DocumentAanvraagStatus.GEREED && it.inhoud != null }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Het document is (nog) niet beschikbaar")

    /**
     * Ends the requests no process ended within [OPEN_TIMEOUT] (the process
     * got stuck or never ran) as MISLUKT and clears their snapshot.
     */
    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    fun expireOpenRequests() {
        val now = LocalDateTime.now()
        val ended = repository.expireOpen(now.minus(OPEN_TIMEOUT), NOT_IN_TIME, now)
        if (ended > 0) log.info("Ended {} document request(s) that took longer than {}", ended, OPEN_TIMEOUT)
    }

    private fun newRequest(session: EvaluationSession, requestedBy: String): DocumentAanvraag {
        val changes = evaluationChangeRepository.findBySessionIdOrderByCreatedAt(session.id)
        return DocumentAanvraag(
            planUuid = session.planUuid,
            dossierId = session.dossierId,
            soort = DocumentSoort.EVALUATIEVERSLAG,
            bronUuid = session.id,
            gegevens = objectMapper.writeValueAsString(documentGegevensService.evaluatieverslag(session, changes)),
            bestandsnaam = documentGegevensService.bestandsnaam(session),
            aangevraagdDoor = requestedBy
        )
    }

    private fun openRequest(sessionId: UUID): DocumentAanvraag? =
        repository.findFirstBySoortAndBronUuidAndStatusIn(DocumentSoort.EVALUATIEVERSLAG, sessionId, OPEN)

    private fun recordProcess(aanvraagId: UUID, processInstanceId: String) {
        try {
            repository.recordProcess(aanvraagId, processInstanceId)
        } catch (e: DataIntegrityViolationException) {
            // The process claimed another request under this id in the meantime; that one is its request.
            log.info("Process {} already has another document request", processInstanceId)
        }
    }

    private fun notOfProcess(aanvraag: DocumentAanvraag) = PluginActionException(
        "AANVRAAG_HOORT_NIET_BIJ_PROCES", "Documentaanvraag ${aanvraag.id} hoort niet bij dit proces"
    )

    private fun pdf(inhoud: Any?): ByteArray {
        val base64 = (inhoud as? String)?.filterNot { it.isWhitespace() }?.takeIf { it.isNotEmpty() }
            ?: throw PluginActionException("INHOUD_ONTBREEKT", "Property 'inhoud' ontbreekt of is leeg")
        val bytes = try {
            Base64.getDecoder().decode(base64)
        } catch (e: IllegalArgumentException) {
            throw PluginActionException("INHOUD_ONGELDIG", "Property 'inhoud' is geen base64")
        }
        if (bytes.size < PDF_SIGNATURE.size || !bytes.copyOfRange(0, PDF_SIGNATURE.size).contentEquals(PDF_SIGNATURE)) {
            throw PluginActionException("INHOUD_ONGELDIG", "Property 'inhoud' is geen PDF")
        }
        return bytes
    }

    /** The `uitkomst` of `document-gereed`. */
    private enum class Outcome { GEREED, MISLUKT }

    companion object {
        const val DOCUMENT_PROCESS_PROPERTY = "documentProces"
        const val DEFAULT_DOCUMENT_PROCESS = "pdca-document"
        const val MEDIATYPE_PDF = "application/pdf"
        const val NOT_MADE = "Het document kon niet worden gemaakt"
        const val NOT_STARTED = "Het documentproces kon niet worden gestart"
        const val NOT_IN_TIME = "Het document is niet op tijd gemaakt"
        const val NOT_CONFIGURED = "Documentgeneratie is niet ingericht"

        /** How long a request may stay open before it is ended as failed. */
        val OPEN_TIMEOUT: Duration = Duration.ofMinutes(10)

        private const val MAX_FILE_NAME = 255
        private val OPEN = listOf(DocumentAanvraagStatus.AANGEVRAAGD, DocumentAanvraagStatus.BEZIG)
        private val PDF_SIGNATURE = "%PDF".toByteArray(Charsets.US_ASCII)
    }
}
