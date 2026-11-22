import { h, clear, btn, pebble, ink, holdButton, sentence, toast } from '../ui.js';

const ORDER = ['MASK', 'REALISTIC_FAKE', 'WARN', 'BLOCK', 'ALLOW'];
const WORDS = { MASK: 'veil it with a token', REALISTIC_FAKE: 'swap for a lookalike', WARN: 'ask me first', BLOCK: 'refuse the request', ALLOW: 'let it pass' };

export default {
  id: 'masks', name: 'Masks', tag: 'Profiles', word: 'MASKS', blurb: 'What gets hidden, and how, for each kind of secret.',
  mount(ctx) {
    const { S, call } = ctx;
    let selId = S.state.gatewayConfig.activeProfileId;
    let draft = null, dirty = false;
    const tabs = h('div', { class: 'tabs' });
    const meta = h('div', { class: 'meta' });
    const ring = h('div', { class: 'ring' });
    const coreTitle = h('b'); const coreSub = h('small');
    ring.append(h('div', { class: 'ring-core' }, coreTitle, coreSub));
    const legend = h('div', { class: 'legend' }, ORDER.map((a) => h('span', { class: `a-${a}` }, h('i'), WORDS[a])));
    const actions = h('div', { class: 'row' });
    const rulesBox = h('div', { class: 'rules' });
    const note = h('p', { class: 'note', 'aria-live': 'polite' });
    const say = (m, bad) => { note.textContent = m || ''; note.classList.toggle('bad', !!bad); };
    const current = () => S.state.profiles.find((p) => p.id === selId) || S.state.profiles[0];
    const touch = () => { dirty = true; drawActions(); };

    function load() { selId = current().id; draft = JSON.parse(JSON.stringify(current())); dirty = false; drawAll(); }

    function drawTabs() {
      clear(tabs);
      S.state.profiles.forEach((p) => tabs.append(h('button', { class: `tab ${p.id === selId ? 'sel' : ''}`, type: 'button', onclick: () => { if (dirty) toast('WARN', 'Changes discarded', 'You left a mask without sealing it.'); selId = p.id; load(); } },
        p.name, p.id === S.state.gatewayConfig.activeProfileId ? h('em', null, ' ◉ worn') : null)));
    }
    function drawMeta() {
      clear(meta);
      meta.append(draft.builtIn ? h('div', null, h('h2', null, draft.name), h('p', { class: 'note' }, `${draft.description || ''} Built in: edit freely, or duplicate to keep the original.`))
        : sentence('Called ', ink(draft.name, (v) => { draft.name = v; touch(); }, { min: 6, max: 28 }), ' — ', ink(draft.description || '', (v) => { draft.description = v; touch(); }, { min: 12, max: 50 })));
    }
    function drawRing() {
      ring.querySelectorAll('.cbead').forEach((n) => n.remove());
      const cats = S.state.categories; const R = 176; const c = 230;
      cats.forEach((cat, i) => {
        const a = -Math.PI / 2 + (i * 2 * Math.PI) / cats.length;
        const on = draft.enabledCategories.includes(cat.name); const act = draft.categoryPolicies[cat.name] || 'MASK';
        const b = h('button', { class: `cbead a-${act} ${on ? '' : 'off'}`, type: 'button', style: `left:${c + Math.cos(a) * R - 34}px;top:${c + Math.sin(a) * R - 34}px`, title: `${cat.display}: click to change, right-click to switch off` }, h('span', null, cat.prefix.replace('_', ' ')));
        const hint = () => { coreTitle.textContent = cat.display; coreSub.textContent = on ? WORDS[draft.categoryPolicies[cat.name] || 'MASK'] : 'switched off'; };
        b.addEventListener('mouseenter', hint); b.addEventListener('focus', hint);
        b.addEventListener('click', (e) => {
          if (e.altKey) return toggle();
          const cur = ORDER.indexOf(draft.categoryPolicies[cat.name] || 'MASK');
          draft.categoryPolicies[cat.name] = ORDER[(cur + 1) % ORDER.length]; touch(); drawRing(); b2(cat.name).focus();
        });
        b.addEventListener('contextmenu', (e) => { e.preventDefault(); toggle(); });
        function toggle() {
          const s = new Set(draft.enabledCategories); s.has(cat.name) ? s.delete(cat.name) : s.add(cat.name);
          draft.enabledCategories = [...s]; touch(); drawRing(); b2(cat.name).focus();
        }
        b.dataset.cat = cat.name; ring.append(b);
      });
      coreTitle.textContent = draft.name; coreSub.textContent = 'tap a bead to change its fate';
    }
    const b2 = (name) => ring.querySelector(`[data-cat="${name}"]`) || ring;

    function drawRules() {
      clear(rulesBox);
      rulesBox.append(h('h2', null, 'Your own rules'));
      rulesBox.append(sentence('Code identifiers to veil ', ink((draft.codeIdentifiers || []).join(', '), (v) => { draft.codeIdentifiers = v.split(',').map((x) => x.trim()).filter(Boolean); touch(); }, { mono: true, min: 20, max: 48, placeholder: 'PaymentLedger, internalApiUrl' })));
      (draft.customRules || []).forEach((r, i) => rulesBox.append(h('div', { class: 'rule' },
        pebble(r.enabled !== false, (v) => { r.enabled = v; touch(); }, 'Rule enabled'), h('b', null, r.name || 'rule'), h('span', { class: 'mono' }, r.pattern),
        h('small', null, `${r.regex ? 'regex' : 'literal'} · ${r.category}`), btn('remove', () => { draft.customRules.splice(i, 1); touch(); drawRules(); }))));
      const nr = { name: '', pattern: '', regex: false, category: 'CUSTOM' };
      const sel = h('select', { class: 'ink', 'aria-label': 'Category' }, S.state.categories.map((c) => h('option', { value: c.name, selected: c.name === 'CUSTOM' }, c.display)));
      sel.addEventListener('change', () => { nr.category = sel.value; });
      rulesBox.append(sentence('Also veil ', ink('', (v) => { nr.pattern = v; }, { mono: true, placeholder: 'pattern', min: 10, max: 30 }), ' as ', sel, ' called ', ink('', (v) => { nr.name = v; }, { placeholder: 'name', min: 8, max: 20 }),
        ' regex ', pebble(false, (v) => { nr.regex = v; }, 'Regex'), ' ', btn('Add rule', () => {
          if (!nr.pattern.trim()) return say('A rule needs a pattern.', true);
          draft.customRules = draft.customRules || [];
          draft.customRules.push({ id: (crypto.randomUUID && crypto.randomUUID()) || String(Date.now()), name: nr.name || nr.pattern.slice(0, 16), pattern: nr.pattern, regex: nr.regex, caseSensitive: false, category: nr.category, enabled: true });
          touch(); drawRules();
        })));
    }
    function drawActions() {
      clear(actions);
      const worn = selId === S.state.gatewayConfig.activeProfileId;
      actions.append(
        btn(worn ? 'Currently worn' : 'Wear this mask', async () => { try { await call('POST', '/api/profile/active', { id: selId }); await ctx.reload(); say('Mask changed.'); } catch (e) { say(e.message, true); } }, 'primary'),
        btn('Duplicate', async () => { try { const c = await call('POST', `/api/profile/${selId}/duplicate`, { name: `${draft.name} (copy)` }); await ctx.reload(); selId = c.id; load(); say('Duplicated. Make it yours.'); } catch (e) { say(e.message, true); } }),
        draft.builtIn ? null : holdButton('Delete (hold)', async () => { try { await call('DELETE', `/api/profile/${selId}`); await ctx.reload(); selId = S.state.gatewayConfig.activeProfileId; load(); } catch (e) { say(e.message, true); } }),
        dirty ? btn('Seal changes', async () => { try { await call('POST', '/api/profile', draft); dirty = false; await ctx.reload(); say('Sealed.'); } catch (e) { say(e.message, true); } }, 'primary') : null);
      if (worn) actions.firstChild.disabled = true;
    }
    function drawAll() { drawTabs(); drawMeta(); drawRing(); drawRules(); drawActions(); }

    const el = h('div', { class: 'masks' }, h('p', { class: 'kicker' }, '03 · Privacy profiles'), h('h1', { class: 'giant' }, 'Masks.'), tabs, meta,
      h('div', { class: 'ringwrap' }, ring), legend, actions, note, h('hr'), rulesBox);
    load();
    return { el, update() { if (!dirty) load(); else { drawTabs(); } }, unmount() {} };
  },
};
