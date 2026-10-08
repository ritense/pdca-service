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
 * sees a running session, colleagues only see it once completed. The
 * evaluation is the set of [EvaluationChange]s made to the plan while it runs
 * plus the contactmoment information below. Ending it is always an explicit
 * completion; hiding the panel does not touch it.
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
    var endedAt: LocalDateTime? = null,

    /** One of the case type's evaluation types (PhaseConfig). */
    @Column(name = "evaluatie_type")
    var evaluatieType: String? = null,

    @Column(name = "deelnemers", columnDefinition = "TEXT")
    var deelnemers: String? = null,

    /** Gespreksverslag; becomes the notitie of the contactmoment on completion. */
    @Column(name = "verslag", columnDefinition = "TEXT")
    var verslag: String? = null,

    /** The contactmoment in Open Plan this evaluation became on completion. */
    @Column(name = "contactmoment_uuid")
    var contactmomentUuid: UUID? = null
)

enum class EvaluationSessionStatus { RUNNING, COMPLETED }
