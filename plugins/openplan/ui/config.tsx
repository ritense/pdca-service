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

// Local dev stack: Open Plan on host port 7501, seen from the plugin host container.
renderConfigForm({
  defaultTitle: "Open Plan",
  fields: [
    {key: "openPlanUrl", kind: "baseUrl", placeholder: "https://openplan.example.nl", localDefault: "http://host.docker.internal:7501"},
    {key: "openPlanToken", kind: "secret", localDefault: "pdca-openplan-dev-token-0123456789abcdef"},
  ],
});
