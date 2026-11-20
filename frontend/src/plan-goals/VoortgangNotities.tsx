import React, { useState } from 'react';
import { Button, InlineNotification, Select, SelectItem, Tag, TextArea, TextInput } from '@carbon/react';
import type { DoelDetails, InstrumentDetails, VoortgangStatusOptie } from '../shared/api';
import { voortgangStatus } from '../shared/labels';
import { useSubmit } from '../shared/useSubmit';
import { Notities, RegistratieInvoer, registratieSamenvatting, VoortgangInvoer } from './doelenActies';

const EFFECTIVITEIT_LABELS = ['geen effect', 'weinig effect', 'neutraal', 'effectief', 'zeer effectief'];

const eersteRegel = (tekst: string) => tekst.split('\n').find(r => r.trim())?.trim() ?? '';

/** The voortgangsstatus of a doel as a tag, looked up in the plan's list. */
export function VoortgangTag({ code, opties }: { code: string; opties: VoortgangStatusOptie[] }) {
  const weergave = voortgangStatus(code, opties);
  return <Tag size="sm" type={weergave.tag}>{weergave.label}</Tag>;
}

/**
 * "Voortgang & notities" of a doel or instrument: a compact summary with
 * "Bijwerken", which swaps in the edit form until it is saved or cancelled.
 */
function VoortgangNotitiesSectie({ bewerken, onBewerken, onSluiten, samenvatting, formulier }: {
  bewerken: boolean; onBewerken: () => void; onSluiten?: () => void;
  samenvatting: React.ReactNode; formulier: React.ReactNode;
}) {
  return (
    <div className="pdca-section-block">
      <div className="pdca-section-title">
        <span>Voortgang &amp; notities</span>
        {!bewerken && (
          <div className="pdca-row-actions">
            <Button size="sm" kind="ghost" onClick={onBewerken}>Bijwerken</Button>
            {onSluiten && <Button size="sm" kind="ghost" onClick={onSluiten}>Sluiten</Button>}
          </div>
        )}
      </div>
      {bewerken ? formulier : samenvatting}
    </div>
  );
}

/**
 * Voortgang (an active subdoel or the hoofddoel) and notes of a doel. The
 * voortgang only shows while the doel runs; once it is closed its register
 * status says how it ended.
 */
export function DoelVoortgangNotities({ id, details, metVoortgang, opties, onSave }: {
  id: string; details?: DoelDetails; metVoortgang: boolean; opties: VoortgangStatusOptie[];
  onSave: (voortgang: VoortgangInvoer | null, notities: Notities | null) => Promise<void>;
}) {
  const [bewerken, setBewerken] = useState(false);
  return (
    <VoortgangNotitiesSectie bewerken={bewerken} onBewerken={() => setBewerken(true)}
      samenvatting={
        <div className="pdca-summary">
          {metVoortgang && (details?.voortgangStatus ? (
            <p className="pdca-summary-line">
              <VoortgangTag code={details.voortgangStatus} opties={opties} />
              {details.voortgangToelichting && <span className="pdca-text-wrap">{details.voortgangToelichting}</span>}
            </p>
          ) : (
            <p className="pdca-summary-line pdca-summary-empty">Nog niet beoordeeld</p>
          ))}
          <NotitiesSamenvatting details={details} />
        </div>
      }
      formulier={
        <DoelBijwerken id={id} details={details} metVoortgang={metVoortgang} opties={opties}
          onSave={async (v, n) => { await onSave(v, n); setBewerken(false); }}
          onCancel={() => setBewerken(false)} />
      } />
  );
}

/** Hours, effectiveness and notes of an instrument; [onSluiten] closes the panel they sit in. */
export function InstrumentVoortgangNotities({ id, details, onSave, onSluiten }: {
  id: string; details?: InstrumentDetails;
  onSave: (registratie: RegistratieInvoer | null, notities: Notities | null) => Promise<void>;
  onSluiten: () => void;
}) {
  const [bewerken, setBewerken] = useState(false);
  const registratie = registratieSamenvatting(details);
  return (
    <VoortgangNotitiesSectie bewerken={bewerken} onBewerken={() => setBewerken(true)} onSluiten={onSluiten}
      samenvatting={
        <div className="pdca-summary">
          <p className={`pdca-summary-line${registratie ? '' : ' pdca-summary-empty'}`}>
            {registratie ?? 'Nog geen uren of effectiviteit geregistreerd'}
          </p>
          {details?.effectiviteitToelichting && <p className="pdca-summary-line pdca-text-wrap">{details.effectiviteitToelichting}</p>}
          <NotitiesSamenvatting details={details} />
        </div>
      }
      formulier={
        <InstrumentBijwerken id={id} details={details}
          onSave={async (r, n) => { await onSave(r, n); setBewerken(false); }}
          onCancel={() => setBewerken(false)} />
      } />
  );
}

function NotitiesSamenvatting({ details }: { details?: Partial<Notities> }) {
  const intern = eersteRegel(details?.interneNotitie ?? '');
  const extern = eersteRegel(details?.externeNotitie ?? '');
  if (!intern && !extern) return <p className="pdca-summary-line pdca-summary-empty">Geen notities</p>;
  return <>
    {intern && <p className="pdca-summary-line pdca-text-ellipsis">Interne notitie: {intern}</p>}
    {extern && <p className="pdca-summary-line pdca-text-ellipsis">Externe notitie: {extern}</p>}
  </>;
}

/** Buttons under an edit form: cancel closes it, save is enabled once something changed. */
function BijwerkenActies({ saving, kanOpslaan, fout, onCancel, onSave }: {
  saving: boolean; kanOpslaan: boolean; fout: string | null; onCancel: () => void; onSave: () => void;
}) {
  return <>
    {fout && <InlineNotification kind="error" title="Opslaan mislukt" subtitle={fout} lowContrast hideCloseButton />}
    <div className="pdca-edit-actions">
      <Button size="sm" kind="ghost" disabled={saving} onClick={onCancel}>Annuleren</Button>
      <Button size="sm" disabled={saving || !kanOpslaan} onClick={onSave}>{saving ? 'Opslaan…' : 'Opslaan'}</Button>
    </div>
  </>;
}

const notitiesVan = (details?: Partial<Notities>): Notities =>
  ({ interneNotitie: details?.interneNotitie ?? '', externeNotitie: details?.externeNotitie ?? '' });

const notitiesGewijzigd = (notities: Notities, details?: Partial<Notities>) =>
  notities.interneNotitie !== (details?.interneNotitie ?? '') || notities.externeNotitie !== (details?.externeNotitie ?? '');

/** Edit form for the voortgang and the notes of a doel; only the parts that changed are saved. */
function DoelBijwerken({ id, details, metVoortgang, opties, onSave, onCancel }: {
  id: string; details?: DoelDetails; metVoortgang: boolean; opties: VoortgangStatusOptie[];
  onSave: (voortgang: VoortgangInvoer | null, notities: Notities | null) => Promise<void>;
  onCancel: () => void;
}) {
  const [status, setStatus] = useState<string>(details?.voortgangStatus ?? '');
  const [toelichting, setToelichting] = useState(details?.voortgangToelichting ?? '');
  const [notities, setNotities] = useState<Notities>(notitiesVan(details));
  const { saving, fout, submit } = useSubmit();
  const voortgangGewijzigd = metVoortgang
    && (status !== (details?.voortgangStatus ?? '') || toelichting !== (details?.voortgangToelichting ?? ''));
  const notitiesAnders = notitiesGewijzigd(notities, details);
  const kanOpslaan = (voortgangGewijzigd || notitiesAnders) && !(voortgangGewijzigd && !status);
  return (
    <div className="pdca-edit-form">
      {metVoortgang && (
        <VoortgangStatusBlok id={id} status={status} toelichting={toelichting} huidig={details?.voortgangStatus}
          opties={opties} onStatus={setStatus} onToelichting={setToelichting} />
      )}
      <NotitiesBlok id={id} notities={notities} onChange={setNotities} />
      <BijwerkenActies saving={saving} kanOpslaan={kanOpslaan} fout={fout} onCancel={onCancel}
        onSave={() => submit(() => onSave(
          voortgangGewijzigd && status ? { status, toelichting } : null,
          notitiesAnders ? notities : null,
        ))} />
    </div>
  );
}

/** Edit form for the hours, effectiveness and notes of an instrument; only the parts that changed are saved. */
function InstrumentBijwerken({ id, details, onSave, onCancel }: {
  id: string; details?: InstrumentDetails;
  onSave: (registratie: RegistratieInvoer | null, notities: Notities | null) => Promise<void>;
  onCancel: () => void;
}) {
  const [uren, setUren] = useState<string>(details?.urenBesteed?.toString() ?? '');
  const [score, setScore] = useState<string>(details?.effectiviteitScore?.toString() ?? '');
  const [toelichting, setToelichting] = useState(details?.effectiviteitToelichting ?? '');
  const [notities, setNotities] = useState<Notities>(notitiesVan(details));
  const { saving, fout, submit } = useSubmit();
  const registratieGewijzigd = uren !== (details?.urenBesteed?.toString() ?? '')
    || score !== (details?.effectiviteitScore?.toString() ?? '')
    || toelichting !== (details?.effectiviteitToelichting ?? '');
  const notitiesAnders = notitiesGewijzigd(notities, details);
  return (
    <div className="pdca-edit-form">
      <div>
        <div className="pdca-form-grid">
          <TextInput id={`uren-${id}`} labelText="Bestede uren" type="number"
            value={uren} onChange={e => setUren(e.target.value)} />
          <Select id={`score-${id}`} labelText="Effectiviteit" value={score} onChange={e => setScore(e.target.value)}>
            <SelectItem value="" text="-" />
            {EFFECTIVITEIT_LABELS.map((label, i) => <SelectItem key={i} value={String(i + 1)} text={`${i + 1} - ${label}`} />)}
          </Select>
        </div>
        <TextArea id={`toel-${id}`} className="pdca-form-field" rows={2} labelText="Toelichting"
          helperText="Hoe werkt dit instrument voor de inwoner?"
          value={toelichting} onChange={e => setToelichting(e.target.value)} />
      </div>
      <NotitiesBlok id={id} notities={notities} onChange={setNotities} />
      <BijwerkenActies saving={saving} kanOpslaan={registratieGewijzigd || notitiesAnders} fout={fout} onCancel={onCancel}
        onSave={() => submit(() => onSave(
          registratieGewijzigd
            ? { uren: uren ? parseInt(uren, 10) : null, score: score ? parseInt(score, 10) : null, toelichting }
            : null,
          notitiesAnders ? notities : null,
        ))} />
    </div>
  );
}

/**
 * Internal note (colleagues only) and external note (may be shared with the
 * inwoner) of a subdoel, the hoofddoel or an instrument.
 */
function NotitiesBlok({ id, notities, onChange }: {
  id: string; notities: Notities; onChange: (notities: Notities) => void;
}) {
  return (
    <div className="pdca-form-grid">
      <TextArea id={`intern-${id}`} rows={3} labelText="Interne notitie" helperText="Alleen zichtbaar voor collega's"
        value={notities.interneNotitie} onChange={e => onChange({ ...notities, interneNotitie: e.target.value })} />
      <TextArea id={`extern-${id}`} rows={3} labelText="Externe notitie" helperText="Kan gedeeld worden met de inwoner"
        value={notities.externeNotitie} onChange={e => onChange({ ...notities, externeNotitie: e.target.value })} />
    </div>
  );
}

/**
 * Voortgangsstatus with its explanation, for an active subdoel or the
 * hoofddoel; the choices are the plan's voortgangsstatussen. A current status
 * that was removed from the list stays selectable until it is changed.
 * During an evaluation a changed explanation becomes the reason of the
 * recorded change.
 */
function VoortgangStatusBlok({ id, status, toelichting, huidig, opties, onStatus, onToelichting }: {
  id: string; status: string; toelichting: string; huidig?: string; opties: VoortgangStatusOptie[];
  onStatus: (status: string) => void; onToelichting: (toelichting: string) => void;
}) {
  const vervallen = huidig && !opties.some(o => o.code === huidig) ? huidig : null;
  return (
    <div className="pdca-form-grid">
      <Select id={`voortgang-${id}`} labelText="Voortgang" value={status}
        onChange={e => onStatus(e.target.value)}>
        <SelectItem value="" text="Geen status" disabled hidden={!!huidig} />
        {opties.map(o => <SelectItem key={o.code} value={o.code} text={o.naam} />)}
        {vervallen && (
          <SelectItem value={vervallen} text={`${voortgangStatus(vervallen, opties).label} (niet meer in gebruik)`} />
        )}
      </Select>
      <TextArea id={`voortgang-toelichting-${id}`} rows={2} labelText="Toelichting" helperText="Waarom gaat het zo?"
        value={toelichting} onChange={e => onToelichting(e.target.value)} />
    </div>
  );
}
