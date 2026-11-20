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
 * The `nl.ritense.openproduct@2` contract on the Open Product Producttypen API v1: read-only, and
 * only the producttypen and their acties (the start recipe of a product request).
 */
export const OPENPRODUCT: RegisterSpec = {
  contract: "nl.ritense.openproduct@2",
  registerName: "Open Product",
  apiPath: "/producttypen/api/v1",
  urlProperty: "openProductUrl",
  tokenProperty: "openProductToken",
  unreachableCode: "OPENPRODUCT_UNREACHABLE",
  resources: {
    producttypen: {operations: ["list", "get"]},
    acties: {operations: ["list"]},
  },
};
