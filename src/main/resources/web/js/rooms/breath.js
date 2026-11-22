import { h, btn, pebble, ink, holdButton, sentence, copy, toast, fmt } from '../ui.js';

export default {
  id: 'breath', name: 'Breath', tag: 'Gateway', word: 'BREATH', blurb: 'Wake it, rest it, shape how it listens.',
  mount(ctx) {
    const { S, call } = ctx;
    const status = h('h1', { class: 'giant' });
    const endpoint = h('button', { class: 'endpoint mono', type: 'button', title: 'Copy the endpoint', onclick: () => copy(S.state.gateway.endpoint) });
    const feedback = h('p', { class: 'note', 'aria-live': 'polite' });
    const say = (m, bad) => { feedback.textContent = m || ''; feedback.classList.toggle('bad', !!bad); };
    const run = (fn) => async () => { try { say('…'); await fn(); await ctx.reload(); say(''); } catch (e) { say(e.message, true); } };

    const wake = btn('Wake the veil', run(() => call('POST', '/api/gateway/start')), 'primary');
    const rest = btn('Let it rest', run(() => call('POST', '/api/gateway/stop')));
    const restart = btn('Restart', run(() => call('POST', '/api/gateway/restart')));
    const pulse = btn('Pulse check', async () => {
      try {
        say('listening for a heartbeat…');
        const r = await call('POST', '/api/gateway/health');
        say(r.ok ? 'It answered. Healthy.' : (r.message || `It answered with ${r.status}.`), !r.ok);
      } catch (e) { say(e.message, true); }
    });

    const c0 = S.state.gatewayConfig;
    const draft = { port: c0.port, rate: c0.rateLimitPerMinute, mb: fmt.mb(c0.maxRequestBodyBytes), timeout: c0.requestTimeoutSeconds, auth: c0.requireLocalAuth, stream: c0.streamingAllowed };
    const save = btn('Seal these settings', run(async () => {
      await call('POST', '/api/gateway/config', {
        port: draft.port, rateLimitPerMinute: draft.rate, maxRequestBodyBytes: Math.round(draft.mb * 1048576),
        requestTimeoutSeconds: draft.timeout, requireLocalAuth: draft.auth, streamingAllowed: draft.stream,
      });
      save.hidden = true; toast('INFO', 'Sealed', 'Gateway settings saved.');
    }), 'primary');
    save.hidden = true;
    const num = (key, v, o) => ink(v, (x) => { draft[key] = Number(x) || 0; save.hidden = false; }, { mono: true, min: 2, max: 7, ...o });

    const TOKEN_MASK = 'veil_gw_••••••••••••••••';
    const tokenVal = h('span', { class: 'mono token' }, TOKEN_MASK);
    let timer;
    const reveal = btn('Reveal for 8 seconds', async () => {
      const { token } = await call('GET', '/api/gateway/token');
      tokenVal.textContent = token; clearTimeout(timer); timer = setTimeout(() => { tokenVal.textContent = TOKEN_MASK; }, 8000);
    });
    const copyTok = btn('Copy', async () => { const { token } = await call('GET', '/api/gateway/token'); copy(token); });
    const rotate = holdButton('Rotate (hold)', async () => {
      await call('POST', '/api/gateway/token/rotate'); tokenVal.textContent = TOKEN_MASK;
      toast('WARN', 'Token rotated', 'Update your tools with the new token.');
    });

    const el = h('div', { class: 'breath' },
      h('p', { class: 'kicker' }, '01 · Gateway'), status, endpoint,
      h('div', { class: 'row' }, wake, rest, restart, pulse), feedback, h('hr'),
      sentence('Listen on ', h('span', { class: 'mono' }, c0.bindAddress), ' port ', num('port', c0.port), '. Accept ', num('rate', c0.rateLimitPerMinute), ' requests a minute, up to ',
        num('mb', draft.mb, { max: 3 }), ' MB each, and wait ', num('timeout', c0.requestTimeoutSeconds), ' seconds on providers.'),
      sentence('Local token required ', pebble(c0.requireLocalAuth, (v) => { draft.auth = v; save.hidden = false; }, 'Require local token'),
        '  Streaming allowed ', pebble(c0.streamingAllowed, (v) => { draft.stream = v; save.hidden = false; }, 'Allow streaming')),
      save, h('hr'),
      sentence('Access token  ', tokenVal), h('div', { class: 'row' }, reveal, copyTok, rotate));

    return {
      el,
      update() {
        const g = S.state.gateway;
        status.textContent = g.running ? 'Listening.' : 'Resting.';
        status.classList.toggle('live', g.running);
        endpoint.textContent = g.endpoint;
        wake.disabled = g.running; rest.disabled = !g.running; restart.disabled = !g.running;
        if (g.lastError && !g.running) say(g.lastError, true);
      },
      unmount() { clearTimeout(timer); },
    };
  },
};
