// A slider label is split into two runs to put any ellipsis in the middle. When the split falls on a
// space, the first run ends with it, and that space must still be drawn: "focal length", not
// "focallength".
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const labels = () => [...document.querySelectorAll('.field-slider .field-label.field-label-middle')]
    .filter((l) => l.getBoundingClientRect().width > 0);
for (let i = 0; i < 80 && labels().length < 2; i++) await sleep(250);
const found = labels();
if (found.length < 2) throw new Error('no split slider labels laid out');
const widthOf = (text, like) => {
    const probe = document.createElement('span');
    probe.textContent = text;
    probe.style.cssText = 'position:absolute;visibility:hidden;white-space:pre';
    like.appendChild(probe);
    const w = probe.getBoundingClientRect().width;
    probe.remove();
    return w;
};
const results = found.map((label) => {
    const start = label.querySelector('.field-label-start');
    const text = start.textContent;
    const drawn = start.scrollWidth;
    return { text, drawn, withSpace: widthOf(text, start), withoutSpace: widthOf(text.trimEnd(), start) };
});
for (const r of results) {
    if (!r.text.endsWith(' ')) throw new Error('test precondition: the split should end on a space: ' + JSON.stringify(results));
    if (r.drawn < r.withSpace - 0.5) throw new Error('the space at the split is not drawn: ' + JSON.stringify(results));
}
return { ok: true, results };
