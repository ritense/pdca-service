/**
 * The case configuration as PDCA Beheer edits it: the stored JSON strings
 * of a PhaseConfig parsed into editable rows, and back into the payload.
 */

import type { PhaseConfig, VoortgangStatusOptie } from '../shared/api';
import { jsonLijst, STANDAARD_EVALUATIETYPEN } from '../shared/caseConfig';
import { GERESERVEERDE_STATUSNAMEN } from '../shared/labels';

export const ALL_EVAL_TYPES = ['INTAKE', 'PROGRESS', 'EVALUATION', 'INSPECTION', 'CRISIS'];

/**
 * Prefill fields of the create-plan task form; order and keys mirror
 * IntakeService.DEFAULT_PREFILL_MAPPING in the backend.
 */
export const PREFILL_VELDEN: { veld: string; label: string }[] = [
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

export const STANDAARD_MAPPING: Record<string, string> = {
  subjectIdPersoon: '/bsn', subjectIdObject: '/objectId', naam: '/naam', objectNaam: '/objectNaam',
  titel: '/planTitel', notitie: '/planNotitie', dienstverlening: '/dienstverlening',
  hoofddoel: '/hoofddoel', subdoelgroep: '/subdoelgroep',
  weergaveStatus: '/weergaveStatus', beginPositie: '/beginPositie',
  startdatum: '/startdatum', streefEinddatum: '/streefEinddatum',
  doelen: '/doelen', instrumenten: '/instrumenten', contactmomenten: '/contactmomenten',
  betrokkenen: '/betrokkenen',
};

/** A voortgangsstatus being edited; [sleutel] keys the row, [code] is empty until the backend assigns one. */
export type VoortgangRij = VoortgangStatusOptie & { sleutel: string };
export type SubdoelMappingRij = { hoofddoel: string; subdoelen: string[] };
export interface MappingRij { pad: string; veld: string }

let volgendeRij = 0;
export const alsRijen = (opties: VoortgangStatusOptie[]): VoortgangRij[] =>
  opties.map(o => ({ ...o, sleutel: `rij-${volgendeRij++}` }));

/** Why a row cannot be saved, or null: a name is required, unique and not a lifecycle status. */
export function voortgangFout(rij: VoortgangRij, rijen: VoortgangRij[]): string | null {
  const naam = rij.naam.trim().toLowerCase();
  if (!naam) return 'Geef de status een naam';
  if (GERESERVEERDE_STATUSNAMEN.includes(naam)) return 'Dit is al een status van het doel zelf';
  if (rijen.some(r => r !== rij && r.naam.trim().toLowerCase() === naam)) return 'Deze naam staat er al';
  return null;
}

/** The configured voortgangsstatussen, or null when the case type configures none (standard list). */
export function parseVoortgang(raw?: string | null): VoortgangStatusOptie[] | null {
  try {
    const opties = JSON.parse(raw || '[]') as VoortgangStatusOptie[];
    return Array.isArray(opties) && opties.length > 0 ? opties : null;
  } catch { return null; }
}

export function parseSubdoelMapping(raw?: string | null): SubdoelMappingRij[] {
  if (!raw?.trim()) return [];
  try {
    const obj = JSON.parse(raw) as Record<string, string[]>;
    return Object.entries(obj).map(([hoofddoel, subdoelen]) => ({ hoofddoel, subdoelen: subdoelen ?? [] }));
  } catch { return []; }
}

function parseMapping(raw?: string | null): MappingRij[] {
  try {
    const obj = JSON.parse(raw || '{}');
    return Object.entries(obj).map(([veld, pad]) => ({ veld, pad: String(pad) }));
  } catch { return []; }
}

/** The configuration form of one case type. */
export interface ConfigForm {
  key: string;
  isIntake: boolean;
  planCaseDefKey: string;
  planStatussen: string[];
  voortgang: VoortgangRij[];
  positieTypen: string[];
  subdoelMapping: SubdoelMappingRij[];
  mapping: MappingRij[];
  evalTypes: string[];
}

export function nieuwForm(standaardVoortgang: VoortgangStatusOptie[]): ConfigForm {
  return {
    key: '', isIntake: false, planCaseDefKey: '',
    planStatussen: ['Concept', 'Vastgesteld', 'In uitvoering'],
    voortgang: alsRijen(standaardVoortgang),
    positieTypen: [], subdoelMapping: [], mapping: [],
    evalTypes: STANDAARD_EVALUATIETYPEN,
  };
}

export function formVan(cfg: PhaseConfig, standaardVoortgang: VoortgangStatusOptie[]): ConfigForm {
  return {
    key: cfg.caseDefinitionKey,
    isIntake: !!cfg.planCaseDefinitionKey,
    planCaseDefKey: cfg.planCaseDefinitionKey || '',
    planStatussen: jsonLijst(cfg.planStatussen),
    voortgang: alsRijen(parseVoortgang(cfg.voortgangStatussen) ?? standaardVoortgang),
    positieTypen: jsonLijst(cfg.positieTypen),
    subdoelMapping: parseSubdoelMapping(cfg.subdoelMapping),
    mapping: parseMapping(cfg.prefillMapping),
    evalTypes: jsonLijst(cfg.evaluationTypes),
  };
}

/** What still keeps the form from being saved; empty when it can be. */
export function formFouten(form: ConfigForm): string[] {
  const fouten: string[] = [];
  if (!form.key.trim()) fouten.push('Kies of vul een dossiertype in.');
  if (form.isIntake && !form.planCaseDefKey.trim()) fouten.push('Kies het plan-dossiertype waar deze intake een plan voor aanmaakt.');
  if (!form.isIntake && form.voortgang.some(r => voortgangFout(r, form.voortgang) !== null)) {
    fouten.push('Geef elke voortgangsstatus een eigen, geldige naam.');
  }
  return fouten;
}

/** The stored shape of [form]: an intake case type carries only its prefill mapping and plan case type. */
export function payloadVan(form: ConfigForm): Partial<PhaseConfig> {
  const subdoelRijen = form.subdoelMapping.filter(r => r.hoofddoel && r.subdoelen.length > 0);
  const mappingRijen = form.mapping.filter(r => r.veld && r.pad.trim());
  return {
    caseDefinitionKey: form.key.trim(),
    planStatussen: JSON.stringify(form.planStatussen),
    voortgangStatussen: form.isIntake ? null
      : JSON.stringify(form.voortgang.map(({ code, naam, betekenis }) => ({ code, naam: naam.trim(), betekenis }))),
    positieTypen: JSON.stringify(form.positieTypen),
    subdoelMapping: subdoelRijen.length > 0
      ? JSON.stringify(Object.fromEntries(subdoelRijen.map(r => [r.hoofddoel, r.subdoelen]))) : null,
    evaluationTypes: JSON.stringify(form.evalTypes),
    prefillMapping: form.isIntake && mappingRijen.length > 0
      ? JSON.stringify(Object.fromEntries(mappingRijen.map(r => [r.veld, r.pad.trim()]))) : null,
    planCaseDefinitionKey: form.isIntake ? form.planCaseDefKey.trim() : null,
  };
}
