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
import com.ritense.pdca.plugin.GzacAccess
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

class RegisterContractRequestFactoryTest {

    private val objectMapper = jacksonObjectMapper()
    private val repository = mock(StoredPluginConfigurationRepository::class.java)
    private lateinit var gzac: MockRestServiceServer
    private lateinit var openPlan: RestClient
    private lateinit var openKlant: RestClient

    private val callUrl = "http://gzac:8080/api/v1/plugin-contract/call"
    private val planUuid = "11111111-1111-1111-1111-111111111111"

    @BeforeEach
    fun setUp() {
        val access = GzacAccess(ConfigurationStore(repository, objectMapper), PdcaProperties())
        val gzacBuilder = RestClient.builder()
        gzac = MockRestServiceServer.bindTo(gzacBuilder).build()
        openPlan = contractClient(Register.OPENPLAN, access, gzacBuilder)
        openKlant = contractClient(Register.OPENKLANT, access, gzacBuilder)
        pushed(grantedContracts = Register.entries.map { it.contract })
    }

    @Test
    fun `list carries resource and query and reproduces the register answer`() {
        expectCall(
            """{"contract":"nl.ritense.openplan@2","operation":"list",
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
    fun `get, update and delete map onto their operations`() {
        expectCall("""{"operation":"get","payload":{"resource":"plan","uuid":"$planUuid"}}""", completed(200, "{}"))
        expectCall(
            """{"operation":"update","payload":{"resource":"doel","uuid":"$planUuid","body":{"status":"afgerond"}}}""",
            completed(200, "{}")
        )
        expectCall("""{"operation":"delete","payload":{"resource":"instrument","uuid":"$planUuid"}}""", completed(204, "null"))

        send(HttpMethod.GET, "/plannen/api/v0/plan/$planUuid")
        send(HttpMethod.PATCH, "/plannen/api/v0/doel/$planUuid", """{"status":"afgerond"}""")
        val (status, body) = send(HttpMethod.DELETE, "/plannen/api/v0/instrument/$planUuid")

        assertEquals(204, status)
        assertEquals("", body)
        gzac.verify()
    }

    @Test
    fun `a PUT has no contract operation and never reaches GZAC`() {
        val (status, body) = send(HttpMethod.PUT, "/plannen/api/v0/plan/$planUuid", """{"titel":"x"}""")

        assertEquals(400, status)
        assertTrue(detail(body).contains("wordt niet ondersteund"))
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
        assertEquals("De Open Plan plugin meldt een fout (PROVIDER_ERROR)", detail(providerError.second))
    }

    @Test
    fun `a call GZAC refuses as too many concurrent calls is retried after a pause`() {
        expectCall("""{"operation":"create"}""", TOO_MANY)
        expectCall("""{"operation":"create"}""", completed(201, """{"uuid":"$planUuid"}"""))

        val (status, body) = send(HttpMethod.POST, "/plannen/api/v0/plan", """{"titel":"Plan"}""")

        assertEquals(201, status)
        assertTrue(body.contains(planUuid))
        assertEquals(1, pauses.size)
        gzac.verify()
    }

    @Test
    fun `too many concurrent contract calls after the last retry is a 503 marked as a contract error`() {
        repeat(3) { expectCall("""{"operation":"list"}""", TOO_MANY) }

        val response = openPlan.get().uri("/plannen/api/v0/plan")
            .exchange { _, r -> Triple(r.statusCode.value(), r.headers.getFirst(RegisterErrors.CONTRACT_ERROR_HEADER), String(r.body.readAllBytes())) }!!

        assertEquals(503, response.first)
        assertEquals("TOO_MANY_CONCURRENT_CALLS", response.second)
        assertTrue(detail(response.third).contains("TOO_MANY_CONCURRENT_CALLS"))
        assertTrue(!response.third.contains("8 calls in progress"))
        assertEquals(2, pauses.size)
        assertTrue(pauses[1] >= java.time.Duration.ofMillis(400), "the pause grows with every retry")
        gzac.verify()
    }

    @Test
    fun `the limiter lets no more calls run at once than configured`() {
        val limiter = ContractCallLimiter(
            PdcaProperties.Registers(contractConcurrency = 2, contractPermitTimeout = java.time.Duration.ofMillis(50))
        )
        val running = java.util.concurrent.atomic.AtomicInteger()
        val maxRunning = java.util.concurrent.atomic.AtomicInteger()
        val busy = java.util.concurrent.atomic.AtomicInteger()
        val release = java.util.concurrent.CountDownLatch(1)
        val threads = (1..4).map {
            Thread {
                try {
                    limiter.withPermit {
                        maxRunning.accumulateAndGet(running.incrementAndGet(), ::maxOf)
                        release.await(1, java.util.concurrent.TimeUnit.SECONDS)
                        running.decrementAndGet()
                    }
                } catch (e: ContractCallLimiter.Busy) {
                    busy.incrementAndGet()
                }
            }.apply { start() }
        }
        Thread.sleep(300)
        release.countDown()
        threads.forEach { it.join() }

        assertEquals(2, maxRunning.get())
        assertEquals(2, busy.get())
    }

    @Test
    fun `several configurations without a configured id are not used`() {
        `when`(repository.findAll()).thenReturn(listOf(stored("config-a"), stored("config-b")))
        gzac.expect(never(), requestTo(callUrl))

        val (status, body) = send(HttpMethod.GET, "/plannen/api/v0/plan")

        assertEquals(503, status)
        assertTrue(detail(body).startsWith("Er zijn meerdere PDCA-configuraties"))
        gzac.verify()
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
    fun `another register uses its own contract, api path and kebab-case resources`() {
        expectCall(
            """{"contract":"nl.ritense.openklant@2","operation":"create",
               "payload":{"resource":"maak-klantcontact","body":{"klantcontact":{"kanaal":"gesprek"}}}}""",
            completed(201, """{"klantcontact":{"uuid":"$planUuid"}}""")
        )

        val (status, body) = send(
            HttpMethod.POST, "/klantinteracties/api/v1/maak-klantcontact", """{"klantcontact":{"kanaal":"gesprek"}}""", openKlant
        )

        assertEquals(201, status)
        assertEquals("""{"klantcontact":{"uuid":"$planUuid"}}""", body)
        gzac.verify()
    }

    @Test
    fun `a binding is checked per register`() {
        pushed(grantedContracts = listOf("nl.ritense.openplan@2"))
        gzac.expect(never(), requestTo(callUrl))

        val (status, body) = send(HttpMethod.GET, "/klantinteracties/api/v1/klantcontacten", client = openKlant)

        assertEquals(503, status)
        assertTrue(detail(body).startsWith("Open Klant is niet gekoppeld: koppel in GZAC"))
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
        assertEquals(400, send(HttpMethod.GET, "/plannen/api/v0/plan", client = openKlant).first)
        gzac.verify()
    }

    private fun pushed(grantedContracts: List<String>) {
        `when`(repository.findAll()).thenReturn(listOf(stored("pdca-config", grantedContracts)))
    }

    private fun stored(configId: String, grantedContracts: List<String> = Register.entries.map { it.contract }) =
        StoredPluginConfiguration(
            configId = configId,
            title = "PDCA Planbeheer",
            properties = "{}",
            serviceToken = "service-token",
            gzacBaseUrl = "http://gzac:8080/",
            grantedContracts = objectMapper.writeValueAsString(grantedContracts),
            updatedAt = LocalDateTime.now()
        )

    private fun expectCall(expectedJson: String, responseJson: String) {
        gzac.expect(requestTo(callUrl))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().json(expectedJson))
            .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON))
    }

    private fun completed(status: Int, body: String) =
        """{"status":"completed","result":{"status":$status,"body":$body}}"""

    /** Retries without real pauses; the pauses are recorded instead. */
    private val pauses = mutableListOf<java.time.Duration>()
    private val limiter = ContractCallLimiter(PdcaProperties.Registers(contractRetries = 2)) { pauses += it }

    private fun contractClient(register: Register, access: GzacAccess, gzacBuilder: RestClient.Builder) =
        RestClient.builder()
            .baseUrl(register.contractBaseUrl)
            .requestFactory(RegisterContractRequestFactory(register, access, objectMapper, gzacBuilder, limiter))
            .build()

    private fun send(method: HttpMethod, path: String, body: String? = null, client: RestClient = openPlan): Pair<Int, String> =
        client.method(method)
            .uri(path)
            .apply { if (body != null) contentType(MediaType.APPLICATION_JSON).body(body) }
            .exchange { _, response -> response.statusCode.value() to String(response.body.readAllBytes()) }!!

    private fun detail(body: String): String = objectMapper.readTree(body).path("detail").asText()

    private companion object {
        const val TOO_MANY = """{"status":"error","errorCode":"TOO_MANY_CONCURRENT_CALLS","errorMessage":"8 calls in progress"}"""
    }
}
