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

import type {ContractCallOutput} from "@valtimo/plugin-sdk";

/**
 * The `nl.ritense.openproduct@1` contract: CRUD on the Open Product Producttypen API v1, one operation per
 * verb. A register answer (including 4xx/5xx) is a completed call with `{status, body}`, so the
 * caller can reproduce Open Product's status codes and validation errors; only failures of the
 * plugin itself (configuration, payload, egress, connectivity) are `status: "error"`.
 */

export const OPENPRODUCT_CONTRACT = "nl.ritense.openproduct@1";

export const RESOURCES = [
  "producttypen",
  "themas",
  "prijzen",
  "links",
  "acties",
  "content",
  "contentlabels",
  "schemas",
] as const;

export const OPERATIONS = ["list", "get", "create", "update", "delete"] as const;
export type Operation = (typeof OPERATIONS)[number];

export interface HttpCall {
  method: string;
  url: string;
  headers: Record<string, string>;
  body?: unknown;
}

/** Shape of the host's `http_request` reply. */
export interface HttpReply {
  status: number;
  headers: Record<string, string>;
  body: unknown;
}

export type Transport = (call: HttpCall) => HttpReply;

const API_PATH = "/producttypen/api/v1";
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const NEEDS_UUID: ReadonlySet<Operation> = new Set(["get", "update", "delete"]);
const TAKES_BODY: ReadonlySet<Operation> = new Set(["create", "update"]);

interface Payload {
  resource: string;
  uuid?: string;
  query?: Record<string, string>;
  body?: unknown;
  replace?: boolean;
}

class ContractError extends Error {
  constructor(readonly errorCode: string, message: string) {
    super(message);
  }
}

export function handleOperation(
  operation: Operation,
  payload: unknown,
  configuration: Record<string, unknown>,
  transport: Transport
): ContractCallOutput {
  try {
    const {baseUrl, token} = readConfiguration(configuration);
    const request = readPayload(operation, payload);
    const reply = transport(buildCall(operation, request, baseUrl, token));
    const failure = hostFailure(reply);
    if (failure) return {status: "error", ...failure};
    return {
      status: "completed",
      result: {status: reply.status, body: reply.body === "" || reply.body === undefined ? null : reply.body},
    };
  } catch (err) {
    if (err instanceof ContractError) {
      return {status: "error", errorCode: err.errorCode, errorMessage: err.message};
    }
    throw err;
  }
}

function readConfiguration(configuration: Record<string, unknown>): {baseUrl: string; token: string} {
  const url = configuration.openProductUrl;
  const token = configuration.openProductToken;
  if (typeof url !== "string" || url.trim() === "" || typeof token !== "string" || token.trim() === "") {
    throw new ContractError(
      "CONFIGURATION_INCOMPLETE",
      "The plugin configuration needs both openProductUrl and openProductToken"
    );
  }
  if (!/^https?:\/\//i.test(url.trim())) {
    throw new ContractError("CONFIGURATION_INCOMPLETE", `openProductUrl '${url}' is not an http(s) URL`);
  }
  return {baseUrl: url.trim().replace(/\/+$/, ""), token: token.trim()};
}

function readPayload(operation: Operation, payload: unknown): Payload {
  if (!isObject(payload)) {
    throw new ContractError("INVALID_PAYLOAD", "The payload must be an object with at least 'resource'");
  }
  const {resource, uuid, query, body, replace} = payload;
  if (typeof resource !== "string" || !(RESOURCES as readonly string[]).includes(resource)) {
    throw new ContractError(
      "UNKNOWN_RESOURCE",
      `Unknown resource '${String(resource)}'; expected one of ${RESOURCES.join(", ")}`
    );
  }
  if (NEEDS_UUID.has(operation)) {
    if (typeof uuid !== "string" || !UUID_PATTERN.test(uuid)) {
      throw new ContractError("INVALID_UUID", `Operation '${operation}' needs a valid 'uuid', got '${String(uuid)}'`);
    }
  } else if (uuid !== undefined) {
    throw new ContractError("INVALID_PAYLOAD", `Operation '${operation}' takes no 'uuid'`);
  }
  if (body !== undefined && !TAKES_BODY.has(operation)) {
    throw new ContractError("INVALID_PAYLOAD", `Operation '${operation}' takes no 'body'`);
  }
  if (replace !== undefined && (operation !== "update" || typeof replace !== "boolean")) {
    throw new ContractError("INVALID_PAYLOAD", "'replace' is a boolean and only allowed on 'update'");
  }
  return {resource, uuid: uuid as string | undefined, query: readQuery(query), body, replace: replace as boolean | undefined};
}

function readQuery(query: unknown): Record<string, string> | undefined {
  if (query === undefined || query === null) return undefined;
  if (!isObject(query)) {
    throw new ContractError("INVALID_PAYLOAD", "'query' must be an object of strings");
  }
  const result: Record<string, string> = {};
  for (const [key, value] of Object.entries(query)) {
    if (typeof value !== "string" && typeof value !== "number" && typeof value !== "boolean") {
      throw new ContractError("INVALID_PAYLOAD", `Query parameter '${key}' must be a string`);
    }
    result[key] = String(value);
  }
  return result;
}

function buildCall(operation: Operation, request: Payload, baseUrl: string, token: string): HttpCall {
  const path = `${baseUrl}${API_PATH}/${request.resource}${request.uuid ? `/${request.uuid}` : ""}`;
  const queryString = Object.entries(request.query ?? {})
    .map(([key, value]) => `${encodeURIComponent(key)}=${encodeURIComponent(value)}`)
    .join("&");
  const call: HttpCall = {
    method: methodFor(operation, request.replace === true),
    url: queryString ? `${path}?${queryString}` : path,
    headers: {Authorization: `Token ${token}`, Accept: "application/json"},
  };
  if (TAKES_BODY.has(operation)) {
    call.headers["Content-Type"] = "application/json";
    call.body = request.body ?? {};
  }
  return call;
}

function methodFor(operation: Operation, replace: boolean): string {
  switch (operation) {
    case "list":
    case "get":
      return "GET";
    case "create":
      return "POST";
    case "update":
      return replace ? "PUT" : "PATCH";
    case "delete":
      return "DELETE";
  }
}

/**
 * Tells a refusal or failure of the host's `http_request` apart from a register answer. The host
 * builds those replies itself with only a content-type header and a body `{error}`; a response that
 * really came from Open Product always carries more headers.
 */
export function hostFailure(reply: HttpReply): {errorCode: string; errorMessage: string} | null {
  const headerNames = Object.keys(reply.headers ?? {}).map((name) => name.toLowerCase());
  const body = reply.body;
  const isHostReply =
    headerNames.length === 1 &&
    headerNames[0] === "content-type" &&
    isObject(body) &&
    Object.keys(body).length === 1 &&
    typeof body.error === "string";
  if (!isHostReply) return null;
  const message = (body as {error: string}).error;
  switch (reply.status) {
    case 400:
      return {errorCode: "REQUEST_REFUSED", errorMessage: `The plugin host refused the request to Open Product: ${message}`};
    case 403:
      return {errorCode: "CAPABILITY_NOT_GRANTED", errorMessage: message};
    case 502:
    case 504:
      return {errorCode: "OPENPRODUCT_UNREACHABLE", errorMessage: `Open Product is unreachable: ${message}`};
    default:
      return {errorCode: "HOST_ERROR", errorMessage: message};
  }
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
