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

package com.ritense.pdca.config

import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.charset.StandardCharsets.UTF_8
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM for the serviceTokens at rest. The key is derived from the
 * configured secret with PBKDF2-HMAC-SHA256 (fixed, app-specific salt: the
 * same secret must give the same key on every start). A stored value is
 * `v1:` + base64(12-byte random IV + ciphertext + tag).
 */
class ServiceTokenCipher(secret: String) {

    private val key = SecretKeySpec(
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(secret.toCharArray(), SALT, ITERATIONS, KEY_BITS))
            .encoded,
        "AES"
    )
    private val random = SecureRandom()

    fun encrypt(plaintext: String): String {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv)) }
        return PREFIX + Base64.getEncoder().encodeToString(iv + cipher.doFinal(plaintext.toByteArray(UTF_8)))
    }

    /** The plaintext, or null for a value that is not ours or does not decrypt with this key. */
    fun decrypt(stored: String): String? {
        if (!stored.startsWith(PREFIX)) return null
        return try {
            val bytes = Base64.getDecoder().decode(stored.removePrefix(PREFIX))
            val cipher = Cipher.getInstance(TRANSFORMATION)
                .apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES)) }
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    private companion object {
        const val PREFIX = "v1:"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val KEY_BITS = 256
        const val ITERATIONS = 210_000
        val SALT = "pdca-service-token".toByteArray(UTF_8)
    }
}

@Configuration
class ServiceTokenCipherConfig {

    private val log = LoggerFactory.getLogger(ServiceTokenCipherConfig::class.java)

    @Bean
    fun serviceTokenCipher(properties: PdcaProperties): ServiceTokenCipher {
        if (properties.encryption.isLocalDevDefault) {
            log.warn(
                "pdca.encryption.secret (PDCA_ENCRYPTION_SECRET) is not set: the serviceTokens are encrypted " +
                    "with the local-dev default secret. Set a secret of your own outside local development."
            )
        }
        return ServiceTokenCipher(properties.encryption.secret)
    }
}
