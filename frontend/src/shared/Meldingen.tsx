import React from 'react';
import { Button, InlineNotification, Loading } from '@carbon/react';
import { Renew } from '@carbon/react/icons';
import { FoutContext, foutmelding, isHttpStatus } from './http';

/** The page's spinner while its first data comes in. */
export function PaginaLaden({ beschrijving }: { beschrijving?: string }) {
  return <div className="pdca-container"><Loading description={beschrijving ?? 'Laden…'} withOverlay={false} /></div>;
}

/** A page that cannot be shown: one sentence, no retry. */
export function PaginaMelding({ titel, tekst }: { titel: string; tekst?: string }) {
  return (
    <div className="pdca-container">
      <InlineNotification kind="error" lowContrast hideCloseButton title={titel} subtitle={tekst} />
    </div>
  );
}

/**
 * A failed load of something the page or section cannot do without: what
 * failed, why (401/403 worded for the [context]) and a retry. After a 401
 * only reloading the page helps, so there is no retry then.
 */
export function LaadFout({ titel, error, context, onRetry, pagina = false }: {
  titel: string;
  error: unknown;
  context?: FoutContext;
  onRetry?: () => void;
  /** Takes the whole page (with its padding) instead of a block within it. */
  pagina?: boolean;
}) {
  const inhoud = (
    <div className="pdca-laadfout">
      <InlineNotification kind="error" lowContrast hideCloseButton title={titel} subtitle={foutmelding(error, context)} />
      {onRetry && !isHttpStatus(error, 401) && (
        <Button size="sm" kind="ghost" renderIcon={Renew} onClick={onRetry}>Opnieuw proberen</Button>
      )}
    </div>
  );
  return pagina ? <div className="pdca-container">{inhoud}</div> : inhoud;
}

/**
 * An optional part that could not be loaded: the page works without it, but
 * says so instead of showing an empty or guessed value.
 */
export function NietGeladen({ wat, error, onRetry }: { wat: string; error: unknown; onRetry?: () => void }) {
  return (
    <p className="pdca-niet-geladen" role="status">
      {wat} kon niet geladen worden ({foutmelding(error)}).
      {onRetry && !isHttpStatus(error, 401) && (
        <> <button type="button" className="pdca-link-button" onClick={onRetry}>Opnieuw proberen</button></>
      )}
    </p>
  );
}
