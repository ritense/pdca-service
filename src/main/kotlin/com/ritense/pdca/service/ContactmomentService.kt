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

import com.ritense.pdca.domain.ContactmomentDetails
import com.ritense.pdca.repository.ContactmomentDetailsRepository
import com.ritense.pdca.web.rest.dto.ContactmomentRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

/**
 * Creating and rescheduling a contactmoment of a plan from the plan tabs:
 * the klantcontact in Open Klant ([KlantcontactService]) plus its overlay
 * row. Open Klant is written first; the overlay row is saved afterwards, so
 * no transaction spans the register call.
 */
@Service
class ContactmomentService(
    private val klantcontactService: KlantcontactService,
    private val contactmomentDetailsRepository: ContactmomentDetailsRepository,
    private val planOverlayService: PlanOverlayService
) {

    /** A new contactmoment (took place or planned) of a plan. */
    fun create(request: ContactmomentRequest): ContactmomentDetails {
        planOverlayService.validateEvaluatieType(request.planUuid, request.evaluatieType)
        val klantcontactUuid = klantcontactService.create(
            NewContactmoment(
                planUuid = request.planUuid,
                evaluatieType = request.evaluatieType,
                plaatsgevondenOp = plaatsgevondenOp(request.datum),
                afgerond = request.afgerond,
                verslag = request.verslag
            )
        )
        return contactmomentDetailsRepository.save(
            ContactmomentDetails(
                contactmomentUuid = klantcontactUuid,
                planUuid = request.planUuid,
                evaluatieType = request.evaluatieType,
                geplandeDatum = request.datum,
                deelnemers = request.deelnemers?.takeIf { it.isNotBlank() },
                actiepunten = request.actiepunten
            )
        )
    }

    /**
     * Reschedules a planned contactmoment: moves the klantcontact's
     * plaatsgevondenOp in Open Klant and the overlay's geplandeDatum. A
     * contactmoment that already took place cannot be moved (409).
     */
    fun reschedule(contactmomentUuid: UUID, datum: LocalDate): ContactmomentDetails {
        val klantcontact = klantcontactService.get(contactmomentUuid)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Contactmoment niet gevonden")
        if (!klantcontact.gepland) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "Dit contactmoment heeft al plaatsgevonden en kan niet meer worden verplaatst"
            )
        }
        val details = contactmomentDetailsRepository.findById(contactmomentUuid).orElse(null)?.apply {
            geplandeDatum = datum
            updatedAt = LocalDateTime.now()
        } ?: ContactmomentDetails(
            contactmomentUuid = contactmomentUuid,
            planUuid = klantcontact.planUuid
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Dit contactmoment hoort niet bij een plan"),
            evaluatieType = klantcontactService.evaluatieTypeOf(klantcontact.onderwerp) ?: DEFAULT_EVALUATIE_TYPE,
            geplandeDatum = datum
        )
        klantcontactService.reschedule(contactmomentUuid, plaatsgevondenOp(datum))
        return contactmomentDetailsRepository.save(details)
    }

    companion object {
        private const val DEFAULT_EVALUATIE_TYPE = "EVALUATION"

        /**
         * The one rule for the moment of a contactmoment on a date (planned
         * or taken place, from every route): noon, local time.
         */
        fun plaatsgevondenOp(datum: LocalDate): OffsetDateTime =
            datum.atTime(12, 0).atZone(ZoneId.systemDefault()).toOffsetDateTime()
    }
}
