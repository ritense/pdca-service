# GZAC case definitions for the PDCA app

Importable case definitions for the PDCA demo cases. The
`caseDefinitionKey`s match the phase configs the PDCA app seeds, so the PDCA
tabs show the right plan immediately after linking.

| Zip | Case | Subject | Role |
|---|---|---|---|
| `inwonerplan.zip` | Inwonerplan (PDCA) | Persoon (bsn) | Plan case type |
| `binnenhof-renovatie.zip` | Binnenhof-renovatie (PDCA) | Object | Plan case type |
| `intake-werk-participatie.zip` | Intake werk en participatie | Persoon (bsn) | Realistic W&P intake incl. DVKM decision table (see below) |
| `renovatie-intake.zip` | Intake renovatie (PDCA) | Object | Minimal intake (start form → plan task) |
| `werkfit-aanvraag.zip` | Werkfit-aanvraag | — | Product dossier (see below) |

Each zip contains everything the case needs: document definition (JSON
schema), BPMN process with the PDCA loop, start form + task forms (form.io)
with process links, process-document link (startable by the user), case list
columns, search fields and case tabs. `binnenhof-renovatie` and the intake
case types carry the default tabs (Samenvatting/Voortgang/Audit); in
`intake-werk-participatie` the Samenvatting tab is a **widget tab** driven by
per-phase internal case statuses (see below); `inwonerplan` carries **only
the three PDCA tabs** — no standard Valtimo tabs, with the task panel kept
on every tab via `showTasks`.
The plan-dossier zips additionally contain `building-block-link`s to the
bundled building blocks (`pdca-actie`; for `inwonerplan` also
`jobcoaching-aanvragen`) with `startableByUser: false` — normal building
block behaviour, but no entry in the start menu (see below). The plan-dossier
zips are **self-contained**: they bundle those building blocks
(`config/building-block/**` next to `config/case/**`), so one import per zip
suffices — GZAC imports the bundled building blocks first and skips any that
already exist. The standalone zips in `../bouwblokken/` remain for
importing/updating a building block on its own.

## Importing

Admin → Case definitions → **Import** and pick the zip, or via the API:

```bash
curl -X POST -H "Authorization: Bearer $TOKEN" \
  -F "file=@inwonerplan.zip;type=application/zip" \
  -F 'pluginConfigurationMappings={"00000000-0000-4000-8000-00000000dca0":"<your PDCA configuration uuid>"};type=application/json' \
  http://localhost:8080/api/management/v1/case/import
```

The `pluginConfigurationMappings` part maps the placeholder configuration id
in the zip to your PDCA plugin configuration (see "PDCA connected at
import"); leave it out to import dangling and repair afterwards.

Re-importing the same key+versionTag overwrites the non-final version;
otherwise bump the `versionTag` (directory `1-0-0` + `case-definition.json` +
the `camunda:versionTag` in the BPMN).

## PDCA connected at import

The PDCA couplings ship **in the zips**:

- the plan case types carry the three PDCA case tabs of type *external
  plugin* — Planoverzicht (`plan-overview`), Doelen & Acties (`plan-goals`)
  and Evaluaties (`plan-evaluations`); on `inwonerplan` these are the only
  tabs;
- all plan and intake case types carry an `external_plugin_task_form`
  process link on the "(…) aanmaken (PDCA)" task (bundleKey `create-plan`).

A configuration UUID is environment-specific and never belongs in a zip, so
these references ship with the fixed placeholder id
`00000000-0000-4000-8000-00000000dca0`. Connecting them to *your* "PDCA
Planbeheer" plugin configuration (prerequisite: the app is registered as an
external app and the configuration exists, Admin → Plugins → Add plugin) is
one choice at import time:

- **Import via the admin UI** — the import wizard detects the placeholder
  and shows a mapping row for plugin `pdca`: pick your configuration and
  tabs + task-form links are connected the moment the import lands. (The
  `pluginDefinitionKey`/`pluginVersion` fields in the tab and process-link
  json exist for this recognition; the import itself ignores them on the
  task-form link.)
- **Import via the API** — pass the mapping as a multipart part (see the
  curl above), or import without it and repair afterwards: the case
  definition shows a configuration issue and *dangling plugin
  configurations* in case management re-points every placeholder reference
  in one go. On the intake case types (no tabs, only the task-form link)
  that panel cannot identify the plugin — there, re-import with the mapping
  or set the configuration by hand on the task's process link.

Re-importing a zip resets the references to the placeholder; map them again
in the wizard (or repeat the repair).

## Process flow

`start-event` (start form: bsn/object id) → `plan-aanmaken` → `pdca-cyclus`
(loop; the form sets process variable `vervolg`, on *afronden* the process
ends). The tasks `plan-aanmaken` and `pdca-cyclus` have working form.io
fallback forms; optionally replace those process links with the app's
external task forms (`create-plan`, `update-goals`, `evaluate`). The document
schema contains `planId`/`planTitel`/`planStatus` as target fields for the
result mapping of the `create-plan` task form.

The zip ships the `plan-aanmaken` link as the PDCA app's external task form
`create-plan` (type `external_plugin_task_form`, with the placeholder
configuration id — see "PDCA connected at import"). The task fills in the
plan basics (dienstverlening, hoofddoel, positions) and links the plan 1:1
to the dossier; when the dossier already has a plan, the form only offers
"complete task".

Demo input: bsn `111222333` (Erika de Goede) or object `binnenhof-001`
(Binnenhof) — the demo plans are seeded unlinked. Two ways to link them to a
new dossier:

1. **Start form**: fill the *Plan-ID* field with the demo plan id
   (`11111111-1111-1111-1111-111111111111` for Erika,
   `33333333-3333-3333-3333-333333333333` for Binnenhof). The PDCA app reads
   the planId from the dossier content and links the plan under water as soon
   as a tab or task opens the dossier — all demo data is then ready.
2. **"Plan aanmaken" task**: the subject lookup detects the standalone plan
   and offers **Koppel aan dit dossier**.

## The realistic intake: `intake-werk-participatie` with the DVKM decision table

`intake-werk-participatie.zip` (1.0.0, "Intake werk en participatie")
demonstrates a realistic W&P intake: a small start form and a user task per
conversation instead of one big start form, and a first mock of the DVKM
'algorithm' as a **DMN decision requirements graph** — inwonergegevens in,
subdoelgroep + inwonerpositie + gekoppeld hoofddoel out. The intake
deliberately carries **no planafspraken step** (titel, planstatus, looptijd
and contactmomenten): the create-plan task form fills those with defaults
and keeps its contactmomenten grid, and `renovatie-intake.zip` stays the
minimal variant that seeds a complete plan *including* contactmomenten from
one start form. The flow (all tasks `ROLE_USER`; defaults walk the Erika de
Goede demo):

1. **Start: Aanmelding** — bsn, naam, dienstverlening, aanleiding.
2. **Intakegesprek voeren** — the eight DVKM questions (the
   *inwonergegevens*, in the document under `dvkm*`), gespreksnotitie and
   betrokkenen.
3. **Inwonerpositie bepalen (DVKM)** — a business rule task evaluates the
   DMN decision requirements graph in `dmn/` (after import both tables are
   visible and editable in Admin → Case definitions →
   intake-werk-participatie → **Decision tables**). The decision tables
   read process variables, so the task's input mapping offers the eight
   document values as task-local variables
   (`documentDelegateService.findValueByJsonPointerOrDefault('/dvkm…',
   execution, '')`) — that way the inwonergegevens stay in the dossier and
   remain visible in the widget summary after the process has ended:
   `dvkm-subdoelgroep` (the ruleset, hit policy FIRST: hard exclusions
   first — acute situatie, ZRM 1, ontheffing — then decisive criteria, a
   default rule last, the DVKM rationale per rule in the annotation)
   determines the **subdoelgroep**; the top table `dvkm-inwonerpositie`
   (the subdoelgroep catalog, one row per subdoelgroep) translates it to
   the **inwonerpositie** (posities 1 and 4 aggregate two subdoelgroepen,
   the others one) and the **gekoppeld hoofddoel**. The result lands in
   three process variables: `subdoelgroepAdvies`, `inwonerpositieAdvies`
   (one of the **six DVKM inwonerposities**, which the app also seeds as
   the positietypen of the `inwonerplan` case type — valid in every
   dropdown and in the server-side positie validation) and
   `hoofddoelAdvies` (a hoofddoeltype name from the register).
4. **Vervolggesprek: positie, doelen en aanpak** — shows the three-part
   advice (and writes it to the document); *Subdoelgroep*, *Positie*
   and *Hoofddoel* follow the advice and can be overridden, in which case
   *Advies overgenomen? = nee* asks for one of the eight DVKM
   afwijkgronden. Plus doelen and beoogde instrumenten.
   The **Doel** column only offers the subdoelen of the chosen hoofddoel.
   A form.io form inside GZAC cannot reach the PDCA API, so this zip
   carries a copy of the subdoelmapping in the select (`dataSrc: custom`,
   `refreshOn: hoofddoel`); `phase_config.subdoel_mapping` stays the source
   and drives the plan tabs and the create-plan task form. **Change the
   mapping in PDCA Beheer and you change the copy here too** — they are
   seeded identically (Liquibase `003`). Switching the hoofddoel never
   empties a filled row: an already-chosen subdoel stays selectable.
5. **Plan aanmaken (PDCA)** — the external `create-plan` task form, shipped
   in the zip (see "PDCA connected at import"); the prefill shows
   everything the steps collected, plan administration left empty by the
   intake gets defaults (titel "Plan – <naam>", startdatum today, first
   configured plan status), and completing it creates the plan + plan
   dossier. Submitting twice from the same intake does not create a second
   plan: the app recognises the intake and hands back the plan it already
   produced, so the task still completes.

   The W&P rule behind this step is that **an inwoner has at most one active
   plan per plan case type**, which would make the demo a one-shot. The
   aanmelding therefore generates a **fresh elfproef-valid BSN per
   aanmelding** (`calculateValue` on the bsn field, overridable): every run
   is a new inwoner, so a new plan can always be created. The persona stays
   Erika de Goede — only her identifier is new. Type an existing BSN over it
   to walk a real inwoner through the intake; the one-active-plan rule then
   applies and is reported above the button ("rond dat plan eerst af of
   breek het af").

Demo variations are one click away in the intakegesprek: defaults →
subdoelgroep **Ontwikkelen naar werk** / positie **4. Ontwikkelen richting
werk** / hoofddoel **Duurzaam aan het werk**; *acute situatie = ja* →
Crisis/positie 1; *ontheffing = ja* → Ontheffing/positie 1;
*werknemersvaardigheden = goed* + *motivatie = aanwezig* → Snel aan het
werk/positie 5.

### Phase statuses and the widget Samenvatting tab

The BPMN sets an **internal case status** per phase ("Fase: …" service
tasks calling `documentDelegateService.setInternalStatus`):
`intakegesprek` → `positie-bepalen` → `vervolggesprek` →
`plan-aanmaken` → `afgerond`. The
statuses are part of the zip (`case/internal-status/`) and show up as a
colored *Fase* tags column in the case list.

The intake's **Samenvatting tab is a widget tab** (`case/widget-tab/`,
with the task panel kept visible via `showTasks`), split into per-phase
**sections by divider widgets** — each section renders as its own grid,
so the gap-free layout keeps widgets inside their phase section. Every
divider carries the same `displayConditions` as its section:

- **Intakefase** (above the first divider) — a metroline of the phases
  the dossier passed through (internal-status history; the last entry is
  the current phase).
- The **Aanmelding** section (Inwoner person card + Aanmeldgegevens) is
  visible from the start; every later section appears from the phase in
  which its data exists (`displayConditions` on `case:internalStatus`)
  and stays after afronding: **Intakegesprek** (Gespreksverslag +
  Betrokkenen), **Positie, doelen en aanpak** (DVKM-advies en positie +
  Beoogde instrumenten + Doelen) and on `afgerond` the **Resultaat**
  section: **Aangemaakt plan** with an *Open plan-dossier* button to the
  created plan dossier.
- **DVKM-inwonergegevens** shows the eight DVKM answers from the dossier
  (`doc:dvkm*`) from the moment the intakegesprek has been recorded, and
  keeps showing them after afronding alongside the advice in *DVKM-advies
  en positie*.

The *Aangemaakt plan* widget and the case list read `planId`, `planTitel`,
`planStatus` and `planDossierId` from the dossier: the `create-plan` task
form submits them **`doc:`-prefixed**. Unprefixed submission keys become
process variables, which end with the process instance and are then no
longer resolvable for a widget.

Rebuilding the zips after changes:
`./gradlew buildGzacZips` (from the project root; per case also individually
via `./gradlew caseZip-inwonerplan`, per building block via
`./gradlew bouwblokZip-pdca-actie`), or `./build-zips.sh` here.

## Building blocks: executing actions with a GZAC building block

Next to the cases, the repo ships importable **building blocks** in
`../bouwblokken/` (same import endpoint/button as the case zips). The bundled
building block `pdca-actie.zip` executes an action of a plan:

`start` (start form `pdca-actie-start`, delivers the start payload) →
service task **update-actie GESTART** → user task **Actie: ${actieTitel}**
(form `pdca-actie-afronden`, result → document field `resultaat`) → service
task **update-actie AFGEROND** → `einde`. The service tasks are
external-plugin process links to the `update-actie` action of the PDCA
plugin, reading their properties from the building block document
(`doc:/actieId`, `doc:/resultaat`).

**The building block document is the interface**: the app and the process
exchange all data through it, so the contract is the building block's
document schema and the state is visible in the dossier's Building blocks
panel. The PDCA app starts the building block by **submitting its start
form** on the plan dossier (`POST /api/v1/process-link/{id}/form/submission`)
— the route GZAC's own UI also uses (the start-by-key endpoint cannot start
blueprint-owned, BB:-tagged processes). The form's hidden `bouwblokStart.*`
fields land on the **dossier document** under `/bouwblokStart`; the
building-block link's **input mappings** (the standard case→bouwblok
mapping, shipped in the plan-dossier zips) copy them into the building block
document before the process runs. A custom building block must therefore
keep that start-form link (or link its own form with the same
`bouwblokStart.*` fields) and get matching input mappings on its
building-block link.

The workflow:

1. Import the plan-dossier zips into GZAC (they bundle `pdca-actie`; the
   standalone `pdca-actie.zip` exists for importing/updating it separately).
   Those contain a `building-block-link` to the building block with the
   input mappings (`/bouwblokStart/...` → building block document, plus
   `pv:actieTitel` for the task title) and `startableByUser: false`: the
   building block gets the normal runtime behaviour (building block
   instance, its own building block document, populated Building blocks
   panel) but does **not** appear in the dossier's start menu. In case
   management (Actions tab) this is the *Visibility* column (toggle *Visible
   in start menu*).
2. Open **PDCA Beheer → Actie-bouwblokken** and link the building block to a
   plan case type: pick the building block (recognised by process version
   tag `BB:<key>:<version>`), the PDCA plugin configuration and the
   doeltype(s) under which the action is available. On save the app writes
   the configuration into the building-block link's
   `pluginConfigurationMappings` (key `external-plugin:pdca@<version>`) —
   the standard GZAC route, so the process links keep their portable
   BUILDING_BLOCK reference. The mapping stays empty in the zip
   (configuration UUIDs are environment specific) and a re-import resets it;
   the app restores it automatically before every start.
3. On the plan page (*Doelen & Acties*) every doel has the **Actie kiezen**
   button on the right (greyed out when no building blocks match the
   doeltype); choosing one creates the action and starts the building block
   process on the plan dossier. GZAC creates a building block document and
   rewrites the business key to it; the task appears in the dossier's task
   list through the building block resolution. Completing the task with a
   result sets the action to *Afgerond* in the tab. Next to the dropdown is
   **Handmatige actie** for a local action without a process.

**The start payload** (fields the app always submits, written to the dossier
document under `/bouwblokStart` and mapped into the building block document
by the link's input mappings):

| Field | Meaning |
|---|---|
| `actieId` | Correlation key — required in every `update-actie` callback (`doc:/actieId`) |
| `actieTitel`, `actieOmschrijving` | Label/description from the koppeling |
| `planUuid`, `doelUuid` | Open Plan context |
| `subjectId` | Subject of the plan: bsn (persoon) or object id, from `plan.domeinregister` |

**Reporting back** can happen anywhere in a (custom) process with the action
`update-actie` (properties: `actieId`, `gebeurtenis` = `GESTART` /
`TER_BEOORDELING` / `AFGEROND` / `AFGEWEZEN`, optional
`resultaat`/`toelichting` — read them from the building block document with
`doc:` references).

**Creating your own building block** = copy `pdca-actie`: new key +
versionTag (directory, definition json AND
`camunda:versionTag="BB:<key>:<version>"`), extend the document schema with
your own fields, draw timers/gateways/extra tasks in between and reuse the
`update-actie` links. One limitation: one PDCA plugin configuration per
building block per case type (the mapping lives on the case type's
building-block link).

## Product building blocks: requesting a product/voorziening with a GZAC building block

Next to actie building blocks there are **product building blocks**
("product X aanvragen"); bundled: `jobcoaching-aanvragen.zip`. All product
logic lives in the building block itself — the PDCA app creates nothing at
start and only determines (PDCA Beheer → Product-bouwblokken) under which
plan case type and doeltype(s) it is available. On the plan page the
instruments section has the **Start product** button (next to **Handmatig
product**), greyed out when no product building blocks match the doeltype.

The bundled process: user task **Jobcoaching-aanvraag beoordelen** (form
with a besluit toekennen/afwijzen — process variables, since the gateway
condition cannot read the document) → on granting, the plugin action
**aanmaak-instrument** puts the instrument in the plan (Open Plan) and its
result mapping writes the returned `instrumentUuid` to
`doc:/instrumentUuid` → user task **Jobcoaching: uitvoeren en afronden**
(document fields `productResultaat`/`productToelichting`) → plugin action
**update-instrument** sets the instrument to afgerond with resultaat
behaald/gefaald (toelichting → PDCA overlay). On rejection the process ends
without anything appearing in the plan.

The start payload is the product subset of the table above: `planUuid`,
`doelUuid`, `subjectId` (no actie fields). The **product identity** (titel +
producttype URN) lives as literal actionProperties on the aanmaak link in
the building block zip — a custom product building block is therefore a copy
of `jobcoaching-aanvragen` with those two values (and the forms/task names)
changed. Don't forget to also add the new building block as a
`building-block-link` (with the input mappings and `startableByUser: false`)
to the plan-dossier zips and to bundle it into those zips (the
`caseZipBouwblokken` map in `build.gradle.kts` + `bouwblokken_for` in
`build-zips.sh`).

## Product dossiers: requesting a product as a full case

Next to the product *building blocks* there are **product dossiers**
(their own PDCA Beheer section, Product-dossiers); bundled:
`werkfit-aanvraag.zip` (product Werkfit Traject WIO). Every request from the
plan page becomes a **case of its own**: own dossier and task list, own
PBAC roles, documents — and optionally a **zaak**: link the case type to a
zaaktype in GZAC case management (*Connected zaak type* with *Automatically
create for each case*) and every aanvraagdossier is created as a zaak. The
zip deliberately ships no zaaktype-link (zaaktype URL and plugin
configuration UUIDs are environment-specific), so it also works in
environments without ZGW.

Import `werkfit-aanvraag.zip` like any case zip and **activate the version**
in case management — an inactive case type stays out of every list,
including the productdossiertype dropdown in PDCA Beheer.

The document schema is the contract (same fields as a product
bouwblokdocument, plus `productTitel` and `dossierUrn` — the marker fields
by which PDCA Beheer recognises product case types). The pdca process links
ship **FIXED and dangling** (no configuration UUID); the app repairs them to
the configuration chosen in PDCA Beheer on save and before every start, via
GZAC's plugin-configuration repair. `startableByUser` is `false`: only the
app starts these dossiers.

The flow: the app creates the dossier (`new-document-and-start-process`,
content = start payload) and writes the dossier's own URN
(`urn:pdca:gzac:dossier:<dossiertype>:<documentId>`) into the document →
user task **Werkfit-aanvraag beoordelen** → on granting,
**aanmaak-instrument** puts the instrument in the plan with that URN in the
instrument's **`zaak`** field in Open Plan (result mapping →
`doc:/instrumentUuid`) → user task **uitvoeren en afronden** →
**update-instrument** (afgerond + resultaat). The plan page reads the zaak
URN from the register and shows a **Dossier** button on the instrument that
jumps to the aanvraagdossier. On rejection the process ends without
anything in the plan.

**A new product dossier** = copy `werkfit-aanvraag`: new case key
(directory, `case-definition.json`, BPMN id + `CD:<key>:<versie>` version
tag, process-document-link), change the literal titel/product-URN in the
`instrument-aanmaken` process link and adjust the forms; add the key to
`caseZipBouwblokken` in `build.gradle.kts` and to `build-zips.sh`.
