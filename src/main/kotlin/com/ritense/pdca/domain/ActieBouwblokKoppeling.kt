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

package com.ritense.pdca.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime
import java.util.UUID

/** ACTIE starts an action under a doel; PRODUCT starts a request process
 *  that manages the instrument in Open Plan itself (plugin actions
 *  aanmaak-instrument/update-instrument). */
enum class BouwblokSoort { ACTIE, PRODUCT }

/** How the process runs: as a GZAC building block inside the plan dossier,
 *  or (PRODUCT only) as a full GZAC case with its own dossier. */
enum class Uitvoeringsvorm { BOUWBLOK, DOSSIER }

/**
 * Link of a GZAC process to a plan case type (PDCA Beheer). Determines under
 * which doeltypes it is available on the plan page and with which PDCA
 * plugin configuration it reports back. Two uitvoeringsvormen:
 *
 *  - BOUWBLOK: a building block on the plan dossier; the configuration is
 *    maintained as pluginConfigurationMappings on the case type's
 *    building-block link and the start payload flows into the building
 *    block document through the link's input mappings;
 *  - DOSSIER (PRODUCT only): a full case of [productCaseDefinitionKey] —
 *    own dossier (optionally with a zaak via GZAC's zaaktype-link), own
 *    PBAC and documents. The start payload is the new dossier's document
 *    content; the app repairs the case's dangling pdca process links to the
 *    chosen configuration.
 *
 * All business logic — for PRODUCT including creating/updating the
 * instrument — belongs in the process itself.
 */
@Entity
@Table(name = "actie_bouwblok_koppeling")
data class ActieBouwblokKoppeling(
    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(name = "case_definition_key", nullable = false)
    val caseDefinitionKey: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var soort: BouwblokSoort = BouwblokSoort.ACTIE,

    /** Label on the plan page (and, for ACTIE, the title of the created action). */
    @Column(nullable = false)
    var naam: String,

    @Column(columnDefinition = "TEXT")
    var omschrijving: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var uitvoeringsvorm: Uitvoeringsvorm = Uitvoeringsvorm.BOUWBLOK,

    /** BOUWBLOK only. */
    @Column(name = "building_block_key")
    var buildingBlockKey: String? = null,

    @Column(name = "building_block_version")
    var buildingBlockVersion: String? = null,

    /** BOUWBLOK only: the building block's main process, started on the plan dossier. */
    @Column(name = "process_definition_key")
    var processDefinitionKey: String? = null,

    /** DOSSIER only: the product case type; a start creates a dossier of this type. */
    @Column(name = "product_case_definition_key")
    var productCaseDefinitionKey: String? = null,

    /** The PDCA plugin configuration (pushed by GZAC) mapped on the case type's building-block link. */
    @Column(name = "plugin_config_id", nullable = false)
    var pluginConfigId: String,

    /** JSON array of doeltype uuids; empty/null = available under every doel. */
    @Column(name = "doeltype_uuids", columnDefinition = "TEXT")
    var doeltypeUuids: String? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now()
)
