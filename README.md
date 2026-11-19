# PDCA app

Plan management along the Plan-Do-Check-Act cycle, as an overlay on Maykin's
Common Ground registers ([Open Plan](https://github.com/maykinmedia/open-plan),
[Open Product](https://github.com/maykinmedia/open-product),
[Open Klant](https://github.com/maykinmedia/open-klant) and the
[Objecten API](https://github.com/maykinmedia/objects-api)), running as an
external plugin (URL app) in Valtimo/GZAC. The app is a Spring Boot service
with React bundles that GZAC embeds as case tabs, a task form, an admin page
and a side panel; it keeps only what the registers do not model in its own
PostgreSQL database, and reaches the registers exclusively through GZAC's
plugin contracts and the register plugins in [`plugins/`](./plugins/README.md).

- Design and workings: [`ARCHITECTURE.md`](./ARCHITECTURE.md) (the single
  source of truth).
- GZAC case definitions: [`gzac/case-definitions/`](./gzac/case-definitions/README.md);
  the local GZAC dev setup: [`gzac/dev-setup/`](./gzac/dev-setup/README.md).
- Register plugins: [`plugins/README.md`](./plugins/README.md).

This file covers setup, configuration, operation and development.

## Prerequisites

- **JDK 21** (Gradle toolchain).
- **Node.js 22+** — the frontend is built and checked by `./gradlew build`
  (or skip it with `-PskipFrontend`, see [Development](#development)).
- **Docker** — the registers and the overlay database run in docker compose;
  the backend's integration tests use Testcontainers.
- **A checkout of the [Valtimo monorepo](https://github.com/valtimo-platform/valtimo)**
  next to this repository (`../valtimo`, or `-PvaltimoDir=<path>`) — only to
  build and test the register plugins (they build against its plugin SDK)
  and to run the local GZAC stack.
- **A Valtimo/GZAC with external plugin contracts** — the contract broker
  and user-token introspection exist on Valtimo's
  `feature/external-plugin-contracts` branch, not yet in a release; iframe
  navigation additionally needs a GZAC frontend change that is pending
  upstream (ARCHITECTURE §8).

## Getting started

```bash
./gradlew bootRunWithDocker    # registers + database (docker compose, waits until healthy), then the app on :7500
```

`bootRunWithDocker` opens the admin pages of Open Plan, Open Product and Open
Klant in the browser, already logged in: the compose stack logs every admin
visitor in as `admin` (`docker/register-dev/pdca_dev.py`). Skip the tabs with
`-PopenRegisterAdmins=false`, turn the auto-login off with
`PDCA_ADMIN_AUTOLOGIN=false docker compose up -d`, or open the tabs on their
own with `./gradlew openRegisterAdmins`. Under `bootRun` the display stubs
(BRP and object demo data) are on.

In two steps instead: `docker compose up -d`, then `./gradlew bootRun`.

| Service | Address | Notes |
|---|---|---|
| Open Plan | <http://localhost:7501> | built from GitHub source on first `up` (no published image; pinned commit); admin `admin`/`admin` |
| Open Product | <http://localhost:7502> | `maykinmedia/open-product:1.8.0`; admin `admin`/`admin` |
| pdca-database | localhost:7503 | PostgreSQL 17 for the overlay (db/user/password `pdca`) |
| Open Klant | <http://localhost:7504> | `maykinmedia/open-klant:2.19.1` (linux/amd64, emulated on Apple silicon); admin `admin`/`admin` |
| pdca-app | <http://localhost:7500> | only with `--profile app` ([Docker](#docker)) |

The `*-init` one-shot containers migrate, create the admin users, provision
fixed dev API tokens (matching the register plugins' local defaults;
`docker/*/init.py`) and seed the reference data (`docker/*/reference_data.py`):
plantypen, doeltypen (fixed uuids; category `Hoofddoel` marks hoofddoel
types, the other categories are themas), instrumenttypen and relatietypen in
Open Plan, and the producttypen with their themas and `StartInstrument`
actie in Open Product. No plans are seeded: they are created through the
intake case types.

The 7500–7504 block avoids every port the Valtimo stack and its dependencies
use (GZAC 8001–8012, 8080, 8081; plugin host 8090–8108; module databases
33xx, 543xx; opensearch 3920x; rabbitmq 4567x, 5567x).

Quick checks: <http://localhost:7500/health>, and a bundle at
<http://localhost:7500/plugins/pdca/0.2.0/bundles/plan-overview.html> (it
waits for GZAC). Everything else is closed to direct calls: the host API
needs GZAC's signature and the REST API answers only through GZAC's `/data`
call with a user token. Register data shows once the app runs in GZAC with
the register plugins bound; until then the requests of an unbound register
answer 503 *"<register> is niet gekoppeld: …"*.

Demo bsn's (elfproef-valid, known to the BRP stub): `111222333` (Erika de
Goede), `123456782` (Jan van Dijk), `999990019` (Fatima El Amrani),
`234567892` (contact person for object plans). The
`intake-werk-participatie` aanmelding generates a fresh elfproef-valid bsn
per run instead, because an inwoner may hold only one active plan per plan
case type; Open Plan gets a persoon for any bsn on the fly.

## Running it in GZAC

### Local GZAC stack

GZAC is the Valtimo monorepo's dev app with its `gzac-docker-compose` stack,
run from a Valtimo checkout (`../valtimo`, or `-PvaltimoDir=<path>`) that is
never changed. In any order:

1. In the Valtimo checkout: `./gradlew :backend:apps:dev:bootRunWithDocker`
   (GZAC's backend and its docker stack).
2. Here: `./gradlew bootRunWithDocker`, and answer its questions. It prints
   a checklist of the local GZAC and, when GZAC is not set up for PDCA yet,
   asks *Configure GZAC for PDCA? [y/n]*; *y* sets it up in the background
   as soon as GZAC and the app answer
   ([`gzac/dev-setup/`](./gzac/dev-setup/README.md)).
3. In the Valtimo checkout: `cd frontend && npm start`, then open
   <http://localhost:4200> (`admin`/`admin`) and start an intake dossier
   ([Using it](#using-it)).

### Any other GZAC

A few steps in the GZAC admin; the PDCA side provisions itself.

1. **Register plugins** — build them (`./gradlew buildPlugins`) and upload
   `plugins/<name>/dist/<name>-2.0.0.zip` for `openplan`, `openproduct`,
   `openklant` and `objecten` onto a plugin host (*Admin → Plugins → Upload
   plugin*). Configure each with the register URL as the **plugin host**
   reaches it and its token, accepting `http_request` and `log`; the
   `objecten` plugin also takes the URL of the `InstrumentVerzoek`
   objecttype. Plain http to a private address needs a plugin host that
   allows it (`HOST_ALLOW_HTTP` and `HOST_ALLOWED_INTERNAL_CIDRS` or
   `HOST_ALLOW_PRIVATE_NETWORK`). Details: [`plugins/README.md`](./plugins/README.md).
2. **Register the app** — add the external-plugin host at the app's URL
   (`http://localhost:7500`, or `http://host.docker.internal:7500` for a GZAC
   in Docker) with `PDCA_HOST_SECRET` as its secret and the GZAC frontend
   origin as frontend origin. Create a configuration for the discovered
   "PDCA Planbeheer" plugin, accept its endpoints and capabilities, and bind
   `nl.ritense.openplan@2`, `nl.ritense.openproduct@2`,
   `nl.ritense.openklant@2` and `nl.ritense.objecten@2` under *Required
   plugins*. GZAC then pushes the serviceToken and the bound contracts to the
   app.
3. **Import the case zips** from `gzac/case-definitions/`, mapping the
   placeholder configuration to your "PDCA Planbeheer" configuration in the
   import wizard, and activate the imported versions
   ([`gzac/case-definitions/README.md`](./gzac/case-definitions/README.md),
   "Importing" and "PDCA connected at import").
4. **Menu** — add the plugin page "PDCA Beheer" (`pdca-admin`).
5. **Verzoek route** — once per GZAC stack: the `InstrumentVerzoek`
   objecttype (schema in [`gzac/objecttypen/`](./gzac/objecttypen/)),
   notifications from the Objecten API, the object management, a zaaktype
   link on `instrument-verzoek` and a Verzoek plugin configuration for
   verzoek type `instrument` ([`gzac/case-definitions/README.md`](./gzac/case-definitions/README.md),
   "Setting up the verzoek route in GZAC"); then give the producttypen their
   `StartInstrument` actie with that objecttype URL
   ([Registers outside docker compose](#registers-outside-docker-compose)).

### Using it

Start an intake dossier (`intake-werk-participatie` or `renovatie-intake`;
their defaults form a complete demo) and complete its tasks. The last task,
"Plan aanmaken (PDCA)", is the `create-plan` form, prefilled from the intake:
submitting it creates the plan, its plan dossier and the requested
instruments' verzoeken; each instrument appears in the plan once its own
instrument dossier has created it. The intake's *Aangemaakt plan* widget
leads to the plan dossier, whose tabs Planoverzicht, Doelen & Acties and
Evaluaties are the PDCA bundles.

## Configuration

Everything is configured through environment variables; the defaults in
`src/main/resources/application.yml` target local development. There are no
register settings: register URLs and tokens live in the register plugin
configurations in GZAC.

| Variable | Default | Purpose |
|---|---|---|
| `SERVER_PORT` | `7500` | HTTP port (the image's healthcheck follows it) |
| `PDCA_DB_HOST` / `PDCA_DB_PORT` | `localhost` / `7503` | overlay database |
| `PDCA_DB_NAME` / `PDCA_DB_USER` / `PDCA_DB_PASS` | `pdca` / `pdca` / `pdca` | overlay database name and credentials |
| `PDCA_HOST_SECRET` | `dev-only-insecure-secret` | the secret GZAC signs its calls with: the PDCA integration's secret in GZAC, at least 16 characters. **Set it outside local development**; the default logs a WARN |
| `PDCA_ENCRYPTION_SECRET` | `pdca-local-dev-encryption-secret` | the key the pushed serviceTokens are encrypted with at rest. **Set it outside local development**; the default logs a WARN. Changing it makes stored tokens unreadable until GZAC's next push |
| `PDCA_GZAC_FRONTEND_ORIGINS` | `http://localhost:4200` | comma-separated GZAC frontend origins that may frame the bundles and call `/data`, in addition to the origins GZAC announces |
| `PDCA_GZAC_ALLOWED_BASE_URLS` | _(empty: any)_ | comma-separated GZAC base URLs whose configuration pushes and announcements are accepted; pins the app to its GZAC |
| `PDCA_GZAC_CONFIGURATION_ID` | _(empty)_ | the PDCA configuration the app uses; needed only when GZAC holds more than one (with several and none named, GZAC and register calls answer 503) |
| `GZAC_URL` | _(empty: the pushed URL)_ | overrides the GZAC URL pushed with the configuration, where the app sees GZAC under another address (the compose app service) |
| `PDCA_HTTP_CONNECT_TIMEOUT` / `PDCA_HTTP_READ_TIMEOUT` | `5s` / `30s` | timeouts of every outbound call (GZAC, and the registers through it) |
| `PDCA_REGISTER_MAX_PAGES` | `50` | most pages read of one register listing; longer fails instead of truncating |
| `PDCA_REGISTER_CONTRACT_CONCURRENCY` | `6` | most register contract calls at once (GZAC allows 8 per configuration) |
| `PDCA_REGISTER_CONTRACT_PERMIT_TIMEOUT` | `30s` | how long a register call waits for its turn before answering 503 |
| `PDCA_REGISTER_CONTRACT_RETRIES` / `PDCA_REGISTER_CONTRACT_RETRY_BACKOFF` | `3` / `200ms` | retries of a call GZAC refuses as too many concurrent calls, and the first (doubling, jittered) pause |
| `PDCA_DATA_RATE_LIMIT_PER_USER` / `PDCA_DATA_RATE_LIMIT_WINDOW` | `600` / `1m` | `/data` calls per window of one verified user (429 over it; 0 switches the limit off); counted per replica |
| `PDCA_DATA_RATE_LIMIT_PER_CLIENT` / `PDCA_DATA_RATE_LIMIT_PER_CONFIGURATION` | `300` / `6000` | per window, the `/data` calls whose user token is not cached and costs an introspection at GZAC: per client address and for the whole configuration (429 over it; 0 switches a limit off); counted per replica |
| `SERVER_FORWARD_HEADERS_STRATEGY` | `none` (the chart: `native`) | where the client address of the `/data` rate limit comes from: `none` is the TCP peer; behind a reverse proxy set `native` so it is the browser's address from the proxy's `X-Forwarded-For` |
| `PDCA_DISPLAY_STUBS` | `false` (`true` under `bootRun` and in the compose app service) | serve demo data for the BRP and object display stubs; local development and demos only |
| `PDCA_LOG_LEVEL` | `INFO` | log level of `com.ritense.pdca` |
| `JAVA_OPTS` | `-XX:MaxRAMPercentage=75` | JVM options of the container image |

Variables of the local stack and tooling, not of the app:

| Variable | Where | Purpose |
|---|---|---|
| `PDCA_ADMIN_AUTOLOGIN` | compose register services | `false` turns the admin auto-login off |
| `PDCA_INSTRUMENT_VERZOEK_OBJECTTYPE` | compose `openproduct-init`, `docker/openproduct/reference_data.py` | the `InstrumentVerzoek` objecttype URL written into the `StartInstrument` acties; the compose default is the local GZAC stack's objecttype; without it a product request reports the missing objecttype |
| `PDCA_APP_IMAGE` | compose `pdca-app` | run a pre-built image instead of building from source |
| `GZAC_DEV_URL`, `GZAC_DEV_KEYCLOAK_URL`, `GZAC_DEV_CLIENT_ID`, `GZAC_DEV_USERNAME`, `GZAC_DEV_PASSWORD` | `gzacDevSetup`, `bootRunWithDocker` | the local GZAC and its dev administrator login (defaults: `http://localhost:8080`, the dev Keycloak realm, `valtimo-console`, `admin`/`admin`; gzac/dev-setup/README.md) |
| `GZAC_COMPOSE_PROJECT` | `gzacDevSetup` | compose project name of the GZAC stack (default `gzac-docker-compose`) |

## Docker

The `Dockerfile` is self-contained: stage 1 installs and builds the frontend
(`npm ci`, `npm run build`; the checks run in `./gradlew build`, not in the
image), stage 2 builds the jar (`./gradlew bootJar -PskipFrontend` with the
bundles from stage 1), and the runtime is a non-root JRE 21 image with a
`/health` healthcheck. The container takes the variables of
[Configuration](#configuration).

```bash
docker compose --profile app up -d --build   # app + registers + database (or ./gradlew dockerUpAll)
docker compose --profile app down            # (or ./gradlew dockerDownAll)
./gradlew dockerBuild                        # image pdca-app:0.1.0 and :latest
./gradlew dockerBuildPush -PdockerRegistry=<registry>/<namespace> [-PdockerTag=<tag>] [-PdockerPlatforms=linux/amd64,linux/arm64]
PDCA_APP_IMAGE=<registry>/pdca-app:<tag> docker compose --profile app up -d --no-build
```

Without `--profile app` compose starts the dependencies only. The compose
app service reaches a GZAC on the host via `host.docker.internal` (mapped
with `host-gateway`, so also on Linux); a GZAC in Docker registers the app as
`http://host.docker.internal:7500`.

A push to `main` builds the image and publishes it to GHCR
(`.github/workflows/release.yaml`); the Helm chart is published by
`helm-publish.yaml`.

### Helm

`charts/pdca-app` runs the image with a read-only root filesystem (with an
`emptyDir` on `/tmp`) and health probes on `/health`. `config.*` sets the
port, JVM options, log level, display stubs, `/data` rate limit and the
forwarded-headers strategy (`native`, so the rate limit sees the browser's
address behind the ingress);
`database.*` the database (password from `database.existingSecret`);
`externalServices.gzac.*` the GZAC URL override, configuration id, allowed
base URLs and frontend origins; `host.existingSecret` provides
`PDCA_HOST_SECRET` and `encryption.existingSecret` `PDCA_ENCRYPTION_SECRET`.
Other variables go in `extraEnv`. The ingress is enabled by default.

## Operation

- **Database** — Liquibase applies the changelogs at startup; Hibernate then
  validates the schema against the entities (`ddl-auto: validate`), so a
  mismatch stops the app instead of running on a drifted schema.
- **Outside local development** set `PDCA_HOST_SECRET` (the PDCA
  integration's secret in GZAC) and `PDCA_ENCRYPTION_SECRET`; set
  `PDCA_GZAC_FRONTEND_ORIGINS` unless GZAC announces its frontend origins;
  pin GZAC with `PDCA_GZAC_ALLOWED_BASE_URLS`; name the configuration with
  `PDCA_GZAC_CONFIGURATION_ID` when GZAC holds several; keep
  `PDCA_DISPLAY_STUBS` off. The `/data` rate limit and the signature replay
  check are kept per replica.
- **The app depends on GZAC at runtime**: it starts without it, but GZAC and
  register calls answer 503 until GZAC has pushed an active configuration
  with the register contracts bound.
- **Resetting local data** — `docker compose down -v` wipes all databases;
  the init containers and Liquibase re-provision everything (same fixed
  uuids) on the next start.

### Registers outside docker compose

The app needs the same reference data in Open Plan and Open Product as the
dev stack, otherwise the choice lists, the product catalog and the intake
prefill stay empty. Seed it once per environment (idempotent) in a shell of
each register container — `reference_data.py`, **not** `init.py`, which
would replace the admin's API token with the dev token:

```bash
kubectl exec -i <openplan-pod> -- python src/manage.py shell < docker/openplan/reference_data.py
kubectl exec -i <openproduct-pod> -- env PDCA_INSTRUMENT_VERZOEK_OBJECTTYPE=<objecttype url> \
  python src/manage.py shell < docker/openproduct/reference_data.py
```

Rerunning the Open Product seed with the variable updates the existing
`StartInstrument` acties.

### Upgrade notes

- **Signed host contract** — the app refuses unsigned calls from GZAC and
  direct browser calls. An existing GZAC registration keeps working only
  when its secret equals `PDCA_HOST_SECRET` (change it in GZAC's admin;
  locally `./gradlew gzacDevSetup` re-applies it),
  and its frontend origins must include the GZAC frontend, otherwise the
  bundles stay blank (`frame-ancestors`) and their calls fail (CORS).
- **Encrypted serviceTokens** — upgrading a database that still holds a
  plaintext token clears it; GZAC and register calls answer 503 until GZAC's
  next discovery poll pushes the configuration again.
- **Document search grant** — the manifest grants
  `POST /api/v1/document-search` (finding a plan's dossier before creating
  one). GZAC asks the administrator to review and accept the changed
  footprint; until then plan creation from an intake answers 502 with that
  request.
- **Instrument dossier 1.2.0** — import `instrument-verzoek.zip` 1.2.0 (all
  three plugin actions asynchronous with retries). Dossiers already running
  keep the process version they started with; new dossiers start on 1.2.0.

## Development

```bash
./gradlew build                  # frontend (install, check, build) + backend (compile, tests) -> build/libs/pdca-app-0.1.0.jar
./gradlew build -PskipFrontend   # no frontend tasks; the jar packages whatever bundles exist
./gradlew test -PskipIntegrationTests   # backend unit tests only
./gradlew frontendCheck          # frontend typecheck, lint and tests
./gradlew buildGzacZips          # rebuild the GZAC case-definition zips (commit source and zips together)
./gradlew buildPlugins           # register plugins -> plugins/<name>/dist/<name>-2.0.0.zip
./gradlew buildOpenPlanPlugin    # one plugin (also OpenProduct, OpenKlant, Objecten)
./gradlew testPlugins            # tests of the register plugins and their core
./gradlew gzacDevSetup           # set up the local GZAC for PDCA (above)
```

**Build pipeline.** `./gradlew build` runs `frontendInstall` (`npm ci`, only
when `frontend/package.json` or `package-lock.json` changed) and
`frontendBuild` (`vite build` into `src/main/resources/static/bundles/react`,
before `processResources`, so the jar always contains the bundles). `check`
runs `frontendCheck` (`frontendTypecheck`, `frontendLint`, `frontendTest`),
the backend tests and — when the Valtimo plugin SDK is present — `testPlugins`;
without the SDK it prints a notice instead. Every frontend and plugin step is
skipped while its inputs are unchanged.

**Gradle properties.** `-PskipFrontend` skips all frontend tasks;
`-PnodeDir=<dir>` picks the Node installation (else the newest nvm-installed
Node 22+, else `node` on the PATH); `-PvaltimoDir=<path>` picks the Valtimo
checkout (default `../valtimo`); `-PskipIntegrationTests` leaves out the
integration tests; `-PopenRegisterAdmins=false` keeps `bootRunWithDocker`
from opening the register admins; `-PgzacDevSetup=true|false` answers its
question about the local GZAC upfront, `true` also when GZAC is set up
already (gzac/dev-setup/README.md).

**Backend tests.** Unit tests plus integration tests
(`src/test/kotlin/com/ritense/pdca/it/`, JUnit tag `integration`) against
PostgreSQL `postgres:17.4-alpine` (the compose version) in Testcontainers:
the Liquibase schema with Hibernate validation, the concurrency and claim
ownership of the plan intake reservation, and the trust boundary in the full
application context against a fake GZAC. They skip themselves when Docker is
unavailable. Three unit tests read files outside the classpath and rerun
when they change: `GzacConfigurationTest` (the GZAC configuration as code
and the local GZAC setup's values against the manifests), `DevSetupTest`
(the local GZAC setup's value loading and case zip handling) and
`RegisterContractFootprintTest` (the register plugins' allowlists against
the app's register calls).

**Frontend** (`frontend/`, Node 22+): `npm run build` (Vite), `npm run
typecheck` (`tsc` over sources, tests and config), `npm run lint` (ESLint
flat config with typescript-eslint, react-hooks and `no-alert`,
`--max-warnings=0`) and `npm test` (Vitest with Testing Library and jsdom,
in time zone Europe/Amsterdam so local-time bugs show). Carbon's styles are
compiled per used component in `frontend/src/shared/styles/carbon.scss`: a
newly used Carbon component needs its style module there.

**Register plugins** (`plugins/`, one npm workspace): they depend on the
Valtimo plugin SDK through `file:../build/valtimo-plugin-sdk`, a link the
Gradle task `pluginSdkLink` points at `<valtimoDir>/plugin-host/plugin-sdk`.
The Gradle tasks handle the link, the SDK build and the install; by hand,
run `./gradlew pluginSdkLink pluginSdkBuild` once before `npm install` in
`plugins/`. See [`plugins/README.md`](./plugins/README.md).
