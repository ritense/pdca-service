import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { CaseConfig } from '../shared/caseConfig';
import type { DoelType, ProductType } from '../shared/types';
import {
  beginState, BetrokkeneRij, EvaluatieRij, FormActie, formReducer, FormState, maakRequest, ontbreekt, Registers, vulVoor,
} from './createPlanForm';

const doeltype = (doelType: string, categorie = 'Werk'): DoelType => ({
  uuid: `dt-${doelType}`, doelType, categorieen: [{ uuid: `cat-${categorie}`, naam: categorie }],
});

const producttype = (code: string): ProductType => ({
  uuid: `pt-${code}`, code, naam: code, themas: [], organisaties: [], keywords: [], parameters: [],
});

const REGISTERS: Registers = {
  plantypen: [{ uuid: 'p1', type: 'Participatie' }, { uuid: 'p2', type: 'Schulddienstverlening' }],
  doeltypen: [doeltype('Werk vinden', 'Hoofddoel'), doeltype('Cv maken'), doeltype('Taalcursus')],
  relatietypen: [{ uuid: 'r1', naam: 'Klantmanager' }],
  producttypen: [producttype('SCHULDHULP'), producttype('TAAL')],
};

const CONFIG: CaseConfig = {
  evaluatieTypen: ['INTAKE', 'EVALUATION', 'CRISIS'],
  planStatussen: [],
  positieTypen: ['Trede 1', 'Trede 2'],
  subdoelMapping: null,
};

const doe = (state: FormState, ...acties: FormActie[]) => acties.reduce(formReducer, state);

beforeEach(() => {
  vi.useFakeTimers();
  // Just after midnight: the default dates must follow the local day.
  vi.setSystemTime(new Date(2026, 0, 15, 0, 30));
});

afterEach(() => {
  vi.useRealTimers();
});

describe('beginState', () => {
  it('starts today with all sections open and nothing attempted', () => {
    const state = beginState();
    expect(state.startdatum).toBe('2026-01-15');
    expect(state.subjectType).toBe('PERSON');
    expect(state.open).toEqual({ subdoelen: true, instrumenten: true, evaluaties: true, betrokkenen: true });
    expect(state.poging).toBe(false);
  });
});

describe('formReducer', () => {
  it('sets a plan field', () => {
    expect(doe(beginState(), { type: 'veld', veld: 'titel', waarde: 'Mijn plan' }).titel).toBe('Mijn plan');
  });

  it('moves the default first evaluation along with the startdatum, but not an edited one', () => {
    const { state } = vulVoor({}, REGISTERS, CONFIG);
    const [auto] = state.evaluaties;
    const handmatig: EvaluatieRij = { ...auto, key: 999, datum: '2026-05-01', auto: false };
    const metTwee = { ...state, evaluaties: [auto, handmatig] };

    const na = doe(metTwee, { type: 'veld', veld: 'startdatum', waarde: '2026-03-01' });
    expect(na.startdatum).toBe('2026-03-01');
    expect(na.evaluaties.map(e => e.datum)).toEqual(['2026-04-12', '2026-05-01']);
  });

  it('leaves the evaluations alone for an invalid startdatum', () => {
    const { state } = vulVoor({}, REGISTERS, CONFIG);
    const na = doe(state, { type: 'veld', veld: 'startdatum', waarde: '' });
    expect(na.startdatum).toBe('');
    expect(na.evaluaties).toBe(state.evaluaties);
  });

  it('changes only the given parts of the subject', () => {
    const state = doe(beginState(), { type: 'subject', subjectId: '999993653' }, { type: 'subject', subjectType: 'OBJECT' });
    expect(state.subjectType).toBe('OBJECT');
    expect(state.subjectId).toBe('999993653');
  });

  it('adds a row with a unique key and opens its section', () => {
    const dicht = doe(beginState(), { type: 'open', sectie: 'subdoelen', open: false });
    const state = doe(dicht,
      { type: 'toevoegen', sectie: 'subdoelen', evaluatieTypen: [] },
      { type: 'toevoegen', sectie: 'subdoelen', evaluatieTypen: [] });
    expect(state.open.subdoelen).toBe(true);
    expect(state.subdoelen).toHaveLength(2);
    expect(state.subdoelen[0]).toMatchObject({ doelType: '', beschrijving: '', status: 'gepland', extra: {} });
    expect(state.subdoelen[0].key).not.toBe(state.subdoelen[1].key);
  });

  it('gives a new evaluation the EVALUATION type when the case type has it, else its first type', () => {
    const metEvaluation = doe(beginState(), { type: 'toevoegen', sectie: 'evaluaties', evaluatieTypen: ['INTAKE', 'EVALUATION'] });
    expect(metEvaluation.evaluaties[0]).toMatchObject({ evaluatieType: 'EVALUATION', status: 'gepland', auto: false, datum: '' });
    const zonder = doe(beginState(), { type: 'toevoegen', sectie: 'evaluaties', evaluatieTypen: ['INTAKE'] });
    expect(zonder.evaluaties[0].evaluatieType).toBe('INTAKE');
    const leeg = doe(beginState(), { type: 'toevoegen', sectie: 'evaluaties', evaluatieTypen: [] });
    expect(leeg.evaluaties[0].evaluatieType).toBe('EVALUATION');
  });

  it('edits one row; editing an evaluation detaches it from the startdatum', () => {
    const { state } = vulVoor({}, REGISTERS, CONFIG);
    const key = state.evaluaties[0].key;
    const na = doe(state, { type: 'wijzigRij', sectie: 'evaluaties', key, wijziging: { notitie: 'Agenda' } });
    expect(na.evaluaties[0]).toMatchObject({ notitie: 'Agenda', auto: false });

    const verschoven = doe(na, { type: 'veld', veld: 'startdatum', waarde: '2026-03-01' });
    expect(verschoven.evaluaties[0].datum).toBe(state.evaluaties[0].datum);
  });

  it('edits a row of another section without touching auto', () => {
    const state = doe(beginState(),
      { type: 'toevoegen', sectie: 'instrumenten', evaluatieTypen: [] },
      { type: 'toevoegen', sectie: 'instrumenten', evaluatieTypen: [] });
    const [eerste, tweede] = state.instrumenten;
    const na = doe(state, { type: 'wijzigRij', sectie: 'instrumenten', key: tweede.key, wijziging: { product: 'TAAL' } });
    expect(na.instrumenten).toEqual([eerste, { ...tweede, product: 'TAAL' }]);
  });

  it('removes one row', () => {
    const state = doe(beginState(),
      { type: 'toevoegen', sectie: 'betrokkenen', evaluatieTypen: [] },
      { type: 'toevoegen', sectie: 'betrokkenen', evaluatieTypen: [] });
    const na = doe(state, { type: 'verwijderRij', sectie: 'betrokkenen', key: state.betrokkenen[0].key });
    expect(na.betrokkenen).toEqual([state.betrokkenen[1]]);
  });

  it('keeps exactly one procesbegeleider', () => {
    const state = doe(beginState(),
      { type: 'toevoegen', sectie: 'betrokkenen', evaluatieTypen: [] },
      { type: 'toevoegen', sectie: 'betrokkenen', evaluatieTypen: [] });
    const [a, b] = state.betrokkenen.map(r => r.key);
    const vlag = (s: FormState) => s.betrokkenen.map((r: BetrokkeneRij) => r.procesbegeleider);

    const metA = doe(state, { type: 'procesbegeleider', key: a, gekozen: true });
    expect(vlag(metA)).toEqual([true, false]);
    const metB = doe(metA, { type: 'procesbegeleider', key: b, gekozen: true });
    expect(vlag(metB)).toEqual([false, true]);
    const geen = doe(metB, { type: 'procesbegeleider', key: b, gekozen: false });
    expect(vlag(geen)).toEqual([false, false]);
  });

  it('a submit attempt opens the sections with rows still missing their choice', () => {
    const state = doe(beginState(),
      { type: 'toevoegen', sectie: 'subdoelen', evaluatieTypen: [] },
      { type: 'open', sectie: 'subdoelen', open: false },
      { type: 'open', sectie: 'instrumenten', open: false },
      { type: 'open', sectie: 'evaluaties', open: false },
      { type: 'poging' });
    expect(state.poging).toBe(true);
    expect(state.open).toMatchObject({ subdoelen: true, instrumenten: false, evaluaties: false });
  });

  it('replaces the whole state', () => {
    const ander = { ...beginState(), titel: 'Vervangen' };
    expect(doe(beginState(), { type: 'vervang', state: ander })).toBe(ander);
  });
});

describe('ontbreekt', () => {
  it('names every missing required field', () => {
    const state = doe(beginState(),
      { type: 'toevoegen', sectie: 'subdoelen', evaluatieTypen: [] },
      { type: 'toevoegen', sectie: 'instrumenten', evaluatieTypen: [] },
      { type: 'toevoegen', sectie: 'instrumenten', evaluatieTypen: [] });
    expect(ontbreekt(state)).toEqual(['BSN', 'titel', 'hoofddoel', 'doel bij 1 subdoel', 'instrumenttype bij 2 instrumenten']);
    expect(ontbreekt(doe(state, { type: 'subject', subjectType: 'OBJECT' }))[0]).toBe('object-ID');
  });

  it('is empty for a complete form', () => {
    const state = doe(beginState(),
      { type: 'subject', subjectId: ' 999993653 ' },
      { type: 'veld', veld: 'titel', waarde: 'Plan' },
      { type: 'veld', veld: 'hoofddoel', waarde: 'Werk vinden' });
    expect(ontbreekt(state)).toEqual([]);
  });
});

describe('vulVoor', () => {
  it('without a prefill: defaults, a first evaluation six weeks after today, and no notice', () => {
    const { state, melding } = vulVoor({}, REGISTERS, CONFIG);
    expect(melding).toBeNull();
    expect(state.startdatum).toBe('2026-01-15');
    expect(state.dienstverlening).toBe('Participatie');
    expect(state.evaluaties).toHaveLength(1);
    expect(state.evaluaties[0]).toMatchObject({ datum: '2026-02-26', evaluatieType: 'EVALUATION', auto: true, status: 'gepland' });
    expect(state.open).toEqual({ subdoelen: true, instrumenten: true, evaluaties: false, betrokkenen: true });
  });

  it('takes over the intake and reports how many fields it filled', () => {
    const { state, melding } = vulVoor({
      subjectType: 'PERSON', subjectId: ' 999993653 ', naam: 'Jan', titel: 'Plan Jan', notitie: 'Toelichting',
      startdatum: '2026-01-31T23:30:00Z', streefEinddatum: '2026-06-30', subdoelgroep: 'Jongeren',
      beginPositie: 'Trede 2', dienstverlening: 'Schulddienstverlening', hoofddoel: 'Werk vinden',
      doelen: [{ doelType: 'Cv maken', beschrijving: 'Cv af', status: 'gestart', voortgang: 50, toelichting: 'blijft' }],
      instrumenten: [{ product: 'TAAL', doel: 'Cv maken', titel: 'genegeerd' }],
      contactmomenten: [{ datum: '2026-02-10T09:00:00', evaluatieType: 'INTAKE', status: 'afgerond', notitie: 'Gesprek', kanaal: 'telefoon' }],
      betrokkenen: [{ naam: 'Piet', rol: 'Klantmanager', organisatie: 'Gemeente', email: 'p@example.org', procesbegeleider: true, telefoon: '06' }],
    }, REGISTERS, CONFIG);

    expect(state).toMatchObject({
      subjectType: 'PERSON', subjectId: '999993653', titel: 'Plan Jan', toelichting: 'Toelichting',
      startdatum: '2026-02-01', streefEinddatum: '2026-06-30', subdoelgroep: 'Jongeren', beginPositie: 'Trede 2',
      dienstverlening: 'Schulddienstverlening', hoofddoel: 'Werk vinden',
    });
    expect(state.subdoelen).toEqual([expect.objectContaining({
      doelType: 'Cv maken', beschrijving: 'Cv af', status: 'gestart', extra: { toelichting: 'blijft' },
    })]);
    expect(state.instrumenten).toEqual([expect.objectContaining({ product: 'TAAL', doel: 'Cv maken', extra: {} })]);
    expect(state.evaluaties).toEqual([expect.objectContaining({
      datum: '2026-02-10', evaluatieType: 'INTAKE', status: 'afgerond', notitie: 'Gesprek', auto: false, extra: { kanaal: 'telefoon' },
    })]);
    expect(state.betrokkenen).toEqual([expect.objectContaining({
      naam: 'Piet', procesbegeleider: true, extra: { telefoon: '06' },
    })]);
    expect(state.open).toEqual({ subdoelen: false, instrumenten: false, evaluaties: false, betrokkenen: false });
    expect(melding).toEqual({
      soort: 'info',
      tekst: 'Voorgevuld vanuit de intake van dit dossier (13 velden). Controleer en vul aan waar nodig.',
    });
  });

  it('does not take over values outside the registers and names them in a warning', () => {
    const { state, melding } = vulVoor({
      beginPositie: 'Trede 9', dienstverlening: 'Onbekend', hoofddoel: 'Cv maken',
      doelen: [{ doelType: 'Vliegen' }],
      instrumenten: [{ product: 'ONBEKEND' }],
    }, REGISTERS, CONFIG);

    expect(state.beginPositie).toBe('');
    expect(state.dienstverlening).toBe('Participatie');
    expect(state.hoofddoel).toBe('');
    expect(melding?.soort).toBe('warning');
    expect(melding?.tekst).toContain(
      'Let op: positie "Trede 9", dienstverlening "Onbekend", hoofddoel "Cv maken", subdoel "Vliegen", instrument "ONBEKEND" uit de intake staat niet in het register.');
  });

  it('names the plan after the subject without a titel', () => {
    expect(vulVoor({ naam: 'Jan' }, REGISTERS, CONFIG).state.titel).toBe('Plan – Jan');
    expect(vulVoor({}, REGISTERS, CONFIG).state.titel).toBe('');
  });

  it('keeps only the first procesbegeleider', () => {
    const { state } = vulVoor({
      betrokkenen: [{ naam: 'A', procesbegeleider: true }, { naam: 'B', procesbegeleider: true }, { naam: 'C', procesbegeleider: 'ja' }],
    }, REGISTERS, CONFIG);
    expect(state.betrokkenen.map(b => b.procesbegeleider)).toEqual([true, false, false]);
  });

  it('skips list entries that are not objects and an unknown subdoel status', () => {
    const { state } = vulVoor({
      doelen: [null, 'tekst', { doelType: 'Cv maken', status: 'onbekend' }] as unknown as Record<string, unknown>[],
      contactmomenten: [{ notitie: 'zonder datum' }],
    }, { ...REGISTERS }, { ...CONFIG, evaluatieTypen: ['INTAKE'] });
    expect(state.subdoelen).toHaveLength(1);
    expect(state.subdoelen[0].status).toBe('gepland');
    expect(state.evaluaties[0]).toMatchObject({ datum: '', evaluatieType: 'INTAKE' });
  });

  it('starts the first evaluation six weeks after a prefilled startdatum', () => {
    const { state } = vulVoor({ startdatum: '2026-03-01' }, REGISTERS, CONFIG);
    expect(state.evaluaties[0]).toMatchObject({ datum: '2026-04-12', auto: true });
  });
});

describe('maakRequest', () => {
  it('builds the intake request, trimming text and leaving out empty rows', () => {
    const { state } = vulVoor({
      subjectId: '999993653', titel: ' Plan ', hoofddoel: 'Werk vinden',
      doelen: [{ doelType: 'Cv maken', beschrijving: 'Cv', toelichting: 'blijft' }],
      betrokkenen: [{ naam: ' Piet ', email: ' p@example.org ' }, { naam: '  ' }],
    }, REGISTERS, CONFIG);
    const zonderDatum = doe(state, { type: 'toevoegen', sectie: 'evaluaties', evaluatieTypen: CONFIG.evaluatieTypen });

    const request = maakRequest(zonderDatum, 'doc-1', 'Jan', 'Actief');
    expect(request).toMatchObject({
      documentId: 'doc-1', subjectType: 'PERSON', subjectId: '999993653', subjectNaam: 'Jan', titel: 'Plan',
      notitie: '', dienstverlening: 'Participatie', hoofddoel: 'Werk vinden', subdoelgroep: null, weergaveStatus: 'Actief',
      beginPositie: null, startdatum: '2026-01-15', streefEinddatum: null,
      doelen: [{ toelichting: 'blijft', doelType: 'Cv maken', beschrijving: 'Cv', status: 'gepland' }],
      instrumenten: [],
    });
    expect(request.contactmomenten).toEqual([{ datum: '2026-02-26', evaluatieType: 'EVALUATION', status: 'gepland', notitie: '' }]);
    expect(request.betrokkenen).toEqual([
      { naam: 'Piet', rol: '', organisatie: '', email: 'p@example.org', procesbegeleider: false },
    ]);
  });
});
