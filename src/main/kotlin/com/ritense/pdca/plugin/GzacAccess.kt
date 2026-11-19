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
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException

/**
 * Which pushed configuration is the app's active one: the rule is explicit
 * and does not depend on push order.
 *
 *  - With `pdca.gzac.configuration-id` set, that configuration (once GZAC
 *    has pushed it with a serviceToken and gzacBaseUrl).
 *  - Without it, the one configuration with GZAC access; several are
 *    ambiguous and nothing is used until the property names one.
 */
object ActiveConfigurationSelector {

    sealed interface Selection {
        data class Selected(val configuration: PluginConfiguration) : Selection
        data object NonePushed : Selection
        data class ConfiguredNotPushed(val configurationId: String) : Selection
        data class Ambiguous(val configurationIds: List<String>) : Selection
    }

    fun select(configurations: Collection<PluginConfiguration>, configuredId: String?): Selection {
        val withAccess = configurations.filter { it.serviceToken.isNotBlank() && it.gzacBaseUrl.isNotBlank() }
        val wanted = configuredId?.trim()?.takeIf { it.isNotEmpty() }
        if (wanted != null) {
            return withAccess.firstOrNull { it.configId == wanted }?.let { Selection.Selected(it) }
                ?: Selection.ConfiguredNotPushed(wanted)
        }
        return when (withAccess.size) {
            0 -> Selection.NonePushed
            1 -> Selection.Selected(withAccess.single())
            else -> Selection.Ambiguous(withAccess.map { it.configId }.sorted())
        }
    }
}

/**
 * GZAC access through the active pushed configuration
 * ([ActiveConfigurationSelector]): its serviceToken plus the gzacBaseUrl it
 * came with. The pushed URL is the host's server-to-server callback URL
 * entered in GZAC; an explicitly configured `pdca.gzac.base-url` (GZAC_URL,
 * no default) overrides it for setups where the app sees GZAC under a
 * different address, e.g. the compose container reaching a GZAC on the host
 * via host.docker.internal.
 */
@Component
class GzacAccess(
    private val configurationStore: ConfigurationStore,
    private val properties: PdcaProperties
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile
    private var reportedAmbiguity: List<String>? = null

    data class Access(val baseUrl: String, val serviceToken: String, val configuration: PluginConfiguration)

    sealed interface Resolution {
        data class Available(val access: Access) : Resolution

        /** No usable configuration; [reason] is the Dutch message for the user, [nonePushed] when GZAC pushed none. */
        data class Unavailable(val reason: String, val nonePushed: Boolean) : Resolution
    }

    fun resolve(): Resolution =
        when (val selection = ActiveConfigurationSelector.select(configurationStore.getAll().values, properties.gzac.configurationId)) {
            is ActiveConfigurationSelector.Selection.Selected -> {
                reportedAmbiguity = null
                val configuration = selection.configuration
                Resolution.Available(
                    Access(configuredBaseUrl() ?: configuration.gzacBaseUrl.trimEnd('/'), configuration.serviceToken, configuration)
                )
            }
            ActiveConfigurationSelector.Selection.NonePushed -> Resolution.Unavailable(
                "Geen GZAC-toegang: GZAC heeft nog geen PDCA-pluginconfiguratie gepusht; activeer PDCA in GZAC",
                nonePushed = true
            )
            is ActiveConfigurationSelector.Selection.ConfiguredNotPushed -> {
                log.warn(
                    "pdca.gzac.configuration-id names {}, but GZAC has not pushed that configuration with a serviceToken",
                    selection.configurationId
                )
                Resolution.Unavailable(
                    "Geen GZAC-toegang: de ingestelde PDCA-configuratie is (nog) niet door GZAC gepusht",
                    nonePushed = false
                )
            }
            is ActiveConfigurationSelector.Selection.Ambiguous -> {
                if (reportedAmbiguity != selection.configurationIds) {
                    reportedAmbiguity = selection.configurationIds
                    log.error(
                        "GZAC pushed several PDCA configurations with access ({}); none is used until " +
                            "pdca.gzac.configuration-id (PDCA_GZAC_CONFIGURATION_ID) names one",
                        selection.configurationIds.joinToString()
                    )
                }
                Resolution.Unavailable(
                    "Er zijn meerdere PDCA-configuraties in GZAC; stel in welke de app gebruikt " +
                        "(PDCA_GZAC_CONFIGURATION_ID)",
                    nonePushed = false
                )
            }
        }

    /** The active access, or a 503 with the reason. */
    fun active(): Access = when (val resolution = resolve()) {
        is Resolution.Available -> resolution.access
        is Resolution.Unavailable -> throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, resolution.reason)
    }

    fun configuredBaseUrl(): String? = properties.gzac.baseUrl?.trimEnd('/')?.takeIf { it.isNotBlank() }
}
