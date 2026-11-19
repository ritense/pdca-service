/**
 * The URNs the app mints for cross-register references:
 * `urn:pdca:<component>:<resource>:<id>`, one kind per reference. The one
 * place in the frontend that formats and parses them; it mirrors the
 * backend's `PdcaUrn`. The formats are stored in the registers and never
 * change.
 *
 * A dossier URN's id is `<caseDefinitionKey>:<documentId>`: both parts of
 * the GZAC route to the dossier.
 */

const PREFIX = 'urn:pdca:';

const KINDS = {
  /** `plan.domeinregister` of a plan about an inwoner: the bsn. */
  persoon: ['brp', 'persoon'],
  /** `plan.domeinregister` of a plan about an object: the object id. */
  object: ['objecten', 'object'],
  /** `persoon.klant` in Open Plan: the bsn. */
  klant: ['openklant', 'klant'],
  /** `persoon.persoonsprofiel` in Open Plan: the bsn. */
  persoonsprofiel: ['profielen', 'persoonsprofiel'],
  /** `plan.medewerker`: the procesbegeleider's name slug. */
  medewerker: ['medewerkers', 'medewerker'],
  /** `instrument.product`: the producttype code in Open Product. */
  producttype: ['openproduct', 'producttype'],
  /** `instrument.zaak`: the instrument dossier, `<caseDefinitionKey>:<documentId>`. */
  dossier: ['gzac', 'dossier'],
} as const;

export type UrnKind = keyof typeof KINDS;

export interface PdcaUrn {
  kind: UrnKind;
  id: string;
}

export function formatUrn(kind: UrnKind, id: string): string {
  const [component, resource] = KINDS[kind];
  return `${PREFIX}${component}:${resource}:${id}`;
}

/** Parses a URN this app minted; null for anything else (or an empty id). */
export function parseUrn(value: string | null | undefined): PdcaUrn | null {
  if (!value || !value.startsWith(PREFIX)) return null;
  const rest = value.slice(PREFIX.length);
  const first = rest.indexOf(':');
  const second = first < 0 ? -1 : rest.indexOf(':', first + 1);
  if (second < 0) return null;
  const component = rest.slice(0, first);
  const resource = rest.slice(first + 1, second);
  const id = rest.slice(second + 1);
  const kind = (Object.keys(KINDS) as UrnKind[]).find(k => KINDS[k][0] === component && KINDS[k][1] === resource);
  return kind && id ? { kind, id } : null;
}

/** The subject URN of a plan (`plan.domeinregister`) for a create-plan subject. */
export const subjectUrn = (subjectType: 'PERSON' | 'OBJECT', id: string) =>
  formatUrn(subjectType === 'PERSON' ? 'persoon' : 'object', id);

/** The subject of a plan from its domeinregister: a persoon (bsn) or an object (id); null without one. */
export function parseSubject(domeinregister: string | null | undefined):
  { kind: 'persoon'; bsn: string } | { kind: 'object'; id: string } | null {
  const urn = parseUrn(domeinregister);
  if (urn?.kind === 'persoon') return { kind: 'persoon', bsn: urn.id };
  if (urn?.kind === 'object') return { kind: 'object', id: urn.id };
  return null;
}

/** The producttype code of an instrument's `product`; null for anything else. */
export function producttypeCode(product: string | null | undefined): string | null {
  const urn = parseUrn(product);
  return urn?.kind === 'producttype' ? urn.id : null;
}

/**
 * The parts of the GZAC route `/cases/<caseDefinitionKey>/document/<documentId>`
 * from a dossier URN (an instrument's `zaak`, written by the instrument
 * dossier that created it); null for anything else.
 */
export function parseDossierUrn(value: string | null | undefined): { caseDefinitionKey: string; documentId: string } | null {
  const urn = parseUrn(value);
  if (urn?.kind !== 'dossier') return null;
  const split = urn.id.indexOf(':');
  if (split <= 0 || split === urn.id.length - 1) return null;
  return { caseDefinitionKey: urn.id.slice(0, split), documentId: urn.id.slice(split + 1) };
}
