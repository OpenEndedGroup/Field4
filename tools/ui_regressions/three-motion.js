const sleep = (ms) => new Promise((r) => setTimeout(r, ms)), checks = [];
const expect = (value, label) => { checks.push({label, passed: !!value}); if (!value) throw new Error(label); };
const viewAt = (n) => [...document.querySelectorAll('iframe')].find((f) => {
    try { return new URL(f.src, location.href).searchParams.get('uid')?.endsWith('_motion') && f.contentDocument.querySelector('.scene-note')?.textContent === 'frame ' + n && f.contentWindow.FieldViewer?.captureStatus().ready; }
    catch (e) { return false; }
});
async function waitFor(n) { for (let i = 0; i < 200; i++) { const f = viewAt(n); if (f) return f; await sleep(100); } throw new Error('no view at frame ' + n); }
try {
    const frame = await waitFor(0), w = frame.contentWindow, d = frame.contentDocument, motion = w.FieldViewer.motion;
    const matrix = (id) => w.FieldInspector.rendered().find((r) => r.partId === id).matrix;
    const tools = d.getElementById('motion-tools');
    expect(!tools.classList.contains('hidden') && d.getElementById('motion-play') && d.getElementById('motion-scrub'), 'The standard viewer shows Play, a scrubber and At rest for declared motions');
    expect(!d.getElementById('motion-tracks').classList.contains('hidden'), 'and Tracks, since a track has points');
    expect(!d.querySelector('[data-tool]'), 'with no viewer tool installed');
    const rest = matrix('door');
    expect(motion.status().mode === 'rest' && /reach under 0.3 m/.test(motion.status().readout), 'At rest the readout counts the motions that reach the rule: ' + motion.status().readout);
    let st = motion.at(0.5);
    const half = matrix('door');
    // 45° about +z through the origin: x' = x cos − y sin.
    expect(Math.abs(half[0] - Math.cos(Math.PI / 4)) < 1e-6 && Math.abs(half[1] - Math.sin(Math.PI / 4)) < 1e-6, 'Scrubbing to t = 0.5 turns the door 45° about its hinge');
    expect(Math.abs(matrix('drawer')[13] + 0.175) < 1e-6, 'and slides the drawer half its travel');
    expect(st.parts[0].band === 1 && st.readout === 'all clear', 'Near the wall at 45°, which colours its track but not the door: ' + st.readout);
    st = motion.at(0);
    expect(st.parts[0].band === 0 && /^door 0\.20 m from wall at 0°/.test(st.readout), 'Closed, the door is under the rule and the readout says by how much: ' + st.readout);
    motion.tracks(true);
    expect(motion.status().tracks, 'Tracks draw the swept path');
    motion.play(true); await sleep(600);
    const playing = motion.status();
    expect(playing.playing && playing.t > 0 && playing.t < 1, 'Play runs the timeline: t = ' + playing.t);
    motion.play(false);
    const t = motion.status().t;
    window.sendSliderUpdate(document.querySelector('[data-slider-id]').dataset.sliderId, 1.25);
    await waitFor(1);
    const after = motion.status();
    expect(after.mode === 'play' && Math.abs(after.t - t) < 1e-9, 'A redraw keeps the timeline where it was');
    motion.at(0);
    expect(motion.status().parts[0].band === 1, 'With the wall moved away the closed door is only near it: band ' + motion.status().parts[0].band);
    motion.rest();
    expect(JSON.stringify(matrix('door')) === JSON.stringify(rest), 'At rest puts the door back where the model has it');
    return {ok: true, checks};
} catch (e) { return {ok: false, reason: String(e), checks}; }
