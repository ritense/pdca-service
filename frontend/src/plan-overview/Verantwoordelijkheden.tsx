import React, { useState } from 'react';
import {
  Button, Checkbox, DataTable, InlineNotification, Layer, Modal, Select, SelectItem, Table, TableBody, TableCell,
  TableHead, TableHeader, TableRow, Tag, TextInput, Tile,
} from '@carbon/react';
import { TrashCan } from '@carbon/react/icons';
import type { Betrokkene, RelatieType } from '../shared/api';
import { NietGeladen } from '../shared/Meldingen';
import type { Resource } from '../shared/useResource';
import { useSubmit } from '../shared/useSubmit';
import type { NieuweBetrokkene } from './overzichtActies';

const HEADERS = [
  { key: 'name', header: 'Naam' },
  { key: 'role', header: 'Rol' },
  { key: 'contact', header: 'Contact' },
  { key: 'actions', header: '' },
];

/**
 * The plan's verantwoordelijken (roles from the Open Plan relatietype
 * register); one of them is the plan's procesbegeleider.
 */
export function Verantwoordelijkheden({ betrokkenen, relatietypen, onToevoegen, onVerwijderen }: {
  betrokkenen: Betrokkene[];
  relatietypen: Resource<RelatieType[]>;
  /** Throw on failure, so the modal keeps the error and stays open. */
  onToevoegen: (invoer: NieuweBetrokkene) => Promise<void>;
  onVerwijderen: (betrokkene: Betrokkene) => Promise<void>;
}) {
  const [toevoegen, setToevoegen] = useState(false);
  const [teVerwijderen, setTeVerwijderen] = useState<Betrokkene | null>(null);
  const procesbegeleider = betrokkenen.find(b => b.isPrimary) ?? null;
  const rows = betrokkenen.map(p => ({
    id: p.id, name: p.name, role: p.role, contact: [p.email, p.phone].filter(Boolean).join(' / '), actions: p.id,
  }));

  return (
    <Tile className="pdca-card">
      <Layer>
        <div className="pdca-card-header">
          <h4>Verantwoordelijkheden</h4>
          <Button size="sm" kind="ghost" onClick={() => setToevoegen(true)}>Toevoegen</Button>
        </div>
        <div className="pdca-card-body">
          {betrokkenen.length === 0 ? <p className="pdca-empty">Geen verantwoordelijkheden</p> : (
            <div className="pdca-table-scroll">
              <DataTable rows={rows} headers={HEADERS}>
                {({ rows, headers, getTableProps, getHeaderProps, getRowProps }) => (
                  <Table {...getTableProps()} size="sm">
                    <TableHead>
                      <TableRow>
                        {headers.map(header => (
                          <TableHeader {...getHeaderProps({ header })} key={header.key}>{header.header}</TableHeader>
                        ))}
                      </TableRow>
                    </TableHead>
                    <TableBody>
                      {rows.map(row => (
                        <TableRow {...getRowProps({ row })} key={row.id}>
                          {row.cells.map(cell => (
                            <TableCell key={cell.id}>
                              {cell.info.header === 'actions' ? (
                                <Button size="sm" kind="danger--ghost" hasIconOnly renderIcon={TrashCan} iconDescription="Verwijderen"
                                  onClick={() => setTeVerwijderen(betrokkenen.find(b => b.id === cell.value) ?? null)} />
                              ) : cell.info.header === 'name' && row.id === procesbegeleider?.id ? (
                                <>{cell.value} <Tag size="sm" type="green">Procesbegeleider</Tag></>
                              ) : cell.value}
                            </TableCell>
                          ))}
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                )}
              </DataTable>
            </div>
          )}
        </div>
      </Layer>
      <ToevoegenModal open={toevoegen} procesbegeleider={procesbegeleider} relatietypen={relatietypen}
        onClose={() => setToevoegen(false)} onSave={onToevoegen} />
      <VerwijderenModal betrokkene={teVerwijderen} onClose={() => setTeVerwijderen(null)} onSubmit={onVerwijderen} />
    </Tile>
  );
}

const LEEG: NieuweBetrokkene = { name: '', role: '', email: '', phone: '', isPrimary: false };

/** Adds a verantwoordelijke; a plan without procesbegeleider gets its first one as procesbegeleider by default. */
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
    <Modal open={open} modalHeading="Verantwoordelijkheid toevoegen" size="sm"
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
    <Modal open={!!betrokkene} danger modalHeading="Verantwoordelijke verwijderen" size="sm"
      primaryButtonText={saving ? 'Verwijderen…' : 'Verwijderen'} secondaryButtonText="Annuleren"
      primaryButtonDisabled={saving} onRequestClose={onClose} onRequestSubmit={verwijder}>
      <div className="pdca-modal-form">
        {fout && <InlineNotification kind="error" title="Verwijderen mislukt" subtitle={fout} lowContrast hideCloseButton />}
        <p>
          <strong>{betrokkene?.name}</strong> ({betrokkene?.role}) wordt verwijderd als verantwoordelijke bij dit plan.
          {betrokkene?.isPrimary && ' Het plan heeft daarna geen procesbegeleider meer.'}
        </p>
      </div>
    </Modal>
  );
}
