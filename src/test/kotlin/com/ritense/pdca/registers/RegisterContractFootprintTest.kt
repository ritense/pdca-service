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

package com.ritense.pdca.registers

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.ritense.pdca.config.PdcaProperties
import com.ritense.pdca.plugin.GzacAccess
import com.ritense.pdca.registers.objecten.ObjectenGateway
import com.ritense.pdca.registers.openklant.OpenKlantGateway
import com.ritense.pdca.registers.openplan.OpenPlanGateway
import com.ritense.pdca.registers.openproduct.OpenProductGateway
import com.ritense.pdca.security.DataAccessPolicy
import com.ritense.pdca.security.PlanScope
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.client.ClientHttpRequestFactory
import org.springframework.mock.http.client.MockClientHttpRequest
import org.springframework.mock.http.client.MockClientHttpResponse
import org.springframework.web.client.RestClient
import java.io.File
import java.net.URI
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.KVisibility
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.instanceParameter
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.full.valueParameters
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * What the app asks of each register contract against what the register
 * plugin serves. The app side is observed, not declared: every public
 * method of every gateway is called against a recording transport, and the
 * register proxy routes of [DataAccessPolicy] are translated the same way,
 * both through [RegisterContractRequestFactory.toContractCall]. The plugin
 * side is the `RegisterSpec` allowlist in `plugins/<key>/src/<key>.ts`.
 * Every (resource, operation) the app uses must be allowed, with only the
 * query parameters a resource restricts itself to, and the plugins allow
 * nothing the app does not use.
 */
class RegisterContractFootprintTest {

    private val objectMapper = jacksonObjectMapper()

    /** One contract call: its resource, operation and query parameter names. */
    private data class Use(val resource: String, val operation: String, val queryKeys: Set<String>, val by: String)

    /** A resource of a plugin's allowlist. */
    private data class Rule(val operations: Set<String>, val queryKeys: Set<String>?)

    @Test
    fun `every register call of the app is allowed by its plugin, and the plugins allow nothing more`() {
        val gatewayUses = gatewayUses()
        val proxyUses = proxyUses()
        Register.entries.forEach { register ->
            val spec = pluginSpec(register)
            val used = gatewayUses[register].orEmpty() + proxyUses[register].orEmpty()
            assertTrue(used.isNotEmpty(), "the app uses ${register.contract}")
            used.forEach { use ->
                val rule = spec[use.resource] ?: fail("${use.by}: resource '${use.resource}' is not served by plugins/${register.key}")
                assertTrue(use.operation in rule.operations, "${use.by}: '${use.operation}' on '${use.resource}' is not allowed by plugins/${register.key}")
                rule.queryKeys?.let { allowed ->
                    assertTrue(allowed.containsAll(use.queryKeys), "${use.by}: query ${use.queryKeys - allowed} on '${use.resource}' is not allowed by plugins/${register.key}")
                }
            }
            val usedOperations = used.map { it.resource to it.operation }.toSet()
            val allowedOperations = spec.flatMap { (resource, rule) -> rule.operations.map { resource to it } }.toSet()
            assertEquals(emptySet(), allowedOperations - usedOperations, "plugins/${register.key} allows operations the app never uses")
        }
    }

    // ------------------------------------------------------------ the app

    /** Calls every public gateway method with sample arguments and records the contract calls it makes. */
    private fun gatewayUses(): Map<Register, List<Use>> {
        val properties = PdcaProperties()
        val gateways: Map<Register, (RestClient) -> Any> = mapOf(
            Register.OPENPLAN to { client -> OpenPlanGateway(client, objectMapper, properties) },
            Register.OPENPRODUCT to { client -> OpenProductGateway(client, objectMapper, properties) },
            Register.OPENKLANT to { client -> OpenKlantGateway(client, objectMapper, properties) },
            Register.OBJECTEN to { client -> ObjectenGateway(client, objectMapper, properties) }
        )
        assertEquals(Register.entries.toSet(), gateways.keys, "a gateway per register")
        return gateways.mapValues { (register, create) ->
            val recorded = mutableListOf<Pair<HttpMethod, URI>>()
            val gateway = create(RestClient.builder().requestFactory(recording(recorded)).build())
            gateway::class.declaredMemberFunctions.filter { it.visibility == KVisibility.PUBLIC }.flatMap { function ->
                recorded.clear()
                val arguments = mapOf(function.instanceParameter!! to gateway) +
                    function.valueParameters.filterNot { it.isOptional }.associateWith { sample(it.type) }
                runCatching { function.callBy(arguments) }
                val by = "${gateway::class.simpleName}.${function.name}"
                if (recorded.isEmpty()) fail("$by made no register call")
                recorded.map { (method, uri) -> use(register, method, uri, by) }
            }
        }
    }

    /** The register proxy routes the bundles reach through `/data`. */
    private fun proxyUses(): Map<Register, List<Use>> {
        val policy = DataAccessPolicy(mock(PlanScope::class.java))
        return policy.routes.filter { it.register }.map { route ->
            val register = Register.entries.singleOrNull { route.pattern.startsWith("/${it.key}/") }
                ?: fail("proxy route ${route.method} ${route.pattern} belongs to no register")
            val query = route.queryKeys.joinToString("&") { "$it=x" }
            val uri = URI.create(route.pattern.replace("{}", UUID.randomUUID().toString()) + if (query.isEmpty()) "" else "?$query")
            register to use(register, HttpMethod.valueOf(route.method), uri, "proxy ${route.method} ${route.pattern}")
        }.groupBy({ it.first }, { it.second })
    }

    private fun use(register: Register, method: HttpMethod, uri: URI, by: String): Use {
        val call = RegisterContractRequestFactory(
            register, mock(GzacAccess::class.java), objectMapper, RestClient.builder(), ContractCallLimiter(PdcaProperties.Registers())
        ).toContractCall(uri, method, null) ?: fail("$by: $method $uri has no contract operation")
        @Suppress("UNCHECKED_CAST")
        val query = call.payload["query"] as? Map<String, String> ?: emptyMap()
        return Use(call.payload["resource"] as String, call.operation, query.keys, by)
    }

    /** A transport that records each request and answers an empty page (which most typed reads then fail to read; that is fine). */
    private fun recording(recorded: MutableList<Pair<HttpMethod, URI>>) = ClientHttpRequestFactory { uri, method ->
        recorded += method to uri
        MockClientHttpRequest(method, uri).apply {
            setResponse(
                MockClientHttpResponse("""{"count":0,"next":null,"previous":null,"results":[]}""".toByteArray(), HttpStatus.OK)
                    .apply { headers.contentType = MediaType.APPLICATION_JSON }
            )
        }
    }

    /** A value of [type]: required constructor arguments filled in recursively, nullable ones null. */
    private fun sample(type: KType): Any? {
        if (type.isMarkedNullable) return null
        val kind = type.classifier as KClass<*>
        return when {
            kind == UUID::class -> UUID.randomUUID()
            kind == String::class -> "x"
            kind == Int::class -> 1
            kind == Long::class -> 1L
            kind == Boolean::class -> false
            kind == LocalDate::class -> LocalDate.now()
            kind == OffsetDateTime::class -> OffsetDateTime.now()
            kind.isSubclassOf(JsonNode::class) -> objectMapper.createObjectNode()
            kind.isSubclassOf(Map::class) -> mapOf(sample(type.arguments[0].type!!) to sample(type.arguments[1].type!!))
            kind.isSubclassOf(Collection::class) -> listOf(sample(type.arguments[0].type!!))
            kind.java.isEnum -> kind.java.enumConstants.first()
            else -> {
                val constructor = kind.primaryConstructor ?: fail("no sample for $type")
                constructor.callBy(constructor.parameters.filterNot { it.isOptional }.associateWith { sample(it.type) })
            }
        }
    }

    // ------------------------------------------------------------ the plugins

    /**
     * The `resources` of `plugins/<key>/src/<key>.ts`: one line per
     * resource, `name: {operations: [...], queryKeys?: [...]}`. A line in
     * the block this does not read fails the test, so a format change shows
     * up here instead of passing silently.
     */
    private fun pluginSpec(register: Register): Map<String, Rule> {
        val source = File("plugins/${register.key}/src/${register.key}.ts").readText()
        assertTrue(source.contains("contract: \"${register.contract}\""), "plugins/${register.key} serves ${register.contract}")
        assertTrue(source.contains("apiPath: \"${register.apiPath}\""), "plugins/${register.key} covers ${register.apiPath}")
        val block = Regex("""resources:\s*\{\n(.*?)\n\s*},""", RegexOption.DOT_MATCHES_ALL).find(source)?.groupValues?.get(1)
            ?: fail("plugins/${register.key}: no resources block")
        val line = Regex("""^\s*"?([a-z][a-z-]*)"?:\s*\{operations:\s*\[([^\]]*)](?:,\s*queryKeys:\s*\[([^\]]*)])?},?\s*$""")
        return block.lines().filter { it.isNotBlank() }.associate { text ->
            val match = line.matchEntire(text) ?: fail("plugins/${register.key}: unreadable resource line '$text'")
            val (name, operations, queryKeys) = match.destructured
            name to Rule(strings(operations), queryKeys.takeIf { it.isNotBlank() }?.let(::strings))
        }
    }

    private fun strings(list: String): Set<String> =
        Regex(""""([^"]+)"""").findAll(list).map { it.groupValues[1] }.toSet()
}
