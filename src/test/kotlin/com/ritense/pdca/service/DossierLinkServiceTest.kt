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

import com.ritense.pdca.domain.PlanDetails
import com.ritense.pdca.registers.StubRegisterService
import com.ritense.pdca.registers.openplan.OpenPlanGateway
import com.ritense.pdca.registers.openplan.Plan
import com.ritense.pdca.repository.PlanDetailsRepository
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.UUID
import kotlin.test.assertEquals

class DossierLinkServiceTest {

    private val gzacClient = mock(GzacClient::class.java)
    private val openPlan = mock(OpenPlanGateway::class.java)
    private val planDetails = InMemoryRepository(PlanDetails::class, "planUuid")
    private val service = DossierLinkService(planDetails.proxy(PlanDetailsRepository::class), gzacClient, openPlan, mock(StubRegisterService::class.java))

    private val planUuid = UUID.randomUUID()

    init {
        planDetails.rows[planUuid] = PlanDetails(planUuid = planUuid, persoonUuid = UUID.randomUUID(), caseDefinitionKey = "inwonerplan")
        `when`(openPlan.getPlan(planUuid)).thenReturn(Plan(uuid = planUuid, status = "actief", titel = "Plan"))
    }

    @Test
    fun `a plan dossier an earlier attempt created but did not link is linked instead of created again`() {
        `when`(gzacClient.findDossierIds("inwonerplan", "planId", planUuid.toString())).thenReturn(listOf("eerder-aangemaakt", "dubbel"))

        val (dossierId, nieuw) = service.ensurePlanDossier(planUuid)

        assertEquals("eerder-aangemaakt", dossierId)
        assertEquals(false, nieuw)
        assertEquals("eerder-aangemaakt", planDetails.rows[planUuid]!!.dossierId)
        verify(gzacClient, never()).createDossier(anyValue(), anyValue())
    }

    @Test
    fun `without a plan dossier in GZAC a new one is created and linked`() {
        `when`(gzacClient.findDossierIds("inwonerplan", "planId", planUuid.toString())).thenReturn(emptyList())
        `when`(gzacClient.createDossier(eqValue("inwonerplan"), anyValue())).thenReturn(GzacClient.Dossier("nieuw-dossier"))

        val (dossierId, nieuw) = service.ensurePlanDossier(planUuid)

        assertEquals("nieuw-dossier", dossierId)
        assertEquals(true, nieuw)
        assertEquals("nieuw-dossier", planDetails.rows[planUuid]!!.dossierId)
    }

    @Test
    fun `a linked plan dossier that still exists is used without a search`() {
        planDetails.rows[planUuid]!!.dossierId = "gekoppeld"
        `when`(gzacClient.documentExists("gekoppeld")).thenReturn(true)

        assertEquals("gekoppeld" to false, service.ensurePlanDossier(planUuid))
        verify(gzacClient, never()).findDossierIds(anyValue(), anyValue(), anyValue())
    }
}
