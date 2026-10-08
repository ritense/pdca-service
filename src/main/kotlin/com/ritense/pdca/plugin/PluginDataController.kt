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

import com.ritense.pdca.domain.EvaluationSession
import com.ritense.pdca.domain.EvaluationSessionStatus
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
    val endedAt: LocalDateTime?
)

/**
 * The contract's `/data` route: GZAC's frontend proxies the iframe's
 * `target: "plugin"` calls here with the user's downscoped token. Data that
 * depends on who the user is goes through this route, because only here the
 * user is verified (token introspection against GZAC). `context` comes from
 * the GZAC frontend but is not trusted: dossier access is checked as the user.
 */
@RestController
class PluginDataController(
    private val configurationStore: ConfigurationStore,
    private val gzacClient: GzacClient,
    private val evaluationSessionService: EvaluationSessionService
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
            "POST" to "/evaluation-sessions/current/complete" -> endSession(userLogin, EvaluationSessionStatus.COMPLETED)
            "POST" to "/evaluation-sessions/current/cancel" -> endSession(userLogin, EvaluationSessionStatus.CANCELLED)
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

    private fun endSession(userLogin: String, status: EvaluationSessionStatus): ResponseEntity<Any> {
        val session = evaluationSessionService.end(userLogin, status)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Er loopt geen evaluatie")
        return ResponseEntity.ok(session.toDto())
    }

    private fun EvaluationSession.toDto() = EvaluationSessionDto(
        id = id,
        dossierId = dossierId,
        caseDefinitionKey = caseDefinitionKey,
        planUuid = planUuid,
        userLogin = userLogin,
        status = status,
        startedAt = startedAt,
        endedAt = endedAt
    )
}
