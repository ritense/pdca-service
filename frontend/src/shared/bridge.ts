export interface GzacContext {
  documentId?: string;
  caseDefinitionKey?: string;
  caseDefinitionVersionTag?: string;
  pluginConfigurationId?: string;
}

type InitCallback = (context: GzacContext) => void;

export interface PluginDataResult<T> {
  status: number;
  body: T;
}

/** GZAC's answer to a task-form submission (plugin-SDK `submitResult`). */
export interface SubmitTaskResult {
  ok: boolean;
  errors?: string[];
  fieldErrors?: Record<string, string>;
}

export type HostTheme = 'white' | 'g10' | 'g90' | 'g100';

const HOST_THEMES: HostTheme[] = ['white', 'g10', 'g90', 'g100'];

function applyHostTheme(theme: unknown): void {
  if (!HOST_THEMES.includes(theme as HostTheme) || theme === hostTheme) return;
  hostTheme = theme as HostTheme;
  themeListeners.forEach(listener => listener(hostTheme));
}

export interface PanelOffer {
  bundleKey?: string;
  key: string;
  title: string;
  subtitle?: string;
  context?: Record<string, unknown>;
}

let initCallback: InitCallback | null = null;
let initialized = false;
let initContext: Record<string, unknown> = {};
let hostTheme: HostTheme = 'white';
const themeListeners = new Set<(theme: HostTheme) => void>();
let correlationCounter = 0;
const pendingRequests = new Map<string, (result: PluginDataResult<unknown>) => void>();
const pendingSubmits = new Map<string, (result: SubmitTaskResult) => void>();

window.addEventListener('message', (event) => {
  const data = event.data;
  if (data?.source !== 'valtimo-host') return;
  if (data.event === 'init') {
    initContext = data.payload?.context || {};
    applyHostTheme(data.payload?.theme);
    if (!initialized && initCallback) {
      initialized = true;
      initCallback(initContext as GzacContext);
    }
  } else if (data.event === 'themeChanged') {
    applyHostTheme(data.payload?.theme);
  } else if (data.event === 'proxyResponse') {
    const resolve = pendingRequests.get(data.payload?.correlationId);
    if (!resolve) return;
    pendingRequests.delete(data.payload.correlationId);
    resolve({ status: data.payload.status ?? 0, body: data.payload.body ?? null });
  } else if (data.event === 'submitResult') {
    const resolve = pendingSubmits.get(data.payload?.correlationId);
    if (!resolve) return;
    pendingSubmits.delete(data.payload.correlationId);
    resolve({
      ok: data.payload.ok === true,
      errors: Array.isArray(data.payload.errors) ? data.payload.errors : undefined,
      fieldErrors: data.payload.fieldErrors ?? undefined,
    });
  }
});

/** GZAC's current Carbon theme (light/dark); `white` until the host says otherwise. */
export function getHostTheme(): HostTheme {
  return hostTheme;
}

/** Subscribes to GZAC theme switches; returns the unsubscribe function. */
export function onHostThemeChanged(listener: (theme: HostTheme) => void): () => void {
  themeListeners.add(listener);
  return () => themeListeners.delete(listener);
}

/** The full context GZAC sent with `init`, including keys from a side-panel offer. */
export function getInitContext(): Record<string, unknown> {
  return initContext;
}

export function onInit(callback: InitCallback): void {
  initCallback = callback;
  window.parent.postMessage({ source: 'valtimo-plugin', event: 'ready' }, '*');
  setTimeout(() => {
    if (!initialized) {
      initialized = true;
      callback({});
    }
  }, 500);
}

export function resizeIframe(): void {
  requestAnimationFrame(() => {
    window.parent.postMessage({
      source: 'valtimo-plugin',
      event: 'resize',
      payload: { height: document.documentElement.scrollHeight },
    }, '*');
  });
}

/**
 * Asks the GZAC host to navigate its router (plugin-SDK message
 * `navigate: { route }`), e.g. to another dossier:
 * `/cases/<caseDefinitionKey>/document/<documentId>`. Requires a host that
 * handles the navigate event; hosts that don't simply ignore the message.
 */
export function navigateHost(route: string): void {
  window.parent.postMessage({
    source: 'valtimo-plugin',
    event: 'navigate',
    payload: { route },
  }, '*');
}

const PLUGIN_DATA_TIMEOUT_MS = 30000;

/**
 * Calls this app's own `/data` route through the GZAC host (plugin-SDK
 * `proxyRequest`, target `plugin`). GZAC attaches the user's downscoped
 * token, which the backend verifies; use this for anything that depends on
 * who the user is. Without an answer from the host within 30 seconds it
 * resolves with status 0 and a message, so a caller never waits forever.
 */
export function pluginData<T>(method: 'GET' | 'POST', path: string, body?: unknown): Promise<PluginDataResult<T>> {
  const correlationId = `pdca-${++correlationCounter}`;
  return new Promise(resolve => {
    pendingRequests.set(correlationId, resolve as (result: PluginDataResult<unknown>) => void);
    setTimeout(() => {
      if (!pendingRequests.delete(correlationId)) return;
      resolve({ status: 0, body: { message: 'Geen antwoord van GZAC' } as T });
    }, PLUGIN_DATA_TIMEOUT_MS);
    window.parent.postMessage({
      source: 'valtimo-plugin',
      event: 'proxyRequest',
      payload: { correlationId, target: 'plugin', method, path, body },
    }, '*');
  });
}

/**
 * Offers one of this app's `side-panel` bundles to GZAC's side panel (the
 * latest offer takes it over; re-offering the same key only shows it again).
 */
export function offerPanel(offer: PanelOffer): void {
  window.parent.postMessage({ source: 'valtimo-plugin', event: 'offerPanel', payload: offer }, '*');
}

/** Removes the side-panel content this app offered under `key`. */
export function withdrawPanel(key: string, bundleKey?: string): void {
  window.parent.postMessage({ source: 'valtimo-plugin', event: 'withdrawPanel', payload: { bundleKey, key } }, '*');
}

function newCorrelationId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    try { return crypto.randomUUID(); } catch { /* not a secure context */ }
  }
  return `pdca-${++correlationCounter}`;
}

/**
 * Submits the external task form this bundle runs in (plugin-SDK
 * `submitTask`); GZAC completes the user task with `data`. Resolves with the
 * host's `submitResult` for this submission. On success GZAC closes the task
 * modal and removes the iframe right away, so the promise may never resolve
 * then: anything that has to happen after a successful submission must be
 * posted in the same tick as this call.
 */
export function submitTask(data: Record<string, unknown>): Promise<SubmitTaskResult> {
  const correlationId = newCorrelationId();
  return new Promise(resolve => {
    pendingSubmits.set(correlationId, resolve);
    window.parent.postMessage({
      source: 'valtimo-plugin',
      event: 'submitTask',
      payload: { correlationId, data },
    }, '*');
  });
}
