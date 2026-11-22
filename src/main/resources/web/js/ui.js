// DOM helpers. Everything user-controlled goes in via textContent, never innerHTML.
export function h(tag, props, ...kids) {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(props || {})) {
    if (v == null || v === false) continue;
    if (k === 'class') e.className = v;
    else if (k === 'style') { if (typeof v === 'string') e.style.cssText = v; else Object.assign(e.style, v); }
    else if (k.startsWith('on') && typeof v === 'function') e.addEventListener(k.slice(2).toLowerCase(), v);
    else if (k === 'value' || k === 'checked' || k === 'disabled' || k === 'placeholder' || k === 'spellcheck' || k === 'selected') e[k] = v;
    else e.setAttribute(k, v === true ? '' : v);
  }
  const add = (k) => {
    if (k == null || k === false) return;
    if (Array.isArray(k)) k.forEach(add);
    else e.append(k instanceof Node ? k : document.createTextNode(String(k)));
  };
  kids.forEach(add);
  return e;
}
export const clear = (el) => { while (el.firstChild) el.removeChild(el.firstChild); return el; };
export const clamp = (v, a, b) => Math.min(b, Math.max(a, v));

export const fmt = {
  n: (v) => (v == null ? '0' : Math.round(v).toLocaleString('en-US')),
  ms: (v) => (v < 1000 ? `${Math.round(v)} ms` : `${(v / 1000).toFixed(1)} s`),
  time: (iso) => { try { return new Date(iso).toLocaleTimeString([], { hour12: false }); } catch { return '--:--:--'; } },
  mb: (bytes) => Math.max(1, Math.round(bytes / 1048576)),
};

export function copy(text) {
  const done = () => toast('INFO', 'Copied', 'It is on your clipboard.');
  if (navigator.clipboard && window.isSecureContext) return navigator.clipboard.writeText(text).then(done, fallback);
  fallback();
  function fallback() {
    const t = h('textarea', { style: 'position:fixed;opacity:0' }); t.value = text; document.body.append(t); t.select();
    try { document.execCommand('copy'); done(); } catch { toast('WARN', 'Copy failed', 'Select it and copy by hand.'); }
    t.remove();
  }
}

let toastBox;
export function toast(level, title, message) {
  toastBox = toastBox || document.getElementById('toasts');
  if (!toastBox) return;
  const t = h('div', { class: `toast t-${String(level).toLowerCase()}`, role: 'status' }, h('b', null, title), message ? h('span', null, message) : null);
  toastBox.append(t);
  requestAnimationFrame(() => t.classList.add('in'));
  setTimeout(() => { t.classList.remove('in'); setTimeout(() => t.remove(), 500); }, 4600);
  while (toastBox.children.length > 3) toastBox.firstChild.remove();
}

export const btn = (label, onClick, cls = '') => h('button', { class: `tbtn ${cls}`, type: 'button', onclick: onClick }, label);

export function pebble(checked, onChange, label) {
  const b = h('button', { class: 'pebble', type: 'button', role: 'switch', 'aria-checked': String(!!checked), 'aria-label': label || 'toggle' }, h('i'));
  b.set = (v) => b.setAttribute('aria-checked', String(!!v));
  b.get = () => b.getAttribute('aria-checked') === 'true';
  b.addEventListener('click', () => { b.set(!b.get()); onChange && onChange(b.get()); });
  return b;
}

export function ink(value, onInput, opts = {}) {
  const i = h('input', { class: `ink ${opts.mono ? 'mono' : ''}`, type: opts.type || 'text', value: value == null ? '' : String(value), spellcheck: 'false', autocomplete: 'off', placeholder: opts.placeholder || '' });
  const fit = () => { i.style.width = `${clamp((i.value.length || (opts.placeholder || '').length || 2) + 1.2, opts.min || 3, opts.max || 60)}ch`; };
  i.addEventListener('input', () => { fit(); onInput && onInput(i.value); });
  fit();
  i.refit = fit;
  return i;
}

// Press and hold to confirm anything destructive.
export function holdButton(label, onDone, ms = 1100) {
  const b = h('button', { class: 'tbtn hold', type: 'button', 'aria-label': `${label} (press and hold)` }, h('span', null, label));
  let raf = 0, t0 = 0;
  const tick = (now) => {
    const p = Math.min(1, (now - t0) / ms);
    b.style.setProperty('--p', p);
    if (p >= 1) { stop(); onDone(); } else raf = requestAnimationFrame(tick);
  };
  const start = (e) => { e.preventDefault(); t0 = performance.now(); b.classList.add('holding'); raf = requestAnimationFrame(tick); };
  const stop = () => { cancelAnimationFrame(raf); b.classList.remove('holding'); b.style.setProperty('--p', 0); };
  b.addEventListener('pointerdown', start);
  ['pointerup', 'pointerleave', 'pointercancel'].forEach((ev) => b.addEventListener(ev, stop));
  b.addEventListener('keydown', (e) => { if ((e.key === 'Enter' || e.key === ' ') && !e.repeat) start(e); });
  b.addEventListener('keyup', stop);
  return b;
}

export const sentence = (...parts) => h('p', { class: 'sentence' }, ...parts);
