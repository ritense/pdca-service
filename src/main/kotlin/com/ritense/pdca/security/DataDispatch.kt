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

package com.ritense.pdca.security

import jakarta.servlet.DispatcherType
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import org.springframework.http.MediaType
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.util.Collections
import java.util.Enumeration

/**
 * A `/data` call that passed verification and the access policy, handed to
 * the app's own REST handler by an internal forward. Its presence on a
 * FORWARD dispatch is what lets a request reach `/api/v1/...` or the
 * register proxy at all (`SecurityConfig`); [context] is the GZAC surface
 * context the call came with (dossier of the tab).
 */
class DataDispatch(val user: PdcaUser, val call: DataCall, val allowed: AllowedCall) {

    companion object {
        val ATTRIBUTE: String = DataDispatch::class.java.name

        /** The dispatch of [request], only on the internal forward that `/data` made. */
        fun of(request: HttpServletRequest): DataDispatch? =
            if (request.dispatcherType == DispatcherType.FORWARD) request.getAttribute(ATTRIBUTE) as? DataDispatch else null
    }
}

/**
 * The request a `/data` call becomes for the handler it is forwarded to:
 * the call's method and JSON body, and none of the browser's headers (only
 * `Content-Type`, `Content-Length` and `Accept` are presented, all JSON).
 * The parameters are the query string of the forward path: the container
 * merges them in below this wrapper, and `/data` itself never carries a
 * query string or form parameters.
 */
class DispatchedRequest(
    request: HttpServletRequest,
    private val method: String,
    private val body: ByteArray
) : HttpServletRequestWrapper(request) {

    private val headers: Map<String, String> = buildMap {
        put("accept", MediaType.APPLICATION_JSON_VALUE)
        if (body.isNotEmpty()) {
            put("content-type", MediaType.APPLICATION_JSON_VALUE)
            put("content-length", body.size.toString())
        }
    }

    override fun getMethod(): String = method

    override fun getInputStream(): ServletInputStream = ByteArrayServletInputStream(body)

    override fun getReader(): BufferedReader = BufferedReader(InputStreamReader(ByteArrayInputStream(body), Charsets.UTF_8))

    override fun getContentType(): String? = headers["content-type"]

    override fun getCharacterEncoding(): String = Charsets.UTF_8.name()

    override fun getContentLength(): Int = body.size

    override fun getContentLengthLong(): Long = body.size.toLong()

    override fun getHeader(name: String): String? = headers[name.lowercase()]

    override fun getHeaders(name: String): Enumeration<String> =
        Collections.enumeration(listOfNotNull(headers[name.lowercase()]))

    override fun getHeaderNames(): Enumeration<String> = Collections.enumeration(headers.keys)

    override fun getIntHeader(name: String): Int = headers[name.lowercase()]?.toInt() ?: -1

    override fun getDateHeader(name: String): Long = -1
}
