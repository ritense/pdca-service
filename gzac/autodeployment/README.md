# Autodeployment set for the local GZAC stack

Everything GZAC needs for the PDCA app and the verzoek route (ARCHITECTURE
§9), in the form GZAC's autodeployment reads at boot. The files live here so
they are versioned with the app and can be imported elsewhere; nothing in the
Valtimo monorepo changes. Values that belong to the local gzac-docker-compose
stack (plugin configuration ids, tokens, URLs) are placeholders with the local
values as default, so another environment overrides them with environment
variables.

## What is in it

| Path | Mechanism | Content |
|---|---|---|
| `resources/config/global/external-plugin/pdca.externalplugin.json` | external plugin descriptor | On the stack's plugin host: the four register plugin packages (uploaded from `plugin-packages/`, see below) and their configurations. The PDCA app as URL app with its configuration `00000000-0000-4000-8000-00000000dca0` (the id the case zips refer to), its endpoint and capability grants and the four contract bindings. |
| `resources/config/plugin-configurations/pdca.pluginconfig.json` | plugin configuration autodeployment | The Verzoek plugin configuration: verzoek type `instrument` → case `instrument-verzoek`, process `instrument-verzoek`, copy strategy full. |
| `resources/config/global/object-management/instrument-verzoek.object-management.json` | object management | The objecttype `InstrumentVerzoek` under the stack's Objecten API and Objecttypen API plugins. |
| `resources/config/case/instrument-verzoek/1-0-0/zgw/zaak-type-link/instrument-verzoek.zaak-type-link.json` | case definition (overlay on the zip's folder) | The zaaktype link the Verzoek plugin needs to create the case; the stack's dev zaaktype, without creating a zaak. |
| `dev/setup-registers.sh` | Django shells in the stack's containers | Creates the objecttype (fixed uuid `00000000-0000-4000-8000-0000dca00001`, published version 1 with `../objecttypen/instrument-verzoek.schema.json`) in the Objecttypen API, registers it in the Objecten API and lets the stack's API token read and write its objects. |
| `dev/jdk-hosts` | JDK hosts file | Resolves `host.docker.internal` for a GZAC that runs on a macOS host. |
| `gzac-dev.init.gradle.kts` | Gradle init script | Puts `resources/`, every case definition under `../case-definitions/` and the built plugin packages on the dev app's classpath, and sets two environment values. |

## Running GZAC with it

From the root of this repository, with the GZAC stack (`gzac-docker-compose`)
and its plugin host running:

1. Build the register plugins once: `./gradlew buildPlugins` (Node 22+, a
   sibling `valtimo` checkout). The init script copies
   `plugins/<name>/dist/<name>-1.0.0.zip` onto the classpath as
   `plugin-packages/<name>-1.0.0.zip`, where the descriptor expects them.
2. Prepare the registers of the GZAC stack: `sh gzac/autodeployment/dev/setup-registers.sh`.
3. Start the PDCA stack with the objecttype URL for the Open Product seed:

   ```bash
   PDCA_INSTRUMENT_VERZOEK_OBJECTTYPE=http://host.docker.internal:8011/api/v2/objecttypes/00000000-0000-4000-8000-0000dca00001 \
     ./gradlew bootRunWithDocker
   ```

4. Start GZAC from the root of the Valtimo monorepo:

   ```bash
   ./gradlew :backend:apps:dev:bootRun -I <pdca-app>/gzac/autodeployment/gzac-dev.init.gradle.kts
   ```

At boot GZAC imports the five case definitions, creates the object management
and the Verzoek plugin configuration, registers the PDCA app and the plugin
configurations, and on its next discovery cycle uploads the plugin packages
to the plugin host, discovers the PDCA app's manifest, binds the contracts
and pushes the configuration to the app, which then repairs the instrument
dossier's process links itself. Everything is idempotent: change a file,
restart GZAC the same way.

### The two environment values

The containers of the stack address the host as `host.docker.internal`, and
that name travels in the URLs they hand out: the Objecten API renders every
objecttype URL with the Objecttypen API address it knows
(`http://host.docker.internal:8011/api/v2/`), and the Objecten API notifies
Open Notificaties with the object URL of the request that created it. Open
Notificaties matches subscription filters literally, and verifies the
callback URL of a subscription by calling it from its container. So GZAC's
subscription must carry `host.docker.internal` too, and the init script sets:

| Variable | Value | Why |
|---|---|---|
| `VALTIMO_OBJECTTYPEN_API_URL` | `http://host.docker.internal:8011/api/v2/` | the objecttype filter of the subscription equals the URL the Objecten API notifies with |
| `VALTIMO_API_URL` | `http://host.docker.internal:8080/api/v1/` | the subscription's callback URL is reachable from the Open Notificaties container |

macOS does not resolve `host.docker.internal` on the host itself, so the
init script also gives the JDK its own hosts file (`dev/jdk-hosts`,
`-Djdk.net.hosts.file`): GZAC can fetch the verzoek object and the
objecttypes under that name. Adding `127.0.0.1 host.docker.internal` to
`/etc/hosts` does the same for every process, after which an IntelliJ run
with the two values above works as well.

## Importing elsewhere

The case definitions go in as zips (`../case-definitions/README.md`). The
other files are autodeployment files: put them on the classpath of that
GZAC (its `config/` resources) and set the environment variables they
reference, or recreate their content in the admin UI — the descriptor lists
exactly which grants and bindings the PDCA configuration needs. The
objecttype and token permission of `dev/setup-registers.sh` are a one-time
action in that environment's Objecttypen and Objecten API; the Open Product
acties get that environment's objecttype URL through the seed
(`docker/openproduct/reference_data.py`) or the Open Product admin.
