// Exercise Field's real disclosure and chat renderer without making a model request.
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const check = (condition, detail) => { if (!condition) throw new Error(detail); };
const content = document.querySelector('.ai-chat-content');
check(content, 'Missing real chat output');
const api = window.disclosureInterface(content.closest('.disclosure-group'));
const messages = content.querySelector('.ai-chat-messages');
const uid = content.querySelector('.ai-chat-window').dataset.uid;
const draft = content.querySelector('textarea');
draft.value = 'Keep my draft';
await delay(800);
check(content.offsetWidth <= 960 && content.offsetHeight <= 560, 'Default chat should remain bounded');
// The native CSS resize grip changes these same inline dimensions.
content.style.width = '1080px';
content.style.height = '820px';
content.dispatchEvent(new MouseEvent('mouseup', {bubbles:true}));
await delay(500);
const size = () => [content.offsetWidth, content.offsetHeight];
check(JSON.stringify(size()) === '[1080,820]', 'User resize was clamped: ' + size());
const history = content.__aiChatHistory;
messages.scrollTop = 320;
await delay(50);
const top = messages.scrollTop;
const first = messages.firstElementChild;
window.aiChatUpdate(content, uid, history);
check(messages.firstElementChild === first, 'Identical history unnecessarily replaced messages');
for (let i = 0; i < 4; i++) {
    window.aiChatUpdate(content, uid, history + '\n\nProgress ' + i);
    api.open('ai-chat');
    await delay(100);
}
check(JSON.stringify(size()) === '[1080,820]', 'Streaming reset size: ' + size());
check(Math.abs(messages.scrollTop - top) <= 2, 'Streaming reset reading position');
api.close('ai-chat'); api.open('ai-chat');
check(JSON.stringify(size()) === '[1080,820]', 'Reopening reset size');
// CSS transforms reproduce canvas zoom without changing the user's viewport.
const wrap = content.parentElement;
const oldTransform = wrap.style.transform;
wrap.style.transform = 'scale(0.5)';
content.dispatchEvent(new MouseEvent('mouseup', {bubbles:true}));
api.close('ai-chat'); api.open('ai-chat');
wrap.style.transform = oldTransform;
check(JSON.stringify(size()) === '[1080,820]', 'Zoomed screen pixels were saved as layout pixels');

// Decode real inline image elements, then stream updates while reading among them.
const svg = '<svg xmlns="http://www.w3.org/2000/svg" width="700" height="260"><rect width="700" height="260" fill="#333"/><text x="20" y="50" fill="white">Chat image regression fixture</text></svg>';
const image = 'data:image/svg+xml;base64,' + btoa(svg);
const imageHistory = '<<you>>\nReview these images.\n<<ai>>\n' + Array.from({length:10}, (_, i) => 'Image ' + i + '\n\n![Evidence](' + image + ')').join('\n\n');
window.aiChatUpdate(content, uid, imageHistory);
await Promise.all([...messages.querySelectorAll('img')].map(img => img.decode()));
await delay(100);
messages.scrollTop = 900;
await delay(30);
const imageTop = messages.scrollTop;
check(imageTop >= 850, 'Image fixture has insufficient scrollable content');
for (let i = 0; i < 4; i++) window.aiChatUpdate(content, uid, imageHistory + '\n\nNew detail ' + i);
await delay(250);
check(Math.abs(messages.scrollTop - imageTop) <= 2, 'Image updates reset reading position: ' + messages.scrollTop);
messages.scrollTop = messages.scrollHeight;
window.aiChatUpdate(content, uid, imageHistory + '\n\nLatest detail');
await delay(100);
check(messages.scrollHeight - messages.scrollTop - messages.clientHeight <= 2, 'Newest messages no longer follow at bottom');
// A user gesture must supersede queued settling callbacks.
window.aiChatUpdate(content, uid, imageHistory + '\n\nAnother detail');
messages.dispatchEvent(new WheelEvent('wheel', {deltaY:-100}));
messages.scrollTop = 400;
await delay(200);
check(Math.abs(messages.scrollTop - 400) <= 2, 'Late image callback overrode reader scrolling');
check(draft.value === 'Keep my draft', 'Draft was lost');
check(JSON.stringify(size()) === '[1080,820]', 'Image updates resized chat');
return {ok:true, size:size(), preservedReadingOffset:imageTop, draftPreserved:true, zoomSafe:true, imageUpdates:4};
