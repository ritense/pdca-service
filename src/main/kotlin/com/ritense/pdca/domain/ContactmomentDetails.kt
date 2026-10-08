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
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * PDCA evaluation overlay for a contactmoment in Open Plan (pk = Open Plan
 * contactmoment uuid). The contactmoment carries datum/status/notitie; the
 * structured check-fase data (type, doelvoortgang, actiepunten) lives here.
 * A contactmoment that is the result of a completed evaluation session
 * points to it through [evaluationSessionId] (its plan changes).
 */
@Entity
@Table(name = "contactmoment_details")
data class ContactmomentDetails(
    @Id
    @Column(name = "contactmoment_uuid")
    val contactmomentUuid: UUID,

    @Column(name = "plan_uuid", nullable = false)
    val planUuid: UUID,

    @Column(name = "evaluatie_type", nullable = false)
    var evaluatieType: String,

    @Column(name = "geplande_datum")
    var geplandeDatum: LocalDate? = null,

    @Column(columnDefinition = "TEXT")
    var deelnemers: String? = null,

    /** JSON: [{"doelUuid": "...", "score": 80, "toelichting": "..."}] */
    @Column(name = "doel_voortgang", columnDefinition = "TEXT")
    var doelVoortgang: String? = null,

    /** JSON: ["actiepunt", ...] */
    @Column(columnDefinition = "TEXT")
    var actiepunten: String? = null,

    @Column(name = "evaluation_session_id")
    var evaluationSessionId: UUID? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now()
)
