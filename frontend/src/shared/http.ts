/**
 * The one HTTP client of the bundles. Every call travels through GZAC
 * (bridge `pluginData`) to the app's `/data` route, which verifies the user
 * and checks access before the app's own handler runs; nothing is fetched
 * from the app directly. A failed call rejects with an [HttpError]: callers
 * branch on its `status`, never on the text of its message.
 */

import { HttpMethod, pluginData } from './bridge';

type Query = Record<string, string>;

/** What a page or section was loading when a call failed: decides the wording of a 403. */
export type FoutContext = 'dossier' | 'beheer' | 'algemeen';

/** A failed call: the HTTP status (0 without an answer), the backend's `detail` and the raw body. */
export class HttpError extends Error {
  constructor(readonly status: number, readonly detail: string, readonly body: unknown) {
    super(foutTekst(status, detail));
    this.name = 'HttpError';
  }
}

/** The backend's own explanation of a failed call (`detail`, or a register's `invalidParams`). */
function detailVan(body: unknown): string {
  const b = body as { invalidParams?: { name: string; reason: string }[]; detail?: unknown; message?: unknown } | null;
  if (!b || typeof b !== 'object') return '';
  if (Array.isArray(b.invalidParams)) return b.invalidParams.map(p => `${p.name}: ${p.reason}`).join(', ');
  if (typeof b.detail === 'string') return b.detail;
  if (typeof b.message === 'string') return b.message;
  return '';
}

/** The generic refusal of the access policy; anything else a 403 says is specific and shown as is. */
const GEEN_TOEGANG = 'Geen toegang';

/** The sentence a user sees for a failed call, worded for what was being loaded. */
function foutTekst(status: number, detail: string, context: FoutContext = 'algemeen'): string {
  if (status === 401) return 'Sessie verlopen — herlaad de pagina';
  // The rate limit of the app's `/data` route; its own wording would not tell the user what to do.
  if (status === 429) return 'Te veel verzoeken — probeer het over een minuut opnieuw';
  if (status === 403 && (!detail || detail === GEEN_TOEGANG)) {
    if (context === 'dossier') return 'Geen toegang tot dit dossier';
    if (context === 'beheer') return 'Alleen beheerders hebben toegang tot PDCA Beheer';
    return 'Je hebt hiervoor geen toegang';
  }
  if (detail) return detail;
  return status === 0 ? 'Geen verbinding met GZAC' : `Er ging iets mis (HTTP ${status})`;
}

/** The message of any error for the user; an [HttpError] is worded for [context]. */
export function foutmelding(error: unknown, context: FoutContext = 'algemeen'): string {
  if (error instanceof HttpError) return foutTekst(error.status, error.detail, context);
  if (error instanceof Error) return error.message;
  return String(error);
}

export const isHttpStatus = (error: unknown, ...statuses: number[]): error is HttpError =>
  error instanceof HttpError && statuses.includes(error.status);

/** Sends one call; resolves with the body (undefined for 204), rejects with an [HttpError]. */
export async function request<T>(
  method: HttpMethod,
  path: string,
  options: { query?: Query; body?: unknown } = {},
): Promise<T> {
  const { status, body } = await pluginData<T>(method, path, options);
  if (status < 200 || status >= 300) throw new HttpError(status, detailVan(body), body);
  if (status === 204) return undefined as T;
  return body;
}

export const get = <T>(path: string, query?: Query) => request<T>('GET', path, { query });
export const send = <T>(method: HttpMethod, path: string, body?: unknown) => request<T>(method, path, { body });

interface Paginated<T> {
  next: string | null;
  results: T[];
}

/** The most pages [listAll] reads; a longer listing fails instead of being shown partially. */
export const MAX_PAGES = 20;

/** Fetches all pages of a paginated register listing; rejects when it has more than [MAX_PAGES]. */
export async function listAll<T>(path: string, params: Query = {}): Promise<T[]> {
  const results: T[] = [];
  for (let page = 1; page <= MAX_PAGES; page++) {
    const body = await get<Paginated<T>>(path, { ...params, page: String(page) });
    results.push(...body.results);
    if (!body.next) return results;
  }
  throw new Error(`De lijst is te lang om volledig te tonen (meer dan ${MAX_PAGES} pagina's)`);
}
