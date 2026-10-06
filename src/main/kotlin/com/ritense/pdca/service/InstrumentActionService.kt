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
import com.ritense.pdca.domain.InstrumentDetails
import com.ritense.pdca.repository.InstrumentDetailsRepository
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.client.RestClient

/**
 * The instrumenten/producten side of the plugin actions: this is how a
 * product building block ("product X aanvragen") manages the instrument in
 * Open Plan itself. The pdca-app creates nothing when starting such a
 * building block — the process decides if and when the instrument comes into
 * existence (for example only after the request is granted) and when it is
 * updated.
 *
 *  - aanmaak-instrument: creates the instrument under a doel and returns the
 *    instrumentUuid as a process variable (the variables channel of the
 *    action response), so the process can use it later on;
 *  - update-instrument: sets status afgerond/geannuleerd (+ resultaat) in
 *    the register and stores an optional toelichting in the PDCA overlay.
 */
@Service
class InstrumentActionService(
    private val instrumentDetailsRepository: InstrumentDetailsRepository,
    private val openPlanRestClient: RestClient,
    private val objectMapper: ObjectMapper
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Plugin action aanmaak-instrument. Properties: doelUuid (required),
     * titel (required), product (URN, optional), zaak (URN, optional —
     * DOSSIER products pass their aanvraagdossier's URN here, which is how
     * the plan page links to the running dossier), status (optional,
     * default "actief"). Returns {"instrumentUuid": "..."} as declared
     * output (result channel).
     */
    @Transactional
    fun handleAanmaakInstrument(properties: Map<String, Any?>): Map<String, Any?> {
        val doelUuid = requiredUuid(properties, "doelUuid")
        val titel = (properties["titel"] as? String)?.trim()?.takeIf { it.isNotBlank() }
            ?: throw PluginActionException("TITEL_ONTBREEKT", "Property 'titel' ontbreekt of is leeg")
        val product = (properties["product"] as? String)?.takeIf { it.isNotBlank() }
        val zaak = (properties["zaak"] as? String)?.takeIf { it.isNotBlank() }
        val status = (properties["status"] as? String)?.takeIf { it.isNotBlank() } ?: "actief"

        val instrumenttypeUuid = listRegister("/plannen/api/v0/instrumenttype")
            .firstOrNull()?.get("uuid") as? String
        val created = postRegister(
            "/plannen/api/v0/instrument", buildMap {
                put("titel", titel)
                put("startdatum", "${LocalDate.now()}T00:00:00Z")
                put("doelenUuids", listOf(doelUuid.toString()))
                put("ontwikkelwensenUuids", emptyList<String>())
                instrumenttypeUuid?.let { put("instrumenttypeUuid", it) }
                product?.let { put("product", it) }
                zaak?.let { put("zaak", it) }
                put("status", status)
            }
        )
        val instrumentUuid = created["uuid"] as? String
            ?: throw PluginActionException("AANMAAK_MISLUKT", "Open Plan gaf geen uuid terug voor het nieuwe instrument")

        log.info("aanmaak-instrument: created '$titel' ($instrumentUuid) under doel $doelUuid")
        return mapOf("instrumentUuid" to instrumentUuid)
    }

    /**
     * Plugin action update-instrument. Properties: instrumentUuid (required),
     * status (required: afgerond or geannuleerd), resultaat (optional;
     * behaald/gefaald, defaults to behaald for afgerond), toelichting
     * (optional — stored in the PDCA overlay: effectiviteitToelichting for
     * afgerond, afbreekReden for geannuleerd; that requires planUuid).
     */
    @Transactional
    fun handleUpdateInstrument(properties: Map<String, Any?>) {
        val instrumentUuid = requiredUuid(properties, "instrumentUuid")
        val status = (properties["status"] as? String)?.trim()?.lowercase()
            ?: throw PluginActionException("STATUS_ONTBREEKT", "Property 'status' ontbreekt")
        if (status !in setOf("afgerond", "geannuleerd")) {
            throw PluginActionException(
                "STATUS_ONGELDIG",
                "Onbekende status '$status' (verwacht: afgerond of geannuleerd)"
            )
        }
        val resultaat = (properties["resultaat"] as? String)?.takeIf { it.isNotBlank() }
        val toelichting = (properties["toelichting"] as? String)?.takeIf { it.isNotBlank() }
        val planUuid = (properties["planUuid"] as? String)?.let {
            try { UUID.fromString(it.trim()) } catch (e: IllegalArgumentException) { null }
        }

        patchRegister(
            "/plannen/api/v0/instrument/$instrumentUuid", buildMap {
                put("status", status)
                put("einddatum", "${LocalDate.now()}T00:00:00Z")
                if (status == "afgerond") put("resultaat", resultaat ?: "behaald")
            }
        )

        if (toelichting != null && planUuid != null) {
            val details = instrumentDetailsRepository.findById(instrumentUuid).orElse(
                InstrumentDetails(instrumentUuid = instrumentUuid, planUuid = planUuid)
            )
            if (status == "geannuleerd") details.afbreekReden = toelichting
            else details.effectiviteitToelichting = toelichting
            details.updatedAt = LocalDateTime.now()
            instrumentDetailsRepository.save(details)
        }

        log.info("update-instrument: $instrumentUuid -> $status${resultaat?.let { " ($it)" } ?: ""}")
    }

    private fun requiredUuid(properties: Map<String, Any?>, key: String): UUID {
        val raw = properties[key] as? String
            ?: throw PluginActionException(
                "${key.uppercase()}_ONTBREEKT", "Property '$key' ontbreekt of is geen tekst"
            )
        return try {
            UUID.fromString(raw.trim())
        } catch (e: IllegalArgumentException) {
            throw PluginActionException("${key.uppercase()}_ONGELDIG", "Property '$key' is geen uuid: '$raw'")
        }
    }

    // --------------------------------------------------- Open Plan helpers

    private fun listRegister(path: String): List<Map<*, *>> {
        val results = mutableListOf<Map<*, *>>()
        var page = 1
        while (page <= 20) {
            val body = openPlanRestClient.get()
                .uri("$path?page=$page")
                .retrieve()
                .body(Map::class.java) ?: break
            (body["results"] as? List<*>)?.filterIsInstance<Map<*, *>>()?.let { results += it }
            if (body["next"] == null) break
            page++
        }
        return results
    }

    private fun postRegister(path: String, body: Map<String, Any?>): Map<*, *> = try {
        openPlanRestClient.post()
            .uri(path)
            .contentType(MediaType.APPLICATION_JSON)
            .body(objectMapper.writeValueAsBytes(body))
            .retrieve()
            .body(Map::class.java)
            ?: throw PluginActionException("REGISTER_FOUT", "Open Plan gaf geen antwoord op POST $path")
    } catch (e: PluginActionException) {
        throw e
    } catch (e: Exception) {
        throw PluginActionException("REGISTER_FOUT", "Open Plan POST $path mislukt: ${e.message}")
    }

    private fun patchRegister(path: String, body: Map<String, Any?>): Map<*, *> = try {
        openPlanRestClient.patch()
            .uri(path)
            .contentType(MediaType.APPLICATION_JSON)
            .body(objectMapper.writeValueAsBytes(body))
            .retrieve()
            .body(Map::class.java)
            ?: throw PluginActionException("REGISTER_FOUT", "Open Plan gaf geen antwoord op PATCH $path")
    } catch (e: PluginActionException) {
        throw e
    } catch (e: Exception) {
        throw PluginActionException("REGISTER_FOUT", "Open Plan PATCH $path mislukt: ${e.message}")
    }
}
