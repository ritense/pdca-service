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
 * PDCA configuration per GZAC case definition: evaluation types, plan display
 * statuses, the positietypen register, which subdoeltypen belong under which
 * hoofddoeltype, the intake prefill mapping and the intake -> plan case type
 * reference.
 */
@Entity
@Table(name = "phase_config")
data class PhaseConfig(
    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(name = "case_definition_key", nullable = false, unique = true)
    val caseDefinitionKey: String,

    /** JSON array of evaluation types (INTAKE, PROGRESS, ...). */
    @Column(name = "evaluation_types", nullable = false, columnDefinition = "TEXT")
    var evaluationTypes: String,

    /** JSON array of configurable plan display statuses (Concept, Vastgesteld, ...). */
    @Column(name = "plan_statussen", columnDefinition = "TEXT")
    var planStatussen: String? = null,

    /**
     * JSON array of positietypen (inwoner-/objectposities) for this domein.
     * The positie of a plan must come from this register; free text
     * is rejected when the register is configured.
     */
    @Column(name = "positie_typen", columnDefinition = "TEXT")
    var positieTypen: String? = null,

    /**
     * JSON object: hoofddoeltype name -> array of subdoeltype names that may
     * be chosen under it, e.g. {"Duurzaam aan het werk": ["Nazorg en borging"]}.
     * Keyed by name, like every other doeltype reference in the intake chain
     * (DMN output, task forms, prefill). A hoofddoel without an entry scopes
     * nothing: all subdoeltypen stay available.
     *
     * The register has no relation between two doeltypen, so this catalog
     * lives here; it moves into the register's Configuratie once Open Plan
     * models "subdoeltypen kunnen bij meerdere doeltypen horen".
     */
    @Column(name = "subdoel_mapping", columnDefinition = "TEXT")
    var subdoelMapping: String? = null,

    /**
     * JSON object: prefill field to JSON pointer in the dossier content,
     * e.g. {"titel": "/planTitel", "doelen": "/doelen"}. Determines how the
     * create-plan task form is prefilled from the (intake) document; empty =
     * the default paths (see IntakeService.DEFAULT_PREFILL_MAPPING).
     */
    @Column(name = "prefill_mapping", columnDefinition = "TEXT")
    var prefillMapping: String? = null,

    /**
     * Intake case types only: the case type of the PLAN that results from
     * the intake (e.g. "inwonerplan" for "intake-werk-participatie"). The
     * create-plan task form then creates a plan dossier of this type next to
     * the plan (standalone route) instead of linking the plan to the intake
     * dossier. Empty = this case type is itself the plan case type.
     */
    @Column(name = "plan_case_definition_key")
    var planCaseDefinitionKey: String? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now()
)
