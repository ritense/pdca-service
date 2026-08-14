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

import com.ritense.pdca.domain.Action
import com.ritense.pdca.domain.ActionStatus
import com.ritense.pdca.domain.AssigneeType
import com.ritense.pdca.domain.Priority
import com.ritense.pdca.repository.DoelDetailsRepository
import com.ritense.pdca.service.ActionService
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
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
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate
import java.util.UUID

/**
 * Local PDCA acties under Open Plan doelen. Open Plan does not model actions,
 * so this is a purely local resource keyed by doel uuid.
 */
@RestController
@RequestMapping("/api/v1/pdca/acties", produces = [MediaType.APPLICATION_JSON_VALUE])
class ActieResource(
    private val actionService: ActionService,
    private val doelDetailsRepository: DoelDetailsRepository
) {

    @GetMapping
    fun list(
        @RequestParam(name = "doelUuid", required = false) doelUuids: List<UUID>?,
        @RequestParam(name = "planUuid", required = false) planUuid: UUID?
    ): List<Action> = when {
        !doelUuids.isNullOrEmpty() -> actionService.findByDoelUuids(doelUuids)
        planUuid != null -> actionService.findByDoelUuids(
            doelDetailsRepository.findByPlanUuid(planUuid).map { it.doelUuid }
        )
        else -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "doelUuid or planUuid parameter is required")
    }

    @PostMapping
    fun create(@Valid @RequestBody request: CreateActieRequest): ResponseEntity<Action> {
        val action = actionService.create(
            Action(
                doelUuid = request.doelUuid,
                title = request.title,
                description = request.description,
                assigneeType = request.assigneeType,
                assigneeName = request.assigneeName,
                priority = request.priority ?: Priority.NORMAL,
                startDate = request.startDate,
                dueDate = request.dueDate,
                contactmomentUuid = request.contactmomentUuid
            )
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(action)
    }

    @PatchMapping("/{id}")
    fun update(
        @PathVariable(name = "id") id: UUID,
        @RequestBody request: UpdateActieRequest
    ): Action {
        val existing = actionService.getById(id)
        return actionService.update(
            id,
            existing.copy(
                title = request.title ?: existing.title,
                description = request.description ?: existing.description,
                status = request.status ?: existing.status,
                assigneeType = request.assigneeType ?: existing.assigneeType,
                assigneeName = request.assigneeName ?: existing.assigneeName,
                priority = request.priority ?: existing.priority,
                startDate = request.startDate ?: existing.startDate,
                dueDate = request.dueDate ?: existing.dueDate,
                completedDate = request.completedDate ?: existing.completedDate,
                result = request.result ?: existing.result
            )
        )
    }

    @DeleteMapping("/{id}")
    fun delete(@PathVariable(name = "id") id: UUID): ResponseEntity<Unit> {
        actionService.delete(id)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/{id}/approve")
    fun approve(@PathVariable(name = "id") id: UUID): Action = actionService.approve(id)

    @PostMapping("/{id}/reject")
    fun reject(@PathVariable(name = "id") id: UUID): Action = actionService.reject(id)
}

data class CreateActieRequest(
    val doelUuid: UUID,
    @field:NotBlank
    val title: String,
    val description: String? = null,
    val assigneeType: AssigneeType? = null,
    val assigneeName: String? = null,
    val priority: Priority? = null,
    val startDate: LocalDate? = null,
    val dueDate: LocalDate? = null,
    val contactmomentUuid: UUID? = null
)

data class UpdateActieRequest(
    val title: String? = null,
    val description: String? = null,
    val status: ActionStatus? = null,
    val assigneeType: AssigneeType? = null,
    val assigneeName: String? = null,
    val priority: Priority? = null,
    val startDate: LocalDate? = null,
    val dueDate: LocalDate? = null,
    val completedDate: LocalDate? = null,
    val result: String? = null
)
