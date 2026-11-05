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

package com.ritense.pdca.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BsnTest {

    @Test
    fun `the demo bsns pass the elfproef`() {
        listOf("111222333", "123456782", "999990019", "234567892", "999993653").forEach { assertTrue(Bsn.isValid(it), it) }
    }

    @Test
    fun `anything else does not`() {
        listOf(null, "", "123456789", "11122233", "1112223334", "11122233a", "000000000").forEach { assertFalse(Bsn.isValid(it), it) }
    }
}
