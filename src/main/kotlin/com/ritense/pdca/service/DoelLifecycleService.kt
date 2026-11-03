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

import com.ritense.pdca.registers.openplan.DoelStatusUpdate
import com.ritense.pdca.registers.openplan.Instrument
import com.ritense.pdca.registers.openplan.InstrumentEinde
import com.ritense.pdca.registers.openplan.OpenPlanGateway
import com.ritense.pdca.registers.openplan.STATUS_ACTIEF
import com.ritense.pdca.registers.openplan.STATUS_GEANNULEERD
import com.ritense.pdca.repository.InstrumentDetailsRepository
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Removing and aborting a doel with what hangs under it (ARCHITECTURE §6.3).
 * Both refuse (409) while the doel has an actief instrument managed by its
 * instrument dossier: that dossier owns the instrument, so it has to be
 * closed there first.
 *
 * The steps run instruments first and the doel last, each skipping what is
 * already done, so a failure halfway leaves a doel that still exists with
 * part of its instruments handled, and a retry finishes it.
 */
@Service
class DoelLifecycleService(
    private val openPlanGateway: OpenPlanGateway,
    private val instrumentDetailsRepository: InstrumentDetailsRepository,
    private val instrumentLifecycleService: InstrumentLifecycleService,
    private val planOverlayService: PlanOverlayService
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** What an abort or removal took along. */
    data class Resultaat(val instrumenten: Int)

    /**
     * Removes a doel: its own instruments (those under no other doel) and
     * their overlay rows, the doel itself, then its overlay row and acties.
     * A doel that is already gone only gets its overlay cleaned up.
     */
    fun verwijderen(doelUuid: UUID): Resultaat {
        if (openPlanGateway.findDoel(doelUuid) == null) {
            planOverlayService.deleteDoelDetails(doelUuid)
            return Resultaat(0)
        }
        val instrumenten = openPlanGateway.listInstrumenten(doelUuid)
        refuseOpenDossiers(instrumenten)
        val eigen = instrumenten.filter { inst -> inst.doelen.all { it.uuid == doelUuid } }
        eigen.forEach { inst ->
            openPlanGateway.deleteInstrument(inst.uuid)
            if (instrumentDetailsRepository.existsById(inst.uuid)) planOverlayService.deleteInstrumentDetails(inst.uuid)
        }
        openPlanGateway.deleteDoel(doelUuid)
        planOverlayService.deleteDoelDetails(doelUuid)
        log.info("Doel {} removed with {} instrumenten", doelUuid, eigen.size)
        return Resultaat(eigen.size)
    }

    /**
     * Aborts a doel with its required reason: its actief instruments are
     * cancelled with "Afgebroken met doel: <reden>", then the doel becomes
     * geannuleerd with the reason as toelichting. A doel that is geannuleerd
     * already is left as it is; one that ended otherwise is refused.
     */
    fun afbreken(doelUuid: UUID, reden: String): Resultaat {
        val tekst = reden.trim().takeIf { it.isNotEmpty() }
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Geef een reden op voor het afbreken")
        val doel = openPlanGateway.findDoel(doelUuid)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Doel niet gevonden in Open Plan")
        if (doel.status != STATUS_ACTIEF && doel.status != STATUS_GEANNULEERD) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Alleen een lopend doel kan worden afgebroken (status: ${doel.status})")
        }
        val instrumenten = openPlanGateway.listInstrumenten(doelUuid)
        refuseOpenDossiers(instrumenten)
        val lopend = instrumenten.filter { it.isActief }
        lopend.forEach { instrumentLifecycleService.afbreken(it.uuid, "Afgebroken met doel: $tekst") }
        if (doel.status != STATUS_GEANNULEERD) {
            openPlanGateway.updateDoelStatus(
                doelUuid,
                DoelStatusUpdate(
                    status = STATUS_GEANNULEERD,
                    einddatum = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS).toString(),
                    toelichtingResultaat = tekst
                )
            )
        }
        log.info("Doel {} aborted with {} instrumenten", doelUuid, lopend.size)
        return Resultaat(lopend.size)
    }

    private fun refuseOpenDossiers(instrumenten: List<Instrument>) {
        val open = instrumenten.filter { it.isActief && it.isDossierManaged }
        if (open.isNotEmpty()) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "Sluit eerst de instrumentdossiers af: ${open.joinToString { it.titel ?: it.uuid.toString() }}"
            )
        }
    }
}
