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
import {
  ContractError,
  handleOperation,
  hostFailure,
  operationsOf,
  serveContract,
  type ContractOutput,
  type HttpCall,
  type HttpReply,
  type RegisterSpec,
} from "../src/contract";

const UUID = "11111111-1111-1111-1111-111111111111";

const SPEC: RegisterSpec = {
  contract: "nl.example.register@2",
  registerName: "Register",
  apiPath: "/api/v1",
  urlProperty: "registerUrl",
  tokenProperty: "registerToken",
  unreachableCode: "REGISTER_UNREACHABLE",
  resources: {
    thing: {operations: ["list", "get", "create", "update", "delete"]},
    kind: {operations: ["list"]},
    filtered: {operations: ["list"], queryKeys: ["name", "page"]},
  },
  headers: {"X-Always": "1"},
  bodyHeaders: {"X-Body": "1"},
};
const CONFIG = {registerUrl: "http://register.example:8000/", registerToken: "secret-token"};

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
  headers: {"content-type": "application/json", date: "Mon, 02 Nov 2026 10:00:00 GMT", "content-length": "2"},
  body,
});
const hostReply = (status: number, error: string): HttpReply => ({
  status,
  headers: {"content-type": "application/json"},
  body: {error},
});

describe("request mapping", () => {
  it("lists with the encoded query string, the token and the extra headers", () => {
    const {calls, transport} = recorder(registerReply(200, {count: 0, results: []}));
    const out = handleOperation(SPEC, "list", {resource: "thing", query: {ids: "a,b", naam: "x y", page: 2}}, CONFIG, transport);

    expect(out).toEqual({status: "completed", result: {status: 200, body: {count: 0, results: []}}});
    expect(calls[0]).toEqual({
      method: "GET",
      url: "http://register.example:8000/api/v1/thing?ids=a%2Cb&naam=x%20y&page=2",
      headers: {Authorization: "Token secret-token", Accept: "application/json", "X-Always": "1"},
    });
  });

  it("maps get, create, update and delete onto GET, POST, PATCH and DELETE", () => {
    const {calls, transport} = recorder(registerReply(200, {}));
    handleOperation(SPEC, "get", {resource: "thing", uuid: UUID, query: {expand: "x"}}, CONFIG, transport);
    handleOperation(SPEC, "create", {resource: "thing", body: {titel: "T"}}, CONFIG, transport);
    handleOperation(SPEC, "update", {resource: "thing", uuid: UUID, body: {titel: "U"}}, CONFIG, transport);
    handleOperation(SPEC, "delete", {resource: "thing", uuid: UUID}, CONFIG, transport);

    expect(calls.map((c) => `${c.method} ${c.url}`)).toEqual([
      `GET http://register.example:8000/api/v1/thing/${UUID}?expand=x`,
      "POST http://register.example:8000/api/v1/thing",
      `PATCH http://register.example:8000/api/v1/thing/${UUID}`,
      `DELETE http://register.example:8000/api/v1/thing/${UUID}`,
    ]);
    expect(calls[1].body).toEqual({titel: "T"});
    expect(calls[1].headers).toMatchObject({"Content-Type": "application/json", "X-Body": "1", "X-Always": "1"});
    expect(calls[0].headers["X-Body"]).toBeUndefined();
    expect(calls[3].body).toBeUndefined();
  });

  it("turns an empty answer into a null body", () => {
    const out = handleOperation(SPEC, "delete", {resource: "thing", uuid: UUID}, CONFIG, recorder(registerReply(204, "")).transport);
    expect(out).toEqual({status: "completed", result: {status: 204, body: null}});
  });
});

describe("register answers", () => {
  it("passes a validation error and a 404 through as completed calls", () => {
    const error = {code: "invalid", invalidParams: [{name: "titel", reason: "Dit veld is vereist."}]};
    expect(handleOperation(SPEC, "create", {resource: "thing", body: {}}, CONFIG, recorder(registerReply(400, error)).transport))
      .toEqual({status: "completed", result: {status: 400, body: error}});
    expect(handleOperation(SPEC, "get", {resource: "thing", uuid: UUID}, CONFIG, recorder(registerReply(404, {detail: "Niet gevonden."})).transport))
      .toEqual({status: "completed", result: {status: 404, body: {detail: "Niet gevonden."}}});
  });

  it("does not mistake a register's own {error} answer for a host failure", () => {
    const reply: HttpReply = {status: 502, headers: {"content-type": "application/json", date: "x"}, body: {error: "upstream"}};
    expect(hostFailure(SPEC, reply)).toBeNull();
  });
});

describe("host failures", () => {
  it.each([
    [400, "REQUEST_REFUSED"],
    [403, "CAPABILITY_NOT_GRANTED"],
    [502, "REGISTER_UNREACHABLE"],
    [504, "REGISTER_UNREACHABLE"],
    [500, "HOST_ERROR"],
  ])("reports a host reply %i as %s", (status, errorCode) => {
    const out = handleOperation(SPEC, "list", {resource: "thing"}, CONFIG, recorder(hostReply(status, "refused")).transport);
    expect(out).toMatchObject({status: "error", errorCode});
  });
});

describe("the allowlist", () => {
  const {transport, calls} = recorder(registerReply(200, {}));
  const call = (operation: Parameters<typeof handleOperation>[1], payload: unknown) =>
    handleOperation(SPEC, operation, payload, CONFIG, transport);

  it("refuses an unknown resource, also an inherited property name", () => {
    expect(call("list", {resource: "zaak"})).toMatchObject({errorCode: "UNKNOWN_RESOURCE"});
    expect(call("list", {resource: "constructor"})).toMatchObject({errorCode: "UNKNOWN_RESOURCE"});
  });

  it("refuses an operation the resource does not allow", () => {
    expect(call("create", {resource: "kind", body: {}})).toMatchObject({errorCode: "OPERATION_NOT_ALLOWED"});
    expect(call("delete", {resource: "kind", uuid: UUID})).toMatchObject({errorCode: "OPERATION_NOT_ALLOWED"});
  });

  it("refuses query parameters outside a resource's query allowlist", () => {
    expect(call("list", {resource: "filtered", query: {name: "a", page: "2"}})).toMatchObject({status: "completed"});
    expect(call("list", {resource: "filtered", query: {other: "x"}})).toMatchObject({errorCode: "QUERY_NOT_ALLOWED"});
  });

  it("requires a valid uuid for get, update and delete", () => {
    for (const operation of ["get", "update", "delete"] as const) {
      expect(call(operation, {resource: "thing"})).toMatchObject({errorCode: "INVALID_UUID"});
      expect(call(operation, {resource: "thing", uuid: "../thing"})).toMatchObject({errorCode: "INVALID_UUID"});
    }
  });

  it("refuses payload fields that do not belong to the operation", () => {
    expect(call("list", {resource: "thing", uuid: UUID})).toMatchObject({errorCode: "INVALID_PAYLOAD"});
    expect(call("get", {resource: "thing", uuid: UUID, body: {}})).toMatchObject({errorCode: "INVALID_PAYLOAD"});
    expect(call("update", {resource: "thing", uuid: UUID, body: {}, replace: true})).toMatchObject({errorCode: "INVALID_PAYLOAD"});
    expect(call("create", {resource: "thing", body: {}, query: {a: "b"}})).toMatchObject({errorCode: "INVALID_PAYLOAD"});
    expect(call("list", "thing")).toMatchObject({errorCode: "INVALID_PAYLOAD"});
  });

  it("never calls out for a refused request", () => {
    calls.length = 0;
    call("list", {resource: "zaak"});
    call("create", {resource: "kind"});
    expect(calls).toHaveLength(0);
  });
});

describe("configuration", () => {
  it("refuses a missing token or a non-http URL without calling out", () => {
    const {calls, transport} = recorder(registerReply(200, {}));
    expect(handleOperation(SPEC, "list", {resource: "thing"}, {registerUrl: "http://x"}, transport))
      .toMatchObject({status: "error", errorCode: "CONFIGURATION_INCOMPLETE"});
    expect(handleOperation(SPEC, "list", {resource: "thing"}, {registerUrl: "ftp://x", registerToken: "t"}, transport))
      .toMatchObject({status: "error", errorCode: "CONFIGURATION_INCOMPLETE"});
    expect(calls).toHaveLength(0);
  });
});

describe("refine", () => {
  it("lets a register refuse or rewrite a request before it goes out", () => {
    const spec: RegisterSpec = {
      ...SPEC,
      refine: (request) => {
        if (request.operation === "create") throw new ContractError("NOPE", "refused");
        return {...request, query: {...request.query, forced: "1"}};
      },
    };
    const {calls, transport} = recorder(registerReply(200, {}));
    expect(handleOperation(spec, "create", {resource: "thing", body: {}}, CONFIG, transport)).toMatchObject({errorCode: "NOPE"});
    handleOperation(spec, "list", {resource: "thing"}, CONFIG, transport);
    expect(calls.map((c) => c.url)).toEqual(["http://register.example:8000/api/v1/thing?forced=1"]);
  });
});

describe("serveContract", () => {
  it("registers exactly the operations of the allowlist and logs failures", () => {
    const handlers = new Map<string, (input: {payload: unknown; configuration: Record<string, unknown>; caller: unknown}) => ContractOutput>();
    const warnings: string[] = [];
    serveContract({...SPEC, resources: {kind: {operations: ["list"]}, thing: {operations: ["get", "create"]}}}, {
      onContractCall: (contract, operation, handler) => handlers.set(`${contract}/${operation}`, handler),
      httpRequest: {request: () => registerReply(200, {})},
      log: {warn: (message) => warnings.push(message)},
    });

    expect([...handlers.keys()]).toEqual(["nl.example.register@2/list", "nl.example.register@2/get", "nl.example.register@2/create"]);
    handlers.get("nl.example.register@2/list")!({payload: {resource: "zaak"}, configuration: CONFIG, caller: {}});
    expect(warnings).toEqual([expect.stringContaining("[UNKNOWN_RESOURCE]")]);
  });

  it("derives the operations in canonical order", () => {
    expect(operationsOf(SPEC)).toEqual(["list", "get", "create", "update", "delete"]);
  });
});
