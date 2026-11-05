import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Button, InlineNotification, Layer, Select, SelectItem, Tag, type TagProps } from '@carbon/react';
import { ArrowRight, ChevronRight } from '@carbon/react/icons';
import { CompletedEvaluation, ContactmomentDetails, Doel, Klantcontact, pdca } from '../shared/api';
import { jsonLijst } from '../shared/caseConfig';
import { formatDate, formatTime } from '../shared/dates';
import {
  currentEvaluationSession, EvaluationChange, EvaluationSession, listEvaluationChanges,
} from '../shared/evaluationSession';
import { foutmelding } from '../shared/http';
import { contactmomentStatus, evalTypeLabel } from '../shared/labels';
import { NietGeladen } from '../shared/Meldingen';
import { WijzigingenLijst } from '../shared/WijzigingenLijst';

const evalTypeTag = (evalType: string): NonNullable<TagProps<'div'>['type']> =>
  evalType === 'INTAKE' ? 'purple' : evalType === 'CRISIS' ? 'red' : evalType === 'INSPECTION' ? 'warm-gray'
    : evalType === 'EVALUATION' ? 'green' : 'blue';

function Chevron({ open }: { open: boolean }) {
  return <ChevronRight size={16} className={`pdca-chevron${open ? ' pdca-chevron--open' : ''}`} />;
}

function InfoBlok({ label, waarde, leeg }: { label: string; waarde: string | null | undefined; leeg?: string }) {
  return (
    <div className="pdca-info-block pdca-info-block--ruim">
      <div className="pdca-info-label">{label}</div>
      <div className={`pdca-info-value${waarde ? '' : ' empty'}`}>{waarde || leeg}</div>
    </div>
  );
}

/**
 * The user's own running evaluation of this plan (only they see it); the
 * verslag and the changes are refreshed when it is opened.
 */
export function LopendeEvaluatieKaart({ session, uitgevoerd, uitgevoerdOp, onSession }: {
  session: EvaluationSession;
  /** The planned contactmoment it carries out, with its date. */
  uitgevoerd: Klantcontact | null;
  uitgevoerdOp: string | null;
  onSession: (session: EvaluationSession) => void;
}) {
  const [open, setOpen] = useState(false);
  const [wijzigingen, setWijzigingen] = useState<EvaluationChange[] | null>(null);
  const [fout, setFout] = useState<unknown>(null);

  const onSessionRef = useRef(onSession);
  onSessionRef.current = onSession;
  const sessionId = session.id;

  const laad = useCallback(() => {
    setFout(null);
    currentEvaluationSession().then(s => { if (s?.id === sessionId) onSessionRef.current(s); }, setFout);
    listEvaluationChanges().then(setWijzigingen, setFout);
  }, [sessionId]);
  useEffect(() => { if (open) laad(); }, [open, laad]);

  return (
    <Layer className={`pdca-eval-card type-${session.evaluatieType ?? ''}`}>
      <div className="pdca-row pdca-eval-header" onClick={() => setOpen(o => !o)}>
        <div className="pdca-row-main">
          <Chevron open={open} />
          {session.evaluatieType && <Tag size="sm" type={evalTypeTag(session.evaluatieType)}>{evalTypeLabel(session.evaluatieType)}</Tag>}
          <Tag size="sm" type="blue">Loopt</Tag>
          <span className="pdca-eval-kop-tekst">
            {uitgevoerdOp && `Gepland op ${formatDate(uitgevoerdOp)} · `}
            Gestart {formatDate(session.startedAt)} {formatTime(session.startedAt)}
          </span>
        </div>
      </div>
      {open && (
        <div className="pdca-eval-body">
          <p className="pdca-eval-meta">Alleen jij ziet deze evaluatie tot je hem afrondt in het zijpaneel.</p>
          {session.deelnemers && <p className="pdca-eval-meta">Deelnemers: {session.deelnemers}</p>}
          {uitgevoerd?.inhoud && <InfoBlok label="Agenda" waarde={uitgevoerd.inhoud} />}
          <InfoBlok label="Gespreksverslag (concept)" waarde={session.verslag} leeg="Nog geen verslag" />
          {fout != null && <NietGeladen wat="De stand van de evaluatie" error={fout} onRetry={laad} />}
          {wijzigingen && (
            <div className="pdca-section-block">
              <div className="pdca-section-title">Wijzigingen in het plan ({wijzigingen.length})</div>
              {wijzigingen.length === 0
                ? <p className="pdca-empty-inline">Nog geen wijzigingen</p>
                : <WijzigingenLijst wijzigingen={wijzigingen} />}
            </div>
          )}
        </div>
      )}
    </Layer>
  );
}

/** One contactmoment: planned (with Starten/Verzetten) or taken place (with its verslag, changes and actiepunten). */
export function ContactmomentKaart({ cm, details, datum, evaluatie, subdoelen, startenUit, onStart, onVerzet }: {
  cm: Klantcontact;
  details: ContactmomentDetails | undefined;
  /** Planned date or the moment it took place. */
  datum: string;
  evaluatie: CompletedEvaluation | undefined;
  subdoelen: Doel[];
  /** Starting is not possible while an evaluation runs or one is being started. */
  startenUit: boolean;
  onStart: () => void;
  onVerzet: () => void;
}) {
  const [open, setOpen] = useState(false);
  const gepland = cm.indicatieContactGelukt == null;
  const evalType = details?.evaluatieType || '';
  const status = contactmomentStatus(cm, gepland ? datum : null);
  const actiepunten = jsonLijst(details?.actiepunten);
  return (
    <Layer className={`pdca-eval-card type-${evalType}`}>
      <div className="pdca-row pdca-eval-header" onClick={() => setOpen(o => !o)}>
        <div className="pdca-row-main">
          <Chevron open={open} />
          {evalType && <Tag size="sm" type={evalTypeTag(evalType)}>{evalTypeLabel(evalType)}</Tag>}
          <Tag size="sm" type={status.tag}>{status.label}</Tag>
          <span className="pdca-eval-kop-tekst">
            {datum ? `${gepland ? 'Gepland op ' : ''}${formatDate(datum)}` : 'Geen datum'}
          </span>
        </div>
        {gepland && (
          <div className="pdca-row-actions" onClick={(e: React.MouseEvent) => e.stopPropagation()}>
            <Button size="sm" kind="tertiary" disabled={startenUit} onClick={onStart}>Starten</Button>
            <Button size="sm" kind="ghost" onClick={onVerzet}>Verzetten</Button>
          </div>
        )}
      </div>
      {open && (
        <div className="pdca-eval-body">
          {evaluatie && <p className="pdca-eval-meta">Uitgevoerd door: {evaluatie.sessie.userLogin}</p>}
          {details?.deelnemers && <p className="pdca-eval-meta">Deelnemers: {details.deelnemers}</p>}
          {gepland
            ? <InfoBlok label="Agenda" waarde={cm.inhoud} leeg="Geen agenda" />
            : cm.inhoud && <InfoBlok label={evaluatie ? 'Gespreksverslag' : 'Notitie'} waarde={cm.inhoud} />}
          {evaluatie && (
            <div className="pdca-section-block">
              <div className="pdca-section-title">Wijzigingen in het plan ({evaluatie.wijzigingen.length})</div>
              {evaluatie.wijzigingen.length === 0
                ? <p className="pdca-empty-inline">Tijdens deze evaluatie is het plan niet gewijzigd</p>
                : <WijzigingenLijst wijzigingen={evaluatie.wijzigingen} />}
            </div>
          )}
          {actiepunten.length > 0 && (
            <div className="pdca-section-block">
              <div className="pdca-section-title">Actiepunten</div>
              {actiepunten.map((tekst, j) => (
                <Actiepunt key={j} id={`${cm.uuid}-${j}`} tekst={tekst} contactmomentUuid={cm.uuid} subdoelen={subdoelen} />
              ))}
            </div>
          )}
        </div>
      )}
    </Layer>
  );
}

/** An actiepunt of a contactmoment, which can become an actie under one of the plan's subdoelen. */
function Actiepunt({ id, tekst, contactmomentUuid, subdoelen }: {
  id: string; tekst: string; contactmomentUuid: string; subdoelen: Doel[];
}) {
  const [doelUuid, setDoelUuid] = useState('');
  const [resultaat, setResultaat] = useState<{ kind: 'success' | 'error'; tekst: string } | null>(null);
  const maakActie = async () => {
    if (!doelUuid) return;
    try {
      await pdca.acties.create({ doelUuid, title: tekst, contactmomentUuid });
      setResultaat({ kind: 'success', tekst: `Actie aangemaakt onder "${subdoelen.find(d => d.uuid === doelUuid)?.titel ?? 'het doel'}"` });
    } catch (e) {
      setResultaat({ kind: 'error', tekst: 'Actie aanmaken mislukt: ' + foutmelding(e) });
    }
  };
  return <>
    <Layer className="pdca-action-row">
      <span className="pdca-text-wrap pdca-actiepunt-tekst">{tekst}</span>
      <Select id={`ap-doel-${id}`} size="sm" labelText="" hideLabel className="pdca-actiepunt-doel" value={doelUuid}
        onChange={(e: React.ChangeEvent<HTMLSelectElement>) => setDoelUuid(e.target.value)}>
        <SelectItem value="" text="Maak actie onder doel..." />
        {subdoelen.filter(d => d.status !== 'geannuleerd').map(d => <SelectItem key={d.uuid} value={d.uuid} text={d.titel} />)}
      </Select>
      <Button size="sm" kind="ghost" renderIcon={ArrowRight} disabled={!doelUuid} onClick={maakActie}>Maak actie</Button>
    </Layer>
    {resultaat && (
      <InlineNotification className="pdca-notification" kind={resultaat.kind} lowContrast
        title={resultaat.kind === 'success' ? 'Gelukt' : 'Fout'} subtitle={resultaat.tekst}
        onCloseButtonClick={() => setResultaat(null)} />
    )}
  </>;
}
