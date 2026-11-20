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
 * The `nl.ritense.openplan@2` contract on the Open Plan Plannen API v0: exactly the resources and
 * operations the PDCA app uses. Reference data is read-only, plans are never deleted, personen only
 * listed and created.
 */
export const OPENPLAN: RegisterSpec = {
  contract: "nl.ritense.openplan@2",
  registerName: "Open Plan",
  apiPath: "/plannen/api/v0",
  urlProperty: "openPlanUrl",
  tokenProperty: "openPlanToken",
  unreachableCode: "OPENPLAN_UNREACHABLE",
  resources: {
    plan: {operations: ["list", "get", "create", "update"]},
    doel: {operations: ["list", "get", "create", "update", "delete"]},
    instrument: {operations: ["list", "get", "create", "update", "delete"]},
    persoon: {operations: ["list", "create"]},
    doeltype: {operations: ["list"]},
    instrumenttype: {operations: ["list"]},
    relatietype: {operations: ["list"]},
    plantype: {operations: ["list"]},
    overkoepelendplan: {operations: ["list"]},
  },
};
