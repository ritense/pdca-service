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

import com.fasterxml.jackson.databind.ObjectMapper
import com.ritense.pdca.registers.RegisterStubController
import com.ritense.pdca.repository.PlanDetailsRepository
import com.ritense.pdca.service.GzacClient
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.client.RestClient
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Plan = dossier (1:1). De taakformulier-route krijgt het dossier van GZAC
 * (documentId in de context); deze resource dekt de omgekeerde route: bij
 * "los een plan aanmaken" maakt de PDCA-app zelf het GZAC-dossier aan en
 * koppelt het plan er direct aan (plan.zaak = zaak-URN van het nieuwe
 * dossier). Zo levert ook de losse route alsnog een dossier op.
 */
@RestController
@RequestMapping("/api/v1/pdca", produces = [MediaType.APPLICATION_JSON_VALUE])
class DossierResource(
    private val planDetailsRepository: PlanDetailsRepository,
    private val gzacClient: GzacClient,
    private val openPlanRestClient: RestClient,
    private val registerStubs: RegisterStubController,
    private val objectMapper: ObjectMapper
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private companion object {
        val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    }

    data class DossierResponse(val documentId: String, val zaakUrn: String, val created: Boolean)

    @PostMapping("/plans/{planUuid}/dossier")
    fun createDossierForPlan(@PathVariable(name = "planUuid") planUuid: UUID): DossierResponse {
        val details = planDetailsRepository.findById(planUuid).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Geen plandetails voor plan $planUuid")
        }
        val caseDefinitionKey = details.caseDefinitionKey
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Plan $planUuid heeft geen caseDefinitionKey")

        val plan = openPlanRestClient.get()
            .uri("/plannen/api/v0/plan/$planUuid")
            .retrieve()
            .body(Map::class.java)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Plan $planUuid niet gevonden in Open Plan")

        // Idempotent: een plan is 1:1 een dossier, dus een bestaande koppeling wint.
        (plan["zaak"] as? String)?.takeIf { it.isNotBlank() }?.let { zaak ->
            return DossierResponse(documentId = zaak.substringAfterLast(':'), zaakUrn = zaak, created = false)
        }

        val content = dossierContent(plan)
        val dossier = gzacClient.createDossier(caseDefinitionKey, content)
        val zaakUrn = "urn:pdca:zaaksysteem:zaak:${dossier.documentId}"

        // Als bytes (met Content-Length): uWSGI voor Open Plan leest chunked
        // request bodies als leeg, waardoor de PATCH stilletjes een no-op wordt.
        val patchResponse = openPlanRestClient.patch()
            .uri("/plannen/api/v0/plan/$planUuid")
            .contentType(MediaType.APPLICATION_JSON)
            .body(objectMapper.writeValueAsBytes(mapOf("zaak" to zaakUrn)))
            .retrieve()
            .toBodilessEntity()
        log.info("Plan $planUuid gekoppeld aan dossier ${dossier.documentId} (PATCH ${patchResponse.statusCode})")

        return DossierResponse(documentId = dossier.documentId, zaakUrn = zaakUrn, created = true)
    }

    data class ResolvePlanResponse(val planUuid: String, val zaakUrn: String, val linked: Boolean)

    /**
     * Onderwater-koppeling voor de startformulier-route: het startformulier
     * zet een (optioneel) planId in de dossier-content; deze endpoint leest
     * het dossier via GZAC, valideert het plan en zet plan.zaak naar dit
     * dossier. Idempotent; de PDCA-schermen roepen dit aan zodra een dossier
     * (nog) geen gekoppeld plan heeft.
     */
    @PostMapping("/dossiers/{documentId}/resolve-plan")
    fun resolvePlan(@PathVariable(name = "documentId") documentId: String): ResolvePlanResponse {
        val zaakUrn = "urn:pdca:zaaksysteem:zaak:$documentId"
        val content = gzacClient.getDocument(documentId)["content"] as? Map<*, *>
        val planIdRaw = (content?.get("planId") as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Dossier $documentId bevat geen planId")
        // Tolerant voor meegekopieerde leestekens/aanhalingstekens: pak de uuid uit de invoer.
        val planId = UUID_PATTERN.find(planIdRaw)?.value
            ?: throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "planId '$planIdRaw' in dossier $documentId is geen geldig plan-id (uuid)"
            )

        val plan = try {
            openPlanRestClient.get()
                .uri("/plannen/api/v0/plan/$planId")
                .retrieve()
                .body(Map::class.java)
        } catch (e: Exception) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Plan '$planId' uit dossier $documentId is onbekend in Open Plan")
        } ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Plan '$planId' uit dossier $documentId is onbekend in Open Plan")

        val huidigeZaak = (plan["zaak"] as? String).orEmpty()
        if (huidigeZaak == zaakUrn) {
            return ResolvePlanResponse(planUuid = planId, zaakUrn = zaakUrn, linked = false)
        }
        if (huidigeZaak.isNotBlank()) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "Plan '$planId' is al gekoppeld aan een ander dossier (${huidigeZaak.substringAfterLast(':')})"
            )
        }

        openPlanRestClient.patch()
            .uri("/plannen/api/v0/plan/$planId")
            .contentType(MediaType.APPLICATION_JSON)
            .body(objectMapper.writeValueAsBytes(mapOf("zaak" to zaakUrn)))
            .retrieve()
            .toBodilessEntity()
        log.info("Plan $planId onderwater gekoppeld aan dossier $documentId (planId uit startformulier)")

        return ResolvePlanResponse(planUuid = planId, zaakUrn = zaakUrn, linked = true)
    }

    /**
     * Dossier-content volgens het documentschema van de case: subjectvelden
     * uit plan.domeinregister (urn:pdca:brp:persoon:<bsn> of
     * urn:pdca:objecten:object:<id>) plus de plan-referentievelden.
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
}
