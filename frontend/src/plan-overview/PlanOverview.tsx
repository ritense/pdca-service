import React, { useState } from 'react';
import { InlineNotification } from '@carbon/react';
import { EvaluatieBanner } from '../shared/EvaluatieBanner';
import { foutmelding } from '../shared/http';
import { LaadFout, NietGeladen, PaginaLaden } from '../shared/Meldingen';
import { PlanDossierPagina, PlanPaginaProps } from '../shared/PlanDossierPagina';
import { useEvaluatieSessie } from '../shared/useEvaluatieSessie';
import { eersteFout } from '../shared/useResource';
import { KpiTegels } from './KpiTegels';
import { overzichtActies, PlanActie } from './overzichtActies';
import { PlanActieModal } from './PlanActieModal';
import { PlanGegevens, RecenteEvaluaties } from './PlanGegevens';
import { PlanKop, SubjectKaart } from './PlanKop';
import { useOverzicht } from './useOverzicht';
import { Verantwoordelijkheden } from './Verantwoordelijkheden';

/** The "Planoverzicht" case tab. */
export function PlanOverview() {
  return <PlanDossierPagina>{props => <OverzichtPagina {...props} />}</PlanDossierPagina>;
}

function OverzichtPagina({ documentId, plan, details, setPlan, setDetails }: PlanPaginaProps) {
  const overzicht = useOverzicht(plan, details);
  /** The user's running evaluation; while it is on this plan, every change here is recorded in it. */
  const evaluatie = useEvaluatieSessie(documentId);
  const [error, setError] = useState<string | null>(null);
  const [successMsg, setSuccessMsg] = useState<string | null>(null);
  const [planActie, setPlanActie] = useState<PlanActie | null>(null);

  const fout = eersteFout(...overzicht.vereist);
  const model = overzicht.model;
  if (!model) {
    return fout
      ? <LaadFout pagina titel="Het planoverzicht kon niet geladen worden" error={fout.error} context="dossier" onRetry={fout.reload} />
      : <PaginaLaden beschrijving="Plan laden…" />;
  }

  const { betrokkenen } = model.data;
  const procesbegeleider = betrokkenen.find(b => b.isPrimary) ?? null;
  const acties = overzichtActies(plan, details, betrokkenen);
  const setBetrokkenen = (lijst: typeof betrokkenen) => overzicht.planData.mutate(d => ({ ...d!, betrokkenen: lijst }));

  const showSuccess = (msg: string) => {
    setSuccessMsg(msg);
    setTimeout(() => setSuccessMsg(null), 3000);
  };

  const weergaveStatus = async (status: string) => {
    try {
      setDetails(await acties.weergaveStatus(status));
      showSuccess('Planstatus gewijzigd naar ' + status);
    } catch (e) {
      setError('Planstatus wijzigen mislukt: ' + foutmelding(e));
    }
  };

  return (
    <div className="pdca-container">
      {error && (
        <InlineNotification className="pdca-notification" kind="error" title="Fout" subtitle={error} lowContrast
          onCloseButtonClick={() => setError(null)} />
      )}
      {fout && <LaadFout titel="Het planoverzicht kon niet worden bijgewerkt" error={fout.error} context="dossier" onRetry={fout.reload} />}
      {successMsg && (
        <InlineNotification className="pdca-notification" kind="success" title="Gelukt" subtitle={successMsg} lowContrast
          onCloseButtonClick={() => setSuccessMsg(null)} />
      )}
      {evaluatie.session?.planUuid === plan.uuid && <EvaluatieBanner />}
      {evaluatie.fout != null && <NietGeladen wat="Je lopende evaluatie" error={evaluatie.fout} onRetry={evaluatie.opnieuw} />}

      <PlanKop plan={plan} details={details} procesbegeleider={procesbegeleider} planStatusOpties={model.planStatusOpties}
        onWeergaveStatus={weergaveStatus} onPlanActie={setPlanActie} />
      <SubjectKaart plan={plan} subject={overzicht.subject} />
      <KpiTegels model={model} />

      <div className="pdca-content-grid">
        <div>
          <PlanGegevens plan={plan} details={details} model={model} procesbegeleider={procesbegeleider}
            onSave={async (veld, waarde) => {
              const resultaat = await acties.bewaarVeld(veld, waarde);
              if ('plan' in resultaat) setPlan(resultaat.plan);
              else if ('details' in resultaat) setDetails(resultaat.details);
              else if ('betrokkenen' in resultaat) setBetrokkenen(resultaat.betrokkenen);
              else if (resultaat.doelenGewijzigd) await overzicht.planData.reload();
              showSuccess('Wijziging opgeslagen');
            }} />
          <RecenteEvaluaties contactmomenten={model.data.contactmomenten} details={model.data.contactmomentDetails} />
        </div>
        <div>
          <Verantwoordelijkheden betrokkenen={betrokkenen} relatietypen={overzicht.relatietypen}
            onToevoegen={async invoer => {
              setBetrokkenen(await acties.voegBetrokkeneToe(invoer));
              showSuccess('Verantwoordelijkheid toegevoegd');
            }}
            onVerwijderen={async betrokkene => {
              await acties.verwijderBetrokkene(betrokkene);
              setBetrokkenen(betrokkenen.filter(b => b.id !== betrokkene.id));
              showSuccess('Verantwoordelijkheid verwijderd');
            }} />
        </div>
      </div>

      <PlanActieModal actie={planActie} planUuid={plan.uuid} weergaveStatus={details.weergaveStatus || 'Concept'}
        onClose={() => setPlanActie(null)}
        onSubmit={async (actie, reden) => {
          const { plan: bijgewerkt, melding } = await acties.planActie(actie, reden);
          setPlan(bijgewerkt);
          showSuccess(melding);
        }} />
    </div>
  );
}
