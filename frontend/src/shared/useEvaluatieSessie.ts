import { useCallback, useEffect, useRef, useState } from 'react';
import { EvaluationSession, onEvaluationEvent, syncEvaluationPanel } from './evaluationSession';

/**
 * The user's running evaluation as a plan tab knows it: synced on load (its
 * side panel comes back when it is on this dossier), and followed when it
 * starts or ends. [onEinde] runs when it ends (completed or cancelled in the
 * side panel). `fout`: the session could not be read, so the tab cannot say
 * whether its changes are recorded in an evaluation.
 */
export function useEvaluatieSessie(documentId: string, onEinde?: () => void) {
  const [session, setSession] = useState<EvaluationSession | null>(null);
  const [fout, setFout] = useState<unknown>(null);
  const onEindeRef = useRef(onEinde);
  onEindeRef.current = onEinde;

  const sync = useCallback(() => {
    setFout(null);
    syncEvaluationPanel(documentId).then(setSession, setFout);
  }, [documentId]);

  useEffect(sync, [sync]);

  useEffect(() => onEvaluationEvent(event => {
    if (event.type === 'started') setSession(event.session);
    if (event.type === 'ended') {
      setSession(null);
      onEindeRef.current?.();
    }
  }), []);

  return { session, setSession, fout, opnieuw: sync };
}
