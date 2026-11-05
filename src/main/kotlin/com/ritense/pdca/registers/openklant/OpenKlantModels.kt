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

package com.ritense.pdca.registers.openklant

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import java.util.UUID

/*
 * Open Klant objects (Klantinteracties API v1) as the app reads and writes
 * them. Request bodies keep explicit nulls where Open Klant expects the
 * field to be present.
 */

@JsonIgnoreProperties(ignoreUnknown = true)
data class Klantcontact(
    val uuid: UUID,
    val onderwerp: String? = null,
    val inhoud: String? = null,
    /** True when the contact took place; null while it is only planned. */
    val indicatieContactGelukt: Boolean? = null,
    val plaatsgevondenOp: String? = null,
    @JsonProperty("_expand")
    val expand: KlantcontactExpand? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class KlantcontactExpand(
    val gingOverOnderwerpobjecten: List<Onderwerpobject> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Onderwerpobject(
    val onderwerpobjectidentificator: OnderwerpobjectIdentificator? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class OnderwerpobjectIdentificator(
    val objectId: String? = null,
    val codeObjecttype: String? = null,
    val codeRegister: String? = null,
    val codeSoortObjectId: String? = null
)

/** Body of `maak-klantcontact`: the klantcontact, what it is about and, optionally, who it was with. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class MaakKlantcontact(
    val klantcontact: NewKlantcontact,
    val onderwerpobject: NewOnderwerpobject,
    val betrokkene: NewBetrokkene? = null
)

data class NewKlantcontact(
    val kanaal: String,
    val onderwerp: String,
    val inhoud: String,
    val indicatieContactGelukt: Boolean?,
    val taal: String,
    val vertrouwelijk: Boolean,
    val plaatsgevondenOp: String
)

data class NewOnderwerpobject(val onderwerpobjectidentificator: OnderwerpobjectIdentificator)

data class NewBetrokkene(
    val wasPartij: UuidRef,
    val rol: String,
    val initiator: Boolean = false,
    val organisatienaam: String = "",
    val contactnaam: Any? = null,
    val bezoekadres: Any? = null,
    val correspondentieadres: Any? = null
)

data class UuidRef(val uuid: UUID)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MaakKlantcontactResult(val klantcontact: Klantcontact? = null)

/** Changes to a klantcontact; absent fields stay as they are. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class KlantcontactUpdate(
    val onderwerp: String? = null,
    val inhoud: String? = null,
    val indicatieContactGelukt: Boolean? = null,
    val plaatsgevondenOp: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Partij(val uuid: UUID)

/** A natuurlijk persoon known only by bsn; the name stays in the BRP. */
data class NewPersoonPartij(
    val soortPartij: String = "persoon",
    val indicatieActief: Boolean = true,
    val indicatieGeheimhouding: Boolean = false,
    val voorkeurstaal: String = "nld",
    val digitaleAdressen: List<Any> = emptyList(),
    val rekeningnummers: List<Any> = emptyList(),
    val voorkeursDigitaalAdres: Any? = null,
    val voorkeursRekeningnummer: Any? = null,
    val partijIdentificatie: Map<String, Any?> = mapOf("contactnaam" to null),
    val partijIdentificatoren: List<PartijIdentificatorWrapper>
) {
    companion object {
        fun withBsn(bsn: String) = NewPersoonPartij(
            partijIdentificatoren = listOf(
                PartijIdentificatorWrapper(
                    PartijIdentificator(
                        codeObjecttype = "natuurlijk_persoon",
                        codeSoortObjectId = "bsn",
                        objectId = bsn,
                        codeRegister = "brp"
                    )
                )
            )
        )
    }
}

data class PartijIdentificatorWrapper(val partijIdentificator: PartijIdentificator)

data class PartijIdentificator(
    val codeObjecttype: String,
    val codeSoortObjectId: String,
    val objectId: String,
    val codeRegister: String
)
