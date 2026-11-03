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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.net.URI

/** One page of a register listing, in the registers' native `{count, next, results}` shape. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class RegisterPage<T>(
    val count: Int? = null,
    val next: String? = null,
    val results: List<T> = emptyList()
)

/**
 * The one pager of the register gateways. It follows the `next` link of
 * each page (an absolute register URL; the contract transport only looks
 * at its path and query) until there is none, and reads at most [maxPages]
 * pages: a listing with more fails with a [RegisterException] and an ERROR
 * log instead of being silently truncated.
 */
class RegisterPager(private val register: Register, private val maxPages: Int) {

    init {
        require(maxPages > 0) { "maxPages must be positive" }
    }

    /** All results of the listing starting at [first]. */
    fun <T> collect(first: URI, subject: String, fetch: (URI) -> RegisterPage<T>): List<T> {
        val results = mutableListOf<T>()
        walk(first, subject, fetch) { page ->
            results += page
            false
        }
        return results
    }

    /** The first result matching [predicate], reading only as many pages as needed. */
    fun <T> find(first: URI, subject: String, fetch: (URI) -> RegisterPage<T>, predicate: (T) -> Boolean): T? {
        var found: T? = null
        walk(first, subject, fetch) { page ->
            found = page.firstOrNull(predicate)
            found != null
        }
        return found
    }

    private fun <T> walk(first: URI, subject: String, fetch: (URI) -> RegisterPage<T>, done: (List<T>) -> Boolean) {
        var uri: URI? = first
        var pages = 0
        while (uri != null) {
            if (pages == maxPages) throw RegisterErrors.tooManyPages(register, subject, maxPages)
            val page = fetch(uri)
            pages++
            if (done(page.results)) return
            uri = page.next?.takeIf { it.isNotBlank() }?.let(::nextUri)
        }
    }

    private fun nextUri(next: String): URI {
        val uri = URI.create(next)
        return if (uri.isAbsolute) uri else URI.create(register.contractBaseUrl).resolve(uri)
    }
}
