import React, { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import {
  Theme, Button, Tag, Modal, TextInput, TextArea, Select, SelectItem,
  Loading, InlineNotification, Slider,
} from '@carbon/react';
import { Add, ChevronRight, TrashCan, ArrowRight } from '@carbon/react/icons';
import { onInit, resizeIframe } from '../shared/bridge';
import {
  openplan, pdca, deleteContactmomentCascade,
  Plan, PlanDetails, Doel, DoelDetails, Contactmoment, ContactmomentDetails,
} from '../shared/api';
import { statusLabel, evalTypeLabel, formatDate } from '../shared/labels';

export function PlanEvaluations() {
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [plan, setPlan] = useState<Plan | null>(null);
  const [details, setDetails] = useState<PlanDetails | null>(null);
  const [doelen, setDoelen] = useState<Doel[]>([]);
  const [doelDetails, setDoelDetails] = useState<DoelDetails[]>([]);
  const [contactmomenten, setContactmomenten] = useState<Contactmoment[]>([]);
  const [cmDetails, setCmDetails] = useState<ContactmomentDetails[]>([]);
  const [evalTypes, setEvalTypes] = useState<string[]>([]);
  const [filter, setFilter] = useState<string | null>(null);
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const [createModal, setCreateModal] = useState(false);
  const cdkRef = useRef<string | null>(null);

  useEffect(() => { onInit(ctx => { cdkRef.current = ctx.caseDefinitionKey || null; loadData(); }); }, []);
  useEffect(() => { resizeIframe(); }, [loading, contactmomenten, expanded]);

  const loadData = useCallback(async () => {
    try {
      setLoading(true);
      const [plannen, alleDetails] = await Promise.all([
        openplan.plannen.list({ status: 'actief' }),
        pdca.plandetails.list(),
      ]);
      const detailsByUuid = new Map(alleDetails.map(d => [d.planUuid, d]));
      let candidates = plannen;
      if (cdkRef.current) {
        candidates = plannen.filter(p => detailsByUuid.get(p.uuid)?.caseDefinitionKey === cdkRef.current);
      }
      if (candidates.length === 0) { setError('Geen actief plan gevonden'); setLoading(false); return; }
      const p = candidates[0];
      const pDetails = detailsByUuid.get(p.uuid) ?? null;
      setPlan(p); setDetails(pDetails);

      const [g, gd, cm, cmd] = await Promise.all([
        openplan.doelen.listByPlan(p.uuid),
        pdca.doeldetails.listByPlan(p.uuid).catch(() => [] as DoelDetails[]),
        openplan.contactmomenten.listByPlan(p.uuid),
        pdca.contactmomentdetails.listByPlan(p.uuid).catch(() => [] as ContactmomentDetails[]),
      ]);
      setDoelen(g); setDoelDetails(gd); setContactmomenten(cm); setCmDetails(cmd);

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

  const reload = useCallback(async () => {
    if (!plan) return;
    const [g, gd, cm, cmd] = await Promise.all([
      openplan.doelen.listByPlan(plan.uuid),
      pdca.doeldetails.listByPlan(plan.uuid).catch(() => [] as DoelDetails[]),
      openplan.contactmomenten.listByPlan(plan.uuid),
      pdca.contactmomentdetails.listByPlan(plan.uuid).catch(() => [] as ContactmomentDetails[]),
    ]);
    setDoelen(g); setDoelDetails(gd); setContactmomenten(cm); setCmDetails(cmd);
  }, [plan]);

  const cmDetailsByUuid = useMemo(() => new Map(cmDetails.map(d => [d.contactmomentUuid, d])), [cmDetails]);

  const toggle = (id: string) => setExpanded(prev => {
    const next = new Set(prev); next.has(id) ? next.delete(id) : next.add(id); return next;
  });

  const handleDelete = async (uuid: string) => {
    if (!confirm('Evaluatie verwijderen?')) return;
    await deleteContactmomentCascade(uuid); await reload();
  };

  const handleCreateActie = async (contactmomentUuid: string, text: string, doelUuid: string) => {
    await pdca.acties.create({ doelUuid, title: text, contactmomentUuid });
    alert('Actie aangemaakt');
  };

  const filtered = filter
    ? contactmomenten.filter(cm => cmDetailsByUuid.get(cm.uuid)?.evaluatieType === filter)
    : contactmomenten;
  const sorted = [...filtered].sort((a, b) => (b.datum || '').localeCompare(a.datum || ''));
  const afgerond = contactmomenten.filter(cm => cm.status === 'afgerond').length;
  const gepland = contactmomenten.filter(cm => cm.status === 'actief').length;

  if (loading) return <Theme theme="g10"><div className="pdca-container"><Loading withOverlay={false} /></div></Theme>;
  if (error && !plan) return <Theme theme="g10"><div className="pdca-container"><InlineNotification kind="error" title={error} /></div></Theme>;
  if (!plan) return null;

  return (
    <Theme theme="g10">
      <div className="pdca-container">
        {error && <InlineNotification kind="error" title="Fout" subtitle={error} lowContrast onCloseButtonClick={() => setError(null)} />}
        <div style={{display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 24}}>
          <div>
            <h1 style={{fontSize: '1.75rem', fontWeight: 600, marginBottom: 8}}>Evaluaties</h1>
            <div style={{display: 'flex', gap: 16}}>
              <Tag size="sm" type="gray">Totaal: {contactmomenten.length}</Tag>
              <Tag size="sm" type="green">Afgerond: {afgerond}</Tag>
              <Tag size="sm" type="warm-gray">Gepland: {gepland}</Tag>
            </div>
          </div>
          <Button renderIcon={Add} onClick={() => setCreateModal(true)}>Nieuwe evaluatie</Button>
        </div>

        <div className="pdca-phase-bar">
          <span style={{fontSize: 12, fontWeight: 600, color: 'var(--cds-text-secondary)'}}>Filter:</span>
          <Button size="sm" kind={!filter ? 'primary' : 'ghost'} onClick={() => setFilter(null)}>Alle</Button>
          {evalTypes.map(t => (
            <Button key={t} size="sm" kind={filter === t ? 'primary' : 'ghost'} onClick={() => setFilter(t)}>{evalTypeLabel(t)}</Button>
          ))}
        </div>

        {sorted.length === 0 && <div className="pdca-empty"><p>Geen evaluaties gevonden</p></div>}

        {sorted.map(cm => {
          const cmd = cmDetailsByUuid.get(cm.uuid);
          const isOpen = expanded.has(cm.uuid);
          const doelVoortgang = tryParse(cmd?.doelVoortgang);
          const actiepunten = tryParse(cmd?.actiepunten);
          const evalType = cmd?.evaluatieType || '';
          return (
            <div key={cm.uuid} className={`pdca-eval-card type-${evalType}`} style={{marginBottom: 8}}>
              <div style={{display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '14px 20px', cursor: 'pointer'}} onClick={() => toggle(cm.uuid)}>
                <div style={{display: 'flex', alignItems: 'center', gap: 10, flex: 1}}>
                  <ChevronRight size={16} style={{transform: isOpen ? 'rotate(90deg)' : 'none', transition: '0.2s', flexShrink: 0}} />
                  {evalType && (
                    <Tag size="sm" type={evalType === 'INTAKE' ? 'purple' : evalType === 'CRISIS' ? 'red' : evalType === 'INSPECTION' ? 'warm-gray' : evalType === 'EVALUATION' ? 'green' : 'blue'}>
                      {evalTypeLabel(evalType)}
                    </Tag>
                  )}
                  <Tag size="sm" type={cm.status === 'afgerond' ? 'green' : cm.status === 'actief' ? 'warm-gray' : 'gray'}>
                    {cm.status === 'actief' ? 'Gepland' : statusLabel(cm.status)}
                  </Tag>
                  <span style={{fontSize: 13, color: 'var(--cds-text-secondary)'}}>{cm.datum ? formatDate(cm.datum) : 'Geen datum'}</span>
                  {cm.notitie && <span style={{fontSize: 13, color: 'var(--cds-text-primary)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', maxWidth: 300}}>{cm.notitie}</span>}
                </div>
                <Button size="sm" kind="danger--ghost" renderIcon={TrashCan} iconDescription="Verwijderen" hasIconOnly onClick={(e: any) => { e.stopPropagation(); handleDelete(cm.uuid); }} />
              </div>
              {isOpen && (
                <div style={{padding: '0 20px 20px 46px', borderTop: '1px solid var(--cds-border-subtle)'}}>
                  {cmd?.deelnemers && <p style={{fontSize: 12, color: 'var(--cds-text-secondary)', marginTop: 12}}>Deelnemers: {cmd.deelnemers}</p>}
                  {cm.notitie && <div className="pdca-info-block" style={{marginTop: 12}}>
                    <div className="pdca-info-label">Notitie</div>
                    <div className="pdca-info-value">{cm.notitie}</div>
                  </div>}
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
                        <div key={j} className="pdca-action-row">
                          <span>{ap}</span>
                          <Select id={`ap-doel-${cm.uuid}-${j}`} size="sm" labelText="" hideLabel style={{minWidth: 180}}>
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
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              )}
            </div>
          );
        })}

        <CreateEvalModal open={createModal} plan={plan} doelen={doelen} doelDetails={doelDetails} evalTypes={evalTypes}
          onClose={() => setCreateModal(false)} onSave={async () => { setCreateModal(false); await reload(); }} />
      </div>
    </Theme>
  );
}

function tryParse(json: string | null | undefined): any[] {
  if (!json) return [];
  try { const r = JSON.parse(json); return Array.isArray(r) ? r : []; } catch { return []; }
}

function CreateEvalModal({ open, plan, doelen, doelDetails, evalTypes, onClose, onSave }: {
  open: boolean; plan: Plan; doelen: Doel[]; doelDetails: DoelDetails[]; evalTypes: string[];
  onClose: () => void; onSave: () => void;
}) {
  const [evalType, setEvalType] = useState('PROGRESS');
  const [date, setDate] = useState('');
  const [notitie, setNotitie] = useState('');
  const [deelnemers, setDeelnemers] = useState('');
  const [scores, setScores] = useState<Record<string, number>>({});
  const [toelichtingen, setToelichtingen] = useState<Record<string, string>>({});
  const [actiepunten, setActiepunten] = useState('');

  const detailsByDoel = useMemo(() => new Map(doelDetails.map(d => [d.doelUuid, d])), [doelDetails]);

  useEffect(() => {
    if (open) {
      setEvalType(evalTypes[0] || 'PROGRESS'); setDate(new Date().toISOString().split('T')[0]);
      setNotitie(''); setDeelnemers(''); setScores({}); setToelichtingen({}); setActiepunten('');
    }
  }, [open]);

  const handleSubmit = async () => {
    const doelVoortgang = doelen.filter(d => d.status !== 'geannuleerd').map(d => ({
      doelUuid: d.uuid, score: scores[d.uuid] || 0, toelichting: toelichtingen[d.uuid] || '',
    })).filter(dv => dv.score > 0 || dv.toelichting);
    const ap = actiepunten.split('\n').map(l => l.replace(/^[-*]\s*/, '').trim()).filter(Boolean);

    // 1. Contactmoment in Open Plan; 2. PDCA overlay; 3. voortgang per doel.
    const cm = await openplan.contactmomenten.create({
      planUuid: plan.uuid,
      datum: `${date}T12:00:00Z`,
      status: 'afgerond',
      notitie: notitie || '',
    });
    await pdca.contactmomentdetails.upsert(cm.uuid, {
      planUuid: plan.uuid,
      evaluatieType: evalType,
      geplandeDatum: date,
      deelnemers: deelnemers || undefined,
      doelVoortgang: JSON.stringify(doelVoortgang),
      actiepunten: JSON.stringify(ap),
    });
    for (const dv of doelVoortgang) {
      if (dv.score > 0) {
        await pdca.doeldetails.upsert(dv.doelUuid, {
          planUuid: plan.uuid,
          voortgangScore: dv.score,
          voortgangToelichting: dv.toelichting || undefined,
        });
      }
    }
    onSave();
  };

  return (
    <Modal open={open} modalHeading="Nieuwe evaluatie" size="lg"
      primaryButtonText="Opslaan" secondaryButtonText="Annuleren"
      onRequestClose={onClose} onRequestSubmit={handleSubmit}>
      <div className="pdca-modal-form">
        <Select id="eval-type" labelText="Type" value={evalType} onChange={(e: any) => setEvalType(e.target.value)}>
          {evalTypes.map(t => <SelectItem key={t} value={t} text={evalTypeLabel(t)} />)}
        </Select>
        <TextInput id="eval-date" labelText="Datum" type="date" value={date} onChange={(e: any) => setDate(e.target.value)} />
        <TextInput id="eval-deelnemers" labelText="Deelnemers" value={deelnemers} onChange={(e: any) => setDeelnemers(e.target.value)} placeholder="Komma-gescheiden" />
        <TextArea id="eval-notitie" labelText="Notitie / samenvatting" value={notitie} onChange={(e: any) => setNotitie(e.target.value)} />
        <h4 style={{marginTop: 16, marginBottom: 8}}>Voortgang per doel (0-100%)</h4>
        {doelen.filter(d => d.status !== 'geannuleerd').map(d => (
          <div key={d.uuid} style={{marginBottom: 16, padding: 12, background: 'var(--cds-layer-02)'}}>
            <p style={{fontWeight: 500, marginBottom: 8}}>{d.titel}</p>
            <Slider id={`score-${d.uuid}`} labelText="Score" min={0} max={100} step={5}
              value={scores[d.uuid] ?? detailsByDoel.get(d.uuid)?.voortgangScore ?? 0}
              onChange={({ value }: any) => setScores(prev => ({ ...prev, [d.uuid]: value }))} />
            <TextInput id={`toel-${d.uuid}`} labelText="Toelichting" size="sm" value={toelichtingen[d.uuid] || ''}
              onChange={(e: any) => setToelichtingen(prev => ({ ...prev, [d.uuid]: e.target.value }))} />
          </div>
        ))}
        <TextArea id="eval-ap" labelText="Actiepunten (een per regel)" value={actiepunten}
          onChange={(e: any) => setActiepunten(e.target.value)} placeholder="- Afspraak inplannen&#10;- CV bijwerken" />
      </div>
    </Modal>
  );
}
