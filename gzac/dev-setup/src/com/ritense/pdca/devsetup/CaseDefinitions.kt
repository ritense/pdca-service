package com.ritense.pdca.devsetup

import com.fasterxml.jackson.databind.JsonNode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** A case definition zip of `gzac/case-definitions`: one key, one version. */
class CaseZip(val bytes: ByteArray) {
    private val entries: Map<String, ByteArray> = ZipInputStream(bytes.inputStream()).use { zip ->
        generateSequence { zip.nextEntry }.filterNot { it.isDirectory }.associate { it.name to zip.readBytes() }
    }
    private val definitionPath = entries.keys.singleOrNull { DEFINITION.matches(it) }
        ?: throw SetupException("the zip holds no single case/definition/<key>.case-definition.json")

    val key: String = DEFINITION.find(definitionPath)!!.groupValues[1]
    val versionDirectory: String = definitionPath.substringBefore("/case/definition/")
    val versionTag: String = Json.mapper.readTree(entries.getValue(definitionPath)).path("versionTag").asText()

    /** This zip with [content] added (or replaced) at [path] below its version directory. */
    fun withFile(path: String, content: ByteArray): CaseZip {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            (entries + ("$versionDirectory/$path" to content)).forEach { (name, data) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return CaseZip(output.toByteArray())
    }

    fun file(path: String): ByteArray? = entries["$versionDirectory/$path"]

    private companion object {
        val DEFINITION = Regex("""^config/case/([^/]+)/[^/]+/case/definition/\1\.case-definition\.json$""")
    }
}

/**
 * Imports the case definitions of `gzac/case-definitions` (Admin → Case
 * definitions → Import), maps their placeholder configuration id to the PDCA
 * configuration, activates the imported versions and gives the instrument
 * dossier its zaak type link.
 */
class CaseDefinitions(private val api: GzacApi, private val values: DevValues, private val repoDir: File) {
    private val classic = ClassicPlugins(api)

    fun apply(pdcaConfigurationId: String) {
        values.caseDefinitions.zips.forEach { name ->
            val file = repoDir.resolve("gzac/case-definitions/$name.zip")
            val zip = withZaakTypeLink(CaseZip(file.readBytes()))
            val label = "${zip.key} ${zip.versionTag}"
            val versions = versions(zip.key)
            if (versions.none { it.text("versionTag") == zip.versionTag }) {
                import(zip, file.name, pdcaConfigurationId)
                Console.info("$label: imported")
            } else {
                Console.info("$label: present")
            }
            if (versions(zip.key).firstOrNull { it.text("versionTag") == zip.versionTag }?.path("active")?.asBoolean() != true) {
                api.post("$CASE_DEFINITION/${zip.key}/version/${zip.versionTag}/active", null)
                    .orThrow("Activating $label (configuration issues? see Admin → Case definitions)")
                Console.info("$label: activated")
            }
            if (zip.key == values.verzoek.zaakTypeLink.caseDefinitionKey) zaakTypeLink(zip)
        }
    }

    /** The case definitions of the dev values that GZAC does not hold, or holds inactive. */
    fun missing(): List<String> = values.caseDefinitions.zips.mapNotNull { name ->
        val zip = CaseZip(repoDir.resolve("gzac/case-definitions/$name.zip").readBytes())
        versionState(versions(zip.key), zip.versionTag)?.let { "case definition ${zip.key} ${zip.versionTag} ($it)" }
    }

    private fun versions(key: String) = api.get("$CASE_DEFINITION/$key/version?size=100").let {
        if (it.status == 404) emptyList() else it.orThrow("Listing the versions of $key").json().items()
    }

    private fun import(zip: CaseZip, fileName: String, pdcaConfigurationId: String) {
        val parts = mutableListOf(Part("file", zip.bytes, "application/zip", fileName))
        val placeholder = values.caseDefinitions.placeholderConfigurationId
        if (pdcaConfigurationId != placeholder) {
            val mapping = Json.mapper.writeValueAsBytes(mapOf(placeholder to pdcaConfigurationId))
            parts += Part("pluginConfigurationMappings", mapping, "application/json")
        }
        api.multipart(IMPORT, parts).orThrow("Importing ${zip.key} ${zip.versionTag} (GZAC's log has the reason)")
    }

    /** The zaak type link of the dev stack, built from the dev app's Zaken and Catalogi API configurations. */
    private fun desiredZaakTypeLink(): Map<String, Any> {
        val link = values.verzoek.zaakTypeLink
        val catalogiUrl = classic.existing("catalogiapi", "Catalogi API").path("properties").text("url")!!
        return mapOf(
            "zaakTypeUrl" to "${catalogiUrl.trimEnd('/')}/zaaktypen/${link.zaaktypeUuid}",
            "zakenApiPluginConfigurationId" to classic.existing("zakenapi", "Zaken API").text("id")!!,
            "createWithDossier" to link.createWithDossier,
            "rsin" to link.rsin,
        )
    }

    /**
     * The zips carry no zaak type link (it names environment values), and GZAC
     * only accepts one on a final version at import, so it is added to the
     * instrument dossier's zip here.
     */
    private fun withZaakTypeLink(zip: CaseZip): CaseZip {
        if (zip.key != values.verzoek.zaakTypeLink.caseDefinitionKey) return zip
        return zip.withFile("zgw/zaak-type-link/${zip.key}.zaak-type-link.json", Json.mapper.writeValueAsBytes(desiredZaakTypeLink()))
    }

    private fun zaakTypeLink(zip: CaseZip) {
        val path = "$CASE_DEFINITION/${zip.key}/version/${zip.versionTag}/zaak-type-link"
        val desired = desiredZaakTypeLink()
        val current = api.get(path).let { if (it.status == 204) null else it.orThrow("Reading the zaak type link of ${zip.key}").json() }
        if (current != null && desired.all { (key, value) -> current.get(key)?.asText() == value.toString() }) {
            Console.info("${zip.key} ${zip.versionTag}: zaak type link up to date (${desired["zaakTypeUrl"]})")
            return
        }
        val response = api.post(path, desired)
        if (!response.ok) {
            throw SetupException(
                "${zip.key} ${zip.versionTag} has ${if (current == null) "no" else "another"} zaak type link than the dev stack's " +
                    "(${desired["zaakTypeUrl"]}, Zaken API configuration ${desired["zakenApiPluginConfigurationId"]}) and GZAC refuses " +
                    "to change it on a final version (HTTP ${response.status}): it only takes one with the import. " +
                    "Verzoeken for ${zip.key} fail until this version is imported into a GZAC database that does not hold it yet"
            )
        }
        Console.info("${zip.key} ${zip.versionTag}: zaak type link set (${desired["zaakTypeUrl"]})")
    }

    companion object {
        private const val CASE_DEFINITION = "/api/management/v1/case-definition"
        private const val IMPORT = "/api/management/v1/case/import"

        /** What is wrong with [versionTag] among GZAC's [versions] of a case definition; null when it is there and active. */
        fun versionState(versions: List<JsonNode>, versionTag: String): String? {
            val version = versions.firstOrNull { it.text("versionTag") == versionTag } ?: return "not imported"
            return if (version.path("active").asBoolean()) null else "inactive"
        }
    }
}
