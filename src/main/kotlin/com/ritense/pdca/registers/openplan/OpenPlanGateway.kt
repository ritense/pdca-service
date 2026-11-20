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

package com.ritense.pdca.registers.openplan

import com.fasterxml.jackson.databind.ObjectMapper
import com.ritense.pdca.config.PdcaProperties
import com.ritense.pdca.registers.Register
import com.ritense.pdca.registers.RegisterGateway
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.util.UUID

/** Open Plan (Plannen API v0) for the backend's services; see [RegisterGateway]. */
@Component
class OpenPlanGateway(
    openPlanRestClient: RestClient,
    objectMapper: ObjectMapper,
    properties: PdcaProperties
) : RegisterGateway(Register.OPENPLAN, openPlanRestClient, objectMapper, properties.registers.maxPages) {

    // ---------------------------------------------------------------- plan

    fun getPlan(uuid: UUID): Plan = get(uri("plan", uuid), Plan::class.java, "plan $uuid")

    fun findPlan(uuid: UUID): Plan? = find(uri("plan", uuid), Plan::class.java, "plan $uuid")

    fun createPlan(plan: NewPlan): Plan = post(uri("plan"), plan, Plan::class.java, "nieuw plan")

    fun updatePlanMedewerker(uuid: UUID, medewerker: String) =
        patch(uri("plan", uuid), PlanMedewerkerUpdate(medewerker), "plan $uuid")

    /** Closes ([PlanStatusUpdate.einde]) or reopens ([PlanStatusUpdate.heropend]) a plan. */
    fun updatePlanStatus(uuid: UUID, update: PlanStatusUpdate) = patch(uri("plan", uuid), update, "plan $uuid")

    /** The plans about one subject (server-side filter on `domeinregister`, the subject URN). */
    fun listPlannenOver(domeinregister: String): List<Plan> =
        list(uri("plan", query = mapOf("domeinregister" to domeinregister)), Plan::class.java, "plannen van $domeinregister")

    fun listPlantypen(): List<PlanType> = list(uri("plantype"), PlanType::class.java, "plantypen")

    /** Any overkoepelend plan: every plan the app creates hangs under one, which one does not matter. */
    fun firstOverkoepelendPlan(): OverkoepelendPlan? =
        firstPage(uri("overkoepelendplan"), OverkoepelendPlan::class.java, "overkoepelende plannen").firstOrNull()

    // ---------------------------------------------------------------- doel

    fun getDoel(uuid: UUID): Doel = get(uri("doel", uuid), Doel::class.java, "doel $uuid")

    fun findDoel(uuid: UUID): Doel? = find(uri("doel", uuid), Doel::class.java, "doel $uuid")

    fun createDoel(doel: NewDoel): Doel = post(uri("doel"), doel, Doel::class.java, "nieuw doel")

    /** The doelen of a plan (server-side filter on the plan). */
    fun listDoelen(planUuid: UUID): List<Doel> =
        list(uri("doel", query = mapOf("plannen__uuid" to planUuid)), Doel::class.java, "doelen van plan $planUuid")

    fun updateDoelStatus(uuid: UUID, update: DoelStatusUpdate) = patch(uri("doel", uuid), update, "doel $uuid")

    fun updateDoelHoofddoel(uuid: UUID, hoofdDoel: UUID) =
        patch(uri("doel", uuid), DoelHoofddoelUpdate(hoofdDoel), "doel $uuid")

    /** False when the doel was already gone. */
    fun deleteDoel(uuid: UUID): Boolean = delete(uri("doel", uuid), "doel $uuid")

    fun listDoeltypen(): List<DoelType> = list(uri("doeltype"), DoelType::class.java, "doeltypen")

    // ---------------------------------------------------------- instrument

    fun createInstrument(instrument: NewInstrument): Instrument =
        post(uri("instrument"), instrument, Instrument::class.java, "nieuw instrument")

    fun findInstrument(uuid: UUID): Instrument? = find(uri("instrument", uuid), Instrument::class.java, "instrument $uuid")

    /**
     * The instruments of a doel (server-side filter on the doel), optionally
     * narrowed to those whose `zaak` is [zaak] (server-side filter as well).
     */
    fun listInstrumenten(doelUuid: UUID, zaak: String? = null): List<Instrument> = list(
        uri("instrument", query = mapOf("doelen__uuid" to doelUuid, "zaak" to zaak)),
        Instrument::class.java, "instrumenten van doel $doelUuid"
    )

    /** The instruments under any of these doelen (server-side `doelen__uuid__in`). */
    fun listInstrumentenVanDoelen(doelUuids: Collection<UUID>): List<Instrument> =
        if (doelUuids.isEmpty()) emptyList()
        else list(
            uri("instrument", query = mapOf("doelen__uuid__in" to doelUuids.joinToString(","))),
            Instrument::class.java, "instrumenten van ${doelUuids.size} doelen"
        )

    fun closeInstrument(uuid: UUID, einde: InstrumentEinde) = patch(uri("instrument", uuid), einde, "instrument $uuid")

    /** False when the instrument was already gone. */
    fun deleteInstrument(uuid: UUID): Boolean = delete(uri("instrument", uuid), "instrument $uuid")

    fun listInstrumenttypen(): List<InstrumentType> =
        list(uri("instrumenttype"), InstrumentType::class.java, "instrumenttypen")

    // ------------------------------------------------------------- persoon

    /**
     * The persoon with this bsn. Open Plan has no bsn filter on its persoon
     * listing, so the pages are read until it turns up; past the page cap
     * this fails rather than creating a duplicate persoon.
     */
    fun findPersoonByBsn(bsn: String): Persoon? =
        findInList(uri("persoon"), Persoon::class.java, "personen") { it.bsn == bsn }

    fun createPersoon(persoon: NewPersoon): Persoon = post(uri("persoon"), persoon, Persoon::class.java, "nieuwe persoon")
}
