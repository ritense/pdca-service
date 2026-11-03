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

import com.ritense.pdca.config.PdcaProperties
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit

/**
 * Keeps the app's register contract calls within what GZAC accepts: GZAC
 * runs at most a fixed number of contract calls per calling configuration at
 * a time (8 by default) and refuses the rest with
 * `TOO_MANY_CONCURRENT_CALLS`. All register calls share the one active
 * configuration, so one limiter guards them all:
 *
 *  - [withPermit] lets at most [PdcaProperties.Registers.contractConcurrency]
 *    calls run at once (default 6, below GZAC's limit so the dossier
 *    processes' own calls have room); a caller waits at most
 *    `contractPermitTimeout` for a permit and then gets [Busy];
 *  - [retryDelay] gives the jittered, exponentially growing pause before
 *    retrying a call GZAC refused as too many: GZAC refuses before
 *    delivering the call, so the retry is safe for every operation, and it
 *    happens at most `contractRetries` times.
 */
class ContractCallLimiter(
    private val settings: PdcaProperties.Registers,
    private val sleeper: (Duration) -> Unit = { Thread.sleep(it.toMillis()) }
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val permits = Semaphore(settings.contractConcurrency.coerceAtLeast(1), true)

    /** No permit came free within the permit timeout. */
    class Busy : RuntimeException("No contract call permit within the timeout")

    val maxRetries: Int get() = settings.contractRetries.coerceAtLeast(0)

    fun <T> withPermit(call: () -> T): T {
        val acquired = try {
            permits.tryAcquire(settings.contractPermitTimeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!acquired) {
            log.warn("No register contract call permit within {}", settings.contractPermitTimeout)
            throw Busy()
        }
        try {
            return call()
        } finally {
            permits.release()
        }
    }

    /** Waits before retry number [attempt] (1-based): base × 2^(attempt-1), plus up to the same again as jitter. */
    fun pauseBeforeRetry(attempt: Int) {
        val base = settings.contractRetryBackoff.toMillis().coerceAtLeast(1) shl (attempt - 1).coerceIn(0, 10)
        val delay = Duration.ofMillis(base + ThreadLocalRandom.current().nextLong(base + 1))
        log.debug("GZAC refused a contract call as too many concurrent calls; retry {} in {}", attempt, delay)
        sleeper(delay)
    }
}
