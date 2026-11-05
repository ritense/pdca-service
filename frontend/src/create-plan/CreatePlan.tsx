import React, { useEffect, useReducer, useRef, useState } from 'react';
import { Accordion, Button, InlineLoading, InlineNotification, Layer } from '@carbon/react';
import { navigateHost, submitTask } from '../shared/bridge';
import { openplan, openproduct } from '../shared/api';
import { laadCaseConfig } from '../shared/caseConfig';
import { foutmelding, HttpError } from '../shared/http';
import { aantal } from '../shared/labels';
import { LaadFout } from '../shared/Meldingen';
import { useInitContext } from '../shared/PdcaRoot';
import { useResource } from '../shared/useResource';
import { createPlanApi } from './createPlanApi';
import { beginState, formReducer, maakRequest, ontbreekt, Registers, vulVoor } from './createPlanForm';
import { BetrokkenenSectie, EvaluatiesSectie, InstrumentenSectie, SubdoelenSectie } from './LijstSecties';
import { PlanVeldenSectie } from './PlanVeldenSectie';
import { SubjectSectie, useSubjectZoeker } from './SubjectSectie';

/*
 * External task form "Plan aanmaken" at the end of an intake. Prefilled from
 * the intake dossier's content through the case type's prefill mapping:
 * anything from just a bsn up to a complete plan with subdoelen,
 * instrumenten, evaluaties and betrokkenen. The backend creates everything,
 * including the plan dossier, in one call (`plan-intake`) and then requests
 * the instrumenten, one verzoek and instrument dossier each; the form then
 * completes the GZAC task with the plan result.
 *
 * GZAC does not size task-form iframes to their content: the iframe is a
 * fixed viewport inside the task modal, so the form scrolls internally and
 * keeps its primary action in a footer bar that is always visible.
 */

/** Register choice lists: values are the register names (no uuids); the backend looks them up itself. */
async function laadRegisters(): Promise<Registers> {
  const [plantypen, doeltypen, relatietypen, producttypen] = await Promise.all([
    openplan.plantypen.list(), openplan.doeltypen.list(), openplan.relatietypen.list(), openproduct.producttypen.list(),
  ]);
  return { plantypen, doeltypen, relatietypen, producttypen };
}

/** A task submission that has to be (re)sent after the plan exists. */
interface TaakAfronding {
  velden: Record<string, unknown>;
  planDossierId: string;
}

/**
 * The task-form submission is categorised by value-resolver prefix:
 * unprefixed keys become process variables, which vanish with the process
 * instance. The plan result has to outlive the process (the case widgets and
 * the case list read it from the dossier), so every key goes out as `doc:`,
 * written to the document content under the property of the same name.
 */
function docVelden(velden: Record<string, unknown>): Record<string, unknown> {
  const data: Record<string, unknown> = {};
  Object.entries(velden).forEach(([veld, waarde]) => {
    if (waarde !== undefined && waarde !== null) data[`doc:${veld}`] = waarde;
  });
  return data;
}

export function CreatePlan() {
  const documentId = useInitContext().documentId;
  // Without the intake there is nothing to create the plan from, and the
  // backend reads the same dossier when the plan is created.
  const prefill = useResource(documentId ? () => createPlanApi.prefill(documentId) : null, [documentId]);
  const registers = useResource(laadRegisters, []);
  /** The plan case type, from the intake case type's configuration; its configuration supplies the lists. */
  const caseKey = prefill.data?.planCaseDefinitionKey ?? null;
  const caseConfig = useResource(caseKey ? () => laadCaseConfig(caseKey) : null, [caseKey]);

  const [state, dispatch] = useReducer(formReducer, undefined, beginState);
  const [klaar, setKlaar] = useState(false);
  const [prefillMelding, setPrefillMelding] = useState<{ soort: 'info' | 'warning'; tekst: string } | null>(null);
  const zoeker = useSubjectZoeker(prefill.data?.prefill);
  const [bezig, setBezig] = useState(false);
  const [fout, setFout] = useState('');
  const [melding, setMelding] = useState<{ titel: string; tekst?: string } | null>(null);
  const [afronding, setAfronding] = useState<TaakAfronding | null>(null);
  const [afronden, setAfronden] = useState(false);
  const toegepast = useRef(false);
  const zoekSubject = zoeker.zoek;

  useEffect(() => {
    if (toegepast.current || !prefill.data || !registers.data || !caseConfig.data) return;
    toegepast.current = true;
    const { state: gevuld, melding: m } = vulVoor(prefill.data.prefill ?? {}, registers.data, caseConfig.data);
    dispatch({ type: 'vervang', state: gevuld });
    setPrefillMelding(m);
    setKlaar(true);
    if (gevuld.subjectId) zoekSubject(gevuld.subjectId, gevuld.subjectType);
  }, [prefill.data, registers.data, caseConfig.data, zoekSubject]);

  function rondTaakAf(taak: TaakAfronding) {
    setAfronding(taak);
    setAfronden(true);
    setFout('');
    const resultaat = submitTask(docVelden(taak.velden));
    // GZAC closes the task modal and removes this iframe as soon as the task
    // is completed, so the navigation is posted in the same tick as the
    // submission: waiting for submitResult would never get here. GZAC only
    // follows `navigate` within the click's user activation, which the
    // creation call has usually outlasted; the intake's Aangemaakt plan
    // widget is the reliable way to the plan dossier.
    navigateHost(`/cases/${caseKey}/document/${taak.planDossierId}`);
    resultaat.then(r => {
      if (r.ok) return;
      const fouten = [...(r.errors ?? []), ...Object.values(r.fieldErrors ?? {})];
      setFout(`Afronden van de taak mislukt${fouten.length ? `: ${fouten.join('; ')}` : '.'}`);
      setAfronden(false);
    });
  }

  const nogNodig = ontbreekt(state);

  async function maakPlan() {
    if (afronding) {
      rondTaakAf(afronding);
      return;
    }
    dispatch({ type: 'poging' });
    if (nogNodig.length > 0) {
      setTimeout(() => {
        document.querySelector<HTMLElement>('.cp-content [aria-invalid="true"], .cp-content [data-invalid="true"]')?.focus();
      }, 50);
      return;
    }
    if (!documentId || !prefill.data) return;
    const p = prefill.data.prefill;
    const id = state.subjectId.trim();
    const intakeNaam = p && String(p.subjectId ?? '') === id ? p.naam || p.objectNaam || null : null;
    const request = maakRequest(state, documentId, zoeker.subject?.id === id ? zoeker.subject.naam : intakeNaam,
      p?.weergaveStatus || caseConfig.data?.planStatussen[0] || null);

    setBezig(true);
    setFout('');
    try {
      const res = await createPlanApi.createPlan(request);
      const waarschuwingen = res.warnings?.length ? `Let op: ${res.warnings.join('; ')}` : undefined;
      // Idempotent per intake: a repeat submission gets back the plan this
      // intake already produced, and a retry after an interruption completes it.
      setMelding(res.bestaand
        ? { titel: 'Deze intake had al een plan; de taak wordt daarop afgerond.' }
        : {
          titel: `Plan ${res.hervat ? 'verder aangemaakt' : 'aangemaakt'} met ${aantal(res.doelen, 'subdoel', 'subdoelen')} en `
            + `${aantal(res.contactmomenten, 'evaluatie', 'evaluaties')}`
            + (res.instrumentenAangevraagd > 0 ? `; ${aantal(res.instrumentenAangevraagd, 'instrument', 'instrumenten')} aangevraagd.` : '.'),
          tekst: waarschuwingen,
        });
      // The task only completes on a complete plan: the response always carries its plan dossier.
      rondTaakAf({
        velden: { planId: res.planUuid, planTitel: request.titel, planDossierId: res.planDossierId },
        planDossierId: res.planDossierId,
      });
    } catch (e) {
      // 409: another attempt is still running (its message says to wait). An
      // interrupted attempt (5xx, no answer) is completed by the next one.
      const status = e instanceof HttpError ? e.status : 0;
      setFout(status === 0 || status >= 500
        ? `${foutmelding(e, 'dossier')} Probeer het opnieuw: wat al is aangemaakt, wordt hergebruikt.`
        : foutmelding(e, 'dossier'));
    } finally {
      setBezig(false);
    }
  }

  const uitgeschakeld = <Button kind="primary" size="md" disabled>Plan aanmaken</Button>;
  let inhoud: React.ReactNode;
  let voet: React.ReactNode = uitgeschakeld;

  if (!documentId) {
    inhoud = <InlineNotification kind="error" lowContrast hideCloseButton
      title="Dit taakformulier hoort bij een intake-dossier, maar GZAC gaf geen dossier mee." />;
  } else if (prefill.error && !prefill.data) {
    inhoud = <LaadFout titel="De intake kon niet worden gelezen" error={prefill.error} context="dossier" onRetry={prefill.reload} />;
  } else if (prefill.data && !caseKey) {
    inhoud = <InlineNotification kind="error" lowContrast hideCloseButton
      title={`Dossiertype '${prefill.data.caseDefinitionKey ?? 'onbekend'}' is geen intake-dossiertype: `
        + 'kies in PDCA Beheer het plan-dossiertype waarvoor deze intake een plan aanmaakt.'} />;
  } else if (registers.error && !registers.data) {
    inhoud = <LaadFout titel="De keuzelijsten uit de registers konden niet geladen worden" error={registers.error} onRetry={registers.reload} />;
  } else if (caseConfig.error && !caseConfig.data) {
    inhoud = <LaadFout titel="De configuratie van het plan-dossiertype kon niet geladen worden" error={caseConfig.error}
      onRetry={caseConfig.reload} />;
  } else if (!klaar || !registers.data || !caseConfig.data) {
    inhoud = <InlineLoading description="Formulier laden…" />;
  } else {
    const sectieProps = {
      state, dispatch, registers: registers.data,
      evaluatieTypen: caseConfig.data.evaluatieTypen, subdoelMapping: caseConfig.data.subdoelMapping,
    };
    inhoud = <>
      {prefillMelding && (
        <InlineNotification kind={prefillMelding.soort} lowContrast title={prefillMelding.tekst}
          onClose={() => { setPrefillMelding(null); return false; }} />
      )}
      <SubjectSectie subjectType={state.subjectType} subjectId={state.subjectId} poging={state.poging} zoeker={zoeker}
        onChange={deel => dispatch({ type: 'subject', ...deel })} />
      <PlanVeldenSectie velden={state} registers={registers.data} posities={caseConfig.data.positieTypen} poging={state.poging}
        onChange={(veld, waarde) => dispatch({ type: 'veld', veld, waarde })} />
      <Accordion align="start" className="cp-secties">
        <SubdoelenSectie {...sectieProps} />
        <InstrumentenSectie {...sectieProps} />
        <EvaluatiesSectie {...sectieProps} />
        <BetrokkenenSectie {...sectieProps} />
      </Accordion>
    </>;

    const samenvatting = [
      zoeker.subject?.naam,
      aantal(state.subdoelen.length, 'subdoel', 'subdoelen'),
      aantal(state.instrumenten.length, 'instrument', 'instrumenten'),
      aantal(state.evaluaties.length, 'evaluatie', 'evaluaties'),
      aantal(state.betrokkenen.length, 'betrokkene', 'betrokkenen'),
    ].filter(Boolean).join(' · ');
    const voettekst = bezig ? 'Plan wordt aangemaakt…'
      : afronden ? 'Taak wordt afgerond…'
      : state.poging && nogNodig.length > 0 ? <span className="cp-voet-fout">Nog nodig: {nogNodig.join(', ')}</span>
      : samenvatting;
    voet = <>
      {melding && (
        <InlineNotification kind={melding.tekst ? 'warning' : 'success'} lowContrast hideCloseButton
          title={melding.titel} subtitle={melding.tekst} />
      )}
      {fout && <InlineNotification kind="error" lowContrast title={fout} onClose={() => { setFout(''); return false; }} />}
      <div className="cp-voet-balk">
        <span className="cp-voet-tekst">{voettekst}</span>
        <Button kind="primary" size="md" disabled={bezig || afronden} onClick={maakPlan}>
          {afronding ? 'Taak afronden' : 'Plan aanmaken'}
        </Button>
      </div>
    </>;
  }

  return (
    // One layer up from the modal's surface, so the fields stand out against it.
    <Layer className="cp-laag">
      <main className="cp-content">{inhoud}</main>
      <footer className="cp-voet">{voet}</footer>
    </Layer>
  );
}
