# Open Klant plugin (`openklant`)

Serves the contract **`nl.ritense.openklant@2`** on an
[Open Klant](https://github.com/maykinmedia/open-klant) instance (Klantinteracties API v1). The
PDCA app keeps the contactmomenten of a plan in it. Build, results, error codes and installation
are shared by the register plugins: see [`../README.md`](../README.md).

## Contract surface

| Resource | Operations |
|---|---|
| `klantcontacten` | `list`, `get`, `update` |
| `maak-klantcontact` | `create` |
| `partijen` | `list`, `create` |

`maak-klantcontact` is Open Klant's convenience endpoint that creates a klantcontact with its
onderwerpobject and betrokkene in one request. `get` takes a query for expansions (e.g.
`expand=gingOverOnderwerpobjecten`). Nothing is deleted.

## Configuration

| Property | |
|---|---|
| `openKlantUrl` | Base URL without `/klantinteracties/api/v1`, as the plugin host reaches it (egress target) |
| `openKlantToken` | Token of an Open Klant API user, sent as `Authorization: Token <token>` (secret) |

A new configuration starts with the local development values: `http://host.docker.internal:7504`
and the dev token the PDCA compose stack provisions. The unreachable code is
`OPENKLANT_UNREACHABLE`.
