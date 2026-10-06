/*
 * Copyright 2015-2024 Ritense BV, the Netherlands.
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

package com.ritense.pdca.registers

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Display-only stubs for registers that are NOT part of this integration:
 * BRP persoonsgegevens (Open Plan only stores bsn + klant/persoonsprofiel
 * URNs) and an objectenregister. In a full Common Ground deployment these
 * would be Haal Centraal BRP and the Objecten API.
 *
 * The bsn's are 11-proef-valid test numbers matching the demo personen
 * seeded into Open Plan by docker/openplan/init.py.
 */
@RestController
@RequestMapping("/api/v1/registers")
class RegisterStubController {

    private val persons = listOf(
        BrpPersoon(
            bsn = "111222333",
            naam = "Erika de Goede",
            geboortedatum = "1986-06-18",
            geslacht = "Vrouw",
            nationaliteit = "Nederlandse",
            adres = Adres("Laakkade", "72", "2521 SJ", "Den Haag"),
            burgerlijkeStaat = "Gescheiden",
            kinderen = listOf(Kind("Daan", "2019-03-15"), Kind("Lisa", "2022-08-22"))
        ),
        BrpPersoon(
            bsn = "123456782",
            naam = "Jan van Dijk",
            geboortedatum = "1975-03-22",
            geslacht = "Man",
            nationaliteit = "Nederlandse",
            adres = Adres("Prinsengracht", "100", "1015 DV", "Amsterdam"),
            burgerlijkeStaat = "Gehuwd",
            kinderen = listOf(Kind("Sophie", "2010-07-12"))
        ),
        BrpPersoon(
            bsn = "999990019",
            naam = "Fatima El Amrani",
            geboortedatum = "1992-11-05",
            geslacht = "Vrouw",
            nationaliteit = "Nederlandse",
            adres = Adres("Kanaalstraat", "45", "3511 KC", "Utrecht"),
            burgerlijkeStaat = "Ongehuwd",
            kinderen = emptyList()
        ),
        BrpPersoon(
            bsn = "234567892",
            naam = "Beheerdienst Rijksvastgoed (contactpersoon)",
            geboortedatum = "1980-01-01",
            geslacht = "Onbekend",
            nationaliteit = "Nederlandse",
            adres = Adres("Korte Voorhout", "7", "2511 CW", "Den Haag"),
            burgerlijkeStaat = "Onbekend",
            kinderen = emptyList()
        )
    ).associateBy { it.bsn }

    private val objects = listOf(
        ObjectRecord(
            id = "binnenhof-001",
            naam = "Binnenhof",
            type = "Rijksmonument",
            monumentnummer = "RM-15234",
            bouwperiode = "1230-1992",
            adres = Adres("Binnenhof", "1", "2513 AA", "Den Haag"),
            eigenaar = "Rijksvastgoedbedrijf",
            beheerder = "Rijksvastgoedbedrijf",
            functie = "Regeringsgebouw",
            status = "In renovatie"
        ),
        ObjectRecord(
            id = "domtoren-001",
            naam = "Domtoren",
            type = "Rijksmonument",
            monumentnummer = "RM-36264",
            bouwperiode = "1321-1382",
            adres = Adres("Domplein", "21", "3512 JE", "Utrecht"),
            eigenaar = "Gemeente Utrecht",
            beheerder = "Gemeente Utrecht",
            functie = "Kerktoren",
            status = "In gebruik"
        ),
        ObjectRecord(
            id = "centraal-museum-001",
            naam = "Centraal Museum",
            type = "Rijksmonument",
            monumentnummer = "RM-18225",
            bouwperiode = "1838",
            adres = Adres("Agnietenstraat", "1", "3512 XA", "Utrecht"),
            eigenaar = "Gemeente Utrecht",
            beheerder = "Gemeente Utrecht",
            functie = "Museum",
            status = "In gebruik"
        )
    ).associateBy { it.id }

    @GetMapping("/personen/{bsn}")
    fun getPersoon(@PathVariable(name = "bsn") bsn: String): ResponseEntity<BrpPersoon> =
        persons[bsn]?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()

    @GetMapping("/objecten/{objectId}")
    fun getObject(@PathVariable(name = "objectId") objectId: String): ResponseEntity<ObjectRecord> =
        objects[objectId]?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()
}

data class Adres(val straat: String, val huisnummer: String, val postcode: String, val woonplaats: String)

data class Kind(val naam: String, val geboortedatum: String)

data class BrpPersoon(
    val bsn: String,
    val naam: String,
    val geboortedatum: String,
    val geslacht: String,
    val nationaliteit: String,
    val adres: Adres,
    val burgerlijkeStaat: String,
    val kinderen: List<Kind>
)

data class ObjectRecord(
    val id: String,
    val naam: String,
    val type: String,
    val monumentnummer: String,
    val bouwperiode: String,
    val adres: Adres,
    val eigenaar: String,
    val beheerder: String,
    val functie: String,
    val status: String
)
