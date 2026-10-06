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

import com.ritense.pdca.domain.PlanDetails
import com.ritense.pdca.registers.RegisterStubController
import com.ritense.pdca.repository.PlanDetailsRepository
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDateTime
import java.util.UUID

/**
 * The direct plan = dossier link (1:1): plan_details.dossier_id, unique.
 * Self-healing for the dev cycle: when an existing link points at a dossier
 * that was deleted in GZAC, it is released instead of holding the plan
 * hostage. [ensurePlanDossier] yields the matching plan dossier: the
 * existing one when present, otherwise it is created in GZAC (standalone
 * and intake route).
 */
@Service
class DossierLinkService(
    private val planDetailsRepository: PlanDetailsRepository,
    private val gzacClient: GzacClient,
    private val openPlanRestClient: RestClient,
    private val registerStubs: RegisterStubController
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Ensures the plan has its own plan dossier (plan = dossier 1:1): an
     * existing link wins — provided that dossier still exists — otherwise a
     * GZAC dossier of the plan's caseDefinitionKey is created and linked.
     * Returns (documentId, newly created).
     */
    fun ensurePlanDossier(planUuid: UUID): Pair<String, Boolean> {
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

        val plan = openPlanRestClient.get()
            .uri("/plannen/api/v0/plan/$planUuid")
            .retrieve()
            .body(Map::class.java)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Plan $planUuid niet gevonden in Open Plan")

        val dossier = gzacClient.createDossier(caseDefinitionKey, dossierContent(plan))
        link(planUuid, dossier.documentId)
        log.info("Plan $planUuid gekoppeld aan nieuw plan-dossier ${dossier.documentId} ($caseDefinitionKey)")
        return dossier.documentId to true
    }

    /**
     * Dossier content following the case's document schema: subject fields
     * from plan.domeinregister (urn:pdca:brp:persoon:<bsn> or
     * urn:pdca:objecten:object:<id>) plus the plan reference fields.
     */
    private fun dossierContent(plan: Map<*, *>): Map<String, Any?> {
        val content = mutableMapOf<String, Any?>(
            "planId" to plan["uuid"],
            "planTitel" to plan["titel"],
            "planStatus" to plan["status"]
        )
        val domeinregister = (plan["domeinregister"] as? String).orEmpty().split(':')
        val resource = domeinregister.getOrNull(3)
        val id = domeinregister.getOrNull(4)
        when {
            resource == "persoon" && id != null -> {
                content["bsn"] = id
                content["naam"] = registerStubs.getPersoon(id).body?.naam
            }
            resource == "object" && id != null -> {
                content["objectId"] = id
                content["objectNaam"] = registerStubs.getObject(id).body?.naam
            }
        }
        return content
    }

    fun findByDossier(documentId: String): PlanDetails? =
        planDetailsRepository.findByDossierId(documentId)

    /**
     * Creates the 1:1 link with conflict guarding: 409 when the plan already
     * hangs under another (still existing) dossier or the dossier already
     * has another plan. Idempotent for an existing identical link.
     */
    fun link(planUuid: UUID, documentId: String): PlanDetails {
        val details = planDetailsRepository.findById(planUuid).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Geen plandetails voor plan $planUuid")
        }
        if (details.dossierId == documentId) return details
        details.dossierId?.let { bestaand ->
            if (gzacClient.documentExists(bestaand)) {
                throw ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Plan '$planUuid' is al gekoppeld aan een ander dossier ($bestaand)"
                )
            }
            log.info("Plan $planUuid was gekoppeld aan verdwenen dossier $bestaand; koppeling vrijgegeven")
            details.dossierId = null
        }
        planDetailsRepository.findByDossierId(documentId)?.let {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "Dossier $documentId heeft al een gekoppeld plan (${it.planUuid})"
            )
        }
        details.dossierId = documentId
        details.updatedAt = LocalDateTime.now()
        return planDetailsRepository.save(details)
    }

    /** Unlinks the plan from this dossier; null when there was no link. */
    fun unlink(documentId: String): PlanDetails? {
        val details = planDetailsRepository.findByDossierId(documentId) ?: return null
        details.dossierId = null
        details.updatedAt = LocalDateTime.now()
        planDetailsRepository.save(details)
        log.info("Plan ${details.planUuid} ontkoppeld van dossier $documentId")
        return details
    }
}
