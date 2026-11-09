// Successive invocations cross real browser reloads; no synthetic transcript is drawn by this probe.
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const check = (value, message) => { if (!value) throw new Error(message); };
const wait = async (fn, message) => {
    for (let i = 0; i < 160; i++) { const value = fn(); if (value) return value; await delay(50); }
    throw new Error(message);
};
await wait(() => window.fieldBackdoorUiReady, 'Document did not finish reloading');
const box = await wait(() => [...document.querySelectorAll('.box')].find(b => b.name === 'ChatPersistence'), 'Missing saved box');
const phaseKey = 'field-chat-persistence-' + box.uid;
const phase = Number(sessionStorage.getItem(phaseKey) || 0);
const group = box.querySelector('.outputArea .disclosure-group');
const api = window.disclosureInterface(group);
const content = () => group.querySelector('.ai-chat-content');
const history = () => content()?.querySelector('.ai-chat-messages')?.textContent;
if (phase === 0) {
    await wait(() => history()?.includes('Saved reply, ready to continue.'), 'Older saved conversation did not automatically reappear');
    check(!content().contains(document.activeElement), 'Automatic restoration stole focus');
    window.openAiChatForBox(box.uid, 'fragment');
    api.setSize('ai-chat', 1080, 820);
    window.openAiChatForBox(box.uid, 'vertex');
} else if (phase === 1) {
    await wait(() => history()?.includes('Saved reply, ready to continue.'), 'Conversation missing after reload');
    check(api.isOpen('ai-chat'), 'Open panel was not restored');
    await wait(() => content().offsetWidth === 1080 && content().offsetHeight === 820 && content().__aiChat.property === 'vertex',
        'Manual dimensions/property were not restored: ' + box.ai4_chat_panel);
    check(!content().contains(document.activeElement), 'Reload stole focus');
    api.close('ai-chat');
} else if (phase === 2) {
    await wait(() => history()?.includes('Saved reply, ready to continue.'), 'Collapsed chat lost its conversation');
    check(!api.isOpen('ai-chat'), 'Collapsed panel reopened');
    check(api.getSize('ai-chat').width === 1080 && api.getSize('ai-chat').height === 820, 'Collapsed panel lost its dimensions');
    api.remove('ai-chat');
} else if (phase === 3) {
    await delay(400);
    check(!content(), 'Removed panel reappeared');
    window.openAiChatForBox(box.uid, 'code');
    const {sendWithReply} = await import('./afield.mjs');
    await new Promise(resolve => sendWithReply('ai-chat-history', {uid: box.uid, clear: true},
        'chat-persistence-clear-' + Date.now(), (_message, response) => { check(!response.error, response.error); resolve(); return 'remove'; }));
    api.setSize('ai-chat', 700, 400);
} else {
    await wait(() => content()?.__aiChat, 'Empty panel was not restored');
    check(api.isOpen('ai-chat'), 'Empty panel is closed');
    check(content().offsetWidth === 700 && content().offsetHeight === 400, 'Empty panel lost its dimensions');
    check(!history()?.includes('Saved reply'), 'Cleared conversation returned');
    sessionStorage.removeItem(phaseKey);
    return {ok: true, reloads: 4, legacy: true, dimensions: true, property: true, collapsed: true, removed: true, empty: true, focusPreserved: true};
}
// Let the websocket round trip finish before navigating away. The next invocation checks the server echo.
await delay(600);
sessionStorage.setItem(phaseKey, String(phase + 1));
setTimeout(() => location.reload(), 150);
return {ok: true, phase, reloading: true};
