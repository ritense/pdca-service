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

package com.ritense.pdca.web.rest

import com.ritense.pdca.service.GzacClient
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.CrossOrigin
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * GZAC's accent colours for the bundles. They render in sandboxed iframes and
 * cannot read the host's inline styles, so they read the `--vcds-color-*` ramp
 * here and apply it to their own root. An empty map means GZAC did not answer
 * or the endpoint is not granted; the bundles then keep their default ramp.
 */
@RestController
@CrossOrigin
@RequestMapping("/api/v1/pdca/accent-colors", produces = [MediaType.APPLICATION_JSON_VALUE])
class AccentColorsResource(
    private val gzacClient: GzacClient
) {

    @GetMapping
    fun get(): ResponseEntity<Map<String, Map<String, String>>> =
        ResponseEntity.ok(mapOf("colors" to gzacClient.accentColors()))
}
