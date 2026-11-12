package com.ritense.pdca.devsetup

import com.fasterxml.jackson.databind.JsonNode
import java.io.File
import java.util.zip.ZipInputStream

/** A system process zip of `gzac/system-processes`: one global BPMN process and its process links. */
class SystemProcessZip(val bytes: ByteArray) {
    private val entries: Map<String, ByteArray> = ZipInputStream(bytes.inputStream()).use { zip ->
        generateSequence { zip.nextEntry }.filterNot { it.isDirectory }.associate { it.name to zip.readBytes() }
    }
    private val bpmnPath = entries.keys.singleOrNull { BPMN.matches(it) }
        ?: throw SetupException("the zip holds no single config/global/bpmn/<key>.bpmn")

    /** The process key: the BPMN's file name, which is its process id. */
    val key: String = BPMN.find(bpmnPath)!!.groupValues[1]

    /** The activity ids of the process links the zip carries. */
    val linkedActivities: Set<String> = entries["config/global/process-link/$key.process-link.json"]
        ?.let { Json.mapper.readTree(it).items().mapNotNull { link -> link.text("activityId") }.toSet() }
        .orEmpty()

    private companion object {
        val BPMN = Regex("""^config/global/bpmn/([^/]+)\.bpmn$""")
    }
}

/**
 * Imports the system processes of `gzac/system-processes` (Admin → Process
 * management → Import) with their process links, mapping the placeholder
 * configuration ids to the PDCA configuration and to GZAC's Epistola
 * configuration. GZAC deploys a BPMN equal to its latest version of the key
 * without a new version and replaces the links of that version with the
 * imported ones, so the import runs on every setup.
 */
class SystemProcesses(private val api: GzacApi, private val values: DevValues, private val repoDir: File) {
    private val classic = ClassicPlugins(api)
    private val pdcaPlaceholder = values.caseDefinitions.placeholderConfigurationId
    private val epistolaPlaceholder = values.systemProcesses.epistolaPlaceholderConfigurationId

    fun apply(pdcaConfigurationId: String) {
        val mappings = configurationMappings(
            mapOf(pdcaPlaceholder to pdcaConfigurationId, epistolaPlaceholder to epistolaConfigurationId())
        )
        zips().forEach { (file, zip) ->
            val before = latest(zip.key)?.version()
            val parts = mutableListOf(Part("file", zip.bytes, "application/zip", file.name))
            if (mappings.isNotEmpty()) parts += Part("pluginConfigurationMappings", Json.mapper.writeValueAsBytes(mappings), "application/json")
            api.multipart(IMPORT, parts).orThrow("Importing system process ${zip.key} (GZAC's log has the reason)")
            val version = latest(zip.key)?.version()
                ?: throw SetupException("system process ${zip.key}: imported, but GZAC does not list it")
            Console.info(
                if (version == before) "${zip.key}: present as version $version, process links applied"
                else "${zip.key}: imported as version $version"
            )
        }
    }

    /** The system processes GZAC does not hold, or whose latest version is not linked to existing configurations. */
    fun missing(): List<String> {
        val epistolaIds = classic.list(EPISTOLA).mapNotNull { it.text("id") }.toSet()
        return zips().mapNotNull { (_, zip) ->
            linkState(latest(zip.key), zip.linkedActivities, setOf(pdcaPlaceholder, epistolaPlaceholder), epistolaIds)
                ?.let { "system process ${zip.key} ($it)" }
        }
    }

    private fun zips() = values.systemProcesses.zips.map { name ->
        val file = repoDir.resolve("$DIRECTORY/$name.zip")
        file to SystemProcessZip(file.readBytes())
    }

    private fun epistolaConfigurationId(): String {
        if (classic.list(EPISTOLA).isEmpty()) {
            throw SetupException(
                "GZAC has no Epistola plugin configuration, which the document process needs. The Valtimo dev app only " +
                    "has the Epistola plugin and its configuration on the Epistola branch: start GZAC from a Valtimo checkout " +
                    "of that branch, then re-run ./gradlew gzacDevSetup"
            )
        }
        return classic.existing(EPISTOLA, "Epistola Document Suite").text("id")!!
    }

    /** GZAC's latest version of [key] with its process links; null when GZAC has none. */
    private fun latest(key: String): JsonNode? = api.get("$PROCESS_DEFINITION/key/$key").let { response ->
        if (response.status == 404) null else response.orThrow("Reading process definition $key").json().items().maxByOrNull { it.version() }
    }

    private fun JsonNode.version() = path("processDefinition").path("version").asInt()

    companion object {
        const val DIRECTORY = "gzac/system-processes"
        private const val EPISTOLA = "epistola"
        private const val PROCESS_DEFINITION = "/api/management/v1/process-definition"
        private const val IMPORT = "$PROCESS_DEFINITION/import"

        /** The import's pluginConfigurationMappings: each placeholder to its configuration, where they differ. */
        fun configurationMappings(configurationPerPlaceholder: Map<String, String>): Map<String, String> =
            configurationPerPlaceholder.filter { (placeholder, id) -> placeholder != id }

        /**
         * What is wrong with [latest], GZAC's latest version of a system
         * process, whose zip links [activities]; null when every one of them
         * is linked: classic (Epistola) links to one of [epistolaIds],
         * external (PDCA) links to a configuration other than a placeholder.
         */
        fun linkState(latest: JsonNode?, activities: Set<String>, placeholders: Set<String>, epistolaIds: Set<String>): String? {
            latest ?: return "not imported"
            val links = latest.path("processLinks").items()
            val linked = links.filter { link ->
                when (link.text("processLinkType")) {
                    "plugin" -> link.text("pluginConfigurationId").let { it != null && it in epistolaIds && it !in placeholders }
                    "external_plugin" -> link.text("externalPluginConfigurationId").let { it != null && it !in placeholders }
                    else -> true
                }
            }.mapNotNull { it.text("activityId") }.toSet()
            return if (linked.containsAll(activities)) null else "not linked to the Epistola/PDCA configuration"
        }
    }
}
