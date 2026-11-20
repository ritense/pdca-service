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
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil
import kotlin.math.min

/**
 * Rate limit of `/data` (`pdca.data.rate-limit`): token buckets, each
 * holding at most its limit and refilled at that limit per window. A call
 * over a limit is refused with 429 and a `Retry-After`; a limit of 0
 * switches that bucket off.
 *
 * - **Introspections** ([acquireForIntrospection]): a call whose user token
 *   is not in `UserVerifier`'s cache costs an introspection at GZAC. Those
 *   calls are taken first from the bucket of the client address and then
 *   from the configuration's, before GZAC is asked. A flood of made-up
 *   tokens therefore stops at its own address's bucket and can at most
 *   empty the configuration's bucket of introspections: users whose token
 *   is cached are not counted there and keep working.
 * - **Per user** ([acquireForUser]): every call of a verified user (subject
 *   within its configuration), right after verification.
 *
 * The buckets live in memory: with several replicas each replica counts on
 * its own, so the effective limit is the configured one times the number of
 * replicas. A refusal is logged once per bucket until it has calls again.
 */
@Component
class DataRateLimiter(properties: PdcaProperties) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val limits = properties.data.rateLimit
    private val windowNanos = limits.window.toNanos().coerceAtLeast(1)
    private val buckets = ConcurrentHashMap<String, Bucket>()
    private val calls = AtomicLong()

    /** Clock of the buckets, replaceable in tests. */
    internal var nanoTime: () -> Long = System::nanoTime

    private class Bucket(var tokens: Double, var updatedAt: Long, var refusalLogged: Boolean = false)

    /**
     * Takes one introspection from the bucket of [clientAddress] and then
     * from the configuration's, or refuses with 429. Called only when the
     * user token is about to be introspected at GZAC.
     */
    fun acquireForIntrospection(configurationId: String, clientAddress: String) {
        acquire("client:$configurationId:$clientAddress", limits.perClient, "a client address of configuration $configurationId")
        acquire("configuration:$configurationId", limits.perConfiguration, "the introspections of configuration $configurationId")
    }

    /** Takes one call from the user's bucket, or refuses with 429. */
    fun acquireForUser(configurationId: String, subject: String) =
        acquire("user:$configurationId:$subject", limits.perUser, "a user of configuration $configurationId")

    private fun acquire(key: String, limit: Int, who: String) {
        if (limit <= 0) return
        val now = nanoTime()
        if (calls.incrementAndGet() % CLEANUP_EVERY == 0L) evictIdle(now)
        val bucket = buckets.computeIfAbsent(key) { Bucket(limit.toDouble(), now) }
        val (waitNanos, firstRefusal) = synchronized(bucket) {
            bucket.tokens = min(limit.toDouble(), bucket.tokens + (now - bucket.updatedAt).toDouble() * limit / windowNanos)
            bucket.updatedAt = now
            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0
                bucket.refusalLogged = false
                return
            }
            val first = !bucket.refusalLogged
            bucket.refusalLogged = true
            ((1.0 - bucket.tokens) * windowNanos / limit).toLong() to first
        }
        if (firstRefusal) log.warn("/data rate limit reached for {}", who)
        throw TooManyRequests(ceil(waitNanos / 1e9).toLong().coerceAtLeast(1))
    }

    /** A bucket idle for a whole window is full again: dropping it changes nothing. */
    private fun evictIdle(now: Long) {
        buckets.entries.removeIf { (_, bucket) -> synchronized(bucket) { now - bucket.updatedAt > windowNanos } }
    }

    class TooManyRequests(val retryAfterSeconds: Long) :
        ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Te veel aanvragen; probeer het over een moment opnieuw") {
        override fun getHeaders(): HttpHeaders = HttpHeaders().apply { set(HttpHeaders.RETRY_AFTER, retryAfterSeconds.toString()) }
    }

    private companion object {
        const val CLEANUP_EVERY = 1024L
    }
}
