/**
 * The changes the Doelen tab makes, each as one call to the API
 * (lifecycle steps are backend use cases) followed by recording it in the
 * user's running evaluation of this plan (a no-op without one). Reloading
 * is up to the page.
 */

import {
  Actie, Doel, IngeplandInstrument, Instrument, InstrumentDetails, LevenscyclusResultaat, openplan, pdca, ProductType,
} from '../shared/api';
import { EvaluationChangeInput, recordEvaluationChange } from '../shared/evaluationSession';
import { datumDeel, formatDate } from '../shared/dates';
import { actieStatus, voortgangStatus } from '../shared/labels';
import type { DoelenModel } from './useDoelenModel';

export type Notities = { interneNotitie: string; externeNotitie: string };
/**
 * Voortgang of a doel: the code of its voortgangsstatus (empty when it has
 * none or the doel is closed) with its interne and externe toelichting
 * (stored as the doel's interne and externe notitie).
 */
export type VoortgangInvoer = Notities & { status: string };
export type RegistratieInvoer = { uren: number | null; score: number | null; toelichting: string };
/** What the subdoel modal saves; streefEinddatum is yyyy-mm-dd, empty for none. */
/** `startdatum`: when work on the subdoel is planned to start (yyyy-mm-dd), on or before the beoogde einddatum. */
export type DoelInvoer = { titel: string; toelichting: string; doeltypeUuid: string; startdatum: string; streefEinddatum: string };
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

/** A planned date (yyyy-mm-dd) as Open Plan stores a doel's startdatum, the way plan creation writes it. */
function registerDatum(datum: string): string {
  return `${datum}T00:00:00Z`;
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

  /** The internal and external notes of an instrument. */
  const notities = async (inst: Instrument, voor: Partial<Notities> | undefined, na: Notities) => {
    await pdca.instrumentdetails.upsert(inst.uuid, { planUuid: plan.uuid, ...na });
    const wijziging = (soort: string, van: string | undefined, naar: string) => {
      if ((van || '') !== naar) instrument(inst, soort, { samenvoegen: true, vanWaarde: van, naarWaarde: naar });
    };
    wijziging('INTERNE_NOTITIE', voor?.interneNotitie, na.interneNotitie);
    wijziging('EXTERNE_NOTITIE', voor?.externeNotitie, na.externeNotitie);
  };

  return {
    /**
     * Requests a product from a startdatum. For today on a plan past concept
     * the app files a verzoek and creates nothing in the plan itself; GZAC's
     * instrument dossier picks it up and creates the instrument. Otherwise
     * the backend plans it, and it is requested on its date.
     */
    async vraagProductAan(producttype: ProductType, doelUuid: string, startdatum: string) {
      const aanvraag = await pdca.instrumenten.start(doelUuid, producttype.uuid, startdatum);
      const doel = { uuid: doelUuid, titel: model.doelTitel(doelUuid) ?? '' };
      if (aanvraag.verzoek) subdoel(doel, 'PRODUCT_AANGEVRAAGD', { naarWaarde: aanvraag.verzoek.productTitel });
      else subdoel(doel, 'PRODUCT_INGEPLAND', { naarWaarde: `${producttype.naam} vanaf ${formatDate(startdatum)}` });
      return aanvraag;
    },

    /** Moves an ingepland instrument to another startdatum; for one the registers refused, also the retry. */
    async wijzigIngepland(rij: IngeplandInstrument, naam: string, startdatum: string) {
      await pdca.instrumenten.wijzigIngepland(rij.id, startdatum);
      if (startdatum !== rij.startdatum) {
        instrument({ uuid: rij.id, titel: naam }, 'INSTRUMENT_STARTDATUM', {
          samenvoegen: true, vanWaarde: formatDate(rij.startdatum), naarWaarde: formatDate(startdatum),
        });
      }
    },

    /** Requests an ingepland instrument now, before its date. */
    async vraagIngeplandAan(rij: IngeplandInstrument, naam: string) {
      const aanvraag = await pdca.instrumenten.vraagIngeplandAan(rij.id);
      subdoel({ uuid: rij.doelUuid, titel: model.doelTitel(rij.doelUuid) ?? '' }, 'PRODUCT_AANGEVRAAGD', {
        naarWaarde: aanvraag.verzoek?.productTitel ?? naam,
      });
      return aanvraag;
    },

    async verwijderIngepland(rij: IngeplandInstrument, naam: string) {
      await pdca.instrumenten.verwijderIngepland(rij.id);
      subdoel({ uuid: rij.doelUuid, titel: model.doelTitel(rij.doelUuid) ?? '' }, 'INGEPLAND_VERWIJDERD', { naarWaarde: naam });
    },

    /**
     * Doelen come from the doeltype register: the title is the register name
     * of the chosen doeltype; free text only as explanation for the inwoner.
     */
    async bewaarDoel(bestaand: Doel | null, data: DoelInvoer) {
      if (bestaand) {
        // The beoogde einddatum goes first: the backend checks it against
        // today and the plan's date before anything else is written.
        const oudeEinddatum = model.detailsVanDoel(bestaand.uuid)?.streefEinddatum || '';
        if (oudeEinddatum !== data.streefEinddatum) {
          await pdca.doeldetails.upsert(bestaand.uuid, { planUuid: plan.uuid, streefEinddatum: data.streefEinddatum || null });
          subdoel(bestaand, 'BEOOGDE_EINDDATUM', {
            samenvoegen: true, vanWaarde: formatDate(oudeEinddatum) || null, naarWaarde: formatDate(data.streefEinddatum) || null,
          });
        }
        const oudeStartdatum = bestaand.startdatum ? datumDeel(bestaand.startdatum) : '';
        if (data.startdatum && oudeStartdatum !== data.startdatum) {
          await openplan.doelen.update(bestaand.uuid, { startdatum: registerDatum(data.startdatum) });
          subdoel(bestaand, 'STARTDATUM', {
            samenvoegen: true, vanWaarde: formatDate(oudeStartdatum) || null, naarWaarde: formatDate(data.startdatum),
          });
        }
        // The doeltype (and with it the title) is fixed once the subdoel exists.
        if ((bestaand.beschrijving || '') !== data.toelichting) {
          await openplan.doelen.update(bestaand.uuid, { beschrijving: data.toelichting });
          subdoel(bestaand, 'SUBDOEL_BEWERKT', { vanWaarde: null, naarWaarde: 'toelichting aangepast' });
        }
        return;
      }
      const doel = await openplan.doelen.create({
        plannenUuids: [plan.uuid],
        persoonUuid: model.details.persoonUuid,
        doeltypeUuid: data.doeltypeUuid,
        titel: data.titel,
        beschrijving: data.toelichting,
        startdatum: registerDatum(data.startdatum),
        // New subdoelen hang under the plan's active hoofddoel (W&P hierarchy).
        ...(model.actiefHoofddoel ? { hoofdDoel: model.actiefHoofddoel.uuid } : {}),
      });
      await pdca.doeldetails.upsert(doel.uuid, {
        planUuid: plan.uuid, uitvoeringsStatus: 'GEPLAND', ...(data.streefEinddatum ? { streefEinddatum: data.streefEinddatum } : {}),
      });
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

    /** Aborting requires a reason; a doel is refused while an instrument, aanvraag or actie task under it still runs. */
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
     * Voortgang of a subdoel or the hoofddoel with its interne and externe
     * toelichting, written in one call; only what changed is sent and
     * recorded. With a voortgangsstatus the externe toelichting is the
     * reason of the recorded VOORTGANG change (when it was changed itself),
     * so the evaluatieverslag shows it with the voortgang; without one it is
     * recorded on its own. The interne toelichting is recorded as an
     * internal change, which no document shows.
     */
    async bewaarDoelBijwerken(kind: 'SUBDOEL' | 'HOOFDDOEL', doel: Doel, invoer: VoortgangInvoer) {
      const voor = model.detailsVanDoel(doel.uuid);
      const statusGewijzigd = !!invoer.status && invoer.status !== (voor?.voortgangStatus ?? '');
      const internGewijzigd = invoer.interneNotitie !== (voor?.interneNotitie ?? '');
      const externGewijzigd = invoer.externeNotitie !== (voor?.externeNotitie ?? '');
      if (!statusGewijzigd && !internGewijzigd && !externGewijzigd) return;
      await pdca.doeldetails.upsert(doel.uuid, {
        planUuid: plan.uuid,
        ...(statusGewijzigd ? { voortgangStatus: invoer.status } : {}),
        ...(internGewijzigd ? { interneNotitie: invoer.interneNotitie } : {}),
        ...(externGewijzigd ? { externeNotitie: invoer.externeNotitie } : {}),
      });
      const doelWijziging = (soort: string, extra: Partial<EvaluationChangeInput>) =>
        rec({ subjectType: kind, subjectUuid: doel.uuid, subjectTitel: doel.titel, soort, samenvoegen: true, ...extra });
      if (statusGewijzigd || (externGewijzigd && invoer.status)) {
        doelWijziging('VOORTGANG', {
          vanWaarde: voor?.voortgangStatus ? voortgangStatus(voor.voortgangStatus, model.voortgangOpties).label : null,
          naarWaarde: voortgangStatus(invoer.status, model.voortgangOpties).label,
          toelichting: externGewijzigd ? invoer.externeNotitie : undefined,
        });
      } else if (externGewijzigd) {
        doelWijziging('EXTERNE_TOELICHTING', { vanWaarde: voor?.externeNotitie, naarWaarde: invoer.externeNotitie });
      }
      if (internGewijzigd) {
        doelWijziging('INTERNE_TOELICHTING', { vanWaarde: voor?.interneNotitie, naarWaarde: invoer.interneNotitie });
      }
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
      if (na) await notities(inst, voor, na);
    },
  };
}
