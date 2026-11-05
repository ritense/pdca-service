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
 * The shared core of the register plugins: one contract per register API, one operation per verb,
 * limited to the resources and operations a register plugin declares in its [RegisterSpec]. A
 * register answer (including 4xx/5xx) is a completed call with `{status, body}`, so the caller can
 * reproduce the register's status codes and validation errors; only failures of the plugin itself
 * (configuration, payload, allowlist, egress, connectivity) are `status: "error"`.
 *
 * This module has no runtime imports, so it bundles into every plugin's Wasm module and runs
 * unchanged in the unit tests; the SDK functions come in through [serveContract].
 */

export const OPERATIONS = ["list", "get", "create", "update", "delete"] as const;
export type Operation = (typeof OPERATIONS)[number];

export interface HttpCall {
  method: string;
  url: string;
  headers: Record<string, string>;
  body?: unknown;
}

/** Shape of the host's `http_request` reply (the SDK's `HttpRequestResponse`). */
export interface HttpReply {
  status: number;
  headers: Record<string, string>;
  body: unknown;
}

export type Transport = (call: HttpCall) => HttpReply;

/** Structurally the SDK's `ContractCallOutput`. */
export interface ContractOutput {
  status: "completed" | "error";
  result?: unknown;
  errorCode?: string;
  errorMessage?: string;
}

/** A validated request, before it becomes an HTTP call. */
export interface ContractRequest {
  operation: Operation;
  resource: string;
  uuid?: string;
  query: Record<string, string>;
  body?: unknown;
}

/** What a register plugin may do with one resource of its API. */
export interface ResourceRule {
  operations: readonly Operation[];
  /** When set, the only query parameters `list` accepts. */
  queryKeys?: readonly string[];
}

export interface RegisterSpec {
  /** Contract id, e.g. `nl.ritense.openplan@2`. */
  contract: string;
  /** Register name in messages, e.g. `Open Plan`. */
  registerName: string;
  /** API path under the configured base URL, e.g. `/plannen/api/v0`. */
  apiPath: string;
  /** Configuration properties with the base URL and the API token. */
  urlProperty: string;
  tokenProperty: string;
  /** Error code for a register that does not answer, e.g. `OPENPLAN_UNREACHABLE`. */
  unreachableCode: string;
  /** The allowlist: every resource the contract serves, with its operations. */
  resources: Readonly<Record<string, ResourceRule>>;
  /** Extra headers on every call (and on calls with a body). */
  headers?: Readonly<Record<string, string>>;
  bodyHeaders?: Readonly<Record<string, string>>;
  /**
   * Register-specific checks after the generic ones: may refuse the request ([ContractError]) or
   * return it changed (e.g. with a forced filter).
   */
  refine?: (request: ContractRequest, configuration: Record<string, unknown>) => ContractRequest;
}

export class ContractError extends Error {
  constructor(readonly errorCode: string, message: string) {
    super(message);
  }
}

/** The operations the contract serves: those of at least one resource, in canonical order. */
export function operationsOf(spec: RegisterSpec): Operation[] {
  const used = new Set(Object.values(spec.resources).flatMap((rule) => rule.operations));
  return OPERATIONS.filter((operation) => used.has(operation));
}

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const NEEDS_UUID: ReadonlySet<Operation> = new Set(["get", "update", "delete"]);
const TAKES_BODY: ReadonlySet<Operation> = new Set(["create", "update"]);
const PAYLOAD_KEYS = new Set(["resource", "uuid", "query", "body"]);

export function handleOperation(
  spec: RegisterSpec,
  operation: Operation,
  payload: unknown,
  configuration: Record<string, unknown>,
  transport: Transport
): ContractOutput {
  try {
    const {baseUrl, token} = readConfiguration(spec, configuration);
    let request = readPayload(spec, operation, payload);
    if (spec.refine) request = spec.refine(request, configuration);
    const reply = transport(buildCall(spec, request, baseUrl, token));
    const failure = hostFailure(spec, reply);
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

/** A required http(s) URL property of the configuration, without trailing slashes. */
export function readUrl(configuration: Record<string, unknown>, property: string): string {
  const value = configuration[property];
  if (typeof value !== "string" || value.trim() === "") {
    throw new ContractError("CONFIGURATION_INCOMPLETE", `The plugin configuration needs '${property}'`);
  }
  if (!/^https?:\/\//i.test(value.trim())) {
    throw new ContractError("CONFIGURATION_INCOMPLETE", `${property} '${value}' is not an http(s) URL`);
  }
  return value.trim().replace(/\/+$/, "");
}

function readConfiguration(spec: RegisterSpec, configuration: Record<string, unknown>): {baseUrl: string; token: string} {
  const baseUrl = readUrl(configuration, spec.urlProperty);
  const token = configuration[spec.tokenProperty];
  if (typeof token !== "string" || token.trim() === "") {
    throw new ContractError("CONFIGURATION_INCOMPLETE", `The plugin configuration needs '${spec.tokenProperty}'`);
  }
  return {baseUrl, token: token.trim()};
}

function readPayload(spec: RegisterSpec, operation: Operation, payload: unknown): ContractRequest {
  if (!isObject(payload)) {
    throw new ContractError("INVALID_PAYLOAD", "The payload must be an object with at least 'resource'");
  }
  const unknownKeys = Object.keys(payload).filter((key) => !PAYLOAD_KEYS.has(key));
  if (unknownKeys.length > 0) {
    throw new ContractError("INVALID_PAYLOAD", `Unknown payload field(s): ${unknownKeys.join(", ")}`);
  }
  const {resource, uuid, query, body} = payload;
  const rule = typeof resource === "string" && Object.prototype.hasOwnProperty.call(spec.resources, resource) ? spec.resources[resource] : undefined;
  if (rule === undefined) {
    throw new ContractError(
      "UNKNOWN_RESOURCE",
      `Unknown resource '${String(resource)}'; expected one of ${Object.keys(spec.resources).join(", ")}`
    );
  }
  if (!rule.operations.includes(operation)) {
    throw new ContractError(
      "OPERATION_NOT_ALLOWED",
      `Operation '${operation}' is not allowed on '${String(resource)}'; allowed: ${rule.operations.join(", ")}`
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
  const parsedQuery = readQuery(query);
  // A listing filters; a get may ask for expansions (Open Klant's `expand`). Writes take no query.
  if (Object.keys(parsedQuery).length > 0 && operation !== "list" && operation !== "get") {
    throw new ContractError("INVALID_PAYLOAD", `Operation '${operation}' takes no 'query'`);
  }
  if (rule.queryKeys) {
    const refused = Object.keys(parsedQuery).filter((key) => !rule.queryKeys!.includes(key));
    if (refused.length > 0) {
      throw new ContractError(
        "QUERY_NOT_ALLOWED",
        `Query parameter(s) ${refused.join(", ")} not allowed on '${resource}'; allowed: ${rule.queryKeys.join(", ")}`
      );
    }
  }
  return {operation, resource: resource as string, uuid: uuid as string | undefined, query: parsedQuery, body};
}

function readQuery(query: unknown): Record<string, string> {
  if (query === undefined || query === null) return {};
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

const METHODS: Record<Operation, string> = {list: "GET", get: "GET", create: "POST", update: "PATCH", delete: "DELETE"};

function buildCall(spec: RegisterSpec, request: ContractRequest, baseUrl: string, token: string): HttpCall {
  const path = `${baseUrl}${spec.apiPath}/${request.resource}${request.uuid ? `/${request.uuid}` : ""}`;
  const queryString = Object.entries(request.query)
    .map(([key, value]) => `${encodeURIComponent(key)}=${encodeURIComponent(value)}`)
    .join("&");
  const call: HttpCall = {
    method: METHODS[request.operation],
    url: queryString ? `${path}?${queryString}` : path,
    headers: {Authorization: `Token ${token}`, Accept: "application/json", ...spec.headers},
  };
  if (TAKES_BODY.has(request.operation)) {
    Object.assign(call.headers, {"Content-Type": "application/json"}, spec.bodyHeaders);
    call.body = request.body ?? {};
  }
  return call;
}

/**
 * Tells a refusal or failure of the host's `http_request` apart from a register answer.
 *
 * The plugin SDK offers no explicit signal for this: the host answers its own refusals (egress,
 * capability, invalid request) and transport failures (connection error, timeout) in the same
 * `{status, headers, body}` shape as a real response, with only a content-type header and a body
 * `{error}`. A response that really came from a register always carries more headers (at least
 * `date` and a length or transfer encoding). This heuristic is kept here, in one place, until the
 * host marks its own replies explicitly.
 */
export function hostFailure(spec: RegisterSpec, reply: HttpReply): {errorCode: string; errorMessage: string} | null {
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
      return {errorCode: "REQUEST_REFUSED", errorMessage: `The plugin host refused the request to ${spec.registerName}: ${message}`};
    case 403:
      return {errorCode: "CAPABILITY_NOT_GRANTED", errorMessage: message};
    case 502:
    case 504:
      return {errorCode: spec.unreachableCode, errorMessage: `${spec.registerName} is unreachable: ${message}`};
    default:
      return {errorCode: "HOST_ERROR", errorMessage: message};
  }
}

/** The SDK functions [serveContract] needs, passed in by the plugin's entry module. */
export interface ContractRuntime {
  onContractCall(
    contract: string,
    operation: string,
    handler: (input: {payload: unknown; configuration: Record<string, unknown>; caller: unknown}) => ContractOutput
  ): void;
  httpRequest: {request(call: HttpCall): HttpReply};
  log: {warn(message: string, data?: Record<string, unknown>): void};
}

/** Registers a handler for every operation of the contract; failures are logged to the configuration's log. */
export function serveContract(spec: RegisterSpec, runtime: ContractRuntime): void {
  const transport: Transport = (call) => runtime.httpRequest.request(call);
  for (const operation of operationsOf(spec)) {
    runtime.onContractCall(spec.contract, operation, (input) => {
      const output = handleOperation(spec, operation, input.payload, input.configuration, transport);
      if (output.status === "error") {
        runtime.log.warn(`${spec.registerName} ${operation} failed: [${output.errorCode}] ${output.errorMessage}`, {
          caller: input.caller,
        });
      }
      return output;
    });
  }
}

export function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
