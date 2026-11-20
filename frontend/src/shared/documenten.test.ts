import { afterEach, describe, expect, it, vi } from 'vitest';
import { base64NaarBytes, downloadBestand, isOpen, metAanvraag, verslagVan } from './documenten';
import type { DocumentAanvraag, DocumentStatus } from './types';

const aanvraag = (id: string, bronUuid: string, aangevraagdOp: string, status: DocumentStatus = 'GEREED'): DocumentAanvraag => ({
  id, planUuid: 'plan-1', soort: 'EVALUATIEVERSLAG', bronUuid, status, aangevraagdOp,
  bijgewerktOp: null, bestandsnaam: null, foutmelding: null,
});

describe('isOpen', () => {
  it.each<[DocumentStatus, boolean]>([
    ['AANGEVRAAGD', true], ['BEZIG', true], ['GEREED', false], ['MISLUKT', false],
  ])('%s is open: %s', (status, open) => {
    expect(isOpen(aanvraag('a', 'sessie-1', '2026-11-11T10:00:00', status))).toBe(open);
  });
});

describe('verslagVan', () => {
  it('finds the verslag of the evaluation session', () => {
    const lijst = [aanvraag('a', 'sessie-1', '2026-11-11T10:00:00'), aanvraag('b', 'sessie-2', '2026-11-11T11:00:00')];
    expect(verslagVan(lijst, 'sessie-2')?.id).toBe('b');
  });

  it('takes the latest aanvraag of a session', () => {
    const lijst = [
      aanvraag('oud', 'sessie-1', '2026-11-11T10:00:00', 'MISLUKT'),
      aanvraag('nieuw', 'sessie-1', '2026-11-11T10:05:00', 'BEZIG'),
      aanvraag('ouder', 'sessie-1', '2026-11-10T09:00:00'),
    ];
    expect(verslagVan(lijst, 'sessie-1')?.id).toBe('nieuw');
  });

  it('is null without an aanvraag for the session', () => {
    expect(verslagVan([aanvraag('a', 'sessie-1', '2026-11-11T10:00:00')], 'sessie-9')).toBeNull();
    expect(verslagVan([], 'sessie-1')).toBeNull();
  });
});

describe('metAanvraag', () => {
  it('replaces the aanvraag of the same bron and keeps the others', () => {
    const lijst = [aanvraag('a', 'sessie-1', '2026-11-11T10:00:00', 'MISLUKT'), aanvraag('b', 'sessie-2', '2026-11-11T10:00:00')];
    const nieuw = aanvraag('c', 'sessie-1', '2026-11-11T12:00:00', 'AANGEVRAAGD');
    expect(metAanvraag(lijst, nieuw).map(a => a.id)).toEqual(['b', 'c']);
    expect(lijst.map(a => a.id)).toEqual(['a', 'b']);
  });

  it('adds the first aanvraag of a bron', () => {
    expect(metAanvraag([], aanvraag('a', 'sessie-1', '2026-11-11T10:00:00')).map(a => a.id)).toEqual(['a']);
  });
});

describe('base64NaarBytes', () => {
  it('decodes base64 to its bytes', () => {
    expect(Array.from(base64NaarBytes('JVBERi0xLjc='))).toEqual(Array.from(new TextEncoder().encode('%PDF-1.7')));
  });

  it('keeps bytes above 127 intact', () => {
    expect(Array.from(base64NaarBytes('AH+A/w=='))).toEqual([0x00, 0x7f, 0x80, 0xff]);
  });

  it('ignores line breaks and a data URL prefix', () => {
    expect(Array.from(base64NaarBytes('data:application/pdf;base64,JVBE\nRi0x\r\nLjc='))).toEqual(
      Array.from(new TextEncoder().encode('%PDF-1.7')));
  });

  it('is empty for an empty string', () => {
    expect(base64NaarBytes('')).toHaveLength(0);
  });

  it('throws for something that is not base64', () => {
    expect(() => base64NaarBytes('geen base64!')).toThrow();
  });
});

describe('downloadBestand', () => {
  afterEach(() => vi.restoreAllMocks());

  it('clicks a blob link carrying the file name and the decoded bytes', async () => {
    let blob: Blob | null = null;
    URL.createObjectURL = vi.fn((b: Blob) => { blob = b; return 'blob:null/verslag'; });
    URL.revokeObjectURL = vi.fn();
    const klik = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
      expect(this.href).toBe('blob:null/verslag');
      expect(this.download).toBe('evaluatieverslag-2026-11-11.pdf');
    });

    downloadBestand(btoa('%PDF-1.7'), 'evaluatieverslag-2026-11-11.pdf', 'application/pdf');

    expect(klik).toHaveBeenCalledOnce();
    expect(blob!.type).toBe('application/pdf');
    const tekst = await new Promise<string>(resolve => {
      const reader = new FileReader();
      reader.onload = () => resolve(reader.result as string);
      reader.readAsText(blob!);
    });
    expect(tekst).toBe('%PDF-1.7');
    expect(document.querySelector('a[download]')).toBeNull();
  });
});
