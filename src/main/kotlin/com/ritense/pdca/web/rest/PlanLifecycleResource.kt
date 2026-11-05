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

package com.ritense.pdca.web.rest

import com.ritense.pdca.registers.openplan.Plan
import com.ritense.pdca.registers.openplan.STATUS_AFGEROND
import com.ritense.pdca.registers.openplan.STATUS_GEANNULEERD
import com.ritense.pdca.service.DoelLifecycleService
import com.ritense.pdca.service.InstrumentLifecycleService
import com.ritense.pdca.service.PlanLifecycleService
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * The lifecycle use cases of the plan tabs, one endpoint per user intent
 * (ARCHITECTURE §6.3): starting, completing and aborting an instrument the
 * plan page manages, removing and aborting a doel with what hangs under it,
 * switching the hoofddoel, and closing and reopening the plan. The rules
 * live in the services; every endpoint is safe to repeat after a failure.
 */
@RestController
@RequestMapping("/api/v1/pdca", produces = [MediaType.APPLICATION_JSON_VALUE])
class PlanLifecycleResource(
    private val instrumentLifecycleService: InstrumentLifecycleService,
    private val doelLifecycleService: DoelLifecycleService,
    private val planLifecycleService: PlanLifecycleService
) {

    data class RedenRequest(@field:NotBlank(message = "Geef een reden op") val reden: String = "")
    data class OptioneleRedenRequest(val reden: String? = null)
    data class AfrondenRequest(val resultaat: String? = null)
    data class HoofddoelRequest(@field:NotNull(message = "Kies een hoofddoel") val doeltypeUuid: UUID? = null)

    // ---------------------------------------------------------- instrumenten

    /** A gepland instrument starts (overlay); its gepland subdoelen start along with it. */
    @PostMapping("/instrumenten/{uuid}/starten")
    fun startInstrument(@PathVariable(name = "uuid") uuid: UUID): InstrumentLifecycleService.Resultaat =
        instrumentLifecycleService.starten(uuid)

    /** Completes an instrument the plan page manages; 409 for one its instrument dossier manages. */
    @PostMapping("/instrumenten/{uuid}/afronden")
    fun completeInstrument(
        @PathVariable(name = "uuid") uuid: UUID,
        @RequestBody(required = false) request: AfrondenRequest?
    ): InstrumentLifecycleService.Resultaat = instrumentLifecycleService.afronden(uuid, request?.resultaat)

    /** Aborts an instrument the plan page manages, with a reason; 409 for one its instrument dossier manages. */
    @PostMapping("/instrumenten/{uuid}/afbreken")
    fun abortInstrument(
        @PathVariable(name = "uuid") uuid: UUID,
        @Valid @RequestBody request: RedenRequest
    ): InstrumentLifecycleService.Resultaat = instrumentLifecycleService.afbreken(uuid, request.reden)

    // ---------------------------------------------------------------- doelen

    /** Removes a doel with its own instruments, overlay row and acties; 409 while an instrument dossier of it is open. */
    @DeleteMapping("/doelen/{uuid}")
    fun deleteDoel(@PathVariable(name = "uuid") uuid: UUID): DoelLifecycleService.Resultaat =
        doelLifecycleService.verwijderen(uuid)

    /** Aborts a doel and its actief instruments with a reason; 409 while an instrument dossier of it is open. */
    @PostMapping("/doelen/{uuid}/afbreken")
    fun abortDoel(
        @PathVariable(name = "uuid") uuid: UUID,
        @Valid @RequestBody request: RedenRequest
    ): DoelLifecycleService.Resultaat = doelLifecycleService.afbreken(uuid, request.reden)

    // --------------------------------------------------------------- plannen

    /** Makes a doel of this hoofddoeltype the plan's one actief hoofddoel. */
    @PutMapping("/plannen/{uuid}/hoofddoel")
    fun switchHoofddoel(
        @PathVariable(name = "uuid") uuid: UUID,
        @Valid @RequestBody request: HoofddoelRequest
    ): PlanLifecycleService.HoofddoelWissel = planLifecycleService.wisselHoofddoel(uuid, request.doeltypeUuid!!)

    /** What is still open in the plan, to warn before closing it. */
    @GetMapping("/plannen/{uuid}/afsluitcheck")
    fun closeCheck(@PathVariable(name = "uuid") uuid: UUID): PlanLifecycleService.AfsluitCheck =
        planLifecycleService.afsluitCheck(uuid)

    @PostMapping("/plannen/{uuid}/afronden")
    fun completePlan(
        @PathVariable(name = "uuid") uuid: UUID,
        @RequestBody(required = false) request: OptioneleRedenRequest?
    ): Plan = planLifecycleService.afsluiten(uuid, STATUS_AFGEROND, request?.reden)

    @PostMapping("/plannen/{uuid}/afbreken")
    fun abortPlan(
        @PathVariable(name = "uuid") uuid: UUID,
        @Valid @RequestBody request: RedenRequest
    ): Plan = planLifecycleService.afsluiten(uuid, STATUS_GEANNULEERD, request.reden)

    /** Reopens a closed plan; 409 when the inwoner has another active plan of this type. */
    @PostMapping("/plannen/{uuid}/heractiveren")
    fun reopenPlan(@PathVariable(name = "uuid") uuid: UUID): Plan = planLifecycleService.heractiveren(uuid)
}
