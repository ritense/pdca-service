/**
 * The shapes the bundles read and write: register objects as the registers
 * return them (Open Plan and Open Klant camelCase, Open Product snake_case
 * fields) and the PDCA overlay's own rows and use-case results.
 */

import type { EvaluationChange, EvaluationSession } from './evaluationSession';
import { producttypeCode } from './urn';

// ------------------------------------------------------- Open Plan types

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

// ------------------------------------------------------- Open Klant types

/**
 * A klantcontact as returned by the Klantinteracties API (fields used by the
 * app). A contactmoment that took place has indicatieContactGelukt true; a
 * planned one has null.
 */
export interface Klantcontact {
  uuid: string;
  kanaal: string;
  onderwerp: string;
  /** Gespreksverslag / notitie. */
  inhoud: string;
  indicatieContactGelukt: boolean | null;
  plaatsgevondenOp: string;
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
  /** The producttype's acties; the one named StartInstrument makes it requestable from the plan page. */
  acties?: { uuid: string; naam: string; url?: string }[];
  gepubliceerd?: boolean;
}

/** Name of the Open Product actie that says how a product is started from a plan. */
const START_ACTIE = 'StartInstrument';

/** Can this producttype be requested from the plan page? Decided by Open Product: it carries a StartInstrument actie. */
export const heeftStartActie = (producttype: ProductType) =>
  (producttype.acties ?? []).some(a => a.naam.toLowerCase() === START_ACTIE.toLowerCase());

/** The producttype an instrument's `product` URN names (by code). */
export function productTypeByUrn(producttypen: ProductType[], productUrn: string | null | undefined): ProductType | undefined {
  const code = producttypeCode(productUrn);
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
  streefEinddatum?: string;
}

export type UitvoeringsStatus = 'GEPLAND' | 'GESTART';

/** What a voortgangsstatus means: drives its tag colour and the Voortgang KPI. */
export type VoortgangBetekenis = 'GAAT_GOED' | 'LET_OP' | 'GAAT_NIET_GOED';

/**
 * One voortgangsstatus of a plan case type (PhaseConfig.voortgangStatussen):
 * the stable code stored on a doel, the name users see and its meaning.
 */
export interface VoortgangStatusOptie {
  code: string;
  naam: string;
  betekenis: VoortgangBetekenis;
}

export interface DoelDetails {
  doelUuid: string;
  planUuid: string;
  /** Refinement of register status actief: gepland or gestart. */
  uitvoeringsStatus: UitvoeringsStatus;
  /** Explanation of the voortgang. */
  voortgangToelichting?: string;
  /** How an active subdoel or hoofddoel is going: code of one of the plan's voortgangsstatussen. */
  voortgangStatus?: string;
  /** Note for colleagues only. */
  interneNotitie?: string;
  /** Note that may be shared with the inwoner. */
  externeNotitie?: string;
  sortering: number;
}

export interface InstrumentDetails {
  instrumentUuid: string;
  planUuid: string;
  /** Refinement of register status actief: gepland or gestart (an active instrument without a row is gestart). */
  uitvoeringsStatus: UitvoeringsStatus;
  urenBesteed?: number;
  effectiviteitScore?: number;
  effectiviteitToelichting?: string;
  afbreekReden?: string;
  interneNotitie?: string;
  externeNotitie?: string;
}

/** A contactmoment to record or plan (`afgerond` false: planned on `datum`). */
export interface NewContactmoment {
  planUuid: string;
  /** Plain date, yyyy-mm-dd. */
  datum: string;
  evaluatieType: string;
  afgerond: boolean;
  /** Verslag, or the agenda/notitie of a planned contactmoment (at most 1000 characters). */
  verslag?: string;
}

export interface ContactmomentDetails {
  contactmomentUuid: string;
  planUuid: string;
  evaluatieType: string;
  geplandeDatum?: string;
  deelnemers?: string;
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
  completedDate?: string;
  result?: string;
}

/** Outcome of a product request: the verzoek object in the Objecten API and the product it asks for. */
export interface InstrumentVerzoek {
  objectUrl: string | null;
  productTitel: string;
}

/** A verzoek whose instrument has not appeared in the plan yet ("Aangevraagd"). */
export interface OpenVerzoek {
  objectUrl: string | null;
  planUuid: string | null;
  doelUuid: string;
  productUrn: string | null;
  productTitel: string | null;
  /** When it was filed (ISO). */
  aangevraagdOp: string | null;
  /** How long it has been open, in minutes. */
  minutenOpen: number | null;
}

/** The subdoelen a lifecycle step started along with an instrument. */
export interface LevenscyclusResultaat {
  gestarteSubdoelen: { uuid: string; titel: string | null }[];
}

export interface HoofddoelWissel {
  gewijzigd: boolean;
  hoofddoelUuid: string;
  van: string | null;
  naar: string | null;
}

/** What is still open in a plan before it is closed. */
export interface AfsluitCheck {
  openSubdoelen: number;
  openInstrumenten: number;
  /** Titles of running instruments managed by an instrument dossier; closing waits for these. */
  openInstrumentdossiers: string[];
  /** null: the Objecten API could not be read. */
  openVerzoeken: number | null;
}

export interface Betrokkene {
  id: string;
  planUuid: string;
  name: string;
  role: string;
  email?: string;
  phone?: string;
  organization?: string;
  /** The plan's procesbegeleider (at most one per plan). */
  isPrimary: boolean;
}

export interface PhaseConfig {
  id: string;
  caseDefinitionKey: string;
  /** JSON array: evaluation types. */
  evaluationTypes: string;
  /** JSON array: configurable plan statuses (Concept, Vastgesteld, ...). */
  planStatussen?: string | null;
  /** JSON array of VoortgangStatusOptie: voortgangsstatussen of doelen; empty = the standard list. */
  voortgangStatussen?: string | null;
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
