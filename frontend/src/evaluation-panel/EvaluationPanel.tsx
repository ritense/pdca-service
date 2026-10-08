import React, { useEffect, useState } from 'react';
import { Theme, Button, Tag, Loading, InlineNotification } from '@carbon/react';
import { onInit, getInitContext, withdrawPanel, getHostTheme, onHostThemeChanged, HostTheme } from '../shared/bridge';
import { formatDate } from '../shared/labels';
import {
  EVALUATION_PANEL_BUNDLE_KEY, EvaluationSession,
  currentEvaluationSession, endEvaluationSession, planTitle,
} from '../shared/evaluationSession';

type Outcome = 'complete' | 'cancel';

const formatTime = (iso: string) => iso.split('T')[1]?.slice(0, 5) ?? '';

/**
 * Side-panel content of a running evaluation. Stays open while the user moves
 * through GZAC; only completing or cancelling ends the session.
 */
export function EvaluationPanel() {
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [session, setSession] = useState<EvaluationSession | null>(null);
  const [titel, setTitel] = useState('');
  const [ended, setEnded] = useState(false);
  const [confirmCancel, setConfirmCancel] = useState(false);
  const [busy, setBusy] = useState(false);
  const [theme, setTheme] = useState<HostTheme>(getHostTheme());

  useEffect(() => { onInit(() => { setTheme(getHostTheme()); load(); }); }, []);
  useEffect(() => onHostThemeChanged(setTheme), []);

  const offeredKey = () => getInitContext().evaluationSessionId as string | undefined;

  const load = async () => {
    try {
      const current = await currentEvaluationSession();
      if (!current || (offeredKey() && current.id !== offeredKey())) {
        setEnded(true);
        return;
      }
      setSession(current);
      setTitel(await planTitle(current));
    } catch (e: any) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  };

  const end = async (outcome: Outcome) => {
    if (!session) return;
    setBusy(true);
    try {
      await endEvaluationSession(outcome);
      withdrawPanel(session.id, EVALUATION_PANEL_BUNDLE_KEY);
    } catch (e: any) {
      setError(e.message);
      setBusy(false);
    }
  };

  if (loading) return <Theme theme={theme} className="pdca-panel-theme"><div className="pdca-panel"><Loading withOverlay={false} small /></div></Theme>;

  if (ended) {
    return (
      <Theme theme={theme} className="pdca-panel-theme">
        <div className="pdca-panel">
          <InlineNotification kind="info" title="Deze evaluatie loopt niet meer" lowContrast hideCloseButton />
          {offeredKey() && (
            <Button size="sm" kind="secondary" onClick={() => withdrawPanel(offeredKey()!, EVALUATION_PANEL_BUNDLE_KEY)}>
              Paneel sluiten
            </Button>
          )}
        </div>
      </Theme>
    );
  }

  return (
    <Theme theme={theme} className="pdca-panel-theme">
      <div className="pdca-panel">
        {error && <InlineNotification kind="error" title="Fout" subtitle={error} lowContrast onCloseButtonClick={() => setError(null)} />}

        {session && (
          <>
            <div><Tag type="green">Evaluatie loopt</Tag></div>

            <div className="pdca-info-block">
              <div className="pdca-info-label">Plan</div>
              <div className="pdca-info-value">{titel}</div>
            </div>
            <div className="pdca-info-block">
              <div className="pdca-info-label">Gestart</div>
              <div className="pdca-info-value">{formatDate(session.startedAt)} {formatTime(session.startedAt)}</div>
            </div>
            <div className="pdca-info-block">
              <div className="pdca-info-label">Door</div>
              <div className="pdca-info-value">{session.userLogin}</div>
            </div>

            <p className="pdca-panel-hint">
              Je kunt vrij door GZAC navigeren; dit paneel blijft staan tot je de evaluatie afrondt of annuleert.
            </p>

            {confirmCancel ? (
              <InlineNotification
                kind="warning"
                title="Evaluatie annuleren?"
                subtitle="De evaluatie wordt beëindigd zonder af te ronden."
                lowContrast
                hideCloseButton
              >
                <div className="pdca-panel-actions">
                  <Button size="sm" kind="danger" disabled={busy} onClick={() => end('cancel')}>Ja, annuleren</Button>
                  <Button size="sm" kind="ghost" disabled={busy} onClick={() => setConfirmCancel(false)}>Nee</Button>
                </div>
              </InlineNotification>
            ) : (
              <div className="pdca-panel-actions">
                <Button size="sm" disabled={busy} onClick={() => end('complete')}>Evaluatie afronden</Button>
                <Button size="sm" kind="danger--ghost" disabled={busy} onClick={() => setConfirmCancel(true)}>Annuleren</Button>
              </div>
            )}
          </>
        )}
      </div>
    </Theme>
  );
}
