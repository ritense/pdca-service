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

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.ritense.pdca.domain.EvaluationChange
import com.ritense.pdca.domain.EvaluationSession
import com.ritense.pdca.domain.PdcaUrn
import com.ritense.pdca.registers.StubRegisterService
import com.ritense.pdca.registers.openplan.OpenPlanGateway
import com.ritense.pdca.repository.ContactmomentDetailsRepository
import com.ritense.pdca.repository.InvolvedPartyRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * The data snapshot (`pdcaGegevens`) a document is made from: the data model
 * of the document template, so every key is always present and every text
 * is a string (empty when unknown), never null. A lookup in a register that
 * fails leaves its field empty rather than failing the document.
 *
 * The evaluatieverslag is shared with the inwoner. It therefore never
 * carries internal data: changes of soort `INTERNE_NOTITIE` and
 * `INTERNE_TOELICHTING` are left out.
 */
@Service
class DocumentGegevensService(
    private val openPlanGateway: OpenPlanGateway,
    private val stubRegisterService: StubRegisterService,
    private val involvedPartyRepository: InvolvedPartyRepository,
    private val contactmomentDetailsRepository: ContactmomentDetailsRepository,
    private val gzacClient: GzacClient,
    private val objectMapper: ObjectMapper
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * The evaluatieverslag of a completed evaluation [session] with its
     * [changes]: plan, the evaluation itself, the plan changes made during
     * it per onderdeel (in the order the onderdelen were first changed;
     * internal notes excluded) and the actiepunten of its contactmoment.
     */
    fun evaluatieverslag(session: EvaluationSession, changes: List<EvaluationChange>): Map<String, Any?> = linkedMapOf(
        "schemaVersie" to SCHEMA_VERSIE,
        "titel" to "Evaluatieverslag",
        "gemaaktOp" to datum(LocalDate.now()),
        "plan" to plan(session.planUuid, session.dossierId),
        "evaluatie" to linkedMapOf(
            "datum" to datum(session.startedAt.toLocalDate()),
            "uitgevoerdDoor" to session.userLogin,
            "deelnemers" to session.deelnemers.orEmpty(),
            "verslag" to session.verslag.orEmpty()
        ),
        "wijzigingen" to wijzigingen(changes),
        "actiepunten" to actiepunten(session.contactmomentUuid).map { mapOf("tekst" to it) }
    )

    /** File name of the evaluatieverslag: `evaluatieverslag-<date of the evaluation>.pdf`. */
    fun bestandsnaam(session: EvaluationSession): String =
        "evaluatieverslag-${session.startedAt.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)}.pdf"

    private fun plan(planUuid: UUID, dossierId: String): Map<String, String> {
        val plan = bestEffort("plan $planUuid") { openPlanGateway.findPlan(planUuid) }
        return linkedMapOf(
            "titel" to plan?.titel.orEmpty(),
            "inwoner" to (plan?.domeinregister?.let { subject(it) } ?: dossierNaam(dossierId)).orEmpty(),
            "procesbegeleider" to bestEffort("the procesbegeleider of plan $planUuid") {
                involvedPartyRepository.findByPlanUuid(planUuid).firstOrNull { it.isPrimary }?.name
            }.orEmpty()
        )
    }

    /** Name of the plan subject (persoon or object) behind its domeinregister URN. */
    private fun subject(domeinregister: String): String? {
        val urn = PdcaUrn.parse(domeinregister) ?: return null
        return bestEffort("plan subject $domeinregister") {
            when (urn.kind) {
                PdcaUrn.Kind.PERSOON -> stubRegisterService.persoon(urn.id)?.naam
                PdcaUrn.Kind.OBJECT -> stubRegisterService.registerObject(urn.id)?.naam
                else -> null
            }
        }
    }

    /**
     * The subject's name as the plan dossier holds it (`naam`, set from the
     * intake when the plan was created), for a subject the registers have no
     * display record of.
     */
    private fun dossierNaam(dossierId: String): String? = bestEffort("the name in plan dossier $dossierId") {
        (gzacClient.getDocument(dossierId)["content"] as? Map<*, *>)?.get("naam") as? String
    }?.takeIf { it.isNotBlank() }

    private fun wijzigingen(changes: List<EvaluationChange>): List<Map<String, String>> =
        changes.filter { it.soort !in INTERN }
            .groupBy { it.subjectType to it.subjectUuid }
            .values
            .flatMap { groep ->
                val label = SUBJECT_TYPE_LABELS[groep.first().subjectType.name] ?: groep.first().subjectType.name
                val titel = groep.lastOrNull { !it.subjectTitel.isNullOrBlank() }?.subjectTitel
                val onderdeel = if (titel != null) "$label: $titel" else label
                groep.map {
                    linkedMapOf(
                        "onderdeel" to onderdeel,
                        "omschrijving" to omschrijving(it.soort, it.vanWaarde, it.naarWaarde),
                        "toelichting" to it.toelichting.orEmpty()
                    )
                }
            }

    /** The actiepunten (a JSON array of texts) of the contactmoment the evaluation became. */
    private fun actiepunten(contactmomentUuid: UUID?): List<String> {
        val json = contactmomentUuid
            ?.let { contactmomentDetailsRepository.findById(it).orElse(null)?.actiepunten }
            ?.takeIf { it.isNotBlank() }
            ?: return emptyList()
        return try {
            objectMapper.readValue(json, object : TypeReference<List<String?>>() {}).filterNotNull().filter { it.isNotBlank() }
        } catch (e: Exception) {
            log.warn("Unreadable actiepunten on contactmoment {}; the document shows none", contactmomentUuid, e)
            emptyList()
        }
    }

    private fun <T> bestEffort(what: String, lookup: () -> T?): T? = try {
        lookup()
    } catch (e: Exception) {
        log.warn("Could not look up {} for a document; the field stays empty", what, e)
        null
    }

    companion object {
        const val SCHEMA_VERSIE = 1
        /** Changes for colleagues only: the internal note or toelichting never goes into a document. */
        val INTERN = setOf("INTERNE_NOTITIE", "INTERNE_TOELICHTING")

        private val DATUM = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.forLanguageTag("nl-NL"))

        /** "11 november 2026". */
        fun datum(date: LocalDate): String = date.format(DATUM)

        // The labels below mirror frontend/src/shared/labels.ts, so the
        // document describes a change as the side panel and the Evaluaties
        // tab do.

        private val SUBJECT_TYPE_LABELS = mapOf(
            "PLAN" to "Plan", "HOOFDDOEL" to "Hoofddoel", "SUBDOEL" to "Subdoel", "INSTRUMENT" to "Instrument", "ACTIE" to "Actie"
        )

        private val WIJZIGING_LABELS = mapOf(
            "SUBDOEL_TOEGEVOEGD" to "Subdoel toegevoegd",
            "SUBDOEL_BEWERKT" to "Subdoel bewerkt",
            "SUBDOEL_GESTART" to "Subdoel gestart",
            "SUBDOEL_AFGEROND" to "Subdoel afgerond",
            "SUBDOEL_AFGEBROKEN" to "Subdoel afgebroken",
            "SUBDOEL_VERWIJDERD" to "Subdoel verwijderd",
            "STARTDATUM" to "Startdatum",
            "BEOOGDE_EINDDATUM" to "Beoogde einddatum",
            "VOORTGANG" to "Voortgang",
            "INTERNE_NOTITIE" to "Interne notitie",
            "EXTERNE_NOTITIE" to "Externe notitie",
            "INTERNE_TOELICHTING" to "Interne toelichting",
            "EXTERNE_TOELICHTING" to "Externe toelichting",
            "INSTRUMENT_TOEGEVOEGD" to "Instrument toegevoegd",
            "INSTRUMENT_GESTART" to "Instrument gestart",
            "INSTRUMENT_AFGEROND" to "Instrument afgerond",
            "INSTRUMENT_AFGEBROKEN" to "Instrument afgebroken",
            "INSTRUMENT_REGISTRATIE" to "Uren/effectiviteit",
            "ACTIE_TOEGEVOEGD" to "Actie toegevoegd",
            "ACTIE_STATUS" to "Actiestatus",
            "PRODUCT_AANGEVRAAGD" to "Instrument aangevraagd",
            "HOOFDDOEL_GEWISSELD" to "Hoofddoel gewisseld",
            "HOOFDDOEL_TOELICHTING" to "Toelichting bij hoofddoel",
            "PLAN_STATUS" to "Status plan",
            "WEERGAVESTATUS" to "Planstatus",
            "POSITIE" to "Positie",
            "SUBDOELGROEP" to "Subdoelgroep",
            "PROCESBEGELEIDER" to "Procesbegeleider",
            "BETROKKENE_TOEGEVOEGD" to "Verantwoordelijke toegevoegd",
            "BETROKKENE_VERWIJDERD" to "Verantwoordelijke verwijderd"
        )

        /** Text changes: only the new text is shown, not old → new. */
        private val TEKST_WIJZIGINGEN = setOf(
            "INTERNE_NOTITIE", "EXTERNE_NOTITIE", "INTERNE_TOELICHTING", "EXTERNE_TOELICHTING", "HOOFDDOEL_TOELICHTING"
        )

        /** "Voortgang: Op koers → Let op", "Externe notitie: ...", "Subdoel toegevoegd: Werk". */
        fun omschrijving(soort: String, vanWaarde: String?, naarWaarde: String?): String {
            val label = WIJZIGING_LABELS[soort] ?: soort
            val van = vanWaarde?.takeIf { it.isNotEmpty() }
            val naar = naarWaarde?.takeIf { it.isNotEmpty() }
            return when {
                soort in TEKST_WIJZIGINGEN -> if (naar != null) "$label: $naar" else "$label verwijderd"
                van != null && naar != null && van != naar -> "$label: $van → $naar"
                naar != null -> "$label: $naar"
                van != null -> "$label: $van → leeg"
                else -> label
            }
        }
    }
}
