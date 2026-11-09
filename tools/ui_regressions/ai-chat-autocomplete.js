// Real composer rendering and keyboard events; a controlled transport isolates timing from model availability.
const {mountChatAutocomplete, setChatAutocomplete, chatAutocompleteDiagnostics} = await import('./aiChatAutocomplete.js');
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const check = (value, message) => { if (!value) throw new Error(message); };
const wait = async (fn, message) => {
    for (let i = 0; i < 100; i++) { const value = fn(); if (value) return value; await delay(50); }
    throw new Error(message);
};
const box = [...document.querySelectorAll('.box')].find(b => b.name === 'ChatAutocomplete');
check(box, 'Missing composer fixture box');
const area = box.querySelector('.outputArea');
area.style.visibility = 'visible';
const api = window.disclosureInterface(area.querySelector('.disclosure-group'));
const content = api.upsertDisclosure('composer', 'Chat autocomplete', '');
const inputWrap = document.createElement('div');
inputWrap.className = 'ai-chat-input';
inputWrap.style.position = 'relative';
const textarea = document.createElement('textarea');
inputWrap.append(textarea);
content.append(inputWrap);
api.open('composer');
api.setSize('composer', 700, 200);
const requests = [];
setChatAutocomplete(true);
mountChatAutocomplete(inputWrap, textarea, {chatUid: () => box.uid, chatProperty: () => 'code',
    requestSuggestion: (_name, payload, _replyName, callback) => requests.push({payload, callback})});
const type = (value, cursor = value.length) => {
    textarea.value = value;
    textarea.focus();
    textarea.setSelectionRange(cursor, cursor);
    textarea.dispatchEvent(new Event('input', {bubbles: true}));
};
type('draw a ci ', 9);
await wait(() => requests.length === 1, 'Trailing whitespace prevented an automatic request');
check(requests[0].payload.draft === 'draw a ci', 'Request must complete at the caret');
requests[0].callback(null, {suggestion: 'rcle please'});
check(inputWrap.querySelector('.ai-chat-ghost-rest').textContent === 'rcle please', 'Suggestion not rendered');
document.execCommand('insertText', false, 'r');
check(inputWrap.querySelector('.ai-chat-ghost-rest').textContent === 'cle please', 'Typing failed to consume the suggestion');
textarea.dispatchEvent(new KeyboardEvent('keydown', {key: 'Tab', bubbles: true, cancelable: true}));
check(textarea.value === 'draw a circle please ', 'Accept must insert at the caret and preserve the whitespace suffix');
type('existing words later', 8);
await delay(650);
check(requests.length === 1, 'Completion requested inside existing text');
textarea.setSelectionRange(textarea.value.length, textarea.value.length);
textarea.dispatchEvent(new KeyboardEvent('keyup', {key: 'End', bubbles: true}));
await wait(() => requests.length === 2, 'Moving to the end did not schedule a request');
requests[1].callback(null, {suggestion: ''});
type('paint the circle');
await wait(() => requests.length === 3, 'Missing slow request');
type('paint the square');
await delay(650);
check(requests.length === 3, 'Requests overlapped while an older one was pending');
requests[2].callback(null, {suggestion: ' red'});
await wait(() => requests.length === 4, 'Newest draft was not retried after the stale response');
check(requests[3].payload.draft === 'paint the square', 'Retry used the stale draft');
requests[3].callback(null, {suggestion: ' blue'});
check(inputWrap.querySelector('.ai-chat-ghost-rest').textContent === ' blue', 'Fresh suggestion missing');
const evidence = chatAutocompleteDiagnostics();
check(evidence.events.some(e => e.event === 'discarded' && e.reason === 'draft_changed'), 'Missing discard diagnostic');
check(evidence.events.filter(e => e.event === 'shown').length >= 2, 'Missing display diagnostics');
check(!JSON.stringify(evidence).includes('paint the'), 'Diagnostics leaked draft contents');
return {ok: true, requests: requests.length, whitespaceSuffix: true, insertionAtCaret: true,
    movingToEnd: true, staleRetry: true, singleFlight: true, diagnostics: true};
