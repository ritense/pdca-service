import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Theme, Button, Tag, Loading, InlineNotification, Select, SelectItem, TextInput, TextArea } from '@carbon/react';
import { Checkmark } from '@carbon/react/icons';
import { onInit, getInitContext, withdrawPanel, getHostTheme, onHostThemeChanged, HostTheme } from '../shared/bridge';
import { pdca } from '../shared/api';
import { evalTypeLabel, formatDate } from '../shared/labels';
import {
  EVALUATION_PANEL_BUNDLE_KEY, EvaluationSession, EvaluationChange,
  currentEvaluationSession, completeEvaluationSession, updateEvaluationDraft, listEvaluationChanges,
  updateChangeToelichting, onEvaluationEvent, planTitle,
} from '../shared/evaluationSession';
import { WijzigingenLijst } from '../shared/WijzigingenLijst';

type Draft = { evaluatieType: string; deelnemers: string; verslag: string };

const formatTime = (iso: string) => iso.split('T')[1]?.slice(0, 5) ?? '';
const SAVE_DELAY_MS = 700;

/**
 * Side-panel content of a running evaluation. Stays open while the user moves
 * through GZAC. The plan itself is changed in the plan tabs; the panel shows
 * those changes live and holds the contactmoment fields (type, deelnemers,
 * gespreksverslag). Only completing ends the evaluation — there is no cancel.
 */
export function EvaluationPanel() {
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [session, setSession] = useState<EvaluationSession | null>(null);
  const [titel, setTitel] = useState('');
  const [evalTypes, setEvalTypes] = useState<string[]>([]);
  const [draft, setDraft] = useState<Draft>({ evaluatieType: '', deelnemers: '', verslag: '' });
  const [saving, setSaving] = useState<'idle' | 'pending' | 'saved'>('idle');
  const [wijzigingen, setWijzigingen] = useState<EvaluationChange[]>([]);
  const [ended, setEnded] = useState(false);
  const [completed, setCompleted] = useState(false);
  const [confirmComplete, setConfirmComplete] = useState(false);
  const [busy, setBusy] = useState(false);
  const [theme, setTheme] = useState<HostTheme>(getHostTheme());
  const pendingDraft = useRef<Partial<Draft> | null>(null);
  const saveTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => { onInit(() => { setTheme(getHostTheme()); load(); }); }, []);
  useEffect(() => onHostThemeChanged(setTheme), []);
  useEffect(() => onEvaluationEvent(event => {
    if (event.type === 'changed') loadWijzigingen();
  }), []);

  const offeredKey = () => getInitContext().evaluationSessionId as string | undefined;

  const loadWijzigingen = useCallback(async () => {
    try { setWijzigingen(await listEvaluationChanges()); }
    catch (e: any) { setError(e.message); }
  }, []);

  const load = async () => {
    try {
      const current = await currentEvaluationSession();
      if (!current || (offeredKey() && current.id !== offeredKey())) {
        setEnded(true);
        return;
      }
      setSession(current);
      setDraft({
        evaluatieType: current.evaluatieType ?? '',
        deelnemers: current.deelnemers ?? '',
        verslag: current.verslag ?? '',
      });
      const [planTitel, types] = await Promise.all([
        planTitle(current),
        current.caseDefinitionKey
          ? pdca.phaseConfigs.get(current.caseDefinitionKey)
              .then(cfg => JSON.parse(cfg.evaluationTypes) as string[]).catch(() => [])
          : Promise.resolve([] as string[]),
      ]);
      setTitel(planTitel);
      setEvalTypes(types);
      await loadWijzigingen();
    } catch (e: any) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  };

  /** Writes the queued field changes; also called right before completing. */
  const flushDraft = useCallback(async () => {
    if (saveTimer.current) { clearTimeout(saveTimer.current); saveTimer.current = null; }
    const changes = pendingDraft.current;
    if (!changes) return;
    pendingDraft.current = null;
    try {
      await updateEvaluationDraft(changes);
      setSaving('saved');
    } catch (e: any) {
      setError('Opslaan mislukt: ' + e.message);
      setSaving('idle');
    }
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
    } catch (e: any) { setError('Reden opslaan mislukt: ' + e.message); }
  };

  const handleComplete = async () => {
    setBusy(true);
    try {
      await flushDraft();
      await completeEvaluationSession();
      setCompleted(true);
    } catch (e: any) {
      setError(e.message);
    } finally {
      setBusy(false);
      setConfirmComplete(false);
    }
  };

  const closePanel = () => {
    const key = session?.id ?? offeredKey();
    if (key) withdrawPanel(key, EVALUATION_PANEL_BUNDLE_KEY);
  };

  const shell = (content: React.ReactNode) => (
    <Theme theme={theme} className="pdca-panel-theme"><div className="pdca-panel">{content}</div></Theme>
  );

  if (loading) return shell(<Loading withOverlay={false} small />);

  if (completed) {
    return shell(<>
      <InlineNotification kind="success" title="Evaluatie afgerond" lowContrast hideCloseButton
        subtitle="Het verslag en de wijzigingen staan nu bij de evaluaties van het plan." />
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
          {saving === 'pending' ? 'Opslaan…' : saving === 'saved' ? 'Opgeslagen' : ''}
        </span>
      </div>

      <div className="pdca-info-block">
        <div className="pdca-info-label">Plan</div>
        <div className="pdca-info-value">{titel}</div>
      </div>
      <div className="pdca-info-block">
        <div className="pdca-info-label">Gestart</div>
        <div className="pdca-info-value">{formatDate(session.startedAt)} {formatTime(session.startedAt)}</div>
      </div>

      {evalTypes.length > 0 && (
        <Select id="eval-type" size="sm" labelText="Type contactmoment" value={draft.evaluatieType}
          onChange={(e: any) => updateField('evaluatieType', e.target.value)}>
          {evalTypes.map(t => <SelectItem key={t} value={t} text={evalTypeLabel(t)} />)}
        </Select>
      )}
      <TextInput id="eval-deelnemers" size="sm" labelText="Deelnemers" placeholder="Komma-gescheiden"
        value={draft.deelnemers} onChange={(e: any) => updateField('deelnemers', e.target.value)} />
      <TextArea id="eval-verslag" labelText="Gespreksverslag" rows={6}
        helperText="Hoe gaat het met de inwoner, wat is besproken en waarom wordt het plan aangepast."
        value={draft.verslag} onChange={(e: any) => updateField('verslag', e.target.value)} />

      <div className="pdca-section-block">
        <div className="pdca-section-title"><span>Wijzigingen in het plan ({wijzigingen.length})</span></div>
        {wijzigingen.length === 0
          ? <p className="pdca-panel-hint">
              Pas het plan aan in de tabbladen Planoverzicht en Doelen &amp; Acties. Elke wijziging die je
              tijdens deze evaluatie doet, verschijnt hier.
            </p>
          : <WijzigingenLijst wijzigingen={wijzigingen} onToelichting={handleToelichting} />}
      </div>

      <p className="pdca-panel-hint">
        Je kunt vrij door GZAC navigeren; sluiten verbergt het paneel alleen. De evaluatie loopt tot je hem afrondt.
      </p>

      {confirmComplete ? (
        <InlineNotification kind="warning" title="Evaluatie afronden?" lowContrast hideCloseButton
          subtitle={`Het verslag en ${wijzigingen.length} wijziging${wijzigingen.length === 1 ? '' : 'en'} worden vastgelegd als contactmoment. Daarna is de evaluatie zichtbaar voor collega's en kun je hem niet meer aanpassen.`}>
          <div className="pdca-panel-actions">
            <Button size="sm" renderIcon={Checkmark} disabled={busy} onClick={handleComplete}>Afronden</Button>
            <Button size="sm" kind="ghost" disabled={busy} onClick={() => setConfirmComplete(false)}>Terug</Button>
          </div>
        </InlineNotification>
      ) : (
        <div className="pdca-panel-actions">
          <Button size="sm" disabled={busy || verslagLeeg} onClick={() => setConfirmComplete(true)}>Evaluatie afronden</Button>
          {verslagLeeg && <span className="pdca-panel-hint">Vul het gespreksverslag in om af te ronden.</span>}
        </div>
      )}
    </>}
  </>);
}
