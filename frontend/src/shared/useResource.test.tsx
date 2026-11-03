import { act, renderHook, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { eersteFout, useResource } from './useResource';

/** A promise the test settles itself. */
function uitgesteld<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
}

describe('useResource', () => {
  it('is loading until the first load succeeds', async () => {
    const laden = uitgesteld<string[]>();
    const { result } = renderHook(() => useResource(() => laden.promise, []));
    expect(result.current).toMatchObject({ data: undefined, error: null, loading: true });

    await act(async () => laden.resolve(['a']));
    expect(result.current).toMatchObject({ data: ['a'], error: null, loading: false });
  });

  it('keeps the failure in error instead of turning it into empty data', async () => {
    const fout = new Error('kapot');
    const { result } = renderHook(() => useResource(() => Promise.reject(fout), []));
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.data).toBeUndefined();
    expect(result.current.error).toBe(fout);
  });

  it('loads nothing without a load function', () => {
    const { result } = renderHook(() => useResource<string>(null, []));
    expect(result.current).toMatchObject({ data: undefined, error: null, loading: false });
  });

  it('reload resolves with the new data and keeps the old data while it runs', async () => {
    let n = 0;
    let volgende = uitgesteld<number>();
    const load = vi.fn(() => { n++; return n === 1 ? Promise.resolve(1) : volgende.promise; });
    const { result } = renderHook(() => useResource(load, []));
    await waitFor(() => expect(result.current.data).toBe(1));

    let herladen!: Promise<number | undefined>;
    act(() => { herladen = result.current.reload(); });
    expect(result.current).toMatchObject({ data: 1, loading: true });

    await act(async () => volgende.resolve(2));
    await expect(herladen).resolves.toBe(2);
    expect(result.current).toMatchObject({ data: 2, loading: false, error: null });

    volgende = uitgesteld<number>();
    act(() => { herladen = result.current.reload(); });
    const fout = new Error('weg');
    await act(async () => volgende.reject(fout));
    await expect(herladen).resolves.toBeUndefined();
    expect(result.current).toMatchObject({ data: 2, error: fout, loading: false });
  });

  it('starts over when the deps change and drops the result of the outdated load', async () => {
    const eerste = uitgesteld<string>();
    const tweede = uitgesteld<string>();
    const { result, rerender } = renderHook(({ id }) => useResource(() => (id === 1 ? eerste.promise : tweede.promise), [id]),
      { initialProps: { id: 1 } });

    rerender({ id: 2 });
    await act(async () => tweede.resolve('twee'));
    expect(result.current.data).toBe('twee');

    await act(async () => eerste.resolve('een'));
    expect(result.current.data).toBe('twee');
  });

  it('clears the data of the previous deps', async () => {
    const { result, rerender } = renderHook(({ id }) => useResource(() => Promise.resolve(`plan ${id}`), [id]),
      { initialProps: { id: 1 } });
    await waitFor(() => expect(result.current.data).toBe('plan 1'));

    rerender({ id: 2 });
    expect(result.current).toMatchObject({ data: undefined, loading: true });
    await waitFor(() => expect(result.current.data).toBe('plan 2'));
  });

  it('drops a result arriving after unmount', async () => {
    const laden = uitgesteld<string>();
    const fouten = vi.spyOn(console, 'error');
    const { result, unmount } = renderHook(() => useResource(() => laden.promise, []));
    const laatste = result.current;
    unmount();
    await act(async () => laden.resolve('te laat'));
    expect(result.current).toBe(laatste);
    expect(fouten).not.toHaveBeenCalled();
  });

  it('mutate replaces the data locally', async () => {
    const { result } = renderHook(() => useResource(() => Promise.resolve([1]), []));
    await waitFor(() => expect(result.current.data).toEqual([1]));
    act(() => result.current.mutate(data => [...(data ?? []), 2]));
    expect(result.current.data).toEqual([1, 2]);
  });
});

describe('eersteFout', () => {
  const bron = (error: unknown) => ({ data: undefined, error, reload: vi.fn(() => Promise.resolve(undefined)) });

  it('is null when nothing failed', () => {
    expect(eersteFout(bron(null), bron(null))).toBeNull();
  });

  it('gives the first failure and reloads every failed source', async () => {
    const goed = bron(null);
    const eerste = bron(new Error('een'));
    const tweede = bron(new Error('twee'));
    const fout = eersteFout(goed, eerste, tweede);
    expect(fout?.error).toBe(eerste.error);

    await fout?.reload();
    expect(eerste.reload).toHaveBeenCalledOnce();
    expect(tweede.reload).toHaveBeenCalledOnce();
    expect(goed.reload).not.toHaveBeenCalled();
  });
});
