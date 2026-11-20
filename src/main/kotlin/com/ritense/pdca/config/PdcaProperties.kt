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

package com.ritense.pdca.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "pdca")
data class PdcaProperties(
    val gzac: GzacApi = GzacApi(),
    val host: Host = Host(),
    val encryption: Encryption = Encryption(),
    val registers: Registers = Registers(),
    val data: DataApi = DataApi(),
    val displayStubs: DisplayStubs = DisplayStubs()
) {
    /**
     * How the app reaches GZAC. Credentials are never configured: the app
     * only uses the serviceToken GZAC pushes with the plugin configuration.
     * [baseUrl] overrides the pushed gzacBaseUrl where the app sees GZAC
     * under a different address than GZAC's own callback URL.
     * [configurationId] names the pushed PDCA configuration the app uses;
     * without it there must be exactly one (see `GzacAccess`).
     * [allowedBaseUrls], when set, pins the GZAC instances the app accepts:
     * a configuration push or instance announcement naming another
     * gzacBaseUrl is refused. [frontendOrigins] are the GZAC frontend
     * origins that may frame the bundles and call `/data`, on top of the
     * ones GZAC announces (`FrontendOrigins`).
     */
    data class GzacApi(
        val baseUrl: String? = null,
        val configurationId: String? = null,
        val allowedBaseUrls: List<String> = emptyList(),
        val frontendOrigins: List<String> = listOf("http://localhost:4200")
    )

    /**
     * The shared secret GZAC signs its requests to the app with (the secret
     * entered when the app is registered as an external-plugin integration);
     * [LOCAL_DEV_SECRET] is the documented local-dev default, which the app
     * warns about at startup. [signatureTolerance] is how far a request's
     * signed timestamp may differ from the app's clock.
     */
    data class Host(
        val secret: String = LOCAL_DEV_SECRET,
        val signatureTolerance: Duration = Duration.ofMinutes(5)
    ) {
        val isLocalDevDefault: Boolean get() = secret == LOCAL_DEV_SECRET

        companion object {
            const val LOCAL_DEV_SECRET = "dev-only-insecure-secret"
        }
    }

    /**
     * Encryption at rest of the pushed serviceTokens. [secret] is the
     * passphrase the AES key is derived from; [LOCAL_DEV_SECRET] is the
     * documented local-dev default, which the app warns about at startup.
     */
    data class Encryption(
        val secret: String = LOCAL_DEV_SECRET
    ) {
        val isLocalDevDefault: Boolean get() = secret == LOCAL_DEV_SECRET

        companion object {
            const val LOCAL_DEV_SECRET = "pdca-local-dev-encryption-secret"
        }
    }

    /**
     * Register access through the gateways: [maxPages] caps a listing; more
     * pages fail instead of truncating. The contract calls behind them are
     * limited to [contractConcurrency] at a time (GZAC refuses more than 8
     * per calling configuration); a caller waits at most
     * [contractPermitTimeout] for its turn, and a call GZAC still refuses as
     * too many is retried at most [contractRetries] times after a jittered
     * pause growing from [contractRetryBackoff] (`ContractCallLimiter`).
     */
    data class Registers(
        val maxPages: Int = 50,
        val contractConcurrency: Int = 6,
        val contractPermitTimeout: Duration = Duration.ofSeconds(30),
        val contractRetries: Int = 3,
        val contractRetryBackoff: Duration = Duration.ofMillis(200)
    )

    /** The browser traffic through `/data`; see [RateLimit]. */
    data class DataApi(val rateLimit: RateLimit = RateLimit())

    /**
     * `DataRateLimiter`, per [window], as a burst or spread out; 0 switches a
     * limit off: at most [perUser] calls of one verified user; and of the
     * calls whose user token is not cached (the ones that cost an
     * introspection at GZAC) at most [perClient] from one client address
     * and [perConfiguration] for the whole configuration.
     */
    data class RateLimit(
        val perUser: Int = 600,
        val perClient: Int = 300,
        val perConfiguration: Int = 6000,
        val window: Duration = Duration.ofMinutes(1)
    )

    /**
     * The display stubs for the registers outside this integration (BRP
     * persoonsgegevens, objectenregister; `StubRegisterService`): fixed demo
     * data, so only [enabled] for local development and demos.
     */
    data class DisplayStubs(val enabled: Boolean = false)
}
