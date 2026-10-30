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
import {handleOperation, type HttpCall, type HttpReply} from "../src/objecten";

const OBJECT_UUID = "22222222-2222-2222-2222-222222222222";
const CONFIG = {objectenUrl: "http://objecten.example:8010/", objectenToken: "secret-token"};
const VERZOEK = {
  type: "http://objecttypen.example:8011/api/v2/objecttypes/33333333-3333-3333-3333-333333333333",
  record: {typeVersion: 1, startAt: "2026-10-30", data: {type: "instrument", data: {planUuid: "p"}}},
};

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
  headers: {"content-type": "application/json", date: "Thu, 30 Oct 2026 10:00:00 GMT", "content-length": "2"},
  body,
});

describe("request mapping", () => {
  it("lists with the query string, the token header and the coordinate system", () => {
    const {calls, transport} = recorder(registerReply(200, {count: 0, results: []}));
    const out = handleOperation("list", {resource: "objects", query: {type: VERZOEK.type}}, CONFIG, transport);

    expect(out).toEqual({status: "completed", result: {status: 200, body: {count: 0, results: []}}});
    expect(calls[0].method).toBe("GET");
    expect(calls[0].url).toBe(`http://objecten.example:8010/api/v2/objects?type=${encodeURIComponent(VERZOEK.type)}`);
    expect(calls[0].headers.Authorization).toBe("Token secret-token");
    expect(calls[0].headers["Accept-Crs"]).toBe("EPSG:4326");
    expect(calls[0].headers["Content-Crs"]).toBeUndefined();
    expect(calls[0].body).toBeUndefined();
  });

  it("gets by uuid", () => {
    const {calls, transport} = recorder(registerReply(200, {uuid: OBJECT_UUID}));
    handleOperation("get", {resource: "objects", uuid: OBJECT_UUID}, CONFIG, transport);
    expect(calls[0]).toMatchObject({
      method: "GET",
      url: `http://objecten.example:8010/api/v2/objects/${OBJECT_UUID}`,
    });
  });

  it("creates a verzoek with a JSON body and both coordinate headers, updates with PATCH or PUT", () => {
    const {calls, transport} = recorder(registerReply(201, {uuid: OBJECT_UUID}));
    const out = handleOperation("create", {resource: "objects", body: VERZOEK}, CONFIG, transport);
    handleOperation("update", {resource: "objects", uuid: OBJECT_UUID, body: VERZOEK}, CONFIG, transport);
    handleOperation("update", {resource: "objects", uuid: OBJECT_UUID, body: VERZOEK, replace: true}, CONFIG, transport);

    expect(out).toEqual({status: "completed", result: {status: 201, body: {uuid: OBJECT_UUID}}});
    expect(calls.map((c) => c.method)).toEqual(["POST", "PATCH", "PUT"]);
    expect(calls[0].headers["Content-Type"]).toBe("application/json");
    expect(calls[0].headers["Content-Crs"]).toBe("EPSG:4326");
    expect(calls[0].headers["Accept-Crs"]).toBe("EPSG:4326");
    expect(calls[0].body).toEqual(VERZOEK);
  });
});

describe("register answers and failures", () => {
  it("passes a register 400 through as a completed call, validation errors included", () => {
    const body = {detail: "Ongeldige invoer.", invalidParams: [{name: "record.data", reason: "'planUuid' is a required property"}]};
    const out = handleOperation("create", {resource: "objects", body: VERZOEK}, CONFIG, recorder(registerReply(400, body)).transport);
    expect(out).toEqual({status: "completed", result: {status: 400, body}});
  });

  it("reports an unreachable register", () => {
    const hostReply: HttpReply = {status: 502, headers: {"content-type": "application/json"}, body: {error: "ECONNREFUSED"}};
    const out = handleOperation("list", {resource: "objects"}, CONFIG, recorder(hostReply).transport);
    expect(out).toMatchObject({status: "error", errorCode: "OBJECTEN_UNREACHABLE"});
  });

  it("refuses a missing configuration without calling out", () => {
    const {calls, transport} = recorder(registerReply(200, {}));
    const out = handleOperation("list", {resource: "objects"}, {objectenUrl: "http://x"}, transport);
    expect(out).toMatchObject({status: "error", errorCode: "CONFIGURATION_INCOMPLETE"});
    expect(calls).toHaveLength(0);
  });

  it("refuses resources outside the Objects API", () => {
    const {transport} = recorder(registerReply(200, {}));
    expect(handleOperation("list", {resource: "objecttypes"}, CONFIG, transport)).toMatchObject({errorCode: "UNKNOWN_RESOURCE"});
    expect(handleOperation("list", {resource: "producttypen"}, CONFIG, transport)).toMatchObject({errorCode: "UNKNOWN_RESOURCE"});
  });
});
