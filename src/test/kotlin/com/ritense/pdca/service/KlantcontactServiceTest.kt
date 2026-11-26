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

import com.ritense.pdca.registers.openklant.MaakKlantcontact
import com.ritense.pdca.registers.openklant.OpenKlantGateway
import com.ritense.pdca.registers.openplan.OpenPlanGateway
import com.ritense.pdca.registers.openplan.Plan
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import com.ritense.pdca.registers.openklant.Klantcontact as OpenKlantKlantcontact

class KlantcontactServiceTest {

    private val openKlant = mock(OpenKlantGateway::class.java)
    private val openPlan = mock(OpenPlanGateway::class.java)
    private val service = KlantcontactService(openKlant, openPlan)
    private val planUuid = UUID.randomUUID()

    @Test
    fun `every contactmoment of a plan is written as an evaluation of that plan`() {
        `when`(openPlan.getPlan(planUuid)).thenReturn(Plan(uuid = planUuid, status = "actief", titel = "Plan: Erika"))
        val onderwerpen = mutableListOf<String>()
        `when`(openKlant.maakKlantcontact(anyValue())).thenAnswer {
            onderwerpen += (it.arguments[0] as MaakKlantcontact).klantcontact.onderwerp
            OpenKlantKlantcontact(uuid = UUID.randomUUID())
        }

        service.create(NewContactmoment(planUuid, ContactmomentService.plaatsgevondenOp(LocalDate.now()), afgerond = false, verslag = null))

        assertEquals(listOf("Evaluatie: Plan: Erika"), onderwerpen)
    }
}
