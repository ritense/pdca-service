# Objecten API plugin (`objecten`)

Serves the contract **`nl.ritense.objecten@2`** on an
[Objecten API](https://github.com/maykinmedia/objects-api) (Objects API v2). The PDCA app files
the **verzoeken** of its product requests through it and reads them back to show the open ones
(see [`ARCHITECTURE.md`](../../ARCHITECTURE.md) §6.2). Build, results, error codes and installation
are shared by the register plugins: see [`../README.md`](../README.md).

## Contract surface

| Resource | Operations | Query (`list`) |
|---|---|---|
| `objects` | `list`, `create` | `type`, `data_attrs`, `page`, `pageSize` |

A configuration serves the objects of **one objecttype**, its `verzoekObjecttypeUrl`:

- `list` always filters on that objecttype (`type=<verzoekObjecttypeUrl>` is added); a `type`
  query naming another objecttype is refused (`OBJECTTYPE_NOT_ALLOWED`). The register's own
  `next` links carry the same `type` and pass.
- `create` refuses a body whose `type` is not that objecttype (`OBJECTTYPE_NOT_ALLOWED`).
- Single objects are never read, changed or deleted through the contract.

Even though the Objecten API token may reach other objecttypes, a caller of the contract cannot.
Every request carries `Accept-Crs: EPSG:4326`, every write also `Content-Crs: EPSG:4326`.

## Configuration

| Property | |
|---|---|
| `objectenUrl` | Base URL without `/api/v2`, as the plugin host reaches it (egress target) |
| `objectenToken` | Token of an Objecten API user, sent as `Authorization: Token <token>` (secret); it must be allowed to read and write objects of the verzoek objecttype (token permissions in the Objecten API admin) |
| `verzoekObjecttypeUrl` | URL of the verzoek objecttype in the Objecttypen API, exactly as the Objecten API stores it on its objects (and as the `StartInstrument` acties in Open Product name it) |

A new configuration starts with the values of the local GZAC stack: `http://host.docker.internal:8010`,
the token of its Objecten API fixtures and the InstrumentVerzoek objecttype
`http://host.docker.internal:8011/api/v2/objecttypes/00000000-0000-4000-8000-0000dca00001` that
`gzac/dev-setup/registers/setup-registers.sh` creates. The unreachable code is
`OBJECTEN_UNREACHABLE`.
