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

import type {RegisterSpec} from "../../register-core/src/contract";

/**
 * The `nl.ritense.openklant@2` contract on the Open Klant Klantinteracties API v1: the
 * contactmomenten of a plan (klantcontacten, created in one go through `maak-klantcontact`) and
 * the partij of its subject. Nothing is deleted.
 */
export const OPENKLANT: RegisterSpec = {
  contract: "nl.ritense.openklant@2",
  registerName: "Open Klant",
  apiPath: "/klantinteracties/api/v1",
  urlProperty: "openKlantUrl",
  tokenProperty: "openKlantToken",
  unreachableCode: "OPENKLANT_UNREACHABLE",
  resources: {
    klantcontacten: {operations: ["list", "get", "update"]},
    "maak-klantcontact": {operations: ["create"]},
    partijen: {operations: ["list", "create"]},
  },
};
