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

package com.ritense.pdca.plugin

import com.ritense.pdca.config.PdcaProperties
import org.springframework.stereotype.Component

/**
 * GZAC access through a pushed plugin configuration: its serviceToken plus
 * the gzacBaseUrl it came with. The pushed URL is the host's
 * server-to-server callback URL entered in GZAC; an explicitly configured
 * `pdca.gzac.base-url` (GZAC_URL, no default) overrides it for setups where
 * the app sees GZAC under a different address, e.g. the compose container
 * reaching a GZAC on the host via host.docker.internal.
 */
@Component
class PushedGzacAccess(
    private val configurationStore: ConfigurationStore,
    private val properties: PdcaProperties
) {

    data class Access(val baseUrl: String, val serviceToken: String, val configuration: PluginConfiguration)

    /** Access through the most recently pushed configuration matching [filter], or null when there is none. */
    fun latest(filter: (PluginConfiguration) -> Boolean = { true }): Access? =
        configurationStore.latestWithGzacAccess(filter)?.let {
            Access(configuredBaseUrl() ?: it.gzacBaseUrl.trimEnd('/'), it.serviceToken, it)
        }

    fun configuredBaseUrl(): String? = properties.gzac?.baseUrl?.trimEnd('/')?.takeIf { it.isNotBlank() }
}
