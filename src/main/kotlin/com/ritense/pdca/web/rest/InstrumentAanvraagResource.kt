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

import com.ritense.pdca.service.IngeplandInstrumentService
import com.ritense.pdca.service.InstrumentAanvraagService
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.util.UUID

/**
 * Requesting a product for a doel from the plan page (ARCHITECTURE §6.2):
 * today on a plan past concept the verzoek is filed (202; the instrument
 * appears once GZAC's instrument dossier has created it), otherwise the
 * product is ingepland (201) and requested on its startdatum. The
 * ingeplande instrumenten are listed, moved, requested now or removed here.
 */
@RestController
@RequestMapping("/api/v1/pdca/instrumenten", produces = [MediaType.APPLICATION_JSON_VALUE])
class InstrumentAanvraagResource(
    private val instrumentAanvraagService: InstrumentAanvraagService,
    private val ingeplandInstrumentService: IngeplandInstrumentService
) {

    data class StartRequest(
        @field:NotNull(message = "Kies het doel") val doelUuid: UUID,
        @field:NotNull(message = "Kies het product") val producttypeUuid: UUID,
        /** The planned start of the instrument; today when absent. */
        val startdatum: LocalDate? = null
    )

    data class StartdatumRequest(@field:NotNull(message = "Kies de startdatum") val startdatum: LocalDate)

    /**
     * The open verzoeken of a plan: filed, but their instrument has not
     * appeared yet (oldest first, with how long they have been open).
     */
    @GetMapping("/verzoeken")
    fun openVerzoeken(@RequestParam(name = "planUuid") planUuid: UUID): List<InstrumentAanvraagService.OpenVerzoek> =
        instrumentAanvraagService.openVerzoeken(planUuid)

    /**
     * Requests a product from a startdatum: 202 with the verzoek when it is
     * filed now, 201 with the ingepland instrument otherwise. 409 when doel
     * or plan no longer runs, a verzoek for the same doel and product is
     * open or the product is ingepland under the doel already.
     */
    @PostMapping("/start")
    fun start(@Valid @RequestBody request: StartRequest): ResponseEntity<IngeplandInstrumentService.Aanvraag> {
        val aanvraag = ingeplandInstrumentService.vraagAan(request.doelUuid, request.producttypeUuid, request.startdatum)
        return ResponseEntity.status(if (aanvraag.verzoek != null) HttpStatus.ACCEPTED else HttpStatus.CREATED).body(aanvraag)
    }

    /** The plan's ingeplande instrumenten, earliest startdatum first. */
    @GetMapping("/ingepland")
    fun ingepland(@RequestParam(name = "planUuid") planUuid: UUID): List<IngeplandInstrumentService.Ingepland> =
        ingeplandInstrumentService.vanPlan(planUuid)

    /** Moves an ingepland instrument to another startdatum (not in the past); clears an earlier refusal. */
    @PatchMapping("/ingepland/{id}")
    fun wijzigStartdatum(@PathVariable id: UUID, @Valid @RequestBody request: StartdatumRequest): IngeplandInstrumentService.Ingepland =
        ingeplandInstrumentService.wijzigStartdatum(id, request.startdatum)

    /** Requests an ingepland instrument now (202); 409 while the plan is in concept. */
    @PostMapping("/ingepland/{id}/aanvragen")
    fun vraagNuAan(@PathVariable id: UUID): ResponseEntity<IngeplandInstrumentService.Aanvraag> =
        ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(IngeplandInstrumentService.Aanvraag(verzoek = ingeplandInstrumentService.vraagNuAan(id)))

    /** Removes an ingepland instrument. */
    @DeleteMapping("/ingepland/{id}")
    fun verwijder(@PathVariable id: UUID): ResponseEntity<Void> {
        ingeplandInstrumentService.verwijder(id)
        return ResponseEntity.noContent().build()
    }
}
