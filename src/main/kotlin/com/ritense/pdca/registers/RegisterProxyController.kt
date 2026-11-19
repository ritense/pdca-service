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

import com.fasterxml.jackson.databind.ObjectMapper
import com.ritense.pdca.security.DataDispatch
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.client.RestClient
import java.net.URI

/**
 * Pass-through to the Maykin registers for the bundles, which read the
 * register APIs in their native shape (identical paths, bodies and status
 * codes). It forwards through the register's RestClient, which goes through
 * the register's GZAC contract:
 *
 *   /openplan/plannen/api/v0/...          -> Open Plan
 *   /openproduct/producttypen/api/v1/...  -> Open Product
 *   /openklant/klantinteracties/api/v1/...-> Open Klant
 *
 * It is reachable only through `/data`, and only for the register routes of
 * the access policy (`DataAccessPolicy`: the reads the bundles make and a
 * few single-object writes, each checked against the user's plan dossier);
 * any other request is refused with 403 before it is forwarded.
 */
@RestController
class RegisterProxyController(
    private val openPlanRestClient: RestClient,
    private val openProductRestClient: RestClient,
    private val openKlantRestClient: RestClient,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @RequestMapping("/openplan/**")
    fun openplan(request: HttpServletRequest, @RequestBody(required = false) body: ByteArray?): ResponseEntity<ByteArray> =
        forward(openPlanRestClient, Register.OPENPLAN.contractBaseUrl, "/openplan", request, body)

    @RequestMapping("/openproduct/**")
    fun openproduct(request: HttpServletRequest, @RequestBody(required = false) body: ByteArray?): ResponseEntity<ByteArray> =
        forward(openProductRestClient, Register.OPENPRODUCT.contractBaseUrl, "/openproduct", request, body)

    @RequestMapping("/openklant/**")
    fun openklant(request: HttpServletRequest, @RequestBody(required = false) body: ByteArray?): ResponseEntity<ByteArray> =
        forward(openKlantRestClient, Register.OPENKLANT.contractBaseUrl, "/openklant", request, body)

    private fun forward(
        client: RestClient,
        baseUrl: String,
        prefix: String,
        request: HttpServletRequest,
        body: ByteArray?
    ): ResponseEntity<ByteArray> {
        if (DataDispatch.of(request)?.allowed?.route?.register != true) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsBytes(mapOf("detail" to "Geen toegang")))
        }
        val path = request.requestURI.removePrefix(request.contextPath).removePrefix(prefix)
        val query = request.queryString?.let { "?$it" } ?: ""
        val uri = URI.create("$baseUrl$path$query")

        return try {
            client.method(HttpMethod.valueOf(request.method))
                .uri(uri)
                .apply {
                    if (body != null && body.isNotEmpty()) {
                        contentType(MediaType.parseMediaType(request.contentType ?: MediaType.APPLICATION_JSON_VALUE))
                        body(body)
                    }
                }
                .exchange({ _, response ->
                    ResponseEntity.status(response.statusCode)
                        .apply { response.headers.contentType?.let { contentType(it) } }
                        .body(response.body.readAllBytes())
                }, false)!!
        } catch (e: Exception) {
            log.warn("Register proxy {} {} failed", request.method, path, e)
            ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsBytes(mapOf("detail" to "Het register is niet bereikbaar")))
        }
    }
}
