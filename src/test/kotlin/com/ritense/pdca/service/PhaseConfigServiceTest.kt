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
import com.ritense.pdca.domain.VoortgangBetekenis
import com.ritense.pdca.domain.VoortgangStatusOptie
import com.ritense.pdca.repository.PhaseConfigRepository
import org.junit.jupiter.api.Test
import org.springframework.web.server.ResponseStatusException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PhaseConfigServiceTest {

    private val configs = InMemoryRepository(PhaseConfig::class, "id")
    private val service = PhaseConfigService(configs.proxy(PhaseConfigRepository::class), jacksonObjectMapper())

    private fun config(voortgang: String) = PhaseConfig(caseDefinitionKey = "plan", voortgangStatussen = voortgang)

    @Test
    fun `a case type without its own list gets the standard voortgangsstatussen`() {
        val opties = service.getVoortgangStatussen("onbekend")

        assertEquals(VoortgangStatusOptie.STANDAARD, opties)
        assertEquals(
            listOf(
                "Starten", "Mee bezig", "Hetzelfde gebleven", "Achteruit gegaan", "Gestopt", "Behaald",
                "Behaald en beter dan verwacht"
            ),
            opties.map { it.naam }
        )
    }

    @Test
    fun `Behaald may be a voortgangsstatus and gets a code from its name`() {
        service.create(config("""[{"naam":"Behaald","betekenis":"GAAT_GOED"},{"naam":"Starten","betekenis":"NEUTRAAL"}]"""))

        assertEquals(
            listOf(
                VoortgangStatusOptie("BEHAALD", "Behaald", VoortgangBetekenis.GAAT_GOED),
                VoortgangStatusOptie("STARTEN", "Starten", VoortgangBetekenis.NEUTRAAL)
            ),
            service.getVoortgangStatussen("plan")
        )
    }

    @Test
    fun `a lifecycle status cannot be a voortgangsstatus`() {
        val fout = assertFailsWith<ResponseStatusException> {
            service.create(config("""[{"naam":"Niet behaald","betekenis":"GAAT_NIET_GOED"}]"""))
        }

        assertTrue(fout.reason!!.contains("'Niet behaald' is al een status van het doel zelf"), fout.reason)
    }
}
