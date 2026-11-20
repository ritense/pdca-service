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

import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals
import kotlin.test.assertIs

class HostSignatureVerifierTest {

    private val secret = "test-host-secret-0123456789"
    private val now = Instant.parse("2026-11-02T10:00:00Z")
    private val verifier = HostSignatureVerifier(secret, Duration.ofMinutes(5), Clock.fixed(now, ZoneOffset.UTC))
    private val body = """{"serviceToken":"x","gzacBaseUrl":"http://gzac.test"}""".toByteArray()

    @Test
    fun `accepts the signature GZAC computes`() {
        val timestamp = now.toString()
        val signature = gzacSignature("POST", "/api/host/configurations/c1", timestamp, body)

        assertEquals(HostSignatureVerifier.Result.Valid, verifier.verify("POST", "/api/host/configurations/c1", timestamp, signature, body))
    }

    @Test
    fun `refuses missing headers, a wrong secret, another path and a tampered body`() {
        val timestamp = now.toString()
        val signature = gzacSignature("POST", "/api/host/configurations/c1", timestamp, body)

        assertIs<HostSignatureVerifier.Result.Invalid>(verifier.verify("POST", "/api/host/configurations/c1", null, signature, body))
        assertIs<HostSignatureVerifier.Result.Invalid>(verifier.verify("POST", "/api/host/configurations/c1", timestamp, null, body))
        assertIs<HostSignatureVerifier.Result.Invalid>(
            verifier.verify("POST", "/api/host/configurations/c1", timestamp, gzacSignature("POST", "/api/host/configurations/c1", timestamp, body, "other-secret-0123456"), body)
        )
        assertIs<HostSignatureVerifier.Result.Invalid>(verifier.verify("POST", "/api/host/configurations/c2", timestamp, signature, body))
        assertIs<HostSignatureVerifier.Result.Invalid>(verifier.verify("POST", "/api/host/configurations/c1", timestamp, signature, body + ' '.code.toByte()))
    }

    @Test
    fun `refuses a timestamp outside the window`() {
        val old = now.minus(Duration.ofMinutes(6)).toString()
        val future = now.plus(Duration.ofMinutes(6)).toString()

        assertIs<HostSignatureVerifier.Result.Invalid>(verifier.verify("GET", "/api/host/plugins", old, gzacSignature("GET", "/api/host/plugins", old, ByteArray(0)), ByteArray(0)))
        assertIs<HostSignatureVerifier.Result.Invalid>(verifier.verify("GET", "/api/host/plugins", future, gzacSignature("GET", "/api/host/plugins", future, ByteArray(0)), ByteArray(0)))
        assertIs<HostSignatureVerifier.Result.Invalid>(verifier.verify("GET", "/api/host/plugins", "yesterday", "00", ByteArray(0)))
    }

    @Test
    fun `refuses a replayed state-changing request but not a repeated read`() {
        val timestamp = now.toString()
        val push = gzacSignature("POST", "/api/host/configurations/c1", timestamp, body)
        val read = gzacSignature("GET", "/api/host/plugins", timestamp, ByteArray(0))

        assertEquals(HostSignatureVerifier.Result.Valid, verifier.verify("POST", "/api/host/configurations/c1", timestamp, push, body))
        assertIs<HostSignatureVerifier.Result.Invalid>(verifier.verify("POST", "/api/host/configurations/c1", timestamp, push, body))
        assertEquals(HostSignatureVerifier.Result.Valid, verifier.verify("GET", "/api/host/plugins", timestamp, read, ByteArray(0)))
        assertEquals(HostSignatureVerifier.Result.Valid, verifier.verify("GET", "/api/host/plugins", timestamp, read, ByteArray(0)))
    }

    companion object {
        /** The signature as GZAC's ExternalPluginHmacSigner computes it. */
        fun gzacSignature(method: String, path: String, timestamp: String, body: ByteArray, key: String = "test-host-secret-0123456789"): String {
            val bodyHash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(body))
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key.toByteArray(), "HmacSHA256"))
            return java.util.HexFormat.of().formatHex(mac.doFinal("${method.uppercase()}\n$path\n$timestamp\n$bodyHash".toByteArray()))
        }
    }
}
