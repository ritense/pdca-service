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

package com.ritense.pdca.plugin

import com.ritense.pdca.plugin.ActiveConfigurationSelector.Selection
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ActiveConfigurationSelectorTest {

    private val a = configuration("config-a")
    private val b = configuration("config-b")
    private val withoutToken = configuration("config-c", token = "")

    @Test
    fun `without a pushed configuration there is none`() {
        assertEquals(Selection.NonePushed, ActiveConfigurationSelector.select(emptyList(), null))
        assertEquals(Selection.NonePushed, ActiveConfigurationSelector.select(listOf(withoutToken), null))
    }

    @Test
    fun `the one configuration with access is used`() {
        assertEquals(Selection.Selected(a), ActiveConfigurationSelector.select(listOf(a, withoutToken), null))
        assertEquals(Selection.Selected(a), ActiveConfigurationSelector.select(listOf(a), " "))
    }

    @Test
    fun `several are ambiguous, whatever the push order`() {
        assertEquals(Selection.Ambiguous(listOf("config-a", "config-b")), ActiveConfigurationSelector.select(listOf(b, a), null))
    }

    @Test
    fun `the configured id decides`() {
        assertEquals(Selection.Selected(b), ActiveConfigurationSelector.select(listOf(a, b), "config-b"))
        assertEquals(Selection.ConfiguredNotPushed("config-x"), ActiveConfigurationSelector.select(listOf(a, b), "config-x"))
        assertEquals(Selection.ConfiguredNotPushed("config-c"), ActiveConfigurationSelector.select(listOf(withoutToken), "config-c"))
    }

    private fun configuration(id: String, token: String = "token-$id") = PluginConfiguration(
        configId = id,
        title = id,
        properties = emptyMap(),
        serviceToken = token,
        gzacBaseUrl = "http://gzac:8080"
    )
}
