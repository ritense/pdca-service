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

package com.ritense.pdca.security

import com.fasterxml.jackson.databind.ObjectMapper
import com.ritense.pdca.config.PdcaProperties
import com.ritense.pdca.config.SecurityConfig
import com.ritense.pdca.domain.DoelDetails
import com.ritense.pdca.domain.PlanDetails
import com.ritense.pdca.plugin.ConfigurationStore
import com.ritense.pdca.plugin.GzacAccess
import com.ritense.pdca.plugin.PluginActionController
import com.ritense.pdca.plugin.PluginConfiguration
import com.ritense.pdca.plugin.PluginDataController
import com.ritense.pdca.plugin.PluginHostController
import com.ritense.pdca.registers.RegisterProxyController
import com.ritense.pdca.registers.openplan.OpenPlanGateway
import com.ritense.pdca.repository.ActionRepository
import com.ritense.pdca.repository.ContactmomentDetailsRepository
import com.ritense.pdca.repository.DoelDetailsRepository
import com.ritense.pdca.repository.InstrumentDetailsRepository
import com.ritense.pdca.repository.InvolvedPartyRepository
import com.ritense.pdca.repository.PlanDetailsRepository
import com.ritense.pdca.service.GzacClient
import com.ritense.pdca.service.InstrumentActionService
import com.ritense.pdca.web.rest.ErrorResponseAdvice
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration
import org.springframework.boot.autoconfigure.liquibase.LiquibaseAutoConfiguration
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.client.RestClient
import org.springframework.web.server.ResponseStatusException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.util.Optional
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The trust boundary end to end in a running servlet container (the `/data`
 * dispatch is a real forward): signed host calls, the `/data` entry with
 * user verification and the access policy, the closed direct API, CORS and
 * framing. GZAC, the database and the registers are mocked.
 */
@SpringBootTest(
    classes = [TrustBoundaryTest.TestApp::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "pdca.host.secret=test-host-secret-0123456789",
        "pdca.gzac.frontend-origins=http://gzac.test:4200",
        "pdca.gzac.allowed-base-urls=http://gzac.test:8080",
        "spring.main.banner-mode=off"
    ]
)
class TrustBoundaryTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration(
        exclude = [
            DataSourceAutoConfiguration::class, DataSourceTransactionManagerAutoConfiguration::class,
            HibernateJpaAutoConfiguration::class, JpaRepositoriesAutoConfiguration::class,
            LiquibaseAutoConfiguration::class, UserDetailsServiceAutoConfiguration::class
        ]
    )
    @EnableConfigurationProperties(PdcaProperties::class)
    @Import(
        SecurityConfig::class, GzacInstances::class, UserVerifier::class, DataAccessPolicy::class, PlanScope::class, DataRateLimiter::class,
        PluginDataController::class, PluginHostController::class, PluginActionController::class,
        RegisterProxyController::class, ErrorResponseAdvice::class, TestHandlers::class
    )
    class TestApp

    /** Stand-ins for the app's handlers on real API paths: they show who and what reached them. */
    @RestController
    class TestHandlers {
        @GetMapping("/api/v1/pdca/plannen/{uuid}/afsluitcheck")
        fun afsluitcheck(@PathVariable uuid: UUID): Map<String, Any> = mapOf("login" to CurrentUser.require().login, "plan" to uuid)

        @GetMapping("/api/v1/pdca/acties")
        fun acties(@RequestParam planUuid: UUID, request: HttpServletRequest): Map<String, Any?> =
            mapOf("planUuid" to planUuid, "method" to request.method)

        @PostMapping("/api/v1/admin/phase-configs")
        fun createPhaseConfig(@RequestBody body: Map<String, Any?>): Map<String, Any?> =
            body + ("login" to CurrentUser.require().login)

        @GetMapping("/api/v1/pdca/dossiers/{id}/plan")
        fun dossierPlan(@PathVariable id: String): Nothing = throw ResponseStatusException(HttpStatus.NOT_FOUND, "Geen plan bij dit dossier")
    }

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @MockitoBean private lateinit var gzacClient: GzacClient
    @MockitoBean private lateinit var gzacAccess: GzacAccess
    @MockitoBean private lateinit var configurationStore: ConfigurationStore
    @MockitoBean private lateinit var instrumentActionService: InstrumentActionService
    @MockitoBean private lateinit var planDetailsRepository: PlanDetailsRepository
    @MockitoBean private lateinit var doelDetailsRepository: DoelDetailsRepository
    @MockitoBean private lateinit var instrumentDetailsRepository: InstrumentDetailsRepository
    @MockitoBean private lateinit var contactmomentDetailsRepository: ContactmomentDetailsRepository
    @MockitoBean private lateinit var actionRepository: ActionRepository
    @MockitoBean private lateinit var involvedPartyRepository: InvolvedPartyRepository
    @MockitoBean private lateinit var openPlanGateway: OpenPlanGateway
    @MockitoBean(name = "openPlanRestClient") private lateinit var openPlanRestClient: RestClient
    @MockitoBean(name = "openProductRestClient") private lateinit var openProductRestClient: RestClient
    @MockitoBean(name = "openKlantRestClient") private lateinit var openKlantRestClient: RestClient

    private val http = HttpClient.newHttpClient()
    private val placeholder = PluginConfiguration("x", "x", emptyMap(), "", "")
    private val configId = "00000000-0000-4000-8000-00000000dca0"
    private val planUuid = UUID.randomUUID()

    @BeforeEach
    fun activeConfiguration() {
        val configuration = PluginConfiguration(configId, "PDCA", emptyMap(), "service-token", "http://gzac.test:8080")
        `when`(gzacAccess.resolve()).thenReturn(
            GzacAccess.Resolution.Available(GzacAccess.Access("http://gzac.test:8080", "service-token", configuration))
        )
        `when`(planDetailsRepository.findById(planUuid)).thenReturn(
            Optional.of(PlanDetails(planUuid = planUuid, persoonUuid = UUID.randomUUID(), dossierId = "plan-dossier"))
        )
    }

    // ------------------------------------------------------------ signed host calls

    @Test
    fun `a signed configuration push is stored, an unsigned or tampered one is refused`() {
        val body = """{"serviceToken":"t","gzacBaseUrl":"http://gzac.test:8080","title":"PDCA"}"""
        val path = "/api/host/configurations/$configId"

        assertEquals(200, send("POST", path, body, signedHeaders("POST", path, body)).statusCode())
        verify(configurationStore).store(eq(configId) ?: configId, any() ?: placeholder)

        assertEquals(401, send("POST", path, body).statusCode())
        assertEquals(401, send("POST", path, body.replace("PDCA", "Evil"), signedHeaders("POST", path, body)).statusCode())
        val expired = Instant.now().minusSeconds(600).toString()
        assertEquals(401, send("POST", path, body, signedHeaders("POST", path, body, expired)).statusCode())
        assertEquals(401, send("POST", path, body, mapOf("X-Valtimo-Signature" to "00", "X-Valtimo-Timestamp" to Instant.now().toString())).statusCode())
    }

    @Test
    fun `a push for a GZAC outside the allowed base URLs is refused`() {
        val body = """{"serviceToken":"t","gzacBaseUrl":"http://evil.test:8080"}"""
        val path = "/api/host/configurations/other"

        val response = send("POST", path, body, signedHeaders("POST", path, body))

        assertEquals(403, response.statusCode())
        verify(configurationStore, never()).store(eq("other") ?: "other", any() ?: placeholder)
    }

    @Test
    fun `discovery and actions need a signature`() {
        assertEquals(401, send("GET", "/api/host/plugins").statusCode())
        assertEquals(200, send("GET", "/api/host/plugins", null, signedHeaders("GET", "/api/host/plugins", "")).statusCode())

        val actionPath = "/plugins/pdca/0.2.0/actions/onbekend"
        assertEquals(401, send("POST", actionPath, "{}").statusCode())
        assertEquals(404, send("POST", actionPath, "{}", signedHeaders("POST", actionPath, "{}")).statusCode())
    }

    @Test
    fun `actions run only for the active configuration`() {
        val path = "/plugins/pdca/0.2.0/actions/update-instrument"
        val other = """{"configurationId":"other-config","properties":{"instrumentUuid":"x","status":"afgerond"}}"""

        val refused = send("POST", path, other, signedHeaders("POST", path, other))

        assertEquals(422, refused.statusCode())
        assertEquals("error", json(refused)["status"])
        assertEquals("CONFIGURATIE_NIET_ACTIEF", json(refused)["errorCode"])
        verify(instrumentActionService, never()).handleUpdateInstrument(any() ?: emptyMap())

        val active = """{"configurationId":"$configId","properties":{"instrumentUuid":"x","status":"afgerond"}}"""
        val accepted = send("POST", path, active, signedHeaders("POST", path, active))
        assertEquals(200, accepted.statusCode(), accepted.body())
        assertEquals("completed", json(accepted)["status"])

        `when`(gzacAccess.resolve()).thenReturn(GzacAccess.Resolution.Unavailable("Geen GZAC-toegang", nonePushed = true))
        val unavailable = send("POST", path, active, signedHeaders("POST", path, active))
        assertEquals(503, unavailable.statusCode())
        assertEquals("TIJDELIJK_NIET_BESCHIKBAAR", json(unavailable)["errorCode"])
    }

    // ------------------------------------------------------------ closed API

    @Test
    fun `the API, the register proxy and stray paths cannot be called directly`() {
        assertEquals(401, send("GET", "/api/v1/pdca/plannen/$planUuid/afsluitcheck").statusCode())
        assertEquals(401, send("GET", "/api/v1/admin/phase-configs").statusCode())
        assertEquals(401, send("GET", "/openplan/plannen/api/v0/plan/$planUuid").statusCode())
        assertEquals(401, send("DELETE", "/openplan/plannen/api/v0/instrument/$planUuid").statusCode())
        assertEquals(401, send("GET", "/bundles/react/plan-overview.html").statusCode())
        assertEquals(200, send("GET", "/health").statusCode())
    }

    // ------------------------------------------------------------ /data

    @Test
    fun `data reaches the handler as the verified user, with its query`() {
        val token = user("jan")
        `when`(gzacClient.userCanReadDocument(token, "plan-dossier")).thenReturn(true)

        val check = data(token, "GET", "/api/v1/pdca/plannen/$planUuid/afsluitcheck")
        assertEquals(200, check.statusCode(), check.body())
        assertEquals("jan", json(check)["login"])

        val acties = data(token, "GET", "/api/v1/pdca/acties", query = mapOf("planUuid" to planUuid.toString()))
        assertEquals(200, acties.statusCode(), acties.body())
        assertEquals(planUuid.toString(), json(acties)["planUuid"])
        assertEquals("GET", json(acties)["method"])
    }

    @Test
    fun `data answers with the handler's own error`() {
        val token = user("jan")
        `when`(gzacClient.userCanReadDocument(token, "dossier-x")).thenReturn(true)

        val response = data(token, "GET", "/api/v1/pdca/dossiers/dossier-x/plan")

        assertEquals(404, response.statusCode())
        assertEquals("Geen plan bij dit dossier", json(response)["detail"])
    }

    @Test
    fun `data refuses another configuration, an unknown token and a failing GZAC`() {
        val token = user("jan")
        assertEquals(403, data(token, "GET", "/api/v1/pdca/plannen/$planUuid/afsluitcheck", configurationId = "other-config").statusCode())

        `when`(gzacClient.introspectUserToken("bad-token")).thenReturn(null)
        assertEquals(401, data("bad-token", "GET", "/api/v1/pdca/plannen/$planUuid/afsluitcheck").statusCode())
        // Repeating a refused token is refused without asking GZAC again.
        assertEquals(401, data("bad-token", "GET", "/api/v1/pdca/plannen/$planUuid/afsluitcheck").statusCode())
        verify(gzacClient, times(1)).introspectUserToken("bad-token")

        `when`(gzacClient.introspectUserToken("token-gzac-down"))
            .thenThrow(ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "GZAC is niet bereikbaar om de gebruiker te controleren"))
        assertEquals(503, data("token-gzac-down", "GET", "/api/v1/pdca/plannen/$planUuid/afsluitcheck").statusCode())

        val otherConfigToken = "token-${UUID.randomUUID()}"
        `when`(gzacClient.introspectUserToken(otherConfigToken))
            .thenReturn(GzacClient.UserTokenIntrospection("jan", "other-config", Instant.now().plusSeconds(600)))
        assertEquals(401, data(otherConfigToken, "GET", "/api/v1/pdca/plannen/$planUuid/afsluitcheck").statusCode())

        `when`(gzacAccess.resolve()).thenReturn(GzacAccess.Resolution.Unavailable("Geen GZAC-toegang", nonePushed = true))
        assertEquals(503, data(token, "GET", "/api/v1/pdca/plannen/$planUuid/afsluitcheck").statusCode())
    }

    @Test
    fun `data refuses a query string of its own`() {
        val response = post("/plugins/pdca/0.2.0/data?planUuid=x", dataBody(user("jan"), "GET", "/api/v1/pdca/acties", mapOf("planUuid" to planUuid.toString())))
        assertEquals(400, response.statusCode())
    }

    // ------------------------------------------------------------ authorization

    @Test
    fun `plan data needs read access to the plan dossier`() {
        val allowed = user("piet")
        val denied = user("klaas")
        `when`(gzacClient.userCanReadDocument(allowed, "plan-dossier")).thenReturn(true)
        `when`(gzacClient.userCanReadDocument(denied, "plan-dossier")).thenReturn(false)

        assertEquals(200, data(allowed, "GET", "/api/v1/pdca/plannen/$planUuid/afsluitcheck").statusCode())
        assertEquals(403, data(denied, "GET", "/api/v1/pdca/plannen/$planUuid/afsluitcheck").statusCode())
        // A plan without plan dossier is nobody's.
        assertEquals(403, data(allowed, "GET", "/api/v1/pdca/plannen/${UUID.randomUUID()}/afsluitcheck").statusCode())
    }

    @Test
    fun `case configuration writes need the GZAC admin role`() {
        val admin = user("beheerder")
        val medewerker = user("medewerker")
        `when`(gzacClient.userIsAdmin(admin)).thenReturn(true)
        `when`(gzacClient.userIsAdmin(medewerker)).thenReturn(false)

        val created = data(admin, "POST", "/api/v1/admin/phase-configs", body = mapOf("caseDefinitionKey" to "inwonerplan"))
        assertEquals(200, created.statusCode(), created.body())
        assertEquals("beheerder", json(created)["login"])
        assertEquals(403, data(medewerker, "POST", "/api/v1/admin/phase-configs", body = mapOf("caseDefinitionKey" to "x")).statusCode())
    }

    @Test
    fun `the register proxy takes only its routes`() {
        val token = user("jan")
        `when`(gzacClient.userCanReadDocument(token, "plan-dossier")).thenReturn(true)

        // Allowed routes reach the proxy (its mocked register client answers 502 here).
        assertEquals(502, data(token, "GET", "/openplan/plannen/api/v0/doeltype", query = mapOf("page" to "1")).statusCode())
        assertEquals(502, data(token, "PATCH", "/openplan/plannen/api/v0/plan/$planUuid", body = mapOf("notitie" to "n")).statusCode())

        // Writes that are backend use cases, other resources and unknown filters are refused.
        assertEquals(403, data(token, "PATCH", "/openplan/plannen/api/v0/plan/$planUuid", body = mapOf("status" to "afgerond")).statusCode())
        assertEquals(403, data(token, "DELETE", "/openplan/plannen/api/v0/instrument/${UUID.randomUUID()}").statusCode())
        assertEquals(403, data(token, "GET", "/openplan/plannen/api/v0/persoon").statusCode())
        assertEquals(403, data(token, "GET", "/openplan/plannen/api/v0/doeltype", query = mapOf("bsn" to "1")).statusCode())
        assertEquals(403, data(token, "GET", "/openplan/plannen/api/v0/doel").statusCode())
        assertEquals(403, data(token, "GET", "/api/v1/pdca/../../openplan/plannen/api/v0/persoon").statusCode())
    }

    @Test
    fun `a doel created through the proxy belongs to the plan in scope, its persoon and its doelen`() {
        val token = user("jan")
        `when`(gzacClient.userCanReadDocument(token, "plan-dossier")).thenReturn(true)
        val persoonUuid = planDetailsRepository.findById(planUuid).get().persoonUuid
        val hoofddoel = UUID.randomUUID()
        val vreemdDoel = UUID.randomUUID()
        `when`(doelDetailsRepository.findById(hoofddoel)).thenReturn(Optional.of(DoelDetails(doelUuid = hoofddoel, planUuid = planUuid)))
        `when`(doelDetailsRepository.findById(vreemdDoel)).thenReturn(Optional.of(DoelDetails(doelUuid = vreemdDoel, planUuid = UUID.randomUUID())))
        val doel = { changes: Map<String, Any?> ->
            mapOf(
                "plannenUuids" to listOf(planUuid.toString()), "persoonUuid" to persoonUuid.toString(),
                "doeltypeUuid" to UUID.randomUUID().toString(), "titel" to "Werk", "beschrijving" to "", "startdatum" to "2026-11-02",
                "hoofdDoel" to hoofddoel.toString()
            ) + changes
        }
        val create = { body: Map<String, Any?> -> data(token, "POST", "/openplan/plannen/api/v0/doel", body = body).statusCode() }

        // Allowed: reaches the proxy (its mocked register client answers 502 here).
        assertEquals(502, create(doel(emptyMap())))
        assertEquals(502, create(doel(mapOf("hoofdDoel" to null)).filterValues { it != null }))

        assertEquals(403, create(doel(mapOf("persoonUuid" to UUID.randomUUID().toString()))))
        assertEquals(403, create(doel(mapOf("hoofdDoel" to vreemdDoel.toString()))))
        assertEquals(403, create(doel(mapOf("plannenUuids" to listOf(planUuid.toString(), UUID.randomUUID().toString())))))
        assertEquals(403, create(doel(mapOf("plannenUuids" to emptyList<String>()))))
        assertEquals(403, create(doel(mapOf("status" to "afgerond"))))
    }

    // ------------------------------------------------------------ browser headers

    @Test
    fun `framing is limited to the GZAC frontend origins, also the announced ones`() {
        assertEquals("frame-ancestors http://gzac.test:4200", send("GET", "/health").headers().firstValue("Content-Security-Policy").orElse(null))
        assertTrue(send("GET", "/health").headers().firstValue("X-Frame-Options").isEmpty)

        val announcement = """{"gzacBaseUrl":"http://gzac.test:8080","frontendOrigins":["https://gzac.example.org"]}"""
        assertEquals(204, send("PUT", "/api/host/gzac-instances", announcement, signedHeaders("PUT", "/api/host/gzac-instances", announcement)).statusCode())

        assertEquals(
            "frame-ancestors http://gzac.test:4200 https://gzac.example.org",
            send("GET", "/health").headers().firstValue("Content-Security-Policy").orElse(null)
        )
    }

    @Test
    fun `CORS is open for bundles and for data from the GZAC frontend only`() {
        val preflight = { origin: String ->
            send(
                "OPTIONS", "/plugins/pdca/0.2.0/data", null,
                mapOf("Origin" to origin, "Access-Control-Request-Method" to "POST", "Access-Control-Request-Headers" to "content-type")
            )
        }
        val fromGzac = preflight("http://gzac.test:4200")
        assertEquals(200, fromGzac.statusCode())
        assertEquals("http://gzac.test:4200", fromGzac.headers().firstValue("Access-Control-Allow-Origin").orElse(null))
        assertEquals(403, preflight("http://evil.test").statusCode())

        val bundle = send("GET", "/plugins/pdca/0.2.0/bundles/missing.html", null, mapOf("Origin" to "null"))
        assertEquals("*", bundle.headers().firstValue("Access-Control-Allow-Origin").orElse(null))

        val api = send("GET", "/api/v1/pdca/acties", null, mapOf("Origin" to "http://gzac.test:4200"))
        assertNull(api.headers().firstValue("Access-Control-Allow-Origin").orElse(null))
    }

    // ------------------------------------------------------------ helpers

    /** A fresh user token that introspects as [login] for the active configuration. */
    private fun user(login: String): String {
        val token = "token-${UUID.randomUUID()}"
        `when`(gzacClient.introspectUserToken(token))
            .thenReturn(GzacClient.UserTokenIntrospection(login, configId, Instant.now().plusSeconds(600)))
        return token
    }

    private fun dataBody(token: String, method: String, path: String, query: Map<String, String>? = null, body: Any? = null, configurationId: String = configId) =
        objectMapper.writeValueAsString(
            mapOf(
                "configurationId" to configurationId, "method" to method, "path" to path, "query" to query,
                "body" to body, "context" to mapOf("documentId" to "plan-dossier"), "userToken" to token
            )
        )

    private fun data(token: String, method: String, path: String, query: Map<String, String>? = null, body: Any? = null, configurationId: String = configId) =
        post("/plugins/pdca/0.2.0/data", dataBody(token, method, path, query, body, configurationId))

    private fun post(path: String, body: String) = send("POST", path, body, mapOf("Origin" to "http://gzac.test:4200"))

    private fun signedHeaders(method: String, path: String, body: String, timestamp: String = Instant.now().toString()) = mapOf(
        HostSignatureVerifier.SIGNATURE_HEADER to HostSignatureVerifierTest.gzacSignature(method, path, timestamp, body.toByteArray()),
        HostSignatureVerifier.TIMESTAMP_HEADER to timestamp
    )

    private fun send(method: String, path: String, body: String? = null, headers: Map<String, String> = emptyMap()): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create("http://localhost:$port$path"))
            .method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
        if (body != null) builder.header("Content-Type", "application/json")
        headers.forEach { (name, value) -> builder.header(name, value) }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    @Suppress("UNCHECKED_CAST")
    private fun json(response: HttpResponse<String>): Map<String, Any?> = objectMapper.readValue(response.body(), Map::class.java) as Map<String, Any?>
}
