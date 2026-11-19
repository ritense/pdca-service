import React, { useState } from 'react';
import { Button, InlineNotification, Layer, Select, SelectItem, Tag, TextArea, Tile, type TagProps } from '@carbon/react';
import type { Betrokkene, ContactmomentDetails, Klantcontact, Plan, PlanDetails } from '../shared/api';
import { formatDate } from '../shared/dates';
import { doelTypeNaam, evalTypeLabel, hoofddoelTypen } from '../shared/labels';
import { useSubmit } from '../shared/useSubmit';
import type { PlanVeld } from './overzichtActies';
import type { OverzichtModel } from './useOverzicht';

const betrokkeneNaam = (b: Betrokkene) => `${b.name} · ${b.role}`;

interface VeldDefinitie {
  key: PlanVeld;
  label: string;
  emptyText: string;
  value?: string;
  display?: string;
  /** Register-driven choices; without them the field is free text. */
  options?: { value: string; text: string }[];
  placeholder?: string;
  /** Shown instead of the edit button when the field cannot be edited yet. */
  hint?: string;
}

/**
 * The editable plan fields. Positions and hoofddoel are register-driven
 * choices (positietype resp. doeltype register), the procesbegeleider one
 * of the plan's verantwoordelijken; only the explanation for the inwoner is
 * free text.
 */
export function PlanGegevens({ plan, details, model, procesbegeleider, onSave }: {
  plan: Plan;
  details: PlanDetails;
  model: OverzichtModel;
  procesbegeleider: Betrokkene | null;
  /** Saves one field; throws on failure so the field stays in edit mode with the error. */
  onSave: (veld: PlanVeld, waarde: string) => Promise<void>;
}) {
  const [bewerkt, setBewerkt] = useState<PlanVeld | null>(null);
  const [waarde, setWaarde] = useState('');
  const { saving, fout, submit, reset } = useSubmit();
  const betrokkenen = model.data.betrokkenen;

  const velden: VeldDefinitie[] = [
    {
      key: 'procesbegeleider', label: 'Procesbegeleider', emptyText: 'Nog geen procesbegeleider',
      value: procesbegeleider?.id || '', display: procesbegeleider ? betrokkeneNaam(procesbegeleider) : '',
      options: betrokkenen.map(b => ({ value: b.id, text: betrokkeneNaam(b) })),
      placeholder: '-- Kies een verantwoordelijke --',
      hint: betrokkenen.length === 0 ? 'Voeg eerst een verantwoordelijke toe onder Verantwoordelijkheden.' : undefined,
    },
    {
      key: 'hoofddoel', label: 'Hoofddoel', emptyText: 'Nog geen hoofddoel gekozen',
      value: model.hoofddoelType?.uuid || '', display: doelTypeNaam(model.hoofddoelType),
      options: hoofddoelTypen(model.doeltypen).map(t => ({ value: t.uuid, text: t.doelType })),
    },
    { key: 'notitie', label: 'Toelichting bij hoofddoel', emptyText: 'Geen toelichting', value: plan.notitie },
    { key: 'subdoelgroep', label: 'Subdoelgroep', emptyText: 'Nog niet bepaald', value: details.subdoelgroep },
    {
      key: 'beginPositie', label: 'Positie', emptyText: 'Nog niet gezet',
      value: details.beginPositie, options: model.caseConfig.positieTypen.map(p => ({ value: p, text: p })),
    },
  ];

  const bewerk = (veld: VeldDefinitie) => {
    reset();
    setBewerkt(veld.key);
    setWaarde(veld.value || '');
  };

  const opslaan = async () => {
    if (!bewerkt) return;
    if (await submit(() => onSave(bewerkt, waarde))) setBewerkt(null);
  };

  return (
    <Tile className="pdca-card">
      <Layer>
        <div className="pdca-card-header"><h4>Plangegevens</h4></div>
        <div className="pdca-card-body">
          {velden.map(veld => (
            <div key={veld.key} className="pdca-info-block">
              <div className="pdca-info-label">
                <span>{veld.label}</span>
                {bewerkt !== veld.key && !veld.hint && (
                  <Button size="sm" kind="ghost" onClick={() => bewerk(veld)}>Bewerken</Button>
                )}
              </div>
              {bewerkt === veld.key ? (
                <div>
                  {veld.options ? (
                    <Select id={`edit-${veld.key}`} labelText="" hideLabel value={waarde}
                      onChange={(e: React.ChangeEvent<HTMLSelectElement>) => setWaarde(e.target.value)}>
                      <SelectItem value="" text={veld.placeholder ?? '-- Kies uit het register --'} />
                      {veld.options.map(o => <SelectItem key={o.value} value={o.value} text={o.text} />)}
                    </Select>
                  ) : (
                    <TextArea id={`edit-${veld.key}`} labelText="" hideLabel value={waarde} rows={3}
                      onChange={(e: React.ChangeEvent<HTMLTextAreaElement>) => setWaarde(e.target.value)} />
                  )}
                  {fout && <InlineNotification kind="error" title="Opslaan mislukt" subtitle={fout} lowContrast hideCloseButton />}
                  <div className="pdca-edit-actions">
                    <Button size="sm" kind="secondary" disabled={saving} onClick={() => setBewerkt(null)}>Annuleren</Button>
                    <Button size="sm" kind="primary" onClick={opslaan} disabled={saving || (!!veld.options && !waarde)}>
                      {saving ? 'Opslaan…' : 'Opslaan'}
                    </Button>
                  </div>
                </div>
              ) : <>
                <p className={`pdca-info-value${veld.value ? '' : ' empty'}`}>{(veld.display ?? veld.value) || veld.emptyText}</p>
                {veld.hint && <p className="pdca-field-hint">{veld.hint}</p>}
              </>}
            </div>
          ))}
        </div>
      </Layer>
    </Tile>
  );
}

const EVAL_TAG_TYPE: Record<string, NonNullable<TagProps<'div'>['type']>> = {
  INTAKE: 'blue', PROGRESS: 'purple', EVALUATION: 'green', INSPECTION: 'warm-gray', CRISIS: 'red',
};

/** The last three contactmomenten that took place. */
export function RecenteEvaluaties({ contactmomenten, details }: {
  contactmomenten: Klantcontact[]; details: ContactmomentDetails[];
}) {
  const recent = contactmomenten
    .filter(c => c.indicatieContactGelukt === true)
    .sort((a, b) => (b.plaatsgevondenOp || '').localeCompare(a.plaatsgevondenOp || ''))
    .slice(0, 3);
  return (
    <Tile className="pdca-card">
      <Layer>
        <div className="pdca-card-header"><h4>Recente evaluaties</h4></div>
        <div className="pdca-card-body">
          {recent.length === 0 ? <p className="pdca-empty">Nog geen evaluaties</p> : recent.map(cm => {
            const cmd = details.find(d => d.contactmomentUuid === cm.uuid);
            return (
              <div key={cm.uuid} className="pdca-eval-item">
                <Tag type={(cmd && EVAL_TAG_TYPE[cmd.evaluatieType]) || 'gray'} size="sm">
                  {cmd ? evalTypeLabel(cmd.evaluatieType) : 'Afgerond'}
                </Tag>
                <div className="pdca-eval-info">
                  <div className="pdca-eval-date">{cm.plaatsgevondenOp ? formatDate(cm.plaatsgevondenOp) : 'Geen datum'}</div>
                  <div className={`pdca-eval-summary${cm.inhoud ? '' : ' empty'}`}>{cm.inhoud || 'Geen notitie'}</div>
                </div>
              </div>
            );
          })}
        </div>
      </Layer>
    </Tile>
  );
}
