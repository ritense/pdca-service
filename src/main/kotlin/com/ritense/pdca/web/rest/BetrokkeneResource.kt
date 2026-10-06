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

import com.ritense.pdca.domain.InvolvedParty
import com.ritense.pdca.repository.InvolvedPartyRepository
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Local contact cards per plan. The role values come from the Open Plan
 * relatietype register (see /api/v1/pdca/bootstrap).
 */
@RestController
@RequestMapping("/api/v1/pdca/betrokkenen", produces = [MediaType.APPLICATION_JSON_VALUE])
class BetrokkeneResource(
    private val involvedPartyRepository: InvolvedPartyRepository
) {

    @GetMapping
    fun list(@RequestParam(name = "planUuid") planUuid: UUID): List<InvolvedParty> =
        involvedPartyRepository.findByPlanUuid(planUuid)

    @PostMapping
    fun create(@Valid @RequestBody request: CreateBetrokkeneRequest): ResponseEntity<InvolvedParty> {
        val party = involvedPartyRepository.save(
            InvolvedParty(
                planUuid = request.planUuid,
                name = request.name,
                role = request.role,
                email = request.email,
                phone = request.phone,
                organization = request.organization,
                isPrimary = request.isPrimary ?: false
            )
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(party)
    }

    @DeleteMapping("/{id}")
    fun delete(@PathVariable(name = "id") id: UUID): ResponseEntity<Unit> {
        involvedPartyRepository.deleteById(id)
        return ResponseEntity.noContent().build()
    }
}

data class CreateBetrokkeneRequest(
    val planUuid: UUID,
    @field:NotBlank
    val name: String,
    @field:NotBlank
    val role: String,
    val email: String? = null,
    val phone: String? = null,
    val organization: String? = null,
    val isPrimary: Boolean? = null
)
