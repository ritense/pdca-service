# Local GZAC dev setup

Configures a local GZAC — the dev app of a Valtimo checkout with
its `gzac-docker-compose` stack — for the PDCA app and the verzoek route
(ARCHITECTURE §6.2). It works like an administrator clicking through GZAC's
admin: it logs in as the dev administrator and uses GZAC's admin REST API.
Nothing is added to GZAC's classpath, GZAC is never restarted and the Valtimo
checkout is never changed. Every step looks up what is there and creates or
updates it, so it is safe to run as often as you like.

## Running it

```bash
./gradlew bootRunWithDocker   # checklist, then: Configure GZAC for PDCA? [y/n]
./gradlew gzacDevSetup        # the whole setup on its own, in the foreground
```

With a Valtimo checkout present (`../valtimo` or `-PvaltimoDir`),
`bootRunWithDocker` first prints a checklist, with one action line for
every ✗:

```
Local GZAC
  ✓ Valtimo checkout: ../valtimo
  ✓ Docker is running
  ✓ host.docker.internal resolves
  ✗ GZAC backend is not running (http://localhost:8080)
    → cd ../valtimo && ./gradlew :backend:apps:dev:bootRunWithDocker (the setup waits for it)
  ✓ GZAC frontend: http://localhost:4200
  ? PDCA set up in GZAC: unknown (GZAC not running)
Configure GZAC for PDCA? [y/n]
```

- **host.docker.internal** — when this machine does not resolve it (below),
  the checklist offers to add `127.0.0.1 host.docker.internal` to
  `/etc/hosts` (macOS asks for the administrator password; a line marked
  `# added by pdca-app dev setup`, only when the name is not there yet).
  Otherwise it names the alternatives: Docker Desktop → Settings → General →
  "Add the \*.docker.internal names to the host's /etc/hosts file", or
  `sudo sh -c 'echo "127.0.0.1 host.docker.internal" >> /etc/hosts'`.
- **PDCA set up in GZAC** — while GZAC runs, the setup program's `--check`
  looks, without changing anything, for the register plugin configurations,
  the Verzoek plugin configuration and Notificaties API callback URL, the
  PDCA app's registration and configuration with its four contract
  bindings, and the case definitions, imported and active.

The question comes only when something is left to set up: when the check
finds a gap, cannot tell, or GZAC is not running yet; otherwise the
checklist ends with *GZAC is set up for PDCA*. The answer defaults to no,
also without a console; `-PgzacDevSetup=false` answers no upfront,
`-PgzacDevSetup=true` yes, also when everything is set up already (the check
does not see a rebuilt plugin package or changed dev values).

After a yes, `bootRunWithDocker` builds the register plugin packages
(`buildPlugins`), starts the app and runs the whole setup in the background:
it waits for GZAC (its ping and the dev login, up to 15 minutes), does the
steps below that need only GZAC, waits for the app's `/health` (up to 15
minutes) and adds the PDCA configuration and the case definitions. So GZAC
may start before or after the app. The background run never holds up or
stops the app, ends with the build, and closes with one line:
*GZAC set up for PDCA: open http://localhost:4200*, or what is not finished
and `./gradlew gzacDevSetup` to retry.

`gzacDevSetup` runs the same setup in the foreground, waiting the same way
for GZAC and the app (start either in another terminal, or stop it with
Ctrl+C), and fails when a step fails.

Console lines of the setup start with `[gzac-dev-setup]`, one heading per
step.

## Steps

| Step | What | GZAC API |
|---|---|---|
| Name resolution | warns when this machine does not resolve `host.docker.internal` (below) | — |
| Verzoek objecttype | `registers/setup-registers.sh`: the objecttype `InstrumentVerzoek` (fixed uuid `00000000-0000-4000-8000-0000dca00001`, published version 1 with `../objecttypen/instrument-verzoek.schema.json`) in the stack's Objecttypen API, registered in its Objecten API with read/write for the stack's API token; Django shells in the containers through `docker exec` | — |
| Register plugins | uploads `plugins/<name>/dist/<name>-<version>.zip` of `openplan`, `openproduct`, `openklant` and `objecten` to the stack's plugin host (a package with other content replaces the one of the same version), waits for their discovery and creates or updates their configurations | `POST …/external-plugin/host/{id}/upload`, `GET …/definition`, `POST`/`PUT …/configuration` |
| Verzoek route | an Objecttypen API configuration at `host.docker.internal:8011` (below), the object management `InstrumentVerzoek`, the Verzoek plugin configuration (type `instrument` → case and process `instrument-verzoek`, copy strategy full) and the callback URL of the dev app's Notificaties API configuration | `/api/v1/plugin/configuration`, `/api/v1/object/management/configuration` |
| PDCA app | registers the app as external app (URL app) with its secret, callback URL and frontend origin, or re-applies them | `POST …/host`, `PATCH …/host/{id}/connection` |
| PDCA configuration *(needs the app)* | waits until GZAC has discovered the app's manifest (accepting a changed manifest of the same version), then creates or updates the configuration with exactly the manifest's grants and the four contract bindings | `GET …/definition`, `POST`/`PUT …/configuration` |
| Case definitions *(needs the app)* | imports the five zips of `../case-definitions/` that GZAC does not hold yet, mapping their placeholder configuration id to the PDCA configuration, activates them, and gives `instrument-verzoek` its zaak type link | `POST /api/management/v1/case/import`, `POST …/case-definition/{key}/version/{tag}/active` |

`…` is `/api/management/v1`. The dev app's own ZGW plugin configurations
(Notificaties, Objecten, Objecttypen, Zaken and Catalogi API) are looked up
through the API, not assumed by id.

The setup is a small Kotlin program (`src/`, Gradle source set `devSetup`);
`DevSetupTest` and `GzacConfigurationTest` cover its inputs and checks.
`bootRunWithDocker` runs its `--check` with the compiled classes; before the
first compile (or after a change to `src/`) the checklist shows the PDCA
line as not checked yet.

## Values

[`dev-values.json`](./dev-values.json) holds every value the setup puts into
GZAC. Values written as `${NAME:default}` take the environment variable
`NAME` when it is set:

| Variable | Default | What |
|---|---|---|
| `PDCA_OPENPLAN_URL`, `PDCA_OPENPRODUCT_URL`, `PDCA_OPENKLANT_URL` and their `…_TOKEN` | the PDCA stack's registers as the plugin host container reaches them, their dev tokens | register plugin configurations |
| `PDCA_OBJECTEN_URL`, `VALTIMO_OBJECTEN_API_TOKEN` | the stack's Objecten API, its fixture token | `objecten` plugin configuration |
| `PDCA_VERZOEK_OBJECTTYPE_URL` | `http://host.docker.internal:8011/api/v2/objecttypes/00000000-0000-4000-8000-0000dca00001` | the objecttype the `objecten` plugin serves |
| `PDCA_APP_URL` | `http://localhost:7500` | the app as GZAC reaches it |
| `PDCA_HOST_SECRET` | `dev-only-insecure-secret` | the secret GZAC signs its calls to the app with; the app's own default, so they match |
| `PDCA_GZAC_CALLBACK_URL` | `http://localhost:8080` | GZAC as the app reaches it |
| `PDCA_GZAC_NOTIFICATIES_CALLBACK_URL` | `http://host.docker.internal:8080/api/v1/notificatiesapi/callback` | the Notificaties API callback URL (below) |
| `PDCA_VERZOEK_OBJECTTYPEN_API_URL` | `http://host.docker.internal:8011/api/v2/` | the verzoek's Objecttypen API configuration (below) |
| `PDCA_VERZOEK_RSIN`, `PDCA_VERZOEK_INITIATOR_ROLTYPE_UUID`, `PDCA_VERZOEK_ZAAKTYPE_UUID` | `051845623`, `1c359a1b-…`, `744ca059-…` | the dev organisation's RSIN and the initiator roltype and zaaktype of the stack's catalogue |

GZAC itself and the login come from these variables:

| Variable | Default |
|---|---|
| `GZAC_DEV_URL` | `http://localhost:8080` |
| `GZAC_DEV_KEYCLOAK_URL` | `http://localhost:8081/auth/realms/valtimo` |
| `GZAC_DEV_CLIENT_ID` | `valtimo-console` (the GZAC frontend's client) |
| `GZAC_DEV_USERNAME` / `GZAC_DEV_PASSWORD` | `admin` / `admin` (the dev realm's administrator) |
| `GZAC_COMPOSE_PROJECT` | `gzac-docker-compose` (the compose project whose containers `setup-registers.sh` uses) |

The setup logs in with a password grant on the dev realm, so it needs a
user with `ROLE_ADMIN`; Keycloak's tokens live five minutes and are renewed
as needed.

## The stack's containers and host.docker.internal

The containers of the stack address the host as `host.docker.internal`, and
that name travels in the URLs they hand out: the Objecten API renders every
objecttype URL with the Objecttypen API address it knows
(`http://host.docker.internal:8011/api/v2/`), and notifies Open Notificaties
with the object URL of the request that created it (the `objecten` plugin
on the plugin host creates verzoeken at `host.docker.internal:8010`). Hence:

- **Objecttypen API configuration** — Open Notificaties matches the Verzoek
  plugin's objecttype filter literally, so the object management uses its
  own Objecttypen API configuration "Objecttypen API (PDCA dev)" at
  `host.docker.internal:8011`, with the token configuration of the dev
  app's Objecttypen API. The dev app's own configurations stay as they are.
- **Notificaties API callback** — Open Notificaties verifies a callback URL
  by calling it from its container, so the setup sets the callback URL of
  the dev app's Notificaties API configuration to
  `host.docker.internal:8080`. Saving it makes GZAC renew its abonnement,
  with the Verzoek plugin's kanaal. GZAC's own start resets this
  configuration to its environment value (`localhost:8080`): the
  abonnement in Open Notificaties stays as it was (GZAC cannot change it to
  an address Open Notificaties cannot reach); after a GZAC restart the
  checklist reports the callback URL as missing, and answering *y* (or
  `gzacDevSetup`) puts GZAC's side back.
- **Name resolution** — GZAC fetches the verzoek object and its objecttype
  under `host.docker.internal`, so this machine must resolve that name.
  Docker Desktop on macOS does not do that for the host itself unless its
  setting for it is on; the checklist offers to add the name to
  `/etc/hosts` (above) and the setup warns while it is missing.

## Existing state

- **Configurations of an earlier setup** are found by title (or as the only
  configuration of their plugin version) and updated; the PDCA app's
  registration is found by base URL or name. Updating sends every property
  again, secrets included, which also stores them encrypted.
- **Configuration ids** are chosen by GZAC; the case zips refer to the PDCA
  configuration by the placeholder `00000000-0000-4000-8000-00000000dca0`,
  which the import maps to the real id.
- **Capabilities** of a configuration cannot change after it was created;
  when they differ from the dev values the setup says so, and the
  configuration has to be deleted in Admin → Plugins before running again.
- **Register plugin versions.** Configurations belong to one plugin version.
  After a version change of the register plugins the setup uploads the new
  packages and creates configurations for the new version, and rebinds the
  PDCA configuration to them; the previous version's configurations stay
  until you delete them in the admin.
- **Case definitions** are final releases: a version GZAC already holds is
  left alone (`../case-definitions/README.md`, "Versions").

## The zaak type link

The Verzoek plugin creates the instrument dossier with the zaaktype of the
case definition's zaak type link. A zaak type link names environment values
(the Zaken API configuration, the zaaktype), so the zips do not carry one,
and GZAC only accepts a zaak type link on a final version as part of its
import. The setup therefore adds
`zgw/zaak-type-link/instrument-verzoek.zaak-type-link.json` with the dev
stack's values to the `instrument-verzoek` zip it imports (without a zaak
per dossier: `createWithDossier` false). A version that GZAC already holds
without that link cannot get it afterwards; the setup reports it.

## Importing elsewhere

The setup is for the local stack. In another environment the same
configuration is made in the admin (README.md, "Any other GZAC", and
`../case-definitions/README.md`); `dev-values.json` lists exactly which
grants and bindings the PDCA configuration needs.
