export interface GzacContext {
  documentId?: string;
  caseDefinitionKey?: string;
  caseDefinitionVersionTag?: string;
  pluginConfigurationId?: string;
}

type InitCallback = (context: GzacContext) => void;

let initCallback: InitCallback | null = null;
let initialized = false;

window.addEventListener('message', (event) => {
  const data = event.data;
  if (data?.source === 'valtimo-host' && data?.event === 'init') {
    const context: GzacContext = data.payload?.context || {};
    if (!initialized && initCallback) {
      initialized = true;
      initCallback(context);
    }
  }
});

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
