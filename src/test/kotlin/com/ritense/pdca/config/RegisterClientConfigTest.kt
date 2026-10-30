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

package com.ritense.pdca.config

import com.ritense.pdca.plugin.ConfigurationStore
import com.ritense.pdca.plugin.PushedGzacAccess
import com.ritense.pdca.repository.StoredPluginConfigurationRepository
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.web.client.RestClient
import kotlin.test.assertEquals

class RegisterClientConfigTest {

    private val runner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                JacksonAutoConfiguration::class.java,
                HttpMessageConvertersAutoConfiguration::class.java,
                RestClientAutoConfiguration::class.java
            )
        )
        .withUserConfiguration(RegisterClientConfig::class.java)
        .withBean(StoredPluginConfigurationRepository::class.java, { mock(StoredPluginConfigurationRepository::class.java) })
        .withBean(ConfigurationStore::class.java)
        .withBean(PushedGzacAccess::class.java)

    @Test
    fun `every register goes through its contract`() {
        runner.run { context ->
            mapOf(
                "openPlanRestClient" to "/plannen/api/v0/plan",
                "openProductRestClient" to "/producttypen/api/v1/producttypen",
                "openKlantRestClient" to "/klantinteracties/api/v1/klantcontacten",
                "objectenRestClient" to "/api/v2/objects"
            ).forEach { (bean, path) ->
                val client = context.getBean(bean, RestClient::class.java)

                val status = client.get().uri(path).exchange { _, response -> response.statusCode.value() }

                assertEquals(503, status, bean)
            }
        }
    }
}
