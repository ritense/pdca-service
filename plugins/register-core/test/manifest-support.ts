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

import {expect} from "vitest";
import {operationsOf, type RegisterSpec} from "../src/contract";

interface Manifest {
  version: string;
  translations: Record<string, Record<string, string>>;
  configurationSchema: {properties: Record<string, Record<string, unknown>>; required: string[]};
  provides: {contract: string; operations: {key: string}[]}[];
}

/**
 * Asserts that a register plugin's manifest declares exactly what its spec serves: the contract,
 * its operations (with a title per language), the configuration properties the core reads, and
 * the base URL as the only egress target.
 */
export function expectManifestMatchesSpec(manifest: Manifest, spec: RegisterSpec, packageVersion: string): void {
  const operations = operationsOf(spec);
  expect(manifest.version).toBe(packageVersion);
  expect(manifest.provides.map((p) => p.contract)).toEqual([spec.contract]);
  expect(manifest.provides[0].operations.map((o) => o.key)).toEqual(operations);
  for (const [language, texts] of Object.entries(manifest.translations)) {
    const declared = Object.keys(texts)
      .filter((key) => key.startsWith(`contracts.${spec.contract}.operations.`))
      .map((key) => key.split(".operations.")[1].replace(/\.title$/, ""));
    expect(declared, `operation titles in '${language}'`).toEqual(operations);
    expect(Object.keys(texts).filter((key) => key.startsWith("contracts.") && !key.startsWith(`contracts.${spec.contract}.`)))
      .toEqual([]);
  }
  expect(manifest.configurationSchema.required).toEqual(expect.arrayContaining([spec.urlProperty, spec.tokenProperty]));
  expect(manifest.configurationSchema.properties[spec.tokenProperty]["x-secret"]).toBe(true);
  const egress = Object.entries(manifest.configurationSchema.properties)
    .filter(([, property]) => property["x-egress-target"] === true)
    .map(([key]) => key);
  expect(egress).toEqual([spec.urlProperty]);
}
