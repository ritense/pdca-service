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

package com.ritense.pdca.web.rest.dto

import com.ritense.pdca.domain.PhaseConfig
import java.time.LocalDateTime
import java.util.UUID

/**
 * PDCA case configuration: optional ordering of doelcategorieën (fasering),
 * evaluation types, configurable plan display statuses and the positietypen
 * register (begin-/doelposities per domein) — all JSON arrays.
 */
data class CreatePhaseConfigRequest(
    val caseDefinitionKey: String,
    val categorieOrdening: String,
    val evaluationTypes: String,
    val planStatussen: String? = null,
    val positieTypen: String? = null
)

data class UpdatePhaseConfigRequest(
    val categorieOrdening: String? = null,
    val evaluationTypes: String? = null,
    val planStatussen: String? = null,
    val positieTypen: String? = null
)

data class PhaseConfigResponse(
    val id: UUID,
    val caseDefinitionKey: String,
    val categorieOrdening: String,
    val evaluationTypes: String,
    val planStatussen: String?,
    val positieTypen: String?,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime
) {
    constructor(config: PhaseConfig) : this(
        id = config.id,
        caseDefinitionKey = config.caseDefinitionKey,
        categorieOrdening = config.categorieOrdening,
        evaluationTypes = config.evaluationTypes,
        planStatussen = config.planStatussen,
        positieTypen = config.positieTypen,
        createdAt = config.createdAt,
        updatedAt = config.updatedAt
    )
}
