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

import com.ritense.pdca.config.PdcaProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Which GZAC instances the app trusts and which browser origins belong to
 * them.
 *
 * - [isAllowedBaseUrl]: with `pdca.gzac.allowed-base-urls` set, only those
 *   gzacBaseUrls are accepted in a configuration push or an instance
 *   announcement; without it every signed push is accepted.
 * - [origins]: the GZAC frontend origins that may frame the bundles
 *   (`frame-ancestors`) and call `/data` (CORS): the configured
 *   `pdca.gzac.frontend-origins` plus what GZAC announces per instance with
 *   `PUT /api/host/gzac-instances` (re-announced on every discovery poll,
 *   so kept in memory only).
 */
@Component
class GzacInstances(properties: PdcaProperties) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val configuredOrigins = properties.gzac.frontendOrigins.mapNotNull { normalizeOrigin(it) }.toSet()
    private val allowedBaseUrls = properties.gzac.allowedBaseUrls.map { normalizeBaseUrl(it) }.filter { it.isNotEmpty() }.toSet()
    private val announced = ConcurrentHashMap<String, Set<String>>()

    fun isAllowedBaseUrl(gzacBaseUrl: String?): Boolean =
        allowedBaseUrls.isEmpty() || (gzacBaseUrl != null && normalizeBaseUrl(gzacBaseUrl) in allowedBaseUrls)

    fun origins(): Set<String> = configuredOrigins + announced.values.flatten()

    /** Records the frontend origins GZAC announced for [gzacBaseUrl]; malformed entries are skipped. */
    fun announce(gzacBaseUrl: String, frontendOrigins: List<String>) {
        val origins = frontendOrigins.take(MAX_ORIGINS_PER_INSTANCE).mapNotNull { normalizeOrigin(it) }.toSet()
        val previous = announced.put(normalizeBaseUrl(gzacBaseUrl), origins)
        if (previous != origins) log.info("GZAC instance {} announced frontend origins {}", normalizeBaseUrl(gzacBaseUrl), origins)
    }

    companion object {
        private const val MAX_ORIGINS_PER_INSTANCE = 20

        fun normalizeBaseUrl(url: String): String = url.trim().trimEnd('/').lowercase()

        /** `scheme://host[:port]` for an http(s) origin, else null. */
        fun normalizeOrigin(value: String): String? {
            val uri = runCatching { URI.create(value.trim()) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return null
            val host = uri.host?.lowercase() ?: return null
            if (!uri.rawPath.isNullOrEmpty() && uri.rawPath != "/") return null
            if (uri.rawQuery != null || uri.rawFragment != null || uri.rawUserInfo != null) return null
            return if (uri.port == -1) "$scheme://$host" else "$scheme://$host:${uri.port}"
        }
    }
}
