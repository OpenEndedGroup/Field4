// Real AI4 composer and RPCs; this layout/control test makes no model request.
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const check = (value, message) => { if (!value) throw new Error(message); };
const wait = async (fn, message) => {
    for (let i = 0; i < 100; i++) { const value = fn(); if (value) return value; await delay(50); }
    throw new Error(message);
};
const box = [...document.querySelectorAll('.box')].find(b => b.name === 'RunControls');
check(box, 'Missing RunControls box');
window.openAiChatForBox(box.uid, 'code');
const root = await wait(() => document.querySelector(`.ai-chat-window[data-uid="${box.uid}"]`), 'Chat did not open');
const toggle = await wait(() => root.querySelector('[aria-label="Chat actions"]'), 'Burger missing');
const menu = root.querySelector('.ai-chat-actions-menu');
const button = label => [...menu.querySelectorAll('button')].find(b => b.textContent === label);
const open = async () => {
    toggle.click();
    await wait(() => menu.matches(':popover-open'), 'Menu did not open');
};
check(!root.querySelector('select[aria-label="Message timing"]'), 'Timing dropdown still present');
check(root.querySelector('.garden-bar').offsetHeight === 0, 'Garden reserves a toolbar row in chat');
check(!menu.matches(':popover-open'), 'Menu should start closed');
await open();
for (const label of ['Correct this run', 'Do next', 'Stop', 'Recall queued', 'Context', 'Open Garden']) {
    check(button(label), `${label} missing`);
}
check(button('Do next').disabled, 'Empty draft must not offer send');
button('Context').click();
await wait(() => root.textContent.includes('Pending input:'), 'Context RPC did not reach this chat');
check(!menu.matches(':popover-open'), 'Choosing an action must close the menu');
const textarea = root.querySelector('.ai-chat-input textarea');
textarea.value = 'keep my draft';
await open();
check(!button('Do next').disabled, 'Draft must enable send');
button('Recall queued').click();
await wait(() => root.textContent.includes('Undelivered messages returned'), 'Recall response missing');
check(textarea.value === 'keep my draft', 'Recall destroyed current draft');
await open();
button('Open Garden').click();
await wait(() => root.classList.contains('garden-open'), 'Garden did not open');
const back = [...root.querySelectorAll('.garden-bar button')].find(b => b.textContent === 'Back to chat');
check(back, 'Garden has no return action');
back.click();
check(!root.classList.contains('garden-open'), 'Back to chat failed');
check(root.querySelector('.garden-bar').offsetHeight === 0, 'Closed Garden still reserves space');
check(textarea.value === 'keep my draft', 'Garden view switch lost draft');
const content = root.closest('.disclosure-content');
content.style.width = '430px';
await delay(100);
await open();
menu.dispatchEvent(new KeyboardEvent('keydown', {key: 'ArrowDown', bubbles: true}));
check(document.activeElement === button('Do next'), 'Arrow navigation failed');
for (const element of [toggle, document.activeElement]) {
    const style = getComputedStyle(element);
    check(style.outlineStyle === 'none' && style.boxShadow === 'none', 'Unwanted focus ring');
}
const bounds = root.getBoundingClientRect(), trigger = toggle.getBoundingClientRect(), popup = menu.getBoundingClientRect();
const entryBounds = textarea.getBoundingClientRect();
check(trigger.right < entryBounds.right && trigger.left > entryBounds.left && trigger.bottom < entryBounds.bottom,
    'Burger must float inside the text entry, not occupy its own column');
const visualizerSelect = document.createElement('select');
visualizerSelect.className = 'field-menu-select';
root.append(visualizerSelect);
const fieldPopup = getComputedStyle(visualizerSelect, '::picker(select)'), chatPopup = getComputedStyle(menu);
for (const property of ['backgroundColor', 'color', 'fontFamily', 'fontSize', 'borderRadius']) {
    check(chatPopup[property] === fieldPopup[property], `Chat popup differs from visualizer popup: ${property}`);
}
visualizerSelect.remove();
check(trigger.right <= bounds.right + 2 && trigger.bottom <= bounds.bottom + 2, 'Burger overflows panel');
check(popup.right <= innerWidth && popup.left >= 0 && popup.top >= 0, 'Popup leaves viewport');
menu.dispatchEvent(new KeyboardEvent('keydown', {key: 'Escape', bubbles: true}));
check(!menu.matches(':popover-open') && document.activeElement === toggle, 'Escape must close and restore focus');
await open();
// Leave the actual popup visible in the regression screenshot.
return {ok: true, burger: true, contextRpc: true, recallPreservesDraft: true, gardenSwitch: true, narrowLayout: true, noFocusRing: true};
