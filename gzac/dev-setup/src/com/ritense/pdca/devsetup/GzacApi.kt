package com.ritense.pdca.devsetup

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.MissingNode
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers
import java.time.Duration
import java.util.UUID

/** Where GZAC and its Keycloak run and who logs in; local-dev defaults, overridable by environment. */
data class Settings(
    val gzacUrl: String,
    val keycloakRealmUrl: String,
    val clientId: String,
    val username: String,
    val password: String,
) {
    companion object {
        fun fromEnvironment(environment: Map<String, String> = System.getenv()) = Settings(
            gzacUrl = (environment["GZAC_DEV_URL"] ?: "http://localhost:8080").trimEnd('/'),
            keycloakRealmUrl = (environment["GZAC_DEV_KEYCLOAK_URL"] ?: "http://localhost:8081/auth/realms/valtimo").trimEnd('/'),
            clientId = environment["GZAC_DEV_CLIENT_ID"] ?: "valtimo-console",
            username = environment["GZAC_DEV_USERNAME"] ?: "admin",
            password = environment["GZAC_DEV_PASSWORD"] ?: "admin",
        )
    }
}

class ApiResponse(val status: Int, val body: String) {
    val ok get() = status in 200..299

    fun json(): JsonNode = if (body.isBlank()) MissingNode.getInstance() else runCatching { Json.mapper.readTree(body) }
        .getOrDefault(MissingNode.getInstance())

    fun excerpt(): String = body.replace(Regex("\\s+"), " ").take(400)

    fun orThrow(what: String): ApiResponse =
        if (ok) this else throw SetupException("$what: HTTP $status ${excerpt()}".trim())
}

/** One part of a multipart/form-data request. */
class Part(val name: String, val content: ByteArray, val contentType: String, val fileName: String? = null)

/**
 * GZAC's REST API, called as the local dev administrator: a password-grant
 * token of the dev Keycloak, the way GZAC's frontend logs in.
 */
class GzacApi(private val settings: Settings) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private var token: String? = null
    private var tokenFetchedAt = 0L

    val gzacUrl get() = settings.gzacUrl

    /** Whether GZAC answers its public ping. */
    fun reachable(): Boolean = isUp("${settings.gzacUrl}/api/v1/ping")

    /** Whether GZAC answers its ping and Keycloak lets the dev administrator log in. */
    fun ready(): Boolean = reachable() && runCatching { accessToken() }.isSuccess

    /** Whether [url] answers 2xx without authentication. */
    fun isUp(url: String): Boolean = runCatching {
        val request = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(5)).GET().build()
        http.send(request, BodyHandlers.discarding()).statusCode() in 200..299
    }.getOrDefault(false)

    fun get(path: String) = send("GET", path, null)
    fun post(path: String, body: Any?) = send("POST", path, body)
    fun put(path: String, body: Any?) = send("PUT", path, body)
    fun patch(path: String, body: Any?) = send("PATCH", path, body)

    fun send(method: String, path: String, body: Any?): ApiResponse {
        val builder = authorized(path).header("Accept", "application/json")
        val publisher = if (body == null) BodyPublishers.noBody() else {
            builder.header("Content-Type", "application/json")
            BodyPublishers.ofString(Json.mapper.writeValueAsString(body))
        }
        return execute(builder.method(method, publisher))
    }

    fun multipart(path: String, parts: List<Part>): ApiResponse {
        val boundary = "pdca-dev-setup-${UUID.randomUUID()}"
        val bytes = ByteArrayOutputStream()
        parts.forEach { part ->
            val disposition = "form-data; name=\"${part.name}\"" + (part.fileName?.let { "; filename=\"$it\"" } ?: "")
            bytes.write("--$boundary\r\nContent-Disposition: $disposition\r\nContent-Type: ${part.contentType}\r\n\r\n".toByteArray())
            bytes.write(part.content)
            bytes.write("\r\n".toByteArray())
        }
        bytes.write("--$boundary--\r\n".toByteArray())
        val builder = authorized(path)
            .header("Accept", "application/json")
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .POST(BodyPublishers.ofByteArray(bytes.toByteArray()))
        return execute(builder)
    }

    private fun authorized(path: String) = HttpRequest.newBuilder(URI("${settings.gzacUrl}$path"))
        .timeout(Duration.ofSeconds(60))
        .header("Authorization", "Bearer ${accessToken()}")

    private fun execute(builder: HttpRequest.Builder): ApiResponse {
        val response = try {
            http.send(builder.build(), BodyHandlers.ofString())
        } catch (e: java.io.IOException) {
            throw SetupException("GZAC at ${settings.gzacUrl} did not answer (${e.message ?: e::class.simpleName})")
        }
        return ApiResponse(response.statusCode(), response.body() ?: "")
    }

    /** The dev administrator's access token; Keycloak issues them for five minutes, so one is reused for four. */
    private fun accessToken(): String {
        token?.takeIf { System.currentTimeMillis() - tokenFetchedAt < TOKEN_REUSE_MILLIS }?.let { return it }
        val form = mapOf(
            "grant_type" to "password",
            "client_id" to settings.clientId,
            "username" to settings.username,
            "password" to settings.password,
            "scope" to "openid",
        ).entries.joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, Charsets.UTF_8)}" }
        val tokenUrl = "${settings.keycloakRealmUrl}/protocol/openid-connect/token"
        val request = HttpRequest.newBuilder(URI(tokenUrl))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(BodyPublishers.ofString(form))
        val response = try {
            http.send(request.build(), BodyHandlers.ofString())
        } catch (e: java.io.IOException) {
            throw SetupException("Keycloak at $tokenUrl did not answer (${e.message ?: e::class.simpleName})")
        }
        if (response.statusCode() != 200) {
            throw SetupException(
                "Keycloak refused the login of '${settings.username}' (client ${settings.clientId}) at $tokenUrl: " +
                    "HTTP ${response.statusCode()} ${response.body().take(200)}"
            )
        }
        val accessToken = Json.mapper.readTree(response.body()).path("access_token").asText()
        token = accessToken
        tokenFetchedAt = System.currentTimeMillis()
        return accessToken
    }

    private companion object {
        const val TOKEN_REUSE_MILLIS = 4 * 60 * 1000L
    }
}

/** Shortcuts for reading GZAC's JSON answers. */
fun JsonNode.text(field: String): String? = get(field)?.takeUnless { it.isNull }?.asText()
fun JsonNode.items(): List<JsonNode> = if (isArray) toList() else emptyList()
