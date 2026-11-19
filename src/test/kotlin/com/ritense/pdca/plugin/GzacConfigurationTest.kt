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
import com.ritense.pdca.service.InstrumentActionService
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatus
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The GZAC configuration as code repeats what [PluginHostController]
 * declares and what the register plugins provide: the dev values of the
 * local GZAC setup (gzac/dev-setup) its grants, contracts and the register
 * plugins' capabilities and properties, the case definitions
 * (gzac/case-definitions) its actions, bundles, plugin version and
 * configuration. These checks keep them from drifting apart.
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

    @Test
    fun `the PDCA configuration has exactly the grants the app declares`() {
        assertEquals(PluginHostController.PLUGIN_ID, pdca.pluginId)
        assertEquals(PluginHostController.PLUGIN_VERSION, pdca.pluginVersion)
        assertEquals(PluginHostController.CAPABILITIES, pdca.grantedCapabilities)
        assertEquals(PluginHostController.GRANTED_ENDPOINTS, pdca.grantedEndpoints.map { mapOf("method" to it.method, "pattern" to it.pattern) })
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
        val links = jsonFiles("process-link").flatMap { (file, json) -> json.toList().map { file to it } }
            .filter { (_, link) -> link.path("processLinkType").asText() == "external_plugin" }
        assertTrue(links.isNotEmpty(), "the instrument dossier runs plugin actions")
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
        val controller = PluginActionController(mock(InstrumentActionService::class.java), gzacAccess)
        fun answer(key: String) = controller.invokeAction(PluginHostController.PLUGIN_ID, PluginHostController.PLUGIN_VERSION, key, emptyMap()).statusCode
        actions.keys.forEach { key -> assertEquals(HttpStatus.SERVICE_UNAVAILABLE, answer(key), "PluginActionController handles the declared action '$key'") }
        assertEquals(HttpStatus.NOT_FOUND, answer("onbekend"))
    }

    @Test
    fun `every PDCA tab and process link names the plugin, its version, the placeholder configuration and a declared bundle`() {
        val configurationId = devValues.caseDefinitions.placeholderConfigurationId
        val references = mutableListOf<String>()

        jsonFiles("process-link").flatMap { (file, json) -> json.toList().map { file to it } }
            .filter { (_, link) -> link.path("processLinkType").asText().startsWith("external_plugin") }
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

        assertTrue(references.any { it.contains("case-tab") } && references.any { it.contains("process-link") }, "found $references")
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

    private fun assertPlugin(where: String, reference: JsonNode) {
        assertEquals(PluginHostController.PLUGIN_ID, reference.path("pluginDefinitionKey").asText(), "$where: plugin")
        assertEquals(PluginHostController.PLUGIN_VERSION, reference.path("pluginVersion").asText(), "$where: plugin version")
    }

    private fun JsonNode.toList(): List<JsonNode> = if (isArray) elements().asSequence().toList() else emptyList()

    private companion object {
        const val CASE_DEFINITIONS = "gzac/case-definitions"
    }
}
