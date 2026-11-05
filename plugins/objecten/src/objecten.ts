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

import {ContractError, readUrl, isObject, type ContractRequest, type RegisterSpec} from "../../register-core/src/contract";

/** The Objects API wants to know the coordinate system of every request and answer. */
const CRS = "EPSG:4326";

/** Configuration property with the URL of the one objecttype this plugin serves. */
export const OBJECTTYPE_PROPERTY = "verzoekObjecttypeUrl";

/**
 * The `nl.ritense.objecten@2` contract on the Objects API v2, confined to the objects of ONE
 * objecttype (the configured verzoek objecttype): a listing is always filtered on it, and a new
 * object must be of it. Objects are listed and created, never read one by one, changed or deleted.
 */
export const OBJECTEN: RegisterSpec = {
  contract: "nl.ritense.objecten@2",
  registerName: "Objecten API",
  apiPath: "/api/v2",
  urlProperty: "objectenUrl",
  tokenProperty: "objectenToken",
  unreachableCode: "OBJECTEN_UNREACHABLE",
  resources: {
    objects: {operations: ["list", "create"], queryKeys: ["type", "data_attrs", "page", "pageSize"]},
  },
  headers: {"Accept-Crs": CRS},
  bodyHeaders: {"Content-Crs": CRS},
  refine: confineToObjecttype,
};

function confineToObjecttype(request: ContractRequest, configuration: Record<string, unknown>): ContractRequest {
  const objecttype = readUrl(configuration, OBJECTTYPE_PROPERTY);
  if (request.operation === "list") {
    // A caller may name the type (a register's `next` link does), but only this one.
    const asked = request.query.type;
    if (asked !== undefined && normalize(asked) !== objecttype) throw notAllowed(asked);
    return {...request, query: {...request.query, type: objecttype}};
  }
  const type = isObject(request.body) ? request.body.type : undefined;
  if (typeof type !== "string" || normalize(type) !== objecttype) throw notAllowed(type);
  return request;
}

function normalize(url: string): string {
  return url.trim().replace(/\/+$/, "");
}

function notAllowed(type: unknown): ContractError {
  return new ContractError(
    "OBJECTTYPE_NOT_ALLOWED",
    `This configuration only serves objects of its verzoek objecttype, not '${String(type)}'`
  );
}
