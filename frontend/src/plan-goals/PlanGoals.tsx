import React, { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import {
  Theme, Button, Tag, Modal, TextInput, TextArea, Select, SelectItem, Checkbox,
  Loading, InlineNotification, ProgressBar,
} from '@carbon/react';
import { Add, Edit, TrashCan, ChevronRight, ChevronDown, Checkmark, Close, StopOutline, Launch } from '@carbon/react/icons';
import { onInit, resizeIframe, navigateHost } from '../shared/bridge';
import {
  openplan, openproduct, pdca, planVoorDossier, urn, deleteDoelCascade, afbreekDoelCascade, afbreekInstrument, productTypeByUrn,
  parseDossierUrn,
  Plan, PlanDetails, Doel, DoelDetails, DoelType, InstrumentType, Instrument, InstrumentDetails, Actie, ProductType,
  ActieBouwblokKoppeling, PhaseConfig,
} from '../shared/api';
import {
  statusLabel, doelStatusLabel, doelStatusTag, doelTypeNaam, subdoelTypen, subdoelTypenVoorHoofddoel, isHoofddoelType,
  doelTypeThemas, producttypenVoorDoel, doelgroepVoorSubject,
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
  const [actieBouwblokken, setActieBouwblokken] = useState<ActieBouwblokKoppeling[]>([]);
  const [startingBouwblok, setStartingBouwblok] = useState<string | null>(null);
  /** DoelUuid whose "Actie kiezen" dropdown is open. */
  const [actieMenuOpen, setActieMenuOpen] = useState<string | null>(null);
  /** DoelUuid whose "Start product" dropdown is open. */
  const [productMenuOpen, setProductMenuOpen] = useState<string | null>(null);
  /** Confirmation after starting a product request; for a DOSSIER product
   *  it carries the created aanvraagdossier for a direct link. */
  const [productStarted, setProductStarted] = useState<
    { message: string; dossier?: { caseDefinitionKey: string; documentId: string } } | null
  >(null);
  const [doeltypen, setDoeltypen] = useState<DoelType[]>([]);
  /** Case configuration; carries the subdoelmapping that scopes the subdoel choice list. */
  const [phaseConfig, setPhaseConfig] = useState<PhaseConfig | null>(null);
  const [instrumenttypen, setInstrumenttypen] = useState<InstrumentType[]>([]);
  const [producttypen, setProducttypen] = useState<ProductType[]>([]);
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const [voortgangOpen, setVoortgangOpen] = useState<Set<string>>(new Set());
  const [goalModal, setGoalModal] = useState(false);
  /** Context of the "Actie toevoegen" modal: the doel and optionally the instrument it supports. */
  const [actionModal, setActionModal] = useState<{ doelUuid: string; instrumentUuid?: string } | null>(null);
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
        // Bouwblok koppelingen of this case type (PDCA Beheer): startable
        // GZAC building blocks under doelen with a matching doeltype.
        try {
          setActieBouwblokken(await pdca.actieBouwblokken.listByCaseDefinition(pDetails.caseDefinitionKey));
        } catch { setActieBouwblokken([]); }
        // Subdoelmapping: which subdoeltypen this hoofddoel allows.
        try {
          setPhaseConfig(await pdca.phaseConfigs.get(pDetails.caseDefinitionKey));
        } catch { setPhaseConfig(null); }
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

  // A doel carries only uuid + name of its doeltype; the register type (with
  // the categorieen that mark hoofddoelen and scope the products) comes from
  // the doeltype list.
  const doeltypeVan = useCallback(
    (d: Doel | undefined) => doeltypen.find(t => t.uuid === d?.doeltype?.uuid),
    [doeltypen]
  );

  // W&P hierarchy: the plan has one active hoofddoel (a real Doel of a
  // hoofddoel type); the subdoelen reference it via doel.hoofdDoel and
  // render as one flat list, ordered by the overlay sortering.
  const isHoofddoelDoel = useCallback((d: Doel) => {
    const type = doeltypeVan(d);
    return type ? isHoofddoelType(type) : false;
  }, [doeltypeVan]);

  const hoofddoelDoelen = useMemo(() => doelen.filter(isHoofddoelDoel), [doelen, isHoofddoelDoel]);
  const actiefHoofddoel = useMemo(
    () => hoofddoelDoelen.find(d => d.status === 'actief') ?? null,
    [hoofddoelDoelen]
  );
  const eerdereHoofddoelen = useMemo(
    () => hoofddoelDoelen.filter(d => d !== actiefHoofddoel),
    [hoofddoelDoelen, actiefHoofddoel]
  );
  // The subdoelmapping is keyed by hoofddoeltype name; fall back to the doel
  // titel for hoofddoelen created before the named doeltype register.
  const actiefHoofddoelNaam = useMemo(
    () => (actiefHoofddoel
      ? doelTypeNaam(doeltypeVan(actiefHoofddoel)) || actiefHoofddoel.titel
      : null),
    [actiefHoofddoel, doeltypeVan]
  );

  const gesorteerdeDoelen = useMemo(
    () => doelen.filter(d => !isHoofddoelDoel(d)).sort((a, b) =>
      (detailsByDoel.get(a.uuid)?.sortering ?? 999) - (detailsByDoel.get(b.uuid)?.sortering ?? 999)),
    [doelen, isHoofddoelDoel, detailsByDoel]
  );

  // Instrument-bound acties (W&P: instrument -> 0..n taken) render in the
  // instrument block; the doel's actie list keeps only the unbound ones.
  const actiesFor = (doelUuid: string) => acties.filter(a => a.doelUuid === doelUuid && !a.instrumentUuid);
  const actiesForInstrument = (instrumentUuid: string) => acties.filter(a => a.instrumentUuid === instrumentUuid);
  const instrumentenFor = (doelUuid: string) => instrumenten.filter(i => i.doelen.some(d => d.uuid === doelUuid));

  /** Koppelingen of one kind available under this doel (doeltype match; empty = always). */
  const koppelingenFor = (doel: Doel, soort: 'ACTIE' | 'PRODUCT') => actieBouwblokken.filter(k => {
    if ((k.soort ?? 'ACTIE') !== soort) return false;
    try {
      const uuids: string[] = JSON.parse(k.doeltypeUuids || '[]');
      return uuids.length === 0 || (doel.doeltype && uuids.includes(doel.doeltype.uuid));
    } catch { return false; }
  });
  const actieBouwblokkenFor = (doel: Doel) => koppelingenFor(doel, 'ACTIE');
  const productBouwblokkenFor = (doel: Doel) => koppelingenFor(doel, 'PRODUCT');

  const handleStartBouwblok = async (koppeling: ActieBouwblokKoppeling, doelUuid: string) => {
    setActieMenuOpen(null);
    setStartingBouwblok(koppeling.id + doelUuid);
    try {
      await pdca.actieBouwblokken.start(koppeling.id, doelUuid);
      await reload();
    } catch (e: any) {
      setError(`Actie "${koppeling.naam}" starten mislukt: ` + e.message);
    } finally {
      setStartingBouwblok(null);
    }
  };

  /**
   * Starts a product request. The app creates nothing in the plan itself:
   * the process manages the instrument (it only appears in the plan once
   * the request is granted) — hence the confirmation message instead of a
   * visible new instrument. A DOSSIER product returns its fresh
   * aanvraagdossier, offered as a direct link.
   */
  const handleStartProduct = async (koppeling: ActieBouwblokKoppeling, doelUuid: string) => {
    setProductMenuOpen(null);
    setStartingBouwblok(koppeling.id + doelUuid);
    try {
      const result = await pdca.actieBouwblokken.start(koppeling.id, doelUuid);
      setProductStarted(result.dossier
        ? {
            message: `Aanvraag "${koppeling.naam}" gestart in een eigen dossier — de beoordelingstaak staat in de takenlijst van dat dossier.`,
            dossier: result.dossier,
          }
        : { message: `Aanvraag "${koppeling.naam}" gestart — de beoordelingstaak staat in de takenlijst van het dossier.` });
      await reload();
    } catch (e: any) {
      setError(`Product "${koppeling.naam}" aanvragen mislukt: ` + e.message);
    } finally {
      setStartingBouwblok(null);
    }
  };

  /** Navigate the GZAC host to a dossier (route /cases/<caseDefKey>/document/<documentId>). */
  const openDossier = (caseDefinitionKey: string, documentId: string) =>
    navigateHost(`/cases/${caseDefinitionKey}/document/${documentId}`);

  // Doelen come from the doeltype register: the title is the register name
  // of the chosen doeltype; free text only as explanation for the inwoner.
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
          // New subdoelen hang under the plan's active hoofddoel (W&P hierarchy).
          ...(actiefHoofddoel ? { hoofdDoel: actiefHoofddoel.uuid } : {}),
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

  // Aborting requires a reason; for a doel, linked active voorzieningen are
  // aborted along with it.
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

  const handleSaveActie = async (ctx: { doelUuid: string; instrumentUuid?: string }, data: any) => {
    await pdca.acties.create({ doelUuid: ctx.doelUuid, instrumentUuid: ctx.instrumentUuid, ...data });
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

  return (
    <Theme theme="g10">
      <div className="pdca-container">
        {error && <InlineNotification kind="error" title="Fout" subtitle={error} lowContrast onCloseButtonClick={() => setError(null)} />}
        {productStarted && (
          <div style={{display: 'flex', alignItems: 'center', gap: 8}}>
            <InlineNotification kind="success" title="Product aangevraagd" subtitle={productStarted.message}
              lowContrast onCloseButtonClick={() => setProductStarted(null)} style={{flex: 1}} />
            {productStarted.dossier && (
              <Button size="sm" kind="tertiary" renderIcon={Launch}
                onClick={() => openDossier(productStarted.dossier!.caseDefinitionKey, productStarted.dossier!.documentId)}>
                Open aanvraagdossier
              </Button>
            )}
          </div>
        )}
        {/* The plan's hoofddoel (strategy) as a fixed card; switching it on the
            overview tab completes the old doel, which stays here as history. */}
        {actiefHoofddoel && (
          <div className="pdca-goal-card" style={{marginBottom: '1rem'}}>
            <div className="pdca-goal-header" style={{cursor: 'default'}}>
              <div style={{display: 'flex', alignItems: 'center', gap: 12, flex: 1, minWidth: 0}}>
                <Tag size="sm" type="high-contrast">Hoofddoel</Tag>
                <span style={{fontWeight: 600}}>{actiefHoofddoel.titel}</span>
                {actiefHoofddoel.beschrijving && (
                  <span style={{fontSize: 12, color: 'var(--cds-text-secondary)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap'}}>
                    — {actiefHoofddoel.beschrijving}
                  </span>
                )}
              </div>
              <Tag size="sm" type={doelStatusTag(actiefHoofddoel.status, actiefHoofddoel.resultaat) as any}>
                {doelStatusLabel(actiefHoofddoel.status, actiefHoofddoel.resultaat)}
              </Tag>
            </div>
          </div>
        )}
        {eerdereHoofddoelen.map(d => (
          <div key={d.uuid} style={{display: 'flex', alignItems: 'center', gap: 8, margin: '0 0 8px 4px', fontSize: 12, color: 'var(--cds-text-secondary)'}}>
            <span>Eerder hoofddoel: {d.titel}</span>
            <Tag size="sm" type={doelStatusTag(d.status, d.resultaat) as any}>{doelStatusLabel(d.status, d.resultaat)}</Tag>
          </div>
        ))}

        <div className="pdca-phase-bar">
          <div style={{flex: 1}} />
          <Button size="sm" renderIcon={Add} onClick={() => { setEditDoel(null); setGoalModal(true); }}>Subdoel toevoegen</Button>
        </div>

        {gesorteerdeDoelen.length === 0 && <div className="pdca-empty"><p>Geen subdoelen gevonden</p></div>}

        {gesorteerdeDoelen.map(doel => {
          const dd = detailsByDoel.get(doel.uuid);
          const da = actiesFor(doel.uuid);
          const di = instrumentenFor(doel.uuid);
          const isOpen = expanded.has(doel.uuid);
          const pct = dd?.voortgangScore || 0;
          const uitvoeringsStatus = dd?.uitvoeringsStatus;
          return (
            <div key={doel.uuid} className={`pdca-goal-card status-${doel.status}`}>
              <div className="pdca-goal-header" onClick={() => toggle(doel.uuid, setExpanded)}>
                <div style={{display: 'flex', alignItems: 'center', gap: 12, flex: 1, minWidth: 0}}>
                  <ChevronRight size={16} style={{transform: isOpen ? 'rotate(90deg)' : 'none', transition: 'transform 0.2s', flexShrink: 0}} />
                  <span style={{fontWeight: 500}}>{doel.titel}</span>
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
                      <div style={{display: 'flex', gap: 4, alignItems: 'center', justifyContent: 'flex-end'}}>
                        <Button size="sm" kind="ghost" renderIcon={Add} onClick={() => setActionModal({ doelUuid: doel.uuid })}>Handmatige actie</Button>
                        {/* Actie building blocks (PDCA Beheer) with a matching doeltype:
                            choosing one creates the action and starts the building block
                            process on the dossier. No matching blocks -> button greyed out. */}
                        {(() => {
                          const available = actieBouwblokkenFor(doel);
                          const open = actieMenuOpen === doel.uuid;
                          const unavailable = available.length === 0 || doel.status !== 'actief';
                          return (
                            <div style={{position: 'relative'}}>
                              <Button size="sm" kind="tertiary" renderIcon={ChevronDown}
                                disabled={unavailable}
                                title={available.length === 0
                                  ? 'Geen bouwblok-acties beschikbaar voor dit doeltype'
                                  : doel.status !== 'actief' ? 'Alleen bij een actief doel' : undefined}
                                onClick={() => setActieMenuOpen(open ? null : doel.uuid)}>
                                Actie kiezen
                              </Button>
                              {open && !unavailable && (
                                <div style={{
                                  position: 'absolute', right: 0, top: 'calc(100% + 4px)', zIndex: 10,
                                  minWidth: 260, background: 'var(--cds-layer, #fff)',
                                  border: '1px solid var(--cds-border-subtle)',
                                  boxShadow: '0 2px 6px rgba(0,0,0,0.2)',
                                }}>
                                  {available.map(k => (
                                    <button key={k.id} type="button"
                                      disabled={startingBouwblok === k.id + doel.uuid}
                                      onClick={() => handleStartBouwblok(k, doel.uuid)}
                                      style={{
                                        display: 'block', width: '100%', textAlign: 'left',
                                        padding: '10px 12px', background: 'none', border: 'none',
                                        borderBottom: '1px solid var(--cds-border-subtle)',
                                        cursor: 'pointer', font: 'inherit',
                                      }}>
                                      <span style={{fontSize: 13, fontWeight: 500}}>
                                        {startingBouwblok === k.id + doel.uuid ? 'Starten…' : k.naam}
                                      </span>
                                      {k.omschrijving && (
                                        <span style={{display: 'block', fontSize: 11, color: 'var(--cds-text-secondary)'}}>{k.omschrijving}</span>
                                      )}
                                    </button>
                                  ))}
                                </div>
                              )}
                            </div>
                          );
                        })()}
                      </div>
                    </div>
                    {da.map(actie => <ActieRow key={actie.id} actie={actie} onStatus={handleActieStatus} />)}
                    {da.length === 0 && <p style={{fontSize: 12, color: 'var(--cds-text-helper)', fontStyle: 'italic'}}>Geen acties</p>}
                  </div>

                  <div className="pdca-section-block">
                    <div className="pdca-section-title">
                      <span>Instrumenten / voorzieningen ({di.length})</span>
                      <div style={{display: 'flex', gap: 4, alignItems: 'center', justifyContent: 'flex-end'}}>
                        <Button size="sm" kind="ghost" renderIcon={Add} onClick={() => setInstrumentModal(doel.uuid)}>Handmatig product</Button>
                        {/* Product building blocks (PDCA Beheer) with a matching doeltype:
                            choosing one starts the request building block on the dossier.
                            The instrument only appears in the plan once the request is
                            granted in the dossier's task list — the block manages it. */}
                        {(() => {
                          const available = productBouwblokkenFor(doel);
                          const open = productMenuOpen === doel.uuid;
                          const unavailable = available.length === 0 || doel.status !== 'actief';
                          return (
                            <div style={{position: 'relative'}}>
                              <Button size="sm" kind="tertiary" renderIcon={ChevronDown}
                                disabled={unavailable}
                                title={available.length === 0
                                  ? 'Geen product-bouwblokken beschikbaar voor dit doeltype'
                                  : doel.status !== 'actief' ? 'Alleen bij een actief doel' : undefined}
                                onClick={() => setProductMenuOpen(open ? null : doel.uuid)}>
                                Start product
                              </Button>
                              {open && !unavailable && (
                                <div style={{
                                  position: 'absolute', right: 0, top: 'calc(100% + 4px)', zIndex: 10,
                                  minWidth: 260, background: 'var(--cds-layer, #fff)',
                                  border: '1px solid var(--cds-border-subtle)',
                                  boxShadow: '0 2px 6px rgba(0,0,0,0.2)',
                                }}>
                                  {available.map(k => (
                                    <button key={k.id} type="button"
                                      disabled={startingBouwblok === k.id + doel.uuid}
                                      onClick={() => handleStartProduct(k, doel.uuid)}
                                      style={{
                                        display: 'block', width: '100%', textAlign: 'left',
                                        padding: '10px 12px', background: 'none', border: 'none',
                                        borderBottom: '1px solid var(--cds-border-subtle)',
                                        cursor: 'pointer', font: 'inherit',
                                      }}>
                                      <span style={{fontSize: 13, fontWeight: 500}}>
                                        {startingBouwblok === k.id + doel.uuid ? 'Starten…' : k.naam}
                                      </span>
                                      {k.omschrijving && (
                                        <span style={{display: 'block', fontSize: 11, color: 'var(--cds-text-secondary)'}}>{k.omschrijving}</span>
                                      )}
                                    </button>
                                  ))}
                                </div>
                              )}
                            </div>
                          );
                        })()}
                      </div>
                    </div>
                    {di.map(inst => {
                      const pt = productTypeByUrn(producttypen, inst.product);
                      const idet = detailsByInstrument.get(inst.uuid);
                      const vOpen = voortgangOpen.has(inst.uuid);
                      // Instrument created by a DOSSIER product: the zaak
                      // register field carries the aanvraagdossier's URN.
                      const dossierRef = parseDossierUrn(inst.zaak);
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
                              {dossierRef && (
                                <Button size="sm" kind="ghost" renderIcon={Launch}
                                  title="Open het aanvraagdossier van dit product"
                                  onClick={() => openDossier(dossierRef.caseDefinitionKey, dossierRef.documentId)}>
                                  Dossier
                                </Button>
                              )}
                              <Button size="sm" kind="ghost" onClick={() => toggle(inst.uuid, setVoortgangOpen)}>Voortgang</Button>
                              {inst.status === 'actief' && (
                                <Button size="sm" kind="ghost" renderIcon={Add}
                                  title="Taak/opdracht bij dit instrument"
                                  onClick={() => setActionModal({ doelUuid: doel.uuid, instrumentUuid: inst.uuid })}>
                                  Actie
                                </Button>
                              )}
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
                          {/* Taken/opdrachten of this instrument (W&P: instrument -> 0..n taken). */}
                          {actiesForInstrument(inst.uuid).length > 0 && (
                            <div style={{margin: '0 0 8px 16px', borderLeft: '2px solid var(--cds-border-subtle)', paddingLeft: 8}}>
                              {actiesForInstrument(inst.uuid).map(actie => (
                                <ActieRow key={actie.id} actie={actie} onStatus={handleActieStatus} />
                              ))}
                            </div>
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

        <DoelModal open={goalModal} doel={editDoel} doeltypen={doeltypen}
          hoofddoelNaam={actiefHoofddoelNaam} subdoelMapping={phaseConfig?.subdoelMapping}
          onClose={() => { setGoalModal(false); setEditDoel(null); }} onSave={handleSaveDoel} />
        <ActieModal open={!!actionModal} ctx={actionModal}
          onClose={() => setActionModal(null)} onSave={handleSaveActie} />
        <InstrumentModal open={!!instrumentModal} doelUuid={instrumentModal} producttypen={producttypen}
          doelType={doeltypeVan(doelen.find(d => d.uuid === instrumentModal))}
          doelgroep={doelgroepVoorSubject(plan?.domeinregister)}
          onClose={() => setInstrumentModal(null)} onSave={handleSaveInstrument} />
        <AfbreekModal target={afbreekTarget} onClose={() => setAfbreekTarget(null)} onSubmit={handleAfbreken} />
      </div>
    </Theme>
  );
}

/**
 * One actie/taak row with the local status flow. With a running building
 * block process the Operaton task is the source of completion; the local
 * status buttons stay hidden. Used under a doel and under an instrument.
 */
function ActieRow({ actie, onStatus }: { actie: Actie; onStatus: (id: string, action: string) => void }) {
  const isBouwblok = actie.uitvoering === 'BOUWBLOK';
  const loopt = isBouwblok && actie.status !== 'COMPLETED' && actie.status !== 'REJECTED';
  return (
    <div className="pdca-action-row">
      <div style={{display: 'flex', alignItems: 'center', gap: 8, flex: 1, minWidth: 0}}>
        <span>{actie.title}</span>
        {actie.assigneeName && <span style={{fontSize: 11, color: 'var(--cds-text-secondary)'}}>— {actie.assigneeName}</span>}
        {actie.dueDate && <span style={{fontSize: 11, color: 'var(--cds-text-secondary)'}}>({formatDate(actie.dueDate)})</span>}
        {actie.result && (actie.status === 'COMPLETED' || actie.status === 'REJECTED') && (
          <span style={{fontSize: 11, color: 'var(--cds-text-secondary)', fontStyle: 'italic', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap'}}>
            — {actie.result}
          </span>
        )}
      </div>
      <div style={{display: 'flex', alignItems: 'center', gap: 6, flexShrink: 0}}>
        {isBouwblok && <Tag size="sm" type="teal" title="Uitgevoerd door een GZAC-bouwblokproces; afronden gaat via de taak in de takenlijst">Bouwblok</Tag>}
        {actie.priority && !isBouwblok && <Tag size="sm" type={actie.priority === 'HIGH' ? 'red' : 'gray'}>{priorityLabel(actie.priority)}</Tag>}
        <Tag size="sm" type={actie.status === 'COMPLETED' ? 'green' : actie.status === 'PENDING_REVIEW' ? 'warm-gray' : actie.status === 'IN_PROGRESS' ? 'blue' : 'gray'}>
          {statusLabel(actie.status)}
        </Tag>
        {!loopt && <>
          {actie.status === 'PLANNED' && !isBouwblok && <Button size="sm" kind="ghost" onClick={() => onStatus(actie.id, 'IN_PROGRESS')}>Start</Button>}
          {actie.status === 'IN_PROGRESS' && !isBouwblok && <Button size="sm" kind="ghost" onClick={() => onStatus(actie.id, 'PENDING_REVIEW')}>Ter beoordeling</Button>}
          {actie.status === 'PENDING_REVIEW' && !isBouwblok && <>
            <Button size="sm" kind="primary" renderIcon={Checkmark} onClick={() => onStatus(actie.id, 'approve')}>Goedkeuren</Button>
            <Button size="sm" kind="danger" renderIcon={Close} onClick={() => onStatus(actie.id, 'reject')}>Afkeuren</Button>
          </>}
        </>}
      </div>
    </div>
  );
}

/** Hours + effectiviteit per instrument (PDCA overlay). */
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

/** Aborting always requires a reason (doel: cascades to linked voorzieningen). */
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
 * Register-driven doel entry: the doel is chosen from
 * the doeltype register and the title is the register name — no free text.
 * Free text is allowed only as explanation for the inwoner.
 */
function DoelModal({ open, doel, doeltypen, hoofddoelNaam, subdoelMapping, onClose, onSave }: {
  open: boolean; doel: Doel | null; doeltypen: DoelType[];
  hoofddoelNaam?: string | null; subdoelMapping?: string | null;
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

  const opties = subdoelTypenVoorHoofddoel(doeltypen, hoofddoelNaam, subdoelMapping);
  const gescoped = opties.length < subdoelTypen(doeltypen).length;
  // Doel with a doeltype predating the named register: show as its own option
  // so the explanation stays editable without reclassification.
  const huidigOnbekend = doel?.doeltype && !opties.some(t => t.uuid === doel.doeltype!.uuid);
  const gekozen = opties.find(t => t.uuid === doeltypeUuid);
  const titel = gekozen ? doelTypeNaam(gekozen) : doel?.titel || '';

  return (
    <Modal open={open} modalHeading={doel ? 'Subdoel bewerken' : 'Subdoel toevoegen'}
      primaryButtonText="Opslaan" secondaryButtonText="Annuleren"
      primaryButtonDisabled={!doeltypeUuid || !titel}
      onRequestClose={onClose}
      onRequestSubmit={() => doeltypeUuid && titel && onSave({ titel, toelichting, doeltypeUuid })}>
      <div className="pdca-modal-form">
        <Select id="doel-type" labelText="Subdoel (uit doeltype-register)" value={doeltypeUuid} onChange={(e: any) => setDoeltypeUuid(e.target.value)}
          helperText={gescoped
            ? `Subdoelen die horen bij hoofddoel "${hoofddoelNaam}"`
            : 'Vast gedefinieerde doelen uit het register'}>
          <SelectItem value="" text="-- Kies subdoel --" />
          {huidigOnbekend && <SelectItem value={doel!.doeltype!.uuid} text={`${doel!.titel} (huidig)`} />}
          {opties.map(t => (
            <SelectItem key={t.uuid} value={t.uuid} text={t.doelType} />
          ))}
        </Select>
        <TextArea id="doel-toelichting" labelText="Toelichting (t.b.v. de inwoner)" value={toelichting}
          onChange={(e: any) => setToelichting(e.target.value)} />
      </div>
    </Modal>
  );
}

function ActieModal({ open, ctx, onClose, onSave }: {
  open: boolean;
  ctx: { doelUuid: string; instrumentUuid?: string } | null;
  onClose: () => void;
  onSave: (ctx: { doelUuid: string; instrumentUuid?: string }, data: any) => void;
}) {
  const [title, setTitle] = useState('');
  const [desc, setDesc] = useState('');
  const [assigneeType, setAssigneeType] = useState('PROFESSIONAL');
  const [assigneeName, setAssigneeName] = useState('');
  const [priority, setPriority] = useState('NORMAL');
  const [dueDate, setDueDate] = useState('');
  useEffect(() => { if (open) { setTitle(''); setDesc(''); setAssigneeName(''); setDueDate(''); } }, [open]);
  return (
    <Modal open={open} modalHeading={ctx?.instrumentUuid ? 'Actie bij instrument toevoegen' : 'Actie toevoegen'}
      primaryButtonText="Opslaan" secondaryButtonText="Annuleren"
      onRequestClose={onClose} onRequestSubmit={() => ctx && onSave(ctx, { title, description: desc, assigneeType, assigneeName, priority, dueDate: dueDate || undefined })}>
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

/**
 * Manual product: an instrument registered straight on the doel, without a
 * request process. The catalog is scoped to the subdoel — producttypen that
 * carry a thema of the doeltype, within the doelgroep of the plan subject —
 * with "Toon alle producten" as escape hatch for a product that is not (yet)
 * thematised.
 */
function InstrumentModal({ open, doelUuid, producttypen, doelType, doelgroep, onClose, onSave }: {
  open: boolean; doelUuid: string | null; producttypen: ProductType[];
  doelType?: DoelType; doelgroep?: string; onClose: () => void;
  onSave: (doelUuid: string, data: { titel: string; producttype?: ProductType }) => void;
}) {
  const [titel, setTitel] = useState('');
  const [productUuid, setProductUuid] = useState('');
  const [alleProducten, setAlleProducten] = useState(false);
  useEffect(() => { if (open) { setTitel(''); setProductUuid(''); setAlleProducten(false); } }, [open]);
  const themas = doelTypeThemas(doelType);
  const passend = useMemo(
    () => producttypenVoorDoel(producttypen, doelType, doelgroep),
    [producttypen, doelType, doelgroep]
  );
  const opties = alleProducten ? producttypen : passend;
  const selected = producttypen.find(p => p.uuid === productUuid);
  const onProductSelect = (uuid: string) => {
    setProductUuid(uuid);
    const pt = producttypen.find(p => p.uuid === uuid);
    if (pt) setTitel(pt.naam);
  };
  // A product picked from the full catalog must not survive narrowing back down.
  const toggleAlleProducten = (checked: boolean) => {
    setAlleProducten(checked);
    if (!checked && productUuid && !passend.some(p => p.uuid === productUuid)) setProductUuid('');
  };
  return (
    <Modal open={open} modalHeading="Instrument toevoegen" primaryButtonText="Opslaan" secondaryButtonText="Annuleren"
      onRequestClose={onClose} onRequestSubmit={() => doelUuid && onSave(doelUuid, { titel, producttype: selected })}>
      <div className="pdca-modal-form">
        <Select id="inst-product" labelText="Producttype uit Open Product" value={productUuid} onChange={(e: any) => onProductSelect(e.target.value)}
          helperText={alleProducten
            ? 'Volledige catalogus van Open Product'
            : themas.length > 0
              ? `Producten bij dit subdoel (${themas.join(', ')})`
              : 'Dit subdoel heeft geen thema; de volledige catalogus wordt getoond'}>
          <SelectItem value="" text="-- Selecteer producttype (optioneel) --" />
          {opties.map(p => (
            <SelectItem key={p.uuid} value={p.uuid} text={`${p.naam}${p.organisaties[0] ? ` (${p.organisaties[0].naam})` : ''}`} />
          ))}
        </Select>
        {(alleProducten || passend.length < producttypen.length) && (
          <Checkbox id="inst-alle-producten" labelText="Toon alle producten"
            checked={alleProducten}
            onChange={(_: unknown, { checked }: { checked: boolean }) => toggleAlleProducten(checked)} />
        )}
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
