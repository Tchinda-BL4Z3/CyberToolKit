/**
 * Client du moteur : envoie le texte au Web Worker (F-51) qui exécute le
 * noyau Rust compilé en WebAssembly. Rien ne quitte la machine.
 */

export interface EngineFinding {
  id: string;
  type: string;
  labelFr: string;
  confidence: 'certain' | 'probable' | 'possible';
  confidenceReason: string;
  start: number;
  end: number;
  line: number;
  column: number;
  ruleId: string;
  category: 'credentials' | 'pii' | 'financial' | 'infra';
  previewMasked: string;
}

export interface EngineReport {
  rulesEvaluated: number;
  candidatesSeen: number;
  candidatesRejected: number;
  counts: {
    certain: number;
    probable: number;
    possible: number;
    total: number;
  };
  findings: EngineFinding[];
}

export interface ScanRequest {
  id: number;
  text?: string;
  file?: ArrayBuffer;
  minLevel?: string | null;
  categories: string[];
}

/** Résultat renvoyé au client : rapport et, pour un fichier bureautique,
 *  le format détecté et le texte extrait (pour le document masqué). */
export interface ScanOutcome {
  report: EngineReport;
  format?: string;
  extractedText?: string;
}

export type WorkerResponse =
  | { id: number; type: 'progress'; pct: number; label: string }
  | { id: number; type: 'done'; report: EngineReport; format?: string; extractedText?: string }
  | { id: number; type: 'error'; message: string };

export class ScanError extends Error {
  readonly code: string;
  constructor(code: string, message: string) {
    super(message);
    this.name = 'ScanError';
    this.code = code;
  }
}

interface Pending {
  resolve: (outcome: ScanOutcome) => void;
  reject: (error: Error) => void;
  onProgress: (pct: number, label: string) => void;
}

let worker: Worker | null = null;
let nextId = 1;
const pending = new Map<number, Pending>();

function getWorker(): Worker {
  if (worker) return worker;
  worker = new Worker(new URL('./worker.ts', import.meta.url), { type: 'module' });
  worker.onmessage = (event: MessageEvent<WorkerResponse>) => {
    const data = event.data;
    const entry = pending.get(data.id);
    if (!entry) return;
    if (data.type === 'progress') {
      entry.onProgress(data.pct, data.label);
      return;
    }
    pending.delete(data.id);
    if (data.type === 'done') {
      entry.resolve({
        report: data.report,
        format: data.format,
        extractedText: data.extractedText,
      });
      return;
    }
    entry.reject(new ScanError('ERR_ENGINE', data.message));
  };
  worker.onerror = (event) => {
    const message = event.message || 'le Web Worker a échoué';
    for (const entry of pending.values()) {
      entry.reject(new ScanError('ERR_WORKER', message));
    }
    pending.clear();
  };
  return worker;
}

/**
 * Lance une analyse sur un texte. `onProgress` ne reçoit que des étapes
 * réellement franchies : aucun pourcentage n'est inventé.
 */
export function scanText(
  text: string,
  options: { minLevel?: string | null; categories: string[] },
  onProgress: (pct: number, label: string) => void,
): Promise<ScanOutcome> {
  return send({ text }, options, onProgress);
}

/**
 * Lance une analyse sur les octets d'un fichier bureautique (F-28). Le
 * tampon est transféré au worker sans copie.
 */
export function scanFile(
  file: ArrayBuffer,
  options: { minLevel?: string | null; categories: string[] },
  onProgress: (pct: number, label: string) => void,
): Promise<ScanOutcome> {
  return send({ file }, options, onProgress, [file]);
}

function send(
  payload: { text?: string; file?: ArrayBuffer },
  options: { minLevel?: string | null; categories: string[] },
  onProgress: (pct: number, label: string) => void,
  transfer?: ArrayBuffer[],
): Promise<ScanOutcome> {
  const id = nextId++;
  const request: ScanRequest = {
    id,
    ...payload,
    minLevel: options.minLevel ?? null,
    categories: options.categories,
  };
  return new Promise<ScanOutcome>((resolve, reject) => {
    pending.set(id, { resolve, reject, onProgress });
    const worker = getWorker();
    if (transfer && transfer.length) {
      worker.postMessage(request, transfer);
    } else {
      worker.postMessage(request);
    }
  });
}

/** Libère le worker (appelé avant la fermeture de la page). */
export function disposeWorker(): void {
  worker?.terminate();
  worker = null;
  pending.clear();
}
