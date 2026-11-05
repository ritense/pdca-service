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
import com.ritense.pdca.registers.openklant.MaakKlantcontact
import com.ritense.pdca.registers.openklant.NewBetrokkene
import com.ritense.pdca.registers.openklant.NewKlantcontact
import com.ritense.pdca.registers.openklant.NewOnderwerpobject
import com.ritense.pdca.registers.openklant.NewPersoonPartij
import com.ritense.pdca.registers.openklant.OnderwerpobjectIdentificator
import com.ritense.pdca.registers.openklant.OpenKlantGateway
import com.ritense.pdca.registers.openklant.KlantcontactUpdate
import com.ritense.pdca.registers.openklant.UuidRef
import com.ritense.pdca.registers.openplan.OpenPlanGateway
import com.ritense.pdca.registers.openplan.Plan
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID
import com.ritense.pdca.registers.openklant.Klantcontact as OpenKlantKlantcontact

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
    private val openKlantGateway: OpenKlantGateway,
    private val openPlanGateway: OpenPlanGateway
) {

    companion object {
        const val KANAAL = "gesprek"
        const val INHOUD_MAX_LENGTH = 1000
        const val PLAN_CODE_REGISTER = "open-plan"
        const val PLAN_CODE_OBJECTTYPE = "plan"
        private const val ONDERWERP_MAX_LENGTH = 200

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
        val plan = openPlanGateway.getPlan(contact.planUuid)
        val bsn = PdcaUrn.parse(plan.domeinregister)?.takeIf { it.kind == PdcaUrn.Kind.PERSOON }?.id

        return openKlantGateway.maakKlantcontact(
            MaakKlantcontact(
                klantcontact = NewKlantcontact(
                    kanaal = KANAAL,
                    onderwerp = onderwerp(contact.evaluatieType, plan),
                    inhoud = verslag,
                    indicatieContactGelukt = if (contact.afgerond) true else null,
                    taal = "nld",
                    vertrouwelijk = true,
                    plaatsgevondenOp = format(contact.plaatsgevondenOp)
                ),
                onderwerpobject = NewOnderwerpobject(planIdentificator(contact.planUuid)),
                betrokkene = bsn?.let { NewBetrokkene(wasPartij = UuidRef(partijUuid(it)), rol = "klant") }
            )
        ).uuid
    }

    /** The klantcontact with this uuid, including the plan it is about; null when Open Klant does not know it. */
    fun get(klantcontactUuid: UUID): Klantcontact? {
        val klantcontact = openKlantGateway.findKlantcontact(klantcontactUuid) ?: return null
        val planUuid = klantcontact.expand?.gingOverOnderwerpobjecten.orEmpty()
            .firstNotNullOfOrNull { it.onderwerpobjectidentificator?.let(::planUuidOf) }
        return toKlantcontact(klantcontact, planUuid)
    }

    /** All contactmomenten of the plan, earliest first. */
    fun ofPlan(planUuid: UUID): List<Klantcontact> =
        openKlantGateway.listKlantcontactenOver(planIdentificator(planUuid))
            .map { toKlantcontact(it, planUuid) }
            .sortedBy { it.plaatsgevondenOp }

    /** The plan's planned contactmomenten (not taken place yet), earliest first. */
    fun planned(planUuid: UUID): List<Klantcontact> = ofPlan(planUuid).filter { it.gepland }

    /** Moves a planned klantcontact to another moment. */
    fun reschedule(klantcontactUuid: UUID, plaatsgevondenOp: OffsetDateTime) {
        openKlantGateway.updateKlantcontact(klantcontactUuid, KlantcontactUpdate(plaatsgevondenOp = format(plaatsgevondenOp)))
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
        openKlantGateway.updateKlantcontact(
            klantcontactUuid,
            KlantcontactUpdate(
                onderwerp = onderwerp(evaluatieType, openPlanGateway.getPlan(planUuid)),
                inhoud = inhoud,
                indicatieContactGelukt = true,
                plaatsgevondenOp = format(plaatsgevondenOp)
            )
        )
    }

    /**
     * The evaluation type an onderwerp ("<label>: <plan titel>") was written
     * with: the type of a built-in label, otherwise the label itself (a
     * configured type without a built-in label is written under its own
     * key, see [onderwerp]); null when the onderwerp carries no label.
     */
    fun evaluatieTypeOf(onderwerp: String?): String? {
        if (onderwerp == null || ':' !in onderwerp) return null
        val label = onderwerp.substringBefore(':').trim().takeIf { it.isNotEmpty() } ?: return null
        return EVALUATIE_TYPE_LABELS.entries.firstOrNull { it.value == label }?.key ?: label
    }

    /** Checks a verslag against Open Klant's limit on a klantcontact's inhoud; returns it trimmed. */
    fun checkedVerslag(verslag: String?): String {
        val inhoud = verslag?.trim().orEmpty()
        if (inhoud.length > INHOUD_MAX_LENGTH) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Het verslag is te lang: maximaal $INHOUD_MAX_LENGTH tekens (${inhoud.length} ingevuld)"
            )
        }
        return inhoud
    }

    private fun planIdentificator(planUuid: UUID) = OnderwerpobjectIdentificator(
        objectId = planUuid.toString(),
        codeObjecttype = PLAN_CODE_OBJECTTYPE,
        codeRegister = PLAN_CODE_REGISTER,
        codeSoortObjectId = "uuid"
    )

    private fun onderwerp(evaluatieType: String, plan: Plan): String {
        val titel = plan.titel ?: "Plan"
        val typeLabel = EVALUATIE_TYPE_LABELS[evaluatieType] ?: evaluatieType
        return "$typeLabel: $titel".take(ONDERWERP_MAX_LENGTH)
    }

    private fun format(moment: OffsetDateTime): String =
        moment.withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS)
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    private fun planUuidOf(identificator: OnderwerpobjectIdentificator): UUID? {
        if (identificator.codeRegister != PLAN_CODE_REGISTER || identificator.codeObjecttype != PLAN_CODE_OBJECTTYPE) {
            return null
        }
        return identificator.objectId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    }

    private fun toKlantcontact(klantcontact: OpenKlantKlantcontact, planUuid: UUID?) = Klantcontact(
        uuid = klantcontact.uuid,
        planUuid = planUuid,
        onderwerp = klantcontact.onderwerp,
        indicatieContactGelukt = klantcontact.indicatieContactGelukt,
        plaatsgevondenOp = klantcontact.plaatsgevondenOp?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }
    )

    /** The partij with this BSN; created with only its identificator when Open Klant does not know it yet. */
    private fun partijUuid(bsn: String): UUID =
        openKlantGateway.findPartijByBsn(bsn)?.uuid ?: openKlantGateway.createPartij(NewPersoonPartij.withBsn(bsn)).uuid
}
