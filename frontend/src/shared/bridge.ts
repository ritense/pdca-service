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

/**
 * Calls this app's own `/data` route through the GZAC host (plugin-SDK
 * `proxyRequest`, target `plugin`). GZAC attaches the user's downscoped
 * token, which the backend verifies; use this for anything that depends on
 * who the user is.
 */
export function pluginData<T>(method: 'GET' | 'POST', path: string, body?: unknown): Promise<PluginDataResult<T>> {
  const correlationId = `pdca-${++correlationCounter}`;
  return new Promise(resolve => {
    pendingRequests.set(correlationId, resolve as (result: PluginDataResult<unknown>) => void);
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
