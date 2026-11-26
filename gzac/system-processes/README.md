# GZAC system processes for the PDCA app

Global GZAC processes the PDCA app starts itself. They belong to no case
definition: GZAC imports them through Process management, outside any case
version, and the app starts them on a dossier by process key.

| Zip | Process | Started by |
|---|---|---|
| `pdca-document.zip` | `pdca-document` "PDCA document maken" | the app, on the plan dossier, when it asks for a document (the evaluatieverslag of a completed evaluation); the key is the PDCA configuration property `documentProces` |

A zip holds `config/global/bpmn/<key>.bpmn` and
`config/global/process-link/<key>.process-link.json`; the BPMN's process id
and the link file's name are the key. The BPMN carries no `versionTag` (the
`CD:`/`BB:` tags belong to case definitions and building blocks).

## `pdca-document`

The process makes one document with the Epistola Document Suite from the
template `evaluatieverslag` of the catalog `pdca-documenten`
([`../epistola/catalogs/pdca-documenten/`](../epistola/catalogs/pdca-documenten/)):

1. **Documentgegevens ophalen** — PDCA action `document-aanvraag-ophalen`:
   the app hands this process its document aanvraag (the one recorded with
   its process id, or else the oldest young unclaimed one of the plan
   dossier) and answers with the process variables `pdcaAanvraagId`, `pdcaBestandsnaam`
   and `pdcaGegevens` (the data snapshot, which is the template's data
   model).
2. **Document genereren** — Epistola action `epistola-generate-document`:
   `pdcaGegevens` as data, PDF, file name `pdcaBestandsnaam`; the request
   lands in `epistolaResult`.
3. **Wachten op het document** — message catch event
   `EpistolaDocumentGenerated`; the Epistola plugin correlates it when the
   Suite has finished and fills `epistolaResult` (`status`, `documentId`,
   `errorMessage`, …).
4. **Document gemaakt?** — `${epistolaResult.status == 'COMPLETED'}`:
   - yes: **Document downloaden** (Epistola action
     `epistola-download-document`, the PDF into the process variable
     `pdcaDocumentInhoud`) → **Document gereed melden** (PDCA action
     `document-gereed` with `uitkomst` `GEREED`, `inhoud` and
     `bestandsnaam`) → end *Document gereed*;
   - otherwise: **Mislukt document melden** (`document-gereed` with
     `uitkomst` `MISLUKT` and Epistola's `errorMessage` as `foutmelding`,
     which the app logs; the user sees a general reason)
     → end *Document mislukt*.

The PDCA tasks run asynchronously and are retried every minute, five times
(`R5/PT1M`), before they become an incident; both actions are safe to
repeat. The Epistola tasks carry an explicit `${null}` expression: GZAC adds
`asyncAfter` to a service task without one, and the Epistola plugin needs the
generate task without `asyncAfter` and the catch event without
`asyncBefore`. The catch event continues asynchronously after the message,
so downloading and reporting run in a job of their own. When the process
ends, `pdcaDocumentInhoud` and `pdcaGegevens` are removed from its
variables; the app holds the document.

## Placeholders

Configuration ids are environment-specific, so the process links ship with
fixed placeholders:

| Placeholder | Links | Configuration |
|---|---|---|
| `00000000-0000-4000-8000-00000000dca0` | `external_plugin` (PDCA actions) | the "PDCA Planbeheer" configuration, the same placeholder as in the case definitions |
| `00000000-0000-4000-8000-0000dca00005` | `plugin` (Epistola actions) | GZAC's Epistola plugin configuration (plugin `epistola`) |

## Importing

Prerequisites: the PDCA configuration exists (README.md, "Any other GZAC"),
GZAC has an Epistola plugin configuration for the Suite tenant, and that
tenant holds the catalog `pdca-documenten` (below).

Admin → Process management → **Import** and pick the zip; map the PDCA and
the Epistola placeholder to your configurations. Or via the API:

```bash
curl -X POST -H "Authorization: Bearer $TOKEN" \
  -F "file=@pdca-document.zip;type=application/zip" \
  -F 'pluginConfigurationMappings={"00000000-0000-4000-8000-00000000dca0":"<your PDCA configuration uuid>","00000000-0000-4000-8000-0000dca00005":"<your Epistola configuration uuid>"};type=application/json' \
  http://localhost:8080/api/management/v1/process-definition/import
```

GZAC checks the zip first and answers 400 when it cannot import it.
Importing a BPMN equal to the latest version of the key creates no new
version, and the imported process links replace that version's links, so
importing again is safe; leave the PDCA configuration property
`documentProces` at `pdca-document` (blank switches document generation
off).

## The Epistola catalog

[`../epistola/catalogs/pdca-documenten/`](../epistola/catalogs/pdca-documenten/)
is an authored Epistola catalog: `catalog.json` (slug `pdca-documenten`,
release version) and `resources/` with the theme `pdca` and the template
`evaluatieverslag`, whose data model is the `pdcaGegevens` snapshot. Import
it into the tenant of GZAC's Epistola configuration as a zip with
`catalog.json` at its root, naming the catalog's registry `local` and the
tenant's namespace (the tenant key, padded with `0` to 3 characters); the
import installs the release of `catalog.json`:

```bash
(cd ../epistola/catalogs/pdca-documenten && zip -r -X /tmp/pdca-documenten.zip catalog.json resources)
curl -X POST -H "Authorization: ApiKey $EPISTOLA_API_KEY" -H "Accept: application/vnd.epistola.v2+json" \
  -F "file=@/tmp/pdca-documenten.zip;type=application/zip" -F registry=local -F namespace=$TENANT \
  "$EPISTOLA_URL/tenants/$TENANT/catalogs/import"
```

Importing a release the Suite already has with the same content changes
nothing; the Suite refuses a release version it has with other content, so a
change to the catalog raises `release.version` in `catalog.json`.

## Rebuilding

`./gradlew buildGzacZips` (from the project root; per process also
`./gradlew systemProcessZip-pdca-document`), or `./build-zips.sh` here.
Commit the source tree and the zip together; `GzacConfigurationTest` fails
on a zip that differs from its source.

For the local stack the dev setup ([`../dev-setup/`](../dev-setup/README.md))
imports the catalog and the process with the right mappings.
