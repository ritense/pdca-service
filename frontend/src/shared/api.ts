/**
 * Direct API access to the Maykin registers plus the small PDCA overlay.
 *
 * - Open Plan  (plannen/doelen/instrumenten/contactmomenten/personen/typen)
 *   is consumed 1:1 via the backend's token-injecting proxy at /openplan/...
 *   camelCase fields, statuses actief/afgerond/geannuleerd, resultaten
 *   behaald/gefaald, ISO datetimes, paginated {count, results}.
 * - Open Product (producttypen) likewise via /openproduct/... (snake_case).
 * - The PDCA overlay (/api/v1/pdca) stores only what the registers don't
 *   model: fase/voortgang/notities per doel and instrument,
 *   evaluatietype/actiepunten per contactmoment, completed evaluations with
 *   their plan changes, acties, betrokkenen and phase-configs.
 */

import type { EvaluationChange, EvaluationSession } from './evaluationSession';

// The bundles are always served by the PDCA app itself (also when iframed
// into GZAC), so the app origin is the API origin. Fallback for non-http
// contexts matches server.port in application.yml.
const API_BASE = window.location.origin.startsWith('http') ? window.location.origin : 'http://localhost:7500';
const OPENPLAN = `${API_BASE}/openplan/plannen/api/v0`;
const OPENPRODUCT = `${API_BASE}/openproduct/producttypen/api/v1`;
const PDCA = `${API_BASE}/api/v1/pdca`;

async function request<T>(url: string, options?: RequestInit): Promise<T> {
  const resp = await fetch(url, {
    headers: { 'Content-Type': 'application/json', ...options?.headers },
    ...options,
  });
  if (!resp.ok) {
    let detail = `HTTP ${resp.status}`;
    try {
      const body = await resp.json();
      if (body.invalidParams) detail += ': ' + body.invalidParams.map((p: any) => `${p.name}: ${p.reason}`).join(', ');
      else if (body.detail) detail += ': ' + body.detail;
      else if (body.message) detail += ': ' + body.message;
    } catch { /* non-json body */ }
    throw new Error(detail);
  }
  if (resp.status === 204) return undefined as T;
  return resp.json();
}

/** Fetches all pages of a paginated register listing. */
async function listAll<T>(url: string, params: Record<string, string> = {}): Promise<T[]> {
  const results: T[] = [];
  for (let page = 1; page <= 20; page++) {
    const qs = new URLSearchParams({ ...params, page: String(page) });
    const body = await request<Paginated<T>>(`${url}?${qs}`);
    results.push(...body.results);
    if (!body.next) break;
  }
  return results;
}

/** URN this app mints: urn:pdca:<component>:<resource>:<id> */
export const urn = (component: string, resource: string, id: string) => `urn:pdca:${component}:${resource}:${id}`;
export const urnId = (u: string | null | undefined) => (u ? u.split(':').pop() ?? null : null);

// ------------------------------------------------------------ Open Plan API

export const openplan = {
  plannen: {
    list: (params?: Record<string, string>) => listAll<Plan>(`${OPENPLAN}/plan`, params),
    get: (uuid: string) => request<Plan>(`${OPENPLAN}/plan/${uuid}`),
    create: (data: Partial<Plan> & { plantypeUuid: string; overkoepelendPlanUuid: string }) =>
      request<Plan>(`${OPENPLAN}/plan`, { method: 'POST', body: JSON.stringify(data) }),
    update: (uuid: string, data: object) =>
      request<Plan>(`${OPENPLAN}/plan/${uuid}`, { method: 'PATCH', body: JSON.stringify(data) }),
    delete: (uuid: string) => request<void>(`${OPENPLAN}/plan/${uuid}`, { method: 'DELETE' }),
  },
  doelen: {
    listByPlan: (planUuid: string) => listAll<Doel>(`${OPENPLAN}/doel`, { plannen__uuid: planUuid }),
    create: (data: object) => request<Doel>(`${OPENPLAN}/doel`, { method: 'POST', body: JSON.stringify(data) }),
    update: (uuid: string, data: object) =>
      request<Doel>(`${OPENPLAN}/doel/${uuid}`, { method: 'PATCH', body: JSON.stringify(data) }),
    delete: (uuid: string) => request<void>(`${OPENPLAN}/doel/${uuid}`, { method: 'DELETE' }),
  },
  instrumenten: {
    listByDoelen: (doelUuids: string[]) =>
      doelUuids.length === 0
        ? Promise.resolve([] as Instrument[])
        : listAll<Instrument>(`${OPENPLAN}/instrument`, { doelen__uuid__in: doelUuids.join(',') }),
    create: (data: object) => request<Instrument>(`${OPENPLAN}/instrument`, { method: 'POST', body: JSON.stringify(data) }),
    update: (uuid: string, data: object) =>
      request<Instrument>(`${OPENPLAN}/instrument/${uuid}`, { method: 'PATCH', body: JSON.stringify(data) }),
    delete: (uuid: string) => request<void>(`${OPENPLAN}/instrument/${uuid}`, { method: 'DELETE' }),
  },
  contactmomenten: {
    listByPlan: (planUuid: string) => listAll<Contactmoment>(`${OPENPLAN}/contactmoment`, { plan__uuid: planUuid }),
    create: (data: object) => request<Contactmoment>(`${OPENPLAN}/contactmoment`, { method: 'POST', body: JSON.stringify(data) }),
    update: (uuid: string, data: object) =>
      request<Contactmoment>(`${OPENPLAN}/contactmoment/${uuid}`, { method: 'PATCH', body: JSON.stringify(data) }),
    delete: (uuid: string) => request<void>(`${OPENPLAN}/contactmoment/${uuid}`, { method: 'DELETE' }),
  },
  personen: {
    list: () => listAll<Persoon>(`${OPENPLAN}/persoon`),
    create: (data: object) => request<Persoon>(`${OPENPLAN}/persoon`, { method: 'POST', body: JSON.stringify(data) }),
    /** Open Plan has no bsn filter; find client-side, create when missing. */
    ensure: async (bsn: string): Promise<Persoon> => {
      const bestaand = (await openplan.personen.list()).find(p => p.bsn === bsn);
      if (bestaand) return bestaand;
      return openplan.personen.create({
        bsn,
        klant: urn('openklant', 'klant', bsn),
        persoonsprofiel: urn('profielen', 'persoonsprofiel', bsn),
      });
    },
  },
  doeltypen: { list: () => listAll<DoelType>(`${OPENPLAN}/doeltype`) },
  instrumenttypen: { list: () => listAll<InstrumentType>(`${OPENPLAN}/instrumenttype`) },
  relatietypen: { list: () => listAll<RelatieType>(`${OPENPLAN}/relatietype`) },
  plantypen: { list: () => listAll<PlanType>(`${OPENPLAN}/plantype`) },
  overkoepelendplannen: { list: () => listAll<OverkoepelendPlan>(`${OPENPLAN}/overkoepelendplan`) },
};

// --------------------------------------------------------- Open Product API

export const openproduct = {
  producttypen: {
    list: () => listAll<ProductType>(`${OPENPRODUCT}/producttypen`),
    get: (uuid: string) => request<ProductType>(`${OPENPRODUCT}/producttypen/${uuid}`),
  },
};

// ------------------------------------------------------------- PDCA overlay

export const pdca = {
  plandetails: {
    list: (caseDefinitionKey?: string) =>
      request<PlanDetails[]>(`${PDCA}/plandetails${caseDefinitionKey ? `?caseDefinitionKey=${caseDefinitionKey}` : ''}`),
    get: (planUuid: string) => request<PlanDetails>(`${PDCA}/plandetails/${planUuid}`),
    upsert: (planUuid: string, data: Partial<PlanDetails>) =>
      request<PlanDetails>(`${PDCA}/plandetails/${planUuid}`, { method: 'PUT', body: JSON.stringify(data) }),
    delete: (planUuid: string) => request<void>(`${PDCA}/plandetails/${planUuid}`, { method: 'DELETE' }),
  },
  doeldetails: {
    listByPlan: (planUuid: string) => request<DoelDetails[]>(`${PDCA}/doeldetails?planUuid=${planUuid}`),
    upsert: (doelUuid: string, data: Partial<DoelDetails>) =>
      request<DoelDetails>(`${PDCA}/doeldetails/${doelUuid}`, { method: 'PUT', body: JSON.stringify(data) }),
    delete: (doelUuid: string) => request<void>(`${PDCA}/doeldetails/${doelUuid}`, { method: 'DELETE' }),
  },
  contactmomentdetails: {
    listByPlan: (planUuid: string) => request<ContactmomentDetails[]>(`${PDCA}/contactmomentdetails?planUuid=${planUuid}`),
    upsert: (contactmomentUuid: string, data: Partial<ContactmomentDetails>) =>
      request<ContactmomentDetails>(`${PDCA}/contactmomentdetails/${contactmomentUuid}`, { method: 'PUT', body: JSON.stringify(data) }),
    delete: (contactmomentUuid: string) =>
      request<void>(`${PDCA}/contactmomentdetails/${contactmomentUuid}`, { method: 'DELETE' }),
  },
  instrumentdetails: {
    listByPlan: (planUuid: string) => request<InstrumentDetails[]>(`${PDCA}/instrumentdetails?planUuid=${planUuid}`),
    upsert: (instrumentUuid: string, data: Partial<InstrumentDetails>) =>
      request<InstrumentDetails>(`${PDCA}/instrumentdetails/${instrumentUuid}`, { method: 'PUT', body: JSON.stringify(data) }),
    delete: (instrumentUuid: string) =>
      request<void>(`${PDCA}/instrumentdetails/${instrumentUuid}`, { method: 'DELETE' }),
  },
  /** Completed evaluations with their plan changes; running ones are never listed here. */
  evaluaties: {
    listByPlan: (planUuid: string) => request<CompletedEvaluation[]>(`${PDCA}/evaluaties?planUuid=${planUuid}`),
  },
  acties: {
    listByPlan: (planUuid: string) => request<Actie[]>(`${PDCA}/acties?planUuid=${planUuid}`),
    create: (data: Partial<Actie> & { doelUuid: string; title: string }) =>
      request<Actie>(`${PDCA}/acties`, { method: 'POST', body: JSON.stringify(data) }),
    update: (id: string, data: Partial<Actie>) =>
      request<Actie>(`${PDCA}/acties/${id}`, { method: 'PATCH', body: JSON.stringify(data) }),
    approve: (id: string) => request<Actie>(`${PDCA}/acties/${id}/approve`, { method: 'POST' }),
    reject: (id: string) => request<Actie>(`${PDCA}/acties/${id}/reject`, { method: 'POST' }),
    delete: (id: string) => request<void>(`${PDCA}/acties/${id}`, { method: 'DELETE' }),
  },
  betrokkenen: {
    listByPlan: (planUuid: string) => request<Betrokkene[]>(`${PDCA}/betrokkenen?planUuid=${planUuid}`),
    create: (data: Partial<Betrokkene> & { planUuid: string; name: string; role: string }) =>
      request<Betrokkene>(`${PDCA}/betrokkenen`, { method: 'POST', body: JSON.stringify(data) }),
    delete: (id: string) => request<void>(`${PDCA}/betrokkenen/${id}`, { method: 'DELETE' }),
  },
  dossiers: {
    /** This dossier's plan (direct link in the overlay); 404 when there is none. */
    getPlan: (documentId: string) => request<PlanDetails>(`${PDCA}/dossiers/${documentId}/plan`),
    /** Explicitly link a plan to this dossier (task-form route); 409 on conflict. */
    linkPlan: (documentId: string, planUuid: string) =>
      request<PlanDetails>(`${PDCA}/dossiers/${documentId}/plan`, { method: 'PUT', body: JSON.stringify({ planUuid }) }),
    /** Unlink the plan from this dossier (admin/demo reset). */
    unlinkPlan: (documentId: string) =>
      request<void>(`${PDCA}/dossiers/${documentId}/plan`, { method: 'DELETE' }),
    /**
     * Under-water link (start-form route): let the backend read the dossier
     * and link a planId passed through the content to this dossier.
     */
    resolvePlan: (documentId: string) =>
      request<{ planUuid: string; documentId: string; linked: boolean }>(
        `${PDCA}/dossiers/${documentId}/resolve-plan`, { method: 'POST' }),
  },
  caseDefinitions: {
    /** Case-definition keys from GZAC, for the case-type dropdown in the admin. */
    list: () => request<string[]>(`${PDCA}/case-definitions`),
    /** Document paths from the GZAC document schema, as path suggestions for the prefill mapping. */
    documentPaths: (caseDefinitionKey: string) =>
      request<string[]>(`${PDCA}/case-definitions/${caseDefinitionKey}/document-paths`),
  },
  phaseConfigs: {
    get: (caseDefKey: string) => request<PhaseConfig>(`${API_BASE}/api/v1/admin/phase-configs/${caseDefKey}`),
    list: () => request<PhaseConfig[]>(`${API_BASE}/api/v1/admin/phase-configs`),
    create: (data: Partial<PhaseConfig>) =>
      request<PhaseConfig>(`${API_BASE}/api/v1/admin/phase-configs`, { method: 'POST', body: JSON.stringify(data) }),
    update: (key: string, data: Partial<PhaseConfig>) =>
      request<PhaseConfig>(`${API_BASE}/api/v1/admin/phase-configs/${key}`, { method: 'PUT', body: JSON.stringify(data) }),
    delete: (key: string) =>
      request<void>(`${API_BASE}/api/v1/admin/phase-configs/${key}`, { method: 'DELETE' }),
  },
  /**
   * Bouwblok koppelingen: GZAC building blocks linked to a plan case type +
   * doeltype(s), as actie or product. Read + start for the plan page; CRUD
   * and dropdown data for the admin.
   */
  actieBouwblokken: {
    listByCaseDefinition: (caseDefinitionKey: string) =>
      request<ActieBouwblokKoppeling[]>(`${PDCA}/actie-bouwblokken?caseDefinitionKey=${encodeURIComponent(caseDefinitionKey)}`),
    start: (id: string, doelUuid: string) =>
      request<BouwblokStartResult>(`${PDCA}/actie-bouwblokken/${id}/start`, { method: 'POST', body: JSON.stringify({ doelUuid }) }),
    admin: {
      list: () => request<ActieBouwblokKoppeling[]>(`${API_BASE}/api/v1/admin/actie-bouwblokken`),
      create: (data: Partial<ActieBouwblokKoppeling>) =>
        request<ActieBouwblokKoppeling>(`${API_BASE}/api/v1/admin/actie-bouwblokken`, { method: 'POST', body: JSON.stringify(data) }),
      update: (id: string, data: Partial<ActieBouwblokKoppeling>) =>
        request<ActieBouwblokKoppeling>(`${API_BASE}/api/v1/admin/actie-bouwblokken/${id}`, { method: 'PUT', body: JSON.stringify(data) }),
      delete: (id: string) =>
        request<void>(`${API_BASE}/api/v1/admin/actie-bouwblokken/${id}`, { method: 'DELETE' }),
      /** Imported building block processes in GZAC (versionTag BB:<key>:<version>). */
      bouwblokProcessen: () =>
        request<BouwblokProces[]>(`${API_BASE}/api/v1/admin/actie-bouwblokken/bouwblok-processen`),
      /** Case types with the product-dossier contract in their schema (DOSSIER uitvoeringsvorm). */
      productDossiertypes: () =>
        request<string[]>(`${API_BASE}/api/v1/admin/actie-bouwblokken/product-dossiertypes`),
      /** This app's plugin configurations pushed by GZAC. */
      pluginConfiguraties: () =>
        request<PluginConfiguratie[]>(`${API_BASE}/api/v1/admin/actie-bouwblokken/plugin-configuraties`),
    },
  },
};

/**
 * Plan = dossier (1:1): a dossier's plan through the direct link in the
 * overlay (plan_details.dossierId). Without a link the start-form route is
 * tried first (resolve-plan: the backend reads a planId from the dossier
 * content and links under water). `fout` carries the message for the empty
 * state; 409/400 from resolve-plan (conflict/invalid planId) are passed
 * through verbatim.
 */
export async function planVoorDossier(
  documentId: string
): Promise<{ plan: Plan; details: PlanDetails } | { plan: null; details: null; fout: string }> {
  let resolveFout: string | null = null;
  let details = await pdca.dossiers.getPlan(documentId).catch(() => null);
  if (!details) {
    const resolved = await pdca.dossiers.resolvePlan(documentId).catch((e: any) => {
      const msg = typeof e?.message === 'string' ? e.message : '';
      if (msg.includes('409') || msg.includes('400')) resolveFout = msg;
      return null;
    });
    if (resolved) details = await pdca.dossiers.getPlan(documentId).catch(() => null);
  }
  if (!details) {
    return {
      plan: null,
      details: null,
      fout: resolveFout ?? 'Geen plan voor dit dossier. Maak eerst een plan aan via de taak "Plan aanmaken".',
    };
  }
  const plan = await openplan.plannen.get(details.planUuid);
  return { plan, details };
}

/** Display stubs for registers outside this integration (BRP, objecten). */
export const registers = {
  persoon: (bsn: string) => request<BrpPersoon>(`${API_BASE}/api/v1/registers/personen/${bsn}`),
  object: (id: string) => request<ObjectRecord>(`${API_BASE}/api/v1/registers/objecten/${id}`),
};

// ------------------------------------------------------ composite mutations
// Multi-call flows against the registers + overlay, orchestrated client-side.

/** Doel + instrumenten in Open Plan, then the local overlay rows. */
export async function deleteDoelCascade(doelUuid: string): Promise<void> {
  const instrumenten = await openplan.instrumenten.listByDoelen([doelUuid]);
  for (const instrument of instrumenten) {
    if (instrument.doelen.every(d => d.uuid === doelUuid)) {
      await openplan.instrumenten.delete(instrument.uuid);
      await pdca.instrumentdetails.delete(instrument.uuid).catch(() => undefined);
    }
  }
  await openplan.doelen.delete(doelUuid);
  await pdca.doeldetails.delete(doelUuid).catch(() => undefined);
}

/**
 * Aborting a doel with a mandatory reason: register status geannuleerd +
 * toelichting, cascading to all still-active linked instrumenten
 * (voorzieningen) with the same reason.
 */
export async function afbreekDoelCascade(doelUuid: string, planUuid: string, reden: string): Promise<void> {
  const einddatum = new Date().toISOString();
  const instrumenten = await openplan.instrumenten.listByDoelen([doelUuid]);
  for (const instrument of instrumenten) {
    if (instrument.status === 'actief') {
      await openplan.instrumenten.update(instrument.uuid, { status: 'geannuleerd', einddatum });
      await pdca.instrumentdetails.upsert(instrument.uuid, {
        planUuid,
        afbreekReden: `Afgebroken met doel: ${reden}`,
      });
    }
  }
  await openplan.doelen.update(doelUuid, {
    status: 'geannuleerd',
    toelichtingResultaat: reden,
    einddatum,
  });
}

/** Aborting an instrument/voorziening with a mandatory reason. */
export async function afbreekInstrument(instrumentUuid: string, planUuid: string, reden: string): Promise<void> {
  await openplan.instrumenten.update(instrumentUuid, { status: 'geannuleerd', einddatum: new Date().toISOString() });
  await pdca.instrumentdetails.upsert(instrumentUuid, { planUuid, afbreekReden: reden });
}

/** Contactmoment in Open Plan plus its overlay row. */
export async function deleteContactmomentCascade(contactmomentUuid: string): Promise<void> {
  await openplan.contactmomenten.delete(contactmomentUuid);
  await pdca.contactmomentdetails.delete(contactmomentUuid).catch(() => undefined);
}

// ------------------------------------------------------- Open Plan types

export interface Paginated<T> {
  count: number;
  next: string | null;
  previous: string | null;
  results: T[];
}

export type OpenPlanStatus = 'actief' | 'afgerond' | 'geannuleerd';
export type Resultaat = '' | 'behaald' | 'gefaald';

export interface NestedPlan {
  uuid: string;
  status: OpenPlanStatus;
  titel: string;
  notitie?: string;
  startdatum?: string;
  einddatum?: string | null;
}

export interface Plan extends NestedPlan {
  urn?: string;
  redenEinde?: string;
  plantype?: PlanType;
  overkoepelendPlan?: OverkoepelendPlan;
  zaak?: string;
  domeinregister?: string;
  medewerker?: string;
  startdatum: string;
}

export interface PlanType {
  uuid: string;
  type: string;
}

export interface OverkoepelendPlan {
  urn?: string;
  uuid: string;
  titel: string;
  status?: OpenPlanStatus;
}

export interface Doel {
  uuid: string;
  plannen: NestedPlan[];
  doeltype?: { uuid: string; doelType: string };
  persoon?: Persoon;
  status: OpenPlanStatus;
  titel: string;
  beschrijving?: string;
  startdatum: string;
  einddatum?: string | null;
  resultaat?: Resultaat;
  toelichtingResultaat?: string;
  hoofdDoel?: string | null;
}

export interface DoelType {
  uuid: string;
  doelType: string;
  categorieen: { uuid: string; naam: string }[];
}

export interface InstrumentType {
  uuid: string;
  instrumentType: string;
  categorieen: { uuid: string; naam: string }[];
}

export interface Instrument {
  uuid: string;
  titel: string;
  startdatum: string;
  einddatum?: string | null;
  status: OpenPlanStatus;
  product?: string;
  zaak?: string;
  resultaat?: Resultaat;
  doelen: { uuid: string; titel?: string; status?: string }[];
  instrumenttype?: { uuid: string; instrumentType: string };
}

export interface Contactmoment {
  uuid: string;
  status: OpenPlanStatus;
  datum: string;
  toelichtingStatus?: string;
  notitie?: string;
  plan?: NestedPlan;
}

export interface Persoon {
  uuid: string;
  persoonsprofiel?: string;
  klant?: string;
  bsn?: string;
}

export interface RelatieType {
  uuid: string;
  naam: string;
}

// ----------------------------------------------------- Open Product types

export interface ProductType {
  uuid: string;
  code: string;
  naam: string;
  samenvatting?: string;
  doelgroep?: string;
  themas: { uuid?: string; naam: string }[];
  organisaties: { uuid?: string; naam: string; code?: string; stad?: string }[];
  keywords: string[];
  parameters: { naam: string; waarde: string }[];
  gepubliceerd?: boolean;
}

export function productTypeByUrn(producttypen: ProductType[], productUrn: string | null | undefined): ProductType | undefined {
  const code = urnId(productUrn);
  return code ? producttypen.find(p => p.code === code || p.uuid === code) : undefined;
}

// ------------------------------------------------------ PDCA overlay types

export interface PlanDetails {
  planUuid: string;
  persoonUuid: string;
  caseDefinitionKey?: string;
  /** GZAC dossier (documentId) this plan is linked to; plan = dossier 1:1. */
  dossierId?: string;
  /** Configurable plan status (Concept, Vastgesteld, ...) while the register status is actief. */
  weergaveStatus?: string;
  /** The positie from the positietype register (PhaseConfig.positieTypen); no free text.
   *  Re-determined over time (an evaluation may move it); there is no target position. */
  beginPositie?: string;
  /** W&P subdoelgroep (segmentation the inwonerpositie aggregates), advised by the intake DMN. */
  subdoelgroep?: string;
  /** The single hoofddoel: uuid of a hoofddoel doeltype (category "Hoofddoel") in Open Plan. */
  hoofddoelTypeUuid?: string;
  streefEinddatum?: string;
}

export type UitvoeringsStatus = 'GEPLAND' | 'GESTART';

/** How an active subdoel is going. */
export type VoortgangStatus = 'OP_KOERS' | 'AANDACHT_NODIG' | 'LOOPT_ACHTER';

export interface DoelDetails {
  doelUuid: string;
  planUuid: string;
  /** Refinement of register status actief: gepland or gestart. */
  uitvoeringsStatus: UitvoeringsStatus;
  /** Numeric score from the intake and the evaluate task form; the plan tabs show voortgangStatus. */
  voortgangScore?: number;
  voortgangToelichting?: string;
  voortgangStatus?: VoortgangStatus;
  /** Note for colleagues only. */
  interneNotitie?: string;
  /** Note that may be shared with the inwoner. */
  externeNotitie?: string;
  sortering: number;
}

export interface InstrumentDetails {
  instrumentUuid: string;
  planUuid: string;
  urenBesteed?: number;
  effectiviteitScore?: number;
  effectiviteitToelichting?: string;
  afbreekReden?: string;
  interneNotitie?: string;
  externeNotitie?: string;
}

export interface ContactmomentDetails {
  contactmomentUuid: string;
  planUuid: string;
  evaluatieType: string;
  geplandeDatum?: string;
  deelnemers?: string;
  doelVoortgang?: string;
  actiepunten?: string;
  /** Set when the contactmoment is the result of a completed evaluation session. */
  evaluationSessionId?: string;
}

/** A completed evaluation: its session (contactmoment fields) and the plan changes made during it. */
export interface CompletedEvaluation {
  sessie: EvaluationSession;
  wijzigingen: EvaluationChange[];
}

export interface Actie {
  id: string;
  doelUuid: string;
  /** Optional instrument this taak/opdracht supports (W&P: instrument -> 0..n taken). */
  instrumentUuid?: string;
  title: string;
  description?: string;
  status: string;
  assigneeType?: string;
  assigneeName?: string;
  priority: string;
  startDate?: string;
  dueDate?: string;
  contactmomentUuid?: string;
  /** LOKAAL (tracked in the tab only) or BOUWBLOK (executed by a GZAC building block process). */
  uitvoering?: string;
  gzacProcessInstanceId?: string;
  bouwblokKoppelingId?: string;
  completedDate?: string;
  result?: string;
}

export interface ActieBouwblokKoppeling {
  id: string;
  caseDefinitionKey: string;
  /** ACTIE starts an action under a doel; PRODUCT starts a request process
   *  that manages the instrument in the plan itself. */
  soort: 'ACTIE' | 'PRODUCT';
  /** BOUWBLOK runs on the plan dossier; DOSSIER (PRODUCT only) is a full
   *  case of productCaseDefinitionKey with its own dossier. */
  uitvoeringsvorm?: 'BOUWBLOK' | 'DOSSIER';
  naam: string;
  omschrijving?: string;
  buildingBlockKey?: string | null;
  buildingBlockVersion?: string;
  processDefinitionKey?: string | null;
  productCaseDefinitionKey?: string | null;
  pluginConfigId: string;
  /** JSON array of doeltype uuids; empty/null = available under every doel. */
  doeltypeUuids?: string | null;
}

/** Outcome of a koppeling start: the created action (ACTIE) and/or the created aanvraagdossier (DOSSIER product). */
export interface BouwblokStartResult {
  actie: Actie | null;
  dossier: { caseDefinitionKey: string; documentId: string; dossierUrn: string } | null;
}

/**
 * Parses a dossier URN (`urn:pdca:gzac:dossier:<caseDefinitionKey>:<documentId>`,
 * stored in an instrument's `zaak` register field) into the parts of the
 * GZAC route `/cases/<caseDefinitionKey>/document/<documentId>`.
 */
export function parseDossierUrn(value?: string | null): { caseDefinitionKey: string; documentId: string } | null {
  if (!value) return null;
  const parts = value.split(':');
  if (parts.length !== 6 || parts[0] !== 'urn' || parts[1] !== 'pdca' || parts[2] !== 'gzac' || parts[3] !== 'dossier') {
    return null;
  }
  return { caseDefinitionKey: parts[4], documentId: parts[5] };
}

export interface BouwblokProces {
  bouwblokKey: string;
  bouwblokVersie: string;
  processDefinitionKey: string;
  naam?: string;
}

export interface PluginConfiguratie {
  configId: string;
  titel: string;
}

export interface Betrokkene {
  id: string;
  planUuid: string;
  name: string;
  role: string;
  email?: string;
  phone?: string;
  organization?: string;
  isPrimary: boolean;
}

export interface PhaseConfig {
  id: string;
  caseDefinitionKey: string;
  /** JSON array: evaluation types. */
  evaluationTypes: string;
  /** JSON array: configurable plan statuses (Concept, Vastgesteld, ...). */
  planStatussen?: string | null;
  /** JSON array: the domein's positietype register (begin/target positions). */
  positieTypen?: string | null;
  /** JSON object: hoofddoeltype name -> subdoeltype names allowed under it. */
  subdoelMapping?: string | null;
  /** JSON object: prefill field -> document path for the create-plan task form. */
  prefillMapping?: string | null;
  /** For an intake case type: the plan case type that results from the intake. */
  planCaseDefinitionKey?: string | null;
}

// ------------------------------------------------------------- stub types

export interface BrpPersoon {
  bsn: string;
  naam: string;
  geboortedatum: string;
  geslacht: string;
  nationaliteit: string;
  adres: { straat: string; huisnummer: string; postcode: string; woonplaats: string };
  burgerlijkeStaat: string;
  kinderen: { naam: string; geboortedatum: string }[];
}

export interface ObjectRecord {
  id: string;
  naam: string;
  type: string;
  monumentnummer?: string;
  bouwperiode?: string;
  adres: { straat: string; huisnummer?: string; postcode: string; woonplaats: string };
  eigenaar: string;
  beheerder: string;
  functie: string;
  status: string;
}
