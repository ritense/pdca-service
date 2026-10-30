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

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.ritense.pdca.plugin.PushedGzacAccess
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.web.client.RestClient
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InstrumentAanvraagServiceTest {

    private val service = InstrumentAanvraagService(
        openPlanRestClient = mock(RestClient::class.java),
        openProductRestClient = mock(RestClient::class.java),
        objectenRestClient = mock(RestClient::class.java),
        pushedGzacAccess = mock(PushedGzacAccess::class.java),
        gzacClient = mock(GzacClient::class.java),
        objectMapper = jacksonObjectMapper()
    )

    @Test
    fun `the start recipe comes out of the actie mapping`() {
        val mapping = mapOf(
            "static" to listOf(
                mapOf("name" to "soort", "classType" to "String", "value" to "Verzoek"),
                mapOf("name" to "type", "classType" to "String", "value" to "http://objecttypen/api/v2/objecttypes/abc"),
                mapOf("name" to "typeVersion", "classType" to "Integer", "value" to "2"),
                mapOf("name" to "kanaal", "classType" to "String", "value" to "plan")
            ),
            "plan" to listOf(mapOf("name" to "data.planid", "classType" to "String", "regex" to "$.uuid")),
            "productType" to listOf(mapOf("name" to "code", "classType" to "String", "regex" to "$.code"))
        )

        val actie = service.parseStartActie(mapping)

        assertEquals("verzoek", actie.soort)
        assertEquals("http://objecttypen/api/v2/objecttypes/abc", actie.objecttype)
        assertEquals(2, actie.typeVersion)
        assertEquals(mapOf<String, Any?>("kanaal" to "plan"), actie.statics)

        val data = service.resolveMappings(
            actie, mapOf("plan" to mapOf("uuid" to "plan-1"), "producttype" to mapOf("code" to "CV"))
        )
        assertEquals(mapOf<String, Any?>("planid" to "plan-1", "code" to "CV"), data)
    }

    @Test
    fun `without a mapping the recipe is a verzoek without objecttype`() {
        val actie = service.parseStartActie(null)

        assertEquals("verzoek", actie.soort)
        assertNull(actie.objecttype)
        assertEquals(1, actie.typeVersion)
        assertTrue(actie.statics.isEmpty())
        assertTrue(actie.mappings.isEmpty())
    }

    @Test
    fun `an unknown source and a missing value are skipped`() {
        val actie = service.parseStartActie(
            mapOf(
                "inwonersprofiel" to listOf(mapOf("name" to "belastbaarheid", "regex" to "$.situatie.belastbaarheid")),
                "doel" to listOf(
                    mapOf("name" to "doeltype", "regex" to "$.doeltype.naam"),
                    mapOf("name" to "ontbreekt", "regex" to "$.nergens.te.vinden")
                )
            )
        )

        val data = service.resolveMappings(actie, mapOf("doel" to mapOf("doeltype" to mapOf("naam" to "Werk"))))

        assertEquals(mapOf<String, Any?>("doeltype" to "Werk"), data)
    }
}
