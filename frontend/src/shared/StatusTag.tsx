import React from 'react';
import { Tag, type TagProps } from '@carbon/react';
import type { TagKleur } from './labels';

/** The colours Carbon's Tag takes as its `type`. */
type CarbonTagType = NonNullable<TagProps<'span'>['type']>;

/**
 * Colours GZAC adds to Carbon's palette (carbon.scss, $extendedTagColors).
 * Carbon's `type` does not know them, so the tag takes a neutral base and the
 * class GZAC registers for them; gzac-theme.css carries their colours, since
 * GZAC's own stylesheet does not reach the iframe.
 */
const UITGEBREID: Partial<Record<TagKleur, CarbonTagType>> = { orange: 'gray' };

/**
 * A status as a Carbon tag. One place decides how a [TagKleur] becomes a tag,
 * so every tab shows the same status in the same colour — and so does the bar
 * beside it on the Doelen tab (doelBalkKleur).
 */
export function StatusTag({ kleur, size = 'sm', children }: {
  kleur: TagKleur;
  size?: TagProps<'span'>['size'];
  children: React.ReactNode;
}) {
  const basis = UITGEBREID[kleur];
  return basis
    ? <Tag size={size} type={basis} className={`cds--tag--${kleur}`}>{children}</Tag>
    : <Tag size={size} type={kleur as CarbonTagType}>{children}</Tag>;
}
