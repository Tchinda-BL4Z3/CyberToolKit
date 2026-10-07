import { spawn } from 'node:child_process';
import { randomBytes, createHash } from 'node:crypto';
import net from 'node:net';
import { setTimeout as sleep } from 'node:timers/promises';
import { deflateRawSync, crc32 } from 'node:zlib';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const PORT = Number(process.env.PSS_CDP_PORT || 9333);
const SERVER_PORT = Number(process.env.PSS_PORT || 3113);
const ORIGIN = `http://127.0.0.1:${SERVER_PORT}`;
const SCENARIOS = ['smoke.html', 'app', 'office'];
const failures = [];

// Construit une archive ZIP minimale (sans dépendance) pour obtenir un vrai
// .docx en mémoire : le moteur devrait extraire le texte de word/document.xml.
function zipBuild(entries) {
  const chunks = [];
  const central = [];
  let offset = 0;
  for (const { name, data } of entries) {
    const nameBuf = Buffer.from(name, 'utf8');
    const compressed = deflateRawSync(data);
    const crc = crc32(data) >>> 0;
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4);
    local.writeUInt16LE(0x0800, 6);
    local.writeUInt16LE(8, 8);
    local.writeUInt16LE(0, 10);
    local.writeUInt32LE(crc, 14);
    local.writeUInt32LE(compressed.length, 18);
    local.writeUInt32LE(data.length, 22);
    local.writeUInt16LE(nameBuf.length, 26);
    local.writeUInt16LE(0, 28);
    chunks.push(local, nameBuf, compressed);

    const cd = Buffer.alloc(46);
    cd.writeUInt32LE(0x02014b50, 0);
    cd.writeUInt16LE(20, 4);
    cd.writeUInt16LE(20, 6);
    cd.writeUInt16LE(0x0800, 8);
    cd.writeUInt16LE(8, 10);
    cd.writeUInt16LE(0, 12);
    cd.writeUInt32LE(crc, 16);
    cd.writeUInt32LE(compressed.length, 20);
    cd.writeUInt32LE(data.length, 24);
    cd.writeUInt16LE(nameBuf.length, 28);
    cd.writeUInt16LE(0, 30);
    cd.writeUInt16LE(0, 32);
    cd.writeUInt16LE(0, 34);
    cd.writeUInt16LE(0, 36);
    cd.writeUInt32LE(0, 38);
    cd.writeUInt32LE(offset, 42);
    central.push(Buffer.concat([cd, nameBuf]));
    offset += local.length + nameBuf.length + compressed.length;
  }
  const body = Buffer.concat(chunks);
  const centralBuf = Buffer.concat(central);
  const eocd = Buffer.alloc(22);
  eocd.writeUInt32LE(0x06054b50, 0);
  eocd.writeUInt16LE(0, 4);
  eocd.writeUInt16LE(0, 6);
  eocd.writeUInt16LE(entries.length, 8);
  eocd.writeUInt16LE(entries.length, 10);
  eocd.writeUInt32LE(centralBuf.length, 12);
  eocd.writeUInt32LE(body.length, 16);
  eocd.writeUInt16LE(0, 20);
  return Buffer.concat([body, centralBuf, eocd]);
}

function buildOfficeDocx() {
  const documentXml = [
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>',
    '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>',
    '<w:p><w:r><w:t>Reunion du 8 mars - facture fournisseur</w:t></w:r></w:p>',
    '<w:p><w:r><w:t>IBAN : FR7600000000000000000000000</w:t></w:r></w:p>',
    '<w:p><w:r><w:t>Carte : 4539 1488 0343 6467</w:t></w:r></w:p>',
    '<w:p><w:r><w:t>Contact : paul.docx@example.com</w:t></w:r></w:p>',
    '</w:body></w:document>',
  ].join('');
  return zipBuild([
    { name: '[Content_Types].xml', data: Buffer.from('<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="xml" ContentType="application/xml"/></Types>') },
    { name: 'word/document.xml', data: Buffer.from(documentXml) },
  ]);
}

const OFFICE_B64 = buildOfficeDocx().toString('base64');

function encodeFrame(data) {
  const payload = Buffer.from(data, 'utf8');
  const mask = randomBytes(4);
  const len = payload.length;
  let header;
  if (len < 126) {
    header = Buffer.from([0x81, 0x80 | len]);
  } else if (len < 65536) {
    header = Buffer.alloc(4);
    header[0] = 0x81;
    header[1] = 0x80 | 126;
    header.writeUInt16BE(len, 2);
  } else {
    header = Buffer.alloc(10);
    header[0] = 0x81;
    header[1] = 0x80 | 127;
    header.writeBigUInt64BE(BigInt(len), 2);
  }
  const masked = Buffer.from(payload);
  for (let i = 0; i < masked.length; i++) masked[i] ^= mask[i % 4];
  return Buffer.concat([header, mask, masked]);
}

function connect(wsUrl) {
  return new Promise((resolve, reject) => {
    const url = new URL(wsUrl);
    const key = randomBytes(16).toString('base64');
    const socket = new net.Socket();
    socket.connect(Number(url.port), url.hostname, () => {
      socket.write(
        [
          `GET ${url.pathname}${url.search} HTTP/1.1`,
          `Host: ${url.host}`,
          'Upgrade: websocket',
          'Connection: Upgrade',
          `Sec-WebSocket-Key: ${key}`,
          'Sec-WebSocket-Version: 13',
          '',
          '',
        ].join('\r\n'),
      );
    });
    socket.once('error', reject);

    let upgraded = false;
    let buffer = Buffer.alloc(0);
    const listeners = [];
    const pending = new Map();
    let nextId = 1;

    const onText = (text) => {
      let msg;
      try {
        msg = JSON.parse(text);
      } catch {
        return;
      }
      if (msg.id && pending.has(msg.id)) {
        const handler = pending.get(msg.id);
        pending.delete(msg.id);
        msg.error ? handler.reject(new Error(msg.error.message)) : handler.resolve(msg.result);
      } else {
        listeners.forEach((fn) => fn(msg));
      }
    };

    socket.on('data', (chunk) => {
      buffer = Buffer.concat([buffer, chunk]);
      if (!upgraded) {
        const idx = buffer.indexOf('\r\n\r\n');
        if (idx === -1) return;
        const head = buffer.subarray(0, idx).toString();
        const expected = createHash('sha1')
          .update(key + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11')
          .digest('base64');
        if (!/ 101 /.test(head) || !head.includes(expected)) {
          reject(new Error('handshake websocket refuse : ' + head.split('\r\n')[0]));
          return;
        }
        buffer = buffer.subarray(idx + 4);
        upgraded = true;
        resolve(api);
      }
      for (;;) {
        if (buffer.length < 2) return;
        const opcode = buffer[0] & 0x0f;
        const masked = (buffer[1] & 0x80) !== 0;
        let len = buffer[1] & 0x7f;
        let offset = 2;
        if (len === 126) {
          if (buffer.length < 4) return;
          len = buffer.readUInt16BE(2);
          offset = 4;
        } else if (len === 127) {
          if (buffer.length < 10) return;
          len = Number(buffer.readBigUInt64BE(2));
          offset = 10;
        }
        const maskLen = masked ? 4 : 0;
        if (buffer.length < offset + maskLen + len) return;
        let payload = buffer.subarray(offset + maskLen, offset + maskLen + len);
        if (masked) {
          const mask = buffer.subarray(offset, offset + 4);
          payload = Buffer.from(payload);
          for (let i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
        }
        buffer = buffer.subarray(offset + maskLen + len);
        if (opcode === 0x1) onText(payload.toString());
        if (opcode === 0x8) socket.destroy();
      }
    });

    const api = {
      send(method, params = {}) {
        const id = nextId++;
        socket.write(encodeFrame(JSON.stringify({ id, method, params })));
        return new Promise((resolveSend, rejectSend) => {
          pending.set(id, { resolve: resolveSend, reject: rejectSend });
        });
      },
      on(fn) {
        listeners.push(fn);
      },
      close() {
        socket.destroy();
      },
    };
  });
}

async function waitServer(timeout) {
  const limit = Date.now() + timeout;
  while (Date.now() < limit) {
    try {
      const res = await fetch(`${ORIGIN}/smoke.html`);
      if (res.ok) return true;
    } catch {
      /* pas encore pret */
    }
    await sleep(300);
  }
  return false;
}

async function waitTarget(timeout) {
  const limit = Date.now() + timeout;
  while (Date.now() < limit) {
    try {
      const res = await fetch(`http://127.0.0.1:${PORT}/json/list`);
      const list = await res.json();
      const page = list.find((t) => t.type === 'page');
      if (page) return page;
    } catch {
      /* chrome n'a pas encore expose de cible */
    }
    await sleep(200);
  }
  throw new Error("Chrome n'a pas expose de cible");
}

function startVite() {
  const webRoot = fileURLToPath(new URL('..', import.meta.url));
  const viteBin = path.join(webRoot, 'node_modules', 'vite', 'bin', 'vite.js');
  const child = spawn(process.execPath, [viteBin, '--port', String(SERVER_PORT), '--strictPort'], {
    cwd: webRoot,
    stdio: 'ignore',
  });
  child.on('error', () => {});
  return child;
}

const vite = startVite();
if (!(await waitServer(60000))) {
  vite.kill();
  console.error(`Echec : le serveur ${ORIGIN} ne repond pas.`);
  process.exit(1);
}

const chrome = spawn(
  'google-chrome',
  [
    '--headless=new',
    '--disable-gpu',
    '--no-sandbox',
    '--disable-dev-shm-usage',
    `--remote-debugging-port=${PORT}`,
    '--user-data-dir=/tmp/pss-smoke-profile',
    'about:blank',
  ],
  { stdio: 'ignore' },
);

let cdp;
try {
  const target = await waitTarget(30000);
  cdp = await connect(target.webSocketDebuggerUrl);
  await cdp.send('Page.enable');
  await cdp.send('Runtime.enable');
} catch (error) {
  console.error('Echec : ' + error.message);
  chrome.kill();
  vite.kill();
  process.exit(1);
}

const consoleErrors = [];
cdp.on((msg) => {
  if (msg.method === 'Runtime.exceptionThrown') {
    const detail = msg.params.exceptionDetails;
    consoleErrors.push(`${detail.text} ${detail.exception?.description || ''}`);
  }
  if (msg.method === 'Runtime.consoleAPICalled' && msg.params.type === 'error') {
    consoleErrors.push(
      (msg.params.args || []).map((a) => a.value ?? a.description ?? '').join(' '),
    );
  }
});

async function evaluate(expression) {
  const r = await cdp.send('Runtime.evaluate', {
    expression,
    returnByValue: true,
    awaitPromise: true,
  });
  if (r.exceptionDetails) {
    throw new Error(`${r.exceptionDetails.text} ${r.exceptionDetails.exception?.description || ''}`);
  }
  return r.result.value;
}

async function waitFor(expression, timeout) {
  const limit = Date.now() + timeout;
  while (Date.now() < limit) {
    if (await evaluate(`(${expression}) ? 1 : 0`)) return true;
    await sleep(250);
  }
  return false;
}

async function runSmokePage() {
  await cdp.send('Page.navigate', { url: `${ORIGIN}/smoke.html` });
  if (!(await waitFor("document.getElementById('out') && /SMOKE_/.test(document.getElementById('out').textContent)", 45000))) {
    const seen = await evaluate("document.getElementById('out') ? document.getElementById('out').textContent : ''");
    return `smoke.html : timeout, dernier etat « ${seen} »`;
  }
  const out = await evaluate("document.getElementById('out').textContent");
  const checks = [
    [/SMOKE_OK/.test(out), 'SMOKE_OK absent'],
    [/total=4/.test(out), '4 detections attendues'],
    [/secret_en_clair=false/.test(out), 'aucune valeur en clair attendue'],
    [/preview=.*0000/.test(out), 'aperçu masqué IBAN attendu'],
    [/preview=.*6467/.test(out), 'aperçu masqué carte attendu'],
    [!/preview=.*FR76/.test(out), 'IBAN visible dans un aperçu'],
  ];
  console.log(out);
  for (const [ok, label] of checks) if (!ok) failures.push(`smoke.html : ${label}`);
  return null;
}

async function runAppFlow() {
  await cdp.send('Page.navigate', { url: `${ORIGIN}/` });
  if (!(await waitFor("document.getElementById('direct-text-area') !== null", 30000))) {
    return "app : zone de saisie introuvable";
  }
  await evaluate(`
    (() => {
      const ta = document.getElementById('direct-text-area');
      ta.value = ['IBAN fournisseur : FR7600000000000000000000000', 'Carte : 4539 1488 0343 6467'].join('\\n');
      ta.dispatchEvent(new Event('input', { bubbles: true }));
      document.getElementById('btn-inspect-direct').click();
    })()
  `);
  const done = await waitFor(
    "document.getElementById('tab-findings') !== null || document.getElementById('close-err') !== null",
    45000,
  );
  if (!done) return "app : le rapport ne s'est jamais affiche";
  const result = await evaluate(`(() => {
    if (document.getElementById('close-err')) {
      return JSON.stringify({ erreur: document.body.innerText.slice(0, 300) });
    }
    const body = document.body.innerText;
    return JSON.stringify({
      rapport: !!document.getElementById('tab-findings'),
      carte_en_clair: body.includes('4539 1488 0343 6467'),
      iban_en_clair: body.includes('FR7600000000000000000000000'),
      detections: body.match(/Détections \\(\\d+\\)/)?.[0] || '',
      extrait: body.slice(0, 200).split('\\n').join(' | '),
    });
  })()`);
  const parsed = JSON.parse(result);
  console.log(JSON.stringify(parsed));
  if (parsed.erreur) return `app : ${parsed.erreur}`;
  if (!parsed.rapport) failures.push('app : rapport absent');
  if (parsed.carte_en_clair) failures.push('app : numéro de carte en clair');
  if (parsed.iban_en_clair) failures.push('app : IBAN en clair');
  return null;
}

async function runOfficeFlow() {
  await cdp.send('Page.navigate', { url: `${ORIGIN}/` });
  if (!(await waitFor("document.getElementById('input-file-hidden') !== null", 30000))) {
    return 'office : input fichier introuvable';
  }
  await evaluate(`(() => {
    const bin = Uint8Array.from(atob('${OFFICE_B64}'), (c) => c.charCodeAt(0));
    const file = new File([bin], 'rapport.docx', { type: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document' });
    const dt = new DataTransfer();
    dt.items.add(file);
    const input = document.getElementById('input-file-hidden');
    input.files = dt.files;
    input.dispatchEvent(new Event('change', { bubbles: true }));
  })()`);
  const done = await waitFor(
    "document.getElementById('tab-findings') !== null || document.getElementById('close-err') !== null",
    60000,
  );
  if (!done) return "office : le rapport ne s'est jamais affiche";
  await evaluate(`document.getElementById('tab-preview')?.click()`);
  const result = await evaluate(`(async () => {
    await new Promise((r) => setTimeout(r, 300));
    if (document.getElementById('close-err')) {
      return JSON.stringify({ erreur: document.body.innerText.slice(0, 300) });
    }
    const body = document.body.innerText;
    return JSON.stringify({
      rapport: !!document.getElementById('tab-findings'),
      iban_entier_visible: body.includes('FR7600000000000000000000000'),
      carte_entier_visible: body.includes('4539 1488 0343 6467'),
      encodage_docx: /docx/.test(body),
      texte_extrait: body.includes('facture fournisseur'),
      colonnes: body.slice(0, 320).split('\\n').join(' | '),
    });
  })()`);
  const parsed = JSON.parse(result);
  console.log(JSON.stringify(parsed));
  if (parsed.erreur) return `office : ${parsed.erreur}`;
  if (!parsed.rapport) failures.push('office : rapport absent');
  if (parsed.iban_entier_visible) failures.push('office : IBAN en clair');
  if (parsed.carte_entier_visible) failures.push('office : carte en clair');
  if (!parsed.encodage_docx) failures.push("office : encodage « docx » non affiche");
  if (!parsed.texte_extrait) failures.push('office : texte du .docx non extrait');
  return null;
}

for (const scenario of SCENARIOS) {
  try {
    const error =
      scenario === 'app'
        ? await runAppFlow()
        : scenario === 'office'
          ? await runOfficeFlow()
          : await runSmokePage();
    if (error) failures.push(error);
  } catch (error) {
    failures.push(`${scenario} : ${error.message}`);
  }
}

if (consoleErrors.length) {
  failures.push(`erreurs console : ${consoleErrors.join(' / ')}`);
}

cdp.close();
chrome.kill();
vite.kill();

if (failures.length) {
  console.error('\nSMOKE_ECHEC');
  for (const failure of failures) console.error(' - ' + failure);
  process.exit(1);
}
console.log('\nSMOKE_OK (3 scenarios)');
process.exit(0);
