# PDCA Prototype App

> The design and workings of the app are described in
> [`ARCHITECTURE.md`](./ARCHITECTURE.md) (single source of truth); this file
> covers setup and operation.

Standalone prototype for PDCA (Plan-Do-Check-Act) plan management, **directly integrated with
Maykin's Common Ground registers**:

- **[Open Plan](https://github.com/maykinmedia/open-plan)** is the system of record for plannen,
  doelen, instrumenten, contactmomenten and personen (Plannen API `v0`).
- **[Open Product](https://github.com/maykinmedia/open-product)** provides the producttypen
  catalog behind the instrument picker (Producttypen API `v1`).

The frontend consumes both register APIs **in their native shape** (camelCase Dutch fields,
statuses `actief`/`afgerond`/`geannuleerd`, resultaat `behaald`/`gefaald`, paginated
`{count, results}`) through a thin token-injecting proxy in the backend:

```
/openplan/plannen/api/v0/...          -> Open Plan     (localhost:7501)
/openproduct/producttypen/api/v1/...  -> Open Product  (localhost:7502)
```

The Spring Boot backend itself only keeps what the registers don't model, keyed by register uuid
(`/api/v1/pdca/*`): the **direct dossier link** (`plan_details.dossier_id`, plan = dossier
1:1), execution status (gepland/gestart) + progress per doel, configurable plan
display statuses (Concept, Vastgesteld, ...), positie + subdoelgroep (W&P
segmentation, advised by the intake DMN) + hoofddoel reference per
plan, hours/effectiveness/abort reason per instrument, evaluation type + doel progress + action
points per contactmoment, actions, responsibilities (betrokkenen incl. the main responsible)
and the case config (evaluation types, plan statuses, **position types**). Doelen are shown
as one flat list ordered by the overlay `sortering`;
the plan owner is the procesbegeleider/main responsible (`plan.medewerker` URN). Cross-register
references are URNs, e.g. `instrument.product = urn:pdca:openproduct:producttype:<code>` and
`plan.domeinregister = urn:pdca:brp:persoon:<bsn>`.

Key modelling choices:

- **Positions, no free text** — the plan's positie comes from the position-type register
  (`phase_config.positie_typen`, configurable per case type/domein via the PDCA Beheer view);
  the backend rejects values outside the register. The positie enters as input (usually from
  the intake) and the procesbegeleider can adjust it; it is re-determined over time, so there
  is no target position on the plan — the direction lives in the hoofddoel.
- **Doelen from the doeltype register** — `DoelType.doelType` carries the name of a fixed,
  defined doel; category `Hoofddoel` marks hoofddoel types and every other category is a
  thema that scopes the product catalog under that subdoel (ARCHITECTURE §9.2). Every plan
  has exactly one **active hoofddoel**, created as a real Doel with the subdoelen
  referencing it via the register field `doel.hoofdDoel` (W&P hierarchy plan → hoofddoel →
  subdoelen; the overlay keeps the type in `hoofddoel_type_uuid`). Switching the hoofddoel
  completes the old doel (history) and re-points the subdoelen. Free text only as an
  explanation for the inwoner (`plan.notitie`, `doel.beschrijving`). Seeded with fixed uuids in
  `docker/openplan/reference_data.py`.
- **Subdoelen scoped by the hoofddoel** — which subdoeltypen are offered under a hoofddoel
  comes from `phase_config.subdoel_mapping` (per case type, editable in PDCA Beheer); the
  register cannot relate two doeltypen, so the catalog lives in the case config until Open
  Plan models it. Empty = all subdoelen, and an already-chosen subdoel stays selectable.
  See ARCHITECTURE §10.
- **Plan = dossier (1:1)** — the link is a **direct reference in the PDCA overlay**
  (`plan_details.dossier_id` = GZAC documentId, unique constraint); the register's `plan.zaak`
  field is not used. The case tabs and task forms look the plan up via
  `GET /api/v1/pdca/dossiers/{documentId}/plan`, no fallback. A dossier without a plan shows
  "maak eerst een plan aan". Unlinking is possible via
  `DELETE /api/v1/pdca/dossiers/{documentId}/plan` (demo reset).
- **Intake → plan through a user task** — the create-plan task form is **prefilled from the
  dossier content** (`GET /api/v1/pdca/dossiers/{documentId}/plan-prefill`). Which document
  paths fill which plan field is defined as a **prefill mapping** in the case configuration
  (PDCA Beheer, per case type; empty = default paths such as `/bsn`, `/planTitel`,
  `/doelen`). Works with minimal prefill (just a bsn) up to maximal prefill (a complete plan
  with doelen, instrumenten, contactmomenten and betrokkenen); creation happens in one call
  (`POST /api/v1/pdca/plan-intake`) that writes the whole structure into Open Plan + overlay.
  When the task runs in an **intake case type** (config with `planCaseDefinitionKey`), the
  plan is not linked to the intake dossier: the app creates a **new plan dossier** of that
  type (standalone route) and links to that instead.
- **Dienstverlening** — the plantype (werk/pip/inkomen) is the dienstverlening the plan falls
  under and is chosen at plan creation.

The app still speaks the Valtimo external-plugin **"URL app" contract** (discovery at
`/api/host/plugins`, iframe bundles at `/bundles/*`) and can be registered in a Valtimo/GZAC
instance by URL.

## Prerequisites

- **JDK 21** (Gradle toolchain)
- **Docker** (registers + PostgreSQL via docker compose)
- **Node.js 18+** — only needed to rebuild the frontend bundles

## Boot

```bash
./gradlew bootRunWithDocker    # docker compose up -d + bootRun (app on http://localhost:7500)
```

Or in two steps: `docker compose up -d` then `./gradlew bootRun`. To run the app itself as a
container too, see [Docker](#docker): `docker compose --profile app up -d --build`.

The compose file runs three stacks:

| Service | URL | Notes |
|---|---|---|
| Open Plan | <http://localhost:7501> | **Built from GitHub source** on first `up` (no published image yet; pinned commit). Admin: `admin`/`admin`. |
| Open Product | <http://localhost:7502> | `maykinmedia/open-product:1.8.0` from Docker Hub. Admin: `admin`/`admin`. |
| pdca-database | localhost:7503 | PostgreSQL for the PDCA overlay (db/user/pass: `pdca`). |

The `openplan-init` / `openproduct-init` one-shot containers migrate, create the admin users,
provision **fixed dev API tokens** (matching `application.yml`, in `docker/*/init.py`) and seed
the **reference data** (`docker/*/reference_data.py`):
producttypen in Open Product (`docker/openproduct/reference_data.py`; the W&P producten carry the
five portfolio themes Perspectief / Randvoorwaarden / Persoonlijke vermogens / Kansen /
Werkgeversdienstverlening) and plantypen, doeltypen (fixed
uuids; the category `Hoofddoel` marks hoofddoel types, the other categories are the themas
that scope the products offered under a subdoel), instrumenttypen and relatietypen in
Open Plan (`docker/openplan/reference_data.py`). Demo plans are **not** seeded anymore — they are created through
the bundled **intake case types** (see below): the intake form carries the full plan structure
as default values, the "Plan aanmaken" user task turns it into the plan.

Quick checks once running:

- Open Plan through the proxy: <http://localhost:7500/openplan/plannen/api/v0/plan>
- Producttypen: <http://localhost:7500/openproduct/producttypen/api/v1/producttypen>
- PDCA overlay: <http://localhost:7500/api/v1/pdca/plandetails>
- Health: <http://localhost:7500/health> · Manifest: <http://localhost:7500/api/host/plugins>

> Port note: the whole 7500-7503 block is deliberately outside every range used by the Valtimo
> project and its dependencies — GZAC stack (8001-8012, 8080, 8081), plugin host and samples
> (8090-8108), module databases (33xx, 543xx), opensearch (3920x) and rabbitmq (4567x, 5567x).
> Override with `server.port` / the compose port mappings if needed.

Demo bsn's are 11-proef-valid test numbers: `111222333` (Erika de Goede), `123456782`
(Jan van Dijk), `999990019` (Fatima El Amrani), `234567892` (contact person for object plans —
Open Plan doelen always require a persoon). These four are the ones the BRP stub knows.

The `intake-werk-participatie` aanmelding does **not** default to one of them: it generates a
fresh elfproef-valid bsn per aanmelding, because an inwoner may hold only one active plan per
plan case type and the demo has to stay repeatable. Such a bsn is unknown to the BRP stub, so
the create-plan task form shows the subject as the intake recorded it instead of a lookup
error. Open Plan gets a persoon for any bsn on the fly (`ensurePersoon`).

## Frontend bundles

The React sources live in [`frontend/`](./frontend/); Vite builds them into
`src/main/resources/static/bundles/react/`, which the backend serves at `/bundles/react/*`. The
build output is **gitignored**, so build it once after a fresh clone:

```bash
cd frontend
npm install
npx vite build
```

The hand-written task pages (`static/bundles/create-plan.html`, `update-goals.html`,
`evaluate.html`) are committed and served as-is; they call the register APIs directly through
the proxy.

## Using it from Valtimo/GZAC

Runtime integration only: register the app by URL (`http://localhost:7500`) in a GZAC instance
that supports external plugin tabs, then create a plugin configuration for the discovered
"PDCA Planbeheer" plugin. The case tabs (`plan-overview`, `plan-goals`, `plan-evaluations`) and
the `pdca-admin` view are loaded as sandboxed iframes and talk to their host page via the
`valtimo-plugin`/`valtimo-host` postMessage protocol implemented in
[`frontend/src/shared/bridge.ts`](./frontend/src/shared/bridge.ts).

**Five** ready-made case definitions live in
[`gzac/case-definitions/`](./gzac/case-definitions/) as importable zips:

- **Plan case types** `inwonerplan` and `binnenhof-renovatie`: the dossier that 1:1 is the
  plan — request start form, BPMN with the PDCA loop, home of the PDCA case tabs.
- **Intake case types** `intake-werk-participatie` and `renovatie-intake` (separate from
  the plan case types), whose default values form a complete demo (Erika de Goede /
  Binnenhof — starting a dossier and clicking through suffices). `renovatie-intake` is
  minimal (intake form = start form → user task "(…) aanmaken (PDCA)" → end);
  `intake-werk-participatie` ("Intake werk en participatie") is a
  **realistic stepwise W&P intake** with conversation tasks, a mocked external research
  step, the DVKM decision table (no planafspraken step — the create-plan task form
  defaults plan administration), and per-phase internal case statuses driving a
  widget-based Samenvatting tab that grows with the intake (see
  [`gzac/case-definitions/README.md`](./gzac/case-definitions/README.md)). In the PDCA
  configuration the intake case type points at its plan case type via
  **planCaseDefinitionKey**.
- **Product-dossier type** `werkfit-aanvraag`: a product request as a **full case** —
  every request from the plan page gets its own dossier (own tasks, PBAC, documents,
  optionally a zaak via GZAC's zaaktype-link) and manages the instrument in the plan
  through the pdca plugin actions; the instrument's `zaak` field carries the dossier URN,
  which gives the plan page its "Dossier" button.

In addition, [`gzac/bouwblokken/`](./gzac/bouwblokken/) ships importable **building
blocks**: `pdca-actie.zip` (an action under a doel executed as a GZAC building block
process) and `jobcoaching-aanvragen.zip` (a product request — all product logic lives in
the building block, which manages the instrument in the plan itself through the
`aanmaak-instrument`/`update-instrument` plugin actions). The plan-dossier zips link to
them with `startableByUser: false` — normal building block behaviour, but no entry in the
start menu; starting happens from the plan page (see
[`gzac/case-definitions/README.md`](./gzac/case-definitions/README.md)).

The setup is the standard GZAC admin flow — a few manual steps in the GZAC UI, nothing else
is needed (the PDCA side seeds itself: reference data in the registers and the case
configurations including prefill mapping and intake→plan reference):

1. **Register the app** — register the external-plugin host at `http://localhost:7500`; the
   plugin "PDCA Planbeheer" is discovered. Create a **plugin configuration** for it and
   accept the requested endpoints/capability (GZAC then automatically pushes the
   serviceToken to the app).
2. **Import the zips** — the five case zips; the PDCA case tabs and the `create-plan`
   task-form links ship inside them with a placeholder configuration id, so in the import
   wizard's mapping step **pick your "PDCA Planbeheer" configuration** for plugin `pdca`
   and everything is connected at import (imported without the mapping, the case shows a
   configuration issue — repairable afterwards; see
   [`gzac/case-definitions/README.md`](./gzac/case-definitions/README.md), "PDCA connected
   at import"). The plan-dossier zips are self-contained and
   bundle the building blocks their building-block-links reference (GZAC imports those
   first and skips any that already exist). The standalone zips in `gzac/bouwblokken/`
   remain for importing/updating a building block on its own. **Activate the imported
   version** in case management afterwards — imported versions arrive inactive, and an
   inactive case type stays invisible (dossier lists, start menus, and the PDCA Beheer
   dropdowns all show active case types only).
3. **Menu config** — add the plugin page "PDCA Beheer" (`pdca-admin`) to the menu.
4. **Link actions and products** — PDCA Beheer → Actie-bouwblokken, Product-bouwblokken
   or Product-dossiers: pick the building block (or, in the Product-dossiers section, the
   product case type such as `werkfit-aanvraag`), the plugin
   configuration and the doeltype(s) per plan case type; see
   [`gzac/case-definitions/README.md`](./gzac/case-definitions/README.md). Optional: give
   product dossiers a **zaak** by linking the product case type to a zaaktype in GZAC case
   management (*Connected zaak type* with *Automatically create for each case*).

Open the "Plan aanmaken" task after the intake: the form is fully prefilled from the intake
document; submitting creates — in a single `POST /api/v1/pdca/plan-intake` — the plan (incl.
doelen/instrumenten/evaluaties/betrokkenen) **and its plan dossier** of the configured
plan case type, linked 1:1 (server-side, so plan and dossier always come into existence
together) — the intake task completes with planId/planTitel/planStatus/planDossierId written
back into the intake document (the form submits them `doc:`-prefixed, so they stay readable
after the process has ended).

### Creation routes

All routes yield a dossier (plan = dossier 1:1):

- **From a dossier, fed by the intake** (task form `create-plan` on the "Plan
  aanmaken" task, linked as a process link of type `external_plugin_task_form`):
  the form receives the `documentId`, prefills itself from the dossier content via the
  prefill mapping, and creates + links the plan via `POST /api/v1/pdca/plan-intake`. If the
  dossier already has a plan, the form only offers "complete task" instead of creating a
  second plan. If an **unlinked** active plan exists for the entered subject in this domein
  (created standalone), the form offers to link that plan instead of creating a new one
  (`PUT /api/v1/pdca/dossiers/{documentId}/plan`) — in standalone mode a dossier is created
  for it.
- **Via the start form** (supplying a plan id when starting a dossier): the form.io start
  form has an optional `planId` field that lands in the dossier content. The PDCA surfaces
  (tabs and task forms) call `POST /api/v1/pdca/dossiers/{documentId}/resolve-plan` when a
  dossier has no linked plan yet; the backend then reads the dossier via GZAC (granted
  endpoint `GET /api/v1/document/*`), validates the plan and creates the direct link — the
  plan is linked "under water" the moment the dossier is opened. Conflicts (plan already
  linked to another dossier, or dossier already has a plan) return 409.
- **Standalone** (create-plan opened without dossier context): the plan structure is created
  via `POST /api/v1/pdca/plan-intake` (without documentId), after which the app calls its own
  `POST /api/v1/pdca/plans/{planUuid}/dossier`, which creates the GZAC dossier
  (`new-document-and-start-process`, content: bsn/naam or objectId/objectNaam +
  planId/planTitel/planStatus) and links the plan to it.
  The call is idempotent: an already-linked plan returns its existing dossier.

The app's manifest declares this GZAC-API footprint (capability `gzac_api` plus
`permissions.endpoints`, see
[`PluginHostController`](./src/main/kotlin/com/ritense/pdca/plugin/PluginHostController.kt)):

- `POST /api/v1/process-document/operation/new-document-and-start-process` — create a dossier;
- `GET /api/v1/document-definition` + `GET /api/v1/document-definition/*` +
  `GET /api/management/v1/document-definition` — list case types for the PDCA Beheer
  dropdowns (the management list, since the v1 list is PBAC-filtered and hides freshly
  imported types) and look up schemas/the caseDefinitionVersionTag (blueprint);
- `GET /api/v1/document/*` — read a dossier (intake prefill, linking a planId under water);
- `GET /api/management/v1/case/*` — read a dossier's content via case inspection, because
  `/api/v1/document/*` omits `content` unless GZAC runs with
  `valtimo.includeDocumentContentInResponse: true` (default off; on in the local dev GZAC);
- `PUT /api/v1/document` — write a product dossier's own URN into its document;
- `GET /api/v1/process/definition` + `GET /api/v1/process/definition/*` — recognise building
  block processes (`BB:` version tag) and look up the latest version per key;
- `POST /api/v1/process-link/*/form/submission` — start a building block via its start form
  (the start payload lands on the dossier document under `/bouwblokStart`);
- `GET /api/v1/process-link` — find a building block's start-form link;
- `GET .../startable-item/*/version/*/properties` + `PUT .../startable-item/*/version/*`
  (under `/api/management/v1/case-definition/*/version/*`) — maintain the
  `pluginConfigurationMappings` on the case type's building-block link (the configuration
  chosen in PDCA Beheer);
- `PUT .../plugin-configuration-mappings` (same base path) — repair a product case type's
  dangling pdca process links to the chosen configuration (DOSSIER products).

The list with per-endpoint rationale lives in one place in the code:
`PluginHostController.GRANTED_ENDPOINTS`.

When the GZAC admin (re)saves the plugin configuration and **grants those endpoints**, the
serviceToken that GZAC pushes is authorized for exactly that allowlist (PBAC is bypassed for
service tokens on granted endpoints). Credentials are resolved in this order (see
[`GzacClient`](./src/main/kotlin/com/ritense/pdca/service/GzacClient.kt)):

1. the pushed `serviceToken` + `gzacBaseUrl` (persisted in the `plugin_configuration` table,
   so restarts are fine; GZAC re-pushes on discovery and on configuration save). The
   `gzacBaseUrl` is the server-to-server callback URL entered when adding the plugin host in
   GZAC, so it must be reachable from the app; `GZAC_URL` overrides it only when set;
2. `pdca.gzac.static-token`;
3. Keycloak client credentials (`pdca.gzac.token-url` + `client-id`/`client-secret`;
   application.yml defaults match the standard gzac-docker-compose m2m client). Only used
   when no plugin configuration was pushed; the service account then needs the realm roles
   `ROLE_USER` + `ROLE_ADMIN` to pass GZAC's PBAC for document creation.

Note: the created dossier starts the BPMN process, so its "Plan aanmaken" task is open while
the plan already exists — complete it with the placeholder button.

## Docker

The `Dockerfile` is fully self-contained: it builds the frontend bundles (Node) and the jar
(Gradle) inside the image, so `docker build` works from a clean checkout without a local JDK or
Node. The runtime image is a slim non-root JRE with a `/health`-based healthcheck.

**Entire stack in Docker** (app + registers + database), one command:

```bash
docker compose --profile app up -d --build   # or: ./gradlew dockerUpAll
# app on http://localhost:7500, tear down with: docker compose --profile app down
```

Without `--profile app` the compose file starts the dependencies only (the
`./gradlew bootRunWithDocker` dev flow is unchanged).

**Build the image** (for the local development cluster or elsewhere):

```bash
./gradlew dockerBuild        # = docker build -t pdca-app:0.1.0 -t pdca-app:latest .
./gradlew dockerBuildPush -PdockerRegistry=<registry>/<namespace>   # buildx linux/amd64 + push
# optional: -PdockerTag=<tag> -PdockerPlatforms=linux/amd64,linux/arm64
```

On a cluster host with the repo checked out, the compose file can also run a pre-built image
instead of building from source:
`PDCA_APP_IMAGE=<registry>/pdca-app:<tag> docker compose --profile app up -d --no-build`.

Everything external is configured through environment variables (defaults in
`application.yml` target the localhost dev setup; the compose `pdca-app` service overrides
them with in-network hostnames):

| Variable | Default | Purpose |
|---|---|---|
| `SERVER_PORT` | `7500` | HTTP port of the app (healthcheck follows it) |
| `PDCA_DB_HOST` / `PDCA_DB_PORT` | `localhost` / `7503` | PostgreSQL for the PDCA overlay |
| `PDCA_DB_NAME` / `PDCA_DB_USER` / `PDCA_DB_PASS` | `pdca` (all three) | Overlay database name/credentials |
| `OPENPLAN_URL` / `OPENPLAN_TOKEN` | `http://localhost:7501` / dev token | Open Plan register |
| `OPENPRODUCT_URL` / `OPENPRODUCT_TOKEN` | `http://localhost:7502` / dev token | Open Product register |
| `GZAC_URL` | _(empty: the pushed callback URL is used)_ | Override of the GZAC API URL pushed by GZAC; required for the static-token/Keycloak fallbacks |
| `GZAC_TOKEN_URL` / `GZAC_CLIENT_ID` / `GZAC_CLIENT_SECRET` | standard gzac-docker-compose m2m client | Keycloak client-credentials fallback |
| `PDCA_SEED_DEMO_DATA` | `true` | Seed demo plans into an empty Open Plan on boot |

**Registers outside docker compose** (e.g. a cluster environment): the app needs the same
reference data in Open Plan and Open Product, otherwise the dienstverlening/hoofddoel/subdoel
choice lists and the product catalog stay empty and the create-plan form cannot apply the
intake prefill. Seed it once per environment (idempotent) in a shell of each register
container — run `reference_data.py`, **not** `init.py`, which would replace the admin user's
API token with the dev token:

```bash
kubectl exec -i <openplan-pod> -- python src/manage.py shell < docker/openplan/reference_data.py
kubectl exec -i <openproduct-pod> -- python src/manage.py shell < docker/openproduct/reference_data.py
```

Container networking notes: the compose `pdca-app` service reaches a GZAC stack running on the
host via `host.docker.internal` (mapped with `host-gateway`, so it also works on Linux). The
other direction, when GZAC itself runs in Docker, register the plugin URL as
`http://host.docker.internal:7500` instead of `localhost:7500`.

## Development

```bash
./gradlew build          # full build -> build/libs/pdca-app-0.1.0.jar
./gradlew buildGzacZips  # rebuild the importable GZAC zips (case definitions + building blocks)
```

Resetting data: `docker compose down -v` wipes all three databases; the init containers and
Liquibase re-provision everything (same fixed uuids) on the next `docker compose up -d` +
app start. Registers not part of this integration (BRP persoonsgegevens, objectenregister) are
served as display-only stubs from `/api/v1/registers/*`.
