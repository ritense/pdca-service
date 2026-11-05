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

import com.ritense.pdca.service.GzacClient
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UserVerifierTest {

    private val gzacClient = mock(GzacClient::class.java)
    private var now = Instant.parse("2026-11-02T12:00:00Z")
    private val clock = object : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant() = now
    }
    private val verifier = UserVerifier(gzacClient, clock)
    private var introspections = 0
    private val countIntrospection: () -> Unit = { introspections++ }

    @Test
    fun `a cached token is not introspected again, nor counted`() {
        `when`(gzacClient.introspectUserToken("goed")).thenReturn(GzacClient.UserTokenIntrospection("jan", "config", now.plusSeconds(600)))

        repeat(3) { assertEquals("jan", verifier.verify("goed", "config", countIntrospection).login) }

        assertEquals(1, introspections)
        verify(gzacClient, times(1)).introspectUserToken("goed")
    }

    @Test
    fun `a refused token is refused from the cache for a while, then asked again`() {
        `when`(gzacClient.introspectUserToken("verzonnen")).thenReturn(null)

        repeat(3) {
            val fout = assertFailsWith<ResponseStatusException> { verifier.verify("verzonnen", "config", countIntrospection) }
            assertEquals(401, fout.statusCode.value())
        }
        assertEquals(1, introspections)

        now = now.plus(UserVerifier.REFUSED_TTL).plusSeconds(1)
        assertFailsWith<ResponseStatusException> { verifier.verify("verzonnen", "config", countIntrospection) }
        assertEquals(2, introspections)
        verify(gzacClient, times(2)).introspectUserToken("verzonnen")
    }

    @Test
    fun `a refused introspection budget stops the call before GZAC is asked`() {
        val fout = assertFailsWith<DataRateLimiter.TooManyRequests> {
            verifier.verify("nieuw", "config") { throw DataRateLimiter.TooManyRequests(5) }
        }

        assertEquals(429, fout.statusCode.value())
        verify(gzacClient, times(0)).introspectUserToken("nieuw")
    }

    @Test
    fun `an expired token is refused and remembered as refused`() {
        `when`(gzacClient.introspectUserToken("verlopen")).thenReturn(GzacClient.UserTokenIntrospection("jan", "config", now.minusSeconds(1)))

        repeat(2) { assertFailsWith<ResponseStatusException> { verifier.verify("verlopen", "config", countIntrospection) } }

        assertEquals(1, introspections)
    }
}
