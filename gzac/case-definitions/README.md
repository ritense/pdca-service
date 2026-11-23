# GZAC case definitions for the PDCA app

Importable case definitions for the PDCA demo cases. The
`caseDefinitionKey`s match the phase configs the PDCA app seeds, so the PDCA
tabs show the dossier's plan as soon as the app has created the dossier.

| Zip | Version | Case | Subject | Role |
|---|---|---|---|---|
| `inwonerplan.zip` | 1.7.0 | Inwonerplan (PDCA) | Persoon (bsn) | Plan case type |
| `binnenhof-renovatie.zip` | 1.7.0 | Binnenhof-renovatie (PDCA) | Object | Plan case type |
| `intake-werk-participatie.zip` | 1.3.0 | Intake werk en participatie | Persoon (bsn) | Realistic W&P intake incl. DVKM decision table (see below) |
| `renovatie-intake.zip` | 1.5.0 | Intake renovatie (PDCA) | Object | Minimal intake (start form → plan task) |
| `instrument-verzoek.zip` | 1.2.0 | Instrumentaanvraag | — | Instrument dossier for every product requested for a plan, started by GZAC's Verzoek plugin (see below) |

Each zip contains everything the case needs: document definition (JSON
schema), BPMN process, process-document link, case list columns, search
fields and case tabs. The intake zips add their start and task forms
(form.io) with process links; the plan-dossier zips carry no forms at all,
only the link to the task building block `pdca-taak` (see "Plan dossiers"
and "The task building block"). `binnenhof-renovatie` and the intake
case types carry the default tabs (Samenvatting/Voortgang/Audit); in
`intake-werk-participatie` the Samenvatting tab is a **widget tab** driven by
per-phase internal case statuses (see below); `inwonerplan` carries **only
the three PDCA tabs** — no standard Valtimo tabs — and all three set
`showTasks`, so GZAC's task panel stays visible on every plan tab. In the
other zips only the first tab sets `showTasks`, so GZAC's task panel shows
next to the first tab and the other tabs use the full width.

## The document process and its Epistola templates

Next to the case definitions the app needs two artefacts for its documents
(the evaluatieverslag of a completed evaluation), described in
[`../system-processes/README.md`](../system-processes/README.md):

- **`../system-processes/pdca-document.zip`** — the global process
  `pdca-document`, outside any case definition, which the app starts on the
  plan dossier. Its PDCA process links carry the placeholder
  `00000000-0000-4000-8000-00000000dca0` (as in the zips here), its
  Epistola process links the placeholder
  `00000000-0000-4000-8000-0000dca00005`. Import it in Admin → Process
  management → **Import** and map both placeholders: the PDCA one to your
  PDCA configuration, the Epistola one to GZAC's Epistola plugin
  configuration.
- **`../epistola/catalogs/pdca-documenten/`** — the Epistola catalog with
  the template `evaluatieverslag` the process renders; import it into the
  tenant of that Epistola configuration and release it.

`./gradlew buildGzacZips` also builds the system process zip
(`../system-processes/build-zips.sh` on its own). For the local stack the
dev setup ([`../dev-setup/`](../dev-setup/README.md)) puts both in place.

## The task building block: `pdca-taak`

Every actie added to a plan — on the Doelen tab, or from an actiepunt of an
evaluation — becomes a task in the plan dossier's task list (ARCHITECTURE
§6.8). The task is the GZAC **building block**
`../bouwblokken/pdca-taak.zip` (version 1.0.0, final), a generic task that
the plan case types link:

- **The building block** — its own document (`titel`, `omschrijving`,
  `deadline`, and for the task of an actie `actieId`, `planUuid`,
  `doelUuid`; then `uitkomst` and `resultaat` from its form) and the
  process `pdca-taak` (`BB:pdca-taak:1.0.0`): start form `pdca-taak-start`
  (title, description and deadline, hidden fields for the actie, all under
  `bouwblokStart.*`) → when the document names an actie, service task
  **actie-taak-gestart** (synchronous: it tells the app which process
  carries the actie) → one user task, named after `/titel` and due on
  `/deadline` (form `pdca-taak-afronden`: uitkomst *uitgevoerd* or
  *vervallen* and a required `resultaat`) → for an actie, service task
  **actie-gereed** (asynchronous, retried every minute five times; it ends
  the actie as afgerond or vervallen with the resultaat) → end. Without an
  actie it is a task like any other, with a title given at its start. Its
  PDCA process links name the plugin (`pdca` 0.2.0) but no configuration:
  GZAC resolves that through the case type's building-block link.
- **The link on the plan case types**
  (`building-block-link/<key>.case-building-block-links.json`) copies the
  start data from the plan dossier's `/bouwblokStart` into the building
  block document; `startableByUser` is `false`, so the building block is
  not in the dossier's start menu and is always the task of an actie
  there. The plan case types' schemas declare `/bouwblokStart`, where the
  start form writes. Another case type can link the building block the
  same way to offer a plain task, with `startableByUser` `true`.
- **Starting** is up to the app: it submits the building block's start
  form on the plan dossier, the way GZAC's own UI starts a building block,
  with every start field (empty ones too, so nothing of the previous start
  stays behind). GZAC's import does not map placeholder configuration ids
  inside a building-block link, so the link ships with empty
  `pluginConfigurationMappings` and the app sets the mapping
  `external-plugin:pdca@0.2.0` → its configuration before a start when it
  is missing (also after a re-import, which resets it).

Import the building block before the plan case types that link it (Admin →
Building blocks → **Import**, or `POST
/api/management/v1/building-block/import` with the zip as `file`). The app
needs the grants of its manifest for this (ARCHITECTURE §3.4): accept the
changed manifest in GZAC, or the app's start of a task is refused and the
actie is not added. `./gradlew buildGzacZips` builds the zip
(`../bouwblokken/build-zips.sh` on its own); the dev setup imports it.

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

## Versions

A released case definition version never changes. The rules:

- **Every change is a new version.** Any change to a definition's files —
  schema, BPMN, forms, tabs, links — goes into a new version directory
  `config/case/<key>/<x-y-z>/` (the old one is removed; a zip holds one
  version), with the same version as `versionTag` in
  `case/definition/<key>.case-definition.json` and as
  `camunda:versionTag="CD:<key>:<x.y.z>"` in the BPMN. Rebuild the zip
  (`./gradlew buildGzacZips`) and commit sources and zip together.
- **Released versions are final.** A version on `main` has
  `"final": true`. GZAC treats a final version as immutable: importing or
  autodeploying a final version it already holds is skipped without a
  message — also when the files differ, which is why a released version is
  never edited in place.
- **Non-final only while unreleased.** While a new version is being
  developed on a branch it may carry `"final": false`, so it can be
  re-imported in place on a development GZAC. GZAC accepts a non-final
  version only in a draft environment (`valtimo.draft.environments`, by
  default the Spring profiles `dev`, `test` and `inttest`, or
  `valtimo.draft.enabled`); elsewhere the import fails, and an
  autodeployment of it stops GZAC's start. Set `"final": true` before the
  version is merged.

All five definitions here are final releases (see the table above).

## PDCA connected at import

The PDCA couplings ship **in the zips**:

- the plan case types carry the three PDCA case tabs of type *external
  plugin* — Planoverzicht (`plan-overview`), Doelen (`plan-goals`)
  and Evaluaties (`plan-evaluations`); on `inwonerplan` these are the only
  tabs;
- the intake case types carry an `external_plugin_task_form` process link
  on their last task, "(…) aanmaken (PDCA)" (bundleKey `create-plan`);
- the instrument dossier `instrument-verzoek` carries three `external_plugin`
  process links on its service tasks, the plugin actions
  `aanmaak-instrument` and `update-instrument` (once for afronden, once
  for annuleren).

A configuration UUID is environment-specific and never belongs in a zip, so
these references ship with the fixed placeholder id
`00000000-0000-4000-8000-00000000dca0`. Connecting them to *your* "PDCA
Planbeheer" plugin configuration (prerequisite: the app is registered as an
external app and the configuration exists, Admin → Plugins → Add plugin) is
one choice at import time:

- **Import via the admin UI** — the import wizard detects the placeholder
  and shows a mapping row for plugin `pdca`: pick your configuration and
  tabs and process links are connected the moment the import lands. (The
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

## Plan dossiers

A plan dossier (`inwonerplan`, `binnenhof-renovatie`) only comes into being
from an intake: the `create-plan` task form at the end of
`intake-werk-participatie` (→ `inwonerplan`) or `renovatie-intake`
(→ `binnenhof-renovatie`) creates the plan in Open Plan and then a new plan
dossier through the GZAC API (`new-document-and-start-process`, process
definition key = case key), linked 1:1 to the plan. Users cannot create a
plan dossier by hand: the process-document link keeps
`canInitializeDocument: true` for the app but sets `startableByUser: false`,
so the case has no entry in the Start menu.

The plan-dossier process has **no user tasks**: `start-event` →
`einde` ("Plandossier aangemaakt"). There are no start form, task forms or
process links. The plan is kept up to date in the PDCA tabs (Planoverzicht,
Doelen, Evaluaties); every actie added there runs as the task building block
`pdca-taak` on the plan dossier (see "The task building block"), and a
product requested there — or by the intake when the plan was created — gets
an instrument dossier of its own (see "The instrument dossier").

The app fills the dossier document on creation:

| Field | Content |
|---|---|
| `onderwerp` | Subject line: name of the inwoner or the object (as entered in the intake, otherwise from the register, otherwise the plan titel) |
| `bsn` + `naam` / `objectId` + `objectNaam` | Subject of the plan, from `plan.domeinregister` |
| `planId`, `planTitel` | Reference to the plan in Open Plan |
| `bouwblokStart` | Start data of the most recently started task building block — actie, title, description, deadline — (written by its start form, read by the building-block link) |

The case list shows **Onderwerp** (`doc:onderwerp`) as its first column,
followed by BSN (`inwonerplan`) or Object-id (`binnenhof-renovatie`),
*Aangemaakt op* and *Gewijzigd op*.

## The realistic intake: `intake-werk-participatie` with the DVKM decision table

`intake-werk-participatie.zip` (1.3.0, "Intake werk en participatie")
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

1. **Start: Aanmelding** — bsn, naam, dienstverlening (Terug naar werk /
   Inburgering (PIP) / Inkomensondersteuning, values `werk`/`pip`/`inkomen`),
   aanleiding.
2. **Intakegesprek voeren** — the eight DVKM questions (the
   *inwonergegevens*, in the document under `dvkm*`), gespreksnotitie and
   betrokkenen, one of them marked as procesbegeleider.
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
   afwijkgronden. Plus doelen and the instruments to request (see "Intake
   forms").
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
   dossier and then requests the instruments, each in its own instrument
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

### Intake forms

The intake forms of both intake case types (`intake-werk-participatie`,
`renovatie-intake`) are plain form.io forms shown in GZAC's task modal
(about 750px wide), so their data grids stay narrow: help texts sit once on
the grid instead of on every row and the "add" buttons carry Dutch labels.
They record what is agreed at intake time; progress (voortgang, uren,
effectiviteit) is recorded later in the plan. The instruments are the ones
to **request once the plan exists**: plan creation files a verzoek per row,
so each gets its own instrument dossier (see "The instrument dossier"); the
intake gives them no status. The row shapes are the input of the
`create-plan` prefill:

| Grid | Row fields |
|---|---|
| `doelen` | `doelType`, `beschrijving`, `status`, `startdatum`, `streefEinddatum` |
| `instrumenten` | `product` (producttype code; the select shows the product name), `doel` (the subdoel it is requested under) |
| `betrokkenen` | `naam`, `rol`, `organisatie`, `email`, `procesbegeleider` |
| `contactmomenten` (renovatie-intake only) | `datum`, `evaluatieType`, `status`, `notitie`, `deelnemers` |

- **Status** of doelen: `gepland`, `gestart` or `afgerond` (Gepland /
  Gestart / Afgerond); of contactmomenten: `gepland` or `afgerond`.
- **Beoogde einddatum** (`streefEinddatum`) of a doel: an optional date
  picker per row, stored as form.io's date value (date, time and offset;
  the app reads the date part). Custom validation on the field applies the
  app's rules for a subdoel: not in the past and, in `renovatie-intake`,
  not after the plan's `streefEinddatum`; an afgerond doel is not checked.
- **Startdatum** (`startdatum`) of a doel: an optional date picker per row
  like the beoogde einddatum, not after it; without one the subdoel starts
  on the plan's startdatum. The intake-werk-participatie Samenvatting tab
  shows both dates as columns in the Doelen table.
- **Demo defaults**: every subdoel gepland, with a startdatum and beoogde
  einddatum in the second half of 2027 that follow the order the subdoelen
  come up in, so sorting them on the Doelen tab shows a difference;
  `renovatie-intake` runs its plan from 1 July to 31 December 2027.
- **Products** in the selects are producttypen seeded in Open Product, all
  with a `StartInstrument` actie (`renovatie-intake` offers only the
  objectdomein ones); the demo defaults put each under a subdoel with a
  matching thema, so the create-plan step offers them as requestable.
- **Procesbegeleider**: a checkbox column in the betrokkenen grid. At most
  one betrokkene can be marked (custom validation on the grid); that
  betrokkene becomes the plan's procesbegeleider.

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
  Aan te vragen instrumenten + Doelen) and on `afgerond` the **Resultaat**
  section: **Aangemaakt plan** with an *Open plan-dossier* button to the
  created plan dossier.
- **DVKM-inwonergegevens** shows the eight DVKM answers from the dossier
  (`doc:dvkm*`) from the moment the intakegesprek has been recorded, and
  keeps showing them after afronding alongside the advice in *DVKM-advies
  en positie*.

The *Aangemaakt plan* widget and the case list read `planId`, `planTitel`
and `planDossierId` from the dossier: the `create-plan` task form submits
them **`doc:`-prefixed**, once the plan is complete with its plan dossier.
The plan's status is not copied into the intake: it changes in Open Plan
and is shown on the plan dossier's tabs. Unprefixed submission keys become
process variables, which end with the process instance and are then no
longer resolvable for a widget.

Rebuilding the zips after changes:
`./gradlew buildGzacZips` (from the project root; per case also individually
via `./gradlew caseZip-inwonerplan`), or `./build-zips.sh` here.

## The instrument dossier: `instrument-verzoek`

Every product requested for a PDCA plan becomes a dossier of this one type,
whatever the product — and every instrument in a plan is such a product:
the plan page's "Instrument aanvragen" and the plan creation from an intake
(one request per instrument of the intake) are the only ways in. Neither
starts the dossier itself: they file a **verzoek** — an object in the
Objecten API — and GZAC's **Verzoek plugin** turns that object into the
dossier (ARCHITECTURE §6.2). Which products can be requested
is knowledge of Open Product (the actie `StartInstrument` on the producttype,
see "Making a product requestable"); this dossier knows no product.

The flow: verzoek object created → Open Notificaties notifies GZAC → the
Verzoek plugin creates the dossier with the verzoek data as its document and
starts `instrument-verzoek` → **aanmaak-instrument** puts the instrument in
the plan at once (titel, product URN and start status from the document; the
dossier's own URN lands in the instrument's `zaak` field in Open Plan, which
gives the plan page its **Dossier** button; result mapping →
`doc:/instrumentUuid`) → user task **Instrument uitvoeren en beëindigen**
(form `instrument-afronden`: the afloop `instrumentAfloop`, *Afronden* or
*Annuleren*; on afronden the `productResultaat` + `productToelichting`, on
annuleren the required `annuleringsReden`) → gateway on
`instrumentAfloop` → **update-instrument** with afgerond + resultaat (the
toelichting goes to the PDCA overlay) or with geannuleerd (the reason
becomes the instrument's afbreekreden) → end *Instrument afgerond* or
*Instrument geannuleerd*.

This dossier is the instrument's **only owner**: the plan page shows its
status and the **Dossier** button, but offers no Afronden or Afbreken for
it, and aborting or removing its subdoel, or closing the plan, waits until
the dossier has ended it.

All three service tasks run asynchronously and are retried every minute,
five times (`failedJobRetryTimeCycle` `R5/PT1M`), before they become an
incident on the dossier; both actions are safe to retry
(`aanmaak-instrument` returns the instrument it created before,
`update-instrument` leaves an instrument that already has the requested
status alone). So completing *Instrument uitvoeren en beëindigen* always
succeeds for the behandelaar, also while a register or the app is briefly
unavailable; the instrument's status in the plan follows shortly after,
once the job has run.

The document schema is the verzoek data (copy strategy *full*): `planUuid`,
`doelUuid`, `subjectId`, `productTypeUuid`, `productTypeCode`, `productTitel`,
`productUrn`, `startStatus`, `instrumenttype` and `aangevraagdOp`, plus
`instrumentUuid`, `instrumentAfloop`, `productResultaat`,
`productToelichting` and `annuleringsReden`, written during the process.

The pdca process links ship with the placeholder configuration id and are
connected at import like the other zips (see "PDCA connected at import");
the app does not touch them. `startableByUser` is `false`: only the Verzoek
plugin starts these dossiers.

### Setting up the verzoek route in GZAC

For the local gzac-docker-compose stack all of this is done by the dev
setup in [`../dev-setup/`](../dev-setup/README.md). The steps below are what
it does, for any other environment. One-time
configuration in the GZAC stack (the local gzac-docker-compose has all the
parts: Objecten API on 8010, Objecttypen API on 8011, Open Notificaties on
8002, Open Zaak on 8001):

1. **Objecttype** — create the objecttype `InstrumentVerzoek` in the
   Objecttypen API with
   [`../objecttypen/instrument-verzoek.schema.json`](../objecttypen/instrument-verzoek.schema.json)
   as its published version 1. Its URL
   (`<objecttypen api>/api/v2/objecttypes/<uuid>`) is what the
   StartInstrument acties in Open Product refer to.
2. **Notifications** — the Objecten API must publish on the `objecten`
   kanaal of Open Notificaties (Objecten API admin: the Notificaties API
   service and the kanaal registration), and GZAC must be reachable for
   Open Notificaties as webhook target; the Notificaties API plugin creates
   the abonnement.
3. **GZAC plugins** — configure the Objecttypen API, Objecten API and
   Notificaties API plugins, and an **object management** configuration for
   `InstrumentVerzoek`. Its Objecten API configuration must have the base URL
   the `objecten` plugin creates the verzoeken at (`objectenUrl` + `/api/v2/`):
   the notification names the verzoek by that URL, and GZAC refuses to fetch
   an object URL that does not start with the configuration's URL. Likewise
   its Objecttypen API configuration must have the address the Objecten API
   renders objecttype URLs with, since Open Notificaties matches the
   objecttype literally.
4. **Zaaktype link** — the Verzoek plugin resolves the zaaktype of the case
   it creates, so `instrument-verzoek` needs a zaak type link. The zip
   carries none (it names environment values), and GZAC changes the zaak
   type link of a final version only as part of its import, so add
   `config/case/instrument-verzoek/<version>/zgw/zaak-type-link/instrument-verzoek.zaak-type-link.json`
   to the zip before importing it: `{"zaakTypeUrl": "<catalogi api>/zaaktypen/<uuid>",
   "zakenApiPluginConfigurationId": "<your Zaken API configuration>",
   "createWithDossier": false, "rsin": "<rsin>"}`. With `createWithDossier`
   false no zaak is created; with true every instrument dossier becomes a
   zaak.
5. **Verzoek plugin** — one configuration: the Notificaties API plugin,
   *Process to start* `instrument-verzoek`, the RSIN of the organisation, and
   one verzoek type with `type` = `instrument`, case definition
   `instrument-verzoek`, process definition key `instrument-verzoek`, the
   object management of step 3, an initiator roltype URL and description,
   copy strategy *full*.
6. **Import and activate** `instrument-verzoek.zip` (with the zaak type
   link of step 4) like any case zip, mapping the placeholder to your PDCA
   configuration.

### Making a product requestable

A producttype can be requested from the plan page when it carries an actie
named **`StartInstrument`** in Open Product. The actie's mapping is the start
recipe: static item `soort` = `verzoek`, static item `type` = the objecttype
URL of step 1 (optionally `typeVersion`); every other static item is copied
into the verzoek data, and items under the keys `plan`, `doel` or
`producttype` map a value of that register object (`regex` = its path, e.g.
`$.uuid`) onto a field (`name`) of the verzoek data. The producttype
parameter `startStatus` (`actief` or `gepland`) is the status the instrument
starts with in the plan, the parameter `instrumenttype` (name or uuid of an
Open Plan instrumenttype) its instrumenttype; without a matching one the
instrument gets the register's first instrumenttype and the app logs a
warning. The dev seed (`docker/openproduct/reference_data.py`) creates the
actie and both parameters for every producttype and takes the objecttype
URL from `PDCA_INSTRUMENT_VERZOEK_OBJECTTYPE`.
