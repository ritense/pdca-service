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

    fun multipart(path: String, parts: List<Part>): ApiResponse {
        val body = MultipartBody(parts)
        return sendOrThrow(request(path).header("Content-Type", body.contentType).POST(BodyPublishers.ofByteArray(body.bytes)))
    }

    private fun request(path: String) = HttpRequest.newBuilder(URI("$baseUrl$path"))
        .timeout(Duration.ofSeconds(60))
        .header("Authorization", "ApiKey ${values.apiKey}")
        .header("Accept", "$MEDIA_TYPE, application/problem+json")

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
        const val MEDIA_TYPE = "application/vnd.epistola.v2+json"
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
 * Suite tenant of the dev values: imports each catalog, which installs the
 * release of its catalog.json, as `local/<namespace>/<slug>` with the
 * tenant's own namespace. A catalog whose release the Suite already has is
 * left alone; a changed catalog needs a higher release.version, since the
 * Suite refuses a release version it has with other content.
 */
class EpistolaCatalogs(private val values: DevValues, private val repoDir: File) {
    private val suite = EpistolaSuite(values.epistola)
    private val catalogsPath = "${suite.tenantPath}/catalogs"
    private val namespace = namespace(values.epistola.tenantId)

    fun apply() {
        val released = releasedVersions(suite.get("$catalogsPath$LIST_QUERY").orThrow("Listing the Epistola catalogs"), namespace)
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
                    import(catalog)
                    Console.info("$label: imported")
                }
            }
        }
    }

    /** The catalogs whose release the Suite does not have, or why the Suite cannot tell. */
    fun missing(): List<String> {
        val response = suite.tryGet("$catalogsPath$LIST_QUERY") ?: return listOf("Epistola (not reachable at ${suite.baseUrl})")
        if (!response.ok) return listOf("Epistola (HTTP ${response.status} at ${suite.baseUrl}$catalogsPath)")
        val released = releasedVersions(response, namespace)
        return catalogs().mapNotNull { catalog ->
            releaseState(released[catalog.slug], catalog.version)?.let { "Epistola catalog ${catalog.slug} ${catalog.version} ($it)" }
        }
    }

    private fun catalogs() = values.epistola.catalogs.map { EpistolaCatalog(repoDir.resolve("$DIRECTORY/$it")) }

    private fun import(catalog: EpistolaCatalog) {
        val response = suite.multipart(
            "$catalogsPath/import",
            listOf(
                Part("file", catalog.zip(), "application/zip", "${catalog.slug}.zip"),
                Part.text("registry", REGISTRY),
                Part.text("namespace", namespace),
            ),
        )
        if (response.status == 409 && response.body.contains("release-number-taken")) {
            throw SetupException(
                "Epistola catalog ${catalog.slug}: the Suite has release ${catalog.version} with other content. " +
                    "Raise release.version in gzac/epistola/catalogs/${catalog.slug}/catalog.json and re-run"
            )
        }
        response.orThrow("Importing Epistola catalog ${catalog.slug} ${catalog.version}")
    }

    companion object {
        const val DIRECTORY = "gzac/epistola/catalogs"
        private const val LIST_QUERY = "?size=100"

        /** The registry of the organisation's own catalogs, which needs no namespace grant. */
        const val REGISTRY = "local"

        /** The tenant's own namespace: its key, padded with `0` to the 3 characters a namespace needs. */
        fun namespace(tenantId: String): String = tenantId.padEnd(3, '0')

        /**
         * The highest release per catalog key of the Suite's catalog list, for the catalogs of the
         * [REGISTRY] in [namespace]; null for a catalog without a release.
         */
        fun releasedVersions(response: ApiResponse, namespace: String): Map<String, String?> =
            response.json().path("items").items()
                .filter { it.text("registry") == REGISTRY && it.text("namespace") == namespace && it.text("catalogKey") != null }
                .associate { it.text("catalogKey")!! to it.text("highestVersion") }

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
