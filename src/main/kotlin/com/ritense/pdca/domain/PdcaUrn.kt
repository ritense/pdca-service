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

/**
 * The URNs this app mints for cross-register references:
 * `urn:pdca:<component>:<resource>:<id>`, one [Kind] per reference. The one
 * place that formats and parses them (the frontend mirror is
 * `frontend/src/shared/urn.ts`). The formats are stored in the registers and
 * never change.
 *
 * A dossier URN's id is `<caseDefinitionKey>:<documentId>`: both parts of
 * the GZAC route to the dossier.
 */
data class PdcaUrn(val kind: Kind, val id: String) {

    enum class Kind(val component: String, val resource: String) {
        /** `plan.domeinregister` of a plan about an inwoner: the bsn. */
        PERSOON("brp", "persoon"),

        /** `plan.domeinregister` of a plan about an object: the object id. */
        OBJECT("objecten", "object"),

        /** `persoon.klant` in Open Plan: the bsn. */
        KLANT("openklant", "klant"),

        /** `persoon.persoonsprofiel` in Open Plan: the bsn. */
        PERSOONSPROFIEL("profielen", "persoonsprofiel"),

        /** `plan.medewerker`: the procesbegeleider's name slug ([medewerker]). */
        MEDEWERKER("medewerkers", "medewerker"),

        /** `instrument.product`: the producttype code in Open Product. */
        PRODUCTTYPE("openproduct", "producttype"),

        /** `instrument.zaak`: the instrument dossier, `<caseDefinitionKey>:<documentId>`. */
        DOSSIER("gzac", "dossier")
    }

    override fun toString(): String = "$PREFIX${kind.component}:${kind.resource}:$id"

    /** The (caseDefinitionKey, documentId) of a dossier URN; null for any other kind. */
    fun dossier(): Pair<String, String>? {
        if (kind != Kind.DOSSIER) return null
        val key = id.substringBefore(':', "")
        val documentId = id.substringAfter(':', "")
        return if (key.isEmpty() || documentId.isEmpty()) null else key to documentId
    }

    companion object {
        const val PREFIX = "urn:pdca:"

        fun persoon(bsn: String) = PdcaUrn(Kind.PERSOON, bsn)
        fun registerObject(objectId: String) = PdcaUrn(Kind.OBJECT, objectId)
        fun klant(bsn: String) = PdcaUrn(Kind.KLANT, bsn)
        fun persoonsprofiel(bsn: String) = PdcaUrn(Kind.PERSOONSPROFIEL, bsn)
        fun producttype(code: String) = PdcaUrn(Kind.PRODUCTTYPE, code)
        fun dossier(caseDefinitionKey: String, documentId: String) = PdcaUrn(Kind.DOSSIER, "$caseDefinitionKey:$documentId")

        /**
         * The Open Plan medewerker reference for a procesbegeleider: the name
         * with whitespace and dots collapsed to single dots, lowercase
         * ("M. de Boer" → `urn:pdca:medewerkers:medewerker:m.de.boer`); empty
         * when there is none.
         */
        fun medewerker(naam: String?): String {
            val id = naam?.trim()?.replace(Regex("[\\s.]+"), ".")?.trim('.')?.lowercase()
            return if (id.isNullOrEmpty()) "" else PdcaUrn(Kind.MEDEWERKER, id).toString()
        }

        /** Parses a URN this app minted; null for anything else (or an empty id). */
        fun parse(value: String?): PdcaUrn? {
            if (value == null || !value.startsWith(PREFIX)) return null
            val rest = value.removePrefix(PREFIX)
            val component = rest.substringBefore(':', "")
            val resource = rest.substringAfter(':', "").substringBefore(':', "")
            val id = rest.substringAfter(':', "").substringAfter(':', "")
            val kind = Kind.entries.firstOrNull { it.component == component && it.resource == resource } ?: return null
            return if (id.isEmpty()) null else PdcaUrn(kind, id)
        }
    }
}
