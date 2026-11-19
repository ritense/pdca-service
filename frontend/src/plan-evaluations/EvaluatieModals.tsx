import React, { useEffect, useState } from 'react';
import { InlineNotification, Modal, Select, SelectItem, TextArea, TextInput } from '@carbon/react';
import { pdca } from '../shared/api';
import { overWeken, vandaag } from '../shared/dates';
import { evalTypeLabel } from '../shared/labels';
import { useSubmit } from '../shared/useSubmit';

const DEFAULT_EVALUATIE_TYPE = 'EVALUATION';
/** Default interval to a newly planned evaluation. */
const PLAN_WEKEN = 6;
/** The agenda becomes the klantcontact's inhoud in Open Klant, which holds at most 1000 characters. */
const AGENDA_MAX_LENGTH = 1000;

/** Plans an evaluation: a planned contactmoment with date, type and an optional agenda. */
export function PlanEvaluatieModal({ open, planUuid, evalTypes, onClose, onPlanned }: {
  open: boolean; planUuid: string; evalTypes: string[];
  onClose: () => void; onPlanned: (datum: string) => void;
}) {
  const defaultType = evalTypes.includes(DEFAULT_EVALUATIE_TYPE) ? DEFAULT_EVALUATIE_TYPE : evalTypes[0] ?? DEFAULT_EVALUATIE_TYPE;
  const [datum, setDatum] = useState('');
  const [type, setType] = useState(defaultType);
  const [agenda, setAgenda] = useState('');
  const { saving, fout, submit, reset } = useSubmit();
  useEffect(() => {
    if (open) { setDatum(overWeken(PLAN_WEKEN)); setType(defaultType); setAgenda(''); reset(); }
  }, [open, defaultType, reset]);

  const plannen = async () => {
    if (!datum || !type) return;
    const ok = await submit(() => pdca.contactmomenten.create({
      planUuid, datum, evaluatieType: type, afgerond: false, verslag: agenda.trim() || undefined,
    }));
    if (ok) onPlanned(datum);
  };

  return (
    <Modal open={open} modalHeading="Evaluatie plannen" size="sm"
      primaryButtonText={saving ? 'Plannen…' : 'Plannen'} secondaryButtonText="Annuleren"
      primaryButtonDisabled={saving || !datum || !type || agenda.length > AGENDA_MAX_LENGTH}
      onRequestClose={onClose} onRequestSubmit={plannen}>
      <div className="pdca-modal-form">
        {fout && <InlineNotification kind="error" title="Plannen mislukt" subtitle={fout} lowContrast hideCloseButton />}
        <TextInput id="plan-datum" type="date" labelText="Datum" min={vandaag()} value={datum}
          onChange={(e: React.ChangeEvent<HTMLInputElement>) => setDatum(e.target.value)} />
        <Select id="plan-type" labelText="Type" value={type}
          onChange={(e: React.ChangeEvent<HTMLSelectElement>) => setType(e.target.value)}>
          {evalTypes.map(t => <SelectItem key={t} value={t} text={evalTypeLabel(t)} />)}
        </Select>
        <TextArea id="plan-agenda" labelText="Agenda of notitie (optioneel)" rows={4}
          enableCounter maxCount={AGENDA_MAX_LENGTH} value={agenda}
          onChange={(e: React.ChangeEvent<HTMLTextAreaElement>) => setAgenda(e.target.value)} />
      </div>
    </Modal>
  );
}

/** Moves a planned evaluation to another date. */
export function VerzetModal({ target, onClose, onMoved }: {
  target: { uuid: string; datum: string } | null; onClose: () => void; onMoved: (datum: string) => void;
}) {
  const [datum, setDatum] = useState('');
  const { saving, fout, submit, reset } = useSubmit();
  useEffect(() => { if (target) { setDatum(target.datum); reset(); } }, [target, reset]);

  const opslaan = async () => {
    if (!target || !datum) return;
    const ok = await submit(() => pdca.contactmomenten.reschedule(target.uuid, datum));
    if (ok) onMoved(datum);
  };

  return (
    <Modal open={!!target} modalHeading="Evaluatie verzetten" size="sm"
      primaryButtonText={saving ? 'Verzetten…' : 'Verzetten'} secondaryButtonText="Annuleren"
      primaryButtonDisabled={saving || !datum || datum === target?.datum}
      onRequestClose={onClose} onRequestSubmit={opslaan}>
      <div className="pdca-modal-form">
        {fout && <InlineNotification kind="error" title="Verzetten mislukt" subtitle={fout} lowContrast hideCloseButton />}
        <TextInput id="verzet-datum" type="date" labelText="Nieuwe datum" min={vandaag()} value={datum}
          onChange={(e: React.ChangeEvent<HTMLInputElement>) => setDatum(e.target.value)} />
      </div>
    </Modal>
  );
}
