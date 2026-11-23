import React, { useEffect, useState } from 'react';
import { InlineNotification, Modal, Select, SelectItem, TextArea, TextInput } from '@carbon/react';
import type { Doel, DoelType } from '../shared/api';
import { datumDeel, formatDate, vandaag } from '../shared/dates';
import { aantal, doelTypeNaam, subdoelTypen, subdoelTypenVoorHoofddoel } from '../shared/labels';
import { useSubmit } from '../shared/useSubmit';
import type { ActieContext, ActieInvoer, DoelInvoer } from './doelenActies';

/** An actie's title is the name of its GZAC task, which holds at most 255 characters. */
const ACTIE_TITEL_MAX = 255;

/** Doel or instrument awaiting its abort reason; `lopendWerk` names what still runs under a doel and blocks it. */
export type AfbreekTarget = { kind: 'doel' | 'instrument'; uuid: string; titel: string; lopendWerk?: string[] } | null;
/** Subdoel awaiting confirmation of its removal, with what goes with it and what still runs under it and blocks it. */
export type VerwijderTarget = { uuid: string; titel: string; instrumenten: number; acties: number; lopendWerk: string[] } | null;

/** Why a subdoel cannot be aborted or removed yet: what still runs under it, and how to end each kind. */
function LopendWerk({ items }: { items: string[] }) {
  return (
    <InlineNotification kind="warning" title="Sluit eerst af wat nog loopt"
      subtitle={`Onder dit subdoel loopt nog: ${items.join(', ')}. Rond een instrument af of breek het af (een instrumentdossier in het dossier zelf), wacht tot een aanvraag een instrument is geworden en rond een taak af in de takenlijst: uitgevoerd, of vervallen als ze niet meer nodig is.`}
      lowContrast hideCloseButton />
  );
}

/** Aborting always requires a reason; a subdoel only once nothing runs under it any more. */
export function AfbreekModal({ target, onClose, onSubmit }: {
  target: AfbreekTarget; onClose: () => void; onSubmit: (reden: string) => Promise<void>;
}) {
  const [reden, setReden] = useState('');
  const { saving, fout, submit, reset } = useSubmit();
  useEffect(() => { if (target) { setReden(''); reset(); } }, [target, reset]);
  const geblokkeerd = (target?.lopendWerk?.length ?? 0) > 0;
  return (
    <Modal open={!!target} danger modalHeading={`${target?.kind === 'doel' ? 'Subdoel' : 'Instrument'} afbreken`}
      primaryButtonText={saving ? 'Afbreken…' : 'Afbreken'} secondaryButtonText="Annuleren"
      primaryButtonDisabled={saving || !reden.trim() || geblokkeerd}
      onRequestClose={onClose} onRequestSubmit={() => reden.trim() && !geblokkeerd && submit(() => onSubmit(reden.trim()))}>
      <div className="pdca-modal-form">
        {fout && <InlineNotification kind="error" title="Afbreken mislukt" subtitle={fout} lowContrast hideCloseButton />}
        {geblokkeerd && <LopendWerk items={target!.lopendWerk!} />}
        <p>Je staat op het punt <strong>{target?.titel}</strong> af te breken.</p>
        <TextArea id="afbreek-reden" labelText="Reden (verplicht)" value={reden}
          onChange={e => setReden(e.target.value)} placeholder="Waarom wordt dit afgebroken?" />
      </div>
    </Modal>
  );
}

/** Removing a subdoel takes its own instruments and its acties with it. */
export function VerwijderModal({ target, onClose, onSubmit }: {
  target: VerwijderTarget; onClose: () => void; onSubmit: () => Promise<void>;
}) {
  const { saving, fout, submit, reset } = useSubmit();
  useEffect(() => { if (target) reset(); }, [target, reset]);
  const geblokkeerd = (target?.lopendWerk.length ?? 0) > 0;
  const mee = target
    ? [
        target.instrumenten > 0 ? aantal(target.instrumenten, 'instrument', 'instrumenten') : null,
        target.acties > 0 ? aantal(target.acties, 'actie', 'acties') : null,
      ].filter(Boolean).join(' en ')
    : '';
  return (
    <Modal open={!!target} danger modalHeading="Subdoel verwijderen"
      primaryButtonText={saving ? 'Verwijderen…' : 'Verwijderen'} secondaryButtonText="Annuleren"
      primaryButtonDisabled={saving || geblokkeerd}
      onRequestClose={onClose} onRequestSubmit={() => !geblokkeerd && submit(onSubmit)}>
      <div className="pdca-modal-form">
        {fout && <InlineNotification kind="error" title="Verwijderen mislukt" subtitle={fout} lowContrast hideCloseButton />}
        {geblokkeerd && <LopendWerk items={target!.lopendWerk} />}
        <p>
          <strong>{target?.titel}</strong> wordt verwijderd{mee ? `, inclusief ${mee}` : ''}. Dit kan niet ongedaan worden gemaakt.
        </p>
        <p>Is het subdoel niet meer van toepassing? Kies dan liever Afbreken; dan blijft het met de reden zichtbaar in het plan.</p>
      </div>
    </Modal>
  );
}

/**
 * Why a beoogde einddatum is not allowed (same rules as the backend), or
 * null. Only a new or changed date is checked, so a date that has since
 * passed can stay.
 */
function einddatumFout(datum: string, huidig: string, planEinddatum?: string): string | null {
  if (!datum || datum === huidig) return null;
  if (datum < vandaag()) return 'De beoogde einddatum kan niet in het verleden liggen';
  if (planEinddatum && datum > planEinddatum.slice(0, 10)) {
    return `De beoogde einddatum mag niet na die van het plan (${formatDate(planEinddatum)}) liggen`;
  }
  return null;
}

/**
 * Register-driven doel entry: the doel is chosen from
 * the doeltype register and the title is the register name — no free text.
 * Free text is allowed only as explanation for the inwoner. When editing, the
 * doeltype is shown read-only: only the explanation and the beoogde
 * einddatum can change.
 */
export function DoelModal({ open, doel, doeltypen, hoofddoelNaam, subdoelMapping, huidigeEinddatum, planEinddatum, onClose, onSave }: {
  open: boolean; doel: Doel | null; doeltypen: DoelType[];
  hoofddoelNaam?: string | null; subdoelMapping?: string | null;
  /** The subdoel's current beoogde einddatum (when editing). */
  huidigeEinddatum?: string;
  /** The plan's beoogde einddatum: the latest date a subdoel can get. */
  planEinddatum?: string;
  onClose: () => void; onSave: (data: DoelInvoer) => Promise<void>;
}) {
  const [toelichting, setToelichting] = useState('');
  const [doeltypeUuid, setDoeltypeUuid] = useState('');
  const [startdatum, setStartdatum] = useState('');
  const [streefEinddatum, setStreefEinddatum] = useState('');
  const { saving, fout, submit, reset } = useSubmit();
  useEffect(() => {
    if (open) {
      setToelichting(doel?.beschrijving || '');
      setDoeltypeUuid(doel?.doeltype?.uuid || '');
      setStartdatum(doel?.startdatum ? datumDeel(doel.startdatum) : vandaag());
      setStreefEinddatum(huidigeEinddatum?.slice(0, 10) || '');
      reset();
    }
  }, [open, doel, huidigeEinddatum, reset]);
  const datumFout = einddatumFout(streefEinddatum, huidigeEinddatum?.slice(0, 10) || '', planEinddatum);
  const startFout = !startdatum ? 'Vul een startdatum in'
    : streefEinddatum && startdatum > streefEinddatum ? 'Na de beoogde einddatum' : null;

  const opties = subdoelTypenVoorHoofddoel(doeltypen, hoofddoelNaam, subdoelMapping);
  const gescoped = opties.length < subdoelTypen(doeltypen).length;
  const gekozen = opties.find(t => t.uuid === doeltypeUuid);
  const titel = doel ? doel.titel : gekozen ? doelTypeNaam(gekozen) : '';
  const kanOpslaan = !!titel && (!!doel || !!doeltypeUuid) && !datumFout && !startFout;

  return (
    <Modal open={open} modalHeading={doel ? 'Subdoel bewerken' : 'Subdoel toevoegen'}
      primaryButtonText={saving ? 'Opslaan…' : 'Opslaan'} secondaryButtonText="Annuleren"
      primaryButtonDisabled={saving || !kanOpslaan}
      onRequestClose={onClose}
      onRequestSubmit={() => kanOpslaan && submit(() => onSave({ titel, toelichting, doeltypeUuid, startdatum, streefEinddatum }))}>
      <div className="pdca-modal-form">
        {fout && <InlineNotification kind="error" title="Opslaan mislukt" subtitle={fout} lowContrast hideCloseButton />}
        {doel ? (
          <TextInput id="doel-type" labelText="Subdoel" value={doel.titel} readOnly
            helperText="Het subdoel zelf kan niet worden gewijzigd" />
        ) : (
          <Select id="doel-type" labelText="Subdoel" value={doeltypeUuid} onChange={e => setDoeltypeUuid(e.target.value)}
            helperText={gescoped
              ? `Subdoelen die horen bij hoofddoel "${hoofddoelNaam}"`
              : 'Vast gedefinieerde doelen uit het doeltype-register'}>
            <SelectItem value="" text="-- Kies subdoel --" />
            {opties.map(t => (
              <SelectItem key={t.uuid} value={t.uuid} text={t.doelType} />
            ))}
          </Select>
        )}
        <TextArea id="doel-toelichting" labelText="Toelichting" helperText="Uitleg voor de inwoner" value={toelichting}
          onChange={e => setToelichting(e.target.value)} />
        <TextInput id="doel-startdatum" type="date" labelText="Startdatum"
          helperText="Wanneer het werken aan dit subdoel begint"
          max={streefEinddatum || planEinddatum?.slice(0, 10) || undefined}
          value={startdatum} onChange={e => setStartdatum(e.target.value)}
          invalid={!!startFout} invalidText={startFout || undefined} />
        <TextInput id="doel-einddatum" type="date" labelText="Beoogde einddatum (optioneel)"
          helperText={planEinddatum
            ? `Uiterlijk de beoogde einddatum van het plan (${formatDate(planEinddatum)})`
            : 'Laat zien in welke volgorde de subdoelen aan bod komen'}
          min={vandaag()} max={planEinddatum?.slice(0, 10) || undefined}
          value={streefEinddatum} onChange={e => setStreefEinddatum(e.target.value)}
          invalid={!!datumFout} invalidText={datumFout || undefined} />
      </div>
    </Modal>
  );
}

export function ActieModal({ open, ctx, onClose, onSave }: {
  open: boolean;
  ctx: ActieContext | null;
  onClose: () => void;
  onSave: (ctx: ActieContext, data: ActieInvoer) => Promise<void>;
}) {
  const [title, setTitle] = useState('');
  const [desc, setDesc] = useState('');
  const [assigneeName, setAssigneeName] = useState('');
  const [priority, setPriority] = useState('NORMAL');
  const [dueDate, setDueDate] = useState('');
  const { saving, fout, submit, reset } = useSubmit();
  useEffect(() => { if (open) { setTitle(''); setDesc(''); setAssigneeName(''); setDueDate(''); reset(); } }, [open, reset]);
  const opslaan = () => {
    if (!ctx || !title.trim()) return;
    submit(() => onSave(ctx, {
      title: title.trim(), description: desc, assigneeName, priority, dueDate: dueDate || undefined,
    }));
  };
  return (
    <Modal open={open} modalHeading={ctx?.instrumentUuid ? 'Actie bij instrument toevoegen' : 'Actie toevoegen'}
      primaryButtonText={saving ? 'Opslaan…' : 'Opslaan'} secondaryButtonText="Annuleren"
      primaryButtonDisabled={saving || !title.trim()}
      onRequestClose={onClose} onRequestSubmit={opslaan}>
      <div className="pdca-modal-form">
        {fout && <InlineNotification kind="error" title="Opslaan mislukt" subtitle={fout} lowContrast hideCloseButton />}
        <TextInput id="actie-title" labelText="Titel (verplicht)" value={title} onChange={e => setTitle(e.target.value)}
          maxLength={ACTIE_TITEL_MAX}
          helperText="De actie wordt een taak in de takenlijst van dit dossier, met deze titel, beschrijving en deadline; daar rond je haar af. Die drie liggen daarna vast." />
        <TextArea id="actie-desc" labelText="Beschrijving" value={desc} onChange={e => setDesc(e.target.value)} />
        <TextInput id="actie-assignee" labelText="Naam uitvoerder" value={assigneeName} onChange={e => setAssigneeName(e.target.value)} />
        <Select id="actie-priority" labelText="Prioriteit" value={priority} onChange={e => setPriority(e.target.value)}>
          <SelectItem value="NORMAL" text="Normaal" />
          <SelectItem value="HIGH" text="Hoog" />
          <SelectItem value="LOW" text="Laag" />
        </Select>
        <TextInput id="actie-due" labelText="Deadline" type="date" value={dueDate} onChange={e => setDueDate(e.target.value)} />
      </div>
    </Modal>
  );
}
