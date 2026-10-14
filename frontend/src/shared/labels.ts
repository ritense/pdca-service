/**
 * Labels for the Open Plan status domain (actief/afgerond/geannuleerd,
 * resultaat behaald/gefaald), the local PDCA actie workflow and the other
 * domain values the plan tabs show.
 */

import type { VoortgangBetekenis, VoortgangStatusOptie } from './api';

const EVAL_TYPE_LABELS: Record<string, string> = {
  INTAKE: 'Intake', PROGRESS: 'Voortgang', EVALUATION: 'Evaluatie',
  INSPECTION: 'Inspectie', CRISIS: 'Crisis',
};

const PRIORITY_LABELS: Record<string, string> = {
  HIGH: 'Hoog', NORMAL: 'Normaal', LOW: 'Laag',
};

const ASSIGNEE_TYPE_LABELS: Record<string, string> = {
  PROFESSIONAL: 'Behandelaar', SUBJECT: 'Inwoner/Eigenaar', PROVIDER: 'Aanbieder',
};

const DOELGROEP_LABELS: Record<string, string> = {
  burgers: 'Burgers', interne_organisatie: 'Interne organisatie',
  samenwerkingspartners: 'Samenwerkingspartners',
  bedrijven_en_instellingen: 'Bedrijven en instellingen',
};

/** Plantype = the dienstverlening the plan falls under. */
const DIENSTVERLENING_LABELS: Record<string, string> = {
  werk: 'Terug naar werk', pip: 'Inburgering (PIP)', inkomen: 'Inkomensondersteuning',
};

export function evalTypeLabel(s: string): string { return EVAL_TYPE_LABELS[s] || s; }
export function priorityLabel(s: string): string { return PRIORITY_LABELS[s] || s; }
export function assigneeTypeLabel(s: string): string { return ASSIGNEE_TYPE_LABELS[s] || s; }
export function doelgroepLabel(s: string): string { return DOELGROEP_LABELS[s] || s; }
export function dienstverleningLabel(s: string): string { return DIENSTVERLENING_LABELS[s] || s; }

/** Carbon tag colours (the `type` of a Carbon Tag). */
export type TagKleur =
  | 'red' | 'magenta' | 'purple' | 'blue' | 'cyan' | 'teal' | 'green'
  | 'gray' | 'cool-gray' | 'warm-gray' | 'high-contrast' | 'outline';

/** A status as shown in the tabs: its label and the colour of its tag. */
export interface StatusWeergave {
  label: string;
  tag: TagKleur;
}

const GEPLAND: StatusWeergave = { label: 'Gepland', tag: 'cool-gray' };
const GESTART: StatusWeergave = { label: 'Gestart', tag: 'blue' };
const AFGEROND: StatusWeergave = { label: 'Afgerond', tag: 'green' };
const AFGEBROKEN: StatusWeergave = { label: 'Afgebroken', tag: 'red' };
const NIET_BEHAALD: StatusWeergave = { label: 'Niet behaald', tag: 'red' };

/**
 * The lifecycle shared by hoofddoel, subdoel and instrument (an actie
 * follows it too, see actieStatus): Gepland -> Gestart -> Afgerond |
 * Afgebroken, and Niet behaald for afgerond with resultaat gefaald. The
 * register knows only actief/afgerond/geannuleerd; for actief the caller
 * decides between Gepland and Gestart.
 */
function levenscyclus(
  item: { status: string; resultaat?: string | null }, gestart: boolean,
): StatusWeergave {
  if (item.status === 'geannuleerd') return AFGEBROKEN;
  if (item.status === 'afgerond') return item.resultaat === 'gefaald' ? NIET_BEHAALD : AFGEROND;
  return gestart ? GESTART : GEPLAND;
}

/**
 * Status of a subdoel or the hoofddoel. An active subdoel is Gestart once
 * its overlay uitvoeringsstatus says so and Gepland until then; an active
 * hoofddoel is Gestart unless its overlay explicitly says Gepland.
 */
export function doelStatus(
  doel: { status: string; resultaat?: string | null },
  details?: { uitvoeringsStatus?: string | null } | null,
  hoofddoel = false,
): StatusWeergave {
  const uitvoering = details?.uitvoeringsStatus;
  return levenscyclus(doel, hoofddoel ? uitvoering !== 'GEPLAND' : uitvoering === 'GESTART');
}

/** Status of an instrument: an active one is Gestart unless its overlay says Gepland. */
export function instrumentStatus(
  instrument: { status: string; resultaat?: string | null },
  details?: { uitvoeringsStatus?: string | null } | null,
): StatusWeergave {
  return levenscyclus(instrument, details?.uitvoeringsStatus !== 'GEPLAND');
}

const ACTIE_STATUSSEN: Record<string, StatusWeergave> = {
  PLANNED: GEPLAND,
  IN_PROGRESS: GESTART,
  PENDING_REVIEW: { label: 'Ter beoordeling', tag: 'warm-gray' },
  COMPLETED: AFGEROND,
  REJECTED: { label: 'Afgekeurd', tag: 'red' },
};

/** Status of an actie in the local workflow (PLANNED -> IN_PROGRESS -> PENDING_REVIEW -> COMPLETED/REJECTED). */
export function actieStatus(status: string): StatusWeergave {
  return ACTIE_STATUSSEN[status] ?? { label: status, tag: 'gray' };
}

/**
 * Status of a plan: while it runs (register actief) its configured
 * weergavestatus (Concept, Vastgesteld, ...); a closed plan is Afgerond or
 * Afgebroken (register geannuleerd).
 */
export function planStatus(status: string, weergaveStatus?: string | null): StatusWeergave {
  if (status === 'afgerond') return AFGEROND;
  if (status === 'geannuleerd') return AFGEBROKEN;
  return { label: weergaveStatus || 'Concept', tag: 'blue' };
}

/**
 * Status of a contactmoment, read from its klantcontact in Open Klant: one
 * that took place has indicatieContactGelukt true, a planned one null. A
 * planned one whose date has passed shows as Verlopen.
 */
export function contactmomentStatus(
  c: { indicatieContactGelukt: boolean | null }, geplandeDatum?: string | null,
): StatusWeergave {
  if (c.indicatieContactGelukt === true) return AFGEROND;
  if (c.indicatieContactGelukt === false) return { label: 'Niet gelukt', tag: 'red' };
  return isVerlopen(geplandeDatum) ? { label: 'Verlopen', tag: 'red' } : GEPLAND;
}

/**
 * Date of a contactmoment: for a planned one the planned date from the
 * overlay (its plaatsgevondenOp holds the same planned moment), otherwise
 * the moment it took place.
 */
export function contactmomentDatum(
  c: { indicatieContactGelukt: boolean | null; plaatsgevondenOp: string },
  details?: { geplandeDatum?: string | null } | null,
): string {
  return (c.indicatieContactGelukt == null && details?.geplandeDatum) || c.plaatsgevondenOp;
}

/** "1 actie", "3 acties". */
export function aantal(n: number, enkelvoud: string, meervoud: string): string {
  return `${n} ${n === 1 ? enkelvoud : meervoud}`;
}

/** Today as a plain date (yyyy-mm-dd) in local time. */
export function vandaag(): string {
  return isoDatum(new Date());
}

/** A plain date (yyyy-mm-dd) the given number of weeks from today. */
export function overWeken(weken: number): string {
  const datum = new Date();
  datum.setDate(datum.getDate() + weken * 7);
  return isoDatum(datum);
}

function isoDatum(datum: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${datum.getFullYear()}-${pad(datum.getMonth() + 1)}-${pad(datum.getDate())}`;
}

/** Whether a date (plain or ISO datetime) lies before today. */
export function isVerlopen(datum: string | null | undefined): boolean {
  return !!datum && datum.split('T')[0] < vandaag();
}

/** Formats register ISO datetimes and plain dates as dd-mm-yyyy. */
export function formatDate(dateStr: string | undefined | null): string {
  if (!dateStr) return '';
  const datePart = dateStr.split('T')[0];
  const parts = datePart.split('-');
  if (parts.length === 3) return `${parts[2]}-${parts[1]}-${parts[0]}`;
  return dateStr;
}

/**
 * Doeltype register: DoelType.doelType carries the name
 * of the fixed, defined doel; the category "Hoofddoel" marks hoofddoel
 * types and every other category is a thema (below). Legacy anonymous types
 * ("hoofddoel"/"subdoel" without a name) are kept out of the choice lists.
 */
type DoelTypeLike = { uuid: string; doelType: string; categorieen: { naam: string }[] };

/** Marker category separating hoofddoel types from subdoel types. */
const HOOFDDOEL_CATEGORIE = 'Hoofddoel';

export function doelTypeNaam(doelType: DoelTypeLike | undefined): string {
  return doelType?.doelType || '';
}

export function isHoofddoelType(doelType: DoelTypeLike): boolean {
  return doelType.categorieen?.some(c => c.naam === HOOFDDOEL_CATEGORIE) ?? false;
}

const isLegacyType = (t: DoelTypeLike) => t.doelType === 'hoofddoel' || t.doelType === 'subdoel';

export function hoofddoelTypen(doeltypen: DoelTypeLike[]): DoelTypeLike[] {
  return doeltypen.filter(t => isHoofddoelType(t) && !isLegacyType(t));
}

export function subdoelTypen(doeltypen: DoelTypeLike[]): DoelTypeLike[] {
  return doeltypen
    .filter(t => !isHoofddoelType(t) && !isLegacyType(t))
    .sort((a, b) => a.doelType.localeCompare(b.doelType));
}

/**
 * The subdoeltypen that may be chosen under a hoofddoel: the case config's
 * subdoelmapping (PhaseConfig.subdoelMapping, a JSON object keyed by
 * hoofddoeltype name) narrows the register's subdoeltypen. The register
 * cannot relate two doeltypen, so the catalog lives in the case config until
 * Open Plan models it.
 *
 * Unscoped — all subdoeltypen — when there is no mapping, no hoofddoel, or
 * no entry for this hoofddoel: the same "empty = always" convention as the
 * themas of a doeltype and the doeltypen of a bouwblokkoppeling.
 */
export function subdoelTypenVoorHoofddoel(
  doeltypen: DoelTypeLike[], hoofddoelNaam: string | null | undefined, subdoelMapping?: string | null,
): DoelTypeLike[] {
  const alle = subdoelTypen(doeltypen);
  if (!hoofddoelNaam || !subdoelMapping?.trim()) return alle;
  let toegestaan: string[] | undefined;
  try {
    toegestaan = (JSON.parse(subdoelMapping) as Record<string, string[]>)[hoofddoelNaam];
  } catch {
    return alle; // malformed config must not empty the choice list
  }
  if (!toegestaan?.length) return alle;
  return alle.filter(t => toegestaan.includes(t.doelType));
}

type ProductTypeLike = { themas: { naam: string }[]; doelgroep?: string };

/**
 * Themas of a doeltype: its categories minus the Hoofddoel marker. Doeltype
 * themas carry the same names as the Open Product themas (W&P portfolio
 * themes in the inwonerdomein, own themes in the objectdomein) and are the
 * link between a subdoel and the product catalog.
 */
export function doelTypeThemas(doelType: DoelTypeLike | undefined): string[] {
  return (doelType?.categorieen ?? []).map(c => c.naam).filter(n => n !== HOOFDDOEL_CATEGORIE);
}

/**
 * The producttypen that fit under a doel: thema overlap with the doeltype,
 * within the doelgroep of the plan subject. A doeltype without themas
 * scopes nothing and a producttype without doelgroep fits everywhere — the
 * same "empty = always" convention as the doeltypen of a bouwblokkoppeling.
 */
export function producttypenVoorDoel<T extends ProductTypeLike>(
  producttypen: T[], doelType: DoelTypeLike | undefined, doelgroep?: string,
): T[] {
  const themas = doelTypeThemas(doelType);
  return producttypen.filter(p =>
    (!doelgroep || !p.doelgroep || p.doelgroep === doelgroep) &&
    (themas.length === 0 || p.themas.some(t => themas.includes(t.naam))));
}

/**
 * Open Product doelgroep of a plan subject, read from the domeinregister URN
 * (urn:pdca:brp:persoon:<bsn> or urn:pdca:objecten:object:<id>); undefined
 * for a plan without subject, which scopes nothing.
 */
export function doelgroepVoorSubject(domeinregister: string | null | undefined): string | undefined {
  const resource = domeinregister?.split(':')[3];
  if (resource === 'persoon') return 'burgers';
  if (resource === 'object') return 'bedrijven_en_instellingen';
  return undefined;
}

/** What a voortgangsstatus means, as chosen in PDCA Beheer; the tag colour follows it. */
export const VOORTGANG_BETEKENISSEN: { betekenis: VoortgangBetekenis; label: string; tag: TagKleur }[] = [
  { betekenis: 'GAAT_GOED', label: 'Gaat goed', tag: 'green' },
  { betekenis: 'LET_OP', label: 'Let op', tag: 'magenta' },
  { betekenis: 'GAAT_NIET_GOED', label: 'Gaat niet goed', tag: 'red' },
];

/**
 * Names of the lifecycle statuses (register status plus gepland/gestart); a
 * voortgangsstatus may not reuse them. Mirrors VoortgangStatusOptie in the backend.
 */
export const GERESERVEERDE_STATUSNAMEN = ['gepland', 'gestart', 'afgerond', 'afgebroken', 'niet behaald', 'behaald'];

/** OP_KOERS -> "Op koers": the name of a status that is no longer in the plan's list. */
function codeAlsNaam(code: string): string {
  const tekst = code.toLowerCase().replace(/_/g, ' ');
  return tekst.charAt(0).toUpperCase() + tekst.slice(1);
}

/**
 * Voortgangsstatus of an active subdoel or hoofddoel (overlay
 * DoelDetails.voortgangStatus), looked up in the plan's list. A status that
 * was removed from the list keeps showing, in gray.
 */
export function voortgangStatus(code: string, opties: VoortgangStatusOptie[], naam?: string | null): StatusWeergave {
  const optie = opties.find(o => o.code === code);
  if (!optie) return { label: naam || codeAlsNaam(code), tag: 'gray' };
  return { label: optie.naam, tag: VOORTGANG_BETEKENISSEN.find(b => b.betekenis === optie.betekenis)?.tag ?? 'gray' };
}

/**
 * Plan changes as recorded in an evaluation (EvaluationChange.soort). The
 * tabs record them; the side panel and the Evaluaties tab describe them.
 */
const WIJZIGING_LABELS: Record<string, string> = {
  SUBDOEL_TOEGEVOEGD: 'Subdoel toegevoegd',
  SUBDOEL_BEWERKT: 'Subdoel bewerkt',
  SUBDOEL_GESTART: 'Subdoel gestart',
  SUBDOEL_AFGEROND: 'Subdoel afgerond',
  SUBDOEL_AFGEBROKEN: 'Subdoel afgebroken',
  SUBDOEL_VERWIJDERD: 'Subdoel verwijderd',
  VOORTGANG: 'Voortgang',
  INTERNE_NOTITIE: 'Interne notitie',
  EXTERNE_NOTITIE: 'Externe notitie',
  INSTRUMENT_TOEGEVOEGD: 'Instrument toegevoegd',
  INSTRUMENT_GESTART: 'Instrument gestart',
  INSTRUMENT_AFGEROND: 'Instrument afgerond',
  INSTRUMENT_AFGEBROKEN: 'Instrument afgebroken',
  INSTRUMENT_REGISTRATIE: 'Uren/effectiviteit',
  ACTIE_TOEGEVOEGD: 'Actie toegevoegd',
  ACTIE_STATUS: 'Actiestatus',
  BOUWBLOK_GESTART: 'Bouwblok-actie gestart',
  PRODUCT_AANGEVRAAGD: 'Instrument aangevraagd',
  HOOFDDOEL_GEWISSELD: 'Hoofddoel gewisseld',
  HOOFDDOEL_TOELICHTING: 'Toelichting bij hoofddoel',
  PLAN_STATUS: 'Status plan',
  WEERGAVESTATUS: 'Planstatus',
  POSITIE: 'Positie',
  SUBDOELGROEP: 'Subdoelgroep',
  PROCESBEGELEIDER: 'Procesbegeleider',
  BETROKKENE_TOEGEVOEGD: 'Verantwoordelijke toegevoegd',
  BETROKKENE_VERWIJDERD: 'Verantwoordelijke verwijderd',
};

/** Text changes: only the new text is shown, not old → new. */
const TEKST_WIJZIGINGEN = new Set(['INTERNE_NOTITIE', 'EXTERNE_NOTITIE', 'HOOFDDOEL_TOELICHTING']);

/** A text change is its own explanation; every other change can be given a reason. */
export function wijzigingHeeftReden(soort: string): boolean { return !TEKST_WIJZIGINGEN.has(soort); }

const SUBJECT_TYPE_LABELS: Record<string, string> = {
  PLAN: 'Plan', HOOFDDOEL: 'Hoofddoel', SUBDOEL: 'Subdoel', INSTRUMENT: 'Instrument', ACTIE: 'Actie',
};

export function subjectTypeLabel(s: string): string { return SUBJECT_TYPE_LABELS[s] || s; }

export function wijzigingOmschrijving(w: { soort: string; vanWaarde?: string | null; naarWaarde?: string | null }): string {
  const label = WIJZIGING_LABELS[w.soort] || w.soort;
  if (TEKST_WIJZIGINGEN.has(w.soort)) return w.naarWaarde ? `${label}: ${w.naarWaarde}` : `${label} verwijderd`;
  if (w.vanWaarde && w.naarWaarde && w.vanWaarde !== w.naarWaarde) return `${label}: ${w.vanWaarde} → ${w.naarWaarde}`;
  if (w.naarWaarde) return `${label}: ${w.naarWaarde}`;
  if (w.vanWaarde) return `${label}: ${w.vanWaarde} → leeg`;
  return label;
}

/** Changes grouped per plan onderdeel, in the order the onderdelen were first changed. */
export function wijzigingenPerOnderdeel<T extends { subjectType: string; subjectUuid: string; subjectTitel?: string | null }>(
  wijzigingen: T[],
): { key: string; subjectType: string; titel: string; wijzigingen: T[] }[] {
  const groepen = new Map<string, { key: string; subjectType: string; titel: string; wijzigingen: T[] }>();
  for (const w of wijzigingen) {
    const key = `${w.subjectType}:${w.subjectUuid}`;
    const groep = groepen.get(key) ?? { key, subjectType: w.subjectType, titel: '', wijzigingen: [] };
    groep.titel = w.subjectTitel || groep.titel || subjectTypeLabel(w.subjectType);
    groep.wijzigingen.push(w);
    groepen.set(key, groep);
  }
  return [...groepen.values()];
}
