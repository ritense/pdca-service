package com.ritense.pdca.config

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

        println("""

            ┌──────────────────────────────────────────────────────────────────────────┐
            │  PDCA App running at $base
            ├──────────────────────────────────────────────────────────────────────────┤
            │
            │  Registers (Maykin, via docker compose):
            │    Open Plan API:     $base/openplan/plannen/api/v0/plan
            │                       (direct: http://localhost:7501, admin/admin)
            │    Open Product API:  $base/openproduct/producttypen/api/v1/producttypen
            │                       (direct: http://localhost:7502, admin/admin)
            │    Demo plan Erika:   $base/openplan/plannen/api/v0/plan/11111111-1111-1111-1111-111111111111
            │    Demo plan Binnenhof: .../plan/33333333-3333-3333-3333-333333333333
            │
            │  PDCA overlay API (status/voortgang/acties, keyed by register uuid):
            │    Plandetails:   $base/api/v1/pdca/plandetails
            │    Doeldetails:   $base/api/v1/pdca/doeldetails?planUuid=...
            │    Instrumenten:  $base/api/v1/pdca/instrumentdetails?planUuid=...
            │    Acties:        $base/api/v1/pdca/acties?planUuid=...
            │    Betrokkenen:   $base/api/v1/pdca/betrokkenen?planUuid=...
            │    Config:        $base/api/v1/admin/phase-configs
            │    BRP/object stub: $base/api/v1/registers/personen/111222333
            │
            │  Plugin host:
            │    Health:    $base/health
            │    Manifest:  $base/api/host/plugins
            │    Bundles:   $base/bundles/react/plan-overview.html
            │
            │  To connect to GZAC: register this app by URL ($base) as an
            │  external plugin; case tabs plan-overview / plan-goals / plan-evaluations.
            │
            └──────────────────────────────────────────────────────────────────────────┘

        """.trimIndent())
    }
}
