/**
 * pdf.js, loaded on first use so only a page that shows a PDF pays for it.
 *
 * The bundles run in a sandboxed iframe at an opaque origin, where a web
 * worker cannot be started from the bundle's URL. pdf.js therefore runs its
 * worker code on the main thread: importing the worker module sets
 * `globalThis.pdfjsWorker`, and with that set pdf.js sets up its in-thread
 * ("fake") worker without trying `new Worker`. The legacy build carries the
 * polyfills for browsers that lack the newest JavaScript features.
 */

type Pdfjs = typeof import('pdfjs-dist/legacy/build/pdf.mjs');

let laden: Promise<Pdfjs> | null = null;

/** pdf.js with its worker code in this thread; a failed load is tried again on the next call. */
export function laadPdfjs(): Promise<Pdfjs> {
  if (!laden) {
    laden = Promise.all([
      import('pdfjs-dist/legacy/build/pdf.mjs'),
      import('pdfjs-dist/legacy/build/pdf.worker.mjs'),
    ]).then(([pdfjs]) => pdfjs);
    laden.catch(() => { laden = null; });
  }
  return laden;
}

/**
 * Document options for the sandbox: nothing is fetched (no wasm decoders,
 * cMaps or standard font data; the generated PDFs embed their fonts) and
 * rendering stays on the main thread. Embedded fonts load as font faces.
 */
export const PDF_OPTIES = {
  useWasm: false,
  disableWorkerRendering: true,
  disableFontFace: false,
  enableXfa: false,
} as const;
