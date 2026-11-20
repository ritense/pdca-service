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

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.ritense.pdca.web.rest.dto.PlanPrefillResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * The prefill of the create-plan task form, fed by the intake in the
 * dossier content: [prefill] reads the dossier via GZAC and translates the
 * content into a proposal for the form, using the document paths from the
 * case type's prefill mapping (PhaseConfig.prefillMapping, configurable in
 * PDCA Beheer; [DEFAULT_PREFILL_MAPPING] when nothing is configured).
 * Minimal content (just a bsn) yields a minimal prefill; a full intake
 * prefills the whole plan. Creating the plan is [PlanIntakeService].
 */
@Service
class IntakeService(
    private val gzacClient: GzacClient,
    private val phaseConfigService: PhaseConfigService,
    private val objectMapper: ObjectMapper
) {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /**
         * Sane default document paths (JSON pointers in the dossier
         * content). Same convention as the bundled intake case types; a case
         * type with a different content structure configures its own mapping
         * in PDCA Beheer.
         */
        val DEFAULT_PREFILL_MAPPING: Map<String, String> = linkedMapOf(
            "subjectIdPersoon" to "/bsn",
            "subjectIdObject" to "/objectId",
            "naam" to "/naam",
            "objectNaam" to "/objectNaam",
            "titel" to "/planTitel",
            "notitie" to "/planNotitie",
            "dienstverlening" to "/dienstverlening",
            "hoofddoel" to "/hoofddoel",
            "subdoelgroep" to "/subdoelgroep",
            "weergaveStatus" to "/weergaveStatus",
            "beginPositie" to "/beginPositie",
            "startdatum" to "/startdatum",
            "streefEinddatum" to "/streefEinddatum",
            "doelen" to "/doelen",
            "instrumenten" to "/instrumenten",
            "contactmomenten" to "/contactmomenten",
            "betrokkenen" to "/betrokkenen"
        )
    }

    // ------------------------------------------------------------- prefill

    fun prefill(documentId: String): PlanPrefillResponse {
        val document = gzacClient.getDocument(documentId)
        val content = document["content"] as? Map<*, *> ?: emptyMap<Any, Any>()
        val caseDefinitionKey = definitionName(document)
        val config = caseDefinitionKey?.let { phaseConfigService.findByCaseDefinitionKey(it) }
        val mapping = caseDefinitionKey?.let { prefillMapping(it) } ?: DEFAULT_PREFILL_MAPPING

        val prefill = mutableMapOf<String, Any?>()
        mapping.forEach { (veld, pad) ->
            resolvePointer(content, pad)?.let { prefill[veld] = it }
        }
        // Subject resolution: persoon wins; otherwise object.
        when {
            prefill["subjectIdPersoon"] != null -> {
                prefill["subjectType"] = "PERSON"
                prefill["subjectId"] = prefill.remove("subjectIdPersoon")
                prefill.remove("subjectIdObject")
                prefill.remove("objectNaam")
            }
            prefill["subjectIdObject"] != null -> {
                prefill["subjectType"] = "OBJECT"
                prefill["subjectId"] = prefill.remove("subjectIdObject")
                prefill["naam"] = prefill.remove("objectNaam") ?: prefill["naam"]
            }
        }
        return PlanPrefillResponse(
            documentId = documentId,
            caseDefinitionKey = caseDefinitionKey,
            planCaseDefinitionKey = config?.planCaseDefinitionKey?.takeIf { it.isNotBlank() },
            mapping = mapping,
            prefill = prefill
        )
    }

    private fun prefillMapping(caseDefinitionKey: String): Map<String, String> {
        val configured = phaseConfigService.findByCaseDefinitionKey(caseDefinitionKey)
            ?.prefillMapping?.takeIf { it.isNotBlank() } ?: return DEFAULT_PREFILL_MAPPING
        return try {
            objectMapper.readValue(configured, object : TypeReference<LinkedHashMap<String, String>>() {})
        } catch (e: Exception) {
            log.warn("Invalid prefill mapping for '{}'; using the default paths", caseDefinitionKey, e)
            DEFAULT_PREFILL_MAPPING
        }
    }

    private fun definitionName(document: Map<*, *>): String? =
        (document["definitionId"] as? Map<*, *>)?.get("name") as? String

    /** Minimal JSON-pointer resolution ("/a/b/0") over maps and lists. */
    private fun resolvePointer(root: Any?, pointer: String): Any? {
        var huidig: Any? = root
        for (segment in pointer.trim('/').split('/')) {
            if (segment.isEmpty()) continue
            huidig = when (huidig) {
                is Map<*, *> -> huidig[segment]
                is List<*> -> segment.toIntOrNull()?.let { huidig.getOrNull(it) }
                else -> null
            } ?: return null
        }
        return when (huidig) {
            is String -> huidig.takeIf { it.isNotBlank() }
            is List<*> -> huidig.takeIf { it.isNotEmpty() }
            else -> huidig
        }
    }
}
