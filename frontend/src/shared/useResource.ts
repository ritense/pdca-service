import { DependencyList, useCallback, useEffect, useRef, useState } from 'react';

/**
 * One source of data on a page, with its own loading and error state: a
 * failed source never turns into an empty list. A reload keeps the data it
 * had (the page does not flash) and sets `error` when it fails.
 */
export interface Resource<T> {
  /** undefined until the first load succeeded. */
  data: T | undefined;
  error: unknown;
  loading: boolean;
  /** Loads again; resolves with the new data, or undefined when it failed (the failure is in `error`). */
  reload: () => Promise<T | undefined>;
  /** Replaces the data locally, e.g. with what a save returned. */
  mutate: (update: (data: T | undefined) => T) => void;
}

/**
 * Loads [load] on mount and whenever [deps] change; `load` null means there
 * is nothing to load (yet). Results of an outdated load, or arriving after
 * unmount, are dropped.
 */
export function useResource<T>(load: (() => Promise<T>) | null, deps: DependencyList): Resource<T> {
  const [state, setState] = useState<{ data: T | undefined; error: unknown; loading: boolean }>(
    { data: undefined, error: null, loading: !!load });
  const loadRef = useRef(load);
  loadRef.current = load;
  const run = useRef(0);

  const reload = useCallback(async (): Promise<T | undefined> => {
    const fn = loadRef.current;
    const id = ++run.current;
    if (!fn) {
      setState({ data: undefined, error: null, loading: false });
      return undefined;
    }
    setState(s => ({ ...s, loading: true }));
    try {
      const data = await fn();
      if (id === run.current) setState({ data, error: null, loading: false });
      return data;
    } catch (error) {
      if (id === run.current) setState(s => ({ data: s.data, error, loading: false }));
      return undefined;
    }
  }, []);

  useEffect(() => {
    setState({ data: undefined, error: null, loading: !!loadRef.current });
    reload();
    // `run` is a load counter, not an element ref: the cleanup must bump its latest value.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    return () => { run.current++; };
    // The caller's deps decide when to load again; `load` itself is read through loadRef.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);

  const mutate = useCallback((update: (data: T | undefined) => T) =>
    setState(s => ({ ...s, data: update(s.data) })), []);

  return { ...state, reload, mutate };
}

/** What [eersteFout] needs of a source. */
export type Bron = { data: unknown; error: unknown; reload: () => Promise<unknown> };

/** The first failure among sources the page cannot do without, with a reload of all of them. */
export function eersteFout(...resources: Bron[]): { error: unknown; reload: () => Promise<void> } | null {
  const failed = resources.filter(r => r.error);
  if (failed.length === 0) return null;
  return { error: failed[0].error, reload: async () => { await Promise.all(failed.map(r => r.reload())); } };
}
