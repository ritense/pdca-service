import React, { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import {
  Theme, Layer, Button, Tag, Select, SelectItem, Loading, InlineNotification, Modal, TextInput, TextArea,
  ContentSwitcher, Switch,
} from '@carbon/react';
import { ChevronRight, ArrowRight } from '@carbon/react/icons';
import { onInit, resizeIframe } from '../shared/bridge';
import {
  openplan, openklant, pdca, planVoorDossier,
  Plan, Doel, Klantcontact, ContactmomentDetails, CompletedEvaluation, VoortgangStatusOptie,
} from '../shared/api';
import {
  evalTypeLabel, formatDate, isHoofddoelType, contactmomentStatus, contactmomentDatum, overWeken, vandaag,
  voortgangStatus,
} from '../shared/labels';
import {
  EvaluationSession, EvaluationChange, offerEvaluationPanel, startEvaluationSession, syncEvaluationPanel,
  onEvaluationEvent, currentEvaluationSession, listEvaluationChanges,
} from '../shared/evaluationSession';
import { WijzigingenLijst } from '../shared/WijzigingenLijst';
import { useSubmit } from '../shared/useSubmit';

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

const DEFAULT_EVAL_TYPES = ['INTAKE', 'PROGRESS', 'EVALUATION'];
const DEFAULT_EVALUATIE_TYPE = 'EVALUATION';
/** Default interval to a newly planned evaluation. */
const PLAN_WEKEN = 6;
/** The agenda becomes the klantcontact's inhoud in Open Klant, which holds at most 1000 characters. */
const AGENDA_MAX_LENGTH = 1000;

const isGepland = (cm: Klantcontact) => cm.indicatieContactGelukt == null;

/**
 * The plan's contactmomenten. Evaluations are planned here ("Evaluatie
 * plannen", a planned contactmoment that can be moved) and run in GZAC's side
 * panel ("Start evaluatie", or "Starten" on a planned one). The user's own
 * running evaluation of this plan is shown on top (only to them); colleagues
 * see an evaluation once it is completed, with its gespreksverslag and the
 * plan changes made during it.
 */
export function PlanEvaluations() {
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);
  const [plan, setPlan] = useState<Plan | null>(null);
  const [doelen, setDoelen] = useState<Doel[]>([]);
  const [contactmomenten, setContactmomenten] = useState<Klantcontact[]>([]);
  const [cmDetails, setCmDetails] = useState<ContactmomentDetails[]>([]);
  const [evaluaties, setEvaluaties] = useState<CompletedEvaluation[]>([]);
  const [evalTypes, setEvalTypes] = useState<string[]>([]);
  const [voortgangOpties, setVoortgangOpties] = useState<VoortgangStatusOptie[]>([]);
  const [filter, setFilter] = useState<string | null>(null);
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const [session, setSession] = useState<EvaluationSession | null>(null);
  const [sessionMessage, setSessionMessage] = useState<string | null>(null);
  const [lopendOpen, setLopendOpen] = useState(false);
  const [lopendeWijzigingen, setLopendeWijzigingen] = useState<EvaluationChange[]>([]);
  const [planModal, setPlanModal] = useState(false);
  /** The planned contactmoment being moved to another date. */
  const [verzetten, setVerzetten] = useState<{ uuid: string; datum: string } | null>(null);
  /** Chosen doel per actiepunt (key: contactmoment uuid + index). */
  const [actiepuntDoel, setActiepuntDoel] = useState<Record<string, string>>({});
  /** Outcome of "Maak actie" per actiepunt, shown right below it. */
  const [actiepuntResultaat, setActiepuntResultaat] =
    useState<Record<string, { kind: 'success' | 'error'; tekst: string }>>({});
  const [starting, setStarting] = useState(false);
  const docRef = useRef<string | null>(null);

  useEffect(() => {
    onInit(ctx => {
      docRef.current = ctx.documentId || null;
      loadData();
      syncEvaluationPanel(docRef.current).then(setSession);
    });
  }, []);

  /** Starts an evaluation in the side panel, optionally carrying out a planned contactmoment. */
  const handleStartSession = async (geplandContactmomentUuid?: string) => {
    setStarting(true);
    try {
      setSessionMessage(null);
      const result = await startEvaluationSession(geplandContactmomentUuid);
      if (result.session) {
        setSession(result.session);
        await offerEvaluationPanel(result.session);
      } else {
        setSession(result.conflict);
        setSessionMessage(result.message);
      }
    } catch (e: any) {
      setError('Evaluatie starten mislukt: ' + e.message);
    } finally {
      setStarting(false);
    }
  };
  useEffect(() => onEvaluationEvent(event => {
    if (event.type === 'started') setSession(event.session);
    if (event.type === 'ended') { setSession(null); reloadRef.current(); }
  }), []);
  useEffect(() => { resizeIframe(); }, [loading, contactmomenten, expanded, session, sessionMessage, lopendOpen, lopendeWijzigingen, success, error, actiepuntResultaat]);

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
        } catch { setEvalTypes(DEFAULT_EVAL_TYPES); }
      } else {
        setEvalTypes(DEFAULT_EVAL_TYPES);
      }
      setLoading(false);
    } catch (e: any) { setError(e.message); setLoading(false); }
  }, []);

  const loadLists = async (planUuid: string) => {
    const [g, cm, cmd, ev, vo] = await Promise.all([
      listSubdoelen(planUuid),
      openklant.klantcontacten.listByPlan(planUuid),
      pdca.contactmomentdetails.listByPlan(planUuid).catch(() => [] as ContactmomentDetails[]),
      pdca.evaluaties.listByPlan(planUuid).catch(() => [] as CompletedEvaluation[]),
      pdca.voortgangStatussen(planUuid).catch(() => [] as VoortgangStatusOptie[]),
    ]);
    setDoelen(g); setContactmomenten(cm); setCmDetails(cmd); setEvaluaties(ev); setVoortgangOpties(vo);
  };

  const reload = useCallback(async () => {
    if (plan) await loadLists(plan.uuid);
  }, [plan]);
  // Event listeners are registered once; they reach the current reload through this ref.
  const reloadRef = useRef(reload);
  reloadRef.current = reload;

  const cmDetailsByUuid = useMemo(() => new Map(cmDetails.map(d => [d.contactmomentUuid, d])), [cmDetails]);
  const evaluatieBySessie = useMemo(() => new Map(evaluaties.map(e => [e.sessie.id, e])), [evaluaties]);

  const datumVan = (cm: Klantcontact) => contactmomentDatum(cm, cmDetailsByUuid.get(cm.uuid));

  const toggle = (id: string) => setExpanded(prev => {
    const next = new Set(prev); next.has(id) ? next.delete(id) : next.add(id); return next;
  });

  const handleCreateActie = async (key: string, contactmomentUuid: string, text: string) => {
    const doelUuid = actiepuntDoel[key];
    if (!doelUuid) return;
    const resultaat = (r: { kind: 'success' | 'error'; tekst: string }) =>
      setActiepuntResultaat(prev => ({ ...prev, [key]: r }));
    try {
      await pdca.acties.create({ doelUuid, title: text, contactmomentUuid });
      resultaat({ kind: 'success', tekst: `Actie aangemaakt onder "${doelen.find(d => d.uuid === doelUuid)?.titel ?? 'het doel'}"` });
    } catch (e: any) {
      resultaat({ kind: 'error', tekst: 'Actie aanmaken mislukt: ' + e.message });
    }
  };

  /** The user's own running evaluation of this plan; the verslag and changes are refreshed on opening. */
  const lopend = session && plan && session.planUuid === plan.uuid ? session : null;
  useEffect(() => {
    if (!lopend || !lopendOpen) return;
    currentEvaluationSession().then(s => { if (s?.id === lopend.id) setSession(s); }).catch(() => undefined);
    listEvaluationChanges().then(setLopendeWijzigingen).catch(() => setLopendeWijzigingen([]));
  }, [lopend?.id, lopendOpen]);

  // A planned contactmoment carried out by the running evaluation is shown as that evaluation.
  const uitgevoerd = lopend?.geplandContactmomentUuid
    ? contactmomenten.find(cm => cm.uuid === lopend.geplandContactmomentUuid) ?? null : null;
  const zichtbaar = contactmomenten.filter(cm => cm !== uitgevoerd);
  const filtered = filter
    ? zichtbaar.filter(cm => cmDetailsByUuid.get(cm.uuid)?.evaluatieType === filter)
    : zichtbaar;
  // Planned contactmomenten first (soonest on top), then the history (latest on top).
  const geplande = filtered.filter(isGepland)
    .sort((a, b) => (datumVan(a) || '').localeCompare(datumVan(b) || ''));
  const historie = filtered.filter(cm => !isGepland(cm))
    .sort((a, b) => (b.plaatsgevondenOp || '').localeCompare(a.plaatsgevondenOp || ''));
  const sorted = [...geplande, ...historie];
  const afgerond = contactmomenten.filter(cm => cm.indicatieContactGelukt === true).length;
  const gepland = zichtbaar.filter(isGepland).length;
  const filterOpties = [null, ...evalTypes];

  if (loading) return <Theme theme="g10"><div className="pdca-container"><Loading withOverlay={false} /></div></Theme>;
  if (error && !plan) return <Theme theme="g10"><div className="pdca-container"><InlineNotification kind="error" title={error} /></div></Theme>;
  if (!plan) return null;

  return (
    <Theme theme="g10">
      <div className="pdca-container">
        {error && <InlineNotification className="pdca-notification" kind="error" title="Fout" subtitle={error} lowContrast onCloseButtonClick={() => setError(null)} />}
        {success && <InlineNotification className="pdca-notification" kind="success" title="Gelukt" subtitle={success} lowContrast onCloseButtonClick={() => setSuccess(null)} />}
        <div className="pdca-page-toolbar">
          <div>
            <h1>Evaluaties</h1>
            <div className="pdca-tags">
              {lopend && <Tag size="sm" type="blue">Lopend: 1</Tag>}
              <Tag size="sm" type="gray">Totaal: {contactmomenten.length}</Tag>
              <Tag size="sm" type="green">Afgerond: {afgerond}</Tag>
              <Tag size="sm" type="cool-gray">Gepland: {gepland}</Tag>
            </div>
          </div>
          <div className="pdca-toolbar-actions">
            <Button kind="secondary" onClick={() => setPlanModal(true)}>Evaluatie plannen</Button>
            {session?.dossierId === docRef.current
              ? <Button kind="tertiary" onClick={() => offerEvaluationPanel(session!)}>Toon lopende evaluatie</Button>
              : <Button disabled={!!session || starting} onClick={() => handleStartSession()}>Start evaluatie</Button>}
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

        {evalTypes.length > 0 && (
          <div className="pdca-phase-bar">
            <ContentSwitcher size="sm" className="pdca-filter-switcher"
              selectedIndex={Math.max(0, filterOpties.indexOf(filter))}
              onChange={({ index }: { index?: number }) => setFilter(filterOpties[index ?? 0] ?? null)}>
              {filterOpties.map(t => <Switch key={t ?? 'alle'} name={t ?? 'alle'} text={t ? evalTypeLabel(t) : 'Alle'} />)}
            </ContentSwitcher>
          </div>
        )}

        {lopend && (
          <Layer className={`pdca-eval-card type-${lopend.evaluatieType ?? ''}`}>
            <div className="pdca-row pdca-eval-header" onClick={() => setLopendOpen(open => !open)}>
              <div className="pdca-row-main">
                <ChevronRight size={16} style={{transform: lopendOpen ? 'rotate(90deg)' : 'none', transition: '0.2s', flexShrink: 0}} />
                {lopend.evaluatieType && <Tag size="sm" type={evalTypeTag(lopend.evaluatieType) as any}>{evalTypeLabel(lopend.evaluatieType)}</Tag>}
                <Tag size="sm" type="blue">Loopt</Tag>
                <span style={{fontSize: 13, color: 'var(--cds-text-secondary)'}}>
                  {uitgevoerd && datumVan(uitgevoerd) && `Gepland op ${formatDate(datumVan(uitgevoerd))} · `}
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
                {uitgevoerd?.inhoud && (
                  <div className="pdca-info-block" style={{marginTop: 12}}>
                    <div className="pdca-info-label">Agenda</div>
                    <div className="pdca-info-value">{uitgevoerd.inhoud}</div>
                  </div>
                )}
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
          const planned = isGepland(cm);
          const datum = datumVan(cm);
          const status = contactmomentStatus(cm, planned ? datum : null);
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
                  <Tag size="sm" type={status.tag}>{status.label}</Tag>
                  <span style={{fontSize: 13, color: 'var(--cds-text-secondary)'}}>
                    {datum ? `${planned ? 'Gepland op ' : ''}${formatDate(datum)}` : 'Geen datum'}
                  </span>
                </div>
                {planned && (
                  <div className="pdca-row-actions" onClick={(e: React.MouseEvent) => e.stopPropagation()}>
                    <Button size="sm" kind="tertiary" disabled={!!session || starting}
                      onClick={() => handleStartSession(cm.uuid)}>Starten</Button>
                    <Button size="sm" kind="ghost"
                      onClick={() => setVerzetten({ uuid: cm.uuid, datum: (datum || '').split('T')[0] })}>Verzetten</Button>
                  </div>
                )}
              </div>
              {isOpen && (
                <div className="pdca-eval-body">
                  {evaluatie && <p style={{fontSize: 12, color: 'var(--cds-text-secondary)', marginTop: 12}}>Uitgevoerd door: {evaluatie.sessie.userLogin}</p>}
                  {cmd?.deelnemers && <p style={{fontSize: 12, color: 'var(--cds-text-secondary)', marginTop: 12}}>Deelnemers: {cmd.deelnemers}</p>}
                  {planned ? (
                    <div className="pdca-info-block" style={{marginTop: 12}}>
                      <div className="pdca-info-label">Agenda</div>
                      <div className={`pdca-info-value${cm.inhoud ? '' : ' empty'}`}>{cm.inhoud || 'Geen agenda'}</div>
                    </div>
                  ) : cm.inhoud && (
                    <div className="pdca-info-block" style={{marginTop: 12}}>
                      <div className="pdca-info-label">{evaluatie ? 'Gespreksverslag' : 'Notitie'}</div>
                      <div className="pdca-info-value">{cm.inhoud}</div>
                    </div>
                  )}
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
                        const weergave = dv.status ? voortgangStatus(dv.status, voortgangOpties, dv.statusNaam) : null;
                        return (
                          <div key={j} style={{marginBottom: 8, padding: '8px 12px', background: 'var(--cds-layer-02)'}}>
                            <div style={{fontWeight: 500, fontSize: 13}}>{doel?.titel || dv.doelUuid}</div>
                            {weergave && <Tag size="sm" type={weergave.tag}>{weergave.label}</Tag>}
                            {dv.toelichting && <p style={{fontSize: 12, color: 'var(--cds-text-secondary)', marginTop: 4}}>{dv.toelichting}</p>}
                          </div>
                        );
                      })}
                    </div>
                  )}
                  {actiepunten.length > 0 && (
                    <div className="pdca-section-block">
                      <div className="pdca-section-title">Actiepunten</div>
                      {actiepunten.map((ap: string, j: number) => {
                        const key = `${cm.uuid}-${j}`;
                        const resultaat = actiepuntResultaat[key];
                        return (
                          <React.Fragment key={j}>
                            <Layer className="pdca-action-row">
                              <span className="pdca-text-wrap" style={{flex: '1 1 12rem'}}>{ap}</span>
                              <Select id={`ap-doel-${key}`} size="sm" labelText="" hideLabel style={{minWidth: 'min(180px, 100%)'}}
                                value={actiepuntDoel[key] ?? ''}
                                onChange={(e: React.ChangeEvent<HTMLSelectElement>) =>
                                  setActiepuntDoel(prev => ({ ...prev, [key]: e.target.value }))}>
                                <SelectItem value="" text="Maak actie onder doel..." />
                                {doelen.filter(d => d.status !== 'geannuleerd').map(d => (
                                  <SelectItem key={d.uuid} value={d.uuid} text={d.titel} />
                                ))}
                              </Select>
                              <Button size="sm" kind="ghost" renderIcon={ArrowRight} disabled={!actiepuntDoel[key]}
                                onClick={() => handleCreateActie(key, cm.uuid, ap)}>Maak actie</Button>
                            </Layer>
                            {resultaat && (
                              <InlineNotification className="pdca-notification" kind={resultaat.kind} lowContrast
                                title={resultaat.kind === 'success' ? 'Gelukt' : 'Fout'} subtitle={resultaat.tekst}
                                onCloseButtonClick={() => setActiepuntResultaat(prev => {
                                  const { [key]: _, ...rest } = prev;
                                  return rest;
                                })} />
                            )}
                          </React.Fragment>
                        );
                      })}
                    </div>
                  )}
                </div>
              )}
            </Layer>
          );
        })}

        <PlanEvaluatieModal open={planModal} planUuid={plan.uuid} evalTypes={evalTypes}
          onClose={() => setPlanModal(false)}
          onPlanned={async datum => {
            setPlanModal(false);
            setSuccess(`Evaluatie gepland op ${formatDate(datum)}`);
            await reload();
          }} />
        <VerzetModal target={verzetten} onClose={() => setVerzetten(null)}
          onMoved={async datum => {
            setVerzetten(null);
            setSuccess(`Evaluatie verzet naar ${formatDate(datum)}`);
            await reload();
          }} />
      </div>
    </Theme>
  );
}

/** Plans an evaluation: a planned contactmoment with date, type and an optional agenda. */
function PlanEvaluatieModal({ open, planUuid, evalTypes, onClose, onPlanned }: {
  open: boolean; planUuid: string; evalTypes: string[];
  onClose: () => void; onPlanned: (datum: string) => void;
}) {
  const defaultType = evalTypes.includes(DEFAULT_EVALUATIE_TYPE) ? DEFAULT_EVALUATIE_TYPE : evalTypes[0] ?? DEFAULT_EVALUATIE_TYPE;
  const [datum, setDatum] = useState('');
  const [type, setType] = useState(defaultType);
  const [agenda, setAgenda] = useState('');
  const { saving, fout, submit, reset } = useSubmit();
  useEffect(() => {
    if (open) { setDatum(overWeken(PLAN_WEKEN)); setType(defaultType); setAgenda(''); reset(); }
  }, [open]);

  const plannen = async () => {
    if (!datum || !type) return;
    const ok = await submit(() => pdca.contactmomenten.create({
      planUuid, datum, evaluatieType: type, afgerond: false, verslag: agenda.trim() || undefined,
    }));
    if (ok) onPlanned(datum);
  };

  return (
    <Modal open={open} modalHeading="Evaluatie plannen" size="sm"
      primaryButtonText={saving ? 'Plannen…' : 'Plannen'} secondaryButtonText="Annuleren"
      primaryButtonDisabled={saving || !datum || !type || agenda.length > AGENDA_MAX_LENGTH}
      onRequestClose={onClose} onRequestSubmit={plannen}>
      <div className="pdca-modal-form">
        {fout && <InlineNotification kind="error" title="Plannen mislukt" subtitle={fout} lowContrast hideCloseButton />}
        <TextInput id="plan-datum" type="date" labelText="Datum" min={vandaag()} value={datum}
          onChange={(e: React.ChangeEvent<HTMLInputElement>) => setDatum(e.target.value)} />
        <Select id="plan-type" labelText="Type" value={type}
          onChange={(e: React.ChangeEvent<HTMLSelectElement>) => setType(e.target.value)}>
          {evalTypes.map(t => <SelectItem key={t} value={t} text={evalTypeLabel(t)} />)}
        </Select>
        <TextArea id="plan-agenda" labelText="Agenda of notitie (optioneel)" rows={4}
          enableCounter maxCount={AGENDA_MAX_LENGTH} value={agenda}
          onChange={(e: React.ChangeEvent<HTMLTextAreaElement>) => setAgenda(e.target.value)} />
      </div>
    </Modal>
  );
}

/** Moves a planned evaluation to another date. */
function VerzetModal({ target, onClose, onMoved }: {
  target: { uuid: string; datum: string } | null; onClose: () => void; onMoved: (datum: string) => void;
}) {
  const [datum, setDatum] = useState('');
  const { saving, fout, submit, reset } = useSubmit();
  useEffect(() => { if (target) { setDatum(target.datum); reset(); } }, [target]);

  const opslaan = async () => {
    if (!target || !datum) return;
    const ok = await submit(() => pdca.contactmomenten.reschedule(target.uuid, datum));
    if (ok) onMoved(datum);
  };

  return (
    <Modal open={!!target} modalHeading="Evaluatie verzetten" size="sm"
      primaryButtonText={saving ? 'Verzetten…' : 'Verzetten'} secondaryButtonText="Annuleren"
      primaryButtonDisabled={saving || !datum || datum === target?.datum}
      onRequestClose={onClose} onRequestSubmit={opslaan}>
      <div className="pdca-modal-form">
        {fout && <InlineNotification kind="error" title="Verzetten mislukt" subtitle={fout} lowContrast hideCloseButton />}
        <TextInput id="verzet-datum" type="date" labelText="Nieuwe datum" min={vandaag()} value={datum}
          onChange={(e: React.ChangeEvent<HTMLInputElement>) => setDatum(e.target.value)} />
      </div>
    </Modal>
  );
}

function tryParse(json: string | null | undefined): any[] {
  if (!json) return [];
  try { const r = JSON.parse(json); return Array.isArray(r) ? r : []; } catch { return []; }
}
