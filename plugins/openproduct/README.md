# Open Product plugin (`openproduct`)

Serves the contract **`nl.ritense.openproduct@2`** on an
[Open Product](https://github.com/maykinmedia/open-product) instance (Producttypen API v1). The
PDCA app reads the requestable products and their start recipes through it. Build, results, error
codes and installation are shared by the register plugins: see [`../README.md`](../README.md).

## Contract surface

Read-only:

| Resource | Operations |
|---|---|
| `producttypen` | `list`, `get` |
| `acties` | `list` |

## Configuration

| Property | |
|---|---|
| `openProductUrl` | Base URL without `/producttypen/api/v1`, as the plugin host reaches it (egress target) |
| `openProductToken` | Token of an Open Product API user, sent as `Authorization: Token <token>` (secret) |

A new configuration starts with the local development values: `http://host.docker.internal:7502`
and the dev token the PDCA compose stack provisions. Since the contract is read-only, a token of a
user with read permissions is enough. The unreachable code is `OPENPRODUCT_UNREACHABLE`.
