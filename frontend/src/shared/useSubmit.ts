import { useCallback, useRef, useState } from 'react';

/**
 * Submit state of a modal or inline form: a second submit is ignored while
 * the first one runs (`saving` disables the primary button), and a failure
 * stays inside the form as `fout` instead of disappearing behind it.
 * `submit` resolves to true when the action succeeded.
 */
export function useSubmit() {
  const [saving, setSaving] = useState(false);
  const [fout, setFout] = useState<string | null>(null);
  const running = useRef(false);

  const submit = useCallback(async (action: () => Promise<unknown>): Promise<boolean> => {
    if (running.current) return false;
    running.current = true;
    setSaving(true);
    setFout(null);
    try {
      await action();
      return true;
    } catch (e: any) {
      setFout(e?.message ?? String(e));
      return false;
    } finally {
      running.current = false;
      setSaving(false);
    }
  }, []);

  const reset = useCallback(() => setFout(null), []);

  return { saving, fout, submit, reset };
}
