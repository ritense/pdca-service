import {
  Actie, Betrokkene, CompletedEvaluation, ContactmomentDetails, Doel, DoelDetails, Instrument, InstrumentDetails,
  Klantcontact, openklant, openplan, pdca,
} from './api';
import { Resource, useResource } from './useResource';

/** Everything a plan tab can load about its plan; each tab picks the parts it shows. */
export interface PlanData {
  doelen: Doel[];
  doelDetails: DoelDetails[];
  acties: Actie[];
  /** All instruments of the plan's doelen, in one listing. */
  instrumenten: Instrument[];
  instrumentDetails: InstrumentDetails[];
  /** The plan's contactmomenten: klantcontacten in Open Klant. */
  contactmomenten: Klantcontact[];
  contactmomentDetails: ContactmomentDetails[];
  betrokkenen: Betrokkene[];
  evaluaties: CompletedEvaluation[];
}

export type PlanBron = keyof PlanData;

/** One listing per part; the instruments need the doelen first. */
const LADERS: { [K in Exclude<PlanBron, 'doelen' | 'instrumenten'>]: (planUuid: string) => Promise<PlanData[K]> } = {
  doelDetails: pdca.doeldetails.listByPlan,
  acties: pdca.acties.listByPlan,
  instrumentDetails: pdca.instrumentdetails.listByPlan,
  contactmomenten: openklant.klantcontacten.listByPlan,
  contactmomentDetails: pdca.contactmomentdetails.listByPlan,
  betrokkenen: pdca.betrokkenen.listByPlan,
  evaluaties: pdca.evaluaties.listByPlan,
};

/**
 * Loads the [bronnen] of a plan in parallel. One failing part fails the
 * whole load: a tab never shows a plan with a part silently missing.
 */
export async function laadPlanData<K extends PlanBron>(planUuid: string, bronnen: readonly K[]): Promise<Pick<PlanData, K>> {
  const result: Partial<PlanData> = {};
  const wil = new Set<PlanBron>(bronnen);
  const doelen = wil.has('doelen') || wil.has('instrumenten') ? openplan.doelen.listByPlan(planUuid) : null;
  await Promise.all([...wil].map(async bron => {
    if (bron === 'doelen') {
      result.doelen = await doelen!;
    } else if (bron === 'instrumenten') {
      result.instrumenten = await openplan.instrumenten.listByDoelen((await doelen!).map(d => d.uuid));
    } else {
      (result as Record<string, unknown>)[bron] = await LADERS[bron](planUuid);
    }
  }));
  return result as Pick<PlanData, K>;
}

/** [laadPlanData] as a page resource; pass [bronnen] as a constant. */
export function usePlanData<K extends PlanBron>(planUuid: string, bronnen: readonly K[]): Resource<Pick<PlanData, K>> {
  return useResource(() => laadPlanData(planUuid, bronnen), [planUuid, bronnen.join(',')]);
}
