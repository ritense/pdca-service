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

package com.ritense.pdca.web.rest

import com.ritense.pdca.domain.EvaluationChange
import com.ritense.pdca.domain.EvaluationSession
import com.ritense.pdca.domain.EvaluationSessionStatus
import com.ritense.pdca.domain.EvaluationSubjectType
import com.ritense.pdca.security.CurrentUser
import com.ritense.pdca.security.DataDispatch
import com.ritense.pdca.service.EvaluationChangeRequest
import com.ritense.pdca.service.EvaluationDraft
import com.ritense.pdca.service.EvaluationSessionConflictException
import com.ritense.pdca.service.EvaluationSessionService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDateTime
import java.util.UUID

data class EvaluationSessionDto(
    val id: UUID,
    val dossierId: String,
    val caseDefinitionKey: String?,
    val planUuid: UUID,
    val userLogin: String,
    val status: EvaluationSessionStatus,
    val startedAt: LocalDateTime,
    val endedAt: LocalDateTime?,
    val evaluatieType: String?,
    val deelnemers: String?,
    val verslag: String?,
    val geplandContactmomentUuid: UUID?,
    val contactmomentUuid: UUID?
)

data class EvaluationChangeDto(
    val id: UUID,
    val subjectType: EvaluationSubjectType,
    val subjectUuid: UUID,
    val subjectTitel: String?,
    val soort: String,
    val vanWaarde: String?,
    val naarWaarde: String?,
    val toelichting: String?,
    val createdAt: LocalDateTime
)

fun EvaluationSession.toDto() = EvaluationSessionDto(
    id = id,
    dossierId = dossierId,
    caseDefinitionKey = caseDefinitionKey,
    planUuid = planUuid,
    userLogin = userLogin,
    status = status,
    startedAt = startedAt,
    endedAt = endedAt,
    evaluatieType = evaluatieType,
    deelnemers = deelnemers,
    verslag = verslag,
    geplandContactmomentUuid = geplandContactmomentUuid,
    contactmomentUuid = contactmomentUuid
)

fun EvaluationChange.toDto() = EvaluationChangeDto(
    id = id,
    subjectType = subjectType,
    subjectUuid = subjectUuid,
    subjectTitel = subjectTitel,
    soort = soort,
    vanWaarde = vanWaarde,
    naarWaarde = naarWaarde,
    toelichting = toelichting,
    createdAt = createdAt
)

data class ChangeToelichtingRequest(val id: UUID, val toelichting: String? = null)

/** Optional body of a session start: the planned contactmoment the evaluation carries out. */
data class StartEvaluationRequest(val geplandContactmomentUuid: UUID? = null)

/**
 * The user's own evaluation session (ARCHITECTURE §6.5). Every operation acts for the
 * verified user of the `/data` call ([CurrentUser]), so a running session,
 * its contactmoment fields and its changes are only reachable by their
 * owner. A start takes the dossier from the GZAC context of the calling tab;
 * the access policy has checked that the user can read it.
 */
@RestController
@RequestMapping("/api/v1/pdca/evaluation-sessions", produces = [MediaType.APPLICATION_JSON_VALUE])
class EvaluationSessionResource(
    private val evaluationSessionService: EvaluationSessionService
) {

    @GetMapping("/current")
    fun current(): ResponseEntity<EvaluationSessionDto> =
        evaluationSessionService.current(CurrentUser.require().login)
            ?.let { ResponseEntity.ok(it.toDto()) }
            ?: ResponseEntity.noContent().build()

    @PostMapping
    fun start(
        request: HttpServletRequest,
        @RequestBody(required = false) start: StartEvaluationRequest?
    ): ResponseEntity<EvaluationSessionDto> {
        val context = DataDispatch.of(request)?.call?.context ?: emptyMap()
        val documentId = context["documentId"] as? String
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Geen dossier in de context")
        val session = evaluationSessionService.start(
            CurrentUser.require().login, documentId, context["caseDefinitionKey"] as? String, start?.geplandContactmomentUuid
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(session.toDto())
    }

    @PostMapping("/current/draft")
    fun updateDraft(@RequestBody draft: EvaluationDraft): EvaluationSessionDto =
        evaluationSessionService.updateDraft(CurrentUser.require().login, draft).toDto()

    @GetMapping("/current/changes")
    fun changes(): List<EvaluationChangeDto> {
        val session = evaluationSessionService.current(CurrentUser.require().login)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Er loopt geen evaluatie")
        return evaluationSessionService.changes(session).map { it.toDto() }
    }

    @PostMapping("/current/changes")
    fun recordChange(@RequestBody change: EvaluationChangeRequest): ResponseEntity<EvaluationChangeDto> =
        evaluationSessionService.recordChange(CurrentUser.require().login, change)
            ?.let { ResponseEntity.ok(it.toDto()) }
            ?: ResponseEntity.noContent().build()

    @PostMapping("/current/change-toelichting")
    fun updateChangeToelichting(@RequestBody body: ChangeToelichtingRequest): EvaluationChangeDto =
        evaluationSessionService.updateChangeToelichting(CurrentUser.require().login, body.id, body.toelichting).toDto()

    @PostMapping("/current/complete")
    fun complete(): EvaluationSessionDto = evaluationSessionService.complete(CurrentUser.require().login).toDto()

    @PostMapping("/current/cancel")
    fun cancel(): EvaluationSessionDto = evaluationSessionService.cancel(CurrentUser.require().login).toDto()

    /** 409 with the running session, so the tab can say which evaluation to finish first. */
    @ExceptionHandler(EvaluationSessionConflictException::class)
    fun conflict(e: EvaluationSessionConflictException): ResponseEntity<Map<String, Any?>> =
        ResponseEntity.status(HttpStatus.CONFLICT)
            .body(mapOf("detail" to e.message, "session" to e.running?.toDto()))
}
