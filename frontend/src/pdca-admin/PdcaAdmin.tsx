import React, { useEffect, useState } from 'react';
import { Button, InlineNotification, Modal } from '@carbon/react';
import { Add } from '@carbon/react/icons';
import { openplan, pdca, PhaseConfig } from '../shared/api';
import { LaadFout, PaginaLaden } from '../shared/Meldingen';
import { eersteFout, useResource } from '../shared/useResource';
import { useSubmit } from '../shared/useSubmit';
import { ConfigModal } from './ConfigModal';
import { ConfigTabel } from './ConfigTabel';

/**
 * PDCA Beheer: the case configuration per case type (GZAC administrators
 * only). The configurations and the standard voortgangsstatussen are needed
 * for the page; the GZAC case-type list and the doeltype register are only
 * needed in the edit modal, which says so when they fail.
 */
export function PdcaAdmin() {
  const configs = useResource(() => pdca.phaseConfigs.list(), []);
  const standaardVoortgang = useResource(() => pdca.voortgangStatussen(), []);
  const gzacDossiertypes = useResource(() => pdca.caseDefinitions.list(), []);
  const doeltypen = useResource(() => openplan.doeltypen.list(), []);
  /** The edit modal: closed, a new configuration (null) or the one being edited. */
  const [modal, setModal] = useState<{ bewerk: PhaseConfig | null } | null>(null);
  const [teVerwijderen, setTeVerwijderen] = useState<PhaseConfig | null>(null);
  const [toast, setToast] = useState<string | null>(null);

  useEffect(() => {
    if (!toast) return;
    const timer = setTimeout(() => setToast(null), 3000);
    return () => clearTimeout(timer);
  }, [toast]);

  const fout = eersteFout(configs, standaardVoortgang);
  if (!configs.data || !standaardVoortgang.data) {
    return fout
      ? <LaadFout pagina titel="De dossierconfiguratie kon niet geladen worden" error={fout.error} context="beheer" onRetry={fout.reload} />
      : <PaginaLaden />;
  }

  return (
    <div className="pdca-container">
      {toast && <InlineNotification className="pdca-notification" kind="success" title={toast} onClose={() => setToast(null)} />}
      {fout && <LaadFout titel="De dossierconfiguratie kon niet worden bijgewerkt" error={fout.error} context="beheer" onRetry={fout.reload} />}

      <div className="pdca-page-toolbar">
        <h1>PDCA Beheer — Dossierconfiguratie</h1>
        <Button renderIcon={Add} onClick={() => setModal({ bewerk: null })}>Nieuwe configuratie</Button>
      </div>

      {configs.data.length === 0 ? (
        <div className="pdca-empty"><p>Geen configuraties gevonden. Maak een nieuwe aan.</p></div>
      ) : (
        <ConfigTabel configs={configs.data} standaardVoortgang={standaardVoortgang.data}
          onBewerk={cfg => setModal({ bewerk: cfg })} onVerwijder={setTeVerwijderen} />
      )}

      <ConfigModal open={!!modal} bewerk={modal?.bewerk ?? null} configs={configs.data}
        gzacDossiertypes={gzacDossiertypes} doeltypen={doeltypen} standaardVoortgang={standaardVoortgang.data}
        onClose={() => setModal(null)}
        onSaved={() => { setModal(null); setToast('Configuratie opgeslagen'); configs.reload(); }} />
      <VerwijderModal config={teVerwijderen} onClose={() => setTeVerwijderen(null)}
        onDeleted={() => { setTeVerwijderen(null); setToast('Configuratie verwijderd'); configs.reload(); }} />
    </div>
  );
}

/** Removing a case type's configuration, confirmed in a modal; a refusal stays in the modal. */
function VerwijderModal({ config, onClose, onDeleted }: {
  config: PhaseConfig | null; onClose: () => void; onDeleted: () => void;
}) {
  const { saving, fout, submit, reset } = useSubmit();
  useEffect(() => { if (config) reset(); }, [config, reset]);
  const verwijder = async () => {
    if (config && await submit(() => pdca.phaseConfigs.delete(config.caseDefinitionKey))) onDeleted();
  };
  return (
    <Modal open={!!config} danger size="sm" modalHeading="Configuratie verwijderen"
      primaryButtonText={saving ? 'Verwijderen…' : 'Verwijderen'} secondaryButtonText="Annuleren"
      primaryButtonDisabled={saving} onRequestClose={onClose} onRequestSubmit={verwijder}>
      <div className="pdca-modal-form">
        {fout && <InlineNotification kind="error" title="Verwijderen mislukt" subtitle={fout} lowContrast hideCloseButton />}
        <p>De configuratie voor <strong>{config?.caseDefinitionKey}</strong> wordt verwijderd.</p>
      </div>
    </Modal>
  );
}
