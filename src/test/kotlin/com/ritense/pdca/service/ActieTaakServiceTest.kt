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
import com.ritense.pdca.domain.ActionStatus
import com.ritense.pdca.domain.DoelDetails
import com.ritense.pdca.domain.PlanDetails
import com.ritense.pdca.plugin.GzacAccess
import com.ritense.pdca.plugin.PluginConfiguration
import com.ritense.pdca.registers.openplan.OpenPlanGateway
import com.ritense.pdca.repository.ActionRepository
import com.ritense.pdca.repository.DoelDetailsRepository
import com.ritense.pdca.repository.PlanDetailsRepository
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActieTaakServiceTest {

    private val planUuid = UUID.randomUUID()
    private val doelUuid = UUID.randomUUID()
    private val configId = "11111111-1111-4111-8111-111111111111"
    private val acties = InMemoryRepository(Action::class, "id")
    private val doelDetails = InMemoryRepository(DoelDetails::class, "doelUuid")
    private val planDetails = InMemoryRepository(PlanDetails::class, "planUuid")
    private val gzac = mock(GzacClient::class.java)
    private val gzacAccess = mock(GzacAccess::class.java)

    /** recordTaak as the database does it: only while no process is recorded. */
    private val actieRepository = acties.proxy(
        ActionRepository::class,
        mapOf("recordTaak" to { args ->
            val actie = acties.rows[args[0]]
            if (actie == null || actie.taakProcessInstanceId != null) 0 else { actie.taakProcessInstanceId = args[1] as String; 1 }
        })
    )
    private val service = ActieTaakService(
        actieRepository, doelDetails.proxy(DoelDetailsRepository::class), planDetails.proxy(PlanDetailsRepository::class),
        mock(OpenPlanGateway::class.java), gzac, gzacAccess
    )

    init {
        doelDetails.rows[doelUuid] = DoelDetails(doelUuid = doelUuid, planUuid = planUuid)
        planDetails.rows[planUuid] = PlanDetails(planUuid, UUID.randomUUID(), caseDefinitionKey = "inwonerplan", dossierId = "plan-dossier")
        `when`(gzacAccess.active()).thenReturn(
            GzacAccess.Access("http://gzac", "token", PluginConfiguration(configId, "PDCA", emptyMap(), "token", "http://gzac"))
        )
        `when`(gzac.caseDefinitionVersionTag("inwonerplan")).thenReturn("1.7.0")
        `when`(gzac.bouwblokLink("inwonerplan", "1.7.0", "pdca-taak")).thenReturn(GzacClient.BouwblokLink("1.0.0", "pdca-taak:1:abc"))
        `when`(gzac.startformulier("pdca-taak:1:abc", "plan-dossier")).thenReturn("start-link")
    }

    private fun linkMapt(configuratie: String?) {
        `when`(gzac.bouwblokLinkEigenschappen("inwonerplan", "1.7.0", "pdca-taak", "1.0.0")).thenReturn(
            mapOf(
                "inputMappings" to listOf(mapOf("source" to "doc:/bouwblokStart/titel", "target" to "/titel")),
                "outputMappings" to emptyList<Any>(),
                "pluginConfigurationMappings" to (configuratie?.let { mapOf(ActieTaakService.CONFIGURATIE_SLEUTEL to it) } ?: emptyMap())
            )
        )
    }

    private fun opgeslagen(titel: String = "Werkgever bellen", deadline: LocalDate? = null) =
        Action(doelUuid = doelUuid, title = titel, description = "Over de proefplaatsing", dueDate = deadline)
            .also { acties.rows[it.id] = it }

    /** The building block calls back within the start, as its synchronous first service task does. */
    private fun startMeldtZich(proces: String) {
        doAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            val start = (invocation.arguments[2] as Map<String, Map<String, String>>)["bouwblokStart"]!!
            service.taakGestart(mapOf("actieId" to start["actieId"]), proces)
            null
        }.`when`(gzac).startformulierIndienen(anyString(), anyString(), anyValue())
    }

    @Test
    fun `a new actie becomes the building block on the plan dossier, which records its process`() {
        linkMapt(configId)
        startMeldtZich("proces-1")
        val actie = opgeslagen(deadline = LocalDate.of(2026, 12, 1))

        val gestart = service.start(actie)

        assertEquals("proces-1", gestart.taakProcessInstanceId)
        verify(gzac).startformulierIndienen(
            "start-link", "plan-dossier",
            mapOf(
                "bouwblokStart" to linkedMapOf(
                    "actieId" to actie.id.toString(), "titel" to "Werkgever bellen", "omschrijving" to "Over de proefplaatsing",
                    "deadline" to "2026-12-01", "planUuid" to planUuid.toString(), "doelUuid" to doelUuid.toString()
                )
            )
        )
        verify(gzac, never()).bouwblokLinkBijwerken(anyString(), anyString(), anyString(), anyString(), anyValue())
    }

    @Test
    fun `the start sends every field, also the empty ones, so nothing of a previous start stays behind`() {
        linkMapt(configId)
        startMeldtZich("proces-1")
        val actie = Action(doelUuid = doelUuid, title = "Werkgever bellen").also { acties.rows[it.id] = it }

        service.start(actie)

        verify(gzac).startformulierIndienen(
            "start-link", "plan-dossier",
            mapOf(
                "bouwblokStart" to linkedMapOf(
                    "actieId" to actie.id.toString(), "titel" to "Werkgever bellen", "omschrijving" to "",
                    "deadline" to "", "planUuid" to planUuid.toString(), "doelUuid" to doelUuid.toString()
                )
            )
        )
    }

    @Test
    fun `a link without the active configuration is mapped to it first, keeping its mappings`() {
        linkMapt(null)
        startMeldtZich("proces-1")

        service.start(opgeslagen())

        verify(gzac).bouwblokLinkBijwerken(
            "inwonerplan", "1.7.0", "pdca-taak", "1.0.0",
            mapOf(
                "inputMappings" to listOf(mapOf("source" to "doc:/bouwblokStart/titel", "target" to "/titel")),
                "outputMappings" to emptyList<Any>(),
                "pluginConfigurationMappings" to mapOf(ActieTaakService.CONFIGURATIE_SLEUTEL to configId)
            )
        )
    }

    @Test
    fun `a start GZAC refuses removes the actie again`() {
        linkMapt(configId)
        doThrow(GzacClient.StartGeweigerdException("geweigerd")).`when`(gzac).startformulierIndienen(anyString(), anyString(), anyValue())
        val actie = opgeslagen()

        assertFailsWith<GzacClient.StartGeweigerdException> { service.start(actie) }

        assertTrue(acties.rows.isEmpty())
    }

    @Test
    fun `a case type without the building block refuses the actie`() {
        `when`(gzac.bouwblokLink("inwonerplan", "1.7.0", "pdca-taak")).thenReturn(null)

        val fout = assertFailsWith<ResponseStatusException> { service.start(opgeslagen()) }

        assertEquals(HttpStatus.BAD_GATEWAY, fout.statusCode)
        assertTrue(acties.rows.isEmpty())
    }

    @Test
    fun `a start whose answer is lost keeps the actie when its process reported itself`() {
        linkMapt(configId)
        doAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            val start = (invocation.arguments[2] as Map<String, Map<String, String>>)["bouwblokStart"]!!
            service.taakGestart(mapOf("actieId" to start["actieId"]), "proces-1")
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "geen antwoord")
        }.`when`(gzac).startformulierIndienen(anyString(), anyString(), anyValue())
        val actie = opgeslagen()

        assertEquals("proces-1", service.start(actie).taakProcessInstanceId)
        assertTrue(acties.rows.containsKey(actie.id))
    }

    @Test
    fun `a start whose answer is lost without a report removes the actie`() {
        linkMapt(configId)
        doThrow(ResponseStatusException(HttpStatus.BAD_GATEWAY, "geen antwoord")).`when`(gzac).startformulierIndienen(anyString(), anyString(), anyValue())

        assertFailsWith<ResponseStatusException> { service.start(opgeslagen()) }

        assertTrue(acties.rows.isEmpty())
    }

    @Test
    fun `a plan without plan dossier cannot hold a task`() {
        planDetails.rows[planUuid] = PlanDetails(planUuid, UUID.randomUUID(), caseDefinitionKey = "inwonerplan")

        val fout = assertFailsWith<ResponseStatusException> { service.start(opgeslagen()) }

        assertEquals(HttpStatus.CONFLICT, fout.statusCode)
        assertTrue(acties.rows.isEmpty())
    }

    @Test
    fun `taak gestart is safe to repeat and refuses a second process`() {
        val actie = opgeslagen()

        service.taakGestart(mapOf("actieId" to actie.id.toString()), "proces-1")
        service.taakGestart(mapOf("actieId" to actie.id.toString()), "proces-1")
        val fout = assertFailsWith<PluginActionException> { service.taakGestart(mapOf("actieId" to actie.id.toString()), "proces-2") }

        assertEquals("ACTIE_HEEFT_AL_EEN_TAAK", fout.errorCode)
        assertEquals("proces-1", acties.rows[actie.id]!!.taakProcessInstanceId)
    }

    @Test
    fun `gereed completes the actie with the resultaat, once, and only from its own process`() {
        val actie = opgeslagen().also { it.taakProcessInstanceId = "proces-1" }

        val ander = assertFailsWith<PluginActionException> { service.gereed(mapOf("actieId" to actie.id.toString()), "proces-2") }
        assertEquals("ACTIE_HOORT_NIET_BIJ_PROCES", ander.errorCode)

        service.gereed(mapOf("actieId" to actie.id.toString(), "resultaat" to " Gebeld, start maandag "), "proces-1")
        service.gereed(mapOf("actieId" to actie.id.toString(), "resultaat" to "later"), "proces-1")

        val afgerond = acties.rows[actie.id]!!
        assertEquals(ActionStatus.COMPLETED, afgerond.status)
        assertEquals("Gebeld, start maandag", afgerond.result)
        assertEquals(LocalDate.now(), afgerond.completedDate)
    }

    @Test
    fun `gereed follows the task's uitkomst, uitgevoerd completes the actie and vervallen cancels it`() {
        val uitgevoerd = opgeslagen().also { it.taakProcessInstanceId = "proces-1" }
        val vervallen = opgeslagen("Cursus zoeken").also { it.taakProcessInstanceId = "proces-2" }

        service.gereed(mapOf("actieId" to uitgevoerd.id.toString(), "uitkomst" to "uitgevoerd", "resultaat" to "Gebeld"), "proces-1")
        service.gereed(mapOf("actieId" to vervallen.id.toString(), "uitkomst" to "Vervallen", "resultaat" to "Doel afgebroken"), "proces-2")
        service.gereed(mapOf("actieId" to vervallen.id.toString(), "uitkomst" to "uitgevoerd"), "proces-2")

        assertEquals(ActionStatus.COMPLETED, acties.rows[uitgevoerd.id]!!.status)
        assertEquals(ActionStatus.CANCELLED, acties.rows[vervallen.id]!!.status, "an ended actie stays as it is")
        assertEquals("Doel afgebroken", acties.rows[vervallen.id]!!.result)
    }

    @Test
    fun `gereed refuses an unknown uitkomst`() {
        val actie = opgeslagen().also { it.taakProcessInstanceId = "proces-1" }

        val fout = assertFailsWith<PluginActionException> {
            service.gereed(mapOf("actieId" to actie.id.toString(), "uitkomst" to "half"), "proces-1")
        }

        assertEquals("UITKOMST_ONGELDIG", fout.errorCode)
        assertEquals(ActionStatus.PLANNED, acties.rows[actie.id]!!.status)
    }

    @Test
    fun `callbacks for an unknown actie or without actieId are refused`() {
        assertEquals("ACTIE_ONBEKEND", assertFailsWith<PluginActionException> {
            service.gereed(mapOf("actieId" to UUID.randomUUID().toString()), "proces-1")
        }.errorCode)
        assertEquals("ACTIEID_ONTBREEKT", assertFailsWith<PluginActionException> { service.taakGestart(emptyMap(), "proces-1") }.errorCode)
        assertEquals("GEEN_PROCES", assertFailsWith<PluginActionException> { service.taakGestart(mapOf("actieId" to "x"), null) }.errorCode)
        assertNull(acties.rows.values.firstOrNull())
    }
}
