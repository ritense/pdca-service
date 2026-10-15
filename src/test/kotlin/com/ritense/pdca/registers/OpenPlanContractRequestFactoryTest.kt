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

package com.ritense.pdca.registers

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.ritense.pdca.config.PdcaProperties
import com.ritense.pdca.domain.StoredPluginConfiguration
import com.ritense.pdca.plugin.ConfigurationStore
import com.ritense.pdca.plugin.PushedGzacAccess
import com.ritense.pdca.repository.StoredPluginConfigurationRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.ExpectedCount.never
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.net.URI
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenPlanContractRequestFactoryTest {

    private val objectMapper = jacksonObjectMapper()
    private val repository = mock(StoredPluginConfigurationRepository::class.java)
    private lateinit var gzac: MockRestServiceServer
    private lateinit var openPlan: RestClient

    private val callUrl = "http://gzac:8080/api/v1/plugin-contract/call"
    private val planUuid = "11111111-1111-1111-1111-111111111111"

    @BeforeEach
    fun setUp() {
        val properties = PdcaProperties(
            openproduct = PdcaProperties.RegisterApi("http://openproduct", "t"),
            openklant = PdcaProperties.RegisterApi("http://openklant", "t"),
            gzac = PdcaProperties.GzacApi()
        )
        val access = PushedGzacAccess(ConfigurationStore(repository, objectMapper), properties)
        val gzacBuilder = RestClient.builder()
        gzac = MockRestServiceServer.bindTo(gzacBuilder).build()
        val factory = OpenPlanContractRequestFactory(access, objectMapper, gzacBuilder)
        openPlan = RestClient.builder()
            .baseUrl(PdcaProperties.OpenPlanApi.CONTRACT_BASE_URL)
            .requestFactory(factory)
            .build()
        pushed(grantedContracts = listOf("nl.ritense.openplan@1"))
    }

    @Test
    fun `list carries resource and query and reproduces the register answer`() {
        expectCall(
            """{"contract":"nl.ritense.openplan@1","operation":"list",
               "payload":{"resource":"doel","query":{"plannen__uuid":"$planUuid","page":"1"}}}""",
            completed(200, """{"count":1,"next":null,"results":[{"uuid":"$planUuid"}]}""")
        )

        val (status, body) = send(HttpMethod.GET, "/plannen/api/v0/doel?plannen__uuid=$planUuid&page=1")

        assertEquals(200, status)
        assertEquals("""{"count":1,"next":null,"results":[{"uuid":"$planUuid"}]}""", body)
        gzac.verify()
    }

    @Test
    fun `an absolute next link maps onto the same list call`() {
        expectCall(
            """{"operation":"list","payload":{"resource":"plan","query":{"page":"2","naam":"a b+c"}}}""",
            completed(200, """{"count":0,"results":[]}""")
        )

        val response = openPlan.get()
            .uri(URI.create("http://host.docker.internal:7501/plannen/api/v0/plan?page=2&naam=a+b%2Bc"))
            .retrieve()
            .body(Map::class.java)

        assertEquals(0, response?.get("count"))
        gzac.verify()
    }

    @Test
    fun `get, update, replace and delete map onto their operations`() {
        expectCall("""{"operation":"get","payload":{"resource":"plan","uuid":"$planUuid"}}""", completed(200, "{}"))
        expectCall(
            """{"operation":"update","payload":{"resource":"doel","uuid":"$planUuid","body":{"status":"afgerond"}}}""",
            completed(200, "{}")
        )
        expectCall(
            """{"operation":"update","payload":{"resource":"plan","uuid":"$planUuid","body":{"titel":"x"},"replace":true}}""",
            completed(200, "{}")
        )
        expectCall("""{"operation":"delete","payload":{"resource":"instrument","uuid":"$planUuid"}}""", completed(204, "null"))

        send(HttpMethod.GET, "/plannen/api/v0/plan/$planUuid")
        send(HttpMethod.PATCH, "/plannen/api/v0/doel/$planUuid", """{"status":"afgerond"}""")
        send(HttpMethod.PUT, "/plannen/api/v0/plan/$planUuid", """{"titel":"x"}""")
        val (status, body) = send(HttpMethod.DELETE, "/plannen/api/v0/instrument/$planUuid")

        assertEquals(204, status)
        assertEquals("", body)
        gzac.verify()
    }

    @Test
    fun `create sends the body and the call carries the pushed service token`() {
        gzac.expect(requestTo(callUrl))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer service-token"))
            .andExpect(content().json("""{"operation":"create","payload":{"resource":"plan","body":{"titel":"Plan"}}}"""))
            .andRespond(withSuccess(completed(201, """{"uuid":"$planUuid"}"""), MediaType.APPLICATION_JSON))

        val (status, body) = send(HttpMethod.POST, "/plannen/api/v0/plan", """{"titel":"Plan"}""")

        assertEquals(201, status)
        assertEquals("""{"uuid":"$planUuid"}""", body)
        gzac.verify()
    }

    @Test
    fun `a register validation error keeps its status and body`() {
        val error = """{"code":"invalid","invalidParams":[{"name":"titel","reason":"Dit veld is vereist."}]}"""
        expectCall("""{"operation":"create"}""", completed(400, error))

        val (status, body) = send(HttpMethod.POST, "/plannen/api/v0/plan", "{}")

        assertEquals(400, status)
        assertEquals(error, body)
    }

    @Test
    fun `an unbound contract is a 503 with a detail`() {
        expectCall(
            """{"operation":"list"}""",
            """{"status":"error","errorCode":"NO_PROVIDER_BOUND","errorMessage":"No provider is bound"}"""
        )

        val (status, body) = send(HttpMethod.GET, "/plannen/api/v0/plan")

        assertEquals(503, status)
        assertTrue(detail(body).contains("NO_PROVIDER_BOUND"))
    }

    @Test
    fun `an unreachable provider is a 503 and a provider error a 502`() {
        expectCall("""{"operation":"list"}""", """{"status":"error","errorCode":"PROVIDER_UNREACHABLE","errorMessage":"down"}""")
        expectCall(
            """{"operation":"list"}""",
            """{"status":"error","errorCode":"PROVIDER_ERROR","errorMessage":"[OPENPLAN_UNREACHABLE] Open Plan is unreachable"}"""
        )

        val unreachable = send(HttpMethod.GET, "/plannen/api/v0/plan")
        val providerError = send(HttpMethod.GET, "/plannen/api/v0/plan")

        assertEquals(503, unreachable.first)
        assertEquals(502, providerError.first)
        assertTrue(detail(providerError.second).contains("OPENPLAN_UNREACHABLE"))
    }

    @Test
    fun `a refused service token is a 503`() {
        gzac.expect(requestTo(callUrl)).andRespond(withStatus(HttpStatus.UNAUTHORIZED))

        val (status, body) = send(HttpMethod.GET, "/plannen/api/v0/plan")

        assertEquals(503, status)
        assertTrue(detail(body).contains("HTTP 401"))
    }

    @Test
    fun `without a pushed configuration nothing is sent and the answer explains it`() {
        `when`(repository.findAll()).thenReturn(emptyList())
        gzac.expect(never(), requestTo(callUrl))

        val (status, body) = send(HttpMethod.GET, "/plannen/api/v0/plan")

        assertEquals(503, status)
        assertEquals("Open Plan is niet gekoppeld: activeer PDCA in GZAC en koppel de Open Plan plugin", detail(body))
        gzac.verify()
    }

    @Test
    fun `a configuration without the granted contract is not used`() {
        pushed(grantedContracts = emptyList())
        gzac.expect(never(), requestTo(callUrl))

        val (status, body) = send(HttpMethod.GET, "/plannen/api/v0/plan")

        assertEquals(503, status)
        assertTrue(detail(body).startsWith("Open Plan is niet gekoppeld: koppel in GZAC"))
        gzac.verify()
    }

    @Test
    fun `requests outside the contract are refused locally`() {
        gzac.expect(never(), requestTo(callUrl))

        assertEquals(400, send(HttpMethod.POST, "/plannen/api/v0/plan/$planUuid", "{}").first)
        assertEquals(400, send(HttpMethod.PATCH, "/plannen/api/v0/plan", "{}").first)
        assertEquals(400, send(HttpMethod.GET, "/plannen/api/v0/plan/$planUuid/doelen").first)
        assertEquals(400, send(HttpMethod.GET, "/producttypen/api/v1/producttypen").first)
        assertEquals(400, send(HttpMethod.HEAD, "/plannen/api/v0/plan").first)
        gzac.verify()
    }

    private fun pushed(grantedContracts: List<String>) {
        `when`(repository.findAll()).thenReturn(
            listOf(
                StoredPluginConfiguration(
                    configId = "pdca-config",
                    properties = "{}",
                    serviceToken = "service-token",
                    gzacBaseUrl = "http://gzac:8080/",
                    grantedContracts = objectMapper.writeValueAsString(grantedContracts),
                    updatedAt = LocalDateTime.now()
                )
            )
        )
    }

    private fun expectCall(expectedJson: String, responseJson: String) {
        gzac.expect(requestTo(callUrl))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().json(expectedJson))
            .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON))
    }

    private fun completed(status: Int, body: String) =
        """{"status":"completed","result":{"status":$status,"body":$body}}"""

    private fun send(method: HttpMethod, path: String, body: String? = null): Pair<Int, String> =
        openPlan.method(method)
            .uri(path)
            .apply { if (body != null) contentType(MediaType.APPLICATION_JSON).body(body) }
            .exchange { _, response -> response.statusCode.value() to String(response.body.readAllBytes()) }!!

    private fun detail(body: String): String = objectMapper.readTree(body).path("detail").asText()
}
