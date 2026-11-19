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
import com.ritense.pdca.domain.PhaseConfig
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IntakeServiceTest {

    private val gzacClient = mock(GzacClient::class.java)
    private val phaseConfigService = mock(PhaseConfigService::class.java)
    private val service = IntakeService(gzacClient, phaseConfigService, jacksonObjectMapper())

    @Test
    fun `a persoon intake prefills the subject, the plan fields and the plan case type`() {
        `when`(gzacClient.getDocument("intake")).thenReturn(
            mapOf(
                "definitionId" to mapOf("name" to "intake-werk-participatie"),
                "content" to mapOf("bsn" to "999993653", "naam" to "Erika", "objectId" to "obj-1", "hoofddoel" to "Werken", "doelen" to emptyList<Any>())
            )
        )
        `when`(phaseConfigService.findByCaseDefinitionKey("intake-werk-participatie")).thenReturn(
            PhaseConfig(caseDefinitionKey = "intake-werk-participatie", evaluationTypes = "[]", planCaseDefinitionKey = "inwonerplan")
        )

        val response = service.prefill("intake")

        assertEquals("inwonerplan", response.planCaseDefinitionKey)
        assertEquals("PERSON", response.prefill["subjectType"])
        assertEquals("999993653", response.prefill["subjectId"])
        assertEquals("Werken", response.prefill["hoofddoel"])
        // Empty lists and the object subject of a persoon intake are left out.
        assertNull(response.prefill["doelen"])
        assertNull(response.prefill["subjectIdObject"])
    }

    @Test
    fun `a configured prefill mapping replaces the default paths`() {
        `when`(gzacClient.getDocument("intake")).thenReturn(
            mapOf(
                "definitionId" to mapOf("name" to "renovatie-intake"),
                "content" to mapOf("object" to mapOf("id" to "pand-7", "naam" to "Pand 7"))
            )
        )
        `when`(phaseConfigService.findByCaseDefinitionKey("renovatie-intake")).thenReturn(
            PhaseConfig(
                caseDefinitionKey = "renovatie-intake", evaluationTypes = "[]", planCaseDefinitionKey = "binnenhof-renovatie",
                prefillMapping = """{"subjectIdObject":"/object/id","objectNaam":"/object/naam"}"""
            )
        )

        val response = service.prefill("intake")

        assertEquals("OBJECT", response.prefill["subjectType"])
        assertEquals("pand-7", response.prefill["subjectId"])
        assertEquals("Pand 7", response.prefill["naam"])
    }
}
