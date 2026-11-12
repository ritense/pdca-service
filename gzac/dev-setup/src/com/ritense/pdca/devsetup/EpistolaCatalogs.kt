package com.ritense.pdca.devsetup

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers
import java.time.Duration
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The Epistola Suite's REST API, called with the tenant's API key. */
class EpistolaSuite(private val values: EpistolaValues) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    val baseUrl = values.baseUrl.trimEnd('/')
    val tenantPath = "/tenants/${values.tenantId}"

    /** The answer to a GET of [path], or null when the Suite does not answer. */
    fun tryGet(path: String): ApiResponse? = try {
        send(request(path).GET())
    } catch (e: IOException) {
        null
    }

    fun get(path: String): ApiResponse = tryGet(path) ?: throw unreachable()

    fun post(path: String, body: Any): ApiResponse = sendOrThrow(
        request(path).header("Content-Type", MEDIA_TYPE).POST(BodyPublishers.ofString(Json.mapper.writeValueAsString(body)))
    )

    fun multipart(path: String, parts: List<Part>): ApiResponse {
        val body = MultipartBody(parts)
        return sendOrThrow(request(path).header("Content-Type", body.contentType).POST(BodyPublishers.ofByteArray(body.bytes)))
    }

    private fun request(path: String) = HttpRequest.newBuilder(URI("$baseUrl$path"))
        .timeout(Duration.ofSeconds(60))
        .header("Authorization", "ApiKey ${values.apiKey}")
        .header("Accept", MEDIA_TYPE)

    private fun sendOrThrow(builder: HttpRequest.Builder): ApiResponse = try {
        send(builder)
    } catch (e: IOException) {
        throw unreachable()
    }

    private fun send(builder: HttpRequest.Builder): ApiResponse {
        val response = http.send(builder.build(), BodyHandlers.ofString())
        return ApiResponse(response.statusCode(), response.body() ?: "")
    }

    private fun unreachable() = SetupException(
        "the Epistola Suite at $baseUrl does not answer (PDCA_EPISTOLA_URL sets another address); start it, then re-run ./gradlew gzacDevSetup"
    )

    private companion object {
        const val MEDIA_TYPE = "application/vnd.epistola.v1+json"
    }
}

/**
 * An Epistola catalog of `gzac/epistola/catalogs/<name>`: `catalog.json`
 * with the catalog's slug and release version, and its `resources/`.
 */
class EpistolaCatalog(private val directory: File) {
    private val descriptor = Json.mapper.readTree(directory.resolve("catalog.json"))

    val slug: String = descriptor.path("catalog").text("slug")
        ?: throw SetupException("${directory.name}/catalog.json names no catalog.slug")
    val version: String = descriptor.path("release").text("version")
        ?: throw SetupException("${directory.name}/catalog.json names no release.version")

    /** The catalog as the Suite imports it: catalog.json at the root and every file below resources/. */
    fun zip(): ByteArray {
        val files = listOf(directory.resolve("catalog.json")) +
            directory.resolve("resources").walkTopDown().filter { it.isFile && it.name != ".DS_Store" }.sortedBy { it.path }
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            files.forEach { file ->
                zip.putNextEntry(ZipEntry(file.relativeTo(directory).invariantSeparatorsPath))
                zip.write(file.readBytes())
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}

/**
 * Puts the document templates of `gzac/epistola/catalogs` into the Epistola
 * Suite tenant of the dev values: imports each catalog (authored, merged)
 * and releases it with the version of its catalog.json. Every import makes
 * new template versions in the Suite, so a catalog whose release the Suite
 * already has is left alone; a changed catalog needs a higher
 * release.version.
 */
class EpistolaCatalogs(private val values: DevValues, private val repoDir: File) {
    private val suite = EpistolaSuite(values.epistola)
    private val catalogsPath = "${suite.tenantPath}/catalogs"

    fun apply() {
        val released = releasedVersions(suite.get("$catalogsPath$LIST_QUERY").orThrow("Listing the Epistola catalogs"))
        catalogs().forEach { catalog ->
            val label = "Epistola catalog ${catalog.slug} ${catalog.version}"
            val current = released[catalog.slug]
            when {
                current == catalog.version -> Console.info("$label: present")
                current != null && compareVersions(current, catalog.version) > 0 -> throw SetupException(
                    "$label: the Suite (tenant ${values.epistola.tenantId}) already has the newer release $current; " +
                        "raise release.version in gzac/epistola/catalogs/${catalog.slug}/catalog.json above it"
                )
                else -> {
                    importAndRelease(catalog)
                    Console.info("$label: imported and released ${catalog.version}")
                }
            }
        }
    }

    /** The catalogs whose release the Suite does not have, or why the Suite cannot tell. */
    fun missing(): List<String> {
        val response = suite.tryGet("$catalogsPath$LIST_QUERY") ?: return listOf("Epistola (not reachable at ${suite.baseUrl})")
        if (!response.ok) return listOf("Epistola (HTTP ${response.status} at ${suite.baseUrl}$catalogsPath)")
        val released = releasedVersions(response)
        return catalogs().mapNotNull { catalog ->
            releaseState(released[catalog.slug], catalog.version)?.let { "Epistola catalog ${catalog.slug} ${catalog.version} ($it)" }
        }
    }

    private fun catalogs() = values.epistola.catalogs.map { EpistolaCatalog(repoDir.resolve("$DIRECTORY/$it")) }

    private fun importAndRelease(catalog: EpistolaCatalog) {
        suite.multipart(
            "$catalogsPath/import",
            listOf(
                Part("file", catalog.zip(), "application/zip", "${catalog.slug}.zip"),
                Part.text("catalogType", "AUTHORED"),
                Part.text("authoredMode", "MERGE"),
            ),
        ).orThrow("Importing Epistola catalog ${catalog.slug}")
        val response = suite.post("$catalogsPath/${catalog.slug}/release", mapOf("releaseVersion" to catalog.version))
        if (response.status == 400 && response.body.contains("catalog-release-version-invalid")) {
            throw SetupException(
                "Epistola catalog ${catalog.slug} imported, but the Suite refuses release ${catalog.version}: it must be greater than " +
                    "the catalog's last release. Raise release.version in gzac/epistola/catalogs/${catalog.slug}/catalog.json and re-run"
            )
        }
        response.orThrow("Releasing Epistola catalog ${catalog.slug} ${catalog.version}")
    }

    companion object {
        const val DIRECTORY = "gzac/epistola/catalogs"
        private const val LIST_QUERY = "?size=100"

        /** The released version per catalog slug of the Suite's catalog list; null for a catalog never released. */
        fun releasedVersions(response: ApiResponse): Map<String, String?> =
            response.json().path("items").items().filter { it.text("slug") != null }.associate { it.text("slug")!! to it.text("releasedVersion") }

        /** What is wrong with the Suite's [released] version of a catalog whose catalog.json says [version]; null when equal. */
        fun releaseState(released: String?, version: String): String? = when {
            released == version -> null
            released == null || compareVersions(released, version) < 0 -> "not released"
            else -> "the Suite has the newer release $released"
        }

        /** Compares dotted versions part by part, numerically where both parts are numbers. */
        fun compareVersions(a: String, b: String): Int {
            val left = a.split('.')
            val right = b.split('.')
            for (i in 0 until maxOf(left.size, right.size)) {
                val x = left.getOrElse(i) { "0" }
                val y = right.getOrElse(i) { "0" }
                val compared = (x.toIntOrNull() to y.toIntOrNull()).let { (m, n) -> if (m != null && n != null) m.compareTo(n) else x.compareTo(y) }
                if (compared != 0) return compared
            }
            return 0
        }
    }
}
