import React, { useState, useEffect, useCallback } from 'react';
import {
  Theme, Button, Tag, Modal, TextInput, Checkbox, Loading, InlineNotification, Tile,
  Select, SelectItem, ContentSwitcher, Switch,
} from '@carbon/react';
import { Add, Edit, TrashCan, Draggable, Close } from '@carbon/react/icons';
import { onInit, resizeIframe } from '../shared/bridge';
import {
  openplan, pdca, PhaseConfig, DoelType, ActieBouwblokKoppeling, BouwblokProces, PluginConfiguratie,
} from '../shared/api';
import { evalTypeLabel, subdoelTypen, hoofddoelTypen, doelTypeNaam } from '../shared/labels';

const ALL_EVAL_TYPES = ['INTAKE', 'PROGRESS', 'EVALUATION', 'INSPECTION', 'CRISIS'];

/**
 * Prefill fields of the create-plan task form; order and keys mirror
 * IntakeService.DEFAULT_PREFILL_MAPPING in the backend.
 */
const PREFILL_VELDEN: { veld: string; label: string }[] = [
  { veld: 'subjectIdPersoon', label: 'BSN (persoonssubject)' },
  { veld: 'subjectIdObject', label: 'Object-id (objectsubject)' },
  { veld: 'naam', label: 'Naam subject' },
  { veld: 'objectNaam', label: 'Objectnaam' },
  { veld: 'titel', label: 'Plantitel' },
  { veld: 'notitie', label: 'Toelichting hoofddoel' },
  { veld: 'dienstverlening', label: 'Dienstverlening' },
  { veld: 'hoofddoel', label: 'Hoofddoel' },
  { veld: 'subdoelgroep', label: 'Subdoelgroep' },
  { veld: 'weergaveStatus', label: 'Planstatus (weergave)' },
  { veld: 'beginPositie', label: 'Positie' },
  { veld: 'startdatum', label: 'Startdatum' },
  { veld: 'streefEinddatum', label: 'Streefeinddatum' },
  { veld: 'doelen', label: 'Doelen (lijst)' },
  { veld: 'instrumenten', label: 'Instrumenten (lijst)' },
  { veld: 'contactmomenten', label: 'Contactmomenten (lijst)' },
  { veld: 'betrokkenen', label: 'Betrokkenen (lijst)' },
];

const STANDAARD_MAPPING: Record<string, string> = {
  subjectIdPersoon: '/bsn', subjectIdObject: '/objectId', naam: '/naam', objectNaam: '/objectNaam',
  titel: '/planTitel', notitie: '/planNotitie', dienstverlening: '/dienstverlening',
  hoofddoel: '/hoofddoel', subdoelgroep: '/subdoelgroep',
  weergaveStatus: '/weergaveStatus', beginPositie: '/beginPositie',
  startdatum: '/startdatum', streefEinddatum: '/streefEinddatum',
  doelen: '/doelen', instrumenten: '/instrumenten', contactmomenten: '/contactmomenten',
  betrokkenen: '/betrokkenen',
};

const veldLabel = (veld: string) => PREFILL_VELDEN.find(v => v.veld === veld)?.label ?? veld;

const sectionTitleStyle: React.CSSProperties = {
  fontSize: 13, fontWeight: 600, margin: '20px 0 4px', color: 'var(--cds-text-primary)',
};
const hintStyle: React.CSSProperties = { fontSize: 12, color: 'var(--cds-text-helper)', marginBottom: 8 };

/**
 * Sortable list with drag & drop; items are added via a text field —
 * never comma-separated strings.
 */
function SortableList({ items, onChange, emptyText, tagType }: {
  items: string[];
  onChange: (items: string[]) => void;
  emptyText: string;
  tagType: string;
}) {
  const [sleepIndex, setSleepIndex] = useState<number | null>(null);
  const [overIndex, setOverIndex] = useState<number | null>(null);
  const [nieuw, setNieuw] = useState('');

  const verplaats = (van: number, naar: number) => {
    if (van === naar) return;
    const kopie = [...items];
    const [item] = kopie.splice(van, 1);
    kopie.splice(naar, 0, item);
    onChange(kopie);
  };

  const voegToe = (waarde: string) => {
    const schoon = waarde.trim();
    if (!schoon || items.includes(schoon)) return;
    onChange([...items, schoon]);
    setNieuw('');
  };

  return (
    <div>
      {items.length === 0 && <div style={{ ...hintStyle, fontStyle: 'italic' }}>{emptyText}</div>}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 2, marginBottom: 8 }}>
        {items.map((item, i) => (
          <div key={item} draggable
            onDragStart={() => setSleepIndex(i)}
            onDragOver={e => { e.preventDefault(); setOverIndex(i); }}
            onDragLeave={() => setOverIndex(cur => (cur === i ? null : cur))}
            onDrop={() => { if (sleepIndex !== null) verplaats(sleepIndex, i); setSleepIndex(null); setOverIndex(null); }}
            onDragEnd={() => { setSleepIndex(null); setOverIndex(null); }}
            style={{
              display: 'flex', alignItems: 'center', gap: 8, padding: '4px 8px',
              background: 'var(--cds-layer-01, #f4f4f4)', cursor: 'grab',
              border: '1px solid var(--cds-border-subtle)',
              borderTop: overIndex === i && sleepIndex !== null && sleepIndex !== i
                ? '2px solid var(--cds-border-interactive, #0f62fe)' : '1px solid var(--cds-border-subtle)',
              opacity: sleepIndex === i ? 0.5 : 1,
            }}>
            <Draggable size={16} style={{ color: 'var(--cds-icon-secondary)', flexShrink: 0 }} />
            <span style={{ fontSize: 12, color: 'var(--cds-text-helper)', width: 16 }}>{i + 1}.</span>
            <span style={{ flex: 1, fontSize: 14 }}>{item}</span>
            <Button size="sm" kind="ghost" renderIcon={Close} iconDescription="Verwijderen" hasIconOnly
              onClick={() => onChange(items.filter(x => x !== item))} />
          </div>
        ))}
      </div>
      <div style={{ display: 'flex', gap: 8, alignItems: 'flex-end' }}>
        <div style={{ flex: 1 }}>
          <TextInput id={`nieuw-${tagType}`} labelText="" hideLabel size="sm" value={nieuw}
            placeholder="Nieuwe waarde…"
            onChange={(e: any) => setNieuw(e.target.value)}
            onKeyDown={(e: any) => { if (e.key === 'Enter') { e.preventDefault(); voegToe(nieuw); } }} />
        </div>
        <Button size="sm" kind="tertiary" renderIcon={Add} onClick={() => voegToe(nieuw)}
          disabled={!nieuw.trim()}>Toevoegen</Button>
      </div>
    </div>
  );
}

interface MappingRij { pad: string; veld: string; }

/**
 * Prefill mapping as a source→target table, modelled after the GZAC
 * building block input/output mapping: document path → plan field, with an
 * add button that disables once all fields are mapped.
 */
type SubdoelMappingRij = { hoofddoel: string; subdoelen: string[] };

/**
 * Which subdoeltypen may be chosen under which hoofddoeltype (per case type).
 * Both sides come from the doeltype register, so the mapping is picked rather
 * than typed — it is keyed by name and a typo would silently scope nothing.
 * A hoofddoel without a row keeps all subdoelen available.
 */
function SubdoelMappingEditor({ rijen, onChange, doeltypen }: {
  rijen: SubdoelMappingRij[];
  onChange: (rijen: SubdoelMappingRij[]) => void;
  doeltypen: DoelType[];
}) {
  const hoofddoelen = hoofddoelTypen(doeltypen);
  const subdoelen = subdoelTypen(doeltypen);
  const gebruikt = rijen.map(r => r.hoofddoel);
  const vrij = hoofddoelen.filter(h => !gebruikt.includes(doelTypeNaam(h)));

  const wijzig = (i: number, deel: Partial<SubdoelMappingRij>) =>
    onChange(rijen.map((r, ri) => (ri === i ? { ...r, ...deel } : r)));

  const toggle = (i: number, naam: string) => {
    const huidig = rijen[i].subdoelen;
    wijzig(i, { subdoelen: huidig.includes(naam) ? huidig.filter(n => n !== naam) : [...huidig, naam] });
  };

  return (
    <div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 12, marginBottom: 8 }}>
        {rijen.map((rij, i) => (
          <div key={i} style={{ border: '1px solid var(--cds-border-subtle)', padding: 12 }}>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 40px', gap: 8, alignItems: 'center' }}>
              <Select id={`subdoelmap-hoofddoel-${i}`} labelText="" hideLabel size="sm" value={rij.hoofddoel}
                onChange={(e: any) => wijzig(i, { hoofddoel: e.target.value })}>
                {!rij.hoofddoel && <SelectItem value="" text="-- kies hoofddoel --" />}
                {hoofddoelen
                  .filter(h => doelTypeNaam(h) === rij.hoofddoel || !gebruikt.includes(doelTypeNaam(h)))
                  .map(h => <SelectItem key={h.uuid} value={doelTypeNaam(h)} text={doelTypeNaam(h)} />)}
              </Select>
              <Button size="sm" kind="danger--ghost" renderIcon={TrashCan} iconDescription="Verwijderen" hasIconOnly
                onClick={() => onChange(rijen.filter((_, ri) => ri !== i))} />
            </div>
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: 16, marginTop: 8 }}>
              {subdoelen.map(sd => (
                <Checkbox key={sd.uuid} id={`subdoelmap-${i}-${sd.uuid}`} labelText={doelTypeNaam(sd)}
                  checked={rij.subdoelen.includes(doelTypeNaam(sd))}
                  onChange={() => toggle(i, doelTypeNaam(sd))} />
              ))}
              {subdoelen.length === 0 && (
                <span style={hintStyle}>Geen subdoeltypen in het register.</span>
              )}
            </div>
            {rij.subdoelen.length === 0 && (
              <p style={{ ...hintStyle, marginTop: 6 }}>
                Niets aangevinkt — onder dit hoofddoel blijven alle subdoelen kiesbaar.
              </p>
            )}
          </div>
        ))}
      </div>
      <Button size="sm" kind="tertiary" renderIcon={Add} disabled={vrij.length === 0}
        onClick={() => onChange([...rijen, { hoofddoel: doelTypeNaam(vrij[0]), subdoelen: [] }])}>
        Hoofddoel toevoegen
      </Button>
      {rijen.length > 0 && (
        <Button size="sm" kind="ghost" onClick={() => onChange([])}>Leegmaken</Button>
      )}
    </div>
  );
}

function MappingEditor({ rijen, onChange, padSuggesties }: {
  rijen: MappingRij[];
  onChange: (rijen: MappingRij[]) => void;
  padSuggesties: string[];
}) {
  const gebruikteVelden = rijen.map(r => r.veld);
  const vrijeVelden = PREFILL_VELDEN.filter(v => !gebruikteVelden.includes(v.veld));

  const wijzig = (i: number, deel: Partial<MappingRij>) =>
    onChange(rijen.map((r, ri) => (ri === i ? { ...r, ...deel } : r)));

  return (
    <div>
      {/* Path suggestions from this case type's GZAC document schema. */}
      <datalist id="document-paden">
        {padSuggesties.map(p => <option key={p} value={p} />)}
      </datalist>
      {rijen.length > 0 && (
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 24px 1fr 40px', gap: 8, alignItems: 'center', marginBottom: 4 }}>
          <span style={{ fontSize: 12, fontWeight: 600, color: 'var(--cds-text-secondary)' }}>
            Documentpad (dossier-content{padSuggesties.length > 0 ? ', uit het documentschema' : ''})
          </span>
          <span />
          <span style={{ fontSize: 12, fontWeight: 600, color: 'var(--cds-text-secondary)' }}>Planveld</span>
          <span />
        </div>
      )}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 6, marginBottom: 8 }}>
        {rijen.map((rij, i) => (
          <div key={i} style={{ display: 'grid', gridTemplateColumns: '1fr 24px 1fr 40px', gap: 8, alignItems: 'center' }}>
            <TextInput id={`pad-${i}`} labelText="" hideLabel size="sm" value={rij.pad}
              list="document-paden"
              placeholder="/planTitel" onChange={(e: any) => wijzig(i, { pad: e.target.value })} />
            <span style={{ textAlign: 'center', color: 'var(--cds-text-helper)' }}>→</span>
            <Select id={`veld-${i}`} labelText="" hideLabel size="sm" value={rij.veld}
              onChange={(e: any) => wijzig(i, { veld: e.target.value })}>
              {!rij.veld && <SelectItem value="" text="-- kies planveld --" />}
              {PREFILL_VELDEN
                .filter(v => v.veld === rij.veld || !gebruikteVelden.includes(v.veld))
                .map(v => <SelectItem key={v.veld} value={v.veld} text={v.label} />)}
            </Select>
            <Button size="sm" kind="danger--ghost" renderIcon={TrashCan} iconDescription="Verwijderen" hasIconOnly
              onClick={() => onChange(rijen.filter((_, ri) => ri !== i))} />
          </div>
        ))}
      </div>
      <div style={{ display: 'flex', gap: 8 }}>
        <Button size="sm" kind="tertiary" renderIcon={Add} disabled={vrijeVelden.length === 0}
          onClick={() => onChange([...rijen, { pad: '', veld: vrijeVelden[0]?.veld ?? '' }])}>
          Mapping toevoegen
        </Button>
        <Button size="sm" kind="ghost"
          onClick={() => onChange(PREFILL_VELDEN.map(v => ({ pad: STANDAARD_MAPPING[v.veld], veld: v.veld })))}>
          Standaardpaden invullen
        </Button>
        {rijen.length > 0 && (
          <Button size="sm" kind="ghost" onClick={() => onChange([])}>Leegmaken</Button>
        )}
      </div>
      <p style={{ ...hintStyle, marginTop: 6 }}>
        Leeg = de standaardpaden. Bepaalt hoe het create-plan taakformulier uit de dossier-content
        (intake) voorgevuld wordt.
      </p>
    </div>
  );
}

export function PdcaAdmin() {
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [configs, setConfigs] = useState<PhaseConfig[]>([]);
  const [doeltypen, setDoeltypen] = useState<DoelType[]>([]);
  const [modal, setModal] = useState(false);
  const [editKey, setEditKey] = useState<string | null>(null);
  const [formKey, setFormKey] = useState('');
  const [formPlanStatussen, setFormPlanStatussen] = useState<string[]>([]);
  const [formPositieTypen, setFormPositieTypen] = useState<string[]>([]);
  const [formSubdoelMapping, setFormSubdoelMapping] = useState<SubdoelMappingRij[]>([]);
  const [formMapping, setFormMapping] = useState<MappingRij[]>([]);
  const [formIsIntake, setFormIsIntake] = useState(false);
  const [formPlanCaseDefKey, setFormPlanCaseDefKey] = useState('');
  const [formEvalTypes, setFormEvalTypes] = useState<Set<string>>(new Set());
  const [padSuggesties, setPadSuggesties] = useState<string[]>([]);
  const [gzacDossiertypes, setGzacDossiertypes] = useState<string[] | null>(null);
  const [toast, setToast] = useState<string | null>(null);

  /** Path suggestions for the mapping come from this case type's GZAC document schema. */
  const loadPathSuggestions = useCallback((key: string) => {
    if (!key) { setPadSuggesties([]); return; }
    pdca.caseDefinitions.documentPaths(key).then(setPadSuggesties).catch(() => setPadSuggesties([]));
  }, []);

  useEffect(() => { onInit(() => loadData()); }, []);
  useEffect(() => { resizeIframe(); }, [loading, configs, modal, formPlanStatussen, formPositieTypen, formSubdoelMapping, formMapping]);

  const loadData = useCallback(async () => {
    try {
      setLoading(true);
      const [data, dossiertypes, dt] = await Promise.all([
        pdca.phaseConfigs.list(),
        // Case types from GZAC; null = list unavailable (grant not yet
        // accepted or GZAC down) -> free entry as fallback.
        pdca.caseDefinitions.list().catch(() => null),
        // Doeltype register: the doeltype selection of the bouwblok koppelingen.
        openplan.doeltypen.list().catch(() => [] as DoelType[]),
      ]);
      setConfigs(data);
      setGzacDossiertypes(dossiertypes);
      setDoeltypen(dt);
      setLoading(false);
    } catch (e: any) { setError(e.message); setLoading(false); }
  }, []);

  const parseList = (raw?: string | null): string[] => {
    try { return JSON.parse(raw || '[]'); } catch { return []; }
  };

  const parseSubdoelMapping = (raw?: string | null): SubdoelMappingRij[] => {
    if (!raw?.trim()) return [];
    try {
      const obj = JSON.parse(raw) as Record<string, string[]>;
      return Object.entries(obj).map(([hoofddoel, subdoelen]) => ({ hoofddoel, subdoelen: subdoelen ?? [] }));
    } catch { return []; }
  };

  const parseMapping = (raw?: string | null): MappingRij[] => {
    try {
      const obj = JSON.parse(raw || '{}');
      return Object.entries(obj).map(([veld, pad]) => ({ veld, pad: String(pad) }));
    } catch { return []; }
  };

  const openCreate = () => {
    setEditKey(null); setFormKey('');
    setFormPlanStatussen(['Concept', 'Vastgesteld', 'In uitvoering']);
    setFormPositieTypen([]);
    setFormSubdoelMapping([]);
    setFormMapping([]);
    setFormIsIntake(false);
    setFormPlanCaseDefKey('');
    setFormEvalTypes(new Set(['INTAKE', 'PROGRESS', 'EVALUATION']));
    setPadSuggesties([]);
    setModal(true);
  };

  const openEdit = (cfg: PhaseConfig) => {
    setEditKey(cfg.caseDefinitionKey);
    setFormKey(cfg.caseDefinitionKey);
    setFormPlanStatussen(parseList(cfg.planStatussen));
    setFormPositieTypen(parseList(cfg.positieTypen));
    setFormSubdoelMapping(parseSubdoelMapping(cfg.subdoelMapping));
    setFormMapping(parseMapping(cfg.prefillMapping));
    setFormIsIntake(!!cfg.planCaseDefinitionKey);
    setFormPlanCaseDefKey(cfg.planCaseDefinitionKey || '');
    setFormEvalTypes(new Set(JSON.parse(cfg.evaluationTypes)));
    loadPathSuggestions(cfg.caseDefinitionKey);
    setModal(true);
  };

  const handleSave = async () => {
    if (!formKey) { alert('Vul een dossiertype in'); return; }
    if (formIsIntake && !formPlanCaseDefKey.trim()) { alert('Kies het plan-dossiertype waar deze intake een plan voor aanmaakt'); return; }
    const subdoelRijen = formSubdoelMapping.filter(r => r.hoofddoel && r.subdoelen.length > 0);
    const subdoelMap: Record<string, string[]> = {};
    subdoelRijen.forEach(r => { subdoelMap[r.hoofddoel] = r.subdoelen; });
    const mappingRijen = formMapping.filter(r => r.veld && r.pad.trim());
    const mapping: Record<string, string> = {};
    mappingRijen.forEach(r => { mapping[r.veld] = r.pad.trim(); });
    const payload = {
      caseDefinitionKey: formKey,
      planStatussen: JSON.stringify(formPlanStatussen),
      positieTypen: JSON.stringify(formPositieTypen),
      subdoelMapping: subdoelRijen.length > 0 ? JSON.stringify(subdoelMap) : null,
      evaluationTypes: JSON.stringify(Array.from(formEvalTypes)),
      prefillMapping: mappingRijen.length > 0 ? JSON.stringify(mapping) : null,
      planCaseDefinitionKey: formIsIntake ? formPlanCaseDefKey.trim() : null,
    };
    try {
      if (editKey) {
        await pdca.phaseConfigs.update(editKey, payload);
      } else {
        await pdca.phaseConfigs.create(payload);
      }
      setModal(false); showToast('Configuratie opgeslagen'); await loadData();
    } catch (e: any) { alert('Fout: ' + e.message); }
  };

  const handleDelete = async (key: string) => {
    if (!confirm(`Configuratie voor "${key}" verwijderen?`)) return;
    try { await pdca.phaseConfigs.delete(key); showToast('Configuratie verwijderd'); await loadData(); }
    catch (e: any) { alert('Fout: ' + e.message); }
  };

  const showToast = (msg: string) => { setToast(msg); setTimeout(() => setToast(null), 3000); };

  const toggleEvalType = (type: string) => {
    setFormEvalTypes(prev => {
      const next = new Set(prev);
      next.has(type) ? next.delete(type) : next.add(type);
      return next;
    });
  };

  if (loading) return <Theme theme="g10"><div className="pdca-container"><Loading withOverlay={false} /></div></Theme>;
  if (error) return <Theme theme="g10"><div className="pdca-container"><InlineNotification kind="error" title={error} /></div></Theme>;

  const thStyle: React.CSSProperties = {
    textAlign: 'left', padding: '12px 16px', borderBottom: '2px solid var(--cds-border-subtle)',
    fontSize: 12, fontWeight: 600, color: 'var(--cds-text-secondary)', textTransform: 'uppercase', letterSpacing: 0.5,
  };

  return (
    <Theme theme="g10">
      <div className="pdca-container">
        {toast && <InlineNotification kind="success" title={toast} style={{marginBottom: 16}} onClose={() => setToast(null)} />}

        <div style={{display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 24}}>
          <h1 style={{fontSize: '1.75rem', fontWeight: 600}}>PDCA Beheer — Dossierconfiguratie</h1>
          <Button renderIcon={Add} onClick={openCreate}>Nieuwe configuratie</Button>
        </div>

        {configs.length === 0 ? (
          <div className="pdca-empty">
            <p>Geen configuraties gevonden. Maak een nieuwe aan.</p>
          </div>
        ) : (
          <Tile>
            <table style={{width: '100%', borderCollapse: 'collapse'}}>
              <thead>
                <tr>
                  <th style={thStyle}>Dossiertype</th>
                  <th style={thStyle}>Planstatussen</th>
                  <th style={thStyle}>Positietypen</th>
                  <th style={thStyle}>Subdoelen per hoofddoel</th>
                  <th style={thStyle}>Evaluatietypen</th>
                  <th style={{...thStyle, textAlign: 'right'}}>Acties</th>
                </tr>
              </thead>
              <tbody>
                {configs.map(cfg => {
                  const ps = parseList(cfg.planStatussen);
                  const pt = parseList(cfg.positieTypen);
                  const sm = parseSubdoelMapping(cfg.subdoelMapping);
                  const et = JSON.parse(cfg.evaluationTypes) as string[];
                  return (
                    <tr key={cfg.caseDefinitionKey} style={{borderBottom: '1px solid var(--cds-border-subtle)'}}>
                      <td style={{padding: '12px 16px', fontWeight: 500}}>
                        {cfg.caseDefinitionKey}
                        {cfg.planCaseDefinitionKey && (
                          <div style={{fontSize: 12, fontWeight: 400, color: 'var(--cds-text-helper)'}}>
                            intake → {cfg.planCaseDefinitionKey}
                          </div>
                        )}
                      </td>
                      <td style={{padding: '12px 16px'}}>
                        <div style={{display: 'flex', gap: 4, flexWrap: 'wrap'}}>
                          {ps.map(p => <Tag key={p} size="sm" type="teal">{p}</Tag>)}
                        </div>
                      </td>
                      <td style={{padding: '12px 16px'}}>
                        <div style={{display: 'flex', gap: 4, flexWrap: 'wrap'}}>
                          {pt.length === 0 ? <span style={{fontSize: 12, color: 'var(--cds-text-helper)', fontStyle: 'italic'}}>geen (vrije invoer)</span>
                            : pt.map(p => <Tag key={p} size="sm" type="cyan">{p}</Tag>)}
                        </div>
                      </td>
                      <td style={{padding: '12px 16px'}}>
                        <div style={{display: 'flex', gap: 4, flexWrap: 'wrap'}}>
                          {sm.length === 0 ? <span style={{fontSize: 12, color: 'var(--cds-text-helper)', fontStyle: 'italic'}}>geen (alle subdoelen)</span>
                            : sm.map(r => (
                              <Tag key={r.hoofddoel} size="sm" type="magenta" title={r.subdoelen.join(', ')}>
                                {r.hoofddoel} ({r.subdoelen.length})
                              </Tag>
                            ))}
                        </div>
                      </td>
                      <td style={{padding: '12px 16px'}}>
                        <div style={{display: 'flex', gap: 4, flexWrap: 'wrap'}}>
                          {et.map(t => <Tag key={t} size="sm" type="purple">{evalTypeLabel(t)}</Tag>)}
                        </div>
                      </td>
                      <td style={{padding: '12px 16px', textAlign: 'right'}}>
                        <Button size="sm" kind="ghost" renderIcon={Edit} iconDescription="Bewerken" hasIconOnly onClick={() => openEdit(cfg)} />
                        <Button size="sm" kind="danger--ghost" renderIcon={TrashCan} iconDescription="Verwijderen" hasIconOnly onClick={() => handleDelete(cfg.caseDefinitionKey)} />
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </Tile>
        )}

        <BouwblokkenBeheer soort="ACTIE" vorm="BOUWBLOK"
          planConfigs={configs.filter(c => !c.planCaseDefinitionKey)}
          doeltypen={doeltypen}
          onToast={showToast}
        />

        <BouwblokkenBeheer soort="PRODUCT" vorm="BOUWBLOK"
          planConfigs={configs.filter(c => !c.planCaseDefinitionKey)}
          doeltypen={doeltypen}
          onToast={showToast}
        />

        <BouwblokkenBeheer soort="PRODUCT" vorm="DOSSIER"
          planConfigs={configs.filter(c => !c.planCaseDefinitionKey)}
          doeltypen={doeltypen}
          onToast={showToast}
        />

        <Modal open={modal} size="lg" modalHeading={editKey ? 'Configuratie bewerken' : 'Nieuwe configuratie'}
          primaryButtonText="Opslaan" secondaryButtonText="Annuleren"
          onRequestClose={() => setModal(false)} onRequestSubmit={handleSave}>
          <div className="pdca-modal-form">
            {editKey ? (
              /* Editing: the case type is the configuration's identity and
                 dus niet te wijzigen — wel zichtbaar, als disabled dropdown. */
              <Select id="cfg-key" labelText="Dossiertype" disabled
                helperText="Het dossiertype van een bestaande configuratie is niet te wijzigen."
                value={formKey} onChange={() => undefined}>
                <SelectItem value={formKey} text={formKey} />
              </Select>
            ) : gzacDossiertypes !== null ? (
              <Select id="cfg-key" labelText="Dossiertype"
                helperText="Dossierdefinities uit GZAC; al geconfigureerde dossiertypes worden niet getoond."
                value={formKey}
                onChange={(e: any) => { setFormKey(e.target.value); loadPathSuggestions(e.target.value); }}>
                <SelectItem value="" text="— kies dossiertype —" />
                {gzacDossiertypes
                  .filter(k => !configs.some(c => c.caseDefinitionKey === k))
                  .map(k => <SelectItem key={k} value={k} text={k} />)}
              </Select>
            ) : (
              <TextInput id="cfg-key" labelText="Dossiertype (case definition key)"
                value={formKey} onChange={(e: any) => setFormKey(e.target.value)}
                onBlur={() => loadPathSuggestions(formKey.trim())}
                placeholder="bijv. jeugdzorg-traject"
                helperText="De dossiertypelijst van GZAC is niet beschikbaar (accepteer de nieuwe manifest-grant in de pluginconfiguratie); vul de key handmatig in." />
            )}

            {/* The context choice determines which configuration applies: an
                plan-dossiertype draagt de PDCA-inrichting, een intake-
                dossiertype alleen de prefill mapping + doelverwijzing. */}
            <div style={{margin: '4px 0 16px'}}>
              <p style={{fontSize: 12, fontWeight: 600, color: 'var(--cds-text-secondary)', marginBottom: 4}}>Soort dossiertype</p>
              <ContentSwitcher selectedIndex={formIsIntake ? 1 : 0} size="sm"
                onChange={({ index }: any) => setFormIsIntake(index === 1)}>
                <Switch name="plan" text="Plan-dossiertype" />
                <Switch name="intake" text="Intake-dossiertype" />
              </ContentSwitcher>
              <p style={{...hintStyle, marginTop: 4}}>
                {formIsIntake
                  ? 'Intake: dit dossier verzamelt de input; het create-plan taakformulier maakt er een plan mét eigen plan-dossier van.'
                  : 'Plan-dossier: dit dossier ís 1:1 het plan en draagt de PDCA-inrichting en de tabs.'}
              </p>
            </div>

            {formIsIntake && (
              <Select id="cfg-plancasedefkey" labelText="Maakt plannen voor dossiertype"
                helperText="Het plan-dossiertype waarvan het create-plan taakformulier het plan-dossier aanmaakt. Keuze uit de geconfigureerde plan-dossiertypes."
                value={formPlanCaseDefKey} onChange={(e: any) => setFormPlanCaseDefKey(e.target.value)}>
                <SelectItem value="" text="— kies plan-dossiertype —" />
                {configs.filter(c => c.caseDefinitionKey !== formKey && !c.planCaseDefinitionKey)
                  .map(c => <SelectItem key={c.caseDefinitionKey} value={c.caseDefinitionKey} text={c.caseDefinitionKey} />)}
              </Select>
            )}

            {!formIsIntake && (
              <>
                <h5 style={sectionTitleStyle}>Planstatussen</h5>
                <p style={hintStyle}>
                  Eigen invoer: er bestaat geen statusregister — deze lijst ís de bron. Sleep voor de volgorde.
                </p>
                <SortableList items={formPlanStatussen} onChange={setFormPlanStatussen}
                  emptyText="Geen statussen — vrije invoer in het planoverzicht." tagType="teal" />

                <h5 style={sectionTitleStyle}>Positietypen</h5>
                <p style={hintStyle}>
                  Eigen invoer: deze lijst ís het positieregister van dit domein — de positie van een plan
                  moet hieruit komen. Sleep voor de volgorde (laag → hoog).
                </p>
                <SortableList items={formPositieTypen} onChange={setFormPositieTypen}
                  emptyText="Geen positieregister — posities zijn dan vrije invoer." tagType="cyan" />

                <h5 style={sectionTitleStyle}>Subdoelen per hoofddoel</h5>
                <p style={hintStyle}>
                  Welke subdoelen kiesbaar zijn onder welk hoofddoel. Beide komen uit het doeltype-register;
                  het register kent de relatie zelf niet, dus die staat hier. Een hoofddoel zonder rij — of
                  met een rij zonder vinkjes — houdt alle subdoelen beschikbaar.
                </p>
                <SubdoelMappingEditor rijen={formSubdoelMapping} onChange={setFormSubdoelMapping}
                  doeltypen={doeltypen} />

                <h5 style={sectionTitleStyle}>Evaluatietypen</h5>
                <p style={hintStyle}>Vaste typen uit de PDCA-app (geen register).</p>
                <div style={{display: 'flex', flexWrap: 'wrap', gap: 16, marginBottom: 8}}>
                  {ALL_EVAL_TYPES.map(t => (
                    <Checkbox key={t} id={`et-${t}`} labelText={evalTypeLabel(t)}
                      checked={formEvalTypes.has(t)} onChange={() => toggleEvalType(t)} />
                  ))}
                </div>
              </>
            )}

            <h5 style={sectionTitleStyle}>Prefill mapping (documentpad → planveld)</h5>
            <p style={hintStyle}>
              {formIsIntake
                ? 'Bepaalt hoe het create-plan taakformulier het plan voorvult uit de intake. Paden komen uit het documentschema van dit dossiertype; planvelden zijn het vaste contract van het taakformulier.'
                : 'Alleen relevant als het create-plan taakformulier ook op dit dossiertype gekoppeld is (vanuit-dossier-route). Leeg = standaardpaden, die o.a. de subjectvelden van het aanvraagformulier dekken.'}
            </p>
            <MappingEditor rijen={formMapping} onChange={setFormMapping} padSuggesties={padSuggesties} />
          </div>
        </Modal>
      </div>
    </Theme>
  );
}

// -------------------------------------------------- actie/product-bouwblokken

/**
 * Building blocks per plan case type: link a GZAC building block (imported
 * zip) under chosen doeltype(s). The koppeling happens entirely here — the
 * backend maps the chosen plugin configuration on the case type's
 * building-block link (pluginConfigurationMappings). The building block
 * link itself ships in the case zip with the input mappings and
 * startableByUser=false: normal building block runtime behaviour (instance,
 * building block document), but no entry in GZAC's start menu.
 *
 * Two kinds: ACTIE (starts an action under a doel) and PRODUCT ("product X
 * aanvragen" — all product logic lives in the building block, which manages
 * the instrument in the plan itself through the plugin actions
 * aanmaak-instrument/update-instrument; only availability lives here).
 */
function BouwblokkenBeheer({ soort, vorm, planConfigs, doeltypen, onToast }: {
  soort: 'ACTIE' | 'PRODUCT';
  /** The section determines the uitvoeringsvorm: BOUWBLOK sections manage
   *  building blocks on the plan dossier, the DOSSIER section (products
   *  only) manages product case types with their own dossier per request. */
  vorm: 'BOUWBLOK' | 'DOSSIER';
  planConfigs: PhaseConfig[];
  doeltypen: DoelType[];
  onToast: (msg: string) => void;
}) {
  const product = soort === 'PRODUCT';
  const dossierVorm = vorm === 'DOSSIER';
  const t = dossierVorm ? {
    titel: 'Product-dossiers',
    hint: 'Productaanvragen als volwaardige case: elke aanvraag vanaf de planpagina krijgt een eigen dossier ' +
      '(eigen taken, PBAC, documenten en optioneel een zaak via de zaaktype-link van het dossiertype). ' +
      'Het proces zet bij toekenning het instrument in het plan — met de dossier-URN in het zaak-veld, ' +
      'waardoor de planpagina naar het aanvraagdossier kan springen.',
    kolom: 'Product',
    kolomProces: 'Dossiertype',
    leeg: 'Geen product-dossiers gekoppeld.',
    naamLabel: 'Naam van het product',
    naamPlaceholder: 'bijv. Werkfit-traject aanvragen',
    koppelKnop: 'Product-dossier koppelen',
    modalEdit: 'Product-dossier bewerken',
    toastGekoppeld: 'Product-dossier gekoppeld; de pdca-proceskoppelingen van het dossiertype staan op de gekozen configuratie',
    toastOntkoppeld: 'Product-dossier ontkoppeld',
    confirmOntkoppelen: (naam: string, caseDef: string) => `Product-dossier "${naam}" (${caseDef}) ontkoppelen?`,
  } : product ? {
    titel: 'Product-bouwblokken',
    hint: 'GZAC-bouwblokken die een product/voorziening aanvragen onder een doel ("product X aanvragen") ' +
      'en op het plandossier draaien. Alle productlogica zit in het bouwblok zelf — via de plugin-actions ' +
      'aanmaak-instrument/update-instrument zet het het instrument in het plan en werkt het het bij; ' +
      'hier bepaal je alleen onder welk plandossiertype en doeltype(s) het beschikbaar is.',
    kolom: 'Product',
    kolomProces: 'Bouwblok',
    leeg: 'Geen product-bouwblokken gekoppeld.',
    naamLabel: 'Naam van het product',
    naamPlaceholder: 'bijv. Jobcoaching aanvragen',
    koppelKnop: 'Product-bouwblok koppelen',
    modalEdit: 'Product-bouwblok bewerken',
    toastGekoppeld: 'Product-bouwblok gekoppeld; de pluginconfiguratie staat op de bouwbloklink van het dossiertype',
    toastOntkoppeld: 'Product-bouwblok ontkoppeld',
    confirmOntkoppelen: (naam: string, caseDef: string) => `Product-bouwblok "${naam}" (${caseDef}) ontkoppelen?`,
  } : {
    titel: 'Actie-bouwblokken',
    hint: 'GZAC-bouwblokken die als actie onder een doel gestart kunnen worden. Importeer de bouwblok-zip ' +
      'in GZAC en koppel hem hier aan een plandossiertype en doeltype(s) — de taak van het bouwblok ' +
      'verschijnt in de takenlijst van het plandossier.',
    kolom: 'Actie',
    kolomProces: 'Bouwblok',
    leeg: 'Geen actie-bouwblokken gekoppeld.',
    naamLabel: 'Naam van de actie',
    naamPlaceholder: 'bijv. Verdiepingsonderzoek',
    koppelKnop: 'Bouwblok koppelen',
    modalEdit: 'Actie-bouwblok bewerken',
    toastGekoppeld: 'Actie-bouwblok gekoppeld; de pluginconfiguratie staat op de bouwbloklink van het dossiertype',
    toastOntkoppeld: 'Actie-bouwblok ontkoppeld',
    confirmOntkoppelen: (naam: string, caseDef: string) => `Actie-bouwblok "${naam}" (${caseDef}) ontkoppelen?`,
  };
  const [koppelingen, setKoppelingen] = useState<ActieBouwblokKoppeling[]>([]);
  const [bouwblokken, setBouwblokken] = useState<BouwblokProces[] | null>(null);
  const [productDossiertypes, setProductDossiertypes] = useState<string[] | null>(null);
  const [pluginConfigs, setPluginConfigs] = useState<PluginConfiguratie[]>([]);
  const [modal, setModal] = useState(false);
  const [editId, setEditId] = useState<string | null>(null);
  const [formCaseDefKey, setFormCaseDefKey] = useState('');
  const [formProcesKey, setFormProcesKey] = useState('');
  const [formProductCaseKey, setFormProductCaseKey] = useState('');
  const [formNaam, setFormNaam] = useState('');
  const [formOmschrijving, setFormOmschrijving] = useState('');
  const [formPluginConfig, setFormPluginConfig] = useState('');
  const [formDoeltypen, setFormDoeltypen] = useState<string[]>([]);
  const [bezig, setBezig] = useState(false);

  const doeltypeOpties = subdoelTypen(doeltypen) as DoelType[];
  const doeltypeNaam = (uuid: string) => {
    const t = doeltypen.find(d => d.uuid === uuid);
    return t ? doelTypeNaam(t) : uuid;
  };

  const load = useCallback(async () => {
    const [k, bb, pdt, pc] = await Promise.all([
      pdca.actieBouwblokken.admin.list().catch(() => [] as ActieBouwblokKoppeling[]),
      // null = list unavailable (grant not yet accepted or GZAC down) ->
      // free key entry as fallback.
      dossierVorm ? Promise.resolve(null) : pdca.actieBouwblokken.admin.bouwblokProcessen().catch(() => null),
      dossierVorm ? pdca.actieBouwblokken.admin.productDossiertypes().catch(() => null) : Promise.resolve(null),
      pdca.actieBouwblokken.admin.pluginConfiguraties().catch(() => [] as PluginConfiguratie[]),
    ]);
    setKoppelingen(k.filter(x =>
      (x.soort ?? 'ACTIE') === soort && (x.uitvoeringsvorm ?? 'BOUWBLOK') === vorm));
    setBouwblokken(bb);
    setProductDossiertypes(pdt);
    setPluginConfigs(pc);
  }, [soort, vorm, dossierVorm]);

  useEffect(() => { load(); }, [load]);
  useEffect(() => { resizeIframe(); }, [koppelingen, modal, formDoeltypen]);

  const parseList = (raw?: string | null): string[] => {
    try { return JSON.parse(raw || '[]'); } catch { return []; }
  };
  const selectedBouwblok = bouwblokken?.find(b => b.processDefinitionKey === formProcesKey) ?? null;

  const openCreate = () => {
    setEditId(null);
    setFormCaseDefKey(planConfigs[0]?.caseDefinitionKey ?? '');
    setFormProcesKey('');
    setFormProductCaseKey('');
    setFormNaam('');
    setFormOmschrijving('');
    setFormPluginConfig(pluginConfigs.length === 1 ? pluginConfigs[0].configId : '');
    setFormDoeltypen([]);
    setModal(true);
  };

  const openEdit = (k: ActieBouwblokKoppeling) => {
    setEditId(k.id);
    setFormCaseDefKey(k.caseDefinitionKey);
    setFormProcesKey(k.processDefinitionKey || '');
    setFormProductCaseKey(k.productCaseDefinitionKey || '');
    setFormNaam(k.naam);
    setFormOmschrijving(k.omschrijving || '');
    setFormPluginConfig(k.pluginConfigId);
    setFormDoeltypen(parseList(k.doeltypeUuids));
    setModal(true);
  };

  const selectBouwblok = (procesKey: string) => {
    setFormProcesKey(procesKey);
    const bb = bouwblokken?.find(b => b.processDefinitionKey === procesKey);
    if (bb && !formNaam.trim()) setFormNaam(bb.naam || bb.bouwblokKey);
  };

  const handleSave = async () => {
    if (!formCaseDefKey) { alert('Kies een plandossiertype'); return; }
    if (dossierVorm && !formProductCaseKey.trim()) { alert('Kies het productdossiertype'); return; }
    if (!dossierVorm && !formProcesKey.trim()) { alert('Kies een bouwblok (of vul de proceskey in)'); return; }
    if (!formNaam.trim()) { alert('Vul een naam voor de actie in'); return; }
    if (!formPluginConfig) { alert('Kies de PDCA-pluginconfiguratie'); return; }
    const payload = {
      soort,
      uitvoeringsvorm: vorm,
      caseDefinitionKey: formCaseDefKey,
      naam: formNaam.trim(),
      omschrijving: formOmschrijving.trim() || undefined,
      buildingBlockKey: dossierVorm ? undefined : (selectedBouwblok?.bouwblokKey ?? formProcesKey.trim()),
      buildingBlockVersion: dossierVorm ? undefined : selectedBouwblok?.bouwblokVersie,
      processDefinitionKey: dossierVorm ? undefined : formProcesKey.trim(),
      productCaseDefinitionKey: dossierVorm ? formProductCaseKey.trim() : undefined,
      pluginConfigId: formPluginConfig,
      doeltypeUuids: formDoeltypen.length > 0 ? JSON.stringify(formDoeltypen) : null,
    };
    setBezig(true);
    try {
      if (editId) await pdca.actieBouwblokken.admin.update(editId, payload as any);
      else await pdca.actieBouwblokken.admin.create(payload as any);
      setModal(false);
      onToast(t.toastGekoppeld);
      await load();
    } catch (e: any) { alert('Fout: ' + e.message); }
    finally { setBezig(false); }
  };

  const handleDelete = async (k: ActieBouwblokKoppeling) => {
    if (!confirm(t.confirmOntkoppelen(k.naam, k.caseDefinitionKey))) return;
    try { await pdca.actieBouwblokken.admin.delete(k.id); onToast(t.toastOntkoppeld); await load(); }
    catch (e: any) { alert('Fout: ' + e.message); }
  };

  const thStyle: React.CSSProperties = {
    textAlign: 'left', padding: '12px 16px', borderBottom: '2px solid var(--cds-border-subtle)',
    fontSize: 12, fontWeight: 600, color: 'var(--cds-text-secondary)', textTransform: 'uppercase', letterSpacing: 0.5,
  };

  return (
    <>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', margin: '32px 0 8px' }}>
        <div>
          <h2 style={{ fontSize: '1.25rem', fontWeight: 600 }}>{t.titel}</h2>
          <p style={{ ...hintStyle, marginBottom: 0 }}>{t.hint}</p>
        </div>
        <Button renderIcon={Add} kind="tertiary" onClick={openCreate} disabled={planConfigs.length === 0}>
          {t.koppelKnop}
        </Button>
      </div>

      {koppelingen.length === 0 ? (
        <div className="pdca-empty"><p>{t.leeg}</p></div>
      ) : (
        <Tile>
          <table style={{ width: '100%', borderCollapse: 'collapse' }}>
            <thead>
              <tr>
                <th style={thStyle}>Plandossiertype</th>
                <th style={thStyle}>{t.kolom}</th>
                <th style={thStyle}>{t.kolomProces}</th>
                <th style={thStyle}>Doeltypen</th>
                <th style={{ ...thStyle, textAlign: 'right' }}>Acties</th>
              </tr>
            </thead>
            <tbody>
              {koppelingen.map(k => {
                const dt = parseList(k.doeltypeUuids);
                return (
                  <tr key={k.id} style={{ borderBottom: '1px solid var(--cds-border-subtle)' }}>
                    <td style={{ padding: '12px 16px' }}>{k.caseDefinitionKey}</td>
                    <td style={{ padding: '12px 16px', fontWeight: 500 }}>
                      {k.naam}
                      {k.omschrijving && <div style={{ fontSize: 12, fontWeight: 400, color: 'var(--cds-text-helper)' }}>{k.omschrijving}</div>}
                    </td>
                    <td style={{ padding: '12px 16px' }}>
                      {dossierVorm
                        ? <Tag size="sm" type="blue" title="Eigen dossier per aanvraag">{k.productCaseDefinitionKey}</Tag>
                        : <Tag size="sm" type="high-contrast">{k.buildingBlockKey}{k.buildingBlockVersion ? ` ${k.buildingBlockVersion}` : ''}</Tag>}
                    </td>
                    <td style={{ padding: '12px 16px' }}>
                      <div style={{ display: 'flex', gap: 4, flexWrap: 'wrap' }}>
                        {dt.length === 0
                          ? <span style={{ fontSize: 12, color: 'var(--cds-text-helper)', fontStyle: 'italic' }}>alle doelen</span>
                          : dt.map(u => <Tag key={u} size="sm" type="purple">{doeltypeNaam(u)}</Tag>)}
                      </div>
                    </td>
                    <td style={{ padding: '12px 16px', textAlign: 'right' }}>
                      <Button size="sm" kind="ghost" renderIcon={Edit} iconDescription="Bewerken" hasIconOnly onClick={() => openEdit(k)} />
                      <Button size="sm" kind="danger--ghost" renderIcon={TrashCan} iconDescription="Ontkoppelen" hasIconOnly onClick={() => handleDelete(k)} />
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </Tile>
      )}

      <Modal open={modal} size="lg" modalHeading={editId ? t.modalEdit : t.koppelKnop}
        primaryButtonText={bezig ? 'Bezig…' : 'Opslaan'} secondaryButtonText="Annuleren" primaryButtonDisabled={bezig}
        onRequestClose={() => setModal(false)} onRequestSubmit={handleSave}>
        <div className="pdca-modal-form">
          <Select id={`bb-${soort}-${vorm}-casedef`} labelText="Plandossiertype" value={formCaseDefKey}
            disabled={!!editId}
            helperText={editId ? 'Het dossiertype van een bestaande koppeling is niet te wijzigen.' : undefined}
            onChange={(e: any) => setFormCaseDefKey(e.target.value)}>
            {!formCaseDefKey && <SelectItem value="" text="— kies plandossiertype —" />}
            {planConfigs.map(c => <SelectItem key={c.caseDefinitionKey} value={c.caseDefinitionKey} text={c.caseDefinitionKey} />)}
            {editId && !planConfigs.some(c => c.caseDefinitionKey === formCaseDefKey) && (
              <SelectItem value={formCaseDefKey} text={formCaseDefKey} />
            )}
          </Select>

          {dossierVorm ? (
            productDossiertypes !== null ? (
              <Select id={`bb-${soort}-${vorm}-productcase`} labelText="Productdossiertype"
                helperText="Dossiertypes met het productcontract in hun documentschema (dossierUrn + instrumentUuid + doelUuid). Alleen dossiertypes met een geactiveerde versie verschijnen hier."
                value={formProductCaseKey} onChange={(e: any) => {
                  setFormProductCaseKey(e.target.value);
                  if (e.target.value && !formNaam.trim()) setFormNaam(e.target.value);
                }}>
                <SelectItem value="" text={productDossiertypes.length === 0 ? '— importeer én activeer eerst een productdossier-zip in GZAC —' : '— kies productdossiertype —'} />
                {productDossiertypes.map(c => <SelectItem key={c} value={c} text={c} />)}
                {formProductCaseKey && !productDossiertypes.includes(formProductCaseKey) && (
                  <SelectItem value={formProductCaseKey} text={`${formProductCaseKey} (huidig)`} />
                )}
              </Select>
            ) : (
              <TextInput id={`bb-${soort}-${vorm}-productcase`} labelText="Productdossiertype (case definition key)"
                value={formProductCaseKey} onChange={(e: any) => setFormProductCaseKey(e.target.value)}
                placeholder="werkfit-aanvraag"
                helperText="De dossiertypelijst van GZAC is niet beschikbaar (accepteer de nieuwe manifest-grant); vul de key handmatig in." />
            )
          ) : bouwblokken !== null ? (
            <Select id={`bb-${soort}-${vorm}-proces`} labelText="Bouwblok"
              helperText="Geïmporteerde bouwblokken in GZAC (herkend aan versionTag BB:<key>:<versie>)."
              value={formProcesKey} onChange={(e: any) => selectBouwblok(e.target.value)}>
              <SelectItem value="" text={bouwblokken.length === 0 ? '— importeer eerst de bouwblok-zip in GZAC —' : '— kies bouwblok —'} />
              {bouwblokken.map(b => (
                <SelectItem key={b.processDefinitionKey} value={b.processDefinitionKey}
                  text={`${b.naam || b.bouwblokKey} (${b.bouwblokKey} ${b.bouwblokVersie} — proces ${b.processDefinitionKey})`} />
              ))}
              {formProcesKey && !bouwblokken.some(b => b.processDefinitionKey === formProcesKey) && (
                <SelectItem value={formProcesKey} text={`${formProcesKey} (huidig)`} />
              )}
            </Select>
          ) : (
            <TextInput id={`bb-${soort}-${vorm}-proces`} labelText="Proceskey van het bouwblok"
              value={formProcesKey} onChange={(e: any) => setFormProcesKey(e.target.value)}
              placeholder="pdca-actie-uitvoeren"
              helperText="De proceslijst van GZAC is niet beschikbaar (accepteer de nieuwe manifest-grant); vul de key handmatig in." />
          )}

          <Select id={`bb-${soort}-${vorm}-pluginconfig`} labelText="PDCA-pluginconfiguratie"
            helperText={dossierVorm
              ? 'De losse pdca-proceskoppelingen van het productdossiertype worden aan deze configuratie gekoppeld; één configuratie per productdossiertype.'
              : 'Deze configuratie wordt als pluginmapping op de bouwbloklink van het dossiertype gezet; één configuratie per bouwblok per dossiertype.'}
            value={formPluginConfig} onChange={(e: any) => setFormPluginConfig(e.target.value)}>
            <SelectItem value="" text={pluginConfigs.length === 0 ? '— geen configuratie gepusht door GZAC —' : '— kies configuratie —'} />
            {pluginConfigs.map(c => <SelectItem key={c.configId} value={c.configId} text={c.titel} />)}
          </Select>

          <TextInput id={`bb-${soort}-${vorm}-naam`} labelText={t.naamLabel} value={formNaam}
            onChange={(e: any) => setFormNaam(e.target.value)} placeholder={t.naamPlaceholder} />
          <TextInput id={`bb-${soort}-${vorm}-omschrijving`} labelText="Omschrijving (optioneel)" value={formOmschrijving}
            onChange={(e: any) => setFormOmschrijving(e.target.value)} />

          <h5 style={sectionTitleStyle}>Beschikbaar onder doeltype(s)</h5>
          <p style={hintStyle}>Uit het doeltype-register van Open Plan; klik om te (de)selecteren. Leeg = beschikbaar onder elk doel.</p>
          <div style={{ display: 'flex', gap: 4, flexWrap: 'wrap', marginBottom: 8 }}>
            {doeltypeOpties.map(t => {
              const actief = formDoeltypen.includes(t.uuid);
              return (
                <Tag key={t.uuid} size="sm" type={actief ? 'purple' : 'outline'} style={{ cursor: 'pointer' }}
                  onClick={() => setFormDoeltypen(prev => actief ? prev.filter(u => u !== t.uuid) : [...prev, t.uuid])}>
                  {actief ? '✓ ' : '+ '}{doelTypeNaam(t)}
                </Tag>
              );
            })}
            {doeltypeOpties.length === 0 && (
              <span style={{ ...hintStyle, fontStyle: 'italic', marginBottom: 0 }}>doeltype-register niet beschikbaar</span>
            )}
          </div>

        </div>
      </Modal>
    </>
  );
}
