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

package com.ritense.pdca.service

import com.ritense.pdca.domain.PdcaUrn
import com.ritense.pdca.domain.PlanDetails
import com.ritense.pdca.registers.StubRegisterService
import com.ritense.pdca.registers.openplan.OpenPlanGateway
import com.ritense.pdca.registers.openplan.Plan
import com.ritense.pdca.repository.PlanDetailsRepository
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDateTime
import java.util.UUID

/**
 * The direct plan = dossier link (1:1): plan_details.dossier_id, unique.
 * [ensurePlanDossier] yields the plan's plan dossier: the linked one while it
 * still exists in GZAC, otherwise one GZAC already holds for the plan,
 * otherwise a new one created in GZAC and linked.
 */
@Service
class DossierLinkService(
    private val planDetailsRepository: PlanDetailsRepository,
    private val gzacClient: GzacClient,
    private val openPlanGateway: OpenPlanGateway,
    private val stubRegisterService: StubRegisterService
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Ensures the plan has its own plan dossier (plan = dossier 1:1): an
     * existing link wins — provided that dossier still exists. Otherwise a
     * dossier of the plan's caseDefinitionKey whose `planId` is this plan is
     * looked up in GZAC first: creating one and linking it are two steps, so
     * an earlier attempt may have created it and then lost GZAC's answer (a
     * read timeout) or failed to save the link. The oldest such dossier is
     * linked; only when there is none is a new one created and linked.
     * [onderwerp] is the subject's display name as the caller knows it (the
     * intake); see [dossierContent]. Returns (documentId, newly created).
     */
    fun ensurePlanDossier(planUuid: UUID, onderwerp: String? = null): Pair<String, Boolean> {
        val details = planDetailsRepository.findById(planUuid).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Geen plandetails voor plan $planUuid")
        }
        details.dossierId?.let { bestaand ->
            if (gzacClient.documentExists(bestaand)) {
                return bestaand to false
            }
        }
        val caseDefinitionKey = details.caseDefinitionKey
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Plan $planUuid heeft geen caseDefinitionKey")

        gzacClient.findDossierIds(caseDefinitionKey, PLAN_ID_FIELD, planUuid.toString()).firstOrNull()?.let { found ->
            link(planUuid, found)
            log.info("Plan {} linked to its existing plan dossier {} ({})", planUuid, found, caseDefinitionKey)
            return found to false
        }

        val plan = openPlanGateway.getPlan(planUuid)

        val dossier = gzacClient.createDossier(caseDefinitionKey, dossierContent(plan, onderwerp?.takeIf { it.isNotBlank() }))
        link(planUuid, dossier.documentId)
        log.info("Plan {} linked to its new plan dossier {} ({})", planUuid, dossier.documentId, caseDefinitionKey)
        return dossier.documentId to true
    }

    /**
     * Dossier content following the case's document schema: subject fields
     * from plan.domeinregister (a persoon or object URN, [PdcaUrn]) plus the
     * plan reference fields. `onderwerp` is the dossier's subject line:
     * [onderwerp] when given, otherwise the name from the (stub) register,
     * otherwise the plan titel; a person unknown to the register gets that
     * name as `naam` too.
     */
    private fun dossierContent(plan: Plan, onderwerp: String?): Map<String, Any?> {
        val content = mutableMapOf<String, Any?>(
            PLAN_ID_FIELD to plan.uuid.toString(),
            "planTitel" to plan.titel
        )
        val subject = PdcaUrn.parse(plan.domeinregister)
        var registerName: String? = null
        when (subject?.kind) {
            PdcaUrn.Kind.PERSOON -> {
                registerName = stubRegisterService.persoon(subject.id)?.naam
                content["bsn"] = subject.id
                content["naam"] = registerName ?: onderwerp
            }
            PdcaUrn.Kind.OBJECT -> {
                registerName = stubRegisterService.registerObject(subject.id)?.naam
                content["objectId"] = subject.id
                content["objectNaam"] = registerName
            }
            else -> {}
        }
        content["onderwerp"] = onderwerp ?: registerName ?: plan.titel
        return content
    }

    fun findByDossier(documentId: String): PlanDetails? =
        planDetailsRepository.findByDossierId(documentId)

    /** Points the plan at its plan dossier. */
    private fun link(planUuid: UUID, documentId: String) {
        val details = planDetailsRepository.findById(planUuid).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Geen plandetails voor plan $planUuid")
        }
        details.dossierId = documentId
        details.updatedAt = LocalDateTime.now()
        planDetailsRepository.save(details)
    }

    private companion object {
        /** The plan dossier's document field with the plan's uuid; also how an unlinked plan dossier is found. */
        const val PLAN_ID_FIELD = "planId"
    }
}
