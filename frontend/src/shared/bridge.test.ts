import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

type Bridge = typeof import('./bridge');

const GZAC = 'https://gzac.example';
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

/** The frame's parent: GZAC, receiving what the bundle posts. */
let host: { postMessage: ReturnType<typeof vi.fn> };
/** The message listeners the bridge module registered, removed again after each test. */
let listeners: EventListenerOrEventListenerObject[];

/**
 * A fresh bridge module, loaded as a page framed by GZAC (or opened on its
 * own): its state lives at module level and is decided when it loads.
 */
async function laadBridge({ framed = true } = {}): Promise<Bridge> {
  if (framed) Object.defineProperty(window, 'parent', { value: host, configurable: true });
  const add = vi.spyOn(window, 'addEventListener');
  vi.resetModules();
  const bridge = await import('./bridge');
  listeners.push(...add.mock.calls.filter(([type]) => type === 'message').map(([, listener]) => listener));
  add.mockRestore();
  return bridge;
}

/** A message from [source] (by default the parent window) as the browser delivers it. */
function bericht(data: unknown, { origin = GZAC, source = window.parent as unknown }: { origin?: string; source?: unknown } = {}) {
  window.dispatchEvent(new MessageEvent('message', { data, origin, source: source as Window }));
}

const vanGzac = (event: string, payload?: unknown) => ({ source: 'valtimo-host', event, payload });

/** What the bundle posted to the host, as [event, payload, targetOrigin]. */
function gepost(): [string, Record<string, unknown>, string][] {
  return host.postMessage.mock.calls.map(([message, origin]) => [message.event, message.payload, origin]);
}

function laatsteVerzoek(): Record<string, unknown> {
  const verzoeken = gepost().filter(([event]) => event === 'proxyRequest');
  return verzoeken[verzoeken.length - 1][1];
}

const zelf = window;

beforeEach(() => {
  host = { postMessage: vi.fn() };
  listeners = [];
});

afterEach(() => {
  listeners.forEach(listener => window.removeEventListener('message', listener));
  Object.defineProperty(window, 'parent', { value: zelf, configurable: true });
  document.body.innerHTML = '';
  vi.useRealTimers();
});

describe('incoming messages', () => {
  it('ignores messages that do not come from the parent window', async () => {
    const bridge = await laadBridge();
    const callback = vi.fn();
    bridge.onInit(callback);

    bericht(vanGzac('init', { context: { documentId: 'vals' } }), { source: {} });
    bericht(vanGzac('init', { context: { documentId: 'vals' } }), { source: window });
    expect(callback).not.toHaveBeenCalled();

    bericht(vanGzac('init', { context: { documentId: 'echt' } }));
    expect(callback).toHaveBeenCalledWith({ documentId: 'echt' });
  });

  it('ignores messages of another protocol', async () => {
    const bridge = await laadBridge();
    const callback = vi.fn();
    bridge.onInit(callback);
    bericht({ source: 'iets-anders', event: 'init', payload: { context: {} } });
    bericht('init');
    expect(callback).not.toHaveBeenCalled();
  });

  it('after init only accepts messages from the origin init came from', async () => {
    const bridge = await laadBridge();
    bridge.onInit(() => undefined);
    bericht(vanGzac('init', { context: {} }));

    bericht(vanGzac('themeChanged', { theme: 'g100' }), { origin: 'https://elders.example' });
    expect(bridge.getHostTheme()).toBe('white');
    bericht(vanGzac('themeChanged', { theme: 'g100' }));
    expect(bridge.getHostTheme()).toBe('g100');
  });

  it('follows the theme of init and later switches, ignoring unknown themes', async () => {
    const bridge = await laadBridge();
    const listener = vi.fn();
    bridge.onHostThemeChanged(listener);
    bridge.onInit(() => undefined);

    bericht(vanGzac('init', { context: {}, theme: 'g90' }));
    bericht(vanGzac('themeChanged', { theme: 'paars' }));
    bericht(vanGzac('themeChanged', { theme: 'g10' }));
    expect(listener.mock.calls).toEqual([['g90'], ['g10']]);
    expect(bridge.getHostTheme()).toBe('g10');
  });
});

describe('onInit', () => {
  it('announces itself to the host and waits for init', async () => {
    const bridge = await laadBridge();
    const callback = vi.fn();
    bridge.onInit(callback);
    expect(gepost()).toEqual([['ready', undefined, '*']]);
    expect(callback).not.toHaveBeenCalled();
  });

  it('a late init is authoritative: shown as waiting meanwhile, never started without context', async () => {
    vi.useFakeTimers();
    const bridge = await laadBridge();
    const callback = vi.fn();
    bridge.onInit(callback);

    vi.advanceTimersByTime(1500);
    expect(document.body.textContent).toContain('Wachten op GZAC…');
    vi.advanceTimersByTime(60_000);
    expect(callback).not.toHaveBeenCalled();

    bericht(vanGzac('init', { context: { documentId: 'doc-1', caseDefinitionKey: 'plan' }, theme: 'g100' }));
    expect(callback).toHaveBeenCalledOnce();
    expect(callback).toHaveBeenCalledWith({ documentId: 'doc-1', caseDefinitionKey: 'plan' });
    expect(bridge.getInitContext()).toEqual({ documentId: 'doc-1', caseDefinitionKey: 'plan' });
    expect(bridge.getHostTheme()).toBe('g100');
    expect(document.getElementById('pdca-wachten-op-gzac')).toBeNull();

    bericht(vanGzac('init', { context: { documentId: 'doc-2' } }));
    expect(callback).toHaveBeenCalledOnce();
  });

  it('shows no waiting notice when init arrives in time', async () => {
    vi.useFakeTimers();
    const bridge = await laadBridge();
    bridge.onInit(() => undefined);
    bericht(vanGzac('init', { context: {} }));
    vi.advanceTimersByTime(5000);
    expect(document.getElementById('pdca-wachten-op-gzac')).toBeNull();
  });

  it('delivers an init that arrived before onInit', async () => {
    const bridge = await laadBridge();
    bericht(vanGzac('init', { context: { documentId: 'vroeg' } }));
    const callback = vi.fn();
    bridge.onInit(callback);
    expect(callback).toHaveBeenCalledWith({ documentId: 'vroeg' });
  });

  it('pins the origin of init for everything posted afterwards', async () => {
    const bridge = await laadBridge();
    bridge.onInit(() => undefined);
    bericht(vanGzac('init', { context: {} }));
    bridge.navigateHost('/cases/plan/document/1');
    expect(gepost().at(-1)).toEqual(['navigate', { route: '/cases/plan/document/1' }, GZAC]);
  });

  it('starts right away with an empty context outside GZAC', async () => {
    vi.useFakeTimers();
    const bridge = await laadBridge({ framed: false });
    const callback = vi.fn();
    bridge.onInit(callback);
    vi.advanceTimersByTime(0);
    expect(callback).toHaveBeenCalledWith({});
  });
});

describe('pluginData', () => {
  it('posts a proxy request with a random correlation id', async () => {
    const bridge = await laadBridge();
    const uuid = vi.spyOn(crypto, 'randomUUID');
    void bridge.pluginData('POST', '/plannen', { body: { titel: 'x' } });
    void bridge.pluginData('GET', '/plannen');

    expect(uuid).toHaveBeenCalledTimes(2);
    const [eerste, tweede] = gepost().filter(([event]) => event === 'proxyRequest').map(([, payload]) => payload);
    expect(eerste).toEqual({
      correlationId: expect.stringMatching(UUID), target: 'plugin', method: 'POST', path: '/plannen',
      query: undefined, body: { titel: 'x' },
    });
    expect(tweede.correlationId).toMatch(UUID);
    expect(tweede.correlationId).not.toBe(eerste.correlationId);
  });

  it('falls back to random bytes when randomUUID is not available', async () => {
    const bridge = await laadBridge();
    vi.spyOn(crypto, 'randomUUID').mockImplementation(() => { throw new Error('not a secure context'); });
    void bridge.pluginData('GET', '/a');
    void bridge.pluginData('GET', '/b');
    const [eerste, tweede] = gepost().filter(([event]) => event === 'proxyRequest').map(([, payload]) => payload.correlationId);
    expect(eerste).toMatch(/^[0-9a-f]{32}$/);
    expect(tweede).toMatch(/^[0-9a-f]{32}$/);
    expect(eerste).not.toBe(tweede);
  });

  it('sends the query only when it has keys', async () => {
    const bridge = await laadBridge();
    void bridge.pluginData('GET', '/a', { query: {} });
    expect(laatsteVerzoek().query).toBeUndefined();
    void bridge.pluginData('GET', '/a', { query: { plan: '1' } });
    expect(laatsteVerzoek().query).toEqual({ plan: '1' });
  });

  it('resolves each request with the response carrying its correlation id', async () => {
    const bridge = await laadBridge();
    const eerste = bridge.pluginData('GET', '/een');
    const eersteId = laatsteVerzoek().correlationId;
    const tweede = bridge.pluginData('GET', '/twee');
    const tweedeId = laatsteVerzoek().correlationId;
    const eersteKlaar = vi.fn();
    void eerste.then(eersteKlaar);

    bericht(vanGzac('proxyResponse', { correlationId: 'onbekend', status: 500, body: null }));
    bericht(vanGzac('proxyResponse', { correlationId: tweedeId, status: 201, body: { id: 2 } }));
    await expect(tweede).resolves.toEqual({ status: 201, body: { id: 2 } });
    expect(eersteKlaar).not.toHaveBeenCalled();

    bericht(vanGzac('proxyResponse', { correlationId: eersteId, status: 200, body: { id: 1 } }));
    await expect(eerste).resolves.toEqual({ status: 200, body: { id: 1 } });
  });

  it('ignores a response from outside the parent window', async () => {
    const bridge = await laadBridge();
    const antwoord = vi.fn();
    void bridge.pluginData('GET', '/een').then(antwoord);
    const id = laatsteVerzoek().correlationId;
    bericht(vanGzac('proxyResponse', { correlationId: id, status: 200, body: {} }), { source: {} });
    await Promise.resolve();
    expect(antwoord).not.toHaveBeenCalled();
  });

  it('defaults a response without status or body to status 0 and null', async () => {
    const bridge = await laadBridge();
    const verzoek = bridge.pluginData('GET', '/een');
    bericht(vanGzac('proxyResponse', { correlationId: laatsteVerzoek().correlationId }));
    await expect(verzoek).resolves.toEqual({ status: 0, body: null });
  });

  it('gives up after 30 seconds without an answer and ignores a later one', async () => {
    vi.useFakeTimers();
    const bridge = await laadBridge();
    const verzoek = bridge.pluginData('GET', '/traag');
    const id = laatsteVerzoek().correlationId;
    const klaar = vi.fn();
    void verzoek.then(klaar);

    await vi.advanceTimersByTimeAsync(29_999);
    expect(klaar).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(1);
    expect(klaar).toHaveBeenCalledWith({ status: 0, body: { detail: 'Geen antwoord van GZAC' } });

    bericht(vanGzac('proxyResponse', { correlationId: id, status: 200, body: {} }));
    await vi.advanceTimersByTimeAsync(0);
    expect(klaar).toHaveBeenCalledOnce();
  });

  it('does not time out a request that was answered', async () => {
    vi.useFakeTimers();
    const bridge = await laadBridge();
    const verzoek = bridge.pluginData('GET', '/snel');
    bericht(vanGzac('proxyResponse', { correlationId: laatsteVerzoek().correlationId, status: 200, body: { ok: true } }));
    await vi.advanceTimersByTimeAsync(30_000);
    await expect(verzoek).resolves.toEqual({ status: 200, body: { ok: true } });
  });

  it('answers status 0 right away outside GZAC', async () => {
    const bridge = await laadBridge({ framed: false });
    await expect(bridge.pluginData('GET', '/a')).resolves.toEqual({ status: 0, body: { detail: 'PDCA werkt alleen binnen GZAC' } });
    expect(host.postMessage).not.toHaveBeenCalled();
  });
});

describe('submitTask', () => {
  it('resolves with the submit result of its own correlation id', async () => {
    const bridge = await laadBridge();
    const resultaat = bridge.submitTask({ akkoord: true });
    const [event, payload] = gepost().at(-1)!;
    expect(event).toBe('submitTask');
    expect(payload).toEqual({ correlationId: expect.stringMatching(UUID), data: { akkoord: true } });

    bericht(vanGzac('submitResult', { correlationId: 'ander', ok: true }));
    bericht(vanGzac('submitResult', { correlationId: payload.correlationId, ok: false, errors: ['Fout'], fieldErrors: { a: 'b' } }));
    await expect(resultaat).resolves.toEqual({ ok: false, errors: ['Fout'], fieldErrors: { a: 'b' } });
  });

  it('only treats ok: true as success', async () => {
    const bridge = await laadBridge();
    const resultaat = bridge.submitTask({});
    bericht(vanGzac('submitResult', { correlationId: gepost().at(-1)![1].correlationId, ok: 'ja', errors: 'geen lijst' }));
    await expect(resultaat).resolves.toEqual({ ok: false, errors: undefined, fieldErrors: undefined });
  });
});

describe('side panel', () => {
  it('offers and withdraws a panel through the host', async () => {
    const bridge = await laadBridge();
    bridge.offerPanel({ key: 'evaluatie', title: 'Evaluatie' });
    bridge.withdrawPanel('evaluatie', 'evaluation-panel');
    expect(gepost().slice(-2)).toEqual([
      ['offerPanel', { key: 'evaluatie', title: 'Evaluatie' }, '*'],
      ['withdrawPanel', { bundleKey: 'evaluation-panel', key: 'evaluatie' }, '*'],
    ]);
  });
});
