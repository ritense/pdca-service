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

package com.ritense.pdca.registers.openplan

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.ritense.pdca.domain.PdcaUrn
import java.util.UUID

/*
 * Open Plan objects (Plannen API v0) as the app reads and writes them, in the
 * register's native camelCase. Dates and datetimes stay ISO strings, exactly
 * as the register sends and expects them. Read models cover every field the
 * register serializes, so a start recipe (ARCHITECTURE §6.2) can map any of them.
 */

@JsonIgnoreProperties(ignoreUnknown = true)
data class Persoon(
    val uuid: UUID,
    val bsn: String? = null,
    val klant: String? = null,
    val persoonsprofiel: String? = null
)

data class NewPersoon(
    val bsn: String,
    val klant: String,
    val persoonsprofiel: String
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PlanType(val uuid: UUID, val type: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class OverkoepelendPlan(
    val urn: String? = null,
    val uuid: UUID,
    val titel: String? = null,
    val status: String? = null,
    val medewerker: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Plan(
    val urn: String? = null,
    val uuid: UUID,
    val status: String? = null,
    val titel: String? = null,
    val notitie: String? = null,
    val startdatum: String? = null,
    val einddatum: String? = null,
    val redenEinde: String? = null,
    val plantype: PlanType? = null,
    val overkoepelendPlan: OverkoepelendPlan? = null,
    val zaak: String? = null,
    /** The plan's subject as a URN (`PdcaUrn` persoon or object). */
    val domeinregister: String? = null,
    /** The procesbegeleider as a medewerker URN; empty when there is none. */
    val medewerker: String? = null
)

/** A plan as embedded in a doel (`doel.plannen`). */
@JsonIgnoreProperties(ignoreUnknown = true)
data class PlanRef(
    val uuid: UUID,
    val status: String? = null,
    val titel: String? = null,
    val notitie: String? = null,
    val startdatum: String? = null,
    val einddatum: String? = null,
    val redenEinde: String? = null,
    val zaak: String? = null,
    val domeinregister: String? = null,
    val medewerker: String? = null
)

data class NewPlan(
    val titel: String,
    val notitie: String,
    val startdatum: String,
    val plantypeUuid: UUID,
    val overkoepelendPlanUuid: UUID,
    val domeinregister: String,
    val medewerker: String
)

data class PlanMedewerkerUpdate(val medewerker: String)

/**
 * The status of a plan with its end: closed with an [einddatum] and the
 * explanation in [redenEinde], or reopened with both cleared (an explicit
 * null clears the einddatum, so nulls are sent).
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
data class PlanStatusUpdate(val status: String, val einddatum: String?, val redenEinde: String) {
    companion object {
        fun einde(status: String, einddatum: String, reden: String) = PlanStatusUpdate(status, einddatum, reden)
        fun heropend() = PlanStatusUpdate(STATUS_ACTIEF, null, "")
    }
}

/** Register status of plannen, doelen and instrumenten. */
const val STATUS_ACTIEF = "actief"
const val STATUS_AFGEROND = "afgerond"
const val STATUS_GEANNULEERD = "geannuleerd"

@JsonIgnoreProperties(ignoreUnknown = true)
data class DoelCategorie(val uuid: UUID? = null, val naam: String? = null)

/**
 * A doeltype from the doeltype register. The category [HOOFDDOEL_CATEGORIE]
 * marks a hoofddoel type; every other category is a thema.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class DoelType(
    val uuid: UUID,
    val doelType: String? = null,
    val categorieen: List<DoelCategorie> = emptyList()
) {
    @get:JsonIgnore
    val isHoofddoelType: Boolean get() = categorieen.any { it.naam == HOOFDDOEL_CATEGORIE }

    companion object {
        const val HOOFDDOEL_CATEGORIE = "Hoofddoel"
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class Doel(
    val uuid: UUID,
    val plannen: List<PlanRef> = emptyList(),
    val doeltype: DoelType? = null,
    val persoon: Persoon? = null,
    val status: String? = null,
    val titel: String? = null,
    val beschrijving: String? = null,
    val startdatum: String? = null,
    val einddatum: String? = null,
    val resultaat: String? = null,
    val toelichtingResultaat: String? = null,
    val hoofdDoel: UUID? = null
)

/** A status change of a doel: closing it (einddatum, resultaat, toelichting); absent fields stay as they are. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class DoelStatusUpdate(
    val status: String,
    val einddatum: String? = null,
    val resultaat: String? = null,
    val toelichtingResultaat: String? = null
)

data class DoelHoofddoelUpdate(val hoofdDoel: UUID)

/** A new doel; absent optional fields are left out of the request. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class NewDoel(
    val plannenUuids: List<UUID>,
    val persoonUuid: UUID,
    val doeltypeUuid: UUID,
    val titel: String,
    val beschrijving: String,
    val startdatum: String,
    val status: String? = null,
    val hoofdDoel: UUID? = null,
    val resultaat: String? = null,
    val einddatum: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class InstrumentType(val uuid: UUID, val instrumentType: String? = null)

/** A doel as embedded in an instrument (`instrument.doelen`). */
@JsonIgnoreProperties(ignoreUnknown = true)
data class DoelRef(
    val uuid: UUID,
    val status: String? = null,
    val titel: String? = null,
    val hoofdDoel: UUID? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Instrument(
    val uuid: UUID,
    val titel: String? = null,
    val startdatum: String? = null,
    val einddatum: String? = null,
    val status: String? = null,
    /** The requested product as a producttype URN. */
    val product: String? = null,
    /** The instrument dossier that manages the instrument, as a dossier URN. */
    val zaak: String? = null,
    val resultaat: String? = null,
    val doelen: List<DoelRef> = emptyList(),
    val instrumenttype: InstrumentType? = null
) {
    /**
     * The instrument dossier that manages this instrument, from its `zaak`
     * URN; null for an instrument without one. Such an instrument has one
     * owner, its dossier: the plan page and the app's own endpoints do not
     * close, cancel or delete it (ARCHITECTURE §6.2).
     */
    @get:JsonIgnore
    val dossier: Pair<String, String>? get() = PdcaUrn.parse(zaak)?.dossier()

    @get:JsonIgnore
    val isDossierManaged: Boolean get() = dossier != null

    @get:JsonIgnore
    val isActief: Boolean get() = status == STATUS_ACTIEF
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class NewInstrument(
    val titel: String,
    val startdatum: String,
    val doelenUuids: List<UUID>,
    val ontwikkelwensenUuids: List<UUID> = emptyList(),
    val instrumenttypeUuid: UUID? = null,
    val product: String? = null,
    val zaak: String? = null,
    val status: String
)

/** Closing an instrument: its end status, end date and (for afgerond) resultaat. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class InstrumentEinde(
    val status: String,
    val einddatum: String,
    val resultaat: String? = null
)
