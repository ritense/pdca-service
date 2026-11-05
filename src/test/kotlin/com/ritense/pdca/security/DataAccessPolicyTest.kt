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

package com.ritense.pdca.security

import com.fasterxml.jackson.databind.ObjectMapper
import com.ritense.pdca.domain.DoelDetails
import com.ritense.pdca.domain.PlanDetails
import com.ritense.pdca.domain.UitvoeringsStatus
import com.ritense.pdca.registers.openplan.OpenPlanGateway
import com.ritense.pdca.repository.ActionRepository
import com.ritense.pdca.repository.ContactmomentDetailsRepository
import com.ritense.pdca.repository.DoelDetailsRepository
import com.ritense.pdca.repository.InstrumentDetailsRepository
import com.ritense.pdca.repository.InvolvedPartyRepository
import com.ritense.pdca.repository.PlanDetailsRepository
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.web.server.ResponseStatusException
import java.util.Optional
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DataAccessPolicyTest {

    private val planUuid = UUID.randomUUID()
    private val doelUuid = UUID.randomUUID()
    private val persoonUuid = UUID.randomUUID()
    private val planDetails = mock(PlanDetailsRepository::class.java)
    private val doelDetails = mock(DoelDetailsRepository::class.java)
    private val openPlan = mock(OpenPlanGateway::class.java)
    private val policy = DataAccessPolicy(
        PlanScope(
            planDetails, doelDetails, mock(InstrumentDetailsRepository::class.java),
            mock(ContactmomentDetailsRepository::class.java), mock(ActionRepository::class.java),
            mock(InvolvedPartyRepository::class.java), openPlan
        )
    )
    private val json = ObjectMapper()

    init {
        `when`(planDetails.findById(any() ?: planUuid)).thenReturn(Optional.empty())
        `when`(planDetails.findById(planUuid)).thenReturn(Optional.of(PlanDetails(planUuid, persoonUuid, dossierId = "plan-dossier")))
        `when`(doelDetails.findById(any() ?: doelUuid)).thenReturn(Optional.empty())
        `when`(doelDetails.findById(doelUuid)).thenReturn(
            Optional.of(DoelDetails(doelUuid = doelUuid, planUuid = planUuid, uitvoeringsStatus = UitvoeringsStatus.GEPLAND))
        )
    }

    private fun call(method: String, path: String, query: Map<String, String> = emptyMap(), body: String? = null, context: Map<String, Any?> = emptyMap()) =
        DataCall(method, path, query, body?.let { json.readTree(it) }, context)

    private val planDossier = Requirement.Dossiers(setOf("plan-dossier"))

    @Test
    fun `reference data is open to every verified user, PDCA Beheer to administrators`() {
        assertEquals(Requirement.AnyUser, policy.resolve(call("GET", "/openplan/plannen/api/v0/doeltype", mapOf("page" to "1"))).requirement)
        assertEquals(Requirement.AnyUser, policy.resolve(call("GET", "/api/v1/admin/phase-configs/inwonerplan")).requirement)
        assertEquals(Requirement.Admin, policy.resolve(call("PUT", "/api/v1/admin/phase-configs/inwonerplan", body = "{}")).requirement)
        assertEquals(Requirement.Admin, policy.resolve(call("GET", "/api/v1/pdca/case-definitions")).requirement)
    }

    @Test
    fun `plan data resolves to the plan dossier, also through a doel`() {
        assertEquals(planDossier, policy.resolve(call("GET", "/api/v1/pdca/doeldetails", mapOf("planUuid" to "$planUuid"))).requirement)
        assertEquals(planDossier, policy.resolve(call("DELETE", "/api/v1/pdca/doelen/$doelUuid")).requirement)
        assertEquals(
            planDossier,
            policy.resolve(call("GET", "/openplan/plannen/api/v0/instrument", mapOf("doelen__uuid__in" to "$doelUuid", "page" to "1"))).requirement
        )
        assertTrue(policy.resolve(call("GET", "/openplan/plannen/api/v0/plan/$planUuid")).route.register)
    }

    @Test
    fun `the intake and an evaluation start check the dossier they name`() {
        assertEquals(
            Requirement.Dossiers(setOf("intake-1")),
            policy.resolve(call("POST", "/api/v1/pdca/plan-intake", body = """{"documentId":"intake-1"}""")).requirement
        )
        assertEquals(
            Requirement.Dossiers(setOf("tab-dossier")),
            policy.resolve(call("POST", "/api/v1/pdca/evaluation-sessions", context = mapOf("documentId" to "tab-dossier"))).requirement
        )
        assertFailsWith<ResponseStatusException> { policy.resolve(call("POST", "/api/v1/pdca/evaluation-sessions")) }
    }

    @Test
    fun `register writes are limited to the fields the bundles edit`() {
        assertEquals(planDossier, policy.resolve(call("PATCH", "/openplan/plannen/api/v0/doel/$doelUuid", body = """{"status":"afgerond","resultaat":"behaald"}""")).requirement)
        denied(call("PATCH", "/openplan/plannen/api/v0/doel/$doelUuid", body = """{"status":"geannuleerd"}"""))
        denied(call("PATCH", "/openplan/plannen/api/v0/doel/$doelUuid", body = """{"plannenUuids":["$planUuid"]}"""))
        denied(call("PATCH", "/openplan/plannen/api/v0/plan/$planUuid", body = """{"notitie":"n","status":"afgerond"}"""))
        denied(call("DELETE", "/openplan/plannen/api/v0/doel/$doelUuid"))
        denied(call("PATCH", "/openplan/plannen/api/v0/instrument/${UUID.randomUUID()}", body = """{"status":"afgerond"}"""))
    }

    @Test
    fun `a checked body field that is not a non-blank string is refused`() {
        fun nieuwDoel(hoofdDoel: String) = call(
            "POST", "/openplan/plannen/api/v0/doel",
            body = """{"plannenUuids":["$planUuid"],"persoonUuid":"$persoonUuid","titel":"Werk","hoofdDoel":$hoofdDoel}"""
        )
        assertEquals(planDossier, policy.resolve(nieuwDoel("\"$doelUuid\"")).requirement)
        assertEquals(planDossier, policy.resolve(nieuwDoel("null")).requirement, "a doel without hoofddoel")
        denied(nieuwDoel("42"))
        denied(nieuwDoel("{\"uuid\":\"$doelUuid\"}"))
        denied(nieuwDoel("\"\""))

        denied(call("PATCH", "/openplan/plannen/api/v0/doel/$doelUuid", body = """{"status":1}"""))
        denied(call("PATCH", "/openplan/plannen/api/v0/doel/$doelUuid", body = """{"status":null}"""))
        denied(call("PATCH", "/openplan/plannen/api/v0/doel/$doelUuid", body = """{"status":""}"""))
        denied(call("PUT", "/api/v1/pdca/doeldetails/$doelUuid", body = """{"planUuid":5}"""))
        assertEquals(planDossier, policy.resolve(call("PUT", "/api/v1/pdca/doeldetails/$doelUuid", body = """{"planUuid":null}""")).requirement)
    }

    @Test
    fun `listings must carry their scoping filter and nothing else`() {
        denied(call("GET", "/openplan/plannen/api/v0/doel"))
        denied(call("GET", "/openplan/plannen/api/v0/instrument"))
        denied(call("GET", "/openklant/klantinteracties/api/v1/klantcontacten", mapOf("onderwerpobject__onderwerpobjectidentificatorObjectId" to "$planUuid")))
        denied(call("GET", "/api/v1/pdca/acties", mapOf("planUuid" to "$planUuid", "doelUuid" to "$doelUuid")))
        assertEquals(
            planDossier,
            policy.resolve(
                call(
                    "GET", "/openklant/klantinteracties/api/v1/klantcontacten",
                    mapOf(
                        "onderwerpobject__onderwerpobjectidentificatorObjectId" to "$planUuid",
                        "onderwerpobject__onderwerpobjectidentificatorCodeObjecttype" to "plan",
                        "onderwerpobject__onderwerpobjectidentificatorCodeRegister" to "open-plan"
                    )
                )
            ).requirement
        )
    }

    @Test
    fun `unknown targets, unknown routes and odd paths are refused`() {
        denied(call("GET", "/api/v1/pdca/plannen/${UUID.randomUUID()}/afsluitcheck"))
        denied(call("GET", "/api/v1/pdca/plandetails"))
        denied(call("GET", "/api/host/configurations"))
        denied(call("GET", "/api/v1/pdca/plandetails/../plandetails"))
        denied(call("GET", "/api/v1/pdca//plandetails"))
        denied(call("GET", "/api/v1/pdca/plandetails/%2e%2e"))
        assertEquals(400, assertFailsWith<ResponseStatusException> { policy.resolve(call("GET", "/api/v1/pdca/plannen/not-a-uuid/afsluitcheck")) }.statusCode.value())
    }

    private fun denied(call: DataCall) {
        assertEquals(403, assertFailsWith<ResponseStatusException> { policy.resolve(call) }.statusCode.value())
    }
}
