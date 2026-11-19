package com.ritense.pdca.plugin

import com.ritense.pdca.service.InstrumentActionService
import com.ritense.pdca.service.PluginActionException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * Execution of the manifest actions (URL-app contract): GZAC calls this
 * endpoint when an external-plugin process link on a service task fires.
 * Body: {configurationId, processInstanceId, documentId, activityId,
 * properties}; the properties are already resolved by GZAC (doc: and pv:
 * references are real values by then).
 *
 * Response: 200 {"status": "completed"} on success; on failure
 * {"status": "error", errorCode, errorMessage} with 422 for a business
 * refusal (retrying does not help) and 503 for a temporary outage of a
 * register or GZAC (retrying later does). GZAC turns every non-2xx answer
 * into a failed job, retried by the job executor and an incident once the
 * retries are used up; there is no BPMN-error channel for external plugin
 * actions (ARCHITECTURE §7.4). Like the host API, every call is signed by
 * GZAC with the shared host secret (`HostSignatureFilter`).
 *
 * Only the app's **active** configuration ([GzacAccess]) may run an action:
 * a call for another configurationId is refused (422, CONFIGURATIE_NIET_ACTIEF),
 * and while no configuration is active the call fails temporarily (503).
 */
@RestController
class PluginActionController(
    private val instrumentActionService: InstrumentActionService,
    private val gzacAccess: GzacAccess
) {

    private val logger = LoggerFactory.getLogger(PluginActionController::class.java)

    @PostMapping("/plugins/{pluginId}/{version}/actions/{actionKey}")
    fun invokeAction(
        @PathVariable(name = "pluginId") pluginId: String,
        @PathVariable(name = "version") version: String,
        @PathVariable(name = "actionKey") actionKey: String,
        @RequestBody body: Map<String, Any?>
    ): ResponseEntity<Map<String, Any?>> {
        if (pluginId != PluginHostController.PLUGIN_ID) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(mapOf("error" to "Plugin not found: $pluginId@$version"))
        }

        @Suppress("UNCHECKED_CAST")
        val properties = body["properties"] as? Map<String, Any?> ?: emptyMap()
        val documentId = body["documentId"] as? String

        val action: () -> Map<String, Any?> = when (actionKey) {
            // The instrument dossier manages the instrument in Open Plan with
            // these two actions. The create response returns the
            // instrumentUuid as declared output (the result channel of the
            // URL-app contract); the dossier's result mapping writes it into
            // the dossier document.
            "aanmaak-instrument" -> {
                { mapOf("result" to instrumentActionService.handleAanmaakInstrument(properties, documentId)) }
            }

            "update-instrument" -> {
                {
                    instrumentActionService.handleUpdateInstrument(properties)
                    emptyMap()
                }
            }

            else -> return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                mapOf("error" to "Action '$actionKey' not found on plugin $pluginId@$version")
            )
        }

        activeConfigurationRefusal(body["configurationId"]?.toString())?.let { return failure(actionKey, it) }
        logger.info(
            "Plugin action '{}' invoked (processInstance={}, document={}, activity={})",
            actionKey, body["processInstanceId"], documentId, body["activityId"]
        )
        return execute(actionKey, action)
    }

    /** Why a call for [configurationId] may not run, or null when it is the active configuration. */
    private fun activeConfigurationRefusal(configurationId: String?): PluginActionException? =
        when (val resolution = gzacAccess.resolve()) {
            is GzacAccess.Resolution.Unavailable ->
                PluginActionException("TIJDELIJK_NIET_BESCHIKBAAR", resolution.reason, retryable = true)
            is GzacAccess.Resolution.Available ->
                if (configurationId == resolution.access.configuration.configId) null
                else PluginActionException(
                    "CONFIGURATIE_NIET_ACTIEF",
                    "De PDCA-configuratie ${configurationId ?: "(geen)"} is niet de actieve configuratie van de app"
                )
        }

    /** 200 completed (plus any extra response fields), or 422/503 with an error code. */
    private fun execute(
        actionKey: String,
        action: () -> Map<String, Any?>
    ): ResponseEntity<Map<String, Any?>> = try {
        ResponseEntity.ok(mapOf<String, Any?>("status" to "completed") + action())
    } catch (e: PluginActionException) {
        failure(actionKey, e)
    } catch (e: ResponseStatusException) {
        // A service-level refusal (4xx) or outage (5xx) outside the action's own checks.
        failure(
            actionKey,
            PluginActionException(
                if (e.statusCode.is5xxServerError) "TIJDELIJK_NIET_BESCHIKBAAR" else "GEWEIGERD",
                e.reason ?: "Actie '$actionKey' mislukt",
                retryable = e.statusCode.is5xxServerError
            )
        )
    }

    private fun failure(actionKey: String, e: PluginActionException): ResponseEntity<Map<String, Any?>> {
        logger.warn("{} {} ({}): {}", actionKey, if (e.retryable) "failed temporarily" else "refused", e.errorCode, e.message)
        return ResponseEntity.status(if (e.retryable) HttpStatus.SERVICE_UNAVAILABLE else HttpStatus.UNPROCESSABLE_ENTITY).body(
            mapOf("status" to "error", "errorCode" to e.errorCode, "errorMessage" to e.message)
        )
    }
}
