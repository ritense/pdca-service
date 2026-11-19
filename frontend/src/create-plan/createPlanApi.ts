/**
 * Endpoints of the create-plan task form (on the shared HTTP client): the
 * prefill from the intake dossier's content and the one-call plan creation.
 * A refusal is an `HttpError` carrying the backend's own `detail` (unknown
 * hoofddoel, positie outside the register, the one-active-plan rule), so the
 * form can tell a refusal (4xx) from an interrupted creation (409 "wordt al
 * aangemaakt", 5xx, no answer) that a retry completes.
 */

import { PDCA } from '../shared/api';
import { get, send } from '../shared/http';

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
  /** Case type of the plan resulting from this intake dossier; null when the dossier's case type is no intake. */
  planCaseDefinitionKey?: string | null;
  mapping?: Record<string, string>;
  prefill?: PlanPrefill | null;
}

export type DoelStatus = 'gepland' | 'gestart' | 'afgerond';
export type EvaluatieStatus = 'gepland' | 'afgerond';

export interface PlanIntakeRequest {
  /** The intake dossier the task form runs in. */
  documentId: string;
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
  /** Instrumenten to request once the plan exists: producttype code + subdoel (doeltype name; empty = the first). */
  instrumenten: { product: string; doel: string }[];
  contactmomenten: (Record<string, unknown> & { datum: string; evaluatieType: string; status: EvaluatieStatus; notitie: string })[];
  betrokkenen: (Record<string, unknown> & {
    naam: string; rol: string; organisatie: string; email: string; procesbegeleider: boolean;
  })[];
}

export interface PlanIntakeResponse {
  planUuid: string;
  documentId: string;
  /** Always set: a plan is only reported once its plan dossier exists. */
  planDossierId: string;
  doelen: number;
  /** Verzoeken filed for the instrumenten; each appears in the plan once its instrument dossier created it. */
  instrumentenAangevraagd: number;
  contactmomenten: number;
  betrokkenen?: number;
  warnings?: string[];
  /** This intake's plan was complete already; nothing was created. */
  bestaand?: boolean;
  /** This call completed a plan an earlier, interrupted attempt had started. */
  hervat?: boolean;
}

export const createPlanApi = {
  prefill: (documentId: string) =>
    get<PlanPrefillResponse>(`${PDCA}/dossiers/${documentId}/plan-prefill`),
  /**
   * Plan, doelen, contactmomenten, betrokkenen and the plan dossier in one
   * go, then a verzoek per instrument. Safe to repeat: a retry resumes an
   * interrupted attempt; 409 while another attempt is still running.
   */
  createPlan: (request: PlanIntakeRequest) =>
    send<PlanIntakeResponse>('POST', `${PDCA}/plan-intake`, request),
};
