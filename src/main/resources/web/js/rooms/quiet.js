import { h, btn, pebble, ink, holdButton, sentence, toast } from '../ui.js';

export default {
  id: 'quiet', name: 'Quiet', tag: 'Settings', word: 'QUIET', blurb: 'Detectors, memory, motion and manners.',
  mount(ctx) {
    const { S, call } = ctx;
    const s0 = S.state.settings;
    const d = { sessionTtlMinutes: s0.sessionTtlMinutes, logRetentionDays: s0.logRetentionDays, logPromptPreviews: s0.logPromptPreviews, reducedMotion: s0.reducedMotion,
      minimizeToTray: s0.minimizeToTray, desktopNotifications: s0.desktopNotifications, enabledDetectors: { ...s0.enabledDetectors } };
    const note = h('p', { class: 'note', 'aria-live': 'polite' });
    const save = btn('Seal these settings', async () => {
      try { await call('POST', '/api/settings', { ...d, sessionTtlMinutes: Number(d.sessionTtlMinutes), logRetentionDays: Number(d.logRetentionDays) }); await ctx.reload(); save.hidden = true; note.textContent = ''; toast('INFO', 'Sealed', 'Settings saved.'); }
      catch (e) { note.textContent = e.message; note.classList.add('bad'); }
    }, 'primary');
    save.hidden = true;
    const dirty = () => { save.hidden = false; };
    const eyes = h('div', { class: 'detectors' }, S.state.detectors.map((x) => h('div', { class: 'detector' }, pebble(d.enabledDetectors[x.name] !== false, (v) => { d.enabledDetectors[x.name] = v; dirty(); }, x.name), h('b', null, x.name.replace('Detector', '')), h('small', null, x.description))));
    const el = h('div', { class: 'quiet' }, h('p', { class: 'kicker' }, '07 · Settings'), h('h1', { class: 'giant' }, 'Quiet.'),
      h('h2', null, 'What looks'), eyes, h('hr'),
      sentence('Forget sessions after ', ink(d.sessionTtlMinutes, (v) => { d.sessionTtlMinutes = v; dirty(); }, { mono: true, min: 2, max: 6 }), ' minutes. Keep activity for ', ink(d.logRetentionDays, (v) => { d.logRetentionDays = v; dirty(); }, { mono: true, min: 2, max: 4 }), ' days.'),
      sentence('Store prompt previews ', pebble(d.logPromptPreviews, (v) => { d.logPromptPreviews = v; dirty(); }, 'Prompt previews')),
      sentence('Calmer motion ', pebble(d.reducedMotion, (v) => { d.reducedMotion = v; dirty(); }, 'Reduced motion'), '   Hide to the tray on close ', pebble(d.minimizeToTray, (v) => { d.minimizeToTray = v; dirty(); }, 'Minimise to tray'),
        '   Desktop whispers ', pebble(d.desktopNotifications, (v) => { d.desktopNotifications = v; dirty(); }, 'Desktop notifications')),
      save, note, h('hr'), h('div', { class: 'row' }, holdButton('Quit Veil entirely (hold)', async () => { try { await call('POST', '/api/quit'); } catch { /* server is going away */ } document.body.classList.add('gone'); })));
    return { el, update() {}, unmount() {} };
  },
};
