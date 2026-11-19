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

import com.ritense.pdca.plugin.GzacAccess
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

/**
 * Calls GZAC itself, e.g. to read an intake dossier or to create the plan
 * dossier of a new plan (plan = dossier 1:1). It authenticates with the
 * serviceToken and gzacBaseUrl GZAC pushed with the plugin configuration
 * (URL-app contract, persisted in `ConfigurationStore`) of the active
 * configuration ([GzacAccess]); without one there is no access (503).
 * The manifest declares capability `gzac_api` plus the required endpoints
 * under `permissions.endpoints`; GZAC enforces that allowlist for the
 * serviceToken (PBAC does not apply there) once the administrator has
 * accepted the footprint. The checks about a user (token introspection,
 * dossier access, admin role) are made with that user's own token instead.
 * Failure reasons are fixed Dutch messages; the underlying error is logged.
 */
@Service
class GzacClient(
    private val gzacAccess: GzacAccess,
    restClientBuilder: RestClient.Builder
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val gzac = restClientBuilder.clone().build()

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
            gzac.post()
                .uri("$baseUrl/api/v1/process-document/operation/new-document-and-start-process")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map::class.java)
        } catch (e: Exception) {
            log.warn("Creating a GZAC dossier failed for case '{}'", caseDefinitionKey, e)
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "GZAC dossier aanmaken mislukt")
        }

        val document = result?.get("document") as? Map<*, *>
        val errors = (result?.get("errors") as? List<*>)?.filterNotNull() ?: emptyList<Any>()
        val documentId = document?.get("id") as? String
            ?: run {
                log.warn("GZAC returned no dossier for case '{}': {}", caseDefinitionKey, errors)
                throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "GZAC gaf geen dossier terug voor dossiertype '$caseDefinitionKey'")
            }
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
                gzac.get()
                    .uri("$baseUrl/api/management/v1/document-definition?page=$page&size=100")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                    .retrieve()
                    .body(Map::class.java)
            } catch (e: Exception) {
                log.warn("Listing GZAC document definitions failed", e)
                throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "GZAC dossierdefinities ophalen mislukt")
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
        val definition = documentDefinition(baseUrl, token, caseDefinitionKey)
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
            gzac.get()
                .uri("$baseUrl/api/v1/document/{id}", documentId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .retrieve()
                .toBodilessEntity()
            true
        } catch (e: HttpClientErrorException.NotFound) {
            false
        } catch (e: Exception) {
            log.warn("Checking GZAC dossier {} failed", documentId, e)
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "GZAC dossier $documentId controleren mislukt")
        }
    }

    /**
     * The ids of the dossiers of [caseDefinitionKey] whose document holds
     * [value] in the top-level field [field], oldest first, through GZAC's
     * document search (a granted endpoint; PBAC does not apply to the
     * service token). GZAC matches a field case-insensitively on "contains",
     * so this is meant for values that cannot occur inside another value,
     * such as a uuid. GZAC refusing the call (the grant not yet accepted)
     * or failing is a 502: an empty answer must mean "none".
     */
    fun findDossierIds(caseDefinitionKey: String, field: String, value: String): List<String> {
        val (baseUrl, token) = resolveAccess()
        val body = mapOf(
            "documentDefinitionName" to caseDefinitionKey,
            "otherFilters" to listOf(mapOf("path" to "$.$field", "value" to value))
        )
        val page = try {
            gzac.post()
                .uri("$baseUrl/api/v1/document-search?page=0&size=$DOSSIER_SEARCH_SIZE&sort=createdOn,asc")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map::class.java)
        } catch (e: HttpClientErrorException) {
            log.warn("Searching GZAC dossiers of case '{}' answered {}", caseDefinitionKey, e.statusCode.value())
            throw ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                if (e.statusCode.value() == 401 || e.statusCode.value() == 403) {
                    "GZAC staat het zoeken naar dossiers niet toe. Accepteer in GZAC de permissies van de " +
                        "PDCA-plugin opnieuw (endpoint POST /api/v1/document-search)."
                } else {
                    "GZAC dossiers zoeken mislukt"
                }
            )
        } catch (e: Exception) {
            log.warn("Searching GZAC dossiers of case '{}' failed", caseDefinitionKey, e)
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "GZAC dossiers zoeken mislukt")
        }
        return (page?.get("content") as? List<*>).orEmpty()
            .mapNotNull { (it as? Map<*, *>)?.get("id") as? String }
    }

    /**
     * Dossier (document) from GZAC, incl. `content` — for the intake prefill
     * and the plan creation from an intake.
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
            gzac.get()
                .uri("$baseUrl/api/v1/document/{id}", documentId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .retrieve()
                .body(Map::class.java)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Dossier $documentId niet gevonden in GZAC")
        } catch (e: ResponseStatusException) {
            throw e
        } catch (e: HttpClientErrorException.NotFound) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Dossier $documentId niet gevonden in GZAC")
        } catch (e: Exception) {
            log.warn("Reading GZAC dossier {} failed", documentId, e)
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "GZAC dossier $documentId ophalen mislukt")
        }
        if (document["content"] is Map<*, *>) return document
        return document + ("content" to inspectionContent(baseUrl, token, documentId))
    }

    private fun inspectionContent(baseUrl: String, token: String, documentId: String): Map<*, *> {
        val inspected = try {
            gzac.get()
                .uri("$baseUrl/api/management/v1/case/{id}", documentId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .retrieve()
                .body(Map::class.java)
        } catch (e: Exception) {
            log.warn("GZAC case inspection for dossier {} failed", documentId, e)
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

    /**
     * The document definition name of a dossier, read from the document
     * itself (`definitionId.name`; for the PDCA cases equal to the case
     * definition key). This is how aanmaak-instrument learns which dossier
     * type is calling it, without the process having to say so.
     */
    fun documentDefinitionName(documentId: String): String {
        val (baseUrl, token) = resolveAccess()
        val document = try {
            gzac.get()
                .uri("$baseUrl/api/v1/document/{id}", documentId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .retrieve()
                .body(Map::class.java)
        } catch (e: Exception) {
            log.warn("Reading GZAC dossier {} failed", documentId, e)
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "GZAC dossier $documentId ophalen mislukt")
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
        val definition = documentDefinition(baseUrl, token, caseDefinitionKey)
        val blueprint = ((definition?.get("id") as? Map<*, *>)?.get("blueprintId") as? Map<*, *>)
        return blueprint?.get("blueprintVersionTag") as? String
            ?: throw ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "Documentdefinitie '$caseDefinitionKey' heeft geen case-blueprint versionTag (is de zip geïmporteerd?)"
            )
    }

    /** What GZAC's introspection says about a user token. */
    data class UserTokenIntrospection(val subject: String, val configurationId: String, val expiresAt: Instant?)

    /**
     * Introspects a downscoped user token (forwarded by the GZAC frontend on
     * a `/data` call) at the active configuration's GZAC: the token is its
     * own credential there. Null when GZAC refuses the token (401/403);
     * fails closed with 503 when GZAC cannot be asked or answers
     * unexpectedly, never a pass. Neither the token nor GZAC's answer is
     * logged.
     */
    fun introspectUserToken(userToken: String): UserTokenIntrospection? {
        val baseUrl = gzacAccess.active().baseUrl
        val response = try {
            gzac.get()
                .uri("$baseUrl/api/v1/external-plugin/user-token/introspect")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $userToken")
                .retrieve()
                .body(Map::class.java)
        } catch (e: HttpClientErrorException) {
            if (e.statusCode.value() == 401 || e.statusCode.value() == 403) return null
            log.warn("User token introspection answered {}", e.statusCode.value())
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, GEBRUIKER_NIET_TE_CONTROLEREN)
        } catch (e: Exception) {
            log.warn("User token introspection failed: {}", e.javaClass.simpleName)
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, GEBRUIKER_NIET_TE_CONTROLEREN)
        }
        val subject = response?.get("subject") as? String
        val configurationId = response?.get("configurationId")?.toString()
        if (subject.isNullOrBlank() || configurationId.isNullOrBlank()) {
            log.warn("User token introspection returned no subject or configuration")
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, GEBRUIKER_NIET_TE_CONTROLEREN)
        }
        val expiresAt = (response["expiresAt"] as? String)?.let { runCatching { Instant.parse(it) }.getOrNull() }
        return UserTokenIntrospection(subject, configurationId, expiresAt)
    }

    /**
     * Can the user behind [userToken] read the dossier? Asked as the user
     * (`GET /api/v1/document/{id}`), so GZAC applies the user's PBAC. A
     * refusal or unknown dossier (4xx) is false; an outage fails closed (503).
     */
    fun userCanReadDocument(userToken: String, documentId: String): Boolean {
        val baseUrl = gzacAccess.active().baseUrl
        return try {
            gzac.get()
                .uri("$baseUrl/api/v1/document/{id}", documentId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer $userToken")
                .retrieve()
                .toBodilessEntity()
            true
        } catch (e: HttpClientErrorException) {
            false
        } catch (e: Exception) {
            log.warn("Checking GZAC dossier {} as the user failed: {}", documentId, e.javaClass.simpleName)
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, GEBRUIKER_NIET_TE_CONTROLEREN)
        }
    }

    /**
     * Is the user behind [userToken] a GZAC administrator? The introspection
     * carries no roles, so the user token asks an endpoint GZAC only opens to
     * `ROLE_ADMIN`: the management list of document definitions (one of the
     * granted endpoints). 401/403 is false; an outage fails closed (503).
     */
    fun userIsAdmin(userToken: String): Boolean {
        val baseUrl = gzacAccess.active().baseUrl
        return try {
            gzac.get()
                .uri("$baseUrl/api/management/v1/document-definition?page=0&size=1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $userToken")
                .retrieve()
                .toBodilessEntity()
            true
        } catch (e: HttpClientErrorException) {
            if (e.statusCode.value() == 401 || e.statusCode.value() == 403) return false
            log.warn("Checking the GZAC admin role answered {}", e.statusCode.value())
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, GEBRUIKER_NIET_TE_CONTROLEREN)
        } catch (e: Exception) {
            log.warn("Checking the GZAC admin role failed: {}", e.javaClass.simpleName)
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, GEBRUIKER_NIET_TE_CONTROLEREN)
        }
    }

    private fun documentDefinition(baseUrl: String, token: String, caseDefinitionKey: String): Map<*, *>? = try {
        gzac.get()
            .uri("$baseUrl/api/v1/document-definition/{key}", caseDefinitionKey)
            .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            .retrieve()
            .body(Map::class.java)
    } catch (e: Exception) {
        log.warn("Reading GZAC document definition '{}' failed", caseDefinitionKey, e)
        throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "GZAC documentdefinitie '$caseDefinitionKey' ophalen mislukt")
    }

    private fun resolveAccess(): Pair<String, String> = gzacAccess.active().let { it.baseUrl to it.serviceToken }

    private companion object {
        const val GEBRUIKER_NIET_TE_CONTROLEREN = "GZAC is niet bereikbaar om de gebruiker te controleren"

        /** Matches a dossier search reads; more than a few means duplicates, and the oldest is what counts. */
        const val DOSSIER_SEARCH_SIZE = 10
    }
}
