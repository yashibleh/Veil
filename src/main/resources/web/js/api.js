// Thin client for the loopback control API. If no backend answers (or ?demo is in the URL),
// a self-contained demo backend takes over so the interface can be previewed without Java.
let demo = null;
export const isDemo = () => !!demo;

export async function call(method, path, body) {
  if (demo) return demo.call(method, path, body);
  const res = await fetch(path, {
    method,
    headers: { 'Content-Type': 'application/json', 'X-Veil-UI': '1' },
    body: body === undefined ? undefined : JSON.stringify(body),
    credentials: 'same-origin',
  });
  let data = null;
  try { data = await res.json(); } catch { /* empty body */ }
  if (!res.ok) throw new Error((data && data.error) || `HTTP ${res.status}`);
  return data;
}

export async function boot() {
  const wantDemo = new URLSearchParams(location.search).has('demo');
  if (!wantDemo) {
    try { return await call('GET', '/api/state'); } catch (e) { console.warn('[veil] no backend:', e.message); }
  }
  demo = await import('./demo.js');
  return demo.call('GET', '/api/state');
}

export function stream(on) {
  if (demo) return demo.stream(on);
  const es = new EventSource('/api/stream');
  for (const t of ['hello', 'request', 'gateway', 'notify', 'warn']) {
    es.addEventListener(t, (e) => { try { on(t, JSON.parse(e.data)); } catch { /* ignore */ } });
  }
  es.onopen = () => on('link', { up: true });
  es.onerror = () => on('link', { up: false });
  return es;
}

// Streams one conversation turn as newline-delimited JSON: meta, delta..., done | error.
export async function chatStream(body, onMessage, signal) {
  if (demo) return demo.chatStream(body, onMessage, signal);
  const res = await fetch('/api/chat/stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Veil-UI': '1' },
    body: JSON.stringify(body),
    credentials: 'same-origin',
    signal,
  });
  if (!res.ok) {
    let message = `HTTP ${res.status}`;
    try { message = (await res.json()).error || message; } catch { /* not json */ }
    throw new Error(message);
  }
  const reader = res.body.getReader();
  const dec = new TextDecoder();
  let buf = '';
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    buf += dec.decode(value, { stream: true });
    let nl;
    while ((nl = buf.indexOf('\n')) >= 0) {
      const line = buf.slice(0, nl).trim();
      buf = buf.slice(nl + 1);
      if (line) { try { onMessage(JSON.parse(line)); } catch { /* skip a bad line */ } }
    }
  }
}
