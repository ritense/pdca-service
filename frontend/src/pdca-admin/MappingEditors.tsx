import React from 'react';
import { Button, Checkbox, Select, SelectItem, TextInput } from '@carbon/react';
import { Add, TrashCan } from '@carbon/react/icons';
import type { DoelType } from '../shared/api';
import { doelTypeNaam, hoofddoelTypen, subdoelTypen } from '../shared/labels';
import { MappingRij, PREFILL_VELDEN, STANDAARD_MAPPING, SubdoelMappingRij } from './configForm';
import { hintStyle } from './LijstEditors';

/**
 * Which subdoeltypen may be chosen under which hoofddoeltype (per case type).
 * Both sides come from the doeltype register, so the mapping is picked rather
 * than typed — it is keyed by name and a typo would silently scope nothing.
 * A hoofddoel without a row keeps all subdoelen available.
 */
export function SubdoelMappingEditor({ rijen, onChange, doeltypen }: {
  rijen: SubdoelMappingRij[];
  onChange: (rijen: SubdoelMappingRij[]) => void;
  doeltypen: DoelType[];
}) {
  const hoofddoelen = hoofddoelTypen(doeltypen);
  const subdoelen = subdoelTypen(doeltypen);
  const gebruikt = rijen.map(r => r.hoofddoel);
  const vrij = hoofddoelen.filter(h => !gebruikt.includes(doelTypeNaam(h)));

  const wijzig = (i: number, deel: Partial<SubdoelMappingRij>) =>
    onChange(rijen.map((r, ri) => (ri === i ? { ...r, ...deel } : r)));

  const toggle = (i: number, naam: string) => {
    const huidig = rijen[i].subdoelen;
    wijzig(i, { subdoelen: huidig.includes(naam) ? huidig.filter(n => n !== naam) : [...huidig, naam] });
  };

  return (
    <div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 12, marginBottom: 8 }}>
        {rijen.map((rij, i) => (
          <div key={i} style={{ border: '1px solid var(--cds-border-subtle)', padding: 12 }}>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 40px', gap: 8, alignItems: 'center' }}>
              <Select id={`subdoelmap-hoofddoel-${i}`} labelText="" hideLabel size="sm" value={rij.hoofddoel}
                onChange={e => wijzig(i, { hoofddoel: e.target.value })}>
                {!rij.hoofddoel && <SelectItem value="" text="-- kies hoofddoel --" />}
                {hoofddoelen
                  .filter(h => doelTypeNaam(h) === rij.hoofddoel || !gebruikt.includes(doelTypeNaam(h)))
                  .map(h => <SelectItem key={h.uuid} value={doelTypeNaam(h)} text={doelTypeNaam(h)} />)}
              </Select>
              <Button size="sm" kind="danger--ghost" renderIcon={TrashCan} iconDescription="Verwijderen" hasIconOnly
                onClick={() => onChange(rijen.filter((_, ri) => ri !== i))} />
            </div>
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: 16, marginTop: 8 }}>
              {subdoelen.map(sd => (
                <Checkbox key={sd.uuid} id={`subdoelmap-${i}-${sd.uuid}`} labelText={doelTypeNaam(sd)}
                  checked={rij.subdoelen.includes(doelTypeNaam(sd))}
                  onChange={() => toggle(i, doelTypeNaam(sd))} />
              ))}
              {subdoelen.length === 0 && (
                <span style={hintStyle}>Geen subdoeltypen in het register.</span>
              )}
            </div>
            {rij.subdoelen.length === 0 && (
              <p style={{ ...hintStyle, marginTop: 6 }}>
                Niets aangevinkt — onder dit hoofddoel blijven alle subdoelen kiesbaar.
              </p>
            )}
          </div>
        ))}
      </div>
      <Button size="sm" kind="tertiary" renderIcon={Add} disabled={vrij.length === 0}
        onClick={() => onChange([...rijen, { hoofddoel: doelTypeNaam(vrij[0]), subdoelen: [] }])}>
        Hoofddoel toevoegen
      </Button>
      {rijen.length > 0 && (
        <Button size="sm" kind="ghost" onClick={() => onChange([])}>Leegmaken</Button>
      )}
    </div>
  );
}

/**
 * Prefill mapping as a source→target table, modelled after GZAC's own
 * mapping tables: document path → plan field, with an add button that
 * disables once all fields are mapped.
 */
export function MappingEditor({ rijen, onChange, padSuggesties }: {
  rijen: MappingRij[];
  onChange: (rijen: MappingRij[]) => void;
  padSuggesties: string[];
}) {
  const gebruikteVelden = rijen.map(r => r.veld);
  const vrijeVelden = PREFILL_VELDEN.filter(v => !gebruikteVelden.includes(v.veld));

  const wijzig = (i: number, deel: Partial<MappingRij>) =>
    onChange(rijen.map((r, ri) => (ri === i ? { ...r, ...deel } : r)));

  return (
    <div>
      {/* Path suggestions from this case type's GZAC document schema. */}
      <datalist id="document-paden">
        {padSuggesties.map(p => <option key={p} value={p} />)}
      </datalist>
      {rijen.length > 0 && (
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 24px 1fr 40px', gap: 8, alignItems: 'center', marginBottom: 4 }}>
          <span style={{ fontSize: 12, fontWeight: 600, color: 'var(--cds-text-secondary)' }}>
            Documentpad (dossier-content{padSuggesties.length > 0 ? ', uit het documentschema' : ''})
          </span>
          <span />
          <span style={{ fontSize: 12, fontWeight: 600, color: 'var(--cds-text-secondary)' }}>Planveld</span>
          <span />
        </div>
      )}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 6, marginBottom: 8 }}>
        {rijen.map((rij, i) => (
          <div key={i} style={{ display: 'grid', gridTemplateColumns: '1fr 24px 1fr 40px', gap: 8, alignItems: 'center' }}>
            <TextInput id={`pad-${i}`} labelText="" hideLabel size="sm" value={rij.pad}
              list="document-paden"
              placeholder="/planTitel" onChange={e => wijzig(i, { pad: e.target.value })} />
            <span style={{ textAlign: 'center', color: 'var(--cds-text-helper)' }}>→</span>
            <Select id={`veld-${i}`} labelText="" hideLabel size="sm" value={rij.veld}
              onChange={e => wijzig(i, { veld: e.target.value })}>
              {!rij.veld && <SelectItem value="" text="-- kies planveld --" />}
              {PREFILL_VELDEN
                .filter(v => v.veld === rij.veld || !gebruikteVelden.includes(v.veld))
                .map(v => <SelectItem key={v.veld} value={v.veld} text={v.label} />)}
            </Select>
            <Button size="sm" kind="danger--ghost" renderIcon={TrashCan} iconDescription="Verwijderen" hasIconOnly
              onClick={() => onChange(rijen.filter((_, ri) => ri !== i))} />
          </div>
        ))}
      </div>
      <div style={{ display: 'flex', gap: 8 }}>
        <Button size="sm" kind="tertiary" renderIcon={Add} disabled={vrijeVelden.length === 0}
          onClick={() => onChange([...rijen, { pad: '', veld: vrijeVelden[0]?.veld ?? '' }])}>
          Mapping toevoegen
        </Button>
        <Button size="sm" kind="ghost"
          onClick={() => onChange(PREFILL_VELDEN.map(v => ({ pad: STANDAARD_MAPPING[v.veld], veld: v.veld })))}>
          Standaardpaden invullen
        </Button>
        {rijen.length > 0 && (
          <Button size="sm" kind="ghost" onClick={() => onChange([])}>Leegmaken</Button>
        )}
      </div>
      <p style={{ ...hintStyle, marginTop: 6 }}>
        Leeg = de standaardpaden. Bepaalt hoe het create-plan taakformulier uit de dossier-content
        (intake) voorgevuld wordt.
      </p>
    </div>
  );
}

