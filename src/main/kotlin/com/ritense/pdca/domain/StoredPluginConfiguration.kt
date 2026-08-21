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

/**
 * Plugin configuration pushed by GZAC (URL-app contract), persisted so the
 * serviceToken + gzacBaseUrl survive an app restart. The serviceToken is what
 * lets this app call GZAC back, e.g. to create a dossier for a plan.
 */
@Entity
@Table(name = "plugin_configuration")
data class StoredPluginConfiguration(
    @Id
    @Column(name = "config_id")
    val configId: String,

    /** JSON object: the configured plugin properties. */
    @Column(columnDefinition = "TEXT", nullable = false)
    var properties: String,

    @Column(name = "service_token", columnDefinition = "TEXT")
    var serviceToken: String? = null,

    @Column(name = "gzac_base_url")
    var gzacBaseUrl: String? = null,

    /** JSON array of subscribed event types. */
    @Column(name = "event_subscriptions", columnDefinition = "TEXT", nullable = false)
    var eventSubscriptions: String = "[]",

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now()
)
