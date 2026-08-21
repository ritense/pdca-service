# PDCA Prototype App

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
(`/api/v1/pdca/*`): uitvoeringsstatus (gepland/gestart) + voortgang per doel, configurable plan
display statuses (Concept, Vastgesteld, ...), begin-/doelpositie + hoofddoel-verwijzing per plan,
uren/effectiviteit/afbreekreden per instrument, evaluatietype + doelvoortgang + actiepunten per
contactmoment, acties, verantwoordelijkheden (betrokkenen incl. hoofdverantwoordelijke) and the
case config (optional doelcategorie-ordening = fasering, evaluation types, plan statuses,
**positietypen**). Doelen are grouped by their **doelcategorie from the register**; the plan
owner is the procesbegeleider/hoofdverantwoordelijke (`plan.medewerker` URN). Cross-register
references are URNs, e.g. `instrument.product = urn:pdca:openproduct:producttype:<code>` and
`plan.domeinregister = urn:pdca:brp:persoon:<bsn>`.

Model decisions from the 17-08-26 session are wired in:

- **Posities, geen vrije tekst** — begin- en doelpositie komen uit het positietype-register
  (`phase_config.positie_typen`, inrichtbaar per dossiertype/domein via de PDCA-beheer view);
  the backend rejects values outside the register. De beginpositie is input (doorgaans uit de
  intake — die zelf buiten scope is); de procesbegeleider kan beide bijstellen.
- **Doelen uit het doeltype-register** — `DoelType.doelType` carries the name of a vast
  gedefinieerd doel; categorie `Hoofddoel` marks hoofddoel-typen. Elk plan heeft precies één
  hoofddoel (overlay `hoofddoel_type_uuid`); vrije tekst alleen als toelichting t.b.v. de
  inwoner (`plan.notitie`, `doel.beschrijving`). Seeded with fixed uuids in
  `docker/openplan/init.py`.
- **Plan = dossier (1:1)** — the case tabs and task forms match the plan strictly on the
  zaak-URN of the current dossier (`plan.zaak = urn:pdca:zaaksysteem:zaak:<documentId>`), no
  fallback. A dossier without plan shows "maak eerst een plan aan". The demo plans are seeded
  **ongekoppeld** (no zaak): start a dossier for their subject and the "Plan aanmaken"-taak
  offers to link the existing plan (terugkeerder-scenario).
- **Dienstverlening** — het plantype (werk/pip/inkomen) is de dienstverlening waaronder het
  plan valt en wordt gekozen bij plan-aanmaak.

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

Or in two steps: `docker compose up -d` then `./gradlew bootRun`.

The compose file runs three stacks:

| Service | URL | Notes |
|---|---|---|
| Open Plan | <http://localhost:7501> | **Built from GitHub source** on first `up` (no published image yet; pinned commit). Admin: `admin`/`admin`. |
| Open Product | <http://localhost:7502> | `maykinmedia/open-product:1.8.0` from Docker Hub. Admin: `admin`/`admin`. |
| pdca-database | localhost:7503 | PostgreSQL for the PDCA overlay (db/user/pass: `pdca`). |

The `openplan-init` / `openproduct-init` one-shot containers migrate, create the admin users,
provision **fixed dev API tokens** (matching `application.yml`) and seed everything:
producttypen in Open Product (`docker/openproduct/init.py`) and reference data + two demo
plannen with **fixed uuids** in Open Plan (`docker/openplan/init.py`). Liquibase seeds the
matching PDCA overlay rows (`011-overlay-seed-data.xml`) — keep those two files in sync.

Quick checks once running:

- Open Plan through the proxy: <http://localhost:7500/openplan/plannen/api/v0/plan>
- Demo plan Erika: `.../plan/11111111-1111-1111-1111-111111111111`
- Producttypen: <http://localhost:7500/openproduct/producttypen/api/v1/producttypen>
- PDCA overlay: <http://localhost:7500/api/v1/pdca/plandetails>
- Health: <http://localhost:7500/health> · Manifest: <http://localhost:7500/api/host/plugins>

> Port note: the whole 7500-7503 block is deliberately outside every range used by the Valtimo
> project and its dependencies — GZAC stack (8001-8012, 8080, 8081), plugin host and samples
> (8090-8108), module databases (33xx, 543xx), opensearch (3920x) and rabbitmq (4567x, 5567x).
> Override with `server.port` / the compose port mappings if needed.

Demo bsn's are 11-proef-valid test numbers: `111222333` (Erika de Goede), `123456782`
(Jan van Dijk), `999990019` (Fatima El Amrani), `234567892` (contactpersoon for object plans —
Open Plan doelen always require a persoon).

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

Ready-made GZAC case definitions for the two demo cases (`inwonerplan`,
`binnenhof-renovatie`) — document definition, BPMN with PDCA loop, start form, process links,
list/search/tab config — live in [`gzac/case-definitions/`](./gzac/case-definitions/) as
importable zips; after importing, linking the external-plugin case tabs is the only manual step.

### Los een plan aanmaken → PDCA maakt zelf het dossier aan

Both aanmaakroutes yield a dossier (plan = dossier 1:1):

- **Vanuit een dossier** (taakformulier `create-plan` in de "Plan aanmaken"-taak, gekoppeld
  als proceskoppeling van het type `external_plugin_task_form`): the form receives the
  `documentId` and links the plan to that dossier. Has the dossier already a plan (e.g. via
  the losse route below), the form only offers "taak afronden" instead of creating a second
  plan. Exists an **ongekoppeld** actief plan for the entered subject in this domein (a los
  aangemaakt plan or a seeded demo plan), the form offers to link that plan instead of
  creating a new one — in de losse modus wordt daarvoor een dossier aangemaakt.
- **Via het startformulier** (plan-id opgeven bij dossier starten): the form.io start form
  has an optional `planId` field that lands in the dossier content. The PDCA surfaces
  (tabs and task forms) call `POST /api/v1/pdca/dossiers/{documentId}/resolve-plan` when a
  dossier has no linked plan yet; the backend then reads the dossier via GZAC (granted
  endpoint `GET /api/v1/document/*`), validates the plan and sets `plan.zaak` — the plan is
  linked "onderwater" the moment the dossier is opened. Conflicts (plan already linked to
  another dossier) return 409.
- **Los** (create-plan opened without dossier context): after creating the plan the app calls
  its own `POST /api/v1/pdca/plans/{planUuid}/dossier`, which creates the GZAC dossier
  (`new-document-and-start-process`, content: bsn/naam of objectId/objectNaam +
  planId/planTitel/planStatus), and sets `plan.zaak` to the new dossier's zaak-URN.
  The call is idempotent: an already-linked plan returns its existing dossier.

The app's manifest declares this GZAC-API footprint (capability `gzac_api` plus
`permissions.endpoints`, see
[`PluginHostController`](./src/main/kotlin/com/ritense/pdca/plugin/PluginHostController.kt)):

- `POST /api/v1/process-document/operation/new-document-and-start-process` — dossier aanmaken;
- `GET /api/v1/document-definition/*` — caseDefinitionVersionTag opzoeken (blueprint;
  `/api/management/**` is voor external-plugin service-tokens niet bereikbaar).

When the GZAC admin (re)saves the plugin configuration and **grants those endpoints**, the
serviceToken that GZAC pushes is authorized for exactly that allowlist (PBAC is bypassed for
service tokens on granted endpoints). Credentials are resolved in this order (see
[`GzacClient`](./src/main/kotlin/com/ritense/pdca/service/GzacClient.kt)):

1. the pushed `serviceToken` + `gzacBaseUrl` (persisted in the `plugin_configuration` table,
   so restarts are fine; GZAC re-pushes on discovery and on configuration save);
2. `pdca.gzac.static-token`;
3. Keycloak client credentials (`pdca.gzac.token-url` + `client-id`/`client-secret`;
   application.yml defaults match the standaard gzac-docker-compose m2m-client). Only used
   when no plugin configuration was pushed; the service account then needs the realm roles
   `ROLE_USER` + `ROLE_ADMIN` to pass GZAC's PBAC for document creation.

Note: the created dossier starts the BPMN process, so its "Plan aanmaken"-taak is open while
the plan already exists — complete it with the placeholder button.

## Development

```bash
./gradlew build          # full build -> build/libs/pdca-app-0.1.0.jar
./gradlew buildCaseZips  # rebuild the importable GZAC case-definition zips
docker build -t pdca-app .   # containerize (expects the jar from ./gradlew build)
```

Resetting data: `docker compose down -v` wipes all three databases; the init containers and
Liquibase re-provision everything (same fixed uuids) on the next `docker compose up -d` +
app start. Registers not part of this integration (BRP persoonsgegevens, objectenregister) are
served as display-only stubs from `/api/v1/registers/*`.
