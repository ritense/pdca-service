import React, { createContext, useContext, useEffect, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { Theme } from '@carbon/react';
import { getHostTheme, getInitContext, GzacContext, HostTheme, onHostThemeChanged, onInit } from './bridge';

/** GZAC's `init` context, including keys from a side-panel offer. */
export type InitContext = GzacContext & Record<string, unknown>;

const InitContextValue = createContext<InitContext | null>(null);

/**
 * The root of every bundle: follows GZAC's theme (light or dark, also when
 * it switches later) for everything the bundle shows, loading and error
 * states included, and renders the page once GZAC's `init` arrived (the
 * bridge shows "Wachten op GZAC…" until then).
 */
export function PdcaRoot({ className = 'pdca-root', children }: { className?: string; children: React.ReactNode }) {
  const [theme, setTheme] = useState<HostTheme>(getHostTheme());
  const [context, setContext] = useState<InitContext | null>(null);

  useEffect(() => onHostThemeChanged(setTheme), []);
  useEffect(() => {
    onInit(() => {
      setTheme(getHostTheme());
      setContext(getInitContext() as InitContext);
    });
  }, []);

  return (
    <Theme theme={theme} className={className}>
      {context && <InitContextValue.Provider value={context}>{children}</InitContextValue.Provider>}
    </Theme>
  );
}

/** The `init` context of the GZAC surface this bundle runs in; only inside [PdcaRoot]. */
export function useInitContext(): InitContext {
  const context = useContext(InitContextValue);
  if (!context) throw new Error('useInitContext buiten PdcaRoot');
  return context;
}

/** Mounts a bundle's page in `#root` under [PdcaRoot]. */
export function mountBundle(page: React.ReactNode, rootClassName?: string): void {
  createRoot(document.getElementById('root')!).render(
    <React.StrictMode>
      <PdcaRoot className={rootClassName}>{page}</PdcaRoot>
    </React.StrictMode>,
  );
}
