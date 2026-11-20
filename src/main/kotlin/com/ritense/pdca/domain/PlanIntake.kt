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
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime
import java.util.UUID

/**
 * The creation of a plan from an intake dossier (`POST /plan-intake`,
 * ARCHITECTURE §6.1), keyed by that intake dossier, so one intake creates at
 * most one plan. The row is reserved before anything is written to a
 * register and stays [PlanIntakeStatus.IN_AANMAAK] until every step is
 * done; a retry resumes the missing steps.
 *
 * The request working on it holds a claim: a random [claimToken] with a
 * lease until [claimedUntil], renewed between the steps. A second request
 * while the lease runs gets a 409 instead of building the same plan twice.
 * Every change of the row is one conditional UPDATE in
 * `PlanIntakeRepository` (claim, renew, record the plan, complete, release,
 * reopen), the writes of a holder on its token, so a request whose lease ran
 * out and was taken over can no longer change the row. The fields are
 * read-only here: the row is never saved as a whole.
 */
@Entity
@Table(name = "plan_intake")
data class PlanIntake(
    @Id
    @Column(name = "intake_dossier_id")
    val intakeDossierId: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    val status: PlanIntakeStatus = PlanIntakeStatus.IN_AANMAAK,

    /** The plan in Open Plan, once created. */
    @Column(name = "plan_uuid")
    val planUuid: UUID? = null,

    /** The claim of the request working on it; null when none does. */
    @Column(name = "claim_token")
    val claimToken: String? = null,

    /** Until when that request holds it; null when none does. */
    @Column(name = "claimed_until")
    val claimedUntil: LocalDateTime? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    val updatedAt: LocalDateTime = LocalDateTime.now()
)

/** IN_AANMAAK while steps are missing (also after a failed attempt); GEREED once plan, plan dossier and verzoeken are done. */
enum class PlanIntakeStatus { IN_AANMAAK, GEREED }
