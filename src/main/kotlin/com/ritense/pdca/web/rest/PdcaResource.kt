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

import com.ritense.pdca.domain.ContactmomentDetails
import com.ritense.pdca.domain.DoelDetails
import com.ritense.pdca.domain.InstrumentDetails
import com.ritense.pdca.domain.PlanDetails
import com.ritense.pdca.domain.VoortgangStatusOptie
import com.ritense.pdca.service.ContactmomentService
import com.ritense.pdca.service.PlanOverlayService
import com.ritense.pdca.web.rest.dto.ContactmomentDatumRequest
import com.ritense.pdca.web.rest.dto.ContactmomentRequest
import com.ritense.pdca.web.rest.dto.DoelDetailsRequest
import com.ritense.pdca.web.rest.dto.InstrumentDetailsRequest
import com.ritense.pdca.web.rest.dto.PlanDetailsRequest
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * PDCA process overlay keyed by register uuids. Plans, doelen, instrumenten,
 * personen, doeltypen, relatietypen and producttypen are consumed directly
 * from Open Plan / Open Product, contactmomenten (klantcontacten) from Open
 * Klant, through the [com.ritense.pdca.registers.RegisterProxyController];
 * this resource only stores what those registers do not model
 * ([PlanOverlayService]); the PUT endpoints are partial updates (an absent
 * field keeps its value, an explicit null clears it). Overlay rows go when
 * their register object goes, through the lifecycle use cases.
 * Contactmomenten are the exception: creating one and rescheduling a
 * planned one go through this resource ([ContactmomentService]), reading
 * them goes through the proxy. Contactmomenten cannot be deleted.
 */
@RestController
@RequestMapping("/api/v1/pdca", produces = [MediaType.APPLICATION_JSON_VALUE])
class PdcaResource(
    private val planOverlayService: PlanOverlayService,
    private val contactmomentService: ContactmomentService
) {

    // --------------------------------------------------------- plandetails

    /** Edits the overlay row of an existing plan (404 without one: plans come from the intake). */
    @PutMapping("/plandetails/{planUuid}")
    fun updatePlanDetails(
        @PathVariable(name = "planUuid") planUuid: UUID,
        @RequestBody request: PlanDetailsRequest
    ): PlanDetails = planOverlayService.updatePlanDetails(planUuid, request)

    // --------------------------------------------------------- doeldetails

    /**
     * The voortgangsstatussen a doel of this plan can get (its case type's
     * list, or the standard list); without planUuid the standard list.
     */
    @GetMapping("/voortgangstatussen")
    fun listVoortgangStatussen(
        @RequestParam(name = "planUuid", required = false) planUuid: UUID?
    ): List<VoortgangStatusOptie> = planOverlayService.voortgangStatussen(planUuid)

    @GetMapping("/doeldetails")
    fun listDoelDetails(@RequestParam(name = "planUuid") planUuid: UUID): List<DoelDetails> =
        planOverlayService.listDoelDetails(planUuid)

    @PutMapping("/doeldetails/{doelUuid}")
    fun upsertDoelDetails(
        @PathVariable(name = "doelUuid") doelUuid: UUID,
        @RequestBody request: DoelDetailsRequest
    ): DoelDetails = planOverlayService.upsertDoelDetails(doelUuid, request)

    // --------------------------------------------------- instrumentdetails

    @GetMapping("/instrumentdetails")
    fun listInstrumentDetails(@RequestParam(name = "planUuid") planUuid: UUID): List<InstrumentDetails> =
        planOverlayService.listInstrumentDetails(planUuid)

    @PutMapping("/instrumentdetails/{instrumentUuid}")
    fun upsertInstrumentDetails(
        @PathVariable(name = "instrumentUuid") instrumentUuid: UUID,
        @RequestBody request: InstrumentDetailsRequest
    ): InstrumentDetails = planOverlayService.upsertInstrumentDetails(instrumentUuid, request)

    // ------------------------------------------------ contactmomentdetails

    @GetMapping("/contactmomentdetails")
    fun listContactmomentDetails(@RequestParam(name = "planUuid") planUuid: UUID): List<ContactmomentDetails> =
        planOverlayService.listContactmomentDetails(planUuid)

    // ------------------------------------------------------ contactmomenten

    /** A contactmoment of a plan: the klantcontact in Open Klant plus its overlay row. */
    @PostMapping("/contactmomenten")
    fun createContactmoment(@Valid @RequestBody request: ContactmomentRequest): ResponseEntity<ContactmomentDetails> =
        ResponseEntity.status(HttpStatus.CREATED).body(contactmomentService.create(request))

    /** Reschedules a planned contactmoment; one that already took place cannot be moved (409). */
    @PatchMapping("/contactmomenten/{contactmomentUuid}")
    fun rescheduleContactmoment(
        @PathVariable(name = "contactmomentUuid") contactmomentUuid: UUID,
        @Valid @RequestBody request: ContactmomentDatumRequest
    ): ContactmomentDetails = contactmomentService.reschedule(contactmomentUuid, request.datum)
}
