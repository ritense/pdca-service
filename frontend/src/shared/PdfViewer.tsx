import React, { RefObject, useEffect, useRef, useState } from 'react';
import { InlineLoading, InlineNotification } from '@carbon/react';
import type { PDFDocumentLoadingTask, PDFDocumentProxy, RenderTask } from 'pdfjs-dist/legacy/build/pdf.mjs';
import { base64NaarBytes } from './documenten';
import { foutmelding } from './http';
import { laadPdfjs, PDF_OPTIES } from './pdfjs';

/** Pause after the last resize before the pages are drawn again at the new width. */
const RESIZE_DEBOUNCE_MS = 150;

/**
 * A PDF shown inline: every page drawn on a canvas as wide as the viewer,
 * sharp on high-density screens and drawn again when the viewer changes
 * width. The sandboxed iframe cannot open the browser's PDF viewer, so this
 * is how the document is shown.
 */
export function PdfViewer({ data, titel }: {
  /** The PDF, base64-encoded. */
  data: string;
  /** What the document is, for screen readers. */
  titel: string;
}) {
  const container = useRef<HTMLDivElement>(null);
  const canvases = useRef<(HTMLCanvasElement | null)[]>([]);
  const [doc, setDoc] = useState<PDFDocumentProxy | null>(null);
  const [fout, setFout] = useState<unknown>(null);
  /** Every page has been drawn once; until then the (empty) canvases are hidden. */
  const [getekend, setGetekend] = useState(false);
  const breedte = useBreedte(container);

  useEffect(() => {
    let actief = true;
    let taak: PDFDocumentLoadingTask | null = null;
    setDoc(null);
    setFout(null);
    setGetekend(false);
    (async () => {
      const bytes = base64NaarBytes(data);
      const pdfjs = await laadPdfjs();
      if (!actief) return;
      taak = pdfjs.getDocument({ data: bytes, ...PDF_OPTIES });
      const geladen = await taak.promise;
      if (actief) setDoc(geladen);
    })().catch((e: unknown) => { if (actief) setFout(e); });
    return () => {
      actief = false;
      taak?.destroy();
    };
  }, [data]);

  useEffect(() => {
    if (!doc || breedte <= 0) return;
    let actief = true;
    const taken: RenderTask[] = [];
    (async () => {
      const dpr = window.devicePixelRatio || 1;
      for (let i = 0; i < doc.numPages; i++) {
        const pagina = await doc.getPage(i + 1);
        if (!actief) return;
        const viewport = pagina.getViewport({ scale: (breedte / pagina.getViewport({ scale: 1 }).width) * dpr });
        // Drawn off screen first, so a page keeps its old picture until the new one is ready.
        const buffer = document.createElement('canvas');
        buffer.width = Math.floor(viewport.width);
        buffer.height = Math.floor(viewport.height);
        const taak = pagina.render({ canvas: buffer, viewport });
        taken.push(taak);
        await taak.promise;
        const doel = canvases.current[i];
        if (!actief || !doel) return;
        doel.width = buffer.width;
        doel.height = buffer.height;
        doel.style.width = `${breedte}px`;
        doel.style.height = `${viewport.height / dpr}px`;
        doel.getContext('2d')?.drawImage(buffer, 0, 0);
      }
      if (actief) setGetekend(true);
    })().catch((e: unknown) => { if (actief) setFout(e); });
    // A cancelled render rejects; by then this run is no longer active, so that is not an error.
    return () => {
      actief = false;
      taken.forEach(t => t.cancel());
    };
  }, [doc, breedte]);

  return (
    <div ref={container} className="pdca-pdf" role="document" aria-label={titel} aria-busy={!getekend && !fout}>
      {fout != null
        ? <InlineNotification kind="error" lowContrast hideCloseButton title="Het document kan niet getoond worden"
            subtitle={foutmelding(fout)} />
        : !getekend && <InlineLoading description="Document laden…" />}
      {doc && fout == null && Array.from({ length: doc.numPages }, (_, i) => (
        <canvas key={i} className="pdca-pdf-pagina" hidden={!getekend} role="img"
          aria-label={`Pagina ${i + 1} van ${doc.numPages}`}
          ref={el => { canvases.current[i] = el; }} />
      ))}
    </div>
  );
}

/** The inner width of [ref]'s element in whole pixels; a resize is passed on once it settles. */
function useBreedte(ref: RefObject<HTMLElement>): number {
  const [breedte, setBreedte] = useState(0);
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const meet = () => setBreedte(Math.floor(el.clientWidth));
    meet();
    if (typeof ResizeObserver === 'undefined') return;
    let timer: number | undefined;
    const observer = new ResizeObserver(() => {
      window.clearTimeout(timer);
      timer = window.setTimeout(meet, RESIZE_DEBOUNCE_MS);
    });
    observer.observe(el);
    return () => {
      observer.disconnect();
      window.clearTimeout(timer);
    };
  }, [ref]);
  return breedte;
}
