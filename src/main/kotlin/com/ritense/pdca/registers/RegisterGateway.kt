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

package com.ritense.pdca.registers

import com.fasterxml.jackson.databind.JavaType
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import org.springframework.web.util.UriComponentsBuilder
import org.springframework.web.util.UriUtils
import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8

/**
 * Base of the typed register gateways (`OpenPlanGateway`,
 * `OpenKlantGateway`, `OpenProductGateway`, `ObjectenGateway`). A gateway
 * is the only way services reach a register: it builds the request URIs
 * (path segments and query values encoded, never concatenated), reads and
 * writes typed DTOs, lists through the one [RegisterPager] and maps every
 * failure through [RegisterErrors] to a [RegisterException]. Underneath is
 * the register's RestClient, whose transport is the register's GZAC
 * contract ([RegisterContractRequestFactory]).
 */
abstract class RegisterGateway(
    protected val register: Register,
    private val client: RestClient,
    protected val objectMapper: ObjectMapper,
    maxPages: Int
) {

    private val pager = RegisterPager(register, maxPages)

    private class Answer(val status: Int, val headers: HttpHeaders, val body: ByteArray)

    /** `<contract base><api path>/<segments...>?<query>`, every segment and query value encoded. */
    protected fun uri(vararg segments: Any, query: Map<String, Any?> = emptyMap()): URI {
        val builder = UriComponentsBuilder.fromUriString(register.contractBaseUrl + register.apiPath)
        segments.forEach { builder.path("/" + UriUtils.encodePathSegment(it.toString(), UTF_8)) }
        query.forEach { (name, value) ->
            if (value != null) builder.queryParam(UriUtils.encode(name, UTF_8), UriUtils.encode(value.toString(), UTF_8))
        }
        return builder.build(true).toUri()
    }

    protected fun <T> get(uri: URI, type: Class<T>, subject: String): T =
        read(exchange(HttpMethod.GET, uri, null, subject), type, subject)

    /** Like [get], but null when the register does not know the object. */
    protected fun <T> find(uri: URI, type: Class<T>, subject: String): T? = try {
        get(uri, type, subject)
    } catch (e: RegisterException) {
        if (e.failure == RegisterFailure.NOT_FOUND) null else throw e
    }

    protected fun <T> list(uri: URI, type: Class<T>, subject: String): List<T> =
        pager.collect(uri, subject) { page(it, type, subject) }

    protected fun <T> findInList(uri: URI, type: Class<T>, subject: String, predicate: (T) -> Boolean): T? =
        pager.find(uri, subject, { page(it, type, subject) }, predicate)

    /** The results of the first page only, for "any one will do" lookups. */
    protected fun <T> firstPage(uri: URI, type: Class<T>, subject: String): List<T> = page(uri, type, subject).results

    protected fun <T> post(uri: URI, body: Any, type: Class<T>, subject: String): T =
        read(exchange(HttpMethod.POST, uri, body, subject), type, subject)

    protected fun patch(uri: URI, body: Any, subject: String) {
        exchange(HttpMethod.PATCH, uri, body, subject)
    }

    /** Deletes the object; false when the register did not know it (already gone), so a retry is harmless. */
    protected fun delete(uri: URI, subject: String): Boolean = try {
        exchange(HttpMethod.DELETE, uri, null, subject)
        true
    } catch (e: RegisterException) {
        if (e.failure == RegisterFailure.NOT_FOUND) false else throw e
    }

    private fun <T> page(uri: URI, type: Class<T>, subject: String): RegisterPage<T> {
        val pageType: JavaType = objectMapper.typeFactory.constructParametricType(RegisterPage::class.java, type)
        return read(exchange(HttpMethod.GET, uri, null, subject), pageType, subject)
    }

    private fun exchange(method: HttpMethod, uri: URI, body: Any?, subject: String): Answer {
        val answer = try {
            client.method(method)
                .uri(uri)
                .apply {
                    if (body != null) contentType(MediaType.APPLICATION_JSON).body(objectMapper.writeValueAsBytes(body))
                }
                .exchange({ _, response ->
                    Answer(response.statusCode.value(), response.headers, response.body.readAllBytes())
                }, true)!!
        } catch (e: Exception) {
            throw RegisterErrors.transport(register, subject, e)
        }
        if (answer.status !in 200..299) {
            throw RegisterErrors.fromResponse(register, answer.status, answer.headers, answer.body, objectMapper, subject)
        }
        return answer
    }

    private fun <T> read(answer: Answer, type: Class<T>, subject: String): T =
        read(answer, objectMapper.typeFactory.constructType(type), subject)

    private fun <T> read(answer: Answer, type: JavaType, subject: String): T = try {
        objectMapper.readValue<T>(answer.body, type)
            ?: throw IllegalStateException("empty body")
    } catch (e: Exception) {
        throw RegisterErrors.unreadable(register, subject, e)
    }
}
