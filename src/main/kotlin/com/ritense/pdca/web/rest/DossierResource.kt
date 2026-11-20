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

package com.ritense.pdca.web.rest

import com.ritense.pdca.domain.PlanDetails
import com.ritense.pdca.service.DossierLinkService
import com.ritense.pdca.service.GzacClient
import com.ritense.pdca.service.IntakeService
import com.ritense.pdca.service.PlanIntakeService
import com.ritense.pdca.web.rest.dto.PlanIntakeRequest
import com.ritense.pdca.web.rest.dto.PlanIntakeResponse
import com.ritense.pdca.web.rest.dto.PlanPrefillResponse
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * Plan = dossier (1:1). The link is a direct reference in the PDCA overlay
 * (plan_details.dossier_id, unique); the register (plan.zaak) is not used
 * for it. A plan and its plan dossier come into existence in one way only:
 * the create-plan task form at the end of an intake prefills itself from the
 * intake dossier (GET /dossiers/{documentId}/plan-prefill, mapping per case
 * type) and POST /plan-intake creates the plan structure together with its
 * plan dossier. The plan tabs find the plan of their dossier through
 * GET /dossiers/{documentId}/plan.
 */
@RestController
@RequestMapping("/api/v1/pdca", produces = [MediaType.APPLICATION_JSON_VALUE])
class DossierResource(
    private val dossierLinkService: DossierLinkService,
    private val intakeService: IntakeService,
    private val planIntakeService: PlanIntakeService,
    private val gzacClient: GzacClient
) {

    // ------------------------------------------------ intake -> plan (user task)

    /** Prefill for the create-plan task form from the intake dossier's content. */
    @GetMapping("/dossiers/{documentId}/plan-prefill")
    fun getPlanPrefill(@PathVariable(name = "documentId") documentId: String): PlanPrefillResponse =
        intakeService.prefill(documentId)

    /** Case-definition keys from GZAC — the case-type dropdown in PDCA Beheer. */
    @GetMapping("/case-definitions")
    fun getCaseDefinitions(): List<String> = gzacClient.caseDefinitionKeys()

    /**
     * Document paths from a case definition's document schema in GZAC —
     * path suggestions for the prefill mapping in PDCA Beheer.
     */
    @GetMapping("/case-definitions/{caseDefinitionKey}/document-paths")
    fun getDocumentPaths(@PathVariable(name = "caseDefinitionKey") caseDefinitionKey: String): List<String> =
        gzacClient.documentSchemaPaths(caseDefinitionKey)

    /**
     * Creates the full plan structure (Open Plan + overlay) and its plan
     * dossier, then requests the intake's instrumenten (one verzoek each).
     * Safe to repeat: a retry resumes a failed attempt, a request while
     * another runs is a 409, a complete plan comes back as `bestaand`.
     */
    @PostMapping("/plan-intake")
    fun createPlanFromIntake(@Valid @RequestBody request: PlanIntakeRequest): PlanIntakeResponse =
        planIntakeService.createPlanFromIntake(request)

    // ------------------------------------------------------- plan tabs

    /** This plan dossier's plan (direct link in the overlay), or 404. */
    @GetMapping("/dossiers/{documentId}/plan")
    fun getDossierPlan(@PathVariable(name = "documentId") documentId: String): PlanDetails =
        dossierLinkService.findByDossier(documentId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Geen plan gekoppeld aan dossier $documentId")
}
