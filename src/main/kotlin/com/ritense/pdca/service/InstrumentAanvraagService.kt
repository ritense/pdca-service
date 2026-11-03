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

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.ritense.pdca.domain.PdcaUrn
import com.ritense.pdca.registers.objecten.NewObject
import com.ritense.pdca.registers.objecten.NewObjectRecord
import com.ritense.pdca.registers.objecten.ObjectenGateway
import com.ritense.pdca.registers.openplan.Doel
import com.ritense.pdca.registers.openplan.Instrument
import com.ritense.pdca.registers.openplan.OpenPlanGateway
import com.ritense.pdca.registers.openplan.Plan
import com.ritense.pdca.registers.openplan.STATUS_ACTIEF
import com.ritense.pdca.registers.openproduct.OpenProductGateway
import com.ritense.pdca.registers.openproduct.ProductType
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock

/**
 * Product requests the way the registers describe them (ARCHITECTURE §6.2).
 *
 * Which products can be requested, and how, lives in Open Product: a
 * producttype that carries an actie named [START_ACTIE] can be requested
 * from the plan page, and [PlanIntakeService] requests the instruments an
 * intake set up once their plan exists. This is the only way an instrument
 * gets into a plan. Requesting one files a **verzoek**: an object in the
 * Objecten API of the objecttype named in that actie, with the plan, doel,
 * subject and producttype as its data. GZAC's Verzoek plugin picks the
 * object up (through Open Notificaties) and starts the instrument dossier
 * `instrument-verzoek`, which manages the instrument in Open Plan through the
 * plugin actions aanmaak-instrument and update-instrument
 * ([InstrumentActionService]). The app creates nothing in the plan and
 * keeps no record of the request: the verzoek is in the Objecten API, the
 * dossier in GZAC, and the instrument's `zaak` field in Open Plan points at
 * the dossier.
 *
 * A verzoek is **open** as long as its instrument has not appeared: per doel
 * and product, the verzoeken outnumber the instruments with a dossier as
 * `zaak` ([openVerzoeken], oldest verzoeken matched first). The plan page
 * shows open verzoeken as "Aangevraagd", and [start] refuses a second
 * request for a doel and product while one is open (409), so a slow or
 * broken notification route never leads to duplicates. The check and the
 * filing run under a lock per doel and product, so two concurrent requests
 * cannot both pass the check; the lock is held in memory, so with several
 * replicas two requests for the same doel and product that reach different
 * replicas at the same moment can still both be filed.
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
    private val openPlanGateway: OpenPlanGateway,
    private val openProductGateway: OpenProductGateway,
    private val objectenGateway: ObjectenGateway,
    private val objectMapper: ObjectMapper
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** The lock per doel and product that [start] files under. */
    private val requestLocks = KeyedLocks()

    companion object {
        /** Name of the Open Product actie that makes a producttype requestable. */
        const val START_ACTIE = "StartInstrument"

        /** The `type` of every verzoek this app files; the Verzoek plugin routes it to the instrument dossier. */
        const val VERZOEK_TYPE = "instrument"

        /** Producttype parameter with the start status of the instrument (actief or gepland). */
        const val START_STATUS_PARAMETER = "startStatus"

        /** Producttype parameter with the Open Plan instrumenttype (name or uuid) of the instrument. */
        const val INSTRUMENTTYPE_PARAMETER = "instrumenttype"

        private const val SOORT_VERZOEK = "verzoek"
        private val CONTROL_STATICS = setOf("soort", "type", "typeVersion")
    }

    /** Outcome of a request: the created verzoek object and the requested product. */
    data class Verzoek(val objectUrl: String?, val productTitel: String)

    /**
     * A verzoek whose instrument has not appeared in the plan yet. [aangevraagdOp]
     * is when it was filed (ISO date-time; for an older verzoek only its
     * registration date), [minutenOpen] how long ago that is.
     */
    data class OpenVerzoek(
        val objectUrl: String?,
        val planUuid: UUID?,
        val doelUuid: UUID,
        val productUrn: String?,
        val productTitel: String?,
        val aangevraagdOp: String?,
        val minutenOpen: Long?
    )

    /** A verzoek as read back from the Objecten API. */
    data class VerzoekRecord(
        val objectUrl: String?,
        val planUuid: UUID?,
        val doelUuid: UUID,
        val productUrn: String?,
        val productTitel: String?,
        val aangevraagdOp: OffsetDateTime?
    )

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
        val doel = openPlanGateway.getDoel(doelUuid)
        val planUuid = doel.plannen.firstOrNull()?.uuid
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Doel $doelUuid hangt niet onder een plan")
        val plan = openPlanGateway.getPlan(planUuid)
        if (doel.status != STATUS_ACTIEF || plan.status != STATUS_ACTIEF) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                if (plan.status != STATUS_ACTIEF) "Het plan is ${plan.status}; er kan geen instrument meer worden aangevraagd"
                else "Het doel is ${doel.status}; er kan geen instrument meer onder worden aangevraagd"
            )
        }
        val producttype = openProductGateway.getProducttype(producttypeUuid)
        val productUrn = PdcaUrn.producttype(producttype.code ?: producttypeUuid.toString()).toString()
        return requestLocks.withLock("$doelUuid $productUrn") {
            fileVerzoek(doel, plan, producttypeUuid, producttype, productUrn)
        }
    }

    /** Files the verzoek for [doel] and [producttype], unless one is open already (409); runs under the lock of [start]. */
    private fun fileVerzoek(doel: Doel, plan: Plan, producttypeUuid: UUID, producttype: ProductType, productUrn: String): Verzoek {
        val doelUuid = doel.uuid
        val planUuid = plan.uuid
        val code = producttype.code ?: producttypeUuid.toString()
        val titel = producttype.naam ?: code
        openVerzoekVoor(doelUuid, productUrn)?.let { open ->
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "'$titel' is voor dit doel al aangevraagd${open.aangevraagdOp?.let { " (${weergave(it)})" } ?: ""}; " +
                    "het instrument verschijnt zodra GZAC het instrumentdossier heeft aangemaakt"
            )
        }

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

        val (subjectId, bsn) = subjectOf(plan, doel)
        val data = linkedMapOf<String, Any?>(
            "planUuid" to planUuid.toString(),
            "doelUuid" to doelUuid.toString(),
            "subjectId" to subjectId,
            "productTypeUuid" to producttypeUuid.toString(),
            "productTypeCode" to code,
            "productTitel" to titel,
            "productUrn" to productUrn,
            "startStatus" to producttype.parameter(START_STATUS_PARAMETER),
            "instrumenttype" to producttype.parameter(INSTRUMENTTYPE_PARAMETER),
            "aangevraagdOp" to OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS).toString()
        )
        data += actie.statics
        data += resolveMappings(
            actie,
            mapOf(
                "plan" to objectMapper.valueToTree(plan),
                "doel" to objectMapper.valueToTree(doel),
                "producttype" to objectMapper.valueToTree(producttype)
            )
        )

        val verzoek = linkedMapOf<String, Any?>("type" to VERZOEK_TYPE)
        bsn?.let { verzoek["bsn"] = it }
        verzoek["data"] = data.filterValues { it != null }
        val created = objectenGateway.createObject(
            NewObject(
                type = objecttype,
                record = NewObjectRecord(typeVersion = actie.typeVersion, startAt = LocalDate.now().toString(), data = verzoek)
            )
        )
        log.info("Verzoek for product '$code' filed for doel $doelUuid of plan $planUuid: ${created.url ?: "(no url)"}")
        return Verzoek(created.url, titel)
    }

    /**
     * [start] for a producttype known by its code, the way intake documents
     * name products. Fails with 422 when Open Product has no producttype
     * with that code.
     */
    fun startByCode(doelUuid: UUID, code: String): Verzoek {
        val producttype = openProductGateway.findProducttypeByCode(code) ?: throw ResponseStatusException(
            HttpStatus.UNPROCESSABLE_ENTITY, "Open Product kent geen producttype met code '$code'"
        )
        return start(doelUuid, producttype.uuid)
    }

    // ------------------------------------------------------- open verzoeken

    /** The open verzoeken of a plan, for the doelen it (still) has; oldest first. */
    fun openVerzoeken(planUuid: UUID): List<OpenVerzoek> {
        val doelen = openPlanGateway.listDoelen(planUuid).map { it.uuid }.toSet()
        val verzoeken = verzoeken(mapOf("data__planUuid" to planUuid.toString())).filter { it.doelUuid in doelen }
        if (verzoeken.isEmpty()) return emptyList()
        val instrumenten = openPlanGateway.listInstrumentenVanDoelen(verzoeken.map { it.doelUuid }.distinct())
        return open(verzoeken, instrumenten)
    }

    /** All verzoeken ever filed for a doel (open or not), as read back from the Objecten API. */
    fun verzoekenVoor(doelUuid: UUID): List<VerzoekRecord> = verzoeken(mapOf("data__doelUuid" to doelUuid.toString()))

    /** The oldest open verzoek for this doel and product, or null. */
    fun openVerzoekVoor(doelUuid: UUID, productUrn: String): OpenVerzoek? {
        val verzoeken = verzoekenVoor(doelUuid).filter { it.productUrn == productUrn }
        if (verzoeken.isEmpty()) return null
        return open(verzoeken, openPlanGateway.listInstrumenten(doelUuid)).firstOrNull()
    }

    /**
     * Count-based matching: per doel and product the oldest verzoeken are
     * fulfilled by the instruments that carry a dossier as `zaak`; the rest
     * is open. (A verzoek does not know its dossier and an instrument not its
     * verzoek, so counting is the only match there is.)
     */
    fun open(verzoeken: List<VerzoekRecord>, instrumenten: List<Instrument>, nu: OffsetDateTime = OffsetDateTime.now()): List<OpenVerzoek> =
        verzoeken.groupBy { it.doelUuid to it.productUrn }
            .flatMap { (sleutel, groep) ->
                val (doelUuid, productUrn) = sleutel
                val vervuld = instrumenten.count { inst ->
                    inst.isDossierManaged && inst.product == productUrn && inst.doelen.any { it.uuid == doelUuid }
                }
                groep.sortedBy { it.aangevraagdOp ?: OffsetDateTime.MIN }.drop(vervuld)
            }
            .sortedBy { it.aangevraagdOp ?: OffsetDateTime.MIN }
            .map { v ->
                OpenVerzoek(
                    objectUrl = v.objectUrl,
                    planUuid = v.planUuid,
                    doelUuid = v.doelUuid,
                    productUrn = v.productUrn,
                    productTitel = v.productTitel,
                    aangevraagdOp = v.aangevraagdOp?.toString(),
                    minutenOpen = v.aangevraagdOp?.let { Duration.between(it, nu).toMinutes().coerceAtLeast(0) }
                )
            }

    private fun verzoeken(dataAttrs: Map<String, String>): List<VerzoekRecord> =
        objectenGateway.listObjects(mapOf("type" to VERZOEK_TYPE) + dataAttrs).mapNotNull { obj ->
            val data = obj.record?.data?.path("data") ?: return@mapNotNull null
            val doelUuid = data.path("doelUuid").asText("").let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return@mapNotNull null
            VerzoekRecord(
                objectUrl = obj.url,
                planUuid = data.path("planUuid").asText("").let { runCatching { UUID.fromString(it) }.getOrNull() },
                doelUuid = doelUuid,
                productUrn = data.path("productUrn").takeIf { it.isTextual }?.asText(),
                productTitel = data.path("productTitel").takeIf { it.isTextual }?.asText(),
                aangevraagdOp = data.path("aangevraagdOp").takeIf { it.isTextual }?.asText()
                    ?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }
                    ?: (obj.record.registrationAt ?: obj.record.startAt)
                        ?.let { runCatching { LocalDate.parse(it).atStartOfDay().atOffset(ZoneOffset.UTC) }.getOrNull() }
            )
        }

    private fun weergave(aangevraagdOp: String): String = runCatching {
        val moment = OffsetDateTime.parse(aangevraagdOp).atZoneSameInstant(ZoneId.systemDefault())
        "op ${moment.format(DateTimeFormatter.ofPattern("d-M-yyyy 'om' HH:mm"))}"
    }.getOrDefault("eerder")

    // ---------------------------------------------------------- the recipe

    /** The StartInstrument actie of a producttype, read from Open Product (the acties list carries the mapping). */
    private fun startActie(producttypeUuid: UUID, titel: String): StartActie {
        val actie = openProductGateway.listActies(producttypeUuid)
            .firstOrNull { it.naam?.equals(START_ACTIE, ignoreCase = true) == true }
            ?: throw ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "Producttype '$titel' heeft geen actie '$START_ACTIE' in Open Product en kan niet worden aangevraagd"
            )
        return parseStartActie(actie.mapping)
    }

    /** Reads the start recipe out of an actie's mapping (Open Product's DMN-mapping shape, see the class doc). */
    fun parseStartActie(mapping: JsonNode?): StartActie {
        val root = mapping?.takeIf { it.isObject }
        val statics = root?.path("static")?.takeIf { it.isArray }.orEmpty()
            .mapNotNull { item ->
                val name = item.path("name").takeIf { it.isTextual }?.asText() ?: return@mapNotNull null
                name to item.get("value")?.let { objectMapper.treeToValue(it, Any::class.java) }
            }
            .toMap()
        val mappings = root?.properties().orEmpty()
            .filter { (source, items) -> source != "static" && items.isArray }
            .associate { (source, items) ->
                source.lowercase() to items.mapNotNull { item ->
                    val name = item.path("name").takeIf { it.isTextual }?.asText() ?: return@mapNotNull null
                    val path = item.path("regex").takeIf { it.isTextual }?.asText() ?: return@mapNotNull null
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
    fun resolveMappings(actie: StartActie, sources: Map<String, JsonNode>): Map<String, Any?> = buildMap {
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
    private fun valueAt(source: JsonNode, path: String): Any? {
        val node = path.removePrefix("$").trim('.').split('.').filter { it.isNotBlank() }
            .fold(source) { current, key -> current.path(key) }
        return if (node.isMissingNode || node.isNull) null else objectMapper.treeToValue(node, Any::class.java)
    }

    /**
     * The subject of the plan from `plan.domeinregister` ([PdcaUrn] persoon
     * or object): the id, plus the bsn when the subject is a persoon — that
     * bsn is the verzoek's initiator. A plan without domeinregister falls
     * back to the doel's persoon.
     */
    private fun subjectOf(plan: Plan, doel: Doel): Pair<String?, String?> {
        val subject = PdcaUrn.parse(plan.domeinregister)
        val doelBsn = doel.persoon?.bsn
        return when (subject?.kind) {
            PdcaUrn.Kind.PERSOON -> subject.id to subject.id
            null -> doelBsn to doelBsn
            else -> subject.id to null
        }
    }

    private fun JsonNode?.orEmpty(): List<JsonNode> = this?.toList() ?: emptyList()
}

/**
 * In-memory locks per key, held only while someone uses them: [withLock]
 * runs a block exclusively per key, and a key's lock is dropped once no
 * caller holds or waits for it.
 */
internal class KeyedLocks {
    private class Entry(val lock: ReentrantLock = ReentrantLock(), var users: Int = 0)

    private val entries = HashMap<String, Entry>()

    fun <T> withLock(key: String, block: () -> T): T {
        val entry = synchronized(entries) { entries.getOrPut(key) { Entry() }.also { it.users++ } }
        entry.lock.lock()
        try {
            return block()
        } finally {
            entry.lock.unlock()
            synchronized(entries) { if (--entry.users == 0) entries.remove(key) }
        }
    }
}
