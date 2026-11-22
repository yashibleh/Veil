import { h, clear, btn, sentence } from '../ui.js';

const SAMPLE = 'Hi team, please email rahul.sharma@example.com about the outage on 192.168.1.12. His number is +91 98765 43210, PAN ABCDE1234F, and the test card is 4111 1111 1111 1111.';
const OUT = { MASK: 'tok', WARN: 'tok', REALISTIC_FAKE: 'fake', ALLOW: 'allow', BLOCK: 'block' };

export default {
  id: 'atelier', name: 'Atelier', tag: 'Playground', word: 'ATELIER', blurb: 'Type, and watch the veil fall over your words.',
  mount(ctx) {
    const { S, call } = ctx;
    let ents = [], timer = 0, seq = 0, provider = S.state.gatewayConfig.activeProvider;
    const mirror = h('div', { class: 'mirror', 'aria-hidden': 'true' });
    const area = h('textarea', { class: 'editor-area', spellcheck: 'false', placeholder: 'Paste a prompt. Emails, hosts, keys, cards and names will light up as they are found…', rows: 6 });
    const out = h('div', { class: 'preview' });
    const verdict = h('p', { class: 'note' });
    const sealed = h('div', { class: 'result', hidden: true });
    const reply = h('div', { class: 'result', hidden: true });

    function drawMirror() {
      const t = area.value; clear(mirror); let cur = 0;
      for (const e of ents) { mirror.append(t.slice(cur, e.start), h('mark', { class: `m a-${e.action}` }, t.slice(e.start, e.end))); cur = e.end; }
      mirror.append(t.slice(cur) + '\u200b'); mirror.scrollTop = area.scrollTop;
    }
    function drawOut() {
      const t = area.value; clear(out); let cur = 0; const counters = {}; let blocked = 0, held = 0, veiled = 0;
      for (const e of ents) {
        out.append(t.slice(cur, e.start)); cur = e.end;
        if (e.action === 'ALLOW') out.append(h('span', { class: 'o allow' }, t.slice(e.start, e.end)));
        else if (e.action === 'BLOCK') { blocked++; out.append(h('span', { class: 'o block' }, `⛔ ${e.prefix}`)); }
        else if (e.action === 'REALISTIC_FAKE') { veiled++; out.append(h('span', { class: 'o fake' }, `⟨${e.prefix.toLowerCase()} lookalike⟩`)); }
        else { if (e.action === 'WARN') held++; veiled++; counters[e.prefix] = (counters[e.prefix] || 0) + 1; out.append(h('span', { class: `o ${OUT[e.action]}` }, `[${e.prefix}_${String(counters[e.prefix]).padStart(3, '0')}]`)); }
      }
      out.append(t.slice(cur));
      verdict.classList.toggle('bad', blocked > 0);
      verdict.textContent = !t.trim() ? '' : !ents.length ? 'Nothing sensitive found. This would pass untouched.'
        : blocked ? `${blocked} item(s) would make the profile refuse this request outright.` : `${veiled} would be veiled${held ? `, ${held} of them after asking you` : ''}. Preview only: the real seal may number tokens differently.`;
    }
    function detect() {
      clearTimeout(timer);
      timer = setTimeout(async () => {
        const mine = ++seq;
        if (!area.value.trim()) { ents = []; drawMirror(); drawOut(); return; }
        try { const r = await call('POST', '/api/detect', { text: area.value }); if (mine === seq) { ents = r.entities; drawMirror(); drawOut(); } } catch (e) { verdict.textContent = e.message; verdict.classList.add('bad'); }
      }, 170);
    }
    area.addEventListener('input', () => { drawMirror(); detect(); });
    area.addEventListener('scroll', () => { mirror.scrollTop = area.scrollTop; });

    const show = (box, label, text, bad) => { box.hidden = false; clear(box).append(h('small', null, label), h('pre', { class: bad ? 'bad' : '' }, text)); };
    const seal = btn('Seal it for real', async () => {
      try { const r = await call('POST', '/api/sanitize', { text: area.value }); show(sealed, `what leaves this machine · session ${r.session}`, r.blocked ? `⛔ ${r.reason}` : r.sanitized, r.blocked); } catch (e) { show(sealed, 'error', e.message, true); }
    }, 'primary');
    const provSel = h('select', { class: 'ink', 'aria-label': 'Provider' }, S.state.providers.map((p) => h('option', { value: p.type, selected: p.type === provider }, p.name)));
    provSel.addEventListener('change', () => { provider = provSel.value; });
    const send = btn('Send it', async () => {
      show(reply, 'waiting for the provider…', '');
      try { const r = await call('POST', '/api/send', { text: area.value, provider }); show(reply, 'what came back, with your words restored', r.ok ? r.reply : r.message, !r.ok); } catch (e) { show(reply, 'error', e.message, true); }
    });

    const el = h('div', { class: 'atelier' }, h('p', { class: 'kicker' }, '06 · Prompt playground'), h('h1', { class: 'giant' }, 'Atelier.'),
      h('div', { class: 'editor' }, mirror, area), h('div', { class: 'row' }, btn('Try a sample', () => { area.value = SAMPLE; drawMirror(); detect(); }), seal, sentence('or send it through ', provSel, ' '), send),
      h('p', { class: 'kicker' }, 'What would leave'), out, verdict, sealed, reply);
    return { el, update() {}, unmount() { clearTimeout(timer); } };
  },
};
