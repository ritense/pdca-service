/**
 * The changes the Planoverzicht makes, each as one call to the API (closing,
 * reopening and the hoofddoel switch are backend use cases) followed by
 * recording it in the user's running evaluation of this plan (a no-op
 * without one). Each returns what the page needs to show the new state.
 */

import { Betrokkene, openplan, pdca, Plan, PlanDetails } from '../shared/api';
import { EvaluationChangeInput, recordEvaluationChange } from '../shared/evaluationSession';
import { planStatus } from '../shared/labels';

/** Closing or reopening the plan, each confirmed in its own modal. */
export type PlanActie = 'afronden' | 'afbreken' | 'heractiveren';

/** The plan fields edited in the Plangegevens card. */
export type PlanVeld = 'procesbegeleider' | 'hoofddoel' | 'notitie' | 'subdoelgroep' | 'beginPositie';

export interface NieuweBetrokkene {
  name: string;
  role: string;
  email: string;
  phone: string;
  isPrimary: boolean;
}

export function overzichtActies(plan: Plan, details: PlanDetails, betrokkenen: Betrokkene[]) {
  const rec = (change: Omit<EvaluationChangeInput, 'subjectType' | 'subjectUuid' | 'subjectTitel'>) =>
    recordEvaluationChange(plan.uuid, { subjectType: 'PLAN', subjectUuid: plan.uuid, subjectTitel: plan.titel, ...change });
  const procesbegeleider = betrokkenen.find(b => b.isPrimary) ?? null;

  return {
    /**
     * Closes the plan with einddatum and redenEinde (optional explanation when
     * afgerond, a required reason when afgebroken) or reopens it, clearing
     * both. The backend owns the rules: closing waits for open instrument
     * dossiers, reopening for the one-active-plan rule.
     */
    async planActie(actie: PlanActie, reden: string): Promise<{ plan: Plan; melding: string }> {
      const updated = actie === 'afronden' ? await pdca.plannen.afronden(plan.uuid, reden)
        : actie === 'afbreken' ? await pdca.plannen.afbreken(plan.uuid, reden)
        : await pdca.plannen.heractiveren(plan.uuid);
      const naar = planStatus(updated.status, details.weergaveStatus).label;
      rec({
        soort: 'PLAN_STATUS', samenvoegen: true,
        vanWaarde: planStatus(plan.status, details.weergaveStatus).label, naarWaarde: naar,
        toelichting: reden || undefined,
      });
      return { plan: updated, melding: actie === 'heractiveren' ? 'Plan heractiveerd' : `Plan ${naar.toLowerCase()}` };
    },

    /** The configurable plan status (Concept, Vastgesteld, ...) in the overlay. */
    async weergaveStatus(weergaveStatus: string): Promise<PlanDetails> {
      const updated = await pdca.plandetails.update(plan.uuid, { weergaveStatus });
      rec({ soort: 'WEERGAVESTATUS', samenvoegen: true, vanWaarde: details.weergaveStatus, naarWaarde: weergaveStatus });
      return updated;
    },

    /**
     * Saves one plan field. notitie lives on the Open Plan plan; positie and
     * subdoelgroep in the overlay; the procesbegeleider is one of the plan's
     * betrokkenen (the backend keeps plan.medewerker in step); the hoofddoel
     * is the plan's active hoofddoel-type doel (W&P: one active hoofddoel).
     */
    async bewaarVeld(veld: PlanVeld, waarde: string): Promise<
      { plan: Plan } | { details: PlanDetails } | { betrokkenen: Betrokkene[] } | { doelenGewijzigd: boolean }
    > {
      if (veld === 'notitie') {
        const updated = await openplan.plannen.update(plan.uuid, { notitie: waarde.trim() });
        rec({ soort: 'HOOFDDOEL_TOELICHTING', samenvoegen: true, vanWaarde: plan.notitie, naarWaarde: waarde.trim() });
        return { plan: updated };
      }
      if (veld === 'procesbegeleider') {
        if (waarde === procesbegeleider?.id) return { betrokkenen };
        const bijgewerkt = await pdca.betrokkenen.setProcesbegeleider(waarde);
        const nieuw = bijgewerkt.find(b => b.id === waarde);
        rec({ soort: 'PROCESBEGELEIDER', samenvoegen: true, vanWaarde: procesbegeleider?.name, naarWaarde: nieuw?.name });
        return { betrokkenen: bijgewerkt };
      }
      if (veld === 'hoofddoel') {
        // The backend completes the current hoofddoel-doel (history in the doelen
        // tab), creates one of the chosen type and re-points the subdoelen to it.
        const wissel = await pdca.plannen.wisselHoofddoel(plan.uuid, waarde);
        if (wissel.gewijzigd) rec({ soort: 'HOOFDDOEL_GEWISSELD', vanWaarde: wissel.van, naarWaarde: wissel.naar });
        return { doelenGewijzigd: wissel.gewijzigd };
      }
      const updated = await pdca.plandetails.update(plan.uuid, { [veld]: waarde.trim() || null });
      rec({
        soort: veld === 'beginPositie' ? 'POSITIE' : 'SUBDOELGROEP', samenvoegen: true,
        vanWaarde: details[veld], naarWaarde: waarde.trim(),
      });
      return { details: updated };
    },

    /** Adds a verantwoordelijke; returns the plan's betrokkenen. */
    async voegBetrokkeneToe(invoer: NieuweBetrokkene): Promise<Betrokkene[]> {
      const name = invoer.name.trim();
      await pdca.betrokkenen.create({
        planUuid: plan.uuid,
        name,
        role: invoer.role,
        email: invoer.email.trim() || undefined,
        phone: invoer.phone.trim() || undefined,
        isPrimary: invoer.isPrimary,
      });
      rec({ soort: 'BETROKKENE_TOEGEVOEGD', naarWaarde: `${name} (${invoer.role})` });
      if (invoer.isPrimary) rec({ soort: 'PROCESBEGELEIDER', samenvoegen: true, vanWaarde: procesbegeleider?.name, naarWaarde: name });
      return pdca.betrokkenen.listByPlan(plan.uuid);
    },

    async verwijderBetrokkene(betrokkene: Betrokkene): Promise<void> {
      await pdca.betrokkenen.delete(betrokkene.id);
      rec({ soort: 'BETROKKENE_VERWIJDERD', naarWaarde: `${betrokkene.name} (${betrokkene.role})` });
      if (betrokkene.isPrimary) rec({ soort: 'PROCESBEGELEIDER', samenvoegen: true, vanWaarde: betrokkene.name, naarWaarde: null });
    },
  };
}
