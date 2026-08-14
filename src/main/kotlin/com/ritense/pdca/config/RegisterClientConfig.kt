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

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.web.client.RestClient

/**
 * RestClient beans for the Maykin registers. Both APIs use DRF token
 * authentication: `Authorization: Token <key>`.
 */
@Configuration
@EnableConfigurationProperties(PdcaProperties::class)
class RegisterClientConfig {

    @Bean
    fun openPlanRestClient(builder: RestClient.Builder, properties: PdcaProperties): RestClient =
        registerRestClient(builder, properties.openplan)

    @Bean
    fun openProductRestClient(builder: RestClient.Builder, properties: PdcaProperties): RestClient =
        registerRestClient(builder, properties.openproduct)

    private fun registerRestClient(builder: RestClient.Builder, api: PdcaProperties.RegisterApi): RestClient =
        builder.clone()
            .baseUrl(api.baseUrl)
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Token ${api.token}")
            .build()
}
