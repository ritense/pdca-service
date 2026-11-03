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

package com.ritense.pdca.registers.openproduct

import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.JsonNode
import java.util.UUID

/**
 * A producttype from Open Product (Producttypen API v1). The fields the app
 * uses are typed; every other field of the register object is kept in
 * [other], so a start recipe (ARCHITECTURE §6.2) can map any producttype field.
 */
data class ProductType(
    val uuid: UUID,
    val code: String? = null,
    val naam: String? = null,
    val parameters: List<Parameter> = emptyList()
) {
    @get:JsonAnyGetter
    @field:JsonAnySetter
    val other: MutableMap<String, Any?> = linkedMapOf()

    /** The value of the producttype parameter with this name, if any. */
    fun parameter(naam: String): String? = parameters.firstOrNull { it.naam == naam }?.waarde
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class Parameter(val naam: String? = null, val waarde: String? = null)

/** An actie of a producttype; [mapping] is Open Product's DMN-mapping shape, read as the start recipe. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class Actie(
    val uuid: UUID? = null,
    val naam: String? = null,
    val mapping: JsonNode? = null
)
