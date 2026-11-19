import React, { useEffect, useRef, useState } from 'react';
import { Button, Layer, OverflowMenu, OverflowMenuItem, Tag } from '@carbon/react';
import { Checkmark, ChevronDown, Close, Launch } from '@carbon/react/icons';
import { navigateHost } from '../shared/bridge';
import { Actie, Doel, Instrument, OpenVerzoek, ProductType, productTypeByUrn } from '../shared/api';
import { formatDate } from '../shared/dates';
import { actieStatus, instrumentStatus, priorityLabel } from '../shared/labels';
import { parseDossierUrn } from '../shared/urn';
import type { DoelenHandlers } from './SubdoelCard';
import type { DoelenModel } from './useDoelenModel';
import { InstrumentVoortgangNotities } from './VoortgangNotities';

/** After this many minutes an open verzoek says that its dossier has not shown up yet. */
const VERZOEK_TRAAG_MINUTEN = 10;

/** Navigates the GZAC host to a dossier (route /cases/<caseDefKey>/document/<documentId>). */
const openDossier = (caseDefinitionKey: string, documentId: string) =>
  navigateHost(`/cases/${caseDefinitionKey}/document/${documentId}`);

/** One instrument under a subdoel: its state actions, its acties and its "Voortgang & notities". */
export function InstrumentRij({ inst, doel, model, producttypen, handlers }: {
  inst: Instrument; doel: Doel; model: DoelenModel; producttypen: ProductType[] | undefined; handlers: DoelenHandlers;
}) {
  const [voortgangOpen, setVoortgangOpen] = useState(false);
  const pt = producttypen ? productTypeByUrn(producttypen, inst.product) : undefined;
  const idet = model.detailsVanInstrument(inst.uuid);
  const status = instrumentStatus(inst, idet);
  const actief = inst.status === 'actief';
  const gepland = actief && idet?.uitvoeringsStatus === 'GEPLAND';
  const acties = model.actiesVanInstrument(inst.uuid);
  // Instrument created by an instrument dossier: the zaak register field
  // carries that dossier's URN, and that dossier alone completes or cancels it.
  const dossierRef = parseDossierUrn(inst.zaak);
  return (
    <div className="pdca-instrument">
      <div className="pdca-action-row">
        <div className="pdca-row-main">
          <span className="pdca-text-wrap">{inst.titel}</span>
          {pt && <span className="pdca-meta-text">— {pt.organisaties[0]?.naam || pt.code}{pt.themas[0] ? ` · ${pt.themas[0].naam}` : ''}</span>}
          {idet?.urenBesteed != null && <Tag size="sm" type="cool-gray">{idet.urenBesteed} uur</Tag>}
          {idet?.effectiviteitScore != null && <Tag size="sm" type="teal">effectiviteit {idet.effectiviteitScore}/5</Tag>}
        </div>
        <div className="pdca-row-actions">
          <Tag size="sm" type={status.tag}>{status.label}</Tag>
          {dossierRef && (
            <Button size="sm" kind="ghost" renderIcon={Launch} title="Open het instrumentdossier van dit instrument"
              onClick={() => openDossier(dossierRef.caseDefinitionKey, dossierRef.documentId)}>
              Dossier
            </Button>
          )}
          {gepland && (
            <Button size="sm" kind="ghost" disabled={handlers.busy} onClick={() => handlers.startInstrument(inst)}>Starten</Button>
          )}
          {actief && !gepland && !dossierRef && (
            <Button size="sm" kind="ghost" renderIcon={Checkmark} disabled={handlers.busy}
              onClick={() => handlers.rondInstrumentAf(inst)}>Afronden</Button>
          )}
          <OverflowMenu size="sm" flipped aria-label={`Meer acties voor ${inst.titel}`} iconDescription="Meer acties">
            {actief && (
              <OverflowMenuItem itemText="Actie toevoegen"
                onClick={() => handlers.nieuweActie({ doelUuid: doel.uuid, instrumentUuid: inst.uuid })} />
            )}
            <OverflowMenuItem itemText="Voortgang & notities" onClick={() => setVoortgangOpen(open => !open)} />
            {actief && !dossierRef && (
              <OverflowMenuItem itemText="Afbreken" isDelete hasDivider
                onClick={() => handlers.breekAf({ kind: 'instrument', uuid: inst.uuid, titel: inst.titel })} />
            )}
          </OverflowMenu>
        </div>
      </div>
      {idet?.afbreekReden && <p className="pdca-afbreekreden">Afbreekreden: {idet.afbreekReden}</p>}
      {/* Taken/opdrachten of this instrument (W&P: instrument -> 0..n taken). */}
      {acties.length > 0 && (
        <div className="pdca-instrument-acties">
          {acties.map(actie => <ActieRow key={actie.id} actie={actie} busy={handlers.busy} onStatus={handlers.actieStatus} />)}
        </div>
      )}
      {voortgangOpen && (
        <InstrumentPanel>
          <InstrumentVoortgangNotities id={inst.uuid} details={idet}
            onSave={handlers.bewaarInstrumentBijwerken(inst)} onSluiten={() => setVoortgangOpen(false)} />
        </InstrumentPanel>
      )}
    </div>
  );
}

/** A requested product whose instrument has not appeared yet. */
export function VerzoekRij({ verzoek, producttypen }: { verzoek: OpenVerzoek; producttypen: ProductType[] | undefined }) {
  const naam = verzoek.productTitel
    ?? (producttypen ? productTypeByUrn(producttypen, verzoek.productUrn)?.naam : undefined)
    ?? 'Instrument';
  return (
    <div className="pdca-action-row">
      <div className="pdca-row-main">
        <span className="pdca-text-wrap">{naam}</span>
        {verzoek.minutenOpen != null && verzoek.minutenOpen >= VERZOEK_TRAAG_MINUTEN && (
          <span className="pdca-meta-text">
            — nog geen dossier na {verzoek.minutenOpen} minuten; controleer de aanvraag in GZAC
          </span>
        )}
      </div>
      <div className="pdca-row-actions">
        <Tag size="sm" type="blue" title={verzoek.aangevraagdOp ? `Aangevraagd op ${formatDate(verzoek.aangevraagdOp)}` : undefined}>
          Aangevraagd
        </Tag>
      </div>
    </div>
  );
}

/** One actie/taak row with the local status flow. Used under a doel and under an instrument. */
export function ActieRow({ actie, busy, onStatus }: { actie: Actie; busy: boolean; onStatus: (id: string, action: string) => void }) {
  const status = actieStatus(actie.status);
  return (
    <div className="pdca-action-row">
      <div className="pdca-row-main">
        <span className="pdca-text-wrap">{actie.title}</span>
        {actie.assigneeName && <span className="pdca-meta-text">— {actie.assigneeName}</span>}
        {actie.dueDate && <span className="pdca-meta-text">({formatDate(actie.dueDate)})</span>}
        {actie.result && (actie.status === 'COMPLETED' || actie.status === 'REJECTED') && (
          <span className="pdca-text-ellipsis pdca-meta-text pdca-meta-text--italic">— {actie.result}</span>
        )}
      </div>
      <div className="pdca-row-actions">
        {actie.priority && <Tag size="sm" type={actie.priority === 'HIGH' ? 'red' : 'gray'}>{priorityLabel(actie.priority)}</Tag>}
        <Tag size="sm" type={status.tag}>{status.label}</Tag>
        {actie.status === 'PLANNED' && <Button size="sm" kind="ghost" disabled={busy} onClick={() => onStatus(actie.id, 'IN_PROGRESS')}>Starten</Button>}
        {actie.status === 'IN_PROGRESS' && <Button size="sm" kind="ghost" disabled={busy} onClick={() => onStatus(actie.id, 'PENDING_REVIEW')}>Ter beoordeling</Button>}
        {actie.status === 'PENDING_REVIEW' && <>
          <Button size="sm" kind="primary" renderIcon={Checkmark} disabled={busy} onClick={() => onStatus(actie.id, 'approve')}>Goedkeuren</Button>
          <Button size="sm" kind="danger" renderIcon={Close} disabled={busy} onClick={() => onStatus(actie.id, 'reject')}>Afkeuren</Button>
        </>}
      </div>
    </div>
  );
}

/** "Instrument aanvragen": a dropdown of the producttypen that can be requested under a doel. */
export function ProductMenu({ producttypen, disabled, bezigMet, onKies }: {
  producttypen: ProductType[]; disabled: boolean;
  /** The producttype whose request is under way. */
  bezigMet: string | null;
  onKies: (producttype: ProductType) => void;
}) {
  const [open, setOpen] = useState(false);
  return (
    <div className="pdca-menu-anker">
      <Button size="sm" kind="tertiary" renderIcon={ChevronDown} disabled={disabled}
        title={disabled ? 'Alleen bij een actief doel' : undefined} onClick={() => setOpen(o => !o)}>
        Instrument aanvragen
      </Button>
      {open && !disabled && (
        <div className="pdca-menu">
          {producttypen.map(p => (
            <button key={p.uuid} type="button" disabled={bezigMet === p.uuid} className="pdca-menu-item"
              onClick={() => { setOpen(false); onKies(p); }}>
              <span className="pdca-menu-item-naam">{bezigMet === p.uuid ? 'Aanvragen…' : p.naam}</span>
              {(p.organisaties[0] || p.samenvatting) && (
                <span className="pdca-menu-item-sub">{[p.organisaties[0]?.naam, p.samenvatting].filter(Boolean).join(' · ')}</span>
              )}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

/** The nested "Voortgang & notities" panel under an instrument row; scrolls into view when it opens. */
function InstrumentPanel({ children }: { children: React.ReactNode }) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => { ref.current?.scrollIntoView({ block: 'nearest', behavior: 'smooth' }); }, []);
  return <Layer className="pdca-instrument-panel"><div ref={ref}>{children}</div></Layer>;
}
