# GZAC case definitions voor de PDCA-app

Importeerbare dossierdefinities voor de twee PDCA-democases. De `caseDefinitionKey`s
(`inwonerplan`, `binnenhof-renovatie`) matchen de phase-configs en demo-plannen die de
PDCA-app seedt, zodat de PDCA-tabbladen na het koppelen direct het juiste plan tonen.

| Zip | Case | Subject | PDCA-fases (uit de app) |
|---|---|---|---|
| `inwonerplan.zip` | Inwonerplan (PDCA) | Persoon (bsn) | Analyse / Uitvoering / Plaatsing |
| `binnenhof-renovatie.zip` | Binnenhof-renovatie (PDCA) | Object | Analyse / Uitvoering / Check / Act |

Elke zip bevat alles wat de case nodig heeft: documentdefinitie (JSON-schema),
BPMN-proces met PDCA-lus, startformulier + taakformulieren (form.io) mét
proceskoppelingen, process-document-link (startbaar door gebruiker), dossierlijst-
kolommen, zoekvelden en de standaard-tabbladen (Samenvatting/Voortgang/Audit).

## Importeren

Admin → Dossierdefinities → **Importeren** en de zip kiezen, of via de API:

```bash
curl -X POST -H "Authorization: Bearer $TOKEN" \
  -F "file=@inwonerplan.zip;type=application/zip" \
  http://localhost:8080/api/management/v1/case/import
```

Opnieuw importeren van dezelfde key+versionTag overschrijft de niet-definitieve
versie; verhoog anders de `versionTag` (map `1-0-0` + `case-definition.json` + de
`camunda:versionTag` in de BPMN).

## Enige handmatige stap: PDCA-tabbladen koppelen

Vereist dat de PDCA-app als external app is gekoppeld **en** er een
pluginconfiguratie voor "PDCA Planbeheer" is aangemaakt (Admin → Plugins →
Plugin toevoegen). Daarna per dossierdefinitie: Tabbladen → tab toevoegen van het
type *external plugin* → kies de configuratie + tab:

- Planoverzicht (`plan-overview`)
- Doelen & Acties (`plan-goals`)
- Evaluaties (`plan-evaluations`)

## Procesverloop

`start-event` (startformulier: bsn/object-id) → `plan-aanmaken` → `pdca-cyclus`
(lus; het formulier zet procesvariabele `vervolg`, bij *afronden* eindigt het
proces). De taken `plan-aanmaken` en `pdca-cyclus` hebben werkende
form.io-fallbackformulieren; optioneel vervang je die proceskoppelingen door de
externe taakformulieren van de app (`create-plan`, `update-goals`, `evaluate`).
Het documentschema bevat `planId`/`planTitel`/`planStatus` als doelvelden voor de
resultaat-mapping van het `create-plan`-taakformulier.

Demo-invoer: bsn `111222333` (Erika de Goede, matcht het geseede demo-plan),
object `binnenhof-001` (Binnenhof). Zips opnieuw bouwen na wijzigingen:
`./build-zips.sh`.
