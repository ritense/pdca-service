# Register plugins

Four Valtimo external plugins (Wasm, built with `@valtimo/plugin-sdk`) that give the PDCA app
access to the registers through GZAC's contract broker (see [`ARCHITECTURE.md`](../ARCHITECTURE.md)
§3.3). Each plugin holds its register's URL and token; callers never see either. The caller names
the contract, and the GZAC administrator binds it to a configuration of the plugin.

| Plugin | Contract | Register API | Surface |
|---|---|---|---|
| [`openplan`](openplan/README.md) | `nl.ritense.openplan@2` | Open Plan, Plannen API v0 | the plan data the app manages, reference data read-only |
| [`openproduct`](openproduct/README.md) | `nl.ritense.openproduct@2` | Open Product, Producttypen API v1 | read-only: producttypen and their acties |
| [`openklant`](openklant/README.md) | `nl.ritense.openklant@2` | Open Klant, Klantinteracties API v1 | klantcontacten and partijen, no deletes |
| [`objecten`](objecten/README.md) | `nl.ritense.objecten@2` | Objecten API, Objects API v2 | list and create, confined to one configured objecttype |

## The shared core

`register-core/` holds everything the plugins have in common; a plugin itself is only its
manifest, its `RegisterSpec` (`src/<name>.ts`: contract id, API path, configuration properties and
the allowlist) and the fields of its configuration screen (`ui/config.tsx`).

- **`src/contract.ts`** — the contract handler. An operation (`list`, `get`, `create`, `update`,
  `delete`) maps onto one HTTP verb (`GET`, `GET`, `POST`, `PATCH`, `DELETE`) on
  `<url><apiPath>/<resource>[/<uuid>][?<query>]`. The payload is
  `{resource, uuid?, query?: {name: value}, body?}`; `uuid` is required (and must be a UUID) for
  `get`, `update` and `delete`, `body` only goes with `create` and `update`, `query` only with
  `list` and `get`. The **allowlist** names every resource with the operations it allows (and,
  optionally, the only query parameters its listing accepts); anything else is refused before a
  request leaves the plugin. A plugin can add its own check (`refine`), as `objecten` does.
- **`frontend/config-form.tsx`** — the configuration screen (React with Carbon): a name plus the
  plugin's fields (base URL, token, other URLs), prefilled with the local development values; when
  editing, the stored token is never shown and an empty field keeps it.

A contract only declares the operations its allowlist uses (`operationsOf`), and each plugin's
tests check that its manifest declares exactly those. A smaller surface is a breaking change for
callers, so it gets a new contract major (`@2`) and plugin version.

### Results and errors

Result: `{status, body}` — the register's HTTP status and its parsed JSON body (`null` when empty).
A 4xx or 5xx from the register is a normal result, so callers can show the register's own
validation errors. Failures of the plugin itself come back as a contract error:

| `errorCode` | Meaning |
|---|---|
| `CONFIGURATION_INCOMPLETE` | A required configuration property is missing or not an http(s) URL |
| `INVALID_PAYLOAD`, `INVALID_UUID` | The payload does not fit the operation |
| `UNKNOWN_RESOURCE`, `OPERATION_NOT_ALLOWED`, `QUERY_NOT_ALLOWED` | Outside the contract's allowlist |
| `OBJECTTYPE_NOT_ALLOWED` | (`objecten`) an object of another objecttype than the configured one |
| `REQUEST_REFUSED` | The plugin host refused the outbound request (egress or network policy) |
| `<REGISTER>_UNREACHABLE` | The register did not answer (`OPENPLAN_UNREACHABLE`, …) |
| `CAPABILITY_NOT_GRANTED`, `HOST_ERROR` | The configuration lacks `http_request`, or the host failed |

GZAC reports these to the caller as `PROVIDER_ERROR`, with the plugin's code in the message.

The plugin host answers its own refusals and transport failures of `http_request` in the same
`{status, headers, body}` shape as a register response; the SDK has no field that marks them.
`hostFailure` in the core recognises them by their shape (only a content-type header and a body
`{error}`), which a real register response never has.

## Build

Requires Node.js 22+ and a checkout of the Valtimo monorepo next to this repository
(`../valtimo`, or `-PvaltimoDir=<path>`): the plugins build against the plugin SDK of that
checkout until the SDK is published. The workspace depends on it as
`file:../build/valtimo-plugin-sdk`, a link that the Gradle task `pluginSdkLink` points at
`<valtimoDir>/plugin-host/plugin-sdk`. The Wasm toolchain (extism-js, binaryen) is downloaded on
first build. The core and the plugins are one npm workspace (`package.json` here, one
`package-lock.json`, one install).

From the repository root:

```bash
./gradlew buildPlugins          # -> plugins/<name>/dist/<name>-2.0.0.zip, all four
./gradlew buildOpenPlanPlugin   # one plugin (also OpenProduct, OpenKlant, Objecten)
./gradlew testPlugins           # unit tests of the core and the plugins
```

These link and build the SDK, install the workspace and pack each plugin; `testPlugins` is also
part of `./gradlew check` whenever the SDK is present. Node is taken from
`-PnodeDir=<node install dir>`, else the newest nvm-installed Node 22+, else `node` on the PATH.

Packing a plugin (`npm run build:pack`) builds the Wasm module (`build`), then the configuration
screen (`build:frontend`: `register-core/build-frontend.mjs` bundles `ui/config.tsx` minified, with
React's production build, into `frontend/config.bundle.{js,css}`, which `frontend/config.html`
loads), and finally packs both (`pack`, `valtimo-plugin-pack`). By hand:

```bash
./gradlew pluginSdkLink pluginSdkBuild   # once: link and build the SDK
cd plugins
npm install
npm test                # all tests
npm run typecheck       # tsc over the core and the plugins
cd openplan && npm run build:pack   # -> dist/openplan-2.0.0.zip
```

## Install and configure

1. **Upload** `dist/<name>-2.0.0.zip` in GZAC: *Admin → Plugins → Upload plugin*, onto a plugin
   host. (For the local GZAC stack `./gradlew gzacDevSetup` does this, see `gzac/dev-setup/README.md`.)
2. **Configure** the plugin and accept its permissions (`http_request`, `log`). The base URL is the
   address **the plugin host** uses to reach the register, without the API path. It is the only
   egress target: entering it is what allows the plugin to call it. Plain `http` and private
   addresses need a plugin host that allows them (`HOST_ALLOW_HTTP`, and
   `HOST_ALLOWED_INTERNAL_CIDRS` or `HOST_ALLOW_PRIVATE_NETWORK`); the Valtimo dev compose stack
   does.
3. **Bind** a consumer to it: when activating (or editing) a configuration that declares the
   contract, such as PDCA, pick this configuration under *Required plugins*. With exactly one
   configuration of the plugin GZAC preselects it.

Calls and failures appear in the configuration's *Logs* in GZAC.
