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
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime
import java.util.UUID

/**
 * Local contact card for a plan in Open Plan. [role] holds the naam of an
 * Open Plan relatietype (see /api/v1/pdca/bootstrap).
 */
@Entity
@Table(name = "involved_party")
data class InvolvedParty(
    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(name = "plan_uuid", nullable = false)
    val planUuid: UUID,

    @Column(nullable = false)
    var name: String,

    @Column(nullable = false)
    var role: String,

    @Column
    var email: String? = null,

    @Column
    var phone: String? = null,

    @Column
    var organization: String? = null,

    @Column(name = "is_primary", nullable = false)
    var isPrimary: Boolean = false,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now()
)
