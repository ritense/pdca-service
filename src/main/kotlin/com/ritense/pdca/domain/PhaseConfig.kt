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
 * PDCA configuration per GZAC case definition. Fasering is optional: when
 * [categorieOrdening] is set, the doelen view orders its doelcategorie groups
 * accordingly (e.g. Verkenning, Uitvoering, Nazorg); without it, categories
 * are shown alphabetically.
 */
@Entity
@Table(name = "phase_config")
data class PhaseConfig(
    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(name = "case_definition_key", nullable = false, unique = true)
    val caseDefinitionKey: String,

    /** JSON array of doelcategorie names defining the display order. */
    @Column(name = "categorie_ordening", nullable = false, columnDefinition = "TEXT")
    var categorieOrdening: String,

    /** JSON array of evaluation types (INTAKE, PROGRESS, ...). */
    @Column(name = "evaluation_types", nullable = false, columnDefinition = "TEXT")
    var evaluationTypes: String,

    /** JSON array of configurable plan display statuses (Concept, Vastgesteld, ...). */
    @Column(name = "plan_statussen", columnDefinition = "TEXT")
    var planStatussen: String? = null,

    /**
     * JSON array of positietypen (inwoner-/objectposities) for this domein.
     * Begin- en doelpositie of a plan must come from this register; free text
     * is rejected when the register is configured.
     */
    @Column(name = "positie_typen", columnDefinition = "TEXT")
    var positieTypen: String? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now()
)
