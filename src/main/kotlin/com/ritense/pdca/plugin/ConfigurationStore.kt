package com.ritense.pdca.plugin

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.ritense.pdca.domain.StoredPluginConfiguration
import com.ritense.pdca.repository.StoredPluginConfigurationRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

data class PluginConfiguration(
    val configId: String,
    /** The administrator-facing name of the configuration in GZAC (pushed along; null for older pushes). */
    val title: String? = null,
    val properties: Map<String, Any>,
    val serviceToken: String,
    val gzacBaseUrl: String,
    val eventSubscriptions: List<String> = emptyList(),
    /** Contracts GZAC bound for this configuration (`name@major`); empty when none is bound. */
    val grantedContracts: List<String> = emptyList()
)

/**
 * Plugin configurations pushed by GZAC, persisted in the PDCA database so the
 * serviceToken + gzacBaseUrl survive restarts (see [com.ritense.pdca.service.GzacClient]).
 */
@Component
@Transactional
class ConfigurationStore(
    private val repository: StoredPluginConfigurationRepository,
    private val objectMapper: ObjectMapper
) {

    private val logger = LoggerFactory.getLogger(ConfigurationStore::class.java)

    fun store(configId: String, configuration: PluginConfiguration) {
        repository.save(
            StoredPluginConfiguration(
                configId = configId,
                title = configuration.title?.takeIf { it.isNotBlank() },
                properties = objectMapper.writeValueAsString(configuration.properties),
                serviceToken = configuration.serviceToken.ifBlank { null },
                gzacBaseUrl = configuration.gzacBaseUrl.ifBlank { null },
                eventSubscriptions = objectMapper.writeValueAsString(configuration.eventSubscriptions),
                grantedContracts = objectMapper.writeValueAsString(configuration.grantedContracts),
                updatedAt = LocalDateTime.now()
            )
        )
        logger.info("Stored plugin configuration for configId={}", configId)
    }

    fun get(configId: String): PluginConfiguration? =
        repository.findById(configId).orElse(null)?.let { toConfiguration(it) }

    fun remove(configId: String): Boolean {
        val exists = repository.existsById(configId)
        if (exists) {
            repository.deleteById(configId)
            logger.info("Removed plugin configuration for configId={}", configId)
        } else {
            logger.warn("Attempted to remove non-existent configuration for configId={}", configId)
        }
        return exists
    }

    fun getAll(): Map<String, PluginConfiguration> =
        repository.findAll().associate { it.configId to toConfiguration(it) }

    /** The most recently pushed configuration that can authenticate against GZAC and matches [filter]. */
    fun latestWithGzacAccess(filter: (PluginConfiguration) -> Boolean = { true }): PluginConfiguration? =
        repository.findAll()
            .filter { !it.serviceToken.isNullOrBlank() && !it.gzacBaseUrl.isNullOrBlank() }
            .sortedByDescending { it.updatedAt }
            .map { toConfiguration(it) }
            .firstOrNull(filter)

    private fun toConfiguration(entity: StoredPluginConfiguration) = PluginConfiguration(
        configId = entity.configId,
        title = entity.title,
        properties = objectMapper.readValue(entity.properties, object : TypeReference<Map<String, Any>>() {}),
        serviceToken = entity.serviceToken ?: "",
        gzacBaseUrl = entity.gzacBaseUrl ?: "",
        eventSubscriptions = objectMapper.readValue(entity.eventSubscriptions, object : TypeReference<List<String>>() {}),
        grantedContracts = objectMapper.readValue(entity.grantedContracts, object : TypeReference<List<String>>() {})
    )
}
