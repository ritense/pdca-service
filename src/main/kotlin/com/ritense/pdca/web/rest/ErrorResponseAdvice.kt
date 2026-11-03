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

package com.ritense.pdca.web.rest

import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

/**
 * The app's error responses: always `{"detail": "<Dutch message>"}`, the
 * shape the frontend shows. A deliberate [ResponseStatusException] carries
 * its reason, failed bean validation the constraints' own Dutch messages (the services' fixed user messages, including those of the
 * register gateways); Spring MVC's own errors get a fixed message per
 * status; anything else is a 500 with a fixed message, logged with its
 * stack trace here and never passed on.
 */
@RestControllerAdvice
class ErrorResponseAdvice : ResponseEntityExceptionHandler() {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(e: Exception): ResponseEntity<Any> {
        log.error("Unhandled error", e)
        return detail(HttpStatus.INTERNAL_SERVER_ERROR, UNEXPECTED)
    }

    override fun handleMethodArgumentNotValid(
        ex: MethodArgumentNotValidException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest
    ): ResponseEntity<Any> {
        val messages = ex.bindingResult.allErrors.mapNotNull { it.defaultMessage }.distinct()
        return detail(HttpStatus.BAD_REQUEST, "Ongeldige invoer: ${messages.joinToString("; ")}")
    }

    override fun handleExceptionInternal(
        ex: Exception,
        body: Any?,
        headers: HttpHeaders,
        statusCode: HttpStatusCode,
        request: WebRequest
    ): ResponseEntity<Any> {
        val message = (ex as? ResponseStatusException)?.reason?.takeIf { it.isNotBlank() }
            ?: genericMessage(statusCode)
        if (statusCode.is5xxServerError && ex !is ResponseStatusException) log.error("Request failed", ex)
        return ResponseEntity.status(statusCode)
            .headers(headers)
            .contentType(MediaType.APPLICATION_JSON)
            .body(mapOf("detail" to message))
    }

    private fun detail(status: HttpStatus, message: String): ResponseEntity<Any> =
        ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(mapOf("detail" to message))

    private fun genericMessage(status: HttpStatusCode): String = when (status.value()) {
        400 -> "Ongeldige aanvraag"
        401 -> "Niet aangemeld"
        403 -> "Geen toegang"
        404 -> "Niet gevonden"
        405 -> "Deze methode wordt hier niet ondersteund"
        406, 415 -> "Dit formaat wordt niet ondersteund"
        409 -> "De aanvraag botst met de huidige toestand"
        in 400..499 -> "Ongeldige aanvraag"
        503 -> "De dienst is tijdelijk niet beschikbaar; probeer het later opnieuw"
        else -> UNEXPECTED
    }

    private companion object {
        const val UNEXPECTED = "Er ging iets mis in PDCA; probeer het later opnieuw"
    }
}
