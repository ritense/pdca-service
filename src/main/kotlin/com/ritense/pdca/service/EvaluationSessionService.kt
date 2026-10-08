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

import com.ritense.pdca.domain.EvaluationSession
import com.ritense.pdca.domain.EvaluationSessionStatus
import com.ritense.pdca.repository.EvaluationSessionRepository
import com.ritense.pdca.repository.PlanDetailsRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDateTime

class EvaluationSessionConflictException(val running: EvaluationSession?) :
    RuntimeException("Er loopt al een evaluatie; rond die eerst af of annuleer hem")

/**
 * Evaluation sessions per user. One running session per user (also enforced
 * by a partial unique index); a running session is only ever returned to its
 * owner.
 */
@Service
@Transactional
class EvaluationSessionService(
    private val repository: EvaluationSessionRepository,
    private val planDetailsRepository: PlanDetailsRepository
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
                    userLogin = userLogin
                )
            )
        } catch (e: DataIntegrityViolationException) {
            throw EvaluationSessionConflictException(null)
        }
    }

    fun end(userLogin: String, status: EvaluationSessionStatus): EvaluationSession? {
        require(status != EvaluationSessionStatus.RUNNING)
        val session = current(userLogin) ?: return null
        session.status = status
        session.endedAt = LocalDateTime.now()
        return repository.save(session)
    }
}
