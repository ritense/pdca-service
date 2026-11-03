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

import com.ritense.pdca.domain.InstrumentDetails
import com.ritense.pdca.domain.PdcaUrn
import com.ritense.pdca.domain.UitvoeringsStatus
import com.ritense.pdca.registers.RegisterException
import com.ritense.pdca.registers.RegisterFailure
import com.ritense.pdca.registers.openplan.Doel
import com.ritense.pdca.registers.openplan.DoelRef
import com.ritense.pdca.registers.openplan.Instrument
import com.ritense.pdca.registers.openplan.InstrumentEinde
import com.ritense.pdca.registers.openplan.NewInstrument
import com.ritense.pdca.registers.openplan.OpenPlanGateway
import com.ritense.pdca.registers.openplan.STATUS_ACTIEF
import com.ritense.pdca.registers.openplan.STATUS_AFGEROND
import com.ritense.pdca.registers.openplan.STATUS_GEANNULEERD
import com.ritense.pdca.repository.InstrumentDetailsRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * The instrumenten side of the plugin actions: this is how the instrument
 * dossier (`instrument-verzoek`, ARCHITECTURE §6.2) manages the instrument in
 * Open Plan. The app creates nothing when a product is requested — the
 * dossier decides when the instrument comes into existence and how it ends.
 *
 *  - aanmaak-instrument: creates the instrument under a doel, records the
 *    calling dossier in the instrument's `zaak` field and returns the
 *    instrumentUuid as declared output (the result channel of the action
 *    response), so the dossier can use it later on. Retry-safe: an
 *    instrument under the doel whose `zaak` is this dossier is returned
 *    instead of creating a second one.
 *  - update-instrument: ends the instrument as afgerond (+ resultaat) or
 *    geannuleerd in the register and stores an optional toelichting in the
 *    PDCA overlay. Retry-safe: an instrument already in the requested end
 *    state is a success without a second write.
 *
 * Both apply the subdoel rule of [InstrumentLifecycleService.startSubdoelen].
 * A refusal is a [PluginActionException] (422); a register or GZAC that is
 * temporarily unavailable is a retryable one (503), so the job executor's
 * retry, or an incident retry, can complete the step. The register is
 * written first and the overlay row afterwards, outside any transaction
 * around the HTTP call.
 */
@Service
class InstrumentActionService(
    private val instrumentDetailsRepository: InstrumentDetailsRepository,
    private val openPlanGateway: OpenPlanGateway,
    private val gzacClient: GzacClient,
    private val instrumentLifecycleService: InstrumentLifecycleService,
    private val transactionTemplate: TransactionTemplate
) {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /** Start status that creates the instrument as gepland (overlay refinement) instead of gestart. */
        const val STATUS_GEPLAND = "gepland"
    }

    /**
     * Plugin action aanmaak-instrument. Properties: doelUuid (required),
     * titel (required), product (URN, optional), status (optional: `actief`,
     * the default, or `gepland` — actief in the register with the overlay
     * refinement GEPLAND), instrumenttype (optional: name or uuid of an Open
     * Plan instrumenttype, from the producttype parameter `instrumenttype`;
     * without a match the first instrumenttype, with a WARN), planUuid
     * (optional; otherwise the doel's plan). The calling dossier
     * ([documentId] of the action call) becomes the instrument's `zaak`: its
     * dossier URN ([PdcaUrn.dossier]), the dossiertype being the document's
     * definition name. Refused when the doel or its plan is not actief.
     * Returns {"instrumentUuid": "..."} as declared output (result channel).
     */
    fun handleAanmaakInstrument(properties: Map<String, Any?>, documentId: String?): Map<String, Any?> {
        val doelUuid = requiredUuid(properties, "doelUuid")
        val titel = (properties["titel"] as? String)?.trim()?.takeIf { it.isNotBlank() }
            ?: throw PluginActionException("TITEL_ONTBREEKT", "Property 'titel' ontbreekt of is leeg")
        val product = (properties["product"] as? String)?.takeIf { it.isNotBlank() }
        val gewensteStatus = (properties["status"] as? String)?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: STATUS_ACTIEF
        if (gewensteStatus != STATUS_ACTIEF && gewensteStatus != STATUS_GEPLAND) {
            throw PluginActionException("STATUS_ONGELDIG", "Onbekende startstatus '$gewensteStatus' (verwacht: actief of gepland)")
        }
        val gepland = gewensteStatus == STATUS_GEPLAND
        val zaak = documentId?.takeIf { it.isNotBlank() }?.let(::dossierUrn)

        val doel = registerCall { openPlanGateway.findDoel(doelUuid) }
            ?: throw PluginActionException("DOEL_ONBEKEND", "Doel $doelUuid bestaat niet (meer) in Open Plan")
        val planUuid = optionalUuid(properties, "planUuid") ?: doel.plannen.firstOrNull()?.uuid

        // Retry-safe: the job executor retries this step, and a lost answer
        // must not lead to a second instrument for the same dossier.
        val bestaand = zaak?.let { registerCall { openPlanGateway.listInstrumenten(doelUuid, zaak) }.firstOrNull { it.zaak == zaak } }
        val instrument = if (bestaand != null) {
            log.info("aanmaak-instrument: {} already has instrument {} under doel {}; returned it", zaak, bestaand.uuid, doelUuid)
            bestaand
        } else {
            requireLopend(doel)
            val instrumenttypeUuid = registerCall { instrumenttype(properties["instrumenttype"] as? String, titel) }
            registerCall {
                openPlanGateway.createInstrument(
                    NewInstrument(
                        titel = titel,
                        startdatum = "${LocalDate.now()}T00:00:00Z",
                        doelenUuids = listOf(doelUuid),
                        instrumenttypeUuid = instrumenttypeUuid,
                        product = product,
                        zaak = zaak,
                        status = STATUS_ACTIEF
                    )
                )
            }.also { log.info("aanmaak-instrument: created '$titel' (${it.uuid}) under doel $doelUuid${zaak?.let { z -> " from $z" } ?: ""}") }
        }

        if (gepland && planUuid != null) {
            markeerGepland(instrument.uuid, planUuid)
        } else if (!gepland) {
            registerCall { instrumentLifecycleService.startSubdoelen(instrument.withDoel(doel), planUuid) }
        }
        return mapOf("instrumentUuid" to instrument.uuid.toString())
    }

    /**
     * Plugin action update-instrument. Properties: instrumentUuid (required),
     * status (required: afgerond or geannuleerd), resultaat (optional;
     * behaald/gefaald, defaults to behaald for afgerond), toelichting
     * (optional — stored in the PDCA overlay: effectiviteitToelichting for
     * afgerond, afbreekReden for geannuleerd), planUuid (optional; otherwise
     * the instrument's plan). An instrument already in the requested end
     * state is left as it is (a retry); one that ended otherwise is refused.
     */
    fun handleUpdateInstrument(properties: Map<String, Any?>) {
        val instrumentUuid = requiredUuid(properties, "instrumentUuid")
        val status = (properties["status"] as? String)?.trim()?.lowercase()
            ?: throw PluginActionException("STATUS_ONTBREEKT", "Property 'status' ontbreekt")
        if (status !in setOf(STATUS_AFGEROND, STATUS_GEANNULEERD)) {
            throw PluginActionException(
                "STATUS_ONGELDIG",
                "Onbekende status '$status' (verwacht: afgerond of geannuleerd)"
            )
        }
        val resultaat = (properties["resultaat"] as? String)?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        if (status == STATUS_AFGEROND && resultaat != null && resultaat !in InstrumentLifecycleService.RESULTATEN) {
            throw PluginActionException("RESULTAAT_ONGELDIG", "Onbekend resultaat '$resultaat' (verwacht: behaald of gefaald)")
        }
        val toelichting = (properties["toelichting"] as? String)?.takeIf { it.isNotBlank() }

        val instrument = registerCall { openPlanGateway.findInstrument(instrumentUuid) }
            ?: throw PluginActionException("INSTRUMENT_ONBEKEND", "Instrument $instrumentUuid bestaat niet (meer) in Open Plan")
        val planUuid = optionalUuid(properties, "planUuid") ?: registerCall { instrumentLifecycleService.planVan(instrument) }

        if (instrument.status == status) {
            log.info("update-instrument: {} is already {}; nothing to change", instrumentUuid, status)
        } else {
            if (!instrument.isActief) {
                throw PluginActionException(
                    "INSTRUMENT_NIET_ACTIEF",
                    "Instrument '${instrument.titel ?: instrumentUuid}' is al ${instrument.status ?: "beëindigd"} " +
                        "en kan niet meer worden ${if (status == STATUS_AFGEROND) "afgerond" else "geannuleerd"}"
                )
            }
            registerCall {
                openPlanGateway.closeInstrument(
                    instrumentUuid,
                    InstrumentEinde(
                        status = status,
                        einddatum = "${LocalDate.now()}T00:00:00Z",
                        resultaat = if (status == STATUS_AFGEROND) resultaat ?: InstrumentLifecycleService.RESULTAAT_BEHAALD else null
                    )
                )
            }
            log.info("update-instrument: $instrumentUuid -> $status${resultaat?.let { " ($it)" } ?: ""}")
        }
        if (status == STATUS_AFGEROND) {
            registerCall { instrumentLifecycleService.startSubdoelen(instrument, planUuid) }
        }
        if (toelichting != null) {
            transactionTemplate.executeWithoutResult {
                val details = instrumentDetailsRepository.findById(instrumentUuid).orElse(null)
                    ?: InstrumentDetails(instrumentUuid = instrumentUuid, planUuid = planUuid)
                if (status == STATUS_GEANNULEERD) details.afbreekReden = toelichting
                else details.effectiviteitToelichting = toelichting
                details.updatedAt = LocalDateTime.now()
                instrumentDetailsRepository.save(details)
            }
        }
    }

    /** The doel and its plan must still run before an instrument is added under it. */
    private fun requireLopend(doel: Doel) {
        if (doel.status != null && doel.status != STATUS_ACTIEF) {
            throw PluginActionException(
                "DOEL_NIET_ACTIEF",
                "Doel '${doel.titel ?: doel.uuid}' is ${doel.status}; er wordt geen instrument meer onder aangemaakt"
            )
        }
        doel.plannen.firstOrNull { it.status != null && it.status != STATUS_ACTIEF }?.let { plan ->
            throw PluginActionException(
                "PLAN_NIET_ACTIEF",
                "Plan '${plan.titel ?: plan.uuid}' is ${plan.status}; er wordt geen instrument meer in aangemaakt"
            )
        }
    }

    /**
     * The instrumenttype for a new instrument: the one named (by name or
     * uuid) by the producttype parameter, else the register's first, with a
     * WARN so a missing or unknown parameter shows up in the logs.
     */
    private fun instrumenttype(gevraagd: String?, titel: String): UUID? {
        val typen = openPlanGateway.listInstrumenttypen()
        val waarde = gevraagd?.trim()?.takeIf { it.isNotEmpty() }
        val match = waarde?.let { w ->
            typen.firstOrNull { it.uuid.toString().equals(w, ignoreCase = true) || it.instrumentType.equals(w, ignoreCase = true) }
        }
        if (match != null) return match.uuid
        log.warn(
            "aanmaak-instrument: {} for '{}'; using the first instrumenttype '{}'",
            if (waarde == null) "no instrumenttype parameter on the producttype" else "unknown instrumenttype '$waarde'",
            titel, typen.firstOrNull()?.instrumentType
        )
        return typen.firstOrNull()?.uuid
    }

    private fun markeerGepland(instrumentUuid: UUID, planUuid: UUID) {
        transactionTemplate.executeWithoutResult {
            // Only a new row: on a retry the instrument may have been started meanwhile.
            if (instrumentDetailsRepository.findById(instrumentUuid).isEmpty) {
                instrumentDetailsRepository.save(
                    InstrumentDetails(instrumentUuid = instrumentUuid, planUuid = planUuid, uitvoeringsStatus = UitvoeringsStatus.GEPLAND)
                )
            }
        }
    }

    /** A freshly created instrument carries the doel's status as the register embeds it. */
    private fun Instrument.withDoel(doel: Doel): Instrument =
        if (doelen.any { it.uuid == doel.uuid && it.status != null }) this
        else copy(doelen = doelen.filter { it.uuid != doel.uuid } + DoelRef(doel.uuid, doel.status, doel.titel, doel.hoofdDoel))

    /**
     * The URN of the calling dossier, from the dossier itself: its document
     * definition name (the dossier type) plus its id. The plan page reads it
     * back from the register and offers the jump to the dossier.
     */
    private fun dossierUrn(documentId: String): String = try {
        PdcaUrn.dossier(gzacClient.documentDefinitionName(documentId), documentId).toString()
    } catch (e: ResponseStatusException) {
        throw PluginActionException(
            "DOSSIER_ONBEREIKBAAR",
            "Het aanroepende dossier $documentId is niet op te halen in GZAC: ${e.reason}",
            retryable = true
        )
    }

    /** Register failures: a refusal is final (422), an outage or unexpected answer may pass (503, retried). */
    private fun <T> registerCall(call: () -> T): T = try {
        call()
    } catch (e: RegisterException) {
        val melding = e.reason ?: "${e.register.displayName}-aanroep mislukt"
        when (e.failure) {
            RegisterFailure.NOT_FOUND, RegisterFailure.REJECTED -> throw PluginActionException("REGISTER_WEIGERT", melding)
            RegisterFailure.UNAVAILABLE, RegisterFailure.FAILED ->
                throw PluginActionException("REGISTER_ONBEREIKBAAR", melding, retryable = true)
        }
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

    /** An optional uuid property; an unparseable value counts as absent. */
    private fun optionalUuid(properties: Map<String, Any?>, key: String): UUID? =
        (properties[key] as? String)?.let {
            try { UUID.fromString(it.trim()) } catch (e: IllegalArgumentException) { null }
        }
}
