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

import com.ritense.pdca.config.PdcaProperties
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import kotlin.test.assertEquals

class DataRateLimiterTest {

    private var now = 0L
    private fun limiter(perUser: Int = 0, perClient: Int = 0, perConfiguration: Int = 0) = DataRateLimiter(
        PdcaProperties(data = PdcaProperties.DataApi(PdcaProperties.RateLimit(perUser, perClient, perConfiguration, Duration.ofMinutes(1))))
    ).also { it.nanoTime = { now } }

    @Test
    fun `a user gets a burst up to the limit, then 429 until the bucket refills`() {
        val limiter = limiter(perUser = 3)
        repeat(3) { limiter.acquireForUser("config", "jan") }

        val refused = assertThrows<DataRateLimiter.TooManyRequests> { limiter.acquireForUser("config", "jan") }
        assertEquals(429, refused.statusCode.value())
        assertEquals("20", refused.headers.getFirst("Retry-After"))

        // Other users have their own bucket.
        limiter.acquireForUser("config", "piet")

        // One window / limit later there is one call again.
        now += Duration.ofSeconds(20).toNanos()
        limiter.acquireForUser("config", "jan")
        assertThrows<DataRateLimiter.TooManyRequests> { limiter.acquireForUser("config", "jan") }
    }

    @Test
    fun `introspections are limited per client address before the configuration counts them`() {
        val limiter = limiter(perClient = 2, perConfiguration = 3)
        repeat(2) { limiter.acquireForIntrospection("config", "10.0.0.1") }

        // The flooding address stops at its own bucket, without using up the configuration's.
        repeat(5) { assertThrows<DataRateLimiter.TooManyRequests> { limiter.acquireForIntrospection("config", "10.0.0.1") } }
        limiter.acquireForIntrospection("config", "10.0.0.2")

        // The configuration caps all addresses together.
        assertThrows<DataRateLimiter.TooManyRequests> { limiter.acquireForIntrospection("config", "10.0.0.3") }
        limiter.acquireForIntrospection("other-config", "10.0.0.3")
    }

    @Test
    fun `a limit of 0 is off`() {
        val limiter = limiter()
        repeat(10_000) {
            limiter.acquireForUser("config", "jan")
            limiter.acquireForIntrospection("config", "10.0.0.1")
        }
    }
}
