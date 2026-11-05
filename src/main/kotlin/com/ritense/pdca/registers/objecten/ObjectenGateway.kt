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

package com.ritense.pdca.registers.objecten

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.ritense.pdca.config.PdcaProperties
import com.ritense.pdca.registers.Register
import com.ritense.pdca.registers.RegisterGateway
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.util.UUID

/** A new object in the Objecten API: its objecttype URL and first record. */
data class NewObject(val type: String, val record: NewObjectRecord)

/** [data] follows the objecttype's JSON schema; for a verzoek that is the app's own payload (ARCHITECTURE §6.2). */
data class NewObjectRecord(val typeVersion: Int, val startAt: String, val data: Map<String, Any?>)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ObjectRef(val url: String? = null, val uuid: UUID? = null)

/** An object as the Objecten API lists it, with its current record. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class RegisterObject(
    val url: String? = null,
    val uuid: UUID? = null,
    val type: String? = null,
    val record: ObjectRecord? = null
)

/** [data] is the record's data as stored (for a verzoek the app's own payload, ARCHITECTURE §6.2); dates are ISO dates. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ObjectRecord(
    val typeVersion: Int? = null,
    val data: JsonNode? = null,
    val startAt: String? = null,
    val registrationAt: String? = null
)

/** The Objecten API (v2) for the backend's services; see [RegisterGateway]. */
@Component
class ObjectenGateway(
    objectenRestClient: RestClient,
    objectMapper: ObjectMapper,
    properties: PdcaProperties
) : RegisterGateway(Register.OBJECTEN, objectenRestClient, objectMapper, properties.registers.maxPages) {

    fun createObject(newObject: NewObject): ObjectRef = post(uri("objects"), newObject, ObjectRef::class.java, "nieuw object")

    /**
     * The objects whose record data matches every `path → value` pair
     * exactly (the Objects API's `data_attrs` filter: `key__exact__value`,
     * comma-separated, a nested key's parts joined by `__`). Values may not
     * contain `__` or a comma; uuids and fixed codes are what this is for.
     */
    fun listObjects(dataAttrs: Map<String, String>): List<RegisterObject> {
        val filter = dataAttrs.entries.joinToString(",") { (path, value) ->
            require(!value.contains("__") && !value.contains(',')) { "Unsupported data_attrs value '$value'" }
            "${path}__exact__$value"
        }
        return list(uri("objects", query = mapOf("data_attrs" to filter)), RegisterObject::class.java, "objecten ($filter)")
    }
}
