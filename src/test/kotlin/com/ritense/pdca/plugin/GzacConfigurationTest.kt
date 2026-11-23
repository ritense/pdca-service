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

package com.ritense.pdca.plugin

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.ritense.pdca.devsetup.DevValues
import com.ritense.pdca.registers.Register
import com.ritense.pdca.security.GzacInstances
import com.ritense.pdca.service.ActieTaakService
import com.ritense.pdca.service.DocumentAanvraagService
import com.ritense.pdca.service.InstrumentActionService
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatus
import org.w3c.dom.Element
import java.io.File
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The GZAC configuration as code repeats what [PluginHostController]
 * declares and what the register plugins provide: the dev values of the
 * local GZAC setup (gzac/dev-setup) its grants, configuration properties,
 * contracts and the register plugins' capabilities and properties, the case
 * definitions (gzac/case-definitions), the building blocks
 * (gzac/bouwblokken) and the system processes (gzac/system-processes) its
 * actions, bundles, plugin version and configuration. These checks keep them
 * from drifting apart.
 */
class GzacConfigurationTest {

    private val objectMapper = ObjectMapper()
    private val devValues = DevValues.load(File(DevValues.PATH), emptyMap())
    private val pdca = devValues.app.configuration

    @Suppress("UNCHECKED_CAST")
    private val manifest = PluginHostController(mock(ConfigurationStore::class.java), mock(GzacInstances::class.java))
        .getPlugins().single()["manifest"] as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private val actions = (manifest["actions"] as List<Map<String, Any?>>).associateBy { it["key"] as String }

    @Suppress("UNCHECKED_CAST")
    private val bundles = (manifest["frontendBundles"] as List<Map<String, Any?>>)
        .groupBy({ it["type"] as String }, { it["key"] as String })

    /** The current version directory of every case definition: config/case/<key>/<x-y-z>. */
    private val caseVersions = File(CASE_DEFINITIONS).listFiles { f -> f.isDirectory }!!.map { root ->
        val keyDir = root.resolve("config/case/${root.name}")
        val versions = keyDir.listFiles { f -> f.isDirectory }.orEmpty().toList()
        assertEquals(1, versions.size, "${root.name} holds exactly one version")
        versions.single()
    }

    private fun jsonFiles(kind: String): List<Pair<File, JsonNode>> =
        caseVersions.flatMap { version -> version.resolve(kind).listFiles { f -> f.name.endsWith(".json") }.orEmpty().toList() }
            .map { it to objectMapper.readTree(it) }

    /** The source tree of every system process: gzac/system-processes/<key>/config/global. */
    private val systemProcesses = File(SYSTEM_PROCESSES).listFiles { f -> f.isDirectory }!!.map { it.resolve("config/global") }

    /** The current version directory of every building block: gzac/bouwblokken/<key>/config/building-block/<key>/<x-y-z>. */
    private val buildingBlockVersions = File(BUILDING_BLOCKS).listFiles { f -> f.isDirectory }!!.map { root ->
        val versions = root.resolve("config/building-block/${root.name}").listFiles { f -> f.isDirectory }.orEmpty().toList()
        assertEquals(1, versions.size, "${root.name} holds exactly one version")
        versions.single()
    }

    /** Every process link of a building block, with its file. */
    private fun buildingBlockLinks(): List<Pair<File, JsonNode>> =
        buildingBlockVersions.flatMap { version ->
            version.resolve("process-link").listFiles { f -> f.name.endsWith(".process-link.json") }.orEmpty().map { it to objectMapper.readTree(it) }
        }.flatMap { (file, json) -> json.toList().map { file to it } }

    /** Every process link of a case definition or a system process, with its file. */
    private fun processLinks(): List<Pair<File, JsonNode>> =
        (jsonFiles("process-link") + systemProcesses.flatMap { global ->
            global.resolve("process-link").listFiles { f -> f.name.endsWith(".process-link.json") }.orEmpty().map { it to objectMapper.readTree(it) }
        }).flatMap { (file, json) -> json.toList().map { file to it } }

    @Test
    fun `the PDCA configuration has exactly the grants the app declares`() {
        assertEquals(PluginHostController.PLUGIN_ID, pdca.pluginId)
        assertEquals(PluginHostController.PLUGIN_VERSION, pdca.pluginVersion)
        assertEquals(PluginHostController.CAPABILITIES, pdca.grantedCapabilities)
        assertEquals(PluginHostController.GRANTED_ENDPOINTS, pdca.grantedEndpoints.map { mapOf("method" to it.method, "pattern" to it.pattern) })
    }

    @Test
    fun `the PDCA configuration only sets properties of the app's configuration schema`() {
        @Suppress("UNCHECKED_CAST")
        val schema = (manifest["configurationSchema"] as Map<String, Any?>)["properties"] as Map<String, Any?>
        val given = pdca.properties.fieldNames().asSequence().toSet()
        assertTrue(schema.keys.containsAll(given), "undeclared configuration properties ${given - schema.keys}")
        assertEquals(SYSTEM_PROCESS_KEY, pdca.properties.path("documentProces").asText(), "the dev configuration makes documents with the system process")
    }

    @Test
    fun `every contract the app requires is bound to a register configuration of the plugin that provides it`() {
        val bindings = pdca.contractBindings.associate { it.contract to it.pluginId }
        assertEquals(PluginHostController.CONTRACTS.toSet(), bindings.keys)

        Register.entries.forEach { register ->
            val manifest = objectMapper.readTree(File("plugins/${register.key}/manifest.json"))
            assertTrue(
                manifest.path("provides").any { it.path("contract").asText() == register.contract },
                "plugins/${register.key} provides ${register.contract}"
            )
            assertEquals(register.key, bindings[register.contract], "binding of ${register.contract}")
        }
    }

    @Test
    fun `every register plugin is configured with the properties and capabilities its manifest asks for`() {
        assertEquals(Register.entries.map { it.key }.toSet(), devValues.pluginHost.configurations.map { it.pluginId }.toSet())
        devValues.pluginHost.configurations.forEach { configuration ->
            val manifest = objectMapper.readTree(File("plugins/${configuration.pluginId}/manifest.json"))
            val required = manifest.path("configurationSchema").path("required").map { it.asText() }.toSet()
            assertEquals(required, configuration.properties.fieldNames().asSequence().toSet(), "properties of ${configuration.pluginId}")
            assertEquals(
                manifest.path("permissions").path("capabilities").map { it.asText() }.toSet(),
                configuration.grantedCapabilities.toSet(),
                "capabilities of ${configuration.pluginId}"
            )
        }
    }

    @Test
    fun `the dev setup imports every case definition zip`() {
        val zips = File(CASE_DEFINITIONS).listFiles { f -> f.name.endsWith(".zip") }!!.map { it.nameWithoutExtension }
        assertEquals(zips.toSet(), devValues.caseDefinitions.zips.toSet())
        assertTrue(devValues.verzoek.zaakTypeLink.caseDefinitionKey in zips, "the zaak type link belongs to a case definition")
    }

    @Test
    fun `every external plugin process link runs a declared action the app handles, with its declared properties`() {
        val links = (processLinks() + buildingBlockLinks()).filter { (_, link) -> link.path("processLinkType").asText() == "external_plugin" }
        assertTrue(links.any { (file, _) -> file.name == "instrument-verzoek.process-link.json" }, "the instrument dossier runs plugin actions")
        assertTrue(links.any { (file, _) -> file.name == "$SYSTEM_PROCESS_KEY.process-link.json" }, "the document process runs plugin actions")
        assertTrue(links.any { (file, _) -> file.name == "${ActieTaakService.BOUWBLOK}.process-link.json" }, "the task building block runs plugin actions")
        links.forEach { (file, link) ->
            val where = "${file.name} ${link.path("activityId").asText()}"
            val action = actions[link.path("actionKey").asText()] ?: fail("$where: action '${link.path("actionKey").asText()}' is not in the manifest")
            @Suppress("UNCHECKED_CAST")
            val properties = action["properties"] as List<Map<String, Any?>>
            val declared = properties.map { it["key"] as String }.toSet()
            val required = properties.filter { it["required"] == true }.map { it["key"] as String }.toSet()
            val given = link.path("actionProperties").fieldNames().asSequence().toSet()
            assertTrue(declared.containsAll(given), "$where: undeclared properties ${given - declared}")
            assertTrue(given.containsAll(required), "$where: missing required properties ${required - given}")
            @Suppress("UNCHECKED_CAST")
            val outputs = (action["outputs"] as? List<String>).orEmpty().map { "/$it" }.toSet()
            link.path("actionResultMappings").forEach { mapping ->
                assertTrue(mapping.path("source").asText() in outputs, "$where: result mapping of an undeclared output ${mapping.path("source").asText()}")
            }
        }

        // Without an active configuration a handled action answers 503 before it runs; an unknown one 404.
        val gzacAccess = mock(GzacAccess::class.java)
        `when`(gzacAccess.resolve()).thenReturn(GzacAccess.Resolution.Unavailable("niet actief", nonePushed = true))
        val controller = PluginActionController(
            mock(InstrumentActionService::class.java), gzacAccess, mock(DocumentAanvraagService::class.java), mock(ActieTaakService::class.java)
        )
        fun answer(key: String) = controller.invokeAction(PluginHostController.PLUGIN_ID, PluginHostController.PLUGIN_VERSION, key, emptyMap()).statusCode
        actions.keys.forEach { key -> assertEquals(HttpStatus.SERVICE_UNAVAILABLE, answer(key), "PluginActionController handles the declared action '$key'") }
        assertEquals(HttpStatus.NOT_FOUND, answer("onbekend"))
    }

    @Test
    fun `every PDCA tab and process link names the plugin, its version, the placeholder configuration and a declared bundle`() {
        val configurationId = devValues.caseDefinitions.placeholderConfigurationId
        val references = mutableListOf<String>()

        processLinks().filter { (_, link) -> link.path("processLinkType").asText().startsWith("external_plugin") }
            .forEach { (file, link) ->
                val where = "${file.name} ${link.path("activityId").asText()}"
                references += where
                assertPlugin(where, link)
                assertEquals(configurationId, link.path("externalPluginConfigurationId").asText(), "$where: configuration")
                if (link.path("processLinkType").asText() == "external_plugin_task_form") {
                    assertTrue(link.path("bundleKey").asText() in bundles["task-form"].orEmpty(), "$where: task-form bundle ${link.path("bundleKey").asText()}")
                }
            }

        jsonFiles("case/tab").flatMap { (file, json) -> json.toList().map { file to it } }
            .filter { (_, tab) -> tab.path("type").asText() == "external_plugin" }
            .forEach { (file, tab) ->
                val where = "${file.name} ${tab.path("key").asText()}"
                references += where
                assertPlugin(where, tab)
                val (tabConfiguration, bundleKey) = tab.path("contentKey").asText().split(':', limit = 2)
                assertEquals(configurationId, tabConfiguration, "$where: configuration")
                assertTrue(bundleKey in bundles["case-tab"].orEmpty(), "$where: case-tab bundle $bundleKey")
            }

        // A building block's links reference the plugin, not a configuration:
        // the case type's building-block link maps it (the app sets that).
        buildingBlockLinks().filter { (_, link) -> link.path("processLinkType").asText().startsWith("external_plugin") }
            .forEach { (file, link) ->
                val where = "${file.name} ${link.path("activityId").asText()}"
                assertPlugin(where, link)
                assertTrue(link.path("externalPluginConfigurationId").isMissingNode, "$where: no configuration in a building block")
            }

        assertTrue(references.any { it.contains("case-tab") } && references.any { it.contains("process-link") }, "found $references")
    }

    @Test
    fun `the dev setup imports every building block zip, built from its source tree`() {
        val zips = File(BUILDING_BLOCKS).listFiles { f -> f.name.endsWith(".zip") }!!.map { it.nameWithoutExtension }
        assertEquals(zips.toSet(), devValues.buildingBlocks.zips.toSet())
        assertEquals(zips.toSet(), buildingBlockVersions.map { it.parentFile.name }.toSet(), "a zip per source tree")
        buildingBlockVersions.forEach { version ->
            val root = File(BUILDING_BLOCKS).resolve(version.parentFile.name)
            val sources = root.resolve("config").walkTopDown().filter { it.isFile && it.name != ".DS_Store" }
                .associate { it.relativeTo(root).invariantSeparatorsPath to it.readText() }
            val entries = ZipInputStream(root.resolveSibling("${root.name}.zip").inputStream()).use { zip ->
                generateSequence { zip.nextEntry }.filterNot { it.isDirectory }.associate { it.name to zip.readBytes().decodeToString() }
            }
            assertEquals(sources, entries, "${root.name}.zip is up to date (./gradlew buildGzacZips)")
        }
    }

    @Test
    fun `every building block's version is the same in its directory, definition and BPMN`() {
        buildingBlockVersions.forEach { version ->
            val key = version.parentFile.name
            val tag = version.name.replace('-', '.')
            val definition = objectMapper.readTree(version.resolve("building-block/definition/$key.building-block-definition.json"))
            assertEquals(tag, definition.path("versionTag").asText(), "versionTag of $key")
            val main = objectMapper.readTree(version.resolve("building-block/building-block-definition-main-process-definition.json"))
            val bpmn = version.resolve("bpmn/${main.path("processDefinitionKey").asText()}.bpmn")
            assertTrue(bpmn.isFile, "$key: main process ${bpmn.name}")
            assertEquals(listOf("BB:$key:$tag"), Regex("""camunda:versionTag="([^"]+)"""").findAll(bpmn.readText()).map { it.groupValues[1] }.toList())
        }
    }

    @Test
    fun `every plan case type links the task building block with the start data the app submits`() {
        val bouwblok = buildingBlockVersions.single { it.parentFile.name == ActieTaakService.BOUWBLOK }
        val startFields = objectMapper.readTree(bouwblok.resolve("form/${ActieTaakService.BOUWBLOK}-start.form.json"))
            .path("components").map { it.path("key").asText() }.toSet()
        val startData = setOf("actieId", "titel", "omschrijving", "planUuid", "doelUuid")
        assertEquals(startData.map { "bouwblokStart.$it" }.toSet(), startFields, "start form fields")

        // A plan case type is one with the PDCA plan tabs.
        val planCaseTypes = caseVersions.filter { version ->
            val key = version.parentFile.name
            version.resolve("case/tab/$key.case-tab.json").takeIf { it.isFile }
                ?.let { objectMapper.readTree(it).toList().any { tab -> tab.path("type").asText() == "external_plugin" } } == true
        }
        assertTrue(planCaseTypes.isNotEmpty(), "plan case types found")
        planCaseTypes.forEach { version ->
            val key = version.parentFile.name
            val schema = objectMapper.readTree(version.resolve("document/definition/$key.schema.document-definition.json"))
            assertEquals(startData, schema.path("properties").path("bouwblokStart").path("properties").fieldNames().asSequence().toSet(), "$key: /bouwblokStart")
            val link = objectMapper.readTree(version.resolve("building-block-link/$key.case-building-block-links.json")).toList()
                .singleOrNull { it.path("buildingBlockDefinitionKey").asText() == ActieTaakService.BOUWBLOK } ?: fail("$key: no link to the task building block")
            assertEquals(bouwblok.name.replace('-', '.'), link.path("buildingBlockDefinitionVersionTag").asText(), "$key: building block version")
            assertEquals(false, link.path("startableByUser").asBoolean(true), "$key: started by the app only")
            val mappings = link.path("inputMappings").toList().map { it.path("source").asText() to it.path("target").asText() }.toSet()
            assertTrue(startData.all { ("doc:/bouwblokStart/$it" to "/$it") in mappings }, "$key: start data into the building block document")
            assertTrue(("doc:/bouwblokStart/titel" to "pv:taakTitel") in mappings, "$key: the task title")
        }
    }

    @Test
    fun `every case definition's version is the same in its directory, definition and BPMN`() {
        caseVersions.forEach { version ->
            val key = version.parentFile.name
            val tag = version.name.replace('-', '.')
            val definition = objectMapper.readTree(version.resolve("case/definition/$key.case-definition.json"))
            assertEquals(tag, definition.path("versionTag").asText(), "versionTag of $key")
            version.resolve("bpmn").listFiles { f -> f.name.endsWith(".bpmn") }.orEmpty().forEach { bpmn ->
                Regex("""camunda:versionTag="([^"]+)"""").findAll(bpmn.readText()).forEach {
                    assertEquals("CD:$key:$tag", it.groupValues[1], "versionTag in ${bpmn.name}")
                }
            }
        }
    }

    @Test
    fun `the dev setup imports every system process zip, built from its source tree`() {
        val zips = File(SYSTEM_PROCESSES).listFiles { f -> f.name.endsWith(".zip") }!!.map { it.nameWithoutExtension }
        assertEquals(zips.toSet(), devValues.systemProcesses.zips.toSet())
        assertEquals(zips.toSet(), systemProcesses.map { it.parentFile.parentFile.name }.toSet(), "a zip per source tree")
        systemProcesses.forEach { global ->
            val root = global.parentFile.parentFile
            val sources = global.walkTopDown().filter { it.isFile && it.name != ".DS_Store" }
                .associate { it.relativeTo(root).invariantSeparatorsPath to it.readText() }
            val entries = ZipInputStream(root.resolveSibling("${root.name}.zip").inputStream()).use { zip ->
                generateSequence { zip.nextEntry }.filterNot { it.isDirectory }.associate { it.name to zip.readBytes().decodeToString() }
            }
            assertEquals(sources, entries, "${root.name}.zip is up to date (./gradlew buildGzacZips)")
        }
    }

    @Test
    fun `every system process is named after its key and links only its own activities`() {
        systemProcesses.forEach { global ->
            val key = global.parentFile.parentFile.name
            val bpmnFiles = global.resolve("bpmn").listFiles { f -> f.name.endsWith(".bpmn") }.orEmpty().toList()
            assertEquals(listOf("$key.bpmn"), bpmnFiles.map { it.name }, "$key: one BPMN named after the key")
            val bpmn = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(bpmnFiles.single())
            val processes = bpmn.getElementsByTagNameNS(BPMN_MODEL, "process")
            assertEquals(1, processes.length, "$key: one process")
            val process = processes.item(0) as Element
            assertEquals(key, process.getAttribute("id"), "$key: process id")
            assertTrue(!process.getAttributeNS(CAMUNDA, "versionTag").matches(Regex("^(CD|BB):.*")), "$key: no case or building block version tag")
            val activities = (0 until process.childNodes.length).map { process.childNodes.item(it) }
                .filterIsInstance<Element>().associateBy { it.getAttribute("id") }

            val linkFiles = global.resolve("process-link").listFiles { f -> f.name.endsWith(".json") }.orEmpty().toList()
            assertEquals(listOf("$key.process-link.json"), linkFiles.map { it.name }, "$key: one link file named after the key")
            objectMapper.readTree(linkFiles.single()).toList().forEach { link ->
                val activityId = link.path("activityId").asText()
                val activity = activities[activityId] ?: fail("$key: link to unknown activity $activityId")
                assertEquals("bpmn:ServiceTask:start", link.path("activityType").asText(), "$key $activityId: activity type")
                assertEquals("serviceTask", activity.localName, "$key $activityId: a service task")
                when (link.path("processLinkType").asText()) {
                    "external_plugin" -> {
                        // GZAC runs a PDCA action as an asynchronous job, retried before it becomes an incident.
                        assertEquals("true", activity.getAttributeNS(CAMUNDA, "asyncBefore"), "$key $activityId: asyncBefore")
                        assertEquals("\${true}", activity.getAttributeNS(CAMUNDA, "expression"), "$key $activityId: expression")
                    }
                    "plugin" -> {
                        assertEquals(EPISTOLA, link.path("pluginDefinitionKey").asText(), "$key $activityId: plugin")
                        assertEquals(
                            devValues.systemProcesses.epistolaPlaceholderConfigurationId,
                            link.path("pluginConfigurationId").asText(),
                            "$key $activityId: Epistola placeholder configuration",
                        )
                        // The Epistola plugin needs these without the asyncAfter GZAC adds to a service task without expression.
                        assertEquals("\${null}", activity.getAttributeNS(CAMUNDA, "expression"), "$key $activityId: expression")
                        assertEquals("", activity.getAttributeNS(CAMUNDA, "asyncAfter"), "$key $activityId: no asyncAfter")
                    }
                    else -> fail("$key $activityId: unexpected process link type ${link.path("processLinkType").asText()}")
                }
            }
            activities.values.filter { it.localName == "intermediateCatchEvent" }.forEach { event ->
                assertEquals("", event.getAttributeNS(CAMUNDA, "asyncBefore"), "$key ${event.getAttribute("id")}: no asyncBefore")
            }
        }
    }

    private fun assertPlugin(where: String, reference: JsonNode) {
        assertEquals(PluginHostController.PLUGIN_ID, reference.path("pluginDefinitionKey").asText(), "$where: plugin")
        assertEquals(PluginHostController.PLUGIN_VERSION, reference.path("pluginVersion").asText(), "$where: plugin version")
    }

    private fun JsonNode.toList(): List<JsonNode> = if (isArray) elements().asSequence().toList() else emptyList()

    private companion object {
        const val CASE_DEFINITIONS = "gzac/case-definitions"
        const val SYSTEM_PROCESSES = "gzac/system-processes"
        const val BUILDING_BLOCKS = "gzac/bouwblokken"
        const val SYSTEM_PROCESS_KEY = "pdca-document"
        const val EPISTOLA = "epistola"
        const val BPMN_MODEL = "http://www.omg.org/spec/BPMN/20100524/MODEL"
        const val CAMUNDA = "http://camunda.org/schema/1.0/bpmn"
    }
}
