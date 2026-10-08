import React from 'react';
import { InlineNotification } from '@carbon/react';

/** Shown on the plan tabs while the user's evaluation of this plan runs. */
export function EvaluatieBanner() {
  return (
    <InlineNotification kind="info" lowContrast hideCloseButton title="Evaluatie loopt"
      subtitle="Wijzigingen die je nu in het plan doet, worden vastgelegd in de evaluatie in het zijpaneel." />
  );
}
