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

import {renderConfigForm} from "../../register-core/frontend/config-form";

// Local GZAC stack (gzac-docker-compose): Objecten API on host port 8010 with its fixture token, and
// the InstrumentVerzoek objecttype gzac/dev-setup/registers/setup-registers.sh creates in the stack's
// Objecttypen API; all seen from the plugin host container.
renderConfigForm({
  defaultTitle: "Objecten API",
  fields: [
    {key: "objectenUrl", kind: "baseUrl", placeholder: "https://objecten.example.nl", localDefault: "http://host.docker.internal:8010"},
    {key: "objectenToken", kind: "secret", localDefault: "182c13e2209161852c53cef53a879f7a2f923430"},
    {
      key: "verzoekObjecttypeUrl",
      kind: "url",
      placeholder: "https://objecttypen.example.nl/api/v2/objecttypes/<uuid>",
      localDefault: "http://host.docker.internal:8011/api/v2/objecttypes/00000000-0000-4000-8000-0000dca00001",
    },
  ],
});
