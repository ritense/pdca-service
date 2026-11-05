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

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.nio.charset.StandardCharsets.UTF_8

/** What went wrong with a register call, as far as a caller acts on it. */
enum class RegisterFailure {
    /** The register does not know the object (its own 404). */
    NOT_FOUND,

    /** The register refused the request (its own 4xx, e.g. validation with `invalidParams`). */
    REJECTED,

    /** The register or the contract chain is temporarily unavailable (503): retrying later can help. */
    UNAVAILABLE,

    /** Anything else: an unexpected register answer or a contract error (502). */
    FAILED
}

/**
 * The one exception for failed register calls, thrown by the register
 * gateways. It is a [ResponseStatusException] with a fixed Dutch reason the
 * UI can show; register bodies and transport messages are logged, never
 * part of the reason. The only register text that reaches the reason is a
 * validation error's `invalidParams` (field name + reason), which is meant
 * for the user.
 */
class RegisterException(
    val register: Register,
    val failure: RegisterFailure,
    status: HttpStatus,
    reason: String,
    cause: Throwable? = null
) : ResponseStatusException(status, reason, cause)

/**
 * Error mapping of the register gateways: turns a non-2xx register answer
 * (or a transport failure) into a [RegisterException].
 *
 * Answers synthesised by [RegisterContractRequestFactory] for a failure of
 * the contract chain carry [CONTRACT_ERROR_HEADER]; their `detail` is the
 * factory's own fixed message and becomes the reason (503 stays
 * [RegisterFailure.UNAVAILABLE], e.g. GZAC's `TOO_MANY_CONCURRENT_CALLS`;
 * everything else is [RegisterFailure.FAILED]). Every other answer is the
 * register's own.
 */
object RegisterErrors {

    /** Header on a response the contract transport made up itself; its value is the error code. */
    const val CONTRACT_ERROR_HEADER = "X-Pdca-Contract-Error"

    private const val MAX_INVALID_PARAMS_LENGTH = 300
    private val log = LoggerFactory.getLogger(RegisterErrors::class.java)

    fun fromResponse(
        register: Register,
        status: Int,
        headers: HttpHeaders,
        body: ByteArray,
        objectMapper: ObjectMapper,
        subject: String
    ): RegisterException {
        val name = register.displayName
        val contractError = headers.getFirst(CONTRACT_ERROR_HEADER)
        if (contractError != null) {
            val detail = textField(objectMapper, body, "detail") ?: "$name is niet bereikbaar via GZAC"
            log.warn("{} call for {} failed in the contract chain: [{}] {}", name, subject, contractError, detail)
            return if (status == HttpStatus.SERVICE_UNAVAILABLE.value()) {
                RegisterException(register, RegisterFailure.UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE, detail)
            } else {
                RegisterException(register, RegisterFailure.FAILED, HttpStatus.BAD_GATEWAY, detail)
            }
        }
        log.warn("{} answered HTTP {} for {}: {}", name, status, subject, String(body, UTF_8).take(1000))
        return when (status) {
            404 -> notFound(register, subject)
            401, 403 -> RegisterException(
                register, RegisterFailure.FAILED, HttpStatus.BAD_GATEWAY,
                "De $name plugin heeft geen toegang tot $name; controleer het token in de pluginconfiguratie"
            )
            429, 503 -> unavailable(register)
            in 400..499 -> {
                val invalidParams = invalidParams(objectMapper, body)
                RegisterException(
                    register, RegisterFailure.REJECTED, rejectedStatus(status),
                    "$name heeft het verzoek geweigerd" + (invalidParams?.let { ": $it" } ?: "")
                )
            }
            else -> RegisterException(
                register, RegisterFailure.FAILED, HttpStatus.BAD_GATEWAY,
                "$name gaf een onverwachte fout (HTTP $status)"
            )
        }
    }

    fun notFound(register: Register, subject: String) = RegisterException(
        register, RegisterFailure.NOT_FOUND, HttpStatus.NOT_FOUND,
        "${subject.replaceFirstChar { it.uppercase() }} niet gevonden in ${register.displayName}"
    )

    fun unavailable(register: Register) = RegisterException(
        register, RegisterFailure.UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE,
        "${register.displayName} is tijdelijk niet beschikbaar; probeer het later opnieuw"
    )

    /** A listing with more pages than the gateway reads: failing beats silently working on part of it. */
    fun tooManyPages(register: Register, subject: String, maxPages: Int): RegisterException {
        log.error(
            "{} listing of {} has more than {} pages; refusing to work on a partial list " +
                "(raise pdca.registers.max-pages if this is expected)",
            register.displayName, subject, maxPages
        )
        return RegisterException(
            register, RegisterFailure.FAILED, HttpStatus.BAD_GATEWAY,
            "${register.displayName} gaf meer dan $maxPages pagina's terug voor $subject; " +
                "de lijst is niet volledig gelezen"
        )
    }

    fun unreadable(register: Register, subject: String, cause: Throwable): RegisterException {
        log.warn("{} answer for {} could not be read", register.displayName, subject, cause)
        return RegisterException(
            register, RegisterFailure.FAILED, HttpStatus.BAD_GATEWAY,
            "${register.displayName} gaf een onleesbaar antwoord", cause
        )
    }

    fun transport(register: Register, subject: String, cause: Throwable): RegisterException {
        log.warn("{} call for {} failed", register.displayName, subject, cause)
        return RegisterException(
            register, RegisterFailure.FAILED, HttpStatus.BAD_GATEWAY,
            "${register.displayName}-aanroep mislukt", cause
        )
    }

    /** The register's own refusal keeps its status where the UI can act on it. */
    private fun rejectedStatus(status: Int): HttpStatus = when (status) {
        409 -> HttpStatus.CONFLICT
        422 -> HttpStatus.UNPROCESSABLE_ENTITY
        else -> HttpStatus.BAD_REQUEST
    }

    private fun invalidParams(objectMapper: ObjectMapper, body: ByteArray): String? = try {
        objectMapper.readTree(body).path("invalidParams")
            .takeIf { it.isArray && it.size() > 0 }
            ?.joinToString("; ") { "${it.path("name").asText()}: ${it.path("reason").asText()}" }
            ?.take(MAX_INVALID_PARAMS_LENGTH)
    } catch (e: Exception) {
        null
    }

    private fun textField(objectMapper: ObjectMapper, body: ByteArray, field: String): String? = try {
        objectMapper.readTree(body).path(field).takeIf { it.isTextual }?.asText()
    } catch (e: Exception) {
        null
    }
}
