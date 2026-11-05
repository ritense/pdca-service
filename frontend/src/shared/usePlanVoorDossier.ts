import { openplan, pdca, Plan, PlanDetails } from './api';
import { isHttpStatus } from './http';
import { Resource, useResource } from './useResource';

/** A plan dossier's plan: the Open Plan plan and its overlay row. */
export interface PlanVanDossier {
  plan: Plan;
  details: PlanDetails;
}

/**
 * Plan = dossier (1:1): the plan of a plan dossier through the direct link
 * in the overlay (plan_details.dossierId). null when the dossier has no plan
 * (404); any other failure (no access, GZAC unreachable) is an error, never
 * "no plan".
 */
export async function laadPlanVoorDossier(documentId: string): Promise<PlanVanDossier | null> {
  let details: PlanDetails;
  try {
    details = await pdca.dossiers.getPlan(documentId);
  } catch (e) {
    if (isHttpStatus(e, 404)) return null;
    throw e;
  }
  return { plan: await openplan.plannen.get(details.planUuid), details };
}

/** [laadPlanVoorDossier] as a page resource. */
export function usePlanVoorDossier(documentId: string | undefined): Resource<PlanVanDossier | null> {
  return useResource(documentId ? () => laadPlanVoorDossier(documentId) : null, [documentId]);
}
