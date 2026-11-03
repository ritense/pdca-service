import React, { useState } from 'react';
import { Button, InlineNotification, Tag } from '@carbon/react';
import { Add } from '@carbon/react/icons';
import { Doel } from '../shared/api';
import { EvaluatieBanner } from '../shared/EvaluatieBanner';
import { foutmelding } from '../shared/http';
import { doelStatus } from '../shared/labels';
import { LaadFout, NietGeladen, PaginaLaden } from '../shared/Meldingen';
import { PlanDossierPagina, PlanPaginaProps } from '../shared/PlanDossierPagina';
import { useEvaluatieSessie } from '../shared/useEvaluatieSessie';
import { eersteFout } from '../shared/useResource';
import { formatUrn } from '../shared/urn';
import { ActieContext, doelenActies } from './doelenActies';
import { ActieModal, AfbreekModal, AfbreekTarget, DoelModal, VerwijderModal, VerwijderTarget } from './DoelenModals';
import { DoelenHandlers, HoofddoelCard, SubdoelCard } from './SubdoelCard';
import { useDoelenData } from './useDoelenModel';

/** The "Doelen & Acties" case tab. */
export function PlanGoals() {
  return <PlanDossierPagina>{props => <DoelenPagina {...props} />}</PlanDossierPagina>;
}

function DoelenPagina({ documentId, plan, details }: PlanPaginaProps) {
  const data = useDoelenData(plan, details);
  /** The user's running evaluation; while it is on this plan, every change here is recorded in it. */
  const evaluatie = useEvaluatieSessie(documentId);
  const [error, setError] = useState<string | null>(null);
  /** Confirmation after filing a product request. */
  const [productStarted, setProductStarted] = useState<string | null>(null);
  const [aanvraag, setAanvraag] = useState<DoelenHandlers['aanvraag']>(null);
  /** The subdoel modal: closed, a new subdoel (null) or the subdoel being edited. */
  const [doelModal, setDoelModal] = useState<{ doel: Doel | null } | null>(null);
  const [actieModal, setActieModal] = useState<ActieContext | null>(null);
  const [afbreekTarget, setAfbreekTarget] = useState<AfbreekTarget>(null);
  const [verwijderTarget, setVerwijderTarget] = useState<VerwijderTarget>(null);
  /** Key of the state action (starten, afronden, actiestatus) under way; one at a time. */
  const [busy, setBusy] = useState<string | null>(null);

  const fout = eersteFout(...data.vereist);
  const model = data.model;
  if (!model) {
    return fout
      ? <LaadFout pagina titel="De doelen konden niet geladen worden" error={fout.error} context="dossier" onRetry={fout.reload} />
      : <PaginaLaden beschrijving="Doelen laden…" />;
  }

  const acties = doelenActies(model);

  /** Runs a state action from a button, one at a time, then reloads; a failure shows as the page error. */
  const run = (key: string, melding: string, action: () => Promise<unknown>) => async () => {
    if (busy) return;
    setBusy(key);
    try {
      await action();
      await data.herlaad();
    } catch (e) {
      setError(`${melding}: ${foutmelding(e)}`);
    } finally {
      setBusy(null);
    }
  };

  /** A modal's save: throws on failure so the modal keeps the error and stays open. */
  const opslaan = (action: () => Promise<unknown>, sluit: () => void) => async () => {
    await action();
    sluit();
    await data.herlaad();
  };

  const handlers: DoelenHandlers = {
    busy: !!busy,
    aanvraag,
    startDoel: doel => run(`doel-${doel.uuid}`, 'Subdoel starten mislukt', () => acties.startDoel(doel))(),
    rondDoelAf: doel => run(`doel-${doel.uuid}`, 'Subdoel afronden mislukt', () => acties.rondDoelAf(doel))(),
    bewerkDoel: doel => setDoelModal({ doel }),
    breekAf: setAfbreekTarget,
    verwijderDoel: doel => setVerwijderTarget({
      uuid: doel.uuid,
      titel: doel.titel,
      // Removing a doel takes only the instruments that belong to this doel alone.
      instrumenten: model.instrumentenVan(doel.uuid).filter(i => i.doelen.every(d => d.uuid === doel.uuid)).length,
      acties: model.alleActiesVan(doel.uuid).length,
      openDossiers: model.openDossiersVan(doel.uuid),
    }),
    nieuweActie: setActieModal,
    actieStatus: (id, actie) => run(`actie-${id}`, 'Actiestatus wijzigen mislukt', () => acties.wijzigActieStatus(id, actie))(),
    startInstrument: inst => run(`instrument-${inst.uuid}`, 'Instrument starten mislukt', () => acties.startInstrument(inst))(),
    rondInstrumentAf: inst => run(`instrument-${inst.uuid}`, 'Instrument afronden mislukt', () => acties.rondInstrumentAf(inst))(),
    // The app only files a verzoek; the subdoel shows it as "Aangevraagd" and
    // the page follows it until GZAC's instrument dossier created the instrument.
    vraagProductAan: async (producttype, doelUuid) => {
      setAanvraag({ producttypeUuid: producttype.uuid, doelUuid });
      try {
        const verzoek = await acties.vraagProductAan(producttype, doelUuid);
        setProductStarted(`Verzoek voor "${verzoek.productTitel}" ingediend — het instrument verschijnt zodra GZAC het instrumentdossier heeft aangemaakt.`);
        await data.herlaad();
        const productUrn = formatUrn('producttype', producttype.code || producttype.uuid);
        data.volgVerzoek(v => v.doelUuid === doelUuid && v.productUrn === productUrn);
      } catch (e) {
        setError(`Instrument "${producttype.naam}" aanvragen mislukt: ${foutmelding(e)}`);
      } finally {
        setAanvraag(null);
      }
    },
    bewaarDoelBijwerken: (kind, doel) => async (voortgang, notities) => {
      await acties.bewaarDoelBijwerken(kind, doel, voortgang, notities);
      await data.herlaad();
    },
    bewaarInstrumentBijwerken: inst => async (registratie, notities) => {
      await acties.bewaarInstrumentBijwerken(inst, registratie, notities);
      await data.herlaad();
    },
  };

  return (
    <div className="pdca-container">
      {error && <InlineNotification className="pdca-notification" kind="error" title="Fout" subtitle={error} lowContrast onCloseButtonClick={() => setError(null)} />}
      {fout && <LaadFout titel="De doelen konden niet worden bijgewerkt" error={fout.error} context="dossier" onRetry={fout.reload} />}
      {evaluatie.session?.planUuid === plan.uuid && <EvaluatieBanner />}
      {evaluatie.fout != null && <NietGeladen wat="Je lopende evaluatie" error={evaluatie.fout} onRetry={evaluatie.opnieuw} />}
      {productStarted && (
        <InlineNotification className="pdca-notification" kind="success" title="Instrument aangevraagd" subtitle={productStarted}
          lowContrast onCloseButtonClick={() => setProductStarted(null)} />
      )}
      {model.actiefHoofddoel && <HoofddoelCard doel={model.actiefHoofddoel} model={model} handlers={handlers} />}
      {model.eerdereHoofddoelen.map(d => {
        const status = doelStatus(d, model.detailsVanDoel(d.uuid), true);
        return (
          <div key={d.uuid} className="pdca-tags pdca-eerder-hoofddoel">
            <span className="pdca-text-wrap">Eerder hoofddoel: {d.titel}</span>
            <Tag size="sm" type={status.tag}>{status.label}</Tag>
          </div>
        );
      })}

      <div className="pdca-phase-bar">
        <div className="pdca-spacer" />
        <Button size="sm" renderIcon={Add} onClick={() => setDoelModal({ doel: null })}>Subdoel toevoegen</Button>
      </div>

      {model.subdoelen.length === 0 && <div className="pdca-empty"><p>Geen subdoelen gevonden</p></div>}
      {model.subdoelen.map(doel => (
        <SubdoelCard key={doel.uuid} doel={doel} model={model} producttypen={data.producttypen}
          verzoeken={data.verzoeken} handlers={handlers} />
      ))}

      <DoelModal open={!!doelModal} doel={doelModal?.doel ?? null} doeltypen={model.doeltypen}
        hoofddoelNaam={model.actiefHoofddoelNaam} subdoelMapping={model.caseConfig.subdoelMapping}
        onClose={() => setDoelModal(null)}
        onSave={invoer => opslaan(() => acties.bewaarDoel(doelModal?.doel ?? null, invoer), () => setDoelModal(null))()} />
      <ActieModal open={!!actieModal} ctx={actieModal} onClose={() => setActieModal(null)}
        onSave={(ctx, invoer) => opslaan(() => acties.voegActieToe(ctx, invoer), () => setActieModal(null))()} />
      <AfbreekModal target={afbreekTarget} onClose={() => setAfbreekTarget(null)}
        onSubmit={reden => opslaan(() => acties.breekAf(afbreekTarget!, reden), () => setAfbreekTarget(null))()} />
      <VerwijderModal target={verwijderTarget} onClose={() => setVerwijderTarget(null)}
        onSubmit={() => opslaan(() => acties.verwijderDoel(verwijderTarget!), () => setVerwijderTarget(null))()} />
    </div>
  );
}
