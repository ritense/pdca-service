import { useMemo } from 'react';
import {
  BrpPersoon, Doel, DoelType, ObjectRecord, openplan, pdca, Plan, PlanDetails, registers, VoortgangStatusOptie,
} from '../shared/api';
import { CaseConfig, useCaseConfig } from '../shared/caseConfig';
import { isHttpStatus } from '../shared/http';
import { isHoofddoelType } from '../shared/labels';
import { PlanData, usePlanData } from '../shared/usePlanData';
import { Bron, useResource } from '../shared/useResource';
import { parseSubject } from '../shared/urn';

const BRONNEN = ['doelen', 'doelDetails', 'acties', 'contactmomenten', 'contactmomentDetails', 'betrokkenen'] as const;

export type OverzichtData = Pick<PlanData, typeof BRONNEN[number]>;

/**
 * The plan subject from its domeinregister URN: a persoon (bsn) or an object
 * (id), with its display record; null when the register does not know the id.
 */
export type SubjectData =
  | { kind: 'persoon'; bsn: string; brp: BrpPersoon | null }
  | { kind: 'object'; id: string; object: ObjectRecord | null }
  | null;

/** A register's display record; a 404 (the register does not know the id) is no record, not a failure. */
const ofNietGevonden = <T>(record: Promise<T>): Promise<T | null> =>
  record.catch((e: unknown) => {
    if (isHttpStatus(e, 404)) return null;
    throw e;
  });

async function laadSubject(plan: Plan): Promise<SubjectData> {
  const subject = parseSubject(plan.domeinregister);
  if (subject?.kind === 'persoon') return { kind: 'persoon', bsn: subject.bsn, brp: await ofNietGevonden(registers.persoon(subject.bsn)) };
  if (subject?.kind === 'object') return { kind: 'object', id: subject.id, object: await ofNietGevonden(registers.object(subject.id)) };
  return null;
}

/** What the Planoverzicht shows, once the sources it cannot do without are there. */
export interface OverzichtModel {
  data: OverzichtData;
  doeltypen: DoelType[];
  voortgangOpties: VoortgangStatusOptie[];
  caseConfig: CaseConfig;
  /** The planstatussen to choose from; Concept and Vastgesteld when the case type configures none. */
  planStatusOpties: string[];
  isHoofddoel: (doel: Doel) => boolean;
  /** The plan's active hoofddoel and its doeltype. */
  actiefHoofddoel: Doel | undefined;
  hoofddoelType: DoelType | undefined;
}

/**
 * The data of the Planoverzicht. The plan data, the doeltypen (which doel is
 * the hoofddoel), the voortgangsstatussen (the Voortgang KPI) and the case
 * configuration (planstatussen, posities) are needed for the page; the
 * subject's display record and the relatietypen (roles to choose from) are
 * optional and say so when they fail.
 */
export function useOverzicht(plan: Plan, details: PlanDetails) {
  const planData = usePlanData(plan.uuid, BRONNEN);
  const doeltypen = useResource(() => openplan.doeltypen.list(), []);
  const voortgangOpties = useResource(() => pdca.voortgangStatussen(plan.uuid), [plan.uuid]);
  const caseConfig = useCaseConfig(details.caseDefinitionKey);
  const relatietypen = useResource(() => openplan.relatietypen.list(), []);
  const subject = useResource(() => laadSubject(plan), [plan.domeinregister]);

  const model = useMemo((): OverzichtModel | null => {
    const data = planData.data;
    if (!data || !doeltypen.data || !voortgangOpties.data || !caseConfig.data) return null;
    const types = doeltypen.data;
    const isHoofddoel = (d: Doel) => {
      const type = types.find(t => t.uuid === d.doeltype?.uuid);
      return !!type && isHoofddoelType(type);
    };
    const actiefHoofddoel = data.doelen.find(d => isHoofddoel(d) && d.status === 'actief');
    return {
      data,
      doeltypen: types,
      voortgangOpties: voortgangOpties.data,
      caseConfig: caseConfig.data,
      planStatusOpties: caseConfig.data.planStatussen.length ? caseConfig.data.planStatussen : ['Concept', 'Vastgesteld'],
      isHoofddoel,
      actiefHoofddoel,
      hoofddoelType: types.find(t => t.uuid === actiefHoofddoel?.doeltype?.uuid),
    };
  }, [planData.data, doeltypen.data, voortgangOpties.data, caseConfig.data]);

  return {
    model,
    planData,
    vereist: [planData, doeltypen, voortgangOpties, caseConfig] as Bron[],
    relatietypen,
    subject,
  };
}
