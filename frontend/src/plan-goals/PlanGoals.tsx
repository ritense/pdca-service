import React, { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import {
  Theme, Button, Tag, Modal, TextInput, TextArea, Select, SelectItem,
  Loading, InlineNotification, ProgressBar,
} from '@carbon/react';
import { Add, Edit, TrashCan, ChevronRight, Checkmark, Close, StopOutline } from '@carbon/react/icons';
import { onInit, resizeIframe } from '../shared/bridge';
import {
  openplan, openproduct, pdca, urn, urnId, deleteDoelCascade, afbreekDoelCascade, afbreekInstrument, productTypeByUrn,
  Plan, PlanDetails, Doel, DoelDetails, DoelType, InstrumentType, Instrument, InstrumentDetails, Actie, ProductType,
} from '../shared/api';
import {
  statusLabel, doelStatusLabel, doelStatusTag, doelTypeNaam, subdoelTypen, doelCategorie, ordenCategorieen,
  priorityLabel, formatDate,
} from '../shared/labels';

type AfbreekTarget = { kind: 'doel' | 'instrument'; uuid: string; titel: string } | null;

export function PlanGoals() {
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [plan, setPlan] = useState<Plan | null>(null);
  const [details, setDetails] = useState<PlanDetails | null>(null);
  const [doelen, setDoelen] = useState<Doel[]>([]);
  const [doelDetails, setDoelDetails] = useState<DoelDetails[]>([]);
  const [acties, setActies] = useState<Actie[]>([]);
  const [instrumenten, setInstrumenten] = useState<Instrument[]>([]);
  const [instrumentDetails, setInstrumentDetails] = useState<InstrumentDetails[]>([]);
  const [categorieOrdening, setCategorieOrdening] = useState<string[]>([]);
  const [doeltypen, setDoeltypen] = useState<DoelType[]>([]);
  const [instrumenttypen, setInstrumenttypen] = useState<InstrumentType[]>([]);
  const [producttypen, setProducttypen] = useState<ProductType[]>([]);
  const [categorieFilter, setCategorieFilter] = useState<string | null>(null);
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const [voortgangOpen, setVoortgangOpen] = useState<Set<string>>(new Set());
  const [goalModal, setGoalModal] = useState(false);
  const [actionModal, setActionModal] = useState<string | null>(null);
  const [instrumentModal, setInstrumentModal] = useState<string | null>(null);
  const [afbreekTarget, setAfbreekTarget] = useState<AfbreekTarget>(null);
  const [editDoel, setEditDoel] = useState<Doel | null>(null);
  const docRef = useRef<string | null>(null);

  useEffect(() => {
    onInit(ctx => {
      docRef.current = ctx.documentId || null;
      loadData();
    });
  }, []);

  useEffect(() => { resizeIframe(); }, [loading, doelen, expanded, voortgangOpen]);

  const loadData = useCallback(async () => {
    try {
      setLoading(true);
      // Plan = dossier (1:1): match uitsluitend op de zaak-URN van dit dossier.
      if (!docRef.current) {
        setError('Geen dossiercontext ontvangen. Een plan is 1:1 een dossier; open dit tabblad vanuit een GZAC-dossier.');
        setLoading(false);
        return;
      }
      const [plannen, alleDetails] = await Promise.all([
        openplan.plannen.list(),
        pdca.plandetails.list(),
      ]);
      const detailsByUuid = new Map(alleDetails.map(d => [d.planUuid, d]));
      let matches = plannen.filter(p => urnId(p.zaak) === docRef.current);
      let resolveFout: string | null = null;
      if (matches.length === 0) {
        // Startformulier-route: backend koppelt een planId uit de dossier-content onderwater.
        const resolved = await pdca.dossiers.resolvePlan(docRef.current).catch((e: any) => {
          const msg = typeof e?.message === 'string' ? e.message : '';
          if (msg.includes('409') || msg.includes('400')) resolveFout = msg;
          return null;
        });
        if (resolved) {
          matches = (await openplan.plannen.list()).filter(p => urnId(p.zaak) === docRef.current);
        }
      }
      if (matches.length === 0) {
        setError(resolveFout ?? 'Geen plan voor dit dossier. Maak eerst een plan aan via de taak "Plan aanmaken".');
        setLoading(false);
        return;
      }
      const p = matches.find(m => m.status === 'actief') ?? matches[0];
      const pDetails = detailsByUuid.get(p.uuid) ?? null;
      setPlan(p);
      setDetails(pDetails);

      const [g, gd, a, idet, dt, it, pt] = await Promise.all([
        openplan.doelen.listByPlan(p.uuid),
        pdca.doeldetails.listByPlan(p.uuid).catch(() => [] as DoelDetails[]),
        pdca.acties.listByPlan(p.uuid).catch(() => [] as Actie[]),
        pdca.instrumentdetails.listByPlan(p.uuid).catch(() => [] as InstrumentDetails[]),
        openplan.doeltypen.list().catch(() => [] as DoelType[]),
        openplan.instrumenttypen.list().catch(() => [] as InstrumentType[]),
        openproduct.producttypen.list().catch(() => [] as ProductType[]),
      ]);
      const i = await openplan.instrumenten.listByDoelen(g.map(d => d.uuid)).catch(() => [] as Instrument[]);
      setDoelen(g); setDoelDetails(gd); setActies(a); setInstrumenten(i); setInstrumentDetails(idet);
      setDoeltypen(dt); setInstrumenttypen(it); setProducttypen(pt);

      if (pDetails?.caseDefinitionKey) {
        try {
          const cfg = await pdca.phaseConfigs.get(pDetails.caseDefinitionKey);
          setCategorieOrdening(JSON.parse(cfg.categorieOrdening || '[]'));
        } catch { setCategorieOrdening([]); }
      }
      setLoading(false);
    } catch (e: any) { setError(e.message); setLoading(false); }
  }, []);

  const reload = useCallback(async () => {
    if (!plan) return;
    const [g, gd, a, idet] = await Promise.all([
      openplan.doelen.listByPlan(plan.uuid),
      pdca.doeldetails.listByPlan(plan.uuid).catch(() => [] as DoelDetails[]),
      pdca.acties.listByPlan(plan.uuid).catch(() => [] as Actie[]),
      pdca.instrumentdetails.listByPlan(plan.uuid).catch(() => [] as InstrumentDetails[]),
    ]);
    const i = await openplan.instrumenten.listByDoelen(g.map(d => d.uuid)).catch(() => [] as Instrument[]);
    setDoelen(g); setDoelDetails(gd); setActies(a); setInstrumenten(i); setInstrumentDetails(idet);
  }, [plan]);

  const detailsByDoel = useMemo(() => new Map(doelDetails.map(d => [d.doelUuid, d])), [doelDetails]);
  const detailsByInstrument = useMemo(() => new Map(instrumentDetails.map(d => [d.instrumentUuid, d])), [instrumentDetails]);

  const toggle = (id: string, set: React.Dispatch<React.SetStateAction<Set<string>>>) => set(prev => {
    const next = new Set(prev);
    next.has(id) ? next.delete(id) : next.add(id);
    return next;
  });

  // Doelen gegroepeerd op doelcategorie (register); fasering = optionele ordening.
  const doelenPerCategorie = () => {
    const withCategorie = doelen.map(d => ({ doel: d, categorie: doelCategorie(d, doeltypen) }));
    const filtered = categorieFilter ? withCategorie.filter(x => x.categorie === categorieFilter) : withCategorie;
    const grouped: Record<string, Doel[]> = {};
    filtered.forEach(({ doel, categorie }) => {
      (grouped[categorie] = grouped[categorie] || []).push(doel);
    });
    Object.values(grouped).forEach(list =>
      list.sort((a, b) => (detailsByDoel.get(a.uuid)?.sortering ?? 999) - (detailsByDoel.get(b.uuid)?.sortering ?? 999)));
    return { grouped, order: ordenCategorieen(Object.keys(grouped), categorieOrdening) };
  };

  const alleCategorieen = useMemo(
    () => ordenCategorieen([...new Set(doelen.map(d => doelCategorie(d, doeltypen)))], categorieOrdening),
    [doelen, doeltypen, categorieOrdening]
  );

  const actiesVoor = (doelUuid: string) => acties.filter(a => a.doelUuid === doelUuid);
  const instrumentenVoor = (doelUuid: string) => instrumenten.filter(i => i.doelen.some(d => d.uuid === doelUuid));

  // Doelen komen uit het doeltype-register: de titel is de registernaam van
  // het gekozen doeltype, vrije tekst alleen als toelichting t.b.v. de inwoner.
  const handleSaveDoel = async (data: { titel: string; toelichting: string; doeltypeUuid: string }) => {
    try {
      if (editDoel) {
        await openplan.doelen.update(editDoel.uuid, {
          titel: data.titel,
          beschrijving: data.toelichting,
          doeltypeUuid: data.doeltypeUuid,
        });
      } else if (plan && details) {
        const doel = await openplan.doelen.create({
          plannenUuids: [plan.uuid],
          persoonUuid: details.persoonUuid,
          doeltypeUuid: data.doeltypeUuid,
          titel: data.titel,
          beschrijving: data.toelichting,
          startdatum: new Date().toISOString(),
        });
        await pdca.doeldetails.upsert(doel.uuid, { planUuid: plan.uuid, uitvoeringsStatus: 'GEPLAND' });
      }
      setGoalModal(false); setEditDoel(null); await reload();
    } catch (e: any) { setError('Doel opslaan mislukt: ' + e.message); }
  };

  const handleDeleteDoel = async (uuid: string) => {
    if (!confirm('Doel verwijderen (inclusief instrumenten en acties)?')) return;
    try { await deleteDoelCascade(uuid); await reload(); }
    catch (e: any) { setError('Verwijderen mislukt: ' + e.message); }
  };

  const handleStartDoel = async (uuid: string) => {
    if (!plan) return;
    await pdca.doeldetails.upsert(uuid, { planUuid: plan.uuid, uitvoeringsStatus: 'GESTART' });
    await reload();
  };

  const handleAfrondenDoel = async (uuid: string) => {
    await openplan.doelen.update(uuid, { status: 'afgerond', resultaat: 'behaald', einddatum: new Date().toISOString() });
    await reload();
  };

  // Afbreken met verplichte reden; bij een doel worden gekoppelde actieve
  // voorzieningen mee afgebroken.
  const handleAfbreken = async (reden: string) => {
    if (!afbreekTarget || !plan) return;
    try {
      if (afbreekTarget.kind === 'doel') {
        await afbreekDoelCascade(afbreekTarget.uuid, plan.uuid, reden);
      } else {
        await afbreekInstrument(afbreekTarget.uuid, plan.uuid, reden);
      }
      setAfbreekTarget(null);
      await reload();
    } catch (e: any) { setError('Afbreken mislukt: ' + e.message); }
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
        ...(data.producttype ? { product: urn('openproduct', 'producttype', data.producttype.code) } : {}),
      });
      setInstrumentModal(null); await reload();
    } catch (e: any) { setError('Instrument opslaan mislukt: ' + e.message); }
  };

  const handleInstrumentAfronden = async (uuid: string) => {
    await openplan.instrumenten.update(uuid, { status: 'afgerond', resultaat: 'behaald', einddatum: new Date().toISOString() });
    await reload();
  };

  const handleVoortgangSave = async (instrumentUuid: string, uren: number | null, score: number | null, toelichting: string) => {
    if (!plan) return;
    try {
      await pdca.instrumentdetails.upsert(instrumentUuid, {
        planUuid: plan.uuid,
        urenBesteed: uren ?? undefined,
        effectiviteitScore: score ?? undefined,
        effectiviteitToelichting: toelichting || undefined,
      });
      await reload();
    } catch (e: any) { setError('Voortgang opslaan mislukt: ' + e.message); }
  };

  if (loading) return <Theme theme="g10"><div className="pdca-container"><Loading withOverlay={false} /></div></Theme>;
  if (error && !plan) return <Theme theme="g10"><div className="pdca-container"><InlineNotification kind="error" title={error} /></div></Theme>;
  if (!plan) return null;

  const { grouped, order } = doelenPerCategorie();

  return (
    <Theme theme="g10">
      <div className="pdca-container">
        {error && <InlineNotification kind="error" title="Fout" subtitle={error} lowContrast onCloseButtonClick={() => setError(null)} />}
        <div className="pdca-phase-bar">
          <span className="pdca-section-title" style={{margin: 0}}>Categorie:</span>
          <Button size="sm" kind={!categorieFilter ? 'primary' : 'ghost'} onClick={() => setCategorieFilter(null)}>Alle</Button>
          {alleCategorieen.map(c => (
            <Button key={c} size="sm" kind={categorieFilter === c ? 'primary' : 'ghost'} onClick={() => setCategorieFilter(c)}>{c}</Button>
          ))}
          <div style={{flex: 1}} />
          <Button size="sm" renderIcon={Add} onClick={() => { setEditDoel(null); setGoalModal(true); }}>Doel toevoegen</Button>
        </div>

        {order.length === 0 && <div className="pdca-empty"><p>Geen doelen gevonden</p></div>}

        {order.map(categorie => (
          <div key={categorie} className="pdca-phase-group">
            <div className="pdca-phase-group-header">
              <h3>{categorie}</h3>
              <Tag size="sm" type="gray">{grouped[categorie].length} doelen</Tag>
            </div>
            {grouped[categorie].map(doel => {
              const dd = detailsByDoel.get(doel.uuid);
              const da = actiesVoor(doel.uuid);
              const di = instrumentenVoor(doel.uuid);
              const isOpen = expanded.has(doel.uuid);
              const pct = dd?.voortgangScore || 0;
              const uitvoeringsStatus = dd?.uitvoeringsStatus;
              return (
                <div key={doel.uuid} className={`pdca-goal-card status-${doel.status}`}>
                  <div className="pdca-goal-header" onClick={() => toggle(doel.uuid, setExpanded)}>
                    <div style={{display: 'flex', alignItems: 'center', gap: 12, flex: 1, minWidth: 0}}>
                      <ChevronRight size={16} style={{transform: isOpen ? 'rotate(90deg)' : 'none', transition: 'transform 0.2s', flexShrink: 0}} />
                      <span style={{fontWeight: 500}}>{doel.titel}</span>
                      {doel.doeltype && (
                        <Tag size="sm" type="purple">
                          {doelCategorie(doel, doeltypen)}
                        </Tag>
                      )}
                    </div>
                    <div style={{display: 'flex', alignItems: 'center', gap: 8, flexShrink: 0}}>
                      <div className="pdca-progress-mini">
                        <div className={`pdca-progress-mini-fill ${pct >= 75 ? 'high' : pct >= 40 ? 'mid' : ''}`} style={{width: `${pct}%`}} />
                      </div>
                      <span style={{fontSize: 11, color: 'var(--cds-text-secondary)'}}>{pct}%</span>
                      <Tag size="sm" type={doelStatusTag(doel.status, doel.resultaat, uitvoeringsStatus) as any}>
                        {doelStatusLabel(doel.status, doel.resultaat, uitvoeringsStatus)}
                      </Tag>
                    </div>
                  </div>
                  {isOpen && (
                    <div className="pdca-goal-body" style={{ padding: '1rem 1.25rem 1.25rem 3.25rem' }}>
                      {doel.beschrijving && <p style={{color: 'var(--cds-text-secondary)', fontSize: 13, marginBottom: 16}}>{doel.beschrijving}</p>}
                      {doel.status === 'geannuleerd' && doel.toelichtingResultaat && (
                        <InlineNotification kind="warning" title="Afgebroken" subtitle={doel.toelichtingResultaat} lowContrast hideCloseButton style={{marginBottom: 16}} />
                      )}

                      <div style={{display: 'flex', gap: 6, marginBottom: 20, flexWrap: 'wrap', alignItems: 'center'}}>
                        {doel.status === 'actief' && uitvoeringsStatus !== 'GESTART' && (
                          <Button size="sm" kind="primary" onClick={() => handleStartDoel(doel.uuid)}>Starten</Button>
                        )}
                        {doel.status === 'actief' && uitvoeringsStatus === 'GESTART' && (
                          <Button size="sm" kind="primary" renderIcon={Checkmark} onClick={() => handleAfrondenDoel(doel.uuid)}>Afronden</Button>
                        )}
                        {doel.status === 'actief' && (
                          <Button size="sm" kind="danger--tertiary" renderIcon={StopOutline}
                            onClick={() => setAfbreekTarget({ kind: 'doel', uuid: doel.uuid, titel: doel.titel })}>Afbreken</Button>
                        )}
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
                          <span>Instrumenten / voorzieningen ({di.length})</span>
                          <Button size="sm" kind="ghost" renderIcon={Add} onClick={() => setInstrumentModal(doel.uuid)}>Instrument toevoegen</Button>
                        </div>
                        {di.map(inst => {
                          const pt = productTypeByUrn(producttypen, inst.product);
                          const idet = detailsByInstrument.get(inst.uuid);
                          const vOpen = voortgangOpen.has(inst.uuid);
                          return (
                            <div key={inst.uuid} style={{borderBottom: '1px solid var(--cds-border-subtle)'}}>
                              <div className="pdca-action-row" style={{borderBottom: 'none'}}>
                                <div style={{display: 'flex', alignItems: 'center', gap: 8, flex: 1}}>
                                  <span>{inst.titel}</span>
                                  {pt && <span style={{fontSize: 11, color: 'var(--cds-text-secondary)'}}>— {pt.organisaties[0]?.naam || pt.code}{pt.themas[0] ? ` · ${pt.themas[0].naam}` : ''}</span>}
                                  {idet?.urenBesteed != null && <Tag size="sm" type="cool-gray">{idet.urenBesteed} uur</Tag>}
                                  {idet?.effectiviteitScore != null && <Tag size="sm" type="teal">effectiviteit {idet.effectiviteitScore}/5</Tag>}
                                </div>
                                <div style={{display: 'flex', alignItems: 'center', gap: 6}}>
                                  <Tag size="sm" type={doelStatusTag(inst.status, inst.resultaat) as any}>{doelStatusLabel(inst.status, inst.resultaat)}</Tag>
                                  <Button size="sm" kind="ghost" onClick={() => toggle(inst.uuid, setVoortgangOpen)}>Voortgang</Button>
                                  {inst.status === 'actief' && <Button size="sm" kind="ghost" onClick={() => handleInstrumentAfronden(inst.uuid)}>Afronden</Button>}
                                  {inst.status === 'actief' && (
                                    <Button size="sm" kind="danger--ghost" renderIcon={StopOutline} iconDescription="Afbreken" hasIconOnly
                                      onClick={() => setAfbreekTarget({ kind: 'instrument', uuid: inst.uuid, titel: inst.titel })} />
                                  )}
                                </div>
                              </div>
                              {idet?.afbreekReden && (
                                <p style={{fontSize: 12, color: 'var(--cds-text-error, #da1e28)', margin: '0 0 8px 0'}}>Afbreekreden: {idet.afbreekReden}</p>
                              )}
                              {vOpen && (
                                <VoortgangForm instrument={inst} details={idet}
                                  onSave={(uren, score, toelichting) => handleVoortgangSave(inst.uuid, uren, score, toelichting)} />
                              )}
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

        <DoelModal open={goalModal} doel={editDoel} doeltypen={doeltypen}
          onClose={() => { setGoalModal(false); setEditDoel(null); }} onSave={handleSaveDoel} />
        <ActieModal open={!!actionModal} doelUuid={actionModal}
          onClose={() => setActionModal(null)} onSave={handleSaveActie} />
        <InstrumentModal open={!!instrumentModal} doelUuid={instrumentModal} producttypen={producttypen}
          onClose={() => setInstrumentModal(null)} onSave={handleSaveInstrument} />
        <AfbreekModal target={afbreekTarget} onClose={() => setAfbreekTarget(null)} onSubmit={handleAfbreken} />
      </div>
    </Theme>
  );
}

/** Uren + effectiviteit per instrument (PDCA overlay). */
function VoortgangForm({ instrument, details, onSave }: {
  instrument: Instrument; details?: InstrumentDetails;
  onSave: (uren: number | null, score: number | null, toelichting: string) => void;
}) {
  const [uren, setUren] = useState<string>(details?.urenBesteed?.toString() ?? '');
  const [score, setScore] = useState<string>(details?.effectiviteitScore?.toString() ?? '');
  const [toelichting, setToelichting] = useState(details?.effectiviteitToelichting ?? '');
  return (
    <div style={{background: 'var(--cds-layer-02)', padding: 12, margin: '0 0 8px 0', display: 'flex', gap: 12, alignItems: 'flex-end', flexWrap: 'wrap'}}>
      <TextInput id={`uren-${instrument.uuid}`} labelText="Bestede uren" type="number" style={{maxWidth: 120}}
        value={uren} onChange={(e: any) => setUren(e.target.value)} />
      <Select id={`score-${instrument.uuid}`} labelText="Effectiviteit" value={score} onChange={(e: any) => setScore(e.target.value)}>
        <SelectItem value="" text="-" />
        {[1, 2, 3, 4, 5].map(n => <SelectItem key={n} value={String(n)} text={`${n} - ${['geen effect', 'weinig effect', 'neutraal', 'effectief', 'zeer effectief'][n - 1]}`} />)}
      </Select>
      <TextInput id={`toel-${instrument.uuid}`} labelText="Toelichting" style={{minWidth: 240}}
        value={toelichting} onChange={(e: any) => setToelichting(e.target.value)} />
      <Button size="sm" onClick={() => onSave(uren ? parseInt(uren, 10) : null, score ? parseInt(score, 10) : null, toelichting)}>Opslaan</Button>
    </div>
  );
}

/** Afbreken vereist altijd een reden (doel: cascade naar gekoppelde voorzieningen). */
function AfbreekModal({ target, onClose, onSubmit }: {
  target: AfbreekTarget; onClose: () => void; onSubmit: (reden: string) => void;
}) {
  const [reden, setReden] = useState('');
  useEffect(() => { if (target) setReden(''); }, [target]);
  return (
    <Modal open={!!target} danger modalHeading={`${target?.kind === 'doel' ? 'Doel' : 'Instrument'} afbreken`}
      primaryButtonText="Afbreken" secondaryButtonText="Annuleren" primaryButtonDisabled={!reden.trim()}
      onRequestClose={onClose} onRequestSubmit={() => reden.trim() && onSubmit(reden.trim())}>
      <div className="pdca-modal-form">
        <p style={{marginBottom: 12}}>
          Je staat op het punt <strong>{target?.titel}</strong> af te breken.
          {target?.kind === 'doel' && ' Gekoppelde actieve voorzieningen worden ook afgebroken.'}
        </p>
        <TextArea id="afbreek-reden" labelText="Reden (verplicht)" value={reden}
          onChange={(e: any) => setReden(e.target.value)} placeholder="Waarom wordt dit afgebroken?" />
      </div>
    </Modal>
  );
}

/**
 * Registergedreven doelinvoer (beslissingen 17-08-26): het doel wordt gekozen
 * uit het doeltype-register en de titel is de registernaam — geen vrije tekst.
 * Vrije tekst mag alleen als toelichting ten behoeve van de inwoner.
 */
function DoelModal({ open, doel, doeltypen, onClose, onSave }: {
  open: boolean; doel: Doel | null; doeltypen: DoelType[];
  onClose: () => void; onSave: (data: { titel: string; toelichting: string; doeltypeUuid: string }) => void;
}) {
  const [toelichting, setToelichting] = useState('');
  const [doeltypeUuid, setDoeltypeUuid] = useState('');
  useEffect(() => {
    if (open) {
      setToelichting(doel?.beschrijving || '');
      setDoeltypeUuid(doel?.doeltype?.uuid || '');
    }
  }, [open, doel]);

  const opties = subdoelTypen(doeltypen);
  // Doel met een doeltype van vóór het benoemde register: als eigen optie tonen
  // zodat bewerken van de toelichting mogelijk blijft zonder herclassificatie.
  const huidigOnbekend = doel?.doeltype && !opties.some(t => t.uuid === doel.doeltype!.uuid);
  const gekozen = opties.find(t => t.uuid === doeltypeUuid);
  const titel = gekozen ? doelTypeNaam(gekozen) : doel?.titel || '';

  return (
    <Modal open={open} modalHeading={doel ? 'Doel bewerken' : 'Doel toevoegen'}
      primaryButtonText="Opslaan" secondaryButtonText="Annuleren"
      primaryButtonDisabled={!doeltypeUuid || !titel}
      onRequestClose={onClose}
      onRequestSubmit={() => doeltypeUuid && titel && onSave({ titel, toelichting, doeltypeUuid })}>
      <div className="pdca-modal-form">
        <Select id="doel-type" labelText="Doel (uit doeltype-register)" value={doeltypeUuid} onChange={(e: any) => setDoeltypeUuid(e.target.value)}
          helperText="Vast gedefinieerde doelen; de categorie groepeert ze in de PDCA-weergave">
          <SelectItem value="" text="-- Kies doel --" />
          {huidigOnbekend && <SelectItem value={doel!.doeltype!.uuid} text={`${doel!.titel} (huidig)`} />}
          {opties.map(t => (
            <SelectItem key={t.uuid} value={t.uuid} text={`${t.doelType} — ${t.categorieen?.[0]?.naam || 'Overig'}`} />
          ))}
        </Select>
        <TextArea id="doel-toelichting" labelText="Toelichting (t.b.v. de inwoner)" value={toelichting}
          onChange={(e: any) => setToelichting(e.target.value)} />
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
