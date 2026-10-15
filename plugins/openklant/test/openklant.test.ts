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
import {handleOperation, type HttpCall, type HttpReply} from "../src/openklant";

const KLANTCONTACT_UUID = "33333333-3333-3333-3333-333333333333";
const PLAN_UUID = "11111111-1111-1111-1111-111111111111";
const CONFIG = {openKlantUrl: "http://openklant.example:7504/", openKlantToken: "secret-token"};

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
  it("lists klantcontacten by onderwerpobject with the token header", () => {
    const {calls, transport} = recorder(registerReply(200, {count: 0, results: []}));
    const out = handleOperation(
      "list",
      {
        resource: "klantcontacten",
        query: {
          onderwerpobject__onderwerpobjectidentificatorObjectId: PLAN_UUID,
          onderwerpobject__onderwerpobjectidentificatorCodeRegister: "open-plan",
        },
      },
      CONFIG,
      transport
    );

    expect(out).toEqual({status: "completed", result: {status: 200, body: {count: 0, results: []}}});
    expect(calls[0].method).toBe("GET");
    expect(calls[0].url).toBe(
      "http://openklant.example:7504/klantinteracties/api/v1/klantcontacten" +
        `?onderwerpobject__onderwerpobjectidentificatorObjectId=${PLAN_UUID}` +
        "&onderwerpobject__onderwerpobjectidentificatorCodeRegister=open-plan"
    );
    expect(calls[0].headers.Authorization).toBe("Token secret-token");
  });

  it("creates through maak-klantcontact", () => {
    const {calls, transport} = recorder(registerReply(201, {klantcontact: {uuid: KLANTCONTACT_UUID}}));
    const body = {klantcontact: {kanaal: "gesprek"}, onderwerpobject: {}};
    const out = handleOperation("create", {resource: "maak-klantcontact", body}, CONFIG, transport);

    expect(calls[0]).toMatchObject({
      method: "POST",
      url: "http://openklant.example:7504/klantinteracties/api/v1/maak-klantcontact",
      body,
    });
    expect(out.result).toEqual({status: 201, body: {klantcontact: {uuid: KLANTCONTACT_UUID}}});
  });

  it("patches a klantcontact", () => {
    const {calls, transport} = recorder(registerReply(200, {}));
    handleOperation(
      "update",
      {resource: "klantcontacten", uuid: KLANTCONTACT_UUID, body: {plaatsgevondenOp: "2026-10-15T10:00:00Z"}},
      CONFIG,
      transport
    );
    expect(calls[0]).toMatchObject({
      method: "PATCH",
      url: `http://openklant.example:7504/klantinteracties/api/v1/klantcontacten/${KLANTCONTACT_UUID}`,
    });
  });
});

describe("register answers and failures", () => {
  it("passes a register validation error through as a completed call", () => {
    const error = {code: "invalid", invalidParams: [{name: "kanaal", reason: "Dit veld is vereist."}]};
    const out = handleOperation(
      "create",
      {resource: "maak-klantcontact", body: {}},
      CONFIG,
      recorder(registerReply(400, error)).transport
    );
    expect(out).toEqual({status: "completed", result: {status: 400, body: error}});
  });

  it("reports an unreachable register", () => {
    const hostReply: HttpReply = {status: 504, headers: {"content-type": "application/json"}, body: {error: "timeout"}};
    const out = handleOperation("list", {resource: "partijen"}, CONFIG, recorder(hostReply).transport);
    expect(out).toMatchObject({status: "error", errorCode: "OPENKLANT_UNREACHABLE"});
  });

  it("refuses a missing configuration without calling out", () => {
    const {calls, transport} = recorder(registerReply(200, {}));
    const out = handleOperation("list", {resource: "partijen"}, {openKlantToken: "t"}, transport);
    expect(out).toMatchObject({status: "error", errorCode: "CONFIGURATION_INCOMPLETE"});
    expect(calls).toHaveLength(0);
  });

  it("refuses resources outside the Klantinteracties API and invalid uuids", () => {
    const {transport} = recorder(registerReply(200, {}));
    expect(handleOperation("list", {resource: "producttypen"}, CONFIG, transport)).toMatchObject({errorCode: "UNKNOWN_RESOURCE"});
    expect(handleOperation("get", {resource: "partijen", uuid: "../x"}, CONFIG, transport)).toMatchObject({
      errorCode: "INVALID_UUID",
    });
  });
});
