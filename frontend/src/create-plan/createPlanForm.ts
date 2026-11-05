/**
 * The form state of "Plan aanmaken" as one reducer: the subject, the plan
 * fields and the four lists, the open/closed accordion sections and whether
 * a submit was attempted (which turns on the required-field markers).
 * Includes applying the intake's prefill and building the request.
 */

import type { DoelType, PlanType, ProductType, RelatieType } from '../shared/api';
import type { CaseConfig } from '../shared/caseConfig';
import { datumDeel, plusWeken, vandaag } from '../shared/dates';
import { aantal, hoofddoelTypen } from '../shared/labels';
import type { DoelStatus, EvaluatieStatus, PlanIntakeRequest, PlanPrefill, SubjectType } from './createPlanApi';

/** The first evaluation is planned this many weeks after the start of the plan. */
const EERSTE_EVALUATIE_WEKEN = 6;

/**
 * An editable list row. Prefill fields the form does not edit (e.g. a
 * toelichting) stay in `extra` and travel to the backend unchanged.
 */
type Rij<T> = T & { key: number; extra: Record<string, unknown> };

export type SubdoelRij = Rij<{ doelType: string; beschrijving: string; status: DoelStatus }>;
/** An instrument to request once the plan exists: producttype code + subdoel (doeltype name; empty = the first). */
export type InstrumentRij = Rij<{ product: string; doel: string }>;
/** `auto`: the default first evaluation, whose date follows the startdatum until the row is edited. */
export type EvaluatieRij = Rij<{ datum: string; evaluatieType: string; status: EvaluatieStatus; notitie: string; auto: boolean }>;
export type BetrokkeneRij = Rij<{ naam: string; rol: string; organisatie: string; email: string; procesbegeleider: boolean }>;

export type Sectie = 'subdoelen' | 'instrumenten' | 'evaluaties' | 'betrokkenen';

/** The register lists the form chooses from (values are register names; the backend looks them up). */
export interface Registers {
  plantypen: PlanType[];
  doeltypen: DoelType[];
  relatietypen: RelatieType[];
  producttypen: ProductType[];
}

export interface PlanVelden {
  titel: string;
  dienstverlening: string;
  hoofddoel: string;
  toelichting: string;
  subdoelgroep: string;
  beginPositie: string;
  startdatum: string;
  streefEinddatum: string;
}

export interface FormState extends PlanVelden {
  subjectType: SubjectType;
  subjectId: string;
  subdoelen: SubdoelRij[];
  instrumenten: InstrumentRij[];
  evaluaties: EvaluatieRij[];
  betrokkenen: BetrokkeneRij[];
  open: Record<Sectie, boolean>;
  /** A submit was attempted: missing required fields show as invalid. */
  poging: boolean;
}

type Lijsten = Pick<FormState, Sectie>;

export type FormActie =
  | { type: 'veld'; veld: keyof PlanVelden; waarde: string }
  | { type: 'subject'; subjectType?: SubjectType; subjectId?: string }
  | { type: 'toevoegen'; sectie: Sectie; evaluatieTypen: string[] }
  | { type: 'wijzigRij'; sectie: Sectie; key: number; wijziging: Record<string, unknown> }
  | { type: 'verwijderRij'; sectie: Sectie; key: number }
  | { type: 'procesbegeleider'; key: number; gekozen: boolean }
  | { type: 'open'; sectie: Sectie; open: boolean }
  | { type: 'poging' }
  | { type: 'vervang'; state: FormState };

let volgendeSleutel = 0;
const sleutel = () => ++volgendeSleutel;

const tekst = (v: unknown): string => (typeof v === 'string' ? v : v == null ? '' : String(v));

export function beginState(): FormState {
  return {
    subjectType: 'PERSON', subjectId: '',
    titel: '', dienstverlening: '', hoofddoel: '', toelichting: '', subdoelgroep: '', beginPositie: '',
    startdatum: vandaag(), streefEinddatum: '',
    subdoelen: [], instrumenten: [], evaluaties: [], betrokkenen: [],
    open: { subdoelen: true, instrumenten: true, evaluaties: true, betrokkenen: true },
    poging: false,
  };
}

function nieuweEvaluatie(evaluatieTypen: string[], datum = '', auto = false): EvaluatieRij {
  return {
    key: sleutel(), extra: {}, datum, notitie: '', status: 'gepland', auto,
    evaluatieType: evaluatieTypen.includes('EVALUATION') ? 'EVALUATION' : evaluatieTypen[0] ?? 'EVALUATION',
  };
}

function nieuweRij(sectie: Sectie, evaluatieTypen: string[]): Lijsten[Sectie][number] {
  if (sectie === 'subdoelen') return { key: sleutel(), extra: {}, doelType: '', beschrijving: '', status: 'gepland' };
  if (sectie === 'instrumenten') return { key: sleutel(), extra: {}, product: '', doel: '' };
  if (sectie === 'evaluaties') return nieuweEvaluatie(evaluatieTypen);
  return { key: sleutel(), extra: {}, naam: '', rol: '', organisatie: '', email: '', procesbegeleider: false };
}

export function formReducer(state: FormState, actie: FormActie): FormState {
  switch (actie.type) {
    case 'veld': {
      if (actie.veld !== 'startdatum') return { ...state, [actie.veld]: actie.waarde };
      // The default first evaluation follows the startdatum until it is edited.
      const eerste = plusWeken(actie.waarde, EERSTE_EVALUATIE_WEKEN);
      return {
        ...state, startdatum: actie.waarde,
        evaluaties: eerste ? state.evaluaties.map(r => (r.auto ? { ...r, datum: eerste } : r)) : state.evaluaties,
      };
    }
    case 'subject':
      return { ...state, subjectType: actie.subjectType ?? state.subjectType, subjectId: actie.subjectId ?? state.subjectId };
    case 'toevoegen':
      return {
        ...state,
        open: { ...state.open, [actie.sectie]: true },
        [actie.sectie]: [...state[actie.sectie], nieuweRij(actie.sectie, actie.evaluatieTypen)],
      };
    case 'wijzigRij': {
      // Editing the default first evaluation detaches its date from the startdatum.
      const wijziging = actie.sectie === 'evaluaties' ? { ...actie.wijziging, auto: false } : actie.wijziging;
      return {
        ...state,
        [actie.sectie]: (state[actie.sectie] as { key: number }[]).map(r => (r.key === actie.key ? { ...r, ...wijziging } : r)),
      };
    }
    case 'verwijderRij':
      return { ...state, [actie.sectie]: (state[actie.sectie] as { key: number }[]).filter(r => r.key !== actie.key) };
    case 'procesbegeleider':
      // Exactly one procesbegeleider per plan.
      return {
        ...state,
        betrokkenen: state.betrokkenen.map(r => ({
          ...r, procesbegeleider: r.key === actie.key ? actie.gekozen : actie.gekozen ? false : r.procesbegeleider,
        })),
      };
    case 'open':
      return { ...state, open: { ...state.open, [actie.sectie]: actie.open } };
    case 'poging':
      return {
        ...state,
        poging: true,
        open: {
          ...state.open,
          subdoelen: state.open.subdoelen || state.subdoelen.some(d => !d.doelType),
          instrumenten: state.open.instrumenten || state.instrumenten.some(i => !i.product),
        },
      };
    case 'vervang':
      return actie.state;
  }
}

/** What is still missing before the plan can be created; empty when nothing is. */
export function ontbreekt(state: FormState): string[] {
  const lijst: string[] = [];
  if (!state.subjectId.trim()) lijst.push(state.subjectType === 'PERSON' ? 'BSN' : 'object-ID');
  if (!state.titel.trim()) lijst.push('titel');
  if (!state.hoofddoel) lijst.push('hoofddoel');
  const zonderDoel = state.subdoelen.filter(d => !d.doelType).length;
  if (zonderDoel) lijst.push(`doel bij ${aantal(zonderDoel, 'subdoel', 'subdoelen')}`);
  const zonderProduct = state.instrumenten.filter(i => !i.product).length;
  if (zonderProduct) lijst.push(`instrumenttype bij ${aantal(zonderProduct, 'instrument', 'instrumenten')}`);
  return lijst;
}

/** The prefill object minus the fields the form edits itself. */
function overige(rij: Record<string, unknown>, bekend: string[]): Record<string, unknown> {
  const extra = { ...rij };
  bekend.forEach(veld => delete extra[veld]);
  return extra;
}

function prefillRijen(waarde: unknown): Record<string, unknown>[] {
  return Array.isArray(waarde) ? waarde.filter((r): r is Record<string, unknown> => !!r && typeof r === 'object') : [];
}

/** Status of a prefilled subdoel; without a known one it is gepland. */
function doelStatusUit(status: unknown): DoelStatus {
  return status === 'gestart' || status === 'afgerond' ? status : 'gepland';
}

/**
 * The form filled from the intake. Values outside the registers are not
 * taken over (the backend would reject them at submit time) but named in the
 * notice; an intake without titel or startdatum gets sensible defaults.
 */
export function vulVoor(p: PlanPrefill, registers: Registers, cfg: CaseConfig):
  { state: FormState; melding: { soort: 'info' | 'warning'; tekst: string } | null } {
  const state = beginState();
  let velden = 0;
  const nietGezet: string[] = [];

  if (p.subjectType === 'PERSON' || p.subjectType === 'OBJECT') state.subjectType = p.subjectType;
  state.subjectId = tekst(p.subjectId).trim();
  if (state.subjectId) velden++;
  if (p.titel) velden++;
  state.titel = p.titel || (p.naam ? `Plan – ${p.naam}` : '');
  if (p.notitie) { state.toelichting = p.notitie; velden++; }
  if (p.startdatum) { state.startdatum = datumDeel(p.startdatum); velden++; }
  if (p.streefEinddatum) { state.streefEinddatum = datumDeel(p.streefEinddatum); velden++; }
  if (p.subdoelgroep) { state.subdoelgroep = p.subdoelgroep; velden++; }

  if (p.beginPositie) {
    if (cfg.positieTypen.includes(p.beginPositie)) { state.beginPositie = p.beginPositie; velden++; }
    else nietGezet.push(`positie "${p.beginPositie}"`);
  }
  const plantypen = registers.plantypen.map(pt => pt.type);
  if (p.dienstverlening && plantypen.includes(p.dienstverlening)) {
    state.dienstverlening = p.dienstverlening;
    velden++;
  } else {
    if (p.dienstverlening) nietGezet.push(`dienstverlening "${p.dienstverlening}"`);
    state.dienstverlening = plantypen[0] ?? '';
  }
  if (p.hoofddoel) {
    if (hoofddoelTypen(registers.doeltypen).some(t => t.doelType === p.hoofddoel)) { state.hoofddoel = p.hoofddoel; velden++; }
    else nietGezet.push(`hoofddoel "${p.hoofddoel}"`);
  }

  const bekend = registers.doeltypen.map(t => t.doelType);
  state.subdoelen = prefillRijen(p.doelen).map(d => ({
    key: sleutel(),
    extra: overige(d, ['doelType', 'beschrijving', 'status', 'voortgang']),
    doelType: tekst(d.doelType),
    beschrijving: tekst(d.beschrijving),
    status: doelStatusUit(d.status),
  }));
  state.subdoelen.forEach(d => { if (d.doelType && !bekend.includes(d.doelType)) nietGezet.push(`subdoel "${d.doelType}"`); });
  // Only producttype + subdoel matter for a request; anything else an
  // intake records about an instrument is not carried over.
  state.instrumenten = prefillRijen(p.instrumenten).map(i => ({ key: sleutel(), extra: {}, product: tekst(i.product), doel: tekst(i.doel) }));
  state.instrumenten.forEach(i => {
    if (i.product && !registers.producttypen.some(pt => pt.code === i.product)) nietGezet.push(`instrument "${i.product}"`);
  });
  const evaluaties: EvaluatieRij[] = prefillRijen(p.contactmomenten).map(c => ({
    key: sleutel(),
    extra: overige(c, ['datum', 'evaluatieType', 'status', 'notitie']),
    datum: c.datum ? datumDeel(tekst(c.datum)) : '',
    evaluatieType: tekst(c.evaluatieType) || nieuweEvaluatie(cfg.evaluatieTypen).evaluatieType,
    status: c.status === 'afgerond' ? 'afgerond' : 'gepland',
    notitie: tekst(c.notitie),
    auto: false,
  }));
  state.evaluaties = evaluaties.length > 0 ? evaluaties
    : [nieuweEvaluatie(cfg.evaluatieTypen, plusWeken(state.startdatum, EERSTE_EVALUATIE_WEKEN), true)];
  let procesbegeleiderGekozen = false;
  state.betrokkenen = prefillRijen(p.betrokkenen).map(b => {
    // Exactly one procesbegeleider per plan: the first one marked wins.
    const procesbegeleider = b.procesbegeleider === true && !procesbegeleiderGekozen;
    if (procesbegeleider) procesbegeleiderGekozen = true;
    return {
      key: sleutel(),
      extra: overige(b, ['naam', 'rol', 'organisatie', 'email', 'procesbegeleider']),
      naam: tekst(b.naam), rol: tekst(b.rol), organisatie: tekst(b.organisatie), email: tekst(b.email), procesbegeleider,
    };
  });
  velden += state.subdoelen.length + state.instrumenten.length + evaluaties.length + state.betrokkenen.length;
  state.open = {
    subdoelen: state.subdoelen.length === 0 || state.subdoelen.some(d => !d.doelType),
    instrumenten: state.instrumenten.length === 0,
    evaluaties: state.evaluaties.length === 0,
    betrokkenen: state.betrokkenen.length === 0,
  };

  if (velden === 0 && nietGezet.length === 0) return { state, melding: null };
  let bericht = `Voorgevuld vanuit de intake van dit dossier (${aantal(velden, 'veld', 'velden')}). Controleer en vul aan waar nodig.`;
  if (nietGezet.length > 0) {
    bericht += ` Let op: ${nietGezet.join(', ')} uit de intake staat niet in het register. Kies zelf een waarde of vul het register aan.`;
  }
  return { state, melding: { soort: nietGezet.length > 0 ? 'warning' : 'info', tekst: bericht } };
}

/** The plan-intake request for the intake dossier [documentId]. */
export function maakRequest(state: FormState, documentId: string, subjectNaam: string | null, weergaveStatus: string | null): PlanIntakeRequest {
  return {
    // The backend creates the plan dossier of the plan case type this
    // intake's configuration names: plan and dossier come into existence together.
    documentId,
    subjectType: state.subjectType,
    subjectId: state.subjectId.trim(),
    subjectNaam,
    titel: state.titel.trim(),
    notitie: state.toelichting.trim(),
    dienstverlening: state.dienstverlening || null,
    hoofddoel: state.hoofddoel,
    subdoelgroep: state.subdoelgroep.trim() || null,
    weergaveStatus,
    beginPositie: state.beginPositie || null,
    startdatum: state.startdatum || null,
    streefEinddatum: state.streefEinddatum || null,
    doelen: state.subdoelen.map(d => ({ ...d.extra, doelType: d.doelType, beschrijving: d.beschrijving, status: d.status })),
    instrumenten: state.instrumenten.map(i => ({ product: i.product, doel: i.doel })),
    contactmomenten: state.evaluaties.filter(c => c.datum).map(c => ({
      ...c.extra, datum: c.datum, evaluatieType: c.evaluatieType, status: c.status, notitie: c.notitie,
    })),
    betrokkenen: state.betrokkenen.filter(b => b.naam.trim()).map(b => ({
      ...b.extra, naam: b.naam.trim(), rol: b.rol, organisatie: b.organisatie, email: b.email.trim(),
      procesbegeleider: b.procesbegeleider,
    })),
  };
}
