import React, { useState, useEffect, useCallback, useMemo } from 'react';
import {
  Layer,
  Tile,
  Tag,
  Button,
  Checkbox,
  TextArea,
  TextInput,
  DataTable,
  Table,
  TableHead,
  TableRow,
  TableHeader,
  TableBody,
  TableCell,
  InlineNotification,
  Loading,
  Modal,
  Select,
  SelectItem,
  Theme,
  MenuButton,
  MenuItem,
  MenuItemDivider,
} from '@carbon/react';
import { TrashCan } from '@carbon/react/icons';
import { onInit, resizeIframe, GzacContext } from '../shared/bridge';
import {
  EvaluationSession, EvaluationChangeInput, syncEvaluationPanel, recordEvaluationChange, onEvaluationEvent,
} from '../shared/evaluationSession';
import { EvaluatieBanner } from '../shared/EvaluatieBanner';
import { useSubmit } from '../shared/useSubmit';
import {
  openplan,
  openklant,
  pdca,
  planVoorDossier,
  registers,
  Plan,
  PlanDetails,
  Doel,
  DoelDetails,
  DoelType,
  Actie,
  Klantcontact,
  ContactmomentDetails,
  Betrokkene,
  RelatieType,
  BrpPersoon,
  ObjectRecord,
  VoortgangStatusOptie,
} from '../shared/api';
import {
  evalTypeLabel, formatDate, planStatus, isVerlopen, contactmomentDatum,
  dienstverleningLabel, doelTypeNaam, hoofddoelTypen, isHoofddoelType,
} from '../shared/labels';
import '../shared/styles.css';

type SubjectData =
  | { kind: 'persoon'; brp: BrpPersoon | null; bsn: string }
  | { kind: 'object'; object: ObjectRecord | null; id: string }
  | null;

const EVAL_TAG_TYPE: Record<string, string> = {
  INTAKE: 'blue',
  PROGRESS: 'purple',
  EVALUATION: 'green',
  INSPECTION: 'warm-gray',
  CRISIS: 'red',
};

const KPI_COLORS = ['#24a148', '#0f62fe', '#ff832b', '#8a3ffc'];

/** Closing or reopening the plan, each confirmed in its own modal. */
type PlanActie = 'afronden' | 'afbreken' | 'heractiveren';

const betrokkeneNaam = (b: Betrokkene) => `${b.name} · ${b.role}`;

function formatAddress(adres: { straat: string; huisnummer?: string; postcode: string; woonplaats: string }): string {
  const street = adres.straat + (adres.huisnummer ? ' ' + adres.huisnummer : '');
  return `${street}, ${adres.postcode} ${adres.woonplaats}`;
}

/** The subject of a plan is its domeinregister URN: urn:pdca:brp:persoon:<bsn> or urn:pdca:objecten:object:<id>. */
async function loadSubject(plan: Plan): Promise<SubjectData> {
  const parts = plan.domeinregister?.split(':') ?? [];
  const id = parts[4];
  if (!id) return null;
  if (parts[3] === 'persoon') {
    return { kind: 'persoon', bsn: id, brp: await registers.persoon(id).catch(() => null) };
  }
  if (parts[3] === 'object') {
    return { kind: 'object', id, object: await registers.object(id).catch(() => null) };
  }
  return null;
}

export function PlanOverview() {
  const [plan, setPlan] = useState<Plan | null>(null);
  const [details, setDetails] = useState<PlanDetails | null>(null);
  const [doelen, setDoelen] = useState<Doel[]>([]);
  const [doelDetails, setDoelDetails] = useState<DoelDetails[]>([]);
  const [acties, setActies] = useState<Actie[]>([]);
  const [contactmomenten, setContactmomenten] = useState<Klantcontact[]>([]);
  const [cmDetails, setCmDetails] = useState<ContactmomentDetails[]>([]);
  const [betrokkenen, setBetrokkenen] = useState<Betrokkene[]>([]);
  const [subject, setSubject] = useState<SubjectData>(null);
  const [planStatusOpties, setPlanStatusOpties] = useState<string[]>([]);
  const [positieTypen, setPositieTypen] = useState<string[]>([]);
  const [voortgangOpties, setVoortgangOpties] = useState<VoortgangStatusOptie[]>([]);
  const [doeltypen, setDoeltypen] = useState<DoelType[]>([]);
  const [relatietypen, setRelatietypen] = useState<RelatieType[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [successMsg, setSuccessMsg] = useState<string | null>(null);

  const [editingField, setEditingField] = useState<string | null>(null);
  const [editValue, setEditValue] = useState('');
  const [modalOpen, setModalOpen] = useState(false);
  const [partyForm, setPartyForm] = useState({ name: '', role: '', email: '', phone: '', isPrimary: false });
  /** Betrokkene awaiting confirmation of its removal. */
  const [partyToDelete, setPartyToDelete] = useState<Betrokkene | null>(null);
  /** Plan action awaiting confirmation, with the reason or explanation entered for it. */
  const [planActie, setPlanActie] = useState<PlanActie | null>(null);
  const [redenEinde, setRedenEinde] = useState('');
  /** The user's running evaluation; while it is on this plan, every change here is recorded in it. */
  const [session, setSession] = useState<EvaluationSession | null>(null);
  const fieldSubmit = useSubmit();
  const partySubmit = useSubmit();
  const deleteSubmit = useSubmit();
  const planSubmit = useSubmit();

  useEffect(() => { resizeIframe(); });

  useEffect(() => onEvaluationEvent(event => {
    if (event.type === 'started') setSession(event.session);
    if (event.type === 'ended') setSession(null);
  }), []);

  useEffect(() => {
    onInit(async (ctx: GzacContext) => {
      try {
        // Plan = dossier (1:1): this tab shows only the plan directly
        // linked to this dossier (overlay) — no fallback.
        if (!ctx.documentId) {
          setError('Geen dossiercontext ontvangen. Een plan is 1:1 een dossier; open dit tabblad vanuit een GZAC-dossier.');
          setLoading(false);
          return;
        }
        syncEvaluationPanel(ctx.documentId).then(setSession);
        const result = await planVoorDossier(ctx.documentId);
        if (!result.plan) {
          setError(result.fout);
          setLoading(false);
          return;
        }
        await loadPlanData(result.plan, result.details);
      } catch (err: any) {
        setError('Kan geen verbinding maken met Open Plan: ' + err.message);
        setLoading(false);
      }
    });
  }, []);

  async function loadPlanData(selected: Plan, selectedDetails: PlanDetails | null) {
    try {
      const [doelenRes, doelDetailsRes, actiesRes, cmRes, cmDetailsRes, betrokkenenRes, rollenRes, doeltypenRes, subjectRes, voortgangRes] =
        await Promise.all([
          openplan.doelen.listByPlan(selected.uuid),
          pdca.doeldetails.listByPlan(selected.uuid).catch(() => [] as DoelDetails[]),
          pdca.acties.listByPlan(selected.uuid).catch(() => [] as Actie[]),
          openklant.klantcontacten.listByPlan(selected.uuid).catch(() => [] as Klantcontact[]),
          pdca.contactmomentdetails.listByPlan(selected.uuid).catch(() => [] as ContactmomentDetails[]),
          pdca.betrokkenen.listByPlan(selected.uuid).catch(() => [] as Betrokkene[]),
          openplan.relatietypen.list().catch(() => [] as RelatieType[]),
          openplan.doeltypen.list().catch(() => [] as DoelType[]),
          loadSubject(selected),
          pdca.voortgangStatussen(selected.uuid).catch(() => [] as VoortgangStatusOptie[]),
        ]);

      let statusOptiesRes: string[] = [];
      let positieTypenRes: string[] = [];
      if (selectedDetails?.caseDefinitionKey) {
        try {
          const cfg = await pdca.phaseConfigs.get(selectedDetails.caseDefinitionKey);
          statusOptiesRes = JSON.parse(cfg.planStatussen || '[]');
          positieTypenRes = JSON.parse(cfg.positieTypen || '[]');
        } catch { /* no config */ }
      }

      setPlan(selected);
      setDetails(selectedDetails);
      setDoelen(doelenRes);
      setDoelDetails(doelDetailsRes);
      setActies(actiesRes);
      setContactmomenten(cmRes);
      setCmDetails(cmDetailsRes);
      setBetrokkenen(betrokkenenRes);
      setRelatietypen(rollenRes);
      setDoeltypen(doeltypenRes);
      setSubject(subjectRes);
      setPlanStatusOpties(statusOptiesRes.length ? statusOptiesRes : ['Concept', 'Vastgesteld']);
      setPositieTypen(positieTypenRes);
      setVoortgangOpties(voortgangRes);
      setLoading(false);
    } catch (err: any) {
      setError('Fout bij laden van plangegevens: ' + err.message);
      setLoading(false);
    }
  }

  const detailsByDoel = useMemo(() => new Map(doelDetails.map(d => [d.doelUuid, d])), [doelDetails]);
  const cmDetailsByUuid = useMemo(() => new Map(cmDetails.map(d => [d.contactmomentUuid, d])), [cmDetails]);
  const procesbegeleider = betrokkenen.find(b => b.isPrimary) ?? null;

  /** Records a plan change in the running evaluation of this plan (no-op without one). */
  const rec = useCallback((change: Omit<EvaluationChangeInput, 'subjectType' | 'subjectUuid' | 'subjectTitel'>) => {
    if (plan) recordEvaluationChange(plan.uuid, { subjectType: 'PLAN', subjectUuid: plan.uuid, subjectTitel: plan.titel, ...change });
  }, [plan]);

  const kpi = useMemo(() => {
    // Voortgang = how the active subdoelen are going (overlay voortgangsstatus, counted by the
    // betekenis of the status in the plan's list); the hoofddoel stays out.
    const subdoelen = doelen.filter(d => {
      const type = doeltypen.find(t => t.uuid === d.doeltype?.uuid);
      return !(type && isHoofddoelType(type));
    });
    const actieveSubdoelen = subdoelen.filter(d => d.status === 'actief');
    const betekenisVan = (d: Doel) =>
      voortgangOpties.find(o => o.code === detailsByDoel.get(d.uuid)?.voortgangStatus)?.betekenis;
    const opKoers = actieveSubdoelen.filter(d => betekenisVan(d) === 'GAAT_GOED').length;
    const aandacht = actieveSubdoelen.filter(d => {
      const betekenis = betekenisVan(d);
      return betekenis === 'LET_OP' || betekenis === 'GAAT_NIET_GOED';
    }).length;
    const beoordeeld = actieveSubdoelen.filter(d => !!detailsByDoel.get(d.uuid)?.voortgangStatus).length;
    const openActies = acties.filter(a =>
      a.status === 'PLANNED' || a.status === 'IN_PROGRESS' || a.status === 'PENDING_REVIEW'
    ).length;
    const afgerondeEvaluaties = contactmomenten.filter(c => c.indicatieContactGelukt === true).length;
    // A planned contactmoment has no outcome yet (indicatieContactGelukt null).
    const geplandeDatums = contactmomenten
      .filter(c => c.indicatieContactGelukt == null)
      .map(c => contactmomentDatum(c, cmDetailsByUuid.get(c.uuid)))
      .filter(Boolean)
      .sort();
    return {
      voortgang: { opKoers, aandacht, beoordeeld, nietBeoordeeld: actieveSubdoelen.length - beoordeeld },
      lopendeSubdoelen: actieveSubdoelen.length,
      totaalSubdoelen: subdoelen.length,
      openActies,
      afgerondeEvaluaties,
      volgendeEvaluatie: geplandeDatums[0] ?? null,
    };
  }, [doelen, doeltypen, detailsByDoel, voortgangOpties, acties, contactmomenten, cmDetailsByUuid]);

  const recentEvals = useMemo(() =>
    contactmomenten
      .filter(c => c.indicatieContactGelukt === true)
      .sort((a, b) => (b.plaatsgevondenOp || '').localeCompare(a.plaatsgevondenOp || ''))
      .slice(0, 3),
  [contactmomenten]);

  const showSuccess = useCallback((msg: string) => {
    setSuccessMsg(msg);
    setTimeout(() => setSuccessMsg(null), 3000);
  }, []);

  const openPlanActie = useCallback((actie: PlanActie) => {
    setRedenEinde('');
    planSubmit.reset();
    setPlanActie(actie);
  }, [planSubmit]);

  // Closing a plan sets its register status with einddatum and redenEinde
  // (optional explanation when afgerond, a required reason when afgebroken);
  // reopening clears both.
  const handlePlanActie = useCallback(async () => {
    if (!plan || !planActie) return;
    const reden = redenEinde.trim();
    if (planActie === 'afbreken' && !reden) return;
    const status = planActie === 'afronden' ? 'afgerond' : planActie === 'afbreken' ? 'geannuleerd' : 'actief';
    const ok = await planSubmit.submit(async () => {
      const body = status === 'actief'
        ? { status, einddatum: null, redenEinde: '' }
        : { status, einddatum: new Date().toISOString(), redenEinde: reden };
      const updated = await openplan.plannen.update(plan.uuid, body);
      const naar = planStatus(status, details?.weergaveStatus).label;
      rec({
        soort: 'PLAN_STATUS', samenvoegen: true,
        vanWaarde: planStatus(plan.status, details?.weergaveStatus).label, naarWaarde: naar,
        toelichting: reden || undefined,
      });
      setPlan(updated);
      showSuccess(status === 'actief' ? 'Plan heractiveerd' : `Plan ${naar.toLowerCase()}`);
    });
    if (ok) setPlanActie(null);
  }, [plan, planActie, redenEinde, details, planSubmit, showSuccess, rec]);

  // Configurable plan status (Concept, Vastgesteld, ...) — overlay.
  const handleWeergaveStatus = useCallback(async (weergaveStatus: string) => {
    if (!plan || !weergaveStatus) return;
    try {
      const updated = await pdca.plandetails.upsert(plan.uuid, { weergaveStatus });
      rec({ soort: 'WEERGAVESTATUS', samenvoegen: true, vanWaarde: details?.weergaveStatus, naarWaarde: weergaveStatus });
      setDetails(updated);
      showSuccess('Planstatus gewijzigd naar ' + weergaveStatus);
    } catch (err: any) {
      setError('Planstatus wijzigen mislukt: ' + err.message);
    }
  }, [plan, details, showSuccess, rec]);

  // notitie lives on the Open Plan plan; positions and hoofddoel in the PDCA
  // overlay; the procesbegeleider is one of the plan's betrokkenen.
  // Positions and hoofddoel are register-driven choices (positietype resp.
  // doeltype register) — no free text; only the explanation for the inwoner
  // remains free text.
  const hoofddoelOpties = hoofddoelTypen(doeltypen);
  const gekozenHoofddoel = doeltypen.find(t => t.uuid === details?.hoofddoelTypeUuid);
  const editableFields: {
    key: string; label: string; emptyText: string; value?: string;
    display?: string; options?: { value: string; text: string }[]; placeholder?: string;
    /** Shown instead of the edit button when the field cannot be edited yet. */
    hint?: string;
  }[] = [
    {
      key: 'procesbegeleider', label: 'Procesbegeleider', emptyText: 'Nog geen procesbegeleider',
      value: procesbegeleider?.id || '', display: procesbegeleider ? betrokkeneNaam(procesbegeleider) : '',
      options: betrokkenen.map(b => ({ value: b.id, text: betrokkeneNaam(b) })),
      placeholder: '-- Kies een verantwoordelijke --',
      hint: betrokkenen.length === 0 ? 'Voeg eerst een verantwoordelijke toe onder Verantwoordelijkheden.' : undefined,
    },
    {
      key: 'hoofddoelTypeUuid', label: 'Hoofddoel', emptyText: 'Nog geen hoofddoel gekozen',
      value: details?.hoofddoelTypeUuid || '', display: doelTypeNaam(gekozenHoofddoel),
      options: hoofddoelOpties.map(t => ({ value: t.uuid, text: t.doelType })),
    },
    { key: 'notitie', label: 'Toelichting bij hoofddoel', emptyText: 'Geen toelichting', value: plan?.notitie },
    { key: 'subdoelgroep', label: 'Subdoelgroep', emptyText: 'Nog niet bepaald', value: details?.subdoelgroep },
    {
      key: 'beginPositie', label: 'Positie', emptyText: 'Nog niet gezet',
      value: details?.beginPositie, options: positieTypen.map(p => ({ value: p, text: p })),
    },
  ];

  const startEditing = useCallback((key: string, value?: string) => {
    fieldSubmit.reset();
    setEditingField(key);
    setEditValue(value || '');
  }, [fieldSubmit]);

  const saveField = useCallback(async () => {
    if (!plan || !editingField) return;
    const ok = await fieldSubmit.submit(async () => {
      if (editingField === 'notitie') {
        const updated = await openplan.plannen.update(plan.uuid, { notitie: editValue.trim() });
        rec({ soort: 'HOOFDDOEL_TOELICHTING', samenvoegen: true, vanWaarde: plan.notitie, naarWaarde: editValue.trim() });
        setPlan(updated);
      } else if (editingField === 'procesbegeleider') {
        // The backend also keeps the Open Plan plan.medewerker in step.
        if (editValue !== procesbegeleider?.id) {
          const bijgewerkt = await pdca.betrokkenen.setProcesbegeleider(editValue);
          const nieuw = bijgewerkt.find(b => b.id === editValue);
          rec({ soort: 'PROCESBEGELEIDER', samenvoegen: true, vanWaarde: procesbegeleider?.name, naarWaarde: nieuw?.name });
          setBetrokkenen(bijgewerkt);
        }
      } else if (editingField === 'hoofddoelTypeUuid') {
        // W&P: "een plan heeft maar 1 hoofddoel actief". Switching completes
        // the current hoofddoel-doel (history in the doelen tab), creates a
        // new one of the chosen type and re-points the subdoelen to it.
        const nieuwType = doeltypen.find(t => t.uuid === editValue);
        if (!nieuwType || !details) throw new Error('Onbekend hoofddoeltype');
        const isHoofddoelDoel = (d: Doel) => {
          const t = doeltypen.find(x => x.uuid === d.doeltype?.uuid);
          return t ? isHoofddoelType(t) : false;
        };
        const huidig = doelen.find(d => isHoofddoelDoel(d) && d.status === 'actief');
        if (!huidig || huidig.doeltype?.uuid !== editValue) {
          if (huidig) {
            await openplan.doelen.update(huidig.uuid, { status: 'afgerond', einddatum: new Date().toISOString() });
          }
          const nieuw = await openplan.doelen.create({
            plannenUuids: [plan.uuid],
            persoonUuid: details.persoonUuid,
            doeltypeUuid: nieuwType.uuid,
            titel: doelTypeNaam(nieuwType),
            beschrijving: plan.notitie || '',
            startdatum: new Date().toISOString(),
          });
          const subdoelen = doelen.filter(d => !isHoofddoelDoel(d));
          await Promise.all(subdoelen.map(d => openplan.doelen.update(d.uuid, { hoofdDoel: nieuw.uuid })));
          rec({ soort: 'HOOFDDOEL_GEWISSELD', vanWaarde: huidig?.titel, naarWaarde: nieuw.titel });
          setDoelen(await openplan.doelen.listByPlan(plan.uuid));
        }
        const updated = await pdca.plandetails.upsert(plan.uuid, { hoofddoelTypeUuid: editValue } as any);
        setDetails(updated);
      } else {
        // Register-driven fields (beginPositie, subdoelgroep).
        const updated = await pdca.plandetails.upsert(plan.uuid, { [editingField]: editValue.trim() } as any);
        if (editingField === 'beginPositie' || editingField === 'subdoelgroep') {
          rec({
            soort: editingField === 'beginPositie' ? 'POSITIE' : 'SUBDOELGROEP', samenvoegen: true,
            vanWaarde: details?.[editingField], naarWaarde: editValue.trim(),
          });
        }
        setDetails(updated);
      }
    });
    if (ok) {
      setEditingField(null);
      showSuccess('Wijziging opgeslagen');
    }
  }, [plan, editingField, editValue, doelen, doeltypen, details, procesbegeleider, fieldSubmit, showSuccess, rec]);

  // A plan without procesbegeleider gets its first verantwoordelijke as procesbegeleider by default.
  const openPartyModal = useCallback(() => {
    setPartyForm({ name: '', role: '', email: '', phone: '', isPrimary: !procesbegeleider });
    partySubmit.reset();
    setModalOpen(true);
  }, [procesbegeleider, partySubmit]);

  const handleAddParty = useCallback(async () => {
    if (!plan || !partyForm.name.trim() || !partyForm.role) return;
    const ok = await partySubmit.submit(async () => {
      const name = partyForm.name.trim();
      await pdca.betrokkenen.create({
        planUuid: plan.uuid,
        name,
        role: partyForm.role,
        email: partyForm.email.trim() || undefined,
        phone: partyForm.phone.trim() || undefined,
        isPrimary: partyForm.isPrimary,
      });
      rec({ soort: 'BETROKKENE_TOEGEVOEGD', naarWaarde: `${name} (${partyForm.role})` });
      if (partyForm.isPrimary) {
        rec({ soort: 'PROCESBEGELEIDER', samenvoegen: true, vanWaarde: procesbegeleider?.name, naarWaarde: name });
      }
      setBetrokkenen(await pdca.betrokkenen.listByPlan(plan.uuid));
    });
    if (ok) {
      setModalOpen(false);
      showSuccess('Verantwoordelijkheid toegevoegd');
    }
  }, [plan, partyForm, procesbegeleider, partySubmit, showSuccess, rec]);

  const handleDeleteParty = useCallback(async () => {
    const party = partyToDelete;
    if (!party) return;
    const ok = await deleteSubmit.submit(async () => {
      await pdca.betrokkenen.delete(party.id);
      rec({ soort: 'BETROKKENE_VERWIJDERD', naarWaarde: `${party.name} (${party.role})` });
      if (party.isPrimary) rec({ soort: 'PROCESBEGELEIDER', samenvoegen: true, vanWaarde: party.name, naarWaarde: null });
      setBetrokkenen(prev => prev.filter(p => p.id !== party.id));
    });
    if (ok) {
      setPartyToDelete(null);
      showSuccess('Verantwoordelijkheid verwijderd');
    }
  }, [partyToDelete, deleteSubmit, showSuccess, rec]);

  if (loading) {
    return <Loading description="Plan laden..." withOverlay={false} />;
  }

  if (error && !plan) {
    return <InlineNotification kind="error" title="Fout" subtitle={error} lowContrast hideCloseButton />;
  }

  if (!plan) return null;

  const { voortgang } = kpi;
  const voortgangSub = voortgang.beoordeeld === 0
    ? 'Nog niet beoordeeld'
    : 'gaat goed'
      + (voortgang.aandacht ? ` · ${voortgang.aandacht} ${voortgang.aandacht === 1 ? 'vraagt' : 'vragen'} aandacht` : '')
      + (voortgang.nietBeoordeeld ? ` · ${voortgang.nietBeoordeeld} nog niet beoordeeld` : '');
  const volgendeVerlopen = isVerlopen(kpi.volgendeEvaluatie);
  const kpiItems = [
    {
      label: 'Voortgang',
      value: voortgang.beoordeeld === 0 ? '–' : `${voortgang.opKoers}/${voortgang.beoordeeld}`,
      sub: voortgangSub,
      color: KPI_COLORS[0],
    },
    { label: 'Subdoelen', value: String(kpi.lopendeSubdoelen), sub: `van ${kpi.totaalSubdoelen} subdoelen`, color: KPI_COLORS[1] },
    { label: 'Open acties', value: String(kpi.openActies), sub: 'openstaand', color: KPI_COLORS[2] },
    {
      label: 'Volgende evaluatie',
      value: kpi.volgendeEvaluatie ? formatDate(kpi.volgendeEvaluatie) : 'Niet gepland',
      sub: `${volgendeVerlopen ? 'verlopen · ' : ''}${kpi.afgerondeEvaluaties} afgerond`,
      color: KPI_COLORS[3],
      compact: true,
    },
  ];

  const partyHeaders = [
    { key: 'name', header: 'Naam' },
    { key: 'role', header: 'Rol' },
    { key: 'contact', header: 'Contact' },
    { key: 'actions', header: '' },
  ];

  // Responsibilities model: roles from the Open Plan relatietype register;
  // one of them is the plan's procesbegeleider.
  const partyRows = betrokkenen.map(p => ({
    id: p.id,
    name: p.name,
    role: p.role,
    contact: [p.email, p.phone].filter(Boolean).join(' / '),
    actions: p.id,
  }));

  const status = planStatus(plan.status, details?.weergaveStatus);
  const actief = plan.status === 'actief';
  const huidigeWeergave = details?.weergaveStatus || 'Concept';

  return (
    <Theme theme="g10">
      <div className="pdca-container">
        {error && (
          <InlineNotification
            className="pdca-notification"
            kind="error"
            title="Fout"
            subtitle={error}
            lowContrast
            onCloseButtonClick={() => setError(null)}
          />
        )}
        {successMsg && (
          <InlineNotification
            className="pdca-notification"
            kind="success"
            title="Gelukt"
            subtitle={successMsg}
            lowContrast
            onCloseButtonClick={() => setSuccessMsg(null)}
          />
        )}
        {session?.planUuid === plan.uuid && <EvaluatieBanner />}

        {/* Plan header */}
        <div className="pdca-page-header">
          <h1>{plan.titel}</h1>
          <div className="pdca-meta">
            <div className="pdca-status-control">
              <Tag type={status.tag}>{status.label}</Tag>
              <MenuButton label="Planacties" size="sm" kind="ghost" menuAlignment="bottom-start">
                {actief ? <>
                  {planStatusOpties.map(s => (
                    <MenuItem key={s} label={`Status: ${s}`} disabled={s === huidigeWeergave}
                      onClick={() => handleWeergaveStatus(s)} />
                  ))}
                  <MenuItemDivider />
                  <MenuItem label="Plan afronden" onClick={() => openPlanActie('afronden')} />
                  <MenuItem label="Plan afbreken" kind="danger" onClick={() => openPlanActie('afbreken')} />
                </> : (
                  <MenuItem label="Plan heractiveren" onClick={() => openPlanActie('heractiveren')} />
                )}
              </MenuButton>
            </div>
            {procesbegeleider && <Tag size="sm" type="green">Procesbegeleider: {procesbegeleider.name}</Tag>}
            {plan.plantype && <Tag size="sm" type="cool-gray">Dienstverlening: {dienstverleningLabel(plan.plantype.type)}</Tag>}
            {details?.subdoelgroep && <Tag size="sm" type="teal">Subdoelgroep: {details.subdoelgroep}</Tag>}
            {details?.beginPositie && (
              <Tag size="sm" type="purple">Positie: {details.beginPositie}</Tag>
            )}
            {plan.startdatum && <span>Start: {formatDate(plan.startdatum)}</span>}
            {details?.streefEinddatum && <span>Streefdatum: {formatDate(details.streefEinddatum)}</span>}
            {plan.einddatum && <span>Einde: {formatDate(plan.einddatum)}</span>}
          </div>
          {!actief && plan.redenEinde && (
            <p className="pdca-plan-reden">
              {plan.status === 'geannuleerd' ? 'Reden afbreken' : 'Toelichting afronden'}: {plan.redenEinde}
            </p>
          )}
        </div>

        {/* Subject card (via plan.domeinregister URN) */}
        {subject && (
          <div className="pdca-subject-card">
            {subject.kind === 'persoon' ? (
              <>
                <h4>{subject.brp?.naam || 'Persoon'}</h4>
                <div className="pdca-subject-detail">
                  <div className="detail-label">BSN</div>
                  <div className="detail-value">{subject.bsn}</div>
                </div>
                {subject.brp?.geboortedatum && (
                  <div className="pdca-subject-detail">
                    <div className="detail-label">Geboortedatum</div>
                    <div className="detail-value">{formatDate(subject.brp.geboortedatum)}</div>
                  </div>
                )}
                {subject.brp?.adres && (
                  <div className="pdca-subject-detail">
                    <div className="detail-label">Adres</div>
                    <div className="detail-value">{formatAddress(subject.brp.adres)}</div>
                  </div>
                )}
              </>
            ) : (
              <>
                <h4>{subject.object?.naam || 'Object'}</h4>
                <div className="pdca-subject-detail">
                  <div className="detail-label">Object-ID</div>
                  <div className="detail-value">{subject.id}</div>
                </div>
                {subject.object?.type && (
                  <div className="pdca-subject-detail">
                    <div className="detail-label">Type</div>
                    <div className="detail-value">{subject.object.type}</div>
                  </div>
                )}
                {subject.object?.eigenaar && (
                  <div className="pdca-subject-detail">
                    <div className="detail-label">Eigenaar</div>
                    <div className="detail-value">{subject.object.eigenaar}</div>
                  </div>
                )}
                {subject.object?.adres && (
                  <div className="pdca-subject-detail">
                    <div className="detail-label">Adres</div>
                    <div className="detail-value">{formatAddress(subject.object.adres)}</div>
                  </div>
                )}
              </>
            )}
          </div>
        )}

        {/* KPI tiles */}
        <div className="pdca-kpi-grid">
          {kpiItems.map((item, i) => (
            <Tile key={i} className="pdca-kpi-card" style={{ borderTopColor: item.color }}>
              <div className="pdca-kpi-label">{item.label}</div>
              <div className={`pdca-kpi-value${'compact' in item ? ' compact' : ''}`}>{item.value}</div>
              <div className="pdca-kpi-sub">{item.sub}</div>
            </Tile>
          ))}
        </div>

        <div className="pdca-content-grid">
          <div>
            {/* Plan details */}
            <Tile className="pdca-card">
              <Layer>
                <div className="pdca-card-header">
                  <h4>Plangegevens</h4>
                </div>
                <div className="pdca-card-body">
                  {editableFields.map(({ key, label, emptyText, value, display, options, placeholder, hint }) => (
                    <div key={key} className="pdca-info-block">
                      <div className="pdca-info-label">
                        <span>{label}</span>
                        {editingField !== key && !hint && (
                          <Button size="sm" kind="ghost" onClick={() => startEditing(key, value)}>Bewerken</Button>
                        )}
                      </div>
                      {editingField === key ? (
                        <div>
                          {options ? (
                            <Select
                              id={`edit-${key}`}
                              labelText=""
                              hideLabel
                              value={editValue}
                              onChange={(e: React.ChangeEvent<HTMLSelectElement>) => setEditValue(e.target.value)}
                            >
                              <SelectItem value="" text={placeholder ?? '-- Kies uit het register --'} />
                              {options.map(o => <SelectItem key={o.value} value={o.value} text={o.text} />)}
                            </Select>
                          ) : (
                            <TextArea
                              id={`edit-${key}`}
                              labelText=""
                              hideLabel
                              value={editValue}
                              onChange={(e: React.ChangeEvent<HTMLTextAreaElement>) => setEditValue(e.target.value)}
                              rows={3}
                            />
                          )}
                          {fieldSubmit.fout && (
                            <InlineNotification kind="error" title="Opslaan mislukt" subtitle={fieldSubmit.fout}
                              lowContrast hideCloseButton />
                          )}
                          <div className="pdca-edit-actions">
                            <Button size="sm" kind="secondary" disabled={fieldSubmit.saving} onClick={() => setEditingField(null)}>Annuleren</Button>
                            <Button size="sm" kind="primary" onClick={saveField}
                              disabled={fieldSubmit.saving || (!!options && !editValue)}>
                              {fieldSubmit.saving ? 'Opslaan…' : 'Opslaan'}
                            </Button>
                          </div>
                        </div>
                      ) : <>
                        <p className={`pdca-info-value${value ? '' : ' empty'}`}>{(display ?? value) || emptyText}</p>
                        {hint && <p className="pdca-field-hint">{hint}</p>}
                      </>}
                    </div>
                  ))}
                </div>
              </Layer>
            </Tile>

            {/* Contactmomenten that took place */}
            <Tile className="pdca-card">
              <Layer>
                <div className="pdca-card-header">
                  <h4>Recente evaluaties</h4>
                </div>
                <div className="pdca-card-body">
                  {recentEvals.length === 0 ? (
                    <p className="pdca-empty">Nog geen evaluaties</p>
                  ) : (
                    recentEvals.map(cm => {
                      const cmd = cmDetailsByUuid.get(cm.uuid);
                      return (
                        <div key={cm.uuid} className="pdca-eval-item">
                          <Tag type={(cmd && EVAL_TAG_TYPE[cmd.evaluatieType]) as any || 'gray'} size="sm">
                            {cmd ? evalTypeLabel(cmd.evaluatieType) : 'Afgerond'}
                          </Tag>
                          <div className="pdca-eval-info">
                            <div className="pdca-eval-date">{cm.plaatsgevondenOp ? formatDate(cm.plaatsgevondenOp) : 'Geen datum'}</div>
                            <div className={`pdca-eval-summary${cm.inhoud ? '' : ' empty'}`}>
                              {cm.inhoud || 'Geen notitie'}
                            </div>
                          </div>
                        </div>
                      );
                    })
                  )}
                </div>
              </Layer>
            </Tile>
          </div>

          {/* Right column: Verantwoordelijkheden */}
          <div>
            <Tile className="pdca-card">
              <Layer>
                <div className="pdca-card-header">
                  <h4>Verantwoordelijkheden</h4>
                  <Button size="sm" kind="ghost" onClick={openPartyModal}>Toevoegen</Button>
                </div>
                <div className="pdca-card-body">
                  {betrokkenen.length === 0 ? (
                    <p className="pdca-empty">Geen verantwoordelijkheden</p>
                  ) : (
                    <div className="pdca-table-scroll">
                    <DataTable rows={partyRows} headers={partyHeaders}>
                      {({ rows, headers, getTableProps, getHeaderProps, getRowProps }: any) => (
                        <Table {...getTableProps()} size="sm">
                          <TableHead>
                            <TableRow>
                              {headers.map((header: any) => (
                                <TableHeader {...getHeaderProps({ header })} key={header.key}>
                                  {header.header}
                                </TableHeader>
                              ))}
                            </TableRow>
                          </TableHead>
                          <TableBody>
                            {rows.map((row: any) => (
                              <TableRow {...getRowProps({ row })} key={row.id}>
                                {row.cells.map((cell: any) => (
                                  <TableCell key={cell.id}>
                                    {cell.info.header === 'actions' ? (
                                      <Button
                                        size="sm"
                                        kind="danger--ghost"
                                        hasIconOnly
                                        renderIcon={TrashCan}
                                        iconDescription="Verwijderen"
                                        onClick={() => {
                                          deleteSubmit.reset();
                                          setPartyToDelete(betrokkenen.find(b => b.id === cell.value) ?? null);
                                        }}
                                      />
                                    ) : cell.info.header === 'name' && row.id === procesbegeleider?.id ? (
                                      <>{cell.value} <Tag size="sm" type="green">Procesbegeleider</Tag></>
                                    ) : (
                                      cell.value
                                    )}
                                  </TableCell>
                                ))}
                              </TableRow>
                            ))}
                          </TableBody>
                        </Table>
                      )}
                    </DataTable>
                    </div>
                  )}
                </div>
              </Layer>
            </Tile>
          </div>
        </div>

        {/* Add verantwoordelijkheid modal — roles from the Open Plan relatietype register */}
        <Modal
          open={modalOpen}
          modalHeading="Verantwoordelijkheid toevoegen"
          primaryButtonText={partySubmit.saving ? 'Toevoegen…' : 'Toevoegen'}
          secondaryButtonText="Annuleren"
          primaryButtonDisabled={partySubmit.saving || !partyForm.name.trim() || !partyForm.role}
          onRequestClose={() => setModalOpen(false)}
          onRequestSubmit={handleAddParty}
          onSecondarySubmit={() => setModalOpen(false)}
          size="sm"
        >
          <div className="pdca-modal-form">
            {partySubmit.fout && (
              <InlineNotification kind="error" title="Toevoegen mislukt" subtitle={partySubmit.fout} lowContrast hideCloseButton />
            )}
            <TextInput
              id="party-name"
              labelText="Naam *"
              placeholder="Volledige naam"
              value={partyForm.name}
              onChange={(e: React.ChangeEvent<HTMLInputElement>) => setPartyForm(prev => ({ ...prev, name: e.target.value }))}
            />
            <Select
              id="party-role"
              labelText="Rol *"
              value={partyForm.role}
              onChange={(e: React.ChangeEvent<HTMLSelectElement>) => setPartyForm(prev => ({ ...prev, role: e.target.value }))}
            >
              <SelectItem value="" text="Selecteer een rol..." />
              {relatietypen.map(r => (
                <SelectItem key={r.uuid} value={r.naam} text={r.naam} />
              ))}
            </Select>
            <Checkbox
              id="party-primary"
              labelText="Procesbegeleider"
              helperText={procesbegeleider && partyForm.isPrimary ? `Vervangt ${procesbegeleider.name} als procesbegeleider` : undefined}
              checked={partyForm.isPrimary}
              onChange={(_: unknown, { checked }: { checked: boolean }) => setPartyForm(prev => ({ ...prev, isPrimary: checked }))}
            />
            <TextInput
              id="party-email"
              labelText="E-mail"
              placeholder="email@voorbeeld.nl"
              value={partyForm.email}
              onChange={(e: React.ChangeEvent<HTMLInputElement>) => setPartyForm(prev => ({ ...prev, email: e.target.value }))}
            />
            <TextInput
              id="party-phone"
              labelText="Telefoon"
              placeholder="06-12345678"
              value={partyForm.phone}
              onChange={(e: React.ChangeEvent<HTMLInputElement>) => setPartyForm(prev => ({ ...prev, phone: e.target.value }))}
            />
          </div>
        </Modal>

        {/* Remove verantwoordelijkheid */}
        <Modal
          open={!!partyToDelete}
          danger
          modalHeading="Verantwoordelijke verwijderen"
          primaryButtonText={deleteSubmit.saving ? 'Verwijderen…' : 'Verwijderen'}
          secondaryButtonText="Annuleren"
          primaryButtonDisabled={deleteSubmit.saving}
          onRequestClose={() => setPartyToDelete(null)}
          onRequestSubmit={handleDeleteParty}
          size="sm"
        >
          <div className="pdca-modal-form">
            {deleteSubmit.fout && (
              <InlineNotification kind="error" title="Verwijderen mislukt" subtitle={deleteSubmit.fout} lowContrast hideCloseButton />
            )}
            <p>
              <strong>{partyToDelete?.name}</strong> ({partyToDelete?.role}) wordt verwijderd als verantwoordelijke bij dit plan.
              {partyToDelete?.isPrimary && ' Het plan heeft daarna geen procesbegeleider meer.'}
            </p>
          </div>
        </Modal>

        {/* Close or reopen the plan */}
        <Modal
          open={!!planActie}
          danger={planActie === 'afbreken'}
          modalHeading={planActie === 'afronden' ? 'Plan afronden' : planActie === 'afbreken' ? 'Plan afbreken' : 'Plan heractiveren'}
          primaryButtonText={planSubmit.saving ? 'Bezig…'
            : planActie === 'afronden' ? 'Afronden' : planActie === 'afbreken' ? 'Afbreken' : 'Heractiveren'}
          secondaryButtonText="Annuleren"
          primaryButtonDisabled={planSubmit.saving || (planActie === 'afbreken' && !redenEinde.trim())}
          onRequestClose={() => setPlanActie(null)}
          onRequestSubmit={handlePlanActie}
          size="sm"
        >
          <div className="pdca-modal-form">
            {planSubmit.fout && (
              <InlineNotification kind="error" title="Opslaan mislukt" subtitle={planSubmit.fout} lowContrast hideCloseButton />
            )}
            {planActie === 'heractiveren' ? (
              <p>Het plan wordt weer actief, met status {huidigeWeergave}. De einddatum en de reden van beëindigen vervallen.</p>
            ) : <>
              <p>
                {planActie === 'afronden'
                  ? 'Het plan wordt afgerond met de datum van vandaag als einddatum.'
                  : 'Het plan wordt afgebroken met de datum van vandaag als einddatum.'}
              </p>
              <TextArea
                id="plan-reden-einde"
                labelText={planActie === 'afronden' ? 'Toelichting (optioneel)' : 'Reden (verplicht)'}
                placeholder={planActie === 'afronden' ? 'Hoe is het plan afgesloten?' : 'Waarom wordt het plan afgebroken?'}
                value={redenEinde}
                onChange={(e: React.ChangeEvent<HTMLTextAreaElement>) => setRedenEinde(e.target.value)}
                rows={3}
              />
            </>}
          </div>
        </Modal>
      </div>
    </Theme>
  );
}
