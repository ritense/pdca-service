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
     * Direct link to the GZAC dossier (documentId). Unique: plan = dossier
     * (1:1) is enforced by the database. Null while the plan is standalone;
     * linking/unlinking runs through the dossier endpoints in
     * [com.ritense.pdca.web.rest.DossierResource].
     */
    @Column(name = "dossier_id", unique = true)
    var dossierId: String? = null,

    /**
     * The intake dossier this plan was created from, when it came in through
     * the intake route (a case type with `planCaseDefinitionKey`). The plan
     * itself belongs to the plan dossier in [dossierId]; this is the only
     * trace back to the intake, and it is what makes a repeated create-plan
     * submission from that intake idempotent instead of running into the
     * one-active-plan-per-inwoner rule with no way out.
     */
    @Column(name = "intake_dossier_id")
    var intakeDossierId: String? = null,

    /**
     * Configurable plan display status (e.g. Concept, Vastgesteld) shown while
     * the register status is `actief`; valid values come from the case config.
     */
    @Column(name = "weergave_status")
    var weergaveStatus: String? = null,

    /**
     * The positie from the domein's positietype register
     * (PhaseConfig.positieTypen); no free text. The beginpositie enters PDCA
     * as input (usually from the intake); the procesbegeleider can adjust
     * both within a running plan.
     */
    @Column(name = "begin_positie", columnDefinition = "TEXT")
    var beginPositie: String? = null,

    /**
     * The inwoner's subdoelgroep (W&P segmentation): the finer segment the
     * inwonerpositie aggregates. Advised by the DVKM decision tables in the
     * intake (the subdoelgroep catalog lives in the DMN, not in a config);
     * the vervolggesprek confirms or overrides it.
     */
    @Column(name = "subdoelgroep")
    var subdoelgroep: String? = null,

    /**
     * The plan's single hoofddoel: a reference to a hoofddoel doeltype in
     * the Open Plan doeltype register (category "Hoofddoel"). Free text
     * belongs in plan.notitie (explanation for the inwoner).
     */
    @Column(name = "hoofddoel_type_uuid")
    var hoofddoelTypeUuid: UUID? = null,

    @Column(name = "streef_einddatum")
    var streefEinddatum: LocalDate? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now()
)
