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

package com.ritense.pdca.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.ritense.pdca.registers.objecten.NewObject
import com.ritense.pdca.registers.objecten.ObjectRecord
import com.ritense.pdca.registers.objecten.ObjectRef
import com.ritense.pdca.registers.objecten.ObjectenGateway
import com.ritense.pdca.registers.objecten.RegisterObject
import com.ritense.pdca.registers.openplan.Doel
import com.ritense.pdca.registers.openplan.DoelRef
import com.ritense.pdca.registers.openplan.Instrument
import com.ritense.pdca.registers.openplan.OpenPlanGateway
import com.ritense.pdca.registers.openplan.Plan
import com.ritense.pdca.registers.openplan.PlanRef
import com.ritense.pdca.registers.openproduct.Actie
import com.ritense.pdca.registers.openproduct.OpenProductGateway
import com.ritense.pdca.registers.openproduct.ProductType
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InstrumentAanvraagServiceTest {

    private val objectMapper = jacksonObjectMapper()
    private val openPlan = mock(OpenPlanGateway::class.java)
    private val openProduct = mock(OpenProductGateway::class.java)
    private val objecten = mock(ObjectenGateway::class.java)
    private val service = InstrumentAanvraagService(
        openPlanGateway = openPlan,
        openProductGateway = openProduct,
        objectenGateway = objecten,
        objectMapper = objectMapper
    )

    private val planUuid = UUID.randomUUID()
    private val doelUuid = UUID.randomUUID()
    private val jobcoach = "urn:pdca:openproduct:producttype:JOBCOACHING"
    private val nu = OffsetDateTime.parse("2026-11-02T12:00:00Z")

    @Test
    fun `verzoeken are open until an instrument with a dossier appears for the same doel and product, oldest matched first`() {
        val oud = verzoek(jobcoach, nu.minusMinutes(30))
        val nieuw = verzoek(jobcoach, nu.minusMinutes(5))
        val ander = verzoek("urn:pdca:openproduct:producttype:TAALCOACH", nu.minusMinutes(1))
        val metDossier = Instrument(
            uuid = UUID.randomUUID(), product = jobcoach, zaak = "urn:pdca:gzac:dossier:instrument-verzoek:d1", doelen = listOf(DoelRef(doelUuid))
        )
        val zonderDossier = Instrument(uuid = UUID.randomUUID(), product = jobcoach, doelen = listOf(DoelRef(doelUuid)))

        val open = service.open(listOf(nieuw, ander, oud), listOf(metDossier, zonderDossier), nu)

        assertEquals(listOf(nieuw.aangevraagdOp.toString(), ander.aangevraagdOp.toString()), open.map { it.aangevraagdOp })
        assertEquals(listOf(5L, 1L), open.map { it.minutenOpen })
    }

    @Test
    fun `a second request for a doel and product with an open verzoek is refused`() {
        val producttypeUuid = UUID.randomUUID()
        `when`(openPlan.getDoel(doelUuid)).thenReturn(Doel(uuid = doelUuid, status = "actief", plannen = listOf(PlanRef(planUuid, "actief"))))
        `when`(openPlan.getPlan(planUuid)).thenReturn(Plan(uuid = planUuid, status = "actief"))
        `when`(openProduct.getProducttype(producttypeUuid)).thenReturn(ProductType(producttypeUuid, "JOBCOACHING", "Jobcoaching"))
        `when`(objecten.listObjects(mapOf("type" to "instrument", "data__doelUuid" to doelUuid.toString()))).thenReturn(
            listOf(
                RegisterObject(
                    url = "http://objecten/1",
                    record = ObjectRecord(data = tree(mapOf("type" to "instrument", "data" to mapOf(
                        "doelUuid" to doelUuid.toString(), "productUrn" to jobcoach, "aangevraagdOp" to "2026-11-02T10:15:00Z"
                    ))))
                )
            )
        )
        `when`(openPlan.listInstrumenten(doelUuid)).thenReturn(emptyList())

        val fout = assertFailsWith<ResponseStatusException> { service.start(doelUuid, producttypeUuid) }

        assertEquals(HttpStatus.CONFLICT, fout.statusCode)
        assertTrue(fout.reason!!.startsWith("'Jobcoaching' is voor dit doel al aangevraagd"), fout.reason)
        verify(objecten, never()).createObject(anyValue())
    }

    @Test
    fun `no instrument can be requested under a doel that no longer runs`() {
        `when`(openPlan.getDoel(doelUuid)).thenReturn(Doel(uuid = doelUuid, status = "afgerond", plannen = listOf(PlanRef(planUuid, "actief"))))
        `when`(openPlan.getPlan(planUuid)).thenReturn(Plan(uuid = planUuid, status = "actief"))

        val fout = assertFailsWith<ResponseStatusException> { service.start(doelUuid, UUID.randomUUID()) }

        assertEquals(HttpStatus.CONFLICT, fout.statusCode)
        verify(objecten, never()).createObject(anyValue())
    }

    @Test
    fun `of two concurrent requests for the same doel and product only one is filed`() {
        val producttypeUuid = UUID.randomUUID()
        `when`(openPlan.getDoel(doelUuid)).thenReturn(Doel(uuid = doelUuid, status = "actief", plannen = listOf(PlanRef(planUuid, "actief"))))
        `when`(openPlan.getPlan(planUuid)).thenReturn(Plan(uuid = planUuid, status = "actief"))
        `when`(openPlan.listInstrumenten(doelUuid)).thenReturn(emptyList())
        `when`(openProduct.getProducttype(producttypeUuid)).thenReturn(ProductType(producttypeUuid, "JOBCOACHING", "Jobcoaching"))
        `when`(openProduct.listActies(producttypeUuid)).thenReturn(
            listOf(
                Actie(
                    naam = InstrumentAanvraagService.START_ACTIE,
                    mapping = tree(mapOf("static" to listOf(mapOf("name" to "type", "value" to "http://objecttypen/api/v2/objecttypes/verzoek"))))
                )
            )
        )
        val registered = CopyOnWriteArrayList<RegisterObject>()
        `when`(objecten.listObjects(mapOf("type" to "instrument", "data__doelUuid" to doelUuid.toString()))).thenAnswer { registered.toList() }
        `when`(objecten.createObject(anyValue())).thenAnswer {
            Thread.sleep(50)
            val nieuw = it.arguments[0] as NewObject
            registered += RegisterObject(url = "http://objecten/${registered.size + 1}", record = ObjectRecord(data = tree(nieuw.record.data)))
            ObjectRef(url = registered.last().url)
        }

        val barrier = CyclicBarrier(2)
        val executor = Executors.newFixedThreadPool(2)
        val outcomes = try {
            List(2) {
                executor.submit<Int> {
                    barrier.await(10, TimeUnit.SECONDS)
                    try {
                        service.start(doelUuid, producttypeUuid)
                        200
                    } catch (e: ResponseStatusException) {
                        e.statusCode.value()
                    }
                }
            }.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        assertEquals(listOf(200, 409), outcomes.sorted())
        assertEquals(1, registered.size)
    }

    private fun verzoek(productUrn: String, op: OffsetDateTime) =
        InstrumentAanvraagService.VerzoekRecord(null, planUuid, doelUuid, productUrn, null, op)

    @Test
    fun `the start recipe comes out of the actie mapping`() {
        val mapping = mapOf(
            "static" to listOf(
                mapOf("name" to "soort", "classType" to "String", "value" to "Verzoek"),
                mapOf("name" to "type", "classType" to "String", "value" to "http://objecttypen/api/v2/objecttypes/abc"),
                mapOf("name" to "typeVersion", "classType" to "Integer", "value" to "2"),
                mapOf("name" to "kanaal", "classType" to "String", "value" to "plan")
            ),
            "plan" to listOf(mapOf("name" to "data.planid", "classType" to "String", "regex" to "$.uuid")),
            "productType" to listOf(mapOf("name" to "code", "classType" to "String", "regex" to "$.code"))
        )

        val actie = service.parseStartActie(objectMapper.valueToTree(mapping))

        assertEquals("verzoek", actie.soort)
        assertEquals("http://objecttypen/api/v2/objecttypes/abc", actie.objecttype)
        assertEquals(2, actie.typeVersion)
        assertEquals(mapOf<String, Any?>("kanaal" to "plan"), actie.statics)

        val data = service.resolveMappings(
            actie, mapOf("plan" to tree(mapOf("uuid" to "plan-1")), "producttype" to tree(mapOf("code" to "CV")))
        )
        assertEquals(mapOf<String, Any?>("planid" to "plan-1", "code" to "CV"), data)
    }

    @Test
    fun `without a mapping the recipe is a verzoek without objecttype`() {
        val actie = service.parseStartActie(null)

        assertEquals("verzoek", actie.soort)
        assertNull(actie.objecttype)
        assertEquals(1, actie.typeVersion)
        assertTrue(actie.statics.isEmpty())
        assertTrue(actie.mappings.isEmpty())
    }

    @Test
    fun `an unknown source and a missing value are skipped`() {
        val actie = service.parseStartActie(
            tree(mapOf(
                "inwonersprofiel" to listOf(mapOf("name" to "belastbaarheid", "regex" to "$.situatie.belastbaarheid")),
                "doel" to listOf(
                    mapOf("name" to "doeltype", "regex" to "$.doeltype.naam"),
                    mapOf("name" to "ontbreekt", "regex" to "$.nergens.te.vinden")
                )
            ))
        )

        val data = service.resolveMappings(actie, mapOf("doel" to tree(mapOf("doeltype" to mapOf("naam" to "Werk")))))

        assertEquals(mapOf<String, Any?>("doeltype" to "Werk"), data)
    }

    @Test
    fun `a recipe can map any producttype field, also one the app does not read itself`() {
        val producttype = objectMapper.readValue(
            """{"uuid":"${UUID.randomUUID()}","code":"CV","naam":"Cv","doelgroep":"burgers","parameters":[]}""",
            ProductType::class.java
        )
        val actie = service.parseStartActie(
            tree(mapOf("productType" to listOf(mapOf("name" to "doelgroep", "regex" to "$.doelgroep"))))
        )

        val data = service.resolveMappings(actie, mapOf("producttype" to objectMapper.valueToTree(producttype)))

        assertEquals(mapOf<String, Any?>("doelgroep" to "burgers"), data)
    }

    private fun tree(value: Any) = objectMapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(value)
}
