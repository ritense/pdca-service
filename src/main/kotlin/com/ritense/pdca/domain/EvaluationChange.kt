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
 * One change made to the plan while an [EvaluationSession] was running. The
 * change itself is already applied to the registers/overlay when it is
 * recorded; this row is the evaluation's account of it. [soort] is the kind
 * of change (e.g. `SUBDOEL_TOEGEVOEGD`, `VOORTGANG`); [vanWaarde] and
 * [naarWaarde] are display values for value changes. [toelichting] is the
 * reason the user gives in the evaluation.
 */
@Entity
@Table(name = "evaluation_change")
data class EvaluationChange(
    @Id
    @Column(name = "id")
    val id: UUID = UUID.randomUUID(),

    @Column(name = "session_id", nullable = false)
    val sessionId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", nullable = false)
    val subjectType: EvaluationSubjectType,

    /** Register uuid of the plan, doel or instrument; the overlay id of an actie. */
    @Column(name = "subject_uuid", nullable = false)
    val subjectUuid: UUID,

    @Column(name = "subject_titel")
    var subjectTitel: String? = null,

    @Column(name = "soort", nullable = false)
    val soort: String,

    @Column(name = "van_waarde", columnDefinition = "TEXT")
    var vanWaarde: String? = null,

    @Column(name = "naar_waarde", columnDefinition = "TEXT")
    var naarWaarde: String? = null,

    @Column(name = "toelichting", columnDefinition = "TEXT")
    var toelichting: String? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now()
)

enum class EvaluationSubjectType { PLAN, HOOFDDOEL, SUBDOEL, INSTRUMENT, ACTIE }
