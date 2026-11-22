import { createRenderer } from './gl.js';
import { Spring } from './spring.js';
import { boot, call, stream, isDemo } from './api.js';
import { h, clear, clamp, fmt, toast, copy } from './ui.js';
import { ROOMS } from './rooms/index.js';

const $ = (id) => document.getElementById(id);
const S = { state: null };
const pointer = { x: innerWidth / 2, y: innerHeight / 2 };
let W = innerWidth, H = innerHeight, R = 140;
let mode = 'hub', bloom = false, cur = -1, hiddenSat = -1, closingIdx = -1, hoverSat = -1, inst = null, mountSeq = 0;
let lensOverride = null, hoverUi = false, flash = 0, activity = 0, glyphGain = new Spring(0, 40, 1);

// ───────── shapes ─────────
const lerp = (a, b, t) => a + (b - a) * t;
class Shape {
  constructor(k = 150, z = 0.62) {
    this.x = new Spring(0, k, z); this.y = new Spring(0, k, z); this.hw = new Spring(0, k, z); this.hh = new Spring(0, k, z); this.r = new Spring(0, k, z);
    this.en = new Spring(0.5, 80, 0.9); this.al = new Spring(1, 100, 0.9); this.tint = [0.6, 0.55, 1]; this.tt = [0.6, 0.55, 1];
  }
  goto(x, y, hw, hh, r) { this.x.to(x); this.y.to(y); this.hw.to(hw); this.hh.to(hh); this.r.to(r); }
  snapTo(x, y, hw, hh, r) { this.x.snap(x); this.y.snap(y); this.hw.snap(hw); this.hh.snap(hh); this.r.snap(r); }
  step(dt) { for (const s of [this.x, this.y, this.hw, this.hh, this.r, this.en, this.al]) s.step(dt); for (let i = 0; i < 3; i++) this.tint[i] = lerp(this.tint[i], this.tt[i], Math.min(1, dt * 5)); }
  get visible() { return this.hw.v > 1.2 && this.hh.v > 1.2; }
  pack(scale = 1) { return { x: this.x.v, y: this.y.v, hw: Math.max(0.6, this.hw.v * scale), hh: Math.max(0.6, this.hh.v * scale), r: Math.max(0, this.r.v * scale), energy: clamp(this.en.v, 0, 1.5), alpha: clamp(this.al.v, 0, 1), tint: this.tint }; }
}
const orb = new Shape(120, 0.55), sheet = new Shape(130, 0.7), lens = new Shape(320, 0.85), dialog = new Shape(150, 0.7);
const sats = ROOMS.map(() => new Shape(140, 0.6));
const drops = Array.from({ length: 5 }, () => ({ s: new Shape(34, 0.5), live: false, phase: '', born: 0, bad: false, tx: 0, ty: 0 }));
const pulse = new Spring(0, 230, 0.3), calm = new Spring(1, 20, 1), blend = new Spring(46, 60, 1), frost = new Spring(0.3, 40, 1);
const accent = [0.55, 0.45, 1]; const accentT = [0.55, 0.45, 1];
const TINTS = [[0.55, 0.95, 0.85], [0.45, 0.7, 1], [0.8, 0.55, 1], [0.6, 0.85, 1], [0.45, 0.55, 1], [1, 0.75, 0.6], [0.75, 0.8, 0.95]];
const ACCENTS = { personal: [0.25, 0.85, 0.75], developer: [0.55, 0.45, 1], enterprise: [0.3, 0.6, 1], strict: [1, 0.3, 0.4] };
const DROP_TINT = { FORWARDED: [0.55, 0.95, 0.85], BLOCKED: [1, 0.3, 0.25], WARN_DENIED: [1, 0.7, 0.25], PROVIDER_ERROR: [1, 0.55, 0.3], AUTH_REJECTED: [0.9, 0.3, 0.8], VALIDATION_ERROR: [0.7, 0.7, 0.8] };

const renderer = createRenderer($('gl'));

// ───────── layout ─────────
function dims() {
  W = innerWidth; H = innerHeight;
  // leave a clear band at the bottom for the status text, so the ring never touches it
  const fit = (0.56 * H - 204) / 2.094;
  R = clamp(Math.min(clamp(Math.min(W, H * 0.95) * 0.15, 60, 160), fit), 56, 160);
}
const orbPos = () => (mode === 'hub' ? [W / 2, H / 2 - H * 0.06, R] : [W / 2, 30, 12]);
function satPos(i) {
  const a = -Math.PI / 2 + (i * 2 * Math.PI) / sats.length; const rr = R * 1.85; const [ox, oy] = [W / 2, H / 2 - H * 0.06];
  return [ox + Math.cos(a) * rr * (W > H ? 1.3 : 1), oy + Math.sin(a) * rr, R * 0.37];
}
function sheetRect() {
  const small = W < 760; const top = 62, bottom = 20;
  return { x: W / 2, y: (top + H - bottom) / 2, hw: small ? W / 2 - 6 : Math.min(W / 2 - 16, 810), hh: (H - top - bottom) / 2, r: small ? 24 : 36 };
}
function layout() {
  dims();
  const [ox, oy, or] = orbPos(); orb.goto(ox, oy, or, or, or);
  sats.forEach((s, i) => {
    if (mode === 'hub' && bloom && i !== hiddenSat) { const [x, y, r] = satPos(i); const k = i === hoverSat ? 1.16 : 1; s.goto(x, y, r * k, r * k, r * k); } else s.goto(ox, oy, 0, 0, 0);
  });
  if (mode === 'room') { const q = sheetRect(); sheet.goto(q.x, q.y, q.hw, q.hh, q.r); const rm = $('room').style; rm.setProperty('--rx', `${q.x - q.hw}px`); rm.setProperty('--ry', `${q.y - q.hh}px`); rm.setProperty('--rw', `${q.hw * 2}px`); rm.setProperty('--rh', `${q.hh * 2}px`); }
  else if (closingIdx >= 0) { const [x, y, r] = satPos(closingIdx); sheet.goto(x, y, r, r, r); } else sheet.goto(ox, oy, 0, 0, 0);
  blend.to(mode === 'room' ? 10 : 46); frost.to(mode === 'room' || !$('decision').hidden ? 0.74 : 0.3);
  const hit = $('orbHit').style; hit.width = hit.height = `${or * 2}px`;
  $('stage').classList.toggle('room-mode', mode === 'room');
  $('hubLine').classList.toggle('hub-off', mode === 'room'); $('orbText').style.opacity = mode === 'room' ? 0 : 1; $('hint').style.opacity = mode === 'room' ? 0 : 1;
  document.body.classList.toggle('in-room', mode === 'room');
}

// ───────── glyph layer (text the glass can bend) ─────────
const gc = document.createElement('canvas');
function drawGlyphs(word) {
  const w = (gc.width = Math.ceil(W / 2)), hh = (gc.height = Math.ceil(H / 2)); const g = gc.getContext('2d');
  g.clearRect(0, 0, w, hh);
  const fs = Math.min(w / (word.length * 0.6), hh * 0.85);
  g.font = `700 ${fs}px Bahnschrift, "Segoe UI Variable Display", "Segoe UI", system-ui, sans-serif`;
  g.lineWidth = 1.6; g.strokeStyle = 'rgba(255,255,255,.95)'; g.fillStyle = 'rgba(255,255,255,.22)'; g.textBaseline = 'alphabetic';
  const x = mode === 'hub' ? (w - g.measureText(word).width) / 2 : w * 0.03, y = mode === 'hub' ? hh * 0.72 : hh * 0.97;
  g.fillText(word, x, y); g.strokeText(word, x, y);
  g.font = '9px "Cascadia Mono", Consolas, monospace'; g.fillStyle = 'rgba(255,255,255,.55)';
  const toks = ['[EMAIL_001]', '[IP_002]', '[PHONE_001]', '[API_KEY_001]', '[NAME_003]', '[PAN_001]', '[CARD_002]', '[TOKEN_004]'];
  let seed = word.length * 9301 + 49297; const rnd = () => ((seed = (seed * 9301 + 49297) % 233280) / 233280);
  for (let i = 0; i < 46; i++) g.fillText(toks[(rnd() * toks.length) | 0], rnd() * w, rnd() * hh);
  renderer.setGlyphs(gc); glyphGain.snap(0); glyphGain.to(1);
}

// ───────── hub / state ─────────
let numShown = new Spring(0, 40, 1);
function profileAccent() { const id = S.state.gatewayConfig.activeProfileId; return ACCENTS[id] || [1, 0.72, 0.35]; }
function refreshHub() {
  const st = S.state, g = st.gateway;
  numShown.to(st.analytics.masked || 0);
  const p = st.profiles.find((x) => x.id === st.gatewayConfig.activeProfileId), prov = st.providers.find((x) => x.type === st.gatewayConfig.activeProvider);
  $('hubStatus').textContent = g.running ? `● listening · ${g.endpoint.replace('/chat/completions', '')}` : '○ resting · the gateway is not listening';
  $('hubStatus').classList.toggle('live', g.running);
  $('hubSub').textContent = `wearing the ${p ? p.name : '—'} mask · routed to ${prov ? prov.name : '—'}`;
  $('wake').textContent = g.running ? 'Let it rest' : 'Wake the veil';
  $('wake').classList.toggle('primary', !g.running);
  calm.to(g.running ? 0 : 1);
  accentT.splice(0, 3, ...profileAccent());
  orb.en.to(g.running ? 0.85 : 0.2); orb.tt = g.running ? [lerp(accentT[0], 1, 0.35), lerp(accentT[1], 1, 0.35), lerp(accentT[2], 1, 0.35)] : [0.5, 0.55, 0.7];
}
async function reload() { S.state = await call('GET', '/api/state'); refreshHub(); if (inst) inst.update(); }
let reloadTimer = 0; const reloadSoon = () => { clearTimeout(reloadTimer); reloadTimer = setTimeout(() => reload().catch(() => {}), 650); };

// ───────── satellites (DOM) ─────────
ROOMS.forEach((r, i) => {
  const b = h('button', { class: 'sat', type: 'button', 'aria-label': `${r.name}: ${r.tag}`, title: r.blurb }, h('b', null, r.name), h('small', null, r.tag));
  b.addEventListener('click', () => openRoom(i));
  b.addEventListener('mouseenter', () => { hoverSat = i; layout(); }); b.addEventListener('mouseleave', () => { if (hoverSat === i) { hoverSat = -1; layout(); } });
  b.addEventListener('focus', () => { hoverSat = i; layout(); }); b.addEventListener('blur', () => { hoverSat = -1; layout(); });
  $('sats').append(b); sats[i].dom = b; sats[i].tt = sats[i].tint = TINTS[i].slice(); sats[i].en.snap(0.55);
});
const nav = $('roomNav');
ROOMS.forEach((r, i) => nav.append(h('button', { type: 'button', 'data-i': i, onclick: () => openRoom(i) }, r.name)));

// ───────── rooms ─────────
const ctx = { S, call, reload, pointer, setLens(n) { lensOverride = n; }, lensRadius: () => lensOverride || 0 };
function unmountRoom() { mountSeq++; if (inst) { try { inst.unmount(); } catch { /* ignore */ } inst = null; } $('room').classList.remove('in'); }
function mountRoom(i, delay) {
  const seq = ++mountSeq;
  setTimeout(() => {
    if (seq !== mountSeq || mode !== 'room') return;
    const body = $('roomBody'); clear(body);
    inst = ROOMS[i].mount(ctx); body.append(inst.el); inst.update(); body.scrollTop = 0;
    $('room').hidden = false; requestAnimationFrame(() => $('room').classList.add('in'));
    nav.querySelectorAll('button').forEach((b) => b.classList.toggle('sel', Number(b.dataset.i) === i));
    const first = body.querySelector('button:not(:disabled), input'); if (first && matchMedia('(pointer: fine)').matches) first.focus({ preventScroll: true });
  }, delay);
}
function openRoom(i) {
  if (!S.state || (mode === 'room' && cur === i)) return;
  const [ox, oy] = orbPos();
  if (mode === 'hub') {
    const s = sats[i]; sheet.snapTo(s.x.v, s.y.v, s.hw.v, s.hh.v, s.r.v); closingIdx = -1; hiddenSat = i; mode = 'room'; bloom = false;
    if (!s.visible) sheet.snapTo(ox, oy, 0, 0, 0);
  } else { sheet.hw.kick(-160); sheet.hh.kick(-120); sheet.x.kick(0); unmountRoom(); }
  cur = i; sheet.tt = TINTS[i].slice(); sheet.en.to(0.5);
  layout(); drawGlyphs(ROOMS[i].word); unmountRoom(); mountRoom(i, 360);
}
function closeRoom() {
  if (mode !== 'room') return;
  unmountRoom(); closingIdx = cur; hiddenSat = cur; mode = 'hub'; bloom = true; layout(); drawGlyphs('VEIL');
  const done = cur; setTimeout(() => { if (mode !== 'hub') return; hiddenSat = -1; closingIdx = -1; const [x, y, r] = satPos(done); sats[done].snapTo(x, y, r, r, r); sheet.snapTo(...orbPos().slice(0, 2), 0, 0, 0); layout(); }, 640);
  cur = -1; setTimeout(() => { if (mode === 'hub') $('room').hidden = true; }, 350);
}
$('orbHit').addEventListener('click', () => { if (mode === 'room') closeRoom(); else { bloom = !bloom; layout(); } });
$('back').addEventListener('click', closeRoom); $('scrim').addEventListener('click', closeRoom);
$('wake').addEventListener('click', async () => {
  try { await call('POST', S.state.gateway.running ? '/api/gateway/stop' : '/api/gateway/start'); await reload(); } catch (e) { toast('ERROR', 'Gateway', e.message); }
});

// ───────── droplets: every real request becomes a drop of glass ─────────
function spawnDrop(ev) {
  const d = drops.find((x) => !x.live); if (!d) return;
  const side = Math.random() * 4 | 0; const m = 60;
  const x = side === 0 ? -m : side === 1 ? W + m : Math.random() * W, y = side === 2 ? -m : side === 3 ? H + m : Math.random() * H;
  const rad = 9 + Math.min(ev.detectedCount || 0, 12) * 2.2; const [ox, oy] = orbPos();
  d.s.snapTo(x, y, rad, rad, rad); d.s.en.snap(0.9); d.s.tt = d.s.tint = (DROP_TINT[ev.status] || DROP_TINT.FORWARDED).slice();
  d.s.x.k = d.s.y.k = 26 + Math.random() * 10; d.s.hw.k = d.s.hh.k = d.s.r.k = 120; d.rad = rad; d.live = true; d.phase = 'in'; d.born = performance.now();
  d.bad = ev.status !== 'FORWARDED'; d.tx = ox; d.ty = oy; d.s.x.to(ox); d.s.y.to(oy);
}
function stepDrops(now) {
  const [ox, oy, or] = orbPos();
  for (const d of drops) {
    if (!d.live) continue;
    d.s.x.to(d.phase === 'out' ? d.tx : ox); d.s.y.to(d.phase === 'out' ? d.ty : oy);
    const dist = Math.hypot(d.s.x.v - ox, d.s.y.v - oy);
    if (d.phase === 'in' && d.bad && dist < or + d.rad + 8) {
      d.phase = 'out'; flash = Math.max(flash, d.s.tt[0] > 0.9 ? 0.9 : 0.5); orb.x.kick((d.s.x.v - ox) * -3); orb.y.kick((d.s.y.v - oy) * -3); pulse.kick(-1.2);
      d.tx = d.s.x.v + (d.s.x.v - ox) * 3; d.ty = d.s.y.v + (d.s.y.v - oy) * 3; d.s.hw.to(0); d.s.hh.to(0); d.s.r.to(0); d.born = now;
    } else if (d.phase === 'in' && !d.bad && dist < or * 0.7 + 4) {
      d.phase = 'dying'; pulse.kick(1.1); d.s.hw.to(0); d.s.hh.to(0); d.s.r.to(0); d.born = now;
    }
    if ((d.phase === 'dying' || d.phase === 'out') && now - d.born > 520) d.live = false;
    if (now - d.born > 9000) d.live = false;
  }
}

// ───────── live stream ─────────
function onRequest(ev) {
  S.state.events.unshift(ev); if (S.state.events.length > 300) S.state.events.length = 300;
  activity = Math.min(1, activity + 0.55); spawnDrop(ev);
  if (inst && inst.onEvent) inst.onEvent('request', ev);
  reloadSoon();
}
const warns = [];
function showWarn(w) { warns.push(w); if ($('decision').hidden) nextWarn(); }
function nextWarn() {
  const w = warns[0]; const box = $('decision');
  if (!w) { box.hidden = true; dialog.goto(W / 2, H / 2, 0, 0, 0); layout(); return; }
  $('decTitle').textContent = `${w.count} sensitive item${w.count === 1 ? '' : 's'} in the mask “${w.profile}”`;
  $('decBody').textContent = Object.entries(w.categories || {}).map(([k, v]) => `${k} ×${v}`).join('  ·  ') + '. Send the request with reversible veiling applied?';
  box.hidden = false; dialog.snapTo(W / 2, H / 2, 40, 20, 20); dialog.goto(W / 2, H / 2, Math.min(280, W / 2 - 10), 150, 44); dialog.tt = [1, 0.75, 0.4]; layout();
  box.dataset.id = w.id;
}
async function answerWarn(ok) { const w = warns.shift(); try { if (w) await call('POST', `/api/warn/${w.id}`, { approve: ok }); } catch (e) { toast('WARN', 'Too late', e.message); } nextWarn(); }
$('decYes').addEventListener('click', () => answerWarn(true)); $('decNo').addEventListener('click', () => answerWarn(false));

// ───────── command bar ─────────
const cmdBox = $('cmd'), cmdIn = $('cmdIn'), cmdList = $('cmdList'); let cmdSel = 0, cmdItems = [];
const act = (fn, ok) => async () => { try { await fn(); await reload(); if (ok) toast('INFO', ok); } catch (e) { toast('ERROR', 'Could not do that', e.message); } };
const COMMANDS = () => [
  ...ROOMS.map((r, i) => ({ label: `Open ${r.name}`, hint: r.tag, run: () => openRoom(i) })),
  { label: 'Wake the gateway', hint: 'start', run: act(() => call('POST', '/api/gateway/start'), 'The veil is listening.') },
  { label: 'Let the gateway rest', hint: 'stop', run: act(() => call('POST', '/api/gateway/stop'), 'The veil is resting.') },
  { label: 'Restart the gateway', hint: 'restart', run: act(() => call('POST', '/api/gateway/restart'), 'Restarted.') },
  { label: 'Copy the endpoint', hint: 'clipboard', run: () => copy(S.state.gateway.endpoint) },
  { label: 'Copy the access token', hint: 'clipboard', run: async () => copy((await call('GET', '/api/gateway/token')).token) },
  { label: 'Lock the vault', hint: 'forget in memory', run: act(() => call('POST', '/api/vault/lock'), 'Vault locked.') },
  ...S.state.profiles.map((p) => ({ label: `Wear the ${p.name} mask`, hint: 'profile', run: act(() => call('POST', '/api/profile/active', { id: p.id }), `Now wearing ${p.name}.`) })),
  { label: 'Toggle calmer motion', hint: 'settings', run: act(() => call('POST', '/api/settings', { reducedMotion: !S.state.settings.reducedMotion })) },
  { label: 'Quit Veil', hint: 'stops the gateway', run: async () => { try { await call('POST', '/api/quit'); } catch { /* going away */ } document.body.classList.add('gone'); } },
];
function drawCmd() {
  const q = cmdIn.value.toLowerCase().split(/\s+/).filter(Boolean);
  cmdItems = COMMANDS().filter((c) => q.every((w) => (c.label + ' ' + c.hint).toLowerCase().includes(w))).slice(0, 9); cmdSel = Math.min(cmdSel, Math.max(0, cmdItems.length - 1));
  clear(cmdList); cmdItems.forEach((c, i) => cmdList.append(h('li', { role: 'option', class: i === cmdSel ? 'sel' : '', onclick: () => runCmd(i), onmousemove: () => { cmdSel = i; [...cmdList.children].forEach((n, j) => n.classList.toggle('sel', j === i)); } }, c.label, h('small', null, c.hint))));
}
function openCmd() { cmdBox.hidden = false; cmdIn.value = ''; cmdSel = 0; drawCmd(); cmdIn.focus(); dialog.snapTo(W / 2, H * 0.12 + 60, 20, 20, 20); dialog.goto(W / 2, H * 0.12 + 74, Math.min(310, W / 2 - 8), 90, 32); dialog.tt = [0.7, 0.7, 1]; }
function closeCmd() { cmdBox.hidden = true; if ($('decision').hidden) dialog.goto(W / 2, H / 2, 0, 0, 0); }
function runCmd(i) { const c = cmdItems[i]; closeCmd(); if (c) c.run(); }
cmdIn.addEventListener('input', () => { cmdSel = 0; drawCmd(); });
cmdIn.addEventListener('keydown', (e) => {
  if (e.key === 'ArrowDown') { cmdSel = (cmdSel + 1) % Math.max(1, cmdItems.length); drawCmd(); e.preventDefault(); }
  else if (e.key === 'ArrowUp') { cmdSel = (cmdSel - 1 + cmdItems.length) % Math.max(1, cmdItems.length); drawCmd(); e.preventDefault(); }
  else if (e.key === 'Enter') runCmd(cmdSel);
});
addEventListener('keydown', (e) => {
  const typing = /INPUT|TEXTAREA|SELECT/.test((document.activeElement || {}).tagName || '');
  if (!S.state) return;
  if (((e.key === '/' && !typing) || ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k')) && cmdBox.hidden) { e.preventDefault(); openCmd(); return; }
  if (e.key === 'Escape') { if (!cmdBox.hidden) closeCmd(); else if (!$('decision').hidden) answerWarn(false); else if (mode === 'room') closeRoom(); else if (bloom) { bloom = false; layout(); } return; }
  if (!typing && cmdBox.hidden && /^[1-7]$/.test(e.key)) openRoom(Number(e.key) - 1);
});

// ───────── pointer ─────────
addEventListener('pointermove', (e) => { pointer.x = e.clientX; pointer.y = e.clientY; hoverUi = !!(e.target.closest && e.target.closest('button, a, input, select, textarea, summary')); }, { passive: true });
addEventListener('resize', () => { renderer.resize(); layout(); if (S.state) drawGlyphs(mode === 'room' ? ROOMS[cur].word : 'VEIL'); });

// ───────── frame loop ─────────
let last = performance.now(), qualityLow = false;
function frame(now) {
  requestAnimationFrame(frame);
  if (document.hidden || !S.state) return;
  const reduced = matchMedia('(prefers-reduced-motion: reduce)').matches || S.state.settings.reducedMotion;
  if (now - last < (reduced ? 1000 / 24 : 1000 / 62)) return;
  if (reduced !== qualityLow) { qualityLow = reduced; renderer.setQuality(reduced ? 'low' : 'high'); }
  const dt = Math.min(0.05, (now - last) / 1000); last = now; const t = now / 1000;

  [orb, sheet, lens, dialog, ...sats].forEach((s) => s.step(dt));
  drops.forEach((d) => d.live && d.s.step(dt));
  pulse.step(dt); calm.step(dt); blend.step(dt); frost.step(dt); numShown.step(dt); glyphGain.step(dt);
  for (let i = 0; i < 3; i++) accent[i] = lerp(accent[i], accentT[i], Math.min(1, dt * 3));
  activity = Math.max(0, activity - dt * 0.35); flash = Math.max(0, flash - dt * 1.6);
  stepDrops(now);

  // cursor lens
  const ls = lensOverride || (mode === 'hub' ? (hoverUi ? 22 : 13) : 0);
  lens.goto(pointer.x, pointer.y, ls, ls, ls); lens.en.to(0.35); lens.tt = [0.85, 0.9, 1];

  // DOM follows the physics
  const [ox, oy, or] = orbPos();
  const hit = $('orbHit').style; hit.transform = `translate(${orb.x.v - or}px, ${orb.y.v - or}px)`; hit.left = hit.top = '0';
  $('orbText').style.left = `${orb.x.v}px`; $('orbText').style.top = `${orb.y.v}px`;
  $('hubLine').style.left = `${W / 2}px`; $('hubLine').style.top = `${H - 158}px`;
  $('orbNum').textContent = fmt.n(numShown.v);
  sats.forEach((s, i) => {
    const d = s.dom, r = s.hw.v; const on = r > 18 && mode === 'hub';
    d.style.width = d.style.height = `${Math.max(0, r * 2)}px`; d.style.transform = `translate(${s.x.v - r}px, ${s.y.v - r}px)`;
    d.style.opacity = on ? clamp((r - 18) / 24, 0, 1) : 0; d.style.pointerEvents = on ? 'auto' : 'none'; d.tabIndex = on ? 0 : -1;
  });
  if (mode === 'hub' && closingIdx >= 0) sats[closingIdx].dom.style.opacity = 0;
  $('wake').style.pointerEvents = mode === 'hub' ? 'auto' : 'none';
  $('orbHit').style.pointerEvents = 'auto'; $('orbHit').title = mode === 'room' ? 'Back to the veil' : (bloom ? 'Fold the rooms' : 'Open the rooms');

  const scale = 1 + (S.state.gateway.running ? 0.016 : 0.006) * Math.sin(t * 0.9) + pulse.v * 0.05;
  const shapes = [orb.pack(scale), sheet, ...sats, ...drops.filter((d) => d.live).map((d) => d.s), lens, dialog].map((s) => (s.pack ? s.pack() : s)).filter((s, i) => i === 0 || s.hw > 1.2);
  const orbRef = shapes[0]; if (orbRef.hw < 1) shapes.shift();
  const f = flash;
  renderer.draw({
    time: t, mouse: [pointer.x / W, 1 - pointer.y / H], focus: [orb.x.v / W, 1 - orb.y.v / H], pulse: clamp(activity + (S.state.gateway.running ? 0.15 : 0), 0, 1), calm: calm.v, accent,
    flash: [f * 0.35, f * 0.05, f * 0.03], glyphGain: clamp(glyphGain.v, 0, 1) * (mode === 'room' ? 0.8 : 1), shapes,
    blend: Math.max(4, blend.v), disp: 0.07, refr: 0.5, frost: clamp(frost.v, 0, 1), bevel: 42,
  });
}

// ───────── boot ─────────
async function start() {
  try { S.state = await boot(); } catch (e) { $('boot').firstElementChild.textContent = `could not reach Veil: ${e.message}`; return; }
  if (isDemo()) $('demoTag').hidden = false;
  dims(); sats.forEach((s) => s.snapTo(W / 2, H / 2, 0, 0, 0)); orb.snapTo(W / 2, H / 2 - H * 0.06, 0, 0, 0); orb.tint = [0.5, 0.55, 0.7]; orb.tt = [0.5, 0.55, 0.7];
  dialog.snapTo(W / 2, H / 2, 0, 0, 0); lens.snapTo(pointer.x, pointer.y, 0, 0, 0); sheet.snapTo(W / 2, H / 2, 0, 0, 0); accent.splice(0, 3, ...profileAccent());
  refreshHub(); numShown.snap(S.state.analytics.masked || 0); layout(); drawGlyphs('VEIL');
  $('orbText').style.opacity = 1;
  const [ox, oy, or] = orbPos(); orb.goto(ox, oy, or, or, or);
  stream((type, d) => {
    if (type === 'request') onRequest(d);
    else if (type === 'gateway') { S.state.gateway.running = d.running; refreshHub(); if (inst) inst.update(); reloadSoon(); }
    else if (type === 'notify') toast(d.level, d.title, d.message);
    else if (type === 'warn') showWarn(d);
    else if (type === 'link') $('link').hidden = d.up;
  });
  requestAnimationFrame(frame);
  setTimeout(() => $('boot').classList.add('off'), 250);
  setTimeout(() => { bloom = true; layout(); }, 1400);
}
start();
