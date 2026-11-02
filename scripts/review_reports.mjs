// Browser review of generated HTML reports through a local headless Chromium (DevTools protocol).
// Opens each report with file://, captures full-page screenshots per viewport and tab, measures
// layout problems and runs an interaction smoke test. No dependencies; Node 22+ (global WebSocket).
// Usage: node scripts/review_reports.mjs --browser PATH --out DIR [--shots] [--sizes 1440x900,820x1180,390x844] REPORT_DIR...
import { spawn } from 'node:child_process';
import { mkdtempSync, readFileSync, writeFileSync, mkdirSync, existsSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve, basename } from 'node:path';
import { pathToFileURL } from 'node:url';

const args = process.argv.slice(2);
const opt = (k, d) => { const i = args.indexOf(k); if (i < 0) return d; const v = args[i + 1]; args.splice(i, 2); return v; };
const flag = k => { const i = args.indexOf(k); if (i < 0) return false; args.splice(i, 1); return true; };
const browserPath = opt('--browser');
const out = resolve(opt('--out', 'build/report-review'));
const sizes = opt('--sizes', '1440x900,820x1180,390x844').split(',').map(s => s.split('x').map(Number));
const tabs = opt('--tabs', 'overview,offense,defense,flow,timeline').split(',');
const shots = flag('--shots');
const interact = !flag('--no-interact');
const playback = flag('--playback');
const resizeCheck = flag('--resize');
const reports = args.map(a => resolve(a));
if (!browserPath || !reports.length) { console.error('usage: --browser PATH --out DIR REPORT_DIR...'); process.exit(2); }
mkdirSync(out, { recursive: true });

const profile = mkdtempSync(join(tmpdir(), 'phen-review-'));
const proc = spawn(browserPath, ['--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
  '--hide-scrollbars', '--remote-debugging-port=0', `--user-data-dir=${profile}`, 'about:blank'], { stdio: 'ignore' });
let port;
for (let i = 0; i < 100 && !port; i++) {
  await new Promise(r => setTimeout(r, 100));
  const f = join(profile, 'DevToolsActivePort');
  if (existsSync(f)) port = readFileSync(f, 'utf8').split('\n')[0].trim();
}
if (!port) throw new Error('DevTools port not found');
const version = await (await fetch(`http://127.0.0.1:${port}/json/version`)).json();
const ws = new WebSocket(version.webSocketDebuggerUrl);
await new Promise((r, j) => { ws.onopen = r; ws.onerror = j; });
let nextId = 0; const pending = new Map(); const listeners = [];
ws.onmessage = m => { const d = JSON.parse(m.data); if (d.id && pending.has(d.id)) { const { res, rej } = pending.get(d.id); pending.delete(d.id); d.error ? rej(new Error(d.error.message)) : res(d.result); } else for (const l of listeners) l(d); };
const send = (method, params = {}, sessionId) => new Promise((res, rej) => { const id = ++nextId; pending.set(id, { res, rej }); ws.send(JSON.stringify({ id, method, params, sessionId })); });
const sleep = ms => new Promise(r => setTimeout(r, ms));

const { targetId } = await send('Target.createTarget', { url: 'about:blank' });
const { sessionId: S } = await send('Target.attachToTarget', { targetId, flatten: true });
const page = (m, p) => send(m, p, S);
await page('Page.enable'); await page('Runtime.enable'); await page('Network.enable');
let errors = [], requests = [], loaded = null;
listeners.push(d => {
  if (d.sessionId !== S) return;
  if (d.method === 'Runtime.exceptionThrown') errors.push(d.params.exceptionDetails.exception?.description || d.params.exceptionDetails.text);
  if (d.method === 'Runtime.consoleAPICalled' && d.params.type === 'error') errors.push(d.params.args.map(a => a.value ?? a.description).join(' '));
  if (d.method === 'Network.requestWillBeSent') requests.push(d.params.request.url);
  if (d.method === 'Page.loadEventFired' && loaded) loaded();
});
const evaluate = async expr => { const r = await page('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true }); if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description || r.exceptionDetails.text); return r.result.value; };
// A fragment-only change is a same-document navigation without a load event, so reset first.
async function open(url) { await page('Page.navigate', { url: 'about:blank' }); await sleep(30); const done = new Promise(r => { loaded = r; }); await page('Page.navigate', { url }); await Promise.race([done, sleep(15000)]); await sleep(120); }

// Layout audit evaluated inside the page for the visible tab.
const AUDIT = `(() => {
  const vw = innerWidth, de = document.documentElement;
  const r = { pageOverflowPx: Math.max(0, de.scrollWidth - vw), smallText: [], svgTextTooSmall: 0, svgTextOutside: 0, svgOverlaps: 0, overlapSamples: [], offscreen: [], escapesCard: [] };
  const panel = document.querySelector('section[role=tabpanel].on');
  const visible = n => { const b = n.getBoundingClientRect(); return b.width > 0 && b.height > 0; };
  for (const n of document.querySelectorAll('body *')) {
    if (n.closest('svg') || !visible(n) || n.closest('details:not([open])')) continue;
    const own = [...n.childNodes].some(c => c.nodeType === 3 && c.textContent.trim());
    if (!own) continue;
    const fs = parseFloat(getComputedStyle(n).fontSize);
    if (fs < 11.5) r.smallText.push(n.tagName + '.' + n.className + ':' + fs);
    const b = n.getBoundingClientRect();
    if (b.right > vw + 1 && !n.closest('.scroll') && !n.closest('nav.tabs')) r.offscreen.push(n.tagName + '.' + n.className);
    const card = n.closest('.card'); if (card && b.right > card.getBoundingClientRect().right + 1) r.escapesCard.push(n.tagName + '.' + n.className + ':' + n.textContent.slice(0, 30));
  }
  for (const svg of (panel || document).querySelectorAll('svg')) {
    if (!visible(svg)) continue;
    const sb = svg.getBoundingClientRect(), vb = svg.viewBox.baseVal, k = vb && vb.width ? sb.width / vb.width : 1;
    const texts = [...svg.querySelectorAll('text')].map(t => ({ t, b: t.getBoundingClientRect(), fs: parseFloat(t.getAttribute('font-size') || 12) * k }));
    for (const x of texts) { if (x.fs < 10.5) r.svgTextTooSmall++; if (x.b.left < sb.left - 1 || x.b.right > sb.right + 1) { r.svgTextOutside++; r.overlapSamples.push('outside: ' + x.t.textContent); } }
    for (let i = 0; i < texts.length && i < 400; i++) for (let j = i + 1; j < texts.length && j < 400; j++) {
      const a = texts[i].b, b = texts[j].b; if (a.left < b.right - 1 && b.left < a.right - 1 && a.top < b.bottom - 1 && b.top < a.bottom - 1) { r.svgOverlaps++; if (r.overlapSamples.length < 4) r.overlapSamples.push(texts[i].t.textContent + ' | ' + texts[j].t.textContent); } }
  }
  r.smallText = [...new Set(r.smallText)].slice(0, 8); r.escapesCard = [...new Set(r.escapesCard)].slice(0, 8); r.offscreen = [...new Set(r.offscreen)].slice(0, 8);
  r.domNodes = document.getElementsByTagName('*').length; r.svgNodes = document.querySelectorAll('svg *').length;
  return r;
})()`;

// Exercise controls of the visible tab; returns counts of actions performed.
const INTERACT = `(async () => {
  const p = document.querySelector('section[role=tabpanel].on'); let n = 0; const wait = ms => new Promise(r => setTimeout(r, ms));
  for (const b of p.querySelectorAll('button.info')) { b.click(); n++; if (b.nextElementSibling) {} }
  for (const d of p.querySelectorAll('details')) { d.open = true; n++; }
  for (const s of [...p.querySelectorAll('select')]) { if (!s.isConnected) continue; for (const o of [...s.options]) { s.value = o.value; s.dispatchEvent(new Event('change')); n++; } }
  for (const s of [...p.querySelectorAll('input[type=range]')]) { if (!s.isConnected) continue; for (const v of [s.max, s.min, (+s.max + +s.min) / 2]) { s.value = v; s.dispatchEvent(new Event('input')); n++; } }
  for (const r of [...p.querySelectorAll('svg [tabindex]')].slice(0, 20)) { if (!r.isConnected) continue; r.dispatchEvent(new MouseEvent('click', { bubbles: true })); r.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true })); r.focus(); n++; }
  for (const svg of [...p.querySelectorAll('svg')]) { const b = svg.getBoundingClientRect(); for (const f of [.2, .5, .8]) { svg.dispatchEvent(new PointerEvent('pointermove', { clientX: b.left + b.width * f, clientY: b.top + b.height / 2, bubbles: true, pointerType: 'mouse' })); n++; } }
  for (const label of ['Next', 'Previous', 'Next event', 'Previous event']) for (const b of p.querySelectorAll('button')) if (b.getAttribute('aria-label') === label || b.textContent === label) { b.click(); b.click(); n++; }
  const play = [...p.querySelectorAll('button')].find(b => b.textContent === 'Play');
  if (play) { play.click(); await wait(400); play.click(); n++; }
  return n;
})()`;

const result = { browser: version.Browser, generated: new Date().toISOString(), reports: [] };
for (const dir of reports) {
  const file = join(dir, 'index.html'); const name = basename(dir);
  const rep = { name, bytes: readFileSync(file).length, views: [] };
  for (const [w, h] of sizes) {
    await page('Emulation.setDeviceMetricsOverride', { width: w, height: h, deviceScaleFactor: 1, mobile: w < 600 });
    for (const tab of tabs) {
      errors = []; requests = [];
      await open(pathToFileURL(file).href + '#' + tab);
      const timing = await evaluate(`(() => { const n = performance.getEntriesByType('navigation')[0]; return { domContentLoadedMs: Math.round(n.domContentLoadedEventEnd), loadMs: Math.round(n.loadEventEnd) }; })()`);
      const audit = await evaluate(AUDIT);
      const switchMs = await evaluate(`(() => { const t = performance.now(); const b = document.getElementById('t-${tab === 'overview' ? 'timeline' : 'overview'}'); b.click(); document.getElementById('t-${tab}').click(); return Math.round((performance.now() - t) * 10) / 10; })()`);
      if (shots) {
        const { cssContentSize } = await page('Page.getLayoutMetrics');
        const fullH = Math.min(16000, Math.ceil(cssContentSize.height));
        await page('Emulation.setDeviceMetricsOverride', { width: w, height: fullH, deviceScaleFactor: 1, mobile: w < 600 });
        await sleep(80);
        const shot = await page('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false });
        mkdirSync(join(out, name), { recursive: true });
        writeFileSync(join(out, name, `${w}x${h}-${tab}.png`), Buffer.from(shot.data, 'base64'));
        await page('Emulation.setDeviceMetricsOverride', { width: w, height: h, deviceScaleFactor: 1, mobile: w < 600 });
      }
      // Playback cost: 200 seeks across the recording through the real slider handler.
      const playbackSeekMs = playback && tab === 'timeline' ? await evaluate(`(() => { const s = document.querySelector('input[aria-label="Playback position"]'); if (!s) return null; const t = performance.now(); for (let i = 0; i < 200; i++) { s.value = String(Math.round(i * 1000 / 199)); s.dispatchEvent(new Event('input')); } return Math.round((performance.now() - t) * 10) / 10; })()`) : undefined;
      let actions = 0;
      if (interact) { actions = await evaluate(INTERACT); await sleep(50); }
      const after = interact ? await evaluate(AUDIT) : null;
      const external = requests.filter(u => !u.startsWith('file:') && !u.startsWith('data:'));
      rep.views.push({ viewport: `${w}x${h}`, tab, timing, switchMs, playbackSeekMs, audit, afterInteraction: after && { pageOverflowPx: after.pageOverflowPx, svgTextOutside: after.svgTextOutside, svgOverlaps: after.svgOverlaps }, actions, errors: [...errors], externalRequests: external });
    }
  }
  if (resizeCheck) {
    // Change a filter at desktop width, shrink to phone width, and confirm the rebuilt charts kept it.
    errors = [];
    await page('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });
    await open(pathToFileURL(file).href + '#offense');
    const before = await evaluate(`(() => { const s = document.querySelector('#p-offense select'); if (!s || s.options.length < 2) return null; s.value = s.options[1].value; s.dispatchEvent(new Event('change')); return { value: s.value, svgWidth: document.querySelector('#p-offense svg').getBoundingClientRect().width }; })()`);
    await page('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true });
    await sleep(600);
    const after = await evaluate(`(() => { const s = document.querySelector('#p-offense select'); const svg = document.querySelector('#p-offense svg'); return { value: s && s.value, svgViewBoxWidth: svg && svg.viewBox.baseVal.width, overflow: document.documentElement.scrollWidth - innerWidth }; })()`);
    rep.resize = { before, after, preserved: !before || before.value === after.value, errors: [...errors] };
  }
  result.reports.push(rep);
  const bad = rep.views.filter(v => v.errors.length || v.externalRequests.length || v.audit.pageOverflowPx > 0);
  console.log(`${name}: ${rep.views.length} views, ${bad.length} with errors/overflow/requests`);
}
writeFileSync(join(out, 'review.json'), JSON.stringify(result, null, 2));
ws.close(); proc.kill();
await sleep(300);
try { rmSync(profile, { recursive: true, force: true }); } catch { /* browser may still hold files */ }
