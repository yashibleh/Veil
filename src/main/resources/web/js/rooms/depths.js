import { h, clear, btn, ink, holdButton, sentence, toast } from '../ui.js';

const NOISE = '▓▒░#%&@$*+=?/|<>0123456789ABCDEFXYZ';

export default {
  id: 'depths', name: 'Depths', tag: 'Vault', word: 'DEPTHS', blurb: 'Where the originals sleep. Only the lens can read them.',
  mount(ctx) {
    const { S, call } = ctx;
    let sid = null, rows = [], unveil = false, chars = [], raf = 0, last = 0, pass = '';
    const head = h('p', { class: 'sentence big' });
    const crypto = h('div', { class: 'row' });
    const sessions = h('div', { class: 'tabs' });
    const list = h('div', { class: 'strata' });
    const note = h('p', { class: 'note', 'aria-live': 'polite' });
    const say = (m, bad) => { note.textContent = m || ''; note.classList.toggle('bad', !!bad); };
    const v = () => S.state.vault;

    function drawHead() {
      const x = v();
      clear(head).append('The vault is ', h('b', null, x.locked ? 'locked' : 'open'), ', kept ', h('b', null, x.persistent ? 'sealed on disk (AES-256-GCM)' : 'only in memory'), '. ', h('b', null, String(x.sessions.length)), ' sessions hold ', h('b', null, String(x.total)), ' secrets.');
    }
    function drawCrypto() {
      clear(crypto);
      const pw = ink('', (val) => { pass = val; }, { type: 'password', placeholder: 'speak the passphrase', mono: true, min: 20, max: 34 });
      const go = (path, ok) => async () => { try { await call('POST', path, { passphrase: pass }); pw.value = ''; pass = ''; await ctx.reload(); say(ok); } catch (e) { say(e.message, true); } };
      crypto.append(pw, btn('Seal to disk', go('/api/vault/persist', 'Sealed. Only the passphrase opens it.')), btn('Lock', go('/api/vault/lock', 'Locked. Memory cleared.')), btn('Unlock', go('/api/vault/unlock', 'Unlocked.')),
        holdButton('Wipe everything (hold)', async () => { try { await call('POST', '/api/vault/wipe'); sid = null; await ctx.reload(); say('Wiped.'); } catch (e) { say(e.message, true); } }));
    }
    function drawSessions() {
      clear(sessions);
      v().sessions.forEach((s) => sessions.append(h('button', { class: `tab mono ${s.id === sid ? 'sel' : ''}`, type: 'button', onclick: () => { sid = s.id; drawSessions(); loadRows(); } }, `${s.id} · ${s.count}`)));
      if (!v().sessions.length) sessions.append(h('span', { class: 'note' }, 'No sessions yet.'));
    }
    async function loadRows() {
      if (!sid) { rows = []; return drawRows(); }
      try { rows = (await call('GET', `/api/vault/session?id=${encodeURIComponent(sid)}${unveil ? '&reveal=1' : ''}`)).mappings; } catch (e) { rows = []; say(e.message, true); }
      drawRows();
    }
    function drawRows() {
      clear(list); chars = [];
      if (!rows.length) return list.append(h('p', { class: 'note' }, 'Choose a session to look into it.'));
      rows.forEach((m) => {
        const val = h('span', { class: 'mono val' });
        if (unveil && m.original != null) [...m.original].forEach((ch) => { const n = h('i', null, ch); val.append(n); chars.push({ node: n, ch, cx: 0, cy: 0 }); });
        else val.textContent = m.preview;
        list.append(h('div', { class: 'stratum' }, h('span', { class: 'mono tok' }, m.token), h('small', null, `${m.categoryName} · ${m.action}`), val));
      });
      measure();
    }
    function measure() { chars.forEach((c) => { const r = c.node.getBoundingClientRect(); c.cx = r.left + r.width / 2; c.cy = r.top + r.height / 2; }); }
    const unveilBtn = btn('Unveil with the lens', async () => {
      unveil = !unveil; unveilBtn.textContent = unveil ? 'Cover the lens' : 'Unveil with the lens'; unveilBtn.classList.toggle('on', unveil);
      ctx.setLens(unveil ? 78 : null); await loadRows(); say(unveil ? 'Move the lens over the noise. Originals are held in memory only while it is on.' : '');
    });
    function loop(ts) {
      raf = requestAnimationFrame(loop);
      if (!unveil || ts - last < 70) return;
      last = ts; if (ts % 1000 < 80) measure();
      const R = 78, px = ctx.pointer.x, py = ctx.pointer.y;
      for (const c of chars) {
        const live = (c.cx - px) ** 2 + (c.cy - py) ** 2 < R * R;
        if (live) { if (c.node.textContent !== c.ch) c.node.textContent = c.ch; c.node.className = 'live'; }
        else { c.node.textContent = c.ch === ' ' ? ' ' : NOISE[(Math.random() * NOISE.length) | 0]; c.node.className = ''; }
      }
    }
    raf = requestAnimationFrame(loop);

    const el = h('div', { class: 'depths' }, h('p', { class: 'kicker' }, '05 · Token vault'), h('h1', { class: 'giant' }, 'Depths.'), head, crypto, note, h('hr'),
      h('div', { class: 'row' }, sessions), h('div', { class: 'row' }, unveilBtn, btn('Clear this session', async () => { if (!sid) return; await call('POST', '/api/vault/clear', { id: sid }); sid = null; await ctx.reload(); })), list);
    el.addEventListener('scroll', measure, true);
    drawHead(); drawCrypto(); drawSessions();
    if (v().sessions[0]) { sid = v().sessions[0].id; drawSessions(); loadRows(); } else drawRows();
    return {
      el,
      update() { drawHead(); drawSessions(); if (!v().sessions.find((s) => s.id === sid)) { sid = v().sessions[0] ? v().sessions[0].id : null; drawSessions(); } if (!unveil) loadRows(); },
      unmount() { cancelAnimationFrame(raf); rows = []; chars = []; ctx.setLens(null); },
    };
  },
};
