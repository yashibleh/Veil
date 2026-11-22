// In-memory stand-in for the Java control server. Used only when no backend is reachable.
const CATS = [
  ['PERSONAL_NAME', 'NAME', 'Personal Name'], ['EMAIL', 'EMAIL', 'Email Address'], ['PHONE', 'PHONE', 'Phone Number'],
  ['AADHAAR', 'AADHAAR', 'Aadhaar Number'], ['PAN', 'PAN', 'PAN Number'], ['CREDIT_CARD', 'CARD', 'Payment Card'],
  ['IP_ADDRESS', 'IP', 'IP Address'], ['URL', 'URL', 'Private/Internal URL'], ['API_KEY', 'API_KEY', 'API Key'],
  ['ACCESS_TOKEN', 'TOKEN', 'Access Token'], ['PASSWORD', 'PASSWORD', 'Password'], ['DB_CREDENTIAL', 'DB_CRED', 'Database Credential'],
  ['PRIVATE_IDENTIFIER', 'PRIV_ID', 'Private Identifier'], ['CODE_IDENTIFIER', 'CODE_ID', 'Code Identifier'], ['CUSTOM', 'CUSTOM', 'Custom Rule'],
].map(([name, prefix, display]) => ({ name, prefix, display }));
const ACTIONS = [['MASK', 'Mask with Reversible Token'], ['BLOCK', 'Block Request'], ['WARN', 'Warn & Require Confirmation'],
  ['REALISTIC_FAKE', 'Replace with Realistic Synthetic Value'], ['ALLOW', 'Allow Unmodified']].map(([name, description]) => ({ name, description }));

const mk = (id, name, description, over) => {
  const categoryPolicies = {}; CATS.forEach((c) => { categoryPolicies[c.name] = 'MASK'; });
  Object.assign(categoryPolicies, over);
  return { id, name, description, builtIn: true, enabledCategories: CATS.map((c) => c.name), categoryPolicies, codeIdentifiers: [], customRules: [] };
};
const secrets = { API_KEY: 'BLOCK', ACCESS_TOKEN: 'BLOCK', PASSWORD: 'BLOCK', DB_CREDENTIAL: 'BLOCK' };
const D = {
  gateway: { running: false, bindAddress: '127.0.0.1', port: 8080, endpoint: 'http://127.0.0.1:8080/v1/chat/completions' },
  gatewayConfig: { bindAddress: '127.0.0.1', port: 8080, requireLocalAuth: true, maxRequestBodyBytes: 2097152, rateLimitPerMinute: 120,
    requestTimeoutSeconds: 60, streamingAllowed: true, activeProvider: 'OPENAI', activeProfileId: 'developer', baseUrl: 'http://127.0.0.1:8080/v1',
    endpoint: 'http://127.0.0.1:8080/v1/chat/completions' },
  settings: { chatContextChars: 48000, sessionTtlMinutes: 60, logRetentionDays: 14, logPromptPreviews: false, reducedMotion: false, minimizeToTray: true,
    desktopNotifications: true, enabledDetectors: {} },
  detectors: ['RegexDetector', 'SecretDetector', 'NameDetector', 'CodeIdentifierDetector', 'CustomRuleDetector'].map((name) => ({
    name, enabled: true, description: { RegexDetector: 'Emails, phones, cards (Luhn), Aadhaar (Verhoeff), PAN, IPs, internal URLs, DB URIs.',
      SecretDetector: 'API keys, JWTs, bearer tokens, private keys and password assignments, by entropy.',
      NameDetector: 'Personal names found by context cues.', CodeIdentifierDetector: 'Identifiers you list per mask.',
      CustomRuleDetector: 'Your own literal and regex rules.' }[name] })),
  providers: [['OPENAI', 'OpenAI', false, true, 'https://api.openai.com/v1', 'gpt-4o-mini'], ['ANTHROPIC', 'Anthropic', false, true, 'https://api.anthropic.com/v1', 'claude-3-5-sonnet-20241022'],
    ['GEMINI', 'Google Gemini', false, true, 'https://generativelanguage.googleapis.com/v1beta', 'gemini-2.0-flash'], ['OLLAMA', 'Ollama (Local)', true, false, 'http://127.0.0.1:11434', 'llama3.2']]
    .map(([type, name, local, requiresKey, baseUrl, model]) => ({ type, name, local, requiresKey, baseUrl, model, timeoutSeconds: 60, streaming: true, hasKey: type === 'OLLAMA', keyMasked: type === 'OLLAMA' ? 'Not required' : 'Not configured' })),
  profiles: [mk('personal', 'Personal', 'Everyday chats: hide who you are.', { API_KEY: 'WARN' }),
    mk('developer', 'Developer', 'Hide secrets and infrastructure.', { ...secrets, CODE_IDENTIFIER: 'MASK' }),
    mk('enterprise', 'Enterprise', 'Hide people, hosts and credentials.', { ...secrets, PERSONAL_NAME: 'REALISTIC_FAKE', EMAIL: 'REALISTIC_FAKE' }),
    mk('strict', 'Strict', 'Nothing sensitive leaves. Ever.', { ...secrets, PAN: 'BLOCK', AADHAAR: 'BLOCK', CREDIT_CARD: 'BLOCK' })],
  categories: CATS, actions: ACTIONS,
  vault: { locked: false, persistent: false, hasFile: false, total: 0, sessions: [], ttlMinutes: 60 },
  analytics: { total: 0, forwarded: 0, blocked: 0, errors: 0, detected: 0, masked: 0, avgMs: 0, categories: {}, providers: {} },
  events: [],
};
D.settings.enabledDetectors = Object.fromEntries(D.detectors.map((d) => [d.name, true]));
const mappings = {}; // sid -> rows
let token = 'veil_gw_demo_' + Math.random().toString(36).slice(2, 12);
let listeners = null;

const RX = [['EMAIL', /[\w.+-]+@[\w-]+\.[\w.-]+/g, 'Email Address'], ['IP_ADDRESS', /\b(?:\d{1,3}\.){3}\d{1,3}\b/g, 'IPv4 Address'],
  ['API_KEY', /\bsk-[A-Za-z0-9_-]{16,}/g, 'Provider API Key'], ['PAN', /\b[A-Z]{5}\d{4}[A-Z]\b/g, 'PAN'],
  ['CREDIT_CARD', /\b(?:\d[ -]?){13,16}\b/g, 'Payment Card'], ['PHONE', /\+?\d[\d -]{8,}\d/g, 'Phone Number']];
const prof = () => D.profiles.find((p) => p.id === D.gatewayConfig.activeProfileId) || D.profiles[0];
const policy = (p, c) => (p.enabledCategories.includes(c) ? p.categoryPolicies[c] : 'ALLOW');

function detect(text) {
  const p = prof(); const found = [];
  for (const [cat, re, label] of RX) {
    for (const m of text.matchAll(re)) {
      const s = m.index, e = s + m[0].length;
      if (found.some((f) => s < f.end && e > f.start)) continue;
      const c = CATS.find((x) => x.name === cat);
      found.push({ category: cat, prefix: c.prefix, categoryName: c.display, label, detector: 'DemoDetector', start: s, end: e, confidence: 0.9, action: policy(p, cat) });
    }
  }
  return found.sort((a, b) => a.start - b.start);
}
let seq = 0;
function seal(text, sid) {
  const ents = detect(text); let out = ''; let cur = 0; const blocked = ents.find((e) => e.action === 'BLOCK');
  for (const e of ents) {
    out += text.slice(cur, e.start); const orig = text.slice(e.start, e.end);
    if (e.action === 'ALLOW') out += orig;
    else if (e.action === 'REALISTIC_FAKE') out += 'synthetic.' + e.prefix.toLowerCase();
    else {
      const tok = `[${e.prefix}_${String(++seq).padStart(3, '0')}]`; out += tok;
      (mappings[sid] = mappings[sid] || []).push({ token: tok, category: e.category, categoryName: e.categoryName, action: e.action, createdAt: new Date().toISOString(), preview: orig.length > 4 ? orig.slice(0, 2) + '••••' + orig.slice(-2) : '••••', original: orig });
    }
    cur = e.end;
  }
  out += text.slice(cur);
  D.vault.sessions = Object.keys(mappings).map((id) => ({ id, count: mappings[id].length })); D.vault.total = Object.values(mappings).reduce((n, a) => n + a.length, 0);
  return { ents, out, blocked };
}
function record(ev) {
  const e = { id: Math.random().toString(36).slice(2), timestamp: new Date().toISOString(), sessionId: 'gw-' + Math.random().toString(16).slice(2, 10), source: 'GATEWAY',
    provider: D.gatewayConfig.activeProvider, model: D.providers.find((p) => p.type === D.gatewayConfig.activeProvider).model, profileName: prof().name,
    status: 'FORWARDED', detectedCount: 0, maskedCount: 0, blocked: false, streaming: Math.random() > 0.5, durationMs: 150 + Math.round(Math.random() * 600), summaryNote: 'Sanitized & forwarded', categoryCounts: {}, ...ev };
  D.events.unshift(e); const a = D.analytics; a.total++; a.detected += e.detectedCount; a.masked += e.maskedCount;
  if (e.status === 'FORWARDED') a.forwarded++; else if (e.status === 'BLOCKED') a.blocked++; else a.errors++;
  a.avgMs = D.events.reduce((n, x) => n + x.durationMs, 0) / D.events.length; a.providers[e.provider] = (a.providers[e.provider] || 0) + 1;
  for (const [k, v] of Object.entries(e.categoryCounts)) a.categories[k] = (a.categories[k] || 0) + v;
  if (listeners) listeners('request', e); return e;
}
const SAMPLE = [['EMAIL', 2], ['IP_ADDRESS', 1], ['PHONE', 1], ['API_KEY', 1], ['PERSONAL_NAME', 2], ['PAN', 1], ['URL', 1]];
function fakeTraffic() {
  if (!D.gateway.running) return;
  const r = Math.random(); const picks = SAMPLE.filter(() => Math.random() > 0.55); const cc = Object.fromEntries(picks);
  const n = Object.values(cc).reduce((a, b) => a + b, 0);
  const sid = 'gw-' + Math.random().toString(16).slice(2, 10);
  for (const [c] of picks) { const cat = CATS.find((x) => x.name === c); (mappings[sid] = mappings[sid] || []).push({ token: `[${cat.prefix}_${String(++seq).padStart(3, '0')}]`, category: c, categoryName: cat.display, action: 'MASK', createdAt: new Date().toISOString(), preview: 'ab••••yz', original: 'demo-' + cat.prefix.toLowerCase() + '@example.com' }); }
  D.vault.sessions = Object.keys(mappings).map((id) => ({ id, count: mappings[id].length })); D.vault.total = Object.values(mappings).reduce((q, a) => q + a.length, 0);
  if (r < 0.14 && picks.length) record({ sessionId: sid, status: 'BLOCKED', blocked: true, detectedCount: n, maskedCount: 0, summaryNote: 'Blocked by privacy policy (Developer): detected API Key', categoryCounts: cc });
  else if (r < 0.2) record({ sessionId: sid, status: 'PROVIDER_ERROR', summaryNote: 'Provider returned 429', durationMs: 90 });
  else record({ sessionId: sid, detectedCount: n, maskedCount: n, categoryCounts: cc });
}
setInterval(fakeTraffic, 3200);

const J = (o) => JSON.parse(JSON.stringify(o));
const wait = (ms) => new Promise((r) => setTimeout(r, ms));
export function stream(on) { listeners = on; setTimeout(() => on('link', { up: true }), 50); return { close() { listeners = null; } }; }

export async function call(method, path, body = {}) {
  await wait(30);
  const [route, qs] = path.split('?'); const q = new URLSearchParams(qs || ''); const key = `${method} ${route}`;
  const gw = () => { D.gateway.endpoint = `http://127.0.0.1:${D.gatewayConfig.port}/v1/chat/completions`; return J(D.gateway); };
  switch (key) {
    case 'GET /api/state': D.gateway.endpoint = `http://127.0.0.1:${D.gatewayConfig.port}/v1/chat/completions`; return J(D);
    case 'GET /api/events': return J(D.events);
    case 'GET /api/analytics': return J(D.analytics);
    case 'POST /api/analytics/clear': D.events = []; Object.assign(D.analytics, { total: 0, forwarded: 0, blocked: 0, errors: 0, detected: 0, masked: 0, avgMs: 0, categories: {}, providers: {} }); return { ok: true };
    case 'GET /api/integrations': return [
      { name: 'OpenAI-compatible coding assistants (Continue, Cline, Aider, Cursor)', status: 'Supported via Local Gateway', mechanism: 'Set the OpenAI base URL to Veil', instructions: 'Point your tool at the local base URL with the local token.', snippet: `OPENAI_BASE_URL=${D.gatewayConfig.baseUrl}\nOPENAI_API_KEY=<YOUR_VEIL_GATEWAY_TOKEN>` },
      { name: 'cURL', status: 'Supported via Local Gateway', mechanism: 'POST /v1/chat/completions', instructions: 'Verify sanitising from a terminal.', snippet: `curl -X POST ${D.gatewayConfig.endpoint} \\\n  -H "Authorization: Bearer <YOUR_VEIL_GATEWAY_TOKEN>" \\\n  -d '{"messages":[{"role":"user","content":"Email rahul@example.com"}]}'` }];
    case 'POST /api/gateway/start': D.gateway.running = true; D.gateway.startedAt = new Date().toISOString(); if (listeners) listeners('gateway', { running: true, message: 'Gateway listening' }); return gw();
    case 'POST /api/gateway/stop': D.gateway.running = false; if (listeners) listeners('gateway', { running: false, message: 'Gateway stopped' }); return gw();
    case 'POST /api/gateway/restart': D.gateway.running = true; return gw();
    case 'POST /api/gateway/config': Object.assign(D.gatewayConfig, body); D.gateway.port = D.gatewayConfig.port; return { gateway: gw(), config: J(D.gatewayConfig) };
    case 'GET /api/gateway/token': return { token };
    case 'POST /api/gateway/token/rotate': token = 'veil_gw_demo_' + Math.random().toString(36).slice(2, 12); return { token };
    case 'POST /api/gateway/health': return { ok: D.gateway.running, status: D.gateway.running ? 200 : 0, message: D.gateway.running ? undefined : 'Gateway did not answer: connection refused', body: '{"status":"ok"}' };
    case 'POST /api/provider/active': D.gatewayConfig.activeProvider = body.type; return { active: body.type };
    case 'POST /api/profile': { const i = D.profiles.findIndex((p) => p.id === body.id); const old = D.profiles[i]; D.profiles[i] = { ...body, builtIn: old.builtIn }; return J(D.profiles[i]); }
    case 'POST /api/profile/active': D.gatewayConfig.activeProfileId = body.id; return { active: body.id };
    case 'GET /api/vault': return J(D.vault);
    case 'GET /api/vault/session': return { id: q.get('id'), mappings: (mappings[q.get('id')] || []).map((m) => ({ ...m, original: q.get('reveal') === '1' ? m.original : undefined })) };
    case 'POST /api/vault/lock': D.vault.locked = true; D.vault.sessions = []; D.vault.total = 0; return J(D.vault);
    case 'POST /api/vault/unlock': if ((body.passphrase || '').length < 8) throw new Error('Could not unlock (check the passphrase)'); D.vault.locked = false; return J(D.vault);
    case 'POST /api/vault/persist': if ((body.passphrase || '').length < 8) throw new Error('Vault passphrase must be at least 8 characters'); D.vault.persistent = true; D.vault.hasFile = true; return J(D.vault);
    case 'POST /api/vault/wipe': for (const k of Object.keys(mappings)) delete mappings[k]; D.vault.sessions = []; D.vault.total = 0; return J(D.vault);
    case 'POST /api/vault/clear': delete mappings[body.id]; D.vault.sessions = D.vault.sessions.filter((s) => s.id !== body.id); D.vault.total = Object.values(mappings).reduce((n, a) => n + a.length, 0); return J(D.vault);
    case 'POST /api/detect': return { profile: prof().name, entities: detect(body.text || ''), text: (body.text || '').length };
    case 'POST /api/sanitize': { const sid = 'pg-' + Math.random().toString(16).slice(2, 10); const r = seal(body.text || '', sid); return r.blocked ? { session: sid, blocked: true, reason: `Blocked by privacy policy (${prof().name}): detected ${r.blocked.categoryName}`, entities: r.ents } : { session: sid, blocked: false, sanitized: r.out, masked: r.ents.filter((e) => e.action !== 'ALLOW').length, entities: r.ents }; }
    case 'POST /api/send': { await wait(700); const sid = 'pg-' + Math.random().toString(16).slice(2, 10); const r = seal(body.text || '', sid); if (r.blocked) return { ok: false, blocked: true, status: 403, message: `Blocked by privacy policy (${prof().name}): detected ${r.blocked.categoryName}` }; return { ok: true, session: sid, reply: 'Demo reply. In the real app this is the provider\'s answer with your tokens restored:\n\n' + (body.text || '') }; }
    case 'POST /api/settings': Object.assign(D.settings, body); D.detectors.forEach((d) => { d.enabled = D.settings.enabledDetectors[d.name] !== false; }); return J(D.settings);
    case 'POST /api/quit': return { ok: true };
    default: break;
  }
  let m;
  if ((m = route.match(/^\/api\/provider\/(\w+)$/)) && method === 'POST') { const p = D.providers.find((x) => x.type === m[1]); Object.assign(p, { baseUrl: body.baseUrl ?? p.baseUrl, model: body.defaultModel ?? p.model, timeoutSeconds: body.timeoutSeconds ?? p.timeoutSeconds, streaming: body.streamingEnabled ?? p.streaming }); if (body.apiKey) { p.hasKey = true; p.keyMasked = '••••' + body.apiKey.slice(-4); } return J(p); }
  if ((m = route.match(/^\/api\/provider\/(\w+)\/key$/)) && method === 'DELETE') { const p = D.providers.find((x) => x.type === m[1]); p.hasKey = false; p.keyMasked = 'Not configured'; return J(p); }
  if ((m = route.match(/^\/api\/provider\/(\w+)\/test$/))) { await wait(600); const p = D.providers.find((x) => x.type === m[1]); return p.hasKey || p.local ? { ok: true, message: 'The line is open.' } : { ok: false, message: 'No API key stored for this provider.' }; }
  if ((m = route.match(/^\/api\/profile\/([\w-]+)\/duplicate$/))) { const src = D.profiles.find((p) => p.id === m[1]); const c = { ...J(src), id: 'custom-' + Math.random().toString(16).slice(2, 8), name: body.name || src.name + ' (copy)', builtIn: false }; D.profiles.push(c); return J(c); }
  if ((m = route.match(/^\/api\/profile\/([\w-]+)$/)) && method === 'DELETE') { const p = D.profiles.find((x) => x.id === m[1]); if (!p || p.builtIn) throw new Error('Built-in masks cannot be deleted. Duplicate one instead.'); D.profiles = D.profiles.filter((x) => x.id !== m[1]); if (D.gatewayConfig.activeProfileId === m[1]) D.gatewayConfig.activeProfileId = 'developer'; return { ok: true }; }
  if (route.startsWith('/api/warn/')) return { ok: true };
  if (route.startsWith('/api/chat')) return chatCall(method, route, body);
  throw new Error('demo: no route ' + key);
}

// ── conversations (demo) ──
const convos = {};
const cview = (c, full) => ({ id: c.id, title: c.title, session: 'chat-' + c.id, system: c.system, count: c.messages.length, updated: c.updated, busy: false, ...(full ? { messages: J(c.messages) } : {}) });
export async function chatCall(method, route, body) {
  if (route === '/api/chat' && method === 'GET') return Object.values(convos).sort((a, b) => b.updated.localeCompare(a.updated)).map((c) => cview(c, false));
  if (route === '/api/chat' && method === 'POST') { const c = { id: Math.random().toString(16).slice(2, 10), title: 'New conversation', system: body.system || '', messages: [], updated: new Date().toISOString() }; convos[c.id] = c; return cview(c, false); }
  const m = route.match(/^\/api\/chat\/(\w+)$/);
  if (m && method === 'GET') { if (!convos[m[1]]) throw new Error('No such conversation'); return cview(convos[m[1]], true); }
  if (m && method === 'DELETE') { delete convos[m[1]]; return { ok: true }; }
  throw new Error('demo: no route ' + method + ' ' + route);
}
export async function chatStream(body, on, signal) {
  const c = convos[body.id]; if (!c) { on({ t: 'error', message: 'That conversation no longer exists.', status: 404 }); return; }
  const sid = 'chat-' + c.id; const r = seal(body.text, sid);
  if (r.blocked) { on({ t: 'error', message: `Blocked by privacy policy (${prof().name}): detected ${r.blocked.categoryName}`, status: 403, blocked: true }); return; }
  const cats = {}; r.ents.forEach((e) => { cats[e.categoryName] = (cats[e.categoryName] || 0) + 1; });
  const prov = D.providers.find((p) => p.type === (body.provider || D.gatewayConfig.activeProvider));
  const budget = D.settings.chatContextChars; let used = 0, kept = 0;
  for (let i = c.messages.length - 1; i >= 0; i--) { used += c.messages[i].content.length; if (used > budget && kept) break; kept++; }
  on({ t: 'meta', sanitized: r.out, detected: r.ents.length, masked: r.ents.filter((e) => e.action !== 'ALLOW').length, categories: cats, trimmed: c.messages.length - kept, provider: prov.name, model: prov.model });
  const reply = `Got it. Here is what I understood from your message:\n\n> ${body.text}\n\nA few things worth knowing:\n\n1. **Sensitive values** were swapped for tokens before this left your machine.\n2. They are swapped back in my answer, so you only ever see the real ones.\n3. This is demo mode, so I am only echoing. Connect a provider for real answers.\n\n\`\`\`js\nconsole.log("this thread has " + ${c.messages.length + 2} + " messages");\n\`\`\``;
  let acc = '';
  for (let i = 0; i < reply.length; i += 6) {
    if (signal && signal.aborted) { const e = new Error('aborted'); e.name = 'AbortError'; if (acc) { c.messages.push({ role: 'user', content: body.text, wire: r.out, detected: r.ents.length, masked: r.ents.length, categories: cats }, { role: 'assistant', content: acc, provider: prov.name, model: prov.model }); } throw e; }
    await wait(24); const piece = reply.slice(i, i + 6); acc += piece; on({ t: 'delta', d: piece });
  }
  if (!c.messages.length) c.title = body.text.slice(0, 60);
  c.messages.push({ role: 'user', content: body.text, wire: r.out, detected: r.ents.length, masked: r.ents.filter((e) => e.action !== 'ALLOW').length, categories: cats }, { role: 'assistant', content: acc, provider: prov.name, model: prov.model });
  c.updated = new Date().toISOString();
  record({ sessionId: sid, source: 'SALON', detectedCount: r.ents.length, maskedCount: r.ents.length, categoryCounts: Object.fromEntries(r.ents.map((e) => [e.category, 1])), summaryNote: 'Conversation turn sanitized & restored' });
  on({ t: 'done', reply: acc, model: prov.model, provider: prov.name, count: c.messages.length, title: c.title });
}
