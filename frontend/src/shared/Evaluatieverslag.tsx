import React, { useRef, useState } from 'react';
import { Button, InlineLoading, InlineNotification } from '@carbon/react';
import { Download, Renew } from '@carbon/react/icons';
import { DocumentAanvraag, DocumentInhoud, pdca } from './api';
import { formatDate, formatTime } from './dates';
import { downloadBestand, isOpen } from './documenten';
import { LaadFout } from './Meldingen';
import { PdfViewer } from './PdfViewer';
import { useResource } from './useResource';
import { useSubmit } from './useSubmit';

/**
 * The evaluatieverslag of a completed evaluation: while it is being made a
 * status line, when it failed the reason with "Opnieuw maken", when it is
 * ready the PDF inline and as a download. Without an aanvraag (an
 * evaluation from before the verslag existed) it offers to make one.
 */
export function Evaluatieverslag({ aanvraag, onMaak, inklapbaar = false }: {
  /** null: the evaluation has no verslag (yet). */
  aanvraag: DocumentAanvraag | null;
  /** Makes the verslag (again). */
  onMaak: () => Promise<unknown>;
  /** The PDF sits behind "Verslag bekijken" instead of being shown right away. */
  inklapbaar?: boolean;
}) {
  const { saving, fout, submit, reset } = useSubmit();
  const [zichtbaar, setZichtbaar] = useState(!inklapbaar);
  const [downloaden, setDownloaden] = useState(false);
  const [downloadFout, setDownloadFout] = useState<string | null>(null);
  /** The verslag as last read, so a download of a shown verslag needs no second read. */
  const geladen = useRef<{ id: string; inhoud: DocumentInhoud } | null>(null);
  const maak = () => submit(onMaak);

  const download = async (id: string) => {
    setDownloadFout(null);
    setDownloaden(true);
    try {
      const inhoud = geladen.current?.id === id ? geladen.current.inhoud : await pdca.documenten.inhoud(id);
      geladen.current = { id, inhoud };
      downloadBestand(inhoud.inhoud, inhoud.bestandsnaam || 'evaluatieverslag.pdf', inhoud.mediatype || 'application/pdf');
    } catch (e) {
      setDownloadFout(e instanceof Error ? e.message : 'Het verslag kon niet gedownload worden');
    } finally {
      setDownloaden(false);
    }
  };

  let inhoud: React.ReactNode;
  if (!aanvraag) {
    inhoud = <>
      <p className="pdca-empty-inline">Van deze evaluatie is nog geen verslag gemaakt.</p>
      <div className="pdca-verslag-acties">
        <Button size="sm" kind="tertiary" disabled={saving} onClick={maak}>
          {saving ? 'Verslag maken…' : 'Verslag maken'}
        </Button>
      </div>
    </>;
  } else if (isOpen(aanvraag)) {
    inhoud = <InlineLoading description="Evaluatieverslag wordt gemaakt…" />;
  } else if (aanvraag.status === 'MISLUKT') {
    // A Carbon InlineNotification throws when it contains interactive content, so the button sits below it.
    inhoud = <>
      <InlineNotification kind="error" lowContrast hideCloseButton title="Het verslag kon niet gemaakt worden"
        subtitle={aanvraag.foutmelding ?? undefined} />
      <div className="pdca-verslag-acties">
        <Button size="sm" kind="tertiary" renderIcon={Renew} disabled={saving} onClick={maak}>
          {saving ? 'Opnieuw maken…' : 'Opnieuw maken'}
        </Button>
      </div>
    </>;
  } else {
    const gemaaktOp = aanvraag.bijgewerktOp ?? aanvraag.aangevraagdOp;
    inhoud = <>
      <div className="pdca-verslag-acties">
        <span className="pdca-verslag-meta">Gemaakt op {formatDate(gemaaktOp)} {formatTime(gemaaktOp)}</span>
        {inklapbaar && (
          <Button size="sm" kind="ghost" onClick={() => setZichtbaar(z => !z)}>
            {zichtbaar ? 'Verslag verbergen' : 'Verslag bekijken'}
          </Button>
        )}
        <Button size="sm" kind="ghost" renderIcon={Download} disabled={downloaden} onClick={() => download(aanvraag.id)}>
          {downloaden ? 'Downloaden…' : 'Downloaden'}
        </Button>
      </div>
      {downloadFout && (
        <InlineNotification kind="error" lowContrast title="Downloaden mislukt" subtitle={downloadFout}
          onCloseButtonClick={() => setDownloadFout(null)} />
      )}
      {zichtbaar && <VerslagInhoud id={aanvraag.id} onGeladen={inhoud => { geladen.current = { id: aanvraag.id, inhoud }; }} />}
    </>;
  }

  return (
    <div className="pdca-section-block pdca-verslag">
      <div className="pdca-section-title"><span>Evaluatieverslag</span></div>
      {inhoud}
      {fout && (
        <InlineNotification kind="error" lowContrast title="Verslag maken mislukt" subtitle={fout}
          onCloseButtonClick={reset} />
      )}
    </div>
  );
}

/** The finished verslag, read when it is shown. */
function VerslagInhoud({ id, onGeladen }: { id: string; onGeladen: (inhoud: DocumentInhoud) => void }) {
  const inhoud = useResource(() => pdca.documenten.inhoud(id).then(data => { onGeladen(data); return data; }), [id]);
  if (inhoud.error) {
    return <LaadFout titel="Het verslag kon niet geladen worden" error={inhoud.error} onRetry={inhoud.reload} />;
  }
  if (!inhoud.data) return <InlineLoading description="Verslag laden…" />;
  return <PdfViewer data={inhoud.data.inhoud} titel={inhoud.data.bestandsnaam || 'Evaluatieverslag'} />;
}
