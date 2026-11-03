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

import org.springframework.http.HttpStatus
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.authority.AuthorityUtils
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.server.ResponseStatusException
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat

/**
 * A GZAC user verified on `/data`: the login GZAC's introspection returned
 * for the downscoped user token, the configuration the token belongs to and
 * the token itself, kept for the checks the app makes as this user (dossier
 * access, admin role). The token never leaves the request and is never
 * printed.
 */
class PdcaUser(
    val login: String,
    val configurationId: String,
    val token: String,
    val tokenExpiresAt: Instant
) {
    /** SHA-256 of the token: the cache key of everything learned about this token. */
    val tokenHash: String by lazy { hashToken(token) }

    override fun toString(): String = "PdcaUser(login=$login, configurationId=$configurationId)"

    companion object {
        fun hashToken(token: String): String =
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)))
    }
}

/** The authentication of a request dispatched from `/data`. */
class PdcaUserAuthentication(private val user: PdcaUser) :
    AbstractAuthenticationToken(AuthorityUtils.createAuthorityList(ROLE)) {

    init {
        isAuthenticated = true
    }

    override fun getCredentials(): Any? = null
    override fun getPrincipal(): PdcaUser = user
    override fun getName(): String = user.login

    companion object {
        const val ROLE = "ROLE_PDCA_USER"
    }
}

/** The verified user of the current request; handlers reached through `/data` always have one. */
object CurrentUser {
    fun get(): PdcaUser? = (SecurityContextHolder.getContext().authentication as? PdcaUserAuthentication)?.principal

    fun require(): PdcaUser = get() ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Niet aangemeld")
}
