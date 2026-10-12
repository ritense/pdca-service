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

import com.ritense.pdca.domain.ContactmomentDetails
import com.ritense.pdca.domain.EvaluationChange
import com.ritense.pdca.domain.EvaluationSession
import com.ritense.pdca.domain.EvaluationSessionStatus
import com.ritense.pdca.domain.EvaluationSubjectType
import com.ritense.pdca.repository.ContactmomentDetailsRepository
import com.ritense.pdca.repository.EvaluationChangeRepository
import com.ritense.pdca.repository.EvaluationSessionRepository
import com.ritense.pdca.repository.PlanDetailsRepository
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
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
 * it started drops out of the evaluation, unless a new [toelichting] comes
 * with it. [toelichting] is the reason entered with the change in the tab
 * (e.g. the explanation of a voortgang); null leaves the change's reason as
 * it is, blank clears it.
 */
data class EvaluationChangeRequest(
    val planUuid: UUID,
    val subjectType: EvaluationSubjectType,
    val subjectUuid: UUID,
    val subjectTitel: String? = null,
    val soort: String,
    val vanWaarde: String? = null,
    val naarWaarde: String? = null,
    val toelichting: String? = null,
    val samenvoegen: Boolean = false
)

/**
 * Evaluation sessions per user. One running session per user (also enforced
 * by a partial unique index); a running session, its contactmoment fields and
 * its changes are only ever returned to the owner. Completing turns the
 * session into a contactmoment that took place — a klantcontact in Open
 * Klant — which is when colleagues get to see the evaluation. A session
 * started for a planned contactmoment carries that one out instead of
 * creating a new one. Cancelling ends the session without a contactmoment;
 * a cancelled evaluation is never shown to colleagues.
 */
@Service
@Transactional
class EvaluationSessionService(
    private val repository: EvaluationSessionRepository,
    private val changeRepository: EvaluationChangeRepository,
    private val planDetailsRepository: PlanDetailsRepository,
    private val contactmomentDetailsRepository: ContactmomentDetailsRepository,
    private val phaseConfigService: PhaseConfigService,
    private val klantcontactService: KlantcontactService
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(readOnly = true)
    fun current(userLogin: String): EvaluationSession? =
        repository.findByUserLoginAndStatus(userLogin, EvaluationSessionStatus.RUNNING)

    /**
     * Starts an evaluation of the dossier's plan. With
     * [geplandContactmomentUuid] it carries out that planned contactmoment
     * of the plan; without it, it carries out the plan's earliest planned
     * contactmoment that is due (today or earlier), if any. The session takes
     * over the planned contactmoment's evaluation type.
     */
    fun start(
        userLogin: String,
        dossierId: String,
        caseDefinitionKey: String?,
        geplandContactmomentUuid: UUID? = null
    ): EvaluationSession {
        current(userLogin)?.let { throw EvaluationSessionConflictException(it) }
        val plan = planDetailsRepository.findByDossierId(dossierId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Bij dit dossier hoort geen plan")
        val gepland = geplandContactmomentUuid
            ?.let { checkedPlannedContactmoment(plan.planUuid, it) }
            ?: duePlannedContactmoment(plan.planUuid)
        val evaluatieType = gepland
            ?.let { contactmomentDetailsRepository.findById(it).orElse(null)?.evaluatieType }
            ?.takeIf { type -> evaluationTypes(caseDefinitionKey).let { it.isEmpty() || type in it } }
            ?: defaultEvaluationType(caseDefinitionKey)
        return try {
            repository.saveAndFlush(
                EvaluationSession(
                    dossierId = dossierId,
                    caseDefinitionKey = caseDefinitionKey,
                    planUuid = plan.planUuid,
                    userLogin = userLogin,
                    evaluatieType = evaluatieType,
                    geplandContactmomentUuid = gepland
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
        draft.verslag?.let { verslag ->
            if (verslag.length > KlantcontactService.INHOUD_MAX_LENGTH) {
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Het gespreksverslag mag maximaal ${KlantcontactService.INHOUD_MAX_LENGTH} tekens bevatten"
                )
            }
            session.verslag = verslag
        }
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
        val toelichting = request.toelichting?.takeIf { it.isNotBlank() }
        val earlier = if (request.samenvoegen) {
            changeRepository.findFirstBySessionIdAndSubjectTypeAndSubjectUuidAndSoort(
                session.id, request.subjectType, request.subjectUuid, request.soort
            )
        } else null

        if (earlier != null) {
            if (earlier.vanWaarde == naar && toelichting == null) {
                changeRepository.delete(earlier)
                return null
            }
            earlier.naarWaarde = naar
            if (request.toelichting != null) earlier.toelichting = toelichting
            earlier.subjectTitel = request.subjectTitel ?: earlier.subjectTitel
            earlier.updatedAt = LocalDateTime.now()
            return changeRepository.save(earlier)
        }
        if (request.samenvoegen && van == naar && toelichting == null) return null
        return changeRepository.save(
            EvaluationChange(
                sessionId = session.id,
                subjectType = request.subjectType,
                subjectUuid = request.subjectUuid,
                subjectTitel = request.subjectTitel,
                soort = request.soort,
                vanWaarde = van,
                naarWaarde = naar,
                toelichting = toelichting
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
     * Completes the running evaluation as a contactmoment that took place at
     * the session's start, with the gespreksverslag as inhoud and its overlay
     * row pointing back to this session. When the session carries out a
     * planned contactmoment that is still planned, that klantcontact is
     * recorded as having taken place; otherwise a new klantcontact is created
     * in Open Klant. The session is marked completed.
     */
    fun complete(userLogin: String): EvaluationSession {
        val session = running(userLogin)
        val verslag = session.verslag?.takeIf { it.isNotBlank() }
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Vul eerst het gespreksverslag in")
        val evaluatieType = session.evaluatieType ?: defaultEvaluationType(session.caseDefinitionKey)
        val now = LocalDateTime.now()
        val plaatsgevondenOp = session.startedAt.atZone(ZoneId.systemDefault()).toOffsetDateTime()
        val deelnemers = session.deelnemers?.takeIf { it.isNotBlank() }
        val gepland = session.geplandContactmomentUuid
            ?.let { klantcontactService.get(it) }
            ?.takeIf { it.gepland && it.planUuid == session.planUuid }

        val contactmomentUuid = if (gepland != null) {
            klantcontactService.completePlanned(gepland.uuid, session.planUuid, evaluatieType, plaatsgevondenOp, verslag)
            val details = contactmomentDetailsRepository.findById(gepland.uuid).orElse(null)?.apply {
                this.evaluatieType = evaluatieType
                this.deelnemers = deelnemers ?: this.deelnemers
                evaluationSessionId = session.id
                updatedAt = now
            } ?: ContactmomentDetails(
                contactmomentUuid = gepland.uuid,
                planUuid = session.planUuid,
                evaluatieType = evaluatieType,
                geplandeDatum = gepland.plaatsgevondenOp?.atZoneSameInstant(ZoneId.systemDefault())?.toLocalDate(),
                deelnemers = deelnemers,
                evaluationSessionId = session.id
            )
            contactmomentDetailsRepository.save(details)
            gepland.uuid
        } else {
            val created = klantcontactService.create(
                NewContactmoment(
                    planUuid = session.planUuid,
                    evaluatieType = evaluatieType,
                    plaatsgevondenOp = plaatsgevondenOp,
                    afgerond = true,
                    verslag = verslag
                )
            )
            contactmomentDetailsRepository.save(
                ContactmomentDetails(
                    contactmomentUuid = created,
                    planUuid = session.planUuid,
                    evaluatieType = evaluatieType,
                    geplandeDatum = LocalDate.now(),
                    deelnemers = deelnemers,
                    evaluationSessionId = session.id
                )
            )
            created
        }
        session.evaluatieType = evaluatieType
        session.contactmomentUuid = contactmomentUuid
        session.status = EvaluationSessionStatus.COMPLETED
        session.endedAt = now
        return repository.save(session)
    }

    /**
     * Cancels the running evaluation. Nothing is written to Open Klant and a
     * planned contactmoment it carried out stays planned; the recorded
     * changes stay with the cancelled session. The plan changes themselves
     * were made in the registers already and are not reverted.
     */
    fun cancel(userLogin: String): EvaluationSession {
        val session = running(userLogin)
        session.status = EvaluationSessionStatus.CANCELLED
        session.endedAt = LocalDateTime.now()
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

    /** A planned contactmoment of this plan, given explicitly at start: 404 when unknown, 400 when not usable. */
    private fun checkedPlannedContactmoment(planUuid: UUID, klantcontactUuid: UUID): UUID {
        val klantcontact = klantcontactService.get(klantcontactUuid)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Gepland contactmoment niet gevonden")
        if (klantcontact.planUuid != planUuid) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Het contactmoment hoort niet bij dit plan")
        }
        if (!klantcontact.gepland) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Het contactmoment heeft al plaatsgevonden")
        }
        return klantcontact.uuid
    }

    /**
     * The plan's earliest planned contactmoment that is due (today or
     * earlier) and not already being carried out by another running session.
     * Best effort: when Open Klant cannot be reached the session starts
     * without one.
     */
    private fun duePlannedContactmoment(planUuid: UUID): UUID? {
        val today = LocalDate.now()
        val inUse = repository.findByPlanUuidAndStatus(planUuid, EvaluationSessionStatus.RUNNING)
            .mapNotNull { it.geplandContactmomentUuid }
            .toSet()
        return try {
            klantcontactService.planned(planUuid)
                .filter { it.uuid !in inUse }
                .firstOrNull { gepland ->
                    gepland.plaatsgevondenOp?.atZoneSameInstant(ZoneId.systemDefault())?.toLocalDate()
                        ?.let { !it.isAfter(today) } == true
                }
                ?.uuid
        } catch (e: Exception) {
            log.warn("Could not look up planned contactmomenten of plan $planUuid; starting the evaluation without one: ${e.message}")
            null
        }
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
}
