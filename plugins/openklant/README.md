# Open Klant plugin (`openklant`)

A Valtimo external plugin (Wasm, built with `@valtimo/plugin-sdk`) that gives other plugins and
apps access to an [Open Klant](https://github.com/maykinmedia/open-klant) instance through the
contract **`nl.ritense.openklant@1`**. The PDCA app consumes this contract for all of its Open Klant
traffic (see [`ARCHITECTURE.md`](../../ARCHITECTURE.md) §6.3).

The plugin holds the Open Klant URL and API token; callers never see either. Every call is brokered
by GZAC: the caller names the contract, the GZAC administrator binds it to a configuration of this
plugin.

## Contract `nl.ritense.openklant@1`

Five synchronous operations over the Klantinteracties API v1:

| Operation | Open Klant request |
|---|---|
| `list` | `GET {openKlantUrl}/klantinteracties/api/v1/<resource>?<query>` |
| `get` | `GET .../<resource>/<uuid>` |
| `create` | `POST .../<resource>` with `body` |
| `update` | `PATCH .../<resource>/<uuid>` with `body` (`PUT` when `replace` is true) |
| `delete` | `DELETE .../<resource>/<uuid>` |

Payload: `{resource, uuid?, query?: {name: value}, body?, replace?}`. `resource` is one of `klantcontacten`, `maak-klantcontact`, `betrokkenen`, `onderwerpobjecten`, `bijlagen`, `partijen`, `partij-identificatoren`, `vertegenwoordigingen`, `actoren`, `actorklantcontacten`, `internetaken`, `digitaleadressen`, `rekeningnummers`, `categorieen` and `categorie-relaties`; `uuid` is required (and must be a UUID) for `get`, `update` and `delete`.
`maak-klantcontact` is Open Klant's convenience endpoint that creates a klantcontact with its
onderwerpobject and betrokkene in one request; it only answers to `create`.

Result: `{status, body}` — Open Klant's HTTP status and its parsed JSON body (`null` when empty).
A 4xx or 5xx from Open Klant is a normal result, so callers can show Open Klant's own validation
errors. Failures of the plugin itself come back as a contract error with one of these codes:

| `errorCode` | Meaning |
|---|---|
| `CONFIGURATION_INCOMPLETE` | `openKlantUrl` or `openKlantToken` is missing |
| `INVALID_PAYLOAD`, `UNKNOWN_RESOURCE`, `INVALID_UUID` | The payload does not fit the operation |
| `REQUEST_REFUSED` | The plugin host refused the outbound request (egress or network policy) |
| `OPENKLANT_UNREACHABLE` | Open Klant did not answer |
| `CAPABILITY_NOT_GRANTED`, `HOST_ERROR` | The configuration lacks `http_request`, or the host failed |

GZAC reports these to the caller as `PROVIDER_ERROR`, with the plugin's code in the message.

## Build

Requires Node.js 22+ and a checkout of the Valtimo monorepo next to this repository
(`../valtimo`), because the SDK dependency points at `../../../valtimo/plugin-host/plugin-sdk`
. The Wasm toolchain (extism-js, binaryen) is downloaded on first build.

From the repository root, one command builds the SDK, installs the dependencies and packs the
plugin:

```bash
./gradlew buildOpenKlantPlugin   # -> plugins/openklant/dist/openklant-1.0.0.zip
```

`./gradlew buildPlugins` builds this plugin together with the other register plugins.

Node is taken from `-PnodeDir=<node install dir>`, else the newest nvm-installed Node 22+, else
`node` on the PATH; `-PvaltimoDir=<path>` points at a Valtimo checkout elsewhere. By hand:

```bash
cd plugins/openklant
npm install
npm test               # unit tests of the request mapping
npm run build:pack     # -> dist/openklant-1.0.0.zip
```

## Install and configure

1. **Upload** `dist/openklant-1.0.0.zip` in GZAC: *Admin → Plugins → Upload plugin*, onto a plugin
   host.
2. **Configure** the plugin and accept its permissions (`http_request`, `log`). The plugin's own
   configuration screen (`frontend/config.tsx`, React with Carbon) asks for a name, the Open Klant
   URL and the API token. A new configuration starts with the local development values
   (`http://host.docker.internal:7504` and the dev token); *Use local development values* restores
   them. When editing, the stored token is never shown: leave the field empty to keep it.

   `openKlantUrl` is the address **the plugin host** uses to reach Open Klant, without
   `/klantinteracties/api/v1`. For the PDCA dev stack (Open Klant on host port 7504, plugin host in Docker)
   that is `http://host.docker.internal:7504` with the dev token the compose init provisions
   (`docker/openklant/init.py`). The URL is an egress target:
   entering it is what allows the plugin to call it. Plain `http` and private addresses need a
   plugin host that allows them (`HOST_ALLOW_HTTP`, and `HOST_ALLOWED_INTERNAL_CIDRS` or
   `HOST_ALLOW_PRIVATE_NETWORK`); the Valtimo dev compose stack does.
3. **Bind** a consumer to it: when activating (or editing) a configuration that declares
   `nl.ritense.openklant@1`, such as PDCA, pick this configuration under *Required plugins*. With
   exactly one Open Klant configuration GZAC preselects it.

Calls and failures appear in the configuration's *Logs* in GZAC.
