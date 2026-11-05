import { beforeEach, describe, expect, it, vi } from 'vitest';
import { pluginData } from './bridge';
import { foutmelding, get, HttpError, isHttpStatus, listAll, MAX_PAGES, request, send } from './http';

vi.mock('./bridge', () => ({ pluginData: vi.fn() }));

const answer = vi.mocked(pluginData);

/** Lets the next call through the bridge answer with [status] and [body]. */
function antwoord(status: number, body: unknown = null) {
  answer.mockResolvedValueOnce({ status, body });
}

/** The error a call rejects with when the bridge answers with [status] and [body]. */
async function foutVan(status: number, body: unknown = null): Promise<HttpError> {
  antwoord(status, body);
  try {
    await get('/iets');
  } catch (e) {
    if (e instanceof HttpError) return e;
    throw e;
  }
  throw new Error('the call did not fail');
}

beforeEach(() => {
  answer.mockReset();
});

describe('request', () => {
  it('resolves with the body of a 2xx answer', async () => {
    antwoord(200, { titel: 'Plan' });
    await expect(get('/plannen/1')).resolves.toEqual({ titel: 'Plan' });
  });

  it('resolves with undefined for 204', async () => {
    antwoord(204, { genegeerd: true });
    await expect(send('DELETE', '/plannen/1')).resolves.toBeUndefined();
  });

  it('passes method, path, query and body to the bridge', async () => {
    antwoord(200, {});
    await request('PATCH', '/plannen/1', { query: { a: 'b' }, body: { titel: 'x' } });
    expect(answer).toHaveBeenCalledWith('PATCH', '/plannen/1', { query: { a: 'b' }, body: { titel: 'x' } });
  });

  it('rejects anything outside 2xx with an HttpError carrying status, detail and body', async () => {
    const body = { detail: 'Plan bestaat niet' };
    const fout = await foutVan(404, body);
    expect(fout).toBeInstanceOf(HttpError);
    expect(fout.name).toBe('HttpError');
    expect(fout.status).toBe(404);
    expect(fout.detail).toBe('Plan bestaat niet');
    expect(fout.body).toBe(body);
    expect(fout.message).toBe('Plan bestaat niet');
  });
});

describe('error wording', () => {
  it('401: the session expired, whatever the backend says', async () => {
    expect((await foutVan(401, { detail: 'Unauthorized' })).message).toBe('Sessie verlopen — herlaad de pagina');
  });

  it('429: too many calls, whatever the backend says', async () => {
    const fout = await foutVan(429, { detail: 'Rate limit exceeded' });
    expect(fout.message).toBe('Te veel verzoeken — probeer het over een minuut opnieuw');
    expect(foutmelding(fout)).toBe('Te veel verzoeken — probeer het over een minuut opnieuw');
    expect(foutmelding(fout, 'dossier')).toBe('Te veel verzoeken — probeer het over een minuut opnieuw');
  });

  it('429 without a body has the same wording', async () => {
    expect((await foutVan(429)).message).toBe('Te veel verzoeken — probeer het over een minuut opnieuw');
  });

  it('403 without detail is worded for what was being loaded', async () => {
    const fout = await foutVan(403);
    expect(fout.message).toBe('Je hebt hiervoor geen toegang');
    expect(foutmelding(fout)).toBe('Je hebt hiervoor geen toegang');
    expect(foutmelding(fout, 'dossier')).toBe('Geen toegang tot dit dossier');
    expect(foutmelding(fout, 'beheer')).toBe('Alleen beheerders hebben toegang tot PDCA Beheer');
  });

  it('403 with the generic refusal is worded for the context too', async () => {
    const fout = await foutVan(403, { detail: 'Geen toegang' });
    expect(foutmelding(fout, 'dossier')).toBe('Geen toegang tot dit dossier');
  });

  it('403 with a specific detail shows that detail', async () => {
    const fout = await foutVan(403, { detail: 'Dit plan is afgesloten' });
    expect(foutmelding(fout, 'dossier')).toBe('Dit plan is afgesloten');
  });

  it("uses a register's invalidParams over detail", async () => {
    const fout = await foutVan(400, {
      detail: 'Invalid input.',
      invalidParams: [{ name: 'titel', reason: 'Dit veld is vereist.' }, { name: 'startdatum', reason: 'Ongeldige datum.' }],
    });
    expect(fout.message).toBe('titel: Dit veld is vereist., startdatum: Ongeldige datum.');
  });

  it('falls back to a message field', async () => {
    expect((await foutVan(500, { message: 'Interne fout' })).message).toBe('Interne fout');
  });

  it('ignores a detail that is not a string', async () => {
    const fout = await foutVan(500, { detail: { nested: true } });
    expect(fout.detail).toBe('');
    expect(fout.message).toBe('Er ging iets mis (HTTP 500)');
  });

  it('names the status when the body explains nothing', async () => {
    expect((await foutVan(502, 'Bad Gateway')).message).toBe('Er ging iets mis (HTTP 502)');
  });

  it('status 0 without explanation: no connection with GZAC', async () => {
    expect((await foutVan(0)).message).toBe('Geen verbinding met GZAC');
  });

  it("status 0 with the bridge's own explanation shows it", async () => {
    const fout = await foutVan(0, { detail: 'Geen antwoord van GZAC' });
    expect(fout.status).toBe(0);
    expect(fout.message).toBe('Geen antwoord van GZAC');
  });

  it('a rejected bridge call surfaces as is', async () => {
    answer.mockRejectedValueOnce(new Error('Geen veilige willekeurige waarden beschikbaar'));
    await expect(get('/iets')).rejects.toThrow('Geen veilige willekeurige waarden beschikbaar');
  });
});

describe('foutmelding', () => {
  it('uses the message of any other error', () => {
    expect(foutmelding(new Error('Kapot'))).toBe('Kapot');
  });

  it('stringifies anything else', () => {
    expect(foutmelding('tekst')).toBe('tekst');
    expect(foutmelding(42)).toBe('42');
  });
});

describe('isHttpStatus', () => {
  it('matches an HttpError with one of the statuses', async () => {
    const fout = await foutVan(409);
    expect(isHttpStatus(fout, 404, 409)).toBe(true);
    expect(isHttpStatus(fout, 404)).toBe(false);
  });

  it('never matches anything else', () => {
    expect(isHttpStatus(new Error('409'), 409)).toBe(false);
    expect(isHttpStatus({ status: 409 }, 409)).toBe(false);
  });
});

describe('listAll', () => {
  it('follows the pages until there is no next one', async () => {
    antwoord(200, { next: 'p2', results: [1, 2] });
    antwoord(200, { next: null, results: [3] });
    await expect(listAll<number>('/doelen', { plan: 'x' })).resolves.toEqual([1, 2, 3]);
    expect(answer).toHaveBeenNthCalledWith(1, 'GET', '/doelen', { query: { plan: 'x', page: '1' } });
    expect(answer).toHaveBeenNthCalledWith(2, 'GET', '/doelen', { query: { plan: 'x', page: '2' } });
  });

  it('reads exactly MAX_PAGES pages', async () => {
    for (let i = 1; i < MAX_PAGES; i++) antwoord(200, { next: 'meer', results: [i] });
    antwoord(200, { next: null, results: [MAX_PAGES] });
    await expect(listAll<number>('/doelen')).resolves.toHaveLength(MAX_PAGES);
  });

  it('rejects a listing longer than MAX_PAGES instead of returning part of it', async () => {
    answer.mockResolvedValue({ status: 200, body: { next: 'meer', results: [0] } });
    await expect(listAll<number>('/doelen')).rejects.toThrow(/te lang/);
    expect(answer).toHaveBeenCalledTimes(MAX_PAGES);
  });

  it('rejects when a page fails', async () => {
    antwoord(200, { next: 'p2', results: [1] });
    antwoord(429);
    await expect(listAll('/doelen')).rejects.toMatchObject({ status: 429 });
  });
});
