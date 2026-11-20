import { pdca, PhaseConfig } from './api';
import { isHttpStatus } from './http';
import { Resource, useResource } from './useResource';

/** Evaluation types of a case type that configures none. */
export const STANDAARD_EVALUATIETYPEN = ['INTAKE', 'PROGRESS', 'EVALUATION'];

/** A JSON array from the case configuration as strings; empty when absent or malformed. */
export function jsonLijst(json: string | null | undefined): string[] {
  try {
    const waarde = JSON.parse(json || '[]');
    return Array.isArray(waarde) ? waarde.map(String) : [];
  } catch {
    return [];
  }
}

/** The lists the plan pages take from a case type's configuration. */
export interface CaseConfig {
  evaluatieTypen: string[];
  planStatussen: string[];
  positieTypen: string[];
  /** JSON object hoofddoeltype name -> subdoeltype names (see `subdoelTypenVoorHoofddoel`). */
  subdoelMapping: string | null;
}

/** The lists of [cfg]; a case type without configuration (null) gets the standard evaluation types and nothing else. */
export function caseConfigVan(cfg: PhaseConfig | null): CaseConfig {
  const evaluatieTypen = jsonLijst(cfg?.evaluationTypes);
  return {
    evaluatieTypen: evaluatieTypen.length > 0 ? evaluatieTypen : STANDAARD_EVALUATIETYPEN,
    planStatussen: jsonLijst(cfg?.planStatussen),
    positieTypen: jsonLijst(cfg?.positieTypen),
    subdoelMapping: cfg?.subdoelMapping ?? null,
  };
}

/** The lists of a case type's configuration; the standard ones when it has none (404) or there is no case type. */
export async function laadCaseConfig(caseDefinitionKey: string | null | undefined): Promise<CaseConfig> {
  if (!caseDefinitionKey) return caseConfigVan(null);
  try {
    return caseConfigVan(await pdca.phaseConfigs.get(caseDefinitionKey));
  } catch (e) {
    if (isHttpStatus(e, 404)) return caseConfigVan(null);
    throw e;
  }
}

/** [laadCaseConfig] as a page resource. */
export function useCaseConfig(caseDefinitionKey: string | null | undefined): Resource<CaseConfig> {
  return useResource(() => laadCaseConfig(caseDefinitionKey), [caseDefinitionKey]);
}
