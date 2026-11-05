import React, { useEffect, useState } from 'react';
import { InlineNotification, Modal, Select, SelectItem, TextArea, TextInput } from '@carbon/react';
import type { Doel, DoelType } from '../shared/api';
import { aantal, doelTypeNaam, subdoelTypen, subdoelTypenVoorHoofddoel } from '../shared/labels';
import { useSubmit } from '../shared/useSubmit';
import type { ActieContext, ActieInvoer, DoelInvoer } from './doelenActies';

/** Doel or instrument awaiting its abort reason; `openDossiers` names the instrument dossiers that block a doel. */
export type AfbreekTarget = { kind: 'doel' | 'instrument'; uuid: string; titel: string; openDossiers?: string[] } | null;
/** Subdoel awaiting confirmation of its removal, with what goes with it and the instrument dossiers that block it. */
export type VerwijderTarget = { uuid: string; titel: string; instrumenten: number; acties: number; openDossiers: string[] } | null;

/** "X wordt beheerd door een eigen instrumentdossier; …": why a doel cannot be aborted or removed yet. */
function OpenDossiers({ titels }: { titels: string[] }) {
  return (
    <InlineNotification kind="warning" title="Sluit eerst de instrumentdossiers af"
      subtitle={`${titels.join(', ')} ${titels.length === 1 ? 'wordt' : 'worden'} beheerd door een eigen instrumentdossier; rond ze daar af of annuleer ze daar.`}
      lowContrast hideCloseButton />
  );
}

/** Aborting always requires a reason (doel: cascades to linked instruments). */
export function AfbreekModal({ target, onClose, onSubmit }: {
  target: AfbreekTarget; onClose: () => void; onSubmit: (reden: string) => Promise<void>;
}) {
  const [reden, setReden] = useState('');
  const { saving, fout, submit, reset } = useSubmit();
  useEffect(() => { if (target) { setReden(''); reset(); } }, [target, reset]);
  const geblokkeerd = (target?.openDossiers?.length ?? 0) > 0;
  return (
    <Modal open={!!target} danger modalHeading={`${target?.kind === 'doel' ? 'Subdoel' : 'Instrument'} afbreken`}
      primaryButtonText={saving ? 'Afbreken…' : 'Afbreken'} secondaryButtonText="Annuleren"
      primaryButtonDisabled={saving || !reden.trim() || geblokkeerd}
      onRequestClose={onClose} onRequestSubmit={() => reden.trim() && !geblokkeerd && submit(() => onSubmit(reden.trim()))}>
      <div className="pdca-modal-form">
        {fout && <InlineNotification kind="error" title="Afbreken mislukt" subtitle={fout} lowContrast hideCloseButton />}
        {geblokkeerd && <OpenDossiers titels={target!.openDossiers!} />}
        <p>
          Je staat op het punt <strong>{target?.titel}</strong> af te breken.
          {target?.kind === 'doel' && ' Gekoppelde actieve instrumenten worden ook afgebroken.'}
        </p>
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
  const geblokkeerd = (target?.openDossiers.length ?? 0) > 0;
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
        {geblokkeerd && <OpenDossiers titels={target!.openDossiers} />}
        <p>
          <strong>{target?.titel}</strong> wordt verwijderd{mee ? `, inclusief ${mee}` : ''}. Dit kan niet ongedaan worden gemaakt.
        </p>
        <p>Is het subdoel niet meer van toepassing? Kies dan liever Afbreken; dan blijft het met de reden zichtbaar in het plan.</p>
      </div>
    </Modal>
  );
}

/**
 * Register-driven doel entry: the doel is chosen from
 * the doeltype register and the title is the register name — no free text.
 * Free text is allowed only as explanation for the inwoner.
 */
export function DoelModal({ open, doel, doeltypen, hoofddoelNaam, subdoelMapping, onClose, onSave }: {
  open: boolean; doel: Doel | null; doeltypen: DoelType[];
  hoofddoelNaam?: string | null; subdoelMapping?: string | null;
  onClose: () => void; onSave: (data: DoelInvoer) => Promise<void>;
}) {
  const [toelichting, setToelichting] = useState('');
  const [doeltypeUuid, setDoeltypeUuid] = useState('');
  const { saving, fout, submit, reset } = useSubmit();
  useEffect(() => {
    if (open) {
      setToelichting(doel?.beschrijving || '');
      setDoeltypeUuid(doel?.doeltype?.uuid || '');
      reset();
    }
  }, [open, doel, reset]);

  const opties = subdoelTypenVoorHoofddoel(doeltypen, hoofddoelNaam, subdoelMapping);
  const gescoped = opties.length < subdoelTypen(doeltypen).length;
  // Doel with a doeltype predating the named register: show as its own option
  // so the explanation stays editable without reclassification.
  const huidigOnbekend = doel?.doeltype && !opties.some(t => t.uuid === doel.doeltype!.uuid);
  const gekozen = opties.find(t => t.uuid === doeltypeUuid);
  const titel = gekozen ? doelTypeNaam(gekozen) : doel?.titel || '';

  return (
    <Modal open={open} modalHeading={doel ? 'Subdoel bewerken' : 'Subdoel toevoegen'}
      primaryButtonText={saving ? 'Opslaan…' : 'Opslaan'} secondaryButtonText="Annuleren"
      primaryButtonDisabled={saving || !doeltypeUuid || !titel}
      onRequestClose={onClose}
      onRequestSubmit={() => doeltypeUuid && titel && submit(() => onSave({ titel, toelichting, doeltypeUuid }))}>
      <div className="pdca-modal-form">
        {fout && <InlineNotification kind="error" title="Opslaan mislukt" subtitle={fout} lowContrast hideCloseButton />}
        <Select id="doel-type" labelText="Subdoel" value={doeltypeUuid} onChange={e => setDoeltypeUuid(e.target.value)}
          helperText={gescoped
            ? `Subdoelen die horen bij hoofddoel "${hoofddoelNaam}"`
            : 'Vast gedefinieerde doelen uit het doeltype-register'}>
          <SelectItem value="" text="-- Kies subdoel --" />
          {huidigOnbekend && <SelectItem value={doel!.doeltype!.uuid} text={`${doel!.titel} (huidig)`} />}
          {opties.map(t => (
            <SelectItem key={t.uuid} value={t.uuid} text={t.doelType} />
          ))}
        </Select>
        <TextArea id="doel-toelichting" labelText="Toelichting" helperText="Uitleg voor de inwoner" value={toelichting}
          onChange={e => setToelichting(e.target.value)} />
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
  const [assigneeType, setAssigneeType] = useState('PROFESSIONAL');
  const [assigneeName, setAssigneeName] = useState('');
  const [priority, setPriority] = useState('NORMAL');
  const [dueDate, setDueDate] = useState('');
  const { saving, fout, submit, reset } = useSubmit();
  useEffect(() => { if (open) { setTitle(''); setDesc(''); setAssigneeName(''); setDueDate(''); reset(); } }, [open, reset]);
  const opslaan = () => {
    if (!ctx || !title.trim()) return;
    submit(() => onSave(ctx, {
      title: title.trim(), description: desc, assigneeType, assigneeName, priority, dueDate: dueDate || undefined,
    }));
  };
  return (
    <Modal open={open} modalHeading={ctx?.instrumentUuid ? 'Actie bij instrument toevoegen' : 'Actie toevoegen'}
      primaryButtonText={saving ? 'Opslaan…' : 'Opslaan'} secondaryButtonText="Annuleren"
      primaryButtonDisabled={saving || !title.trim()}
      onRequestClose={onClose} onRequestSubmit={opslaan}>
      <div className="pdca-modal-form">
        {fout && <InlineNotification kind="error" title="Opslaan mislukt" subtitle={fout} lowContrast hideCloseButton />}
        <TextInput id="actie-title" labelText="Titel (verplicht)" value={title} onChange={e => setTitle(e.target.value)} />
        <TextArea id="actie-desc" labelText="Beschrijving" value={desc} onChange={e => setDesc(e.target.value)} />
        <Select id="actie-assignee-type" labelText="Uitvoerder type" value={assigneeType} onChange={e => setAssigneeType(e.target.value)}>
          <SelectItem value="PROFESSIONAL" text="Behandelaar" />
          <SelectItem value="SUBJECT" text="Inwoner / Eigenaar" />
          <SelectItem value="PROVIDER" text="Aanbieder" />
        </Select>
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
