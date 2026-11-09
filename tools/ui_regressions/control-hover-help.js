const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const checks = [];
const check = (condition, message) => {
    checks.push({message, passed: !!condition});
    if (!condition) throw new Error(message);
};
const expected = {
    'A long gain label for testing': '**Gain** & `units`\n\n- first\n- [guide](https://example.com)\n- [bad](javascript:alert)\n\n<img src=x onerror="window.controlHoverXss=1">',
    Enabled: 'Toggle help', Reset: 'Reset help', Name: 'Name help', Tint: 'Colour help',
    Position: 'XY help', Level: 'Level help', Steps: 'Steps help', Grid: 'Grid help',
    Range: 'Range help', Mode: 'Mode help', Gesture: 'Gesture help', 'Key size': 'Keyed help'
};
const labels = [...document.querySelectorAll('.field-label')];
for (const [name, text] of Object.entries(expected)) {
    const label = labels.find(el => el.textContent === name);
    check(label?.dataset.hoverText === text, `Help survives markup for ${name}`);
    const row = label.closest('.field-control');
    const target = row.matches('[tabindex]') ? row : row.querySelector('input:not([type=hidden]), select, button, textarea, [tabindex]') || label;
    const accessible = target.getAttribute('aria-description');
    check(name === 'A long gain label for testing' ? accessible?.includes('Gain') && accessible.includes('units') && !accessible.includes('**') : accessible === text, `Accessible help for ${name}`);
}
const plain = labels.find(el => el.textContent === 'Plain slider');
check(!plain.dataset.hoverText && plain.title === 'Plain slider', 'Existing label tooltip is retained');
const label = labels.find(el => el.textContent === 'A long gain label for testing');
check(!label.title && label.classList.contains('field-label-middle'), 'Custom help survives slider label preparation');
label.dispatchEvent(new PointerEvent('pointerover', {bubbles:true, pointerType:'mouse'}));
await delay(550);
const popup = document.querySelector('.field-control-tooltip');
check(popup && !popup.hidden, 'Hover displays a Field popup');
check(popup.textContent.includes(label.textContent), 'Popup contains the full label');
check(popup.querySelector('strong')?.textContent === 'Gain' && popup.querySelector('code')?.textContent === 'units', 'Markdown emphasis and code render');
check(popup.querySelectorAll('li').length === 3, 'Markdown list renders');
check(popup.querySelector('a[href="https://example.com"]'), 'Safe Markdown links render');
check(![...popup.querySelectorAll('a')].some(el => el.textContent === 'bad') && !popup.querySelector('img, script') && !window.controlHoverXss, 'Unsafe links and markup are inert');
const rect = popup.getBoundingClientRect();
check(rect.left >= 0 && rect.top >= 0 && rect.right <= innerWidth && rect.bottom <= innerHeight, 'Popup fits in the viewport');
label.dispatchEvent(new PointerEvent('pointerout', {bubbles:true, relatedTarget:document.body}));
popup.dispatchEvent(new PointerEvent('pointerover', {bubbles:true, pointerType:'mouse'}));
await delay(300);
check(!popup.hidden, 'The pointer can move into the help to reach its links');
popup.querySelector('a[href="https://example.com"]').dispatchEvent(new PointerEvent('pointerdown', {bubbles:true}));
check(!popup.hidden, 'Pressing a help link does not dismiss the popup');
popup.dispatchEvent(new PointerEvent('pointerout', {bubbles:true, relatedTarget:document.body}));
await delay(300);
check(popup.hidden, 'Leaving the label hides the popup');
const checkboxLabel = labels.find(el => el.textContent === 'Enabled');
const checkbox = checkboxLabel.closest('.field-control').querySelector('input');
checkboxLabel.click();
check(checkbox.checked, 'Clicking the checkbox label still toggles the control');
checkbox.blur();
checkbox.focus();
await delay(550);
check(!popup.hidden && popup.textContent.includes('Toggle help'), 'Keyboard focus shows the same help');
document.dispatchEvent(new KeyboardEvent('keydown', {key:'Escape', bubbles:true}));
check(popup.hidden, 'Escape dismisses the help');
label.dispatchEvent(new PointerEvent('pointerover', {bubbles:true, pointerType:'mouse'}));
await delay(550);
const box = [...document.querySelectorAll('.box')].find(el => el.textContent.includes('ControlHoverHelp'));
check(box, 'The source box is available to the controller page');
if (typeof window.selectBox === 'function') window.selectBox(box);
if (typeof window.selectionContextChanged === 'function') window.selectionContextChanged();
await delay(900);
const frame = document.createElement('iframe');
frame.style.cssText = 'position:fixed;left:0;top:0;width:400px;height:500px;opacity:0;pointer-events:none';
frame.src = `/controllers?page=controls&file=${encodeURIComponent(new URL(location.href).searchParams.get('file') || 'scratch')}`;
document.body.appendChild(frame);
const until = async (fn) => { for (let i = 0; i < 120; i++) { const value = fn(); if (value) return value; await delay(100); } return null; };
const phoneLabel = await until(() => [...(frame.contentDocument?.querySelectorAll('.phone-control-label') || [])]
    .find(el => el.textContent === 'A long gain label for testing'));
check(phoneLabel && phoneLabel.dataset.hoverText === expected[label.textContent], 'The controller view receives the Markdown help');
phoneLabel.dispatchEvent(new frame.contentWindow.PointerEvent('pointerover', {bubbles:true, pointerType:'mouse'}));
await delay(550);
const phonePopup = frame.contentDocument.querySelector('.field-control-tooltip');
check(phonePopup && !phonePopup.hidden && phonePopup.querySelector('strong')?.textContent === 'Gain', 'The controller view renders Markdown help');
frame.remove();
return {ok:true, checks, popup:{text:popup.textContent, left:popup.style.left, top:popup.style.top}};
