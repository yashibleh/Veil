import { h, clear, btn, holdButton, toast, copy } from '../ui.js';
import { chatStream } from '../api.js';
import { renderMd } from '../md.js';

let remembered = null; // the open conversation survives closing and reopening the room

export default {
  id: 'salon', name: 'Salon', tag: 'Conversation', word: 'SALON', fill: true,
  blurb: 'Talk to the model. Veiled on the way out, restored on the way back.',
  mount(ctx) {
    const { S, call } = ctx;
    let threads = [], cur = null, busy = false, ctl = null, stick = true, provider = S.state.gatewayConfig.activeProvider, detTimer = 0, detSeq = 0;

    const tabs = h('div', { class: 'tabs threads' });
    const bar = h('div', { class: 'row salon-bar' });
    const msgs = h('div', { class: 'msgs', tabindex: '-1' });
    const trimNote = h('p', { class: 'note', hidden: true });
    const hint = h('p', { class: 'note composer-hint', 'aria-live': 'polite' });
    const area = h('textarea', { class: 'composer-area', rows: 1, spellcheck: 'false', placeholder: 'Say something…  Enter sends, Shift+Enter adds a line' });
    const send = h('button', { class: 'send', type: 'button', 'aria-label': 'Send' }, '↑');
    const scene = h('textarea', { class: 'scene', rows: 2, spellcheck: 'false', placeholder: 'Optional: set the scene for the model (a system prompt). It is veiled too.' });
    const provSel = h('select', { class: 'ink', 'aria-label': 'Provider' }, S.state.providers.map((p) => h('option', { value: p.type, selected: p.type === provider }, p.name)));
    provSel.addEventListener('change', () => { provider = provSel.value; });

    const scrollEnd = (force) => { if (force || stick) msgs.scrollTop = msgs.scrollHeight; };
    msgs.addEventListener('scroll', () => { stick = msgs.scrollHeight - msgs.scrollTop - msgs.clientHeight < 90; });
    const grow = () => { area.style.height = 'auto'; area.style.height = `${Math.min(area.scrollHeight, 190)}px`; };

    // ── message elements ──
    function turnEl(role, who) {
      const txt = h('div', { class: 'txt' });
      const whoEl = h('span', { class: 'who' }, who);
      const note = h('div', { class: 'vnote', hidden: true });
      const root = h('article', { class: `turn ${role}` }, whoEl, txt, note);
      msgs.append(root);
      return { root, txt, whoEl, note };
    }
    function setNote(el, info) {
      const cats = Object.entries(info.categories || {}).map(([k, v]) => `${k} ×${v}`).join(' · ');
      if (!info.detected) { el.note.hidden = true; return; }
      el.note.hidden = false; clear(el.note);
      const seen = h('pre', { class: 'wire mono', hidden: true }, info.sanitized || '');
      const label = info.masked ? `◌ ${info.masked} veiled before it left` : `◌ ${info.detected} found, all allowed by your mask`;
      el.note.append(h('button', { type: 'button', class: 'vtoggle', onclick: () => { seen.hidden = !seen.hidden; } }, `${label} — ${cats}`), seen);
    }
    function actions(el, text) {
      el.root.append(h('div', { class: 'acts' }, h('button', { type: 'button', onclick: () => copy(text) }, 'copy')));
    }
    const dots = () => h('span', { class: 'dots' }, h('i'), h('i'), h('i'));

    function empty() {
      clear(msgs).append(h('div', { class: 'empty' },
        h('p', { class: 'sentence big' }, 'Say something.'),
        h('p', { class: 'note' }, 'Whatever you write is checked on this machine first. Secrets are swapped for tokens before anything leaves, and swapped back when the answer returns. The conversation can run as long as you like.'),
        cur ? null : scene));
    }
    function drawThread() {
      clear(msgs); trimNote.hidden = true;
      if (!cur || !cur.messages.length) return empty();
      for (const m of cur.messages) {
        if (m.role === 'user') {
          const u = turnEl('user', 'you'); u.txt.textContent = m.content;
          setNote(u, { detected: m.detected, masked: m.masked, categories: m.categories, sanitized: m.wire });
        } else {
          const a = turnEl('assistant', `${m.provider || 'model'} · ${m.model || ''}`); renderMd(m.content, a.txt); actions(a, m.content);
        }
      }
    }
    function drawTabs() {
      clear(tabs);
      threads.forEach((t) => tabs.append(h('button', { class: `tab ${cur && cur.id === t.id ? 'sel' : ''}`, type: 'button', title: t.title, onclick: () => openThread(t.id) }, t.title.length > 26 ? `${t.title.slice(0, 25)}…` : t.title)));
      tabs.append(h('button', { class: 'tab plus', type: 'button', onclick: newThread }, '＋ new'));
    }
    function drawBar() {
      clear(bar);
      bar.append(h('span', { class: 'sentence' }, 'Talking to '), provSel);
      if (cur) {
        bar.append(btn('Export .md', exportMd), holdButton('Forget it (hold)', async () => {
          await call('DELETE', `/api/chat/${cur.id}`); remembered = null; cur = null; await refreshThreads(); drawThread(); drawBar(); await ctx.reload();
        }));
      }
    }
    async function refreshThreads() { try { threads = await call('GET', '/api/chat'); } catch { threads = []; } drawTabs(); }
    async function openThread(id) {
      try { cur = await call('GET', `/api/chat/${id}`); remembered = id; } catch (e) { toast('ERROR', 'Conversation', e.message); return; }
      drawTabs(); drawThread(); drawBar(); stick = true; scrollEnd(true);
    }
    function newThread() { cur = null; remembered = null; drawTabs(); drawThread(); drawBar(); area.focus(); }
    function exportMd() {
      const body = `# ${cur.title}\n\n` + cur.messages.map((m) => `**${m.role === 'user' ? 'You' : 'Model'}**\n\n${m.content}\n`).join('\n');
      const a = h('a', { href: URL.createObjectURL(new Blob([body], { type: 'text/markdown' })), download: `veil-conversation-${cur.id}.md` });
      document.body.append(a); a.click(); a.remove(); setTimeout(() => URL.revokeObjectURL(a.href), 4000);
    }
    const setBusy = (b) => { busy = b; send.textContent = b ? '■' : '↑'; send.setAttribute('aria-label', b ? 'Stop' : 'Send'); send.classList.toggle('stop', b); };

    // ── sending ──
    async function sendMessage() {
      const text = area.value.trim();
      if (!text || busy) return;
      setBusy(true);
      try {
        if (!cur) {
          const c = await call('POST', '/api/chat', { system: scene.value.trim() });
          cur = { ...c, messages: [] }; remembered = c.id; threads.unshift(c); drawTabs(); drawBar();
        }
        if (!cur.messages.length) clear(msgs);
        area.value = ''; grow(); hint.textContent = '';
        const u = turnEl('user', 'you'); u.txt.textContent = text;
        const a = turnEl('assistant', '…'); a.root.classList.add('pending'); a.txt.append(dots());
        stick = true; scrollEnd(true);
        let acc = '', raf = 0, got = false, failed = false;
        const paint = () => { raf = 0; renderMd(acc, a.txt); scrollEnd(); };
        ctl = new AbortController();
        try {
          await chatStream({ id: cur.id, text, provider }, (m) => {
            if (m.t === 'meta') {
              setNote(u, { detected: m.detected, masked: m.masked, categories: m.categories, sanitized: m.sanitized });
              a.whoEl.textContent = `${m.provider} · ${m.model}`;
              trimNote.hidden = !m.trimmed; trimNote.textContent = m.trimmed ? `The ${m.trimmed} oldest messages have slipped out of the model's memory (adjust in Quiet). They are still here.` : '';
            } else if (m.t === 'delta') {
              if (!got) { got = true; a.root.classList.remove('pending'); clear(a.txt); }
              acc += m.d; if (!raf) raf = requestAnimationFrame(paint);
            } else if (m.t === 'error') {
              failed = true; a.root.classList.remove('pending'); a.root.classList.add('failed');
              clear(a.txt).append(m.blocked ? `⛔ ${m.message}` : m.message); if (m.blocked) u.root.classList.add('refused');
            }
          }, ctl.signal);
        } catch (e) {
          if (e.name !== 'AbortError') { failed = true; a.root.classList.remove('pending'); a.root.classList.add('failed'); clear(a.txt).append(e.message); }
        }
        if (raf) cancelAnimationFrame(raf);
        a.root.classList.remove('pending');
        if (!failed && acc) { renderMd(acc, a.txt); actions(a, acc); }
        else if (!failed && !acc) { a.root.remove(); }
        try { cur = await call('GET', `/api/chat/${cur.id}`); } catch { /* keep what we have */ }
        await refreshThreads();
      } catch (e) { toast('ERROR', 'Conversation', e.message); }
      finally { ctl = null; setBusy(false); area.focus(); scrollEnd(); ctx.reload().catch(() => {}); }
    }

    send.addEventListener('click', () => { if (busy && ctl) ctl.abort(); else sendMessage(); });
    area.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) { e.preventDefault(); sendMessage(); }
      else if (e.key === 'Escape') { e.stopPropagation(); area.blur(); }
    });
    area.addEventListener('input', () => {
      grow(); clearTimeout(detTimer);
      detTimer = setTimeout(async () => {
        const mine = ++detSeq, t = area.value;
        if (!t.trim()) { hint.textContent = ''; return; }
        try {
          const r = await call('POST', '/api/detect', { text: t });
          if (mine !== detSeq) return;
          const block = r.entities.filter((e) => e.action === 'BLOCK').length, veil = r.entities.filter((e) => e.action !== 'ALLOW' && e.action !== 'BLOCK').length;
          hint.classList.toggle('bad', block > 0);
          hint.textContent = block ? `${block} item(s) in this message will make your mask refuse it.` : veil ? `${veil} item(s) will be veiled before this leaves.` : '';
        } catch { hint.textContent = ''; }
      }, 240);
    });

    const el = h('div', { class: 'salon' },
      h('div', { class: 'salon-head' }, h('p', { class: 'kicker' }, '08 · Conversation'), h('h1', { class: 'giant small' }, 'Salon.')),
      tabs, bar, msgs, trimNote, h('div', { class: 'composer' }, h('div', { class: 'composer-box' }, area, send), hint));

    drawTabs(); drawBar(); empty();
    refreshThreads().then(() => { if (remembered && threads.find((t) => t.id === remembered)) openThread(remembered); });
    return {
      el,
      update() { if (!busy) { /* provider list may have changed */ } },
      unmount() { if (ctl) ctl.abort(); clearTimeout(detTimer); },
    };
  },
};
