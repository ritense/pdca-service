import React, { useMemo, useState } from 'react';
import { Button, ContentSwitcher, InlineNotification, Switch, Tag } from '@carbon/react';
import { Klantcontact, openplan } from '../shared/api';
import { useCaseConfig } from '../shared/caseConfig';
import { datumDeel, formatDate } from '../shared/dates';
import { offerEvaluationPanel, startEvaluationSession } from '../shared/evaluationSession';
import { foutmelding } from '../shared/http';
import { contactmomentDatum, evalTypeLabel, isHoofddoelType } from '../shared/labels';
import { LaadFout, NietGeladen, PaginaLaden } from '../shared/Meldingen';
import { PlanDossierPagina, PlanPaginaProps } from '../shared/PlanDossierPagina';
import { useEvaluatieSessie } from '../shared/useEvaluatieSessie';
import { usePlanData } from '../shared/usePlanData';
import { eersteFout, useResource } from '../shared/useResource';
import { ContactmomentKaart, LopendeEvaluatieKaart } from './EvaluatieKaarten';
import { PlanEvaluatieModal, VerzetModal } from './EvaluatieModals';

const BRONNEN = ['doelen', 'contactmomenten', 'contactmomentDetails', 'evaluaties'] as const;

const isGepland = (cm: Klantcontact) => cm.indicatieContactGelukt == null;

/**
 * The "Evaluaties" case tab: the plan's contactmomenten. Evaluations are
 * planned here ("Evaluatie plannen", a planned contactmoment that can be
 * moved) and run in GZAC's side panel ("Start evaluatie", or "Starten" on a
 * planned one). The user's own running evaluation of this plan is shown on
 * top (only to them); colleagues see an evaluation once it is completed,
 * with its gespreksverslag and the plan changes made during it.
 */
export function PlanEvaluations() {
  return <PlanDossierPagina>{props => <EvaluatiesPagina {...props} />}</PlanDossierPagina>;
}

function EvaluatiesPagina({ documentId, plan, details }: PlanPaginaProps) {
  const planData = usePlanData(plan.uuid, BRONNEN);
  // Actiepunten become acties under a subdoel; the doeltypen tell which doel is the hoofddoel.
  const doeltypen = useResource(() => openplan.doeltypen.list(), []);
  const caseConfig = useCaseConfig(details.caseDefinitionKey);
  const evaluatie = useEvaluatieSessie(documentId, () => { planData.reload(); });
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);
  const [sessionMessage, setSessionMessage] = useState<string | null>(null);
  const [filter, setFilter] = useState<string | null>(null);
  const [planModal, setPlanModal] = useState(false);
  /** The planned contactmoment being moved to another date. */
  const [verzetten, setVerzetten] = useState<{ uuid: string; datum: string } | null>(null);
  const [starting, setStarting] = useState(false);

  const subdoelen = useMemo(() => (planData.data && doeltypen.data
    ? planData.data.doelen.filter(d => {
      const type = doeltypen.data!.find(t => t.uuid === d.doeltype?.uuid);
      return !(type && isHoofddoelType(type));
    })
    : []), [planData.data, doeltypen.data]);

  const fout = eersteFout(planData, doeltypen, caseConfig);
  if (!planData.data || !doeltypen.data || !caseConfig.data) {
    return fout
      ? <LaadFout pagina titel="De evaluaties konden niet geladen worden" error={fout.error} context="dossier" onRetry={fout.reload} />
      : <PaginaLaden beschrijving="Evaluaties laden…" />;
  }

  const { contactmomenten, contactmomentDetails, evaluaties } = planData.data;
  const evalTypes = caseConfig.data.evaluatieTypen;
  const session = evaluatie.session;
  const detailsVan = (cm: Klantcontact) => contactmomentDetails.find(d => d.contactmomentUuid === cm.uuid);
  const datumVan = (cm: Klantcontact) => contactmomentDatum(cm, detailsVan(cm));

  /** Starts an evaluation in the side panel, optionally carrying out a planned contactmoment. */
  const start = async (geplandContactmomentUuid?: string) => {
    setStarting(true);
    try {
      setSessionMessage(null);
      const result = await startEvaluationSession(geplandContactmomentUuid);
      if (result.session) {
        evaluatie.setSession(result.session);
        await offerEvaluationPanel(result.session);
      } else {
        evaluatie.setSession(result.conflict);
        setSessionMessage(result.message);
      }
    } catch (e) {
      setError('Evaluatie starten mislukt: ' + foutmelding(e));
    } finally {
      setStarting(false);
    }
  };

  const lopend = session && session.planUuid === plan.uuid ? session : null;
  // A planned contactmoment carried out by the running evaluation is shown as that evaluation.
  const uitgevoerd = lopend?.geplandContactmomentUuid
    ? contactmomenten.find(cm => cm.uuid === lopend.geplandContactmomentUuid) ?? null : null;
  const zichtbaar = contactmomenten.filter(cm => cm !== uitgevoerd);
  const gefilterd = filter ? zichtbaar.filter(cm => detailsVan(cm)?.evaluatieType === filter) : zichtbaar;
  // Planned contactmomenten first (soonest on top), then the history (latest on top).
  const geplande = gefilterd.filter(isGepland).sort((a, b) => (datumVan(a) || '').localeCompare(datumVan(b) || ''));
  const historie = gefilterd.filter(cm => !isGepland(cm))
    .sort((a, b) => (b.plaatsgevondenOp || '').localeCompare(a.plaatsgevondenOp || ''));
  const filterOpties = [null, ...evalTypes];

  return (
    <div className="pdca-container">
      {error && <InlineNotification className="pdca-notification" kind="error" title="Fout" subtitle={error} lowContrast onCloseButtonClick={() => setError(null)} />}
      {fout && <LaadFout titel="De evaluaties konden niet worden bijgewerkt" error={fout.error} context="dossier" onRetry={fout.reload} />}
      {success && <InlineNotification className="pdca-notification" kind="success" title="Gelukt" subtitle={success} lowContrast onCloseButtonClick={() => setSuccess(null)} />}
      {evaluatie.fout != null && <NietGeladen wat="Je lopende evaluatie" error={evaluatie.fout} onRetry={evaluatie.opnieuw} />}
      <div className="pdca-page-toolbar">
        <div>
          <h1>Evaluaties</h1>
          <div className="pdca-tags">
            {lopend && <Tag size="sm" type="blue">Lopend: 1</Tag>}
            <Tag size="sm" type="gray">Totaal: {contactmomenten.length}</Tag>
            <Tag size="sm" type="green">Afgerond: {contactmomenten.filter(cm => cm.indicatieContactGelukt === true).length}</Tag>
            <Tag size="sm" type="cool-gray">Gepland: {zichtbaar.filter(isGepland).length}</Tag>
          </div>
        </div>
        <div className="pdca-toolbar-actions">
          <Button kind="secondary" onClick={() => setPlanModal(true)}>Evaluatie plannen</Button>
          {session?.dossierId === documentId
            ? <Button kind="tertiary" onClick={() => offerEvaluationPanel(session)}>Toon lopende evaluatie</Button>
            : <Button disabled={!!session || starting} onClick={() => start()}>Start evaluatie</Button>}
        </div>
      </div>
      {session && session.dossierId !== documentId && (
        <InlineNotification className="pdca-notification" kind="info" lowContrast hideCloseButton
          title="Je hebt een lopende evaluatie voor een ander plan"
          subtitle={sessionMessage ?? 'Rond die eerst af voordat je hier een evaluatie start.'} />
      )}

      {evalTypes.length > 0 && (
        <div className="pdca-phase-bar">
          <ContentSwitcher size="sm" className="pdca-filter-switcher"
            selectedIndex={Math.max(0, filterOpties.indexOf(filter))}
            onChange={({ index }: { index?: number }) => setFilter(filterOpties[index ?? 0] ?? null)}>
            {filterOpties.map(t => <Switch key={t ?? 'alle'} name={t ?? 'alle'} text={t ? evalTypeLabel(t) : 'Alle'} />)}
          </ContentSwitcher>
        </div>
      )}

      {lopend && (
        <LopendeEvaluatieKaart session={lopend} uitgevoerd={uitgevoerd}
          uitgevoerdOp={uitgevoerd ? datumVan(uitgevoerd) : null} onSession={evaluatie.setSession} />
      )}

      {geplande.length + historie.length === 0 && !lopend && <div className="pdca-empty"><p>Geen evaluaties gevonden</p></div>}

      {[...geplande, ...historie].map(cm => {
        const cmd = detailsVan(cm);
        const datum = datumVan(cm);
        return (
          <ContactmomentKaart key={cm.uuid} cm={cm} details={cmd} datum={datum}
            evaluatie={cmd?.evaluationSessionId ? evaluaties.find(e => e.sessie.id === cmd.evaluationSessionId) : undefined}
            subdoelen={subdoelen} startenUit={!!session || starting} onStart={() => start(cm.uuid)}
            onVerzet={() => setVerzetten({ uuid: cm.uuid, datum: datum ? datumDeel(datum) : '' })} />
        );
      })}

      <PlanEvaluatieModal open={planModal} planUuid={plan.uuid} evalTypes={evalTypes}
        onClose={() => setPlanModal(false)}
        onPlanned={async datum => {
          setPlanModal(false);
          setSuccess(`Evaluatie gepland op ${formatDate(datum)}`);
          await planData.reload();
        }} />
      <VerzetModal target={verzetten} onClose={() => setVerzetten(null)}
        onMoved={async datum => {
          setVerzetten(null);
          setSuccess(`Evaluatie verzet naar ${formatDate(datum)}`);
          await planData.reload();
        }} />
    </div>
  );
}
