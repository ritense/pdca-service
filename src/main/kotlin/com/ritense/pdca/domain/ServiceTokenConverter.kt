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

package com.ritense.pdca.domain

import com.ritense.pdca.config.ServiceTokenCipher
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter
import org.slf4j.LoggerFactory

/**
 * Encrypts `plugin_configuration.service_token` at rest ([ServiceTokenCipher]).
 * A stored token that does not decrypt (another secret, or a value from
 * before encryption) reads as absent: the configuration has no GZAC access
 * until GZAC pushes it again, which it does on its next discovery poll.
 * Hibernate creates the converter through Spring, which supplies the cipher.
 */
@Converter
class ServiceTokenConverter(private val cipher: ServiceTokenCipher) : AttributeConverter<String?, String?> {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun convertToDatabaseColumn(attribute: String?): String? =
        attribute?.takeIf { it.isNotBlank() }?.let(cipher::encrypt)

    override fun convertToEntityAttribute(dbData: String?): String? {
        if (dbData.isNullOrBlank()) return null
        return cipher.decrypt(dbData) ?: run {
            log.warn(
                "A stored serviceToken cannot be decrypted (different pdca.encryption.secret?); " +
                    "it counts as absent until GZAC pushes the configuration again"
            )
            null
        }
    }
}
