import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { OpenVerzoek, pdca } from './api';
import { useOpenVerzoeken } from './useOpenVerzoeken';

vi.mock('./api', () => ({ pdca: { instrumenten: { verzoeken: vi.fn() } } }));

const verzoekenVan = vi.mocked(pdca.instrumenten.verzoeken);

const verzoek = (doelUuid: string): OpenVerzoek => ({
  objectUrl: `https://objecten.example/api/v2/objects/${doelUuid}`, planUuid: 'plan-1', doelUuid,
  productUrn: 'urn:pdca:openproduct:producttype:SCHULDHULP', productTitel: 'Schuldhulp', aangevraagdOp: null, minutenOpen: 0,
});

const isDoel1 = (v: OpenVerzoek) => v.doelUuid === 'doel-1';

/** The moments (ms after following) at which the plan is reloaded while the verzoek stays open. */
const SCHEMA = [2000, 6000, 14000, 30000, 46000];

let herlaadPlan: ReturnType<typeof vi.fn<() => Promise<unknown>>>;

async function verstrijk(ms: number) {
  await act(async () => { await vi.advanceTimersByTimeAsync(ms); });
}

/** Mounts the hook and lets the first load of the verzoeken finish. */
async function gemount(planUuid = 'plan-1') {
  const hook = renderHook(({ uuid }) => useOpenVerzoeken(uuid, herlaadPlan), { initialProps: { uuid: planUuid } });
  await verstrijk(0);
  return hook;
}

/** The moments at which the plan was reloaded, relative to [begin]. */
const herlaadMomenten = (begin: number, momenten: number[]) => momenten.map(t => t - begin);

beforeEach(() => {
  vi.useFakeTimers();
  herlaadPlan = vi.fn(() => Promise.resolve(undefined));
  verzoekenVan.mockResolvedValue([verzoek('doel-1')]);
});

afterEach(() => {
  vi.useRealTimers();
});

describe('useOpenVerzoeken', () => {
  it('loads the open verzoeken of the plan', async () => {
    const { result } = await gemount();
    expect(verzoekenVan).toHaveBeenCalledWith('plan-1');
    expect(result.current.verzoeken.data).toEqual([verzoek('doel-1')]);
  });

  it('follows a verzoek with doubling pauses up to 16 seconds and gives up after a minute', async () => {
    const { result } = await gemount();
    const momenten: number[] = [];
    herlaadPlan.mockImplementation(() => { momenten.push(Date.now()); return Promise.resolve(undefined); });
    const begin = Date.now();

    act(() => result.current.volg(isDoel1));
    await verstrijk(120_000);

    expect(herlaadMomenten(begin, momenten)).toEqual(SCHEMA);
    expect(verzoekenVan).toHaveBeenCalledTimes(1 + SCHEMA.length);
  });

  it('stops as soon as the verzoek is no longer open', async () => {
    const { result } = await gemount();
    act(() => result.current.volg(isDoel1));

    await verstrijk(2000);
    expect(herlaadPlan).toHaveBeenCalledTimes(1);

    verzoekenVan.mockResolvedValue([verzoek('doel-2')]);
    await verstrijk(4000);
    expect(herlaadPlan).toHaveBeenCalledTimes(2);
    expect(result.current.verzoeken.data).toEqual([verzoek('doel-2')]);

    await verstrijk(120_000);
    expect(herlaadPlan).toHaveBeenCalledTimes(2);
  });

  it('keeps trying when the verzoeken cannot be read', async () => {
    const { result } = await gemount();
    verzoekenVan.mockRejectedValue(new Error('Objecten API onbereikbaar'));
    act(() => result.current.volg(isDoel1));

    await verstrijk(14_000);
    expect(herlaadPlan).toHaveBeenCalledTimes(3);
    expect(result.current.verzoeken.error).toBeInstanceOf(Error);
  });

  it('stops polling on unmount', async () => {
    const { result, unmount } = await gemount();
    act(() => result.current.volg(isDoel1));
    await verstrijk(2000);
    expect(herlaadPlan).toHaveBeenCalledTimes(1);

    unmount();
    await verstrijk(120_000);
    expect(herlaadPlan).toHaveBeenCalledTimes(1);
    expect(vi.getTimerCount()).toBe(0);
  });

  it('drops a tick whose reload was still running at unmount', async () => {
    const { result, unmount } = await gemount();
    let herladen!: () => void;
    herlaadPlan.mockImplementationOnce(() => new Promise(resolve => { herladen = () => resolve(undefined); }));
    act(() => result.current.volg(isDoel1));
    await verstrijk(2000);

    unmount();
    await act(async () => herladen());
    await verstrijk(120_000);
    expect(herlaadPlan).toHaveBeenCalledTimes(1);
    expect(vi.getTimerCount()).toBe(0);
  });

  it('stops polling when the plan changes', async () => {
    const { result, rerender } = await gemount();
    act(() => result.current.volg(isDoel1));
    rerender({ uuid: 'plan-2' });
    await verstrijk(120_000);
    expect(herlaadPlan).not.toHaveBeenCalled();
    expect(verzoekenVan).toHaveBeenLastCalledWith('plan-2');
  });

  it('following a new verzoek restarts the schedule', async () => {
    const { result } = await gemount();
    const momenten: number[] = [];
    herlaadPlan.mockImplementation(() => { momenten.push(Date.now()); return Promise.resolve(undefined); });

    act(() => result.current.volg(isDoel1));
    await verstrijk(3000);
    const begin = Date.now();
    act(() => result.current.volg(isDoel1));
    await verstrijk(120_000);

    expect(momenten[0]).toBe(begin - 1000);
    expect(herlaadMomenten(begin, momenten.slice(1))).toEqual(SCHEMA);
  });
});
