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

package com.ritense.pdca.it

import com.fasterxml.jackson.databind.ObjectMapper
import com.ritense.pdca.domain.DoelDetails
import com.ritense.pdca.domain.PlanDetails
import com.ritense.pdca.plugin.ConfigurationStore
import com.ritense.pdca.repository.DoelDetailsRepository
import com.ritense.pdca.repository.PlanDetailsRepository
import com.ritense.pdca.repository.StoredPluginConfigurationRepository
import com.ritense.pdca.security.HostSignatureVerifier
import com.ritense.pdca.security.HostSignatureVerifierTest
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.JdbcTemplate
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The trust boundary in the complete application against PostgreSQL: the
 * configuration GZAC pushes is persisted (token encrypted) and then used to
 * reach GZAC, and a `/data` call is verified at GZAC and authorized against
 * the overlay before it is forwarded to the real handler. GZAC is an
 * in-process HTTP server; what it answers depends on the user token.
 * `TrustBoundaryTest` covers the boundary's cases in detail with the
 * persistence and GZAC mocked.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TrustBoundaryIntegrationTest : PostgresIntegrationTest() {

    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var configurationStore: ConfigurationStore
    @Autowired private lateinit var storedConfigurations: StoredPluginConfigurationRepository
    @Autowired private lateinit var planDetailsRepository: PlanDetailsRepository
    @Autowired private lateinit var doelDetailsRepository: DoelDetailsRepository
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    private val http = HttpClient.newHttpClient()
    private val gzac = FakeGzac()
    private val configId = "00000000-0000-4000-8000-0000000017e5"
    private val serviceToken = "service-token-${UUID.randomUUID()}"

    @BeforeAll
    fun startGzac() = gzac.start()

    @AfterAll
    fun stopGzac() {
        storedConfigurations.deleteById(configId)
        gzac.stop()
    }

    @BeforeEach
    fun pushConfiguration() {
        val body = objectMapper.writeValueAsString(
            mapOf("serviceToken" to serviceToken, "gzacBaseUrl" to gzac.baseUrl, "title" to "PDCA", "properties" to emptyMap<String, Any>())
        )
        val path = "/api/host/configurations/$configId"
        val response = send("POST", path, body, signedHeaders("POST", path, body))
        assertEquals(200, response.statusCode(), response.body())
    }

    // ------------------------------------------------------------ host calls

    @Test
    fun `a signed push is persisted with the token encrypted, an unsigned one is refused`() {
        val stored = configurationStore.get(configId)!!
        assertEquals(serviceToken, stored.serviceToken)
        assertEquals(gzac.baseUrl, stored.gzacBaseUrl)
        val column = jdbcTemplate.queryForObject("SELECT service_token FROM plugin_configuration WHERE config_id = ?", String::class.java, configId)
        assertNotEquals(serviceToken, column)
        assertFalse(column!!.contains(serviceToken))

        val other = "00000000-0000-4000-8000-0000000017e6"
        val body = """{"serviceToken":"t","gzacBaseUrl":"${gzac.baseUrl}","title":"Evil"}"""
        assertEquals(401, send("POST", "/api/host/configurations/$other", body).statusCode())
        assertNull(configurationStore.get(other))
    }

    @Test
    fun `the API cannot be called directly`() {
        assertEquals(401, send("GET", "/api/v1/pdca/doeldetails?planUuid=${UUID.randomUUID()}").statusCode())
        assertEquals(401, send("GET", "/api/v1/admin/phase-configs").statusCode())
        assertEquals(401, send("GET", "/api/host/configurations").statusCode())
    }

    // ------------------------------------------------------------ /data

    @Test
    fun `data reaches the overlay for a user who can read the plan dossier`() {
        val plan = seedPlan()
        val token = gzac.user("jan", dossiers = setOf(plan.dossierId!!))

        val response = data(token, "GET", "/api/v1/pdca/doeldetails", query = mapOf("planUuid" to plan.planUuid.toString()))

        assertEquals(200, response.statusCode(), response.body())
        val doelen = objectMapper.readValue(response.body(), List::class.java).map { (it as Map<*, *>)["doelUuid"] }
        assertEquals(doelDetailsRepository.findByPlanUuid(plan.planUuid).sortedBy { it.sortering }.map { it.doelUuid.toString() }, doelen)
        assertTrue(gzac.received("GET /api/v1/external-plugin/user-token/introspect", token))
        assertTrue(gzac.received("GET /api/v1/document/${plan.dossierId}", token))
    }

    @Test
    fun `data refuses a user GZAC denies the plan dossier, an unknown token and a call outside the policy`() {
        val plan = seedPlan()
        val outsider = gzac.user("klaas", dossiers = emptySet())
        val query = mapOf("planUuid" to plan.planUuid.toString())

        assertEquals(403, data(outsider, "GET", "/api/v1/pdca/doeldetails", query = query).statusCode())
        assertTrue(gzac.received("GET /api/v1/document/${plan.dossierId}", outsider))

        val unknown = "token-${UUID.randomUUID()}"
        assertEquals(401, data(unknown, "GET", "/api/v1/pdca/doeldetails", query = query).statusCode())
        assertTrue(gzac.received("GET /api/v1/external-plugin/user-token/introspect", unknown))

        val insider = gzac.user("jan", dossiers = setOf(plan.dossierId!!))
        assertEquals(403, data(insider, "GET", "/api/v1/pdca/doeldetails", query = query + ("bsn" to "1")).statusCode())
        assertEquals(403, data(insider, "GET", "/api/host/configurations").statusCode())
        assertEquals(403, data(insider, "GET", "/api/v1/pdca/doeldetails", query = mapOf("planUuid" to UUID.randomUUID().toString())).statusCode())
    }

    @Test
    fun `case configuration management needs the GZAC admin role`() {
        val admin = gzac.user("beheerder", admin = true)
        val medewerker = gzac.user("medewerker")

        val list = data(admin, "GET", "/api/v1/admin/phase-configs")
        assertEquals(200, list.statusCode(), list.body())
        val keys = objectMapper.readValue(list.body(), List::class.java).map { (it as Map<*, *>)["caseDefinitionKey"] }
        assertEquals(jdbcTemplate.queryForList("SELECT case_definition_key FROM phase_config", String::class.java).sorted(), keys.map { it as String }.sorted())
        assertTrue(gzac.received("GET /api/management/v1/document-definition", admin))

        assertEquals(403, data(medewerker, "GET", "/api/v1/admin/phase-configs").statusCode())
    }

    // ------------------------------------------------------------ helpers

    /** A plan in the overlay with its plan dossier and two doelen. */
    private fun seedPlan(): PlanDetails {
        val plan = planDetailsRepository.save(
            PlanDetails(planUuid = UUID.randomUUID(), persoonUuid = UUID.randomUUID(), dossierId = "dossier-${UUID.randomUUID()}")
        )
        repeat(2) { doelDetailsRepository.save(DoelDetails(doelUuid = UUID.randomUUID(), planUuid = plan.planUuid, sortering = it)) }
        return plan
    }

    private fun data(token: String, method: String, path: String, query: Map<String, String>? = null) =
        send(
            "POST", "/plugins/pdca/0.2.0/data",
            objectMapper.writeValueAsString(
                mapOf(
                    "configurationId" to configId, "method" to method, "path" to path, "query" to query,
                    "context" to emptyMap<String, Any>(), "userToken" to token
                )
            )
        )

    private fun signedHeaders(method: String, path: String, body: String, timestamp: String = Instant.now().toString()) = mapOf(
        HostSignatureVerifier.SIGNATURE_HEADER to HostSignatureVerifierTest.gzacSignature(method, path, timestamp, body.toByteArray(), HOST_SECRET),
        HostSignatureVerifier.TIMESTAMP_HEADER to timestamp
    )

    private fun send(method: String, path: String, body: String? = null, headers: Map<String, String> = emptyMap()): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create("http://localhost:$port$path"))
            .method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
        if (body != null) builder.header("Content-Type", "application/json")
        headers.forEach { (name, value) -> builder.header(name, value) }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    /**
     * GZAC as far as the app asks it about a user: the token introspection,
     * reading a dossier as the user and the admin-only management list. It
     * knows the tokens handed out by [user] and records every request.
     */
    private inner class FakeGzac {
        private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        private val users = ConcurrentHashMap<String, User>()
        private val requests = Collections.synchronizedList(mutableListOf<Pair<String, String?>>())

        private inner class User(val login: String, val dossiers: Set<String>, val admin: Boolean)

        val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

        fun start() {
            server.createContext("/") { exchange ->
                try {
                    handle(exchange)
                } finally {
                    exchange.close()
                }
            }
            server.start()
        }

        fun stop() = server.stop(0)

        /** A fresh user token GZAC knows as [login] of the configuration under test. */
        fun user(login: String, dossiers: Set<String> = emptySet(), admin: Boolean = false): String =
            "token-${UUID.randomUUID()}".also { users[it] = User(login, dossiers, admin) }

        /** Did GZAC get "[request]" (method and path) with [token] as bearer? */
        fun received(request: String, token: String): Boolean = synchronized(requests) {
            requests.any { it.first == request && it.second == "Bearer $token" }
        }

        private fun handle(exchange: HttpExchange) {
            val path = exchange.requestURI.path
            val authorization = exchange.requestHeaders.getFirst("Authorization")
            requests += "${exchange.requestMethod} $path" to authorization
            val user = authorization?.removePrefix("Bearer ")?.let { users[it] }
            when {
                user == null -> respond(exchange, 401)
                path == "/api/v1/external-plugin/user-token/introspect" -> respond(
                    exchange, 200,
                    mapOf("subject" to user.login, "configurationId" to configId, "expiresAt" to Instant.now().plusSeconds(600).toString())
                )
                path.startsWith("/api/v1/document/") ->
                    if (path.removePrefix("/api/v1/document/") in user.dossiers) respond(exchange, 200, mapOf("id" to path.substringAfterLast('/'))) else respond(exchange, 403)
                path == "/api/management/v1/document-definition" ->
                    if (user.admin) respond(exchange, 200, mapOf("content" to emptyList<Any>(), "last" to true)) else respond(exchange, 403)
                else -> respond(exchange, 404)
            }
        }

        private fun respond(exchange: HttpExchange, status: Int, body: Any? = null) {
            val bytes = body?.let { objectMapper.writeValueAsBytes(it) }
            if (bytes == null) {
                exchange.sendResponseHeaders(status, -1)
            } else {
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
    }
}
