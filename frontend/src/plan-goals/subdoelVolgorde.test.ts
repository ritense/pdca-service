import { describe, expect, it } from 'vitest';
import type { Doel, DoelDetails } from '../shared/types';
import { sorteerSubdoelen, STANDAARD_VOLGORDE } from './subdoelVolgorde';

const doel = (uuid: string, titel: string, startdatum: string): Doel => ({
  uuid, titel, startdatum, plannen: [], status: 'actief',
});

const SUBDOELEN = [
  doel('a', 'Taalcursus', '2026-03-01T09:00:00Z'),
  doel('b', 'cv maken', '2026-01-15T09:00:00Z'),
  doel('c', 'Werkervaring', ''),
  doel('d', 'Budgetcoaching', '2026-02-01T09:00:00Z'),
];

const EINDDATA: Record<string, string> = { a: '2026-12-01', d: '2026-09-01', b: '2027-02-01' };
const details = (uuid: string): DoelDetails | undefined =>
  EINDDATA[uuid] ? { doelUuid: uuid, planUuid: 'p', sortering: 0, streefEinddatum: EINDDATA[uuid] } as DoelDetails : undefined;

const volgorde = (key: Parameters<typeof sorteerSubdoelen>[1]) => sorteerSubdoelen(SUBDOELEN, key, details).map(d => d.uuid);

describe('sorteerSubdoelen', () => {
  it('starts with the planned start, oldest first, and leaves the input alone', () => {
    const resultaat = sorteerSubdoelen(SUBDOELEN, STANDAARD_VOLGORDE, details);
    expect(resultaat.map(d => d.uuid)).toEqual(['b', 'd', 'a', 'c']);
    expect(SUBDOELEN.map(d => d.uuid)).toEqual(['a', 'b', 'c', 'd']);
  });

  it('sorts on startdatum both ways, without a date last', () => {
    expect(volgorde('startdatum-op')).toEqual(['b', 'd', 'a', 'c']);
    expect(volgorde('startdatum-af')).toEqual(['a', 'd', 'b', 'c']);
  });

  it('sorts on the beoogde einddatum both ways, without a date last', () => {
    expect(volgorde('einddatum-op')).toEqual(['d', 'a', 'b', 'c']);
    expect(volgorde('einddatum-af')).toEqual(['b', 'a', 'd', 'c']);
  });

  it('sorts alphabetically ignoring case', () => {
    expect(volgorde('titel-op')).toEqual(['d', 'b', 'a', 'c']);
    expect(volgorde('titel-af')).toEqual(['c', 'a', 'b', 'd']);
  });

  it('keeps the order they were added in between equal keys', () => {
    const gelijk = [doel('x', 'Zelfde', '2026-01-01'), doel('y', 'Zelfde', '2026-01-01')];
    expect(sorteerSubdoelen(gelijk, 'titel-af', () => undefined).map(d => d.uuid)).toEqual(['x', 'y']);
    expect(sorteerSubdoelen(gelijk, 'startdatum-af', () => undefined).map(d => d.uuid)).toEqual(['x', 'y']);
  });
});
