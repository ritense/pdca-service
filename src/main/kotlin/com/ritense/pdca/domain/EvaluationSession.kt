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

package com.ritense.pdca.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime
import java.util.UUID

/**
 * An evaluation a user runs on a plan, shown in GZAC's side panel while
 * [EvaluationSessionStatus.RUNNING]. Belongs to user + plan: only its owner
 * sees a running session. Ending it is always explicit (complete or cancel);
 * hiding the panel does not touch it.
 */
@Entity
@Table(name = "evaluation_session")
data class EvaluationSession(
    @Id
    @Column(name = "id")
    val id: UUID = UUID.randomUUID(),

    @Column(name = "dossier_id", nullable = false)
    val dossierId: String,

    @Column(name = "case_definition_key")
    val caseDefinitionKey: String? = null,

    @Column(name = "plan_uuid", nullable = false)
    val planUuid: UUID,

    /** GZAC login of the owner, as verified by user-token introspection. */
    @Column(name = "user_login", nullable = false)
    val userLogin: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: EvaluationSessionStatus = EvaluationSessionStatus.RUNNING,

    @Column(name = "started_at", nullable = false)
    val startedAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "ended_at")
    var endedAt: LocalDateTime? = null
)

enum class EvaluationSessionStatus { RUNNING, COMPLETED, CANCELLED }
