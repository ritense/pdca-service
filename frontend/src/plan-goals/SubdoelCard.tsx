import React, { useState } from 'react';
import { Button, InlineNotification, Layer, OverflowMenu, OverflowMenuItem, Tag } from '@carbon/react';
import { Add, Checkmark, ChevronRight } from '@carbon/react/icons';
import type { Doel, IngeplandInstrument, Instrument, OpenVerzoek, ProductType } from '../shared/api';
import { formatDate, isVerlopen } from '../shared/dates';
import { aantal, doelBalkKleur, doelStatus, instrumentenTelling, instrumentStatus } from '../shared/labels';
import { NietGeladen } from '../shared/Meldingen';
import { VERGRENDELD_HINT } from '../shared/planSlot';
import type { Resource } from '../shared/useResource';
import type { ActieContext, Notities, RegistratieInvoer, VoortgangInvoer } from './doelenActies';
import type { AfbreekTarget } from './DoelenModals';
import { ActieRow, GeplandRij, ingeplandNaam, InstrumentRij, ProductMenu, VerzoekRij } from './InstrumentRij';
import { aanvraagbareProducttypen, DoelenModel, ingeplandVan, verzoekenVan } from './useDoelenModel';
import { DoelVoortgangNotities, VoortgangTag } from './VoortgangNotities';

/** What the cards of the Doelen tab can ask the page to do. */
export interface DoelenHandlers {
  /** A state action is under way; the state buttons wait for it. */
  busy: boolean;
  /** The plan is past concept and not in the user's evaluation: subdoelen and instrument requests cannot change. */
  vergrendeld: boolean;
  /** The plan is still in concept: nothing is requested yet, every chosen instrument is gepland. */
  planInConcept: boolean;
  startDoel: (doel: Doel) => void;
  rondDoelAf: (doel: Doel) => void;
  bewerkDoel: (doel: Doel) => void;
  breekAf: (target: NonNullable<AfbreekTarget>) => void;
  verwijderDoel: (doel: Doel) => void;
  nieuweActie: (ctx: ActieContext) => void;
  actieStatus: (id: string, actie: string) => void;
  startInstrument: (inst: Instrument) => void;
  rondInstrumentAf: (inst: Instrument) => void;
  /** A product chosen under "Instrument aanvragen"; its startdatum is asked next. */
  kiesProduct: (producttype: ProductType, doelUuid: string) => void;
  wijzigIngepland: (rij: IngeplandInstrument, naam: string) => void;
  vraagIngeplandAan: (rij: IngeplandInstrument, naam: string) => void;
  verwijderIngepland: (rij: IngeplandInstrument, naam: string) => void;
  /** Retries a gepland instrument the registers refused: from today, or its later date. */
  probeerIngeplandOpnieuw: (rij: IngeplandInstrument, naam: string) => void;
  bewaarDoelBijwerken: (kind: 'SUBDOEL' | 'HOOFDDOEL', doel: Doel) => (invoer: VoortgangInvoer) => Promise<void>;
  bewaarInstrumentBijwerken: (inst: Instrument) =>
    (registratie: RegistratieInvoer | null, notities: Notities | null) => Promise<void>;
}

/**
 * A beoogde einddatum in a card header. A date cannot be set in the past but
 * time moves on: once it has passed on a doel that still runs, it is marked
 * verlopen instead of blocked.
 */
function BeoogdDatum({ datum, lopend }: { datum?: string; lopend: boolean }) {
  if (!datum) return null;
  const verlopen = lopend && isVerlopen(datum);
  return (
    <span className={verlopen ? 'pdca-muted-count pdca-muted-count--verlopen' : 'pdca-muted-count'}>
      Beoogd: {formatDate(datum)}{verlopen && ' · verlopen'}
    </span>
  );
}

/** The plan's active hoofddoel (the strategy) as a fixed card; it is set when the plan is created from the intake. */
export function HoofddoelCard({ doel, model, handlers }: { doel: Doel; model: DoelenModel; handlers: DoelenHandlers }) {
  const [open, setOpen] = useState(false);
  const details = model.detailsVanDoel(doel.uuid);
  const status = doelStatus(doel, details, true);
  const balk = doelBalkKleur(status, details?.voortgangStatus, model.voortgangOpties);
  return (
    <Layer className={`pdca-goal-card pdca-goal-card--hoofddoel pdca-goal-card--${balk}`}>
      <div className="pdca-goal-header" onClick={() => setOpen(o => !o)}>
        <div className="pdca-row-main">
          <ChevronRight size={16} className={`pdca-chevron${open ? ' pdca-chevron--open' : ''}`} />
          <Tag size="sm" type="high-contrast">Hoofddoel</Tag>
          <span className="pdca-text-wrap pdca-goal-titel--hoofddoel">{doel.titel}</span>
          {doel.beschrijving && <span className="pdca-text-ellipsis pdca-goal-beschrijving">— {doel.beschrijving}</span>}
        </div>
        <div className="pdca-row-actions">
          {/* The plan's beoogde einddatum: the subdoel dates below are read against it. */}
          <BeoogdDatum datum={model.details.streefEinddatum} lopend={model.plan.status === 'actief'} />
          {details?.voortgangStatus && <VoortgangTag code={details.voortgangStatus} opties={model.voortgangOpties} />}
          <Tag size="sm" type={status.tag}>{status.label}</Tag>
        </div>
      </div>
      {open && (
        <div className="pdca-goal-body">
          <DoelVoortgangNotities id={doel.uuid} details={details} metVoortgang opties={model.voortgangOpties}
            onSave={handlers.bewaarDoelBijwerken('HOOFDDOEL', doel)} />
        </div>
      )}
    </Layer>
  );
}

/** One subdoel: its state actions, acties, instruments with their open requests and geplande ones, and voortgang & notes. */
export function SubdoelCard({ doel, model, producttypen, verzoeken, ingepland, handlers }: {
  doel: Doel;
  model: DoelenModel;
  producttypen: Resource<ProductType[]>;
  verzoeken: Resource<OpenVerzoek[]>;
  ingepland: Resource<IngeplandInstrument[]>;
  handlers: DoelenHandlers;
}) {
  const [open, setOpen] = useState(false);
  const details = model.detailsVanDoel(doel.uuid);
  const acties = model.actiesVan(doel.uuid);
  const instrumenten = model.instrumentenVan(doel.uuid);
  const openVerzoeken = verzoekenVan(verzoeken.data, doel.uuid);
  const geplande = ingeplandVan(ingepland.data, doel.uuid);
  const status = doelStatus(doel, details);
  const actief = doel.status === 'actief';
  const gestart = details?.uitvoeringsStatus === 'GESTART';
  const aantalActies = model.alleActiesVan(doel.uuid).length;
  // Without the producttypen nothing can be said about what is requestable.
  const aanvraagbaar = producttypen.data ? aanvraagbareProducttypen(model, producttypen.data, doel) : null;
  const balk = doelBalkKleur(status, actief ? details?.voortgangStatus : null, model.voortgangOpties);
  return (
    <Layer className={`pdca-goal-card status-${doel.status} pdca-goal-card--${balk}`}>
      <div className="pdca-goal-header" onClick={() => setOpen(o => !o)}>
        <div className="pdca-row-main pdca-row-main--nowrap">
          <ChevronRight size={16} className={`pdca-chevron${open ? ' pdca-chevron--open' : ''}`} />
          <span className="pdca-text-wrap pdca-goal-titel">{doel.titel}</span>
        </div>
        <div className="pdca-row-actions">
          {(aantalActies > 0 || instrumenten.length > 0) && (
            <span className="pdca-muted-count">
              {aantal(aantalActies, 'actie', 'acties')} · {aantal(instrumenten.length, 'instrument', 'instrumenten')}
            </span>
          )}
          {openVerzoeken.length > 0 && <Tag size="sm" type="blue">{aantal(openVerzoeken.length, 'aanvraag', 'aanvragen')} loopt</Tag>}
          {geplande.length > 0 && <Tag size="sm" type="purple">{geplande.length} gepland</Tag>}
          {doel.startdatum && <span className="pdca-muted-count">Start: {formatDate(doel.startdatum)}</span>}
          <BeoogdDatum datum={details?.streefEinddatum} lopend={model.plan.status === 'actief' && actief} />
          {actief && details?.voortgangStatus && <VoortgangTag code={details.voortgangStatus} opties={model.voortgangOpties} />}
          <Tag size="sm" type={status.tag}>{status.label}</Tag>
        </div>
      </div>
      {open && (
        <div className="pdca-goal-body">
          {doel.beschrijving && <p className="pdca-goal-toelichting">{doel.beschrijving}</p>}
          {doel.status === 'geannuleerd' && doel.toelichtingResultaat && (
            <InlineNotification className="pdca-notification" kind="warning" title="Afgebroken"
              subtitle={doel.toelichtingResultaat} lowContrast hideCloseButton />
          )}

          <div className="pdca-goal-actions">
            {actief && !gestart && (
              <Button size="sm" kind="primary" disabled={handlers.busy} onClick={() => handlers.startDoel(doel)}>Starten</Button>
            )}
            {actief && gestart && (
              <Button size="sm" kind="primary" renderIcon={Checkmark} disabled={handlers.busy || handlers.vergrendeld}
                title={handlers.vergrendeld ? VERGRENDELD_HINT : undefined}
                onClick={() => handlers.rondDoelAf(doel)}>Afronden</Button>
            )}
            <OverflowMenu size="sm" aria-label={`Meer acties voor ${doel.titel}`} iconDescription="Meer acties">
              <OverflowMenuItem itemText="Bewerken" disabled={handlers.vergrendeld} onClick={() => handlers.bewerkDoel(doel)} />
              {actief && (
                <OverflowMenuItem itemText="Afbreken" isDelete hasDivider disabled={handlers.vergrendeld}
                  onClick={() => handlers.breekAf({
                    kind: 'doel', uuid: doel.uuid, titel: doel.titel, lopendWerk: model.lopendWerkVan(doel.uuid, verzoeken.data, 'afbreken'),
                    vervalt: geplande.map(rij => ingeplandNaam(rij, producttypen.data)),
                  })} />
              )}
              <OverflowMenuItem itemText="Verwijderen" isDelete hasDivider={!actief} disabled={handlers.vergrendeld}
                onClick={() => handlers.verwijderDoel(doel)} />
            </OverflowMenu>
          </div>

          <div className="pdca-section-block">
            <div className="pdca-section-title">
              <span>Acties ({acties.length})</span>
              <div className="pdca-row-actions">
                <Button size="sm" kind="ghost" renderIcon={Add} onClick={() => handlers.nieuweActie({ doelUuid: doel.uuid })}>Actie toevoegen</Button>
              </div>
            </div>
            {acties.map(actie => <ActieRow key={actie.id} actie={actie} busy={handlers.busy} onStatus={handlers.actieStatus} />)}
            {acties.length === 0 && <p className="pdca-empty-inline">Geen acties</p>}
          </div>

          <div className="pdca-section-block">
            <div className="pdca-section-title">
              <span>
                Instrumenten ({instrumentenTelling([
                  ...instrumenten.map(i => instrumentStatus(i, model.detailsVanInstrument(i.uuid)).label),
                  ...openVerzoeken.map(() => 'Aangevraagd'),
                  ...geplande.map(() => 'Gepland'),
                ])})
              </span>
              <div className="pdca-row-actions">
                {/* Producttypen with a StartInstrument actie in Open Product that fit
                    this subdoel: choosing one asks its startdatum. For today on a plan
                    past concept a verzoek is filed and the instrument appears once
                    GZAC's instrument dossier has created it; otherwise it is gepland. */}
                {aanvraagbaar && aanvraagbaar.length > 0 && (
                  <ProductMenu producttypen={aanvraagbaar}
                    disabled={!actief ? 'Alleen bij een actief doel' : handlers.vergrendeld ? VERGRENDELD_HINT : null}
                    onKies={p => handlers.kiesProduct(p, doel.uuid)} />
                )}
              </div>
            </div>
            {instrumenten.map(inst => (
              <InstrumentRij key={inst.uuid} inst={inst} doel={doel} model={model} producttypen={producttypen.data} handlers={handlers} />
            ))}
            {openVerzoeken.map(v => (
              <VerzoekRij key={v.objectUrl ?? `${v.productUrn}-${v.aangevraagdOp}`} verzoek={v} producttypen={producttypen.data} />
            ))}
            {geplande.map(rij => (
              <GeplandRij key={rij.id} rij={rij} producttypen={producttypen.data} planInConcept={handlers.planInConcept} handlers={handlers} />
            ))}
            {verzoeken.error != null && <NietGeladen wat="Lopende aanvragen" error={verzoeken.error} onRetry={verzoeken.reload} />}
            {ingepland.error != null && <NietGeladen wat="Geplande instrumenten" error={ingepland.error} onRetry={ingepland.reload} />}
            {producttypen.error != null && !producttypen.data && (
              <NietGeladen wat="Wat hier aan te vragen is" error={producttypen.error} onRetry={producttypen.reload} />
            )}
            {instrumenten.length === 0 && openVerzoeken.length === 0 && geplande.length === 0 && aanvraagbaar && (
              <p className="pdca-empty-inline">
                {aanvraagbaar.length > 0
                  ? 'Geen instrumenten; vraag er een aan via "Instrument aanvragen"'
                  : 'Geen instrumenten; bij dit subdoel zijn geen instrumenten aan te vragen'}
              </p>
            )}
          </div>

          <DoelVoortgangNotities id={doel.uuid} details={details} metVoortgang={actief} opties={model.voortgangOpties}
            onSave={handlers.bewaarDoelBijwerken('SUBDOEL', doel)} />
        </div>
      )}
    </Layer>
  );
}
