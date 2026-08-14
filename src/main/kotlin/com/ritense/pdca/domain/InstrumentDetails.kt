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
import java.time.LocalDateTime
import java.util.UUID

/**
 * PDCA voortgangsregistratie for an instrument/voorziening in Open Plan
 * (pk = Open Plan instrument uuid): bestede uren, effectiviteit and — when the
 * instrument is afgebroken — the mandatory reason. The register itself only
 * carries status/resultaat.
 */
@Entity
@Table(name = "instrument_details")
data class InstrumentDetails(
    @Id
    @Column(name = "instrument_uuid")
    val instrumentUuid: UUID,

    @Column(name = "plan_uuid", nullable = false)
    val planUuid: UUID,

    @Column(name = "uren_besteed")
    var urenBesteed: Int? = null,

    /** Effectiviteit 1 (geen effect) t/m 5 (zeer effectief). */
    @Column(name = "effectiviteit_score")
    var effectiviteitScore: Int? = null,

    @Column(name = "effectiviteit_toelichting", columnDefinition = "TEXT")
    var effectiviteitToelichting: String? = null,

    @Column(name = "afbreek_reden", columnDefinition = "TEXT")
    var afbreekReden: String? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now()
)
