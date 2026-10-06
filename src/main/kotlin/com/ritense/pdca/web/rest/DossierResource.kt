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
import com.ritense.pdca.web.rest.dto.PlanIntakeRequest
import com.ritense.pdca.web.rest.dto.PlanIntakeResponse
import com.ritense.pdca.web.rest.dto.PlanPrefillResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.client.RestClient
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Plan = dossier (1:1). The link is a direct reference in the PDCA overlay
 * (plan_details.dossier_id, unique); the register (plan.zaak) is not used
 * for it. Routes:
 *
 *  - user task (intake): GET /dossiers/{documentId}/plan-prefill prefills
 *    the create-plan task form from the dossier content (mapping per case
 *    type), POST /plan-intake creates the full plan structure and links it;
 *  - task form, link an existing plan: PUT /dossiers/{documentId}/plan;
 *  - start form: POST /dossiers/{documentId}/resolve-plan reads a planId
 *    from the dossier content via GZAC and links under water;
 *  - standalone route: POST /plans/{planUuid}/dossier creates the GZAC
 *    dossier itself (plan = dossier: a standalone plan still yields a
 *    dossier) and links it.
 */
@RestController
@RequestMapping("/api/v1/pdca", produces = [MediaType.APPLICATION_JSON_VALUE])
class DossierResource(
    private val dossierLinkService: DossierLinkService,
    private val intakeService: IntakeService,
    private val gzacClient: GzacClient,
    private val openPlanRestClient: RestClient
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private companion object {
        val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    }

    // ------------------------------------------------ intake -> plan (user task)

    /** Prefill for the create-plan task form from the dossier content. */
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

    /** Creates the full plan structure (Open Plan + overlay) and links the dossier. */
    @PostMapping("/plan-intake")
    fun createPlanFromIntake(@RequestBody request: PlanIntakeRequest): PlanIntakeResponse =
        intakeService.createPlanFromIntake(request)

    // -------------------------------------------------------- direct link

    data class DossierPlanRequest(val planUuid: UUID)

    /** This dossier's plan (direct link in the overlay), or 404. */
    @GetMapping("/dossiers/{documentId}/plan")
    fun getDossierPlan(@PathVariable(name = "documentId") documentId: String): PlanDetails =
        dossierLinkService.findByDossier(documentId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Geen plan gekoppeld aan dossier $documentId")

    /** Explicitly link a plan to this dossier (task-form route). */
    @PutMapping("/dossiers/{documentId}/plan")
    fun linkDossierPlan(
        @PathVariable(name = "documentId") documentId: String,
        @RequestBody request: DossierPlanRequest
    ): PlanDetails = dossierLinkService.link(request.planUuid, documentId)

    /** Unlink the plan from this dossier (admin/demo reset). */
    @DeleteMapping("/dossiers/{documentId}/plan")
    fun unlinkDossierPlan(@PathVariable(name = "documentId") documentId: String): ResponseEntity<Unit> =
        dossierLinkService.unlink(documentId)
            ?.let { ResponseEntity.noContent().build() }
            ?: ResponseEntity.notFound().build()

    // ------------------------------------------------------ standalone route

    data class DossierResponse(val documentId: String, val created: Boolean)

    @PostMapping("/plans/{planUuid}/dossier")
    fun createDossierForPlan(@PathVariable(name = "planUuid") planUuid: UUID): DossierResponse {
        val (documentId, created) = dossierLinkService.ensurePlanDossier(planUuid)
        return DossierResponse(documentId = documentId, created = created)
    }

    // ------------------------------------------------------ start-form route

    data class ResolvePlanResponse(val planUuid: String, val documentId: String, val linked: Boolean)

    /**
     * Under-water link for the start-form route: the start form puts an
     * (optional) planId in the dossier content; this endpoint reads the
     * dossier via GZAC, validates the plan and creates the direct link.
     * Idempotent; the PDCA views call this whenever a dossier has no linked
     * plan (yet).
     */
    @PostMapping("/dossiers/{documentId}/resolve-plan")
    fun resolvePlan(@PathVariable(name = "documentId") documentId: String): ResolvePlanResponse {
        // Already linked: return idempotently without a GZAC read.
        dossierLinkService.findByDossier(documentId)?.let {
            return ResolvePlanResponse(planUuid = it.planUuid.toString(), documentId = documentId, linked = false)
        }
        val content = gzacClient.getDocument(documentId)["content"] as? Map<*, *>
        val planIdRaw = (content?.get("planId") as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Dossier $documentId bevat geen planId")
        // Tolerant of copied punctuation/quotes: extract the uuid from the input.
        val planId = UUID_PATTERN.find(planIdRaw)?.value
            ?: throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "planId '$planIdRaw' in dossier $documentId is geen geldig plan-id (uuid)"
            )

        try {
            openPlanRestClient.get()
                .uri("/plannen/api/v0/plan/$planId")
                .retrieve()
                .toBodilessEntity()
        } catch (e: Exception) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Plan '$planId' uit dossier $documentId is onbekend in Open Plan")
        }

        val linked = try {
            dossierLinkService.link(UUID.fromString(planId), documentId)
        } catch (e: ResponseStatusException) {
            if (e.statusCode == HttpStatus.NOT_FOUND) {
                // The plan exists in the register but has no PDCA overlay: an
                // explainable error the task/tab view shows (only 400/409 are shown).
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Plan '$planId' uit dossier $documentId heeft geen PDCA-plandetails; maak het plan via PDCA aan"
                )
            }
            throw e
        }
        log.info("Plan ${linked.planUuid} onderwater gekoppeld aan dossier $documentId (planId uit startformulier)")
        return ResolvePlanResponse(planUuid = linked.planUuid.toString(), documentId = documentId, linked = true)
    }
}
