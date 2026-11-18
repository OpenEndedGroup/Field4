// The WebGL context budget (threejs_viewer.html): every view on screen draws, views off screen give
// their contexts back, and panning brings the ones it reaches back to life.
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const checks = [];
const expect = (ok, label, detail) => { checks.push({label, passed: !!ok}); if (!ok) throw new Error(label + (detail ? ': ' + JSON.stringify(detail) : '')); };
const frames = () => [...document.querySelectorAll('iframe')].filter(f => (f.src || '').includes('/threejs'));
const viewers = () => frames().map(f => { try { return {f, v: f.contentWindow.FieldViewer}; } catch (_) { return {f, v: null}; } })
    .filter(x => x.v && x.v.contextStatus);
for (let i = 0; i < 240 && viewers().length < 20; i++) await delay(250);
expect(viewers().length === 20, 'all twenty views loaded', viewers().length);
const background = document.querySelector('.field-main-viewport').__fieldWebglBackground;
expect(background && background.ok && background.kind === '2d', 'the canvas background holds no WebGL context', background && background.kind);
function state() {
    const all = viewers().map(({f, v}) => ({name: new URL(f.src).searchParams.get('uid').split('_').pop(), ...v.contextStatus()}));
    return {all, onScreen: all.filter(s => s.onScreen), live: all.filter(s => s.live)};
}
async function everyOnScreenViewLive(label) {
    let s;
    for (let i = 0; i < 40; i++) { s = state(); if (s.onScreen.length && s.onScreen.every(v => v.live)) break; await delay(100); }
    expect(s.onScreen.length > 0, label + ': some view is on screen');
    expect(s.onScreen.every(v => v.live), label + ': every view on screen has a context', s.onScreen.filter(v => !v.live).map(v => v.name));
    expect(!s.all.some(v => v.blocked), label + ': no view is waiting for a click', s.all.filter(v => v.blocked).map(v => v.name));
    return s;
}
await delay(2000);
await everyOnScreenViewLive('loaded');
// Views off screen for the release window give their contexts back, down to the budget.
await delay(17000);
let s = state();
expect(s.live.length <= 12, 'off-screen views released down to the budget', {live: s.live.length});
expect(s.all.some(v => v.releases > 0), 'some view released its context');
const top = await everyOnScreenViewLive('after the release window');
// Pan to the last views, which were released or evicted, and back.
const last = frames()[frames().length - 1].getBoundingClientRect();
const [tx, ty] = [background.tx, background.ty];
window.moveCanvasCamera(tx, ty - (last.top - 120), 1);
await delay(1500);
const bottom = await everyOnScreenViewLive('panned to the last views');
expect(bottom.onScreen.every(v => !top.onScreen.some(t => t.name === v.name)), 'the pan shows other views', bottom.onScreen.map(v => v.name));
window.moveCanvasCamera(tx, ty, 1);
await delay(1500);
await everyOnScreenViewLive('panned back');
// An agent's snapshot of a view off screen still gets a picture.
const away = viewers().find(({v}) => !v.contextStatus().onScreen);
await away.v.ensureContext();
expect(away.v.contextStatus().live, 'ensureContext restores a view off screen');
const image = away.v.snapshotImage();
expect(image.length > 2000, 'a restored view off screen draws a snapshot', image.length);
return {ok: true, checks, live: state().live.length};
