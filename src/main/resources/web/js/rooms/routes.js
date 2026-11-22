import { h, clear, btn, pebble, ink, holdButton, sentence, copy, toast } from '../ui.js';

export default {
  id: 'routes', name: 'Routes', tag: 'Providers', word: 'ROUTES', blurb: 'Where your veiled words travel, and how to plug tools in.',
  mount(ctx) {
    const { S, call } = ctx;
    let sel = S.state.gatewayConfig.activeProvider;
    const beads = h('div', { class: 'beads' });
    const editor = h('div', { class: 'editor' });
    const connect = h('div', { class: 'connect' }, h('p', { class: 'note' }, 'Fetching the wiring diagrams…'));
    const provider = () => S.state.providers.find((p) => p.type === sel) || S.state.providers[0];

    function drawBeads() {
      clear(beads);
      for (const p of S.state.providers) {
        const active = p.type === S.state.gatewayConfig.activeProvider;
        beads.append(h('button', { class: `bead ${p.type === sel ? 'sel' : ''} ${active ? 'active' : ''}`, type: 'button', onclick: () => { sel = p.type; drawBeads(); drawEditor(); } },
          h('b', null, p.name), h('small', null, p.local ? 'stays on this machine' : (p.hasKey ? 'key stored' : 'no key yet')), active ? h('em', null, 'carrying traffic') : null));
      }
    }

    function drawEditor() {
      clear(editor);
      const p = provider();
      const d = { baseUrl: p.baseUrl, defaultModel: p.model, timeoutSeconds: p.timeoutSeconds, streamingEnabled: p.streaming, apiKey: '' };
      const result = h('p', { class: 'note', 'aria-live': 'polite' });
      const say = (m, bad) => { result.textContent = m; result.classList.toggle('bad', !!bad); };
      const keyField = ink('', (v) => { d.apiKey = v; }, { type: 'password', placeholder: 'paste a new key', mono: true, min: 16, max: 40 });
      const save = async () => {
        try {
          await call('POST', `/api/provider/${p.type}`, { baseUrl: d.baseUrl, defaultModel: d.defaultModel, timeoutSeconds: Number(d.timeoutSeconds) || 60, streamingEnabled: d.streamingEnabled, apiKey: d.apiKey });
          d.apiKey = ''; keyField.value = ''; await ctx.reload(); say('Saved.'); return true;
        } catch (e) { say(e.message, true); return false; }
      };
      editor.append(
        sentence('Speak to ', ink(d.baseUrl, (v) => { d.baseUrl = v; }, { mono: true, min: 20, max: 52 }), ' using the model ', ink(d.defaultModel, (v) => { d.defaultModel = v; }, { mono: true, min: 8, max: 32 }),
          ', and give up after ', ink(d.timeoutSeconds, (v) => { d.timeoutSeconds = v; }, { mono: true, min: 2, max: 4 }), ' seconds. Streaming ', pebble(d.streamingEnabled, (v) => { d.streamingEnabled = v; }, 'Streaming')),
        p.requiresKey || p.hasKey ? sentence('Key  ', h('span', { class: 'mono' }, p.keyMasked), '   ', keyField) : sentence('This route never leaves your machine, so it needs no key.'),
        h('div', { class: 'row' },
          btn('Carry my traffic here', async () => {
            if (!(await save())) return;
            try { await call('POST', '/api/provider/active', { type: p.type }); await ctx.reload(); say(`${p.name} now carries the traffic.`); } catch (e) { say(e.message, true); }
          }, 'primary'),
          btn('Save', save),
          btn('Test the line', async () => { say('dialling…'); try { const r = await call('POST', `/api/provider/${p.type}/test`); say(r.message, !r.ok); } catch (e) { say(e.message, true); } }),
          p.requiresKey ? holdButton('Forget the key (hold)', async () => { try { await call('DELETE', `/api/provider/${p.type}/key`); await ctx.reload(); say('Key forgotten.'); } catch (e) { say(e.message, true); } }) : null),
        result);
    }

    async function loadConnect() {
      try {
        const list = await call('GET', '/api/integrations');
        clear(connect);
        connect.append(h('h2', null, 'Plug your tools in'));
        for (const it of list) {
          connect.append(h('details', { class: 'wire' }, h('summary', null, h('b', null, it.name), h('small', null, it.status)),
            h('p', { class: 'note' }, `${it.mechanism}. ${it.instructions}`),
            h('pre', { class: 'mono snippet' }, it.snippet), btn('Copy snippet', () => copy(it.snippet))));
        }
      } catch (e) { clear(connect).append(h('p', { class: 'note bad' }, e.message)); }
    }

    const el = h('div', { class: 'routes' }, h('p', { class: 'kicker' }, '02 · Providers & connections'), h('h1', { class: 'giant' }, 'Routes.'), beads, editor, h('hr'), connect);
    drawBeads(); drawEditor(); loadConnect();
    return { el, update() { drawBeads(); }, unmount() {} };
  },
};
