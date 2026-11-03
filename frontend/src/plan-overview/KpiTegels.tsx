import React from 'react';
import { Tile } from '@carbon/react';
import type { Doel } from '../shared/api';
import { formatDate, isVerlopen } from '../shared/dates';
import { contactmomentDatum } from '../shared/labels';
import type { OverzichtModel } from './useOverzicht';

const KPI_COLORS = ['#24a148', '#0f62fe', '#ff832b', '#8a3ffc'];

const OPEN_ACTIESTATUSSEN = ['PLANNED', 'IN_PROGRESS', 'PENDING_REVIEW'];

/** The figures of the KPI tiles. */
function kpi(model: OverzichtModel) {
  const { doelen, doelDetails, acties, contactmomenten, contactmomentDetails } = model.data;
  const detailsVan = (uuid: string) => doelDetails.find(d => d.doelUuid === uuid);
  // Voortgang = how the active subdoelen are going (overlay voortgangsstatus, counted by the
  // betekenis of the status in the plan's list); the hoofddoel stays out.
  const subdoelen = doelen.filter(d => !model.isHoofddoel(d));
  const actief = subdoelen.filter(d => d.status === 'actief');
  const betekenisVan = (d: Doel) =>
    model.voortgangOpties.find(o => o.code === detailsVan(d.uuid)?.voortgangStatus)?.betekenis;
  const opKoers = actief.filter(d => betekenisVan(d) === 'GAAT_GOED').length;
  const aandacht = actief.filter(d => {
    const betekenis = betekenisVan(d);
    return betekenis === 'LET_OP' || betekenis === 'GAAT_NIET_GOED';
  }).length;
  const beoordeeld = actief.filter(d => !!detailsVan(d.uuid)?.voortgangStatus).length;
  // A planned contactmoment has no outcome yet (indicatieContactGelukt null).
  const geplandeDatums = contactmomenten
    .filter(c => c.indicatieContactGelukt == null)
    .map(c => contactmomentDatum(c, contactmomentDetails.find(d => d.contactmomentUuid === c.uuid)))
    .filter(Boolean)
    .sort();
  return {
    voortgang: { opKoers, aandacht, beoordeeld, nietBeoordeeld: actief.length - beoordeeld },
    lopendeSubdoelen: actief.length,
    totaalSubdoelen: subdoelen.length,
    openActies: acties.filter(a => OPEN_ACTIESTATUSSEN.includes(a.status)).length,
    afgerondeEvaluaties: contactmomenten.filter(c => c.indicatieContactGelukt === true).length,
    volgendeEvaluatie: geplandeDatums[0] ?? null,
  };
}

/** Voortgang, subdoelen, open acties and the next evaluation of the plan. */
export function KpiTegels({ model }: { model: OverzichtModel }) {
  const cijfers = kpi(model);
  const { voortgang } = cijfers;
  const voortgangSub = voortgang.beoordeeld === 0
    ? 'Nog niet beoordeeld'
    : 'gaat goed'
      + (voortgang.aandacht ? ` · ${voortgang.aandacht} ${voortgang.aandacht === 1 ? 'vraagt' : 'vragen'} aandacht` : '')
      + (voortgang.nietBeoordeeld ? ` · ${voortgang.nietBeoordeeld} nog niet beoordeeld` : '');
  const items = [
    {
      label: 'Voortgang',
      value: voortgang.beoordeeld === 0 ? '–' : `${voortgang.opKoers}/${voortgang.beoordeeld}`,
      sub: voortgangSub,
    },
    { label: 'Subdoelen', value: String(cijfers.lopendeSubdoelen), sub: `van ${cijfers.totaalSubdoelen} subdoelen` },
    { label: 'Open acties', value: String(cijfers.openActies), sub: 'openstaand' },
    {
      label: 'Volgende evaluatie',
      value: cijfers.volgendeEvaluatie ? formatDate(cijfers.volgendeEvaluatie) : 'Niet gepland',
      sub: `${isVerlopen(cijfers.volgendeEvaluatie) ? 'verlopen · ' : ''}${cijfers.afgerondeEvaluaties} afgerond`,
      compact: true,
    },
  ];
  return (
    <div className="pdca-kpi-grid">
      {items.map((item, i) => (
        <Tile key={item.label} className="pdca-kpi-card" style={{ borderTopColor: KPI_COLORS[i] }}>
          <div className="pdca-kpi-label">{item.label}</div>
          <div className={`pdca-kpi-value${item.compact ? ' compact' : ''}`}>{item.value}</div>
          <div className="pdca-kpi-sub">{item.sub}</div>
        </Tile>
      ))}
    </div>
  );
}
