package com.ritense.pdca.devsetup

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DevSetupTest {

    @Test
    fun `placeholders take the environment value, else their default, JSON-escaped`() {
        val text = """{"a": "${'$'}{A:one}", "b": "${'$'}{B:two}", "c": "x-${'$'}{C}"}"""
        val resolved = DevValues.resolve(text, mapOf("B" to "say \"hi\"", "C" to "3"))
        val json = Json.mapper.readTree(resolved)
        assertEquals("one", json.path("a").asText())
        assertEquals("say \"hi\"", json.path("b").asText())
        assertEquals("x-3", json.path("c").asText())
        assertThrows<SetupException> { DevValues.resolve("${'$'}{MISSING}", emptyMap()) }
    }

    @Test
    fun `the dev values load with their local defaults`() {
        val values = DevValues.load(File(DevValues.PATH), emptyMap())
        assertEquals("http://localhost:7500", values.app.baseUrl)
        assertEquals("dev-only-insecure-secret", values.app.secret)
        assertEquals("pdca-document", values.app.configuration.properties.path("documentProces").asText())
        assertEquals(
            GrantedEndpoint("GET", "/api/v1/admin-settings/accent-colors"),
            values.app.configuration.grantedEndpoints.last(),
        )
        assertEquals(EpistolaValues("http://localhost:4000/api", "demo", "epk_demo_000000000000000000000000000000000000", listOf("pdca-documenten")), values.epistola)
        assertEquals(SystemProcessValues("00000000-0000-4000-8000-0000dca00005", listOf("pdca-document")), values.systemProcesses)

        val elsewhere = DevValues.load(File(DevValues.PATH), mapOf("PDCA_EPISTOLA_URL" to "http://epistola:4000/api", "PDCA_EPISTOLA_TENANT" to "t"))
        assertEquals("http://epistola:4000/api", elsewhere.epistola.baseUrl)
        assertEquals("t", elsewhere.epistola.tenantId)
    }

    @Test
    fun `an Epistola catalog zips its catalog json at the root with its resources`() {
        val catalog = EpistolaCatalog(File("${EpistolaCatalogs.DIRECTORY}/pdca-documenten"))
        assertEquals("pdca-documenten", catalog.slug)
        val entries = ZipInputStream(catalog.zip().inputStream()).use { zip -> generateSequence { zip.nextEntry }.map { it.name }.toList() }
        assertEquals("catalog.json", entries.first())
        assertTrue("resources/template/evaluatieverslag.json" in entries, "entries $entries")
        assertTrue("resources/theme/pdca.json" in entries, "entries $entries")
        assertTrue(entries.all { it == "catalog.json" || it.startsWith("resources/") }, "entries $entries")
        // Every catalog of the dev values has a directory named after its slug.
        DevValues.load(File(DevValues.PATH), emptyMap()).epistola.catalogs.forEach { name ->
            assertEquals(name, EpistolaCatalog(File("${EpistolaCatalogs.DIRECTORY}/$name")).slug)
        }
    }

    @Test
    fun `an Epistola catalog is missing until the Suite has its release`() {
        val list = ApiResponse(
            200,
            """{"items": [
                {"registry": "local", "namespace": "ab0", "catalogKey": "a", "highestVersion": "1.0.0"},
                {"registry": "local", "namespace": "ab0", "catalogKey": "b", "highestVersion": null},
                {"registry": "local", "namespace": "xyz", "catalogKey": "c", "highestVersion": "1.0.0"},
                {"registry": "exchange.epistola.app", "namespace": "ab0", "catalogKey": "d", "highestVersion": "1.0.0"}
            ], "page": {}}""",
        )
        val released = EpistolaCatalogs.releasedVersions(list, EpistolaCatalogs.namespace("ab"))
        assertEquals(mapOf("a" to "1.0.0", "b" to null), released)
        assertEquals("demo", EpistolaCatalogs.namespace("demo"))
        assertEquals(null, EpistolaCatalogs.releaseState(released["a"], "1.0.0"))
        assertEquals("not released", EpistolaCatalogs.releaseState(released["a"], "1.1.0"))
        assertEquals("not released", EpistolaCatalogs.releaseState(released["b"], "1.0.0"))
        assertEquals("not released", EpistolaCatalogs.releaseState(released["c"], "1.0.0"))
        assertEquals("the Suite has the newer release 1.0.0", EpistolaCatalogs.releaseState(released["a"], "0.9.0"))
        assertTrue(EpistolaCatalogs.compareVersions("1.10.0", "1.9.0") > 0)
        assertEquals(0, EpistolaCatalogs.compareVersions("1.0", "1.0.0"))
    }

    @Test
    fun `a system process zip names its key and the activities it links`() {
        val zip = SystemProcessZip(File("${SystemProcesses.DIRECTORY}/pdca-document.zip").readBytes())
        assertEquals("pdca-document", zip.key)
        assertEquals(
            setOf("gegevens-ophalen", "document-genereren", "document-downloaden", "document-gereed", "document-mislukt"),
            zip.linkedActivities,
        )
    }

    @Test
    fun `the import maps only the placeholders that differ from their configuration`() {
        assertEquals(
            mapOf("p-pdca" to "pdca-1"),
            SystemProcesses.configurationMappings(mapOf("p-pdca" to "pdca-1", "p-epistola" to "p-epistola")),
        )
        assertEquals(emptyMap(), SystemProcesses.configurationMappings(mapOf("p" to "p")))
    }

    @Test
    fun `a system process is missing when not imported or not linked to existing configurations`() {
        val placeholders = setOf("p-pdca", "p-epistola")
        val activities = setOf("pdca-task", "epistola-task")
        fun version(pdca: String, epistola: String) = Json.mapper.readTree(
            """{"processDefinition": {"version": 2}, "processLinks": [
                {"activityId": "pdca-task", "processLinkType": "external_plugin", "externalPluginConfigurationId": "$pdca"},
                {"activityId": "epistola-task", "processLinkType": "plugin", "pluginConfigurationId": "$epistola"}]}"""
        )
        assertEquals("not imported", SystemProcesses.linkState(null, activities, placeholders, setOf("e-1")))
        assertEquals(null, SystemProcesses.linkState(version("pdca-1", "e-1"), activities, placeholders, setOf("e-1")))
        val notLinked = "not linked to the Epistola/PDCA configuration"
        assertEquals(notLinked, SystemProcesses.linkState(version("p-pdca", "e-1"), activities, placeholders, setOf("e-1")))
        assertEquals(notLinked, SystemProcesses.linkState(version("pdca-1", "p-epistola"), activities, placeholders, setOf("e-1")))
        assertEquals(notLinked, SystemProcesses.linkState(version("pdca-1", "e-gone"), activities, placeholders, setOf("e-1")))
        assertEquals(notLinked, SystemProcesses.linkState(version("pdca-1", "e-1"), activities + "other-task", placeholders, setOf("e-1")))
    }

    @Test
    fun `a PDCA configuration property is missing when GZAC holds another value or none`() {
        val desired = Json.mapper.readTree("""{"documentProces": "pdca-document"}""")
        assertEquals(emptyList(), PdcaApp.differingProperties(desired, Json.mapper.readTree("""{"documentProces": "pdca-document", "x": 1}""")))
        assertEquals(
            listOf("PDCA configuration property documentProces (is \"\")"),
            PdcaApp.differingProperties(desired, Json.mapper.readTree("""{"documentProces": ""}""")),
        )
        assertEquals(
            listOf("PDCA configuration property documentProces (is not set)"),
            PdcaApp.differingProperties(desired, Json.mapper.createObjectNode()),
        )
    }

    @Test
    fun `a file added to a case zip lands in its version directory, the rest unchanged`() {
        val zip = CaseZip(File("gzac/case-definitions/instrument-verzoek.zip").readBytes())
        val extended = zip.withFile("zgw/zaak-type-link/instrument-verzoek.zaak-type-link.json", """{"rsin":"000000000"}""".toByteArray())
        assertEquals("instrument-verzoek", extended.key)
        assertEquals(zip.versionTag, extended.versionTag)
        assertEquals(zip.versionDirectory, extended.versionDirectory)
        assertEquals("""{"rsin":"000000000"}""", String(extended.file("zgw/zaak-type-link/instrument-verzoek.zaak-type-link.json")!!))
        assertEquals(String(zip.file("bpmn/instrument-verzoek.bpmn")!!), String(extended.file("bpmn/instrument-verzoek.bpmn")!!))
    }

    @Test
    fun `a contract binding is missing when it is absent, points elsewhere or its target configuration is missing`() {
        val current = Json.mapper.readTree(
            """[{"contract": "a@2", "targetConfigurationId": "1"}, {"contract": "b@2", "targetConfigurationId": "9"}]"""
        )
        val desired = mapOf("a@2" to "1", "b@2" to "2", "c@2" to "3", "d@2" to null)
        assertEquals(
            listOf("contract binding b@2", "contract binding c@2", "contract binding d@2"),
            PdcaApp.missingBindings(desired, current),
        )
        assertEquals(emptyList(), PdcaApp.missingBindings(mapOf("a@2" to "1"), current))
    }

    @Test
    fun `a case definition version is missing when not imported or inactive`() {
        val versions = Json.mapper.readTree(
            """[{"versionTag": "1.0.0", "active": false}, {"versionTag": "1.1.0", "active": true}]"""
        ).toList()
        assertEquals(null, CaseDefinitions.versionState(versions, "1.1.0"))
        assertEquals("inactive", CaseDefinitions.versionState(versions, "1.0.0"))
        assertEquals("not imported", CaseDefinitions.versionState(versions, "2.0.0"))
        assertEquals("not imported", CaseDefinitions.versionState(emptyList(), "1.0.0"))
    }

    @Test
    fun `the waiter polls until the condition holds, within its time`() {
        var now = 0L
        val waiter = Waiter(clock = { now }, sleep = { now += it }, intervalMillis = 5_000, reportEveryMillis = 60_000)
        var calls = 0
        assertTrue(waiter.await("x", "start x", seconds = 60) { ++calls >= 4 })
        assertEquals(4, calls)
        assertEquals(15_000, now)

        now = 0
        assertFalse(waiter.await("x", "start x", seconds = 60) { false })
        assertEquals(60_000, now)
        assertFalse(waiter.await("x", "start x", seconds = 0) { false })
        assertTrue(waiter.await("x", "start x", seconds = 0) { true })
    }
}
