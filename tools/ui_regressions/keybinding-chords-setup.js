// Two-key chords (KeybindingsClient.kt, ChordState): three throwaway palette actions, so the
// regression owns every binding it presses and touches no user keybindings.yaml.
//   cmd+k cmd+j -> "sequence"   cmd+k -> "prefix" (also the chord's first key)   cmd+j -> "second"
window.__chordHits = [];
window.__chordStep = 0;
const action = (id, keys, tag) => ({ id, label: `Chord regression: ${tag}`, detail: '', keys, run: () => window.__chordHits.push(tag) });
window.allActions.chordRegression = [
    action('chord-regression-sequence', 'cmd+k cmd+j', 'sequence'),
    action('chord-regression-prefix', 'cmd+k', 'prefix'),
    action('chord-regression-second', 'cmd+j', 'second'),
];
window.updateActions();
if (document.activeElement && document.activeElement !== document.body) document.activeElement.blur();
if (document.activeElement !== document.body) throw new Error(`focus is on ${document.activeElement?.tagName}, not the canvas`);
if (typeof window.fieldKeybindings?.pendingChord !== 'function') throw new Error('fieldKeybindings.pendingChord is missing');
return { ok: true };
