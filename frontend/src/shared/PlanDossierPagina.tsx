import React from 'react';
import type { Plan, PlanDetails } from './api';
import { LaadFout, PaginaLaden, PaginaMelding } from './Meldingen';
import { useInitContext } from './PdcaRoot';
import { usePlanVoorDossier } from './usePlanVoorDossier';

/** What a plan tab gets once its plan is there. */
export interface PlanPaginaProps {
  documentId: string;
  plan: Plan;
  details: PlanDetails;
  /** Replaces the plan or its overlay row locally with what a save returned. */
  setPlan: (plan: Plan) => void;
  setDetails: (details: PlanDetails) => void;
}

/**
 * The shared frame of the plan tabs: the dossier from GZAC's context, its
 * plan, and the states before it is there (no dossier, loading, no access or
 * another failure with a retry, a dossier without plan). The tab itself is
 * only rendered with its plan.
 */
export function PlanDossierPagina({ children }: { children: (props: PlanPaginaProps) => React.ReactNode }) {
  const documentId = useInitContext().documentId;
  const resource = usePlanVoorDossier(documentId);

  if (!documentId) {
    return <PaginaMelding titel="Geen dossiercontext ontvangen"
      tekst="Een plan is 1:1 een dossier; open dit tabblad vanuit een GZAC-dossier." />;
  }
  if (resource.data === undefined) {
    if (resource.error) {
      return <LaadFout pagina titel="Het plan kon niet geladen worden" error={resource.error} context="dossier"
        onRetry={resource.reload} />;
    }
    return <PaginaLaden beschrijving="Plan laden…" />;
  }
  if (resource.data === null) {
    return <PaginaMelding titel="Bij dit dossier hoort (nog) geen plan" tekst="Een plan ontstaat uit een intake." />;
  }
  const { plan, details } = resource.data;
  return <>{children({
    documentId,
    plan,
    details,
    setPlan: p => resource.mutate(d => ({ details: d?.details ?? details, plan: p })),
    setDetails: pd => resource.mutate(d => ({ plan: d?.plan ?? plan, details: pd })),
  })}</>;
}
