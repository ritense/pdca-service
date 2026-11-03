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

/**
 * One property of a register plugin's configuration. `baseUrl` and `url` are http(s) URLs (a base
 * URL loses its trailing slashes); a `secret` is never sent back by GZAC, so when editing an empty
 * field keeps the stored value. Labels come from the manifest translations
 * `config.<key>.label|help|invalid|required|keep|show|hide`.
 */
export interface ConfigField {
  key: string;
  kind: "baseUrl" | "url" | "secret";
  placeholder?: string;
  /** Value of the local development stack, filled in for a new configuration. */
  localDefault: string;
}

export interface ConfigFormSpec {
  /** Configuration name for a new configuration. */
  defaultTitle: string;
  fields: ConfigField[];
}

export function isHttpUrl(value: string): boolean {
  try {
    const url = new URL(value);
    return url.protocol === "http:" || url.protocol === "https:";
  } catch {
    return false;
  }
}

export function fieldValid(field: ConfigField, value: string, editing: boolean): boolean {
  if (field.kind === "secret") return editing || value.trim().length > 0;
  return isHttpUrl(value.trim());
}

/** The configuration properties to save: URLs trimmed, secrets only when entered. */
export function configurationData(fields: ConfigField[], values: Record<string, string>): Record<string, unknown> {
  const data: Record<string, unknown> = {};
  for (const field of fields) {
    const value = (values[field.key] ?? "").trim();
    if (field.kind === "secret") {
      if (value) data[field.key] = value;
    } else {
      data[field.key] = field.kind === "baseUrl" ? value.replace(/\/+$/, "") : value;
    }
  }
  return data;
}
