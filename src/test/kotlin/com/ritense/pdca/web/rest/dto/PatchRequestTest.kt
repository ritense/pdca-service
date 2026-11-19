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
package com.ritense.pdca.web.rest.dto

import com.fasterxml.jackson.datatype.jdk8.Jdk8Module
import com.ritense.pdca.domain.PhaseConfig
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The overlay's partial updates: an absent field keeps the stored value, an explicit null clears it. */
class PatchRequestTest {

    private val objectMapper = jacksonObjectMapper().registerModule(Jdk8Module())

    @Test
    fun `absent keeps, null clears, a value sets`() {
        val request = objectMapper.readValue<InstrumentDetailsRequest>("""{"urenBesteed":null,"effectiviteitScore":4}""")

        assertNull(request.effectiviteitToelichting, "absent")
        assertEquals("bewaard", request.effectiviteitToelichting.patch("bewaard"))
        assertNull(request.urenBesteed.patch(12), "explicit null clears")
        assertEquals(4, request.effectiviteitScore.patch(2))
    }

    @Test
    fun `clearing the plan case type of a case configuration turns an intake back into a plan case type`() {
        val intake = PhaseConfig(
            caseDefinitionKey = "renovatie-intake",
            evaluationTypes = "[\"INTAKE\"]",
            planStatussen = "[\"Concept\"]",
            prefillMapping = "{\"titel\":\"/planTitel\"}",
            planCaseDefinitionKey = "binnenhof-renovatie"
        )
        val request = objectMapper.readValue<UpdatePhaseConfigRequest>(
            """{"evaluationTypes":"[\"INTAKE\"]","planCaseDefinitionKey":null,"prefillMapping":null,"voortgangStatussen":"[]"}"""
        )

        val updated = request.applyTo(intake)

        assertNull(updated.planCaseDefinitionKey)
        assertNull(updated.prefillMapping)
        assertEquals("[]", updated.voortgangStatussen)
        assertEquals("[\"Concept\"]", updated.planStatussen, "absent keeps")
        assertEquals("[\"INTAKE\"]", updated.evaluationTypes)
    }
}
