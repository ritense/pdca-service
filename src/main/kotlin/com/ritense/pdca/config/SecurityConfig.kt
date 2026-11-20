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

import com.fasterxml.jackson.databind.ObjectMapper
import com.ritense.pdca.plugin.PluginHostController
import com.ritense.pdca.security.DataDispatch
import com.ritense.pdca.security.GzacHostAuthentication
import com.ritense.pdca.security.GzacInstances
import com.ritense.pdca.security.HostSignatureFilter
import com.ritense.pdca.security.HostSignatureVerifier
import com.ritense.pdca.security.PdcaUserAuthentication
import jakarta.servlet.DispatcherType
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.authorization.AuthorizationDecision
import org.springframework.security.authorization.AuthorizationManager
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.intercept.RequestAuthorizationContext
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository
import org.springframework.security.web.context.SecurityContextRepository
import org.springframework.security.web.header.HeaderWriter
import org.springframework.util.AntPathMatcher
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource

/**
 * The app's trust boundary (ARCHITECTURE §4). Every request is one of:
 *
 * - **signed by GZAC** — the host API (`/api/host/...`) and the plugin
 *   actions, verified by [HostSignatureFilter] with the shared host secret;
 * - **a user's call through `/data`** — authenticated by the controller
 *   itself (user-token introspection), then forwarded internally to the
 *   REST handler; only that forward, carrying a [DataDispatch] and the
 *   verified [PdcaUserAuthentication], reaches `/api/v1/...` and the
 *   register proxy;
 * - **public** — `/health`, the manifest the GZAC frontend reads and the
 *   bundle files.
 *
 * Everything else, including a direct call of an API path, is answered 401
 * (403 for an authenticated caller outside its reach). There are no sessions
 * or cookies, so CSRF protection has nothing to protect. Framing is limited
 * to the GZAC frontend origins with CSP `frame-ancestors`; CORS is open only
 * for the public bundle files (to `*`, they are fetched from the sandboxed
 * iframe's opaque origin) and for `/data` (to the GZAC frontend origins).
 */
@Configuration
@EnableWebSecurity
class SecurityConfig(
    private val properties: PdcaProperties,
    private val gzacInstances: GzacInstances,
    private val objectMapper: ObjectMapper
) {

    private val log = LoggerFactory.getLogger(SecurityConfig::class.java)
    private val paths = AntPathMatcher()

    @Bean
    fun securityContextRepository(): SecurityContextRepository = RequestAttributeSecurityContextRepository()

    @Bean
    fun hostSignatureVerifier(): HostSignatureVerifier {
        val secret = properties.host.secret
        require(secret.length >= MIN_SECRET_LENGTH) {
            "pdca.host.secret (PDCA_HOST_SECRET) must be at least $MIN_SECRET_LENGTH characters"
        }
        if (properties.host.isLocalDevDefault) {
            log.warn(
                "pdca.host.secret (PDCA_HOST_SECRET) is not set: GZAC's requests are verified with the local-dev " +
                    "default secret. Set a secret of your own, the same as the PDCA integration's secret in GZAC, " +
                    "outside local development."
            )
        }
        return HostSignatureVerifier(secret, properties.host.signatureTolerance)
    }

    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        securityContextRepository: SecurityContextRepository,
        hostSignatureVerifier: HostSignatureVerifier
    ): SecurityFilterChain {
        val plugin = "/plugins/${PluginHostController.PLUGIN_ID}/*"
        http
            .csrf { it.disable() }
            .cors { it.configurationSource(corsConfigurationSource()) }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .securityContext { it.securityContextRepository(securityContextRepository) }
            .requestCache { it.disable() }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .logout { it.disable() }
            .headers { headers ->
                headers.frameOptions { it.disable() }
                headers.addHeaderWriter(frameAncestors())
            }
            .exceptionHandling { handling ->
                handling.authenticationEntryPoint(HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                handling.accessDeniedHandler { _, response, _ ->
                    response.status = HttpStatus.FORBIDDEN.value()
                    response.contentType = MediaType.APPLICATION_JSON_VALUE
                    objectMapper.writeValue(response.outputStream, mapOf("detail" to "Geen toegang"))
                }
            }
            .addFilterBefore(HostSignatureFilter(hostSignatureVerifier), AnonymousAuthenticationFilter::class.java)
            .authorizeHttpRequests { requests ->
                requests
                    .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                    .dispatcherTypeMatchers(DispatcherType.FORWARD).access(dispatchedFromData())
                    .requestMatchers(HttpMethod.GET, "/health").permitAll()
                    .requestMatchers(HttpMethod.GET, "$plugin/bundles/**", "$plugin/plugin-manifest").permitAll()
                    .requestMatchers(HttpMethod.POST, "$plugin/data").permitAll()
                    .requestMatchers("/api/host/**", "/plugins/*/*/actions/**").hasAuthority(GzacHostAuthentication.ROLE)
                    .anyRequest().denyAll()
            }
        return http.build()
    }

    /** A forward is only allowed when `/data` made it for a verified user. */
    private fun dispatchedFromData() = AuthorizationManager<RequestAuthorizationContext> { authentication, context ->
        AuthorizationDecision(
            DataDispatch.of(context.request) != null && authentication.get() is PdcaUserAuthentication
        )
    }

    private fun frameAncestors() = HeaderWriter { _, response ->
        if (response.getHeader(CSP) == null) {
            val origins = gzacInstances.origins().sorted()
            response.setHeader(CSP, "frame-ancestors " + (origins.joinToString(" ").ifEmpty { "'none'" }))
        }
    }

    private fun corsConfigurationSource() = CorsConfigurationSource { request -> corsFor(request) }

    private fun corsFor(request: HttpServletRequest): CorsConfiguration? {
        val path = request.requestURI.removePrefix(request.contextPath)
        val plugin = "/plugins/${PluginHostController.PLUGIN_ID}/*"
        return when {
            paths.match("$plugin/data", path) -> CorsConfiguration().apply {
                allowedOrigins = gzacInstances.origins().toList()
                allowedMethods = listOf("POST")
                allowedHeaders = listOf("Content-Type")
                maxAge = 600
            }
            paths.match("$plugin/bundles/**", path) || paths.match("$plugin/plugin-manifest", path) -> CorsConfiguration().apply {
                allowedOrigins = listOf("*")
                allowedMethods = listOf("GET")
            }
            else -> null
        }
    }

    private companion object {
        const val CSP = "Content-Security-Policy"
        const val MIN_SECRET_LENGTH = 16
    }
}
