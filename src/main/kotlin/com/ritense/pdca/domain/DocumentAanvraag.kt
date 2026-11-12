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

package com.ritense.pdca.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime
import java.util.UUID

/**
 * A document PDCA has GZAC make for a plan: PDCA starts the configured
 * document process on the plan dossier ([dossierId]), the process fetches
 * the [gegevens] snapshot, has the document generated and hands it back as
 * [inhoud]. [bronUuid] is what the document is about, for an
 * evaluatieverslag the completed evaluation session.
 *
 * [gegevens] is the JSON data snapshot taken when the document was
 * requested, so the document shows the state of that moment; it is cleared
 * once the request ends. Not a data class: [inhoud] is a byte array and
 * must neither take part in equality nor be printed.
 */
@Entity
@Table(name = "document_aanvraag")
class DocumentAanvraag(
    @Id
    @Column(name = "id")
    val id: UUID = UUID.randomUUID(),

    @Column(name = "plan_uuid", nullable = false)
    val planUuid: UUID,

    /** The GZAC plan dossier the document process runs on. */
    @Column(name = "dossier_id", nullable = false)
    val dossierId: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "soort", nullable = false)
    val soort: DocumentSoort,

    @Column(name = "bron_uuid", nullable = false)
    val bronUuid: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: DocumentAanvraagStatus = DocumentAanvraagStatus.AANGEVRAAGD,

    /**
     * The GZAC process of the request: recorded from the start's answer, or
     * by the process claiming it first; null while neither happened.
     */
    @Column(name = "process_instance_id")
    var processInstanceId: String? = null,

    /** JSON data snapshot for the document template; null once the request ended. */
    @Column(name = "gegevens", columnDefinition = "TEXT")
    var gegevens: String? = null,

    @Column(name = "bestandsnaam")
    var bestandsnaam: String? = null,

    @Column(name = "mediatype")
    var mediatype: String? = null,

    /** The finished document; only for [DocumentAanvraagStatus.GEREED]. */
    @Column(name = "inhoud")
    var inhoud: ByteArray? = null,

    /** Why the document could not be made; only for [DocumentAanvraagStatus.MISLUKT]. */
    @Column(name = "foutmelding", length = 1000)
    var foutmelding: String? = null,

    /** GZAC login of the user who requested it (for an evaluatieverslag: who completed the evaluation). */
    @Column(name = "aangevraagd_door")
    val aangevraagdDoor: String? = null,

    @Column(name = "aangevraagd_op", nullable = false, updatable = false)
    val aangevraagdOp: LocalDateTime = LocalDateTime.now(),

    @Column(name = "bijgewerkt_op", nullable = false)
    var bijgewerktOp: LocalDateTime = LocalDateTime.now()
)

enum class DocumentSoort { EVALUATIEVERSLAG }

/**
 * AANGEVRAAGD until the document process claims the request, BEZIG while it
 * makes the document, then GEREED (with [DocumentAanvraag.inhoud]) or
 * MISLUKT (with [DocumentAanvraag.foutmelding]).
 */
enum class DocumentAanvraagStatus { AANGEVRAAGD, BEZIG, GEREED, MISLUKT }
