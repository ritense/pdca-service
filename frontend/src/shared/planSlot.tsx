/**
 * The plan lock as the tabs see it (the backend enforces it, ARCHITECTURE
 * §6.7): once a plan has left its concept status, its subdoelen, their
 * toelichting and beoogde einddatum, the plan's toelichting bij het
 * hoofddoel and its instrument requests change only in the user's own
 * evaluation of the plan. The tabs disable those actions and say why, so
 * nobody runs into the backend's refusal.
 */

import React from 'react';
import { InlineNotification } from '@carbon/react';
import type { EvaluationSession } from './evaluationSession';
import type { PlanDetails } from './types';

/** The concept status of a case type: its first display status, "Concept" without any. */
export function conceptStatus(planStatussen: string[]): string {
  return planStatussen[0] ?? 'Concept';
}

/** Whether the plan is still in its concept status (a plan without display status is). */
export function inConcept(details: PlanDetails, planStatussen: string[]): boolean {
  const huidig = details.weergaveStatus?.trim();
  return !huidig || huidig.toLowerCase() === conceptStatus(planStatussen).toLowerCase();
}

/** Whether the locked parts of the plan cannot change now: past concept and not in the user's evaluation of it. */
export function isVergrendeld(
  planUuid: string, details: PlanDetails, planStatussen: string[], sessie: EvaluationSession | null,
): boolean {
  return !inConcept(details, planStatussen) && sessie?.planUuid !== planUuid;
}

/** Why a locked action is disabled, as a short hint next to it. */
export const VERGRENDELD_HINT = 'Wijzig je tijdens een evaluatie';

/** Shown on a plan tab while the plan's locked parts cannot change. */
export function PlanSlotMelding({ wat }: { wat: string }) {
  return (
    <InlineNotification className="pdca-notification" kind="info" lowContrast hideCloseButton title="Plan vastgesteld"
      subtitle={`${wat} wijzig je tijdens een evaluatie, zodat de inwoner op de hoogte is en de reden wordt vastgelegd. Start een evaluatie op het tabblad Evaluaties.`} />
  );
}
