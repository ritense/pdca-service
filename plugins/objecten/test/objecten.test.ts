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
import {OBJECTEN} from "../src/objecten";
import manifest from "../manifest.json";
import pkg from "../package.json";

const UUID = "22222222-2222-2222-2222-222222222222";
const OBJECTTYPE = "http://objecttypen.example:8011/api/v2/objecttypes/33333333-3333-3333-3333-333333333333";
const CONFIG = {objectenUrl: "http://objecten.example:8010/", objectenToken: "secret-token", verzoekObjecttypeUrl: OBJECTTYPE};
const VERZOEK = {type: OBJECTTYPE, record: {typeVersion: 1, startAt: "2026-11-02", data: {type: "instrument", data: {}}}};
const reply: HttpReply = {status: 201, headers: {"content-type": "application/json", date: "x"}, body: {uuid: UUID}};

function call(operation: Parameters<typeof handleOperation>[1], payload: unknown, configuration: Record<string, unknown> = CONFIG) {
  const calls: HttpCall[] = [];
  const out = handleOperation(OBJECTEN, operation, payload, configuration, (c) => (calls.push(c), reply));
  return {out, calls};
}

describe("nl.ritense.objecten@2", () => {
  it("declares in its manifest exactly what it serves, with the objecttype as a required property", () => {
    expectManifestMatchesSpec(manifest, OBJECTEN, pkg.version);
    expect(manifest.configurationSchema.required).toContain("verzoekObjecttypeUrl");
  });

  it("always filters a listing on the configured objecttype, with the coordinate system", () => {
    const {calls} = call("list", {resource: "objects", query: {data_attrs: "data__planUuid__exact__p", type: "instrument"}});
    expect(calls).toHaveLength(0);

    const listed = call("list", {resource: "objects", query: {data_attrs: "data__planUuid__exact__p"}}).calls[0];
    expect(listed.url).toBe(
      `http://objecten.example:8010/api/v2/objects?data_attrs=data__planUuid__exact__p&type=${encodeURIComponent(OBJECTTYPE)}`
    );
    expect(listed.headers["Accept-Crs"]).toBe("EPSG:4326");
    expect(listed.headers["Content-Crs"]).toBeUndefined();

    const nextPage = call("list", {resource: "objects", query: {page: "2", type: `${OBJECTTYPE}/`}});
    expect(nextPage.out).toMatchObject({status: "completed"});
  });

  it("refuses a listing of another objecttype or with other filters", () => {
    expect(call("list", {resource: "objects", query: {type: "http://elsewhere/objecttypes/1"}}).out)
      .toMatchObject({errorCode: "OBJECTTYPE_NOT_ALLOWED"});
    expect(call("list", {resource: "objects", query: {date: "2026-01-01"}}).out).toMatchObject({errorCode: "QUERY_NOT_ALLOWED"});
  });

  it("creates objects of the configured objecttype only", () => {
    const {out, calls} = call("create", {resource: "objects", body: VERZOEK});
    expect(out).toEqual({status: "completed", result: {status: 201, body: {uuid: UUID}}});
    expect(calls[0].headers).toMatchObject({"Content-Crs": "EPSG:4326", "Accept-Crs": "EPSG:4326"});

    for (const body of [{...VERZOEK, type: "http://elsewhere/objecttypes/1"}, {record: VERZOEK.record}, undefined]) {
      const refused = call("create", {resource: "objects", body});
      expect(refused.out).toMatchObject({errorCode: "OBJECTTYPE_NOT_ALLOWED"});
      expect(refused.calls).toHaveLength(0);
    }
  });

  it("never reads, changes or deletes single objects", () => {
    expect(call("get", {resource: "objects", uuid: UUID}).out).toMatchObject({errorCode: "OPERATION_NOT_ALLOWED"});
    expect(call("update", {resource: "objects", uuid: UUID, body: VERZOEK}).out).toMatchObject({errorCode: "OPERATION_NOT_ALLOWED"});
    expect(call("delete", {resource: "objects", uuid: UUID}).out).toMatchObject({errorCode: "OPERATION_NOT_ALLOWED"});
    expect(call("list", {resource: "objecttypes"}).out).toMatchObject({errorCode: "UNKNOWN_RESOURCE"});
  });

  it("needs the objecttype in its configuration", () => {
    const {out, calls} = call("list", {resource: "objects"}, {objectenUrl: "http://objecten.example", objectenToken: "t"});
    expect(out).toMatchObject({errorCode: "CONFIGURATION_INCOMPLETE"});
    expect(calls).toHaveLength(0);
  });
});
