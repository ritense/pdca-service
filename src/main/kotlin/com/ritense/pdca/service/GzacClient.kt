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
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

/**
 * Calls GZAC itself, e.g. to create a dossier for a plan (plan = dossier 1:1;
 * "los een plan aanmaken" levert alsnog een dossier op). Authentication, in
 * order of preference:
 *
 *  1. the serviceToken + gzacBaseUrl pushed with the plugin configuration
 *     (URL-app contract, persisted in [ConfigurationStore]). Het manifest
 *     declareert hiervoor capability `gzac_api` plus de benodigde endpoints
 *     onder `permissions.endpoints`; GZAC handhaaft die allowlist voor de
 *     serviceToken (PBAC geldt daar niet) zodra de beheerder de footprint
 *     heeft geaccepteerd;
 *  2. `pdca.gzac.static-token`;
 *  3. Keycloak client credentials (`pdca.gzac.token-url` + client id/secret) —
 *     dev-fallback zonder pluginconfiguratie. NB: het service-account heeft
 *     dan ROLE_USER/ROLE_ADMIN in het GZAC-realm nodig (PBAC).
 */
@Service
class GzacClient(
    private val configurationStore: ConfigurationStore,
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
            log.warn("GZAC dossier aanmaken mislukt voor case '$caseDefinitionKey': ${e.message}")
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

    /** Dossier (document) uit GZAC, incl. `content` — voor de onderwater-koppeling via planId. */
    fun getDocument(documentId: String): Map<*, *> {
        val (baseUrl, token) = resolveAccess()
        return try {
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
    }

    /**
     * Version tag of the case definition behind [caseDefinitionKey], read from
     * the document definition's blueprint (`/api/v1/document-definition` — a
     * non-management endpoint, dus grantbaar voor de serviceToken). For the
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

    private fun resolveAccess(): Pair<String, String> {
        val gzac = properties.gzac
        val pushed = configurationStore.latestWithGzacAccess()
        pushed?.let { return it.gzacBaseUrl.trimEnd('/') to it.serviceToken }
        val baseUrl = gzac?.baseUrl?.trimEnd('/')
            ?: throw ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Geen GZAC-toegang: geen pluginconfiguratie gepusht en geen pdca.gzac.base-url geconfigureerd"
            )
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
