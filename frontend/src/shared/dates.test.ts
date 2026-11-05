import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { datumDeel, formatDate, formatTime, isVerlopen, overWeken, plusWeken, vandaag } from './dates';

/** Sets the clock to a local moment (the tests run in Europe/Amsterdam). */
function klok(jaar: number, maand: number, dag: number, uur = 12, minuut = 0) {
  vi.setSystemTime(new Date(jaar, maand - 1, dag, uur, minuut));
}

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
});

it('runs in a timezone ahead of UTC', () => {
  // Winter time in Amsterdam is UTC+1; without it the near-midnight cases prove nothing.
  expect(new Date(2026, 0, 15).getTimezoneOffset()).toBe(-60);
});

describe('vandaag', () => {
  it('is the local date, also just after midnight when UTC is still on the day before', () => {
    klok(2026, 1, 15, 0, 30);
    expect(new Date().toISOString().slice(0, 10)).toBe('2026-01-14');
    expect(vandaag()).toBe('2026-01-15');
  });

  it('is the local date just before midnight', () => {
    klok(2026, 1, 15, 23, 59);
    expect(vandaag()).toBe('2026-01-15');
  });
});

describe('overWeken', () => {
  it('counts weeks from the local today', () => {
    klok(2026, 1, 28, 0, 15);
    expect(overWeken(1)).toBe('2026-02-04');
    expect(overWeken(0)).toBe('2026-01-28');
  });

  it('crosses the change to summer time without losing a day', () => {
    klok(2026, 3, 25, 0, 30);
    expect(overWeken(1)).toBe('2026-04-01');
  });
});

describe('plusWeken', () => {
  it('adds whole weeks to a plain date', () => {
    expect(plusWeken('2026-01-01', 6)).toBe('2026-02-12');
    expect(plusWeken('2026-12-28', 1)).toBe('2027-01-04');
  });

  it('crosses the change to summer and winter time without losing a day', () => {
    expect(plusWeken('2026-03-24', 1)).toBe('2026-03-31');
    expect(plusWeken('2026-10-21', 1)).toBe('2026-10-28');
  });

  it('is empty for an invalid date', () => {
    expect(plusWeken('', 1)).toBe('');
    expect(plusWeken('geen-datum', 1)).toBe('');
    expect(plusWeken('2026-01', 1)).toBe('');
  });
});

describe('datumDeel', () => {
  it('keeps a plain date as is', () => {
    expect(datumDeel('2026-01-15')).toBe('2026-01-15');
  });

  it('takes the local day of a UTC datetime just before midnight UTC', () => {
    expect(datumDeel('2026-01-14T23:30:00Z')).toBe('2026-01-15');
    expect(datumDeel('2026-01-14T23:30:00.000+00:00')).toBe('2026-01-15');
  });

  it('reads a datetime without zone as local time', () => {
    expect(datumDeel('2026-01-15T00:30:00')).toBe('2026-01-15');
    expect(datumDeel('2026-01-15T23:59:59')).toBe('2026-01-15');
  });

  it('falls back to the first ten characters of an unreadable datetime', () => {
    expect(datumDeel('2026-01-15Tgeen-tijd')).toBe('2026-01-15');
  });
});

describe('isVerlopen', () => {
  beforeEach(() => klok(2026, 1, 15, 0, 30));

  it('is true for a day before today', () => {
    expect(isVerlopen('2026-01-14')).toBe(true);
  });

  it('is false for today and later', () => {
    expect(isVerlopen('2026-01-15')).toBe(false);
    expect(isVerlopen('2026-01-16')).toBe(false);
  });

  it("compares a datetime by its local day: today's first half hour is still today", () => {
    expect(isVerlopen('2026-01-14T23:10:00Z')).toBe(false);
    expect(isVerlopen('2026-01-14T22:50:00Z')).toBe(true);
  });

  it('is false without a value', () => {
    expect(isVerlopen(null)).toBe(false);
    expect(isVerlopen(undefined)).toBe(false);
    expect(isVerlopen('')).toBe(false);
  });
});

describe('formatDate', () => {
  it('shows a plain date as dd-mm-yyyy, without conversion', () => {
    expect(formatDate('2026-01-01')).toBe('01-01-2026');
  });

  it('shows the local date of a datetime', () => {
    expect(formatDate('2025-12-31T23:30:00Z')).toBe('01-01-2026');
    expect(formatDate('2026-01-01T00:15:00')).toBe('01-01-2026');
  });

  it('is empty without a value', () => {
    expect(formatDate(null)).toBe('');
    expect(formatDate(undefined)).toBe('');
    expect(formatDate('')).toBe('');
  });

  it('shows anything it cannot read as is', () => {
    expect(formatDate('onbekend')).toBe('onbekend');
  });
});

describe('formatTime', () => {
  it('shows the local time of a datetime', () => {
    expect(formatTime('2026-01-14T23:30:00Z')).toBe('00:30');
    expect(formatTime('2026-07-01T10:05:00Z')).toBe('12:05');
    expect(formatTime('2026-01-15T09:07:00')).toBe('09:07');
  });

  it('is empty for a plain date, nothing, or an unreadable datetime', () => {
    expect(formatTime('2026-01-15')).toBe('');
    expect(formatTime(null)).toBe('');
    expect(formatTime('2026-01-15Tgeen-tijd')).toBe('');
  });
});
