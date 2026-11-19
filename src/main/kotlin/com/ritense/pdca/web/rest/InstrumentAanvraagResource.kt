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

package com.ritense.pdca.web.rest

import com.ritense.pdca.service.InstrumentAanvraagService
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Requesting a product for a doel from the plan page: files the verzoek
 * (ARCHITECTURE §6.2). The answer is 202: the instrument appears in the plan
 * once GZAC's instrument dossier has created it.
 */
@RestController
@RequestMapping("/api/v1/pdca/instrumenten", produces = [MediaType.APPLICATION_JSON_VALUE])
class InstrumentAanvraagResource(
    private val instrumentAanvraagService: InstrumentAanvraagService
) {

    data class StartRequest(
        @field:NotNull(message = "Kies het doel") val doelUuid: UUID,
        @field:NotNull(message = "Kies het product") val producttypeUuid: UUID
    )

    /**
     * The open verzoeken of a plan: filed, but their instrument has not
     * appeared yet (oldest first, with how long they have been open).
     */
    @GetMapping("/verzoeken")
    fun openVerzoeken(@RequestParam(name = "planUuid") planUuid: UUID): List<InstrumentAanvraagService.OpenVerzoek> =
        instrumentAanvraagService.openVerzoeken(planUuid)

    /** Files a verzoek; 409 while one for the same doel and product is open, or when doel or plan no longer runs. */
    @PostMapping("/start")
    fun start(@Valid @RequestBody request: StartRequest): ResponseEntity<InstrumentAanvraagService.Verzoek> =
        ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(instrumentAanvraagService.start(request.doelUuid, request.producttypeUuid))
}
