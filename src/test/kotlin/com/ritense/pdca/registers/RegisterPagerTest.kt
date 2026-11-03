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

import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class RegisterPagerTest {

    private val first = URI.create("http://openplan.contract/plannen/api/v0/persoon")

    @Test
    fun `follows the next links until there are none`() {
        val requested = mutableListOf<URI>()
        val pages = mapOf(
            first to RegisterPage(next = "http://host.docker.internal:7501/plannen/api/v0/persoon?page=2", results = listOf(1, 2)),
            URI.create("http://host.docker.internal:7501/plannen/api/v0/persoon?page=2") to RegisterPage(next = null, results = listOf(3))
        )

        val results = RegisterPager(Register.OPENPLAN, 5).collect(first, "personen") { requested += it; pages.getValue(it) }

        assertEquals(listOf(1, 2, 3), results)
        assertEquals(2, requested.size)
    }

    @Test
    fun `a relative next link resolves against the register`() {
        val pages = mapOf(
            first to RegisterPage(next = "/plannen/api/v0/persoon?page=2", results = listOf(1)),
            URI.create("http://openplan.contract/plannen/api/v0/persoon?page=2") to RegisterPage<Int>(results = listOf(2))
        )

        assertEquals(listOf(1, 2), RegisterPager(Register.OPENPLAN, 5).collect(first, "personen") { pages.getValue(it) })
    }

    @Test
    fun `more pages than the cap fail loudly instead of truncating`() {
        var fetched = 0
        val failure = assertFailsWith<RegisterException> {
            RegisterPager(Register.OPENPLAN, 3).collect(first, "personen") {
                fetched++
                RegisterPage(next = "$first?page=${fetched + 1}", results = listOf(fetched))
            }
        }

        assertEquals(3, fetched)
        assertEquals(RegisterFailure.FAILED, failure.failure)
        assertEquals(502, failure.statusCode.value())
        assertEquals("Open Plan gaf meer dan 3 pagina's terug voor personen; de lijst is niet volledig gelezen", failure.reason)
    }

    @Test
    fun `find stops at the first match and also respects the cap`() {
        var fetched = 0
        val pager = RegisterPager(Register.OPENPLAN, 2)
        val endless = { _: URI -> fetched++; RegisterPage(next = "$first?page=${fetched + 1}", results = listOf(fetched)) }

        assertEquals(1, pager.find(first, "personen", endless) { it == 1 })
        assertEquals(1, fetched)
        assertFailsWith<RegisterException> { pager.find(first, "personen", endless) { it == 99 } }
        assertNull(pager.find(first, "personen", { RegisterPage(results = listOf(1)) }) { it == 2 })
    }
}
