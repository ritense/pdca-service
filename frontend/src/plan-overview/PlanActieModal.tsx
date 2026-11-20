import React, { useCallback, useEffect, useState } from 'react';
import { InlineNotification, Modal, TextArea } from '@carbon/react';
import { AfsluitCheck, pdca } from '../shared/api';
import { NietGeladen } from '../shared/Meldingen';
import { useSubmit } from '../shared/useSubmit';
import type { PlanActie } from './overzichtActies';

/** "2 lopende subdoelen, 1 instrument en 1 aanvraag" for what is still open; empty when nothing is. */
function openOnderdelen(check: AfsluitCheck): string {
  const delen = [
    check.openSubdoelen > 0 ? `${check.openSubdoelen} ${check.openSubdoelen === 1 ? 'lopend subdoel' : 'lopende subdoelen'}` : null,
    check.openInstrumenten > 0 ? `${check.openInstrumenten} ${check.openInstrumenten === 1 ? 'lopend instrument' : 'lopende instrumenten'}` : null,
    check.openVerzoeken ? `${check.openVerzoeken} ${check.openVerzoeken === 1 ? 'aanvraag zonder instrument' : 'aanvragen zonder instrument'}` : null,
  ].filter(Boolean) as string[];
  return delen.length <= 1 ? delen.join('') : `${delen.slice(0, -1).join(', ')} en ${delen[delen.length - 1]}`;
}

/**
 * Closing or reopening the plan. Before closing, the backend tells what is
 * still open, so the modal can warn; running instrument dossiers block it.
 */
export function PlanActieModal({ actie, planUuid, weergaveStatus, onClose, onSubmit }: {
  actie: PlanActie | null;
  planUuid: string;
  weergaveStatus: string;
  onClose: () => void;
  /** Throws on failure, so the modal keeps the error and stays open. */
  onSubmit: (actie: PlanActie, reden: string) => Promise<void>;
}) {
  const [reden, setReden] = useState('');
  /** What is still open; null while it loads. */
  const [check, setCheck] = useState<AfsluitCheck | null>(null);
  const [checkFout, setCheckFout] = useState<unknown>(null);
  const { saving, fout, submit, reset } = useSubmit();

  const laadCheck = useCallback(() => {
    setCheck(null);
    setCheckFout(null);
    pdca.plannen.afsluitcheck(planUuid).then(setCheck, setCheckFout);
  }, [planUuid]);

  useEffect(() => {
    if (!actie) return;
    setReden('');
    reset();
    if (actie !== 'heractiveren') laadCheck();
  }, [actie, reset, laadCheck]);

  const sluiten = actie === 'afronden' || actie === 'afbreken';
  const geblokkeerd = sluiten && (check?.openInstrumentdossiers.length ?? 0) > 0;
  const open = check ? openOnderdelen(check) : '';

  const bevestig = async () => {
    if (!actie || (actie === 'afbreken' && !reden.trim()) || geblokkeerd) return;
    if (await submit(() => onSubmit(actie, reden.trim()))) onClose();
  };

  return (
    <Modal open={!!actie} danger={actie === 'afbreken'} size="sm"
      modalHeading={actie === 'afronden' ? 'Plan afronden' : actie === 'afbreken' ? 'Plan afbreken' : 'Plan heractiveren'}
      primaryButtonText={saving ? 'Bezig…' : actie === 'afronden' ? 'Afronden' : actie === 'afbreken' ? 'Afbreken' : 'Heractiveren'}
      secondaryButtonText="Annuleren"
      primaryButtonDisabled={saving || (actie === 'afbreken' && !reden.trim()) || geblokkeerd}
      onRequestClose={onClose} onRequestSubmit={bevestig}>
      <div className="pdca-modal-form">
        {fout && <InlineNotification kind="error" title="Opslaan mislukt" subtitle={fout} lowContrast hideCloseButton />}
        {actie === 'heractiveren' ? (
          <p>Het plan wordt weer actief, met status {weergaveStatus}. De einddatum en de reden van beëindigen vervallen.</p>
        ) : <>
          {checkFout != null && <NietGeladen wat="Wat er nog openstaat" error={checkFout} onRetry={laadCheck} />}
          {geblokkeerd && (
            <InlineNotification kind="warning" title="Sluit eerst de instrumentdossiers af" lowContrast hideCloseButton
              subtitle={`${check!.openInstrumentdossiers.join(', ')} ${check!.openInstrumentdossiers.length === 1 ? 'loopt' : 'lopen'} nog in een eigen instrumentdossier; rond ze daar af of annuleer ze daar.`} />
          )}
          {check && !geblokkeerd && open && (
            <InlineNotification kind="info" title="Er staat nog werk open" lowContrast hideCloseButton
              subtitle={`${open}. Die blijven zoals ze zijn; het plan wordt toch ${actie === 'afronden' ? 'afgerond' : 'afgebroken'}.`} />
          )}
          <p>
            Het plan wordt {actie === 'afronden' ? 'afgerond' : 'afgebroken'} met de datum van vandaag als einddatum.
          </p>
          <TextArea id="plan-reden-einde" rows={3}
            labelText={actie === 'afronden' ? 'Toelichting (optioneel)' : 'Reden (verplicht)'}
            placeholder={actie === 'afronden' ? 'Hoe is het plan afgesloten?' : 'Waarom wordt het plan afgebroken?'}
            value={reden} onChange={(e: React.ChangeEvent<HTMLTextAreaElement>) => setReden(e.target.value)} />
        </>}
      </div>
    </Modal>
  );
}
