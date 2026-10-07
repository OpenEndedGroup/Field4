// The lens viewer draws a chain of lenses beside the chat they summarize, with the chat panel's renderer, and
// a band from each lens block to the stretch it cites, coloured by the voice it comes from. The fixture
// (chat-lens.kt) is a summary lens over a two-block lens over a two-turn chat; the lower lens cites from inside
// a folded round, leaves the second turn uncited, and reuses a tool picture through the blob route. A saved
// view is one HTML file that draws its bands with no Field.
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const check = (value, message) => { if (!value) throw new Error(message); };
const wait = async (fn, label, timeout = 20000) => {
    const until = Date.now() + timeout;
    while (Date.now() < until) { if (fn()) return; await delay(50); }
    throw new Error(label);
};

try {
let root;
await wait(() => (root = document.querySelector('.chat-lens'))?.chatLensState && root.clientHeight > 0 && root.chatLensState().perPair.every(p => p.drawn > 0),
    'the viewer mounts and draws its bands between every pair of columns');
const state = root.chatLensState();
check(state.columns === 3, `three columns (overview, fixture, chat), got ${state.columns}`);
check(state.perPair[0].bands === 1 && state.perPair[1].bands === 2, `bands per pair: ${JSON.stringify(state.perPair)}`);
const [overview, middle, source] = root.querySelectorAll('.chat-lens-col');
check(overview.querySelector('.ai-chat-turn.summary') && middle.querySelector('.ai-chat-turn.you') && source.querySelector('.ai-chat-round'),
    'every column renders as chat, the top one in the summary voice');
check(middle.querySelector('[data-ai-sources] .ai-chat-model')?.textContent === 'fixture · fixture-model', 'lens blocks are labelled with their lens and its model');
check(root.querySelectorAll('.chat-lens-label').length === 3, 'each column is labelled');

const [upperGutter, lowerGutter] = root.querySelectorAll('.chat-lens-gutter');
check(upperGutter.querySelector('.chat-lens-band').classList.contains('voice-summary'), 'a band from a summary is grey');
const lowerBands = lowerGutter.querySelectorAll('.chat-lens-band');
check(lowerBands[0].classList.contains('voice-you') && lowerBands[1].classList.contains('voice-ai'), 'a band from the user is amber, from the agent cyan');
check(getComputedStyle(lowerBands[0]).fill !== getComputedStyle(lowerBands[1]).fill, 'and they look different');

const images = [...root.querySelectorAll('img')];
await wait(() => images.length === 2 && images.every(img => img.complete && img.naturalWidth > 0), 'both pictures load through the blob route');
check(images.every(img => img.loading === 'eager'), 'pictures load eagerly: a late one would move the bands below it');

const roof = source.querySelector('[data-ai-address="t-b.b0"]');
check(roof?.classList.contains('chat-lens-uncited'), 'what the lens leaves out reads dimmer');
check(!source.querySelector('[data-ai-address="t-a.b6"]').classList.contains('chat-lens-uncited'), 'what it cites does not');

// The lower lens's second band starts inside the closed round: its source end is the round's line.
const round = source.querySelector('.ai-chat-round');
check(!round.open, 'the round is closed');
const band = lowerBands[1];
const gutter = lowerGutter.getBoundingClientRect();
// d is M0,lt C mid,lt mid,st x1,st L x1,sb C …: the first curve's end point is the source top.
const sourceTop = Number(band.getAttribute('d').match(/C([\d.-]+),([\d.-]+) ([\d.-]+),([\d.-]+) ([\d.-]+),([\d.-]+)/)?.[6]);
check(Math.abs(sourceTop - (round.getBoundingClientRect().top - gutter.top)) < 2,
    `the band's source end is the round's line (band ${sourceTop}, round ${round.getBoundingClientRect().top - gutter.top})`);

const cited = middle.querySelectorAll('[data-ai-sources]')[1];
cited.dispatchEvent(new MouseEvent('mouseenter'));
check(band.classList.contains('active') && round.classList.contains('chat-lens-active'), 'hover lights the band and its stretch');
cited.dispatchEvent(new MouseEvent('mouseleave'));
check(!band.classList.contains('active'), 'and lets go');

// Saved: one HTML file, pictures inline, that draws its bands with no Field behind it.
const { chatLensHtml } = await import('/chatLens.js');
const html = await chatLensHtml(root, 'overview');
check(!html.includes('/api/chatlog/blob') && (html.match(/data:image\/png;base64/g) || []).length >= 2, 'the saved file carries its pictures');
const frame = document.createElement('iframe');
frame.style.cssText = 'position:fixed;left:0;top:0;width:1200px;height:700px;z-index:99999';
frame.srcdoc = html;
document.body.appendChild(frame);
let saved;
await wait(() => (saved = frame.contentDocument?.querySelector('.chat-lens'))?.chatLensState?.().perPair.every(p => p.drawn > 0),
    'the saved file draws its bands on its own');
const savedState = saved.chatLensState();
frame.remove();
check(savedState.columns === 3 && savedState.bands === 3, `the saved view keeps the chain: ${JSON.stringify(savedState)}`);
return {ok: true, state, savedBytes: html.length};
} catch (error) { return {ok: false, reason: String(error)}; }
