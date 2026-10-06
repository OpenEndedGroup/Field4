// The lens viewer draws a lens beside its chat with the chat panel's renderer and a band from each lens
// block to the stretch it cites. The fixture (chat-lens.kt) cites from inside a folded round, leaves the
// second turn uncited, and reuses a tool picture through the blob route.
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const check = (value, message) => { if (!value) throw new Error(message); };
const wait = async (fn, label, timeout = 20000) => {
    const until = Date.now() + timeout;
    while (Date.now() < until) { if (fn()) return; await delay(50); }
    throw new Error(label);
};

try {
let root;
await wait(() => (root = document.querySelector('.chat-lens'))?.chatLensState && root.clientHeight > 0 && root.chatLensState().drawn > 0,
    'the viewer mounts and draws its bands');
const state = root.chatLensState();
check(state.bands === 2, `two bands, got ${state.bands}`);
const lens = root.querySelector('.chat-lens-lens');
const source = root.querySelector('.chat-lens-source');
check(lens.querySelector('.ai-chat-turn.you') && source.querySelector('.ai-chat-round'), 'both columns render as chat');
check(lens.querySelector('[data-ai-sources] .ai-chat-model')?.textContent === 'fixture · fixture-model', 'lens blocks are labelled with the lens and its model');
check(root.querySelector('.chat-lens-meta').textContent.includes('2.0× shorter'), 'the head carries the record');

const images = [...root.querySelectorAll('img')];
await wait(() => images.length === 2 && images.every(img => img.complete && img.naturalWidth > 0), 'both pictures load through the blob route');
check(images.every(img => img.loading === 'eager'), 'pictures load eagerly: a late one would move the bands below it');

const roof = [...source.querySelectorAll('[data-ai-address]')].find(el => el.dataset.aiAddress === 't-b.b0');
check(roof?.classList.contains('chat-lens-uncited'), 'what the lens leaves out reads dimmer');
check(!source.querySelector('[data-ai-address="t-a.b6"]').classList.contains('chat-lens-uncited'), 'what it cites does not');

// The second band starts inside the closed round: its source end is the round's line.
const round = source.querySelector('.ai-chat-round');
check(!round.open, 'the round is closed');
const band = root.querySelectorAll('.chat-lens-band')[1];
const d = band.getAttribute('d');
const gutter = root.querySelector('.chat-lens-gutter').getBoundingClientRect();
// d is M0,lt C mid,lt mid,st x1,st L x1,sb C …: the first curve's end point is the source top.
const sourceTop = Number(d.match(/C([\d.-]+),([\d.-]+) ([\d.-]+),([\d.-]+) ([\d.-]+),([\d.-]+)/)?.[6]);
check(Math.abs(sourceTop - (round.getBoundingClientRect().top - gutter.top)) < 2,
    `the band's source end is the round's line (band ${sourceTop}, round ${round.getBoundingClientRect().top - gutter.top})`);

// Hover highlights both ends.
const cited = lens.querySelectorAll('[data-ai-sources]')[1];
cited.dispatchEvent(new MouseEvent('mouseenter'));
check(band.classList.contains('active') && round.classList.contains('chat-lens-active'), 'hover lights the band and its stretch');
cited.dispatchEvent(new MouseEvent('mouseleave'));
check(!band.classList.contains('active'), 'and lets go');
return {ok: true, state};
} catch (error) { return {ok: false, reason: String(error)}; }
