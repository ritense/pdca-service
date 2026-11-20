import React, { useState } from 'react';
import {
  Button, ContainedList, ContainedListItem, IconButton, InlineNotification, Layer, Select, SelectItem, Stack, Tag,
  TextArea, Tile, type TagProps,
} from '@carbon/react';
import { CenterCircle, Document, Edit, Group, Milestone, UserFollow, type CarbonIconType } from '@carbon/react/icons';
import type { Betrokkene, ContactmomentDetails, Klantcontact, Plan, PlanDetails } from '../shared/api';
import { formatDate } from '../shared/dates';
import { doelTypeNaam, evalTypeLabel } from '../shared/labels';
import { useSubmit } from '../shared/useSubmit';
import type { PlanVeld } from './overzichtActies';
import type { OverzichtModel } from './useOverzicht';

const betrokkeneNaam = (b: Betrokkene) => `${b.name} · ${b.role}`;

interface VeldDefinitie {
  key: PlanVeld | 'hoofddoel' | 'subdoelgroep' | 'beginPositie';
  label: string;
  emptyText: string;
  /** Carbon icon marking the field in the list. */
  icon: CarbonIconType;
  /** Carbon tag colour the icon tile takes. */
  tint: 'blue' | 'green' | 'purple' | 'teal' | 'cyan';
  value?: string;
  display?: string;
  /** Register-driven choices; without them the field is free text. */
  options?: { value: string; text: string }[];
  placeholder?: string;
  /** Shown instead of the edit button when the field cannot be edited yet. */
  hint?: string;
  /** Set when the plan is created from the intake; shown without edit button. */
  readOnly?: boolean;
}

/**
 * The plan fields. Only the procesbegeleider (one of the plan's betrokkenen)
 * and the explanation for the inwoner are edited here; hoofddoel,
 * subdoelgroep and positie are set when the plan is created from the intake
 * and shown read-only.
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
      key: 'hoofddoel', label: 'Hoofddoel', emptyText: 'Nog geen hoofddoel gekozen', icon: CenterCircle, tint: 'blue',
      value: model.hoofddoelType?.uuid || '', display: doelTypeNaam(model.hoofddoelType), readOnly: true,
    },
    {
      key: 'notitie', label: 'Toelichting bij hoofddoel', emptyText: 'Geen toelichting', icon: Document, tint: 'green',
      value: plan.notitie,
    },
    {
      key: 'procesbegeleider', label: 'Procesbegeleider', emptyText: 'Nog geen procesbegeleider', icon: UserFollow,
      tint: 'purple',
      value: procesbegeleider?.id || '', display: procesbegeleider ? betrokkeneNaam(procesbegeleider) : '',
      options: betrokkenen.map(b => ({ value: b.id, text: betrokkeneNaam(b) })),
      placeholder: '-- Kies een betrokkene --',
      hint: betrokkenen.length === 0 ? 'Voeg eerst een betrokkene toe onder Betrokkenen.' : undefined,
    },
    {
      key: 'subdoelgroep', label: 'Subdoelgroep', emptyText: 'Nog niet bepaald', icon: Group, tint: 'teal',
      value: details.subdoelgroep, readOnly: true,
    },
    {
      key: 'beginPositie', label: 'Positie', emptyText: 'Nog niet gezet', icon: Milestone, tint: 'cyan',
      value: details.beginPositie, readOnly: true,
    },
  ];

  const bewerk = (veld: VeldDefinitie) => {
    if (veld.readOnly) return;
    reset();
    setBewerkt(veld.key as PlanVeld);
    setWaarde(veld.value || '');
  };

  const opslaan = async () => {
    if (!bewerkt) return;
    if (await submit(() => onSave(bewerkt, waarde))) setBewerkt(null);
  };

  return (
    <Tile className="pdca-card">
      <Layer>
        <ContainedList className="pdca-kaartlijst pdca-veldenlijst" label="Plangegevens" kind="on-page" isInset>
          {velden.map(veld => {
            const Icoon = veld.icon;
            const bewerkbaar = !veld.readOnly && !veld.hint;
            const inBewerking = bewerkt === veld.key;
            return (
              <ContainedListItem key={veld.key} className={`pdca-veld pdca-veld--${veld.tint}`}
                renderIcon={() => <Icoon size={20} />}
                action={bewerkbaar && !inBewerking ? (
                  <IconButton kind="ghost" size="sm" align="left" label={`${veld.label} bewerken`}
                    onClick={() => bewerk(veld)}>
                    <Edit size={16} />
                  </IconButton>
                ) : undefined}>
                {inBewerking ? (
                  <Stack gap={4}>
                    <span className="pdca-veld-label">{veld.label}</span>
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
                  </Stack>
                ) : <>
                  <span className={`pdca-veld-waarde${veld.value ? '' : ' empty'}`}>
                    {(veld.display ?? veld.value) || veld.emptyText}
                  </span>
                  <span className="pdca-veld-label">{veld.label}</span>
                  {veld.hint && <span className="pdca-field-hint">{veld.hint}</span>}
                </>}
              </ContainedListItem>
            );
          })}
        </ContainedList>
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
