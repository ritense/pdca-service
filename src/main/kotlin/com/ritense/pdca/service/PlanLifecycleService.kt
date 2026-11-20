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
import com.ritense.pdca.domain.PdcaUrn
import com.ritense.pdca.domain.UitvoeringsStatus
import com.ritense.pdca.registers.RegisterException
import com.ritense.pdca.registers.openplan.Doel
import com.ritense.pdca.registers.openplan.DoelStatusUpdate
import com.ritense.pdca.registers.openplan.NewDoel
import com.ritense.pdca.registers.openplan.OpenPlanGateway
import com.ritense.pdca.registers.openplan.Plan
import com.ritense.pdca.registers.openplan.PlanStatusUpdate
import com.ritense.pdca.registers.openplan.STATUS_ACTIEF
import com.ritense.pdca.registers.openplan.STATUS_AFGEROND
import com.ritense.pdca.registers.openplan.STATUS_GEANNULEERD
import com.ritense.pdca.repository.DoelDetailsRepository
import com.ritense.pdca.repository.PlanDetailsRepository
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * The plan-level use cases of the plan tabs (ARCHITECTURE §6.3):
 * switching the hoofddoel, closing a plan (with the check that tells the
 * user what is still open) and reopening it under the one-active-plan rule.
 * The register is the one store for each of them; there is no overlay copy
 * of the hoofddoel or the plan status.
 */
@Service
class PlanLifecycleService(
    private val openPlanGateway: OpenPlanGateway,
    private val planDetailsRepository: PlanDetailsRepository,
    private val doelDetailsRepository: DoelDetailsRepository,
    private val instrumentAanvraagService: InstrumentAanvraagService
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Outcome of a hoofddoel switch, for the evaluation record of the caller. */
    data class HoofddoelWissel(val gewijzigd: Boolean, val hoofddoelUuid: UUID, val van: String?, val naar: String?)

    /** What is still open in a plan, so the user can be warned before closing it. */
    data class AfsluitCheck(
        /** Subdoelen still actief in Open Plan. */
        val openSubdoelen: Int,
        /** Actief instruments the plan page manages. */
        val openInstrumenten: Int,
        /** Titles of actief instruments managed by an instrument dossier; closing waits for these. */
        val openInstrumentdossiers: List<String>,
        /** Verzoeken whose instrument has not appeared yet; null when the Objecten API could not be read. */
        val openVerzoeken: Int?
    )

    /**
     * Makes a doel of hoofddoeltype [doeltypeUuid] the plan's one actief
     * hoofddoel: the actief hoofddoel of that type when there is one (also
     * the one an interrupted earlier switch created), otherwise a new one
     * (with its overlay row, gestart); every other actief hoofddoel becomes
     * afgerond and stays as history; every subdoel is re-pointed to it.
     * Each step skips what is done, so a retry after a failure completes the
     * switch.
     */
    fun wisselHoofddoel(planUuid: UUID, doeltypeUuid: UUID): HoofddoelWissel {
        val plan = openPlanGateway.getPlan(planUuid)
        if (plan.status != STATUS_ACTIEF) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Het hoofddoel van een gesloten plan kan niet meer wisselen")
        }
        val details = planDetailsRepository.findById(planUuid).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Geen plandetails voor plan $planUuid")
        }
        val doeltypen = openPlanGateway.listDoeltypen()
        val type = doeltypen.firstOrNull { it.uuid == doeltypeUuid }
            ?.takeIf { it.isHoofddoelType }
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Kies een hoofddoel uit het doeltype-register")
        val hoofddoelTypen = doeltypen.filter { it.isHoofddoelType }.map { it.uuid }.toSet()
        val doelen = openPlanGateway.listDoelen(planUuid)
        val isHoofddoel = { d: Doel -> d.doeltype?.uuid in hoofddoelTypen }
        val actief = doelen.filter { isHoofddoel(it) && it.status == STATUS_ACTIEF }

        val bestaand = actief.firstOrNull { it.doeltype?.uuid == doeltypeUuid }
        val oud = actief.filter { it.uuid != bestaand?.uuid }
        val subdoelen = doelen.filterNot(isHoofddoel)
        if (bestaand != null && oud.isEmpty() && subdoelen.all { it.hoofdDoel == bestaand.uuid }) {
            return HoofddoelWissel(false, bestaand.uuid, bestaand.titel, bestaand.titel)
        }

        val nieuw = bestaand ?: openPlanGateway.createDoel(
            NewDoel(
                plannenUuids = listOf(planUuid),
                persoonUuid = details.persoonUuid,
                doeltypeUuid = type.uuid,
                titel = type.doelType.orEmpty(),
                beschrijving = plan.notitie.orEmpty(),
                startdatum = nu()
            )
        )
        if (!doelDetailsRepository.existsById(nieuw.uuid)) {
            doelDetailsRepository.save(
                DoelDetails(
                    doelUuid = nieuw.uuid,
                    planUuid = planUuid,
                    uitvoeringsStatus = UitvoeringsStatus.GESTART,
                    sortering = PlanIntakeService.HOOFDDOEL_SORTERING
                )
            )
        }
        oud.forEach { openPlanGateway.updateDoelStatus(it.uuid, DoelStatusUpdate(status = STATUS_AFGEROND, einddatum = nu())) }
        subdoelen.filter { it.hoofdDoel != nieuw.uuid }.forEach { openPlanGateway.updateDoelHoofddoel(it.uuid, nieuw.uuid) }
        log.info("Plan {}: hoofddoel switched to {} ({})", planUuid, nieuw.uuid, type.doelType)
        return HoofddoelWissel(true, nieuw.uuid, oud.firstOrNull()?.titel, nieuw.titel ?: type.doelType)
    }

    /** What is still open in the plan (read only). */
    fun afsluitCheck(planUuid: UUID): AfsluitCheck {
        val doelen = openPlanGateway.listDoelen(planUuid)
        val hoofddoelTypen = openPlanGateway.listDoeltypen().filter { it.isHoofddoelType }.map { it.uuid }.toSet()
        val instrumenten = openPlanGateway.listInstrumentenVanDoelen(doelen.map { it.uuid }).filter { it.isActief }
        val verzoeken = try {
            instrumentAanvraagService.openVerzoeken(planUuid).size
        } catch (e: RegisterException) {
            log.warn("Open verzoeken of plan {} could not be read: {}", planUuid, e.reason)
            null
        }
        return AfsluitCheck(
            openSubdoelen = doelen.count { it.doeltype?.uuid !in hoofddoelTypen && it.status == STATUS_ACTIEF },
            openInstrumenten = instrumenten.count { !it.isDossierManaged },
            openInstrumentdossiers = instrumenten.filter { it.isDossierManaged }.map { it.titel ?: it.uuid.toString() },
            openVerzoeken = verzoeken
        )
    }

    /**
     * Closes the plan as afgerond (optional explanation) or geannuleerd
     * (reason required), with today as einddatum. Open subdoelen,
     * instruments and verzoeken do not stop it — the plan page warns about
     * them ([afsluitCheck]) — but an actief instrument managed by its
     * instrument dossier does: that dossier has to be closed first (409). A
     * plan that has the requested status already is left as it is.
     */
    fun afsluiten(planUuid: UUID, status: String, reden: String?): Plan {
        val plan = openPlanGateway.getPlan(planUuid)
        if (plan.status == status) return plan
        if (plan.status != STATUS_ACTIEF) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Alleen een actief plan kan worden afgesloten (status: ${plan.status})")
        }
        val tekst = reden?.trim().orEmpty()
        if (status == STATUS_GEANNULEERD && tekst.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Geef een reden op voor het afbreken")
        }
        if (tekst.length > PlanIntakeService.TEKST_MAX) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "De toelichting is langer dan ${PlanIntakeService.TEKST_MAX} tekens")
        }
        val check = afsluitCheck(planUuid)
        if (check.openInstrumentdossiers.isNotEmpty()) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "Sluit eerst de instrumentdossiers af: ${check.openInstrumentdossiers.joinToString()}"
            )
        }
        openPlanGateway.updatePlanStatus(planUuid, PlanStatusUpdate.einde(status, nu(), tekst))
        log.info("Plan {} closed as {}", planUuid, status)
        return openPlanGateway.getPlan(planUuid)
    }

    /**
     * Reopens a closed plan, clearing its einddatum and redenEinde. For a
     * plan about an inwoner the W&P rule holds: at most one active plan per
     * plan case type, so another active plan of that inwoner and type
     * refuses it (409).
     */
    fun heractiveren(planUuid: UUID): Plan {
        val plan = openPlanGateway.getPlan(planUuid)
        if (plan.status == STATUS_ACTIEF) return plan
        val details = planDetailsRepository.findById(planUuid).orElse(null)
        val caseDefinitionKey = details?.caseDefinitionKey
        if (details != null && caseDefinitionKey != null && PdcaUrn.parse(plan.domeinregister)?.kind == PdcaUrn.Kind.PERSOON) {
            val anderActief = planDetailsRepository.findByPersoonUuidAndCaseDefinitionKey(details.persoonUuid, caseDefinitionKey)
                .filter { it.planUuid != planUuid }
                .firstNotNullOfOrNull { ander -> openPlanGateway.findPlan(ander.planUuid)?.takeIf { it.status == STATUS_ACTIEF } }
            if (anderActief != null) {
                throw ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Deze inwoner heeft al een actief plan van dit type ('${anderActief.titel ?: anderActief.uuid}'); " +
                        "rond dat plan eerst af of breek het af"
                )
            }
        }
        openPlanGateway.updatePlanStatus(planUuid, PlanStatusUpdate.heropend())
        log.info("Plan {} reopened", planUuid)
        return openPlanGateway.getPlan(planUuid)
    }

    private fun nu() = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS).toString()
}
