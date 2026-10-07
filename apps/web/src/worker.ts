/**
 * Web Worker (F-51) : charge le noyau Rust en WebAssembly et l'exécute hors du
 * fil d'exécution de l'interface, pour que la page reste réactive.
 *
 * Le WASM n'a aucune importation réseau : il lit un tampon mémoire, écrit un
 * autre tampon, et rend la main.
 */

import type { EngineReport, ScanRequest, WorkerResponse } from './engineClient';

interface WasmExports {
  memory: WebAssembly.Memory;
  pss_alloc(len: number): number;
  pss_dealloc(ptr: number, len: number): void;
  pss_scan(ptr: number, len: number, outPtr: number, outLen: number): number;
  pss_scan_file(
    ptr: number,
    len: number,
    reqPtr: number,
    reqLen: number,
    outPtr: number,
    outLen: number,
  ): number;
}

const WASM_URL = new URL('./wasm/sensitive_wasm.wasm', import.meta.url);

let exportsPromise: Promise<WasmExports> | null = null;

function loadWasm(): Promise<WasmExports> {
  if (exportsPromise) return exportsPromise;
  exportsPromise = (async () => {
    const response = await fetch(WASM_URL);
    if (!response.ok) {
      throw new Error(
        `moteur introuvable (${response.status}) : lancez « npm run wasm » pour compiler le WebAssembly`,
      );
    }
    const bytes = await response.arrayBuffer();
    const result = await WebAssembly.instantiate(bytes, {});
    return result.instance.exports as unknown as WasmExports;
  })();
  return exportsPromise;
}

const encoder = new TextEncoder();
const decoder = new TextDecoder();

function post(message: WorkerResponse): void {
  (self as unknown as { postMessage(m: unknown): void }).postMessage(message);
}

function runScan(request: ScanRequest): void {
  (async () => {
    post({ id: request.id, type: 'progress', pct: 15, label: 'Chargement du moteur WebAssembly…' });
    const wasm = await loadWasm();
    post({ id: request.id, type: 'progress', pct: 45, label: 'Transfert du document vers le moteur…' });

    const inputPtr = request.file
      ? writeFile(wasm, request)
      : writeText(wasm, request);

    const outPtr = wasm.pss_alloc(8);
    if (!outPtr) throw new Error('allocation de la zone de résultat impossible');

    post({ id: request.id, type: 'progress', pct: 70, label: 'Extraction, validation et pondération…' });
    const status = inputPtr.reqPtr !== 0
      ? wasm.pss_scan_file(inputPtr.ptr, inputPtr.len, inputPtr.reqPtr, inputPtr.reqLen, outPtr, outPtr + 4)
      : wasm.pss_scan(inputPtr.ptr, inputPtr.len, outPtr, outPtr + 4);
    if (status !== 0) throw new Error('le moteur a refusé la requête');

    // La mémoire a pu grossir : on relit les tampons à chaque accès.
    const view = new DataView(wasm.memory.buffer);
    const resultPtr = view.getUint32(outPtr, true);
    const resultLen = view.getUint32(outPtr + 4, true);
    const json = decoder.decode(new Uint8Array(wasm.memory.buffer, resultPtr, resultLen));

    wasm.pss_dealloc(outPtr, 8);
    wasm.pss_dealloc(resultPtr, resultLen);
    wasm.pss_dealloc(inputPtr.ptr, inputPtr.len);
    if (inputPtr.reqPtr !== 0) wasm.pss_dealloc(inputPtr.reqPtr, inputPtr.reqLen);

    const parsed = JSON.parse(json) as unknown;
    if (parsed && typeof parsed === 'object' && 'error' in (parsed as Record<string, unknown>)) {
      const message = (parsed as { message?: string }).message || 'requête refusée par le moteur';
      throw new Error(message);
    }

    // pss_scan (texte) renvoie le rapport à la racine ; pss_scan_file (F-28)
    // renvoie `{ "format", "report", "extractedText" }`.
    const report = request.file
      ? (parsed as { report: EngineReport }).report
      : (parsed as EngineReport);
    const fileParsed =
      request.file && parsed && typeof parsed === 'object'
        ? (parsed as { format?: string; extractedText?: string })
        : null;

    post({ id: request.id, type: 'progress', pct: 100, label: 'Rapport généré.' });
    post({
      id: request.id,
      type: 'done',
      report,
      format: fileParsed?.format,
      extractedText: fileParsed?.extractedText,
    });
  })().catch((error: unknown) => {
    const message = error instanceof Error ? error.message : String(error);
    post({ id: request.id, type: 'error', message });
  });
}

interface InputBuffer {
  ptr: number;
  len: number;
  reqPtr: number;
  reqLen: number;
}

function writeText(wasm: WasmExports, request: ScanRequest): InputBuffer {
  const payload = encoder.encode(
    JSON.stringify({
      text: request.text ?? '',
      minLevel: request.minLevel ?? null,
      categories: request.categories,
    }),
  );
  const inputPtr = wasm.pss_alloc(payload.length);
  if (!inputPtr) throw new Error('allocation de la mémoire du moteur impossible');
  new Uint8Array(wasm.memory.buffer, inputPtr, payload.length).set(payload);
  return { ptr: inputPtr, len: payload.length, reqPtr: 0, reqLen: 0 };
}

function writeFile(wasm: WasmExports, request: ScanRequest): InputBuffer {
  const bytes = new Uint8Array(request.file as ArrayBuffer);
  const inputPtr = wasm.pss_alloc(bytes.length);
  if (!inputPtr) throw new Error('allocation de la mémoire du moteur impossible');
  new Uint8Array(wasm.memory.buffer, inputPtr, bytes.length).set(bytes);

  const options = encoder.encode(
    JSON.stringify({ minLevel: request.minLevel ?? null, categories: request.categories }),
  );
  const reqPtr = wasm.pss_alloc(options.length);
  if (!reqPtr) throw new Error('allocation de la mémoire du moteur impossible');
  new Uint8Array(wasm.memory.buffer, reqPtr, options.length).set(options);

  return { ptr: inputPtr, len: bytes.length, reqPtr, reqLen: options.length };
}

post({ id: 0, type: 'progress', pct: 1, label: 'worker chargé' } as WorkerResponse);

const ctx = self as unknown as {
  onmessage: ((event: MessageEvent<ScanRequest>) => void) | null;
};

ctx.onmessage = (event: MessageEvent<ScanRequest>) => {
  runScan(event.data);
};
