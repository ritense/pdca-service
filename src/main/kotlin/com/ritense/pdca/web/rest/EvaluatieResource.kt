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

import com.ritense.pdca.plugin.EvaluationChangeDto
import com.ritense.pdca.plugin.EvaluationSessionDto
import com.ritense.pdca.plugin.toDto
import com.ritense.pdca.service.EvaluationSessionService
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class CompletedEvaluationDto(
    val sessie: EvaluationSessionDto,
    val wijzigingen: List<EvaluationChangeDto>
)

/**
 * Completed evaluations of a plan with the plan changes made during them, for
 * the Evaluaties tab. Running evaluations are never returned here: they are
 * only visible to their owner through the plugin `/data` route.
 */
@RestController
@RequestMapping("/api/v1/pdca", produces = [MediaType.APPLICATION_JSON_VALUE])
class EvaluatieResource(
    private val evaluationSessionService: EvaluationSessionService
) {

    @GetMapping("/evaluaties")
    fun listCompleted(@RequestParam(name = "planUuid") planUuid: UUID): List<CompletedEvaluationDto> =
        evaluationSessionService.completed(planUuid).map { (session, changes) ->
            CompletedEvaluationDto(session.toDto(), changes.map { it.toDto() })
        }
}
