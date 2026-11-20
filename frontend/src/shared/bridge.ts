/**
 * The postMessage protocol with the GZAC host (`valtimo-plugin` /
 * `valtimo-host`, the plugin SDK's messages).
 *
 * - Only messages from the parent window are accepted; once `init` arrived,
 *   only from the origin it came from. Messages to the host go to that
 *   origin (before `init`: the parent origin the browser reports, else `*`).
 * - `init` is authoritative: the bundle waits for it however late it comes
 *   and shows "Wachten op GZAC…" meanwhile. Only a page that is not framed at
 *   all starts without context.
 * - All API calls of the bundles go through the host (`pluginData`): GZAC
 *   adds the user's token and posts the call to this app's `/data` route.
 * - The bridge reports the content height to the host itself whenever the
 *   page changes size.
 */

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

export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';

export interface PanelOffer {
  bundleKey?: string;
  key: string;
  title: string;
  subtitle?: string;
  context?: Record<string, unknown>;
}

const HOST_THEMES: HostTheme[] = ['white', 'g10', 'g90', 'g100'];
const PLUGIN_DATA_TIMEOUT_MS = 30000;
const WAITING_NOTICE_DELAY_MS = 1500;
const WAITING_NOTICE_ID = 'pdca-wachten-op-gzac';

/** Framed by GZAC (or anything else); a page opened on its own has no host. */
const embedded = window.parent !== window;

let hostOrigin = embedded ? reportedParentOrigin() : '*';
let originPinned = false;
let initCallback: InitCallback | null = null;
let initContext: Record<string, unknown> | null = null;
let initDelivered = false;
let hostTheme: HostTheme = 'white';
const themeListeners = new Set<(theme: HostTheme) => void>();
const pendingRequests = new Map<string, (result: PluginDataResult<unknown>) => void>();
const pendingSubmits = new Map<string, (result: SubmitTaskResult) => void>();

/** The parent's origin as far as the browser tells a sandboxed frame; `*` when it does not. */
function reportedParentOrigin(): string {
  try {
    const ancestors = (window.location as Location & { ancestorOrigins?: DOMStringList }).ancestorOrigins;
    if (ancestors && ancestors.length > 0 && ancestors[0] !== 'null') return ancestors[0];
  } catch { /* not supported */ }
  try {
    if (document.referrer) {
      const origin = new URL(document.referrer).origin;
      if (origin && origin !== 'null') return origin;
    }
  } catch { /* no usable referrer */ }
  return '*';
}

function postToHost(event: string, payload?: unknown): void {
  if (!embedded) return;
  window.parent.postMessage({ source: 'valtimo-plugin', event, payload }, hostOrigin);
}

function newCorrelationId(): string {
  if (typeof crypto !== 'undefined') {
    if (typeof crypto.randomUUID === 'function') {
      try { return crypto.randomUUID(); } catch { /* not a secure context */ }
    }
    const bytes = crypto.getRandomValues(new Uint8Array(16));
    return Array.from(bytes, b => b.toString(16).padStart(2, '0')).join('');
  }
  throw new Error('Geen veilige willekeurige waarden beschikbaar');
}

function applyHostTheme(theme: unknown): void {
  if (!HOST_THEMES.includes(theme as HostTheme) || theme === hostTheme) return;
  hostTheme = theme as HostTheme;
  themeListeners.forEach(listener => listener(hostTheme));
}

function deliverInit(): void {
  if (initDelivered || !initCallback || initContext === null) return;
  initDelivered = true;
  hideWaitingNotice();
  initCallback(initContext as GzacContext);
}

function showWaitingNotice(): void {
  if (initDelivered || document.getElementById(WAITING_NOTICE_ID)) return;
  const notice = document.createElement('div');
  notice.id = WAITING_NOTICE_ID;
  notice.className = 'pdca-wachten-op-gzac';
  notice.setAttribute('role', 'status');
  notice.textContent = 'Wachten op GZAC…';
  document.body.prepend(notice);
}

function hideWaitingNotice(): void {
  document.getElementById(WAITING_NOTICE_ID)?.remove();
}

window.addEventListener('message', (event: MessageEvent) => {
  if (!embedded || event.source !== window.parent) return;
  if (originPinned && event.origin !== hostOrigin) return;
  const data = event.data;
  if (data?.source !== 'valtimo-host') return;

  if (data.event === 'init') {
    if (!originPinned && event.origin && event.origin !== 'null') {
      hostOrigin = event.origin;
      originPinned = true;
    }
    initContext = data.payload?.context ?? {};
    applyHostTheme(data.payload?.theme);
    deliverInit();
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
  return initContext ?? {};
}

/**
 * Calls [callback] once with GZAC's `init` context. In GZAC that waits for
 * `init`, however late; a page opened outside GZAC starts right away with
 * an empty context.
 */
export function onInit(callback: InitCallback): void {
  initCallback = callback;
  if (!embedded) {
    initContext = {};
    setTimeout(deliverInit, 0);
    return;
  }
  postToHost('ready');
  deliverInit();
  if (!initDelivered) setTimeout(showWaitingNotice, WAITING_NOTICE_DELAY_MS);
}

/**
 * Asks the GZAC host to navigate its router (plugin-SDK message
 * `navigate: { route }`), e.g. to another dossier:
 * `/cases/<caseDefinitionKey>/document/<documentId>`. GZAC only follows case
 * routes, and only during the user activation of a click, so call it
 * straight from a click handler; hosts without the handler ignore it.
 */
export function navigateHost(route: string): void {
  postToHost('navigate', { route });
}

/**
 * Calls this app's REST API through the GZAC host (plugin-SDK
 * `proxyRequest`, target `plugin`): GZAC attaches the user's downscoped
 * token and posts `{method, path, query, body}` to the app's `/data` route,
 * which verifies the user, checks access and answers with the handler's
 * status and body. Without an answer within 30 seconds (or outside GZAC) it
 * resolves with status 0 and a message, so a caller never waits forever.
 */
export function pluginData<T>(
  method: HttpMethod,
  path: string,
  options: { query?: Record<string, string>; body?: unknown } = {}
): Promise<PluginDataResult<T>> {
  if (!embedded) {
    return Promise.resolve({ status: 0, body: { detail: 'PDCA werkt alleen binnen GZAC' } as T });
  }
  const correlationId = newCorrelationId();
  return new Promise(resolve => {
    pendingRequests.set(correlationId, resolve as (result: PluginDataResult<unknown>) => void);
    setTimeout(() => {
      if (!pendingRequests.delete(correlationId)) return;
      resolve({ status: 0, body: { detail: 'Geen antwoord van GZAC' } as T });
    }, PLUGIN_DATA_TIMEOUT_MS);
    postToHost('proxyRequest', {
      correlationId,
      target: 'plugin',
      method,
      path,
      query: options.query && Object.keys(options.query).length > 0 ? options.query : undefined,
      body: options.body,
    });
  });
}

/**
 * Offers one of this app's `side-panel` bundles to GZAC's side panel (the
 * latest offer takes it over; re-offering the same key only shows it again).
 */
export function offerPanel(offer: PanelOffer): void {
  postToHost('offerPanel', offer);
}

/** Removes the side-panel content this app offered under `key`. */
export function withdrawPanel(key: string, bundleKey?: string): void {
  postToHost('withdrawPanel', { bundleKey, key });
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
    postToHost('submitTask', { correlationId, data });
  });
}

// ------------------------------------------------------------- page height

/**
 * The height the page's content needs: from the top of the rendered content
 * to the bottom of its lowest child, independent of the viewport-filling
 * minimum heights, so it can shrink as well as grow.
 */
function contentHeight(): number {
  const root = document.getElementById('root');
  const content = (root?.firstElementChild as HTMLElement | null) ?? root ?? document.body;
  const top = content.getBoundingClientRect().top;
  let bottom = top;
  for (const child of Array.from(content.children)) {
    bottom = Math.max(bottom, child.getBoundingClientRect().bottom);
  }
  const style = getComputedStyle(content);
  return Math.ceil(bottom - top + (parseFloat(style.paddingBottom) || 0) + (parseFloat(style.borderBottomWidth) || 0));
}

/** One observer for the whole page: reports the content height to the host when it changes. */
function observePageHeight(): void {
  if (!embedded || typeof ResizeObserver === 'undefined') return;
  let reported = 0;
  let scheduled = false;
  const report = () => {
    scheduled = false;
    const height = contentHeight();
    if (height > 0 && height !== reported) {
      reported = height;
      postToHost('resize', { height });
    }
  };
  const schedule = () => {
    if (scheduled) return;
    scheduled = true;
    requestAnimationFrame(report);
  };
  const resizes = new ResizeObserver(schedule);
  let observed: Element[] = [];
  // The observed elements follow the content's top-level children; any
  // other change in the page only asks for a new measurement.
  const observeContent = () => {
    const root = document.getElementById('root');
    const content = root?.firstElementChild;
    const targets = [root ?? document.body, ...(content ? Array.from(content.children) : [])];
    if (targets.length !== observed.length || targets.some((t, i) => t !== observed[i])) {
      resizes.disconnect();
      targets.forEach(target => resizes.observe(target));
      observed = targets;
    }
    schedule();
  };
  new MutationObserver(observeContent).observe(document.body, { childList: true, subtree: true, characterData: true });
  observeContent();
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', observePageHeight, { once: true });
} else {
  observePageHeight();
}
