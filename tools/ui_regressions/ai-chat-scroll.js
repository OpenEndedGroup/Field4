// The AI chat re-renders its whole transcript on every update. A reader scrolled up to an earlier turn
// must stay there, and one at the bottom must stay at the bottom, when the transcript holds inline images
// (a painting turn's paint_look pictures): before 2026-10-04 they had no height at the moment the
// position was restored, and the panel jumped to near the top on every update.
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const check = (value, message) => { if (!value) throw new Error(message); };
const wait = async (fn, label, timeout = 15000) => {
    const until = Date.now() + timeout;
    while (Date.now() < until) { if (fn()) return; await delay(50); }
    throw new Error(label);
};
const picture = (i) => {
    const c = document.createElement('canvas'); c.width = 900; c.height = 720;
    const g = c.getContext('2d');
    g.fillStyle = `hsl(${(i * 37) % 360} 60% 45%)`; g.fillRect(0, 0, 900, 720);
    g.fillStyle = '#fff'; g.font = '80px sans-serif'; g.fillText('look ' + i, 60, 380);
    return c.toDataURL('image/png');
};
const history = (turns) => Array.from({length: turns}, (_, i) =>
    `<<you>>\nstage ${i}\n<<ai>>\nLooked at the canvas.\n\n![look ${i}](${picture(i)})\n\nOn to stage ${i + 1}.`).join('\n');

try {
const box = [...document.querySelectorAll('.box')].find(b => b.name === 'ChatScroll');
check(box, 'fixture box');
window.openAiChatForBox(box.uid);
await wait(() => box.querySelector('.ai-chat-messages'), 'chat panel opens');
const messages = box.querySelector('.ai-chat-messages');
let container = messages;
while (container && !container.__aiChat) container = container.parentElement;
check(container, 'chat container');
// What outputareas.kt does for every `set-output` on the ai-chat channel during a turn.
const push = (markdown) => {
    const inter = window.disclosureInterface(box.querySelector('.outputArea .disclosure-group'));
    const content = inter.upsertDisclosure('ai-chat', 'AI Chat', '__AFIELD_PRESERVE_DISCLOSURE_CONTENT__');
    content.classList.add('ai-chat-content');
    window.aiChatUpdate(content, box.uid, markdown);
    inter.open('ai-chat');
};
const settled = async () => {
    await wait(() => [...messages.querySelectorAll('img')].every(img => img.complete), 'images load');
    await delay(150);
};

let md = history(10);
push(md);
await settled();
const max = messages.scrollHeight - messages.clientHeight;
check(max > 2000, `the transcript is tall enough to scroll (${max})`);

// A reader parked in the middle, re-reading an earlier stage.
const parked = Math.round(max * 0.5);
messages.scrollTop = parked;
await delay(50);
for (let n = 0; n < 3; n++) {
    md += `\n<<progress>>\nprogram: paint_work (${n + 1})`;
    push(md);
    check(Math.abs(messages.scrollTop - parked) < 4, `update ${n}: jumped from ${parked} to ${messages.scrollTop} at once`);
    await settled();
    check(Math.abs(messages.scrollTop - parked) < 4, `update ${n}: jumped from ${parked} to ${messages.scrollTop} once the images loaded`);
}

// A reader at the bottom stays at the bottom as the turn grows.
messages.scrollTop = messages.scrollHeight;
await delay(50);
md += `\n<<ai>>\nOne more look.\n\n![look 10](${picture(10)})`;
push(md);
await settled();
const slack = messages.scrollHeight - messages.scrollTop - messages.clientHeight;
check(slack < 4, `stayed at the bottom (slack ${slack})`);
return {ok: true, parked, max, slack};
} catch (error) { return {ok: false, reason: String(error)}; }
