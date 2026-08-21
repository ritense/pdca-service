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
    val openplan: RegisterApi,
    val openproduct: RegisterApi,
    val gzac: GzacApi? = null
) {
    data class RegisterApi(
        val baseUrl: String,
        val token: String
    )

    /**
     * Fallback for calling GZAC itself (dossier aanmaken vanuit de PDCA-app).
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
