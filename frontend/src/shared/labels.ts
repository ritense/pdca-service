/**
 * Labels for the Open Plan status domain (actief/afgerond/geannuleerd,
 * resultaat behaald/gefaald) plus the local PDCA actie workflow.
 */

const STATUS_LABELS: Record<string, string> = {
  // Open Plan statuses (plan / doel / instrument / contactmoment)
  actief: 'Actief', afgerond: 'Afgerond', geannuleerd: 'Geannuleerd',
  behaald: 'Behaald', gefaald: 'Niet behaald',
  // Local actie workflow
  PLANNED: 'Gepland', IN_PROGRESS: 'In uitvoering', PENDING_REVIEW: 'Ter beoordeling',
  COMPLETED: 'Afgerond', REJECTED: 'Afgekeurd',
};

const EVAL_TYPE_LABELS: Record<string, string> = {
  INTAKE: 'Intake', PROGRESS: 'Voortgang', EVALUATION: 'Evaluatie',
  INSPECTION: 'Inspectie', CRISIS: 'Crisis',
};

const PRIORITY_LABELS: Record<string, string> = {
  HIGH: 'Hoog', NORMAL: 'Normaal', LOW: 'Laag',
};

const ASSIGNEE_TYPE_LABELS: Record<string, string> = {
  PROFESSIONAL: 'Behandelaar', SUBJECT: 'Inwoner/Eigenaar', PROVIDER: 'Aanbieder',
};

const DOELGROEP_LABELS: Record<string, string> = {
  burgers: 'Burgers', interne_organisatie: 'Interne organisatie',
  samenwerkingspartners: 'Samenwerkingspartners',
  bedrijven_en_instellingen: 'Bedrijven en instellingen',
};

export function statusLabel(s: string): string { return STATUS_LABELS[s] || s; }
export function evalTypeLabel(s: string): string { return EVAL_TYPE_LABELS[s] || s; }
export function priorityLabel(s: string): string { return PRIORITY_LABELS[s] || s; }
export function assigneeTypeLabel(s: string): string { return ASSIGNEE_TYPE_LABELS[s] || s; }
export function doelgroepLabel(s: string): string { return DOELGROEP_LABELS[s] || s; }

/**
 * Combined doel/instrument display status: afgerond+gefaald reads as
 * "Niet behaald", afgerond+behaald as "Behaald".
 */
export function doelStatusLabel(status: string, resultaat?: string | null): string {
  if (status === 'afgerond' && resultaat) return statusLabel(resultaat);
  return statusLabel(status);
}

export function doelStatusTag(status: string, resultaat?: string | null): string {
  if (status === 'afgerond') return resultaat === 'gefaald' ? 'red' : 'green';
  if (status === 'actief') return 'blue';
  return 'gray';
}

/** Formats Open Plan ISO datetimes and plain dates as dd-mm-yyyy. */
export function formatDate(dateStr: string | undefined | null): string {
  if (!dateStr) return '';
  const datePart = dateStr.split('T')[0];
  const parts = datePart.split('-');
  if (parts.length === 3) return `${parts[2]}-${parts[1]}-${parts[0]}`;
  return dateStr;
}

/** Doeltype display label: the first doelcategorie naam. */
export function doelTypeLabel(doelType: { doelType: string; categorieen: { naam: string }[] } | undefined): string {
  if (!doelType) return '';
  return doelType.categorieen?.[0]?.naam || doelType.doelType;
}
