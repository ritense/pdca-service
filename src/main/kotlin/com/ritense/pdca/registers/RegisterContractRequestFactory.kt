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

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.ritense.pdca.plugin.GzacAccess
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.client.AbstractClientHttpRequest
import org.springframework.http.client.ClientHttpRequest
import org.springframework.http.client.ClientHttpRequestFactory
import org.springframework.http.client.ClientHttpResponse
import org.springframework.web.client.RestClient
import org.springframework.web.util.UriUtils
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8

/**
 * Transport of a register's RestClient: every request to
 * [register] becomes a call of GZAC's contract broker
 * (`POST /api/v1/plugin-contract/call`, contract [Register.contract]) with
 * the pushed serviceToken, and GZAC forwards it to the configuration the
 * administrator bound (the register's plugin). The register's status code and
 * body come back unchanged, so callers of the RestClient see the same
 * responses as the register API itself.
 *
 * Only the path and query of the request URI count — any base URL works,
 * including the register's own absolute `next` links:
 * `<apiPath>/<resource>[/<uuid>]` + method → operation (GET → list or get,
 * POST → create, PATCH → update, DELETE → delete); a request the contract
 * has no operation for (PUT, or a method on the wrong path shape) is
 * answered 400 without calling GZAC. Which resources and operations a
 * contract serves is up to its register plugin (`plugins/<name>`), which
 * refuses anything outside its allowlist. The calls go through the shared [ContractCallLimiter]: at most
 * a configured number at a time, and a call GZAC refuses with
 * `TOO_MANY_CONCURRENT_CALLS` is retried a few times after a jittered pause.
 * Contract and transport failures become a JSON `{"detail": ...}`
 * response with a fixed Dutch message (502, or 503 when the register is not
 * bound, its provider is unavailable or GZAC is busy), the shape the
 * frontend shows, marked with [RegisterErrors.CONTRACT_ERROR_HEADER] so the
 * gateways can tell it from the register's own answer. Downstream bodies and
 * transport messages are logged, never passed on.
 */
class RegisterContractRequestFactory(
    private val register: Register,
    private val gzacAccess: GzacAccess,
    private val objectMapper: ObjectMapper,
    restClientBuilder: RestClient.Builder,
    private val limiter: ContractCallLimiter
) : ClientHttpRequestFactory {

    private val log = LoggerFactory.getLogger(javaClass)
    private val gzac = restClientBuilder.clone().build()
    private val path = Regex("""^(?:.*)?${Regex.escape(register.apiPath)}/([a-z][a-z-]*)(?:/([^/]+))?/?$""")
    private val name = register.displayName

    data class ContractCall(val operation: String, val payload: Map<String, Any>)

    override fun createRequest(uri: URI, httpMethod: HttpMethod): ClientHttpRequest = ContractRequest(uri, httpMethod)

    private inner class ContractRequest(private val uri: URI, private val method: HttpMethod) : AbstractClientHttpRequest() {
        private val body = ByteArrayOutputStream()

        override fun getMethod(): HttpMethod = method
        override fun getURI(): URI = uri
        override fun getBodyInternal(headers: HttpHeaders): OutputStream = body
        override fun executeInternal(headers: HttpHeaders): ClientHttpResponse = execute(uri, method, body.toByteArray())
    }

    fun execute(uri: URI, method: HttpMethod, body: ByteArray): ClientHttpResponse {
        val requestBody = try {
            body.takeIf { it.isNotEmpty() }?.let { objectMapper.readTree(it) }
        } catch (e: Exception) {
            return detail(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Ongeldige JSON in het verzoek aan $name")
        }
        val call = toContractCall(uri, method, requestBody)
            ?: return detail(
                HttpStatus.BAD_REQUEST, "UNSUPPORTED_REQUEST",
                "$method ${uri.rawPath} wordt niet ondersteund via het $name-contract (${register.contract})"
            )
        val access = when (val resolution = gzacAccess.resolve()) {
            is GzacAccess.Resolution.Available -> resolution.access
            is GzacAccess.Resolution.Unavailable -> return detail(
                HttpStatus.SERVICE_UNAVAILABLE, "NOT_BOUND",
                if (resolution.nonePushed) "$name is niet gekoppeld: activeer PDCA in GZAC en koppel de $name plugin"
                else resolution.reason
            )
        }
        if (register.contract !in access.configuration.grantedContracts) {
            return detail(
                HttpStatus.SERVICE_UNAVAILABLE, "NOT_BOUND",
                "$name is niet gekoppeld: koppel in GZAC de PDCA-configuratie aan de $name plugin"
            )
        }

        val request = objectMapper.writeValueAsBytes(
            mapOf("contract" to register.contract, "operation" to call.operation, "payload" to call.payload)
        )
        var attempt = 0
        while (true) {
            val (status, responseBody) = try {
                limiter.withPermit {
                    gzac.post()
                        .uri("${access.baseUrl}/api/v1/plugin-contract/call")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer ${access.serviceToken}")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(request)
                        .exchange { _, response -> response.statusCode.value() to response.body.readAllBytes() }!!
                }
            } catch (e: ContractCallLimiter.Busy) {
                return detail(
                    HttpStatus.SERVICE_UNAVAILABLE, "PDCA_BUSY",
                    "PDCA verwerkt al het maximale aantal gelijktijdige registeraanroepen; probeer het zo opnieuw"
                )
            } catch (e: Exception) {
                log.warn("{} contract call to GZAC at {} failed", name, access.baseUrl, e)
                return detail(HttpStatus.SERVICE_UNAVAILABLE, "GZAC_UNREACHABLE", "GZAC is niet bereikbaar voor $name")
            }
            // GZAC refuses a call over its concurrency limit before delivering
            // it, so retrying is safe for every operation.
            if (status in 200..299 && isTooManyConcurrentCalls(responseBody) && attempt < limiter.maxRetries) {
                attempt++
                limiter.pauseBeforeRetry(attempt)
                continue
            }
            return answer(status, responseBody)
        }
    }

    private fun isTooManyConcurrentCalls(body: ByteArray): Boolean = try {
        val response = objectMapper.readTree(body)
        response.path("status").asText() == "error" && response.path("errorCode").asText() == TOO_MANY_CONCURRENT_CALLS
    } catch (e: Exception) {
        false
    }

    private fun answer(status: Int, responseBody: ByteArray): ClientHttpResponse {
        return when (status) {
            in 200..299 -> fromContractResponse(responseBody)
            401, 403 -> detail(
                HttpStatus.SERVICE_UNAVAILABLE, "GZAC_REFUSED",
                "GZAC weigert de $name-aanroep (HTTP $status): het servicetoken van de PDCA-configuratie " +
                    "is verlopen of ongeldig. Controleer of PDCA in GZAC actief en geaccepteerd is."
            )
            404 -> detail(
                HttpStatus.SERVICE_UNAVAILABLE, "CONTRACTS_UNSUPPORTED",
                "Deze GZAC-versie ondersteunt geen plugincontracten (HTTP 404 op /api/v1/plugin-contract/call), " +
                    "die PDCA nodig heeft voor $name"
            )
            else -> {
                log.warn("GZAC answered HTTP {} on the {} contract call: {}", status, name, String(responseBody, UTF_8).take(1000))
                detail(HttpStatus.BAD_GATEWAY, "GZAC_ERROR", "GZAC gaf HTTP $status op de $name-aanroep")
            }
        }
    }

    /** The contract operation and payload for a request to the register API, or null when the contract has none. */
    fun toContractCall(uri: URI, method: HttpMethod, body: JsonNode?): ContractCall? {
        val match = path.matchEntire(uri.rawPath ?: return null) ?: return null
        val resource = match.groupValues[1]
        val uuid = match.groupValues[2].takeIf { it.isNotEmpty() }?.let { UriUtils.decode(it, UTF_8) }
        val operation = when (method) {
            HttpMethod.GET -> if (uuid == null) "list" else "get"
            HttpMethod.POST -> "create".takeIf { uuid == null }
            HttpMethod.PATCH -> "update".takeIf { uuid != null }
            HttpMethod.DELETE -> "delete".takeIf { uuid != null }
            else -> null
        } ?: return null

        val payload = linkedMapOf<String, Any>("resource" to resource)
        uuid?.let { payload["uuid"] = it }
        query(uri).takeIf { it.isNotEmpty() }?.let { payload["query"] = it }
        if (operation == "create" || operation == "update") body?.let { payload["body"] = it }
        return ContractCall(operation, payload)
    }

    private fun query(uri: URI): Map<String, String> =
        (uri.rawQuery ?: "").split('&')
            .filter { it.isNotEmpty() }
            .associate { part ->
                val (key, value) = part.split('=', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                URLDecoder.decode(key, UTF_8) to URLDecoder.decode(value, UTF_8)
            }

    private fun fromContractResponse(bytes: ByteArray): ClientHttpResponse {
        val response = try {
            objectMapper.readTree(bytes)
        } catch (e: Exception) {
            return detail(HttpStatus.BAD_GATEWAY, "GZAC_UNREADABLE", "GZAC gaf een onleesbaar antwoord op de $name-aanroep")
        }
        return when (response.path("status").asText()) {
            "completed" -> {
                val result = response.path("result")
                val status = result.path("status").takeIf { it.isInt }?.asInt()?.takeIf { it in 100..599 }
                    ?: return detail(
                        HttpStatus.BAD_GATEWAY, "PROVIDER_INVALID_RESULT",
                        "De $name plugin gaf geen geldige HTTP-status terug"
                    )
                val body = result.path("body").takeUnless { it.isMissingNode || it.isNull }
                JsonResponse(HttpStatusCode.valueOf(status), body?.let { objectMapper.writeValueAsBytes(it) } ?: ByteArray(0))
            }
            "error" -> contractError(response.path("errorCode").asText("ONBEKEND"), response.path("errorMessage").asText(""))
            else -> detail(HttpStatus.BAD_GATEWAY, "GZAC_UNREADABLE", "GZAC gaf een onbekend antwoord op de $name-aanroep")
        }
    }

    /** A contract error: the code is shown (it names what to fix), the provider's message is only logged. */
    private fun contractError(errorCode: String, errorMessage: String): ClientHttpResponse {
        log.warn("{} contract call failed: [{}] {}", name, errorCode, errorMessage)
        return when (errorCode) {
            "NO_PROVIDER_BOUND" -> detail(
                HttpStatus.SERVICE_UNAVAILABLE, errorCode,
                "$name is niet gekoppeld: koppel in GZAC de PDCA-configuratie aan de $name plugin ($errorCode)"
            )
            "PROVIDER_UNAVAILABLE" -> detail(
                HttpStatus.SERVICE_UNAVAILABLE, errorCode, "De $name plugin is in GZAC niet beschikbaar ($errorCode)"
            )
            "PROVIDER_UNREACHABLE" -> detail(
                HttpStatus.SERVICE_UNAVAILABLE, errorCode, "De plugin host van de $name plugin is niet bereikbaar ($errorCode)"
            )
            TOO_MANY_CONCURRENT_CALLS -> detail(
                HttpStatus.SERVICE_UNAVAILABLE, errorCode,
                "GZAC verwerkt al het maximale aantal gelijktijdige aanroepen van PDCA; probeer het zo opnieuw ($errorCode)"
            )
            "CONTRACT_DEADLINE_EXCEEDED" -> detail(
                HttpStatus.SERVICE_UNAVAILABLE, errorCode, "$name antwoordde niet op tijd; probeer het later opnieuw ($errorCode)"
            )
            "PROVIDER_ERROR" -> detail(HttpStatus.BAD_GATEWAY, errorCode, "De $name plugin meldt een fout ($errorCode)")
            else -> detail(HttpStatus.BAD_GATEWAY, errorCode, "$name-aanroep via GZAC mislukt ($errorCode)")
        }
    }

    private companion object {
        const val TOO_MANY_CONCURRENT_CALLS = "TOO_MANY_CONCURRENT_CALLS"
    }

    private fun detail(status: HttpStatus, errorCode: String, message: String): ClientHttpResponse =
        JsonResponse(status, objectMapper.writeValueAsBytes(mapOf("detail" to message)), errorCode)

    private class JsonResponse(
        private val status: HttpStatusCode,
        private val bytes: ByteArray,
        private val contractError: String? = null
    ) : ClientHttpResponse {
        override fun getStatusCode(): HttpStatusCode = status
        override fun getStatusText(): String = HttpStatus.resolve(status.value())?.reasonPhrase ?: ""
        override fun getHeaders(): HttpHeaders = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            contentLength = bytes.size.toLong()
            contractError?.let { set(RegisterErrors.CONTRACT_ERROR_HEADER, it) }
        }
        override fun getBody(): InputStream = ByteArrayInputStream(bytes)
        override fun close() {}
    }
}
