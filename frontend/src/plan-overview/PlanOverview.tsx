import React, { useState, useEffect, useCallback, useMemo } from 'react';
import {
  Tile,
  ClickableTile,
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
} from '@carbon/react';
import { TrashCan } from '@carbon/react/icons';
import { onInit, resizeIframe, GzacContext } from '../shared/bridge';
import {
  EvaluationSession, EvaluationChangeInput, syncEvaluationPanel, recordEvaluationChange, onEvaluationEvent,
} from '../shared/evaluationSession';
import { EvaluatieBanner } from '../shared/EvaluatieBanner';
import {
  openplan,
  pdca,
  planVoorDossier,
  registers,
  urn,
  urnId,
  Plan,
  PlanDetails,
  Doel,
  DoelDetails,
  DoelType,
  Actie,
  Contactmoment,
  ContactmomentDetails,
  Betrokkene,
  RelatieType,
  BrpPersoon,
  ObjectRecord,
} from '../shared/api';
import {
  statusLabel, evalTypeLabel, formatDate,
  dienstverleningLabel, doelTypeNaam, hoofddoelTypen, isHoofddoelType,
} from '../shared/labels';
import '../shared/styles.css';

type SubjectData =
  | { kind: 'persoon'; brp: BrpPersoon | null; bsn: string }
  | { kind: 'object'; object: ObjectRecord | null; id: string }
  | null;

const STATUS_TAG_TYPE: Record<string, string> = {
  actief: 'blue',
  afgerond: 'green',
  geannuleerd: 'gray',
};

const EVAL_TAG_TYPE: Record<string, string> = {
  INTAKE: 'blue',
  PROGRESS: 'purple',
  EVALUATION: 'green',
  INSPECTION: 'warm-gray',
  CRISIS: 'red',
};

const KPI_COLORS = ['#24a148', '#0f62fe', '#ff832b', '#8a3ffc'];

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
  const [contactmomenten, setContactmomenten] = useState<Contactmoment[]>([]);
  const [cmDetails, setCmDetails] = useState<ContactmomentDetails[]>([]);
  const [betrokkenen, setBetrokkenen] = useState<Betrokkene[]>([]);
  const [subject, setSubject] = useState<SubjectData>(null);
  const [planStatusOpties, setPlanStatusOpties] = useState<string[]>([]);
  const [positieTypen, setPositieTypen] = useState<string[]>([]);
  const [doeltypen, setDoeltypen] = useState<DoelType[]>([]);
  const [relatietypen, setRelatietypen] = useState<RelatieType[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [successMsg, setSuccessMsg] = useState<string | null>(null);

  const [editingField, setEditingField] = useState<string | null>(null);
  const [editValue, setEditValue] = useState('');
  const [modalOpen, setModalOpen] = useState(false);
  const [partyForm, setPartyForm] = useState({ name: '', role: '', email: '', phone: '', isPrimary: false });
  /** The user's running evaluation; while it is on this plan, every change here is recorded in it. */
  const [session, setSession] = useState<EvaluationSession | null>(null);

  useEffect(() => { resizeIframe(); });

  useEffect(() => onEvaluationEvent(event => {
    if (event.type === 'started') setSession(event.session);
    if (event.type === 'completed') setSession(null);
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
      const [doelenRes, doelDetailsRes, actiesRes, cmRes, cmDetailsRes, betrokkenenRes, rollenRes, doeltypenRes, subjectRes] =
        await Promise.all([
          openplan.doelen.listByPlan(selected.uuid),
          pdca.doeldetails.listByPlan(selected.uuid).catch(() => [] as DoelDetails[]),
          pdca.acties.listByPlan(selected.uuid).catch(() => [] as Actie[]),
          openplan.contactmomenten.listByPlan(selected.uuid).catch(() => [] as Contactmoment[]),
          pdca.contactmomentdetails.listByPlan(selected.uuid).catch(() => [] as ContactmomentDetails[]),
          pdca.betrokkenen.listByPlan(selected.uuid).catch(() => [] as Betrokkene[]),
          openplan.relatietypen.list().catch(() => [] as RelatieType[]),
          openplan.doeltypen.list().catch(() => [] as DoelType[]),
          loadSubject(selected),
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
      setPlanStatusOpties(statusOptiesRes.length ? statusOptiesRes : ['Concept', 'Vastgesteld', 'In uitvoering']);
      setPositieTypen(positieTypenRes);
      setLoading(false);
    } catch (err: any) {
      setError('Fout bij laden van plangegevens: ' + err.message);
      setLoading(false);
    }
  }

  const detailsByDoel = useMemo(() => new Map(doelDetails.map(d => [d.doelUuid, d])), [doelDetails]);
  const cmDetailsByUuid = useMemo(() => new Map(cmDetails.map(d => [d.contactmomentUuid, d])), [cmDetails]);

  /** Records a plan change in the running evaluation of this plan (no-op without one). */
  const rec = useCallback((change: Omit<EvaluationChangeInput, 'subjectType' | 'subjectUuid' | 'subjectTitel'>) => {
    if (plan) recordEvaluationChange(plan.uuid, { subjectType: 'PLAN', subjectUuid: plan.uuid, subjectTitel: plan.titel, ...change });
  }, [plan]);

  const kpi = useMemo(() => {
    // Voortgang = how the active subdoelen are going (overlay voortgangsstatus).
    const actieveSubdoelen = doelen.filter(d => {
      const type = doeltypen.find(t => t.uuid === d.doeltype?.uuid);
      return d.status === 'actief' && !(type && isHoofddoelType(type));
    });
    const statusTelling = (status: string) =>
      actieveSubdoelen.filter(d => detailsByDoel.get(d.uuid)?.voortgangStatus === status).length;
    const voortgang = {
      opKoers: statusTelling('OP_KOERS'),
      aandacht: statusTelling('AANDACHT_NODIG') + statusTelling('LOOPT_ACHTER'),
      actief: actieveSubdoelen.length,
    };
    const actieveDoelen = doelen.filter(d => d.status === 'actief').length;
    const openActies = acties.filter(a =>
      a.status === 'PLANNED' || a.status === 'IN_PROGRESS' || a.status === 'PENDING_REVIEW'
    ).length;
    const afgerondeEvaluaties = contactmomenten.filter(c => c.status === 'afgerond').length;
    return { voortgang, actieveDoelen, totalDoelen: doelen.length, openActies, afgerondeEvaluaties };
  }, [doelen, doeltypen, detailsByDoel, acties, contactmomenten]);

  const recentEvals = useMemo(() =>
    [...contactmomenten].sort((a, b) => (b.datum || '').localeCompare(a.datum || '')).slice(0, 3),
  [contactmomenten]);

  const showSuccess = useCallback((msg: string) => {
    setSuccessMsg(msg);
    setTimeout(() => setSuccessMsg(null), 3000);
  }, []);

  const handleStatusChange = useCallback(async (newStatus: 'actief' | 'afgerond' | 'geannuleerd') => {
    if (!plan) return;
    try {
      const body: any = { status: newStatus };
      if (newStatus === 'afgerond') body.einddatum = new Date().toISOString();
      const updated = await openplan.plannen.update(plan.uuid, body);
      rec({ soort: 'PLAN_STATUS', samenvoegen: true, vanWaarde: statusLabel(plan.status), naarWaarde: statusLabel(newStatus) });
      setPlan(updated);
      showSuccess('Status gewijzigd naar ' + statusLabel(newStatus));
    } catch (err: any) {
      setError('Status wijzigen mislukt: ' + err.message);
    }
  }, [plan, showSuccess, rec]);

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

  // notitie/procesbegeleider live on the Open Plan plan; positions and
  // hoofddoel in the PDCA overlay. Positions and hoofddoel are
  // register-driven choices (positietype resp. doeltype register) — no free
  // text; only the explanation for the inwoner remains free text.
  const hoofddoelOpties = hoofddoelTypen(doeltypen);
  const gekozenHoofddoel = doeltypen.find(t => t.uuid === details?.hoofddoelTypeUuid);
  const editableFields: {
    key: string; label: string; emptyText: string; value?: string;
    display?: string; options?: { value: string; text: string }[];
  }[] = [
    { key: 'medewerker', label: 'Procesbegeleider (hoofdverantwoordelijke)', emptyText: 'Nog geen procesbegeleider', value: urnId(plan?.medewerker) || '' },
    {
      key: 'hoofddoelTypeUuid', label: 'Hoofddoel (uit doeltype-register)', emptyText: 'Nog geen hoofddoel gekozen',
      value: details?.hoofddoelTypeUuid || '', display: doelTypeNaam(gekozenHoofddoel),
      options: hoofddoelOpties.map(t => ({ value: t.uuid, text: t.doelType })),
    },
    { key: 'notitie', label: 'Toelichting bij hoofddoel (t.b.v. de inwoner)', emptyText: 'Geen toelichting', value: plan?.notitie },
    {
      key: 'subdoelgroep', label: 'Subdoelgroep (W&P-segmentatie, uit de intake)',
      emptyText: 'Nog niet bepaald', value: details?.subdoelgroep,
    },
    {
      key: 'beginPositie', label: 'Positie (doorgaans uit de intake; wordt later opnieuw bepaald)',
      emptyText: 'Nog niet gezet',
      value: details?.beginPositie, options: positieTypen.map(p => ({ value: p, text: p })),
    },
  ];

  const startEditing = useCallback((key: string, value?: string) => {
    setEditingField(key);
    setEditValue(value || '');
  }, []);

  const saveField = useCallback(async () => {
    if (!plan || !editingField) return;
    try {
      if (editingField === 'notitie') {
        const updated = await openplan.plannen.update(plan.uuid, { notitie: editValue.trim() });
        rec({ soort: 'HOOFDDOEL_TOELICHTING', samenvoegen: true, vanWaarde: plan.notitie, naarWaarde: editValue.trim() });
        setPlan(updated);
      } else if (editingField === 'medewerker') {
        const value = editValue.trim();
        const updated = await openplan.plannen.update(plan.uuid, {
          medewerker: value ? urn('medewerkers', 'medewerker', value.replace(/\s+/g, '.').toLowerCase()) : '',
        });
        rec({ soort: 'PROCESBEGELEIDER', samenvoegen: true, vanWaarde: urnId(plan.medewerker), naarWaarde: urnId(updated.medewerker) });
        setPlan(updated);
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
      setEditingField(null);
      showSuccess('Wijziging opgeslagen');
    } catch (err: any) {
      setError('Opslaan mislukt: ' + err.message);
    }
  }, [plan, editingField, editValue, doelen, doeltypen, details, showSuccess, rec]);

  // Every plan has at least one main responsible: the first responsibility
  // is marked as the main responsible by default.
  const openPartyModal = useCallback(() => {
    setPartyForm({ name: '', role: '', email: '', phone: '', isPrimary: betrokkenen.every(b => !b.isPrimary) });
    setModalOpen(true);
  }, [betrokkenen]);

  const handleAddParty = useCallback(async () => {
    if (!plan) return;
    if (!partyForm.name.trim() || !partyForm.role) {
      setError('Naam en rol zijn verplicht');
      return;
    }
    try {
      await pdca.betrokkenen.create({
        planUuid: plan.uuid,
        name: partyForm.name.trim(),
        role: partyForm.role,
        email: partyForm.email.trim() || undefined,
        phone: partyForm.phone.trim() || undefined,
        isPrimary: partyForm.isPrimary,
      });
      rec({ soort: 'BETROKKENE_TOEGEVOEGD', naarWaarde: `${partyForm.name.trim()} (${partyForm.role})` });
      setBetrokkenen(await pdca.betrokkenen.listByPlan(plan.uuid));
      setModalOpen(false);
      showSuccess('Verantwoordelijkheid toegevoegd');
    } catch (err: any) {
      setError('Toevoegen mislukt: ' + err.message);
    }
  }, [plan, partyForm, showSuccess, rec]);

  const handleDeleteParty = useCallback(async (partyId: string) => {
    try {
      const party = betrokkenen.find(p => p.id === partyId);
      await pdca.betrokkenen.delete(partyId);
      if (party) rec({ soort: 'BETROKKENE_VERWIJDERD', naarWaarde: `${party.name} (${party.role})` });
      setBetrokkenen(prev => prev.filter(p => p.id !== partyId));
      showSuccess('Verantwoordelijkheid verwijderd');
    } catch (err: any) {
      setError('Verwijderen mislukt: ' + err.message);
    }
  }, [betrokkenen, showSuccess, rec]);

  if (loading) {
    return <Loading description="Plan laden..." withOverlay={false} />;
  }

  if (error && !plan) {
    return <InlineNotification kind="error" title="Fout" subtitle={error} lowContrast hideCloseButton />;
  }

  if (!plan) return null;

  const kpiItems = [
    {
      label: 'Voortgang', value: `${kpi.voortgang.opKoers}/${kpi.voortgang.actief}`,
      sub: `actieve subdoelen op koers${kpi.voortgang.aandacht ? ` · ${kpi.voortgang.aandacht} vragen aandacht` : ''}`,
      color: KPI_COLORS[0],
    },
    { label: 'Actieve doelen', value: String(kpi.actieveDoelen), sub: `van ${kpi.totalDoelen} totaal`, color: KPI_COLORS[1] },
    { label: 'Open acties', value: String(kpi.openActies), sub: 'openstaand', color: KPI_COLORS[2] },
    { label: 'Evaluaties', value: String(contactmomenten.length), sub: `${kpi.afgerondeEvaluaties} afgerond`, color: KPI_COLORS[3] },
  ];

  const partyHeaders = [
    { key: 'name', header: 'Naam' },
    { key: 'role', header: 'Rol' },
    { key: 'contact', header: 'Contact' },
    { key: 'actions', header: '' },
  ];

  // Responsibilities model: roles from the Open Plan relatietype register,
  // main responsible(s) marked; the procesbegeleider (plan.medewerker) is
  // additionally always the main responsible at plan level.
  const primaryIds = new Set(betrokkenen.filter(p => p.isPrimary).map(p => p.id));
  const partyRows = betrokkenen.map(p => ({
    id: p.id,
    name: p.name,
    role: p.role,
    contact: [p.email, p.phone].filter(Boolean).join(' / '),
    actions: p.id,
  }));

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
              {plan.status === 'actief' ? (
                <>
                  <Tag type="blue">{details?.weergaveStatus || 'Concept'}</Tag>
                  <Select
                    id="plan-weergave-status"
                    size="sm"
                    labelText=""
                    hideLabel
                    value={details?.weergaveStatus || ''}
                    onChange={(e: React.ChangeEvent<HTMLSelectElement>) => handleWeergaveStatus(e.target.value)}
                  >
                    <SelectItem value="" text="Status wijzigen..." />
                    {planStatusOpties.map(s => <SelectItem key={s} value={s} text={s} />)}
                  </Select>
                  <Button size="sm" kind="tertiary" onClick={() => handleStatusChange('afgerond')}>Afronden</Button>
                  <Button size="sm" kind="danger--tertiary" onClick={() => handleStatusChange('geannuleerd')}>Annuleren</Button>
                </>
              ) : (
                <>
                  <Tag type={STATUS_TAG_TYPE[plan.status] as any || 'gray'}>{statusLabel(plan.status)}</Tag>
                  <Button size="sm" kind="tertiary" onClick={() => handleStatusChange('actief')}>Heractiveren</Button>
                </>
              )}
            </div>
            {urnId(plan.medewerker) && <Tag size="sm" type="green">Procesbegeleider: {urnId(plan.medewerker)}</Tag>}
            {plan.plantype && <Tag size="sm" type="cool-gray">Dienstverlening: {dienstverleningLabel(plan.plantype.type)}</Tag>}
            {details?.subdoelgroep && <Tag size="sm" type="teal">Subdoelgroep: {details.subdoelgroep}</Tag>}
            {details?.beginPositie && (
              <Tag size="sm" type="purple">Positie: {details.beginPositie}</Tag>
            )}
            {plan.startdatum && <span>Start: {formatDate(plan.startdatum)}</span>}
            {details?.streefEinddatum && <span>Streefdatum: {formatDate(details.streefEinddatum)}</span>}
            {plan.einddatum && <span>Einde: {formatDate(plan.einddatum)}</span>}
            {details?.dossierId && <span>Gekoppeld dossier: <code className="pdca-text-wrap" style={{fontSize: 11}}>{details.dossierId}</code></span>}
          </div>
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
            <ClickableTile key={i} className="pdca-kpi-card" style={{ borderTopColor: item.color }}>
              <div className="pdca-kpi-label">{item.label}</div>
              <div className="pdca-kpi-value">{item.value}</div>
              <div className="pdca-kpi-sub">{item.sub}</div>
            </ClickableTile>
          ))}
        </div>

        <div className="pdca-content-grid">
          <div>
            {/* Plan details */}
            <Tile className="pdca-card">
              <div className="pdca-card-header">
                <h4>Plangegevens</h4>
              </div>
              <div className="pdca-card-body">
                {editableFields.map(({ key, label, emptyText, value, display, options }) => (
                  <div key={key} className="pdca-info-block">
                    <div className="pdca-info-label">
                      <span>{label}</span>
                      {editingField !== key && (
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
                            <SelectItem value="" text="-- Kies uit het register --" />
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
                        <div className="pdca-edit-actions">
                          <Button size="sm" kind="secondary" onClick={() => setEditingField(null)}>Annuleren</Button>
                          <Button size="sm" kind="primary" onClick={saveField} disabled={!!options && !editValue}>Opslaan</Button>
                        </div>
                      </div>
                    ) : (
                      <p className={`pdca-info-value${value ? '' : ' empty'}`}>{(display ?? value) || emptyText}</p>
                    )}
                  </div>
                ))}
              </div>
            </Tile>

            {/* Recent contactmomenten */}
            <Tile className="pdca-card">
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
                          {cmd ? evalTypeLabel(cmd.evaluatieType) : statusLabel(cm.status)}
                        </Tag>
                        <div className="pdca-eval-info">
                          <div className="pdca-eval-date">{cm.datum ? formatDate(cm.datum) : 'Geen datum'}</div>
                          <div className={`pdca-eval-summary${cm.notitie ? '' : ' empty'}`}>
                            {cm.notitie || 'Geen notitie'}
                          </div>
                        </div>
                      </div>
                    );
                  })
                )}
              </div>
            </Tile>
          </div>

          {/* Right column: Verantwoordelijkheden */}
          <div>
            <Tile className="pdca-card">
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
                                      onClick={() => handleDeleteParty(cell.value)}
                                    />
                                  ) : cell.info.header === 'name' && primaryIds.has(row.id) ? (
                                    <>{cell.value} <Tag size="sm" type="green">Hoofdverantwoordelijk</Tag></>
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
            </Tile>
          </div>
        </div>

        {/* Add verantwoordelijkheid modal — roles from the Open Plan relatietype register */}
        <Modal
          open={modalOpen}
          modalHeading="Verantwoordelijkheid toevoegen"
          primaryButtonText="Toevoegen"
          secondaryButtonText="Annuleren"
          onRequestClose={() => setModalOpen(false)}
          onRequestSubmit={handleAddParty}
          onSecondarySubmit={() => setModalOpen(false)}
          size="sm"
        >
          <div className="pdca-modal-form">
            <TextInput
              id="party-name"
              labelText="Naam *"
              placeholder="Volledige naam"
              value={partyForm.name}
              onChange={(e: React.ChangeEvent<HTMLInputElement>) => setPartyForm(prev => ({ ...prev, name: e.target.value }))}
            />
            <Select
              id="party-role"
              labelText="Rol * (uit relatietype-register)"
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
              labelText="Hoofdverantwoordelijke"
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
      </div>
    </Theme>
  );
}
