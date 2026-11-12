import { useCallback, useEffect } from 'react';
import { pdca } from './api';
import { DOCUMENT_POLL_MS, isOpen, metAanvraag } from './documenten';
import { useResource } from './useResource';

/**
 * The generated documents of a plan (null: nothing to load), read again
 * every [DOCUMENT_POLL_MS] while one of them is still being made; a failed
 * read keeps the last list and the polling going. [actief] tells whether
 * documents can be made at all (undefined until known). [maakVerslag] makes
 * the evaluatieverslag of an evaluation session (again) and follows it from
 * there. Polling stops on unmount.
 */
export function useDocumenten(planUuid: string | null) {
  const documenten = useResource(planUuid ? () => pdca.documenten.listByPlan(planUuid) : null, [planUuid]);
  const instellingen = useResource(planUuid ? () => pdca.documenten.instellingen() : null, [planUuid]);
  const { reload, mutate } = documenten;
  const open = !!documenten.data?.some(isOpen);

  useEffect(() => {
    if (!open) return;
    let gestopt = false;
    let timer: number | undefined;
    // One read at a time: the next pause starts when the previous read is in.
    const tick = async () => {
      await reload();
      if (!gestopt) timer = window.setTimeout(tick, DOCUMENT_POLL_MS);
    };
    timer = window.setTimeout(tick, DOCUMENT_POLL_MS);
    return () => {
      gestopt = true;
      window.clearTimeout(timer);
    };
  }, [open, reload]);

  const maakVerslag = useCallback(async (sessieId: string) => {
    const aanvraag = await pdca.documenten.maak({ soort: 'EVALUATIEVERSLAG', bronUuid: sessieId });
    mutate(lijst => metAanvraag(lijst ?? [], aanvraag));
    return aanvraag;
  }, [mutate]);

  return { documenten, maakVerslag, actief: instellingen.data?.actief };
}
