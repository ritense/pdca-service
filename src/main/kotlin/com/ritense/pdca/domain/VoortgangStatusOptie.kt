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

package com.ritense.pdca.domain

/**
 * One voortgangsstatus of a plan case type ([PhaseConfig.voortgangStatussen]):
 * how an active subdoel or hoofddoel is going. [code] is stable and stored on
 * the doel ([DoelDetails.voortgangStatus]; empty on a new status, which gets
 * one derived from its name when saved); [naam] is what users see and may be
 * renamed; [betekenis] drives the tag colour and the Voortgang KPI
 * ([VoortgangBetekenis.NEUTRAAL] counts as assessed, neither good nor bad).
 */
data class VoortgangStatusOptie(
    val code: String = "",
    val naam: String,
    val betekenis: VoortgangBetekenis
) {
    companion object {
        /** The list of a plan case type that configures none. */
        val STANDAARD = listOf(
            VoortgangStatusOptie("STARTEN", "Starten", VoortgangBetekenis.NEUTRAAL),
            VoortgangStatusOptie("MEE_BEZIG", "Mee bezig", VoortgangBetekenis.GAAT_GOED),
            VoortgangStatusOptie("HETZELFDE_GEBLEVEN", "Hetzelfde gebleven", VoortgangBetekenis.LET_OP),
            VoortgangStatusOptie("ACHTERUIT_GEGAAN", "Achteruit gegaan", VoortgangBetekenis.GAAT_NIET_GOED),
            VoortgangStatusOptie("GESTOPT", "Gestopt", VoortgangBetekenis.GAAT_NIET_GOED),
            VoortgangStatusOptie("BEHAALD", "Behaald", VoortgangBetekenis.GAAT_GOED),
            VoortgangStatusOptie("BEHAALD_BETER_DAN_VERWACHT", "Behaald en beter dan verwacht", VoortgangBetekenis.GAAT_GOED)
        )

        /**
         * Names of the lifecycle statuses the doel shows (register status plus
         * the overlay's gepland/gestart); a voortgangsstatus may not reuse
         * them, so a tag never means two things. "Behaald" is no lifecycle
         * label and may be a voortgangsstatus: it never closes the doel.
         */
        val GERESERVEERDE_NAMEN = setOf("gepland", "gestart", "afgerond", "afgebroken", "niet behaald")

        val CODE_PATROON = Regex("^[A-Z0-9_]{1,64}$")
    }
}

enum class VoortgangBetekenis { GAAT_GOED, NEUTRAAL, LET_OP, GAAT_NIET_GOED }
