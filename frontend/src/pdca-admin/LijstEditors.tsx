import React, { useState } from 'react';
import { Button, Select, SelectItem, Tag, TextInput } from '@carbon/react';
import { Add, Close, Draggable } from '@carbon/react/icons';
import type { VoortgangBetekenis, VoortgangStatusOptie } from '../shared/api';
import { VOORTGANG_BETEKENISSEN } from '../shared/labels';
import { alsRijen, voortgangFout, VoortgangRij } from './configForm';

export const sectionTitleStyle: React.CSSProperties = {
  fontSize: 13, fontWeight: 600, margin: '20px 0 4px', color: 'var(--cds-text-primary)',
};
export const hintStyle: React.CSSProperties = { fontSize: 12, color: 'var(--cds-text-helper)', marginBottom: 8 };

/**
 * Sortable list with drag & drop; items are added via a text field —
 * never comma-separated strings.
 */
export function SortableList({ items, onChange, emptyText, tagType }: {
  items: string[];
  onChange: (items: string[]) => void;
  emptyText: string;
  tagType: string;
}) {
  const [sleepIndex, setSleepIndex] = useState<number | null>(null);
  const [overIndex, setOverIndex] = useState<number | null>(null);
  const [nieuw, setNieuw] = useState('');

  const verplaats = (van: number, naar: number) => {
    if (van === naar) return;
    const kopie = [...items];
    const [item] = kopie.splice(van, 1);
    kopie.splice(naar, 0, item);
    onChange(kopie);
  };

  const voegToe = (waarde: string) => {
    const schoon = waarde.trim();
    if (!schoon || items.includes(schoon)) return;
    onChange([...items, schoon]);
    setNieuw('');
  };

  return (
    <div>
      {items.length === 0 && <div style={{ ...hintStyle, fontStyle: 'italic' }}>{emptyText}</div>}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 2, marginBottom: 8 }}>
        {items.map((item, i) => (
          <div key={item} draggable
            onDragStart={() => setSleepIndex(i)}
            onDragOver={e => { e.preventDefault(); setOverIndex(i); }}
            onDragLeave={() => setOverIndex(cur => (cur === i ? null : cur))}
            onDrop={() => { if (sleepIndex !== null) verplaats(sleepIndex, i); setSleepIndex(null); setOverIndex(null); }}
            onDragEnd={() => { setSleepIndex(null); setOverIndex(null); }}
            style={{
              display: 'flex', alignItems: 'center', gap: 8, padding: '4px 8px',
              background: 'var(--cds-layer-01, #f4f4f4)', cursor: 'grab',
              border: '1px solid var(--cds-border-subtle)',
              borderTop: overIndex === i && sleepIndex !== null && sleepIndex !== i
                ? '2px solid var(--cds-border-interactive, #0f62fe)' : '1px solid var(--cds-border-subtle)',
              opacity: sleepIndex === i ? 0.5 : 1,
            }}>
            <Draggable size={16} style={{ color: 'var(--cds-icon-secondary)', flexShrink: 0 }} />
            <span style={{ fontSize: 12, color: 'var(--cds-text-helper)', width: 16 }}>{i + 1}.</span>
            <span style={{ flex: 1, fontSize: 14 }}>{item}</span>
            <Button size="sm" kind="ghost" renderIcon={Close} iconDescription="Verwijderen" hasIconOnly
              onClick={() => onChange(items.filter(x => x !== item))} />
          </div>
        ))}
      </div>
      <div style={{ display: 'flex', gap: 8, alignItems: 'flex-end' }}>
        <div style={{ flex: 1 }}>
          <TextInput id={`nieuw-${tagType}`} labelText="" hideLabel size="sm" value={nieuw}
            placeholder="Nieuwe waarde…"
            onChange={e => setNieuw(e.target.value)}
            onKeyDown={e => { if (e.key === 'Enter') { e.preventDefault(); voegToe(nieuw); } }} />
        </div>
        <Button size="sm" kind="tertiary" renderIcon={Add} onClick={() => voegToe(nieuw)}
          disabled={!nieuw.trim()}>Toevoegen</Button>
      </div>
    </div>
  );
}

/**
 * The voortgangsstatussen of a plan case type: per row a name, what it means
 * (gaat goed / let op / gaat niet goed: the tag colour and the Voortgang KPI)
 * and a preview of the tag; drag for the order. The code behind a status is
 * assigned once by the backend, so renaming carries through to existing doelen.
 */
export function VoortgangStatussenEditor({ rijen, onChange, standaard }: {
  rijen: VoortgangRij[];
  onChange: (rijen: VoortgangRij[]) => void;
  standaard: VoortgangStatusOptie[];
}) {
  const [sleepIndex, setSleepIndex] = useState<number | null>(null);
  const [overIndex, setOverIndex] = useState<number | null>(null);

  const wijzig = (i: number, deel: Partial<VoortgangRij>) =>
    onChange(rijen.map((r, ri) => (ri === i ? { ...r, ...deel } : r)));

  const verplaats = (van: number, naar: number) => {
    if (van === naar) return;
    const kopie = [...rijen];
    const [rij] = kopie.splice(van, 1);
    kopie.splice(naar, 0, rij);
    onChange(kopie);
  };

  const isStandaard = rijen.length === standaard.length
    && rijen.every((r, i) => r.naam === standaard[i].naam && r.betekenis === standaard[i].betekenis);

  return (
    <div>
      {rijen.length === 0 && (
        <div style={{ ...hintStyle, fontStyle: 'italic' }}>Geen statussen — de standaardlijst geldt.</div>
      )}
      {rijen.length > 0 && (
        <div style={{ display: 'grid', gridTemplateColumns: '16px 16px 1fr 180px 140px 40px', gap: 8, marginBottom: 4 }}>
          <span /><span />
          <span style={{ fontSize: 12, fontWeight: 600, color: 'var(--cds-text-secondary)' }}>Naam</span>
          <span style={{ fontSize: 12, fontWeight: 600, color: 'var(--cds-text-secondary)' }}>Betekenis</span>
          <span style={{ fontSize: 12, fontWeight: 600, color: 'var(--cds-text-secondary)' }}>Zo ziet het eruit</span>
          <span />
        </div>
      )}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 2, marginBottom: 8 }}>
        {rijen.map((rij, i) => {
          const fout = voortgangFout(rij, rijen);
          const tag = VOORTGANG_BETEKENISSEN.find(b => b.betekenis === rij.betekenis)?.tag ?? 'gray';
          return (
            <div key={rij.sleutel} draggable
              onDragStart={() => setSleepIndex(i)}
              onDragOver={e => { e.preventDefault(); setOverIndex(i); }}
              onDragLeave={() => setOverIndex(cur => (cur === i ? null : cur))}
              onDrop={() => { if (sleepIndex !== null) verplaats(sleepIndex, i); setSleepIndex(null); setOverIndex(null); }}
              onDragEnd={() => { setSleepIndex(null); setOverIndex(null); }}
              style={{
                display: 'grid', gridTemplateColumns: '16px 16px 1fr 180px 140px 40px', gap: 8,
                alignItems: 'start', padding: '6px 8px',
                background: 'var(--cds-layer-01, #f4f4f4)', cursor: 'grab',
                border: '1px solid var(--cds-border-subtle)',
                borderTop: overIndex === i && sleepIndex !== null && sleepIndex !== i
                  ? '2px solid var(--cds-border-interactive, #0f62fe)' : '1px solid var(--cds-border-subtle)',
                opacity: sleepIndex === i ? 0.5 : 1,
              }}>
              <Draggable size={16} style={{ color: 'var(--cds-icon-secondary)', marginTop: 8 }} />
              <span style={{ fontSize: 12, color: 'var(--cds-text-helper)', marginTop: 8 }}>{i + 1}.</span>
              <TextInput id={`voortgang-naam-${rij.sleutel}`} labelText="" hideLabel size="sm" value={rij.naam}
                placeholder="bijv. Wacht op inwoner"
                invalid={!!fout} invalidText={fout ?? undefined}
                onChange={e => wijzig(i, { naam: e.target.value })} />
              <Select id={`voortgang-betekenis-${rij.sleutel}`} labelText="" hideLabel size="sm" value={rij.betekenis}
                onChange={e => wijzig(i, { betekenis: e.target.value as VoortgangBetekenis })}>
                {VOORTGANG_BETEKENISSEN.map(b => <SelectItem key={b.betekenis} value={b.betekenis} text={b.label} />)}
              </Select>
              <div style={{ marginTop: 4 }}>
                <Tag size="sm" type={tag}>{rij.naam.trim() || 'Naam'}</Tag>
              </div>
              <Button size="sm" kind="ghost" renderIcon={Close} iconDescription="Verwijderen" hasIconOnly
                onClick={() => onChange(rijen.filter((_, ri) => ri !== i))} />
            </div>
          );
        })}
      </div>
      <div style={{ display: 'flex', gap: 8 }}>
        <Button size="sm" kind="tertiary" renderIcon={Add}
          onClick={() => onChange([...rijen, ...alsRijen([{ code: '', naam: '', betekenis: 'LET_OP' }])])}>
          Status toevoegen
        </Button>
        {!isStandaard && (
          <Button size="sm" kind="ghost" onClick={() => onChange(alsRijen(standaard))}>Standaardlijst herstellen</Button>
        )}
      </div>
    </div>
  );
}

