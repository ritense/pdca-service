import React from 'react';
import { Button, Tag, Tile } from '@carbon/react';
import { Edit, TrashCan } from '@carbon/react/icons';
import type { PhaseConfig, VoortgangStatusOptie } from '../shared/api';
import { jsonLijst } from '../shared/caseConfig';
import { evalTypeLabel, voortgangStatus } from '../shared/labels';
import { parseSubdoelMapping, parseVoortgang } from './configForm';

const thStyle: React.CSSProperties = {
  textAlign: 'left', padding: '12px 16px', borderBottom: '2px solid var(--cds-border-subtle)',
  fontSize: 12, fontWeight: 600, color: 'var(--cds-text-secondary)', textTransform: 'uppercase', letterSpacing: 0.5,
};
const tdStyle: React.CSSProperties = { padding: '12px 16px' };
const tagsStyle: React.CSSProperties = { display: 'flex', gap: 4, flexWrap: 'wrap', alignItems: 'center' };
const nvtStyle: React.CSSProperties = { fontSize: 12, color: 'var(--cds-text-helper)', fontStyle: 'italic' };

/** The configured case types with what each configures. */
export function ConfigTabel({ configs, standaardVoortgang, onBewerk, onVerwijder }: {
  configs: PhaseConfig[];
  standaardVoortgang: VoortgangStatusOptie[];
  onBewerk: (cfg: PhaseConfig) => void;
  onVerwijder: (cfg: PhaseConfig) => void;
}) {
  return (
    <Tile>
      <table style={{ width: '100%', borderCollapse: 'collapse' }}>
        <thead>
          <tr>
            <th style={thStyle}>Dossiertype</th>
            <th style={thStyle}>Planstatussen</th>
            <th style={thStyle}>Voortgangsstatussen</th>
            <th style={thStyle}>Positietypen</th>
            <th style={thStyle}>Subdoelen per hoofddoel</th>
            <th style={thStyle}>Evaluatietypen</th>
            <th style={{ ...thStyle, textAlign: 'right' }}>Acties</th>
          </tr>
        </thead>
        <tbody>
          {configs.map(cfg => {
            const voortgang = parseVoortgang(cfg.voortgangStatussen);
            const lijst = voortgang ?? standaardVoortgang;
            const posities = jsonLijst(cfg.positieTypen);
            const subdoelMapping = parseSubdoelMapping(cfg.subdoelMapping);
            return (
              <tr key={cfg.caseDefinitionKey} style={{ borderBottom: '1px solid var(--cds-border-subtle)' }}>
                <td style={{ ...tdStyle, fontWeight: 500 }}>
                  {cfg.caseDefinitionKey}
                  {cfg.planCaseDefinitionKey && (
                    <div style={{ fontSize: 12, fontWeight: 400, color: 'var(--cds-text-helper)' }}>
                      intake → {cfg.planCaseDefinitionKey}
                    </div>
                  )}
                </td>
                <td style={tdStyle}>
                  <div style={tagsStyle}>{jsonLijst(cfg.planStatussen).map(p => <Tag key={p} size="sm" type="teal">{p}</Tag>)}</div>
                </td>
                <td style={tdStyle}>
                  {cfg.planCaseDefinitionKey ? <span style={nvtStyle}>n.v.t. (intake)</span> : (
                    <div style={tagsStyle}>
                      {lijst.map(o => {
                        const weergave = voortgangStatus(o.code, lijst);
                        return <Tag key={o.code} size="sm" type={weergave.tag}>{weergave.label}</Tag>;
                      })}
                      {!voortgang && <span style={nvtStyle}>standaard</span>}
                    </div>
                  )}
                </td>
                <td style={tdStyle}>
                  <div style={tagsStyle}>
                    {posities.length === 0 ? <span style={nvtStyle}>geen (vrije invoer)</span>
                      : posities.map(p => <Tag key={p} size="sm" type="cyan">{p}</Tag>)}
                  </div>
                </td>
                <td style={tdStyle}>
                  <div style={tagsStyle}>
                    {subdoelMapping.length === 0 ? <span style={nvtStyle}>geen (alle subdoelen)</span>
                      : subdoelMapping.map(r => (
                        <Tag key={r.hoofddoel} size="sm" type="magenta" title={r.subdoelen.join(', ')}>
                          {r.hoofddoel} ({r.subdoelen.length})
                        </Tag>
                      ))}
                  </div>
                </td>
                <td style={tdStyle}>
                  <div style={tagsStyle}>
                    {jsonLijst(cfg.evaluationTypes).map(t => <Tag key={t} size="sm" type="purple">{evalTypeLabel(t)}</Tag>)}
                  </div>
                </td>
                <td style={{ ...tdStyle, textAlign: 'right' }}>
                  <Button size="sm" kind="ghost" renderIcon={Edit} iconDescription="Bewerken" hasIconOnly onClick={() => onBewerk(cfg)} />
                  <Button size="sm" kind="danger--ghost" renderIcon={TrashCan} iconDescription="Verwijderen" hasIconOnly
                    onClick={() => onVerwijder(cfg)} />
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </Tile>
  );
}
