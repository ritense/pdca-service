# PDCA Prototype App

Standalone prototype for PDCA (Plan-Do-Check-Act) plan management: a Spring Boot 3 / Kotlin
backend with Vite + React + Carbon iframe bundles. It speaks the Valtimo external-plugin
**"URL app" contract** (discovery at `/api/host/plugins`, iframe bundles at `/bundles/*`), so it
can be registered in a Valtimo/GZAC instance by URL — but it builds and runs entirely on its own:
there are **no build-time dependencies** on the Valtimo repository, only the runtime HTTP
integration.

## Prerequisites

- **JDK 21** (Gradle toolchain)
- **Docker** (PostgreSQL via docker compose)
- **Node.js 18+** — only needed to rebuild the frontend bundles

## Boot

```bash
docker compose up -d     # PostgreSQL 17 on localhost:54322 (db/user/pass: pdca)
./gradlew bootRun        # app on http://localhost:8090
```

Or in one step: `./gradlew bootRunWithDocker` (note: the `dockerUp` task invokes
`/usr/local/bin/docker`, i.e. it assumes macOS/Docker Desktop).

Liquibase creates the schema and seeds demo data on first start. The startup banner lists all
useful URLs; quick checks:

- Health: <http://localhost:8090/health>
- Discovery manifest: <http://localhost:8090/api/host/plugins>
- Plans API: `GET /api/v1/plans/{id}`, seeded plan `11111111-1111-1111-1111-111111111111`

> Port note: `8090` is also the default port of the Valtimo plugin host — don't run both at once,
> or override with `server.port`.

## Frontend bundles

The React sources live in [`frontend/`](./frontend/); Vite builds them into
`src/main/resources/static/bundles/react/`, which the backend serves at `/bundles/react/*`. The
build output is **gitignored**, so build it once after a fresh clone:

```bash
cd frontend
npm install
npx vite build
```

(The legacy hand-written bundles at `static/bundles/*.js` are committed and served as-is.)

## Using it from Valtimo/GZAC

Runtime integration only: register the app by URL (`http://localhost:8090`) in a GZAC instance
that supports external plugin tabs; the case tabs (`plan-overview`, `plan-goals`,
`plan-evaluations`) and the `pdca-admin` view are loaded as sandboxed iframes and talk to their
host page via the `valtimo-plugin`/`valtimo-host` postMessage protocol implemented in
[`frontend/src/shared/bridge.ts`](./frontend/src/shared/bridge.ts).

## Development

```bash
./gradlew test           # unit tests (H2 in-memory database)
./gradlew build          # full build → build/libs/pdca-app-0.1.0.jar
docker build -t pdca-app .   # containerize (expects the jar from ./gradlew build)
```

The database keeps its data in the `pdca-database-data` Docker volume; `docker compose down -v`
resets it (Liquibase re-seeds on next boot).
