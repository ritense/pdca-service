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

package com.ritense.pdca.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "pdca")
data class PdcaProperties(
    val openplan: OpenPlanApi = OpenPlanApi(),
    val openproduct: RegisterApi,
    val openklant: RegisterApi,
    val gzac: GzacApi? = null
) {
    data class RegisterApi(
        val baseUrl: String,
        val token: String
    )

    /**
     * Open Plan is reached through GZAC's `nl.ritense.openplan@1` contract
     * ([OpenPlanMode.CONTRACT]) or directly with [baseUrl] + [token]
     * ([OpenPlanMode.DIRECT]); the latter two are only used in direct mode.
     */
    data class OpenPlanApi(
        val mode: OpenPlanMode = OpenPlanMode.CONTRACT,
        val baseUrl: String? = null,
        val token: String? = null
    ) {
        /** Base URL of `openPlanRestClient`; a fixed placeholder in contract mode, where only path and query count. */
        val clientBaseUrl: String
            get() = when (mode) {
                OpenPlanMode.CONTRACT -> CONTRACT_BASE_URL
                OpenPlanMode.DIRECT -> baseUrl?.trimEnd('/')
                    ?: error("pdca.openplan.base-url is required with pdca.openplan.mode=direct")
            }

        companion object {
            const val CONTRACT_BASE_URL = "http://openplan.contract"
        }
    }

    enum class OpenPlanMode { CONTRACT, DIRECT }

    /**
     * Fallback for calling GZAC itself (creating a dossier from the PDCA app).
     * The preferred source is the serviceToken + gzacBaseUrl that GZAC pushes
     * with the plugin configuration; these properties are the dev fallback:
     * either a static bearer token, or Keycloak client credentials.
     */
    data class GzacApi(
        val baseUrl: String? = null,
        val staticToken: String? = null,
        val tokenUrl: String? = null,
        val clientId: String? = null,
        val clientSecret: String? = null
    )
}
