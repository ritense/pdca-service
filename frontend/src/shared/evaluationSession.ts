import { offerPanel } from './bridge';
import { openplan, PDCA } from './api';
import { get, HttpError, isHttpStatus, send } from './http';

export const EVALUATION_PANEL_BUNDLE_KEY = 'evaluation';

export interface EvaluationSession {
  id: string;
  dossierId: string;
  caseDefinitionKey: string | null;
  planUuid: string;
  userLogin: string;
  status: 'RUNNING' | 'COMPLETED' | 'CANCELLED';
  startedAt: string;
  endedAt: string | null;
  evaluatieType: string | null;
  deelnemers: string | null;
  /** Gespreksverslag; becomes the contactmoment's notitie on completion. */
  verslag: string | null;
  contactmomentUuid: string | null;
  /** The planned contactmoment this evaluation carries out; completing turns it into the afgerond one. */
  geplandContactmomentUuid: string | null;
}

export type EvaluationSubjectType = 'PLAN' | 'HOOFDDOEL' | 'SUBDOEL' | 'INSTRUMENT' | 'ACTIE';

/** A plan change as recorded in an evaluation (see labels.ts for its description). */
export interface EvaluationChange {
  id: string;
  subjectType: EvaluationSubjectType;
  subjectUuid: string;
  subjectTitel: string | null;
  soort: string;
  vanWaarde: string | null;
  naarWaarde: string | null;
  /** The reason given in the evaluation. */
  toelichting: string | null;
  createdAt: string;
}

export interface EvaluationChangeInput {
  subjectType: EvaluationSubjectType;
  subjectUuid: string;
  subjectTitel?: string | null;
  soort: string;
  vanWaarde?: string | null;
  naarWaarde?: string | null;
  /** Reason entered with the change in the tab; becomes the change's reason in the side panel. */
  toelichting?: string;
  /** Value changes: a later change of the same kind on the same subject replaces this one. */
  samenvoegen?: boolean;
}

export type StartResult =
  | { session: EvaluationSession; conflict: null }
  | { session: null; conflict: EvaluationSession | null; message: string };

const SESSIONS = `${PDCA}/evaluation-sessions`;

// ------------------------------------------------------ session events
// GZAC sandboxes every bundle at its own opaque origin, so the side panel and
// the plan tabs cannot message each other. Each iframe follows the session
// through the backend instead: the panel polls its changes, a tab with a
// running session polls whether it is still running.

export const EVALUATION_POLL_MS = 3000;

export type EvaluationEvent =
  | { type: 'started'; session: EvaluationSession }
  | { type: 'ended'; session: EvaluationSession };

const listeners = new Set<(event: EvaluationEvent) => void>();

/** The user's running session as this iframe knows it; changes are only recorded for its plan. */
let activeSession: EvaluationSession | null = null;
let pollTimer: ReturnType<typeof setInterval> | null = null;

function setActiveSession(session: EvaluationSession | null) {
  activeSession = session;
  if (session && !pollTimer) pollTimer = setInterval(checkStillRunning, EVALUATION_POLL_MS);
  if (!session && pollTimer) { clearInterval(pollTimer); pollTimer = null; }
}

/**
 * Ends the session in this iframe once it was completed or cancelled
 * elsewhere (in the side panel). A failed check decides nothing: the next
 * tick asks again.
 */
async function checkStillRunning() {
  const known = activeSession;
  if (!known) return;
  const current = await currentEvaluationSession().catch(() => known);
  if (current?.id !== known.id && activeSession?.id === known.id) emit({ type: 'ended', session: known });
}

function emit(event: EvaluationEvent) {
  if (event.type === 'started') setActiveSession(event.session);
  if (event.type === 'ended' && activeSession?.id === event.session.id) setActiveSession(null);
  listeners.forEach(listener => listener(event));
}

/** Subscribes to the session starting or ending in this iframe; returns the unsubscribe function. */
export function onEvaluationEvent(listener: (event: EvaluationEvent) => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

// ------------------------------------------------------------- the session

/** The user's own running evaluation session, if any. */
export async function currentEvaluationSession(): Promise<EvaluationSession | null> {
  return (await get<EvaluationSession | undefined>(`${SESSIONS}/current`)) ?? null;
}

/**
 * Starts a session on the dossier of the current GZAC surface. With a
 * planned contactmoment the session carries that one out; without it the
 * backend links the earliest planned contactmoment that is due. 409: the
 * user already runs one (returned as the conflict).
 */
export async function startEvaluationSession(geplandContactmomentUuid?: string): Promise<StartResult> {
  try {
    const session = await send<EvaluationSession>(
      'POST', SESSIONS, geplandContactmomentUuid ? { geplandContactmomentUuid } : undefined);
    emit({ type: 'started', session });
    return { session, conflict: null };
  } catch (e) {
    if (!isHttpStatus(e, 409)) throw e;
    const conflict = (e.body as { session?: EvaluationSession } | null)?.session ?? null;
    return { session: null, conflict, message: e.message };
  }
}

/** Saves the contactmoment fields of the running evaluation. */
export const updateEvaluationDraft = (draft: { evaluatieType?: string; deelnemers?: string; verslag?: string }) =>
  send<EvaluationSession>('POST', `${SESSIONS}/current/draft`, draft);

/** Completes the running evaluation: it becomes an afgerond contactmoment, visible to colleagues. */
export async function completeEvaluationSession(): Promise<EvaluationSession> {
  const session = await send<EvaluationSession>('POST', `${SESSIONS}/current/complete`);
  emit({ type: 'ended', session });
  return session;
}

/**
 * Cancels the running evaluation: no contactmoment is recorded; the plan
 * changes made during it stay, like any other plan edit.
 */
export async function cancelEvaluationSession(): Promise<EvaluationSession> {
  const session = await send<EvaluationSession>('POST', `${SESSIONS}/current/cancel`);
  emit({ type: 'ended', session });
  return session;
}

export const listEvaluationChanges = () => get<EvaluationChange[]>(`${SESSIONS}/current/changes`);

export const updateChangeToelichting = (id: string, toelichting: string) =>
  send<void>('POST', `${SESSIONS}/current/change-toelichting`, { id, toelichting });

/**
 * Plan tabs call this after every successful plan change. While the user's
 * evaluation of this plan runs, the change becomes part of it and shows up in
 * the side panel; otherwise nothing happens. A failure to record never undoes
 * or blocks the change itself.
 */
export async function recordEvaluationChange(planUuid: string, change: EvaluationChangeInput): Promise<void> {
  if (!activeSession || activeSession.planUuid !== planUuid) return;
  try {
    await send<void>('POST', `${SESSIONS}/current/changes`, { planUuid, ...change });
  } catch (e) {
    if (e instanceof HttpError && (e.status === 404 || e.status === 409)) {
      if (activeSession) emit({ type: 'ended', session: activeSession });
      return;
    }
    console.warn('Wijziging niet vastgelegd in de evaluatie', e);
  }
}

export const planTitle = (session: EvaluationSession): Promise<string> =>
  openplan.plannen.get(session.planUuid).then(p => p.titel);

/** Offers the side panel of [session]; without the plan's title (it could not be read) its subtitle is just "Plan". */
export async function offerEvaluationPanel(session: EvaluationSession): Promise<void> {
  offerPanel({
    bundleKey: EVALUATION_PANEL_BUNDLE_KEY,
    key: session.id,
    title: 'Evaluatie',
    subtitle: await planTitle(session).catch(() => 'Plan'),
    context: { evaluationSessionId: session.id },
  });
}

/**
 * Plan pages call this on load: the user's running evaluation of this plan
 * comes back into the side panel, also after it was closed, and changes made
 * on the page are recorded in it.
 */
export async function syncEvaluationPanel(documentId: string | null | undefined): Promise<EvaluationSession | null> {
  if (!documentId) return null;
  const session = await currentEvaluationSession();
  setActiveSession(session);
  if (session && session.dossierId === documentId) await offerEvaluationPanel(session);
  return session;
}
