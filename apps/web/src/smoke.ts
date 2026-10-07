/**
 * Test de bout en bout : charge le WebAssembly dans le Web Worker, analyse un
 * document contenant des données réelles, et publie le résultat dans le DOM
 * pour être lu par un navigateur en mode sans-tête.
 *
 *   google-chrome --headless=new --dump-dom --virtual-time-budget=20000 \
 *     http://localhost:3000/smoke.html
 */

import { scanText } from './engineClient';

const SAMPLE = [
  'Réunion du 14 mars, 12 h 30, salle 3.',
  'IBAN du fournisseur : FR7600000000000000000000000',
  'Carte de test : 4539 1488 0343 6467',
  'Contact : pierre.exemple@exemple.fr',
  'Jeton : eyJhbGciOiJub25lIn0.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N',
].join('\n');

const out = document.getElementById('out') as HTMLPreElement;
out.textContent = 'module chargé';


scanText(SAMPLE, { categories: ['credentials', 'pii', 'financial', 'infra'] }, () => {})
  .then((outcome) => {
    const report = outcome.report;
    const serialised = JSON.stringify(outcome);
    const leak = serialised.includes('4539148803436467') || serialised.includes('FR7600000000000000000000000');
    out.textContent = [
      'SMOKE_OK',
      `total=${report.counts.total}`,
      `certain=${report.counts.certain}`,
      `probable=${report.counts.probable}`,
      `regles=${report.rulesEvaluated}`,
      `ids=${report.findings.map((f) => f.ruleId).sort().join(',')}`,
      `secret_en_clair=${leak}`,
      `preview=${report.findings.map((f) => f.previewMasked).join('|')}`,
    ].join('\n');
    document.title = 'SMOKE_OK';
  })
  .catch((error: unknown) => {
    out.textContent = `SMOKE_FAIL ${error instanceof Error ? error.message : String(error)}`;
    document.title = 'SMOKE_FAIL';
  });
