# Open Plan plugin (`openplan`)

Serves the contract **`nl.ritense.openplan@2`** on an [Open Plan](https://github.com/maykinmedia/open-plan)
instance (Plannen API v0). The PDCA app uses it for all of its Open Plan traffic. Build, results,
error codes and installation are shared by the register plugins: see [`../README.md`](../README.md).

## Contract surface

| Resource | Operations |
|---|---|
| `plan` | `list`, `get`, `create`, `update` |
| `doel`, `instrument` | `list`, `get`, `create`, `update`, `delete` |
| `persoon` | `list`, `create` |
| `doeltype`, `instrumenttype`, `relatietype`, `plantype`, `overkoepelendplan` | `list` |

`update` is a `PATCH`. Plans are never deleted (they are closed), reference data is read-only.

## Configuration

| Property | |
|---|---|
| `openPlanUrl` | Base URL without `/plannen/api/v0`, as the plugin host reaches it (egress target) |
| `openPlanToken` | Token of an Open Plan API user, sent as `Authorization: Token <token>` (secret) |

A new configuration starts with the local development values: `http://host.docker.internal:7501`
and the dev token the PDCA compose stack provisions (`docker/openplan/init.py`). The unreachable
code is `OPENPLAN_UNREACHABLE`.
