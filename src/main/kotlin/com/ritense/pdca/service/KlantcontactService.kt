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

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.server.ResponseStatusException
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID

/** A contactmoment to record for a plan. */
data class NewContactmoment(
    val planUuid: UUID,
    val evaluatieType: String,
    val plaatsgevondenOp: OffsetDateTime,
    /** True for a contactmoment that took place, false for a planned one. */
    val afgerond: Boolean,
    /** Gespreksverslag / notitie; becomes the klantcontact's inhoud. */
    val verslag: String?
)

/** A klantcontact as far as PDCA needs it. */
data class Klantcontact(
    val uuid: UUID,
    /** The plan its onderwerpobject points at; null when it is not about a plan. */
    val planUuid: UUID?,
    val onderwerp: String?,
    val indicatieContactGelukt: Boolean?,
    val plaatsgevondenOp: OffsetDateTime?
) {
    /** A planned contactmoment has not taken place yet: Open Klant does not know whether it succeeded. */
    val gepland: Boolean get() = indicatieContactGelukt == null
}

/**
 * Contactmomenten are klantcontacten in Open Klant (Klantinteracties API).
 * A contactmoment of a plan is created in one call (`maak-klantcontact`):
 *
 *  - the klantcontact itself: kanaal "gesprek", onderwerp "<type>: <plan
 *    titel>", the verslag as inhoud, confidential, in Dutch;
 *    `indicatieContactGelukt` true when it took place, null while planned;
 *  - an onderwerpobject pointing at the plan in Open Plan (codeRegister
 *    `open-plan`, codeObjecttype `plan`, codeSoortObjectId `uuid`), which is
 *    also how a plan's contactmomenten are listed;
 *  - for a plan about a person, the inwoner as betrokkene (rol klant): the
 *    partij with that BSN, created with only the identificator when Open
 *    Klant does not know it yet — the name stays in the BRP.
 *
 * A planned contactmoment is rescheduled by moving its `plaatsgevondenOp`
 * ([reschedule]) and recorded as having taken place by patching that same
 * klantcontact ([completePlanned]). Contactmomenten are never deleted.
 *
 * The PDCA overlay ([com.ritense.pdca.domain.ContactmomentDetails]) is keyed
 * by the klantcontact uuid.
 */
@Service
class KlantcontactService(
    private val openKlantRestClient: RestClient,
    private val openPlanRestClient: RestClient,
    private val objectMapper: ObjectMapper
) {

    companion object {
        private const val API = "/klantinteracties/api/v1"
        const val KANAAL = "gesprek"
        const val INHOUD_MAX_LENGTH = 1000
        const val PLAN_CODE_REGISTER = "open-plan"
        const val PLAN_CODE_OBJECTTYPE = "plan"
        private const val ONDERWERP_MAX_LENGTH = 200
        private const val MAX_PAGES = 20

        private val EVALUATIE_TYPE_LABELS = mapOf(
            "INTAKE" to "Intake",
            "PROGRESS" to "Voortgang",
            "EVALUATION" to "Evaluatie",
            "INSPECTION" to "Inspectie",
            "CRISIS" to "Crisis"
        )
    }

    /** Creates the klantcontact for a contactmoment of a plan; returns its uuid. */
    fun create(contact: NewContactmoment): UUID {
        val verslag = checkedVerslag(contact.verslag)
        val plan = getPlan(contact.planUuid)
        val bsn = bsnOf(plan["domeinregister"] as? String)

        val body = buildMap<String, Any?> {
            put(
                "klantcontact", mapOf(
                    "kanaal" to KANAAL,
                    "onderwerp" to onderwerp(contact.evaluatieType, plan),
                    "inhoud" to verslag,
                    "indicatieContactGelukt" to (if (contact.afgerond) true else null),
                    "taal" to "nld",
                    "vertrouwelijk" to true,
                    "plaatsgevondenOp" to format(contact.plaatsgevondenOp)
                )
            )
            put(
                "onderwerpobject", mapOf(
                    "onderwerpobjectidentificator" to mapOf(
                        "objectId" to contact.planUuid.toString(),
                        "codeObjecttype" to PLAN_CODE_OBJECTTYPE,
                        "codeRegister" to PLAN_CODE_REGISTER,
                        "codeSoortObjectId" to "uuid"
                    )
                )
            )
            bsn?.let { put("betrokkene", betrokkene(partijUuid(it))) }
        }
        val created = post("$API/maak-klantcontact", body)
        val klantcontact = created["klantcontact"] as? Map<*, *>
        return UUID.fromString(klantcontact?.get("uuid") as? String ?: throw ResponseStatusException(
            HttpStatus.BAD_GATEWAY, "Open Klant gaf geen klantcontact terug"
        ))
    }

    /** The klantcontact with this uuid, including the plan it is about; null when Open Klant does not know it. */
    fun get(klantcontactUuid: UUID): Klantcontact? {
        val body = try {
            openKlantRestClient.get()
                .uri { it.path("$API/klantcontacten/$klantcontactUuid")
                    .queryParam("expand", "gingOverOnderwerpobjecten")
                    .build() }
                .retrieve()
                .body(Map::class.java)
        } catch (e: RestClientResponseException) {
            if (e.statusCode.value() == 404) return null
            throw registerError("ophalen", e)
        } catch (e: Exception) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "Contactmoment ophalen uit Open Klant mislukt: ${e.message}")
        } ?: return null
        val onderwerpobjecten = ((body["_expand"] as? Map<*, *>)?.get("gingOverOnderwerpobjecten") as? List<*>)
            ?.filterIsInstance<Map<*, *>>().orEmpty()
        val planUuid = onderwerpobjecten.firstNotNullOfOrNull { planUuidOf(it) }
        return toKlantcontact(body, planUuid)
    }

    /** The plan's planned contactmomenten (not taken place yet), earliest first. */
    fun planned(planUuid: UUID): List<Klantcontact> {
        val result = mutableListOf<Klantcontact>()
        var page = 1
        while (page <= MAX_PAGES) {
            val body = try {
                openKlantRestClient.get()
                    .uri { it.path("$API/klantcontacten")
                        .queryParam("onderwerpobject__onderwerpobjectidentificatorObjectId", planUuid.toString())
                        .queryParam("onderwerpobject__onderwerpobjectidentificatorCodeObjecttype", PLAN_CODE_OBJECTTYPE)
                        .queryParam("onderwerpobject__onderwerpobjectidentificatorCodeRegister", PLAN_CODE_REGISTER)
                        .queryParam("page", page)
                        .build() }
                    .retrieve()
                    .body(Map::class.java)
            } catch (e: RestClientResponseException) {
                throw registerError("ophalen", e)
            } catch (e: Exception) {
                throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "Contactmomenten ophalen uit Open Klant mislukt: ${e.message}")
            } ?: break
            (body["results"] as? List<*>)?.filterIsInstance<Map<*, *>>()?.forEach { result += toKlantcontact(it, planUuid) }
            if (body["next"] == null) break
            page++
        }
        return result.filter { it.gepland }.sortedBy { it.plaatsgevondenOp }
    }

    /** Moves a planned klantcontact to another moment. */
    fun reschedule(klantcontactUuid: UUID, plaatsgevondenOp: OffsetDateTime) {
        patch("$API/klantcontacten/$klantcontactUuid", mapOf("plaatsgevondenOp" to format(plaatsgevondenOp)))
    }

    /**
     * Records a planned klantcontact of a plan as having taken place at
     * [plaatsgevondenOp], with the verslag as inhoud and the onderwerp of
     * the evaluation type.
     */
    fun completePlanned(
        klantcontactUuid: UUID,
        planUuid: UUID,
        evaluatieType: String,
        plaatsgevondenOp: OffsetDateTime,
        verslag: String?
    ) {
        val inhoud = checkedVerslag(verslag)
        patch(
            "$API/klantcontacten/$klantcontactUuid", mapOf(
                "onderwerp" to onderwerp(evaluatieType, getPlan(planUuid)),
                "inhoud" to inhoud,
                "indicatieContactGelukt" to true,
                "plaatsgevondenOp" to format(plaatsgevondenOp)
            )
        )
    }

    /** The evaluation type whose label starts the onderwerp ("<label>: <plan titel>"); null when unrecognised. */
    fun evaluatieTypeOf(onderwerp: String?): String? {
        val label = onderwerp?.substringBefore(':')?.trim() ?: return null
        return EVALUATIE_TYPE_LABELS.entries.firstOrNull { it.value == label }?.key
    }

    private fun checkedVerslag(verslag: String?): String {
        val inhoud = verslag?.trim().orEmpty()
        if (inhoud.length > INHOUD_MAX_LENGTH) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Het verslag is te lang: maximaal $INHOUD_MAX_LENGTH tekens (${inhoud.length} ingevuld)"
            )
        }
        return inhoud
    }

    private fun onderwerp(evaluatieType: String, plan: Map<*, *>): String {
        val titel = plan["titel"] as? String ?: "Plan"
        val typeLabel = EVALUATIE_TYPE_LABELS[evaluatieType] ?: evaluatieType
        return "$typeLabel: $titel".take(ONDERWERP_MAX_LENGTH)
    }

    private fun format(moment: OffsetDateTime): String =
        moment.withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS)
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    private fun planUuidOf(onderwerpobject: Map<*, *>): UUID? {
        val identificator = onderwerpobject["onderwerpobjectidentificator"] as? Map<*, *> ?: return null
        if (identificator["codeRegister"] != PLAN_CODE_REGISTER || identificator["codeObjecttype"] != PLAN_CODE_OBJECTTYPE) {
            return null
        }
        return (identificator["objectId"] as? String)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    }

    private fun toKlantcontact(body: Map<*, *>, planUuid: UUID?) = Klantcontact(
        uuid = UUID.fromString(body["uuid"] as String),
        planUuid = planUuid,
        onderwerp = body["onderwerp"] as? String,
        indicatieContactGelukt = body["indicatieContactGelukt"] as? Boolean,
        plaatsgevondenOp = (body["plaatsgevondenOp"] as? String)?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }
    )

    private fun betrokkene(partijUuid: UUID) = mapOf(
        "wasPartij" to mapOf("uuid" to partijUuid.toString()),
        "rol" to "klant",
        "initiator" to false,
        "organisatienaam" to "",
        "contactnaam" to null,
        "bezoekadres" to null,
        "correspondentieadres" to null
    )

    /** The partij with this BSN; created with only its identificator when Open Klant does not know it yet. */
    private fun partijUuid(bsn: String): UUID {
        val found = try {
            openKlantRestClient.get()
                .uri { it.path("$API/partijen")
                    .queryParam("partijIdentificator__codeSoortObjectId", "bsn")
                    .queryParam("partijIdentificator__objectId", bsn)
                    .build() }
                .retrieve()
                .body(Map::class.java)
        } catch (e: RestClientResponseException) {
            throw registerError("vastleggen", e)
        }
        ((found?.get("results") as? List<*>)?.firstOrNull() as? Map<*, *>)?.get("uuid")?.let {
            return UUID.fromString(it as String)
        }
        val created = post(
            "$API/partijen", mapOf(
                "soortPartij" to "persoon",
                "indicatieActief" to true,
                "indicatieGeheimhouding" to false,
                "voorkeurstaal" to "nld",
                "digitaleAdressen" to emptyList<Any>(),
                "rekeningnummers" to emptyList<Any>(),
                "voorkeursDigitaalAdres" to null,
                "voorkeursRekeningnummer" to null,
                "partijIdentificatie" to mapOf("contactnaam" to null),
                "partijIdentificatoren" to listOf(
                    mapOf(
                        "partijIdentificator" to mapOf(
                            "codeObjecttype" to "natuurlijk_persoon",
                            "codeSoortObjectId" to "bsn",
                            "objectId" to bsn,
                            "codeRegister" to "brp"
                        )
                    )
                )
            )
        )
        return UUID.fromString(created["uuid"] as String)
    }

    /** The BSN from plan.domeinregister (urn:pdca:brp:persoon:<bsn>); null for an object plan. */
    private fun bsnOf(domeinregister: String?): String? {
        val parts = domeinregister.orEmpty().split(':')
        return if (parts.getOrNull(3) == "persoon") parts.getOrNull(4)?.takeIf { it.isNotBlank() } else null
    }

    private fun getPlan(planUuid: UUID): Map<*, *> = try {
        openPlanRestClient.get().uri("/plannen/api/v0/plan/$planUuid").retrieve().body(Map::class.java)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Plan $planUuid niet gevonden")
    } catch (e: ResponseStatusException) {
        throw e
    } catch (e: RestClientResponseException) {
        throw ResponseStatusException(
            if (e.statusCode.value() == 404) HttpStatus.NOT_FOUND else HttpStatus.BAD_GATEWAY,
            "Plan $planUuid ophalen uit Open Plan mislukt (HTTP ${e.statusCode.value()})"
        )
    }

    private fun post(path: String, body: Map<String, Any?>): Map<*, *> = try {
        openKlantRestClient.post()
            .uri(path)
            .contentType(MediaType.APPLICATION_JSON)
            .body(objectMapper.writeValueAsBytes(body))
            .retrieve()
            .body(Map::class.java)
            ?: throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "Open Klant gaf geen antwoord op POST $path")
    } catch (e: ResponseStatusException) {
        throw e
    } catch (e: RestClientResponseException) {
        throw registerError("vastleggen", e)
    } catch (e: Exception) {
        throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "Contactmoment vastleggen in Open Klant mislukt: ${e.message}")
    }

    private fun patch(path: String, body: Map<String, Any?>) {
        try {
            openKlantRestClient.patch()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsBytes(body))
                .retrieve()
                .toBodilessEntity()
        } catch (e: RestClientResponseException) {
            if (e.statusCode.value() == 404) {
                throw ResponseStatusException(HttpStatus.NOT_FOUND, "Contactmoment niet gevonden in Open Klant")
            }
            throw registerError("bijwerken", e)
        } catch (e: Exception) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "Contactmoment bijwerken in Open Klant mislukt: ${e.message}")
        }
    }

    /** Open Klant's validation errors (`invalidParams`) in the message, so they reach the UI. */
    private fun registerError(actie: String, e: RestClientResponseException): ResponseStatusException {
        val detail = try {
            val body = objectMapper.readValue(e.responseBodyAsString, Map::class.java)
            (body["invalidParams"] as? List<*>)?.filterIsInstance<Map<*, *>>()
                ?.joinToString("; ") { "${it["name"]}: ${it["reason"]}" }
                ?.takeIf { it.isNotBlank() }
                ?: body["detail"] as? String
        } catch (_: Exception) {
            null
        }
        return ResponseStatusException(
            HttpStatus.BAD_GATEWAY,
            "Contactmoment $actie in Open Klant mislukt (HTTP ${e.statusCode.value()})" + (detail?.let { ": $it" } ?: "")
        )
    }
}
