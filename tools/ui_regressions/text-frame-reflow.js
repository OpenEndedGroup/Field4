// Real text edits drive intrinsic typeset sizing and persistent graph-frame reflow.
const {newBox, send} = await import('./afield.mjs');
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const check = (value, message) => { if (!value) throw Error(message); };
const wait = async (fn, message) => {
    for (let i = 0; i < 160; i++) { if (fn()) return; await delay(50); }
    throw Error(message);
};
const boxes = () => [...document.querySelectorAll('.box')];
const created = [];
async function make(name, x, y, kind = 'text-frame', textFrame = 'sizing: fixed\n') {
    newBox({kind, name, contents: 'One line.', textFrame, frame: {x, y, w: 300, h: 100}});
    await wait(() => boxes().some(b => b.name === name), 'Reflow fixture missing');
    const box = boxes().find(b => b.name === name);
    created.push(box);
    box.querySelector('.text-frame-rich-block')?.dispatchEvent(new KeyboardEvent('keydown', {key:'Escape', bubbles:true,cancelable:true}));
    await delay(100);
    return box;
}
const source = await make('Flow source', 2000, 1000, 'text-frame', 'minHeight: 100\n');
await wait(() => source.data_h === 100, 'Frame did not fit its typeset minimum');
const lower = await make('Flow lower', 2150, 1132);
const chain = await make('Flow chain', 2400, 1264);
const beside = await make('Other column', 2800, 1132);
const regular = await make('Regular stays', 2000, 1396, 'regular');
const setText = value => send('set-string', {uid:source.uid,property:'contents',value});
const long = ('A formatted paragraph on the canvas.\n\n').repeat(14);
setText(long);
await wait(() => source.data_h > 400 && lower.data_y >= source.data_y + source.data_h + 32, 'Expanding text did not push the colliding frame down');
await wait(() => chain.data_y >= lower.data_y + lower.data_h + 32, 'Expansion did not propagate through the column');
check(beside.data_y === 1132 && regular.data_y === 1396, 'Reflow moved a different column or a regular box');
const expanded = source.data_h, lowerY = lower.data_y, chainY = chain.data_y;
const partial = await make('Partly overlapping', 2000, source.data_y + expanded - 20);
const partialY = partial.data_y;
setText('One line.');
await wait(() => source.data_h === 100, 'Removing text did not contract the frame');
await wait(() => Math.abs(lower.data_y - (lowerY - expanded + 100)) <= 1, 'Contraction did not pull the wholly lower frame up');
check(Math.abs(chain.data_y - (chainY - expanded + 100)) <= 1, 'Contraction moved the chain twice or failed to pull it up');
check(partial.data_y === partialY && beside.data_y === 1132, 'Contraction pulled a partly overlapping frame or another column');
send('set-string', {uid:source.uid,property:'textFrame',value:'minHeight: 100\nreflow: false\n'});
await wait(() => source.textFrame.includes('reflow: false'), 'Reflow opt-out did not sync');
const heldY = lower.data_y;
setText(long);
await wait(() => source.data_h > 400, 'Opt-out fixture did not expand');
await delay(200);
check(lower.data_y === heldY, 'Reflow opt-out still moved another frame');
send('perform-action', {uid_currentSelection:created.map(b=>b.uid),uid_action:'-delete-box'});
await wait(() => created.every(b=>!b.isConnected || b.classList.contains('tombstoned')), 'Reflow fixtures did not clean up');
return {ok:true, fittedHeight:true, pushCollision:true, pullWhollyBelow:true, chainMovesOnce:true,
    partlyOverlappingStays:true, otherColumnStays:true, regularBoxStays:true, optOut:true};
