package com.ritense.pdca.devsetup

import java.io.File

/**
 * Reports, without changing anything, whether GZAC holds what the setup puts
 * in: the register plugin configurations, the verzoek route's Verzoek plugin
 * configuration and Notificaties API callback URL, the PDCA app's
 * registration and configuration with its contract bindings, and the case
 * definitions, imported and active. `bootRunWithDocker` runs it to decide
 * whether to offer the setup.
 *
 * Output: one `missing: <what>` line per gap, or one `unknown: <why>` line
 * when GZAC cannot be asked. Exit code: 0 configured, 1 something missing,
 * 2 unknown.
 */
class SetupCheck(private val repoDir: File, private val settings: Settings) {
    private val api = GzacApi(settings)

    fun run(): Int {
        if (!api.ready()) return unknown("GZAC at ${settings.gzacUrl} does not answer or refuses the dev login")
        val missing = try {
            missing(DevValues.load(repoDir.resolve(DevValues.PATH), System.getenv()))
        } catch (e: Exception) {
            return unknown(e.message ?: e::class.simpleName ?: "check failed")
        }
        missing.forEach { println("$MISSING$it") }
        return if (missing.isEmpty()) 0 else 1
    }

    private fun missing(values: DevValues): List<String> {
        val registerConfigurations = RegisterPlugins(api, values, repoDir).currentConfigurations()
        return registerConfigurations.filterValues { it == null }.keys.map { "$it configuration" } +
            VerzoekRoute(api, values).missing() +
            PdcaApp(api, values).missing(registerConfigurations) +
            CaseDefinitions(api, values, repoDir).missing()
    }

    private fun unknown(reason: String): Int {
        println("$UNKNOWN$reason")
        return 2
    }

    companion object {
        const val MISSING = "missing: "
        const val UNKNOWN = "unknown: "
    }
}
