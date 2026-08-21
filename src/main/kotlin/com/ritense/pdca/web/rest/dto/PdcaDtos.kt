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

import com.ritense.pdca.domain.UitvoeringsStatus
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import java.time.LocalDate
import java.util.UUID

/** Upsert requests for the PDCA overlay; null fields leave existing values unchanged. */

data class PlanDetailsRequest(
    val persoonUuid: UUID? = null,
    val caseDefinitionKey: String? = null,
    val weergaveStatus: String? = null,
    val beginPositie: String? = null,
    val doelPositie: String? = null,
    val hoofddoelTypeUuid: UUID? = null,
    val streefEinddatum: LocalDate? = null
)

data class DoelDetailsRequest(
    val planUuid: UUID? = null,
    val uitvoeringsStatus: UitvoeringsStatus? = null,
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

data class InstrumentDetailsRequest(
    val planUuid: UUID? = null,
    val urenBesteed: Int? = null,
    @field:Min(1)
    @field:Max(5)
    val effectiviteitScore: Int? = null,
    val effectiviteitToelichting: String? = null,
    val afbreekReden: String? = null
)
