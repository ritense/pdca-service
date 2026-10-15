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

package com.ritense.pdca.registers

/**
 * The Maykin registers the app uses. Each one is reached only through GZAC's
 * contract broker ([contract], served by the external plugin [key] on a
 * plugin host); the app holds no register URL or token. [apiPath] is the
 * register API the contract covers; [key] is also the prefix of the
 * same-origin proxy (`/<key>/...`).
 */
enum class Register(
    val key: String,
    val displayName: String,
    val contract: String,
    val apiPath: String
) {
    OPENPLAN("openplan", "Open Plan", "nl.ritense.openplan@1", "/plannen/api/v0"),
    OPENPRODUCT("openproduct", "Open Product", "nl.ritense.openproduct@1", "/producttypen/api/v1"),
    OPENKLANT("openklant", "Open Klant", "nl.ritense.openklant@1", "/klantinteracties/api/v1");

    /** Placeholder base URL of the register's RestClient; only path and query count. */
    val contractBaseUrl: String
        get() = "http://$key.contract"
}
