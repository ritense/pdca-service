/**
 * The changes the Doelen & Acties tab makes, each as one call to the API
 * (lifecycle steps are backend use cases) followed by recording it in the
 * user's running evaluation of this plan (a no-op without one). Reloading
 * is up to the page.
 */

import { Actie, Doel, Instrument, InstrumentDetails, LevenscyclusResultaat, openplan, pdca, ProductType } from '../shared/api';
import { EvaluationChangeInput, recordEvaluationChange } from '../shared/evaluationSession';
import { actieStatus, voortgangStatus } from '../shared/labels';
import type { DoelenModel } from './useDoelenModel';

export type Notities = { interneNotitie: string; externeNotitie: string };
export type VoortgangInvoer = { status: string; toelichting: string };
export type RegistratieInvoer = { uren: number | null; score: number | null; toelichting: string };
export type DoelInvoer = { titel: string; toelichting: string; doeltypeUuid: string };
export type ActieInvoer = Partial<Actie> & { title: string };
/** The doel (and optionally the instrument) a new actie belongs to. */
export type ActieContext = { doelUuid: string; instrumentUuid?: string };

/** "12 uur · effectiviteit 4/5": an instrument's registration as recorded in an evaluation. */
export function registratieSamenvatting(d: InstrumentDetails | undefined): string | null {
  if (!d) return null;
  const delen = [
    d.urenBesteed != null ? `${d.urenBesteed} uur` : null,
    d.effectiviteitScore != null ? `effectiviteit ${d.effectiviteitScore}/5` : null,
  ].filter(Boolean);
  return delen.length > 0 ? delen.join(' · ') : null;
}

export function doelenActies(model: DoelenModel) {
  const { plan } = model;
  const rec = (change: EvaluationChangeInput) => recordEvaluationChange(plan.uuid, change);
  const subdoel = (doel: { uuid: string; titel: string }, soort: string, extra: Partial<EvaluationChangeInput> = {}) =>
    rec({ subjectType: 'SUBDOEL', subjectUuid: doel.uuid, subjectTitel: doel.titel, soort, ...extra });
  const instrument = (inst: { uuid: string; titel: string }, soort: string, extra: Partial<EvaluationChangeInput> = {}) =>
    rec({ subjectType: 'INSTRUMENT', subjectUuid: inst.uuid, subjectTitel: inst.titel, soort, ...extra });
  /** The subdoelen the backend started along with an instrument (a subdoel starts with its first started instrument). */
  const gestarteSubdoelen = (resultaat: LevenscyclusResultaat) => resultaat.gestarteSubdoelen.forEach(s =>
    subdoel({ uuid: s.uuid, titel: s.titel ?? model.doelTitel(s.uuid) ?? '' }, 'SUBDOEL_GESTART'));

  const notities = async (
    target: { kind: 'SUBDOEL' | 'HOOFDDOEL' | 'INSTRUMENT'; uuid: string; titel: string },
    voor: Partial<Notities> | undefined,
    na: Notities,
  ) => {
    if (target.kind === 'INSTRUMENT') await pdca.instrumentdetails.upsert(target.uuid, { planUuid: plan.uuid, ...na });
    else await pdca.doeldetails.upsert(target.uuid, { planUuid: plan.uuid, ...na });
    const wijziging = (soort: string, van: string | undefined, naar: string) => {
      if ((van || '') !== naar) {
        rec({ subjectType: target.kind, subjectUuid: target.uuid, subjectTitel: target.titel, soort, samenvoegen: true, vanWaarde: van, naarWaarde: naar });
      }
    };
    wijziging('INTERNE_NOTITIE', voor?.interneNotitie, na.interneNotitie);
    wijziging('EXTERNE_NOTITIE', voor?.externeNotitie, na.externeNotitie);
  };

  return {
    /**
     * Requests a product: the app files a verzoek and creates nothing in the
     * plan itself; GZAC's instrument dossier picks it up and creates the instrument.
     */
    async vraagProductAan(producttype: ProductType, doelUuid: string) {
      const verzoek = await pdca.instrumenten.start(doelUuid, producttype.uuid);
      subdoel({ uuid: doelUuid, titel: model.doelTitel(doelUuid) ?? '' }, 'PRODUCT_AANGEVRAAGD', { naarWaarde: verzoek.productTitel });
      return verzoek;
    },

    /**
     * Doelen come from the doeltype register: the title is the register name
     * of the chosen doeltype; free text only as explanation for the inwoner.
     */
    async bewaarDoel(bestaand: Doel | null, data: DoelInvoer) {
      if (bestaand) {
        await openplan.doelen.update(bestaand.uuid, { titel: data.titel, beschrijving: data.toelichting, doeltypeUuid: data.doeltypeUuid });
        const titelGewijzigd = bestaand.titel !== data.titel;
        if (titelGewijzigd || (bestaand.beschrijving || '') !== data.toelichting) {
          subdoel({ uuid: bestaand.uuid, titel: data.titel }, 'SUBDOEL_BEWERKT', {
            vanWaarde: titelGewijzigd ? bestaand.titel : null,
            naarWaarde: titelGewijzigd ? data.titel : 'toelichting aangepast',
          });
        }
        return;
      }
      const doel = await openplan.doelen.create({
        plannenUuids: [plan.uuid],
        persoonUuid: model.details.persoonUuid,
        doeltypeUuid: data.doeltypeUuid,
        titel: data.titel,
        beschrijving: data.toelichting,
        startdatum: new Date().toISOString(),
        // New subdoelen hang under the plan's active hoofddoel (W&P hierarchy).
        ...(model.actiefHoofddoel ? { hoofdDoel: model.actiefHoofddoel.uuid } : {}),
      });
      await pdca.doeldetails.upsert(doel.uuid, { planUuid: plan.uuid, uitvoeringsStatus: 'GEPLAND' });
      subdoel(doel, 'SUBDOEL_TOEGEVOEGD');
    },

    async verwijderDoel(doel: { uuid: string; titel: string }) {
      await pdca.doelen.verwijderen(doel.uuid);
      subdoel(doel, 'SUBDOEL_VERWIJDERD');
    },

    async startDoel(doel: Doel) {
      await pdca.doeldetails.upsert(doel.uuid, { planUuid: plan.uuid, uitvoeringsStatus: 'GESTART' });
      subdoel(doel, 'SUBDOEL_GESTART');
    },

    async rondDoelAf(doel: Doel) {
      await openplan.doelen.update(doel.uuid, { status: 'afgerond', resultaat: 'behaald', einddatum: new Date().toISOString() });
      subdoel(doel, 'SUBDOEL_AFGEROND');
    },

    /** Aborting requires a reason; a doel takes its running instruments along (refused while a dossier manages one). */
    async breekAf(target: { kind: 'doel' | 'instrument'; uuid: string; titel: string }, reden: string) {
      if (target.kind === 'doel') {
        await pdca.doelen.afbreken(target.uuid, reden);
        subdoel(target, 'SUBDOEL_AFGEBROKEN', { naarWaarde: reden });
      } else {
        await pdca.instrumenten.afbreken(target.uuid, reden);
        instrument(target, 'INSTRUMENT_AFGEBROKEN', { naarWaarde: reden });
      }
    },

    async voegActieToe(ctx: ActieContext, data: ActieInvoer) {
      const actie = await pdca.acties.create({ doelUuid: ctx.doelUuid, instrumentUuid: ctx.instrumentUuid, ...data });
      rec({ subjectType: 'ACTIE', subjectUuid: actie.id, subjectTitel: actie.title, soort: 'ACTIE_TOEGEVOEGD' });
    },

    /** `approve`/`reject` review an actie; anything else is its new status. */
    async wijzigActieStatus(id: string, actie: string) {
      const voor = model.acties.find(a => a.id === id);
      const na = actie === 'approve' ? await pdca.acties.approve(id)
        : actie === 'reject' ? await pdca.acties.reject(id)
        : await pdca.acties.update(id, { status: actie });
      rec({
        subjectType: 'ACTIE', subjectUuid: id, subjectTitel: na.title, soort: 'ACTIE_STATUS', samenvoegen: true,
        vanWaarde: voor ? actieStatus(voor.status).label : null, naarWaarde: actieStatus(na.status).label,
      });
    },

    async startInstrument(inst: Instrument) {
      const resultaat = await pdca.instrumenten.starten(inst.uuid);
      instrument(inst, 'INSTRUMENT_GESTART');
      gestarteSubdoelen(resultaat);
    },

    /** Only for an instrument without instrument dossier; one with a dossier is completed there. */
    async rondInstrumentAf(inst: Instrument) {
      const resultaat = await pdca.instrumenten.afronden(inst.uuid, 'behaald');
      instrument(inst, 'INSTRUMENT_AFGEROND');
      gestarteSubdoelen(resultaat);
    },

    /**
     * Voortgang and notes of a subdoel or the hoofddoel, edited together;
     * each changed part is written and recorded on its own. The explanation
     * entered with a voortgang becomes the reason of the recorded change
     * (only when it was changed itself).
     */
    async bewaarDoelBijwerken(kind: 'SUBDOEL' | 'HOOFDDOEL', doel: Doel, voortgang: VoortgangInvoer | null, na: Notities | null) {
      const voor = model.detailsVanDoel(doel.uuid);
      if (voortgang) {
        await pdca.doeldetails.upsert(doel.uuid, {
          planUuid: plan.uuid, voortgangStatus: voortgang.status, voortgangToelichting: voortgang.toelichting,
        });
        rec({
          subjectType: kind, subjectUuid: doel.uuid, subjectTitel: doel.titel, soort: 'VOORTGANG', samenvoegen: true,
          vanWaarde: voor?.voortgangStatus ? voortgangStatus(voor.voortgangStatus, model.voortgangOpties).label : null,
          naarWaarde: voortgangStatus(voortgang.status, model.voortgangOpties).label,
          toelichting: voortgang.toelichting !== (voor?.voortgangToelichting ?? '') ? voortgang.toelichting : undefined,
        });
      }
      if (na) await notities({ kind, uuid: doel.uuid, titel: doel.titel }, voor, na);
    },

    /** Hours, effectiveness and notes of an instrument; emptied fields are sent as null, which clears them. */
    async bewaarInstrumentBijwerken(inst: Instrument, registratie: RegistratieInvoer | null, na: Notities | null) {
      const voor = model.detailsVanInstrument(inst.uuid);
      if (registratie) {
        const bijgewerkt = await pdca.instrumentdetails.upsert(inst.uuid, {
          planUuid: plan.uuid,
          urenBesteed: registratie.uren,
          effectiviteitScore: registratie.score,
          effectiviteitToelichting: registratie.toelichting,
        });
        instrument(inst, 'INSTRUMENT_REGISTRATIE', {
          samenvoegen: true,
          vanWaarde: registratieSamenvatting(voor), naarWaarde: registratieSamenvatting(bijgewerkt),
          toelichting: registratie.toelichting !== (voor?.effectiviteitToelichting ?? '') ? registratie.toelichting : undefined,
        });
      }
      if (na) await notities({ kind: 'INSTRUMENT', uuid: inst.uuid, titel: inst.titel }, voor, na);
    },
  };
}
