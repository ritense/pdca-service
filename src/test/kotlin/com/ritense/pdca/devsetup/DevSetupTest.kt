package com.ritense.pdca.devsetup

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
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
