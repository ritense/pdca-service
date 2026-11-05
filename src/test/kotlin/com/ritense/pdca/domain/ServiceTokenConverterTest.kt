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

import com.ritense.pdca.config.ServiceTokenCipher
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServiceTokenConverterTest {

    private val converter = ServiceTokenConverter(ServiceTokenCipher("secret-one"))

    @Test
    fun `a token round-trips and is not stored in plaintext`() {
        val stored = converter.convertToDatabaseColumn("service-token")!!

        assertTrue(stored.startsWith("v1:"))
        assertFalse(stored.contains("service-token"))
        assertNotEquals(stored, converter.convertToDatabaseColumn("service-token"), "a fresh IV per write")
        assertEquals("service-token", converter.convertToEntityAttribute(stored))
    }

    @Test
    fun `a token stored with another secret reads as absent`() {
        val stored = ServiceTokenConverter(ServiceTokenCipher("secret-two")).convertToDatabaseColumn("service-token")

        assertNull(converter.convertToEntityAttribute(stored))
    }

    @Test
    fun `a plaintext or tampered value reads as absent`() {
        val stored = converter.convertToDatabaseColumn("service-token")!!
        val tampered = stored.dropLast(2) + (if (stored.endsWith("AA")) "BB" else "AA")

        assertNull(converter.convertToEntityAttribute("service-token"))
        assertNull(converter.convertToEntityAttribute(tampered))
    }

    @Test
    fun `no token stays no token`() {
        assertNull(converter.convertToDatabaseColumn(null))
        assertNull(converter.convertToDatabaseColumn(""))
        assertNull(converter.convertToEntityAttribute(null))
    }
}
