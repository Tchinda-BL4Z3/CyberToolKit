import './index.css';
import { scanFile, scanText, type EngineReport } from './engineClient';

/**
 * ProjectSecurityScanner - Moteur d'inspection 100% réel et local
 * UI Haute Fidélité, Optimisation Mobile & Tablette, Zéro Emoji, Zéro Simulation
 */

export type ConfidenceLevel = 'certain' | 'probable' | 'possible';

export interface Finding {
  id: string;
  type: string;
  labelFr: string;
  confidence: ConfidenceLevel;
  confidenceReason: string;
  startOffset: number;
  endOffset: number;
  previewMasked: string;
  line: number;
  column: number;
  ruleId: string;
  category: 'credentials' | 'pii' | 'financial' | 'infra';
}

export interface ScopeReport {
  fileName: string;
  fileSizeBytes: number;
  mimeType: string;
  charCount: number;
  lineCount: number;
  analysisDurationMs: number;
  encoding: string;
}

export interface ScannerSettings {
  categoryCredentials: boolean;
  categoryPii: boolean;
  categoryFinancial: boolean;
  categoryInfra: boolean;
  showPossibleByDefault: boolean;
}

const DEFAULT_SETTINGS: ScannerSettings = {
  categoryCredentials: true,
  categoryPii: true,
  categoryFinancial: true,
  categoryInfra: true,
  showPossibleByDefault: false,
};

// Bibliothèque vectorielle SVG
function getSvgIcon(name: string, size = 16, className = ''): string {
  const icons: Record<string, string> = {
    shield: `<svg width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="${className}"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/></svg>`,
    shieldCheck: `<svg width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="${className}"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/><path d="m9 12 2 2 4-4"/></svg>`,
    radar: `<svg width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="${className}"><circle cx="12" cy="12" r="10"/><circle cx="12" cy="12" r="6"/><circle cx="12" cy="12" r="2"/><line x1="12" y1="2" x2="12" y2="22"/><line x1="2" y1="12" x2="22" y2="12"/></svg>`,
    fileText: `<svg width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="${className}"><path d="M14.5 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7.5L14.5 2z"/><polyline points="14 2 14 8 20 8"/><line x1="16" y1="13" x2="8" y2="13"/><line x1="16" y1="17" x2="8" y2="17"/><line x1="10" y1="9" x2="8" y2="9"/></svg>`,
    clipboard: `<svg width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="${className}"><rect width="8" height="4" x="8" y="2" rx="1" ry="1"/><path d="M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2"/></svg>`,
    settings: `<svg width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="${className}"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"/></svg>`,
    trash: `<svg width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="${className}"><path d="M3 6h18"/><path d="M19 6v14c0 1-1 2-2 2H7c-1 0-2-1-2-2V6"/><path d="M8 6V4c0-1 1-2 2-2h4c1 0 2 1 2 2v2"/><line x1="10" y1="11" x2="10" y2="17"/><line x1="14" y1="11" x2="14" y2="17"/></svg>`,
    monitor: `<svg width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="${className}"><rect width="20" height="14" x="2" y="3" rx="2"/><line x1="8" y1="21" x2="16" y2="21"/><line x1="12" y1="17" x2="12" y2="21"/></svg>`,
    copy: `<svg width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="${className}"><rect width="14" height="14" x="8" y="8" rx="2" ry="2"/><path d="M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2"/></svg>`,
    download: `<svg width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="${className}"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>`,
    check: `<svg width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="${className}"><polyline points="20 6 9 17 4 12"/></svg>`,
    close: `<svg width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="${className}"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>`,
    arrowRight: `<svg width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="${className}"><line x1="5" y1="12" x2="19" y2="12"/><polyline points="12 5 19 12 12 19"/></svg>`,
    alertTriangle: `<svg width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="${className}"><path d="m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3Z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/></svg>`,
    refresh: `<svg width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="${className}"><path d="M3 12a9 9 0 0 1 15-6.7L21 8"/><path d="M21 3v5h-5"/><path d="M21 12a9 9 0 0 1-15 6.7L3 16"/><path d="M3 21v-5h5"/></svg>`,
    edit: `<svg width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="${className}"><path d="M17 3a2.85 2.83 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5Z"/><path d="m15 5 4 4"/></svg>`,
    layers: `<svg width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="${className}"><polygon points="12 2 2 7 12 12 22 7 12 2"/><polyline points="2 17 12 22 22 17"/><polyline points="2 12 12 17 22 12"/></svg>`,
    smartphone: `<svg width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="${className}"><rect width="14" height="20" x="5" y="2" rx="2" ry="2"/><line x1="12" y1="18" x2="12.01" y2="18"/></svg>`,
  };
  return icons[name] || '';
}

// Calcul d'entropie de Shannon
function esc(value: unknown): string {
  return String(value ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

// --- Garde-fous d'entrée (F-27 encodage, F-31 limites, F-32 non analysable) ---

const MAX_INPUT_BYTES = 50 * 1024 * 1024;

const UNSUPPORTED_EXTENSIONS = [
  '.pdf', '.doc', '.xls', '.ppt', '.odt', '.ods', '.odp',
  '.zip', '.tar', '.gz', '.tgz', '.7z', '.rar', '.bz2',
  '.png', '.jpg', '.jpeg', '.gif', '.webp', '.bmp', '.ico', '.tif', '.tiff',
  '.mp3', '.mp4', '.mov', '.avi', '.mkv', '.wav', '.flac', '.ogg',
  '.exe', '.dll', '.so', '.dylib', '.apk', '.jar', '.class', '.bin', '.pyc',
  '.woff', '.woff2', '.ttf', '.otf', '.eot',
];

/** Formats bureautiques lisibles par le moteur WASM (F-28) : le texte est
 *  extrait des XML de l'archive, jamais exécuté. */
const OFFICE_EXTENSIONS = ['.docx', '.xlsx', '.pptx'];

interface BinarySignature {
  name: string;
  bytes: number[];
}

const BINARY_SIGNATURES: BinarySignature[] = [
  { name: 'PDF', bytes: [0x25, 0x50, 0x44, 0x46, 0x2d] },
  { name: 'archive ZIP / Office', bytes: [0x50, 0x4b, 0x03, 0x04] },
  { name: 'image PNG', bytes: [0x89, 0x50, 0x4e, 0x47] },
  { name: 'image JPEG', bytes: [0xff, 0xd8, 0xff] },
  { name: 'image GIF', bytes: [0x47, 0x49, 0x46, 0x38] },
  { name: 'conteneur RIFF (WEBP/WAV/AVI)', bytes: [0x52, 0x49, 0x46, 0x46] },
  { name: 'archive GZIP', bytes: [0x1f, 0x8b] },
  { name: 'archive 7-Zip', bytes: [0x37, 0x7a, 0xbc, 0xaf] },
  { name: 'archive RAR', bytes: [0x52, 0x61, 0x72, 0x21] },
  { name: 'binaire EXE/PE', bytes: [0x4d, 0x5a] },
  { name: 'binaire ELF', bytes: [0x7f, 0x45, 0x4c, 0x46] },
  { name: 'archive JAR/class', bytes: [0xca, 0xfe, 0xba, 0xbe] },
  { name: 'flux OGG', bytes: [0x4f, 0x67, 0x67, 0x53] },
  { name: 'audio MP3', bytes: [0x49, 0x44, 0x33] },
  { name: 'vidéo MP4', bytes: [0x66, 0x74, 0x79, 0x70] },
];

function detectBinarySignature(b: Uint8Array): string | null {
  for (const sig of BINARY_SIGNATURES) {
    if (b.length < sig.bytes.length) continue;
    if (sig.bytes.every((v, i) => b[i] === v)) return sig.name;
  }
  return null;
}

function extensionOf(fileName: string): string {
  const idx = fileName.lastIndexOf('.');
  return idx === -1 ? '' : fileName.slice(idx).toLowerCase();
}

/**
 * F-54 : l'accès disque persistant n'existe que dans Chromium (Firefox a une
 * position normative négative, Safari s'y oppose). L'UI doit l'annoncer.
 */
function isChromiumBrowser(): boolean {
  return typeof window !== 'undefined' && 'showOpenFilePicker' in window;
}

function navigatorLabel(): string {
  const ua = typeof navigator === 'undefined' ? '' : navigator.userAgent;
  if (/firefox/i.test(ua)) return 'Firefox';
  if (/edg\//i.test(ua)) return 'Edge';
  if (/chrome|chromium|crios/i.test(ua)) return 'Chrome';
  if (/safari/i.test(ua) && !/chrome/i.test(ua)) return 'Safari';
  return 'ce navigateur';
}

export interface DecodedInput {
  text: string;
  encoding: string;
  byteLength: number;
}

/**
 * Décodage explicite de l'encodage (F-27) : UTF-8 strict en premier, repli
 * UTF-16 puis Windows-1252. Un détecteur qui rate le Latin-1 laisse passer des
 * fuites silencieusement.
 */
export function decodeBytes(buf: ArrayBuffer): DecodedInput {
  const b = new Uint8Array(buf);

  if (b.length >= 3 && b[0] === 0xef && b[1] === 0xbb && b[2] === 0xbf) {
    return { text: new TextDecoder('utf-8').decode(b.subarray(3)), encoding: 'UTF-8 (BOM)', byteLength: b.length };
  }
  if (b.length >= 2 && b[0] === 0xff && b[1] === 0xfe) {
    return { text: new TextDecoder('utf-16le').decode(b.subarray(2)), encoding: 'UTF-16 LE', byteLength: b.length };
  }
  if (b.length >= 2 && b[0] === 0xfe && b[1] === 0xff) {
    return { text: new TextDecoder('utf-16be').decode(b.subarray(2)), encoding: 'UTF-16 BE', byteLength: b.length };
  }

  if (b.length >= 4) {
    const sample = Math.min(b.length, 8192);
    let evenNull = 0;
    let oddNull = 0;
    for (let i = 0; i < sample; i++) {
      if (b[i] === 0) {
        if (i % 2 === 0) evenNull++;
        else oddNull++;
      }
    }
    const half = Math.floor(sample / 2);
    if (oddNull > half * 0.3 && evenNull < half * 0.05) {
      return { text: new TextDecoder('utf-16le').decode(b), encoding: 'UTF-16 LE (déduit)', byteLength: b.length };
    }
    if (evenNull > half * 0.3 && oddNull < half * 0.05) {
      return { text: new TextDecoder('utf-16be').decode(b), encoding: 'UTF-16 BE (déduit)', byteLength: b.length };
    }
  }

  try {
    return { text: new TextDecoder('utf-8', { fatal: true }).decode(b), encoding: 'UTF-8', byteLength: b.length };
  } catch {
    return { text: new TextDecoder('windows-1252').decode(b), encoding: 'Windows-1252 (déduit)', byteLength: b.length };
  }
}

interface ScanProgressState {
  percentage: number;
  currentStepLabel: string;
  stepIndex: number;
  linesAnalyzed: number;
  totalLines: number;
}

class ScannerApp {
  private currentScreen: 'home' | 'scanning' | 'report' = 'home';
  private currentDrawer: 'none' | 'detail' | 'settings' | 'desktopModal' | 'errorModal' = 'none';
  private selectedFinding: Finding | null = null;
  private currentError: { title: string; message: string; action: string; code: string } | null = null;
  private settings: ScannerSettings = { ...DEFAULT_SETTINGS };

  private currentInputText: string = '';
  private currentFileName: string = '';
  private currentEncoding: string = 'UTF-8';
  private currentByteSize: number = 0;
  private currentFindings: Finding[] = [];
  private currentScope: ScopeReport | null = null;
  private showPossibleFindings: boolean = false;
  private previewMode: 'redacted' | 'raw' = 'redacted';
  private wasPurged: boolean = false;

  // Vue active sur mobile pour l'écran de rapport : 'findings' ou 'preview'
  private mobileActiveTab: 'findings' | 'preview' = 'findings';

  private scanState: ScanProgressState = {
    percentage: 0,
    currentStepLabel: 'Préparation...',
    stepIndex: 1,
    linesAnalyzed: 0,
    totalLines: 0,
  };
  private sessionToken: number = 0;

  private root: HTMLElement;

  constructor(rootEl: HTMLElement) {
    this.root = rootEl;
    this.setupBackgroundLayers();
    window.addEventListener('resize', () => {
      this.render();
    });
    this.render();
  }

  private setupBackgroundLayers() {
    if (!document.querySelector('.pss-cyber-bg')) {
      const bg = document.createElement('div');
      bg.className = 'pss-cyber-bg';
      document.body.prepend(bg);

      const grid = document.createElement('div');
      grid.className = 'pss-grid-overlay';
      document.body.prepend(grid);
    }
  }

  /**
   * Lecture d'un fichier soumis (glisser-déposer ou sélecteur).
   * L'analyse est refusée explicitement pour tout format binaire : produire un
   * rapport « aucun résultat » sur un document non lu serait un faux négatif.
   */
  private loadFile(file: File) {
    const ext = extensionOf(file.name);
    if (UNSUPPORTED_EXTENSIONS.includes(ext)) {
      this.currentError = {
        title: `Format « ${ext} » non pris en charge`,
        message: `Le fichier « ${file.name} » est un format binaire ou conteneur. Il n'a pas été ouvert, donc il n'a pas été analysé.`,
        action: 'La version web ne lit que le texte (code source, .env, .csv, .json, .md, .yaml). Convertissez le document avant de le soumettre, et ne le considérez pas comme sûr.',
        code: 'ERR_UNSUPPORTED_FORMAT',
      };
      this.currentDrawer = 'errorModal';
      this.render();
      return;
    }

    if (file.size > MAX_INPUT_BYTES) {
      this.currentError = {
        title: 'Document trop volumineux',
        message: `Taille détectée : ${(file.size / (1024 * 1024)).toFixed(1)} Mio, pour un plafond de 50 Mio. Le fichier n'a pas été lu.`,
        action: 'Découpez le document, ou soumettez uniquement la partie qui vous intéresse.',
        code: 'ERR_INPUT_TOO_LARGE',
      };
      this.currentDrawer = 'errorModal';
      this.render();
      return;
    }

    const reader = new FileReader();
    reader.onerror = () => {
      this.currentError = {
        title: 'Lecture impossible',
        message: 'Le navigateur n\'a pas pu lire ce fichier (permission ou erreur système).',
        action: 'Réessayez, ou collez le contenu dans la zone de texte directe.',
        code: 'ERR_READ_FAILED',
      };
      this.currentDrawer = 'errorModal';
      this.render();
    };
    reader.onload = (ev) => {
      const buf = ev.target?.result as ArrayBuffer;
      if (!(buf instanceof ArrayBuffer)) return;

      const signature = detectBinarySignature(new Uint8Array(buf));
      if (signature && !OFFICE_EXTENSIONS.includes(ext)) {
        this.currentError = {
          title: `Conteneur ${signature} non pris en charge`,
          message: `Signature binaire détectée en tête de fichier : ${signature}. Le contenu n'a pas été lu, donc il n'a pas été analysé.`,
          action: 'Ne concluez pas que ce fichier est propre : il n\'a simplement pas été ouvert. Convertissez-le en .txt ou .csv puis relancez le scan.',
          code: 'ERR_UNSUPPORTED_BINARY',
        };
        this.currentDrawer = 'errorModal';
        this.render();
        return;
      }

      if (OFFICE_EXTENSIONS.includes(ext)) {
        // Word, Excel, PowerPoint : le moteur Rust extrait le texte des XML
        // (F-28) à l'intérieur du Web Worker.
        this.startInspectionFile(buf, file.name, file.type || 'application/zip');
        return;
      }

      const decoded = decodeBytes(buf);
      this.startInspection(decoded.text, file.name, file.type || 'text/plain', decoded.encoding, decoded.byteLength);
    };
    reader.readAsArrayBuffer(file);
  }

  public async startInspection(
    text: string,
    fileName = 'saisie_utilisateur.txt',
    mime = 'text/plain',
    encoding = 'UTF-8',
    byteLength?: number,
  ) {
    const byteSize = byteLength ?? new Blob([text]).size;

    if (byteSize > MAX_INPUT_BYTES) {
      this.currentError = {
        title: 'Document trop volumineux',
        message: `Taille détectée : ${(byteSize / (1024 * 1024)).toFixed(1)} Mio, pour un plafond de 50 Mio. Le fichier n'a pas été analysé.`,
        action: 'Découpez le document, ou soumettez uniquement la partie qui vous intéresse.',
        code: 'ERR_INPUT_TOO_LARGE',
      };
      this.currentDrawer = 'errorModal';
      this.render();
      return;
    }

    if (!text || text.trim().length === 0) {
      this.currentError = {
        title: 'Document vide',
        message: 'Le contenu soumis ne contient aucun caractère analysable.',
        action: 'Fournissez un document ou collez du texte contenant des données réelles.',
        code: 'ERR_EMPTY_PAYLOAD',
      };
      this.currentDrawer = 'errorModal';
      this.render();
      return;
    }

    if (text.includes('\0')) {
      this.currentError = {
        title: 'Conteneur binaire non lu',
        message: 'Le décodage a laissé des octets nuls : ce fichier n\'est pas du texte exploitable. Il n\'a pas été analysé.',
        action: 'Ne concluez pas qu\'il est propre : il n\'a simplement pas été lu. Convertissez-le en .txt, .csv ou .json puis relancez. Aucune lecture de PDF ni d\'image (pas d\'OCR) dans la version web.',
        code: 'ERR_BINARY_CONTAINER',
      };
      this.currentDrawer = 'errorModal';
      this.render();
      return;
    }

    this.wasPurged = false;
    this.currentInputText = text;
    this.currentFileName = fileName;
    this.currentEncoding = encoding;
    this.currentByteSize = byteSize;
    const lines = text.split('\n');
    const totalLines = lines.length;

    this.currentScreen = 'scanning';
    this.scanState = {
      percentage: 5,
      currentStepLabel: 'Préparation...',
      stepIndex: 1,
      linesAnalyzed: 0,
      totalLines,
    };
    this.render();

    const startTime = performance.now();
    const token = ++this.sessionToken;
    const categories: string[] = [];
    if (this.settings.categoryCredentials) categories.push('credentials');
    if (this.settings.categoryPii) categories.push('pii');
    if (this.settings.categoryFinancial) categories.push('financial');
    if (this.settings.categoryInfra) categories.push('infra');

    // Le pourcentage ne reflète que des étapes réellement franchies :
    // aucune valeur n'est inventée pour « faire joli ».
    const onProgress = (pct: number, label: string) => {
      if (token !== this.sessionToken) return;
      this.scanState = {
        percentage: pct,
        currentStepLabel: label,
        stepIndex: pct < 30 ? 1 : pct < 60 ? 2 : pct < 95 ? 3 : 4,
        linesAnalyzed: pct >= 100 ? totalLines : 0,
        totalLines,
      };
      this.render();
    };

    try {
      const outcome = await scanText(text, { categories }, onProgress);
      if (token !== this.sessionToken) return;
      this.finishScan(outcome.report, text, fileName, mime, encoding, byteSize, totalLines, startTime, token);
    } catch (error) {
      if (token !== this.sessionToken) return;
      this.currentError = {
        title: "Échec de l'analyse",
        message: error instanceof Error ? error.message : String(error),
        action: "Le document n'a pas été analysé. Réessayez, ou utilisez la CLI « scanner » qui ne dépend pas du navigateur.",
        code: 'ERR_ENGINE_UNAVAILABLE',
      };
      this.currentDrawer = 'errorModal';
      this.currentScreen = 'home';
      this.render();
    }
  }

  /**
   * Analyse les octets d'un fichier bureautique (F-28) : le moteur Rust extrait
   * le texte des XML de l'archive dans le worker, puis le scan habituel.
   */
  private async startInspectionFile(bytes: ArrayBuffer, fileName: string, mime: string) {
    const byteSize = bytes.byteLength;
    if (byteSize > MAX_INPUT_BYTES) {
      this.currentError = {
        title: 'Document trop volumineux',
        message: 'Taille détectée : ' + (byteSize / (1024 * 1024)).toFixed(1) + ' Mio, pour un plafond de 50 Mio. Le fichier n\'a pas été analysé.',
        action: 'Découpez le document, ou soumettez uniquement la partie qui vous intéresse.',
        code: 'ERR_INPUT_TOO_LARGE',
      };
      this.currentDrawer = 'errorModal';
      this.render();
      return;
    }

    this.wasPurged = false;
    this.currentScreen = 'scanning';
    this.scanState = {
      percentage: 5,
      currentStepLabel: 'Préparation...',
      stepIndex: 1,
      linesAnalyzed: 0,
      totalLines: -1,
    };
    this.render();

    const startTime = performance.now();
    const token = ++this.sessionToken;
    const categories: string[] = [];
    if (this.settings.categoryCredentials) categories.push('credentials');
    if (this.settings.categoryPii) categories.push('pii');
    if (this.settings.categoryFinancial) categories.push('financial');
    if (this.settings.categoryInfra) categories.push('infra');

    const onProgress = (pct: number, label: string) => {
      if (token !== this.sessionToken) return;
      this.scanState = { percentage: pct, currentStepLabel: label, stepIndex: pct < 30 ? 1 : pct < 60 ? 2 : pct < 95 ? 3 : 4, linesAnalyzed: pct >= 100 ? -1 : 0, totalLines: -1 };
      this.render();
    };

    try {
      const outcome = await scanFile(bytes, { categories }, onProgress);
      if (token !== this.sessionToken) return;
      const text = outcome.extractedText ?? '';
      const encoding = outcome.format ?? 'bureautique';
      this.currentInputText = text;
      this.currentFileName = fileName;
      this.currentEncoding = encoding;
      this.currentByteSize = byteSize;
      const totalLines = text.length === 0 ? 0 : text.split('\n').length;
      this.finishScan(outcome.report, text, fileName, mime, encoding, byteSize, totalLines, startTime, token);
    } catch (error) {
      if (token !== this.sessionToken) return;
      this.currentError = {
        title: "Échec de l'analyse du document",
        message: error instanceof Error ? error.message : String(error),
        action: "Document non lu. Convertissez-le en .txt/.csv si le format n'est pas pris en charge, ou utilisez la CLI « scanner ».",
        code: 'ERR_ENGINE_UNAVAILABLE',
      };
      this.currentDrawer = 'errorModal';
      this.currentScreen = 'home';
      this.render();
    }
  }

  private finishScan(
    report: EngineReport,
    text: string,
    fileName: string,
    mime: string,
    encoding: string,
    byteSize: number,
    totalLines: number,
    startTime: number,
    token: number,
  ) {
    const findings: Finding[] = report.findings.map((f) => ({
      id: f.id,
      type: f.type,
      labelFr: f.labelFr,
      confidence: f.confidence,
      confidenceReason: f.confidenceReason,
      startOffset: f.start,
      endOffset: f.end,
      previewMasked: f.previewMasked,
      line: f.line,
      column: f.column,
      ruleId: f.ruleId,
      category: f.category,
    }));

    this.currentFindings = findings;
    this.currentScope = {
      fileName,
      fileSizeBytes: byteSize,
      mimeType: mime,
      charCount: text.length,
      lineCount: totalLines,
      analysisDurationMs: Math.max(1, Math.round(performance.now() - startTime)),
      encoding,
    };

    this.scanState.percentage = 100;
    this.scanState.currentStepLabel = 'Rapport généré.';
    this.scanState.stepIndex = 4;
    this.render();

    setTimeout(() => {
      if (token !== this.sessionToken) return;
      this.currentScreen = 'report';
      this.previewMode = 'redacted';
      this.mobileActiveTab = findings.length > 0 ? 'findings' : 'preview';
      this.render();
    }, 160);
  }

  public purgeSession() {
    this.sessionToken += 1;
    this.currentInputText = '';
    this.currentFileName = '';
    this.currentEncoding = 'UTF-8';
    this.currentByteSize = 0;
    this.currentFindings = [];
    this.currentScope = null;
    this.selectedFinding = null;
    this.wasPurged = true;
    this.currentScreen = 'home';
    this.currentDrawer = 'none';
    this.render();
  }

  public getRedactedContent(): string {
    const text = this.currentInputText;
    if (!text) return '';

    // La copie et l'export masquent TOUS les findings, y compris ceux que
    // l'interface masque à l'affichage (« Possible » désactivé par défaut).
    // Le recadrage porte sur les offsets absolus : aucune chaîne sensible n'est
    // transportée dans le rapport.
    const candidates = this.currentFindings
      .filter((f) => f.endOffset > f.startOffset && f.endOffset <= text.length)
      .sort((a, b) => a.startOffset - b.startOffset || b.endOffset - a.endOffset);

    const kept: typeof candidates = [];
    let cursor = -1;
    for (const f of candidates) {
      if (f.startOffset < cursor) continue;
      kept.push(f);
      cursor = f.endOffset;
    }

    let result = text;
    for (const f of kept.sort((a, b) => b.startOffset - a.startOffset)) {
      result = result.slice(0, f.startOffset) + `[REDACTED:${f.type}]` + result.slice(f.endOffset);
    }
    return result;
  }

  public getVisibleFindings(): Finding[] {
    return this.currentFindings.filter(f => {
      if (f.confidence === 'possible' && !this.showPossibleFindings) return false;
      return true;
    });
  }

  public render() {
    this.root.innerHTML = '';

    const container = document.createElement('div');
    container.className = 'min-h-screen flex flex-col w-full text-slate-100';

    container.appendChild(this.renderHeader());

    const main = document.createElement('main');
    main.className = 'flex-1 flex flex-col max-w-5xl w-full mx-auto p-3 sm:p-6';

    if (this.currentScreen === 'home') {
      main.appendChild(this.renderHomeScreen());
    } else if (this.currentScreen === 'scanning') {
      main.appendChild(this.renderScanningScreen());
    } else if (this.currentScreen === 'report') {
      main.appendChild(this.renderReportScreen());
    }

    container.appendChild(main);

    if (this.currentDrawer === 'detail' && this.selectedFinding) {
      container.appendChild(this.renderDetailDrawer());
    } else if (this.currentDrawer === 'settings') {
      container.appendChild(this.renderSettingsDrawer());
    } else if (this.currentDrawer === 'desktopModal') {
      container.appendChild(this.renderDesktopModal());
    } else if (this.currentDrawer === 'errorModal' && this.currentError) {
      container.appendChild(this.renderErrorModal());
    }

    this.root.appendChild(container);
  }

  // Header optimisé mobile
  private renderHeader(): HTMLElement {
    const header = document.createElement('header');
    header.className = 'border-b border-[#1c2637] bg-[#0c111a]/90 backdrop-blur-md sticky top-0 z-30 px-3 sm:px-6 py-2.5 flex items-center justify-between gap-2';

    const brand = document.createElement('div');
    brand.className = 'flex items-center gap-2 sm:gap-3 min-w-0';
    brand.innerHTML = `
      <div class="w-8 h-8 shrink-0 rounded-lg bg-blue-600/15 border border-blue-500/30 flex items-center justify-center text-blue-400">
        ${getSvgIcon('shield', 18)}
      </div>
      <div class="flex items-center gap-1.5 sm:gap-2 min-w-0">
        <span class="font-semibold text-xs sm:text-sm tracking-tight text-white font-mono truncate max-w-[120px] sm:max-w-none">ProjectSecurityScanner</span>
        <span class="shrink-0 inline-flex items-center gap-1 px-1.5 sm:px-2 py-0.5 rounded text-[10px] font-mono bg-emerald-500/10 border border-emerald-500/30 text-emerald-400">
          <span class="w-1.5 h-1.5 rounded-full bg-emerald-400"></span>
          <span class="hidden sm:inline">Mode </span>Local
        </span>
      </div>
    `;

    const actions = document.createElement('div');
    actions.className = 'flex items-center gap-1.5 sm:gap-2 shrink-0';

    const deskBtn = document.createElement('button');
    deskBtn.className = 'pss-btn pss-btn-secondary px-2 sm:px-2.5 py-1.5 text-xs';
    deskBtn.title = 'Plateformes prises en charge et feuille de route';
    deskBtn.innerHTML = `${getSvgIcon(window.innerWidth < 768 ? 'smartphone' : 'monitor', 14)} <span class="hidden sm:inline">Plateformes</span>`;
    deskBtn.onclick = () => {
      this.currentDrawer = 'desktopModal';
      this.render();
    };

    const setBtn = document.createElement('button');
    setBtn.className = 'pss-btn pss-btn-secondary px-2 sm:px-2.5 py-1.5 text-xs';
    setBtn.title = 'Règles et filtres';
    setBtn.innerHTML = `${getSvgIcon('settings', 14)} <span class="hidden sm:inline">Règles</span>`;
    setBtn.onclick = () => {
      this.currentDrawer = 'settings';
      this.render();
    };

    const purgeBtn = document.createElement('button');
    purgeBtn.className = 'pss-btn pss-btn-danger px-2 sm:px-2.5 py-1.5 text-xs';
    purgeBtn.title = 'Purger la session';
    purgeBtn.innerHTML = `${getSvgIcon('trash', 14)} <span class="hidden sm:inline">Purger</span>`;
    purgeBtn.onclick = () => this.purgeSession();

    actions.appendChild(deskBtn);
    actions.appendChild(setBtn);
    actions.appendChild(purgeBtn);

    header.appendChild(brand);
    header.appendChild(actions);
    return header;
  }

  // Écran d'accueil responsive
  private renderHomeScreen(): HTMLElement {
    const wrap = document.createElement('div');
    wrap.className = 'flex flex-col gap-4 my-auto py-2 sm:py-4 max-w-2xl mx-auto w-full';

    if (this.wasPurged) {
      const purgeNotice = document.createElement('div');
      purgeNotice.className = 'p-3 rounded-lg border border-emerald-500/30 bg-emerald-950/20 text-emerald-300 text-xs flex items-center gap-2';
      purgeNotice.innerHTML = `
        <span class="text-emerald-400 shrink-0">${getSvgIcon('check', 16)}</span>
        <span>Mémoire vive réinitialisée. Aucune trace conservée.</span>
      `;
      wrap.appendChild(purgeNotice);
    }

    // Zone de dépôt adaptée tactile
    const dropzone = document.createElement('div');
    dropzone.className = 'pss-glass rounded-xl p-5 sm:p-8 text-center transition-all cursor-pointer flex flex-col items-center justify-center gap-3 hover:border-blue-500/50 group relative overflow-hidden';

    const chromiumNote = isChromiumBrowser()
      ? ''
      : `<div class="mt-1 w-full max-w-md rounded border border-amber-500/30 bg-amber-950/30 px-3 py-2 text-[11px] leading-relaxed text-amber-200/90">
           <span class="font-mono text-[10px] uppercase text-amber-400">Accès disque — ${esc(navigatorLabel())}</span><br>
           Ce navigateur n'autorise pas l'accès disque persistant ni à un dossier : seuls le glisser-déposer,
           le sélecteur de fichier et le presse-papiers sont utilisables. Aucune surveillance de dossier ici —
           elle existera dans la version desktop (Tauri), pas sur le web.
         </div>`;

    dropzone.innerHTML = `
      <div class="w-12 h-12 sm:w-14 sm:h-14 rounded-2xl bg-slate-900/80 border border-slate-700/60 flex items-center justify-center text-slate-300 group-hover:text-blue-400 group-hover:border-blue-500/40 transition-colors shadow-inner">
        ${getSvgIcon('fileText', 24)}
      </div>
      <div>
        <h2 class="text-sm sm:text-base font-semibold text-white tracking-tight">Déposer un document pour analyse locale</h2>
        <p class="text-xs text-slate-400 mt-0.5">
          Fichiers texte, code source, CSV, JSON ou .env
        </p>
      </div>
      <div class="flex flex-col sm:flex-row items-stretch sm:items-center gap-2 mt-1 w-full sm:w-auto">
        <button id="btn-select-file" class="pss-btn pss-btn-primary px-3.5 py-2 text-xs w-full sm:w-auto">
          ${getSvgIcon('fileText', 14)} Parcourir le disque
        </button>
        <button id="btn-paste-clip" class="pss-btn pss-btn-secondary px-3.5 py-2 text-xs w-full sm:w-auto">
          ${getSvgIcon('clipboard', 14)} Coller le presse-papiers
        </button>
      </div>
      <input type="file" id="input-file-hidden" class="hidden" accept=".txt,.log,.env,.ini,.cfg,.conf,.csv,.tsv,.json,.md,.yaml,.yml,.toml,.xml,.sql,.properties,.sh,.py,.js,.ts,.tsx,.jsx,.rs,.go,.java,.c,.h,.cpp,.rb,.php,.kt,.swift,.docx,.xlsx,.pptx" />
      ${chromiumNote}
    `;

    dropzone.ondragover = (e) => {
      e.preventDefault();
      dropzone.classList.add('border-blue-500', 'bg-blue-950/30');
    };
    dropzone.ondragleave = () => {
      dropzone.classList.remove('border-blue-500', 'bg-blue-950/30');
    };
    dropzone.ondrop = (e) => {
      e.preventDefault();
      dropzone.classList.remove('border-blue-500', 'bg-blue-950/30');
      if (e.dataTransfer && e.dataTransfer.files.length > 0) {
        this.loadFile(e.dataTransfer.files[0]);
      }
    };

    const selectBtn = dropzone.querySelector('#btn-select-file') as HTMLButtonElement;
    const fileInput = dropzone.querySelector('#input-file-hidden') as HTMLInputElement;
    selectBtn.onclick = (e) => {
      e.stopPropagation();
      fileInput.click();
    };
    fileInput.onchange = () => {
      if (fileInput.files && fileInput.files.length > 0) {
        this.loadFile(fileInput.files[0]);
        fileInput.value = '';
      }
    };

    const pasteBtn = dropzone.querySelector('#btn-paste-clip') as HTMLButtonElement;
    pasteBtn.onclick = (e) => {
      e.stopPropagation();
      navigator.clipboard.readText().then((txt) => {
        if (!txt || !txt.trim()) {
          alert('Le presse-papiers est vide.');
          return;
        }
        this.startInspection(txt, 'presse_papiers.txt', 'text/plain');
      }).catch(() => {
        const manual = prompt('Coller le texte :');
        if (manual && manual.trim()) this.startInspection(manual, 'saisie_manuelle.txt', 'text/plain');
      });
    };

    wrap.appendChild(dropzone);

    // Zone de texte direct
    const directBox = document.createElement('div');
    directBox.className = 'pss-glass rounded-xl p-3.5 sm:p-4 flex flex-col gap-2.5';
    directBox.innerHTML = `
      <div class="flex items-center justify-between text-xs font-mono text-slate-300">
        <span class="flex items-center gap-1.5">
          ${getSvgIcon('edit', 14)} Ou coller directement le texte :
        </span>
      </div>
      <textarea id="direct-text-area" rows="3" placeholder="Collez ici des variables d'environnement, clés d'API, code ou données à inspecter..." class="w-full bg-[#090d14] border border-[#1c2637] focus:border-blue-500 rounded-lg p-2.5 sm:p-3 text-xs font-mono text-slate-200 outline-none resize-y"></textarea>
      <div class="flex justify-end">
        <button id="btn-inspect-direct" class="pss-btn pss-btn-primary px-4 py-2 text-xs w-full sm:w-auto">
          ${getSvgIcon('radar', 14)} Lancer le scan
        </button>
      </div>
    `;

    directBox.querySelector('#btn-inspect-direct')?.addEventListener('click', () => {
      const textarea = directBox.querySelector('#direct-text-area') as HTMLTextAreaElement;
      if (textarea && textarea.value.trim().length > 0) {
        this.startInspection(textarea.value, 'saisie_directe.txt', 'text/plain');
      } else {
        alert('Veuillez saisir ou coller du texte dans la zone.');
      }
    });

    wrap.appendChild(directBox);
    return wrap;
  }

  // Écran d'animation de scan responsive
  private renderScanningScreen(): HTMLElement {
    const wrap = document.createElement('div');
    wrap.className = 'my-auto py-4 sm:py-8 max-w-xl mx-auto w-full flex flex-col items-center justify-center gap-4 sm:gap-6 px-1';

    const card = document.createElement('div');
    card.className = 'pss-glass rounded-2xl p-5 sm:p-8 w-full border border-blue-500/30 flex flex-col items-center gap-5 sm:gap-6 relative overflow-hidden shadow-2xl';

    const laser = document.createElement('div');
    laser.className = 'pss-scan-laser';
    card.appendChild(laser);

    const radarContainer = document.createElement('div');
    radarContainer.className = 'relative w-24 h-24 sm:w-28 sm:h-28 flex items-center justify-center';
    radarContainer.innerHTML = `
      <div class="absolute inset-0 rounded-full border border-blue-500/40 pss-radar-spin flex items-center justify-center">
        <div class="w-full h-[1px] bg-gradient-to-r from-transparent via-blue-400 to-transparent"></div>
      </div>
      <div class="absolute inset-2 rounded-full border border-blue-400/20 pss-pulse-glow"></div>
      <div class="w-12 h-12 sm:w-14 sm:h-14 rounded-full bg-blue-950/80 border border-blue-500/50 flex items-center justify-center text-blue-400 shadow-lg">
        ${getSvgIcon('shield', 22)}
      </div>
    `;
    card.appendChild(radarContainer);

    const metaBox = document.createElement('div');
    metaBox.className = 'text-center flex flex-col items-center gap-0.5 w-full';
    metaBox.innerHTML = `
      <div class="font-mono text-[11px] sm:text-xs uppercase tracking-wider text-blue-400 font-semibold flex items-center gap-1.5">
        <span class="w-2 h-2 rounded-full bg-blue-400 animate-ping"></span>
        Inspection en cours
      </div>
      <h3 class="text-xs sm:text-sm font-semibold text-white font-mono mt-1 truncate max-w-[260px] sm:max-w-md">${esc(this.currentFileName)}</h3>
      <div class="text-[10px] sm:text-[11px] font-mono text-slate-400">
        ${this.scanState.totalLines} ligne(s) · ${esc(this.currentEncoding)} · moteur hors fil d'exécution
      </div>
    `;
    card.appendChild(metaBox);

    const progressContainer = document.createElement('div');
    progressContainer.className = 'w-full flex flex-col gap-1.5';
    progressContainer.innerHTML = `
      <div class="flex items-center justify-between font-mono text-[11px] sm:text-xs text-slate-300">
        <span class="text-slate-400 text-[10px] sm:text-[11px] truncate max-w-[200px] sm:max-w-none">${this.scanState.currentStepLabel}</span>
        <span class="font-bold text-blue-400">${this.scanState.percentage}%</span>
      </div>
      <div class="w-full h-1.5 bg-[#090d14] rounded-full overflow-hidden border border-[#1c2637]">
        <div class="h-full bg-gradient-to-r from-blue-600 via-blue-400 to-emerald-400 transition-all duration-120 ease-out" style="width: ${this.scanState.percentage}%"></div>
      </div>
    `;
    card.appendChild(progressContainer);

    const stepsList = document.createElement('div');
    stepsList.className = 'w-full grid grid-cols-1 sm:grid-cols-2 gap-1.5 sm:gap-2 pt-2 border-t border-[#1c2637] font-mono text-[10px]';

    const s1Active = this.scanState.stepIndex >= 1;
    const s2Active = this.scanState.stepIndex >= 2;
    const s3Active = this.scanState.stepIndex >= 3;
    const s4Active = this.scanState.stepIndex >= 4;

    stepsList.innerHTML = `
      <div class="flex items-center gap-1.5 ${s1Active ? 'text-emerald-400' : 'text-slate-600'}">
        <span>${s1Active ? getSvgIcon('check', 12) : '•'}</span>
        <span>Chargement du moteur WASM</span>
      </div>
      <div class="flex items-center gap-1.5 ${s2Active ? 'text-emerald-400' : 'text-slate-600'}">
        <span>${s2Active ? getSvgIcon('check', 12) : '•'}</span>
        <span>Transfert vers le worker</span>
      </div>
      <div class="flex items-center gap-1.5 ${s3Active ? 'text-emerald-400' : 'text-slate-600'}">
        <span>${s3Active ? getSvgIcon('check', 12) : '•'}</span>
        <span>Extraction &amp; validation</span>
      </div>
      <div class="flex items-center gap-1.5 ${s4Active ? 'text-emerald-400' : 'text-slate-600'}">
        <span>${s4Active ? getSvgIcon('check', 12) : '•'}</span>
        <span>Tri des 3 niveaux</span>
      </div>
    `;
    card.appendChild(stepsList);

    wrap.appendChild(card);
    return wrap;
  }

  // Écran de Rapport optimisé (Bascule d'onglets mobile automatique)
  private renderReportScreen(): HTMLElement {
    const wrap = document.createElement('div');
    wrap.className = 'flex flex-col gap-3 sm:gap-4 py-1';

    const certainCount = this.currentFindings.filter(f => f.confidence === 'certain').length;
    const probableCount = this.currentFindings.filter(f => f.confidence === 'probable').length;
    const possibleCount = this.currentFindings.filter(f => f.confidence === 'possible').length;

    // 1. Bannière de périmètre compacte
    const scopeBanner = document.createElement('div');
    scopeBanner.className = 'pss-glass rounded-lg p-3 sm:px-4 sm:py-3 flex flex-wrap items-center justify-between gap-2 text-xs';
    scopeBanner.innerHTML = `
      <div class="flex items-center gap-2 sm:gap-3 min-w-0 flex-wrap">
        <span class="text-blue-400 shrink-0">${getSvgIcon('fileText', 16)}</span>
        <strong class="font-mono text-white text-xs truncate max-w-[150px] sm:max-w-xs">${esc(this.currentScope?.fileName)}</strong>
        <span class="text-slate-500 font-mono text-[10px] sm:text-[11px] shrink-0">${this.currentScope?.fileSizeBytes} o</span>
        <span class="text-slate-500 hidden sm:inline">•</span>
        <span class="text-slate-400 text-[10px] sm:text-[11px] hidden sm:inline">${this.currentScope?.lineCount} lignes</span>
        <span class="text-slate-500 hidden sm:inline">•</span>
        <span class="text-slate-500 text-[10px] sm:text-[11px] shrink-0">${esc(this.currentScope?.encoding)}</span>
        <span class="text-slate-500 hidden sm:inline">•</span>
        <span class="text-slate-500 text-[10px] sm:text-[11px] font-mono shrink-0">${this.currentScope?.analysisDurationMs} ms</span>
      </div>
      <button id="btn-re-scan" class="pss-btn pss-btn-secondary px-2.5 py-1 text-xs shrink-0">
        ${getSvgIcon('refresh', 12)} <span class="hidden sm:inline">Nouveau </span>scan
      </button>
    `;
    scopeBanner.querySelector('#btn-re-scan')?.addEventListener('click', () => {
      this.currentScreen = 'home';
      this.render();
    });
    wrap.appendChild(scopeBanner);

    // 2. Grille de métriques
    const metricsGrid = document.createElement('div');
    metricsGrid.className = 'grid grid-cols-2 sm:grid-cols-4 gap-2 sm:gap-3';
    metricsGrid.innerHTML = `
      <div class="pss-glass rounded-lg p-2.5 sm:p-3">
        <div class="text-[10px] font-mono text-slate-400 uppercase">Total</div>
        <div class="text-lg sm:text-xl font-bold text-white mt-0.5 sm:mt-1">${certainCount + probableCount + (this.showPossibleFindings ? possibleCount : 0)}</div>
      </div>
      <div class="pss-glass rounded-lg p-2.5 sm:p-3 border-rose-900/40 bg-rose-950/10">
        <div class="flex items-center justify-between">
          <span class="text-[10px] font-mono font-semibold text-rose-300 uppercase">Certain</span>
          <span class="w-2 h-2 rounded-full bg-rose-500 shrink-0"></span>
        </div>
        <div class="text-lg sm:text-xl font-bold text-rose-400 mt-0.5 sm:mt-1">${certainCount}</div>
      </div>
      <div class="pss-glass rounded-lg p-2.5 sm:p-3 border-amber-900/40 bg-amber-950/10">
        <div class="flex items-center justify-between">
          <span class="text-[10px] font-mono font-semibold text-amber-300 uppercase">Probable</span>
          <span class="w-2 h-2 rounded-full bg-amber-500 shrink-0"></span>
        </div>
        <div class="text-lg sm:text-xl font-bold text-amber-400 mt-0.5 sm:mt-1">${probableCount}</div>
      </div>
      <div class="pss-glass rounded-lg p-2.5 sm:p-3">
        <div class="flex items-center justify-between">
          <span class="text-[10px] font-mono font-semibold text-slate-400 uppercase truncate">Possible (${possibleCount})</span>
          <button id="btn-toggle-possible" class="text-[10px] font-mono text-blue-400 hover:underline shrink-0 ml-1">
            ${this.showPossibleFindings ? 'Masquer' : 'Afficher'}
          </button>
        </div>
        <div class="text-lg sm:text-xl font-bold text-slate-300 mt-0.5 sm:mt-1">${possibleCount}</div>
      </div>
    `;
    metricsGrid.querySelector('#btn-toggle-possible')?.addEventListener('click', () => {
      this.showPossibleFindings = !this.showPossibleFindings;
      this.render();
    });
    wrap.appendChild(metricsGrid);

    // 3. Bascule d'onglets pour MOBILE uniquement (< lg)
    const mobileTabBar = document.createElement('div');
    mobileTabBar.className = 'flex lg:hidden w-full bg-[#101622] p-1 rounded-lg border border-[#1c2637] font-mono text-xs gap-1';
    mobileTabBar.innerHTML = `
      <button id="tab-findings" class="flex-1 py-1.5 px-2 rounded font-medium flex items-center justify-center gap-1.5 transition-colors ${this.mobileActiveTab === 'findings' ? 'bg-blue-600 text-white shadow-sm' : 'text-slate-400 hover:text-white'}">
        ${getSvgIcon('layers', 14)} Détections (${this.getVisibleFindings().length})
      </button>
      <button id="tab-preview" class="flex-1 py-1.5 px-2 rounded font-medium flex items-center justify-center gap-1.5 transition-colors ${this.mobileActiveTab === 'preview' ? 'bg-blue-600 text-white shadow-sm' : 'text-slate-400 hover:text-white'}">
        ${getSvgIcon('fileText', 14)} Texte Masqué
      </button>
    `;
    mobileTabBar.querySelector('#tab-findings')?.addEventListener('click', () => {
      this.mobileActiveTab = 'findings';
      this.render();
    });
    mobileTabBar.querySelector('#tab-preview')?.addEventListener('click', () => {
      this.mobileActiveTab = 'preview';
      this.render();
    });
    wrap.appendChild(mobileTabBar);

    // 4. Colonnes de contenu (Responsive : onglets sur mobile, côte-à-côte sur desktop)
    const content = document.createElement('div');
    content.className = 'grid grid-cols-1 lg:grid-cols-12 gap-3 sm:gap-4 items-start';

    // Colonne Findings
    const findingsCol = document.createElement('div');
    const isFindingsVisibleOnMobile = this.mobileActiveTab === 'findings';
    findingsCol.className = `lg:col-span-6 flex flex-col gap-2.5 ${isFindingsVisibleOnMobile ? 'block' : 'hidden lg:flex'}`;

    const visible = this.getVisibleFindings();
    const hiddenCount = this.currentFindings.length - visible.length;
    if (visible.length === 0) {
      const cleanBox = document.createElement('div');
      cleanBox.className = 'pss-glass rounded-lg p-6 sm:p-8 text-center text-xs flex flex-col items-center gap-2';

      if (hiddenCount > 0) {
        cleanBox.innerHTML = `
          <div class="w-10 h-10 rounded-full bg-amber-500/10 border border-amber-500/30 flex items-center justify-center text-amber-400 mb-1">
            ${getSvgIcon('layers', 20)}
          </div>
          <strong class="text-sm text-amber-300">${hiddenCount} détection(s) « Possible » masquée(s)</strong>
          <p class="text-slate-400 text-xs max-w-xs">
            Aucun finding au-dessus de l'affichage courant, mais ${hiddenCount} élément(s) de niveau « Possible » sont retenus
            et <span class="text-slate-300">déjà masqués dans la copie et l'export</span>.
          </p>
          <button id="btn-show-possible" class="pss-btn pss-btn-secondary px-3 py-1.5 text-xs mt-1">Afficher les « Possible »</button>
        `;
        cleanBox.querySelector('#btn-show-possible')?.addEventListener('click', () => {
          this.showPossibleFindings = true;
          this.render();
        });
      } else {
        cleanBox.innerHTML = `
          <div class="w-10 h-10 rounded-full bg-emerald-500/10 border border-emerald-500/30 flex items-center justify-center text-emerald-400 mb-1">
            ${getSvgIcon('shieldCheck', 20)}
          </div>
          <strong class="text-sm text-emerald-300">Aucune donnée sensible identifiée dans ce qui a été lu</strong>
          <p class="text-slate-400 text-xs max-w-xs">
            ${esc(this.currentScope?.charCount)} caractères analysés, encodage ${esc(this.currentScope?.encoding)},
            règles de clés d'API, de mots de passe, d'IBAN et de NIR.
          </p>
          <div class="mt-2 w-full max-w-sm rounded border border-amber-500/25 bg-amber-950/20 p-2.5 text-left text-[11px] text-amber-200/90">
            <div class="font-mono text-[10px] uppercase text-amber-400 mb-1">Ce que cette analyse n'a pas couvert</div>
            <ul class="list-disc pl-4 space-y-0.5 text-slate-300">
              <li>PDF et images : non ouverts, donc non analysés (aucun OCR).</li>
              <li>Word (.docx), Excel (.xlsx) et PowerPoint (.pptx) : texte des XML lu ; tableaux fusionnés, images et macros ignorés.</li>
              <li>Fichiers binaires : refusés avant lecture.</li>
              <li>Fichiers de plus de 50 Mio : refusés avant lecture.</li>
              <li>Encodages non détectables et texte hors règles chargées.</li>
            </ul>
            <div class="mt-1 text-slate-400">Un « aucun résultat » ne porte que sur le texte réellement lu.</div>
          </div>
        `;
      }
      findingsCol.appendChild(cleanBox);
    } else {
      const list = document.createElement('div');
      list.className = 'flex flex-col gap-2 max-h-[520px] lg:max-h-[580px] overflow-y-auto pr-0.5';
      visible.forEach(f => list.appendChild(this.renderFindingCard(f)));
      findingsCol.appendChild(list);
    }

    // Colonne Masquage
    const previewCol = document.createElement('div');
    const isPreviewVisibleOnMobile = this.mobileActiveTab === 'preview';
    previewCol.className = `lg:col-span-6 flex flex-col gap-2.5 ${isPreviewVisibleOnMobile ? 'block' : 'hidden lg:flex'}`;
    previewCol.appendChild(this.renderRedactionCard());

    content.appendChild(findingsCol);
    content.appendChild(previewCol);
    wrap.appendChild(content);

    return wrap;
  }

  // Carte de Finding
  private renderFindingCard(finding: Finding): HTMLElement {
    const card = document.createElement('article');
    card.className = `pss-glass rounded-lg p-3 transition-all hover:border-slate-500 cursor-pointer flex flex-col gap-2`;

    let badgeClass = 'badge-possible';
    if (finding.confidence === 'certain') badgeClass = 'badge-certain';
    if (finding.confidence === 'probable') badgeClass = 'badge-probable';

    card.innerHTML = `
      <div class="flex items-center justify-between gap-2">
        <div class="flex items-center gap-2 min-w-0">
          <span class="px-2 py-0.5 rounded text-[10px] font-mono font-semibold uppercase shrink-0 ${badgeClass}">
            ${esc(finding.confidence)}
          </span>
          <span class="font-medium text-white text-xs truncate">${esc(finding.labelFr)}</span>
        </div>
        <span class="text-[10px] font-mono text-slate-500 shrink-0">L.${finding.line} : C.${finding.column}</span>
      </div>

      <div class="px-2.5 py-1.5 rounded bg-[#090d14] border border-[#1c2637] flex items-center justify-between text-xs font-mono gap-2">
        <span class="text-slate-400 truncate">${esc(finding.previewMasked)}</span>
        <span class="text-[10px] text-slate-600 shrink-0">${esc(finding.type)}</span>
      </div>

      <div class="flex items-center justify-between text-[11px] text-slate-400 gap-1.5">
        <span class="truncate">${esc(finding.confidenceReason)}</span>
        <span class="text-blue-400 hover:text-blue-300 font-mono text-[10px] flex items-center gap-1 shrink-0">
          Détails ${getSvgIcon('arrowRight', 10)}
        </span>
      </div>
    `;

    card.onclick = () => {
      this.selectedFinding = finding;
      this.currentDrawer = 'detail';
      this.render();
    };

    return card;
  }

  // Carte de masquage
  private renderRedactionCard(): HTMLElement {
    const box = document.createElement('div');
    box.className = 'pss-glass rounded-lg p-3.5 sm:p-4 flex flex-col gap-3';

    const head = document.createElement('div');
    head.className = 'flex items-center justify-between pb-2 border-b border-[#1c2637] gap-2';
    head.innerHTML = `
      <div class="text-xs font-semibold text-slate-200 font-mono truncate">Aperçu du Masquage</div>
      <div class="flex items-center bg-[#090d14] p-0.5 rounded border border-[#1c2637] text-[11px] font-mono shrink-0">
        <button id="toggle-masque" class="px-2.5 py-1 rounded ${this.previewMode === 'redacted' ? 'bg-slate-700 text-white' : 'text-slate-400 hover:text-white'}">
          Masqué
        </button>
        <button id="toggle-source" class="px-2.5 py-1 rounded ${this.previewMode === 'raw' ? 'bg-slate-700 text-white' : 'text-slate-400 hover:text-white'}">
          Source
        </button>
      </div>
    `;

    head.querySelector('#toggle-masque')?.addEventListener('click', () => {
      this.previewMode = 'redacted';
      this.render();
    });
    head.querySelector('#toggle-source')?.addEventListener('click', () => {
      this.previewMode = 'raw';
      this.render();
    });
    box.appendChild(head);

    const canvas = document.createElement('pre');
    canvas.className = 'p-3 rounded-lg bg-[#090d14] border border-[#1c2637] text-xs font-mono overflow-x-auto text-slate-300 max-h-[300px] sm:max-h-[380px] leading-relaxed whitespace-pre-wrap select-all';

    if (this.previewMode === 'redacted') {
      const red = this.getRedactedContent();
      const esc = red.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
      canvas.innerHTML = esc.replace(/(\[REDACTED:[A-Za-z0-9_]+\])/g, '<span class="text-emerald-400 bg-emerald-950/60 border border-emerald-500/40 px-1 py-0.5 rounded font-mono font-semibold">$1</span>');
    } else {
      canvas.textContent = this.currentInputText;
    }
    box.appendChild(canvas);

    const actions = document.createElement('div');
    actions.className = 'flex flex-col sm:flex-row items-stretch sm:items-center justify-end gap-2 pt-2 border-t border-[#1c2637]';

    const exportBtn = document.createElement('button');
    exportBtn.className = 'pss-btn pss-btn-secondary px-3 py-2 text-xs w-full sm:w-auto';
    exportBtn.innerHTML = `${getSvgIcon('download', 14)} Télécharger`;
    exportBtn.onclick = () => {
      const blob = new Blob([this.getRedactedContent()], { type: 'text/plain;charset=utf-8' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `assaini_${this.currentFileName || 'document.txt'}`;
      a.click();
      URL.revokeObjectURL(url);
    };

    const copyBtn = document.createElement('button');
    copyBtn.className = 'pss-btn pss-btn-primary px-3.5 py-2 text-xs w-full sm:w-auto';
    copyBtn.innerHTML = `${getSvgIcon('copy', 14)} Copier la version masquée`;
    copyBtn.onclick = () => {
      navigator.clipboard.writeText(this.getRedactedContent()).then(() => {
        const orig = copyBtn.innerHTML;
        copyBtn.innerHTML = `${getSvgIcon('check', 14)} Copié`;
        setTimeout(() => { copyBtn.innerHTML = orig; }, 1800);
      });
    };

    actions.appendChild(exportBtn);
    actions.appendChild(copyBtn);
    box.appendChild(actions);

    return box;
  }

  // Volet Détail responsive
  private renderDetailDrawer(): HTMLElement {
    const f = this.selectedFinding;
    if (!f) return document.createElement('div');

    const overlay = document.createElement('div');
    overlay.className = 'fixed inset-0 bg-black/60 backdrop-blur-sm z-50 flex justify-end pss-overlay-anim';

    const drawer = document.createElement('div');
    drawer.className = 'pss-drawer-mobile w-full sm:max-w-md bg-[#101622] border-l border-[#1c2637] h-full flex flex-col p-4 sm:p-6 overflow-y-auto text-xs gap-4 pss-drawer-anim shadow-2xl';

    drawer.innerHTML = `
      <div class="flex items-center justify-between border-b border-[#1c2637] pb-3">
        <div class="min-w-0 pr-2">
          <span class="text-[10px] font-mono text-slate-500 uppercase">${esc(f.id)}</span>
          <h2 class="text-sm font-bold text-white truncate">${esc(f.labelFr)}</h2>
        </div>
        <button id="close-drawer" class="p-2 rounded hover:bg-slate-800 text-slate-400 hover:text-white shrink-0">
          ${getSvgIcon('close', 18)}
        </button>
      </div>

      <div class="p-3 rounded border ${f.confidence === 'certain' ? 'badge-certain' : (f.confidence === 'probable' ? 'badge-probable' : 'badge-possible')}">
        <div class="font-mono text-[10px] uppercase font-semibold">Niveau : ${esc(f.confidence)}</div>
        <div class="mt-1 text-slate-200 text-xs">${esc(f.confidenceReason)}</div>
      </div>

      <div class="flex flex-col gap-1">
        <span class="text-slate-400 font-mono text-[11px] uppercase">Règle de détection</span>
        <div class="p-3 rounded bg-[#090d14] border border-[#1c2637] font-mono text-[11px] text-slate-300 space-y-1">
          <div>Règle : <span class="text-white">${esc(f.ruleId)}</span></div>
          <div>Type : <span class="text-white">${esc(f.type)}</span></div>
          <div>Catégorie : <span class="text-white">${esc(f.category)}</span></div>
        </div>
      </div>

      <div class="flex flex-col gap-1">
        <span class="text-slate-400 font-mono text-[11px] uppercase">Position dans le document</span>
        <div class="p-3 rounded bg-[#090d14] border border-[#1c2637] font-mono text-[11px] text-slate-300 grid grid-cols-2 gap-2">
          <div>Ligne : <span class="text-white">${f.line}</span></div>
          <div>Colonne : <span class="text-white">${f.column}</span></div>
        </div>
      </div>

      <div class="flex flex-col gap-1">
        <span class="text-slate-400 font-mono text-[11px] uppercase">Masque appliqué</span>
        <div class="p-3 rounded bg-[#090d14] border border-[#1c2637] font-mono text-xs text-emerald-400 break-all">
          [REDACTED:${f.type}]
        </div>
      </div>

      <div class="mt-auto pt-4 border-t border-[#1c2637]">
        <button id="btn-close-d" class="pss-btn pss-btn-secondary w-full py-2.5">Fermer</button>
      </div>
    `;

    const close = () => {
      this.currentDrawer = 'none';
      this.selectedFinding = null;
      this.render();
    };

    overlay.onclick = (e) => { if (e.target === overlay) close(); };
    drawer.querySelector('#close-drawer')?.addEventListener('click', close);
    drawer.querySelector('#btn-close-d')?.addEventListener('click', close);

    overlay.appendChild(drawer);
    return overlay;
  }

  // Tiroir Réglages responsive
  private renderSettingsDrawer(): HTMLElement {
    const overlay = document.createElement('div');
    overlay.className = 'fixed inset-0 bg-black/60 backdrop-blur-sm z-50 flex justify-end pss-overlay-anim';

    const drawer = document.createElement('div');
    drawer.className = 'pss-drawer-mobile w-full sm:max-w-md bg-[#101622] border-l border-[#1c2637] h-full flex flex-col p-4 sm:p-6 overflow-y-auto text-xs gap-4 sm:gap-5 pss-drawer-anim shadow-2xl';

    drawer.innerHTML = `
      <div class="flex items-center justify-between border-b border-[#1c2637] pb-3">
        <h2 class="text-sm font-bold text-white">Règles & Filtres</h2>
        <button id="close-set" class="p-2 rounded hover:bg-slate-800 text-slate-400 hover:text-white">
          ${getSvgIcon('close', 18)}
        </button>
      </div>

      <div class="flex flex-col gap-2">
        <span class="text-slate-300 font-mono text-[11px] uppercase font-semibold">Niveau de confiance</span>
        <div class="p-3 rounded bg-[#090d14] border border-[#1c2637]">
          <label class="flex items-center gap-2 cursor-pointer text-slate-200">
            <input type="checkbox" id="set-possible" ${this.showPossibleFindings ? 'checked' : ''} class="w-4 h-4 rounded">
            <span>Inclure les détections « Possible »</span>
          </label>
        </div>
      </div>

      <div class="flex flex-col gap-2">
        <span class="text-slate-300 font-mono text-[11px] uppercase font-semibold">Catégories actives</span>
        <div class="p-3 rounded bg-[#090d14] border border-[#1c2637] flex flex-col gap-3">
          <label class="flex items-center justify-between cursor-pointer text-slate-200">
            <span>Identifiants & Clés secrètes</span>
            <input type="checkbox" id="cat-c" ${this.settings.categoryCredentials ? 'checked' : ''} class="w-4 h-4 rounded">
          </label>
          <label class="flex items-center justify-between cursor-pointer text-slate-200">
            <span>Données Personnelles (PII)</span>
            <input type="checkbox" id="cat-p" ${this.settings.categoryPii ? 'checked' : ''} class="w-4 h-4 rounded">
          </label>
          <label class="flex items-center justify-between cursor-pointer text-slate-200">
            <span>Coordonnées bancaires</span>
            <input type="checkbox" id="cat-f" ${this.settings.categoryFinancial ? 'checked' : ''} class="w-4 h-4 rounded">
          </label>
          <label class="flex items-center justify-between cursor-pointer text-slate-200">
            <span>Fuites d'infrastructure</span>
            <input type="checkbox" id="cat-i" ${this.settings.categoryInfra ? 'checked' : ''} class="w-4 h-4 rounded">
          </label>
        </div>
      </div>

      <div class="flex flex-col gap-2 mt-auto pt-3">
        <button id="btn-purge-set" class="pss-btn pss-btn-danger w-full py-2.5">
          ${getSvgIcon('trash', 14)} Réinitialiser la mémoire
        </button>
        <button id="btn-save-set" class="pss-btn pss-btn-primary w-full py-2.5">
          Appliquer les réglages
        </button>
      </div>
    `;

    const close = () => {
      this.currentDrawer = 'none';
      this.render();
    };

    overlay.onclick = (e) => { if (e.target === overlay) close(); };
    drawer.querySelector('#close-set')?.addEventListener('click', close);
    drawer.querySelector('#btn-purge-set')?.addEventListener('click', () => this.purgeSession());

    drawer.querySelector('#btn-save-set')?.addEventListener('click', () => {
      this.showPossibleFindings = (drawer.querySelector('#set-possible') as HTMLInputElement).checked;
      this.settings.categoryCredentials = (drawer.querySelector('#cat-c') as HTMLInputElement).checked;
      this.settings.categoryPii = (drawer.querySelector('#cat-p') as HTMLInputElement).checked;
      this.settings.categoryFinancial = (drawer.querySelector('#cat-f') as HTMLInputElement).checked;
      this.settings.categoryInfra = (drawer.querySelector('#cat-i') as HTMLInputElement).checked;
      this.currentDrawer = 'none';
      if (this.currentInputText) {
        this.startInspection(this.currentInputText, this.currentFileName);
      } else {
        this.render();
      }
    });

    overlay.appendChild(drawer);
    return overlay;
  }

  // Modale « Plateformes » : statut réel, jamais une promesse déguisée en fait.
  private renderDesktopModal(): HTMLElement {
    const overlay = document.createElement('div');
    overlay.className = 'fixed inset-0 bg-black/75 backdrop-blur-sm z-50 flex items-center justify-center p-3 sm:p-4 pss-overlay-anim';

    const modal = document.createElement('div');
    modal.className = 'w-full max-w-xl bg-[#101622] border border-[#1c2637] rounded-xl p-4 sm:p-6 flex flex-col gap-3.5 sm:gap-4 text-xs shadow-2xl max-h-[90vh] overflow-y-auto';

    const localBadge = 'px-1.5 py-0.5 rounded text-[10px] font-mono uppercase bg-emerald-500/10 border border-emerald-500/40 text-emerald-400 shrink-0';
    const todoBadge = 'px-1.5 py-0.5 rounded text-[10px] font-mono uppercase bg-amber-500/10 border border-amber-500/40 text-amber-400 shrink-0';

    modal.innerHTML = `
      <div class="flex items-center justify-between border-b border-[#1c2637] pb-3">
        <div class="flex items-center gap-2">
          <span class="text-blue-400">${getSvgIcon('layers', 18)}</span>
          <h2 class="text-sm font-bold text-white">Plateformes &amp; statut réel</h2>
        </div>
        <button id="close-desk" class="p-2 rounded hover:bg-slate-800 text-slate-400 hover:text-white">
          ${getSvgIcon('close', 18)}
        </button>
      </div>

      <div class="flex flex-col gap-2">
        <div class="p-3 rounded-lg bg-[#090d14] border border-[#1c2637] flex items-start justify-between gap-3">
          <div>
            <h4 class="font-semibold text-white font-mono text-xs">Web (cette page)</h4>
            <p class="text-slate-400 mt-1 text-[11px] leading-relaxed">
              Moteur Rust compilé en WASM, exécuté dans un Web Worker. Entrées : glisser-déposer, sélecteur de fichier et presse-papiers.
            </p>
          </div>
          <span class="${localBadge}">disponible</span>
        </div>

        <div class="p-3 rounded-lg bg-[#090d14] border border-[#1c2637] flex items-start justify-between gap-3">
          <div>
            <h4 class="font-semibold text-white font-mono text-xs">CLI <span class="text-slate-500">sensitive-scan</span></h4>
            <p class="text-slate-400 mt-1 text-[11px] leading-relaxed">
              Binaire natif Linux, Windows et macOS. <span class="font-mono">--format json</span>, <span class="font-mono">--exit-code 2</span>, <span class="font-mono">--redact</span>, <span class="font-mono">rules list</span>.
            </p>
          </div>
          <span class="${localBadge}">disponible</span>
        </div>

        <div class="p-3 rounded-lg bg-[#090d14] border border-[#1c2637] flex items-start justify-between gap-3">
          <div>
            <h4 class="font-semibold text-white font-mono text-xs">Desktop Tauri 2</h4>
            <p class="text-slate-400 mt-1 text-[11px] leading-relaxed">
              Prévu : clic droit « Scanner avant d'envoyer », surveillance de dossier. Non implémenté.
              Sous Linux : <span class="font-mono">libwebkit2gtk-4.1-dev</span>. macOS ne se compile pas depuis Linux (Mac ou runner GitHub requis).
            </p>
          </div>
          <span class="${todoBadge}">à venir</span>
        </div>

        <div class="p-3 rounded-lg bg-[#090d14] border border-[#1c2637] flex items-start justify-between gap-3">
          <div>
            <h4 class="font-semibold text-white font-mono text-xs">Mobile (PWA)</h4>
            <p class="text-slate-400 mt-1 text-[11px] leading-relaxed">
              Prévu : webview installable sans app native au départ. iOS en dernier, sous réserve d'un usage réel.
              Non implémenté.
            </p>
          </div>
          <span class="${todoBadge}">à venir</span>
        </div>
      </div>

      <div class="p-3 rounded-lg bg-[#090d14] border border-amber-500/25 text-[11px] text-amber-200/90 leading-relaxed">
        <div class="font-mono text-[10px] uppercase text-amber-400 mb-1">Limites de la version actuelle</div>
        Aucune lecture de PDF, de documents Office ni d'images (pas d'OCR). Aucun chiffrement de cache local,
        aucune notification système, aucun intégration au gestionnaire de fichiers.
      </div>

      <div class="flex justify-end pt-2 border-t border-[#1c2637]">
        <button id="btn-close-desk-bottom" class="pss-btn pss-btn-secondary w-full sm:w-auto px-4 py-2">Fermer</button>
      </div>
    `;

    const close = () => {
      this.currentDrawer = 'none';
      this.render();
    };

    overlay.onclick = (e) => { if (e.target === overlay) close(); };
    modal.querySelector('#close-desk')?.addEventListener('click', close);
    modal.querySelector('#btn-close-desk-bottom')?.addEventListener('click', close);

    overlay.appendChild(modal);
    return overlay;
  }

  // Modale d'erreur technique responsive
  private renderErrorModal(): HTMLElement {
    const overlay = document.createElement('div');
    overlay.className = 'fixed inset-0 bg-black/80 backdrop-blur-sm z-50 flex items-center justify-center p-3 sm:p-4 pss-overlay-anim';

    const err = this.currentError!;
    const modal = document.createElement('div');
    modal.className = 'w-full max-w-md bg-[#101622] border border-rose-900/60 rounded-xl p-4 sm:p-6 flex flex-col gap-3.5 sm:gap-4 text-xs shadow-2xl';

    modal.innerHTML = `
      <div class="flex items-start justify-between border-b border-[#1c2637] pb-3">
        <div class="flex items-center gap-2.5">
          <span class="text-rose-500 shrink-0">${getSvgIcon('alertTriangle', 18)}</span>
          <div>
            <span class="font-mono text-[10px] text-rose-400 uppercase font-semibold">${esc(err.code)}</span>
            <h2 class="text-sm font-bold text-white">${esc(err.title)}</h2>
          </div>
        </div>
        <button id="close-err" class="p-2 rounded hover:bg-slate-800 text-slate-400 hover:text-white shrink-0">
          ${getSvgIcon('close', 18)}
        </button>
      </div>

      <div class="text-slate-300 leading-relaxed text-xs">
        ${esc(err.message)}
      </div>

      <div class="p-3 rounded-lg bg-[#090d14] border border-[#1c2637] flex flex-col gap-1">
        <span class="font-mono text-slate-400 text-[10px] uppercase">Action requise :</span>
        <div class="text-slate-200 text-xs">${esc(err.action)}</div>
      </div>

      <div class="flex justify-end pt-2 border-t border-[#1c2637]">
        <button id="btn-close-err-b" class="pss-btn pss-btn-secondary w-full sm:w-auto px-4 py-2">Fermer</button>
      </div>
    `;

    const close = () => {
      this.currentDrawer = 'none';
      this.currentError = null;
      this.render();
    };

    overlay.onclick = (e) => { if (e.target === overlay) close(); };
    modal.querySelector('#close-err')?.addEventListener('click', close);
    modal.querySelector('#btn-close-err-b')?.addEventListener('click', close);

    overlay.appendChild(modal);
    return overlay;
  }
}

document.addEventListener('DOMContentLoaded', () => {
  const root = document.getElementById('app');
  if (root) new ScannerApp(root);
});
