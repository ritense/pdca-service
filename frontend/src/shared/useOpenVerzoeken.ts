import { useCallback, useEffect, useRef } from 'react';
import { OpenVerzoek, pdca } from './api';
import { useResource } from './useResource';

/** Polling for the instrument of a new verzoek: first pause, longest pause and how long to keep trying. */
const POLL_EERSTE_MS = 2000;
const POLL_MAX_MS = 16000;
const POLL_TOTAAL_MS = 60000;

/**
 * The plan's verzoeken whose instrument has not appeared yet ("Aangevraagd";
 * read from the Objecten API, so it can fail on its own). [volg] follows a
 * verzoek just filed: it reloads the verzoeken and, through [herlaadPlan],
 * the plan's instruments with growing pauses until the verzoek is no longer
 * open or a minute has passed. Polling stops on unmount.
 */
export function useOpenVerzoeken(planUuid: string, herlaadPlan: () => Promise<unknown>) {
  const verzoeken = useResource(() => pdca.instrumenten.verzoeken(planUuid), [planUuid]);
  const timer = useRef<number | null>(null);
  /** Bumped by [stop]: a tick of an older run that is still waiting for its reload schedules nothing more. */
  const run = useRef(0);
  const herlaadRef = useRef(herlaadPlan);
  herlaadRef.current = herlaadPlan;
  const reloadVerzoeken = verzoeken.reload;

  const stop = useCallback(() => {
    run.current++;
    if (timer.current !== null) window.clearTimeout(timer.current);
    timer.current = null;
  }, []);

  useEffect(() => stop, [planUuid, stop]);

  const volg = useCallback((isDitVerzoek: (verzoek: OpenVerzoek) => boolean) => {
    stop();
    const id = run.current;
    const begin = Date.now();
    let pauze = POLL_EERSTE_MS;
    const tick = async () => {
      timer.current = null;
      const [open] = await Promise.all([reloadVerzoeken(), herlaadRef.current()]);
      if (id !== run.current) return;
      // A failed read decides nothing: keep trying until the time is up.
      const nogOpen = open === undefined || open.some(isDitVerzoek);
      pauze = Math.min(pauze * 2, POLL_MAX_MS);
      if (nogOpen && Date.now() - begin + pauze <= POLL_TOTAAL_MS) timer.current = window.setTimeout(tick, pauze);
    };
    timer.current = window.setTimeout(tick, pauze);
  }, [stop, reloadVerzoeken]);

  return { verzoeken, volg };
}
