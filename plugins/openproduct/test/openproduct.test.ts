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
import {OPENPRODUCT} from "../src/openproduct";
import manifest from "../manifest.json";
import pkg from "../package.json";

const UUID = "11111111-1111-1111-1111-111111111111";
const CONFIG = {openProductUrl: "http://openproduct.example:7502", openProductToken: "secret-token"};
const reply: HttpReply = {status: 200, headers: {"content-type": "application/json", date: "x"}, body: {}};

function call(operation: Parameters<typeof handleOperation>[1], payload: unknown) {
  const calls: HttpCall[] = [];
  const out = handleOperation(OPENPRODUCT, operation, payload, CONFIG, (c) => (calls.push(c), reply));
  return {out, calls};
}

describe("nl.ritense.openproduct@2", () => {
  it("declares in its manifest exactly what it serves", () => {
    expectManifestMatchesSpec(manifest, OPENPRODUCT, pkg.version);
  });

  it("reads producttypen and their acties from the Producttypen API v1", () => {
    expect(call("get", {resource: "producttypen", uuid: UUID}).calls[0].url)
      .toBe(`http://openproduct.example:7502/producttypen/api/v1/producttypen/${UUID}`);
    expect(call("list", {resource: "acties", query: {producttype__uuid: UUID}}).calls[0].url)
      .toBe(`http://openproduct.example:7502/producttypen/api/v1/acties?producttype__uuid=${UUID}`);
  });

  it("is read-only and limited to producttypen and acties", () => {
    expect(call("create", {resource: "producttypen", body: {}}).out).toMatchObject({errorCode: "OPERATION_NOT_ALLOWED"});
    expect(call("update", {resource: "producttypen", uuid: UUID, body: {}}).out).toMatchObject({errorCode: "OPERATION_NOT_ALLOWED"});
    expect(call("delete", {resource: "producttypen", uuid: UUID}).out).toMatchObject({errorCode: "OPERATION_NOT_ALLOWED"});
    expect(call("get", {resource: "acties", uuid: UUID}).out).toMatchObject({errorCode: "OPERATION_NOT_ALLOWED"});
    for (const resource of ["prijzen", "content", "themas", "links", "schemas"]) {
      expect(call("list", {resource}).out).toMatchObject({errorCode: "UNKNOWN_RESOURCE"});
    }
  });
});
