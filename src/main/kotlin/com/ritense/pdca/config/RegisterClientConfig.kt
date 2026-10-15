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

import com.ritense.pdca.registers.OpenPlanContractRequestFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.web.client.RestClient

/**
 * RestClient beans for the Maykin registers. Open Product and Open Klant are
 * called directly with token authentication (`Authorization: Token <key>`).
 * Open Plan depends on `pdca.openplan.mode`: `contract` (default) sends every
 * request through GZAC's `nl.ritense.openplan@1` contract
 * ([OpenPlanContractRequestFactory]), `direct` calls Open Plan with its own
 * base URL and token.
 */
@Configuration
@EnableConfigurationProperties(PdcaProperties::class)
class RegisterClientConfig {

    @Bean
    fun openPlanRestClient(
        builder: RestClient.Builder,
        properties: PdcaProperties,
        contractRequestFactory: OpenPlanContractRequestFactory
    ): RestClient {
        val openplan = properties.openplan
        return when (openplan.mode) {
            PdcaProperties.OpenPlanMode.CONTRACT -> builder.clone()
                .baseUrl(openplan.clientBaseUrl)
                .requestFactory(contractRequestFactory)
                .build()
            PdcaProperties.OpenPlanMode.DIRECT -> {
                check(!openplan.baseUrl.isNullOrBlank() && !openplan.token.isNullOrBlank()) {
                    "pdca.openplan.mode=direct requires pdca.openplan.base-url and pdca.openplan.token " +
                        "(OPENPLAN_URL / OPENPLAN_TOKEN)"
                }
                registerRestClient(builder, PdcaProperties.RegisterApi(openplan.clientBaseUrl, openplan.token))
            }
        }
    }

    @Bean
    fun openProductRestClient(builder: RestClient.Builder, properties: PdcaProperties): RestClient =
        registerRestClient(builder, properties.openproduct)

    @Bean
    fun openKlantRestClient(builder: RestClient.Builder, properties: PdcaProperties): RestClient =
        registerRestClient(builder, properties.openklant)

    private fun registerRestClient(builder: RestClient.Builder, api: PdcaProperties.RegisterApi): RestClient =
        builder.clone()
            .baseUrl(api.baseUrl)
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Token ${api.token}")
            .build()
}
