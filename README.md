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
(`/api/v1/pdca/*`): fase/voortgang per doel, evaluatietype + doelvoortgang + actiepunten per
contactmoment, acties, betrokkenen and phase-configs. Cross-register references are URNs, e.g.
`instrument.product = urn:pdca:openproduct:producttype:<code>` and
`plan.domeinregister = urn:pdca:brp:persoon:<bsn>`.

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

## Development

```bash
./gradlew build          # full build -> build/libs/pdca-app-0.1.0.jar
docker build -t pdca-app .   # containerize (expects the jar from ./gradlew build)
```

Resetting data: `docker compose down -v` wipes all three databases; the init containers and
Liquibase re-provision everything (same fixed uuids) on the next `docker compose up -d` +
app start. Registers not part of this integration (BRP persoonsgegevens, objectenregister) are
served as display-only stubs from `/api/v1/registers/*`.
