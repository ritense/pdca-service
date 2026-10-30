# Objecten API plugin (`objecten`)

A Valtimo external plugin (Wasm, built with `@valtimo/plugin-sdk`) that gives other plugins and
apps access to an [Objecten API](https://github.com/maykinmedia/objects-api) (Objects API v2)
through the contract **`nl.ritense.objecten@1`**. The PDCA app consumes this contract to file the
**verzoeken** of its product requests (see [`ARCHITECTURE.md`](../../ARCHITECTURE.md) §9 and §6.3).

The plugin holds the Objecten API URL and token; callers never see either. Every call is brokered
by GZAC: the caller names the contract, the GZAC administrator binds it to a configuration of this
plugin.

## Contract `nl.ritense.objecten@1`

Five synchronous operations on the `objects` resource of the Objects API v2:

| Operation | Objecten API request |
|---|---|
| `list` | `GET {objectenUrl}/api/v2/objects?<query>` |
| `get` | `GET .../objects/<uuid>` |
| `create` | `POST .../objects` with `body` |
| `update` | `PATCH .../objects/<uuid>` with `body` (`PUT` when `replace` is true) |
| `delete` | `DELETE .../objects/<uuid>` |

Payload: `{resource, uuid?, query?: {name: value}, body?, replace?}`. `resource` is always
`objects`; `uuid` is required (and must be a UUID) for `get`, `update` and `delete`. Every request
carries `Accept-Crs: EPSG:4326`, every write also `Content-Crs: EPSG:4326` — the Objects API
insists on knowing the coordinate system even for objects without geometry. Objecttypes are not
part of the contract: they live in the Objecttypen API and are managed by administrators.

Result: `{status, body}` — the Objecten API's HTTP status and its parsed JSON body (`null` when
empty). A 4xx or 5xx from the register is a normal result, so callers can show its own validation
errors. Failures of the plugin itself come back as a contract error with one of these codes:

| `errorCode` | Meaning |
|---|---|
| `CONFIGURATION_INCOMPLETE` | `objectenUrl` or `objectenToken` is missing |
| `INVALID_PAYLOAD`, `UNKNOWN_RESOURCE`, `INVALID_UUID` | The payload does not fit the operation |
| `REQUEST_REFUSED` | The plugin host refused the outbound request (egress or network policy) |
| `OBJECTEN_UNREACHABLE` | The Objecten API did not answer |
| `CAPABILITY_NOT_GRANTED`, `HOST_ERROR` | The configuration lacks `http_request`, or the host failed |

GZAC reports these to the caller as `PROVIDER_ERROR`, with the plugin's code in the message.

## Build

Requires Node.js 22+ and a checkout of the Valtimo monorepo next to this repository
(`../valtimo`), because the SDK dependency points at `../../../valtimo/plugin-host/plugin-sdk`.
The Wasm toolchain (extism-js, binaryen) is downloaded on first build.

From the repository root, one command builds the SDK, installs the dependencies and packs the
plugin:

```bash
./gradlew buildObjectenPlugin   # -> plugins/objecten/dist/objecten-1.0.0.zip
```

`./gradlew buildPlugins` builds this plugin together with the other register plugins.

Node is taken from `-PnodeDir=<node install dir>`, else the newest nvm-installed Node 22+, else
`node` on the PATH; `-PvaltimoDir=<path>` points at a Valtimo checkout elsewhere. By hand:

```bash
cd plugins/objecten
npm install
npm test               # unit tests of the request mapping
npm run build:pack     # -> dist/objecten-1.0.0.zip
```

## Install and configure

1. **Upload** `dist/objecten-1.0.0.zip` in GZAC: *Admin → Plugins → Upload plugin*, onto a plugin
   host.
2. **Configure** the plugin and accept its permissions (`http_request`, `log`). The plugin's own
   configuration screen (`frontend/config.tsx`, React with Carbon) asks for a name, the Objecten
   API URL and the API token. A new configuration starts with the values of the local GZAC stack
   (`http://host.docker.internal:8010` and the token of its Objecten API fixtures); *Use local
   development values* restores them. When editing, the stored token is never shown: leave the
   field empty to keep it.

   `objectenUrl` is the address **the plugin host** uses to reach the Objecten API, without
   `/api/v2`. The URL is an egress target: entering it is what allows the plugin to call it. Plain
   `http` and private addresses need a plugin host that allows them (`HOST_ALLOW_HTTP`, and
   `HOST_ALLOWED_INTERNAL_CIDRS` or `HOST_ALLOW_PRIVATE_NETWORK`); the Valtimo dev compose stack
   does. The token must be allowed to write objects of the verzoek objecttype (token permissions in
   the Objecten API admin).
3. **Bind** a consumer to it: when activating (or editing) a configuration that declares
   `nl.ritense.objecten@1`, such as PDCA, pick this configuration under *Required plugins*. With
   exactly one Objecten API configuration GZAC preselects it.

Calls and failures appear in the configuration's *Logs* in GZAC.
