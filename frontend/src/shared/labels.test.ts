import { describe, expect, it } from 'vitest';
import type { VoortgangStatusOptie } from './types';
import {
  actieAfgesloten, actieStatus, GERESERVEERDE_STATUSNAMEN, heeftOpenTaak, ingeplandStatus, instrumentenTelling, voortgangStatus,
  wijzigingHeeftReden,
  wijzigingOmschrijving,
} from './labels';

describe('actie status', () => {
  it('names a task ended as vervallen', () => {
    expect(actieStatus('CANCELLED').label).toBe('Vervallen');
  });

  it('counts completed, rejected and vervallen acties as ended', () => {
    expect(['COMPLETED', 'REJECTED', 'CANCELLED'].map(status => actieAfgesloten({ status }))).toEqual([true, true, true]);
    expect(['PLANNED', 'IN_PROGRESS', 'PENDING_REVIEW'].map(status => actieAfgesloten({ status }))).toEqual([false, false, false]);
  });

  it('has an open task only while the task has not ended the actie', () => {
    expect(heeftOpenTaak({ status: 'PLANNED', taakProcessInstanceId: 'p1' })).toBe(true);
    expect(heeftOpenTaak({ status: 'COMPLETED', taakProcessInstanceId: 'p1' })).toBe(false);
    expect(heeftOpenTaak({ status: 'CANCELLED', taakProcessInstanceId: 'p1' })).toBe(false);
    expect(heeftOpenTaak({ status: 'PLANNED' })).toBe(false);
  });
});

describe('gepland instrument', () => {
  it('is Gepland, marked concept while nothing can be requested yet', () => {
    expect(ingeplandStatus(true)).toEqual({ label: 'Gepland (concept)', tag: 'purple' });
    expect(ingeplandStatus(false)).toEqual({ label: 'Gepland', tag: 'purple' });
  });
});

describe('instrumenten telling', () => {
  it('counts only the statuses that occur, running ones first', () => {
    expect(instrumentenTelling(['Gepland'])).toBe('1 gepland');
    expect(instrumentenTelling(['Gepland', 'Gestart', 'Gepland', 'Afgerond', 'Aangevraagd']))
      .toBe('1 gestart, 1 aangevraagd, 2 gepland, 1 afgerond');
    expect(instrumentenTelling([])).toBe('0');
  });
});

describe('voortgangsstatus', () => {
  const opties: VoortgangStatusOptie[] = [
    { code: 'STARTEN', naam: 'Starten', betekenis: 'NEUTRAAL' },
    { code: 'BEHAALD', naam: 'Behaald', betekenis: 'GAAT_GOED' },
  ];

  it('colours a neutral status apart from good and bad', () => {
    expect(voortgangStatus('STARTEN', opties)).toEqual({ label: 'Starten', tag: 'teal' });
    expect(voortgangStatus('BEHAALD', opties)).toEqual({ label: 'Behaald', tag: 'green' });
  });

  it('shows a status that left the list by its code, in gray', () => {
    expect(voortgangStatus('OP_KOERS', opties)).toEqual({ label: 'Op koers', tag: 'gray' });
  });

  it('allows Behaald as a voortgangsstatus but not Niet behaald', () => {
    expect(GERESERVEERDE_STATUSNAMEN).not.toContain('behaald');
    expect(GERESERVEERDE_STATUSNAMEN).toContain('niet behaald');
  });
});

describe('wijziging', () => {
  it('describes a toelichting as its new text, without a separate reason', () => {
    expect(wijzigingOmschrijving({ soort: 'EXTERNE_TOELICHTING', vanWaarde: 'Oud', naarWaarde: 'Nieuw' }))
      .toBe('Externe toelichting: Nieuw');
    expect(wijzigingOmschrijving({ soort: 'INTERNE_TOELICHTING', vanWaarde: 'Oud', naarWaarde: '' }))
      .toBe('Interne toelichting verwijderd');
    expect(wijzigingHeeftReden('INTERNE_TOELICHTING')).toBe(false);
    expect(wijzigingHeeftReden('VOORTGANG')).toBe(true);
  });
});
