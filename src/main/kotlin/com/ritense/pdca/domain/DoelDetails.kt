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
 * PDCA process overlay for a doel in Open Plan (pk = Open Plan doel uuid):
 * voortgang, the list ordering ([sortering] orders the flat doelen list) and
 * the fine-grained uitvoeringsstatus (the register only knows
 * actief/afgerond/geannuleerd).
 */
@Entity
@Table(name = "doel_details")
data class DoelDetails(
    @Id
    @Column(name = "doel_uuid")
    val doelUuid: UUID,

    @Column(name = "plan_uuid", nullable = false)
    val planUuid: UUID,

    /** Refinement of the register status `actief`: gepland or al gestart. */
    @Enumerated(EnumType.STRING)
    @Column(name = "uitvoerings_status", nullable = false)
    var uitvoeringsStatus: UitvoeringsStatus = UitvoeringsStatus.GEPLAND,

    @Column(name = "voortgang_score")
    var voortgangScore: Int? = null,

    @Column(name = "voortgang_toelichting", columnDefinition = "TEXT")
    var voortgangToelichting: String? = null,

    @Column(nullable = false)
    var sortering: Int = 0,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now()
)

enum class UitvoeringsStatus { GEPLAND, GESTART }
