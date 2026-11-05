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
import {OPENPLAN} from "../src/openplan";
import manifest from "../manifest.json";
import pkg from "../package.json";

const UUID = "11111111-1111-1111-1111-111111111111";
const CONFIG = {openPlanUrl: "http://openplan.example:7501/", openPlanToken: "secret-token"};
const reply: HttpReply = {status: 200, headers: {"content-type": "application/json", date: "x"}, body: {}};

function call(operation: Parameters<typeof handleOperation>[1], payload: unknown) {
  const calls: HttpCall[] = [];
  const out = handleOperation(OPENPLAN, operation, payload, CONFIG, (c) => (calls.push(c), reply));
  return {out, calls};
}

describe("nl.ritense.openplan@2", () => {
  it("declares in its manifest exactly what it serves", () => {
    expectManifestMatchesSpec(manifest, OPENPLAN, pkg.version);
  });

  it("calls the Plannen API v0 with the configured token", () => {
    const {calls} = call("list", {resource: "doel", query: {plannen__uuid: UUID}});
    expect(calls[0].url).toBe(`http://openplan.example:7501/plannen/api/v0/doel?plannen__uuid=${UUID}`);
    expect(calls[0].headers.Authorization).toBe("Token secret-token");
  });

  it("serves what the PDCA app uses", () => {
    for (const [operation, payload] of [
      ["get", {resource: "plan", uuid: UUID}],
      ["create", {resource: "plan", body: {}}],
      ["update", {resource: "plan", uuid: UUID, body: {}}],
      ["delete", {resource: "doel", uuid: UUID}],
      ["delete", {resource: "instrument", uuid: UUID}],
      ["create", {resource: "persoon", body: {}}],
      ["list", {resource: "overkoepelendplan"}],
    ] as const) {
      expect(call(operation, payload).out, `${operation} ${payload.resource}`).toMatchObject({status: "completed"});
    }
  });

  it("refuses everything else", () => {
    for (const [operation, payload] of [
      ["delete", {resource: "plan", uuid: UUID}],
      ["get", {resource: "persoon", uuid: UUID}],
      ["update", {resource: "persoon", uuid: UUID, body: {}}],
      ["create", {resource: "doeltype", body: {}}],
      ["update", {resource: "plantype", uuid: UUID, body: {}}],
    ] as const) {
      const {out, calls} = call(operation, payload);
      expect(out, `${operation} ${payload.resource}`).toMatchObject({errorCode: "OPERATION_NOT_ALLOWED"});
      expect(calls).toHaveLength(0);
    }
    expect(call("list", {resource: "zaak"}).out).toMatchObject({errorCode: "UNKNOWN_RESOURCE"});
  });

  it("reports an unreachable Open Plan", () => {
    const out = handleOperation(OPENPLAN, "list", {resource: "plan"}, CONFIG, () => ({
      status: 502,
      headers: {"content-type": "application/json"},
      body: {error: "http_request failed: ECONNREFUSED"},
    }));
    expect(out).toMatchObject({status: "error", errorCode: "OPENPLAN_UNREACHABLE"});
  });
});
