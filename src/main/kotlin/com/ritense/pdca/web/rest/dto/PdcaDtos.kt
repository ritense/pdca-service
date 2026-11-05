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
import java.time.LocalDate
import java.util.Optional
import java.util.UUID

/*
 * Partial updates of the PDCA overlay rows (PUT /plandetails, /doeldetails,
 * /instrumentdetails). Every editable field is an
 * `Optional?`: absent from the JSON (null) keeps the stored value, an
 * explicit JSON null (Optional.empty) clears it, a value sets it ([patch]).
 * `planUuid` only counts for a new row and is checked against the register
 * object the row belongs to.
 */

/** The new value of a field after a partial update: [current] when the field was absent. */
fun <T> Optional<T>?.patch(current: T?): T? = if (this == null) current else orElse(null)

/** Edits of an existing plan's overlay row; the row itself is created by the intake (ARCHITECTURE §6.1). */
data class PlanDetailsRequest(
    val weergaveStatus: Optional<String>? = null,
    val beginPositie: Optional<String>? = null,
    val subdoelgroep: Optional<String>? = null,
    val streefEinddatum: Optional<LocalDate>? = null
)

data class DoelDetailsRequest(
    val planUuid: UUID? = null,
    val uitvoeringsStatus: UitvoeringsStatus? = null,
    val voortgangToelichting: Optional<String>? = null,
    /** Code of one of the plan's voortgangsstatussen; only for a doel that is actief in Open Plan. */
    val voortgangStatus: Optional<String>? = null,
    val interneNotitie: Optional<String>? = null,
    val externeNotitie: Optional<String>? = null,
    val sortering: Int? = null
)

/**
 * A new contactmoment of a plan: the klantcontact in Open Klant (datum,
 * afgerond, verslag) plus its overlay row (the other fields).
 */
data class ContactmomentRequest(
    val planUuid: UUID,
    val datum: LocalDate,
    val evaluatieType: String,
    val afgerond: Boolean = true,
    val verslag: String? = null,
    val deelnemers: String? = null,
    val actiepunten: String? = null
)

/** Reschedules a planned contactmoment to [datum]. */
data class ContactmomentDatumRequest(
    val datum: LocalDate
)

data class InstrumentDetailsRequest(
    val planUuid: UUID? = null,
    val urenBesteed: Optional<Int>? = null,
    /** 1 (no effect) through 5 (very effective). */
    val effectiviteitScore: Optional<Int>? = null,
    val effectiviteitToelichting: Optional<String>? = null,
    val interneNotitie: Optional<String>? = null,
    val externeNotitie: Optional<String>? = null
)
