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
import {handleOperation, type HttpCall, type HttpReply} from "../src/openplan";

const PLAN_UUID = "11111111-1111-1111-1111-111111111111";
const CONFIG = {openPlanUrl: "http://openplan.example:7501/", openPlanToken: "secret-token"};

function recorder(reply: HttpReply) {
  const calls: HttpCall[] = [];
  return {
    calls,
    transport: (call: HttpCall) => {
      calls.push(call);
      return reply;
    },
  };
}

const registerReply = (status: number, body: unknown): HttpReply => ({
  status,
  headers: {"content-type": "application/json", date: "Tue, 14 Oct 2026 10:00:00 GMT", "content-length": "2"},
  body,
});

describe("request mapping", () => {
  it("lists with the query string and the token header", () => {
    const {calls, transport} = recorder(registerReply(200, {count: 0, results: []}));
    const out = handleOperation("list", {resource: "doel", query: {plannen__uuid: PLAN_UUID, page: "2"}}, CONFIG, transport);

    expect(out).toEqual({status: "completed", result: {status: 200, body: {count: 0, results: []}}});
    expect(calls[0].method).toBe("GET");
    expect(calls[0].url).toBe(`http://openplan.example:7501/plannen/api/v0/doel?plannen__uuid=${PLAN_UUID}&page=2`);
    expect(calls[0].headers.Authorization).toBe("Token secret-token");
    expect(calls[0].body).toBeUndefined();
  });

  it("encodes query values", () => {
    const {calls, transport} = recorder(registerReply(200, {count: 0, results: []}));
    handleOperation("list", {resource: "instrument", query: {doelen__uuid__in: "a,b", naam: "x y"}}, CONFIG, transport);
    expect(calls[0].url).toBe("http://openplan.example:7501/plannen/api/v0/instrument?doelen__uuid__in=a%2Cb&naam=x%20y");
  });

  it("gets by uuid", () => {
    const {calls, transport} = recorder(registerReply(200, {uuid: PLAN_UUID}));
    handleOperation("get", {resource: "plan", uuid: PLAN_UUID}, CONFIG, transport);
    expect(calls[0]).toMatchObject({method: "GET", url: `http://openplan.example:7501/plannen/api/v0/plan/${PLAN_UUID}`});
  });

  it("creates with a JSON body", () => {
    const {calls, transport} = recorder(registerReply(201, {uuid: PLAN_UUID}));
    const out = handleOperation("create", {resource: "plan", body: {titel: "Plan"}}, CONFIG, transport);
    expect(calls[0]).toMatchObject({method: "POST", body: {titel: "Plan"}});
    expect(calls[0].headers["Content-Type"]).toBe("application/json");
    expect(out.result).toEqual({status: 201, body: {uuid: PLAN_UUID}});
  });

  it("updates with PATCH, or PUT when replace is set", () => {
    const {calls, transport} = recorder(registerReply(200, {}));
    handleOperation("update", {resource: "doel", uuid: PLAN_UUID, body: {status: "afgerond"}}, CONFIG, transport);
    handleOperation("update", {resource: "doel", uuid: PLAN_UUID, body: {}, replace: true}, CONFIG, transport);
    expect(calls.map((c) => c.method)).toEqual(["PATCH", "PUT"]);
  });

  it("deletes and turns an empty answer into a null body", () => {
    const {calls, transport} = recorder(registerReply(204, ""));
    const out = handleOperation("delete", {resource: "instrument", uuid: PLAN_UUID}, CONFIG, transport);
    expect(calls[0].method).toBe("DELETE");
    expect(out).toEqual({status: "completed", result: {status: 204, body: null}});
  });
});

describe("register answers", () => {
  it("passes a register validation error through as a completed call", () => {
    const error = {code: "invalid", title: "Invalid input.", invalidParams: [{name: "titel", reason: "Dit veld is vereist."}]};
    const out = handleOperation("create", {resource: "plan", body: {}}, CONFIG, recorder(registerReply(400, error)).transport);
    expect(out).toEqual({status: "completed", result: {status: 400, body: error}});
  });

  it("passes a register 404 through", () => {
    const out = handleOperation("get", {resource: "plan", uuid: PLAN_UUID}, CONFIG, recorder(registerReply(404, {detail: "Niet gevonden."})).transport);
    expect(out.result).toEqual({status: 404, body: {detail: "Niet gevonden."}});
  });
});

describe("plugin-level failures", () => {
  const hostReply = (status: number, error: string): HttpReply => ({
    status,
    headers: {"content-type": "application/json"},
    body: {error},
  });

  it("reports an egress refusal", () => {
    const out = handleOperation("list", {resource: "plan"}, CONFIG, recorder(hostReply(400, "Origin not allowed")).transport);
    expect(out).toMatchObject({status: "error", errorCode: "REQUEST_REFUSED"});
  });

  it("reports an unreachable register", () => {
    const out = handleOperation("list", {resource: "plan"}, CONFIG, recorder(hostReply(502, "http_request failed: ECONNREFUSED")).transport);
    expect(out).toMatchObject({status: "error", errorCode: "OPENPLAN_UNREACHABLE"});
  });

  it("refuses a missing configuration without calling out", () => {
    const {calls, transport} = recorder(registerReply(200, {}));
    const out = handleOperation("list", {resource: "plan"}, {openPlanUrl: "http://x"}, transport);
    expect(out).toMatchObject({status: "error", errorCode: "CONFIGURATION_INCOMPLETE"});
    expect(calls).toHaveLength(0);
  });

  it("refuses an unknown resource", () => {
    const out = handleOperation("list", {resource: "zaak"}, CONFIG, recorder(registerReply(200, {})).transport);
    expect(out).toMatchObject({status: "error", errorCode: "UNKNOWN_RESOURCE"});
  });

  it("requires a valid uuid for get, update and delete", () => {
    const {transport} = recorder(registerReply(200, {}));
    for (const operation of ["get", "update", "delete"] as const) {
      expect(handleOperation(operation, {resource: "plan"}, CONFIG, transport)).toMatchObject({errorCode: "INVALID_UUID"});
      expect(handleOperation(operation, {resource: "plan", uuid: "../plan"}, CONFIG, transport)).toMatchObject({errorCode: "INVALID_UUID"});
    }
  });

  it("refuses payload fields that do not belong to the operation", () => {
    const {transport} = recorder(registerReply(200, {}));
    expect(handleOperation("list", {resource: "plan", uuid: PLAN_UUID}, CONFIG, transport)).toMatchObject({errorCode: "INVALID_PAYLOAD"});
    expect(handleOperation("get", {resource: "plan", uuid: PLAN_UUID, body: {}}, CONFIG, transport)).toMatchObject({errorCode: "INVALID_PAYLOAD"});
    expect(handleOperation("create", {resource: "plan", replace: true}, CONFIG, transport)).toMatchObject({errorCode: "INVALID_PAYLOAD"});
    expect(handleOperation("list", "plan", CONFIG, transport)).toMatchObject({errorCode: "INVALID_PAYLOAD"});
  });
});
