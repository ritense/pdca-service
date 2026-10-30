import React, { useState, useEffect, useCallback } from 'react';
import {
  Theme, Button, Tag, Modal, TextInput, Checkbox, Loading, InlineNotification, Tile,
  Select, SelectItem, ContentSwitcher, Switch,
} from '@carbon/react';
import { Add, Edit, TrashCan, Draggable, Close } from '@carbon/react/icons';
import { onInit, resizeIframe } from '../shared/bridge';
import {
  openplan, pdca, PhaseConfig, DoelType,
  VoortgangStatusOptie, VoortgangBetekenis,
} from '../shared/api';
import {
  evalTypeLabel, subdoelTypen, hoofddoelTypen, doelTypeNaam, voortgangStatus,
  VOORTGANG_BETEKENISSEN, GERESERVEERDE_STATUSNAMEN,
} from '../shared/labels';

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

/** A voortgangsstatus being edited; [sleutel] keys the row, [code] is empty until the backend assigns one. */
type VoortgangRij = VoortgangStatusOptie & { sleutel: string };

let volgendeRij = 0;
const alsRijen = (opties: VoortgangStatusOptie[]): VoortgangRij[] =>
  opties.map(o => ({ ...o, sleutel: `rij-${volgendeRij++}` }));

/** Why a row cannot be saved, or null: a name is required, unique and not a lifecycle status. */
function voortgangFout(rij: VoortgangRij, rijen: VoortgangRij[]): string | null {
  const naam = rij.naam.trim().toLowerCase();
  if (!naam) return 'Geef de status een naam';
  if (GERESERVEERDE_STATUSNAMEN.includes(naam)) return 'Dit is al een status van het doel zelf';
  if (rijen.some(r => r !== rij && r.naam.trim().toLowerCase() === naam)) return 'Deze naam staat er al';
  return null;
}

/**
 * The voortgangsstatussen of a plan case type: per row a name, what it means
 * (gaat goed / let op / gaat niet goed: the tag colour and the Voortgang KPI)
 * and a preview of the tag; drag for the order. The code behind a status is
 * assigned once by the backend, so renaming carries through to existing doelen.
 */
function VoortgangStatussenEditor({ rijen, onChange, standaard }: {
  rijen: VoortgangRij[];
  onChange: (rijen: VoortgangRij[]) => void;
  standaard: VoortgangStatusOptie[];
}) {
  const [sleepIndex, setSleepIndex] = useState<number | null>(null);
  const [overIndex, setOverIndex] = useState<number | null>(null);

  const wijzig = (i: number, deel: Partial<VoortgangRij>) =>
    onChange(rijen.map((r, ri) => (ri === i ? { ...r, ...deel } : r)));

  const verplaats = (van: number, naar: number) => {
    if (van === naar) return;
    const kopie = [...rijen];
    const [rij] = kopie.splice(van, 1);
    kopie.splice(naar, 0, rij);
    onChange(kopie);
  };

  const isStandaard = rijen.length === standaard.length
    && rijen.every((r, i) => r.naam === standaard[i].naam && r.betekenis === standaard[i].betekenis);

  return (
    <div>
      {rijen.length === 0 && (
        <div style={{ ...hintStyle, fontStyle: 'italic' }}>Geen statussen — de standaardlijst geldt.</div>
      )}
      {rijen.length > 0 && (
        <div style={{ display: 'grid', gridTemplateColumns: '16px 16px 1fr 180px 140px 40px', gap: 8, marginBottom: 4 }}>
          <span /><span />
          <span style={{ fontSize: 12, fontWeight: 600, color: 'var(--cds-text-secondary)' }}>Naam</span>
          <span style={{ fontSize: 12, fontWeight: 600, color: 'var(--cds-text-secondary)' }}>Betekenis</span>
          <span style={{ fontSize: 12, fontWeight: 600, color: 'var(--cds-text-secondary)' }}>Zo ziet het eruit</span>
          <span />
        </div>
      )}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 2, marginBottom: 8 }}>
        {rijen.map((rij, i) => {
          const fout = voortgangFout(rij, rijen);
          const tag = VOORTGANG_BETEKENISSEN.find(b => b.betekenis === rij.betekenis)?.tag ?? 'gray';
          return (
            <div key={rij.sleutel} draggable
              onDragStart={() => setSleepIndex(i)}
              onDragOver={e => { e.preventDefault(); setOverIndex(i); }}
              onDragLeave={() => setOverIndex(cur => (cur === i ? null : cur))}
              onDrop={() => { if (sleepIndex !== null) verplaats(sleepIndex, i); setSleepIndex(null); setOverIndex(null); }}
              onDragEnd={() => { setSleepIndex(null); setOverIndex(null); }}
              style={{
                display: 'grid', gridTemplateColumns: '16px 16px 1fr 180px 140px 40px', gap: 8,
                alignItems: 'start', padding: '6px 8px',
                background: 'var(--cds-layer-01, #f4f4f4)', cursor: 'grab',
                border: '1px solid var(--cds-border-subtle)',
                borderTop: overIndex === i && sleepIndex !== null && sleepIndex !== i
                  ? '2px solid var(--cds-border-interactive, #0f62fe)' : '1px solid var(--cds-border-subtle)',
                opacity: sleepIndex === i ? 0.5 : 1,
              }}>
              <Draggable size={16} style={{ color: 'var(--cds-icon-secondary)', marginTop: 8 }} />
              <span style={{ fontSize: 12, color: 'var(--cds-text-helper)', marginTop: 8 }}>{i + 1}.</span>
              <TextInput id={`voortgang-naam-${rij.sleutel}`} labelText="" hideLabel size="sm" value={rij.naam}
                placeholder="bijv. Wacht op inwoner"
                invalid={!!fout} invalidText={fout ?? undefined}
                onChange={(e: any) => wijzig(i, { naam: e.target.value })} />
              <Select id={`voortgang-betekenis-${rij.sleutel}`} labelText="" hideLabel size="sm" value={rij.betekenis}
                onChange={(e: any) => wijzig(i, { betekenis: e.target.value as VoortgangBetekenis })}>
                {VOORTGANG_BETEKENISSEN.map(b => <SelectItem key={b.betekenis} value={b.betekenis} text={b.label} />)}
              </Select>
              <div style={{ marginTop: 4 }}>
                <Tag size="sm" type={tag}>{rij.naam.trim() || 'Naam'}</Tag>
              </div>
              <Button size="sm" kind="ghost" renderIcon={Close} iconDescription="Verwijderen" hasIconOnly
                onClick={() => onChange(rijen.filter((_, ri) => ri !== i))} />
            </div>
          );
        })}
      </div>
      <div style={{ display: 'flex', gap: 8 }}>
        <Button size="sm" kind="tertiary" renderIcon={Add}
          onClick={() => onChange([...rijen, ...alsRijen([{ code: '', naam: '', betekenis: 'LET_OP' }])])}>
          Status toevoegen
        </Button>
        {!isStandaard && (
          <Button size="sm" kind="ghost" onClick={() => onChange(alsRijen(standaard))}>Standaardlijst herstellen</Button>
        )}
      </div>
    </div>
  );
}

interface MappingRij { pad: string; veld: string; }

/**
 * Prefill mapping as a source→target table, modelled after GZAC's own
 * mapping tables: document path → plan field, with an add button that
 * disables once all fields are mapped.
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
  const [formVoortgang, setFormVoortgang] = useState<VoortgangRij[]>([]);
  /** The standard voortgangsstatussen (backend), for a case type that configures none. */
  const [standaardVoortgang, setStandaardVoortgang] = useState<VoortgangStatusOptie[]>([]);
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
  useEffect(() => { resizeIframe(); }, [loading, configs, modal, formPlanStatussen, formVoortgang, formPositieTypen, formSubdoelMapping, formMapping]);

  const loadData = useCallback(async () => {
    try {
      setLoading(true);
      const [data, dossiertypes, dt, sv] = await Promise.all([
        pdca.phaseConfigs.list(),
        // Case types from GZAC; null = list unavailable (grant not yet
        // accepted or GZAC down) -> free entry as fallback.
        pdca.caseDefinitions.list().catch(() => null),
        // Doeltype register: the subdoeltypen of the subdoelmapping editor.
        openplan.doeltypen.list().catch(() => [] as DoelType[]),
        pdca.voortgangStatussen().catch(() => [] as VoortgangStatusOptie[]),
      ]);
      setConfigs(data);
      setStandaardVoortgang(sv);
      setGzacDossiertypes(dossiertypes);
      setDoeltypen(dt);
      setLoading(false);
    } catch (e: any) { setError(e.message); setLoading(false); }
  }, []);

  const parseList = (raw?: string | null): string[] => {
    try { return JSON.parse(raw || '[]'); } catch { return []; }
  };

  /** The configured voortgangsstatussen, or null when the case type configures none (standard list). */
  const parseVoortgang = (raw?: string | null): VoortgangStatusOptie[] | null => {
    try {
      const opties = JSON.parse(raw || '[]') as VoortgangStatusOptie[];
      return opties.length > 0 ? opties : null;
    } catch { return null; }
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
    setFormVoortgang(alsRijen(standaardVoortgang));
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
    setFormVoortgang(alsRijen(parseVoortgang(cfg.voortgangStatussen) ?? standaardVoortgang));
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
      voortgangStatussen: formIsIntake ? null
        : JSON.stringify(formVoortgang.map(({ code, naam, betekenis }) => ({ code, naam: naam.trim(), betekenis }))),
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
                  <th style={thStyle}>Voortgangsstatussen</th>
                  <th style={thStyle}>Positietypen</th>
                  <th style={thStyle}>Subdoelen per hoofddoel</th>
                  <th style={thStyle}>Evaluatietypen</th>
                  <th style={{...thStyle, textAlign: 'right'}}>Acties</th>
                </tr>
              </thead>
              <tbody>
                {configs.map(cfg => {
                  const ps = parseList(cfg.planStatussen);
                  const vs = parseVoortgang(cfg.voortgangStatussen);
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
                        {cfg.planCaseDefinitionKey ? (
                          <span style={{fontSize: 12, color: 'var(--cds-text-helper)', fontStyle: 'italic'}}>n.v.t. (intake)</span>
                        ) : (
                          <div style={{display: 'flex', gap: 4, flexWrap: 'wrap', alignItems: 'center'}}>
                            {(vs ?? standaardVoortgang).map(o => {
                              const weergave = voortgangStatus(o.code, vs ?? standaardVoortgang);
                              return <Tag key={o.code} size="sm" type={weergave.tag}>{weergave.label}</Tag>;
                            })}
                            {!vs && <span style={{fontSize: 12, color: 'var(--cds-text-helper)', fontStyle: 'italic'}}>standaard</span>}
                          </div>
                        )}
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

        <Modal open={modal} size="lg" modalHeading={editKey ? 'Configuratie bewerken' : 'Nieuwe configuratie'}
          primaryButtonText="Opslaan" secondaryButtonText="Annuleren"
          primaryButtonDisabled={!formIsIntake && formVoortgang.some(r => voortgangFout(r, formVoortgang) !== null)}
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

                <h5 style={sectionTitleStyle}>Voortgangsstatussen (hoofddoel en subdoelen)</h5>
                <p style={hintStyle}>
                  Hoe het gaat met een lopend hoofddoel of subdoel. De fase (Gepland, Gestart, Afgerond,
                  Afgebroken) komt uit het register en is geen voortgangsstatus; na afronden of afbreken
                  toont een doel weer die fase. Een naam wijzigen werkt door op bestaande doelen; een
                  verwijderde status blijft grijs zichtbaar op doelen die hem nog hebben. Sleep voor de volgorde.
                </p>
                <VoortgangStatussenEditor rijen={formVoortgang} onChange={setFormVoortgang}
                  standaard={standaardVoortgang} />

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
