import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Layer, Button, Tag, Loading, InlineNotification, TextInput, TextArea } from '@carbon/react';
import { Checkmark } from '@carbon/react/icons';
import { withdrawPanel } from '../shared/bridge';
import { openklant, pdca } from '../shared/api';
import { formatDate, formatTime, overWeken, vandaag } from '../shared/dates';
import { verslagVan } from '../shared/documenten';
import { Evaluatieverslag } from '../shared/Evaluatieverslag';
import { foutmelding } from '../shared/http';
import { LaadFout, NietGeladen } from '../shared/Meldingen';
import { useInitContext } from '../shared/PdcaRoot';
import { useDocumenten } from '../shared/useDocumenten';
import {
  EVALUATION_PANEL_BUNDLE_KEY, EvaluationSession, EvaluationChange,
  currentEvaluationSession, completeEvaluationSession, cancelEvaluationSession, updateEvaluationDraft,
  listEvaluationChanges, updateChangeToelichting, planTitle, EVALUATION_POLL_MS,
} from '../shared/evaluationSession';
import { WijzigingenLijst } from '../shared/WijzigingenLijst';

type Draft = { deelnemers: string; verslag: string };

const SAVE_DELAY_MS = 700;
/** The verslag becomes the klantcontact's inhoud in Open Klant, which holds at most 1000 characters. */
const VERSLAG_MAX_LENGTH = 1000;
/** Default interval to the next evaluation offered after completing one. */
const VOLGENDE_EVALUATIE_WEKEN = 6;

/**
 * Side-panel content of a running evaluation. Stays open while the user moves
 * through GZAC. The plan itself is changed in the plan tabs; the panel polls
 * those changes and holds the contactmoment fields (deelnemers,
 * gespreksverslag). The evaluation ends explicitly: completing records it as
 * a contactmoment (the planned one it carries out, if any), after which the
 * panel shows its evaluatieverslag as it is being made and the next
 * evaluation can be planned; cancelling records nothing and leaves the plan
 * changes made during it in place. Once a locked part of the plan has been
 * changed in it, the evaluation can only be completed.
 */
export function EvaluationPanel() {
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  /** The session itself could not be read: the panel cannot show anything else. */
  const [laadFout, setLaadFout] = useState<unknown>(null);
  const [session, setSession] = useState<EvaluationSession | null>(null);
  /** The plan's title; null while unknown, with [titelFout] when it could not be read. */
  const [titel, setTitel] = useState<string | null>(null);
  const [titelFout, setTitelFout] = useState<unknown>(null);
  const [draft, setDraft] = useState<Draft>({ deelnemers: '', verslag: '' });
  const [saving, setSaving] = useState<'idle' | 'pending' | 'saved' | 'failed'>('idle');
  const [wijzigingen, setWijzigingen] = useState<EvaluationChange[]>([]);
  /** A locked part of the plan was changed in this evaluation (the backend refuses to cancel it then). */
  const [vastgesteld, setVastgesteld] = useState(false);
  const [ended, setEnded] = useState(false);
  const [completed, setCompleted] = useState(false);
  const [cancelled, setCancelled] = useState(false);
  const [confirm, setConfirm] = useState<'complete' | 'cancel' | null>(null);
  const [busy, setBusy] = useState(false);
  /** Why completing or cancelling failed, shown next to the buttons (the general error sits at the top of the panel). */
  const [endError, setEndError] = useState<string | null>(null);
  /** Date of the planned contactmoment this evaluation carries out. */
  const [geplandOp, setGeplandOp] = useState<string | null>(null);
  const initContext = useInitContext();
  const pendingDraft = useRef<Partial<Draft> | null>(null);
  const saveTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  /** The autosave under way, so completing waits for it and sends whatever it left behind. */
  const savingDraft = useRef<Promise<boolean> | null>(null);

  const offeredKey = () => initContext.evaluationSessionId as string | undefined;

  const loadWijzigingen = useCallback(async () => {
    try { setWijzigingen(await listEvaluationChanges()); }
    catch (e) { setError(foutmelding(e)); }
  }, []);

  /** Whether the tabs changed a locked part of the plan meanwhile; best effort, a failed read leaves it as it was. */
  const loadVastgesteld = useCallback(async (sessieId: string) => {
    try {
      const current = await currentEvaluationSession();
      if (current?.id === sessieId) setVastgesteld(current.vastgesteldGewijzigd);
    } catch { /* the next poll tries again */ }
  }, []);

  // The tabs record changes in the backend and cannot message the panel, so the panel polls.
  useEffect(() => {
    if (!session || completed || cancelled) return;
    const timer = setInterval(() => { loadWijzigingen(); loadVastgesteld(session.id); }, EVALUATION_POLL_MS);
    return () => clearInterval(timer);
  }, [session, completed, cancelled, loadWijzigingen, loadVastgesteld]);

  /** The plan's title (the panel says so when it fails) and the date of the planned contactmoment it carries out. */
  const loadDetails = useCallback((current: EvaluationSession) => {
    setTitelFout(null);
    planTitle(current).then(setTitel, setTitelFout);
    if (current.geplandContactmomentUuid) {
      // Only the date in the "Planning" line; without it the line just says "Geplande evaluatie".
      openklant.klantcontacten.listByPlan(current.planUuid)
        .then(cms => setGeplandOp(cms.find(cm => cm.uuid === current.geplandContactmomentUuid)?.plaatsgevondenOp ?? null))
        .catch(() => setGeplandOp(null));
    }
  }, []);

  const load = useCallback(async () => {
    setLoading(true);
    setLaadFout(null);
    try {
      const current = await currentEvaluationSession();
      const offered = initContext.evaluationSessionId as string | undefined;
      if (!current || (offered && current.id !== offered)) {
        setEnded(true);
        return;
      }
      setSession(current);
      setVastgesteld(current.vastgesteldGewijzigd);
      setDraft({
        deelnemers: current.deelnemers ?? '',
        verslag: current.verslag ?? '',
      });
      loadDetails(current);
      await loadWijzigingen();
    } catch (e) {
      setLaadFout(e);
    } finally {
      setLoading(false);
    }
  }, [initContext, loadDetails, loadWijzigingen]);

  useEffect(() => { load(); }, [load]);

  /**
   * Writes the queued field changes; also called right before completing.
   * A failed save puts its fields back in the queue (behind newer edits) and
   * resolves false, so completing never goes ahead with an older verslag.
   */
  const flushDraft = useCallback(async (): Promise<boolean> => {
    if (saveTimer.current) { clearTimeout(saveTimer.current); saveTimer.current = null; }
    if (savingDraft.current) await savingDraft.current;
    const changes = pendingDraft.current;
    if (!changes) return true;
    pendingDraft.current = null;
    const save = updateEvaluationDraft(changes).then(
      () => {
        setSaving(pendingDraft.current ? 'pending' : 'saved');
        return true;
      },
      (e: unknown) => {
        pendingDraft.current = { ...changes, ...pendingDraft.current };
        setError('Opslaan mislukt: ' + foutmelding(e));
        setSaving('failed');
        return false;
      },
    );
    savingDraft.current = save;
    const ok = await save;
    if (savingDraft.current === save) savingDraft.current = null;
    return ok;
  }, []);

  const updateField = (field: keyof Draft, value: string) => {
    setDraft(prev => ({ ...prev, [field]: value }));
    pendingDraft.current = { ...pendingDraft.current, [field]: value };
    setSaving('pending');
    if (saveTimer.current) clearTimeout(saveTimer.current);
    saveTimer.current = setTimeout(flushDraft, SAVE_DELAY_MS);
  };

  const handleToelichting = async (id: string, toelichting: string) => {
    try {
      await updateChangeToelichting(id, toelichting);
      setWijzigingen(prev => prev.map(w => (w.id === id ? { ...w, toelichting } : w)));
    } catch (e) { setError('Reden opslaan mislukt: ' + foutmelding(e)); }
  };

  const handleComplete = async () => {
    setBusy(true);
    setEndError(null);
    try {
      if (!(await flushDraft())) {
        setEndError('Het verslag is nog niet opgeslagen. Probeer het opnieuw.');
        return;
      }
      // The completed session is the bron of its evaluatieverslag.
      setSession(await completeEvaluationSession());
      setCompleted(true);
    } catch (e) {
      setEndError(foutmelding(e));
    } finally {
      setBusy(false);
      setConfirm(null);
    }
  };

  // Cancelling discards the contactmoment fields: queued edits are dropped instead of saved.
  const handleCancel = async () => {
    setBusy(true);
    setEndError(null);
    try {
      if (saveTimer.current) { clearTimeout(saveTimer.current); saveTimer.current = null; }
      if (savingDraft.current) await savingDraft.current;
      await cancelEvaluationSession();
      pendingDraft.current = null;
      setCancelled(true);
    } catch (e) {
      setEndError(foutmelding(e));
    } finally {
      setBusy(false);
      setConfirm(null);
    }
  };

  const closePanel = () => {
    const key = session?.id ?? offeredKey();
    if (key) withdrawPanel(key, EVALUATION_PANEL_BUNDLE_KEY);
  };

  // The panel surface is GZAC's layer (PdcaRoot); its content sits one layer up so the fields stand out.
  const shell = (content: React.ReactNode) => <Layer className="pdca-panel">{content}</Layer>;

  if (loading) return shell(<Loading withOverlay={false} small />);

  if (laadFout) return shell(<LaadFout titel="De evaluatie kon niet geladen worden" error={laadFout} onRetry={load} />);

  if (completed && session) {
    return shell(<>
      <InlineNotification kind="success" title="Evaluatie afgerond" lowContrast hideCloseButton
        subtitle="Het verslag en de wijzigingen staan nu bij de evaluaties van het plan." />
      <VerslagSectie planUuid={session.planUuid} sessieId={session.id} />
      <VolgendeEvaluatie planUuid={session.planUuid} />
      <Button size="sm" kind="secondary" onClick={closePanel}>Paneel sluiten</Button>
    </>);
  }

  if (cancelled) {
    return shell(<>
      <InlineNotification kind="info" title="Evaluatie geannuleerd" lowContrast hideCloseButton
        subtitle="Er is geen contactmoment vastgelegd." />
      <Button size="sm" kind="secondary" onClick={closePanel}>Paneel sluiten</Button>
    </>);
  }

  if (ended) {
    return shell(<>
      <InlineNotification kind="info" title="Deze evaluatie loopt niet meer" lowContrast hideCloseButton />
      {offeredKey() && <Button size="sm" kind="secondary" onClick={closePanel}>Paneel sluiten</Button>}
    </>);
  }

  const verslagLeeg = !draft.verslag.trim();

  return shell(<>
    {error && <InlineNotification kind="error" title="Fout" subtitle={error} lowContrast onCloseButtonClick={() => setError(null)} />}

    {session && <>
      <div className="pdca-panel-head">
        <Tag type="green">Evaluatie loopt</Tag>
        <span className="pdca-panel-save">
          {saving === 'pending' ? 'Opslaan…' : saving === 'saved' ? 'Opgeslagen' : saving === 'failed' ? 'Niet opgeslagen' : ''}
        </span>
      </div>

      <div className="pdca-info-block">
        <div className="pdca-info-label">Plan</div>
        <div className="pdca-info-value">{titel ?? 'Plan'}</div>
        {titelFout != null && <NietGeladen wat="De plantitel" error={titelFout} onRetry={() => planTitle(session).then(t => { setTitel(t); setTitelFout(null); }, setTitelFout)} />}
      </div>
      <div className="pdca-info-block">
        <div className="pdca-info-label">Gestart</div>
        <div className="pdca-info-value">{formatDate(session.startedAt)} {formatTime(session.startedAt)}</div>
      </div>
      {session.geplandContactmomentUuid && (
        <div className="pdca-info-block">
          <div className="pdca-info-label">Planning</div>
          <div className="pdca-info-value">
            {geplandOp ? `Geplande evaluatie van ${formatDate(geplandOp)}` : 'Geplande evaluatie'}
          </div>
        </div>
      )}

      <TextInput id="eval-deelnemers" size="sm" labelText="Deelnemers" placeholder="Komma-gescheiden"
        value={draft.deelnemers} onChange={e => updateField('deelnemers', e.target.value)} />
      <TextArea id="eval-verslag" labelText="Gespreksverslag" rows={6} enableCounter maxCount={VERSLAG_MAX_LENGTH}
        helperText="Hoe gaat het met de inwoner, wat is besproken en waarom wordt het plan aangepast."
        value={draft.verslag} onChange={e => updateField('verslag', e.target.value)} />

      <div className="pdca-section-block">
        <div className="pdca-section-title"><span>Wijzigingen in het plan ({wijzigingen.length})</span></div>
        {wijzigingen.length === 0
          ? <p className="pdca-panel-hint">
              Pas het plan aan in de tabbladen Planoverzicht en Doelen. Elke wijziging die je
              tijdens deze evaluatie doet, verschijnt hier.
            </p>
          : <WijzigingenLijst wijzigingen={wijzigingen} onToelichting={handleToelichting} />}
      </div>

      <p className="pdca-panel-hint">
        Je kunt vrij door GZAC navigeren; sluiten verbergt het paneel alleen. De evaluatie loopt tot je hem afrondt
        of annuleert.
      </p>

      {/* A Carbon InlineNotification throws when it contains interactive
          content, so the confirm buttons sit below it. */}
      {confirm === 'complete' && <>
        <InlineNotification kind="warning" title="Evaluatie afronden?" lowContrast hideCloseButton
          subtitle={`Het verslag en ${wijzigingen.length} wijziging${wijzigingen.length === 1 ? '' : 'en'} worden vastgelegd als contactmoment. Daarna is de evaluatie zichtbaar voor collega's en kun je hem niet meer aanpassen.`} />
        <div className="pdca-panel-actions">
          <Button size="sm" renderIcon={Checkmark} disabled={busy} onClick={handleComplete}>
            {busy ? 'Afronden…' : 'Afronden'}
          </Button>
          <Button size="sm" kind="ghost" disabled={busy} onClick={() => setConfirm(null)}>Terug</Button>
        </div>
      </>}
      {confirm === 'cancel' && <>
        <InlineNotification kind="warning" title="Evaluatie annuleren?" lowContrast hideCloseButton
          subtitle="De evaluatie wordt geannuleerd; er wordt geen contactmoment vastgelegd. Wijzigingen die je in het plan hebt gedaan blijven staan." />
        <div className="pdca-panel-actions">
          <Button size="sm" kind="danger" disabled={busy} onClick={handleCancel}>
            {busy ? 'Annuleren…' : 'Evaluatie annuleren'}
          </Button>
          <Button size="sm" kind="ghost" disabled={busy} onClick={() => setConfirm(null)}>Terug</Button>
        </div>
      </>}
      {!confirm && <>
        {endError && (
          <InlineNotification kind="error" title="Dat is niet gelukt" subtitle={endError} lowContrast
            onCloseButtonClick={() => setEndError(null)} />
        )}
        <div className="pdca-panel-actions">
          <Button size="sm" disabled={busy || verslagLeeg} onClick={() => setConfirm('complete')}>Evaluatie afronden</Button>
          {!vastgesteld && (
            <Button size="sm" kind="danger--ghost" disabled={busy} onClick={() => setConfirm('cancel')}>Evaluatie annuleren</Button>
          )}
        </div>
        {verslagLeeg && <p className="pdca-panel-hint">Vul het gespreksverslag in om af te ronden.</p>}
        {vastgesteld && (
          <p className="pdca-panel-hint">
            Je hebt vastgestelde delen van het plan gewijzigd. Rond de evaluatie af, zodat de inwoner op de hoogte is en
            de wijzigingen met het gesprek worden vastgelegd; annuleren kan niet meer.
          </p>
        )}
      </>}
    </>}
  </>);
}

/**
 * After completing: the evaluatieverslag the completion requested (recorded
 * with it), followed until it is ready. Without an aanvraag document
 * generation is off, and the section stays away.
 */
function VerslagSectie({ planUuid, sessieId }: { planUuid: string; sessieId: string }) {
  const { documenten, maakVerslag } = useDocumenten(planUuid);
  if (documenten.error && !documenten.data) {
    return <NietGeladen wat="Het evaluatieverslag" error={documenten.error} onRetry={documenten.reload} />;
  }
  const aanvraag = documenten.data ? verslagVan(documenten.data, sessieId) : null;
  if (!aanvraag) return null;
  return <Evaluatieverslag aanvraag={aanvraag} onMaak={() => maakVerslag(sessieId)} />;
}

/**
 * After completing: plan the next evaluation of the plan as a planned
 * contactmoment (default six weeks from today).
 */
function VolgendeEvaluatie({ planUuid }: { planUuid: string }) {
  const [datum, setDatum] = useState(overWeken(VOLGENDE_EVALUATIE_WEKEN));
  const [busy, setBusy] = useState(false);
  const [fout, setFout] = useState<string | null>(null);
  const [gepland, setGepland] = useState<string | null>(null);

  const plannen = async () => {
    setBusy(true);
    setFout(null);
    try {
      await pdca.contactmomenten.create({ planUuid, datum, afgerond: false });
      setGepland(datum);
    } catch (e) {
      setFout(foutmelding(e));
    } finally {
      setBusy(false);
    }
  };

  if (gepland) {
    return <InlineNotification kind="success" title="Volgende evaluatie gepland" lowContrast hideCloseButton
      subtitle={`Gepland op ${formatDate(gepland)}.`} />;
  }

  return (
    <div className="pdca-section-block">
      <div className="pdca-section-title"><span>Volgende evaluatie plannen</span></div>
      <div className="pdca-panel-form">
        <TextInput id="volgende-datum" size="sm" type="date" labelText="Datum" min={vandaag()}
          value={datum} onChange={e => setDatum(e.target.value)} />
        {fout && <InlineNotification kind="error" title="Plannen mislukt" subtitle={fout} lowContrast
          onCloseButtonClick={() => setFout(null)} />}
        <div className="pdca-panel-actions">
          <Button size="sm" kind="tertiary" disabled={busy || !datum} onClick={plannen}>
            {busy ? 'Plannen…' : 'Plannen'}
          </Button>
        </div>
      </div>
    </div>
  );
}
