package com.ritense.pdca.config

import com.ritense.pdca.plugin.PluginHostController
import com.ritense.pdca.registers.Register
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

@Component
class StartupInfoPrinter(private val env: Environment) {

    @EventListener(ApplicationReadyEvent::class)
    fun onReady() {
        val port = env.getProperty("server.port", "7500")
        val base = "http://localhost:$port"
        val plugin = "$base/plugins/${PluginHostController.PLUGIN_ID}/${PluginHostController.PLUGIN_VERSION}"
        fun via(register: Register) = "contract ${register.contract} via GZAC and the ${register.key} plugin"

        println("""

            ┌──────────────────────────────────────────────────────────────────────────┐
            │  PDCA App running at $base
            ├──────────────────────────────────────────────────────────────────────────┤
            │
            │  Registers (Maykin, via docker compose), reached only through GZAC:
            │    Open Plan:     ${via(Register.OPENPLAN)}
            │                   (register admin: http://localhost:7501/admin/, admin/admin)
            │    Open Product:  ${via(Register.OPENPRODUCT)}
            │                   (register admin: http://localhost:7502/admin/, admin/admin)
            │    Open Klant:    ${via(Register.OPENKLANT)}
            │                   (register admin: http://localhost:7504/admin/, admin/admin)
            │    Objecten API:  ${via(Register.OBJECTEN)}
            │
            │  Plugin host (the only entry points):
            │    Health:    $base/health
            │    Host API:  $base/api/host/*  (signed by GZAC with PDCA_HOST_SECRET)
            │    Actions:   $plugin/actions/*  (signed by GZAC)
            │    Data:      $plugin/data  (the bundles' API calls, with the GZAC user token)
            │    Bundles:   $plugin/bundles/plan-overview.html
            │
            │  The REST API (/api/v1/...) and the register proxy are not reachable
            │  directly: the bundles call them through GZAC and /data.
            │
            │  To connect to GZAC: register this app by URL ($base) as an
            │  external plugin with the same secret as PDCA_HOST_SECRET.
            │
            └──────────────────────────────────────────────────────────────────────────┘

        """.trimIndent())
    }
}
