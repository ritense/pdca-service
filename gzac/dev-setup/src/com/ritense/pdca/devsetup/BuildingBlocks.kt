package com.ritense.pdca.devsetup

import java.io.File
import java.util.zip.ZipInputStream

/** A building block zip of `gzac/bouwblokken`: one config/building-block/<key>/<x-y-z>/ tree. */
class BuildingBlockZip(val bytes: ByteArray) {
    private val entries: Map<String, ByteArray> = ZipInputStream(bytes.inputStream()).use { zip ->
        generateSequence { zip.nextEntry }.filterNot { it.isDirectory }.associate { it.name to zip.readBytes() }
    }
    private val definitionPath = entries.keys.singleOrNull { DEFINITION.matches(it) }
        ?: throw SetupException("the zip holds no single building-block definition")

    val key: String = DEFINITION.find(definitionPath)!!.groupValues[1]
    val versionTag: String = Json.mapper.readTree(entries.getValue(definitionPath)).path("versionTag").asText()

    private companion object {
        val DEFINITION = Regex("""^config/building-block/([^/]+)/[^/]+/building-block/definition/\1\.building-block-definition\.json$""")
    }
}

/**
 * Imports the building blocks of `gzac/bouwblokken` (Admin → Building blocks
 * → Import), before the case definitions that link them. A version GZAC
 * holds already is left as it is: a final building block version does not
 * change. The building block's process links name the PDCA plugin, not a
 * configuration; the app maps the plan case types' links to its
 * configuration itself before it starts one.
 */
class BuildingBlocks(private val api: GzacApi, private val values: DevValues, private val repoDir: File) {

    fun apply() {
        zips().forEach { (file, zip) ->
            val label = "${zip.key} ${zip.versionTag}"
            if (present(zip)) {
                Console.info("$label: present")
            } else {
                api.multipart(IMPORT, listOf(Part("file", zip.bytes, "application/zip", file.name)))
                    .orThrow("Importing building block $label (GZAC's log has the reason)")
                if (!present(zip)) throw SetupException("building block $label: imported, but GZAC does not list it")
                Console.info("$label: imported")
            }
        }
    }

    /** The building blocks of the dev values that GZAC does not hold. */
    fun missing(): List<String> = zips().mapNotNull { (_, zip) ->
        if (present(zip)) null else "building block ${zip.key} ${zip.versionTag} (not imported)"
    }

    private fun present(zip: BuildingBlockZip): Boolean =
        api.get("$BUILDING_BLOCK/${zip.key}/version/${zip.versionTag}").let { response ->
            if (response.status == 404) false else response.orThrow("Reading building block ${zip.key} ${zip.versionTag}").ok
        }

    private fun zips() = values.buildingBlocks.zips.map { name ->
        val file = repoDir.resolve("$DIRECTORY/$name.zip")
        file to BuildingBlockZip(file.readBytes())
    }

    companion object {
        const val DIRECTORY = "gzac/bouwblokken"
        private const val BUILDING_BLOCK = "/api/management/v1/building-block"
        private const val IMPORT = "$BUILDING_BLOCK/import"
    }
}
