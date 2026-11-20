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
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PdcaUrnTest {

    @Test
    fun `formats every kind in the stored wire format`() {
        assertEquals("urn:pdca:brp:persoon:999993653", PdcaUrn.persoon("999993653").toString())
        assertEquals("urn:pdca:objecten:object:binnenhof-001", PdcaUrn.registerObject("binnenhof-001").toString())
        assertEquals("urn:pdca:openklant:klant:999993653", PdcaUrn.klant("999993653").toString())
        assertEquals("urn:pdca:profielen:persoonsprofiel:999993653", PdcaUrn.persoonsprofiel("999993653").toString())
        assertEquals("urn:pdca:openproduct:producttype:JOBCOACH", PdcaUrn.producttype("JOBCOACH").toString())
        assertEquals(
            "urn:pdca:gzac:dossier:instrument-verzoek:0c5f6c2e-1111-4a5b-9a3e-123456789abc",
            PdcaUrn.dossier("instrument-verzoek", "0c5f6c2e-1111-4a5b-9a3e-123456789abc").toString()
        )
        assertEquals("urn:pdca:medewerkers:medewerker:m.de.boer", PdcaUrn.medewerker("  M. de  Boer "))
        assertEquals("", PdcaUrn.medewerker(" "))
        assertEquals("", PdcaUrn.medewerker(null))
    }

    @Test
    fun `parses what it formats`() {
        PdcaUrn.Kind.entries.forEach { kind ->
            val urn = PdcaUrn(kind, if (kind == PdcaUrn.Kind.DOSSIER) "key:doc" else "id-1")
            assertEquals(urn, PdcaUrn.parse(urn.toString()))
        }
    }

    @Test
    fun `a dossier URN carries both route parts`() {
        val urn = PdcaUrn.parse("urn:pdca:gzac:dossier:instrument-verzoek:abc-123")

        assertEquals(PdcaUrn.Kind.DOSSIER, urn?.kind)
        assertEquals("instrument-verzoek" to "abc-123", urn?.dossier())
        assertNull(PdcaUrn.parse("urn:pdca:gzac:dossier:zonder-document")?.dossier())
        assertNull(PdcaUrn.persoon("1").dossier())
    }

    @Test
    fun `anything else is not a pdca URN`() {
        assertNull(PdcaUrn.parse(null))
        assertNull(PdcaUrn.parse(""))
        assertNull(PdcaUrn.parse("urn:other:brp:persoon:1"))
        assertNull(PdcaUrn.parse("urn:pdca:brp:onbekend:1"))
        assertNull(PdcaUrn.parse("urn:pdca:brp:persoon:"))
        assertNull(PdcaUrn.parse("urn:pdca:brp"))
    }
}
