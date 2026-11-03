/**
 * The API the bundles call, on top of the one HTTP client (`http.ts`): the
 * Maykin registers plus the small PDCA overlay. Every call travels through
 * GZAC to the app's `/data` route; the paths are the app's own API paths.
 *
 * - Open Plan  (plannen/doelen/instrumenten/typen) is read 1:1 through the
 *   backend's register proxy at /openplan/...: camelCase fields, statuses
 *   actief/afgerond/geannuleerd, resultaten behaald/gefaald, ISO datetimes,
 *   paginated {count, results}. The proxy takes only the reads and the few
 *   single-object writes below.
 * - Open Product (producttypen) likewise via /openproduct/... (snake_case).
 * - Open Klant (klantcontacten = the plan's contactmomenten) is read via
 *   /openklant/...; creating (also planning) and rescheduling a
 *   contactmoment goes through the overlay API, which writes the
 *   klantcontact with its betrokkene and onderwerpobject.
 * - The PDCA overlay (/api/v1/pdca) stores only what the registers don't
 *   model: fase/voortgang/notities per doel and instrument,
 *   evaluatietype/actiepunten per contactmoment, completed evaluations with
 *   their plan changes, acties, betrokkenen and phase-configs. Its PUT
 *   endpoints are partial updates: an absent field keeps its value, null
 *   clears it.
 * - Lifecycle steps that span several objects or carry a business rule
 *   (closing/reopening a plan, the hoofddoel switch, aborting or removing a
 *   doel, starting/completing/aborting an instrument) are backend use cases
 *   under /api/v1/pdca; the browser never orchestrates them.
 */

import { get, HttpError, listAll, send } from './http';
import type {
  Actie, AfsluitCheck, Betrokkene, BrpPersoon, CompletedEvaluation, ContactmomentDetails, Doel, DoelDetails,
  DoelType, HoofddoelWissel, Instrument, InstrumentDetails, InstrumentVerzoek, Klantcontact, LevenscyclusResultaat,
  NewContactmoment, ObjectRecord, OpenVerzoek, PhaseConfig, Plan, PlanDetails, PlanType, ProductType, RelatieType,
  Resultaat, VoortgangStatusOptie,
} from './types';

export * from './types';

const OPENPLAN = '/openplan/plannen/api/v0';
const OPENPRODUCT = '/openproduct/producttypen/api/v1';
const OPENKLANT = '/openklant/klantinteracties/api/v1';
/** Base path of the PDCA overlay and use-case API. */
export const PDCA = '/api/v1/pdca';
const ADMIN = '/api/v1/admin';

// ------------------------------------------------------------ Open Plan API

export const openplan = {
  plannen: {
    get: (uuid: string) => get<Plan>(`${OPENPLAN}/plan/${uuid}`),
    /** Only the plan's notitie is edited directly; status changes are backend use cases. */
    update: (uuid: string, data: { notitie: string }) => send<Plan>('PATCH', `${OPENPLAN}/plan/${uuid}`, data),
  },
  doelen: {
    listByPlan: (planUuid: string) => listAll<Doel>(`${OPENPLAN}/doel`, { plannen__uuid: planUuid }),
    create: (data: object) => send<Doel>('POST', `${OPENPLAN}/doel`, data),
    /** Its texts and doeltype, or completing it (status afgerond); other status changes are backend use cases. */
    update: (uuid: string, data: object) => send<Doel>('PATCH', `${OPENPLAN}/doel/${uuid}`, data),
  },
  instrumenten: {
    listByDoelen: (doelUuids: string[]) =>
      doelUuids.length === 0
        ? Promise.resolve([] as Instrument[])
        : listAll<Instrument>(`${OPENPLAN}/instrument`, { doelen__uuid__in: doelUuids.join(',') }),
  },
  doeltypen: { list: () => listAll<DoelType>(`${OPENPLAN}/doeltype`) },
  relatietypen: { list: () => listAll<RelatieType>(`${OPENPLAN}/relatietype`) },
  plantypen: { list: () => listAll<PlanType>(`${OPENPLAN}/plantype`) },
};

// --------------------------------------------------------- Open Product API

export const openproduct = {
  producttypen: {
    list: () => listAll<ProductType>(`${OPENPRODUCT}/producttypen`),
  },
};

// ------------------------------------------------------------ Open Klant API

export const openklant = {
  /** The plan's contactmomenten: klantcontacten with an onderwerpobject pointing at the plan. */
  klantcontacten: {
    listByPlan: (planUuid: string) => listAll<Klantcontact>(`${OPENKLANT}/klantcontacten`, {
      onderwerpobject__onderwerpobjectidentificatorObjectId: planUuid,
      onderwerpobject__onderwerpobjectidentificatorCodeObjecttype: 'plan',
      onderwerpobject__onderwerpobjectidentificatorCodeRegister: 'open-plan',
    }),
  },
};

// ------------------------------------------------------------- PDCA overlay

export const pdca = {
  plandetails: {
    /** Edits the plan's overlay row (weergaveStatus, beginPositie, subdoelgroep, streefEinddatum). */
    update: (planUuid: string, data: Patch<Pick<PlanDetails, 'weergaveStatus' | 'beginPositie' | 'subdoelgroep' | 'streefEinddatum'>>) =>
      send<PlanDetails>('PUT', `${PDCA}/plandetails/${planUuid}`, data),
  },
  /** The voortgangsstatussen of a plan (its case type's list); without plan the standard list. */
  voortgangStatussen: (planUuid?: string) =>
    get<VoortgangStatusOptie[]>(`${PDCA}/voortgangstatussen`, planUuid ? { planUuid } : undefined),
  doeldetails: {
    listByPlan: (planUuid: string) => get<DoelDetails[]>(`${PDCA}/doeldetails`, { planUuid }),
    upsert: (doelUuid: string, data: Patch<DoelDetails>) =>
      send<DoelDetails>('PUT', `${PDCA}/doeldetails/${doelUuid}`, data),
  },
  contactmomentdetails: {
    listByPlan: (planUuid: string) => get<ContactmomentDetails[]>(`${PDCA}/contactmomentdetails`, { planUuid }),
  },
  /**
   * A contactmoment: the klantcontact in Open Klant plus its overlay row.
   * With afgerond false it is planned (indicatieContactGelukt null, the
   * datum as its planned moment). Contactmomenten are never deleted.
   */
  contactmomenten: {
    create: (data: NewContactmoment) =>
      send<ContactmomentDetails>('POST', `${PDCA}/contactmomenten`, data),
    /** Moves a planned contactmoment to another date; 409 once it took place. */
    reschedule: (contactmomentUuid: string, datum: string) =>
      send<ContactmomentDetails>('PATCH', `${PDCA}/contactmomenten/${contactmomentUuid}`, { datum }),
  },
  instrumentdetails: {
    listByPlan: (planUuid: string) => get<InstrumentDetails[]>(`${PDCA}/instrumentdetails`, { planUuid }),
    /** Hours, effectiveness and notes; starting and ending go through `instrumenten`. */
    upsert: (instrumentUuid: string, data: Patch<Omit<InstrumentDetails, 'uitvoeringsStatus' | 'afbreekReden'>>) =>
      send<InstrumentDetails>('PUT', `${PDCA}/instrumentdetails/${instrumentUuid}`, data),
  },
  /** Completed evaluations with their plan changes; running ones are never listed here. */
  evaluaties: {
    listByPlan: (planUuid: string) => get<CompletedEvaluation[]>(`${PDCA}/evaluaties`, { planUuid }),
  },
  acties: {
    listByPlan: (planUuid: string) => get<Actie[]>(`${PDCA}/acties`, { planUuid }),
    create: (data: Partial<Actie> & { doelUuid: string; title: string }) =>
      send<Actie>('POST', `${PDCA}/acties`, data),
    update: (id: string, data: Partial<Actie>) =>
      send<Actie>('PATCH', `${PDCA}/acties/${id}`, data),
    approve: (id: string) => send<Actie>('POST', `${PDCA}/acties/${id}/approve`),
    reject: (id: string) => send<Actie>('POST', `${PDCA}/acties/${id}/reject`),
  },
  betrokkenen: {
    listByPlan: (planUuid: string) => get<Betrokkene[]>(`${PDCA}/betrokkenen`, { planUuid }),
    create: (data: Partial<Betrokkene> & { planUuid: string; name: string; role: string }) =>
      send<Betrokkene>('POST', `${PDCA}/betrokkenen`, data),
    /** Makes this betrokkene the plan's procesbegeleider; returns the plan's betrokkenen. */
    setProcesbegeleider: (id: string) =>
      send<Betrokkene[]>('PUT', `${PDCA}/betrokkenen/${id}/procesbegeleider`),
    delete: (id: string) => send<void>('DELETE', `${PDCA}/betrokkenen/${id}`),
  },
  dossiers: {
    /** This plan dossier's plan (direct link in the overlay); 404 when there is none. */
    getPlan: (documentId: string) => get<PlanDetails>(`${PDCA}/dossiers/${documentId}/plan`),
  },
  caseDefinitions: {
    /** Case-definition keys from GZAC, for the case-type dropdown in the admin. */
    list: () => get<string[]>(`${PDCA}/case-definitions`),
    /** Document paths from the GZAC document schema, as path suggestions for the prefill mapping. */
    documentPaths: (caseDefinitionKey: string) =>
      get<string[]>(`${PDCA}/case-definitions/${caseDefinitionKey}/document-paths`),
  },
  phaseConfigs: {
    get: (caseDefKey: string) => get<PhaseConfig>(`${ADMIN}/phase-configs/${caseDefKey}`),
    list: () => get<PhaseConfig[]>(`${ADMIN}/phase-configs`),
    create: (data: Partial<PhaseConfig>) =>
      send<PhaseConfig>('POST', `${ADMIN}/phase-configs`, data),
    update: (key: string, data: Partial<PhaseConfig>) =>
      send<PhaseConfig>('PUT', `${ADMIN}/phase-configs/${key}`, data),
    delete: (key: string) =>
      send<void>('DELETE', `${ADMIN}/phase-configs/${key}`),
  },
  instrumenten: {
    /**
     * Product requests: the app files a verzoek for the producttype's
     * StartInstrument actie (Open Product); GZAC's instrument dossier picks it
     * up and creates the instrument in the plan from there. 409 while one for
     * the same doel and product is open.
     */
    start: (doelUuid: string, producttypeUuid: string) =>
      send<InstrumentVerzoek>('POST', `${PDCA}/instrumenten/start`, { doelUuid, producttypeUuid }),
    /** The plan's verzoeken whose instrument has not appeared yet. */
    verzoeken: (planUuid: string) => get<OpenVerzoek[]>(`${PDCA}/instrumenten/verzoeken`, { planUuid }),
    /** A gepland instrument starts; its gepland subdoelen start along with it. */
    starten: (uuid: string) => send<LevenscyclusResultaat>('POST', `${PDCA}/instrumenten/${uuid}/starten`),
    /** Only for an instrument without instrument dossier (409 otherwise). */
    afronden: (uuid: string, resultaat?: Resultaat) =>
      send<LevenscyclusResultaat>('POST', `${PDCA}/instrumenten/${uuid}/afronden`, { resultaat }),
    /** Only for an instrument without instrument dossier (409 otherwise). */
    afbreken: (uuid: string, reden: string) =>
      send<LevenscyclusResultaat>('POST', `${PDCA}/instrumenten/${uuid}/afbreken`, { reden }),
  },
  doelen: {
    /** Removes a doel with its own instruments and its acties; 409 while one of its instrument dossiers is open. */
    verwijderen: (uuid: string) => send<{ instrumenten: number }>('DELETE', `${PDCA}/doelen/${uuid}`),
    /** Aborts a doel and its running instruments; 409 while one of its instrument dossiers is open. */
    afbreken: (uuid: string, reden: string) =>
      send<{ instrumenten: number }>('POST', `${PDCA}/doelen/${uuid}/afbreken`, { reden }),
  },
  plannen: {
    /** Makes a doel of this hoofddoeltype the plan's one active hoofddoel. */
    wisselHoofddoel: (planUuid: string, doeltypeUuid: string) =>
      send<HoofddoelWissel>('PUT', `${PDCA}/plannen/${planUuid}/hoofddoel`, { doeltypeUuid }),
    /** What is still open, to warn before closing. */
    afsluitcheck: (planUuid: string) => get<AfsluitCheck>(`${PDCA}/plannen/${planUuid}/afsluitcheck`),
    afronden: (planUuid: string, reden: string) =>
      send<Plan>('POST', `${PDCA}/plannen/${planUuid}/afronden`, { reden }),
    afbreken: (planUuid: string, reden: string) =>
      send<Plan>('POST', `${PDCA}/plannen/${planUuid}/afbreken`, { reden }),
    /** 409 when the inwoner has another active plan of this type. */
    heractiveren: (planUuid: string) => send<Plan>('POST', `${PDCA}/plannen/${planUuid}/heractiveren`),
  },
};

/** A partial update of an overlay row: an absent field keeps its value, null clears it. */
export type Patch<T> = { [K in keyof T]?: T[K] | null };

/**
 * An id the backend takes as one path segment: `[A-Za-z0-9._~-]`, not `.`
 * or `..`. The backend refuses any other path (403) before a register is
 * asked, so an id outside it is answered here as what it is: not found.
 */
const PADSEGMENT = /^[A-Za-z0-9._~-]+$/;

function inRegister<T>(pad: string, id: string): Promise<T> {
  if (!PADSEGMENT.test(id) || id === '.' || id === '..') {
    return Promise.reject(new HttpError(404, 'Niet gevonden in het register', null));
  }
  return get<T>(`${pad}/${id}`);
}

/** Display stubs for registers outside this integration (BRP, objecten); 404 when the register does not know the id. */
export const registers = {
  persoon: (bsn: string) => inRegister<BrpPersoon>('/api/v1/registers/personen', bsn),
  object: (id: string) => inRegister<ObjectRecord>('/api/v1/registers/objecten', id),
};

