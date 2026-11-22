const t = window.__passageTest;
const wait = async (test, detail) => {
    for (let i = 0; i < 200; i++) {
        if (test()) return;
        await new Promise(resolve => setTimeout(resolve, 30));
    }
    throw new Error(detail + ': ' + JSON.stringify({anchor:t.anchor, children:t.parent().box_children, note:t.child()?.uid}));
};
const linked = () => t.parent().box_children?.includes(t.child()?.uid);
await wait(() => t.anchor?.excerpt === 'selected words' && !t.anchor.lost && linked(),
    'The note did not retain both the selected passage and the parent edge');
const badge = t.child().querySelector('.text-frame-passage');
badge.click();
const highlight = () => [...(CSS.highlights.get('field-source-passage') || [])][0]?.toString();
await wait(() => highlight() === 'selected words', 'Source passage did not highlight the original text');
const id = t.anchor.id;
t.write('New lead!\n\nLead before.\n\nA **selected extra words** remain.\n\nTail. Appended.');
await wait(() => t.anchor.excerpt === 'selected extra words' && t.anchor.id === id &&
    highlight() === 'selected extra words', 'The anchor and highlight did not follow edits before, inside, and after it');
t.write('New lead!\n\nLead before.\n\nA remain.\n\nTail. Appended.');
await wait(() => t.anchor.lost && t.anchor.id === id && t.child().isConnected && linked() &&
    badge.textContent === 'Passage lost' && !highlight(), 'Deleting the passage should mark the note lost and keep its connection');
return {ok:true};
