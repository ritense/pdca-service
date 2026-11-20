import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { pdca } from './api';
import type { DocumentAanvraag, DocumentStatus } from './types';
import { useDocumenten } from './useDocumenten';

vi.mock('./api', () => ({ pdca: { documenten: { listByPlan: vi.fn(), instellingen: vi.fn(), maak: vi.fn() } } }));

const lijstVan = vi.mocked(pdca.documenten.listByPlan);
const maak = vi.mocked(pdca.documenten.maak);
const instellingen = vi.mocked(pdca.documenten.instellingen);

const aanvraag = (status: DocumentStatus, id = 'doc-1', bronUuid = 'sessie-1'): DocumentAanvraag => ({
  id, planUuid: 'plan-1', soort: 'EVALUATIEVERSLAG', bronUuid, status, aangevraagdOp: '2026-11-11T10:00:00',
  bijgewerktOp: null, bestandsnaam: null, foutmelding: null,
});

async function verstrijk(ms: number) {
  await act(async () => { await vi.advanceTimersByTimeAsync(ms); });
}

/** Mounts the hook and lets the first load finish. */
async function gemount(planUuid: string | null = 'plan-1') {
  const hook = renderHook(({ uuid }) => useDocumenten(uuid), { initialProps: { uuid: planUuid } });
  await verstrijk(0);
  return hook;
}

beforeEach(() => {
  vi.useFakeTimers();
  instellingen.mockResolvedValue({ actief: true });
});

afterEach(() => {
  vi.useRealTimers();
});

describe('useDocumenten', () => {
  it('tells whether documents can be made', async () => {
    lijstVan.mockResolvedValue([]);
    instellingen.mockResolvedValue({ actief: true });
    const { result } = await gemount();
    expect(result.current.actief).toBe(true);
  });

  it('loads the documents of the plan once when none is being made', async () => {
    lijstVan.mockResolvedValue([aanvraag('GEREED')]);
    const { result } = await gemount();
    expect(lijstVan).toHaveBeenCalledWith('plan-1');
    expect(result.current.documenten.data).toEqual([aanvraag('GEREED')]);

    await verstrijk(30_000);
    expect(lijstVan).toHaveBeenCalledTimes(1);
  });

  it('loads nothing without a plan', async () => {
    await gemount(null);
    await verstrijk(30_000);
    expect(lijstVan).not.toHaveBeenCalled();
  });

  it('polls every 3 seconds while a document is being made, and stops once it is done', async () => {
    lijstVan.mockResolvedValue([aanvraag('AANGEVRAAGD')]);
    const { result } = await gemount();

    lijstVan.mockResolvedValue([aanvraag('BEZIG')]);
    await verstrijk(3000);
    expect(lijstVan).toHaveBeenCalledTimes(2);
    await verstrijk(3000);
    expect(lijstVan).toHaveBeenCalledTimes(3);

    lijstVan.mockResolvedValue([aanvraag('GEREED')]);
    await verstrijk(3000);
    expect(lijstVan).toHaveBeenCalledTimes(4);
    expect(result.current.documenten.data?.[0].status).toBe('GEREED');

    await verstrijk(30_000);
    expect(lijstVan).toHaveBeenCalledTimes(4);
  });

  it('keeps polling when a read fails', async () => {
    lijstVan.mockResolvedValue([aanvraag('BEZIG')]);
    const { result } = await gemount();

    lijstVan.mockRejectedValue(new Error('Geen verbinding'));
    await verstrijk(6000);
    expect(lijstVan).toHaveBeenCalledTimes(3);
    expect(result.current.documenten.error).toBeInstanceOf(Error);
    expect(result.current.documenten.data).toEqual([aanvraag('BEZIG')]);
  });

  it('makes a verslag and follows it until it is ready', async () => {
    lijstVan.mockResolvedValue([aanvraag('MISLUKT', 'doc-1'), aanvraag('GEREED', 'doc-2', 'sessie-2')]);
    maak.mockResolvedValue(aanvraag('AANGEVRAAGD', 'doc-3'));
    const { result } = await gemount();

    await act(async () => { await result.current.maakVerslag('sessie-1'); });
    expect(maak).toHaveBeenCalledWith({ soort: 'EVALUATIEVERSLAG', bronUuid: 'sessie-1' });
    expect(result.current.documenten.data?.map(a => a.id)).toEqual(['doc-2', 'doc-3']);

    lijstVan.mockResolvedValue([aanvraag('GEREED', 'doc-3'), aanvraag('GEREED', 'doc-2', 'sessie-2')]);
    await verstrijk(3000);
    expect(lijstVan).toHaveBeenCalledTimes(2);
    await verstrijk(30_000);
    expect(lijstVan).toHaveBeenCalledTimes(2);
  });

  it('stops polling on unmount', async () => {
    lijstVan.mockResolvedValue([aanvraag('AANGEVRAAGD')]);
    const { unmount } = await gemount();
    unmount();
    await verstrijk(30_000);
    expect(lijstVan).toHaveBeenCalledTimes(1);
    expect(vi.getTimerCount()).toBe(0);
  });
});
