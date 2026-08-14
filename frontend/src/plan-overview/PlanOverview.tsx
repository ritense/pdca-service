import React, { useState, useEffect, useCallback, useMemo } from 'react';
import {
  Tile,
  ClickableTile,
  Tag,
  Button,
  TextArea,
  TextInput,
  DataTable,
  Table,
  TableHead,
  TableRow,
  TableHeader,
  TableBody,
  TableCell,
  ProgressBar,
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
  openplan,
  pdca,
  registers,
  urnId,
  Plan,
  PlanDetails,
  Doel,
  DoelDetails,
  Actie,
  Contactmoment,
  ContactmomentDetails,
  Betrokkene,
  RelatieType,
  BrpPersoon,
  ObjectRecord,
} from '../shared/api';
import { statusLabel, evalTypeLabel, formatDate } from '../shared/labels';
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
  const [phases, setPhases] = useState<string[]>([]);
  const [relatietypen, setRelatietypen] = useState<RelatieType[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [successMsg, setSuccessMsg] = useState<string | null>(null);

  const [editingField, setEditingField] = useState<string | null>(null);
  const [editValue, setEditValue] = useState('');
  const [modalOpen, setModalOpen] = useState(false);
  const [partyForm, setPartyForm] = useState({ name: '', role: '', email: '', phone: '' });

  useEffect(() => { resizeIframe(); });

  useEffect(() => {
    onInit(async (ctx: GzacContext) => {
      try {
        // Active plans from Open Plan, matched to this case via the overlay.
        const [plannen, alleDetails] = await Promise.all([
          openplan.plannen.list({ status: 'actief' }),
          pdca.plandetails.list(),
        ]);
        const detailsByUuid = new Map(alleDetails.map(d => [d.planUuid, d]));
        let candidates = plannen;
        if (ctx.caseDefinitionKey) {
          candidates = plannen.filter(p => detailsByUuid.get(p.uuid)?.caseDefinitionKey === ctx.caseDefinitionKey);
        }
        if (candidates.length === 0) {
          setError('Geen actieve plannen gevonden voor deze zaak.');
          setLoading(false);
          return;
        }
        const selected = candidates[0];
        await loadPlanData(selected, detailsByUuid.get(selected.uuid) ?? null);
      } catch (err: any) {
        setError('Kan geen verbinding maken met Open Plan: ' + err.message);
        setLoading(false);
      }
    });
  }, []);

  async function loadPlanData(selected: Plan, selectedDetails: PlanDetails | null) {
    try {
      const [doelenRes, doelDetailsRes, actiesRes, cmRes, cmDetailsRes, betrokkenenRes, rollenRes, subjectRes] =
        await Promise.all([
          openplan.doelen.listByPlan(selected.uuid),
          pdca.doeldetails.listByPlan(selected.uuid).catch(() => [] as DoelDetails[]),
          pdca.acties.listByPlan(selected.uuid).catch(() => [] as Actie[]),
          openplan.contactmomenten.listByPlan(selected.uuid).catch(() => [] as Contactmoment[]),
          pdca.contactmomentdetails.listByPlan(selected.uuid).catch(() => [] as ContactmomentDetails[]),
          pdca.betrokkenen.listByPlan(selected.uuid).catch(() => [] as Betrokkene[]),
          openplan.relatietypen.list().catch(() => [] as RelatieType[]),
          loadSubject(selected),
        ]);

      let phasesRes: string[] = [];
      if (selectedDetails?.caseDefinitionKey) {
        try {
          const cfg = await pdca.phaseConfigs.get(selectedDetails.caseDefinitionKey);
          phasesRes = JSON.parse(cfg.phases || '[]');
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
      setSubject(subjectRes);
      setPhases(phasesRes);
      setLoading(false);
    } catch (err: any) {
      setError('Fout bij laden van plangegevens: ' + err.message);
      setLoading(false);
    }
  }

  const detailsByDoel = useMemo(() => new Map(doelDetails.map(d => [d.doelUuid, d])), [doelDetails]);
  const cmDetailsByUuid = useMemo(() => new Map(cmDetails.map(d => [d.contactmomentUuid, d])), [cmDetails]);

  const kpi = useMemo(() => {
    const scored = doelDetails.filter(d => typeof d.voortgangScore === 'number' && d.voortgangScore! > 0);
    const progressPct = scored.length > 0
      ? Math.round(scored.reduce((sum, d) => sum + d.voortgangScore!, 0) / scored.length)
      : 0;
    const actieveDoelen = doelen.filter(d => d.status === 'actief').length;
    const openActies = acties.filter(a =>
      a.status === 'PLANNED' || a.status === 'IN_PROGRESS' || a.status === 'PENDING_REVIEW'
    ).length;
    const afgerondeEvaluaties = contactmomenten.filter(c => c.status === 'afgerond').length;
    return { progressPct, actieveDoelen, totalDoelen: doelen.length, openActies, afgerondeEvaluaties };
  }, [doelen, doelDetails, acties, contactmomenten]);

  const phaseProgress = useMemo(() => {
    if (doelDetails.length === 0) return [];
    const grouped: Record<string, DoelDetails[]> = {};
    doelDetails.forEach(d => {
      const fase = d.fase || 'Overig';
      (grouped[fase] = grouped[fase] || []).push(d);
    });
    const ordered = phases.length > 0
      ? [...phases.filter(p => grouped[p]), ...Object.keys(grouped).filter(p => !phases.includes(p))]
      : Object.keys(grouped);
    return ordered.map(fase => {
      const scored = grouped[fase].filter(d => typeof d.voortgangScore === 'number' && d.voortgangScore! > 0);
      const pct = scored.length > 0
        ? Math.round(scored.reduce((s, d) => s + d.voortgangScore!, 0) / scored.length)
        : 0;
      return { fase, pct };
    });
  }, [doelDetails, phases]);

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
      setPlan(updated);
      showSuccess('Status gewijzigd naar ' + statusLabel(newStatus));
    } catch (err: any) {
      setError('Status wijzigen mislukt: ' + err.message);
    }
  }, [plan, showSuccess]);

  // notitie lives on the Open Plan plan; situaties in the PDCA overlay.
  const editableFields = [
    { key: 'notitie', label: 'Hoofddoel / notitie', emptyText: 'Geen notitie', value: plan?.notitie },
    { key: 'startSituatie', label: 'Startsituatie', emptyText: 'Niet ingevuld', value: details?.startSituatie },
    { key: 'gewensteSituatie', label: 'Gewenste situatie', emptyText: 'Niet ingevuld', value: details?.gewensteSituatie },
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
        setPlan(updated);
      } else {
        const updated = await pdca.plandetails.upsert(plan.uuid, { [editingField]: editValue.trim() } as any);
        setDetails(updated);
      }
      setEditingField(null);
      showSuccess('Wijziging opgeslagen');
    } catch (err: any) {
      setError('Opslaan mislukt: ' + err.message);
    }
  }, [plan, editingField, editValue, showSuccess]);

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
      });
      setBetrokkenen(await pdca.betrokkenen.listByPlan(plan.uuid));
      setModalOpen(false);
      setPartyForm({ name: '', role: '', email: '', phone: '' });
      showSuccess('Betrokkene toegevoegd');
    } catch (err: any) {
      setError('Toevoegen mislukt: ' + err.message);
    }
  }, [plan, partyForm, showSuccess]);

  const handleDeleteParty = useCallback(async (partyId: string) => {
    try {
      await pdca.betrokkenen.delete(partyId);
      setBetrokkenen(prev => prev.filter(p => p.id !== partyId));
      showSuccess('Betrokkene verwijderd');
    } catch (err: any) {
      setError('Verwijderen mislukt: ' + err.message);
    }
  }, [showSuccess]);

  if (loading) {
    return <Loading description="Plan laden..." withOverlay={false} />;
  }

  if (error && !plan) {
    return <InlineNotification kind="error" title="Fout" subtitle={error} lowContrast hideCloseButton />;
  }

  if (!plan) return null;

  const kpiItems = [
    { label: 'Voortgang', value: `${kpi.progressPct}%`, sub: 'Gemiddelde score van doelen', color: KPI_COLORS[0] },
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

  const partyRows = betrokkenen.map(p => ({
    id: p.id,
    name: p.name + (p.isPrimary ? ' (Primair)' : ''),
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

        {/* Plan header */}
        <div className="pdca-page-header">
          <h1>{plan.titel}</h1>
          <div className="pdca-meta">
            <div className="pdca-status-control">
              <Tag type={STATUS_TAG_TYPE[plan.status] as any || 'gray'}>{statusLabel(plan.status)}</Tag>
              {plan.status === 'actief' && (
                <>
                  <Button size="sm" kind="tertiary" onClick={() => handleStatusChange('afgerond')}>Afronden</Button>
                  <Button size="sm" kind="danger--tertiary" onClick={() => handleStatusChange('geannuleerd')}>Annuleren</Button>
                </>
              )}
              {plan.status !== 'actief' && (
                <Button size="sm" kind="tertiary" onClick={() => handleStatusChange('actief')}>Heractiveren</Button>
              )}
            </div>
            {plan.plantype && <Tag size="sm" type="cool-gray">plantype: {plan.plantype.type}</Tag>}
            {plan.startdatum && <span>Start: {formatDate(plan.startdatum)}</span>}
            {details?.streefEinddatum && <span>Streefdatum: {formatDate(details.streefEinddatum)}</span>}
            {plan.einddatum && <span>Einde: {formatDate(plan.einddatum)}</span>}
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
                {editableFields.map(({ key, label, emptyText, value }) => (
                  <div key={key} className="pdca-info-block">
                    <div className="pdca-info-label">
                      <span>{label}</span>
                      {editingField !== key && (
                        <Button size="sm" kind="ghost" onClick={() => startEditing(key, value)}>Bewerken</Button>
                      )}
                    </div>
                    {editingField === key ? (
                      <div>
                        <TextArea
                          id={`edit-${key}`}
                          labelText=""
                          hideLabel
                          value={editValue}
                          onChange={(e: React.ChangeEvent<HTMLTextAreaElement>) => setEditValue(e.target.value)}
                          rows={3}
                        />
                        <div className="pdca-edit-actions">
                          <Button size="sm" kind="secondary" onClick={() => setEditingField(null)}>Annuleren</Button>
                          <Button size="sm" kind="primary" onClick={saveField}>Opslaan</Button>
                        </div>
                      </div>
                    ) : (
                      <p className={`pdca-info-value${value ? '' : ' empty'}`}>{value || emptyText}</p>
                    )}
                  </div>
                ))}
              </div>
            </Tile>

            {/* Phase progress */}
            {phaseProgress.length > 0 && (
              <Tile className="pdca-card">
                <div className="pdca-card-header">
                  <h4>Voortgang per fase</h4>
                </div>
                <div className="pdca-card-body">
                  {phaseProgress.map(({ fase, pct }) => (
                    <div key={fase} className="pdca-phase-item">
                      <div className="pdca-phase-label">
                        <span className="pdca-phase-name">{fase}</span>
                        <span className="pdca-phase-pct">{pct}%</span>
                      </div>
                      <ProgressBar label="" hideLabel value={pct} max={100} size="small" />
                    </div>
                  ))}
                </div>
              </Tile>
            )}

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

          {/* Right column: Betrokkenen */}
          <div>
            <Tile className="pdca-card">
              <div className="pdca-card-header">
                <h4>Betrokkenen</h4>
                <Button size="sm" kind="ghost" onClick={() => setModalOpen(true)}>Toevoegen</Button>
              </div>
              <div className="pdca-card-body">
                {betrokkenen.length === 0 ? (
                  <p className="pdca-empty">Geen betrokkenen</p>
                ) : (
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
                )}
              </div>
            </Tile>
          </div>
        </div>

        {/* Add betrokkene modal — roles from the Open Plan relatietype register */}
        <Modal
          open={modalOpen}
          modalHeading="Betrokkene toevoegen"
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
              labelText="Rol *"
              value={partyForm.role}
              onChange={(e: React.ChangeEvent<HTMLSelectElement>) => setPartyForm(prev => ({ ...prev, role: e.target.value }))}
            >
              <SelectItem value="" text="Selecteer een rol..." />
              {relatietypen.map(r => (
                <SelectItem key={r.uuid} value={r.naam} text={r.naam} />
              ))}
            </Select>
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
