/*
 * Copyright 2015-2024 Ritense BV, the Netherlands.
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

package com.ritense.pdca.service

import com.ritense.pdca.config.PdcaProperties
import com.ritense.pdca.plugin.ConfigurationStore
import com.ritense.pdca.plugin.PushedGzacAccess
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

/**
 * Calls GZAC itself, e.g. to create a dossier for a plan (plan = dossier 1:1;
 * creating a standalone plan still yields a dossier). Authentication, in
 * order of preference:
 *
 *  1. the serviceToken + gzacBaseUrl pushed with the plugin configuration
 *     (URL-app contract, persisted in [ConfigurationStore], resolved by
 *     [PushedGzacAccess]). The manifest
 *     declares capability `gzac_api` plus the required endpoints under
 *     `permissions.endpoints`; GZAC enforces that allowlist for the
 *     serviceToken (PBAC does not apply there) once the administrator has
 *     accepted the footprint;
 *  2. `pdca.gzac.static-token`;
 *  3. Keycloak client credentials (`pdca.gzac.token-url` + client id/secret) —
 *     dev fallback without a plugin configuration. Note: the service account
 *     then needs ROLE_USER/ROLE_ADMIN in the GZAC realm (PBAC).
 */
@Service
class GzacClient(
    private val configurationStore: ConfigurationStore,
    private val pushedGzacAccess: PushedGzacAccess,
    private val properties: PdcaProperties,
    private val restClientBuilder: RestClient.Builder
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private var cachedToken: String? = null
    private var cachedTokenExpiry: Instant = Instant.EPOCH

    data class Dossier(val documentId: String)

    /**
     * Creates a dossier (document + started BPMN process) in GZAC. The BPMN
     * process key and document definition name equal the case definition key
     * for the PDCA cases; the case definition version tag is resolved from
     * GZAC's case-definition listing (required by NewDocumentRequest).
     */
    fun createDossier(caseDefinitionKey: String, content: Map<String, Any?>): Dossier {
        val (baseUrl, token) = resolveAccess()
        val body = mapOf(
            "processDefinitionKey" to caseDefinitionKey,
            "request" to mapOf(
                "definition" to caseDefinitionKey,
                "caseDefinitionKey" to caseDefinitionKey,
                "caseDefinitionVersionTag" to caseDefinitionVersionTag(baseUrl, token, caseDefinitionKey),
                "content" to content.filterValues { it != null }
            )
        )

        val result = try {
            restClientBuilder.clone().build()
                .post()
                .uri("$baseUrl/api/v1/process-document/operation/new-document-and-start-process")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map::class.java)
        } catch (e: Exception) {
            log.warn("Creating a GZAC dossier failed for case '$caseDefinitionKey': ${e.message}")
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "GZAC dossier aanmaken mislukt: ${e.message}")
        }

        val document = result?.get("document") as? Map<*, *>
        val errors = (result?.get("errors") as? List<*>)?.filterNotNull() ?: emptyList<Any>()
        val documentId = document?.get("id") as? String
            ?: throw ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "GZAC gaf geen dossier terug voor case '$caseDefinitionKey': $errors"
            )
        return Dossier(documentId)
    }

    /**
     * The case-definition keys in GZAC, for the case-type dropdowns in PDCA
     * Beheer. Deliberately the **management** list: the `/api/v1` variant is
     * PBAC-filtered per caller and hides case types without document
     * permission rules (a freshly imported product case type would be
     * invisible). The endpoint caps its page size regardless of the `size`
     * parameter, so all pages are walked.
     */
    fun caseDefinitionKeys(): List<String> {
        val (baseUrl, token) = resolveAccess()
        val names = mutableListOf<String>()
        var page = 0
        while (page < 20) {
            val body = try {
                restClientBuilder.clone().build()
                    .get()
                    .uri("$baseUrl/api/management/v1/document-definition?page=$page&size=100")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                    .retrieve()
                    .body(Map::class.java)
            } catch (e: Exception) {
                throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "GZAC dossierdefinities ophalen mislukt: ${e.message}")
            }
            val content = body?.get("content") as? List<*> ?: emptyList<Any>()
            names += content.mapNotNull { ((it as? Map<*, *>)?.get("id") as? Map<*, *>)?.get("name") as? String }
            if (content.isEmpty() || body?.get("last") == true) break
            page++
        }
        return names.distinct().sorted()
    }

    /**
     * The document paths (JSON pointers) of a case definition's document
     * schema, for the path suggestions in the prefill mapping of PDCA
     * Beheer. Reads the document definition through the granted
     * document-definition endpoint.
     */
    fun documentSchemaPaths(caseDefinitionKey: String): List<String> {
        val (baseUrl, token) = resolveAccess()
        val definition = try {
            restClientBuilder.clone().build()
                .get()
                .uri("$baseUrl/api/v1/document-definition/$caseDefinitionKey")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .retrieve()
                .body(Map::class.java)
        } catch (e: Exception) {
            throw ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "GZAC documentdefinitie '$caseDefinitionKey' ophalen mislukt: ${e.message}"
            )
        }
        val properties = (definition?.get("schema") as? Map<*, *>)?.get("properties") as? Map<*, *>
            ?: return emptyList()
        return properties.keys.filterIsInstance<String>().map { "/$it" }
    }

    /**
     * Does the dossier (still) exist in GZAC? Only a definitive 404 yields
     * false — connectivity or authorization errors assume nothing (502), so
     * a link is never released because of an outage.
     */
    fun documentExists(documentId: String): Boolean {
        val (baseUrl, token) = resolveAccess()
        return try {
            restClientBuilder.clone().build()
                .get()
                .uri("$baseUrl/api/v1/document/$documentId")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .retrieve()
                .toBodilessEntity()
            true
        } catch (e: HttpClientErrorException.NotFound) {
            false
        } catch (e: Exception) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "GZAC dossier $documentId controleren mislukt: ${e.message}")
        }
    }

    /**
     * Dossier (document) from GZAC, incl. `content` — for the intake prefill
     * and the under-water link via planId.
     *
     * GZAC only serializes `content` on `/api/v1/document/{id}` when
     * `valtimo.includeDocumentContentInResponse` is on (default off). Without
     * it the content is read from the case inspection endpoint, which always
     * includes it (a granted endpoint; PBAC does not apply to the service
     * token). When neither yields content the call fails loudly: an empty
     * prefill would look like an intake without data.
     */
    fun getDocument(documentId: String): Map<*, *> {
        val (baseUrl, token) = resolveAccess()
        val document = try {
            restClientBuilder.clone().build()
                .get()
                .uri("$baseUrl/api/v1/document/$documentId")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .retrieve()
                .body(Map::class.java)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Dossier $documentId niet gevonden in GZAC")
        } catch (e: ResponseStatusException) {
            throw e
        } catch (e: Exception) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "GZAC dossier $documentId ophalen mislukt: ${e.message}")
        }
        if (document["content"] is Map<*, *>) return document
        return document + ("content" to inspectionContent(baseUrl, token, documentId))
    }

    private fun inspectionContent(baseUrl: String, token: String, documentId: String): Map<*, *> {
        val inspected = try {
            restClientBuilder.clone().build()
                .get()
                .uri("$baseUrl/api/management/v1/case/$documentId")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .retrieve()
                .body(Map::class.java)
        } catch (e: Exception) {
            log.warn("GZAC case inspection for dossier $documentId failed: ${e.message}")
            null
        }
        return inspected?.get("content") as? Map<*, *>
            ?: throw ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "GZAC levert de inhoud van dossier $documentId niet mee. Accepteer in GZAC de " +
                    "permissies van de PDCA-plugin opnieuw (endpoint GET /api/management/v1/case/*), " +
                    "of zet valtimo.includeDocumentContentInResponse aan."
            )
    }

    // ------------------------------------------ instrument dossier support

    /** Latest version of a process definition (to find its process links). */
    fun latestProcessDefinitionId(processDefinitionKey: String): String {
        val (baseUrl, token) = resolveAccess()
        val definition = try {
            restClientBuilder.clone().build()
                .get()
                .uri("$baseUrl/api/v1/process/definition/$processDefinitionKey")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .retrieve()
                .body(Map::class.java)
        } catch (e: Exception) {
            throw ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "GZAC procesdefinitie '$processDefinitionKey' ophalen mislukt (is het dossiertype geïmporteerd?): ${e.message}"
            )
        }
        return definition?.get("id") as? String
            ?: throw ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "GZAC kent geen procesdefinitie '$processDefinitionKey' (is het dossiertype geïmporteerd?)"
            )
    }

    /** The process links of a process definition version. */
    fun processLinks(processDefinitionId: String): List<Map<*, *>> {
        val (baseUrl, token) = resolveAccess()
        val links = try {
            restClientBuilder.clone().build()
                .get()
                .uri("$baseUrl/api/v1/process-link?processDefinitionId=$processDefinitionId")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .retrieve()
                .body(List::class.java)
        } catch (e: Exception) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "GZAC proceskoppelingen ophalen mislukt: ${e.message}")
        }
        return links?.filterIsInstance<Map<*, *>>() ?: emptyList()
    }

    /** Version tag of the case definition behind [caseDefinitionKey]. */
    fun caseDefinitionVersionTag(caseDefinitionKey: String): String {
        val (baseUrl, token) = resolveAccess()
        return caseDefinitionVersionTag(baseUrl, token, caseDefinitionKey)
    }

    /**
     * GZAC's plugin-configuration repair for a case definition version: maps
     * dangling or re-targeted plugin references to a configuration. Keys are
     * either a process link id (dangling FIXED link without configuration)
     * or a currently referenced configuration id; values the new
     * configuration id. Also clears the case-configuration issue banner.
     */
    fun resolvePluginConfigurationMappings(
        caseDefinitionKey: String,
        caseDefinitionVersionTag: String,
        mappings: Map<String, String>
    ) {
        val (baseUrl, token) = resolveAccess()
        try {
            restClientBuilder.clone().build()
                .put()
                .uri(
                    "$baseUrl/api/management/v1/case-definition/$caseDefinitionKey" +
                        "/version/$caseDefinitionVersionTag/plugin-configuration-mappings"
                )
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(mappings)
                .retrieve()
                .toBodilessEntity()
        } catch (e: Exception) {
            throw ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "GZAC pluginconfiguratie koppelen op dossiertype '$caseDefinitionKey' mislukt: ${e.message}"
            )
        }
    }

    /**
     * The document definition name of a dossier, read from the document
     * itself (`definitionId.name`; for the PDCA cases equal to the case
     * definition key). This is how aanmaak-instrument learns which dossier
     * type is calling it, without the process having to say so.
     */
    fun documentDefinitionName(documentId: String): String {
        val (baseUrl, token) = resolveAccess()
        val document = try {
            restClientBuilder.clone().build()
                .get()
                .uri("$baseUrl/api/v1/document/$documentId")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .retrieve()
                .body(Map::class.java)
        } catch (e: Exception) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "GZAC dossier $documentId ophalen mislukt: ${e.message}")
        }
        return (document?.get("definitionId") as? Map<*, *>)?.get("name") as? String
            ?: throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "GZAC dossier $documentId draagt geen documentdefinitie")
    }

    /**
     * Version tag of the case definition behind [caseDefinitionKey], read from
     * the document definition's blueprint (`/api/v1/document-definition` — a
     * non-management endpoint, so grantable for the serviceToken). For the
     * PDCA cases the document definition name equals the case definition key.
     */
    private fun caseDefinitionVersionTag(baseUrl: String, token: String, caseDefinitionKey: String): String {
        val definition = try {
            restClientBuilder.clone().build()
                .get()
                .uri("$baseUrl/api/v1/document-definition/$caseDefinitionKey")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .retrieve()
                .body(Map::class.java)
        } catch (e: Exception) {
            throw ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "GZAC documentdefinitie '$caseDefinitionKey' ophalen mislukt: ${e.message}"
            )
        }
        val blueprint = ((definition?.get("id") as? Map<*, *>)?.get("blueprintId") as? Map<*, *>)
        return blueprint?.get("blueprintVersionTag") as? String
            ?: throw ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "Documentdefinitie '$caseDefinitionKey' heeft geen case-blueprint versionTag (is de zip geïmporteerd?)"
            )
    }

    /**
     * Verifies a downscoped user token (forwarded by the GZAC frontend on a
     * `/data` call) against GZAC's introspection endpoint and returns the
     * user's login. The token must belong to [configurationId]. Fails closed:
     * an unreachable GZAC is a 503, never a pass.
     */
    fun introspectUserToken(configurationId: String, userToken: String): String {
        val response = try {
            restClientBuilder.clone().build()
                .get()
                .uri("${baseUrlFor(configurationId)}/api/v1/external-plugin/user-token/introspect")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $userToken")
                .retrieve()
                .body(Map::class.java)
        } catch (e: HttpClientErrorException) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Gebruikerstoken is niet geldig")
        } catch (e: Exception) {
            log.warn("User token introspection failed: ${e.message}")
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "GZAC is niet bereikbaar om de gebruiker te controleren")
        }
        if (response?.get("configurationId")?.toString() != configurationId) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Gebruikerstoken hoort niet bij deze configuratie")
        }
        return response["subject"] as? String
            ?: throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "GZAC gaf geen gebruiker terug")
    }

    /** Can the user behind [userToken] read the dossier? GZAC applies PBAC to user tokens. */
    fun userCanReadDocument(configurationId: String, userToken: String, documentId: String): Boolean =
        try {
            restClientBuilder.clone().build()
                .get()
                .uri("${baseUrlFor(configurationId)}/api/v1/document/$documentId")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $userToken")
                .retrieve()
                .toBodilessEntity()
            true
        } catch (e: HttpClientErrorException) {
            false
        } catch (e: Exception) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "GZAC dossier $documentId controleren mislukt: ${e.message}")
        }

    private fun baseUrlFor(configurationId: String): String {
        val configured = pushedGzacAccess.configuredBaseUrl()
        val pushed = configurationStore.get(configurationId)?.gzacBaseUrl?.trimEnd('/')?.takeIf { it.isNotBlank() }
        return configured ?: pushed
            ?: throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Geen GZAC-adres bekend voor configuratie $configurationId")
    }

    private fun resolveAccess(): Pair<String, String> {
        pushedGzacAccess.latest()?.let { return it.baseUrl to it.serviceToken }
        val gzac = properties.gzac
        val baseUrl = pushedGzacAccess.configuredBaseUrl()
        if (gzac == null || baseUrl == null) {
            throw ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Geen GZAC-toegang: geen pluginconfiguratie gepusht en geen pdca.gzac.base-url geconfigureerd"
            )
        }
        gzac.staticToken?.takeIf { it.isNotBlank() }?.let { return baseUrl to it }
        if (!gzac.tokenUrl.isNullOrBlank()) return baseUrl to clientCredentialsToken(gzac)
        throw ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Geen GZAC-credentials: push een pluginconfiguratie of configureer pdca.gzac (static-token of token-url + client)"
        )
    }

    private fun clientCredentialsToken(gzac: PdcaProperties.GzacApi): String {
        cachedToken?.takeIf { Instant.now().isBefore(cachedTokenExpiry) }?.let { return it }
        val tokenUrl = gzac.tokenUrl ?: throw ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE, "pdca.gzac.token-url ontbreekt voor client credentials"
        )
        val form = LinkedMultiValueMap<String, String>().apply {
            add("grant_type", "client_credentials")
            add("client_id", gzac.clientId ?: "")
            add("client_secret", gzac.clientSecret ?: "")
        }
        val response = try {
            restClientBuilder.clone().build()
                .post()
                .uri(tokenUrl)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(Map::class.java)
        } catch (e: Exception) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "Keycloak token ophalen mislukt: ${e.message}")
        }
        val token = response?.get("access_token") as? String
            ?: throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "Keycloak gaf geen access_token terug")
        val expiresIn = (response["expires_in"] as? Number)?.toLong() ?: 60L
        cachedToken = token
        cachedTokenExpiry = Instant.now().plusSeconds(maxOf(expiresIn - 30, 10))
        return token
    }
}
