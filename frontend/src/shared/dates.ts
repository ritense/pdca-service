/**
 * Dates as the bundles show and enter them, all in the browser's local time.
 * Two kinds of value come in: a plain date (`yyyy-mm-dd`, a day in the
 * calendar: never converted, so never a day off) and a datetime (a moment:
 * an ISO string with a `T`, shown as the local date and time; one without
 * zone, like the app's own LocalDateTime values, is read as local time).
 */

const pad = (n: number) => String(n).padStart(2, '0');

/** A local Date as a plain date (yyyy-mm-dd). */
function alsDatum(datum: Date): string {
  return `${datum.getFullYear()}-${pad(datum.getMonth() + 1)}-${pad(datum.getDate())}`;
}

/** The moment of a datetime string; null for a plain date or anything unreadable. */
function moment(waarde: string): Date | null {
  if (!waarde.includes('T')) return null;
  const d = new Date(waarde);
  return Number.isNaN(d.getTime()) ? null : d;
}

/** Today as a plain date. */
export function vandaag(): string {
  return alsDatum(new Date());
}

/** The plain date the given number of weeks from today. */
export function overWeken(weken: number): string {
  const datum = new Date();
  datum.setDate(datum.getDate() + weken * 7);
  return alsDatum(datum);
}

/** The plain date the given number of weeks after another plain date; empty for an invalid date. */
export function plusWeken(datum: string, weken: number): string {
  const [jaar, maand, dag] = datum.split('-').map(Number);
  if (!jaar || !maand || !dag) return '';
  return alsDatum(new Date(jaar, maand - 1, dag + weken * 7));
}

/** The calendar day of a plain date or datetime, as a plain date in local time. */
export function datumDeel(waarde: string): string {
  const m = moment(waarde);
  return m ? alsDatum(m) : waarde.slice(0, 10);
}

/** Whether a plain date or datetime lies on a day before today. */
export function isVerlopen(waarde: string | null | undefined): boolean {
  return !!waarde && datumDeel(waarde) < vandaag();
}

/** dd-mm-yyyy for a plain date or a datetime (its local date); empty without a value. */
export function formatDate(waarde: string | undefined | null): string {
  if (!waarde) return '';
  const [jaar, maand, dag] = datumDeel(waarde).split('-');
  return jaar && maand && dag ? `${dag}-${maand}-${jaar}` : waarde;
}

/** hh:mm of a datetime in local time; empty for a plain date. */
export function formatTime(waarde: string | undefined | null): string {
  const m = waarde ? moment(waarde) : null;
  return m ? `${pad(m.getHours())}:${pad(m.getMinutes())}` : '';
}
