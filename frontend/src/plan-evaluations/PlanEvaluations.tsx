import React, { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import {
  Theme, Layer, Button, Tag, Select, SelectItem, Loading, InlineNotification,
} from '@carbon/react';
import { ChevronRight, TrashCan, ArrowRight } from '@carbon/react/icons';
import { onInit, resizeIframe } from '../shared/bridge';
import {
  openplan, openklant, pdca, planVoorDossier,
  Plan, Doel, Klantcontact, ContactmomentDetails, CompletedEvaluation,
} from '../shared/api';
import { evalTypeLabel, formatDate, isHoofddoelType, contactmomentStatusLabel, contactmomentStatusTag } from '../shared/labels';
import {
  EvaluationSession, EvaluationChange, offerEvaluationPanel, startEvaluationSession, syncEvaluationPanel,
  onEvaluationEvent, currentEvaluationSession, listEvaluationChanges,
} from '../shared/evaluationSession';
import { WijzigingenLijst } from '../shared/WijzigingenLijst';

/** Doelvoortgang in intake contactmomenten covers the subdoelen; the hoofddoel (the strategy) stays out. */
async function listSubdoelen(planUuid: string): Promise<Doel[]> {
  const [doelen, doeltypen] = await Promise.all([
    openplan.doelen.listByPlan(planUuid),
    openplan.doeltypen.list().catch(() => []),
  ]);
  return doelen.filter(d => {
    const type = doeltypen.find(t => t.uuid === d.doeltype?.uuid);
    return !(type && isHoofddoelType(type));
  });
}

const evalTypeTag = (evalType: string) =>
  evalType === 'INTAKE' ? 'purple' : evalType === 'CRISIS' ? 'red' : evalType === 'INSPECTION' ? 'warm-gray'
    : evalType === 'EVALUATION' ? 'green' : 'blue';

const formatTime = (iso: string) => iso.split('T')[1]?.slice(0, 5) ?? '';

/**
 * The plan's contactmomenten. Evaluations are run in GZAC's side panel
 * ("Start evaluatie"). The user's own running evaluation of this plan is
 * shown on top (only to them); colleagues see an evaluation once it is
 * completed, with its gespreksverslag and the plan changes made during it.
 */
export function PlanEvaluations() {
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [plan, setPlan] = useState<Plan | null>(null);
  const [doelen, setDoelen] = useState<Doel[]>([]);
  const [contactmomenten, setContactmomenten] = useState<Klantcontact[]>([]);
  const [cmDetails, setCmDetails] = useState<ContactmomentDetails[]>([]);
  const [evaluaties, setEvaluaties] = useState<CompletedEvaluation[]>([]);
  const [evalTypes, setEvalTypes] = useState<string[]>([]);
  const [filter, setFilter] = useState<string | null>(null);
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const [session, setSession] = useState<EvaluationSession | null>(null);
  const [sessionMessage, setSessionMessage] = useState<string | null>(null);
  const [lopendOpen, setLopendOpen] = useState(false);
  const [lopendeWijzigingen, setLopendeWijzigingen] = useState<EvaluationChange[]>([]);
  const docRef = useRef<string | null>(null);

  useEffect(() => {
    onInit(ctx => {
      docRef.current = ctx.documentId || null;
      loadData();
      syncEvaluationPanel(docRef.current).then(setSession);
    });
  }, []);

  const handleStartSession = async () => {
    try {
      setSessionMessage(null);
      const result = await startEvaluationSession();
      if (result.session) {
        setSession(result.session);
        await offerEvaluationPanel(result.session);
      } else {
        setSession(result.conflict);
        setSessionMessage(result.message);
      }
    } catch (e: any) { setError(e.message); }
  };
  useEffect(() => onEvaluationEvent(event => {
    if (event.type === 'started') setSession(event.session);
    if (event.type === 'completed') { setSession(null); reloadRef.current(); }
  }), []);
  useEffect(() => { resizeIframe(); }, [loading, contactmomenten, expanded, session, sessionMessage, lopendOpen, lopendeWijzigingen]);

  const loadData = useCallback(async () => {
    try {
      setLoading(true);
      // Plan = dossier (1:1): the plan directly linked to this dossier.
      if (!docRef.current) {
        setError('Geen dossiercontext ontvangen. Een plan is 1:1 een dossier; open dit tabblad vanuit een GZAC-dossier.');
        setLoading(false);
        return;
      }
      const result = await planVoorDossier(docRef.current);
      if (!result.plan) {
        setError(result.fout);
        setLoading(false);
        return;
      }
      const p = result.plan;
      const pDetails = result.details;
      setPlan(p);
      await loadLists(p.uuid);

      if (pDetails?.caseDefinitionKey) {
        try {
          const cfg = await pdca.phaseConfigs.get(pDetails.caseDefinitionKey);
          setEvalTypes(JSON.parse(cfg.evaluationTypes));
        } catch { setEvalTypes(['INTAKE', 'PROGRESS', 'EVALUATION']); }
      } else {
        setEvalTypes(['INTAKE', 'PROGRESS', 'EVALUATION']);
      }
      setLoading(false);
    } catch (e: any) { setError(e.message); setLoading(false); }
  }, []);

  const loadLists = async (planUuid: string) => {
    const [g, cm, cmd, ev] = await Promise.all([
      listSubdoelen(planUuid),
      openklant.klantcontacten.listByPlan(planUuid),
      pdca.contactmomentdetails.listByPlan(planUuid).catch(() => [] as ContactmomentDetails[]),
      pdca.evaluaties.listByPlan(planUuid).catch(() => [] as CompletedEvaluation[]),
    ]);
    setDoelen(g); setContactmomenten(cm); setCmDetails(cmd); setEvaluaties(ev);
  };

  const reload = useCallback(async () => {
    if (plan) await loadLists(plan.uuid);
  }, [plan]);
  // Event listeners are registered once; they reach the current reload through this ref.
  const reloadRef = useRef(reload);
  reloadRef.current = reload;

  const cmDetailsByUuid = useMemo(() => new Map(cmDetails.map(d => [d.contactmomentUuid, d])), [cmDetails]);
  const evaluatieBySessie = useMemo(() => new Map(evaluaties.map(e => [e.sessie.id, e])), [evaluaties]);

  const toggle = (id: string) => setExpanded(prev => {
    const next = new Set(prev); next.has(id) ? next.delete(id) : next.add(id); return next;
  });

  const handleDelete = async (uuid: string) => {
    if (!confirm('Evaluatie verwijderen?')) return;
    try { await pdca.contactmomenten.delete(uuid); }
    catch (e: any) { setError('Verwijderen mislukt: ' + e.message); }
    await reload();
  };

  const handleCreateActie = async (contactmomentUuid: string, text: string, doelUuid: string) => {
    await pdca.acties.create({ doelUuid, title: text, contactmomentUuid });
    alert('Actie aangemaakt');
  };

  /** The user's own running evaluation of this plan; the verslag and changes are refreshed on opening. */
  const lopend = session && plan && session.planUuid === plan.uuid ? session : null;
  useEffect(() => {
    if (!lopend || !lopendOpen) return;
    currentEvaluationSession().then(s => { if (s?.id === lopend.id) setSession(s); }).catch(() => undefined);
    listEvaluationChanges().then(setLopendeWijzigingen).catch(() => setLopendeWijzigingen([]));
  }, [lopend?.id, lopendOpen]);

  const filtered = filter
    ? contactmomenten.filter(cm => cmDetailsByUuid.get(cm.uuid)?.evaluatieType === filter)
    : contactmomenten;
  const sorted = [...filtered].sort((a, b) => (b.plaatsgevondenOp || '').localeCompare(a.plaatsgevondenOp || ''));
  const afgerond = contactmomenten.filter(cm => cm.indicatieContactGelukt === true).length;
  const gepland = contactmomenten.filter(cm => cm.indicatieContactGelukt == null).length;

  if (loading) return <Theme theme="g10"><div className="pdca-container"><Loading withOverlay={false} /></div></Theme>;
  if (error && !plan) return <Theme theme="g10"><div className="pdca-container"><InlineNotification kind="error" title={error} /></div></Theme>;
  if (!plan) return null;

  return (
    <Theme theme="g10">
      <div className="pdca-container">
        {error && <InlineNotification className="pdca-notification" kind="error" title="Fout" subtitle={error} lowContrast onCloseButtonClick={() => setError(null)} />}
        <div className="pdca-page-toolbar">
          <div>
            <h1>Evaluaties</h1>
            <div className="pdca-tags">
              {lopend && <Tag size="sm" type="blue">Lopend: 1</Tag>}
              <Tag size="sm" type="gray">Totaal: {contactmomenten.length}</Tag>
              <Tag size="sm" type="green">Afgerond: {afgerond}</Tag>
              <Tag size="sm" type="warm-gray">Gepland: {gepland}</Tag>
            </div>
          </div>
          <div className="pdca-toolbar-actions">
            {session?.dossierId === docRef.current
              ? <Button kind="tertiary" onClick={() => offerEvaluationPanel(session!)}>Toon lopende evaluatie</Button>
              : <Button disabled={!!session} onClick={handleStartSession}>Start evaluatie</Button>}
          </div>
        </div>
        {session && session.dossierId !== docRef.current && (
          <InlineNotification
            className="pdca-notification"
            kind="info"
            title="Je hebt een lopende evaluatie voor een ander plan"
            subtitle={sessionMessage ?? 'Rond die eerst af voordat je hier een evaluatie start.'}
            lowContrast
            hideCloseButton
          />
        )}

        <div className="pdca-phase-bar">
          <span style={{fontSize: 12, fontWeight: 600, color: 'var(--cds-text-secondary)'}}>Filter:</span>
          <Button size="sm" kind={!filter ? 'primary' : 'ghost'} onClick={() => setFilter(null)}>Alle</Button>
          {evalTypes.map(t => (
            <Button key={t} size="sm" kind={filter === t ? 'primary' : 'ghost'} onClick={() => setFilter(t)}>{evalTypeLabel(t)}</Button>
          ))}
        </div>

        {lopend && (
          <Layer className={`pdca-eval-card type-${lopend.evaluatieType ?? ''}`}>
            <div className="pdca-row pdca-eval-header" onClick={() => setLopendOpen(open => !open)}>
              <div className="pdca-row-main">
                <ChevronRight size={16} style={{transform: lopendOpen ? 'rotate(90deg)' : 'none', transition: '0.2s', flexShrink: 0}} />
                {lopend.evaluatieType && <Tag size="sm" type={evalTypeTag(lopend.evaluatieType) as any}>{evalTypeLabel(lopend.evaluatieType)}</Tag>}
                <Tag size="sm" type="blue">Loopt</Tag>
                <span style={{fontSize: 13, color: 'var(--cds-text-secondary)'}}>
                  Gestart {formatDate(lopend.startedAt)} {formatTime(lopend.startedAt)}
                </span>
              </div>
            </div>
            {lopendOpen && (
              <div className="pdca-eval-body">
                <p style={{fontSize: 12, color: 'var(--cds-text-secondary)', marginTop: 12}}>
                  Alleen jij ziet deze evaluatie tot je hem afrondt in het zijpaneel.
                </p>
                {lopend.deelnemers && <p style={{fontSize: 12, color: 'var(--cds-text-secondary)', marginTop: 12}}>Deelnemers: {lopend.deelnemers}</p>}
                <div className="pdca-info-block" style={{marginTop: 12}}>
                  <div className="pdca-info-label">Gespreksverslag (concept)</div>
                  <div className={`pdca-info-value${lopend.verslag ? '' : ' empty'}`}>{lopend.verslag || 'Nog geen verslag'}</div>
                </div>
                <div className="pdca-section-block">
                  <div className="pdca-section-title">Wijzigingen in het plan ({lopendeWijzigingen.length})</div>
                  {lopendeWijzigingen.length === 0
                    ? <p style={{fontSize: 12, color: 'var(--cds-text-helper)', fontStyle: 'italic'}}>Nog geen wijzigingen</p>
                    : <WijzigingenLijst wijzigingen={lopendeWijzigingen} />}
                </div>
              </div>
            )}
          </Layer>
        )}

        {sorted.length === 0 && !lopend && <div className="pdca-empty"><p>Geen evaluaties gevonden</p></div>}

        {sorted.map(cm => {
          const cmd = cmDetailsByUuid.get(cm.uuid);
          const evaluatie = cmd?.evaluationSessionId ? evaluatieBySessie.get(cmd.evaluationSessionId) : undefined;
          const isOpen = expanded.has(cm.uuid);
          const doelVoortgang = tryParse(cmd?.doelVoortgang);
          const actiepunten = tryParse(cmd?.actiepunten);
          const evalType = cmd?.evaluatieType || '';
          return (
            <Layer key={cm.uuid} className={`pdca-eval-card type-${evalType}`}>
              <div className="pdca-row pdca-eval-header" onClick={() => toggle(cm.uuid)}>
                <div className="pdca-row-main">
                  <ChevronRight size={16} style={{transform: isOpen ? 'rotate(90deg)' : 'none', transition: '0.2s', flexShrink: 0}} />
                  {evalType && (
                    <Tag size="sm" type={evalTypeTag(evalType) as any}>
                      {evalTypeLabel(evalType)}
                    </Tag>
                  )}
                  <Tag size="sm" type={contactmomentStatusTag(cm) as any}>{contactmomentStatusLabel(cm)}</Tag>
                  <span style={{fontSize: 13, color: 'var(--cds-text-secondary)'}}>{cm.plaatsgevondenOp ? formatDate(cm.plaatsgevondenOp) : 'Geen datum'}</span>
                  {cm.inhoud && <span className="pdca-text-ellipsis" style={{fontSize: 13, color: 'var(--cds-text-primary)', flex: '1 1 8rem'}}>{cm.inhoud}</span>}
                </div>
                <Button size="sm" kind="danger--ghost" renderIcon={TrashCan} iconDescription="Verwijderen" hasIconOnly onClick={(e: any) => { e.stopPropagation(); handleDelete(cm.uuid); }} />
              </div>
              {isOpen && (
                <div className="pdca-eval-body">
                  {evaluatie && <p style={{fontSize: 12, color: 'var(--cds-text-secondary)', marginTop: 12}}>Uitgevoerd door: {evaluatie.sessie.userLogin}</p>}
                  {cmd?.deelnemers && <p style={{fontSize: 12, color: 'var(--cds-text-secondary)', marginTop: 12}}>Deelnemers: {cmd.deelnemers}</p>}
                  {cm.inhoud && <div className="pdca-info-block" style={{marginTop: 12}}>
                    <div className="pdca-info-label">{evaluatie ? 'Gespreksverslag' : 'Notitie'}</div>
                    <div className="pdca-info-value" style={{whiteSpace: 'pre-wrap'}}>{cm.inhoud}</div>
                  </div>}
                  {evaluatie && (
                    <div className="pdca-section-block">
                      <div className="pdca-section-title">Wijzigingen in het plan ({evaluatie.wijzigingen.length})</div>
                      {evaluatie.wijzigingen.length === 0
                        ? <p style={{fontSize: 12, color: 'var(--cds-text-helper)', fontStyle: 'italic'}}>Tijdens deze evaluatie is het plan niet gewijzigd</p>
                        : <WijzigingenLijst wijzigingen={evaluatie.wijzigingen} />}
                    </div>
                  )}
                  {doelVoortgang.length > 0 && (
                    <div className="pdca-section-block">
                      <div className="pdca-section-title">Doelvoortgang</div>
                      {doelVoortgang.map((dv: any, j: number) => {
                        const doel = doelen.find(d => d.uuid === dv.doelUuid);
                        return (
                          <div key={j} style={{marginBottom: 8, padding: '8px 12px', background: 'var(--cds-layer-02)'}}>
                            <div style={{fontWeight: 500, fontSize: 13}}>{doel?.titel || dv.doelUuid}</div>
                            {dv.score != null && <Tag size="sm" type="blue">{dv.score}%</Tag>}
                            {dv.toelichting && <p style={{fontSize: 12, color: 'var(--cds-text-secondary)', marginTop: 4}}>{dv.toelichting}</p>}
                          </div>
                        );
                      })}
                    </div>
                  )}
                  {actiepunten.length > 0 && (
                    <div className="pdca-section-block">
                      <div className="pdca-section-title">Actiepunten</div>
                      {actiepunten.map((ap: string, j: number) => (
                        <Layer key={j} className="pdca-action-row">
                          <span className="pdca-text-wrap" style={{flex: '1 1 12rem'}}>{ap}</span>
                          <Select id={`ap-doel-${cm.uuid}-${j}`} size="sm" labelText="" hideLabel style={{minWidth: 'min(180px, 100%)'}}>
                            <SelectItem value="" text="Maak actie onder doel..." />
                            {doelen.filter(d => d.status !== 'geannuleerd').map(d => (
                              <SelectItem key={d.uuid} value={d.uuid} text={d.titel} />
                            ))}
                          </Select>
                          <Button size="sm" kind="ghost" renderIcon={ArrowRight}
                            onClick={() => {
                              const sel = (document.getElementById(`ap-doel-${cm.uuid}-${j}`) as HTMLSelectElement)?.value;
                              if (sel) handleCreateActie(cm.uuid, ap, sel);
                              else alert('Selecteer eerst een doel');
                            }}>Maak actie</Button>
                        </Layer>
                      ))}
                    </div>
                  )}
                </div>
              )}
            </Layer>
          );
        })}

      </div>
    </Theme>
  );
}

function tryParse(json: string | null | undefined): any[] {
  if (!json) return [];
  try { const r = JSON.parse(json); return Array.isArray(r) ? r : []; } catch { return []; }
}
