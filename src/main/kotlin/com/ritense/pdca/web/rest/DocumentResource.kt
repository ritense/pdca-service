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

import com.ritense.pdca.domain.DocumentAanvraag
import com.ritense.pdca.domain.DocumentAanvraagStatus
import com.ritense.pdca.domain.DocumentSoort
import com.ritense.pdca.repository.DocumentAanvraagSummary
import com.ritense.pdca.security.CurrentUser
import com.ritense.pdca.service.DocumentAanvraagService
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDateTime
import java.util.Base64
import java.util.UUID

data class DocumentAanvraagDto(
    val id: UUID,
    val planUuid: UUID,
    val soort: DocumentSoort,
    val bronUuid: UUID,
    val status: DocumentAanvraagStatus,
    val aangevraagdOp: LocalDateTime,
    val bijgewerktOp: LocalDateTime,
    val bestandsnaam: String?,
    val foutmelding: String?
)

/** A finished document; [inhoud] is base64. */
data class DocumentInhoudDto(
    val bestandsnaam: String?,
    val mediatype: String?,
    val inhoud: String
)

/** Whether documents can be made, so the bundles only offer what works. */
data class DocumentInstellingenDto(val actief: Boolean)

/**
 * The documents GZAC's document process makes for a plan, such as the
 * evaluatieverslag of a completed evaluation: the listing (the latest
 * request per document, polled while one is being made), a finished
 * document, making one again, and whether documents can be made at all.
 */
@RestController
@RequestMapping("/api/v1/pdca/documenten", produces = [MediaType.APPLICATION_JSON_VALUE])
class DocumentResource(
    private val documentAanvraagService: DocumentAanvraagService
) {

    data class AanvraagRequest(
        @field:NotNull(message = "Kies het soort document") val soort: DocumentSoort?,
        @field:NotNull(message = "Kies waar het document over gaat") val bronUuid: UUID?
    )

    @GetMapping
    fun list(@RequestParam(name = "planUuid") planUuid: UUID): List<DocumentAanvraagDto> =
        documentAanvraagService.list(planUuid).map { it.toDto() }

    @GetMapping("/instellingen")
    fun settings(): DocumentInstellingenDto = DocumentInstellingenDto(actief = documentAanvraagService.enabled())

    /** 404 until the document is GEREED. */
    @GetMapping("/{id}/inhoud")
    fun content(@PathVariable id: UUID): DocumentInhoudDto =
        documentAanvraagService.content(id).let {
            DocumentInhoudDto(it.bestandsnaam, it.mediatype, Base64.getEncoder().encodeToString(it.inhoud))
        }

    /**
     * Makes the document again from the current data; for an
     * evaluatieverslag [AanvraagRequest.bronUuid] is the completed
     * evaluation. While the document is being made, that request is
     * returned; a process that could not be started shows as MISLUKT; 422
     * when document generation is off.
     */
    @PostMapping
    fun request(@Valid @RequestBody request: AanvraagRequest): DocumentAanvraagDto =
        when (request.soort!!) {
            DocumentSoort.EVALUATIEVERSLAG ->
                documentAanvraagService.requestEvaluatieverslag(request.bronUuid!!, CurrentUser.require().login)
        }.toSummary().toDto()
}

fun DocumentAanvraag.toSummary() = DocumentAanvraagSummary(
    id, planUuid, soort, bronUuid, status, aangevraagdOp, bijgewerktOp, bestandsnaam, foutmelding
)

fun DocumentAanvraagSummary.toDto() = DocumentAanvraagDto(
    id = id,
    planUuid = planUuid,
    soort = soort,
    bronUuid = bronUuid,
    status = status,
    aangevraagdOp = aangevraagdOp,
    bijgewerktOp = bijgewerktOp,
    bestandsnaam = bestandsnaam,
    foutmelding = foutmelding
)
