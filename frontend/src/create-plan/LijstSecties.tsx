import React, { Dispatch } from 'react';
import { AccordionItem, Button, Checkbox, Select, SelectItem, Tag, TextInput } from '@carbon/react';
import { Add, TrashCan } from '@carbon/react/icons';
import { heeftStartActie, ProductType } from '../shared/api';
import { formatDate } from '../shared/dates';
import { doelgroepVoorSubject, evalTypeLabel, producttypenVoorDoel, subdoelTypenVoorHoofddoel } from '../shared/labels';
import { subjectUrn } from '../shared/urn';
import type { DoelStatus, EvaluatieStatus } from './createPlanApi';
import type { FormActie, FormState, InstrumentRij, Registers, Sectie } from './createPlanForm';

const DOEL_STATUSSEN: { value: DoelStatus; text: string }[] = [
  { value: 'gepland', text: 'Gepland' },
  { value: 'gestart', text: 'Gestart' },
  { value: 'afgerond', text: 'Afgerond' },
];

const EVALUATIE_STATUSSEN: { value: EvaluatieStatus; text: string }[] = [
  { value: 'gepland', text: 'Gepland' },
  { value: 'afgerond', text: 'Afgerond' },
];

/** Keeps an already chosen value selectable, so narrowing the scope never silently empties a row. */
const metGekozen = (opties: string[], gekozen: string) =>
  gekozen && !opties.includes(gekozen) ? [...opties, gekozen] : opties;

interface SectieProps {
  state: FormState;
  dispatch: Dispatch<FormActie>;
  registers: Registers;
  evaluatieTypen: string[];
  subdoelMapping: string | null;
}

/** One list section: heading with a summary while closed, its rows and an add button. */
function LijstSectie({ sectie, titel, samenvatting, waarschuwing, toevoegen, state, dispatch, evaluatieTypen, children }: {
  sectie: Sectie; titel: string; samenvatting: string; waarschuwing?: string; toevoegen: string;
  state: FormState; dispatch: Dispatch<FormActie>; evaluatieTypen: string[]; children: React.ReactNode;
}) {
  return (
    <AccordionItem open={state.open[sectie]}
      onHeadingClick={({ isOpen }: { isOpen: boolean }) => dispatch({ type: 'open', sectie, open: isOpen })}
      title={
        <span className="cp-sectie-kop">
          <span className="cp-sectie-titel">{titel}</span>
          {waarschuwing && <Tag size="sm" type="red">{waarschuwing}</Tag>}
          {!state.open[sectie] && <span className="cp-sectie-samenvatting">{samenvatting}</span>}
        </span>
      }>
      {children}
      <Button kind="ghost" size="sm" renderIcon={Add} onClick={() => dispatch({ type: 'toevoegen', sectie, evaluatieTypen })}>
        {toevoegen}
      </Button>
    </AccordionItem>
  );
}

/** A row with its fields and a remove button. */
function Rij({ label, onVerwijder, children }: { label: string; onVerwijder: () => void; children: React.ReactNode }) {
  return (
    <div className="cp-rij">
      <div className="cp-rij-velden">{children}</div>
      <Button kind="ghost" size="sm" hasIconOnly renderIcon={TrashCan} iconDescription={label}
        tooltipPosition="left" className="cp-rij-verwijder" onClick={onVerwijder} />
    </div>
  );
}

export function SubdoelenSectie(props: SectieProps) {
  const { state, dispatch, registers, subdoelMapping } = props;
  const opties = subdoelTypenVoorHoofddoel(registers.doeltypen, state.hoofddoel, subdoelMapping).map(t => t.doelType);
  const wijzig = (key: number, wijziging: Record<string, unknown>) => dispatch({ type: 'wijzigRij', sectie: 'subdoelen', key, wijziging });
  return (
    <LijstSectie {...props} sectie="subdoelen" titel={`Subdoelen (${state.subdoelen.length})`} toevoegen="Subdoel toevoegen"
      samenvatting={state.subdoelen.map(d => d.doelType || 'Nog geen doel').join(', ') || 'Nog geen subdoelen'}
      waarschuwing={state.poging && state.subdoelen.some(d => !d.doelType) ? 'Doel ontbreekt' : undefined}>
      {state.subdoelen.length === 0 && <p className="cp-leeg">Nog geen subdoelen.</p>}
      {state.subdoelen.map((d, i) => (
        <Rij key={d.key} label="Subdoel verwijderen" onVerwijder={() => dispatch({ type: 'verwijderRij', sectie: 'subdoelen', key: d.key })}>
          <div className="cp-veld">
            <Select id={`cp-subdoel-${d.key}-type`} size="sm" labelText={`Doel ${i + 1}`} value={d.doelType}
              invalid={state.poging && !d.doelType} invalidText="Kies een doel"
              onChange={e => wijzig(d.key, { doelType: e.target.value })}>
              <SelectItem value="" text="Kies een doel" />
              {metGekozen(opties, d.doelType).map(n => <SelectItem key={n} value={n} text={n} />)}
            </Select>
          </div>
          <div className="cp-veld cp-veld--breed">
            <TextInput id={`cp-subdoel-${d.key}-beschrijving`} size="sm" labelText="Beschrijving" value={d.beschrijving}
              onChange={e => wijzig(d.key, { beschrijving: e.target.value })} />
          </div>
          <div className="cp-veld cp-veld--smal">
            <Select id={`cp-subdoel-${d.key}-status`} size="sm" labelText="Status" value={d.status}
              onChange={e => wijzig(d.key, { status: e.target.value as DoelStatus })}>
              {DOEL_STATUSSEN.map(s => <SelectItem key={s.value} value={s.value} text={s.text} />)}
            </Select>
          </div>
        </Rij>
      ))}
    </LijstSectie>
  );
}

export function InstrumentenSectie(props: SectieProps) {
  const { state, dispatch, registers } = props;
  const gekozenSubdoelen = state.subdoelen.map(d => d.doelType).filter(Boolean);
  const doelgroep = doelgroepVoorSubject(subjectUrn(state.subjectType, state.subjectId.trim()));
  const productNaam = (code: string) => registers.producttypen.find(p => p.code === code)?.naam ?? code;
  /**
   * The producttypen that can be requested under the row's subdoel, as on
   * the plan page: scoped to the subdoel and the subject's doelgroep, and
   * requestable (a StartInstrument actie in Open Product).
   */
  const productOpties = (rij: InstrumentRij): ProductType[] => {
    const doelNaam = rij.doel || gekozenSubdoelen[0];
    return producttypenVoorDoel(registers.producttypen, registers.doeltypen.find(t => t.doelType === doelNaam), doelgroep)
      .filter(heeftStartActie)
      .sort((a, b) => a.naam.localeCompare(b.naam));
  };
  const wijzig = (key: number, wijziging: Record<string, unknown>) => dispatch({ type: 'wijzigRij', sectie: 'instrumenten', key, wijziging });
  return (
    <LijstSectie {...props} sectie="instrumenten" titel={`Instrumenten (${state.instrumenten.length})`} toevoegen="Instrument toevoegen"
      samenvatting={state.instrumenten.map(it => (it.product ? productNaam(it.product) : 'Nog geen instrumenttype')).join(', ')
        || 'Nog geen instrumenten'}
      waarschuwing={state.poging && state.instrumenten.some(i => !i.product) ? 'Instrumenttype ontbreekt' : undefined}>
      <p className="cp-uitleg">
        Deze instrumenten worden aangevraagd zodra het plan is aangemaakt; elk instrument krijgt een eigen instrumentdossier.
      </p>
      {state.instrumenten.length === 0 && <p className="cp-leeg">Nog geen instrumenten.</p>}
      {state.instrumenten.map(it => {
        const opties = productOpties(it);
        const nietAanvraagbaar = !!it.product && !opties.some(p => p.code === it.product);
        return (
          <Rij key={it.key} label="Instrument verwijderen" onVerwijder={() => dispatch({ type: 'verwijderRij', sectie: 'instrumenten', key: it.key })}>
            <div className="cp-veld">
              <Select id={`cp-instrument-${it.key}-doel`} size="sm" labelText="Bij subdoel" value={it.doel}
                onChange={e => wijzig(it.key, { doel: e.target.value })}>
                <SelectItem value="" text="Eerste subdoel" />
                {metGekozen([...new Set(gekozenSubdoelen)], it.doel).map(n => <SelectItem key={n} value={n} text={n} />)}
              </Select>
            </div>
            <div className="cp-veld cp-veld--breed">
              <Select id={`cp-instrument-${it.key}-product`} size="sm" labelText="Instrumenttype" value={it.product}
                invalid={(state.poging && !it.product) || nietAanvraagbaar}
                invalidText={nietAanvraagbaar ? 'Dit instrumenttype is bij dit subdoel niet aan te vragen' : 'Kies een instrumenttype'}
                helperText={opties.length === 0 ? 'Bij dit subdoel zijn geen instrumenten aan te vragen' : undefined}
                onChange={e => wijzig(it.key, { product: e.target.value })}>
                <SelectItem value="" text="Kies een instrumenttype" />
                {nietAanvraagbaar && <SelectItem value={it.product} text={productNaam(it.product)} />}
                {opties.map(p => <SelectItem key={p.code} value={p.code} text={p.naam} />)}
              </Select>
            </div>
          </Rij>
        );
      })}
    </LijstSectie>
  );
}

export function EvaluatiesSectie(props: SectieProps) {
  const { state, dispatch, evaluatieTypen } = props;
  const wijzig = (key: number, wijziging: Record<string, unknown>) => dispatch({ type: 'wijzigRij', sectie: 'evaluaties', key, wijziging });
  return (
    <LijstSectie {...props} sectie="evaluaties" titel={`Geplande evaluaties (${state.evaluaties.length})`} toevoegen="Evaluatie toevoegen"
      samenvatting={state.evaluaties
        .map(c => [evalTypeLabel(c.evaluatieType), c.datum ? formatDate(c.datum) : 'zonder datum'].join(' '))
        .join(', ') || 'Nog geen evaluaties'}>
      {state.evaluaties.length === 0 && <p className="cp-leeg">Nog geen evaluaties.</p>}
      {state.evaluaties.map(c => (
        <Rij key={c.key} label="Evaluatie verwijderen" onVerwijder={() => dispatch({ type: 'verwijderRij', sectie: 'evaluaties', key: c.key })}>
          <div className="cp-veld">
            <TextInput id={`cp-evaluatie-${c.key}-datum`} size="sm" type="date" labelText="Datum" value={c.datum}
              onChange={e => wijzig(c.key, { datum: e.target.value })} />
          </div>
          <div className="cp-veld">
            <Select id={`cp-evaluatie-${c.key}-type`} size="sm" labelText="Type" value={c.evaluatieType}
              onChange={e => wijzig(c.key, { evaluatieType: e.target.value })}>
              {metGekozen(evaluatieTypen, c.evaluatieType).map(t => <SelectItem key={t} value={t} text={evalTypeLabel(t)} />)}
            </Select>
          </div>
          <div className="cp-veld cp-veld--smal">
            <Select id={`cp-evaluatie-${c.key}-status`} size="sm" labelText="Status" value={c.status}
              onChange={e => wijzig(c.key, { status: e.target.value as EvaluatieStatus })}>
              {EVALUATIE_STATUSSEN.map(s => <SelectItem key={s.value} value={s.value} text={s.text} />)}
            </Select>
          </div>
          <div className="cp-veld cp-veld--breed">
            <TextInput id={`cp-evaluatie-${c.key}-notitie`} size="sm" labelText="Notitie" value={c.notitie}
              onChange={e => wijzig(c.key, { notitie: e.target.value })} />
          </div>
        </Rij>
      ))}
    </LijstSectie>
  );
}

export function BetrokkenenSectie(props: SectieProps) {
  const { state, dispatch, registers } = props;
  const wijzig = (key: number, wijziging: Record<string, unknown>) => dispatch({ type: 'wijzigRij', sectie: 'betrokkenen', key, wijziging });
  return (
    <LijstSectie {...props} sectie="betrokkenen" titel={`Betrokkenen (${state.betrokkenen.length})`} toevoegen="Betrokkene toevoegen"
      samenvatting={state.betrokkenen
        .map(b => (b.procesbegeleider ? `${b.naam || 'Zonder naam'} (procesbegeleider)` : b.naam || 'Zonder naam'))
        .join(', ') || 'Nog geen betrokkenen'}>
      {state.betrokkenen.length === 0 && <p className="cp-leeg">Nog geen betrokkenen.</p>}
      {state.betrokkenen.length > 0 && !state.betrokkenen.some(b => b.procesbegeleider) && (
        <p className="cp-leeg">Nog geen procesbegeleider gekozen.</p>
      )}
      {state.betrokkenen.map(b => (
        <Rij key={b.key} label="Betrokkene verwijderen" onVerwijder={() => dispatch({ type: 'verwijderRij', sectie: 'betrokkenen', key: b.key })}>
          <div className="cp-veld">
            <TextInput id={`cp-betrokkene-${b.key}-naam`} size="sm" labelText="Naam" value={b.naam}
              onChange={e => wijzig(b.key, { naam: e.target.value })} />
          </div>
          <div className="cp-veld">
            <Select id={`cp-betrokkene-${b.key}-rol`} size="sm" labelText="Rol" value={b.rol}
              onChange={e => wijzig(b.key, { rol: e.target.value })}>
              <SelectItem value="" text="Kies een rol" />
              {metGekozen(registers.relatietypen.map(rt => rt.naam), b.rol).map(n => <SelectItem key={n} value={n} text={n} />)}
            </Select>
          </div>
          <div className="cp-veld">
            <TextInput id={`cp-betrokkene-${b.key}-organisatie`} size="sm" labelText="Organisatie" value={b.organisatie}
              onChange={e => wijzig(b.key, { organisatie: e.target.value })} />
          </div>
          <div className="cp-veld">
            <TextInput id={`cp-betrokkene-${b.key}-email`} size="sm" type="email" labelText="E-mail" value={b.email}
              onChange={e => wijzig(b.key, { email: e.target.value })} />
          </div>
          <div className="cp-veld cp-veld--smal cp-veld--check">
            <Checkbox id={`cp-betrokkene-${b.key}-procesbegeleider`} labelText="Procesbegeleider" checked={b.procesbegeleider}
              onChange={(_, { checked }) => dispatch({ type: 'procesbegeleider', key: b.key, gekozen: checked })} />
          </div>
        </Rij>
      ))}
    </LijstSectie>
  );
}
