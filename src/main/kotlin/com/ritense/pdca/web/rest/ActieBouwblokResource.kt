/*
 * Copyright 2015-2024 Ritense BV, the Netherlands.
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

package com.ritense.pdca.web.rest

import com.ritense.pdca.domain.ActieBouwblokKoppeling
import com.ritense.pdca.domain.BouwblokSoort
import com.ritense.pdca.domain.Uitvoeringsvorm
import com.ritense.pdca.service.ActieBouwblokService
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.CrossOrigin
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class ActieBouwblokKoppelingRequest(
    @field:NotBlank
    val caseDefinitionKey: String,
    val soort: BouwblokSoort = BouwblokSoort.ACTIE,
    val uitvoeringsvorm: Uitvoeringsvorm = Uitvoeringsvorm.BOUWBLOK,
    @field:NotBlank
    val naam: String,
    val omschrijving: String? = null,
    /** BOUWBLOK only. */
    val buildingBlockKey: String? = null,
    val buildingBlockVersion: String? = null,
    /** BOUWBLOK only. */
    val processDefinitionKey: String? = null,
    /** DOSSIER only. */
    val productCaseDefinitionKey: String? = null,
    @field:NotBlank
    val pluginConfigId: String,
    /** JSON array of doeltype uuids (string); empty/null = all doelen. */
    val doeltypeUuids: String? = null
) {
    fun toKoppeling() = ActieBouwblokKoppeling(
        caseDefinitionKey = caseDefinitionKey,
        soort = soort,
        uitvoeringsvorm = uitvoeringsvorm,
        naam = naam,
        omschrijving = omschrijving,
        buildingBlockKey = buildingBlockKey,
        buildingBlockVersion = buildingBlockVersion,
        processDefinitionKey = processDefinitionKey,
        productCaseDefinitionKey = productCaseDefinitionKey,
        pluginConfigId = pluginConfigId,
        doeltypeUuids = doeltypeUuids
    )
}

/**
 * Administration of the bouwblok koppelingen (PDCA Beheer): which GZAC
 * building blocks are available per plan case type — as actie or product —
 * under which doeltypes and with which plugin configuration.
 */
@RestController
@CrossOrigin
@RequestMapping("/api/v1/admin/actie-bouwblokken", produces = [MediaType.APPLICATION_JSON_VALUE])
class ActieBouwblokAdminResource(
    private val actieBouwblokService: ActieBouwblokService
) {

    @GetMapping
    fun list(
        @RequestParam(name = "caseDefinitionKey", required = false) caseDefinitionKey: String?
    ): List<ActieBouwblokKoppeling> = actieBouwblokService.getKoppelingen(caseDefinitionKey)

    @PostMapping
    fun create(@Valid @RequestBody request: ActieBouwblokKoppelingRequest): ResponseEntity<ActieBouwblokKoppeling> =
        ResponseEntity.status(HttpStatus.CREATED).body(actieBouwblokService.create(request.toKoppeling()))

    @PutMapping("/{id}")
    fun update(
        @PathVariable(name = "id") id: UUID,
        @Valid @RequestBody request: ActieBouwblokKoppelingRequest
    ): ActieBouwblokKoppeling = actieBouwblokService.update(id, request.toKoppeling())

    @DeleteMapping("/{id}")
    fun delete(@PathVariable(name = "id") id: UUID): ResponseEntity<Unit> {
        actieBouwblokService.delete(id)
        return ResponseEntity.noContent().build()
    }

    /** Imported building block processes from GZAC (versionTag "BB:<key>:<version>") for the dropdown. */
    @GetMapping("/bouwblok-processen")
    fun bouwblokProcesses(): List<ActieBouwblokService.BouwblokProcess> = actieBouwblokService.getBouwblokProcesses()

    /** Case types whose document schema carries the product-dossier contract, for the DOSSIER dropdown. */
    @GetMapping("/product-dossiertypes")
    fun productDossiertypes(): List<String> = actieBouwblokService.getProductDossiertypes()

    /** The plugin configurations pushed by GZAC, for the configuration dropdown. */
    @GetMapping("/plugin-configuraties")
    fun pluginConfigurations(): List<Map<String, String>> = actieBouwblokService.getPluginConfigurations()
}

/**
 * Plan-page side: which bouwblok koppelingen exist for this case type, and
 * start one under a doel (for an ACTIE koppeling this creates the action and
 * starts the building block process on the plan dossier; a PRODUCT koppeling
 * only starts the request process).
 */
@RestController
@RequestMapping("/api/v1/pdca/actie-bouwblokken", produces = [MediaType.APPLICATION_JSON_VALUE])
class ActieBouwblokResource(
    private val actieBouwblokService: ActieBouwblokService
) {

    @GetMapping
    fun list(
        @RequestParam(name = "caseDefinitionKey") caseDefinitionKey: String
    ): List<ActieBouwblokKoppeling> = actieBouwblokService.getKoppelingen(caseDefinitionKey)

    data class StartActieRequest(val doelUuid: UUID)

    /** For an ACTIE koppeling the response carries the created action; a
     *  DOSSIER product carries the created aanvraagdossier (for the "open
     *  dossier" link on the plan page); a BOUWBLOK product carries neither. */
    @PostMapping("/{id}/start")
    fun start(
        @PathVariable(name = "id") id: UUID,
        @RequestBody request: StartActieRequest
    ): ResponseEntity<ActieBouwblokService.StartResult> =
        ResponseEntity.status(HttpStatus.CREATED).body(actieBouwblokService.start(id, request.doelUuid))
}
