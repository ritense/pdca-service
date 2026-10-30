/*
 * Copyright 2015-2026 Ritense BV, the Netherlands.
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

import com.fasterxml.jackson.databind.ObjectMapper
import com.ritense.pdca.plugin.PluginHostController
import com.ritense.pdca.plugin.PushedGzacAccess
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate
import java.util.UUID

/**
 * Product requests the way the registers describe them (ARCHITECTURE §9).
 *
 * Which products can be requested, and how, lives in Open Product: a
 * producttype that carries an actie named [START_ACTIE] can be requested
 * from the plan page. Requesting one files a **verzoek**: an object in the
 * Objecten API of the objecttype named in that actie, with the plan, doel,
 * subject and producttype as its data. GZAC's Verzoek plugin picks the
 * object up (through Open Notificaties) and starts the instrument dossier
 * [PRODUCT_CASE_KEY], which manages the instrument in Open Plan through the
 * plugin actions aanmaak-instrument and update-instrument
 * ([InstrumentActionService]). The app creates nothing in the plan and
 * keeps no record of the request: the verzoek is in the Objecten API, the
 * dossier in GZAC, and the instrument's `zaak` field in Open Plan points at
 * the dossier.
 *
 * The actie's `mapping` (Open Product's DMN-mapping shape) is read as the
 * start recipe: the `static` items `soort` (`verzoek`, the only kind so
 * far), `type` (the objecttype URL of the verzoek) and `typeVersion`; every
 * other static item is copied into the verzoek data; and the items under
 * the keys `plan`, `doel` and `producttype` map a value of that register
 * object (`regex` holds its path, e.g. `$.uuid`) onto a field (`name`) of
 * the verzoek data.
 */
@Service
class InstrumentAanvraagService(
    private val openPlanRestClient: RestClient,
    private val openProductRestClient: RestClient,
    private val objectenRestClient: RestClient,
    private val pushedGzacAccess: PushedGzacAccess,
    private val gzacClient: GzacClient,
    private val objectMapper: ObjectMapper
) {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /** Name of the Open Product actie that makes a producttype requestable. */
        const val START_ACTIE = "StartInstrument"

        /** The `type` of every verzoek this app files; the Verzoek plugin routes it to the instrument dossier. */
        const val VERZOEK_TYPE = "instrument"

        /** Case definition key of the instrument dossier (gzac/case-definitions/instrument-verzoek). */
        const val PRODUCT_CASE_KEY = "instrument-verzoek"

        /** Producttype parameter with the start status of the instrument (actief or gepland). */
        const val START_STATUS_PARAMETER = "startStatus"

        private const val SOORT_VERZOEK = "verzoek"
        private val CONTROL_STATICS = setOf("soort", "type", "typeVersion")
    }

    /** Outcome of a request: the created verzoek object and the requested product. */
    data class Verzoek(val objectUrl: String?, val productTitel: String)

    /** The start recipe of a producttype, read from its StartInstrument actie. */
    data class StartActie(
        val soort: String,
        val objecttype: String?,
        val typeVersion: Int,
        /** Static items other than the control ones; copied into the verzoek data. */
        val statics: Map<String, Any?>,
        /** Per source (`plan`, `doel`, `producttype`): field name → path into that object. */
        val mappings: Map<String, List<Pair<String, String>>>
    )

    fun start(doelUuid: UUID, producttypeUuid: UUID): Verzoek {
        val doel = get(openPlanRestClient, "/plannen/api/v0/doel/$doelUuid", "Open Plan")
        val planUuid = ((doel["plannen"] as? List<*>)?.firstOrNull() as? Map<*, *>)?.get("uuid") as? String
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Doel $doelUuid hangt niet onder een plan")
        val plan = get(openPlanRestClient, "/plannen/api/v0/plan/$planUuid", "Open Plan")
        val producttype = get(openProductRestClient, "/producttypen/api/v1/producttypen/$producttypeUuid", "Open Product")
        val code = producttype["code"] as? String ?: producttypeUuid.toString()
        val titel = producttype["naam"] as? String ?: code

        val actie = startActie(producttypeUuid, titel)
        if (actie.soort != SOORT_VERZOEK) {
            throw ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "De startactie van '$titel' heeft soort '${actie.soort}'; alleen 'verzoek' wordt ondersteund"
            )
        }
        val objecttype = actie.objecttype ?: throw ResponseStatusException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "De actie $START_ACTIE van '$titel' noemt geen objecttype van het verzoek " +
                "(static 'type' in de mapping); vul die in Open Product in"
        )

        // The dossier that is going to pick the verzoek up must be there and
        // its pdca process links must point at this app (self-healing after
        // a re-import).
        ensureProductCaseConfiguration()

        val (subjectId, bsn) = subjectOf(plan, doel)
        val data = linkedMapOf<String, Any?>(
            "planUuid" to planUuid,
            "doelUuid" to doelUuid.toString(),
            "subjectId" to subjectId,
            "productTypeUuid" to producttypeUuid.toString(),
            "productTypeCode" to code,
            "productTitel" to titel,
            "productUrn" to "urn:pdca:openproduct:producttype:$code",
            "startStatus" to parameter(producttype, START_STATUS_PARAMETER)
        )
        data += actie.statics
        data += resolveMappings(actie, mapOf("plan" to plan, "doel" to doel, "producttype" to producttype))

        val verzoek = linkedMapOf<String, Any?>("type" to VERZOEK_TYPE)
        bsn?.let { verzoek["bsn"] = it }
        verzoek["data"] = data.filterValues { it != null }
        val created = post(
            objectenRestClient, "/api/v2/objects", "Objecten API",
            mapOf(
                "type" to objecttype,
                "record" to mapOf(
                    "typeVersion" to actie.typeVersion,
                    "startAt" to LocalDate.now().toString(),
                    "data" to verzoek
                )
            )
        )
        val objectUrl = created["url"] as? String
        log.info("Verzoek for product '$code' filed for doel $doelUuid of plan $planUuid: ${objectUrl ?: "(no url)"}")
        return Verzoek(objectUrl, titel)
    }

    /**
     * The instrument dossier's pdca process links are FIXED and ship
     * **dangling** in the zip (configuration UUIDs are environment-specific).
     * Repairs them to the configuration GZAC pushed to this app, through
     * GZAC's plugin-configuration repair (dangling links keyed by link id,
     * re-targeted links by their current configuration id), which also
     * clears the case-configuration issue banner. Runs when GZAC pushes a
     * configuration and before every request (self-healing after a
     * re-import); fails with the reason when the dossier type is missing.
     */
    fun ensureProductCaseConfiguration() {
        val configId = pushedGzacAccess.latest()?.configuration?.configId
            ?: throw ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE, "Geen PDCA-pluginconfiguratie door GZAC gepusht; activeer PDCA in GZAC"
            )
        val processDefinitionId = gzacClient.latestProcessDefinitionId(PRODUCT_CASE_KEY)
        val pdcaLinks = gzacClient.processLinks(processDefinitionId).filter {
            it["processLinkType"] == "external_plugin" && it["pluginDefinitionKey"] == PluginHostController.PLUGIN_ID
        }
        if (pdcaLinks.isEmpty()) {
            throw ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "Dossiertype '$PRODUCT_CASE_KEY' heeft geen pdca-proceskoppelingen; importeer de nieuwste zip"
            )
        }
        val mappings = buildMap {
            pdcaLinks.forEach { link ->
                when (val current = link["externalPluginConfigurationId"] as? String) {
                    null -> put(link["id"] as String, configId)
                    configId -> Unit
                    else -> put(current, configId)
                }
            }
        }
        if (mappings.isEmpty()) return
        gzacClient.resolvePluginConfigurationMappings(
            PRODUCT_CASE_KEY, gzacClient.caseDefinitionVersionTag(PRODUCT_CASE_KEY), mappings
        )
        log.info("Repaired ${mappings.size} pdca process link(s) of dossier type '$PRODUCT_CASE_KEY' to configuration $configId")
    }

    // ---------------------------------------------------------- the recipe

    /** The StartInstrument actie of a producttype, read from Open Product (the acties list carries the mapping). */
    private fun startActie(producttypeUuid: UUID, titel: String): StartActie {
        val acties = list(openProductRestClient, "/producttypen/api/v1/acties?producttype__uuid=$producttypeUuid", "Open Product")
        val actie = acties.firstOrNull { (it["naam"] as? String)?.equals(START_ACTIE, ignoreCase = true) == true }
            ?: throw ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "Producttype '$titel' heeft geen actie '$START_ACTIE' in Open Product en kan niet worden aangevraagd"
            )
        return parseStartActie(actie["mapping"])
    }

    /** Reads the start recipe out of an actie's mapping (Open Product's DMN-mapping shape, see the class doc). */
    fun parseStartActie(mapping: Any?): StartActie {
        val root = mapping as? Map<*, *> ?: emptyMap<Any, Any>()
        val statics = (root["static"] as? List<*>).orEmpty()
            .filterIsInstance<Map<*, *>>()
            .mapNotNull { item -> (item["name"] as? String)?.let { it to item["value"] } }
            .toMap()
        val mappings = root.entries
            .filter { it.key != "static" && it.value is List<*> }
            .associate { (source, items) ->
                source.toString().lowercase() to (items as List<*>)
                    .filterIsInstance<Map<*, *>>()
                    .mapNotNull { item ->
                        val name = item["name"] as? String ?: return@mapNotNull null
                        val path = item["regex"] as? String ?: return@mapNotNull null
                        name to path
                    }
            }
        return StartActie(
            soort = (statics["soort"] as? String)?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: SOORT_VERZOEK,
            objecttype = (statics["type"] as? String)?.trim()?.takeIf { it.isNotBlank() },
            typeVersion = statics["typeVersion"]?.toString()?.trim()?.toIntOrNull() ?: 1,
            statics = statics.filterKeys { it !in CONTROL_STATICS },
            mappings = mappings
        )
    }

    /** Applies the per-source mappings of the recipe; unknown sources and missing values are skipped. */
    fun resolveMappings(actie: StartActie, sources: Map<String, Map<*, *>>): Map<String, Any?> = buildMap {
        actie.mappings.forEach { (source, items) ->
            val sourceObject = sources[source]
            if (sourceObject == null) {
                log.warn("The start recipe maps from unknown source '$source'; skipped")
                return@forEach
            }
            items.forEach { (name, path) ->
                valueAt(sourceObject, path)?.let { put(name.removePrefix("data."), it) }
            }
        }
    }

    /** The value at a `$.a.b` (or `a.b`) path in a register object; null when absent. */
    private fun valueAt(source: Map<*, *>, path: String): Any? =
        path.removePrefix("$").trim('.').split('.').filter { it.isNotBlank() }
            .fold(source as Any?) { current, key -> (current as? Map<*, *>)?.get(key) }

    private fun parameter(producttype: Map<*, *>, naam: String): String? =
        (producttype["parameters"] as? List<*>)?.filterIsInstance<Map<*, *>>()
            ?.firstOrNull { it["naam"] == naam }?.get("waarde") as? String

    /**
     * The subject of the plan from `plan.domeinregister`
     * (`urn:pdca:<register>:<resource>:<id>`): the id, plus the bsn when the
     * subject is a persoon — that bsn is the verzoek's initiator. A plan
     * without domeinregister falls back to the doel's persoon.
     */
    private fun subjectOf(plan: Map<*, *>, doel: Map<*, *>): Pair<String?, String?> {
        val parts = (plan["domeinregister"] as? String)?.split(':').orEmpty()
        val resource = parts.getOrNull(3)
        val id = parts.getOrNull(4)?.takeIf { it.isNotBlank() }
        val doelBsn = (doel["persoon"] as? Map<*, *>)?.get("bsn") as? String
        return when {
            id != null && resource == "persoon" -> id to id
            id != null -> id to null
            else -> doelBsn to doelBsn
        }
    }

    // ------------------------------------------------------ register calls

    private fun get(client: RestClient, path: String, register: String): Map<*, *> =
        call(register, path) { client.get().uri(path).retrieve().body(Map::class.java) }

    private fun list(client: RestClient, path: String, register: String): List<Map<*, *>> =
        (get(client, path, register)["results"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()

    private fun post(client: RestClient, path: String, register: String, body: Map<String, Any?>): Map<*, *> =
        call(register, path) {
            client.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsBytes(body))
                .retrieve()
                .body(Map::class.java)
        }

    /**
     * Runs a register call and turns its failures into answers for the plan
     * page: the register's own 4xx (validation, not found) keeps its status,
     * a contract failure (register not bound, GZAC down) keeps its 503, and
     * anything else is a 502. The message is the `detail` of the answer.
     */
    private fun call(register: String, path: String, request: () -> Map<*, *>?): Map<*, *> = try {
        request() ?: throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "$register gaf geen antwoord op $path")
    } catch (e: ResponseStatusException) {
        throw e
    } catch (e: RestClientResponseException) {
        val detail = detailOf(e.responseBodyAsString) ?: e.responseBodyAsString.take(300)
        val status = when (e.statusCode.value()) {
            400, 404, 422 -> HttpStatus.valueOf(e.statusCode.value())
            503 -> HttpStatus.SERVICE_UNAVAILABLE
            else -> HttpStatus.BAD_GATEWAY
        }
        throw ResponseStatusException(status, "$register: $detail")
    } catch (e: Exception) {
        throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "$register-aanroep $path mislukt: ${e.message}")
    }

    private fun detailOf(body: String): String? = try {
        val node = objectMapper.readTree(body)
        node.path("detail").takeIf { it.isTextual }?.asText()
            ?: node.path("invalidParams").takeIf { it.isArray && it.size() > 0 }
                ?.joinToString(", ") { "${it.path("name").asText()}: ${it.path("reason").asText()}" }
    } catch (e: Exception) {
        null
    }
}
