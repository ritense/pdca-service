import React from 'react';
import { Select, SelectItem, TextArea, TextInput } from '@carbon/react';
import { dienstverleningLabel, hoofddoelTypen } from '../shared/labels';
import type { PlanVelden, Registers } from './createPlanForm';

/** "Plan": titel, dienstverlening, hoofddoel with its toelichting, subdoelgroep, positie and dates. */
export function PlanVeldenSectie({ velden, registers, posities, poging, onChange }: {
  velden: PlanVelden;
  registers: Registers;
  posities: string[];
  poging: boolean;
  onChange: (veld: keyof PlanVelden, waarde: string) => void;
}) {
  const hoofddoelOpties = hoofddoelTypen(registers.doeltypen).map(t => t.doelType);
  return (
    <section className="cp-blok">
      <h2 className="cp-kop">Plan</h2>
      <div className="cp-velden">
        <div className="cp-veld cp-veld--breed">
          <TextInput id="cp-titel" labelText="Titel" placeholder="Bijv. Ontwikkelplan werk" value={velden.titel}
            invalid={poging && !velden.titel.trim()} invalidText="Verplicht"
            onChange={e => onChange('titel', e.target.value)} />
        </div>
        <div className="cp-veld">
          <Select id="cp-dienstverlening" labelText="Dienstverlening" value={velden.dienstverlening}
            onChange={e => onChange('dienstverlening', e.target.value)}>
            {registers.plantypen.length === 0 && <SelectItem value="" text="Geen plantypen in het register" />}
            {registers.plantypen.map(pt => <SelectItem key={pt.uuid} value={pt.type} text={dienstverleningLabel(pt.type)} />)}
          </Select>
        </div>
        <div className="cp-veld">
          <Select id="cp-hoofddoel" labelText="Hoofddoel" value={velden.hoofddoel}
            invalid={poging && !velden.hoofddoel} invalidText="Kies een hoofddoel"
            onChange={e => onChange('hoofddoel', e.target.value)}>
            <SelectItem value="" text="Kies een hoofddoel" />
            {hoofddoelOpties.map(n => <SelectItem key={n} value={n} text={n} />)}
          </Select>
        </div>
        <div className="cp-veld cp-veld--breed">
          <TextArea id="cp-toelichting" labelText="Toelichting bij hoofddoel" rows={2}
            placeholder="In de woorden van de inwoner" value={velden.toelichting}
            onChange={e => onChange('toelichting', e.target.value)} />
        </div>
        <div className="cp-veld">
          <TextInput id="cp-subdoelgroep" labelText="Subdoelgroep" placeholder="Leeg indien onbekend"
            value={velden.subdoelgroep} onChange={e => onChange('subdoelgroep', e.target.value)} />
        </div>
        <div className="cp-veld">
          <Select id="cp-positie" labelText="Positie" value={velden.beginPositie} onChange={e => onChange('beginPositie', e.target.value)}>
            <SelectItem value="" text={posities.length ? 'Kies een positie' : 'Geen posities ingesteld'} />
            {posities.map(p => <SelectItem key={p} value={p} text={p} />)}
          </Select>
        </div>
        <div className="cp-veld">
          <TextInput id="cp-startdatum" type="date" labelText="Startdatum" value={velden.startdatum}
            onChange={e => onChange('startdatum', e.target.value)} />
        </div>
        <div className="cp-veld">
          <TextInput id="cp-einddatum" type="date" labelText="Beoogde einddatum" value={velden.streefEinddatum}
            min={velden.startdatum || undefined} onChange={e => onChange('streefEinddatum', e.target.value)} />
        </div>
      </div>
    </section>
  );
}
