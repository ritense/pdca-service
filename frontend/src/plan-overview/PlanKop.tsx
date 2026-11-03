import React from 'react';
import { MenuButton, MenuItem, MenuItemDivider, Tag } from '@carbon/react';
import type { Betrokkene, Plan, PlanDetails } from '../shared/api';
import { formatDate } from '../shared/dates';
import { dienstverleningLabel, planStatus } from '../shared/labels';
import { NietGeladen } from '../shared/Meldingen';
import type { Resource } from '../shared/useResource';
import { parseSubject } from '../shared/urn';
import type { PlanActie } from './overzichtActies';
import type { SubjectData } from './useOverzicht';

/** The plan's title, status with the plan actions, and its key facts. */
export function PlanKop({ plan, details, procesbegeleider, planStatusOpties, onWeergaveStatus, onPlanActie }: {
  plan: Plan;
  details: PlanDetails;
  procesbegeleider: Betrokkene | null;
  planStatusOpties: string[];
  onWeergaveStatus: (status: string) => void;
  onPlanActie: (actie: PlanActie) => void;
}) {
  const status = planStatus(plan.status, details.weergaveStatus);
  const actief = plan.status === 'actief';
  const huidigeWeergave = details.weergaveStatus || 'Concept';
  return (
    <div className="pdca-page-header">
      <h1>{plan.titel}</h1>
      <div className="pdca-meta">
        <div className="pdca-status-control">
          <Tag type={status.tag}>{status.label}</Tag>
          <MenuButton label="Planacties" size="sm" kind="ghost" menuAlignment="bottom-start">
            {actief ? <>
              {planStatusOpties.map(s => (
                <MenuItem key={s} label={`Status: ${s}`} disabled={s === huidigeWeergave} onClick={() => onWeergaveStatus(s)} />
              ))}
              <MenuItemDivider />
              <MenuItem label="Plan afronden" onClick={() => onPlanActie('afronden')} />
              <MenuItem label="Plan afbreken" kind="danger" onClick={() => onPlanActie('afbreken')} />
            </> : (
              <MenuItem label="Plan heractiveren" onClick={() => onPlanActie('heractiveren')} />
            )}
          </MenuButton>
        </div>
        {procesbegeleider && <Tag size="sm" type="green">Procesbegeleider: {procesbegeleider.name}</Tag>}
        {plan.plantype && <Tag size="sm" type="cool-gray">Dienstverlening: {dienstverleningLabel(plan.plantype.type)}</Tag>}
        {details.subdoelgroep && <Tag size="sm" type="teal">Subdoelgroep: {details.subdoelgroep}</Tag>}
        {details.beginPositie && <Tag size="sm" type="purple">Positie: {details.beginPositie}</Tag>}
        {plan.startdatum && <span>Start: {formatDate(plan.startdatum)}</span>}
        {details.streefEinddatum && <span>Streefdatum: {formatDate(details.streefEinddatum)}</span>}
        {plan.einddatum && <span>Einde: {formatDate(plan.einddatum)}</span>}
      </div>
      {!actief && plan.redenEinde && (
        <p className="pdca-plan-reden">
          {plan.status === 'geannuleerd' ? 'Reden afbreken' : 'Toelichting afronden'}: {plan.redenEinde}
        </p>
      )}
    </div>
  );
}

function formatAddress(adres: { straat: string; huisnummer?: string; postcode: string; woonplaats: string }): string {
  const street = adres.straat + (adres.huisnummer ? ' ' + adres.huisnummer : '');
  return `${street}, ${adres.postcode} ${adres.woonplaats}`;
}

function Detail({ label, waarde }: { label: string; waarde: string }) {
  return (
    <div className="pdca-subject-detail">
      <div className="detail-label">{label}</div>
      <div className="detail-value">{waarde}</div>
    </div>
  );
}

/** The plan subject (plan.domeinregister URN) with its display record; without the record its id and a notice. */
export function SubjectKaart({ plan, subject }: { plan: Plan; subject: Resource<SubjectData> }) {
  const urn = parseSubject(plan.domeinregister);
  if (!urn) return null;
  const data = subject.data;
  if (urn.kind === 'persoon') {
    const brp = data?.kind === 'persoon' ? data.brp : null;
    return (
      <div className="pdca-subject-card">
        <h4>{brp?.naam || 'Persoon'}</h4>
        <Detail label="BSN" waarde={urn.bsn} />
        {brp?.geboortedatum && <Detail label="Geboortedatum" waarde={formatDate(brp.geboortedatum)} />}
        {brp?.adres && <Detail label="Adres" waarde={formatAddress(brp.adres)} />}
        {subject.error != null && <NietGeladen wat="De persoonsgegevens" error={subject.error} onRetry={subject.reload} />}
      </div>
    );
  }
  const object = data?.kind === 'object' ? data.object : null;
  return (
    <div className="pdca-subject-card">
      <h4>{object?.naam || 'Object'}</h4>
      <Detail label="Object-ID" waarde={urn.id} />
      {object?.type && <Detail label="Type" waarde={object.type} />}
      {object?.eigenaar && <Detail label="Eigenaar" waarde={object.eigenaar} />}
      {object?.adres && <Detail label="Adres" waarde={formatAddress(object.adres)} />}
      {subject.error != null && <NietGeladen wat="De objectgegevens" error={subject.error} onRetry={subject.reload} />}
    </div>
  );
}
