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

import com.ritense.pdca.service.GzacClient
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Who the user on `/data` is and what GZAC lets them do, asked of GZAC with
 * the user's own token and cached briefly so a page load's fan-out of calls
 * costs one round trip per question:
 *
 * - the introspection per token (keyed by its SHA-256), for [USER_TTL] and
 *   never beyond the token's expiry; a token GZAC refused (or that has
 *   expired) for [REFUSED_TTL], so repeating it costs no further
 *   introspection;
 * - dossier access per (login, dossier) for [DOSSIER_TTL], only when granted;
 * - the admin role per token for [ADMIN_TTL] (both answers: the roles are
 *   fixed for a token's lifetime).
 *
 * Every question fails closed: GZAC unreachable is a 503, never a pass.
 */
@Component
class UserVerifier(
    private val gzacClient: GzacClient,
    private val clock: Clock = Clock.systemUTC()
) {
    private val users = ExpiringCache<PdcaUser>()
    private val refused = ExpiringCache<Boolean>()
    private val dossierAccess = ExpiringCache<Boolean>()
    private val admins = ExpiringCache<Boolean>()

    /**
     * The verified user behind [userToken], which must belong to
     * [activeConfigurationId]; else 401. [beforeIntrospection] runs only
     * when the token is not cached and GZAC is about to be asked (the rate
     * limit of introspections); it may refuse the call by throwing.
     */
    fun verify(userToken: String, activeConfigurationId: String, beforeIntrospection: () -> Unit = {}): PdcaUser {
        val key = PdcaUser.hashToken(userToken)
        val now = clock.instant()
        val user = users.get(key, now) ?: run {
            if (refused.get(key, now) != null) throw ResponseStatusException(HttpStatus.UNAUTHORIZED, ONGELDIG)
            beforeIntrospection()
            val introspection = gzacClient.introspectUserToken(userToken)
            val expiresAt = introspection?.expiresAt ?: now.plus(USER_TTL)
            if (introspection == null || !expiresAt.isAfter(now)) {
                refused.put(key, true, now.plus(REFUSED_TTL), now)
                throw ResponseStatusException(HttpStatus.UNAUTHORIZED, ONGELDIG)
            }
            PdcaUser(introspection.subject, introspection.configurationId, userToken, expiresAt)
                .also { users.put(key, it, minOf(expiresAt, now.plus(USER_TTL)), now) }
        }
        if (user.configurationId != activeConfigurationId) throw ResponseStatusException(HttpStatus.UNAUTHORIZED, ONGELDIG)
        return user
    }

    /** Throws 403 unless [user] meets [requirement]. */
    fun authorize(user: PdcaUser, requirement: Requirement) {
        when (requirement) {
            Requirement.AnyUser -> Unit
            Requirement.Admin ->
                if (!isAdmin(user)) throw ResponseStatusException(HttpStatus.FORBIDDEN, "Alleen beheerders mogen dit")
            is Requirement.Dossiers ->
                if (requirement.documentIds.isEmpty() || requirement.documentIds.any { !canReadDossier(user, it) }) {
                    throw ResponseStatusException(HttpStatus.FORBIDDEN, "Geen toegang tot dit dossier")
                }
        }
    }

    fun canReadDossier(user: PdcaUser, dossierId: String): Boolean {
        val key = "${user.login}\u0000$dossierId"
        val now = clock.instant()
        dossierAccess.get(key, now)?.let { return it }
        val allowed = gzacClient.userCanReadDocument(user.token, dossierId)
        if (allowed) dossierAccess.put(key, true, now.plus(DOSSIER_TTL), now)
        return allowed
    }

    fun isAdmin(user: PdcaUser): Boolean {
        val now = clock.instant()
        admins.get(user.tokenHash, now)?.let { return it }
        return gzacClient.userIsAdmin(user.token)
            .also { admins.put(user.tokenHash, it, minOf(user.tokenExpiresAt, now.plus(ADMIN_TTL)), now) }
    }

    companion object {
        val USER_TTL: Duration = Duration.ofSeconds(60)
        val REFUSED_TTL: Duration = Duration.ofSeconds(30)
        val DOSSIER_TTL: Duration = Duration.ofMinutes(2)
        val ADMIN_TTL: Duration = Duration.ofMinutes(5)
        const val ONGELDIG = "Je sessie bij GZAC is verlopen of ongeldig; laad de pagina opnieuw"
    }
}

/** A small map with per-entry expiry; expired entries are dropped when it grows. */
internal class ExpiringCache<V : Any>(private val maxEntries: Int = 10_000) {
    private data class Entry<V>(val value: V, val expiresAt: Instant)

    private val entries = ConcurrentHashMap<String, Entry<V>>()

    fun get(key: String, now: Instant): V? = entries[key]?.takeIf { it.expiresAt.isAfter(now) }?.value

    fun put(key: String, value: V, expiresAt: Instant, now: Instant) {
        if (!expiresAt.isAfter(now)) return
        if (entries.size >= maxEntries) {
            entries.entries.removeIf { !it.value.expiresAt.isAfter(now) }
            if (entries.size >= maxEntries) entries.clear()
        }
        entries[key] = Entry(value, expiresAt)
    }
}
