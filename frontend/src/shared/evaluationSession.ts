import { offerPanel, pluginData } from './bridge';
import { openplan } from './api';

export const EVALUATION_PANEL_BUNDLE_KEY = 'evaluation';

export interface EvaluationSession {
  id: string;
  dossierId: string;
  caseDefinitionKey: string | null;
  planUuid: string;
  userLogin: string;
  status: 'RUNNING' | 'COMPLETED';
  startedAt: string;
  endedAt: string | null;
  evaluatieType: string | null;
  deelnemers: string | null;
  /** Gespreksverslag; becomes the contactmoment's notitie on completion. */
  verslag: string | null;
  contactmomentUuid: string | null;
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

const errorMessage = (body: unknown, status: number) =>
  (body as { message?: string } | null)?.message ?? `HTTP ${status}`;

// ------------------------------------------------------ session events
// GZAC sandboxes every bundle at its own opaque origin, so the side panel and
// the plan tabs cannot message each other. Each iframe follows the session
// through the backend instead: the panel polls its changes, a tab with a
// running session polls whether it is still running.

export const EVALUATION_POLL_MS = 3000;

export type EvaluationEvent =
  | { type: 'started'; session: EvaluationSession }
  | { type: 'completed'; session: EvaluationSession };

const listeners = new Set<(event: EvaluationEvent) => void>();

/** The user's running session as this iframe knows it; changes are only recorded for its plan. */
let activeSession: EvaluationSession | null = null;
let pollTimer: ReturnType<typeof setInterval> | null = null;

function setActiveSession(session: EvaluationSession | null) {
  activeSession = session;
  if (session && !pollTimer) pollTimer = setInterval(checkStillRunning, EVALUATION_POLL_MS);
  if (!session && pollTimer) { clearInterval(pollTimer); pollTimer = null; }
}

/** Ends the session in this iframe once it was completed elsewhere (in the side panel). */
async function checkStillRunning() {
  const known = activeSession;
  if (!known) return;
  const current = await currentEvaluationSession().catch(() => known);
  if (current?.id !== known.id && activeSession?.id === known.id) emit({ type: 'completed', session: known });
}

function emit(event: EvaluationEvent) {
  if (event.type === 'started') setActiveSession(event.session);
  if (event.type === 'completed' && activeSession?.id === event.session.id) setActiveSession(null);
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
  const { status, body } = await pluginData<EvaluationSession | null>('GET', '/evaluation-sessions/current');
  if (status === 204) return null;
  if (status !== 200) throw new Error(errorMessage(body, status));
  return body;
}

/** Starts a session on the dossier of the current GZAC surface. */
export async function startEvaluationSession(): Promise<StartResult> {
  const { status, body } = await pluginData<any>('POST', '/evaluation-sessions');
  if (status === 201) {
    emit({ type: 'started', session: body });
    return { session: body, conflict: null };
  }
  if (status === 409) return { session: null, conflict: body?.session ?? null, message: errorMessage(body, status) };
  throw new Error(errorMessage(body, status));
}

/** Saves the contactmoment fields of the running evaluation. */
export async function updateEvaluationDraft(
  draft: { evaluatieType?: string; deelnemers?: string; verslag?: string }
): Promise<EvaluationSession> {
  const { status, body } = await pluginData<any>('POST', '/evaluation-sessions/current/draft', draft);
  if (status !== 200) throw new Error(errorMessage(body, status));
  return body;
}

/** Completes the running evaluation: it becomes an afgerond contactmoment, visible to colleagues. */
export async function completeEvaluationSession(): Promise<EvaluationSession> {
  const { status, body } = await pluginData<any>('POST', '/evaluation-sessions/current/complete');
  if (status !== 200) throw new Error(errorMessage(body, status));
  emit({ type: 'completed', session: body });
  return body;
}

export async function listEvaluationChanges(): Promise<EvaluationChange[]> {
  const { status, body } = await pluginData<any>('GET', '/evaluation-sessions/current/changes');
  if (status !== 200) throw new Error(errorMessage(body, status));
  return body;
}

export async function updateChangeToelichting(id: string, toelichting: string): Promise<void> {
  const { status, body } = await pluginData('POST', '/evaluation-sessions/current/change-toelichting', { id, toelichting });
  if (status !== 200) throw new Error(errorMessage(body, status));
}

/**
 * Plan tabs call this after every successful plan change. While the user's
 * evaluation of this plan runs, the change becomes part of it and shows up in
 * the side panel; otherwise nothing happens. A failure to record never undoes
 * or blocks the change itself.
 */
export async function recordEvaluationChange(planUuid: string, change: EvaluationChangeInput): Promise<void> {
  if (!activeSession || activeSession.planUuid !== planUuid) return;
  try {
    const { status, body } = await pluginData('POST', '/evaluation-sessions/current/changes', { planUuid, ...change });
    if (status === 404 || status === 409) {
      if (activeSession) emit({ type: 'completed', session: activeSession });
      return;
    }
    if (status !== 200 && status !== 204) throw new Error(errorMessage(body, status));
  } catch (e) {
    console.warn('Wijziging niet vastgelegd in de evaluatie', e);
  }
}

export async function planTitle(session: EvaluationSession): Promise<string> {
  return openplan.plannen.get(session.planUuid).then(p => p.titel).catch(() => 'Plan');
}

export async function offerEvaluationPanel(session: EvaluationSession): Promise<void> {
  offerPanel({
    bundleKey: EVALUATION_PANEL_BUNDLE_KEY,
    key: session.id,
    title: 'Evaluatie',
    subtitle: await planTitle(session),
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
  const session = await currentEvaluationSession().catch(() => null);
  setActiveSession(session);
  if (session && session.dossierId === documentId) await offerEvaluationPanel(session);
  return session;
}
