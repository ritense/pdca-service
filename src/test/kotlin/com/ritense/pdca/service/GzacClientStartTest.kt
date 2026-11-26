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

package com.ritense.pdca.service

import com.ritense.pdca.plugin.GzacAccess
import com.ritense.pdca.plugin.PluginConfiguration
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import org.springframework.web.server.ResponseStatusException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

/** How [GzacClient.startformulierIndienen] reads GZAC's answer: a refusal, or an unknown outcome. */
class GzacClientStartTest {

    private val builder = RestClient.builder()
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val gzacAccess = mock(GzacAccess::class.java).also {
        `when`(it.active()).thenReturn(
            GzacAccess.Access("http://gzac", "token", PluginConfiguration("c", "PDCA", emptyMap(), "token", "http://gzac"))
        )
    }
    private val client = GzacClient(gzacAccess, builder)
    private val url = "http://gzac/api/v1/process-link/start-link/form/submission?documentId=plan-dossier"

    private fun indienen() = client.startformulierIndienen("start-link", "plan-dossier", mapOf("bouwblokStart" to emptyMap<String, Any>()))

    @ParameterizedTest
    @ValueSource(ints = [400, 404, 409, 500])
    fun `an error answer from GZAC itself is a refusal`(status: Int) {
        server.expect(requestTo(url)).andRespond(withStatus(HttpStatus.valueOf(status)))

        assertFailsWith<GzacClient.StartGeweigerdException> { indienen() }
    }

    @ParameterizedTest
    @ValueSource(ints = [502, 503, 504])
    fun `a gateway status leaves the outcome unknown`(status: Int) {
        server.expect(requestTo(url)).andRespond(withStatus(HttpStatus.valueOf(status)))

        val fout = assertFailsWith<ResponseStatusException> { indienen() }

        assertFalse(fout is GzacClient.StartGeweigerdException)
        assertEquals(HttpStatus.BAD_GATEWAY, fout.statusCode)
    }

    @Test
    fun `errors in the answer are a refusal`() {
        server.expect(requestTo(url)).andRespond(withSuccess("""{"errors":["ongeldig"]}""", MediaType.APPLICATION_JSON))

        assertIs<GzacClient.StartGeweigerdException>(assertFailsWith<ResponseStatusException> { indienen() })
    }
}
