import { describe, expect, it } from 'vitest';
import { actieAfgesloten, actieStatus, heeftOpenTaak } from './labels';

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
