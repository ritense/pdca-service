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
 * PDCA process overlay for a plan in Open Plan (pk = Open Plan plan uuid).
 * Everything else about a plan (titel, notitie, status, startdatum, zaak- en
 * domeinregister-URN's) lives in Open Plan itself and is accessed directly.
 */
@Entity
@Table(name = "plan_details")
data class PlanDetails(
    @Id
    @Column(name = "plan_uuid")
    val planUuid: UUID,

    /** Open Plan persoon for this plan's doelen (doelen require a persoon). */
    @Column(name = "persoon_uuid", nullable = false)
    val persoonUuid: UUID,

    /** GZAC case definition driving the PDCA configuration. */
    @Column(name = "case_definition_key")
    var caseDefinitionKey: String? = null,

    /**
     * Configurable plan display status (e.g. Concept, Vastgesteld) shown while
     * the register status is `actief`; valid values come from the case config.
     */
    @Column(name = "weergave_status")
    var weergaveStatus: String? = null,

    @Column(name = "start_situatie", columnDefinition = "TEXT")
    var startSituatie: String? = null,

    @Column(name = "gewenste_situatie", columnDefinition = "TEXT")
    var gewensteSituatie: String? = null,

    @Column(name = "streef_einddatum")
    var streefEinddatum: LocalDate? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now()
)
