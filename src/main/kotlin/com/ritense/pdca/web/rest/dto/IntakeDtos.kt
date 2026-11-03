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

import com.fasterxml.jackson.annotation.JsonIgnore
import com.ritense.pdca.domain.Bsn
import jakarta.validation.Valid
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern

/**
 * Prefill for the create-plan task form, built from the intake dossier's
 * content through the case type's prefill mapping (PhaseConfig.prefillMapping
 * or the default paths). `prefill` only contains the fields found in the
 * document — the form works with minimal as well as maximal prefill.
 */
data class PlanPrefillResponse(
    val documentId: String,
    val caseDefinitionKey: String?,
    /**
     * Case type of the plan that results from this intake dossier
     * (PhaseConfig.planCaseDefinitionKey of the intake case type); the plan
     * gets a plan dossier of that type. Null when the dossier's case type is
     * not configured as an intake: no plan can be created from it.
     */
    val planCaseDefinitionKey: String?,
    val mapping: Map<String, String>,
    val prefill: Map<String, Any?>
)

/**
 * The full plan structure from the intake: the create-plan task form sends
 * this (edited by the user) to POST /api/v1/pdca/plan-intake. Everything
 * except subject + titel is optional — minimal prefill yields a bare plan,
 * maximal prefill a complete plan with doelen, contactmomenten and
 * betrokkenen, plus the instrumenten to request once the plan exists. The
 * subject of a persoon plan must be a valid bsn (elfproef, [Bsn]).
 */
data class PlanIntakeRequest(
    /** The intake dossier the task form runs in. */
    @field:NotBlank(message = "Het intakedossier ontbreekt")
    val documentId: String,
    @field:Pattern(regexp = "PERSON|OBJECT", message = "Het onderwerp is een persoon (PERSON) of een object (OBJECT)")
    val subjectType: String,
    @field:NotBlank(message = "Vul een BSN of object-ID in")
    val subjectId: String,
    /**
     * Display name of the subject (inwoner or object) as the intake knows it;
     * becomes the plan dossier's onderwerp when the register has no name.
     */
    val subjectNaam: String? = null,
    @field:NotBlank(message = "Vul een titel voor het plan in")
    val titel: String,
    val notitie: String? = null,
    val dienstverlening: String? = null,
    val hoofddoel: String? = null,
    val subdoelgroep: String? = null,
    val weergaveStatus: String? = null,
    val beginPositie: String? = null,
    val startdatum: String? = null,
    val streefEinddatum: String? = null,
    @field:Valid
    val doelen: List<IntakeDoel> = emptyList(),
    val instrumenten: List<IntakeInstrument> = emptyList(),
    val contactmomenten: List<IntakeContactmoment> = emptyList(),
    val betrokkenen: List<IntakeBetrokkene> = emptyList()
) {
    @get:JsonIgnore
    @get:AssertTrue(message = "Het BSN is ongeldig (elfproef)")
    val isSubjectBsnGeldig: Boolean
        get() = subjectType != "PERSON" || subjectId.isBlank() || Bsn.isValid(subjectId.trim())
}

/** [status]: gepland (the default), gestart or afgerond. */
data class IntakeDoel(
    @field:NotBlank(message = "Elk subdoel heeft een doeltype")
    val doelType: String,
    val beschrijving: String? = null,
    val status: String? = null,
    val resultaat: String? = null,
    val toelichting: String? = null
)

/**
 * An instrument to request once the plan exists: [product] is the Open
 * Product producttype code, [doel] the subdoel by doeltype name. Without a
 * subdoel, or when two subdoelen share that doeltype, it goes under the
 * first (matching) subdoel with a warning; an unknown subdoel is not
 * requested and becomes a warning.
 */
data class IntakeInstrument(
    val product: String? = null,
    val doel: String? = null
)

data class IntakeContactmoment(
    val datum: String? = null,
    val evaluatieType: String? = null,
    val notitie: String? = null,
    val status: String? = null,
    val deelnemers: String? = null,
    val actiepunten: List<String>? = null
)

/**
 * [procesbegeleider] marks the plan's procesbegeleider; only the first
 * flagged betrokkene becomes it.
 */
data class IntakeBetrokkene(
    val naam: String,
    val rol: String,
    val email: String? = null,
    val organisatie: String? = null,
    val procesbegeleider: Boolean = false
)

data class PlanIntakeResponse(
    val planUuid: String,
    /** The intake dossier the plan came from. */
    val documentId: String,
    /** The plan dossier the plan is linked 1:1 to; always set, a plan is only done with its dossier. */
    val planDossierId: String,
    val doelen: Int,
    /** Verzoeken filed for the intake's instrumenten; each instrument appears once its instrument dossier created it. */
    val instrumentenAangevraagd: Int,
    val contactmomenten: Int,
    val betrokkenen: Int,
    val warnings: List<String>,
    /**
     * True when this intake's plan was complete already and nothing was
     * done: the counts are then 0. The task form reports "already created"
     * and completes the task on that plan.
     */
    val bestaand: Boolean = false,
    /** True when this call completed a plan an earlier, failed attempt had started. */
    val hervat: Boolean = false
)
