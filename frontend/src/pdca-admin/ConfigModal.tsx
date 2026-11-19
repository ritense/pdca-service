import React, { useCallback, useEffect, useState } from 'react';
import { Checkbox, ContentSwitcher, InlineNotification, Modal, Select, SelectItem, Switch, TextInput } from '@carbon/react';
import { DoelType, pdca, PhaseConfig, VoortgangStatusOptie } from '../shared/api';
import { evalTypeLabel } from '../shared/labels';
import { LaadFout, NietGeladen } from '../shared/Meldingen';
import { Resource } from '../shared/useResource';
import { useSubmit } from '../shared/useSubmit';
import { ALL_EVAL_TYPES, ConfigForm, formFouten, formVan, nieuwForm, payloadVan } from './configForm';
import { hintStyle, sectionTitleStyle, SortableList, VoortgangStatussenEditor } from './LijstEditors';
import { MappingEditor, SubdoelMappingEditor } from './MappingEditors';

/**
 * Creates or edits the configuration of one case type. Whatever keeps it
 * from being saved shows in the modal; a refusal of the backend too.
 */
export function ConfigModal({ open, bewerk, configs, gzacDossiertypes, doeltypen, standaardVoortgang, onClose, onSaved }: {
  open: boolean;
  /** The configuration being edited; null for a new one. */
  bewerk: PhaseConfig | null;
  configs: PhaseConfig[];
  /** Case types from GZAC; without them the key is entered by hand. */
  gzacDossiertypes: Resource<string[]>;
  doeltypen: Resource<DoelType[]>;
  standaardVoortgang: VoortgangStatusOptie[];
  onClose: () => void;
  onSaved: () => void;
}) {
  const [form, setForm] = useState<ConfigForm>(() => nieuwForm(standaardVoortgang));
  const [poging, setPoging] = useState(false);
  const [paden, setPaden] = useState<string[]>([]);
  const [padenFout, setPadenFout] = useState<unknown>(null);
  const { saving, fout, submit, reset } = useSubmit();

  /** Path suggestions for the mapping come from this case type's GZAC document schema. */
  const laadPaden = useCallback((key: string) => {
    setPaden([]);
    setPadenFout(null);
    if (key) pdca.caseDefinitions.documentPaths(key).then(setPaden, setPadenFout);
  }, []);

  useEffect(() => {
    if (!open) return;
    setForm(bewerk ? formVan(bewerk, standaardVoortgang) : nieuwForm(standaardVoortgang));
    setPoging(false);
    reset();
    laadPaden(bewerk?.caseDefinitionKey ?? '');
  }, [open, bewerk, standaardVoortgang, reset, laadPaden]);

  const wijzig = (deel: Partial<ConfigForm>) => setForm(f => ({ ...f, ...deel }));
  const fouten = formFouten(form);

  const opslaan = async () => {
    setPoging(true);
    if (fouten.length > 0) return;
    const payload = payloadVan(form);
    const ok = await submit(() => (bewerk
      ? pdca.phaseConfigs.update(bewerk.caseDefinitionKey, payload)
      : pdca.phaseConfigs.create(payload)));
    if (ok) onSaved();
  };

  const toggleEvalType = (type: string) => wijzig({
    evalTypes: form.evalTypes.includes(type) ? form.evalTypes.filter(t => t !== type) : [...form.evalTypes, type],
  });

  return (
    <Modal open={open} size="lg" modalHeading={bewerk ? 'Configuratie bewerken' : 'Nieuwe configuratie'}
      primaryButtonText={saving ? 'Opslaan…' : 'Opslaan'} secondaryButtonText="Annuleren"
      primaryButtonDisabled={saving || (poging && fouten.length > 0)}
      onRequestClose={onClose} onRequestSubmit={opslaan}>
      <div className="pdca-modal-form">
        {fout && <InlineNotification kind="error" title="Opslaan mislukt" subtitle={fout} lowContrast hideCloseButton />}
        {poging && fouten.length > 0 && (
          <InlineNotification kind="error" title="Nog niet compleet" subtitle={fouten.join(' ')} lowContrast hideCloseButton />
        )}
        {bewerk ? (
          /* The case type is the configuration's identity and cannot change. */
          <Select id="cfg-key" labelText="Dossiertype" disabled
            helperText="Het dossiertype van een bestaande configuratie is niet te wijzigen."
            value={form.key} onChange={() => undefined}>
            <SelectItem value={form.key} text={form.key} />
          </Select>
        ) : gzacDossiertypes.data ? (
          <Select id="cfg-key" labelText="Dossiertype"
            helperText="Dossierdefinities uit GZAC; al geconfigureerde dossiertypes worden niet getoond."
            value={form.key}
            onChange={e => { wijzig({ key: e.target.value }); laadPaden(e.target.value); }}>
            <SelectItem value="" text="— kies dossiertype —" />
            {gzacDossiertypes.data
              .filter(k => !configs.some(c => c.caseDefinitionKey === k))
              .map(k => <SelectItem key={k} value={k} text={k} />)}
          </Select>
        ) : <>
          {gzacDossiertypes.error != null && (
            <NietGeladen wat="De dossiertypelijst van GZAC" error={gzacDossiertypes.error} onRetry={gzacDossiertypes.reload} />
          )}
          <TextInput id="cfg-key" labelText="Dossiertype (case definition key)"
            value={form.key} onChange={e => wijzig({ key: e.target.value })}
            onBlur={() => laadPaden(form.key.trim())}
            placeholder="bijv. jeugdzorg-traject"
            helperText="Vul de key van het dossiertype handmatig in." />
        </>}

        {/* A plan case type carries the PDCA configuration, an intake case
            type only the prefill mapping and the plan case type it creates plans for. */}
        <div className="pdca-admin-soort">
          <p className="pdca-admin-label">Soort dossiertype</p>
          <ContentSwitcher selectedIndex={form.isIntake ? 1 : 0} size="sm"
            onChange={({ index }) => wijzig({ isIntake: index === 1 })}>
            <Switch name="plan" text="Plan-dossiertype" />
            <Switch name="intake" text="Intake-dossiertype" />
          </ContentSwitcher>
          <p style={{ ...hintStyle, marginTop: 4 }}>
            {form.isIntake
              ? 'Intake: dit dossier verzamelt de input; het create-plan taakformulier maakt er een plan mét eigen plan-dossier van.'
              : 'Plan-dossier: dit dossier ís 1:1 het plan en draagt de PDCA-inrichting en de tabs.'}
          </p>
        </div>

        {form.isIntake ? <>
          <Select id="cfg-plancasedefkey" labelText="Maakt plannen voor dossiertype"
            helperText="Het plan-dossiertype waarvan het create-plan taakformulier het plan-dossier aanmaakt. Keuze uit de geconfigureerde plan-dossiertypes."
            value={form.planCaseDefKey} onChange={e => wijzig({ planCaseDefKey: e.target.value })}>
            <SelectItem value="" text="— kies plan-dossiertype —" />
            {configs.filter(c => c.caseDefinitionKey !== form.key && !c.planCaseDefinitionKey)
              .map(c => <SelectItem key={c.caseDefinitionKey} value={c.caseDefinitionKey} text={c.caseDefinitionKey} />)}
          </Select>
          <h5 style={sectionTitleStyle}>Prefill mapping (documentpad → planveld)</h5>
          <p style={hintStyle}>
            Bepaalt hoe het create-plan taakformulier het plan voorvult uit de intake. Paden komen uit het
            documentschema van dit dossiertype; planvelden zijn het vaste contract van het taakformulier.
          </p>
          {padenFout != null && <NietGeladen wat="De padsuggesties" error={padenFout} onRetry={() => laadPaden(form.key.trim())} />}
          <MappingEditor rijen={form.mapping} onChange={mapping => wijzig({ mapping })} padSuggesties={paden} />
        </> : <>
          <h5 style={sectionTitleStyle}>Planstatussen</h5>
          <p style={hintStyle}>Eigen invoer: er bestaat geen statusregister — deze lijst ís de bron. Sleep voor de volgorde.</p>
          <SortableList items={form.planStatussen} onChange={planStatussen => wijzig({ planStatussen })}
            emptyText="Geen statussen — vrije invoer in het planoverzicht." tagType="teal" />

          <h5 style={sectionTitleStyle}>Voortgangsstatussen (hoofddoel en subdoelen)</h5>
          <p style={hintStyle}>
            Hoe het gaat met een lopend hoofddoel of subdoel. De fase (Gepland, Gestart, Afgerond,
            Afgebroken) komt uit het register en is geen voortgangsstatus; na afronden of afbreken
            toont een doel weer die fase. Een naam wijzigen werkt door op bestaande doelen; een
            verwijderde status blijft grijs zichtbaar op doelen die hem nog hebben. Sleep voor de volgorde.
          </p>
          <VoortgangStatussenEditor rijen={form.voortgang} onChange={voortgang => wijzig({ voortgang })}
            standaard={standaardVoortgang} />

          <h5 style={sectionTitleStyle}>Positietypen</h5>
          <p style={hintStyle}>
            Eigen invoer: deze lijst ís het positieregister van dit domein — de positie van een plan
            moet hieruit komen. Sleep voor de volgorde (laag → hoog).
          </p>
          <SortableList items={form.positieTypen} onChange={positieTypen => wijzig({ positieTypen })}
            emptyText="Geen positieregister — posities zijn dan vrije invoer." tagType="cyan" />

          <h5 style={sectionTitleStyle}>Subdoelen per hoofddoel</h5>
          <p style={hintStyle}>
            Welke subdoelen kiesbaar zijn onder welk hoofddoel. Beide komen uit het doeltype-register;
            het register kent de relatie zelf niet, dus die staat hier. Een hoofddoel zonder rij — of
            met een rij zonder vinkjes — houdt alle subdoelen beschikbaar.
          </p>
          {doeltypen.data ? (
            <SubdoelMappingEditor rijen={form.subdoelMapping} onChange={subdoelMapping => wijzig({ subdoelMapping })}
              doeltypen={doeltypen.data} />
          ) : doeltypen.error != null ? (
            <LaadFout titel="Het doeltype-register kon niet geladen worden" error={doeltypen.error} onRetry={doeltypen.reload} />
          ) : <p style={hintStyle}>Doeltypen laden…</p>}

          <h5 style={sectionTitleStyle}>Evaluatietypen</h5>
          <p style={hintStyle}>Vaste typen uit de PDCA-app (geen register).</p>
          <div className="pdca-admin-checks">
            {ALL_EVAL_TYPES.map(t => (
              <Checkbox key={t} id={`et-${t}`} labelText={evalTypeLabel(t)}
                checked={form.evalTypes.includes(t)} onChange={() => toggleEvalType(t)} />
            ))}
          </div>
        </>}
      </div>
    </Modal>
  );
}
