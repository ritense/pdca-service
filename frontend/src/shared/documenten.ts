import type { DocumentAanvraag } from './types';

/** How often the documents of a plan are read again while one is still being made. */
export const DOCUMENT_POLL_MS = 3000;

/** Whether the document is still being made (AANGEVRAAGD or BEZIG). */
export const isOpen = (aanvraag: DocumentAanvraag): boolean =>
  aanvraag.status === 'AANGEVRAAGD' || aanvraag.status === 'BEZIG';

/**
 * The latest evaluatieverslag aanvraag of an evaluation session (its bron),
 * or null when there is none. The backend lists only the latest per soort
 * and bron; a list that holds more still yields the latest.
 */
export function verslagVan(aanvragen: readonly DocumentAanvraag[], sessieId: string): DocumentAanvraag | null {
  let laatste: DocumentAanvraag | null = null;
  for (const a of aanvragen) {
    if (a.soort !== 'EVALUATIEVERSLAG' || a.bronUuid !== sessieId) continue;
    if (!laatste || a.aangevraagdOp.localeCompare(laatste.aangevraagdOp) > 0) laatste = a;
  }
  return laatste;
}

/** The list with [nieuw] in place of the earlier aanvragen of the same soort and bron. */
export function metAanvraag(aanvragen: readonly DocumentAanvraag[], nieuw: DocumentAanvraag): DocumentAanvraag[] {
  return [...aanvragen.filter(a => a.soort !== nieuw.soort || a.bronUuid !== nieuw.bronUuid), nieuw];
}

/** The bytes of a base64 string (whitespace and a `data:` prefix are ignored); throws when it is not base64. */
export function base64NaarBytes(base64: string): Uint8Array<ArrayBuffer> {
  const tekst = atob(base64.replace(/^data:[^,]*,/, '').replace(/\s+/g, ''));
  const bytes = new Uint8Array(tekst.length);
  for (let i = 0; i < tekst.length; i++) bytes[i] = tekst.charCodeAt(i);
  return bytes;
}

/**
 * Offers a file the bundle already holds as a download: a blob link with the
 * file name, clicked at once. This needs `allow-downloads` in GZAC's iframe
 * sandbox; call it from a click, since a browser may refuse a download
 * nobody asked for.
 */
export function downloadBestand(base64: string, bestandsnaam: string, mediatype: string): void {
  const url = URL.createObjectURL(new Blob([base64NaarBytes(base64)], { type: mediatype }));
  const link = document.createElement('a');
  link.href = url;
  link.download = bestandsnaam;
  link.style.display = 'none';
  document.body.appendChild(link);
  link.click();
  link.remove();
  // Revoking right away can cancel the download in some browsers.
  setTimeout(() => URL.revokeObjectURL(url), 30_000);
}
