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
import com.ritense.pdca.registers.openplan.OpenPlanGateway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.web.client.RestClient
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** The one error mapping of the gateways ([RegisterErrors]), through [OpenPlanGateway]. */
class RegisterGatewayErrorTest {

    private val objectMapper = jacksonObjectMapper()
    private val uuid = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val planUrl = "http://openplan.contract/plannen/api/v0/plan/$uuid"
    private lateinit var server: MockRestServiceServer
    private lateinit var gateway: OpenPlanGateway

    @BeforeEach
    fun setUp() {
        val builder = RestClient.builder()
        server = MockRestServiceServer.bindTo(builder).build()
        gateway = OpenPlanGateway(builder.build(), objectMapper, PdcaProperties())
    }

    @Test
    fun `the register's 404 is not found, and find answers null`() {
        answer(HttpStatus.NOT_FOUND, """{"detail":"Niet gevonden."}""")
        answer(HttpStatus.NOT_FOUND, """{"detail":"Niet gevonden."}""")

        val failure = assertFailsWith<RegisterException> { gateway.getPlan(uuid) }
        assertEquals(RegisterFailure.NOT_FOUND, failure.failure)
        assertEquals(404, failure.statusCode.value())
        assertEquals("Plan $uuid niet gevonden in Open Plan", failure.reason)
        assertNull(gateway.findPlan(uuid))
    }

    @Test
    fun `a validation error is rejected with the invalidParams and nothing else from the body`() {
        answer(
            HttpStatus.BAD_REQUEST,
            """{"code":"invalid","title":"internal trace abc","invalidParams":[{"name":"titel","reason":"Dit veld is vereist."}]}"""
        )

        val failure = assertFailsWith<RegisterException> { gateway.getPlan(uuid) }

        assertEquals(RegisterFailure.REJECTED, failure.failure)
        assertEquals(400, failure.statusCode.value())
        assertEquals("Open Plan heeft het verzoek geweigerd: titel: Dit veld is vereist.", failure.reason)
    }

    @Test
    fun `a register 5xx never passes its body on`() {
        answer(HttpStatus.INTERNAL_SERVER_ERROR, """{"detail":"Traceback (most recent call last) secret"}""")
        answer(HttpStatus.SERVICE_UNAVAILABLE, "")

        val failed = assertFailsWith<RegisterException> { gateway.getPlan(uuid) }
        val unavailable = assertFailsWith<RegisterException> { gateway.getPlan(uuid) }

        assertEquals(RegisterFailure.FAILED, failed.failure)
        assertEquals(502, failed.statusCode.value())
        assertFalse(failed.reason!!.contains("Traceback"))
        assertEquals(RegisterFailure.UNAVAILABLE, unavailable.failure)
        assertEquals(503, unavailable.statusCode.value())
    }

    @Test
    fun `a contract error keeps the transport's message, TOO_MANY_CONCURRENT_CALLS as 503`() {
        contractError(HttpStatus.SERVICE_UNAVAILABLE, "TOO_MANY_CONCURRENT_CALLS", "GZAC is druk (TOO_MANY_CONCURRENT_CALLS)")
        contractError(HttpStatus.BAD_GATEWAY, "PROVIDER_ERROR", "De Open Plan plugin meldt een fout (PROVIDER_ERROR)")

        val busy = assertFailsWith<RegisterException> { gateway.getPlan(uuid) }
        val providerError = assertFailsWith<RegisterException> { gateway.getPlan(uuid) }

        assertEquals(RegisterFailure.UNAVAILABLE, busy.failure)
        assertEquals(503, busy.statusCode.value())
        assertEquals("GZAC is druk (TOO_MANY_CONCURRENT_CALLS)", busy.reason)
        assertEquals(RegisterFailure.FAILED, providerError.failure)
        assertEquals(502, providerError.statusCode.value())
    }

    @Test
    fun `an unreadable answer is a failure`() {
        server.expect(requestTo(planUrl)).andRespond(
            withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body("not json")
        )

        val failure = assertFailsWith<RegisterException> { gateway.getPlan(uuid) }

        assertEquals(RegisterFailure.FAILED, failure.failure)
        assertEquals("Open Plan gaf een onleesbaar antwoord", failure.reason)
    }

    @Test
    fun `query values are encoded, not concatenated`() {
        val uri = object : RegisterGateway(Register.OPENPLAN, RestClient.create(), objectMapper, 1) {
            fun build() = uri("plan", "a b/c", query = mapOf("naam" to "a b+c&d", "leeg" to null))
        }.build()

        assertEquals("http://openplan.contract/plannen/api/v0/plan/a%20b%2Fc?naam=a%20b%2Bc%26d", uri.toString())
    }

    private fun answer(status: HttpStatus, body: String) {
        server.expect(requestTo(planUrl))
            .andRespond(withStatus(status).contentType(MediaType.APPLICATION_JSON).body(body))
    }

    private fun contractError(status: HttpStatus, code: String, detail: String) {
        val headers = HttpHeaders().apply { set(RegisterErrors.CONTRACT_ERROR_HEADER, code) }
        server.expect(requestTo(planUrl)).andRespond(
            withStatus(status).contentType(MediaType.APPLICATION_JSON).headers(headers)
                .body(objectMapper.writeValueAsString(mapOf("detail" to detail)))
        )
    }
}
