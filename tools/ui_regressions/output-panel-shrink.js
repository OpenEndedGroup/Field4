// An auto-sized output panel follows its content down as well as up, in height and in width. It
// used to only grow: the StudyBuild "builds" panel sized itself for every line inside a 320px-capped
// failure <pre>, then kept that height after the content went back to two short lines, its resize
// corner far off screen.
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const errors = [];
const onError = event => errors.push(String(event.error && (event.error.stack || event.error.message) || event.message || event));
window.addEventListener('error', onError);
const steps = {};
const fail = reason => ({ok: false, reason, steps, errors});
const waitFor = async (predicate, timeoutMs = 10000) => {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
        const value = predicate();
        if (value) return value;
        await delay(50);
    }
    return predicate();
};
const lines = (count, label) => Array.from({length: count}, (_, i) => `<p style="margin:0">${label} line ${i + 1}</p>`).join('');
const tall = `<div class="shrink-tall" style="padding:8px;font:14px/1.45 monospace;color:#ddd">${lines(90, 'tall')}</div>`;
const huge = `<div class="shrink-huge" style="padding:8px;font:14px/1.45 monospace;color:#ddd">${lines(400, 'huge')}</div>`;
const short = label => `<div class="shrink-short" style="padding:8px;font:14px/1.45 monospace;color:#ddd"><div>Build checked panels · ${label}</div><div>Check wall layouts · Not built yet</div></div>`;
const fill = `<div class="shrink-fill" style="height:100%;box-sizing:border-box;background:#123;color:#ddd">takes the panel's height</div>`;
const wideLine = `<p style="margin:0;white-space:nowrap">${'wide '.repeat(30)}</p>`;
const wide = `<div class="shrink-wide" style="padding:8px;font:14px/1.45 monospace;color:#ddd">${wideLine}</div>`;
const tallWide = `<div style="padding:8px;font:14px/1.45 monospace;color:#ddd">${wideLine}${lines(90, 'tall')}</div>`;
const words = 'the quick brown fox jumps over the lazy dog while the panel decides how wide to be ';
const paragraph = label => `<div class="shrink-paragraph" style="padding:8px;font:14px/1.45 monospace;color:#ddd">${label} ${words.repeat(8)}</div>`;
try {
    const box = [...document.querySelectorAll('.box')].find(b => String(b.textContent || '').includes('OutputPanelShrinkRegression'))
        || [...document.querySelectorAll('.box')].at(-1);
    if (!box) return fail('box not found');
    const outputArea = await waitFor(() => box.querySelector('.outputArea'));
    if (outputArea) {
        outputArea.style.visibility = 'visible';
        outputArea.style.height = 'auto';
    }
    const fixture = await waitFor(() => box.querySelector('.shrink-fixture'));
    if (!fixture) return fail('the builds fixture was never displayed');
    const cw = fixture.closest('.disclosure-content');
    const wrap = cw.closest('.disclosure');
    const api = window.disclosureInterface(wrap.closest('.disclosure-group'));
    const id = wrap.__disclosureId;
    const height = () => cw.offsetHeight;
    const width = () => cw.offsetWidth;
    const size = () => `${width()}x${height()}`;
    const overflows = () => cw.scrollHeight > cw.clientHeight + 1;
    const overflowsWidth = () => cw.scrollWidth > cw.clientWidth + 1;
    // Auto-size runs its passes over ~0.8s and a shrink waits 0.5s more, so a size that has held
    // for 1.6s has finished moving. Returns the height; read `width()` for the other axis.
    const settle = async (quietMs = 1600) => {
        let last = size();
        let since = Date.now();
        const deadline = Date.now() + 12000;
        while (Date.now() < deadline) {
            await delay(50);
            const now = size();
            if (now !== last) {
                last = now;
                since = Date.now();
            } else if (Date.now() - since >= quietMs) {
                return height();
            }
        }
        return height();
    };
    const show = async html => {
        api.setContent(id, html);
        return settle();
    };

    // 1. The real display() path, StudyBuild-shaped: the panel fits the card as laid out, with the
    //    <pre> at its 320px cap -- not every line the <pre> scrolls through.
    await settle();
    const pre = fixture.querySelector('pre');
    steps.diagnostic = {
        panel: height(), fixture: fixture.offsetHeight, preClient: pre.clientHeight, preScroll: pre.scrollHeight
    };
    if (!(pre.scrollHeight > 2000 && pre.clientHeight <= 320)) return fail('the diagnostic fixture is not a long, capped scroller');
    if (overflows()) return fail(`the builds panel (${height()}px) cuts off its ${fixture.offsetHeight}px card`);
    if (height() > fixture.offsetHeight + 40) {
        return fail(`the builds panel is ${height()}px for a ${fixture.offsetHeight}px card: it measured the text inside the capped <pre>`);
    }

    // 2. Tall content with no scroller of its own: the panel grows to show all of it.
    const tallHeight = await show(tall);
    const tallContent = cw.querySelector('.shrink-tall').offsetHeight;
    steps.tall = {panel: tallHeight, content: tallContent};
    if (tallContent < 1000) return fail(`tall fixture is only ${tallContent}px`);
    if (overflows() || tallHeight > tallContent + 40) return fail(`tall content ${tallContent}px got a ${tallHeight}px panel`);

    // 3. Content that takes the panel's height states none of its own; the panel holds rather than
    //    collapsing to the one line of text it holds.
    const fillHeight = await show(fill);
    steps.fill = {panel: fillHeight};
    if (Math.abs(fillHeight - tallHeight) > 2) return fail(`fill content moved the panel from ${tallHeight}px to ${fillHeight}px`);

    // 4. The reported bug: short content after tall. The panel comes back down to it.
    const shortHeight = await show(short('Building'));
    const shortContent = cw.querySelector('.shrink-short').offsetHeight;
    const shortWidth = width();
    steps.short = {panel: shortHeight, content: shortContent, from: fillHeight, width: shortWidth};
    if (overflows() || shortHeight > shortContent + 40) {
        return fail(`after tall content the panel stayed ${shortHeight}px for ${shortContent}px of content`);
    }

    // 5. Width, the same way: a line that does not wrap widens the panel to show it, and short
    //    content afterwards brings it back to the width short content had before.
    await show(wide);
    const wideWidth = width();
    const wideContent = cw.querySelector('.shrink-wide').scrollWidth;
    steps.wide = {panel: wideWidth, content: wideContent};
    if (wideContent < 1000) return fail(`wide fixture is only ${wideContent}px`);
    if (overflowsWidth()) return fail(`a ${wideWidth}px panel cuts off ${wideContent}px of unwrapped text`);
    const narrowedHeight = await show(short('Building'));
    steps.narrowed = {width: width(), height: narrowedHeight, from: wideWidth};
    if (Math.abs(width() - shortWidth) > 2 || Math.abs(narrowedHeight - shortHeight) > 2) {
        return fail(`after wide content short content got a ${size()} panel, not ${shortWidth}x${shortHeight}`);
    }

    // 6. Wrapped text reaches about as far as the panel, whatever its width, so it is measured at
    //    one fixed width. Re-rendered, it keeps the width it got, instead of creeping in a word at a
    //    time; and it is no wider than a fresh panel would make it.
    await show(wide);
    const paragraphWidths = [];
    for (const label of ['first', 'second', 'third']) {
        await show(paragraph(label));
        paragraphWidths.push(width());
    }
    steps.paragraph = {widths: paragraphWidths, from: width()};
    if (paragraphWidths.some(w => Math.abs(w - paragraphWidths[0]) > 2)) {
        return fail(`a re-rendered paragraph moved the panel's width: ${paragraphWidths.join(' -> ')}px`);
    }
    if (paragraphWidths[0] > 482 || paragraphWidths[0] < 300) {
        return fail(`a paragraph after wide content settled at ${paragraphWidths[0]}px wide, not a fresh panel's ~480px`);
    }
    if (overflows() || overflowsWidth()) return fail(`the paragraph panel ${size()} cuts off its text`);
    await show(short('Building'));

    // 7. Content alternating tall-and-wide/short faster than a shrink settles (progress ticks): the
    //    panel grows once and holds, then shrinks once on each axis after the alternation ends on
    //    short content.
    const samples = [];
    let sampling = true;
    const sample = () => {
        samples.push([width(), height()]);
        if (sampling) requestAnimationFrame(sample);
    };
    requestAnimationFrame(sample);
    for (let i = 0; i < 24; i++) {
        api.setContent(id, i % 2 === 0 ? tallWide : short(`tick ${i}`));
        await delay(60);
    }
    const lastChangeAt = samples.length;
    const settledShort = await settle();
    sampling = false;
    await delay(50);
    const decreases = axis => {
        const found = [];
        for (let i = 1; i < samples.length; i++) {
            if (samples[i][axis] < samples[i - 1][axis]) found.push({at: i, from: samples[i - 1][axis], to: samples[i][axis]});
        }
        return found;
    };
    const widthDecreases = decreases(0);
    const heightDecreases = decreases(1);
    steps.rapid = {samples: samples.length, lastChangeAt, widthDecreases, heightDecreases, settledShort, settledWidth: width()};
    for (const [axis, found] of [['height', heightDecreases], ['width', widthDecreases]]) {
        if (found.length !== 1 || found[0].at < lastChangeAt) {
            return fail(`the panel's ${axis} went down ${found.length} time(s) while content alternated; expected one shrink after it stopped`);
        }
    }
    if (Math.abs(settledShort - shortHeight) > 2 || Math.abs(width() - shortWidth) > 2) {
        return fail(`after alternating, the panel settled at ${size()}, not ${shortWidth}x${shortHeight}`);
    }

    // 8. Content taller than the window: auto-size stops at 80% of it so the resize corner can be
    //    reached, and the rest scrolls inside the panel.
    const hugeHeight = await show(huge);
    const cap = Math.floor(window.innerHeight * 0.8);
    steps.huge = {panel: hugeHeight, cap, content: cw.scrollHeight};
    if (hugeHeight > cap) return fail(`huge content made a ${hugeHeight}px panel; the cap is ${cap}px`);
    if (!overflows()) return fail('huge content should scroll inside the capped panel');
    if (Math.abs(await show(short('Building')) - shortHeight) > 2) return fail('the panel did not come back down from the cap');

    // 9. Canvas zoom is a scale() above the panel, so client rects are twice (or half) the layout
    //    size. Measured unscaled, zooming in doubled a panel on its next content change, and zooming
    //    out would have halved it.
    const previousTransform = wrap.style.transform;
    const zoomed = {};
    for (const zoom of [2, 0.5]) {
        wrap.style.transform = `scale(${zoom})`;
        // The same content as the unzoomed panel, re-rendered twice: width follows the text, so
        // different text would rightly get a different width.
        zoomed[zoom] = [];
        for (let i = 0; i < 2; i++) {
            await show(short('Building'));
            zoomed[zoom].push([width(), height()]);
        }
    }
    wrap.style.transform = previousTransform;
    steps.zoom = {zoomed, unzoomed: [shortWidth, shortHeight]};
    for (const [zoom, sizes] of Object.entries(zoomed)) {
        if (sizes.some(([w, h]) => Math.abs(w - shortWidth) > 2 || Math.abs(h - shortHeight) > 2)) {
            return fail(`at ${zoom}x zoom the panel went ${sizes.map(([w, h]) => `${w}x${h}`).join(' then ')}, not ${shortWidth}x${shortHeight}`);
        }
    }

    // 10. A panel the person resized keeps that size through tall, wide and short content alike.
    //     The native grip writes these inline dimensions.
    await delay(300);
    cw.style.width = '420px';
    cw.style.height = '260px';
    cw.dispatchEvent(new MouseEvent('mouseup', {bubbles: true}));
    await delay(300);
    const userSizes = [];
    for (const html of [tall, wide, short('Building')]) {
        await show(html);
        userSizes.push(size());
    }
    steps.userSized = userSizes;
    if (userSizes.some(s => s !== '420x260')) return fail(`a hand-sized 420x260 panel became ${userSizes.join(', then ')}`);

    return {ok: errors.length === 0, reason: errors.length ? 'page errors' : undefined, steps, errors};
} finally {
    window.removeEventListener('error', onError);
}
