import React, { useEffect, useMemo, useRef, useState } from 'react';
import {
  Accordion, AccordionItem, Button, Checkbox, InlineLoading, InlineNotification, Layer,
  RadioButton, RadioButtonGroup, Select, SelectItem, Tag, TextArea, TextInput, Theme, Tile,
} from '@carbon/react';
import { Add, TrashCan } from '@carbon/react/icons';
import { getHostTheme, HostTheme, navigateHost, onHostThemeChanged, onInit, submitTask } from '../shared/bridge';
import {
  openplan, openproduct, pdca, registers, urn,
  DoelType, PhaseConfig, Plan, PlanType, ProductType, RelatieType,
} from '../shared/api';
import {
  dienstverleningLabel, doelgroepVoorSubject, evalTypeLabel, formatDate, hoofddoelTypen,
  producttypenVoorDoel, subdoelTypenVoorHoofddoel, vandaag,
} from '../shared/labels';
import {
  createPlanApi, DoelStatus, EvaluatieStatus, PlanIntakeRequest, PlanPrefill, PlanPrefillResponse, SubjectType,
} from './createPlanApi';

/*
 * External task form "Plan aanmaken". Prefilled from the dossier content
 * (intake) through the case type's prefill mapping: anything from just a bsn
 * up to a complete plan with subdoelen, instrumenten, evaluaties and
 * betrokkenen. The backend creates everything, including the plan dossier,
 * in one call (POST /api/v1/pdca/plan-intake); the form then completes the
 * GZAC task with the plan result.
 *
 * GZAC does not size task-form iframes to their content: the iframe is a
 * fixed viewport inside the task modal, so the form scrolls internally and
 * keeps its primary action in a footer bar that is always visible.
 */

const STANDAARD_EVALUATIETYPEN = ['INTAKE', 'PROGRESS', 'EVALUATION'];
/** The first evaluation is planned this many weeks after the start of the plan. */
const EERSTE_EVALUATIE_WEKEN = 6;

const DOEL_STATUSSEN: { value: DoelStatus; text: string }[] = [
  { value: 'gepland', text: 'Gepland' },
  { value: 'gestart', text: 'Gestart' },
  { value: 'afgerond', text: 'Afgerond' },
];

const EVALUATIE_STATUSSEN: { value: EvaluatieStatus; text: string }[] = [
  { value: 'gepland', text: 'Gepland' },
  { value: 'afgerond', text: 'Afgerond' },
];

/**
 * An editable list row. Prefill fields the form does not edit (e.g. a
 * toelichting) stay in `extra` and travel to the backend unchanged.
 */
type Rij<T> = T & { key: number; extra: Record<string, unknown> };

type SubdoelRij = Rij<{ doelType: string; beschrijving: string; status: DoelStatus }>;
type InstrumentRij = Rij<{ titel: string; product: string; doel: string; status: DoelStatus }>;
/** `auto`: the default first evaluation, whose date follows the startdatum until the row is edited. */
type EvaluatieRij = Rij<{ datum: string; evaluatieType: string; status: EvaluatieStatus; notitie: string; auto: boolean }>;
type BetrokkeneRij = Rij<{ naam: string; rol: string; organisatie: string; email: string; procesbegeleider: boolean }>;

type Sectie = 'subdoelen' | 'instrumenten' | 'evaluaties' | 'betrokkenen';

interface Registers {
  /** null: the register could not be reached. */
  plantypen: PlanType[] | null;
  doeltypen: DoelType[] | null;
  relatietypen: RelatieType[];
  producttypen: ProductType[];
}

/** The case configuration of the plan case type. */
interface CaseConfig {
  key: string;
  posities: string[];
  subdoelMapping: string | null;
  planStatussen: string[];
  evaluatieTypen: string[];
}

interface SubjectWeergave {
  id: string;
  naam: string;
  regels: string[];
  uitIntake: boolean;
}

/** A task submission that has to be (re)sent after the plan exists. */
interface TaakAfronding {
  velden: Record<string, unknown>;
  planDossierId: string | null;
}

let volgendeSleutel = 0;
const sleutel = () => ++volgendeSleutel;

const tekst = (v: unknown): string => (typeof v === 'string' ? v : v == null ? '' : String(v));

const foutmelding = (e: unknown): string => (e instanceof Error ? e.message : String(e));

function aantal(n: number, enkelvoud: string, meervoud: string): string {
  return `${n} ${n === 1 ? enkelvoud : meervoud}`;
}

function jsonLijst(json: string | null | undefined): string[] {
  try {
    const waarde = JSON.parse(json || '[]');
    return Array.isArray(waarde) ? waarde.map(String) : [];
  } catch {
    return [];
  }
}

/** A plain date (yyyy-mm-dd) the given number of weeks after another plain date. */
function plusWeken(datum: string, weken: number): string {
  const [jaar, maand, dag] = datum.split('-').map(Number);
  if (!jaar || !maand || !dag) return '';
  const uitkomst = new Date(jaar, maand - 1, dag + weken * 7);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${uitkomst.getFullYear()}-${pad(uitkomst.getMonth() + 1)}-${pad(uitkomst.getDate())}`;
}

/** Intake case type: the plan gets its own plan dossier next to this (intake) dossier. */
function isIntake(prefill: PlanPrefillResponse | null): boolean {
  return !!(prefill?.planCaseDefinitionKey && prefill.caseDefinitionKey
    && prefill.planCaseDefinitionKey !== prefill.caseDefinitionKey);
}

/** The case type of the plan: for an intake dossier the plan case type from its configuration. */
function planCaseKey(prefill: PlanPrefillResponse | null, subjectType: SubjectType): string {
  return prefill?.planCaseDefinitionKey || prefill?.caseDefinitionKey
    || (subjectType === 'PERSON' ? 'inwonerplan' : 'binnenhof-renovatie');
}

function subjectUrn(subjectType: SubjectType, id: string): string {
  return subjectType === 'PERSON' ? urn('brp', 'persoon', id) : urn('objecten', 'object', id);
}

/** The prefill object minus the fields the form edits itself. */
function overige(rij: Record<string, unknown>, bekend: string[]): Record<string, unknown> {
  const extra = { ...rij };
  bekend.forEach(veld => delete extra[veld]);
  return extra;
}

/** Status of a prefilled row; the legacy register value `actief` maps to the given status. */
function doelStatusUit(status: unknown, actief: DoelStatus): DoelStatus {
  if (status === 'gepland' || status === 'gestart' || status === 'afgerond') return status;
  return status === 'actief' ? actief : 'gepland';
}

function prefillRijen(waarde: unknown): Record<string, unknown>[] {
  return Array.isArray(waarde)
    ? waarde.filter((r): r is Record<string, unknown> => !!r && typeof r === 'object')
    : [];
}

function nieuweEvaluatie(evaluatieTypen: string[], datum = '', auto = false): EvaluatieRij {
  return {
    key: sleutel(), extra: {}, datum, notitie: '', status: 'gepland', auto,
    evaluatieType: evaluatieTypen.includes('EVALUATION') ? 'EVALUATION' : evaluatieTypen[0] ?? 'EVALUATION',
  };
}

/**
 * The task-form submission is categorised by value-resolver prefix:
 * unprefixed keys become process variables, which vanish with the process
 * instance. The plan result has to outlive the process (the case widgets and
 * the case list read it from the dossier), so every key goes out as `doc:`,
 * written to the document content under the property of the same name.
 */
function docVelden(velden: Record<string, unknown>): Record<string, unknown> {
  const data: Record<string, unknown> = {};
  Object.entries(velden).forEach(([veld, waarde]) => {
    if (waarde !== undefined && waarde !== null) data[`doc:${veld}`] = waarde;
  });
  return data;
}

export function CreatePlan() {
  const [theme, setTheme] = useState<HostTheme>(getHostTheme());
  const [documentId, setDocumentId] = useState<string | null>(null);
  const [prefillGeladen, setPrefillGeladen] = useState(false);
  const [prefillData, setPrefillData] = useState<PlanPrefillResponse | null>(null);
  const [prefillMelding, setPrefillMelding] = useState<{ soort: 'info' | 'warning'; tekst: string } | null>(null);
  const [reg, setReg] = useState<Registers | null>(null);
  const [caseConfig, setCaseConfig] = useState<CaseConfig | null>(null);
  const [caseConfigFout, setCaseConfigFout] = useState(false);
  const [klaar, setKlaar] = useState(false);
  const [bestaandPlan, setBestaandPlan] = useState<Plan | null>(null);

  const [subjectType, setSubjectType] = useState<SubjectType>('PERSON');
  const [subjectId, setSubjectId] = useState('');
  const [subject, setSubject] = useState<SubjectWeergave | null>(null);
  const [subjectFout, setSubjectFout] = useState('');
  const [zoekt, setZoekt] = useState(false);
  const [koppelbaar, setKoppelbaar] = useState<Plan | null>(null);
  const [koppelBezig, setKoppelBezig] = useState(false);
  const [koppelFout, setKoppelFout] = useState('');

  const [titel, setTitel] = useState('');
  const [dienstverlening, setDienstverlening] = useState('');
  const [hoofddoel, setHoofddoel] = useState('');
  const [toelichting, setToelichting] = useState('');
  const [subdoelgroep, setSubdoelgroep] = useState('');
  const [beginPositie, setBeginPositie] = useState('');
  const [startdatum, setStartdatum] = useState(vandaag());
  const [streefEinddatum, setStreefEinddatum] = useState('');

  const [subdoelen, setSubdoelen] = useState<SubdoelRij[]>([]);
  const [instrumenten, setInstrumenten] = useState<InstrumentRij[]>([]);
  const [evaluaties, setEvaluaties] = useState<EvaluatieRij[]>([]);
  const [betrokkenen, setBetrokkenen] = useState<BetrokkeneRij[]>([]);
  const [open, setOpen] = useState<Record<Sectie, boolean>>({
    subdoelen: true, instrumenten: true, evaluaties: true, betrokkenen: true,
  });

  const [poging, setPoging] = useState(false);
  const [bezig, setBezig] = useState(false);
  const [fout, setFout] = useState('');
  const [melding, setMelding] = useState<{ titel: string; tekst?: string } | null>(null);
  const [afronding, setAfronding] = useState<TaakAfronding | null>(null);
  const [afronden, setAfronden] = useState(false);

  const lookupToken = useRef(0);
  const toegepast = useRef(false);

  const intakeMode = isIntake(prefillData);
  const caseKey = planCaseKey(prefillData, subjectType);

  // ------------------------------------------------------------ loading

  useEffect(() => onHostThemeChanged(setTheme), []);

  // Register choice lists: values are the register names (no uuids); the
  // backend looks them up itself.
  useEffect(() => {
    Promise.all([
      openplan.plantypen.list().catch(() => null),
      openplan.doeltypen.list().catch(() => null),
      openplan.relatietypen.list().catch(() => [] as RelatieType[]),
      openproduct.producttypen.list().catch(() => [] as ProductType[]),
    ]).then(([plantypen, doeltypen, relatietypen, producttypen]) =>
      setReg({ plantypen, doeltypen, relatietypen, producttypen }));
  }, []);

  useEffect(() => {
    onInit(ctx => {
      setTheme(getHostTheme());
      const id = ctx.documentId ?? null;
      setDocumentId(id);
      if (!id) {
        setPrefillGeladen(true);
        return;
      }
      createPlanApi.prefill(id)
        .then(res => {
          setPrefillData(res);
          const type = res.prefill?.subjectType;
          if (type === 'PERSON' || type === 'OBJECT') setSubjectType(type);
          // Only a plan dossier can already have a plan; an intake dossier
          // cannot (the plan belongs to the plan dossier this task creates).
          if (!isIntake(res)) zoekBestaandPlan(id);
        })
        .catch(e => {
          setPrefillMelding({ soort: 'warning', tekst: `Voorvullen vanuit de intake mislukt: ${foutmelding(e)}` });
          zoekBestaandPlan(id);
        })
        .finally(() => setPrefillGeladen(true));
    });
  }, []);

  // Positions, subdoel mapping, plan statuses and evaluation types come from
  // the case configuration of the plan case type, which is only known once
  // the prefill is in.
  useEffect(() => {
    if (!prefillGeladen) return;
    let actueel = true;
    pdca.phaseConfigs.get(caseKey)
      .then((cfg: PhaseConfig) => {
        if (!actueel) return;
        const evaluatieTypen = jsonLijst(cfg.evaluationTypes);
        setCaseConfig({
          key: caseKey,
          posities: jsonLijst(cfg.positieTypen),
          subdoelMapping: cfg.subdoelMapping ?? null,
          planStatussen: jsonLijst(cfg.planStatussen),
          evaluatieTypen: evaluatieTypen.length ? evaluatieTypen : STANDAARD_EVALUATIETYPEN,
        });
        setCaseConfigFout(false);
      })
      .catch(() => {
        if (!actueel) return;
        setCaseConfig({
          key: caseKey, posities: [], subdoelMapping: null, planStatussen: [], evaluatieTypen: STANDAARD_EVALUATIETYPEN,
        });
        setCaseConfigFout(true);
      });
    return () => { actueel = false; };
  }, [caseKey, prefillGeladen]);

  useEffect(() => {
    if (toegepast.current || !prefillGeladen || !reg || caseConfig?.key !== caseKey) return;
    toegepast.current = true;
    pasPrefillToe(prefillData?.prefill ?? {}, reg, caseConfig);
    setKlaar(true);
  }, [prefillGeladen, reg, caseConfig, caseKey]);

  /**
   * Plan = dossier (1:1): when a plan is already linked to this dossier, the
   * form only completes the task. Without a link the dossier may carry a
   * planId from the start form: the backend links that plan under water,
   * after which we look once more.
   */
  async function zoekBestaandPlan(id: string) {
    const zoek = () => pdca.dossiers.getPlan(id).then(details => openplan.plannen.get(details.planUuid));
    try {
      setBestaandPlan(await zoek());
      return;
    } catch { /* no linked plan */ }
    try {
      await pdca.dossiers.resolvePlan(id);
      setBestaandPlan(await zoek());
    } catch { /* no plan yet: this form creates it */ }
  }

  function pasPrefillToe(p: PlanPrefill, registers: Registers, cfg: CaseConfig) {
    let velden = 0;
    const nietGezet: string[] = [];

    const id = tekst(p.subjectId).trim();
    if (id) { setSubjectId(id); velden++; }
    if (p.titel) velden++;
    // Intakes without a planafspraken step leave titel and startdatum empty;
    // sensible defaults keep the form quick to complete.
    setTitel(p.titel || (p.naam ? `Plan – ${p.naam}` : ''));
    if (p.notitie) { setToelichting(p.notitie); velden++; }
    const start = p.startdatum ? p.startdatum.slice(0, 10) : vandaag();
    if (p.startdatum) velden++;
    setStartdatum(start);
    if (p.streefEinddatum) { setStreefEinddatum(p.streefEinddatum.slice(0, 10)); velden++; }
    if (p.subdoelgroep) { setSubdoelgroep(p.subdoelgroep); velden++; }

    // The backend rejects values outside the registers, so offering them here
    // would only move the failure to submit time. An empty register (an
    // environment without reference data) shows up here too.
    if (p.beginPositie) {
      if (cfg.posities.includes(p.beginPositie)) { setBeginPositie(p.beginPositie); velden++; }
      else nietGezet.push(`positie "${p.beginPositie}"`);
    }
    const plantypen = (registers.plantypen ?? []).map(pt => pt.type);
    if (p.dienstverlening && plantypen.includes(p.dienstverlening)) {
      setDienstverlening(p.dienstverlening);
      velden++;
    } else {
      if (p.dienstverlening) nietGezet.push(`dienstverlening "${p.dienstverlening}"`);
      setDienstverlening(plantypen[0] ?? '');
    }
    if (p.hoofddoel) {
      if (hoofddoelTypen(registers.doeltypen ?? []).some(t => t.doelType === p.hoofddoel)) {
        setHoofddoel(p.hoofddoel);
        velden++;
      } else nietGezet.push(`hoofddoel "${p.hoofddoel}"`);
    }

    const bekend = (registers.doeltypen ?? []).map(t => t.doelType);
    const nieuweSubdoelen: SubdoelRij[] = prefillRijen(p.doelen).map(d => ({
      key: sleutel(),
      extra: overige(d, ['doelType', 'beschrijving', 'status', 'voortgang']),
      doelType: tekst(d.doelType),
      beschrijving: tekst(d.beschrijving),
      status: doelStatusUit(d.status, 'gepland'),
    }));
    nieuweSubdoelen.forEach(d => {
      if (d.doelType && !bekend.includes(d.doelType)) nietGezet.push(`subdoel "${d.doelType}"`);
    });
    const nieuweInstrumenten: InstrumentRij[] = prefillRijen(p.instrumenten).map(i => ({
      key: sleutel(),
      extra: overige(i, ['titel', 'product', 'doel', 'status', 'voortgang']),
      titel: tekst(i.titel),
      product: tekst(i.product),
      doel: tekst(i.doel),
      status: doelStatusUit(i.status, 'gestart'),
    }));
    let nieuweEvaluaties: EvaluatieRij[] = prefillRijen(p.contactmomenten).map(c => ({
      key: sleutel(),
      extra: overige(c, ['datum', 'evaluatieType', 'status', 'notitie']),
      datum: tekst(c.datum).slice(0, 10),
      evaluatieType: tekst(c.evaluatieType) || nieuweEvaluatie(cfg.evaluatieTypen).evaluatieType,
      status: c.status === 'afgerond' ? 'afgerond' : 'gepland',
      notitie: tekst(c.notitie),
      auto: false,
    }));
    const prefillEvaluaties = nieuweEvaluaties.length;
    if (!prefillEvaluaties) {
      nieuweEvaluaties = [nieuweEvaluatie(cfg.evaluatieTypen, plusWeken(start, EERSTE_EVALUATIE_WEKEN), true)];
    }
    let procesbegeleiderGekozen = false;
    const nieuweBetrokkenen: BetrokkeneRij[] = prefillRijen(p.betrokkenen).map(b => {
      // Exactly one procesbegeleider per plan: the first one marked wins.
      const gemarkeerd = b.procesbegeleider === true || b.hoofdverantwoordelijke === true;
      const procesbegeleider = gemarkeerd && !procesbegeleiderGekozen;
      if (procesbegeleider) procesbegeleiderGekozen = true;
      return {
        key: sleutel(),
        extra: overige(b, ['naam', 'rol', 'organisatie', 'email', 'procesbegeleider', 'hoofdverantwoordelijke']),
        naam: tekst(b.naam),
        rol: tekst(b.rol),
        organisatie: tekst(b.organisatie),
        email: tekst(b.email),
        procesbegeleider,
      };
    });
    velden += nieuweSubdoelen.length + nieuweInstrumenten.length + prefillEvaluaties + nieuweBetrokkenen.length;
    setSubdoelen(nieuweSubdoelen);
    setInstrumenten(nieuweInstrumenten);
    setEvaluaties(nieuweEvaluaties);
    setBetrokkenen(nieuweBetrokkenen);
    setOpen({
      subdoelen: nieuweSubdoelen.length === 0 || nieuweSubdoelen.some(d => !d.doelType),
      instrumenten: nieuweInstrumenten.length === 0,
      evaluaties: nieuweEvaluaties.length === 0,
      betrokkenen: nieuweBetrokkenen.length === 0,
    });

    if (velden > 0 || nietGezet.length > 0) {
      let bericht = `Voorgevuld vanuit de intake van dit dossier (${aantal(velden, 'veld', 'velden')}). Controleer en vul aan waar nodig.`;
      if (nietGezet.length > 0) {
        bericht += ` Let op: ${nietGezet.join(', ')} uit de intake staat niet in het register. Kies zelf een waarde of vul het register aan.`;
      }
      setPrefillMelding({ soort: nietGezet.length > 0 ? 'warning' : 'info', tekst: bericht });
    }

    const type: SubjectType = p.subjectType === 'OBJECT' ? 'OBJECT' : p.subjectType === 'PERSON' ? 'PERSON' : subjectType;
    if (id) zoekSubject(id, type);
  }

  // ------------------------------------------------------------- subject

  function wisSubject() {
    lookupToken.current++;
    setSubject(null);
    setSubjectFout('');
    setZoekt(false);
    setKoppelbaar(null);
    setKoppelFout('');
  }

  async function zoekSubject(id: string, type: SubjectType) {
    wisSubject();
    const token = lookupToken.current;
    if (!id) {
      setSubjectFout(type === 'PERSON' ? 'Vul een BSN in' : 'Vul een object-ID in');
      return;
    }
    setZoekt(true);
    let gevonden: SubjectWeergave | null = null;
    try {
      if (type === 'PERSON') {
        const persoon = await registers.persoon(id);
        const adres = persoon.adres;
        gevonden = {
          id, naam: persoon.naam, uitIntake: false,
          regels: [
            `BSN ${persoon.bsn}`,
            persoon.geboortedatum ? `Geboren ${formatDate(persoon.geboortedatum)}` : '',
            adres ? `${adres.straat} ${adres.huisnummer}, ${adres.woonplaats}` : '',
          ].filter(Boolean),
        };
      } else {
        const object = await registers.object(id);
        const adres = object.adres;
        gevonden = {
          id, naam: object.naam, uitIntake: false,
          regels: [
            object.type,
            adres ? `${adres.straat}, ${adres.woonplaats}` : '',
            object.eigenaar ? `Eigenaar: ${object.eigenaar}` : '',
          ].filter(Boolean),
        };
      }
    } catch {
      // The intake usually registers a subject that is not (yet) in the BRP
      // or objecten register. That is not an error: fall back to what the
      // intake itself recorded. A hand-typed id that resolves to nothing
      // does get the error; nothing vouches for it.
      const p = prefillData?.prefill;
      const naam = p?.naam || p?.objectNaam;
      if (p && tekst(p.subjectId) === id && naam) {
        gevonden = {
          id, naam, uitIntake: true,
          regels: [`${type === 'PERSON' ? 'BSN' : 'Object-ID'} ${id}`, 'Niet in het register gevonden; gegevens uit de intake.'],
        };
      }
    }
    if (token !== lookupToken.current) return;
    setZoekt(false);
    if (!gevonden) {
      setSubjectFout('Geen resultaat gevonden voor dit ID');
      return;
    }
    setSubject(gevonden);
    zoekKoppelbaarPlan(id, type, caseKey, token);
  }

  /**
   * A returning subject may already have an active plan without dossier in
   * this case type: offer to link that plan instead of creating a second one.
   * Detection is best effort.
   */
  function zoekKoppelbaarPlan(id: string, type: SubjectType, key: string, token: number) {
    const domeinregister = subjectUrn(type, id);
    Promise.all([openplan.plannen.list({ status: 'actief' }), pdca.plandetails.list()])
      .then(([plannen, details]) => {
        if (token !== lookupToken.current) return;
        const perPlan = new Map(details.map(d => [d.planUuid, d]));
        const match = plannen.find(plan => {
          const d = perPlan.get(plan.uuid);
          return plan.domeinregister === domeinregister && !!d && !d.dossierId && d.caseDefinitionKey === key;
        });
        setKoppelbaar(match ?? null);
      })
      .catch(() => undefined);
  }

  async function koppelPlan() {
    if (!koppelbaar) return;
    const plan = koppelbaar;
    setKoppelBezig(true);
    setKoppelFout('');
    const velden = { planId: plan.uuid, planTitel: plan.titel, planStatus: plan.status };
    if (documentId && !intakeMode) {
      try {
        await createPlanApi.linkPlan(documentId, plan.uuid);
      } catch (e) {
        setKoppelFout(`Koppelen mislukt: ${foutmelding(e)}`);
        setKoppelBezig(false);
        return;
      }
      setMelding({ titel: 'Bestaand plan gekoppeld aan dit dossier.' });
      setKoppelbaar(null);
      setKoppelBezig(false);
      rondTaakAf({ velden, planDossierId: null });
      return;
    }
    // Standalone or intake mode: the app creates the plan dossier itself.
    let planDossierId: string;
    try {
      planDossierId = (await createPlanApi.createDossier(plan.uuid)).documentId;
    } catch (e) {
      setKoppelFout(`Dossier aanmaken mislukt: ${foutmelding(e)}`);
      setKoppelBezig(false);
      return;
    }
    setMelding({ titel: 'Plan-dossier aangemaakt voor het bestaande plan.' });
    setKoppelbaar(null);
    setKoppelBezig(false);
    if (documentId) {
      rondTaakAf({
        velden: { ...velden, planDossierId },
        planDossierId: planDossierId !== documentId ? planDossierId : null,
      });
    }
  }

  // ----------------------------------------------------- task submission

  function rondTaakAf(taak: TaakAfronding) {
    setAfronding(taak);
    setAfronden(true);
    setFout('');
    const resultaat = submitTask(docVelden(taak.velden));
    // GZAC closes the task modal and removes this iframe as soon as the task
    // is completed, so the navigation is posted in the same tick as the
    // submission: waiting for submitResult would never get here. Current
    // GZAC frontends do not act on `navigate` yet (the plugin SDK marks it
    // reserved); the jump to the plan dossier happens once the host does.
    if (taak.planDossierId) navigateHost(`/cases/${caseKey}/document/${taak.planDossierId}`);
    resultaat.then(r => {
      if (r.ok) return;
      const fouten = [...(r.errors ?? []), ...Object.values(r.fieldErrors ?? {})];
      setFout(`Afronden van de taak mislukt${fouten.length ? `: ${fouten.join('; ')}` : '.'}`);
      setAfronden(false);
    });
  }

  // ----------------------------------------------------------- validation

  const ontbreekt = useMemo(() => {
    const lijst: string[] = [];
    if (!subjectId.trim()) lijst.push(subjectType === 'PERSON' ? 'BSN' : 'object-ID');
    if (!titel.trim()) lijst.push('titel');
    if (!hoofddoel) lijst.push('hoofddoel');
    const zonderDoel = subdoelen.filter(d => !d.doelType).length;
    if (zonderDoel) lijst.push(`doel bij ${aantal(zonderDoel, 'subdoel', 'subdoelen')}`);
    return lijst;
  }, [subjectId, subjectType, titel, hoofddoel, subdoelen]);

  function focusEersteFout() {
    setTimeout(() => {
      document.querySelector<HTMLElement>('.cp-content [aria-invalid="true"], .cp-content [data-invalid="true"]')?.focus();
    }, 50);
  }

  async function maakPlan() {
    if (afronding) {
      rondTaakAf(afronding);
      return;
    }
    setPoging(true);
    if (ontbreekt.length > 0) {
      if (subdoelen.some(d => !d.doelType)) setOpen(o => ({ ...o, subdoelen: true }));
      focusEersteFout();
      return;
    }
    const id = subjectId.trim();
    const intakeNaam = tekst(prefillData?.prefill?.subjectId) === id
      ? prefillData?.prefill?.naam || prefillData?.prefill?.objectNaam || null
      : null;
    const request: PlanIntakeRequest = {
      // The backend decides the route from this dossier: plan case type =
      // link to this dossier; intake case type or standalone = create its own
      // plan dossier. Plan and dossier always come into existence together.
      documentId,
      caseDefinitionKey: caseKey,
      subjectType,
      subjectId: id,
      subjectNaam: subject?.id === id ? subject.naam : intakeNaam,
      titel: titel.trim(),
      notitie: toelichting.trim(),
      dienstverlening: dienstverlening || null,
      hoofddoel,
      subdoelgroep: subdoelgroep.trim() || null,
      weergaveStatus: prefillData?.prefill?.weergaveStatus || caseConfig?.planStatussen[0] || null,
      beginPositie: beginPositie || null,
      startdatum: startdatum || null,
      streefEinddatum: streefEinddatum || null,
      doelen: subdoelen.map(d => ({
        ...d.extra, doelType: d.doelType, beschrijving: d.beschrijving, status: d.status,
      })),
      instrumenten: instrumenten.filter(i => i.titel.trim()).map(i => ({
        ...i.extra, titel: i.titel.trim(), product: i.product, doel: i.doel, status: i.status,
      })),
      contactmomenten: evaluaties.filter(c => c.datum).map(c => ({
        ...c.extra, datum: c.datum, evaluatieType: c.evaluatieType, status: c.status, notitie: c.notitie,
      })),
      betrokkenen: betrokkenen.filter(b => b.naam.trim()).map(b => ({
        ...b.extra, naam: b.naam.trim(), rol: b.rol, organisatie: b.organisatie, email: b.email.trim(),
        procesbegeleider: b.procesbegeleider,
      })),
    };

    setBezig(true);
    setFout('');
    try {
      const res = await createPlanApi.createPlan(request);
      const waarschuwingen = res.warnings?.length ? `Let op: ${res.warnings.join('; ')}` : undefined;
      // Idempotent per intake: a repeat submission gets back the plan this
      // intake already produced instead of a second plan.
      setMelding(res.bestaand
        ? { titel: 'Deze intake had al een plan; de taak wordt daarop afgerond.' }
        : {
          titel: `Plan aangemaakt met ${aantal(res.doelen, 'subdoel', 'subdoelen')}, `
            + `${aantal(res.instrumenten, 'instrument', 'instrumenten')} en `
            + `${aantal(res.contactmomenten, 'evaluatie', 'evaluaties')}.`,
          tekst: waarschuwingen,
        });
      // Only the intake route gets a separate plan dossier; on a plan case
      // type the plan dossier is this dossier, which has no planDossierId.
      const planDossierId = res.planDossierId && res.planDossierId !== documentId ? res.planDossierId : null;
      rondTaakAf({
        velden: { planId: res.planUuid, planTitel: request.titel, planStatus: 'actief', planDossierId },
        planDossierId,
      });
    } catch (e) {
      setFout(foutmelding(e));
    } finally {
      setBezig(false);
    }
  }

  // ------------------------------------------------------------ list edits

  function wijzig<T extends { key: number }>(
    setter: React.Dispatch<React.SetStateAction<T[]>>, key: number, wijziging: Partial<T>,
  ) {
    setter(rijen => rijen.map(r => (r.key === key ? { ...r, ...wijziging } : r)));
  }

  function verwijder<T extends { key: number }>(setter: React.Dispatch<React.SetStateAction<T[]>>, key: number) {
    setter(rijen => rijen.filter(r => r.key !== key));
  }

  function voegToe(sectie: Sectie) {
    setOpen(o => ({ ...o, [sectie]: true }));
    if (sectie === 'subdoelen') {
      setSubdoelen(r => [...r, { key: sleutel(), extra: {}, doelType: '', beschrijving: '', status: 'gepland' }]);
    } else if (sectie === 'instrumenten') {
      setInstrumenten(r => [...r, { key: sleutel(), extra: {}, titel: '', product: '', doel: '', status: 'gepland' }]);
    } else if (sectie === 'evaluaties') {
      setEvaluaties(r => [...r, nieuweEvaluatie(caseConfig?.evaluatieTypen ?? STANDAARD_EVALUATIETYPEN)]);
    } else {
      setBetrokkenen(r => [...r, {
        key: sleutel(), extra: {}, naam: '', rol: '', organisatie: '', email: '', procesbegeleider: false,
      }]);
    }
  }

  function wijzigStartdatum(datum: string) {
    setStartdatum(datum);
    const eerste = plusWeken(datum, EERSTE_EVALUATIE_WEKEN);
    if (eerste) setEvaluaties(rijen => rijen.map(r => (r.auto ? { ...r, datum: eerste } : r)));
  }

  function wijzigEvaluatie(key: number, wijziging: Partial<EvaluatieRij>) {
    wijzig(setEvaluaties, key, { ...wijziging, auto: false });
  }

  function kiesProcesbegeleider(key: number, gekozen: boolean) {
    setBetrokkenen(rijen => rijen.map(r => ({ ...r, procesbegeleider: r.key === key ? gekozen : gekozen ? false : r.procesbegeleider })));
  }

  // --------------------------------------------------------- choice lists

  const doeltypen = reg?.doeltypen ?? [];
  const producttypen = reg?.producttypen ?? [];
  const hoofddoelOpties = hoofddoelTypen(doeltypen).map(t => t.doelType);
  const subdoelOpties = subdoelTypenVoorHoofddoel(doeltypen, hoofddoel, caseConfig?.subdoelMapping)
    .map(t => t.doelType);
  const doelgroep = doelgroepVoorSubject(subjectUrn(subjectType, subjectId.trim()));
  const gekozenSubdoelen = subdoelen.map(d => d.doelType).filter(Boolean);

  /** Keeps an already chosen value selectable, so narrowing the scope never silently empties a row. */
  const metGekozen = (opties: string[], gekozen: string) =>
    gekozen && !opties.includes(gekozen) ? [...opties, gekozen] : opties;

  function productOpties(rij: InstrumentRij): ProductType[] {
    const doelNaam = rij.doel || gekozenSubdoelen[0];
    const passend = producttypenVoorDoel(producttypen, doeltypen.find(t => t.doelType === doelNaam), doelgroep);
    const lijst = passend.length ? passend : producttypen;
    const gekozen = rij.product && !lijst.some(p => p.code === rij.product)
      ? producttypen.filter(p => p.code === rij.product)
      : [];
    return [...lijst, ...gekozen].sort((a, b) => a.naam.localeCompare(b.naam));
  }

  function kiesProduct(rij: InstrumentRij, code: string) {
    const product = producttypen.find(p => p.code === code);
    wijzig(setInstrumenten, rij.key, {
      product: code,
      ...(!rij.titel.trim() && product ? { titel: product.naam } : {}),
    });
  }

  // ---------------------------------------------------------------- render

  const verwijderKnop = (label: string, onClick: () => void) => (
    <Button
      kind="ghost" size="sm" hasIconOnly renderIcon={TrashCan} iconDescription={label}
      tooltipPosition="left" className="cp-rij-verwijder" onClick={onClick}
    />
  );

  const sectieTitel = (titelTekst: string, sectie: Sectie, samenvatting: string, waarschuwing?: string) => (
    <span className="cp-sectie-kop">
      <span className="cp-sectie-titel">{titelTekst}</span>
      {waarschuwing && <Tag size="sm" type="red">{waarschuwing}</Tag>}
      {!open[sectie] && <span className="cp-sectie-samenvatting">{samenvatting}</span>}
    </span>
  );

  const toggle = (sectie: Sectie) => ({ isOpen }: { isOpen: boolean }) =>
    setOpen(o => ({ ...o, [sectie]: isOpen }));

  const subdoelenZonderDoel = subdoelen.filter(d => !d.doelType).length;
  const procesbegeleider = betrokkenen.find(b => b.procesbegeleider);

  const samenvatting = [
    subject?.naam,
    aantal(subdoelen.length, 'subdoel', 'subdoelen'),
    aantal(instrumenten.length, 'instrument', 'instrumenten'),
    aantal(evaluaties.length, 'evaluatie', 'evaluaties'),
    aantal(betrokkenen.length, 'betrokkene', 'betrokkenen'),
  ].filter(Boolean).join(' · ');

  let voettekst: React.ReactNode = samenvatting;
  if (bezig) voettekst = 'Plan wordt aangemaakt…';
  else if (afronden) voettekst = 'Taak wordt afgerond…';
  else if (poging && ontbreekt.length > 0) {
    voettekst = <span className="cp-voet-fout">Nog nodig: {ontbreekt.join(', ')}</span>;
  }

  const meldingen = (
    <>
      {melding && (
        <InlineNotification
          kind={melding.tekst ? 'warning' : 'success'} lowContrast hideCloseButton
          title={melding.titel} subtitle={melding.tekst}
        />
      )}
      {fout && (
        <InlineNotification kind="error" lowContrast title={fout} onClose={() => { setFout(''); return false; }} />
      )}
    </>
  );

  let inhoud: React.ReactNode;
  let voet: React.ReactNode;

  if (!klaar) {
    inhoud = <InlineLoading description="Formulier laden…" />;
    voet = <Button kind="primary" size="md" disabled>Plan aanmaken</Button>;
  } else if (bestaandPlan) {
    inhoud = (
      <Tile className="cp-tile">
        Dit dossier heeft al een plan: <strong>{bestaandPlan.titel}</strong>.
      </Tile>
    );
    voet = (
      <>
        {meldingen}
        <div className="cp-voet-balk">
          <span className="cp-voet-tekst">{afronden ? 'Taak wordt afgerond…' : ''}</span>
          <Button
            kind="primary" size="md" disabled={afronden}
            onClick={() => rondTaakAf({
              velden: { planId: bestaandPlan.uuid, planTitel: bestaandPlan.titel, planStatus: bestaandPlan.status },
              planDossierId: null,
            })}
          >
            Taak afronden
          </Button>
        </div>
      </>
    );
  } else {
    inhoud = (
      <>
        {prefillMelding && (
          <InlineNotification
            kind={prefillMelding.soort} lowContrast title={prefillMelding.tekst}
            onClose={() => { setPrefillMelding(null); return false; }}
          />
        )}

        <section className="cp-blok">
          <h2 className="cp-kop">Inwoner/Object</h2>
          <RadioButtonGroup
            legendText="Soort" name="cp-subjecttype" valueSelected={subjectType}
            onChange={v => { setSubjectType(v === 'OBJECT' ? 'OBJECT' : 'PERSON'); wisSubject(); }}
          >
            <RadioButton id="cp-subject-persoon" labelText="Persoon" value="PERSON" />
            <RadioButton id="cp-subject-object" labelText="Object" value="OBJECT" />
          </RadioButtonGroup>
          <div className="cp-zoek">
            <TextInput
              id="cp-subject-id" labelText={subjectType === 'PERSON' ? 'BSN' : 'Object-ID'}
              placeholder={subjectType === 'PERSON' ? 'Bijv. 111222333' : ''}
              value={subjectId}
              invalid={(poging && !subjectId.trim()) || !!subjectFout}
              invalidText={subjectFout || 'Verplicht'}
              onChange={e => { setSubjectId(e.target.value); wisSubject(); }}
              onKeyDown={e => { if (e.key === 'Enter') zoekSubject(subjectId.trim(), subjectType); }}
            />
            <Button kind="tertiary" size="md" disabled={zoekt} onClick={() => zoekSubject(subjectId.trim(), subjectType)}>
              {zoekt ? 'Zoeken…' : 'Zoek'}
            </Button>
          </div>
          {subject && (
            <Tile className="cp-tile">
              <div className="cp-subject-naam">
                <strong>{subject.naam}</strong>
                {subject.uitIntake && <Tag size="sm" type="warm-gray">Uit intake</Tag>}
              </div>
              {subject.regels.map(r => <div key={r} className="cp-subject-regel">{r}</div>)}
            </Tile>
          )}
          {koppelbaar && (
            <Tile className="cp-tile cp-tile--koppel">
              <p>Er is al een plan zonder dossier voor dit subject: <strong>{koppelbaar.titel}</strong></p>
              {koppelFout && <p className="cp-voet-fout">{koppelFout}</p>}
              <Button kind="secondary" size="sm" disabled={koppelBezig || afronden} onClick={koppelPlan}>
                {documentId && !intakeMode ? 'Koppel aan dit dossier' : 'Maak dossier voor dit plan'}
              </Button>
            </Tile>
          )}
        </section>

        <section className="cp-blok">
          <h2 className="cp-kop">Plan</h2>
          <div className="cp-velden">
            <div className="cp-veld cp-veld--breed">
              <TextInput
                id="cp-titel" labelText="Titel" placeholder="Bijv. Ontwikkelplan werk" value={titel}
                invalid={poging && !titel.trim()} invalidText="Verplicht"
                onChange={e => setTitel(e.target.value)}
              />
            </div>
            <div className="cp-veld">
              <Select
                id="cp-dienstverlening" labelText="Dienstverlening" value={dienstverlening}
                disabled={!reg?.plantypen} onChange={e => setDienstverlening(e.target.value)}
              >
                {!reg?.plantypen && <SelectItem value="" text="Register onbereikbaar" />}
                {(reg?.plantypen ?? []).map(pt => (
                  <SelectItem key={pt.uuid} value={pt.type} text={dienstverleningLabel(pt.type)} />
                ))}
              </Select>
            </div>
            <div className="cp-veld">
              <Select
                id="cp-hoofddoel" labelText="Hoofddoel" value={hoofddoel}
                invalid={poging && !hoofddoel} invalidText="Kies een hoofddoel"
                onChange={e => setHoofddoel(e.target.value)}
              >
                <SelectItem value="" text={reg?.doeltypen ? 'Kies een hoofddoel' : 'Register onbereikbaar'} />
                {hoofddoelOpties.map(n => <SelectItem key={n} value={n} text={n} />)}
              </Select>
            </div>
            <div className="cp-veld cp-veld--breed">
              <TextArea
                id="cp-toelichting" labelText="Toelichting bij hoofddoel" rows={2}
                placeholder="In de woorden van de inwoner" value={toelichting}
                onChange={e => setToelichting(e.target.value)}
              />
            </div>
            <div className="cp-veld">
              <TextInput
                id="cp-subdoelgroep" labelText="Subdoelgroep" placeholder="Leeg indien onbekend"
                value={subdoelgroep} onChange={e => setSubdoelgroep(e.target.value)}
              />
            </div>
            <div className="cp-veld">
              <Select
                id="cp-positie" labelText="Positie" value={beginPositie}
                onChange={e => setBeginPositie(e.target.value)}
              >
                <SelectItem
                  value=""
                  text={caseConfigFout || !caseConfig?.posities.length ? 'Geen posities ingesteld' : 'Kies een positie'}
                />
                {(caseConfig?.posities ?? []).map(p => <SelectItem key={p} value={p} text={p} />)}
              </Select>
            </div>
            <div className="cp-veld">
              <TextInput
                id="cp-startdatum" type="date" labelText="Startdatum" value={startdatum}
                onChange={e => wijzigStartdatum(e.target.value)}
              />
            </div>
            <div className="cp-veld">
              <TextInput
                id="cp-einddatum" type="date" labelText="Beoogde einddatum" value={streefEinddatum}
                min={startdatum || undefined} onChange={e => setStreefEinddatum(e.target.value)}
              />
            </div>
          </div>
        </section>

        <Accordion align="start" className="cp-secties">
          <AccordionItem
            open={open.subdoelen} onHeadingClick={toggle('subdoelen')}
            title={sectieTitel(
              `Subdoelen (${subdoelen.length})`, 'subdoelen',
              subdoelen.map(d => d.doelType || 'Nog geen doel').join(', ') || 'Nog geen subdoelen',
              poging && subdoelenZonderDoel ? 'Doel ontbreekt' : undefined,
            )}
          >
            {subdoelen.length === 0 && <p className="cp-leeg">Nog geen subdoelen.</p>}
            {subdoelen.map((d, i) => (
              <div key={d.key} className="cp-rij">
                <div className="cp-rij-velden">
                  <div className="cp-veld">
                    <Select
                      id={`cp-subdoel-${d.key}-type`} size="sm" labelText={`Doel ${i + 1}`} value={d.doelType}
                      invalid={poging && !d.doelType} invalidText="Kies een doel"
                      onChange={e => wijzig(setSubdoelen, d.key, { doelType: e.target.value })}
                    >
                      <SelectItem value="" text="Kies een doel" />
                      {metGekozen(subdoelOpties, d.doelType).map(n => <SelectItem key={n} value={n} text={n} />)}
                    </Select>
                  </div>
                  <div className="cp-veld cp-veld--breed">
                    <TextInput
                      id={`cp-subdoel-${d.key}-beschrijving`} size="sm" labelText="Beschrijving" value={d.beschrijving}
                      onChange={e => wijzig(setSubdoelen, d.key, { beschrijving: e.target.value })}
                    />
                  </div>
                  <div className="cp-veld cp-veld--smal">
                    <Select
                      id={`cp-subdoel-${d.key}-status`} size="sm" labelText="Status" value={d.status}
                      onChange={e => wijzig(setSubdoelen, d.key, { status: e.target.value as DoelStatus })}
                    >
                      {DOEL_STATUSSEN.map(s => <SelectItem key={s.value} value={s.value} text={s.text} />)}
                    </Select>
                  </div>
                </div>
                {verwijderKnop('Subdoel verwijderen', () => verwijder(setSubdoelen, d.key))}
              </div>
            ))}
            <Button kind="ghost" size="sm" renderIcon={Add} onClick={() => voegToe('subdoelen')}>Subdoel toevoegen</Button>
          </AccordionItem>

          <AccordionItem
            open={open.instrumenten} onHeadingClick={toggle('instrumenten')}
            title={sectieTitel(
              `Instrumenten (${instrumenten.length})`, 'instrumenten',
              instrumenten.map(it => it.titel || 'Zonder titel').join(', ') || 'Nog geen instrumenten',
            )}
          >
            {instrumenten.length === 0 && <p className="cp-leeg">Nog geen instrumenten.</p>}
            {instrumenten.map(it => (
              <div key={it.key} className="cp-rij">
                <div className="cp-rij-velden">
                  <div className="cp-veld cp-veld--breed">
                    <TextInput
                      id={`cp-instrument-${it.key}-titel`} size="sm" labelText="Titel" value={it.titel}
                      onChange={e => wijzig(setInstrumenten, it.key, { titel: e.target.value })}
                    />
                  </div>
                  <div className="cp-veld">
                    <Select
                      id={`cp-instrument-${it.key}-doel`} size="sm" labelText="Bij subdoel" value={it.doel}
                      onChange={e => wijzig(setInstrumenten, it.key, { doel: e.target.value })}
                    >
                      <SelectItem value="" text="Eerste subdoel" />
                      {metGekozen([...new Set(gekozenSubdoelen)], it.doel).map(n => (
                        <SelectItem key={n} value={n} text={n} />
                      ))}
                    </Select>
                  </div>
                  <div className="cp-veld">
                    <Select
                      id={`cp-instrument-${it.key}-product`} size="sm" labelText="Instrumenttype" value={it.product}
                      onChange={e => kiesProduct(it, e.target.value)}
                    >
                      <SelectItem value="" text="Geen instrumenttype" />
                      {productOpties(it).map(p => <SelectItem key={p.code} value={p.code} text={p.naam} />)}
                    </Select>
                  </div>
                  <div className="cp-veld cp-veld--smal">
                    <Select
                      id={`cp-instrument-${it.key}-status`} size="sm" labelText="Status" value={it.status}
                      onChange={e => wijzig(setInstrumenten, it.key, { status: e.target.value as DoelStatus })}
                    >
                      {DOEL_STATUSSEN.map(s => <SelectItem key={s.value} value={s.value} text={s.text} />)}
                    </Select>
                  </div>
                </div>
                {verwijderKnop('Instrument verwijderen', () => verwijder(setInstrumenten, it.key))}
              </div>
            ))}
            <Button kind="ghost" size="sm" renderIcon={Add} onClick={() => voegToe('instrumenten')}>Instrument toevoegen</Button>
          </AccordionItem>

          <AccordionItem
            open={open.evaluaties} onHeadingClick={toggle('evaluaties')}
            title={sectieTitel(
              `Geplande evaluaties (${evaluaties.length})`, 'evaluaties',
              evaluaties
                .map(c => [evalTypeLabel(c.evaluatieType), c.datum ? formatDate(c.datum) : 'zonder datum'].join(' '))
                .join(', ') || 'Nog geen evaluaties',
            )}
          >
            {evaluaties.length === 0 && <p className="cp-leeg">Nog geen evaluaties.</p>}
            {evaluaties.map(c => (
              <div key={c.key} className="cp-rij">
                <div className="cp-rij-velden">
                  <div className="cp-veld">
                    <TextInput
                      id={`cp-evaluatie-${c.key}-datum`} size="sm" type="date" labelText="Datum" value={c.datum}
                      onChange={e => wijzigEvaluatie(c.key, { datum: e.target.value })}
                    />
                  </div>
                  <div className="cp-veld">
                    <Select
                      id={`cp-evaluatie-${c.key}-type`} size="sm" labelText="Type" value={c.evaluatieType}
                      onChange={e => wijzigEvaluatie(c.key, { evaluatieType: e.target.value })}
                    >
                      {metGekozen(caseConfig?.evaluatieTypen ?? STANDAARD_EVALUATIETYPEN, c.evaluatieType).map(t => (
                        <SelectItem key={t} value={t} text={evalTypeLabel(t)} />
                      ))}
                    </Select>
                  </div>
                  <div className="cp-veld cp-veld--smal">
                    <Select
                      id={`cp-evaluatie-${c.key}-status`} size="sm" labelText="Status" value={c.status}
                      onChange={e => wijzigEvaluatie(c.key, { status: e.target.value as EvaluatieStatus })}
                    >
                      {EVALUATIE_STATUSSEN.map(s => <SelectItem key={s.value} value={s.value} text={s.text} />)}
                    </Select>
                  </div>
                  <div className="cp-veld cp-veld--breed">
                    <TextInput
                      id={`cp-evaluatie-${c.key}-notitie`} size="sm" labelText="Notitie" value={c.notitie}
                      onChange={e => wijzigEvaluatie(c.key, { notitie: e.target.value })}
                    />
                  </div>
                </div>
                {verwijderKnop('Evaluatie verwijderen', () => verwijder(setEvaluaties, c.key))}
              </div>
            ))}
            <Button kind="ghost" size="sm" renderIcon={Add} onClick={() => voegToe('evaluaties')}>Evaluatie toevoegen</Button>
          </AccordionItem>

          <AccordionItem
            open={open.betrokkenen} onHeadingClick={toggle('betrokkenen')}
            title={sectieTitel(
              `Betrokkenen (${betrokkenen.length})`, 'betrokkenen',
              betrokkenen
                .map(b => (b.procesbegeleider ? `${b.naam || 'Zonder naam'} (procesbegeleider)` : b.naam || 'Zonder naam'))
                .join(', ') || 'Nog geen betrokkenen',
            )}
          >
            {betrokkenen.length === 0 && <p className="cp-leeg">Nog geen betrokkenen.</p>}
            {betrokkenen.length > 0 && !procesbegeleider && (
              <p className="cp-leeg">Nog geen procesbegeleider gekozen.</p>
            )}
            {betrokkenen.map(b => (
              <div key={b.key} className="cp-rij">
                <div className="cp-rij-velden">
                  <div className="cp-veld">
                    <TextInput
                      id={`cp-betrokkene-${b.key}-naam`} size="sm" labelText="Naam" value={b.naam}
                      onChange={e => wijzig(setBetrokkenen, b.key, { naam: e.target.value })}
                    />
                  </div>
                  <div className="cp-veld">
                    <Select
                      id={`cp-betrokkene-${b.key}-rol`} size="sm" labelText="Rol" value={b.rol}
                      onChange={e => wijzig(setBetrokkenen, b.key, { rol: e.target.value })}
                    >
                      <SelectItem value="" text="Kies een rol" />
                      {metGekozen((reg?.relatietypen ?? []).map(rt => rt.naam), b.rol).map(n => (
                        <SelectItem key={n} value={n} text={n} />
                      ))}
                    </Select>
                  </div>
                  <div className="cp-veld">
                    <TextInput
                      id={`cp-betrokkene-${b.key}-organisatie`} size="sm" labelText="Organisatie" value={b.organisatie}
                      onChange={e => wijzig(setBetrokkenen, b.key, { organisatie: e.target.value })}
                    />
                  </div>
                  <div className="cp-veld">
                    <TextInput
                      id={`cp-betrokkene-${b.key}-email`} size="sm" type="email" labelText="E-mail" value={b.email}
                      onChange={e => wijzig(setBetrokkenen, b.key, { email: e.target.value })}
                    />
                  </div>
                  <div className="cp-veld cp-veld--smal cp-veld--check">
                    <Checkbox
                      id={`cp-betrokkene-${b.key}-procesbegeleider`} labelText="Procesbegeleider"
                      checked={b.procesbegeleider}
                      onChange={(_, { checked }) => kiesProcesbegeleider(b.key, checked)}
                    />
                  </div>
                </div>
                {verwijderKnop('Betrokkene verwijderen', () => verwijder(setBetrokkenen, b.key))}
              </div>
            ))}
            <Button kind="ghost" size="sm" renderIcon={Add} onClick={() => voegToe('betrokkenen')}>Betrokkene toevoegen</Button>
          </AccordionItem>
        </Accordion>
      </>
    );
    voet = (
      <>
        {meldingen}
        <div className="cp-voet-balk">
          <span className="cp-voet-tekst">{voettekst}</span>
          <Button kind="primary" size="md" disabled={bezig || afronden || koppelBezig} onClick={maakPlan}>
            {afronding ? 'Taak afronden' : 'Plan aanmaken'}
          </Button>
        </div>
      </>
    );
  }

  return (
    <Theme theme={theme} className="cp-theme">
      {/* One layer up from the modal's surface, so the fields stand out against it. */}
      <Layer className="cp-laag">
        <main className="cp-content">{inhoud}</main>
        <footer className="cp-voet">{voet}</footer>
      </Layer>
    </Theme>
  );
}
