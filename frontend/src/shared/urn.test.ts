import { describe, expect, it } from 'vitest';
import { formatUrn, parseDossierUrn, parseSubject, parseUrn, producttypeCode, subjectUrn, UrnKind } from './urn';

const VOORBEELDEN: [UrnKind, string, string][] = [
  ['persoon', '999993653', 'urn:pdca:brp:persoon:999993653'],
  ['object', 'obj-1', 'urn:pdca:objecten:object:obj-1'],
  ['klant', '999993653', 'urn:pdca:openklant:klant:999993653'],
  ['persoonsprofiel', '999993653', 'urn:pdca:profielen:persoonsprofiel:999993653'],
  ['medewerker', 'jan-jansen', 'urn:pdca:medewerkers:medewerker:jan-jansen'],
  ['producttype', 'SCHULDHULP', 'urn:pdca:openproduct:producttype:SCHULDHULP'],
  ['dossier', 'instrument-verzoek:4b6f', 'urn:pdca:gzac:dossier:instrument-verzoek:4b6f'],
];

describe('formatUrn', () => {
  it.each(VOORBEELDEN)('formats a %s URN', (kind, id, urn) => {
    expect(formatUrn(kind, id)).toBe(urn);
  });
});

describe('parseUrn', () => {
  it.each(VOORBEELDEN)('parses a %s URN back', (kind, id, urn) => {
    expect(parseUrn(urn)).toEqual({ kind, id });
  });

  it('keeps colons in the id', () => {
    expect(parseUrn('urn:pdca:gzac:dossier:a:b:c')).toEqual({ kind: 'dossier', id: 'a:b:c' });
  });

  it.each([
    [null],
    [undefined],
    [''],
    ['urn:pdca:'],
    ['urn:pdca:brp'],
    ['urn:pdca:brp:persoon'],
    ['urn:pdca:brp:persoon:'],
    ['urn:pdca:brp:onbekend:1'],
    ['urn:pdca:objecten:persoon:1'],
    ['urn:other:brp:persoon:1'],
    ['https://openplan.example/api/v1/doelen/1'],
  ])('rejects %j', value => {
    expect(parseUrn(value)).toBeNull();
  });
});

describe('subjectUrn and parseSubject', () => {
  it('mints and reads a persoon subject', () => {
    const urn = subjectUrn('PERSON', '999993653');
    expect(urn).toBe('urn:pdca:brp:persoon:999993653');
    expect(parseSubject(urn)).toEqual({ kind: 'persoon', bsn: '999993653' });
  });

  it('mints and reads an object subject', () => {
    const urn = subjectUrn('OBJECT', 'obj-1');
    expect(urn).toBe('urn:pdca:objecten:object:obj-1');
    expect(parseSubject(urn)).toEqual({ kind: 'object', id: 'obj-1' });
  });

  it('has no subject for another kind or nothing', () => {
    expect(parseSubject('urn:pdca:openklant:klant:1')).toBeNull();
    expect(parseSubject(null)).toBeNull();
  });
});

describe('producttypeCode', () => {
  it('reads the code of a producttype URN', () => {
    expect(producttypeCode('urn:pdca:openproduct:producttype:SCHULDHULP')).toBe('SCHULDHULP');
  });

  it('is null for anything else', () => {
    expect(producttypeCode('urn:pdca:brp:persoon:1')).toBeNull();
    expect(producttypeCode('SCHULDHULP')).toBeNull();
    expect(producttypeCode(undefined)).toBeNull();
  });
});

describe('parseDossierUrn', () => {
  it('splits the id into case definition key and document id', () => {
    expect(parseDossierUrn('urn:pdca:gzac:dossier:instrument-verzoek:4b6f-11')).toEqual({
      caseDefinitionKey: 'instrument-verzoek', documentId: '4b6f-11',
    });
  });

  it('keeps further colons in the document id', () => {
    expect(parseDossierUrn('urn:pdca:gzac:dossier:key:a:b')).toEqual({ caseDefinitionKey: 'key', documentId: 'a:b' });
  });

  it.each([
    ['urn:pdca:gzac:dossier:zonder-document'],
    ['urn:pdca:gzac:dossier::4b6f'],
    ['urn:pdca:gzac:dossier:key:'],
    ['urn:pdca:openproduct:producttype:key:doc'],
    [null],
  ])('rejects %j', value => {
    expect(parseDossierUrn(value)).toBeNull();
  });
});
