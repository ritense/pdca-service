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

import com.ritense.pdca.domain.Action
import com.ritense.pdca.registers.openplan.DoelRef
import com.ritense.pdca.registers.openplan.Instrument
import com.ritense.pdca.registers.openplan.OpenPlanGateway
import com.ritense.pdca.repository.ActionRepository
import com.ritense.pdca.repository.DoelDetailsRepository
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ActionServiceTest {

    private val openPlan = mock(OpenPlanGateway::class.java)
    private val acties = InMemoryRepository(Action::class, "id")
    private val service = ActionService(acties.proxy(ActionRepository::class), mock(DoelDetailsRepository::class.java), openPlan)

    private val doelUuid = UUID.randomUUID()
    private val instrument = Instrument(uuid = UUID.randomUUID(), titel = "Jobcoaching", status = "actief", doelen = listOf(DoelRef(doelUuid, "actief")))

    init {
        `when`(openPlan.findInstrument(instrument.uuid)).thenReturn(instrument)
    }

    @Test
    fun `an actie may support an instrument of its own doel`() {
        val actie = service.create(Action(doelUuid = doelUuid, instrumentUuid = instrument.uuid, title = "Intakegesprek plannen"))

        assertEquals(instrument.uuid, acties.rows[actie.id]!!.instrumentUuid)
    }

    @Test
    fun `an instrument of another doel or an unknown one is refused`() {
        listOf(
            Action(doelUuid = UUID.randomUUID(), instrumentUuid = instrument.uuid, title = "Andere doel"),
            Action(doelUuid = doelUuid, instrumentUuid = UUID.randomUUID(), title = "Onbekend instrument")
        ).forEach { actie ->
            val fout = assertFailsWith<ResponseStatusException> { service.create(actie) }
            assertEquals(HttpStatus.BAD_REQUEST, fout.statusCode)
        }
        assertTrue(acties.rows.isEmpty())
    }
}
