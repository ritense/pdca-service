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

package com.ritense.pdca.plugin

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.ritense.pdca.security.DataAccessPolicy
import com.ritense.pdca.security.DataCall
import com.ritense.pdca.security.DataDispatch
import com.ritense.pdca.security.DataRateLimiter
import com.ritense.pdca.security.DispatchedRequest
import com.ritense.pdca.security.PdcaUserAuthentication
import com.ritense.pdca.security.UserVerifier
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.context.SecurityContextImpl
import org.springframework.security.web.context.SecurityContextRepository
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.net.URLEncoder

/**
 * The body GZAC's frontend posts to `/data` for an iframe's `proxyRequest`
 * (target `plugin`): the call ([method], [path], [query], [body]) as the
 * bundle asked for it, plus [configurationId], [context] and the user's
 * downscoped [userToken], which the GZAC frontend adds itself.
 */
data class PluginDataRequest(
    val configurationId: String? = null,
    val method: String? = null,
    val path: String? = null,
    val query: Map<String, String>? = null,
    val body: JsonNode? = null,
    val context: Map<String, Any?>? = null,
    val userToken: String? = null
) {
    override fun toString() = "PluginDataRequest(configurationId=$configurationId, method=$method, path=$path)"
}

/**
 * The contract's `/data` route and the app's only entry for browser API
 * traffic. The bundles call the app's REST API exclusively through GZAC's
 * `proxyRequest`; GZAC's frontend posts each call here with the user's
 * downscoped token. A call is handled only when:
 *
 * 1. it is for the **active** configuration ([GzacAccess]), not just any
 *    stored one (403 otherwise; 503 while none is active);
 * 2. the user token is verified for that configuration ([UserVerifier]:
 *    from its cache, else by GZAC's introspection; 401 when refused, also
 *    for a token refused moments ago, 503 when GZAC cannot be asked). A
 *    call that needs an introspection is first counted against the client
 *    address and the configuration ([DataRateLimiter]), so made-up tokens
 *    cannot flood GZAC or crowd out users whose token is cached; every call
 *    of the verified user then against the user's limit (429 over a limit);
 * 3. the [DataAccessPolicy] has a route for it and the user meets its
 *    requirement (plan dossier readable, admin role; 403 otherwise).
 *
 * The call is then forwarded inside the container to the REST handler of
 * its path, as the verified user ([PdcaUserAuthentication]) and marked with
 * a [DataDispatch]; only such a forward gets past `SecurityConfig` to
 * `/api/v1/...` and the register proxy. The handler's status and body are
 * the answer of `/data`, which GZAC hands back to the iframe unchanged.
 */
@RestController
class PluginDataController(
    private val gzacAccess: GzacAccess,
    private val userVerifier: UserVerifier,
    private val accessPolicy: DataAccessPolicy,
    private val rateLimiter: DataRateLimiter,
    private val securityContextRepository: SecurityContextRepository,
    private val objectMapper: ObjectMapper
) {

    @PostMapping("/plugins/${PluginHostController.PLUGIN_ID}/{version}/data")
    fun handle(
        @PathVariable version: String,
        request: HttpServletRequest,
        response: HttpServletResponse
    ) {
        if (request.queryString != null) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Ongeldige aanvraag")
        val data = readRequest(request)

        val active = when (val resolution = gzacAccess.resolve()) {
            is GzacAccess.Resolution.Available -> resolution.access.configuration
            is GzacAccess.Resolution.Unavailable -> throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, resolution.reason)
        }
        if (data.configurationId != active.configId) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Deze PDCA-configuratie is niet de actieve configuratie van de app")
        }
        val userToken = data.userToken?.takeIf { it.isNotBlank() }
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Niet aangemeld")
        val user = userVerifier.verify(userToken, active.configId) {
            rateLimiter.acquireForIntrospection(active.configId, request.remoteAddr ?: "unknown")
        }
        rateLimiter.acquireForUser(active.configId, user.login)

        val method = data.method?.uppercase()?.takeIf { it in METHODS }
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Ongeldige aanvraag")
        val path = data.path ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Ongeldige aanvraag")
        val call = DataCall(method, path, data.query ?: emptyMap(), data.body?.takeUnless { it.isNull }, data.context ?: emptyMap())
        val allowed = accessPolicy.resolve(call)
        userVerifier.authorize(user, allowed.requirement)

        // The forward runs inside this request's security filter pass, so the
        // verified user goes into the current context as well as the request.
        val securityContext = SecurityContextImpl(PdcaUserAuthentication(user))
        SecurityContextHolder.setContext(securityContext)
        securityContextRepository.saveContext(securityContext, request, response)
        request.setAttribute(DataDispatch.ATTRIBUTE, DataDispatch(user, call, allowed))
        val body = call.body?.let { objectMapper.writeValueAsBytes(it) } ?: ByteArray(0)
        request.getRequestDispatcher(call.path + queryString(call.query))
            .forward(DispatchedRequest(request, call.method, body), response)
    }

    private fun readRequest(request: HttpServletRequest): PluginDataRequest {
        val bytes = request.inputStream.readNBytes(MAX_BODY_BYTES + 1)
        if (bytes.size > MAX_BODY_BYTES) throw ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "De aanvraag is te groot")
        return try {
            objectMapper.readValue(bytes, PluginDataRequest::class.java)
        } catch (e: JsonProcessingException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Ongeldige aanvraag")
        }
    }

    private fun queryString(query: Map<String, String>): String =
        if (query.isEmpty()) "" else query.entries.joinToString("&", prefix = "?") { (key, value) ->
            "${URLEncoder.encode(key, Charsets.UTF_8)}=${URLEncoder.encode(value, Charsets.UTF_8)}"
        }

    companion object {
        const val MAX_BODY_BYTES = 1024 * 1024
        private val METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE")
    }
}
