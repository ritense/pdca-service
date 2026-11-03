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

import com.ritense.pdca.domain.DoelDetails
import com.ritense.pdca.domain.InstrumentDetails
import com.ritense.pdca.domain.UitvoeringsStatus
import com.ritense.pdca.registers.openplan.Instrument
import com.ritense.pdca.registers.openplan.InstrumentEinde
import com.ritense.pdca.registers.openplan.OpenPlanGateway
import com.ritense.pdca.registers.openplan.STATUS_ACTIEF
import com.ritense.pdca.registers.openplan.STATUS_AFGEROND
import com.ritense.pdca.registers.openplan.STATUS_GEANNULEERD
import com.ritense.pdca.repository.DoelDetailsRepository
import com.ritense.pdca.repository.InstrumentDetailsRepository
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * The lifecycle of an instrument and the rule that ties it to its subdoel
 * (ARCHITECTURE §6.2, §6.3):
 *
 *  - **A subdoel starts with its first started instrument.** Whenever an
 *    instrument is created as gestart, started or completed, every actief
 *    subdoel it belongs to that is still gepland becomes gestart
 *    ([startSubdoelen]); the plugin actions and the plan page both come
 *    through here.
 *  - **One owner per instrument.** An instrument whose `zaak` is a dossier
 *    URN is managed by its instrument dossier only: [afronden] and
 *    [afbreken] refuse it (409). Starting it stays possible: that is the
 *    overlay's refinement of `actief`, which the dossier does not model.
 *
 * Register writes come first, the overlay row follows outside any
 * transaction around the HTTP call; every step is idempotent, so a retry
 * after a failure completes what is missing.
 */
@Service
class InstrumentLifecycleService(
    private val openPlanGateway: OpenPlanGateway,
    private val instrumentDetailsRepository: InstrumentDetailsRepository,
    private val doelDetailsRepository: DoelDetailsRepository,
    private val transactionTemplate: TransactionTemplate
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** A subdoel moved from gepland to gestart, for the evaluation record of the caller. */
    data class GestartSubdoel(val uuid: UUID, val titel: String?)

    /** Outcome of a lifecycle step on the plan page: the subdoelen that started along with it. */
    data class Resultaat(val gestarteSubdoelen: List<GestartSubdoel> = emptyList())

    /** Starts a gepland instrument (overlay GESTART) and with it its gepland subdoelen. */
    fun starten(instrumentUuid: UUID): Resultaat {
        val instrument = instrument(instrumentUuid)
        if (!instrument.isActief) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT, "Alleen een lopend instrument kan worden gestart (status in Open Plan: ${instrument.status})"
            )
        }
        val planUuid = planVan(instrument)
        transactionTemplate.executeWithoutResult {
            val details = instrumentDetailsRepository.findById(instrumentUuid).orElse(null)
                ?: InstrumentDetails(instrumentUuid = instrumentUuid, planUuid = planUuid)
            details.uitvoeringsStatus = UitvoeringsStatus.GESTART
            details.updatedAt = LocalDateTime.now()
            instrumentDetailsRepository.save(details)
        }
        return Resultaat(startSubdoelen(instrument, planUuid))
    }

    /**
     * Completes an instrument the plan page manages (resultaat behaald unless
     * given). When it is afgerond already only the subdoelen are started, so
     * a retry after a failure between the two steps completes the second.
     */
    fun afronden(instrumentUuid: UUID, resultaat: String?): Resultaat {
        val instrument = instrument(instrumentUuid)
        refuseDossierManaged(instrument, "afgerond")
        if (instrument.status == STATUS_AFGEROND) return Resultaat(startSubdoelen(instrument, planVan(instrument)))
        requireActief(instrument, "afgerond")
        val gekozen = resultaat?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: RESULTAAT_BEHAALD
        if (gekozen !in RESULTATEN) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Onbekend resultaat '$gekozen' (verwacht: behaald of gefaald)")
        }
        openPlanGateway.closeInstrument(instrumentUuid, InstrumentEinde(STATUS_AFGEROND, vandaag(), gekozen))
        return Resultaat(startSubdoelen(instrument, planVan(instrument)))
    }

    /** Cancels an instrument the plan page manages, with its required reason; a no-op when it is geannuleerd already. */
    fun afbreken(instrumentUuid: UUID, reden: String): Resultaat {
        val tekst = reden.trim().takeIf { it.isNotEmpty() }
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Geef een reden op voor het afbreken")
        val instrument = instrument(instrumentUuid)
        refuseDossierManaged(instrument, "afgebroken")
        if (instrument.status != STATUS_GEANNULEERD) {
            requireActief(instrument, "afgebroken")
            openPlanGateway.closeInstrument(instrumentUuid, InstrumentEinde(STATUS_GEANNULEERD, vandaag()))
        }
        saveAfbreekReden(instrumentUuid, planVan(instrument), tekst)
        return Resultaat()
    }

    /**
     * Moves every actief subdoel of [instrument] that is still gepland (an
     * overlay row GEPLAND, or none) to GESTART; the hoofddoel always runs
     * and is left alone. Returns the subdoelen it started.
     */
    fun startSubdoelen(instrument: Instrument, planUuid: UUID?): List<GestartSubdoel> {
        val kandidaten = instrument.doelen.filter { it.status == null || it.status == STATUS_ACTIEF }
        if (kandidaten.isEmpty()) return emptyList()
        val rijen = doelDetailsRepository.findAllById(kandidaten.map { it.uuid }).associateBy { it.doelUuid }
        val teStarten = kandidaten.filter { rijen[it.uuid]?.uitvoeringsStatus != UitvoeringsStatus.GESTART }
        if (teStarten.isEmpty()) return emptyList()
        // Doelen without an overlay row need their plan; a register read, so before the transaction.
        val planPerDoel = teStarten.associate { ref ->
            ref.uuid to (rijen[ref.uuid]?.planUuid ?: planUuid ?: openPlanGateway.getDoel(ref.uuid).plannen.firstOrNull()?.uuid)
        }
        val gestart = transactionTemplate.execute {
            teStarten.mapNotNull { ref ->
                val bestaand = doelDetailsRepository.findById(ref.uuid).orElse(null)
                if (bestaand?.uitvoeringsStatus == UitvoeringsStatus.GESTART) return@mapNotNull null
                val rij = bestaand ?: DoelDetails(
                    doelUuid = ref.uuid,
                    planUuid = planPerDoel[ref.uuid] ?: return@mapNotNull null,
                    sortering = (doelDetailsRepository.findByPlanUuid(planPerDoel[ref.uuid]!!).maxOfOrNull { it.sortering } ?: -1) + 1
                )
                rij.uitvoeringsStatus = UitvoeringsStatus.GESTART
                rij.updatedAt = LocalDateTime.now()
                doelDetailsRepository.save(rij)
                GestartSubdoel(ref.uuid, ref.titel)
            }
        }.orEmpty()
        gestart.forEach { log.info("Subdoel {} started along with instrument {}", it.uuid, instrument.uuid) }
        return gestart
    }

    /** The plan an instrument belongs to: the plan of its first doel. */
    fun planVan(instrument: Instrument): UUID {
        val doelUuid = instrument.doelen.firstOrNull()?.uuid
            ?: throw ResponseStatusException(HttpStatus.CONFLICT, "Instrument ${instrument.uuid} hangt niet onder een doel")
        return instrumentDetailsRepository.findById(instrument.uuid).orElse(null)?.planUuid
            ?: doelDetailsRepository.findById(doelUuid).orElse(null)?.planUuid
            ?: openPlanGateway.getDoel(doelUuid).plannen.firstOrNull()?.uuid
            ?: throw ResponseStatusException(HttpStatus.CONFLICT, "Doel $doelUuid hangt niet onder een plan")
    }

    private fun saveAfbreekReden(instrumentUuid: UUID, planUuid: UUID, reden: String) {
        transactionTemplate.executeWithoutResult {
            val details = instrumentDetailsRepository.findById(instrumentUuid).orElse(null)
                ?: InstrumentDetails(instrumentUuid = instrumentUuid, planUuid = planUuid)
            details.afbreekReden = reden
            details.updatedAt = LocalDateTime.now()
            instrumentDetailsRepository.save(details)
        }
    }

    private fun instrument(uuid: UUID): Instrument =
        openPlanGateway.findInstrument(uuid)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Instrument niet gevonden in Open Plan")

    private fun refuseDossierManaged(instrument: Instrument, wat: String) {
        if (instrument.isDossierManaged) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "'${instrument.titel ?: "Dit instrument"}' wordt beheerd door zijn instrumentdossier en kan alleen " +
                    "daar worden $wat"
            )
        }
    }

    private fun requireActief(instrument: Instrument, wat: String) {
        if (!instrument.isActief) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "'${instrument.titel ?: "Dit instrument"}' is ${instrument.status ?: "niet actief"} en kan niet meer worden $wat"
            )
        }
    }

    private fun vandaag() = "${LocalDate.now()}T00:00:00Z"

    companion object {
        const val RESULTAAT_BEHAALD = "behaald"
        val RESULTATEN = setOf(RESULTAAT_BEHAALD, "gefaald")
    }
}
