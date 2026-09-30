const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
let frame;
for (let i = 0; i < 100; i++) {
    frame = [...document.querySelectorAll('iframe')].find(f => {
        try { return f.src.includes('idle-animation') && f.contentWindow.FieldViewer; }
        catch (_) { return false; }
    });
    if (frame) break;
    await delay(100);
}
if (!frame) throw new Error('Missing live animation');
const before = frame.contentWindow.FieldViewer.renderStats().frames;
await delay(600);
const after = frame.contentWindow.FieldViewer.renderStats().frames;
if (after <= before + 3) throw new Error('Incoming geometry did not redraw');
return {ok: true, framesDuringAnimation: after - before};
