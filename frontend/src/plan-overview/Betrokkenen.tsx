import React, { useState } from 'react';
import {
  Button, Checkbox, ContainedList, ContainedListItem, InlineNotification, Layer, Modal, Select, SelectItem,
  Tag, TextInput, Tile,
} from '@carbon/react';
import { TrashCan } from '@carbon/react/icons';
import type { Betrokkene, RelatieType } from '../shared/api';
import { NietGeladen } from '../shared/Meldingen';
import type { Resource } from '../shared/useResource';
import { useSubmit } from '../shared/useSubmit';
import type { NieuweBetrokkene } from './overzichtActies';

/** Tag tints the initials tile cycles through, so a plan's betrokkenen stay apart. */
const TINTS = ['blue', 'purple', 'green', 'teal', 'cyan', 'magenta'] as const;

/** First letter of the first two words: "S. Jansen" -> "SJ". */
const initialen = (naam: string) => naam.trim().split(/\s+/).slice(0, 2).map(w => w[0] ?? '').join('').toUpperCase();

/**
 * The plan's betrokkenen (roles from the Open Plan relatietype
 * register); one of them is the plan's procesbegeleider.
 */
export function Betrokkenen({ betrokkenen, relatietypen, onToevoegen, onVerwijderen }: {
  betrokkenen: Betrokkene[];
  relatietypen: Resource<RelatieType[]>;
  /** Throw on failure, so the modal keeps the error and stays open. */
  onToevoegen: (invoer: NieuweBetrokkene) => Promise<void>;
  onVerwijderen: (betrokkene: Betrokkene) => Promise<void>;
}) {
  const [toevoegen, setToevoegen] = useState(false);
  const [teVerwijderen, setTeVerwijderen] = useState<Betrokkene | null>(null);
  const procesbegeleider = betrokkenen.find(b => b.isPrimary) ?? null;

  return (
    <Tile className="pdca-card">
      <Layer>
        <ContainedList className="pdca-kaartlijst pdca-betrokkenenlijst" label="Betrokkenen" kind="on-page" isInset
          action={<Button size="sm" kind="ghost" onClick={() => setToevoegen(true)}>Toevoegen</Button>}>
          {betrokkenen.length === 0 ? (
            <ContainedListItem className="pdca-betrokkene">
              <span className="pdca-empty">Geen betrokkenen</span>
            </ContainedListItem>
          ) : betrokkenen.map((betrokkene, i) => {
            const contact = [betrokkene.email, betrokkene.phone].filter(Boolean).join(' / ');
            return (
              <ContainedListItem key={betrokkene.id} className={`pdca-betrokkene pdca-betrokkene--${TINTS[i % TINTS.length]}`}
                renderIcon={() => <span aria-hidden="true">{initialen(betrokkene.name)}</span>}
                action={(
                  <Button size="sm" kind="danger--ghost" hasIconOnly renderIcon={TrashCan}
                    iconDescription={`${betrokkene.name} verwijderen`} tooltipPosition="left"
                    onClick={() => setTeVerwijderen(betrokkene)} />
                )}>
                <span className="pdca-betrokkene-naam">
                  {betrokkene.name}
                  {betrokkene.id === procesbegeleider?.id && <Tag size="sm" type="green">Procesbegeleider</Tag>}
                </span>
                <span className="pdca-betrokkene-rol">{betrokkene.role}</span>
                {contact && <span className="pdca-betrokkene-contact">{contact}</span>}
              </ContainedListItem>
            );
          })}
        </ContainedList>
      </Layer>
      <ToevoegenModal open={toevoegen} procesbegeleider={procesbegeleider} relatietypen={relatietypen}
        onClose={() => setToevoegen(false)} onSave={onToevoegen} />
      <VerwijderenModal betrokkene={teVerwijderen} onClose={() => setTeVerwijderen(null)} onSubmit={onVerwijderen} />
    </Tile>
  );
}

const LEEG: NieuweBetrokkene = { name: '', role: '', email: '', phone: '', isPrimary: false };

/** Adds a betrokkene; a plan without procesbegeleider gets its first one as procesbegeleider by default. */
function ToevoegenModal({ open, procesbegeleider, relatietypen, onClose, onSave }: {
  open: boolean; procesbegeleider: Betrokkene | null; relatietypen: Resource<RelatieType[]>;
  onClose: () => void; onSave: (invoer: NieuweBetrokkene) => Promise<void>;
}) {
  const [form, setForm] = useState<NieuweBetrokkene>(LEEG);
  const [wasOpen, setWasOpen] = useState(false);
  const { saving, fout, submit, reset } = useSubmit();
  if (open !== wasOpen) {
    setWasOpen(open);
    if (open) {
      setForm({ ...LEEG, isPrimary: !procesbegeleider });
      reset();
    }
  }
  const wijzig = (deel: Partial<NieuweBetrokkene>) => setForm(f => ({ ...f, ...deel }));
  const opslaan = async () => {
    if (!form.name.trim() || !form.role) return;
    if (await submit(() => onSave(form))) onClose();
  };
  return (
    <Modal open={open} modalHeading="Betrokkene toevoegen" size="sm"
      primaryButtonText={saving ? 'Toevoegen…' : 'Toevoegen'} secondaryButtonText="Annuleren"
      primaryButtonDisabled={saving || !form.name.trim() || !form.role}
      onRequestClose={onClose} onRequestSubmit={opslaan} onSecondarySubmit={onClose}>
      <div className="pdca-modal-form">
        {fout && <InlineNotification kind="error" title="Toevoegen mislukt" subtitle={fout} lowContrast hideCloseButton />}
        <TextInput id="party-name" labelText="Naam *" placeholder="Volledige naam" value={form.name}
          onChange={(e: React.ChangeEvent<HTMLInputElement>) => wijzig({ name: e.target.value })} />
        {relatietypen.error != null && !relatietypen.data
          ? <NietGeladen wat="De rollen (relatietype-register)" error={relatietypen.error} onRetry={relatietypen.reload} />
          : (
            <Select id="party-role" labelText="Rol *" value={form.role}
              onChange={(e: React.ChangeEvent<HTMLSelectElement>) => wijzig({ role: e.target.value })}>
              <SelectItem value="" text={relatietypen.data ? 'Selecteer een rol...' : 'Rollen laden…'} />
              {(relatietypen.data ?? []).map(r => <SelectItem key={r.uuid} value={r.naam} text={r.naam} />)}
            </Select>
          )}
        <Checkbox id="party-primary" labelText="Procesbegeleider" checked={form.isPrimary}
          helperText={procesbegeleider && form.isPrimary ? `Vervangt ${procesbegeleider.name} als procesbegeleider` : undefined}
          onChange={(_: unknown, { checked }: { checked: boolean }) => wijzig({ isPrimary: checked })} />
        <TextInput id="party-email" labelText="E-mail" placeholder="email@voorbeeld.nl" value={form.email}
          onChange={(e: React.ChangeEvent<HTMLInputElement>) => wijzig({ email: e.target.value })} />
        <TextInput id="party-phone" labelText="Telefoon" placeholder="06-12345678" value={form.phone}
          onChange={(e: React.ChangeEvent<HTMLInputElement>) => wijzig({ phone: e.target.value })} />
      </div>
    </Modal>
  );
}

function VerwijderenModal({ betrokkene, onClose, onSubmit }: {
  betrokkene: Betrokkene | null; onClose: () => void; onSubmit: (betrokkene: Betrokkene) => Promise<void>;
}) {
  const [vorige, setVorige] = useState<Betrokkene | null>(null);
  const { saving, fout, submit, reset } = useSubmit();
  if (betrokkene !== vorige) {
    setVorige(betrokkene);
    if (betrokkene) reset();
  }
  const verwijder = async () => {
    if (betrokkene && await submit(() => onSubmit(betrokkene))) onClose();
  };
  return (
    <Modal open={!!betrokkene} danger modalHeading="Betrokkene verwijderen" size="sm"
      primaryButtonText={saving ? 'Verwijderen…' : 'Verwijderen'} secondaryButtonText="Annuleren"
      primaryButtonDisabled={saving} onRequestClose={onClose} onRequestSubmit={verwijder}>
      <div className="pdca-modal-form">
        {fout && <InlineNotification kind="error" title="Verwijderen mislukt" subtitle={fout} lowContrast hideCloseButton />}
        <p>
          <strong>{betrokkene?.name}</strong> ({betrokkene?.role}) wordt verwijderd als betrokkene bij dit plan.
          {betrokkene?.isPrimary && ' Het plan heeft daarna geen procesbegeleider meer.'}
        </p>
      </div>
    </Modal>
  );
}
