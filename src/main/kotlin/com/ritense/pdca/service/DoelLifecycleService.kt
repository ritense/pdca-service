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

import com.ritense.pdca.domain.heeftOpenTaak
import com.ritense.pdca.registers.openplan.DoelStatusUpdate
import com.ritense.pdca.registers.openplan.Instrument
import com.ritense.pdca.registers.openplan.OpenPlanGateway
import com.ritense.pdca.registers.openplan.STATUS_ACTIEF
import com.ritense.pdca.registers.openplan.STATUS_GEANNULEERD
import com.ritense.pdca.repository.ActionRepository
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
 * Removing and aborting a doel (ARCHITECTURE §6.3). Neither leaves work
 * behind without its doel: aborting is refused (409) while anything still
 * runs under the doel — an actief instrument, managed by its instrument
 * dossier or by the plan page, a verzoek whose instrument dossier has not
 * created its instrument yet, or an actie whose GZAC task is open — and
 * removing while an instrument dossier, a verzoek or a task of it is open.
 * What runs has to be ended (or have its dossier or task ended) first;
 * nothing is cancelled along with the doel. Its ingeplande instrumenten
 * have not started and go with the doel.
 *
 * Removal runs instruments first and the doel last, each step skipping
 * what is already done, so a failure halfway leaves a doel that still
 * exists with part of its instruments removed, and a retry finishes it.
 */
@Service
class DoelLifecycleService(
    private val openPlanGateway: OpenPlanGateway,
    private val instrumentDetailsRepository: InstrumentDetailsRepository,
    private val instrumentAanvraagService: InstrumentAanvraagService,
    private val ingeplandInstrumentService: IngeplandInstrumentService,
    private val actionRepository: ActionRepository,
    private val planOverlayService: PlanOverlayService
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** What a removal took along. */
    data class Resultaat(val instrumenten: Int)

    /**
     * Removes a doel: its own instruments (those under no other doel) and
     * their overlay rows, the doel itself, then its overlay row, acties and
     * ingeplande instrumenten. A doel that is already gone only gets its
     * overlay cleaned up.
     */
    fun verwijderen(doelUuid: UUID): Resultaat {
        if (openPlanGateway.findDoel(doelUuid) == null) {
            planOverlayService.deleteDoelDetails(doelUuid)
            ingeplandInstrumentService.verwijderVanDoel(doelUuid)
            return Resultaat(0)
        }
        val instrumenten = openPlanGateway.listInstrumenten(doelUuid)
        weigerBijLopendWerk(doelUuid, instrumenten.filter { it.isDossierManaged })
        val eigen = instrumenten.filter { inst -> inst.doelen.all { it.uuid == doelUuid } }
        eigen.forEach { inst ->
            openPlanGateway.deleteInstrument(inst.uuid)
            if (instrumentDetailsRepository.existsById(inst.uuid)) planOverlayService.deleteInstrumentDetails(inst.uuid)
        }
        openPlanGateway.deleteDoel(doelUuid)
        planOverlayService.deleteDoelDetails(doelUuid)
        ingeplandInstrumentService.verwijderVanDoel(doelUuid)
        log.info("Doel {} removed with {} instrumenten", doelUuid, eigen.size)
        return Resultaat(eigen.size)
    }

    /**
     * Aborts a doel with its required reason: the doel becomes geannuleerd
     * with the reason as toelichting, and its ingeplande instrumenten are
     * dropped. Refused while anything still runs under it. A doel that is
     * geannuleerd already is left as it is (apart from dropping what is still
     * ingepland); one that ended otherwise is refused.
     */
    fun afbreken(doelUuid: UUID, reden: String) {
        val tekst = reden.trim().takeIf { it.isNotEmpty() }
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Geef een reden op voor het afbreken")
        val doel = openPlanGateway.findDoel(doelUuid)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Doel niet gevonden in Open Plan")
        if (doel.status == STATUS_GEANNULEERD) {
            ingeplandInstrumentService.verwijderVanDoel(doelUuid)
            return
        }
        if (doel.status != STATUS_ACTIEF) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Alleen een lopend doel kan worden afgebroken (status: ${doel.status})")
        }
        weigerBijLopendWerk(doelUuid, openPlanGateway.listInstrumenten(doelUuid))
        openPlanGateway.updateDoelStatus(
            doelUuid,
            DoelStatusUpdate(
                status = STATUS_GEANNULEERD,
                einddatum = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS).toString(),
                toelichtingResultaat = tekst
            )
        )
        ingeplandInstrumentService.verwijderVanDoel(doelUuid)
        log.info("Doel {} aborted", doelUuid)
    }

    /**
     * 409 naming what still runs under the doel: the actief ones among
     * [instrumenten], the doel's open verzoeken and the acties whose GZAC
     * task is open. Fails closed: when the verzoeken cannot be read, the
     * register error stands.
     */
    private fun weigerBijLopendWerk(doelUuid: UUID, instrumenten: List<Instrument>) {
        val lopend = buildList {
            instrumenten.filter { it.isActief }.forEach { inst ->
                add("${inst.titel ?: inst.uuid} (${if (inst.isDossierManaged) "instrumentdossier" else "instrument"})")
            }
            instrumentAanvraagService.openVerzoekenVoor(doelUuid).forEach { add("${it.productTitel ?: "instrument"} (aanvraag)") }
            actionRepository.findByDoelUuid(doelUuid).filter { it.heeftOpenTaak }
                .forEach { add("${it.title} (taak)") }
        }
        if (lopend.isNotEmpty()) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Sluit eerst af wat nog loopt onder dit doel: ${lopend.joinToString()}")
        }
    }
}
