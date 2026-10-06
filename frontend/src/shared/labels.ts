/**
 * Labels for the Open Plan status domain (actief/afgerond/geannuleerd,
 * resultaat behaald/gefaald) plus the local PDCA actie workflow.
 */

const STATUS_LABELS: Record<string, string> = {
  // Open Plan statuses (plan / doel / instrument / contactmoment)
  actief: 'Actief', afgerond: 'Afgerond', geannuleerd: 'Geannuleerd',
  behaald: 'Behaald', gefaald: 'Niet behaald',
  // PDCA uitvoeringsstatus (overlay, refinement of actief)
  GEPLAND: 'Gepland', GESTART: 'Gestart',
  // Local actie workflow
  PLANNED: 'Gepland', IN_PROGRESS: 'In uitvoering', PENDING_REVIEW: 'Ter beoordeling',
  COMPLETED: 'Afgerond', REJECTED: 'Afgekeurd',
};

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

export function statusLabel(s: string): string { return STATUS_LABELS[s] || s; }
export function evalTypeLabel(s: string): string { return EVAL_TYPE_LABELS[s] || s; }
export function priorityLabel(s: string): string { return PRIORITY_LABELS[s] || s; }
export function assigneeTypeLabel(s: string): string { return ASSIGNEE_TYPE_LABELS[s] || s; }
export function doelgroepLabel(s: string): string { return DOELGROEP_LABELS[s] || s; }
export function dienstverleningLabel(s: string): string { return DIENSTVERLENING_LABELS[s] || s; }

/**
 * PDCA doel/instrument display status: Gepland, Gestart, Afgerond, Afgebroken.
 * The register only knows actief/afgerond/geannuleerd; the gepland/gestart
 * split comes from the overlay uitvoeringsstatus (doelen only).
 */
export function doelStatusLabel(status: string, resultaat?: string | null, uitvoeringsStatus?: string): string {
  if (status === 'geannuleerd') return 'Afgebroken';
  if (status === 'afgerond') return resultaat === 'gefaald' ? 'Niet behaald' : 'Afgerond';
  if (status === 'actief' && uitvoeringsStatus) return statusLabel(uitvoeringsStatus);
  return statusLabel(status);
}

export function doelStatusTag(status: string, resultaat?: string | null, uitvoeringsStatus?: string): string {
  if (status === 'geannuleerd') return 'red';
  if (status === 'afgerond') return resultaat === 'gefaald' ? 'red' : 'green';
  if (status === 'actief') return uitvoeringsStatus === 'GEPLAND' ? 'cool-gray' : 'blue';
  return 'gray';
}

/** Formats Open Plan ISO datetimes and plain dates as dd-mm-yyyy. */
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
