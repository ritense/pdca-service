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

package com.ritense.pdca.registers.openklant

import com.fasterxml.jackson.databind.ObjectMapper
import com.ritense.pdca.config.PdcaProperties
import com.ritense.pdca.registers.Register
import com.ritense.pdca.registers.RegisterErrors
import com.ritense.pdca.registers.RegisterGateway
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.util.UUID

/** Open Klant (Klantinteracties API v1) for the backend's services; see [RegisterGateway]. */
@Component
class OpenKlantGateway(
    openKlantRestClient: RestClient,
    objectMapper: ObjectMapper,
    properties: PdcaProperties
) : RegisterGateway(Register.OPENKLANT, openKlantRestClient, objectMapper, properties.registers.maxPages) {

    /** Creates a klantcontact with its onderwerpobject (and betrokkene) in one request; returns the klantcontact. */
    fun maakKlantcontact(request: MaakKlantcontact): Klantcontact =
        post(uri("maak-klantcontact"), request, MaakKlantcontactResult::class.java, "nieuw contactmoment").klantcontact
            ?: throw RegisterErrors.unreadable(
                register, "nieuw contactmoment", IllegalStateException("no klantcontact in the answer")
            )

    /** The klantcontact with the onderwerpobjecten it is about; null when Open Klant does not know it. */
    fun findKlantcontact(uuid: UUID): Klantcontact? = find(
        uri("klantcontacten", uuid, query = mapOf("expand" to "gingOverOnderwerpobjecten")),
        Klantcontact::class.java, "contactmoment $uuid"
    )

    /** The klantcontacten about one object (server-side filter on the onderwerpobject). */
    fun listKlantcontactenOver(identificator: OnderwerpobjectIdentificator): List<Klantcontact> = list(
        uri(
            "klantcontacten",
            query = mapOf(
                "onderwerpobject__onderwerpobjectidentificatorObjectId" to identificator.objectId,
                "onderwerpobject__onderwerpobjectidentificatorCodeObjecttype" to identificator.codeObjecttype,
                "onderwerpobject__onderwerpobjectidentificatorCodeRegister" to identificator.codeRegister
            )
        ),
        Klantcontact::class.java, "contactmomenten van ${identificator.codeObjecttype} ${identificator.objectId}"
    )

    fun updateKlantcontact(uuid: UUID, update: KlantcontactUpdate) =
        patch(uri("klantcontacten", uuid), update, "contactmoment $uuid")

    /** The partij with this bsn (server-side filter on its identificator), or null. */
    fun findPartijByBsn(bsn: String): Partij? = firstPage(
        uri(
            "partijen",
            query = mapOf(
                "partijIdentificator__codeSoortObjectId" to "bsn",
                "partijIdentificator__objectId" to bsn
            )
        ),
        Partij::class.java, "partij"
    ).firstOrNull()

    fun createPartij(partij: NewPersoonPartij): Partij = post(uri("partijen"), partij, Partij::class.java, "nieuwe partij")
}
