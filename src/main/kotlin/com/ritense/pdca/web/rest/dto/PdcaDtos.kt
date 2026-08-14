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

import java.time.LocalDate
import java.util.UUID

/** Upsert requests for the PDCA overlay; null fields leave existing values unchanged. */

data class PlanDetailsRequest(
    val persoonUuid: UUID? = null,
    val caseDefinitionKey: String? = null,
    val startSituatie: String? = null,
    val gewensteSituatie: String? = null,
    val streefEinddatum: LocalDate? = null
)

data class DoelDetailsRequest(
    val planUuid: UUID? = null,
    val fase: String? = null,
    val voortgangScore: Int? = null,
    val voortgangToelichting: String? = null,
    val sortering: Int? = null
)

data class ContactmomentDetailsRequest(
    val planUuid: UUID? = null,
    val evaluatieType: String? = null,
    val geplandeDatum: LocalDate? = null,
    val deelnemers: String? = null,
    val doelVoortgang: String? = null,
    val actiepunten: String? = null
)
