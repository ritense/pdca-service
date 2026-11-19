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

import com.fasterxml.jackson.databind.ObjectMapper
import com.ritense.pdca.config.PdcaProperties
import com.ritense.pdca.registers.Register
import com.ritense.pdca.registers.RegisterGateway
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.util.UUID

/** Open Product (Producttypen API v1) for the backend's services; see [RegisterGateway]. */
@Component
class OpenProductGateway(
    openProductRestClient: RestClient,
    objectMapper: ObjectMapper,
    properties: PdcaProperties
) : RegisterGateway(Register.OPENPRODUCT, openProductRestClient, objectMapper, properties.registers.maxPages) {

    fun getProducttype(uuid: UUID): ProductType =
        get(uri("producttypen", uuid), ProductType::class.java, "producttype $uuid")

    /**
     * The producttype with this code. The `code` query narrows the listing
     * where Open Product supports it; the match on `code` itself decides.
     */
    fun findProducttypeByCode(code: String): ProductType? = findInList(
        uri("producttypen", query = mapOf("code" to code)), ProductType::class.java, "producttypen"
    ) { it.code == code }

    /** The acties of a producttype (server-side filter on the producttype). */
    fun listActies(producttypeUuid: UUID): List<Actie> = list(
        uri("acties", query = mapOf("producttype__uuid" to producttypeUuid)), Actie::class.java,
        "acties van producttype $producttypeUuid"
    )
}
