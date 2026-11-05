import React, { useCallback, useRef, useState } from 'react';
import { Button, RadioButton, RadioButtonGroup, Tag, TextInput, Tile } from '@carbon/react';
import { registers } from '../shared/api';
import { formatDate } from '../shared/dates';
import { isHttpStatus } from '../shared/http';
import type { PlanPrefill, SubjectType } from './createPlanApi';

export interface SubjectWeergave {
  id: string;
  naam: string;
  regels: string[];
  uitIntake: boolean;
}

/**
 * Looks a subject up in the BRP or objecten register. A subject the intake
 * registered but the register does not know (404) is not an error: it shows
 * with what the intake recorded. A hand-typed id that resolves to nothing
 * does get the error, and so does a register that cannot be reached.
 */
export function useSubjectZoeker(prefill: PlanPrefill | null | undefined) {
  const [subject, setSubject] = useState<SubjectWeergave | null>(null);
  const [fout, setFout] = useState('');
  const [zoekt, setZoekt] = useState(false);
  const token = useRef(0);

  const wis = useCallback(() => {
    token.current++;
    setSubject(null);
    setFout('');
    setZoekt(false);
  }, []);

  const zoek = useCallback(async (id: string, type: SubjectType) => {
    wis();
    const mijn = token.current;
    if (!id) {
      setFout(type === 'PERSON' ? 'Vul een BSN in' : 'Vul een object-ID in');
      return;
    }
    setZoekt(true);
    let gevonden: SubjectWeergave | null = null;
    let melding = 'Geen resultaat gevonden voor dit ID';
    try {
      if (type === 'PERSON') {
        const persoon = await registers.persoon(id);
        const adres = persoon.adres;
        gevonden = {
          id, naam: persoon.naam, uitIntake: false,
          regels: [
            `BSN ${persoon.bsn}`,
            persoon.geboortedatum ? `Geboren ${formatDate(persoon.geboortedatum)}` : '',
            adres ? `${adres.straat} ${adres.huisnummer}, ${adres.woonplaats}` : '',
          ].filter(Boolean),
        };
      } else {
        const object = await registers.object(id);
        const adres = object.adres;
        gevonden = {
          id, naam: object.naam, uitIntake: false,
          regels: [object.type, adres ? `${adres.straat}, ${adres.woonplaats}` : '', object.eigenaar ? `Eigenaar: ${object.eigenaar}` : '']
            .filter(Boolean),
        };
      }
    } catch (e) {
      const naam = prefill?.naam || prefill?.objectNaam;
      if (isHttpStatus(e, 404) && prefill && String(prefill.subjectId ?? '') === id && naam) {
        gevonden = {
          id, naam, uitIntake: true,
          regels: [`${type === 'PERSON' ? 'BSN' : 'Object-ID'} ${id}`, 'Niet in het register gevonden; gegevens uit de intake.'],
        };
      } else if (!isHttpStatus(e, 404)) {
        melding = 'Het register kon niet worden doorzocht; probeer het opnieuw';
      }
    }
    if (mijn !== token.current) return;
    setZoekt(false);
    if (gevonden) setSubject(gevonden);
    else setFout(melding);
  }, [prefill, wis]);

  return { subject, fout, zoekt, zoek, wis };
}

/** "Inwoner/Object": the plan's subject, with a lookup in its register. */
export function SubjectSectie({ subjectType, subjectId, poging, zoeker, onChange }: {
  subjectType: SubjectType;
  subjectId: string;
  poging: boolean;
  zoeker: ReturnType<typeof useSubjectZoeker>;
  onChange: (deel: { subjectType?: SubjectType; subjectId?: string }) => void;
}) {
  const { subject, fout, zoekt, zoek, wis } = zoeker;
  return (
    <section className="cp-blok">
      <h2 className="cp-kop">Inwoner/Object</h2>
      <RadioButtonGroup legendText="Soort" name="cp-subjecttype" valueSelected={subjectType}
        onChange={v => { onChange({ subjectType: v === 'OBJECT' ? 'OBJECT' : 'PERSON' }); wis(); }}>
        <RadioButton id="cp-subject-persoon" labelText="Persoon" value="PERSON" />
        <RadioButton id="cp-subject-object" labelText="Object" value="OBJECT" />
      </RadioButtonGroup>
      <div className="cp-zoek">
        <TextInput id="cp-subject-id" labelText={subjectType === 'PERSON' ? 'BSN' : 'Object-ID'}
          placeholder={subjectType === 'PERSON' ? 'Bijv. 111222333' : ''} value={subjectId}
          invalid={(poging && !subjectId.trim()) || !!fout} invalidText={fout || 'Verplicht'}
          onChange={e => { onChange({ subjectId: e.target.value }); wis(); }}
          onKeyDown={e => { if (e.key === 'Enter') zoek(subjectId.trim(), subjectType); }} />
        <Button kind="tertiary" size="md" disabled={zoekt} onClick={() => zoek(subjectId.trim(), subjectType)}>
          {zoekt ? 'Zoeken…' : 'Zoek'}
        </Button>
      </div>
      {subject && (
        <Tile className="cp-tile">
          <div className="cp-subject-naam">
            <strong>{subject.naam}</strong>
            {subject.uitIntake && <Tag size="sm" type="warm-gray">Uit intake</Tag>}
          </div>
          {subject.regels.map(r => <div key={r} className="cp-subject-regel">{r}</div>)}
        </Tile>
      )}
    </section>
  );
}
