import { offerPanel, pluginData } from './bridge';
import { openplan } from './api';

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
}

export type StartResult =
  | { session: EvaluationSession; conflict: null }
  | { session: null; conflict: EvaluationSession | null; message: string };

const errorMessage = (body: unknown, status: number) =>
  (body as { message?: string } | null)?.message ?? `HTTP ${status}`;

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
  if (status === 201) return { session: body, conflict: null };
  if (status === 409) return { session: null, conflict: body?.session ?? null, message: errorMessage(body, status) };
  throw new Error(errorMessage(body, status));
}

export async function endEvaluationSession(outcome: 'complete' | 'cancel'): Promise<void> {
  const { status, body } = await pluginData('POST', `/evaluation-sessions/current/${outcome}`);
  if (status !== 200 && status !== 404) throw new Error(errorMessage(body, status));
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
 * comes back into the side panel, also after it was closed.
 */
export async function syncEvaluationPanel(documentId: string | null | undefined): Promise<EvaluationSession | null> {
  if (!documentId) return null;
  const session = await currentEvaluationSession().catch(() => null);
  if (session && session.dossierId === documentId) await offerEvaluationPanel(session);
  return session;
}
