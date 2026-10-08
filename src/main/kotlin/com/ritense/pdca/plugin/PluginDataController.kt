/*
 * Copyright 2015-2024 Ritense BV, the Netherlands.
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

package com.ritense.pdca.plugin

import com.fasterxml.jackson.databind.ObjectMapper
import com.ritense.pdca.domain.EvaluationChange
import com.ritense.pdca.domain.EvaluationSession
import com.ritense.pdca.domain.EvaluationSessionStatus
import com.ritense.pdca.domain.EvaluationSubjectType
import com.ritense.pdca.service.EvaluationChangeRequest
import com.ritense.pdca.service.EvaluationDraft
import com.ritense.pdca.service.EvaluationSessionConflictException
import com.ritense.pdca.service.EvaluationSessionService
import com.ritense.pdca.service.GzacClient
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDateTime
import java.util.UUID

data class PluginDataRequest(
    val configurationId: String? = null,
    val method: String? = null,
    val path: String? = null,
    val query: Map<String, String>? = null,
    val body: Any? = null,
    val context: Map<String, Any?>? = null,
    val userToken: String? = null
)

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

/**
 * The contract's `/data` route: GZAC's frontend proxies the iframe's
 * `target: "plugin"` calls here with the user's downscoped token. Data that
 * depends on who the user is goes through this route, because only here the
 * user is verified (token introspection against GZAC). `context` comes from
 * the GZAC frontend but is not trusted: dossier access is checked as the user.
 * The running evaluation (its contactmoment fields and recorded plan changes)
 * is only reachable here, so only its owner sees it.
 */
@RestController
class PluginDataController(
    private val configurationStore: ConfigurationStore,
    private val gzacClient: GzacClient,
    private val evaluationSessionService: EvaluationSessionService,
    private val objectMapper: ObjectMapper
) {

    @PostMapping("/plugins/pdca/{version}/data")
    fun handle(
        @PathVariable version: String,
        @RequestBody request: PluginDataRequest
    ): ResponseEntity<Any> = try {
        val configurationId = request.configurationId
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "configurationId ontbreekt")
        configurationStore.get(configurationId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Onbekende configuratie")
        val userToken = request.userToken
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Gebruikerstoken ontbreekt")
        val userLogin = gzacClient.introspectUserToken(configurationId, userToken)

        when (request.method?.uppercase() to request.path) {
            "GET" to "/evaluation-sessions/current" ->
                evaluationSessionService.current(userLogin)
                    ?.let { ResponseEntity.ok<Any>(it.toDto()) }
                    ?: ResponseEntity.noContent().build()
            "POST" to "/evaluation-sessions" -> startSession(configurationId, userToken, userLogin, request.context)
            "POST" to "/evaluation-sessions/current/draft" ->
                ResponseEntity.ok<Any>(evaluationSessionService.updateDraft(userLogin, request.bodyAs<EvaluationDraft>()).toDto())
            "GET" to "/evaluation-sessions/current/changes" -> {
                val session = evaluationSessionService.current(userLogin)
                    ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Er loopt geen evaluatie")
                ResponseEntity.ok<Any>(evaluationSessionService.changes(session).map { it.toDto() })
            }
            "POST" to "/evaluation-sessions/current/changes" ->
                evaluationSessionService.recordChange(userLogin, request.bodyAs<EvaluationChangeRequest>())
                    ?.let { ResponseEntity.ok<Any>(it.toDto()) }
                    ?: ResponseEntity.noContent().build()
            "POST" to "/evaluation-sessions/current/change-toelichting" -> {
                val body = request.bodyAs<ChangeToelichtingRequest>()
                ResponseEntity.ok<Any>(evaluationSessionService.updateChangeToelichting(userLogin, body.id, body.toelichting).toDto())
            }
            "POST" to "/evaluation-sessions/current/complete" ->
                ResponseEntity.ok<Any>(evaluationSessionService.complete(userLogin).toDto())
            else -> throw ResponseStatusException(HttpStatus.NOT_FOUND, "Onbekend pad ${request.path}")
        }
    } catch (e: ResponseStatusException) {
        ResponseEntity.status(e.statusCode).body(mapOf("message" to e.reason))
    } catch (e: EvaluationSessionConflictException) {
        ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("message" to e.message, "session" to e.running?.toDto()))
    }

    private fun startSession(
        configurationId: String,
        userToken: String,
        userLogin: String,
        context: Map<String, Any?>?
    ): ResponseEntity<Any> {
        val documentId = context?.get("documentId") as? String
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Geen dossier in de context")
        if (!gzacClient.userCanReadDocument(configurationId, userToken, documentId)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Geen toegang tot dit dossier")
        }
        val session = evaluationSessionService.start(userLogin, documentId, context["caseDefinitionKey"] as? String)
        return ResponseEntity.status(HttpStatus.CREATED).body(session.toDto())
    }

    private inline fun <reified T> PluginDataRequest.bodyAs(): T = try {
        objectMapper.convertValue(body ?: emptyMap<String, Any>(), T::class.java)
    } catch (e: IllegalArgumentException) {
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Ongeldige aanvraag")
    }
}
