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

package com.ritense.pdca.web.rest.dto

/**
 * Prefill for the create-plan task form, built from the dossier content
 * through the case type's prefill mapping (PhaseConfig.prefillMapping or the
 * default paths). `prefill` only contains the fields found in the document —
 * the form works with minimal as well as maximal prefill.
 */
data class PlanPrefillResponse(
    val documentId: String,
    val caseDefinitionKey: String?,
    /**
     * Case type of the PLAN that results from this dossier. Equal to
     * [caseDefinitionKey] for plan case types; for an intake case type it
     * points at the plan case type (PhaseConfig.planCaseDefinitionKey) — the
     * task form then creates a plan dossier of that type next to the plan
     * instead of linking to this dossier.
     */
    val planCaseDefinitionKey: String?,
    val mapping: Map<String, String>,
    val prefill: Map<String, Any?>
)

/**
 * The full plan structure from the intake: the create-plan task form sends
 * this (edited by the user) to POST /api/v1/pdca/plan-intake. Everything
 * except subject + titel is optional — minimal prefill yields a bare plan,
 * maximal prefill a complete plan with doelen, instrumenten, contactmomenten
 * and betrokkenen.
 */
data class PlanIntakeRequest(
    val documentId: String? = null,
    val caseDefinitionKey: String,
    val subjectType: String,
    val subjectId: String,
    val titel: String,
    val notitie: String? = null,
    val dienstverlening: String? = null,
    val hoofddoel: String? = null,
    val subdoelgroep: String? = null,
    val weergaveStatus: String? = null,
    val beginPositie: String? = null,
    val startdatum: String? = null,
    val streefEinddatum: String? = null,
    val doelen: List<IntakeDoel> = emptyList(),
    val instrumenten: List<IntakeInstrument> = emptyList(),
    val contactmomenten: List<IntakeContactmoment> = emptyList(),
    val betrokkenen: List<IntakeBetrokkene> = emptyList()
)

data class IntakeDoel(
    val doelType: String,
    val beschrijving: String? = null,
    val status: String? = null,
    val resultaat: String? = null,
    val voortgang: Int? = null,
    val toelichting: String? = null
)

data class IntakeInstrument(
    val titel: String,
    val product: String? = null,
    val doel: String? = null,
    val status: String? = null,
    val resultaat: String? = null,
    val urenBesteed: Int? = null,
    val effectiviteit: Int? = null
)

data class IntakeContactmoment(
    val datum: String? = null,
    val evaluatieType: String? = null,
    val notitie: String? = null,
    val status: String? = null,
    val deelnemers: String? = null,
    val actiepunten: List<String>? = null
)

data class IntakeBetrokkene(
    val naam: String,
    val rol: String,
    val email: String? = null,
    val organisatie: String? = null,
    val hoofdverantwoordelijke: Boolean = false
)

data class PlanIntakeResponse(
    val planUuid: String,
    val documentId: String?,
    /**
     * The plan dossier the plan is linked 1:1 to: the dossier itself (plan
     * case type), or the newly created plan dossier (intake and standalone
     * route) — plan and dossier always come into existence together.
     */
    val planDossierId: String?,
    val doelen: Int,
    val instrumenten: Int,
    val contactmomenten: Int,
    val betrokkenen: Int,
    val warnings: List<String>,
    /**
     * True when this intake had already produced a plan and nothing was
     * created: the counts are then those of the existing plan's structure as
     * far as the overlay knows them (0), not of this call. The task form uses
     * it to report "already created" and complete the task on that plan.
     */
    val bestaand: Boolean = false
)
