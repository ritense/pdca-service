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

package com.ritense.pdca.service

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.ritense.pdca.domain.PhaseConfig
import com.ritense.pdca.domain.VoortgangStatusOptie
import com.ritense.pdca.repository.PhaseConfigRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDateTime
import java.util.UUID

@Service
@Transactional
class PhaseConfigService(
    private val phaseConfigRepository: PhaseConfigRepository,
    private val objectMapper: ObjectMapper
) {

    fun getById(id: UUID): PhaseConfig {
        return phaseConfigRepository.findById(id)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "PhaseConfig not found with id: $id") }
    }

    fun getByCaseDefinitionKey(caseDefinitionKey: String): PhaseConfig {
        return phaseConfigRepository.findByCaseDefinitionKey(caseDefinitionKey)
            ?: throw ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "PhaseConfig not found for caseDefinitionKey: $caseDefinitionKey"
            )
    }

    fun findByCaseDefinitionKey(caseDefinitionKey: String): PhaseConfig? {
        return phaseConfigRepository.findByCaseDefinitionKey(caseDefinitionKey)
    }

    fun create(phaseConfig: PhaseConfig): PhaseConfig {
        phaseConfig.voortgangStatussen = normalizeVoortgangStatussen(phaseConfig.voortgangStatussen)
        return phaseConfigRepository.save(phaseConfig)
    }

    fun getAll(): List<PhaseConfig> {
        return phaseConfigRepository.findAll()
    }

    fun delete(id: UUID) {
        val existing = getById(id)
        phaseConfigRepository.delete(existing)
    }

    fun deleteByCaseDefinitionKey(caseDefinitionKey: String) {
        val existing = getByCaseDefinitionKey(caseDefinitionKey)
        phaseConfigRepository.delete(existing)
    }

    fun updateByCaseDefinitionKey(caseDefinitionKey: String, updated: PhaseConfig): PhaseConfig {
        val existing = getByCaseDefinitionKey(caseDefinitionKey)
        existing.evaluationTypes = updated.evaluationTypes
        existing.planStatussen = updated.planStatussen
        existing.voortgangStatussen = normalizeVoortgangStatussen(updated.voortgangStatussen)
        existing.positieTypen = updated.positieTypen
        existing.subdoelMapping = updated.subdoelMapping
        existing.prefillMapping = updated.prefillMapping
        existing.planCaseDefinitionKey = updated.planCaseDefinitionKey
        existing.updatedAt = LocalDateTime.now()
        return phaseConfigRepository.save(existing)
    }

    fun getEvaluationTypes(caseDefinitionKey: String): List<String> {
        val config = getByCaseDefinitionKey(caseDefinitionKey)
        return parseJsonList(config.evaluationTypes)
    }

    /** Configured plan display statuses, or null when unconfigured (any value allowed). */
    fun getPlanStatussen(caseDefinitionKey: String): List<String>? {
        val config = phaseConfigRepository.findByCaseDefinitionKey(caseDefinitionKey) ?: return null
        val statussen = config.planStatussen?.takeIf { it.isNotBlank() } ?: return null
        return parseJsonList(statussen).takeIf { it.isNotEmpty() }
    }

    /**
     * The voortgangsstatussen of a plan of this case type, in display order:
     * the configured list, or [VoortgangStatusOptie.STANDAARD] when the case
     * type (or the plan) configures none.
     */
    fun getVoortgangStatussen(caseDefinitionKey: String?): List<VoortgangStatusOptie> {
        val raw = caseDefinitionKey?.let { phaseConfigRepository.findByCaseDefinitionKey(it) }
            ?.voortgangStatussen?.takeIf { it.isNotBlank() }
            ?: return VoortgangStatusOptie.STANDAARD
        return parseVoortgangStatussen(raw).takeIf { it.isNotEmpty() } ?: VoortgangStatusOptie.STANDAARD
    }

    /**
     * Validates a configured list and gives new statuses (no code yet) a
     * stable code derived from their name. Names are required, unique and
     * may not be a lifecycle status of the register (Gepland, Afgerond, ...).
     * An empty list is stored as null: the standard list applies.
     */
    private fun normalizeVoortgangStatussen(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val opties = try {
            parseVoortgangStatussen(raw)
        } catch (e: Exception) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Voortgangsstatussen zijn ongeldig: ${e.message}")
        }
        if (opties.isEmpty()) return null
        val namen = mutableSetOf<String>()
        val codes = mutableSetOf<String>()
        val genormaliseerd = opties.map { optie ->
            val naam = optie.naam.trim()
            val sleutel = naam.lowercase()
            when {
                naam.isEmpty() -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Elke voortgangsstatus heeft een naam nodig")
                sleutel in VoortgangStatusOptie.GERESERVEERDE_NAMEN -> throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "'$naam' is al een status van het doel zelf en kan geen voortgangsstatus zijn"
                )
                !namen.add(sleutel) -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Voortgangsstatus '$naam' staat er dubbel in")
            }
            val code = optie.code.trim().ifEmpty { vrijeCode(naam, codes + opties.map { it.code }) }
            if (!VoortgangStatusOptie.CODE_PATROON.matches(code) || !codes.add(code)) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Voortgangsstatus '$naam' heeft een ongeldige code '$code'")
            }
            optie.copy(code = code, naam = naam)
        }
        return objectMapper.writeValueAsString(genormaliseerd)
    }

    /** "Wacht op inwoner" -> WACHT_OP_INWONER, suffixed when that code is taken. */
    private fun vrijeCode(naam: String, bezet: Set<String>): String {
        val basis = naam.uppercase()
            .replace(Regex("[^A-Z0-9]+"), "_")
            .trim('_')
            .take(60)
            .ifEmpty { "STATUS" }
        return generateSequence(1) { it + 1 }
            .map { if (it == 1) basis else "${basis}_$it" }
            .first { it !in bezet }
    }

    /** Configured positietypen for the domein, or null when unconfigured (any value allowed). */
    fun getPositieTypen(caseDefinitionKey: String): List<String>? {
        val config = phaseConfigRepository.findByCaseDefinitionKey(caseDefinitionKey) ?: return null
        val posities = config.positieTypen?.takeIf { it.isNotBlank() } ?: return null
        return parseJsonList(posities).takeIf { it.isNotEmpty() }
    }

    /**
     * Subdoeltypen allowed under a hoofddoeltype, by name. Null means
     * unscoped: no mapping configured, or no entry for this hoofddoel — all
     * subdoeltypen stay available.
     */
    fun getSubdoelenVoorHoofddoel(caseDefinitionKey: String, hoofddoel: String): List<String>? {
        val config = phaseConfigRepository.findByCaseDefinitionKey(caseDefinitionKey) ?: return null
        val mapping = config.subdoelMapping?.takeIf { it.isNotBlank() } ?: return null
        return parseJsonMap(mapping)[hoofddoel]?.takeIf { it.isNotEmpty() }
    }

    private fun parseJsonList(json: String): List<String> =
        objectMapper.readValue(json, object : TypeReference<List<String>>() {})

    private fun parseVoortgangStatussen(json: String): List<VoortgangStatusOptie> =
        objectMapper.readValue(json, object : TypeReference<List<VoortgangStatusOptie>>() {})

    private fun parseJsonMap(json: String): Map<String, List<String>> =
        objectMapper.readValue(json, object : TypeReference<Map<String, List<String>>>() {})
}
