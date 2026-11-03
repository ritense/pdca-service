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
import {configurationData, fieldValid, type ConfigField} from "../frontend/config-fields";

const FIELDS: ConfigField[] = [
  {key: "registerUrl", kind: "baseUrl", localDefault: "http://localhost:1"},
  {key: "registerToken", kind: "secret", localDefault: "t"},
  {key: "typeUrl", kind: "url", localDefault: "http://localhost:2/types/1"},
];

describe("configuration screen logic", () => {
  it("trims URLs, strips a base URL's trailing slashes and only sends an entered secret", () => {
    expect(configurationData(FIELDS, {registerUrl: " http://r.example/// ", registerToken: " ", typeUrl: "http://t.example/x/ "}))
      .toEqual({registerUrl: "http://r.example", typeUrl: "http://t.example/x/"});
    expect(configurationData(FIELDS, {registerUrl: "http://r", registerToken: " tok ", typeUrl: "http://t"}))
      .toMatchObject({registerToken: "tok"});
  });

  it("requires http(s) URLs, and a secret unless an existing configuration is edited", () => {
    expect(fieldValid(FIELDS[0], "ftp://r.example", false)).toBe(false);
    expect(fieldValid(FIELDS[0], "https://r.example", false)).toBe(true);
    expect(fieldValid(FIELDS[2], "not a url", false)).toBe(false);
    expect(fieldValid(FIELDS[1], "", false)).toBe(false);
    expect(fieldValid(FIELDS[1], "", true)).toBe(true);
  });
});
