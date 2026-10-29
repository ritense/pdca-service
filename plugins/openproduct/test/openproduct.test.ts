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
import {handleOperation, type HttpCall, type HttpReply} from "../src/openproduct";

const PRODUCTTYPE_UUID = "22222222-2222-2222-2222-222222222222";
const CONFIG = {openProductUrl: "http://openproduct.example:7502/", openProductToken: "secret-token"};

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
    const out = handleOperation("list", {resource: "producttypen", query: {page: "2"}}, CONFIG, transport);

    expect(out).toEqual({status: "completed", result: {status: 200, body: {count: 0, results: []}}});
    expect(calls[0].method).toBe("GET");
    expect(calls[0].url).toBe("http://openproduct.example:7502/producttypen/api/v1/producttypen?page=2");
    expect(calls[0].headers.Authorization).toBe("Token secret-token");
    expect(calls[0].body).toBeUndefined();
  });

  it("gets by uuid", () => {
    const {calls, transport} = recorder(registerReply(200, {uuid: PRODUCTTYPE_UUID}));
    handleOperation("get", {resource: "producttypen", uuid: PRODUCTTYPE_UUID}, CONFIG, transport);
    expect(calls[0]).toMatchObject({
      method: "GET",
      url: `http://openproduct.example:7502/producttypen/api/v1/producttypen/${PRODUCTTYPE_UUID}`,
    });
  });

  it("creates with a JSON body and updates with PATCH, or PUT when replace is set", () => {
    const {calls, transport} = recorder(registerReply(201, {uuid: PRODUCTTYPE_UUID}));
    handleOperation("create", {resource: "themas", body: {naam: "Werk"}}, CONFIG, transport);
    handleOperation("update", {resource: "themas", uuid: PRODUCTTYPE_UUID, body: {naam: "Werk"}}, CONFIG, transport);
    handleOperation("update", {resource: "themas", uuid: PRODUCTTYPE_UUID, body: {}, replace: true}, CONFIG, transport);
    expect(calls.map((c) => c.method)).toEqual(["POST", "PATCH", "PUT"]);
    expect(calls[0].headers["Content-Type"]).toBe("application/json");
    expect(calls[0].body).toEqual({naam: "Werk"});
  });
});

describe("register answers and failures", () => {
  it("passes a register 404 through as a completed call", () => {
    const out = handleOperation(
      "get",
      {resource: "producttypen", uuid: PRODUCTTYPE_UUID},
      CONFIG,
      recorder(registerReply(404, {detail: "Niet gevonden."})).transport
    );
    expect(out).toEqual({status: "completed", result: {status: 404, body: {detail: "Niet gevonden."}}});
  });

  it("reports an unreachable register", () => {
    const hostReply: HttpReply = {status: 502, headers: {"content-type": "application/json"}, body: {error: "ECONNREFUSED"}};
    const out = handleOperation("list", {resource: "producttypen"}, CONFIG, recorder(hostReply).transport);
    expect(out).toMatchObject({status: "error", errorCode: "OPENPRODUCT_UNREACHABLE"});
  });

  it("refuses a missing configuration without calling out", () => {
    const {calls, transport} = recorder(registerReply(200, {}));
    const out = handleOperation("list", {resource: "producttypen"}, {openProductUrl: "http://x"}, transport);
    expect(out).toMatchObject({status: "error", errorCode: "CONFIGURATION_INCOMPLETE"});
    expect(calls).toHaveLength(0);
  });

  it("refuses resources outside the Producttypen API", () => {
    const {transport} = recorder(registerReply(200, {}));
    expect(handleOperation("list", {resource: "plan"}, CONFIG, transport)).toMatchObject({errorCode: "UNKNOWN_RESOURCE"});
    expect(handleOperation("list", {resource: "bestanden"}, CONFIG, transport)).toMatchObject({errorCode: "UNKNOWN_RESOURCE"});
  });
});
