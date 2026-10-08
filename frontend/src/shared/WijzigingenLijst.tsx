import React, { useEffect, useState } from 'react';
import { Tag, TextInput } from '@carbon/react';
import type { EvaluationChange } from './evaluationSession';
import { subjectTypeLabel, wijzigingOmschrijving, wijzigingenPerOnderdeel } from './labels';

/**
 * The plan changes of an evaluation, grouped per onderdeel. With
 * `onToelichting` (the running evaluation in the side panel) every change gets
 * a field for the reason; without it (a completed evaluation) the reasons are
 * shown read-only.
 */
export function WijzigingenLijst({ wijzigingen, onToelichting }: {
  wijzigingen: EvaluationChange[];
  onToelichting?: (id: string, toelichting: string) => void;
}) {
  return (
    <div className="pdca-wijzigingen">
      {wijzigingenPerOnderdeel(wijzigingen).map(groep => (
        <div key={groep.key} className="pdca-wijziging-groep">
          <div className="pdca-wijziging-onderdeel">
            <Tag size="sm" type="cool-gray">{subjectTypeLabel(groep.subjectType)}</Tag>
            <span className="pdca-text-wrap">{groep.titel}</span>
          </div>
          {groep.wijzigingen.map(w => (
            <div key={w.id} className="pdca-wijziging">
              <div className="pdca-text-wrap">{wijzigingOmschrijving(w)}</div>
              {onToelichting
                ? <ToelichtingVeld wijziging={w} onSave={onToelichting} />
                : w.toelichting && <div className="pdca-wijziging-toelichting">Reden: {w.toelichting}</div>}
            </div>
          ))}
        </div>
      ))}
    </div>
  );
}

function ToelichtingVeld({ wijziging, onSave }: {
  wijziging: EvaluationChange; onSave: (id: string, toelichting: string) => void;
}) {
  const [waarde, setWaarde] = useState(wijziging.toelichting ?? '');
  useEffect(() => { setWaarde(wijziging.toelichting ?? ''); }, [wijziging.toelichting]);
  return (
    <TextInput id={`reden-${wijziging.id}`} size="sm" labelText="" hideLabel placeholder="Reden (optioneel)"
      value={waarde} onChange={(e: any) => setWaarde(e.target.value)}
      onBlur={() => waarde !== (wijziging.toelichting ?? '') && onSave(wijziging.id, waarde)} />
  );
}
