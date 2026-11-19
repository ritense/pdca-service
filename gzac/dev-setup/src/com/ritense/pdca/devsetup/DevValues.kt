package com.ritense.pdca.devsetup

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.File

/**
 * The values the dev setup puts into GZAC (`gzac/dev-setup/dev-values.json`).
 * String values may hold `${NAME:default}` placeholders, resolved from the
 * environment, so another local setup overrides them without editing the file.
 */
data class DevValues(
    val pluginHost: PluginHostValues,
    val app: AppValues,
    val caseDefinitions: CaseDefinitionValues,
    val verzoek: VerzoekValues,
) {
    companion object {
        const val PATH = "gzac/dev-setup/dev-values.json"
        private val PLACEHOLDER = Regex("""\$\{([A-Za-z0-9_.]+)(?::([^}]*))?}""")

        fun load(file: File, environment: Map<String, String>): DevValues =
            Json.mapper.readValue(resolve(file.readText(), environment))

        /** Replaces every placeholder with its environment value or default (JSON-escaped). */
        fun resolve(text: String, environment: Map<String, String>): String =
            PLACEHOLDER.replace(text) { match ->
                val name = match.groupValues[1]
                val value = environment[name]
                    ?: match.groups[2]?.value
                    ?: throw SetupException("$name is not set and has no default in $PATH")
                Json.mapper.writeValueAsString(value).removeSurrounding("\"")
            }
    }
}

data class PluginHostValues(val baseUrl: String, val configurations: List<RegisterConfiguration>)

data class RegisterConfiguration(
    val pluginId: String,
    val title: String,
    val properties: ObjectNode,
    val grantedCapabilities: List<String>,
)

data class AppValues(
    val name: String,
    val baseUrl: String,
    val secret: String,
    val gzacCallbackBaseUrl: String,
    val frontendOrigins: List<String>,
    val configuration: AppConfiguration,
)

data class AppConfiguration(
    val title: String,
    val pluginId: String,
    val pluginVersion: String,
    val properties: ObjectNode,
    val grantedCapabilities: List<String>,
    val grantedEndpoints: List<GrantedEndpoint>,
    val contractBindings: List<ContractBinding>,
)

data class GrantedEndpoint(val method: String, val pattern: String)

/** Binds [contract] to the register configuration of [pluginId] on the plugin host. */
data class ContractBinding(val contract: String, val pluginId: String)

data class CaseDefinitionValues(val placeholderConfigurationId: String, val zips: List<String>)

data class VerzoekValues(
    val notificatiesApiCallbackUrl: String,
    val objecttypenApi: ObjecttypenApiValues,
    val objectManagement: ObjectManagementValues,
    val plugin: VerzoekPluginValues,
    val zaakTypeLink: ZaakTypeLinkValues,
)

data class ObjecttypenApiValues(val id: String, val title: String, val url: String)

data class ObjectManagementValues(
    val id: String,
    val title: String,
    val objecttypeId: String,
    val objecttypeVersion: Int,
    val showInDataMenu: Boolean,
)

data class VerzoekPluginValues(
    val id: String,
    val title: String,
    val processToStart: String,
    val rsin: String,
    val verzoekProperties: List<VerzoekType>,
)

data class VerzoekType(
    val type: String,
    val caseDefinitionKey: String,
    val processDefinitionKey: String,
    val initiatorRoltypeUuid: String,
    val initiatorRolDescription: String,
    val copyStrategy: String,
)

data class ZaakTypeLinkValues(
    val caseDefinitionKey: String,
    val zaaktypeUuid: String,
    val createWithDossier: Boolean,
    val rsin: String,
)

object Json {
    val mapper: ObjectMapper = jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
}
