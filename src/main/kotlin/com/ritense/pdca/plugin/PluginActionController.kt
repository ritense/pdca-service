package com.ritense.pdca.plugin

import com.ritense.pdca.service.ActieBouwblokService
import com.ritense.pdca.service.InstrumentActionService
import com.ritense.pdca.service.PluginActionException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * Execution of the manifest actions (URL-app contract): GZAC calls this
 * endpoint when an external-plugin process link on a service task fires.
 * Body: {configurationId, processInstanceId, documentId, activityId,
 * properties}; the properties are already resolved by GZAC (pv: values are
 * real process-variable values by then).
 *
 * Response: 200 {"status": "completed"} on success; 422 {"status": "error",
 * errorCode, errorMessage} on a business error (catchable in BPMN).
 */
@RestController
class PluginActionController(
    private val actieBouwblokService: ActieBouwblokService,
    private val instrumentActionService: InstrumentActionService
) {

    private val logger = LoggerFactory.getLogger(PluginActionController::class.java)

    // TODO: Add HMAC signature verification for production use (same posture
    //  as the configuration push in PluginHostController).
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
        val processInstanceId = body["processInstanceId"] as? String
        logger.info(
            "Plugin action '{}' invoked (processInstance={}, document={}, activity={})",
            actionKey, processInstanceId, body["documentId"], body["activityId"]
        )

        return when (actionKey) {
            "update-actie" -> execute(actionKey) {
                actieBouwblokService.handleUpdateActie(properties, processInstanceId)
                emptyMap()
            }

            // Instrumenten/producten API for product building blocks: the
            // create response returns the instrumentUuid as declared output
            // (the result channel of the URL-app contract); the building
            // block's result mapping writes it into the building block
            // document.
            "aanmaak-instrument" -> execute(actionKey) {
                mapOf("result" to instrumentActionService.handleAanmaakInstrument(properties))
            }

            "update-instrument" -> execute(actionKey) {
                instrumentActionService.handleUpdateInstrument(properties)
                emptyMap()
            }

            else -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                mapOf("error" to "Action '$actionKey' not found on plugin $pluginId@$version")
            )
        }
    }

    /** 200 completed (plus any extra response fields) or 422 with an error code. */
    private fun execute(
        actionKey: String,
        action: () -> Map<String, Any?>
    ): ResponseEntity<Map<String, Any?>> = try {
        ResponseEntity.ok(mapOf<String, Any?>("status" to "completed") + action())
    } catch (e: PluginActionException) {
        logger.warn("{} rejected ({}): {}", actionKey, e.errorCode, e.message)
        ResponseEntity.unprocessableEntity().body(
            mapOf("status" to "error", "errorCode" to e.errorCode, "errorMessage" to e.message)
        )
    }
}
