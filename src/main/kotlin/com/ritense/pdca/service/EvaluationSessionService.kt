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

package com.ritense.pdca.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.ritense.pdca.domain.ContactmomentDetails
import com.ritense.pdca.domain.EvaluationChange
import com.ritense.pdca.domain.EvaluationSession
import com.ritense.pdca.domain.EvaluationSessionStatus
import com.ritense.pdca.domain.EvaluationSubjectType
import com.ritense.pdca.repository.ContactmomentDetailsRepository
import com.ritense.pdca.repository.EvaluationChangeRepository
import com.ritense.pdca.repository.EvaluationSessionRepository
import com.ritense.pdca.repository.PlanDetailsRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.client.RestClient
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID

class EvaluationSessionConflictException(val running: EvaluationSession?) :
    RuntimeException("Er loopt al een evaluatie; rond die eerst af")

/** Contactmoment fields of a running evaluation; null leaves a field unchanged. */
data class EvaluationDraft(
    val evaluatieType: String? = null,
    val deelnemers: String? = null,
    val verslag: String? = null
)

/**
 * A plan change made in a plan tab while the session runs. With [samenvoegen]
 * a later change of the same kind on the same subject replaces the earlier
 * one's [naarWaarde] (keeping its [vanWaarde]); a value changed back to where
 * it started drops out of the evaluation.
 */
data class EvaluationChangeRequest(
    val planUuid: UUID,
    val subjectType: EvaluationSubjectType,
    val subjectUuid: UUID,
    val subjectTitel: String? = null,
    val soort: String,
    val vanWaarde: String? = null,
    val naarWaarde: String? = null,
    val samenvoegen: Boolean = false
)

/**
 * Evaluation sessions per user. One running session per user (also enforced
 * by a partial unique index); a running session, its contactmoment fields and
 * its changes are only ever returned to the owner. Completing turns the
 * session into an afgerond contactmoment in Open Plan, which is when
 * colleagues get to see the evaluation.
 */
@Service
@Transactional
class EvaluationSessionService(
    private val repository: EvaluationSessionRepository,
    private val changeRepository: EvaluationChangeRepository,
    private val planDetailsRepository: PlanDetailsRepository,
    private val contactmomentDetailsRepository: ContactmomentDetailsRepository,
    private val phaseConfigService: PhaseConfigService,
    private val openPlanRestClient: RestClient,
    private val objectMapper: ObjectMapper
) {

    @Transactional(readOnly = true)
    fun current(userLogin: String): EvaluationSession? =
        repository.findByUserLoginAndStatus(userLogin, EvaluationSessionStatus.RUNNING)

    fun start(userLogin: String, dossierId: String, caseDefinitionKey: String?): EvaluationSession {
        current(userLogin)?.let { throw EvaluationSessionConflictException(it) }
        val plan = planDetailsRepository.findByDossierId(dossierId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Bij dit dossier hoort geen plan")
        return try {
            repository.saveAndFlush(
                EvaluationSession(
                    dossierId = dossierId,
                    caseDefinitionKey = caseDefinitionKey,
                    planUuid = plan.planUuid,
                    userLogin = userLogin,
                    evaluatieType = defaultEvaluationType(caseDefinitionKey)
                )
            )
        } catch (e: DataIntegrityViolationException) {
            throw EvaluationSessionConflictException(null)
        }
    }

    fun updateDraft(userLogin: String, draft: EvaluationDraft): EvaluationSession {
        val session = running(userLogin)
        draft.evaluatieType?.let { type ->
            val types = evaluationTypes(session.caseDefinitionKey)
            if (types.isNotEmpty() && type !in types) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Onbekend evaluatietype '$type'")
            }
            session.evaluatieType = type
        }
        draft.deelnemers?.let { session.deelnemers = it }
        draft.verslag?.let { session.verslag = it }
        return repository.save(session)
    }

    @Transactional(readOnly = true)
    fun changes(session: EvaluationSession): List<EvaluationChange> =
        changeRepository.findBySessionIdOrderByCreatedAt(session.id)

    /** Records a plan change in the user's running evaluation of that plan. */
    fun recordChange(userLogin: String, request: EvaluationChangeRequest): EvaluationChange? {
        val session = running(userLogin)
        if (session.planUuid != request.planUuid) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "De lopende evaluatie hoort bij een ander plan")
        }
        val van = request.vanWaarde?.takeIf { it.isNotBlank() }
        val naar = request.naarWaarde?.takeIf { it.isNotBlank() }
        val earlier = if (request.samenvoegen) {
            changeRepository.findFirstBySessionIdAndSubjectTypeAndSubjectUuidAndSoort(
                session.id, request.subjectType, request.subjectUuid, request.soort
            )
        } else null

        if (earlier != null) {
            if (earlier.vanWaarde == naar) {
                changeRepository.delete(earlier)
                return null
            }
            earlier.naarWaarde = naar
            earlier.subjectTitel = request.subjectTitel ?: earlier.subjectTitel
            earlier.updatedAt = LocalDateTime.now()
            return changeRepository.save(earlier)
        }
        if (request.samenvoegen && van == naar) return null
        return changeRepository.save(
            EvaluationChange(
                sessionId = session.id,
                subjectType = request.subjectType,
                subjectUuid = request.subjectUuid,
                subjectTitel = request.subjectTitel,
                soort = request.soort,
                vanWaarde = van,
                naarWaarde = naar
            )
        )
    }

    /** The reason for a change, given in the side panel. */
    fun updateChangeToelichting(userLogin: String, changeId: UUID, toelichting: String?): EvaluationChange {
        val session = running(userLogin)
        val change = changeRepository.findById(changeId).orElse(null)
            ?.takeIf { it.sessionId == session.id }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Onbekende wijziging")
        change.toelichting = toelichting?.takeIf { it.isNotBlank() }
        change.updatedAt = LocalDateTime.now()
        return changeRepository.save(change)
    }

    /**
     * Completes the running evaluation: an afgerond contactmoment in Open Plan
     * with the gespreksverslag as notitie, its overlay row pointing back to
     * this session, and the session marked completed.
     */
    fun complete(userLogin: String): EvaluationSession {
        val session = running(userLogin)
        val verslag = session.verslag?.takeIf { it.isNotBlank() }
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Vul eerst het gespreksverslag in")
        val evaluatieType = session.evaluatieType ?: defaultEvaluationType(session.caseDefinitionKey)
        val now = LocalDateTime.now()
        val contactmoment = postContactmoment(
            mapOf(
                "planUuid" to session.planUuid.toString(),
                "datum" to OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS)
                    .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                "status" to "afgerond",
                "notitie" to verslag
            )
        )
        val contactmomentUuid = UUID.fromString(contactmoment["uuid"] as String)
        contactmomentDetailsRepository.save(
            ContactmomentDetails(
                contactmomentUuid = contactmomentUuid,
                planUuid = session.planUuid,
                evaluatieType = evaluatieType,
                geplandeDatum = LocalDate.now(),
                deelnemers = session.deelnemers?.takeIf { it.isNotBlank() },
                evaluationSessionId = session.id
            )
        )
        session.evaluatieType = evaluatieType
        session.contactmomentUuid = contactmomentUuid
        session.status = EvaluationSessionStatus.COMPLETED
        session.endedAt = now
        return repository.save(session)
    }

    /** Completed evaluations of a plan with their changes, for every colleague. */
    @Transactional(readOnly = true)
    fun completed(planUuid: UUID): List<Pair<EvaluationSession, List<EvaluationChange>>> {
        val sessions = repository.findByPlanUuidAndStatus(planUuid, EvaluationSessionStatus.COMPLETED)
        val changes = changeRepository.findBySessionIdInOrderByCreatedAt(sessions.map { it.id })
            .groupBy { it.sessionId }
        return sessions.sortedByDescending { it.endedAt }.map { it to (changes[it.id] ?: emptyList()) }
    }

    private fun running(userLogin: String): EvaluationSession =
        current(userLogin) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Er loopt geen evaluatie")

    /** EVALUATION when the case type offers it, otherwise its first evaluation type. */
    private fun defaultEvaluationType(caseDefinitionKey: String?): String {
        val types = evaluationTypes(caseDefinitionKey)
        return types.firstOrNull { it == DEFAULT_EVALUATION_TYPE } ?: types.firstOrNull() ?: DEFAULT_EVALUATION_TYPE
    }

    private fun evaluationTypes(caseDefinitionKey: String?): List<String> =
        caseDefinitionKey?.let { key ->
            phaseConfigService.findByCaseDefinitionKey(key)?.let { phaseConfigService.getEvaluationTypes(key) }
        } ?: emptyList()

    private companion object {
        const val DEFAULT_EVALUATION_TYPE = "EVALUATION"
    }

    private fun postContactmoment(body: Map<String, Any?>): Map<*, *> = try {
        openPlanRestClient.post()
            .uri("/plannen/api/v0/contactmoment")
            .contentType(MediaType.APPLICATION_JSON)
            .body(objectMapper.writeValueAsBytes(body))
            .retrieve()
            .body(Map::class.java)
            ?: throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "Open Plan gaf geen antwoord bij het vastleggen van de evaluatie")
    } catch (e: ResponseStatusException) {
        throw e
    } catch (e: Exception) {
        throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "Evaluatie vastleggen in Open Plan mislukt: ${e.message}")
    }
}
