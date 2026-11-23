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

package com.ritense.pdca.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * PDCA workflow item under a doel in Open Plan. Open Plan does not model
 * actions; they exist only in this app. [doelUuid] references an Open Plan
 * doel, [contactmomentUuid] the contactmoment (evaluation) it originated from.
 * A new actie becomes a task in GZAC ([taakProcessInstanceId], ARCHITECTURE
 * §6.8), which then owns its status and the texts it shows.
 */
@Entity
@Table(name = "action")
data class Action(
    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(name = "doel_uuid", nullable = false)
    val doelUuid: UUID,

    /** Optional instrument this taak/opdracht supports (W&P: instrument -> 0..n taken). */
    @Column(name = "instrument_uuid")
    var instrumentUuid: UUID? = null,

    @Column(nullable = false)
    var title: String,

    @Column(columnDefinition = "TEXT")
    var description: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: ActionStatus = ActionStatus.PLANNED,

    @Column(name = "assignee_name")
    var assigneeName: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var priority: Priority = Priority.NORMAL,

    @Column(name = "start_date")
    var startDate: LocalDate? = null,

    @Column(name = "due_date")
    var dueDate: LocalDate? = null,

    @Column(name = "contactmoment_uuid")
    var contactmomentUuid: UUID? = null,

    @Column(name = "completed_date")
    var completedDate: LocalDate? = null,

    @Column(columnDefinition = "TEXT")
    var result: String? = null,

    /**
     * The GZAC process (building block pdca-taak on the plan dossier) whose
     * task carries this actie; null for an actie from before acties became
     * tasks. Recorded by that process itself (actie-taak-gestart).
     */
    @Column(name = "taak_process_instance_id", unique = true)
    var taakProcessInstanceId: String? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now()
)

/**
 * PLANNED → IN_PROGRESS → PENDING_REVIEW → COMPLETED/REJECTED for an actie
 * without task; an actie with a GZAC task goes from PLANNED to COMPLETED
 * (uitgevoerd) or CANCELLED (vervallen) when its task is done.
 */
enum class ActionStatus {
    PLANNED, IN_PROGRESS, PENDING_REVIEW, COMPLETED, REJECTED, CANCELLED;

    /** Whether the actie has ended and its status no longer changes. */
    val isAfgesloten: Boolean get() = this == COMPLETED || this == REJECTED || this == CANCELLED
}

/** Whether this actie's status belongs to its GZAC task. */
val Action.isTaakBeheerd: Boolean get() = taakProcessInstanceId != null

/** Whether this actie's GZAC task is still open. */
val Action.heeftOpenTaak: Boolean get() = isTaakBeheerd && !status.isAfgesloten
enum class Priority { HIGH, NORMAL, LOW }
