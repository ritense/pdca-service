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
import com.ritense.pdca.plugin.GzacAccess
import com.ritense.pdca.plugin.PluginHostController
import com.ritense.pdca.registers.openplan.OpenPlanGateway
import com.ritense.pdca.repository.ActionRepository
import com.ritense.pdca.repository.DoelDetailsRepository
import com.ritense.pdca.repository.PlanDetailsRepository
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * An actie as a GZAC task (ARCHITECTURE §6.8): every actie added to a plan
 * gets the building block [BOUWBLOK] on the plan dossier, whose one user
 * task carries the actie's title, description and deadline; completing that
 * task ends the actie, as uitgevoerd or vervallen.
 *
 * Starting ([start]) runs after the actie is saved and outside any
 * transaction. The plan case type's building-block link must map the PDCA
 * plugin to the active configuration (a case import leaves the mapping
 * empty), so the link is set first when it does not. The start form is then
 * submitted on the plan dossier; the building block's first service task
 * calls back synchronously ([taakGestart]) and records its process on the
 * actie. A start GZAC refuses removes the actie again, so no actie exists
 * without its task; a start whose answer is lost keeps the actie when the
 * callback recorded the task, and removes it otherwise.
 *
 * The process reports the outcome with [gereed]; both callbacks are safe to
 * repeat and taken only from the process that carries the actie.
 */
@Service
class ActieTaakService(
    private val actionRepository: ActionRepository,
    private val doelDetailsRepository: DoelDetailsRepository,
    private val planDetailsRepository: PlanDetailsRepository,
    private val openPlanGateway: OpenPlanGateway,
    private val gzacClient: GzacClient,
    private val gzacAccess: GzacAccess
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Starts the task of the saved actie [actie] and returns the actie as it is then; see the class comment. */
    fun start(actie: Action): Action {
        try {
            val (dossierId, caseKey, planUuid) = plandossier(actie.doelUuid)
            val caseVersionTag = gzacClient.caseDefinitionVersionTag(caseKey)
            val link = gzacClient.bouwblokLink(caseKey, caseVersionTag, BOUWBLOK)
                ?: throw ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Dossiertype '$caseKey' ($caseVersionTag) kent het taakbouwblok '$BOUWBLOK' niet; " +
                        "importeer het bouwblok en de nieuwste versie van het dossiertype"
                )
            koppelConfiguratie(caseKey, caseVersionTag, link.versionTag)
            val formulier = gzacClient.startformulier(link.processDefinitionId, dossierId)
            gzacClient.startformulierIndienen(
                formulier, dossierId,
                // Every field is sent, empty ones too: the dossier document
                // keeps /bouwblokStart from the previous start.
                mapOf(
                    "bouwblokStart" to linkedMapOf(
                        "actieId" to actie.id.toString(),
                        "titel" to actie.title,
                        "omschrijving" to (actie.description ?: ""),
                        "deadline" to (actie.dueDate?.toString() ?: ""),
                        "planUuid" to planUuid.toString(),
                        "doelUuid" to actie.doelUuid.toString()
                    )
                )
            )
        } catch (e: RuntimeException) {
            val opgenomen = actionRepository.findById(actie.id).orElse(null)
            if (e !is GzacClient.StartGeweigerdException && opgenomen?.taakProcessInstanceId != null) {
                log.warn("Start of the task of actie {} answered with an error, but its process {} reported itself; kept", actie.id, opgenomen.taakProcessInstanceId)
                return opgenomen
            }
            actionRepository.deleteById(actie.id)
            log.warn("Task of actie {} not started; actie removed again: {}", actie.id, e.message)
            throw e
        }
        val gestart = actionRepository.findById(actie.id).orElse(actie)
        if (gestart.taakProcessInstanceId == null) {
            log.warn("Task of actie {} started, but its process did not report itself (does it call actie-taak-gestart first?)", actie.id)
        } else {
            log.info("Actie {} is task process {}", actie.id, gestart.taakProcessInstanceId)
        }
        return gestart
    }

    /** Plugin action actie-taak-gestart: records [processInstanceId] as the process carrying the actie. */
    fun taakGestart(properties: Map<String, Any?>, processInstanceId: String?) {
        val (actie, proces) = actieVanProces(properties, processInstanceId)
        if (actie.taakProcessInstanceId == proces) return
        if (actie.taakProcessInstanceId != null || actionRepository.recordTaak(actie.id, proces) == 0) {
            throw PluginActionException("ACTIE_HEEFT_AL_EEN_TAAK", "Actie ${actie.id} heeft al een taak in een ander proces")
        }
    }

    /**
     * Plugin action actie-gereed: the task is done, so the actie ends with
     * the task's uitkomst — uitgevoerd (COMPLETED, also when none is given)
     * or vervallen (CANCELLED) — and its resultaat. An actie that has ended
     * already stays as it is.
     */
    fun gereed(properties: Map<String, Any?>, processInstanceId: String?) {
        val (actie, proces) = actieVanProces(properties, processInstanceId)
        if (actie.taakProcessInstanceId != null && actie.taakProcessInstanceId != proces) {
            throw PluginActionException("ACTIE_HOORT_NIET_BIJ_PROCES", "Actie ${actie.id} is de taak van een ander proces")
        }
        val status = when (val uitkomst = (properties["uitkomst"] as? String)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }) {
            null, UITGEVOERD -> ActionStatus.COMPLETED
            VERVALLEN -> ActionStatus.CANCELLED
            else -> throw PluginActionException("UITKOMST_ONGELDIG", "Uitkomst '$uitkomst' is geen $UITGEVOERD of $VERVALLEN")
        }
        if (actie.status.isAfgesloten) return
        actie.taakProcessInstanceId = proces
        actie.status = status
        actie.completedDate = LocalDate.now()
        actie.result = (properties["resultaat"] as? String)?.trim()?.takeIf { it.isNotEmpty() }?.take(RESULTAAT_MAX) ?: actie.result
        actie.updatedAt = LocalDateTime.now()
        actionRepository.save(actie)
        log.info("Actie {} ended as {} by its task process {}", actie.id, status, proces)
    }

    private fun actieVanProces(properties: Map<String, Any?>, processInstanceId: String?): Pair<Action, String> {
        val proces = processInstanceId?.takeIf { it.isNotBlank() }
            ?: throw PluginActionException("GEEN_PROCES", "De aanroep noemt geen proces")
        val actieId = (properties["actieId"] as? String)?.let { runCatching { UUID.fromString(it.trim()) }.getOrNull() }
            ?: throw PluginActionException("ACTIEID_ONTBREEKT", "Eigenschap actieId ontbreekt of is geen UUID")
        val actie = actionRepository.findById(actieId).orElse(null)
            ?: throw PluginActionException("ACTIE_ONBEKEND", "Er is geen actie $actieId (meer) in het plan")
        return actie to proces
    }

    private data class Plandossier(val dossierId: String, val caseDefinitionKey: String, val planUuid: UUID)

    /** The plan dossier the task of an actie under [doelUuid] lands on; 409 when the plan has none. */
    private fun plandossier(doelUuid: UUID): Plandossier {
        val planUuid = doelDetailsRepository.findById(doelUuid).orElse(null)?.planUuid
            ?: openPlanGateway.findDoel(doelUuid)?.plannen?.firstOrNull()?.uuid
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Het doel van de actie hoort bij geen plan")
        val details = planDetailsRepository.findById(planUuid).orElse(null)
        val dossierId = details?.dossierId
        val caseKey = details?.caseDefinitionKey
        if (dossierId == null || caseKey == null) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Bij dit plan hoort geen plandossier; de taak van de actie kan nergens landen")
        }
        return Plandossier(dossierId, caseKey, planUuid)
    }

    /**
     * Makes the plan case type's link of the building block map the PDCA
     * plugin to the active configuration, keeping its other properties
     * (GZAC replaces the mappings as a whole).
     */
    private fun koppelConfiguratie(caseKey: String, caseVersionTag: String, bouwblokVersionTag: String) {
        val configurationId = gzacAccess.active().configuration.configId
        val eigenschappen = gzacClient.bouwblokLinkEigenschappen(caseKey, caseVersionTag, BOUWBLOK, bouwblokVersionTag)
        val mappings = (eigenschappen["pluginConfigurationMappings"] as? Map<*, *>).orEmpty()
        if (mappings[CONFIGURATIE_SLEUTEL]?.toString() == configurationId) return
        gzacClient.bouwblokLinkBijwerken(
            caseKey, caseVersionTag, BOUWBLOK, bouwblokVersionTag,
            mapOf(
                "inputMappings" to (eigenschappen["inputMappings"] ?: emptyList<Any>()),
                "outputMappings" to (eigenschappen["outputMappings"] ?: emptyList<Any>()),
                "pluginConfigurationMappings" to mappings.mapKeys { it.key.toString() } + (CONFIGURATIE_SLEUTEL to configurationId)
            )
        )
        log.info("Building-block link {} of case type {} {} now maps the PDCA plugin to configuration {}", BOUWBLOK, caseKey, caseVersionTag, configurationId)
    }

    companion object {
        /** The building block that carries an actie's task. */
        const val BOUWBLOK = "pdca-taak"

        /** The key under which a building-block link maps this plugin to a configuration. */
        val CONFIGURATIE_SLEUTEL = "external-plugin:${PluginHostController.PLUGIN_ID}@${PluginHostController.PLUGIN_VERSION}"

        /** The register limit the bundles also apply to texts. */
        private const val RESULTAAT_MAX = 1000

        /** The uitkomsten of the task form (pdca-taak-afronden). */
        private const val UITGEVOERD = "uitgevoerd"
        private const val VERVALLEN = "vervallen"
    }
}
