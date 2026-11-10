// A lines viewer pins its panel's size; HTML that later replaces it in the same channel must size
// the panel to itself again rather than be clipped to the viewer's old height.
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let card = null;
for (let i = 0; i < 80 && !(card = document.getElementById('mode-switch-card')); i++) await sleep(250);
if (!card) throw new Error('the card never arrived');
await sleep(1500);
const content = card.closest('.disclosure-content');
const result = { mode: content.dataset.afieldAutoSizeMode, scrollHeight: content.scrollHeight, clientHeight: content.clientHeight };
if (content.scrollHeight > content.clientHeight + 4) throw new Error('the panel clips the card: ' + JSON.stringify(result));
return { ok: true, ...result };
