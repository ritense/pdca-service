/**
 * Endpoints of the create-plan task form: the prefill from the dossier
 * content, the one-call plan creation and linking an existing plan. Errors
 * carry the backend's own `message` (unknown hoofddoel, positie outside the
 * register, the one-active-plan rule), so refusals read as plain sentences.
 */

const API_BASE = window.location.origin.startsWith('http') ? window.location.origin : 'http://localhost:7500';
const PDCA = `${API_BASE}/api/v1/pdca`;

export type SubjectType = 'PERSON' | 'OBJECT';

/** Prefill fields found in the dossier content through the case type's prefill mapping. */
export interface PlanPrefill {
  subjectType?: SubjectType;
  subjectId?: string;
  naam?: string;
  objectNaam?: string;
  titel?: string;
  notitie?: string;
  startdatum?: string;
  streefEinddatum?: string;
  beginPositie?: string;
  dienstverlening?: string;
  hoofddoel?: string;
  subdoelgroep?: string;
  weergaveStatus?: string;
  doelen?: Record<string, unknown>[];
  instrumenten?: Record<string, unknown>[];
  contactmomenten?: Record<string, unknown>[];
  betrokkenen?: Record<string, unknown>[];
}

export interface PlanPrefillResponse {
  documentId: string;
  caseDefinitionKey?: string | null;
  /** Case type of the plan resulting from this dossier; differs from caseDefinitionKey for an intake case type. */
  planCaseDefinitionKey?: string | null;
  mapping?: Record<string, string>;
  prefill?: PlanPrefill | null;
}

export type DoelStatus = 'gepland' | 'gestart' | 'afgerond';
export type EvaluatieStatus = 'gepland' | 'afgerond';

export interface PlanIntakeRequest {
  documentId: string | null;
  caseDefinitionKey: string;
  subjectType: SubjectType;
  subjectId: string;
  subjectNaam: string | null;
  titel: string;
  notitie: string;
  dienstverlening: string | null;
  hoofddoel: string;
  subdoelgroep: string | null;
  weergaveStatus: string | null;
  beginPositie: string | null;
  startdatum: string | null;
  streefEinddatum: string | null;
  doelen: (Record<string, unknown> & { doelType: string; beschrijving: string; status: DoelStatus })[];
  instrumenten: (Record<string, unknown> & { titel: string; product: string; doel: string; status: DoelStatus })[];
  contactmomenten: (Record<string, unknown> & { datum: string; evaluatieType: string; status: EvaluatieStatus; notitie: string })[];
  betrokkenen: (Record<string, unknown> & {
    naam: string; rol: string; organisatie: string; email: string; procesbegeleider: boolean;
  })[];
}

export interface PlanIntakeResponse {
  planUuid: string;
  documentId?: string | null;
  planDossierId?: string | null;
  doelen: number;
  instrumenten: number;
  contactmomenten: number;
  betrokkenen?: number;
  warnings?: string[];
  /** This intake already produced a plan; nothing was created. */
  bestaand?: boolean;
}

function foutTekst(status: number, body: string): string {
  try {
    const melding = JSON.parse(body).message;
    if (melding) return melding;
  } catch { /* not a JSON error body */ }
  return `HTTP ${status}${body ? ': ' + body : ''}`;
}

async function call<T>(url: string, method = 'GET', body?: unknown): Promise<T> {
  const resp = await fetch(url, {
    method,
    headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await resp.text().catch(() => '');
  if (!resp.ok) throw new Error(foutTekst(resp.status, text));
  return (text ? JSON.parse(text) : undefined) as T;
}

export const createPlanApi = {
  prefill: (documentId: string) =>
    call<PlanPrefillResponse>(`${PDCA}/dossiers/${documentId}/plan-prefill`),
  /** Plan, doelen, instrumenten, contactmomenten, betrokkenen and the plan dossier in one go. */
  createPlan: (request: PlanIntakeRequest) =>
    call<PlanIntakeResponse>(`${PDCA}/plan-intake`, 'POST', request),
  /** Links an existing plan to this dossier (plan = dossier 1:1). */
  linkPlan: (documentId: string, planUuid: string) =>
    call<unknown>(`${PDCA}/dossiers/${documentId}/plan`, 'PUT', { planUuid }),
  /** Creates the plan dossier for an existing plan. */
  createDossier: (planUuid: string) =>
    call<{ documentId: string; created: boolean }>(`${PDCA}/plans/${planUuid}/dossier`, 'POST'),
};
