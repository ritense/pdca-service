import React, { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import {
  Theme, Layer, Button, Tag, Modal, TextInput, TextArea, Select, SelectItem, Checkbox,
  Loading, InlineNotification, OverflowMenu, OverflowMenuItem,
} from '@carbon/react';
import { Add, ChevronRight, ChevronDown, Checkmark, Close, Launch } from '@carbon/react/icons';
import { onInit, resizeIframe, navigateHost } from '../shared/bridge';
import {
  EvaluationSession, EvaluationChangeInput, syncEvaluationPanel, recordEvaluationChange, onEvaluationEvent,
} from '../shared/evaluationSession';
import { EvaluatieBanner } from '../shared/EvaluatieBanner';
import { useSubmit } from '../shared/useSubmit';
import {
  openplan, openproduct, pdca, planVoorDossier, urn, deleteDoelCascade, afbreekDoelCascade, afbreekInstrument, productTypeByUrn,
  parseDossierUrn,
  Plan, PlanDetails, Doel, DoelDetails, DoelType, InstrumentType, Instrument, InstrumentDetails, Actie, ProductType,
  ActieBouwblokKoppeling, PhaseConfig, VoortgangStatusOptie,
} from '../shared/api';
import {
  aantal, doelStatus, instrumentStatus, actieStatus, doelTypeNaam, subdoelTypen, subdoelTypenVoorHoofddoel, isHoofddoelType,
  doelTypeThemas, producttypenVoorDoel, doelgroepVoorSubject,
  priorityLabel, formatDate, voortgangStatus,
} from '../shared/labels';

type AfbreekTarget = { kind: 'doel' | 'instrument'; uuid: string; titel: string } | null;
/** Subdoel awaiting confirmation of its removal, with what goes with it. */
type VerwijderTarget = { uuid: string; titel: string; instrumenten: number; acties: number } | null;
type Notities = { interneNotitie: string; externeNotitie: string };
type VoortgangInvoer = { status: string; toelichting: string };
type RegistratieInvoer = { uren: number | null; score: number | null; toelichting: string };

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
  /** DoelUuid whose "Instrument aanvragen" dropdown is open. */
  const [productMenuOpen, setProductMenuOpen] = useState<string | null>(null);
  /** Confirmation after starting a product request; for a DOSSIER product
   *  it carries the created aanvraagdossier for a direct link. */
  const [productStarted, setProductStarted] = useState<
    { message: string; dossier?: { caseDefinitionKey: string; documentId: string } } | null
  >(null);
  const [doeltypen, setDoeltypen] = useState<DoelType[]>([]);
  /** Case configuration; carries the subdoelmapping that scopes the subdoel choice list. */
  const [phaseConfig, setPhaseConfig] = useState<PhaseConfig | null>(null);
  /** The voortgangsstatussen of this plan's case type (PDCA Beheer), for subdoelen and the hoofddoel. */
  const [voortgangOpties, setVoortgangOpties] = useState<VoortgangStatusOptie[]>([]);
  const [instrumenttypen, setInstrumenttypen] = useState<InstrumentType[]>([]);
  const [producttypen, setProducttypen] = useState<ProductType[]>([]);
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  /** Instruments whose "Voortgang & notities" panel is open. */
  const [voortgangOpen, setVoortgangOpen] = useState<Set<string>>(new Set());
  /** Doelen and instruments whose voortgang and notes are being edited. */
  const [bewerken, setBewerken] = useState<Set<string>>(new Set());
  const [goalModal, setGoalModal] = useState(false);
  /** Context of the "Actie toevoegen" modal: the doel and optionally the instrument it supports. */
  const [actionModal, setActionModal] = useState<{ doelUuid: string; instrumentUuid?: string } | null>(null);
  const [instrumentModal, setInstrumentModal] = useState<string | null>(null);
  const [afbreekTarget, setAfbreekTarget] = useState<AfbreekTarget>(null);
  const [verwijderTarget, setVerwijderTarget] = useState<VerwijderTarget>(null);
  const [editDoel, setEditDoel] = useState<Doel | null>(null);
  const [hoofddoelOpen, setHoofddoelOpen] = useState(false);
  /** Key of the state action (starten, afronden, actiestatus) under way; one at a time. */
  const [busy, setBusy] = useState<string | null>(null);
  /** The user's running evaluation; while it is on this plan, every change here is recorded in it. */
  const [session, setSession] = useState<EvaluationSession | null>(null);
  const docRef = useRef<string | null>(null);

  useEffect(() => {
    onInit(ctx => {
      docRef.current = ctx.documentId || null;
      loadData();
      syncEvaluationPanel(docRef.current).then(setSession);
    });
  }, []);

  useEffect(() => onEvaluationEvent(event => {
    if (event.type === 'started') setSession(event.session);
    if (event.type === 'ended') setSession(null);
  }), []);

  useEffect(() => { resizeIframe(); }, [loading, doelen, expanded, voortgangOpen, bewerken, hoofddoelOpen, session, error]);

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

      const [g, gd, a, idet, dt, it, pt, vo] = await Promise.all([
        openplan.doelen.listByPlan(p.uuid),
        pdca.doeldetails.listByPlan(p.uuid).catch(() => [] as DoelDetails[]),
        pdca.acties.listByPlan(p.uuid).catch(() => [] as Actie[]),
        pdca.instrumentdetails.listByPlan(p.uuid).catch(() => [] as InstrumentDetails[]),
        openplan.doeltypen.list().catch(() => [] as DoelType[]),
        openplan.instrumenttypen.list().catch(() => [] as InstrumentType[]),
        openproduct.producttypen.list().catch(() => [] as ProductType[]),
        pdca.voortgangStatussen(p.uuid).catch(() => [] as VoortgangStatusOptie[]),
      ]);
      const i = await openplan.instrumenten.listByDoelen(g.map(d => d.uuid)).catch(() => [] as Instrument[]);
      setDoelen(g); setDoelDetails(gd); setActies(a); setInstrumenten(i); setInstrumentDetails(idet);
      setDoeltypen(dt); setInstrumenttypen(it); setProducttypen(pt); setVoortgangOpties(vo);

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

  /** Records a plan change in the running evaluation of this plan (no-op without one). */
  const rec = (change: EvaluationChangeInput) => { if (plan) recordEvaluationChange(plan.uuid, change); };
  const evaluatieLoopt = !!plan && session?.planUuid === plan.uuid;

  const detailsByDoel = useMemo(() => new Map(doelDetails.map(d => [d.doelUuid, d])), [doelDetails]);
  const detailsByInstrument = useMemo(() => new Map(instrumentDetails.map(d => [d.instrumentUuid, d])), [instrumentDetails]);

  const toggle = (id: string, set: React.Dispatch<React.SetStateAction<Set<string>>>) => set(prev => {
    const next = new Set(prev);
    next.has(id) ? next.delete(id) : next.add(id);
    return next;
  });
  const setBewerkt = (id: string, open: boolean) => setBewerken(prev => {
    const next = new Set(prev);
    open ? next.add(id) : next.delete(id);
    return next;
  });

  const doelTitel = (uuid: string) => doelen.find(d => d.uuid === uuid)?.titel ?? null;

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
  const alleActiesFor = (doelUuid: string) => acties.filter(a => a.doelUuid === doelUuid);
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
      rec({ subjectType: 'SUBDOEL', subjectUuid: doelUuid, subjectTitel: doelTitel(doelUuid), soort: 'BOUWBLOK_GESTART', naarWaarde: koppeling.naam });
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
      rec({ subjectType: 'SUBDOEL', subjectUuid: doelUuid, subjectTitel: doelTitel(doelUuid), soort: 'PRODUCT_AANGEVRAAGD', naarWaarde: koppeling.naam });
      setProductStarted(result.dossier
        ? {
            message: `Aanvraag "${koppeling.naam}" gestart in een eigen dossier — de beoordelingstaak staat in de takenlijst van dat dossier.`,
            dossier: result.dossier,
          }
        : { message: `Aanvraag "${koppeling.naam}" gestart — de beoordelingstaak staat in de takenlijst van het dossier.` });
      await reload();
    } catch (e: any) {
      setError(`Instrument "${koppeling.naam}" aanvragen mislukt: ` + e.message);
    } finally {
      setStartingBouwblok(null);
    }
  };

  /** Navigate the GZAC host to a dossier (route /cases/<caseDefKey>/document/<documentId>). */
  const openDossier = (caseDefinitionKey: string, documentId: string) =>
    navigateHost(`/cases/${caseDefinitionKey}/document/${documentId}`);

  /** Runs a state action from a button, one at a time; a failure shows as the page error. */
  const run = async (key: string, foutmelding: string, action: () => Promise<void>) => {
    if (busy) return;
    setBusy(key);
    try {
      await action();
    } catch (e: any) {
      setError(`${foutmelding}: ${e.message}`);
    } finally {
      setBusy(null);
    }
  };

  // Doelen come from the doeltype register: the title is the register name
  // of the chosen doeltype; free text only as explanation for the inwoner.
  // Modal saves throw, so the modal keeps the error and stays open.
  const handleSaveDoel = async (data: { titel: string; toelichting: string; doeltypeUuid: string }) => {
    if (editDoel) {
      await openplan.doelen.update(editDoel.uuid, {
        titel: data.titel,
        beschrijving: data.toelichting,
        doeltypeUuid: data.doeltypeUuid,
      });
      const titelGewijzigd = editDoel.titel !== data.titel;
      if (titelGewijzigd || (editDoel.beschrijving || '') !== data.toelichting) {
        rec({
          subjectType: 'SUBDOEL', subjectUuid: editDoel.uuid, subjectTitel: data.titel, soort: 'SUBDOEL_BEWERKT',
          vanWaarde: titelGewijzigd ? editDoel.titel : null,
          naarWaarde: titelGewijzigd ? data.titel : 'toelichting aangepast',
        });
      }
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
      rec({ subjectType: 'SUBDOEL', subjectUuid: doel.uuid, subjectTitel: doel.titel, soort: 'SUBDOEL_TOEGEVOEGD' });
    }
    setGoalModal(false); setEditDoel(null); await reload();
  };

  const askDeleteDoel = (doel: Doel) => setVerwijderTarget({
    uuid: doel.uuid,
    titel: doel.titel,
    // deleteDoelCascade removes only the instruments that belong to this doel alone.
    instrumenten: instrumentenFor(doel.uuid).filter(i => i.doelen.every(d => d.uuid === doel.uuid)).length,
    acties: alleActiesFor(doel.uuid).length,
  });

  const handleDeleteDoel = async () => {
    if (!verwijderTarget) return;
    await deleteDoelCascade(verwijderTarget.uuid);
    rec({ subjectType: 'SUBDOEL', subjectUuid: verwijderTarget.uuid, subjectTitel: verwijderTarget.titel, soort: 'SUBDOEL_VERWIJDERD' });
    setVerwijderTarget(null);
    await reload();
  };

  /** Writes subdoel status GESTART; the caller reloads. */
  const startSubdoel = async (doel: Doel) => {
    if (!plan) return;
    await pdca.doeldetails.upsert(doel.uuid, { planUuid: plan.uuid, uitvoeringsStatus: 'GESTART' });
    rec({ subjectType: 'SUBDOEL', subjectUuid: doel.uuid, subjectTitel: doel.titel, soort: 'SUBDOEL_GESTART' });
  };

  /** Work on an instrument means its subdoel is under way: a Gepland subdoel starts along with it. */
  const startSubdoelAlsGepland = async (doel: Doel) => {
    if (doel.status === 'actief' && detailsByDoel.get(doel.uuid)?.uitvoeringsStatus !== 'GESTART') await startSubdoel(doel);
  };

  const handleStartDoel = (doel: Doel) => run(`doel-${doel.uuid}`, 'Subdoel starten mislukt', async () => {
    await startSubdoel(doel);
    await reload();
  });

  const handleAfrondenDoel = (doel: Doel) => run(`doel-${doel.uuid}`, 'Subdoel afronden mislukt', async () => {
    await openplan.doelen.update(doel.uuid, { status: 'afgerond', resultaat: 'behaald', einddatum: new Date().toISOString() });
    rec({ subjectType: 'SUBDOEL', subjectUuid: doel.uuid, subjectTitel: doel.titel, soort: 'SUBDOEL_AFGEROND' });
    await reload();
  });

  // Aborting requires a reason; for a doel, linked active instruments are
  // aborted along with it.
  const handleAfbreken = async (reden: string) => {
    if (!afbreekTarget || !plan) return;
    if (afbreekTarget.kind === 'doel') {
      await afbreekDoelCascade(afbreekTarget.uuid, plan.uuid, reden);
      rec({ subjectType: 'SUBDOEL', subjectUuid: afbreekTarget.uuid, subjectTitel: afbreekTarget.titel, soort: 'SUBDOEL_AFGEBROKEN', naarWaarde: reden });
    } else {
      await afbreekInstrument(afbreekTarget.uuid, plan.uuid, reden);
      rec({ subjectType: 'INSTRUMENT', subjectUuid: afbreekTarget.uuid, subjectTitel: afbreekTarget.titel, soort: 'INSTRUMENT_AFGEBROKEN', naarWaarde: reden });
    }
    setAfbreekTarget(null);
    await reload();
  };

  const handleSaveActie = async (ctx: { doelUuid: string; instrumentUuid?: string }, data: Partial<Actie> & { title: string }) => {
    const actie = await pdca.acties.create({ doelUuid: ctx.doelUuid, instrumentUuid: ctx.instrumentUuid, ...data });
    rec({ subjectType: 'ACTIE', subjectUuid: actie.id, subjectTitel: actie.title, soort: 'ACTIE_TOEGEVOEGD' });
    setActionModal(null); await reload();
  };

  const handleActieStatus = (id: string, action: string) => run(`actie-${id}`, 'Actiestatus wijzigen mislukt', async () => {
    const voor = acties.find(a => a.id === id);
    const actie = action === 'approve' ? await pdca.acties.approve(id)
      : action === 'reject' ? await pdca.acties.reject(id)
      : await pdca.acties.update(id, { status: action });
    rec({
      subjectType: 'ACTIE', subjectUuid: id, subjectTitel: actie.title, soort: 'ACTIE_STATUS', samenvoegen: true,
      vanWaarde: voor ? actieStatus(voor.status).label : null, naarWaarde: actieStatus(actie.status).label,
    });
    await reload();
  });

  // A manually added instrument starts as Gepland; "Starten" moves it on.
  const handleSaveInstrument = async (doelUuid: string, data: { titel: string; producttype?: ProductType }) => {
    if (!plan) return;
    const instrument = await openplan.instrumenten.create({
      titel: data.titel,
      startdatum: new Date().toISOString(),
      doelenUuids: [doelUuid],
      ontwikkelwensenUuids: [],
      instrumenttypeUuid: instrumenttypen[0]?.uuid,
      ...(data.producttype ? { product: urn('openproduct', 'producttype', data.producttype.code) } : {}),
    });
    await pdca.instrumentdetails.upsert(instrument.uuid, { planUuid: plan.uuid, uitvoeringsStatus: 'GEPLAND' });
    rec({ subjectType: 'INSTRUMENT', subjectUuid: instrument.uuid, subjectTitel: instrument.titel, soort: 'INSTRUMENT_TOEGEVOEGD' });
    setInstrumentModal(null); await reload();
  };

  const handleInstrumentStarten = (inst: Instrument, doel: Doel) => run(`instrument-${inst.uuid}`, 'Instrument starten mislukt', async () => {
    if (!plan) return;
    await pdca.instrumentdetails.upsert(inst.uuid, { planUuid: plan.uuid, uitvoeringsStatus: 'GESTART' });
    rec({ subjectType: 'INSTRUMENT', subjectUuid: inst.uuid, subjectTitel: inst.titel, soort: 'INSTRUMENT_GESTART' });
    await startSubdoelAlsGepland(doel);
    await reload();
  });

  const handleInstrumentAfronden = (inst: Instrument, doel: Doel) => run(`instrument-${inst.uuid}`, 'Instrument afronden mislukt', async () => {
    await openplan.instrumenten.update(inst.uuid, { status: 'afgerond', resultaat: 'behaald', einddatum: new Date().toISOString() });
    rec({ subjectType: 'INSTRUMENT', subjectUuid: inst.uuid, subjectTitel: inst.titel, soort: 'INSTRUMENT_AFGEROND' });
    await startSubdoelAlsGepland(doel);
    await reload();
  });

  // Voortgang and notes are edited together in the card; each changed part
  // is written and recorded on its own. The explanation entered with a
  // voortgang becomes the reason of the change recorded in the evaluation
  // (only when it was changed itself).
  const saveVoortgangStatus = async (kind: 'SUBDOEL' | 'HOOFDDOEL', doel: Doel, invoer: VoortgangInvoer) => {
    if (!plan) return;
    const voor = detailsByDoel.get(doel.uuid);
    await pdca.doeldetails.upsert(doel.uuid, {
      planUuid: plan.uuid, voortgangStatus: invoer.status, voortgangToelichting: invoer.toelichting,
    });
    rec({
      subjectType: kind, subjectUuid: doel.uuid, subjectTitel: doel.titel, soort: 'VOORTGANG', samenvoegen: true,
      vanWaarde: voor?.voortgangStatus ? voortgangStatus(voor.voortgangStatus, voortgangOpties).label : null,
      naarWaarde: voortgangStatus(invoer.status, voortgangOpties).label,
      toelichting: invoer.toelichting !== (voor?.voortgangToelichting ?? '') ? invoer.toelichting : undefined,
    });
  };

  const saveRegistratie = async (inst: Instrument, invoer: RegistratieInvoer) => {
    if (!plan) return;
    const voor = detailsByInstrument.get(inst.uuid);
    const na = await pdca.instrumentdetails.upsert(inst.uuid, {
      planUuid: plan.uuid,
      urenBesteed: invoer.uren ?? undefined,
      effectiviteitScore: invoer.score ?? undefined,
      effectiviteitToelichting: invoer.toelichting,
    });
    rec({
      subjectType: 'INSTRUMENT', subjectUuid: inst.uuid, subjectTitel: inst.titel,
      soort: 'INSTRUMENT_REGISTRATIE', samenvoegen: true,
      vanWaarde: registratieSamenvatting(voor), naarWaarde: registratieSamenvatting(na),
      toelichting: invoer.toelichting !== (voor?.effectiviteitToelichting ?? '') ? invoer.toelichting : undefined,
    });
  };

  /** Internal and external note of a doel (subdoel or hoofddoel) or an instrument. */
  const saveNotities = async (
    target: { kind: 'SUBDOEL' | 'HOOFDDOEL' | 'INSTRUMENT'; uuid: string; titel: string },
    voor: Partial<Notities> | undefined,
    notities: Notities,
  ) => {
    if (!plan) return;
    if (target.kind === 'INSTRUMENT') {
      await pdca.instrumentdetails.upsert(target.uuid, { planUuid: plan.uuid, ...notities });
    } else {
      await pdca.doeldetails.upsert(target.uuid, { planUuid: plan.uuid, ...notities });
    }
    const notitieWijziging = (soort: string, van: string | undefined, naar: string) => {
      if ((van || '') !== naar) {
        rec({ subjectType: target.kind, subjectUuid: target.uuid, subjectTitel: target.titel, soort, samenvoegen: true, vanWaarde: van, naarWaarde: naar });
      }
    };
    notitieWijziging('INTERNE_NOTITIE', voor?.interneNotitie, notities.interneNotitie);
    notitieWijziging('EXTERNE_NOTITIE', voor?.externeNotitie, notities.externeNotitie);
  };

  /** Saves the edited voortgang and notes of a subdoel or the hoofddoel, then closes the editor. */
  const saveDoelBijwerken = (kind: 'SUBDOEL' | 'HOOFDDOEL', doel: Doel) =>
    async (voortgang: VoortgangInvoer | null, notities: Notities | null) => {
      if (voortgang) await saveVoortgangStatus(kind, doel, voortgang);
      if (notities) await saveNotities({ kind, uuid: doel.uuid, titel: doel.titel }, detailsByDoel.get(doel.uuid), notities);
      await reload();
      setBewerkt(doel.uuid, false);
    };

  const saveInstrumentBijwerken = (inst: Instrument) =>
    async (registratie: RegistratieInvoer | null, notities: Notities | null) => {
      if (registratie) await saveRegistratie(inst, registratie);
      if (notities) await saveNotities({ kind: 'INSTRUMENT', uuid: inst.uuid, titel: inst.titel }, detailsByInstrument.get(inst.uuid), notities);
      await reload();
      setBewerkt(inst.uuid, false);
    };

  if (loading) return <Theme theme="g10"><div className="pdca-container"><Loading withOverlay={false} /></div></Theme>;
  if (error && !plan) return <Theme theme="g10"><div className="pdca-container"><InlineNotification kind="error" title={error} /></div></Theme>;
  if (!plan) return null;

  return (
    <Theme theme="g10">
      <div className="pdca-container">
        {error && <InlineNotification className="pdca-notification" kind="error" title="Fout" subtitle={error} lowContrast onCloseButtonClick={() => setError(null)} />}
        {evaluatieLoopt && <EvaluatieBanner />}
        {productStarted && (
          <div className="pdca-row pdca-notification-row">
            <InlineNotification className="pdca-notification" kind="success" title="Instrument aangevraagd" subtitle={productStarted.message}
              lowContrast onCloseButtonClick={() => setProductStarted(null)} style={{flex: '1 1 16rem'}} />
            {productStarted.dossier && (
              <Button size="sm" kind="tertiary" renderIcon={Launch}
                onClick={() => openDossier(productStarted.dossier!.caseDefinitionKey, productStarted.dossier!.documentId)}>
                Open aanvraagdossier
              </Button>
            )}
          </div>
        )}
        {/* The plan's hoofddoel (strategy) as a fixed card; switching it on the
            overview tab completes the old doel, which stays here as history.
            Cards are a layer of their own, so their fields stand out (Carbon Layer). */}
        {actiefHoofddoel && (() => {
          const hd = detailsByDoel.get(actiefHoofddoel.uuid);
          const status = doelStatus(actiefHoofddoel, hd, true);
          return (
            <Layer className="pdca-goal-card" style={{marginBottom: '1rem'}}>
              <div className="pdca-goal-header" onClick={() => setHoofddoelOpen(open => !open)}>
                <div className="pdca-row-main">
                  <ChevronRight size={16} style={{transform: hoofddoelOpen ? 'rotate(90deg)' : 'none', transition: 'transform 0.2s', flexShrink: 0}} />
                  <Tag size="sm" type="high-contrast">Hoofddoel</Tag>
                  <span className="pdca-text-wrap" style={{fontWeight: 600}}>{actiefHoofddoel.titel}</span>
                  {actiefHoofddoel.beschrijving && (
                    <span className="pdca-text-ellipsis" style={{fontSize: 12, color: 'var(--cds-text-secondary)'}}>
                      — {actiefHoofddoel.beschrijving}
                    </span>
                  )}
                </div>
                <div className="pdca-row-actions">
                  {hd?.voortgangStatus && <VoortgangTag code={hd.voortgangStatus} opties={voortgangOpties} />}
                  <Tag size="sm" type={status.tag}>{status.label}</Tag>
                </div>
              </div>
              {hoofddoelOpen && (
                <div className="pdca-goal-body">
                  <VoortgangNotitiesSectie
                    bewerken={bewerken.has(actiefHoofddoel.uuid)}
                    onBewerken={() => setBewerkt(actiefHoofddoel.uuid, true)}
                    samenvatting={<DoelSamenvatting details={hd} metVoortgang opties={voortgangOpties} />}
                    formulier={
                      <DoelBijwerken id={actiefHoofddoel.uuid} details={hd} metVoortgang opties={voortgangOpties}
                        onSave={saveDoelBijwerken('HOOFDDOEL', actiefHoofddoel)}
                        onCancel={() => setBewerkt(actiefHoofddoel.uuid, false)} />
                    } />
                </div>
              )}
            </Layer>
          );
        })()}
        {eerdereHoofddoelen.map(d => {
          const status = doelStatus(d, detailsByDoel.get(d.uuid), true);
          return (
            <div key={d.uuid} className="pdca-tags" style={{alignItems: 'center', margin: '0 0 8px 4px', fontSize: 12, color: 'var(--cds-text-secondary)'}}>
              <span className="pdca-text-wrap">Eerder hoofddoel: {d.titel}</span>
              <Tag size="sm" type={status.tag}>{status.label}</Tag>
            </div>
          );
        })}

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
          const status = doelStatus(doel, dd);
          const actief = doel.status === 'actief';
          const gestart = dd?.uitvoeringsStatus === 'GESTART';
          const aantalActies = alleActiesFor(doel.uuid).length;
          const actieKoppelingen = actieBouwblokkenFor(doel);
          const productKoppelingen = productBouwblokkenFor(doel);
          return (
            <Layer key={doel.uuid} className={`pdca-goal-card status-${doel.status}`}>
              <div className="pdca-goal-header" onClick={() => toggle(doel.uuid, setExpanded)}>
                <div className="pdca-row-main" style={{flexWrap: 'nowrap'}}>
                  <ChevronRight size={16} style={{transform: isOpen ? 'rotate(90deg)' : 'none', transition: 'transform 0.2s', flexShrink: 0}} />
                  <span className="pdca-text-wrap" style={{fontWeight: 500}}>{doel.titel}</span>
                </div>
                <div className="pdca-row-actions">
                  {(aantalActies > 0 || di.length > 0) && (
                    <span className="pdca-muted-count">
                      {aantal(aantalActies, 'actie', 'acties')} · {aantal(di.length, 'instrument', 'instrumenten')}
                    </span>
                  )}
                  {actief && dd?.voortgangStatus && <VoortgangTag code={dd.voortgangStatus} opties={voortgangOpties} />}
                  <Tag size="sm" type={status.tag}>{status.label}</Tag>
                </div>
              </div>
              {isOpen && (
                <div className="pdca-goal-body">
                  {doel.beschrijving && <p style={{color: 'var(--cds-text-secondary)', fontSize: 13, marginBottom: 16}}>{doel.beschrijving}</p>}
                  {doel.status === 'geannuleerd' && doel.toelichtingResultaat && (
                    <InlineNotification kind="warning" title="Afgebroken" subtitle={doel.toelichtingResultaat} lowContrast hideCloseButton style={{marginBottom: 16}} />
                  )}

                  <div className="pdca-goal-actions">
                    {actief && !gestart && (
                      <Button size="sm" kind="primary" disabled={!!busy} onClick={() => handleStartDoel(doel)}>Starten</Button>
                    )}
                    {actief && gestart && (
                      <Button size="sm" kind="primary" renderIcon={Checkmark} disabled={!!busy}
                        onClick={() => handleAfrondenDoel(doel)}>Afronden</Button>
                    )}
                    <OverflowMenu size="sm" aria-label={`Meer acties voor ${doel.titel}`} iconDescription="Meer acties">
                      <OverflowMenuItem itemText="Bewerken" onClick={() => { setEditDoel(doel); setGoalModal(true); }} />
                      {actief && (
                        <OverflowMenuItem itemText="Afbreken" isDelete hasDivider
                          onClick={() => setAfbreekTarget({ kind: 'doel', uuid: doel.uuid, titel: doel.titel })} />
                      )}
                      <OverflowMenuItem itemText="Verwijderen" isDelete hasDivider={!actief} onClick={() => askDeleteDoel(doel)} />
                    </OverflowMenu>
                  </div>

                  <div className="pdca-section-block">
                    <div className="pdca-section-title">
                      <span>Acties ({da.length})</span>
                      <div className="pdca-row-actions">
                        <Button size="sm" kind="ghost" renderIcon={Add} onClick={() => setActionModal({ doelUuid: doel.uuid })}>Handmatige actie</Button>
                        {/* Actie building blocks (PDCA Beheer) with a matching doeltype:
                            choosing one creates the action and starts the building block
                            process on the dossier. Without matching blocks there is no picker. */}
                        {actieKoppelingen.length > 0 && (
                          <KoppelingMenu label="Actie kiezen" koppelingen={actieKoppelingen} disabled={!actief}
                            open={actieMenuOpen === doel.uuid}
                            onToggle={() => setActieMenuOpen(actieMenuOpen === doel.uuid ? null : doel.uuid)}
                            startingKey={startingBouwblok} doelUuid={doel.uuid}
                            onKies={k => handleStartBouwblok(k, doel.uuid)} />
                        )}
                      </div>
                    </div>
                    {da.map(actie => <ActieRow key={actie.id} actie={actie} busy={!!busy} onStatus={handleActieStatus} />)}
                    {da.length === 0 && <p className="pdca-empty-inline">Geen acties</p>}
                  </div>

                  <div className="pdca-section-block">
                    <div className="pdca-section-title">
                      <span>Instrumenten ({di.length})</span>
                      <div className="pdca-row-actions">
                        <Button size="sm" kind="ghost" renderIcon={Add} onClick={() => setInstrumentModal(doel.uuid)}>Instrument toevoegen</Button>
                        {/* Product building blocks (PDCA Beheer) with a matching doeltype:
                            choosing one starts the request building block on the dossier.
                            The instrument only appears in the plan once the request is
                            granted in the dossier's task list — the block manages it. */}
                        {productKoppelingen.length > 0 && (
                          <KoppelingMenu label="Instrument aanvragen" koppelingen={productKoppelingen} disabled={!actief}
                            open={productMenuOpen === doel.uuid}
                            onToggle={() => setProductMenuOpen(productMenuOpen === doel.uuid ? null : doel.uuid)}
                            startingKey={startingBouwblok} doelUuid={doel.uuid}
                            onKies={k => handleStartProduct(k, doel.uuid)} />
                        )}
                      </div>
                    </div>
                    {di.map(inst => {
                      const pt = productTypeByUrn(producttypen, inst.product);
                      const idet = detailsByInstrument.get(inst.uuid);
                      const instStatus = instrumentStatus(inst, idet);
                      const instActief = inst.status === 'actief';
                      const instGepland = instActief && idet?.uitvoeringsStatus === 'GEPLAND';
                      const instActies = actiesForInstrument(inst.uuid);
                      // Instrument created by a DOSSIER product: the zaak
                      // register field carries the aanvraagdossier's URN.
                      const dossierRef = parseDossierUrn(inst.zaak);
                      return (
                        <div key={inst.uuid} style={{borderBottom: '1px solid var(--cds-border-subtle)'}}>
                          <div className="pdca-action-row" style={{borderBottom: 'none'}}>
                            <div className="pdca-row-main">
                              <span className="pdca-text-wrap">{inst.titel}</span>
                              {pt && <span style={{fontSize: 11, color: 'var(--cds-text-secondary)'}}>— {pt.organisaties[0]?.naam || pt.code}{pt.themas[0] ? ` · ${pt.themas[0].naam}` : ''}</span>}
                              {idet?.urenBesteed != null && <Tag size="sm" type="cool-gray">{idet.urenBesteed} uur</Tag>}
                              {idet?.effectiviteitScore != null && <Tag size="sm" type="teal">effectiviteit {idet.effectiviteitScore}/5</Tag>}
                            </div>
                            <div className="pdca-row-actions">
                              <Tag size="sm" type={instStatus.tag}>{instStatus.label}</Tag>
                              {dossierRef && (
                                <Button size="sm" kind="ghost" renderIcon={Launch}
                                  title="Open het aanvraagdossier van dit instrument"
                                  onClick={() => openDossier(dossierRef.caseDefinitionKey, dossierRef.documentId)}>
                                  Dossier
                                </Button>
                              )}
                              {instGepland && (
                                <Button size="sm" kind="ghost" disabled={!!busy} onClick={() => handleInstrumentStarten(inst, doel)}>Starten</Button>
                              )}
                              {instActief && !instGepland && (
                                <Button size="sm" kind="ghost" renderIcon={Checkmark} disabled={!!busy}
                                  onClick={() => handleInstrumentAfronden(inst, doel)}>Afronden</Button>
                              )}
                              <OverflowMenu size="sm" flipped aria-label={`Meer acties voor ${inst.titel}`} iconDescription="Meer acties">
                                {instActief && (
                                  <OverflowMenuItem itemText="Actie toevoegen"
                                    onClick={() => setActionModal({ doelUuid: doel.uuid, instrumentUuid: inst.uuid })} />
                                )}
                                <OverflowMenuItem itemText="Voortgang & notities" onClick={() => toggle(inst.uuid, setVoortgangOpen)} />
                                {instActief && (
                                  <OverflowMenuItem itemText="Afbreken" isDelete hasDivider
                                    onClick={() => setAfbreekTarget({ kind: 'instrument', uuid: inst.uuid, titel: inst.titel })} />
                                )}
                              </OverflowMenu>
                            </div>
                          </div>
                          {idet?.afbreekReden && (
                            <p style={{fontSize: 12, color: 'var(--cds-text-error, #da1e28)', margin: '0 0 8px 0'}}>Afbreekreden: {idet.afbreekReden}</p>
                          )}
                          {/* Taken/opdrachten of this instrument (W&P: instrument -> 0..n taken). */}
                          {instActies.length > 0 && (
                            <div style={{margin: '0 0 8px 16px', borderLeft: '2px solid var(--cds-border-subtle)', paddingLeft: 8}}>
                              {instActies.map(actie => (
                                <ActieRow key={actie.id} actie={actie} busy={!!busy} onStatus={handleActieStatus} />
                              ))}
                            </div>
                          )}
                          {voortgangOpen.has(inst.uuid) && (
                            <InstrumentPanel>
                              <VoortgangNotitiesSectie
                                bewerken={bewerken.has(inst.uuid)}
                                onBewerken={() => setBewerkt(inst.uuid, true)}
                                onSluiten={() => { toggle(inst.uuid, setVoortgangOpen); setBewerkt(inst.uuid, false); }}
                                samenvatting={<InstrumentSamenvatting details={idet} />}
                                formulier={
                                  <InstrumentBijwerken id={inst.uuid} details={idet}
                                    onSave={saveInstrumentBijwerken(inst)}
                                    onCancel={() => setBewerkt(inst.uuid, false)} />
                                } />
                            </InstrumentPanel>
                          )}
                        </div>
                      );
                    })}
                    {di.length === 0 && <p className="pdca-empty-inline">Geen instrumenten</p>}
                  </div>

                  <VoortgangNotitiesSectie
                    bewerken={bewerken.has(doel.uuid)}
                    onBewerken={() => setBewerkt(doel.uuid, true)}
                    samenvatting={<DoelSamenvatting details={dd} metVoortgang={actief} opties={voortgangOpties} />}
                    formulier={
                      <DoelBijwerken id={doel.uuid} details={dd} metVoortgang={actief} opties={voortgangOpties}
                        onSave={saveDoelBijwerken('SUBDOEL', doel)}
                        onCancel={() => setBewerkt(doel.uuid, false)} />
                    } />
                </div>
              )}
            </Layer>
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
        <VerwijderModal target={verwijderTarget} onClose={() => setVerwijderTarget(null)} onSubmit={handleDeleteDoel} />
      </div>
    </Theme>
  );
}

/** "Actie kiezen" / "Instrument aanvragen": a dropdown of the building block koppelingen available under a doel. */
function KoppelingMenu({ label, koppelingen, disabled, open, onToggle, startingKey, doelUuid, onKies }: {
  label: string; koppelingen: ActieBouwblokKoppeling[]; disabled: boolean; open: boolean; onToggle: () => void;
  startingKey: string | null; doelUuid: string; onKies: (koppeling: ActieBouwblokKoppeling) => void;
}) {
  return (
    <div style={{position: 'relative'}}>
      <Button size="sm" kind="tertiary" renderIcon={ChevronDown} disabled={disabled}
        title={disabled ? 'Alleen bij een actief doel' : undefined} onClick={onToggle}>
        {label}
      </Button>
      {open && !disabled && (
        <div className="pdca-menu">
          {koppelingen.map(k => (
            <button key={k.id} type="button"
              disabled={startingKey === k.id + doelUuid}
              onClick={() => onKies(k)}
              className="pdca-menu-item">
              <span style={{fontSize: 13, fontWeight: 500}}>
                {startingKey === k.id + doelUuid ? 'Starten…' : k.naam}
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
}

/**
 * One actie/taak row with the local status flow. With a running building
 * block process the Operaton task is the source of completion; the local
 * status buttons stay hidden. Used under a doel and under an instrument.
 */
function ActieRow({ actie, busy, onStatus }: { actie: Actie; busy: boolean; onStatus: (id: string, action: string) => void }) {
  const isBouwblok = actie.uitvoering === 'BOUWBLOK';
  const loopt = isBouwblok && actie.status !== 'COMPLETED' && actie.status !== 'REJECTED';
  const status = actieStatus(actie.status);
  return (
    <div className="pdca-action-row">
      <div className="pdca-row-main">
        <span className="pdca-text-wrap">{actie.title}</span>
        {actie.assigneeName && <span style={{fontSize: 11, color: 'var(--cds-text-secondary)'}}>— {actie.assigneeName}</span>}
        {actie.dueDate && <span style={{fontSize: 11, color: 'var(--cds-text-secondary)'}}>({formatDate(actie.dueDate)})</span>}
        {actie.result && (actie.status === 'COMPLETED' || actie.status === 'REJECTED') && (
          <span className="pdca-text-ellipsis" style={{fontSize: 11, color: 'var(--cds-text-secondary)', fontStyle: 'italic'}}>
            — {actie.result}
          </span>
        )}
      </div>
      <div className="pdca-row-actions">
        {actie.priority && !isBouwblok && <Tag size="sm" type={actie.priority === 'HIGH' ? 'red' : 'gray'}>{priorityLabel(actie.priority)}</Tag>}
        <Tag size="sm" type={status.tag}>{status.label}</Tag>
        {!loopt && !isBouwblok && <>
          {actie.status === 'PLANNED' && <Button size="sm" kind="ghost" disabled={busy} onClick={() => onStatus(actie.id, 'IN_PROGRESS')}>Starten</Button>}
          {actie.status === 'IN_PROGRESS' && <Button size="sm" kind="ghost" disabled={busy} onClick={() => onStatus(actie.id, 'PENDING_REVIEW')}>Ter beoordeling</Button>}
          {actie.status === 'PENDING_REVIEW' && <>
            <Button size="sm" kind="primary" renderIcon={Checkmark} disabled={busy} onClick={() => onStatus(actie.id, 'approve')}>Goedkeuren</Button>
            <Button size="sm" kind="danger" renderIcon={Close} disabled={busy} onClick={() => onStatus(actie.id, 'reject')}>Afkeuren</Button>
          </>}
        </>}
      </div>
    </div>
  );
}

/** "12 uur · effectiviteit 4/5" — the instrument registration as recorded in an evaluation. */
function registratieSamenvatting(d: InstrumentDetails | undefined): string | null {
  if (!d) return null;
  const delen = [
    d.urenBesteed != null ? `${d.urenBesteed} uur` : null,
    d.effectiviteitScore != null ? `effectiviteit ${d.effectiviteitScore}/5` : null,
  ].filter(Boolean);
  return delen.length > 0 ? delen.join(' · ') : null;
}

const eersteRegel = (tekst: string) => tekst.split('\n').find(r => r.trim())?.trim() ?? '';

/**
 * "Voortgang & notities" of a doel or instrument: a compact summary with
 * "Bijwerken", which swaps in the edit form until it is saved or cancelled.
 */
function VoortgangNotitiesSectie({ bewerken, onBewerken, onSluiten, samenvatting, formulier }: {
  bewerken: boolean; onBewerken: () => void; onSluiten?: () => void;
  samenvatting: React.ReactNode; formulier: React.ReactNode;
}) {
  return (
    <div className="pdca-section-block">
      <div className="pdca-section-title">
        <span>Voortgang &amp; notities</span>
        {!bewerken && (
          <div className="pdca-row-actions">
            <Button size="sm" kind="ghost" onClick={onBewerken}>Bijwerken</Button>
            {onSluiten && <Button size="sm" kind="ghost" onClick={onSluiten}>Sluiten</Button>}
          </div>
        )}
      </div>
      {bewerken ? formulier : samenvatting}
    </div>
  );
}

function NotitiesSamenvatting({ details }: { details?: Partial<Notities> }) {
  const intern = eersteRegel(details?.interneNotitie ?? '');
  const extern = eersteRegel(details?.externeNotitie ?? '');
  if (!intern && !extern) return <p className="pdca-summary-line pdca-summary-empty">Geen notities</p>;
  return <>
    {intern && <p className="pdca-summary-line pdca-text-ellipsis">Interne notitie: {intern}</p>}
    {extern && <p className="pdca-summary-line pdca-text-ellipsis">Externe notitie: {extern}</p>}
  </>;
}

/** The voortgangsstatus of a doel as a tag, looked up in the plan's list. */
function VoortgangTag({ code, opties }: { code: string; opties: VoortgangStatusOptie[] }) {
  const weergave = voortgangStatus(code, opties);
  return <Tag size="sm" type={weergave.tag}>{weergave.label}</Tag>;
}

/**
 * Summary of a doel's voortgang and notes. The voortgang only shows while the
 * doel runs; once it is closed its register status says how it ended.
 */
function DoelSamenvatting({ details, metVoortgang, opties }: {
  details?: DoelDetails; metVoortgang: boolean; opties: VoortgangStatusOptie[];
}) {
  return (
    <div className="pdca-summary">
      {metVoortgang && (details?.voortgangStatus ? (
        <p className="pdca-summary-line">
          <VoortgangTag code={details.voortgangStatus} opties={opties} />
          {details.voortgangToelichting && <span className="pdca-text-wrap">{details.voortgangToelichting}</span>}
        </p>
      ) : (
        <p className="pdca-summary-line pdca-summary-empty">Nog niet beoordeeld</p>
      ))}
      <NotitiesSamenvatting details={details} />
    </div>
  );
}

function InstrumentSamenvatting({ details }: { details?: InstrumentDetails }) {
  const registratie = registratieSamenvatting(details);
  return (
    <div className="pdca-summary">
      <p className={`pdca-summary-line${registratie ? '' : ' pdca-summary-empty'}`}>
        {registratie ?? 'Nog geen uren of effectiviteit geregistreerd'}
      </p>
      {details?.effectiviteitToelichting && <p className="pdca-summary-line pdca-text-wrap">{details.effectiviteitToelichting}</p>}
      <NotitiesSamenvatting details={details} />
    </div>
  );
}

/** The nested "Voortgang & notities" panel under an instrument row; scrolls into view when it opens. */
function InstrumentPanel({ children }: { children: React.ReactNode }) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => { ref.current?.scrollIntoView({ block: 'nearest', behavior: 'smooth' }); }, []);
  return <Layer className="pdca-instrument-panel"><div ref={ref}>{children}</div></Layer>;
}

/** Buttons under an edit form: cancel closes it, save is enabled once something changed. */
function BijwerkenActies({ saving, kanOpslaan, fout, onCancel, onSave }: {
  saving: boolean; kanOpslaan: boolean; fout: string | null; onCancel: () => void; onSave: () => void;
}) {
  return <>
    {fout && <InlineNotification kind="error" title="Opslaan mislukt" subtitle={fout} lowContrast hideCloseButton />}
    <div className="pdca-edit-actions">
      <Button size="sm" kind="ghost" disabled={saving} onClick={onCancel}>Annuleren</Button>
      <Button size="sm" disabled={saving || !kanOpslaan} onClick={onSave}>{saving ? 'Opslaan…' : 'Opslaan'}</Button>
    </div>
  </>;
}

/**
 * Edit form for the voortgang (active subdoel or hoofddoel) and the notes of
 * a doel; only the parts that changed are saved.
 */
function DoelBijwerken({ id, details, metVoortgang, opties, onSave, onCancel }: {
  id: string; details?: DoelDetails; metVoortgang: boolean; opties: VoortgangStatusOptie[];
  onSave: (voortgang: VoortgangInvoer | null, notities: Notities | null) => Promise<void>;
  onCancel: () => void;
}) {
  const [status, setStatus] = useState<string>(details?.voortgangStatus ?? '');
  const [toelichting, setToelichting] = useState(details?.voortgangToelichting ?? '');
  const [notities, setNotities] = useState<Notities>({
    interneNotitie: details?.interneNotitie ?? '', externeNotitie: details?.externeNotitie ?? '',
  });
  const { saving, fout, submit } = useSubmit();
  const voortgangGewijzigd = metVoortgang
    && (status !== (details?.voortgangStatus ?? '') || toelichting !== (details?.voortgangToelichting ?? ''));
  const notitiesGewijzigd = notities.interneNotitie !== (details?.interneNotitie ?? '')
    || notities.externeNotitie !== (details?.externeNotitie ?? '');
  const kanOpslaan = (voortgangGewijzigd || notitiesGewijzigd) && !(voortgangGewijzigd && !status);
  return (
    <div className="pdca-edit-form">
      {metVoortgang && (
        <VoortgangStatusBlok id={id} status={status} toelichting={toelichting} huidig={details?.voortgangStatus}
          opties={opties} onStatus={setStatus} onToelichting={setToelichting} />
      )}
      <NotitiesBlok id={id} notities={notities} onChange={setNotities} />
      <BijwerkenActies saving={saving} kanOpslaan={kanOpslaan} fout={fout} onCancel={onCancel}
        onSave={() => submit(() => onSave(
          voortgangGewijzigd && status ? { status, toelichting } : null,
          notitiesGewijzigd ? notities : null,
        ))} />
    </div>
  );
}

/** Edit form for the hours, effectiveness and notes of an instrument; only the parts that changed are saved. */
function InstrumentBijwerken({ id, details, onSave, onCancel }: {
  id: string; details?: InstrumentDetails;
  onSave: (registratie: RegistratieInvoer | null, notities: Notities | null) => Promise<void>;
  onCancel: () => void;
}) {
  const [uren, setUren] = useState<string>(details?.urenBesteed?.toString() ?? '');
  const [score, setScore] = useState<string>(details?.effectiviteitScore?.toString() ?? '');
  const [toelichting, setToelichting] = useState(details?.effectiviteitToelichting ?? '');
  const [notities, setNotities] = useState<Notities>({
    interneNotitie: details?.interneNotitie ?? '', externeNotitie: details?.externeNotitie ?? '',
  });
  const { saving, fout, submit } = useSubmit();
  const registratieGewijzigd = uren !== (details?.urenBesteed?.toString() ?? '')
    || score !== (details?.effectiviteitScore?.toString() ?? '')
    || toelichting !== (details?.effectiviteitToelichting ?? '');
  const notitiesGewijzigd = notities.interneNotitie !== (details?.interneNotitie ?? '')
    || notities.externeNotitie !== (details?.externeNotitie ?? '');
  return (
    <div className="pdca-edit-form">
      <VoortgangForm id={id} uren={uren} score={score} toelichting={toelichting}
        onUren={setUren} onScore={setScore} onToelichting={setToelichting} />
      <NotitiesBlok id={id} notities={notities} onChange={setNotities} />
      <BijwerkenActies saving={saving} kanOpslaan={registratieGewijzigd || notitiesGewijzigd} fout={fout} onCancel={onCancel}
        onSave={() => submit(() => onSave(
          registratieGewijzigd
            ? { uren: uren ? parseInt(uren, 10) : null, score: score ? parseInt(score, 10) : null, toelichting }
            : null,
          notitiesGewijzigd ? notities : null,
        ))} />
    </div>
  );
}

/**
 * Internal note (colleagues only) and external note (may be shared with the
 * inwoner) of a subdoel, the hoofddoel or an instrument.
 */
function NotitiesBlok({ id, notities, onChange }: {
  id: string; notities: Notities; onChange: (notities: Notities) => void;
}) {
  return (
    <div className="pdca-form-grid">
      <TextArea id={`intern-${id}`} rows={3} labelText="Interne notitie" helperText="Alleen zichtbaar voor collega's"
        value={notities.interneNotitie} onChange={(e: any) => onChange({ ...notities, interneNotitie: e.target.value })} />
      <TextArea id={`extern-${id}`} rows={3} labelText="Externe notitie" helperText="Kan gedeeld worden met de inwoner"
        value={notities.externeNotitie} onChange={(e: any) => onChange({ ...notities, externeNotitie: e.target.value })} />
    </div>
  );
}

/**
 * Voortgangsstatus with its explanation, for an active subdoel or the
 * hoofddoel; the choices are the plan's voortgangsstatussen. A current status
 * that was removed from the list stays selectable until it is changed.
 * During an evaluation a changed explanation becomes the reason of the
 * recorded change.
 */
function VoortgangStatusBlok({ id, status, toelichting, huidig, opties, onStatus, onToelichting }: {
  id: string; status: string; toelichting: string; huidig?: string; opties: VoortgangStatusOptie[];
  onStatus: (status: string) => void; onToelichting: (toelichting: string) => void;
}) {
  const vervallen = huidig && !opties.some(o => o.code === huidig) ? huidig : null;
  return (
    <div className="pdca-form-grid">
      <Select id={`voortgang-${id}`} labelText="Voortgang" value={status}
        onChange={(e: any) => onStatus(e.target.value)}>
        <SelectItem value="" text="Geen status" disabled hidden={!!huidig} />
        {opties.map(o => <SelectItem key={o.code} value={o.code} text={o.naam} />)}
        {vervallen && (
          <SelectItem value={vervallen} text={`${voortgangStatus(vervallen, opties).label} (niet meer in gebruik)`} />
        )}
      </Select>
      <TextArea id={`voortgang-toelichting-${id}`} rows={2} labelText="Toelichting" helperText="Waarom gaat het zo?"
        value={toelichting} onChange={(e: any) => onToelichting(e.target.value)} />
    </div>
  );
}

const EFFECTIVITEIT_LABELS = ['geen effect', 'weinig effect', 'neutraal', 'effectief', 'zeer effectief'];

/** Hours + effectiviteit with its explanation of an instrument (PDCA overlay). */
function VoortgangForm({ id, uren, score, toelichting, onUren, onScore, onToelichting }: {
  id: string; uren: string; score: string; toelichting: string;
  onUren: (uren: string) => void; onScore: (score: string) => void; onToelichting: (toelichting: string) => void;
}) {
  return (
    <div>
      <div className="pdca-form-grid">
        <TextInput id={`uren-${id}`} labelText="Bestede uren" type="number"
          value={uren} onChange={(e: any) => onUren(e.target.value)} />
        <Select id={`score-${id}`} labelText="Effectiviteit" value={score} onChange={(e: any) => onScore(e.target.value)}>
          <SelectItem value="" text="-" />
          {EFFECTIVITEIT_LABELS.map((label, i) => <SelectItem key={i} value={String(i + 1)} text={`${i + 1} - ${label}`} />)}
        </Select>
      </div>
      <TextArea id={`toel-${id}`} className="pdca-form-field" rows={2} labelText="Toelichting"
        helperText="Hoe werkt dit instrument voor de inwoner?"
        value={toelichting} onChange={(e: any) => onToelichting(e.target.value)} />
    </div>
  );
}

/** Aborting always requires a reason (doel: cascades to linked instruments). */
function AfbreekModal({ target, onClose, onSubmit }: {
  target: AfbreekTarget; onClose: () => void; onSubmit: (reden: string) => Promise<void>;
}) {
  const [reden, setReden] = useState('');
  const { saving, fout, submit, reset } = useSubmit();
  useEffect(() => { if (target) { setReden(''); reset(); } }, [target]);
  return (
    <Modal open={!!target} danger modalHeading={`${target?.kind === 'doel' ? 'Subdoel' : 'Instrument'} afbreken`}
      primaryButtonText={saving ? 'Afbreken…' : 'Afbreken'} secondaryButtonText="Annuleren"
      primaryButtonDisabled={saving || !reden.trim()}
      onRequestClose={onClose} onRequestSubmit={() => reden.trim() && submit(() => onSubmit(reden.trim()))}>
      <div className="pdca-modal-form">
        {fout && <InlineNotification kind="error" title="Afbreken mislukt" subtitle={fout} lowContrast hideCloseButton />}
        <p>
          Je staat op het punt <strong>{target?.titel}</strong> af te breken.
          {target?.kind === 'doel' && ' Gekoppelde actieve instrumenten worden ook afgebroken.'}
        </p>
        <TextArea id="afbreek-reden" labelText="Reden (verplicht)" value={reden}
          onChange={(e: any) => setReden(e.target.value)} placeholder="Waarom wordt dit afgebroken?" />
      </div>
    </Modal>
  );
}

/** Removing a subdoel takes its own instruments and its acties with it. */
function VerwijderModal({ target, onClose, onSubmit }: {
  target: VerwijderTarget; onClose: () => void; onSubmit: () => Promise<void>;
}) {
  const { saving, fout, submit, reset } = useSubmit();
  useEffect(() => { if (target) reset(); }, [target]);
  const mee = target
    ? [
        target.instrumenten > 0 ? aantal(target.instrumenten, 'instrument', 'instrumenten') : null,
        target.acties > 0 ? aantal(target.acties, 'actie', 'acties') : null,
      ].filter(Boolean).join(' en ')
    : '';
  return (
    <Modal open={!!target} danger modalHeading="Subdoel verwijderen"
      primaryButtonText={saving ? 'Verwijderen…' : 'Verwijderen'} secondaryButtonText="Annuleren"
      primaryButtonDisabled={saving}
      onRequestClose={onClose} onRequestSubmit={() => submit(onSubmit)}>
      <div className="pdca-modal-form">
        {fout && <InlineNotification kind="error" title="Verwijderen mislukt" subtitle={fout} lowContrast hideCloseButton />}
        <p>
          <strong>{target?.titel}</strong> wordt verwijderd{mee ? `, inclusief ${mee}` : ''}. Dit kan niet ongedaan worden gemaakt.
        </p>
        <p>Is het subdoel niet meer van toepassing? Kies dan liever Afbreken; dan blijft het met de reden zichtbaar in het plan.</p>
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
  onClose: () => void; onSave: (data: { titel: string; toelichting: string; doeltypeUuid: string }) => Promise<void>;
}) {
  const [toelichting, setToelichting] = useState('');
  const [doeltypeUuid, setDoeltypeUuid] = useState('');
  const { saving, fout, submit, reset } = useSubmit();
  useEffect(() => {
    if (open) {
      setToelichting(doel?.beschrijving || '');
      setDoeltypeUuid(doel?.doeltype?.uuid || '');
      reset();
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
      primaryButtonText={saving ? 'Opslaan…' : 'Opslaan'} secondaryButtonText="Annuleren"
      primaryButtonDisabled={saving || !doeltypeUuid || !titel}
      onRequestClose={onClose}
      onRequestSubmit={() => doeltypeUuid && titel && submit(() => onSave({ titel, toelichting, doeltypeUuid }))}>
      <div className="pdca-modal-form">
        {fout && <InlineNotification kind="error" title="Opslaan mislukt" subtitle={fout} lowContrast hideCloseButton />}
        <Select id="doel-type" labelText="Subdoel" value={doeltypeUuid} onChange={(e: any) => setDoeltypeUuid(e.target.value)}
          helperText={gescoped
            ? `Subdoelen die horen bij hoofddoel "${hoofddoelNaam}"`
            : 'Vast gedefinieerde doelen uit het doeltype-register'}>
          <SelectItem value="" text="-- Kies subdoel --" />
          {huidigOnbekend && <SelectItem value={doel!.doeltype!.uuid} text={`${doel!.titel} (huidig)`} />}
          {opties.map(t => (
            <SelectItem key={t.uuid} value={t.uuid} text={t.doelType} />
          ))}
        </Select>
        <TextArea id="doel-toelichting" labelText="Toelichting" helperText="Uitleg voor de inwoner" value={toelichting}
          onChange={(e: any) => setToelichting(e.target.value)} />
      </div>
    </Modal>
  );
}

function ActieModal({ open, ctx, onClose, onSave }: {
  open: boolean;
  ctx: { doelUuid: string; instrumentUuid?: string } | null;
  onClose: () => void;
  onSave: (ctx: { doelUuid: string; instrumentUuid?: string }, data: Partial<Actie> & { title: string }) => Promise<void>;
}) {
  const [title, setTitle] = useState('');
  const [desc, setDesc] = useState('');
  const [assigneeType, setAssigneeType] = useState('PROFESSIONAL');
  const [assigneeName, setAssigneeName] = useState('');
  const [priority, setPriority] = useState('NORMAL');
  const [dueDate, setDueDate] = useState('');
  const { saving, fout, submit, reset } = useSubmit();
  useEffect(() => { if (open) { setTitle(''); setDesc(''); setAssigneeName(''); setDueDate(''); reset(); } }, [open]);
  const opslaan = () => {
    if (!ctx || !title.trim()) return;
    submit(() => onSave(ctx, {
      title: title.trim(), description: desc, assigneeType, assigneeName, priority, dueDate: dueDate || undefined,
    }));
  };
  return (
    <Modal open={open} modalHeading={ctx?.instrumentUuid ? 'Actie bij instrument toevoegen' : 'Actie toevoegen'}
      primaryButtonText={saving ? 'Opslaan…' : 'Opslaan'} secondaryButtonText="Annuleren"
      primaryButtonDisabled={saving || !title.trim()}
      onRequestClose={onClose} onRequestSubmit={opslaan}>
      <div className="pdca-modal-form">
        {fout && <InlineNotification kind="error" title="Opslaan mislukt" subtitle={fout} lowContrast hideCloseButton />}
        <TextInput id="actie-title" labelText="Titel (verplicht)" value={title} onChange={(e: any) => setTitle(e.target.value)} />
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
 * "Instrument toevoegen": an instrument registered straight on the doel,
 * without a request process; it starts as Gepland. The catalog is scoped to
 * the subdoel — producttypen that carry a thema of the doeltype, within the
 * doelgroep of the plan subject — with "Toon alle instrumenttypen" as escape hatch
 * for a product that is not (yet) thematised.
 */
function InstrumentModal({ open, doelUuid, producttypen, doelType, doelgroep, onClose, onSave }: {
  open: boolean; doelUuid: string | null; producttypen: ProductType[];
  doelType?: DoelType; doelgroep?: string; onClose: () => void;
  onSave: (doelUuid: string, data: { titel: string; producttype?: ProductType }) => Promise<void>;
}) {
  const [titel, setTitel] = useState('');
  const [productUuid, setProductUuid] = useState('');
  const [alleProducten, setAlleProducten] = useState(false);
  const { saving, fout, submit, reset } = useSubmit();
  useEffect(() => { if (open) { setTitel(''); setProductUuid(''); setAlleProducten(false); reset(); } }, [open]);
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
    <Modal open={open} modalHeading="Instrument toevoegen"
      primaryButtonText={saving ? 'Opslaan…' : 'Opslaan'} secondaryButtonText="Annuleren"
      primaryButtonDisabled={saving || !titel.trim()}
      onRequestClose={onClose}
      onRequestSubmit={() => doelUuid && titel.trim() && submit(() => onSave(doelUuid, { titel: titel.trim(), producttype: selected }))}>
      <div className="pdca-modal-form">
        {fout && <InlineNotification kind="error" title="Opslaan mislukt" subtitle={fout} lowContrast hideCloseButton />}
        <Select id="inst-product" labelText="Instrumenttype" value={productUuid} onChange={(e: any) => onProductSelect(e.target.value)}
          helperText={alleProducten
            ? 'Volledige catalogus'
            : themas.length > 0
              ? `Instrumenttypen bij dit subdoel (${themas.join(', ')})`
              : 'Dit subdoel heeft geen thema; de volledige catalogus wordt getoond'}>
          <SelectItem value="" text="-- Selecteer instrumenttype (optioneel) --" />
          {opties.map(p => (
            <SelectItem key={p.uuid} value={p.uuid} text={`${p.naam}${p.organisaties[0] ? ` (${p.organisaties[0].naam})` : ''}`} />
          ))}
        </Select>
        {(alleProducten || passend.length < producttypen.length) && (
          <Checkbox id="inst-alle-producten" labelText="Toon alle instrumenttypen"
            checked={alleProducten}
            onChange={(_: unknown, { checked }: { checked: boolean }) => toggleAlleProducten(checked)} />
        )}
        {selected && (
          <p style={{fontSize: 12, color: 'var(--cds-text-secondary)'}}>
            {selected.samenvatting}
            {selected.parameters.find(p => p.naam === 'duur') ? ` · Duur: ${selected.parameters.find(p => p.naam === 'duur')!.waarde}` : ''}
          </p>
        )}
        <TextInput id="inst-titel" labelText="Titel (verplicht)" value={titel} onChange={(e: any) => setTitel(e.target.value)} />
      </div>
    </Modal>
  );
}
