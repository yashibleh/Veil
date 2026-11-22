// Small, safe markdown renderer for model replies. Builds DOM nodes (never innerHTML), so a model
// can't inject markup. Handles: fenced code, headings, lists, quotes, rules, **bold**, *italic*,
// `code` and http(s) links. Tolerates half-finished input, which is what streaming produces.
import { h, clear, copy } from './ui.js';

const INLINE = /(`[^`\n]+`)|(\*\*[^*\n]+\*\*)|(\*[^*\n]+\*)|(\[[^\]\n]+\]\(https?:\/\/[^\s)]+\))/g;

function inline(text) {
  const frag = document.createDocumentFragment();
  let last = 0, m;
  INLINE.lastIndex = 0;
  while ((m = INLINE.exec(text))) {
    if (m.index > last) frag.append(text.slice(last, m.index));
    const t = m[0];
    if (m[1]) frag.append(h('code', null, t.slice(1, -1)));
    else if (m[2]) frag.append(h('strong', null, t.slice(2, -2)));
    else if (m[3]) frag.append(h('em', null, t.slice(1, -1)));
    else {
      const l = /\[([^\]]+)\]\((https?:\/\/[^)]+)\)/.exec(t);
      frag.append(h('a', { href: l[2], target: '_blank', rel: 'noopener noreferrer' }, l[1]));
    }
    last = INLINE.lastIndex;
  }
  if (last < text.length) frag.append(text.slice(last));
  return frag;
}

function codeBlock(code, lang) {
  return h('div', { class: 'code' },
    h('div', { class: 'code-head' }, h('span', null, lang || 'text'), h('button', { type: 'button', onclick: () => copy(code) }, 'copy')),
    h('pre', null, h('code', null, code)));
}

const BLOCK_START = /^(```|#{1,4}\s|\s*[-*]\s+|\s*\d+[.)]\s+|>)/;

export function renderMd(src, el) {
  clear(el);
  const lines = String(src || '').replace(/\r/g, '').split('\n');
  let i = 0;
  while (i < lines.length) {
    const ln = lines[i];
    if (/^```/.test(ln)) {
      const lang = ln.slice(3).trim(); const buf = []; i++;
      while (i < lines.length && !/^```/.test(lines[i])) buf.push(lines[i++]);
      i++;
      el.append(codeBlock(buf.join('\n'), lang));
      continue;
    }
    if (!ln.trim()) { i++; continue; }
    let m;
    if ((m = /^(#{1,4})\s+(.*)$/.exec(ln))) { el.append(h(`h${m[1].length + 2}`, null, inline(m[2]))); i++; continue; }
    if (/^\s*[-*]\s+/.test(ln)) {
      const ul = h('ul');
      while (i < lines.length && /^\s*[-*]\s+/.test(lines[i])) ul.append(h('li', null, inline(lines[i++].replace(/^\s*[-*]\s+/, ''))));
      el.append(ul); continue;
    }
    if (/^\s*\d+[.)]\s+/.test(ln)) {
      const ol = h('ol');
      while (i < lines.length && /^\s*\d+[.)]\s+/.test(lines[i])) ol.append(h('li', null, inline(lines[i++].replace(/^\s*\d+[.)]\s+/, ''))));
      el.append(ol); continue;
    }
    if (/^>/.test(ln)) {
      const buf = [];
      while (i < lines.length && /^>/.test(lines[i])) buf.push(lines[i++].replace(/^>\s?/, ''));
      el.append(h('blockquote', null, inline(buf.join('\n')))); continue;
    }
    if (/^-{3,}$/.test(ln.trim())) { el.append(h('hr')); i++; continue; }
    const buf = [ln]; i++;
    while (i < lines.length && lines[i].trim() && !BLOCK_START.test(lines[i])) buf.push(lines[i++]);
    el.append(h('p', null, inline(buf.join('\n'))));
  }
  return el;
}
