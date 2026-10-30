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

import com.fasterxml.jackson.databind.ObjectMapper
import com.ritense.pdca.plugin.PushedGzacAccess
import com.ritense.pdca.registers.Register
import com.ritense.pdca.registers.RegisterContractRequestFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient

/**
 * RestClient beans for the Maykin registers. The app holds no register URL or
 * token: every request goes through the register's GZAC contract
 * ([RegisterContractRequestFactory]), served by the bound register plugin.
 */
@Configuration
@EnableConfigurationProperties(PdcaProperties::class)
class RegisterClientConfig(
    private val pushedGzacAccess: PushedGzacAccess,
    private val objectMapper: ObjectMapper
) {

    @Bean
    fun openPlanRestClient(builder: RestClient.Builder): RestClient = registerRestClient(builder, Register.OPENPLAN)

    @Bean
    fun openProductRestClient(builder: RestClient.Builder): RestClient = registerRestClient(builder, Register.OPENPRODUCT)

    @Bean
    fun openKlantRestClient(builder: RestClient.Builder): RestClient = registerRestClient(builder, Register.OPENKLANT)

    @Bean
    fun objectenRestClient(builder: RestClient.Builder): RestClient = registerRestClient(builder, Register.OBJECTEN)

    private fun registerRestClient(builder: RestClient.Builder, register: Register): RestClient =
        builder.clone()
            .baseUrl(register.contractBaseUrl)
            .requestFactory(RegisterContractRequestFactory(register, pushedGzacAccess, objectMapper, builder))
            .build()
}
