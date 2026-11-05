import { beforeEach, describe, expect, it, vi } from 'vitest';
import { registers } from './api';
import { get, HttpError } from './http';

vi.mock('./http', async importOriginal => ({ ...(await importOriginal<typeof import('./http')>()), get: vi.fn() }));

const getMock = vi.mocked(get);

describe('registers', () => {
  beforeEach(() => {
    getMock.mockReset();
    getMock.mockResolvedValue({ naam: 'Binnenhof 1' });
  });

  it('looks up an id that is a plain path segment', async () => {
    await expect(registers.object('OBJ-1.a_b~c')).resolves.toEqual({ naam: 'Binnenhof 1' });
    expect(getMock).toHaveBeenCalledWith('/api/v1/registers/objecten/OBJ-1.a_b~c');
  });

  it.each(['obj 1', 'a/b', '..', '.', 'é', '%2e'])('answers an id the backend cannot route (%s) as not found', async id => {
    const fout = await registers.object(id).catch((e: unknown) => e);
    expect(fout).toBeInstanceOf(HttpError);
    expect((fout as HttpError).status).toBe(404);
    expect(getMock).not.toHaveBeenCalled();
  });
});
