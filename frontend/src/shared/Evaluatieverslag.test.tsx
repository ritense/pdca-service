import React from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { pdca } from './api';
import { Evaluatieverslag } from './Evaluatieverslag';
import { HttpError } from './http';
import type { DocumentAanvraag, DocumentStatus } from './types';

vi.mock('./api', () => ({ pdca: { documenten: { inhoud: vi.fn() } } }));
vi.mock('./PdfViewer', () => ({
  PdfViewer: ({ data, titel }: { data: string; titel: string }) => <div data-testid="pdf" title={titel}>{data}</div>,
}));

const inhoudVan = vi.mocked(pdca.documenten.inhoud);

const aanvraag = (status: DocumentStatus, foutmelding: string | null = null): DocumentAanvraag => ({
  id: 'doc-1', planUuid: 'plan-1', soort: 'EVALUATIEVERSLAG', bronUuid: 'sessie-1', status,
  aangevraagdOp: '2026-11-11T10:00:00', bijgewerktOp: '2026-11-11T10:00:05', bestandsnaam: 'evaluatieverslag.pdf', foutmelding,
});

const gereed = () => inhoudVan.mockResolvedValue({ bestandsnaam: 'evaluatieverslag.pdf', mediatype: 'application/pdf', inhoud: 'JVBERi0=' });

describe('Evaluatieverslag', () => {
  it.each<DocumentStatus>(['AANGEVRAAGD', 'BEZIG'])('says the verslag is being made while %s', status => {
    render(<Evaluatieverslag aanvraag={aanvraag(status)} onMaak={vi.fn()} />);
    expect(screen.getByText('Evaluatieverslag wordt gemaakt…')).toBeTruthy();
    expect(screen.queryByRole('button')).toBeNull();
  });

  it('shows why it failed and makes it again', () => {
    const onMaak = vi.fn(() => Promise.resolve());
    render(<Evaluatieverslag aanvraag={aanvraag('MISLUKT', 'Template niet gevonden')} onMaak={onMaak} />);
    expect(screen.getByText('Template niet gevonden')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: /Opnieuw maken/ }));
    expect(onMaak).toHaveBeenCalledTimes(1);
  });

  it('offers to make a verslag for an evaluation without one, and says so when that fails', async () => {
    const onMaak = vi.fn(() => Promise.reject(new HttpError(422, 'Documentgeneratie staat uit', null)));
    render(<Evaluatieverslag aanvraag={null} onMaak={onMaak} inklapbaar />);
    fireEvent.click(screen.getByRole('button', { name: 'Verslag maken' }));
    expect(await screen.findByText('Documentgeneratie staat uit')).toBeTruthy();
  });

  it('shows a finished verslag right away', async () => {
    gereed();
    render(<Evaluatieverslag aanvraag={aanvraag('GEREED')} onMaak={vi.fn()} />);
    expect((await screen.findByTestId('pdf')).textContent).toBe('JVBERi0=');
    expect(inhoudVan).toHaveBeenCalledWith('doc-1');
  });

  it('shows a finished verslag behind a button when it is collapsible', async () => {
    gereed();
    render(<Evaluatieverslag aanvraag={aanvraag('GEREED')} onMaak={vi.fn()} inklapbaar />);
    expect(screen.queryByTestId('pdf')).toBeNull();
    expect(inhoudVan).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('button', { name: 'Verslag bekijken' }));
    expect(await screen.findByTestId('pdf')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'Verslag verbergen' }));
    expect(screen.queryByTestId('pdf')).toBeNull();
  });
});
