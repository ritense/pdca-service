import { useCallback, useMemo } from 'react';
import {
  Doel, DoelDetails, heeftStartActie, Instrument, InstrumentDetails, openplan, openproduct, pdca, Plan, PlanDetails,
  ProductType, VoortgangStatusOptie, OpenVerzoek, Actie, DoelType,
} from '../shared/api';
import { CaseConfig, useCaseConfig } from '../shared/caseConfig';
import { doelgroepVoorSubject, doelTypeNaam, isHoofddoelType, producttypenVoorDoel } from '../shared/labels';
import { useOpenVerzoeken } from '../shared/useOpenVerzoeken';
import { usePlanData } from '../shared/usePlanData';
import { Bron, useResource } from '../shared/useResource';
import { parseDossierUrn } from '../shared/urn';

const BRONNEN = ['doelen', 'doelDetails', 'acties', 'instrumenten', 'instrumentDetails'] as const;

/** What the Doelen & Acties tab shows, once all of it is there. */
export interface DoelenModel {
  plan: Plan;
  details: PlanDetails;
  doelen: Doel[];
  acties: Actie[];
  doeltypen: DoelType[];
  voortgangOpties: VoortgangStatusOptie[];
  caseConfig: CaseConfig;
  detailsVanDoel: (uuid: string) => DoelDetails | undefined;
  detailsVanInstrument: (uuid: string) => InstrumentDetails | undefined;
  /** The plan's one active hoofddoel (a doel of a hoofddoel type), its earlier ones, and the subdoelen in their order. */
  actiefHoofddoel: Doel | null;
  actiefHoofddoelNaam: string | null;
  eerdereHoofddoelen: Doel[];
  subdoelen: Doel[];
  doelTitel: (uuid: string) => string | null;
  /** Acties under the doel itself; instrument-bound ones show under their instrument. */
  actiesVan: (doelUuid: string) => Actie[];
  alleActiesVan: (doelUuid: string) => Actie[];
  actiesVanInstrument: (instrumentUuid: string) => Actie[];
  instrumentenVan: (doelUuid: string) => Instrument[];
  /** Running instruments of a doel that their instrument dossier manages: they block aborting or removing it. */
  openDossiersVan: (doelUuid: string) => string[];
}

/**
 * The data of the Doelen & Acties tab, each source with its own state. The
 * page needs the plan data, the doeltypen (which doel is the hoofddoel), the
 * voortgangsstatussen and the case configuration (subdoelmapping); the
 * producttypen (what can be requested) and the open verzoeken are sections
 * of their own that can fail without the rest.
 */
export function useDoelenData(plan: Plan, details: PlanDetails) {
  const planData = usePlanData(plan.uuid, BRONNEN);
  const doeltypen = useResource(() => openplan.doeltypen.list(), []);
  const voortgangOpties = useResource(() => pdca.voortgangStatussen(plan.uuid), [plan.uuid]);
  const caseConfig = useCaseConfig(details.caseDefinitionKey);
  const producttypen = useResource(() => openproduct.producttypen.list(), []);
  const { verzoeken, volg } = useOpenVerzoeken(plan.uuid, planData.reload);

  const herlaadPlanData = planData.reload;
  const herlaadVerzoeken = verzoeken.reload;
  const herlaad = useCallback(async () => {
    await Promise.all([herlaadPlanData(), herlaadVerzoeken()]);
  }, [herlaadPlanData, herlaadVerzoeken]);

  const model = useDoelenModel(plan, details, planData.data, doeltypen.data, voortgangOpties.data, caseConfig.data);

  return {
    model,
    /** Sources the page cannot do without. */
    vereist: [planData, doeltypen, voortgangOpties, caseConfig] as Bron[],
    producttypen,
    verzoeken,
    volgVerzoek: volg,
    herlaad,
  };
}

function useDoelenModel(
  plan: Plan,
  details: PlanDetails,
  data: { doelen: Doel[]; doelDetails: DoelDetails[]; acties: Actie[]; instrumenten: Instrument[]; instrumentDetails: InstrumentDetails[] } | undefined,
  doeltypen: DoelType[] | undefined,
  voortgangOpties: VoortgangStatusOptie[] | undefined,
  caseConfig: CaseConfig | undefined,
): DoelenModel | null {
  return useMemo(() => {
    if (!data || !doeltypen || !voortgangOpties || !caseConfig) return null;
    const { doelen, acties, instrumenten } = data;
    const doelDetails = new Map(data.doelDetails.map(d => [d.doelUuid, d]));
    const instrumentDetails = new Map(data.instrumentDetails.map(d => [d.instrumentUuid, d]));
    // A doel carries only uuid + name of its doeltype; the register type
    // (with the categorieen that mark hoofddoelen) comes from the doeltype list.
    const typeVan = (d: Doel) => doeltypen.find(t => t.uuid === d.doeltype?.uuid);
    const isHoofddoel = (d: Doel) => {
      const type = typeVan(d);
      return type ? isHoofddoelType(type) : false;
    };
    // W&P hierarchy: the plan has one active hoofddoel; the subdoelen reference
    // it via doel.hoofdDoel and render as one flat list, ordered by the overlay sortering.
    const hoofddoelen = doelen.filter(isHoofddoel);
    const actiefHoofddoel = hoofddoelen.find(d => d.status === 'actief') ?? null;
    const instrumentenVan = (doelUuid: string) => instrumenten.filter(i => i.doelen.some(d => d.uuid === doelUuid));
    return {
      plan, details, doelen, acties, doeltypen, voortgangOpties, caseConfig,
      detailsVanDoel: uuid => doelDetails.get(uuid),
      detailsVanInstrument: uuid => instrumentDetails.get(uuid),
      actiefHoofddoel,
      // The subdoelmapping is keyed by hoofddoeltype name; fall back to the
      // doel titel for a hoofddoel without a named doeltype.
      actiefHoofddoelNaam: actiefHoofddoel ? doelTypeNaam(typeVan(actiefHoofddoel)) || actiefHoofddoel.titel : null,
      eerdereHoofddoelen: hoofddoelen.filter(d => d !== actiefHoofddoel),
      subdoelen: doelen.filter(d => !isHoofddoel(d)).sort((a, b) =>
        (doelDetails.get(a.uuid)?.sortering ?? 999) - (doelDetails.get(b.uuid)?.sortering ?? 999)),
      doelTitel: uuid => doelen.find(d => d.uuid === uuid)?.titel ?? null,
      actiesVan: doelUuid => acties.filter(a => a.doelUuid === doelUuid && !a.instrumentUuid),
      alleActiesVan: doelUuid => acties.filter(a => a.doelUuid === doelUuid),
      actiesVanInstrument: instrumentUuid => acties.filter(a => a.instrumentUuid === instrumentUuid),
      instrumentenVan,
      openDossiersVan: doelUuid =>
        instrumentenVan(doelUuid).filter(i => i.status === 'actief' && !!parseDossierUrn(i.zaak)).map(i => i.titel),
    };
  }, [plan, details, data, doeltypen, voortgangOpties, caseConfig]);
}

/**
 * The producttypen that can be requested under a doel: the catalog scoped
 * to the subdoel (themas of the doeltype, doelgroep of the plan subject),
 * narrowed to the producttypen Open Product marks as requestable with a
 * StartInstrument actie. Nothing is configured in the app for this.
 */
export function aanvraagbareProducttypen(model: DoelenModel, producttypen: ProductType[], doel: Doel): ProductType[] {
  const doeltype = model.doeltypen.find(t => t.uuid === doel.doeltype?.uuid);
  return producttypenVoorDoel(producttypen, doeltype, doelgroepVoorSubject(model.plan.domeinregister)).filter(heeftStartActie);
}

/** The verzoeken of a doel that have no instrument yet. */
export const verzoekenVan = (verzoeken: OpenVerzoek[] | undefined, doelUuid: string) =>
  (verzoeken ?? []).filter(v => v.doelUuid === doelUuid);
