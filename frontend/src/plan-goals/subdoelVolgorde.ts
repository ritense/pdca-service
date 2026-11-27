/**
 * The order the Doelen tab shows the subdoelen in: on the subdoel's
 * startdatum (Open Plan; oldest first unless chosen otherwise), its beoogde
 * einddatum (overlay) or its title. A subdoel without the date sorts last in
 * both directions, and ties keep the order they were added in (the overlay
 * sortering). The choice is the viewer's own and is not stored: the
 * sandboxed tab has no storage of its own.
 */

import type { Doel, DoelDetails } from '../shared/api';

export type SubdoelVolgorde =
  | 'startdatum-op' | 'startdatum-af'
  | 'einddatum-op' | 'einddatum-af'
  | 'titel-op' | 'titel-af';

export const SUBDOEL_VOLGORDES: { key: SubdoelVolgorde; label: string }[] = [
  { key: 'startdatum-op', label: 'Startdatum (vroegste eerst)' },
  { key: 'startdatum-af', label: 'Startdatum (laatste eerst)' },
  { key: 'einddatum-op', label: 'Beoogde einddatum (vroegste eerst)' },
  { key: 'einddatum-af', label: 'Beoogde einddatum (laatste eerst)' },
  { key: 'titel-op', label: 'Alfabetisch (A–Z)' },
  { key: 'titel-af', label: 'Alfabetisch (Z–A)' },
];

/** A date as a number to compare, or null when there is none (or it cannot be read). */
function tijd(datum: string | null | undefined): number | null {
  if (!datum) return null;
  const t = Date.parse(datum);
  return Number.isNaN(t) ? null : t;
}

/** Compares two optional values; a missing one goes last whatever the direction. */
function vergelijk<T>(a: T | null, b: T | null, richting: 1 | -1, cmp: (x: T, y: T) => number): number {
  if (a == null && b == null) return 0;
  if (a == null) return 1;
  if (b == null) return -1;
  return richting * cmp(a, b);
}

/** The order a tab opens with: the planned start, oldest first. */
export const STANDAARD_VOLGORDE: SubdoelVolgorde = 'startdatum-op';

/**
 * [subdoelen] (in the order they were added in) in the chosen order; a new
 * array, the input is left as it is.
 */
export function sorteerSubdoelen(
  subdoelen: Doel[],
  volgorde: SubdoelVolgorde,
  detailsVan: (uuid: string) => DoelDetails | undefined,
): Doel[] {
  const [veld, kant] = volgorde.split('-') as ['startdatum' | 'einddatum' | 'titel', 'op' | 'af'];
  const richting = kant === 'op' ? 1 : -1;
  const getal = (x: number, y: number) => x - y;
  const tekst = (x: string, y: string) => x.localeCompare(y, 'nl', { sensitivity: 'base', numeric: true });
  const sleutel = (d: Doel) => veld === 'startdatum' ? tijd(d.startdatum)
    : veld === 'einddatum' ? tijd(detailsVan(d.uuid)?.streefEinddatum)
    : d.titel || null;
  return subdoelen
    .map((doel, index) => ({ doel, index, sleutel: sleutel(doel) }))
    .sort((a, b) => {
      const verschil = veld === 'titel'
        ? vergelijk(a.sleutel as string | null, b.sleutel as string | null, richting, tekst)
        : vergelijk(a.sleutel as number | null, b.sleutel as number | null, richting, getal);
      return verschil !== 0 ? verschil : a.index - b.index;
    })
    .map(r => r.doel);
}
