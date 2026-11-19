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

import {describe, expect, it} from "vitest";
import {handleOperation, type HttpCall, type HttpReply} from "../../register-core/src/contract";
import {expectManifestMatchesSpec} from "../../register-core/test/manifest-support";
import {OPENKLANT} from "../src/openklant";
import manifest from "../manifest.json";
import pkg from "../package.json";

const UUID = "11111111-1111-1111-1111-111111111111";
const CONFIG = {openKlantUrl: "http://openklant.example:7504", openKlantToken: "secret-token"};
const reply: HttpReply = {status: 200, headers: {"content-type": "application/json", date: "x"}, body: {}};

function call(operation: Parameters<typeof handleOperation>[1], payload: unknown) {
  const calls: HttpCall[] = [];
  const out = handleOperation(OPENKLANT, operation, payload, CONFIG, (c) => (calls.push(c), reply));
  return {out, calls};
}

describe("nl.ritense.openklant@2", () => {
  it("declares in its manifest exactly what it serves", () => {
    expectManifestMatchesSpec(manifest, OPENKLANT, pkg.version);
  });

  it("serves the contactmomenten and partijen the PDCA app uses", () => {
    const {calls} = call("get", {resource: "klantcontacten", uuid: UUID, query: {expand: "gingOverOnderwerpobjecten"}});
    expect(calls[0].url).toBe(`http://openklant.example:7504/klantinteracties/api/v1/klantcontacten/${UUID}?expand=gingOverOnderwerpobjecten`);
    expect(call("create", {resource: "maak-klantcontact", body: {}}).calls[0].method).toBe("POST");
    expect(call("update", {resource: "klantcontacten", uuid: UUID, body: {}}).calls[0].method).toBe("PATCH");
    expect(call("list", {resource: "partijen"}).out).toMatchObject({status: "completed"});
    expect(call("create", {resource: "partijen", body: {}}).out).toMatchObject({status: "completed"});
  });

  it("refuses deletes and the resources the app does not use", () => {
    expect(call("delete", {resource: "klantcontacten", uuid: UUID}).out).toMatchObject({errorCode: "OPERATION_NOT_ALLOWED"});
    expect(call("update", {resource: "partijen", uuid: UUID, body: {}}).out).toMatchObject({errorCode: "OPERATION_NOT_ALLOWED"});
    for (const resource of ["betrokkenen", "digitaleadressen", "internetaken", "actoren"]) {
      expect(call("list", {resource}).out).toMatchObject({errorCode: "UNKNOWN_RESOURCE"});
    }
  });
});
