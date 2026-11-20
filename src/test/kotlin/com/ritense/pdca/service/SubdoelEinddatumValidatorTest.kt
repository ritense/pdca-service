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

import com.ritense.pdca.domain.DoelDetails
import com.ritense.pdca.domain.PlanDetails
import com.ritense.pdca.registers.openplan.Doel
import com.ritense.pdca.registers.openplan.OpenPlanGateway
import com.ritense.pdca.repository.DoelDetailsRepository
import com.ritense.pdca.repository.PlanDetailsRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate
import java.util.Optional
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubdoelEinddatumValidatorTest {

    private val vandaag = LocalDate.of(2026, 11, 11)
    private val planUuid = UUID.fromString("11111111-1111-1111-1111-111111111111")

    private val planDetailsRepository = mock(PlanDetailsRepository::class.java)
    private val doelDetailsRepository = mock(DoelDetailsRepository::class.java)
    private val openPlan = mock(OpenPlanGateway::class.java)
    private val validator = SubdoelEinddatumValidator(planDetailsRepository, doelDetailsRepository, openPlan)

    // ------------------------------------------------------------ violation

    @Test
    fun `a date from today up to the plan's date is allowed`() {
        val plan = vandaag.plusMonths(6)
        assertNull(SubdoelEinddatumValidator.violation(vandaag, plan, vandaag))
        assertNull(SubdoelEinddatumValidator.violation(plan, plan, vandaag))
    }

    @Test
    fun `a date in the past is refused`() {
        val fout = SubdoelEinddatumValidator.violation(vandaag.minusDays(1), null, vandaag)
        assertNotNull(fout)
        assertTrue(fout.contains("verleden"))
    }

    @Test
    fun `a date after the plan's date is refused with that date in the message`() {
        val fout = SubdoelEinddatumValidator.violation(LocalDate.of(2027, 7, 1), LocalDate.of(2027, 6, 30), vandaag)
        assertNotNull(fout)
        assertTrue(fout.contains("30-06-2027"))
    }

    @Test
    fun `a plan without a date puts no upper bound`() {
        assertNull(SubdoelEinddatumValidator.violation(vandaag.plusYears(10), null, vandaag))
    }

    // --------------------------------------------------------- validatePlan

    @Test
    fun `the plan's date cannot move before the date of an actief subdoel`() {
        val doel = subdoel(LocalDate.of(2027, 6, 1))
        `when`(doelDetailsRepository.findByPlanUuid(planUuid)).thenReturn(listOf(doel, subdoel(null)))
        `when`(openPlan.getDoel(doel.doelUuid)).thenReturn(Doel(uuid = doel.doelUuid, status = "actief"))

        val fout = assertThrows<ResponseStatusException> {
            validator.validatePlan(planUuid, LocalDate.of(2027, 5, 1))
        }
        assertEquals(HttpStatus.CONFLICT, fout.statusCode)
        assertTrue(fout.reason!!.contains("01-06-2027"))
    }

    @Test
    fun `closed subdoelen do not hold back the plan's date`() {
        val doel = subdoel(LocalDate.of(2027, 6, 1))
        `when`(doelDetailsRepository.findByPlanUuid(planUuid)).thenReturn(listOf(doel))
        `when`(openPlan.getDoel(doel.doelUuid)).thenReturn(Doel(uuid = doel.doelUuid, status = "afgerond"))

        assertDoesNotThrow { validator.validatePlan(planUuid, LocalDate.of(2027, 5, 1)) }
    }

    @Test
    fun `subdoelen up to the new plan date are not looked up`() {
        `when`(doelDetailsRepository.findByPlanUuid(planUuid)).thenReturn(listOf(subdoel(LocalDate.of(2027, 5, 1))))
        assertDoesNotThrow { validator.validatePlan(planUuid, LocalDate.of(2027, 5, 1)) }
        verifyNoInteractions(openPlan)
    }

    // ------------------------------------------------------ validateSubdoel

    @Test
    fun `a subdoel date is checked against the plan's stored date`() {
        `when`(planDetailsRepository.findById(planUuid)).thenReturn(Optional.of(plan(LocalDate.now().plusDays(10))))

        val fout = assertThrows<ResponseStatusException> {
            validator.validateSubdoel(planUuid, LocalDate.now().plusDays(11))
        }
        assertEquals(HttpStatus.BAD_REQUEST, fout.statusCode)
        assertDoesNotThrow { validator.validateSubdoel(planUuid, LocalDate.now().plusDays(10)) }
    }

    private fun subdoel(streefEinddatum: LocalDate?) =
        DoelDetails(doelUuid = UUID.randomUUID(), planUuid = planUuid, streefEinddatum = streefEinddatum)

    private fun plan(streefEinddatum: LocalDate?) =
        PlanDetails(planUuid = planUuid, persoonUuid = UUID.randomUUID(), streefEinddatum = streefEinddatum)
}
