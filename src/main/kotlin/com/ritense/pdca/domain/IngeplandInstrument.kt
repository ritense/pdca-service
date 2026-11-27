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

package com.ritense.pdca.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * An ingepland instrument (ARCHITECTURE §6.2): a product chosen for a
 * subdoel that is not requested yet. There is no verzoek, dossier or Open
 * Plan instrument behind it; once [startdatum] has come and the plan is past
 * concept, the verzoek is filed and the row is deleted. One per doel and
 * product ([productUrn], the key verzoeken and instruments carry too).
 */
@Entity
@Table(name = "ingepland_instrument")
data class IngeplandInstrument(
    @Id
    @Column(name = "id")
    val id: UUID = UUID.randomUUID(),

    @Column(name = "plan_uuid", nullable = false)
    val planUuid: UUID,

    @Column(name = "doel_uuid", nullable = false)
    val doelUuid: UUID,

    @Column(name = "producttype_uuid", nullable = false)
    val producttypeUuid: UUID,

    @Column(name = "product_urn", nullable = false)
    val productUrn: String,

    /** The day from which it is requested (the planned start of the instrument). */
    @Column(name = "startdatum", nullable = false)
    var startdatum: LocalDate,

    /** Why the registers refused the last request; the job leaves the row alone until it is retried. */
    @Column(name = "fout", length = 1000)
    var fout: String? = null,

    /** Lease of the request that is filing it; null when nobody is. */
    @Column(name = "geclaimd_tot")
    var geclaimdTot: LocalDateTime? = null,

    @Column(name = "aangemaakt_op", nullable = false, updatable = false)
    val aangemaaktOp: LocalDateTime = LocalDateTime.now()
)
