import { h, clear, holdButton, fmt, ink } from '../ui.js';

const GLYPH = { FORWARDED: '●', BLOCKED: '✕', WARN_DENIED: '◐', PROVIDER_ERROR: '!', AUTH_REJECTED: '⌀', VALIDATION_ERROR: '?' };
const WORD = { FORWARDED: 'crossed', BLOCKED: 'turned back', WARN_DENIED: 'declined', PROVIDER_ERROR: 'provider error', AUTH_REJECTED: 'no token', VALIDATION_ERROR: 'malformed' };

export default {
  id: 'rain', name: 'Rain', tag: 'Activity', word: 'RAIN', blurb: 'Every request that crossed the veil, as it happens.',
  mount(ctx) {
    const { S, call } = ctx;
    let filter = '', only = 'all';
    const summary = h('p', { class: 'sentence big' });
    const bubbles = h('div', { class: 'bubbles' });
    const provs = h('p', { class: 'note' });
    const river = h('div', { class: 'river' });
    const catName = (k) => (S.state.categories.find((c) => c.name === k) || { display: k }).display;

    function drawSummary() {
      const a = S.state.analytics;
      clear(summary);
      if (!a.total) { summary.append('Nothing has crossed the veil yet. Wake the gateway and point a tool at it.'); }
      else summary.append(h('b', null, fmt.n(a.total)), ' requests crossed. ', h('b', null, fmt.n(a.blocked)), ' were turned back. ', h('b', null, fmt.n(a.masked)), ' secrets were hidden, in ', h('b', null, fmt.ms(a.avgMs)), ' on average.');
      clear(bubbles);
      const entries = Object.entries(a.categories || {}).sort((x, y) => y[1] - x[1]);
      const max = entries.length ? entries[0][1] : 1;
      entries.forEach(([k, v]) => { const s = 70 + Math.sqrt(v / max) * 80; bubbles.append(h('div', { class: 'bubble', style: `width:${s}px;height:${s}px` }, h('b', null, fmt.n(v)), h('small', null, catName(k)))); });
      provs.textContent = Object.entries(a.providers || {}).map(([k, v]) => `${(S.state.providers.find((p) => p.type === k) || { name: k }).name} ${fmt.n(v)}`).join('  ·  ');
    }

    function row(ev) {
      const detail = h('div', { class: 'ev-detail', hidden: true },
        h('span', { class: 'mono' }, `session ${ev.sessionId || '—'} · ${ev.source} · mask ${ev.profileName || '—'}${ev.streaming ? ' · streamed' : ''}`),
        h('span', null, Object.entries(ev.categoryCounts || {}).map(([k, v]) => h('i', { class: 'chip' }, `${catName(k)} ×${v}`))));
      const r = h('button', { class: `ev s-${ev.status}`, type: 'button', onclick: () => { detail.hidden = !detail.hidden; } },
        h('span', { class: 'mono t' }, fmt.time(ev.timestamp)), h('span', { class: 'g', title: WORD[ev.status] }, GLYPH[ev.status] || '·'),
        h('span', { class: 'w' }, WORD[ev.status] || ev.status), h('span', { class: 'p' }, `${ev.provider || '—'} ${ev.model || ''}`),
        h('span', { class: 'f' }, ev.detectedCount ? `${ev.detectedCount} found · ${ev.maskedCount} veiled` : ''), h('span', { class: 'mono d' }, ev.durationMs ? fmt.ms(ev.durationMs) : ''),
        h('span', { class: 'n' }, ev.summaryNote || ''));
      return h('div', { class: 'ev-wrap' }, r, detail);
    }
    function drawRiver() {
      clear(river);
      const f = filter.toLowerCase();
      const list = S.state.events.filter((e) => (only === 'all' || (only === 'bad' && e.status !== 'FORWARDED')) && (!f || JSON.stringify([e.status, e.provider, e.model, e.summaryNote, e.sessionId]).toLowerCase().includes(f))).slice(0, 120);
      if (!list.length) river.append(h('p', { class: 'note' }, 'Quiet. Nothing matches.'));
      list.forEach((e) => river.append(row(e)));
    }
    const chips = ['all', 'bad'].map((k) => h('button', { class: `tab ${only === k ? 'sel' : ''}`, type: 'button', 'data-k': k, onclick: (e) => { only = k; chips.forEach((c) => c.classList.toggle('sel', c.dataset.k === k)); drawRiver(); } }, k === 'all' ? 'Everything' : 'Only the trouble'));
    const el = h('div', { class: 'rain' }, h('p', { class: 'kicker' }, '04 · Activity & analytics'), h('h1', { class: 'giant' }, 'Rain.'), summary, bubbles, provs, h('hr'),
      h('div', { class: 'row' }, ...chips, ink('', (v) => { filter = v; drawRiver(); }, { placeholder: 'filter the rain', min: 14, max: 26 }),
        holdButton('Wash the history away (hold)', async () => { await call('POST', '/api/analytics/clear'); await ctx.reload(); })), river);
    drawSummary(); drawRiver();
    return {
      el,
      update() { drawSummary(); drawRiver(); },
      onEvent() { drawSummary(); drawRiver(); },
      unmount() {},
    };
  },
};
