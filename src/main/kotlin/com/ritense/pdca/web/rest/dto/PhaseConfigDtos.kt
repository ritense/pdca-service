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
 * PDCA case configuration: evaluation types, configurable plan display
 * statuses, the positietypen register (de positie per domein) —
 * all JSON arrays — plus two JSON objects: the subdoelmapping (hoofddoeltype
 * -> subdoeltypen that may be chosen under it) and the prefill mapping
 * (prefill-veld -> documentpad) that drives how the create-plan task form is
 * prefilled from the dossier document.
 */
data class CreatePhaseConfigRequest(
    val caseDefinitionKey: String,
    val evaluationTypes: String,
    val planStatussen: String? = null,
    val positieTypen: String? = null,
    val subdoelMapping: String? = null,
    val prefillMapping: String? = null,
    val planCaseDefinitionKey: String? = null
)

data class UpdatePhaseConfigRequest(
    val evaluationTypes: String? = null,
    val planStatussen: String? = null,
    val positieTypen: String? = null,
    val subdoelMapping: String? = null,
    val prefillMapping: String? = null,
    val planCaseDefinitionKey: String? = null
)

data class PhaseConfigResponse(
    val id: UUID,
    val caseDefinitionKey: String,
    val evaluationTypes: String,
    val planStatussen: String?,
    val positieTypen: String?,
    val subdoelMapping: String?,
    val prefillMapping: String?,
    val planCaseDefinitionKey: String?,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime
) {
    constructor(config: PhaseConfig) : this(
        id = config.id,
        caseDefinitionKey = config.caseDefinitionKey,
        evaluationTypes = config.evaluationTypes,
        planStatussen = config.planStatussen,
        positieTypen = config.positieTypen,
        subdoelMapping = config.subdoelMapping,
        prefillMapping = config.prefillMapping,
        planCaseDefinitionKey = config.planCaseDefinitionKey,
        createdAt = config.createdAt,
        updatedAt = config.updatedAt
    )
}
