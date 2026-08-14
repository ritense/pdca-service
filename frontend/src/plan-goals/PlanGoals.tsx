import React, { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import {
  Theme, Button, Tag, Modal, TextInput, TextArea, Select, SelectItem,
  Loading, InlineNotification, ProgressBar,
} from '@carbon/react';
import { Add, Edit, TrashCan, ChevronRight, Checkmark, Close } from '@carbon/react/icons';
import { onInit, resizeIframe } from '../shared/bridge';
import {
  openplan, openproduct, pdca, urn, deleteDoelCascade, productTypeByUrn,
  Plan, PlanDetails, Doel, DoelDetails, DoelType, InstrumentType, Instrument, Actie, ProductType,
} from '../shared/api';
import { statusLabel, doelStatusLabel, doelStatusTag, doelTypeLabel, priorityLabel, formatDate } from '../shared/labels';

export function PlanGoals() {
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [plan, setPlan] = useState<Plan | null>(null);
  const [details, setDetails] = useState<PlanDetails | null>(null);
  const [doelen, setDoelen] = useState<Doel[]>([]);
  const [doelDetails, setDoelDetails] = useState<DoelDetails[]>([]);
  const [acties, setActies] = useState<Actie[]>([]);
  const [instrumenten, setInstrumenten] = useState<Instrument[]>([]);
  const [phases, setPhases] = useState<string[]>([]);
  const [doeltypen, setDoeltypen] = useState<DoelType[]>([]);
  const [instrumenttypen, setInstrumenttypen] = useState<InstrumentType[]>([]);
  const [producttypen, setProducttypen] = useState<ProductType[]>([]);
  const [phaseFilter, setPhaseFilter] = useState<string | null>(null);
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const [goalModal, setGoalModal] = useState(false);
  const [actionModal, setActionModal] = useState<string | null>(null);
  const [instrumentModal, setInstrumentModal] = useState<string | null>(null);
  const [editDoel, setEditDoel] = useState<Doel | null>(null);
  const cdkRef = useRef<string | null>(null);

  useEffect(() => {
    onInit(ctx => {
      cdkRef.current = ctx.caseDefinitionKey || null;
      loadData();
    });
  }, []);

  useEffect(() => { resizeIframe(); }, [loading, doelen, expanded]);

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
      setPlan(p);
      setDetails(pDetails);

      const [g, gd, a, dt, it, pt] = await Promise.all([
        openplan.doelen.listByPlan(p.uuid),
        pdca.doeldetails.listByPlan(p.uuid).catch(() => [] as DoelDetails[]),
        pdca.acties.listByPlan(p.uuid).catch(() => [] as Actie[]),
        openplan.doeltypen.list().catch(() => [] as DoelType[]),
        openplan.instrumenttypen.list().catch(() => [] as InstrumentType[]),
        openproduct.producttypen.list().catch(() => [] as ProductType[]),
      ]);
      const i = await openplan.instrumenten.listByDoelen(g.map(d => d.uuid)).catch(() => [] as Instrument[]);
      setDoelen(g); setDoelDetails(gd); setActies(a); setInstrumenten(i);
      setDoeltypen(dt); setInstrumenttypen(it); setProducttypen(pt);

      if (pDetails?.caseDefinitionKey) {
        try {
          const cfg = await pdca.phaseConfigs.get(pDetails.caseDefinitionKey);
          setPhases(JSON.parse(cfg.phases));
        } catch { setPhases([]); }
      }
      setLoading(false);
    } catch (e: any) { setError(e.message); setLoading(false); }
  }, []);

  const reload = useCallback(async () => {
    if (!plan) return;
    const [g, gd, a] = await Promise.all([
      openplan.doelen.listByPlan(plan.uuid),
      pdca.doeldetails.listByPlan(plan.uuid).catch(() => [] as DoelDetails[]),
      pdca.acties.listByPlan(plan.uuid).catch(() => [] as Actie[]),
    ]);
    const i = await openplan.instrumenten.listByDoelen(g.map(d => d.uuid)).catch(() => [] as Instrument[]);
    setDoelen(g); setDoelDetails(gd); setActies(a); setInstrumenten(i);
  }, [plan]);

  const detailsByDoel = useMemo(() => new Map(doelDetails.map(d => [d.doelUuid, d])), [doelDetails]);

  const toggle = (id: string) => setExpanded(prev => {
    const next = new Set(prev);
    next.has(id) ? next.delete(id) : next.add(id);
    return next;
  });

  const doelenByFase = () => {
    const withFase = doelen.map(d => ({ doel: d, fase: detailsByDoel.get(d.uuid)?.fase || 'Overig' }));
    const filtered = phaseFilter ? withFase.filter(x => x.fase === phaseFilter) : withFase;
    const grouped: Record<string, Doel[]> = {};
    const order = phases.length ? [...phases] : [...new Set(filtered.map(x => x.fase))];
    order.forEach(f => { grouped[f] = []; });
    filtered.forEach(({ doel, fase }) => {
      (grouped[fase] = grouped[fase] || []).push(doel);
    });
    Object.values(grouped).forEach(list =>
      list.sort((a, b) => (detailsByDoel.get(a.uuid)?.sortering ?? 999) - (detailsByDoel.get(b.uuid)?.sortering ?? 999)));
    return { grouped, order: Object.keys(grouped).filter(f => grouped[f]?.length > 0) };
  };

  const actiesVoor = (doelUuid: string) => acties.filter(a => a.doelUuid === doelUuid);
  const instrumentenVoor = (doelUuid: string) => instrumenten.filter(i => i.doelen.some(d => d.uuid === doelUuid));

  // Doel create/update: direct Open Plan call + fase in the PDCA overlay.
  const handleSaveDoel = async (data: { titel: string; beschrijving: string; fase: string; doeltypeUuid?: string }) => {
    try {
      if (editDoel) {
        await openplan.doelen.update(editDoel.uuid, {
          titel: data.titel,
          beschrijving: data.beschrijving,
          ...(data.doeltypeUuid ? { doeltypeUuid: data.doeltypeUuid } : {}),
        });
        await pdca.doeldetails.upsert(editDoel.uuid, { fase: data.fase });
      } else if (plan && details) {
        const doel = await openplan.doelen.create({
          plannenUuids: [plan.uuid],
          persoonUuid: details.persoonUuid,
          doeltypeUuid: data.doeltypeUuid || doeltypen[0]?.uuid,
          titel: data.titel,
          beschrijving: data.beschrijving,
          startdatum: new Date().toISOString(),
        });
        await pdca.doeldetails.upsert(doel.uuid, { planUuid: plan.uuid, fase: data.fase });
      }
      setGoalModal(false); setEditDoel(null); await reload();
    } catch (e: any) { setError('Doel opslaan mislukt: ' + e.message); }
  };

  const handleDeleteDoel = async (uuid: string) => {
    if (!confirm('Doel verwijderen (inclusief instrumenten en acties)?')) return;
    try { await deleteDoelCascade(uuid); await reload(); }
    catch (e: any) { setError('Verwijderen mislukt: ' + e.message); }
  };

  // Behaald/niet behaald: Open Plan status afgerond + resultaat.
  const handleDoelResultaat = async (uuid: string, resultaat: 'behaald' | 'gefaald') => {
    await openplan.doelen.update(uuid, { status: 'afgerond', resultaat, einddatum: new Date().toISOString() });
    await reload();
  };

  const handleSaveActie = async (doelUuid: string, data: any) => {
    await pdca.acties.create({ doelUuid, ...data });
    setActionModal(null); await reload();
  };

  const handleActieStatus = async (id: string, action: string) => {
    if (action === 'approve') await pdca.acties.approve(id);
    else if (action === 'reject') await pdca.acties.reject(id);
    else await pdca.acties.update(id, { status: action });
    await reload();
  };

  const handleSaveInstrument = async (doelUuid: string, data: { titel: string; producttype?: ProductType }) => {
    try {
      await openplan.instrumenten.create({
        titel: data.titel,
        startdatum: new Date().toISOString(),
        doelenUuids: [doelUuid],
        ontwikkelwensenUuids: [],
        instrumenttypeUuid: instrumenttypen[0]?.uuid,
        // Instrument -> producttype link is a URN by producttype code.
        ...(data.producttype ? { product: urn('openproduct', 'producttype', data.producttype.code) } : {}),
      });
      setInstrumentModal(null); await reload();
    } catch (e: any) { setError('Instrument opslaan mislukt: ' + e.message); }
  };

  const handleInstrumentAfronden = async (uuid: string) => {
    await openplan.instrumenten.update(uuid, { status: 'afgerond', resultaat: 'behaald', einddatum: new Date().toISOString() });
    await reload();
  };

  if (loading) return <Theme theme="g10"><div className="pdca-container"><Loading withOverlay={false} /></div></Theme>;
  if (error && !plan) return <Theme theme="g10"><div className="pdca-container"><InlineNotification kind="error" title={error} /></div></Theme>;
  if (!plan) return null;

  const { grouped, order } = doelenByFase();

  return (
    <Theme theme="g10">
      <div className="pdca-container">
        {error && <InlineNotification kind="error" title="Fout" subtitle={error} lowContrast onCloseButtonClick={() => setError(null)} />}
        <div className="pdca-phase-bar">
          <span className="pdca-section-title" style={{margin: 0}}>Fase:</span>
          <Button size="sm" kind={!phaseFilter ? 'primary' : 'ghost'} onClick={() => setPhaseFilter(null)}>Alle</Button>
          {phases.map(p => (
            <Button key={p} size="sm" kind={phaseFilter === p ? 'primary' : 'ghost'} onClick={() => setPhaseFilter(p)}>{p}</Button>
          ))}
          <div style={{flex: 1}} />
          <Button size="sm" renderIcon={Add} onClick={() => { setEditDoel(null); setGoalModal(true); }}>Doel toevoegen</Button>
        </div>

        {order.length === 0 && <div className="pdca-empty"><p>Geen doelen gevonden</p></div>}

        {order.map(fase => (
          <div key={fase} className="pdca-phase-group">
            <div className="pdca-phase-group-header">
              <h3>{fase}</h3>
              <Tag size="sm" type="gray">{grouped[fase].length} doelen</Tag>
            </div>
            {grouped[fase].map(doel => {
              const dd = detailsByDoel.get(doel.uuid);
              const da = actiesVoor(doel.uuid);
              const di = instrumentenVoor(doel.uuid);
              const isOpen = expanded.has(doel.uuid);
              const pct = dd?.voortgangScore || 0;
              return (
                <div key={doel.uuid} className={`pdca-goal-card status-${doel.status}`}>
                  <div className="pdca-goal-header" onClick={() => toggle(doel.uuid)}>
                    <div style={{display: 'flex', alignItems: 'center', gap: 12, flex: 1, minWidth: 0}}>
                      <ChevronRight size={16} style={{transform: isOpen ? 'rotate(90deg)' : 'none', transition: 'transform 0.2s', flexShrink: 0}} />
                      <span style={{fontWeight: 500}}>{doel.titel}</span>
                      {doel.doeltype && (
                        <Tag size="sm" type="purple">
                          {doelTypeLabel(doeltypen.find(t => t.uuid === doel.doeltype!.uuid))}
                        </Tag>
                      )}
                    </div>
                    <div style={{display: 'flex', alignItems: 'center', gap: 8, flexShrink: 0}}>
                      <div className="pdca-progress-mini">
                        <div className={`pdca-progress-mini-fill ${pct >= 75 ? 'high' : pct >= 40 ? 'mid' : ''}`} style={{width: `${pct}%`}} />
                      </div>
                      <span style={{fontSize: 11, color: 'var(--cds-text-secondary)'}}>{pct}%</span>
                      <Tag size="sm" type={doelStatusTag(doel.status, doel.resultaat) as any}>
                        {doelStatusLabel(doel.status, doel.resultaat)}
                      </Tag>
                    </div>
                  </div>
                  {isOpen && (
                    <div className="pdca-goal-body" style={{ padding: '1rem 1.25rem 1.25rem 3.25rem' }}>
                      {doel.beschrijving && <p style={{color: 'var(--cds-text-secondary)', fontSize: 13, marginBottom: 16}}>{doel.beschrijving}</p>}

                      <div style={{display: 'flex', gap: 6, marginBottom: 20, flexWrap: 'wrap', alignItems: 'center'}}>
                        {doel.status === 'actief' && <>
                          <Button size="sm" kind="primary" onClick={() => handleDoelResultaat(doel.uuid, 'behaald')}>Behaald</Button>
                          <Button size="sm" kind="danger" onClick={() => handleDoelResultaat(doel.uuid, 'gefaald')}>Niet behaald</Button>
                        </>}
                        <Button size="sm" kind="ghost" renderIcon={Edit} onClick={() => { setEditDoel(doel); setGoalModal(true); }}>Bewerken</Button>
                        <Button size="sm" kind="danger--ghost" renderIcon={TrashCan} onClick={() => handleDeleteDoel(doel.uuid)}>Verwijderen</Button>
                      </div>

                      <div style={{marginBottom: 20}}>
                        <div style={{display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 4}}>
                          <span style={{fontSize: 12, fontWeight: 500, color: 'var(--cds-text-secondary)', textTransform: 'uppercase', letterSpacing: '0.5px'}}>Voortgang</span>
                          <span style={{fontSize: 13, fontWeight: 600, color: 'var(--cds-text-primary)'}}>{pct}%</span>
                        </div>
                        <ProgressBar label="" value={pct} max={100} size="small" hideLabel />
                        {dd?.voortgangToelichting && <p style={{fontSize: 12, color: 'var(--cds-text-secondary)', marginTop: 4}}>{dd.voortgangToelichting}</p>}
                      </div>

                      <div className="pdca-section-block">
                        <div className="pdca-section-title">
                          <span>Acties ({da.length})</span>
                          <Button size="sm" kind="ghost" renderIcon={Add} onClick={() => setActionModal(doel.uuid)}>Actie toevoegen</Button>
                        </div>
                        {da.map(actie => (
                          <div key={actie.id} className="pdca-action-row">
                            <div style={{display: 'flex', alignItems: 'center', gap: 8, flex: 1, minWidth: 0}}>
                              <span>{actie.title}</span>
                              {actie.assigneeName && <span style={{fontSize: 11, color: 'var(--cds-text-secondary)'}}>— {actie.assigneeName}</span>}
                              {actie.dueDate && <span style={{fontSize: 11, color: 'var(--cds-text-secondary)'}}>({formatDate(actie.dueDate)})</span>}
                            </div>
                            <div style={{display: 'flex', alignItems: 'center', gap: 6, flexShrink: 0}}>
                              {actie.priority && <Tag size="sm" type={actie.priority === 'HIGH' ? 'red' : 'gray'}>{priorityLabel(actie.priority)}</Tag>}
                              <Tag size="sm" type={actie.status === 'COMPLETED' ? 'green' : actie.status === 'PENDING_REVIEW' ? 'warm-gray' : actie.status === 'IN_PROGRESS' ? 'blue' : 'gray'}>
                                {statusLabel(actie.status)}
                              </Tag>
                              {actie.status === 'PLANNED' && <Button size="sm" kind="ghost" onClick={() => handleActieStatus(actie.id, 'IN_PROGRESS')}>Start</Button>}
                              {actie.status === 'IN_PROGRESS' && <Button size="sm" kind="ghost" onClick={() => handleActieStatus(actie.id, 'PENDING_REVIEW')}>Ter beoordeling</Button>}
                              {actie.status === 'PENDING_REVIEW' && <>
                                <Button size="sm" kind="primary" renderIcon={Checkmark} onClick={() => handleActieStatus(actie.id, 'approve')}>Goedkeuren</Button>
                                <Button size="sm" kind="danger" renderIcon={Close} onClick={() => handleActieStatus(actie.id, 'reject')}>Afkeuren</Button>
                              </>}
                            </div>
                          </div>
                        ))}
                        {da.length === 0 && <p style={{fontSize: 12, color: 'var(--cds-text-helper)', fontStyle: 'italic'}}>Geen acties</p>}
                      </div>

                      <div className="pdca-section-block">
                        <div className="pdca-section-title">
                          <span>Instrumenten ({di.length})</span>
                          <Button size="sm" kind="ghost" renderIcon={Add} onClick={() => setInstrumentModal(doel.uuid)}>Instrument toevoegen</Button>
                        </div>
                        {di.map(inst => {
                          const pt = productTypeByUrn(producttypen, inst.product);
                          return (
                            <div key={inst.uuid} className="pdca-action-row">
                              <div style={{display: 'flex', alignItems: 'center', gap: 8, flex: 1}}>
                                <span>{inst.titel}</span>
                                {pt && <span style={{fontSize: 11, color: 'var(--cds-text-secondary)'}}>— {pt.organisaties[0]?.naam || pt.code}{pt.themas[0] ? ` · ${pt.themas[0].naam}` : ''}</span>}
                              </div>
                              <div style={{display: 'flex', alignItems: 'center', gap: 6}}>
                                <Tag size="sm" type={doelStatusTag(inst.status, inst.resultaat) as any}>{doelStatusLabel(inst.status, inst.resultaat)}</Tag>
                                {inst.status === 'actief' && <Button size="sm" kind="ghost" onClick={() => handleInstrumentAfronden(inst.uuid)}>Afronden</Button>}
                              </div>
                            </div>
                          );
                        })}
                        {di.length === 0 && <p style={{fontSize: 12, color: 'var(--cds-text-helper)', fontStyle: 'italic'}}>Geen instrumenten</p>}
                      </div>
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        ))}

        <DoelModal open={goalModal} doel={editDoel} fase={editDoel ? detailsByDoel.get(editDoel.uuid)?.fase : undefined}
          phases={phases} doeltypen={doeltypen}
          onClose={() => { setGoalModal(false); setEditDoel(null); }} onSave={handleSaveDoel} />
        <ActieModal open={!!actionModal} doelUuid={actionModal}
          onClose={() => setActionModal(null)} onSave={handleSaveActie} />
        <InstrumentModal open={!!instrumentModal} doelUuid={instrumentModal} producttypen={producttypen}
          onClose={() => setInstrumentModal(null)} onSave={handleSaveInstrument} />
      </div>
    </Theme>
  );
}

function DoelModal({ open, doel, fase, phases, doeltypen, onClose, onSave }: {
  open: boolean; doel: Doel | null; fase?: string; phases: string[]; doeltypen: DoelType[];
  onClose: () => void; onSave: (data: { titel: string; beschrijving: string; fase: string; doeltypeUuid?: string }) => void;
}) {
  const [titel, setTitel] = useState('');
  const [beschrijving, setBeschrijving] = useState('');
  const [selectedFase, setSelectedFase] = useState('');
  const [doeltypeUuid, setDoeltypeUuid] = useState('');
  useEffect(() => {
    if (open) {
      setTitel(doel?.titel || ''); setBeschrijving(doel?.beschrijving || '');
      setSelectedFase(fase || phases[0] || 'Overig'); setDoeltypeUuid(doel?.doeltype?.uuid || '');
    }
  }, [open, doel]);
  return (
    <Modal open={open} modalHeading={doel ? 'Doel bewerken' : 'Doel toevoegen'}
      primaryButtonText="Opslaan" secondaryButtonText="Annuleren"
      onRequestClose={onClose}
      onRequestSubmit={() => onSave({ titel, beschrijving, fase: selectedFase, doeltypeUuid: doeltypeUuid || undefined })}>
      <div className="pdca-modal-form">
        <Select id="doel-fase" labelText="Fase" value={selectedFase} onChange={(e: any) => setSelectedFase(e.target.value)}>
          {(phases.length ? phases : ['Overig']).map(p => <SelectItem key={p} value={p} text={p} />)}
        </Select>
        <TextInput id="doel-titel" labelText="Titel" value={titel} onChange={(e: any) => setTitel(e.target.value)} />
        <TextArea id="doel-beschrijving" labelText="Beschrijving" value={beschrijving} onChange={(e: any) => setBeschrijving(e.target.value)} />
        <Select id="doel-type" labelText="Doeltype (Open Plan)" value={doeltypeUuid} onChange={(e: any) => setDoeltypeUuid(e.target.value)}>
          <SelectItem value="" text="-- Kies doeltype --" />
          {doeltypen.map(t => <SelectItem key={t.uuid} value={t.uuid} text={doelTypeLabel(t)} />)}
        </Select>
      </div>
    </Modal>
  );
}

function ActieModal({ open, doelUuid, onClose, onSave }: {
  open: boolean; doelUuid: string | null; onClose: () => void; onSave: (doelUuid: string, data: any) => void;
}) {
  const [title, setTitle] = useState('');
  const [desc, setDesc] = useState('');
  const [assigneeType, setAssigneeType] = useState('PROFESSIONAL');
  const [assigneeName, setAssigneeName] = useState('');
  const [priority, setPriority] = useState('NORMAL');
  const [dueDate, setDueDate] = useState('');
  useEffect(() => { if (open) { setTitle(''); setDesc(''); setAssigneeName(''); setDueDate(''); } }, [open]);
  return (
    <Modal open={open} modalHeading="Actie toevoegen" primaryButtonText="Opslaan" secondaryButtonText="Annuleren"
      onRequestClose={onClose} onRequestSubmit={() => doelUuid && onSave(doelUuid, { title, description: desc, assigneeType, assigneeName, priority, dueDate: dueDate || undefined })}>
      <div className="pdca-modal-form">
        <TextInput id="actie-title" labelText="Titel" value={title} onChange={(e: any) => setTitle(e.target.value)} />
        <TextArea id="actie-desc" labelText="Beschrijving" value={desc} onChange={(e: any) => setDesc(e.target.value)} />
        <Select id="actie-assignee-type" labelText="Uitvoerder type" value={assigneeType} onChange={(e: any) => setAssigneeType(e.target.value)}>
          <SelectItem value="PROFESSIONAL" text="Behandelaar" />
          <SelectItem value="SUBJECT" text="Inwoner / Eigenaar" />
          <SelectItem value="PROVIDER" text="Aanbieder" />
        </Select>
        <TextInput id="actie-assignee" labelText="Naam uitvoerder" value={assigneeName} onChange={(e: any) => setAssigneeName(e.target.value)} />
        <Select id="actie-priority" labelText="Prioriteit" value={priority} onChange={(e: any) => setPriority(e.target.value)}>
          <SelectItem value="NORMAL" text="Normaal" />
          <SelectItem value="HIGH" text="Hoog" />
          <SelectItem value="LOW" text="Laag" />
        </Select>
        <TextInput id="actie-due" labelText="Deadline" type="date" value={dueDate} onChange={(e: any) => setDueDate(e.target.value)} />
      </div>
    </Modal>
  );
}

function InstrumentModal({ open, doelUuid, producttypen, onClose, onSave }: {
  open: boolean; doelUuid: string | null; producttypen: ProductType[]; onClose: () => void;
  onSave: (doelUuid: string, data: { titel: string; producttype?: ProductType }) => void;
}) {
  const [titel, setTitel] = useState('');
  const [productUuid, setProductUuid] = useState('');
  useEffect(() => { if (open) { setTitel(''); setProductUuid(''); } }, [open]);
  const selected = producttypen.find(p => p.uuid === productUuid);
  const onProductSelect = (uuid: string) => {
    setProductUuid(uuid);
    const pt = producttypen.find(p => p.uuid === uuid);
    if (pt) setTitel(pt.naam);
  };
  return (
    <Modal open={open} modalHeading="Instrument toevoegen" primaryButtonText="Opslaan" secondaryButtonText="Annuleren"
      onRequestClose={onClose} onRequestSubmit={() => doelUuid && onSave(doelUuid, { titel, producttype: selected })}>
      <div className="pdca-modal-form">
        <Select id="inst-product" labelText="Producttype uit Open Product" value={productUuid} onChange={(e: any) => onProductSelect(e.target.value)}>
          <SelectItem value="" text="-- Selecteer producttype (optioneel) --" />
          {producttypen.map(p => (
            <SelectItem key={p.uuid} value={p.uuid} text={`${p.naam}${p.organisaties[0] ? ` (${p.organisaties[0].naam})` : ''}`} />
          ))}
        </Select>
        {selected && (
          <p style={{fontSize: 12, color: 'var(--cds-text-secondary)'}}>
            {selected.samenvatting}
            {selected.parameters.find(p => p.naam === 'duur') ? ` · Duur: ${selected.parameters.find(p => p.naam === 'duur')!.waarde}` : ''}
          </p>
        )}
        <TextInput id="inst-titel" labelText="Titel" value={titel} onChange={(e: any) => setTitel(e.target.value)} />
      </div>
    </Modal>
  );
}
