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

import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.authority.AuthorityUtils
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.util.AntPathMatcher
import org.springframework.web.filter.OncePerRequestFilter
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Verifies GZAC's request signature: HMAC-SHA256 with the shared host
 * secret over `{METHOD}\n{path}\n{timestamp}\n{sha256-hex(body)}`, sent as
 * lowercase hex in `X-Valtimo-Signature` with the ISO-8601 timestamp in
 * `X-Valtimo-Timestamp`. The path is the request path without query string
 * (GZAC never signs the query). A timestamp outside [tolerance] is refused,
 * and a signature already accepted for a state-changing request is refused
 * as a replay.
 */
class HostSignatureVerifier(
    secret: String,
    private val tolerance: Duration,
    private val clock: Clock = Clock.systemUTC()
) {
    private val key = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), ALGORITHM)
    private val acceptedSignatures = ConcurrentHashMap<String, Instant>()

    sealed interface Result {
        data object Valid : Result
        data class Invalid(val reason: String) : Result
    }

    fun verify(method: String, path: String, timestamp: String?, signature: String?, body: ByteArray): Result {
        if (timestamp.isNullOrBlank() || signature.isNullOrBlank()) return Result.Invalid("signature headers missing")
        val signedAt = try {
            Instant.parse(timestamp)
        } catch (e: DateTimeParseException) {
            return Result.Invalid("timestamp unreadable")
        }
        val now = clock.instant()
        if (Duration.between(signedAt, now).abs() > tolerance) return Result.Invalid("timestamp outside the window")

        val expected = sign(method, path, timestamp, body)
        val given = signature.trim().lowercase().toByteArray(Charsets.US_ASCII)
        if (!MessageDigest.isEqual(expected.toByteArray(Charsets.US_ASCII), given)) return Result.Invalid("signature mismatch")

        if (method.uppercase() !in READ_METHODS) {
            purgeExpired(now)
            if (acceptedSignatures.putIfAbsent(expected, signedAt) != null) return Result.Invalid("signature replayed")
        }
        return Result.Valid
    }

    fun sign(method: String, path: String, timestamp: String, body: ByteArray): String {
        val bodyHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body))
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(key)
        val payload = "${method.uppercase()}\n$path\n$timestamp\n$bodyHash"
        return HexFormat.of().formatHex(mac.doFinal(payload.toByteArray(Charsets.UTF_8)))
    }

    private fun purgeExpired(now: Instant) {
        val horizon = now.minus(tolerance.multipliedBy(2))
        acceptedSignatures.entries.removeIf { it.value.isBefore(horizon) }
    }

    companion object {
        const val SIGNATURE_HEADER = "X-Valtimo-Signature"
        const val TIMESTAMP_HEADER = "X-Valtimo-Timestamp"
        private const val ALGORITHM = "HmacSHA256"
        private val READ_METHODS = setOf("GET", "HEAD")
    }
}

/** The caller of a request GZAC signed with the host secret. */
class GzacHostAuthentication : AbstractAuthenticationToken(AuthorityUtils.createAuthorityList(ROLE)) {
    init {
        isAuthenticated = true
    }

    override fun getCredentials(): Any? = null
    override fun getPrincipal(): Any = "gzac"

    companion object {
        const val ROLE = "ROLE_GZAC_HOST"
    }
}

/**
 * Authenticates the requests GZAC signs ([SIGNED_PATTERNS]): the body is
 * read once (at most [MAX_BODY_BYTES]) and handed on in a
 * [CachedBodyRequest], so the controller reads the exact bytes that were
 * verified. A valid signature makes the request a [GzacHostAuthentication];
 * anything else is answered 401 without a body, and the reason is logged
 * without the signature.
 */
class HostSignatureFilter(private val verifier: HostSignatureVerifier) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(HostSignatureFilter::class.java)
    private val matcher = AntPathMatcher()

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = pathOf(request)
        return SIGNED_PATTERNS.none { matcher.match(it, path) }
    }

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val body = readBounded(request.inputStream)
        if (body == null) {
            log.warn("Refused {} {}: body larger than {} bytes", request.method, pathOf(request), MAX_BODY_BYTES)
            response.status = HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE
            return
        }
        val result = verifier.verify(
            request.method,
            pathOf(request),
            request.getHeader(HostSignatureVerifier.TIMESTAMP_HEADER),
            request.getHeader(HostSignatureVerifier.SIGNATURE_HEADER),
            body
        )
        if (result is HostSignatureVerifier.Result.Invalid) {
            log.warn("Refused unsigned or wrongly signed {} {}: {}", request.method, pathOf(request), result.reason)
            response.status = HttpServletResponse.SC_UNAUTHORIZED
            return
        }
        SecurityContextHolder.getContext().authentication = GzacHostAuthentication()
        filterChain.doFilter(CachedBodyRequest(request, body), response)
    }

    private fun readBounded(input: InputStream): ByteArray? {
        val bytes = input.readNBytes(MAX_BODY_BYTES + 1)
        return if (bytes.size > MAX_BODY_BYTES) null else bytes
    }

    companion object {
        /** What GZAC signs and the app implements: the host API and the plugin actions. */
        val SIGNED_PATTERNS = listOf("/api/host/**", "/plugins/*/*/actions/**")
        const val MAX_BODY_BYTES = 1024 * 1024

        fun pathOf(request: HttpServletRequest): String = request.requestURI.removePrefix(request.contextPath)
    }
}

/** A request whose body has been read already; serves those bytes again. */
class CachedBodyRequest(request: HttpServletRequest, private val body: ByteArray) : HttpServletRequestWrapper(request) {

    override fun getInputStream(): ServletInputStream = ByteArrayServletInputStream(body)

    override fun getReader(): BufferedReader =
        BufferedReader(InputStreamReader(ByteArrayInputStream(body), characterEncoding ?: Charsets.UTF_8.name()))

    override fun getContentLength(): Int = body.size

    override fun getContentLengthLong(): Long = body.size.toLong()
}

internal class ByteArrayServletInputStream(bytes: ByteArray) : ServletInputStream() {
    private val input = ByteArrayInputStream(bytes)

    override fun read(): Int = input.read()

    override fun read(b: ByteArray, off: Int, len: Int): Int = input.read(b, off, len)

    override fun isFinished(): Boolean = input.available() == 0

    override fun isReady(): Boolean = true

    override fun setReadListener(listener: ReadListener?) = throw UnsupportedOperationException()
}
