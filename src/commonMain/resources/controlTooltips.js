// Tooltips for Field's controls: any element carrying `data-tooltip`, which is what the `tooltip`
// argument every control takes becomes (`withControlTooltip` in afield/ui/output/outputs.kt).
//
// One floating element for the whole page, shown after a short hover or at once on keyboard focus,
// hidden by a press, a key, a scroll or leaving the control. It shows the control's full label (a
// long label is clipped in the middle on the control itself) above the text. The native `title` a
// clipped label carries is removed inside a host, or the browser would show a second, plainer
// tooltip a second later.

const SHOW_DELAY_MS = 350;
let tipElement = null;
let showTimer = null;
let shownFor = null;
let suppressedFor = null;

function hostOf(target) {
    return target && target.closest ? target.closest('[data-tooltip]') : null;
}

function tooltipElement() {
    if (tipElement && tipElement.isConnected) return tipElement;
    tipElement = document.createElement('div');
    tipElement.className = 'field-tooltip';
    tipElement.id = 'field-control-tooltip';
    tipElement.setAttribute('role', 'tooltip');
    document.body.appendChild(tipElement);
    return tipElement;
}

function quietNativeTitles(host) {
    host.querySelectorAll('.field-label[title]').forEach((label) => {
        label.dataset.fieldTitle = label.getAttribute('title');
        label.removeAttribute('title');
    });
}

function labelText(host) {
    const label = host.querySelector('.field-label');
    return label ? (label.dataset.fieldTitle || label.textContent || '').trim() : '';
}

function place(host) {
    const tip = tooltipElement();
    const anchor = host.querySelector('.field-label') || host;
    const hostRect = host.getBoundingClientRect();
    const anchorRect = anchor.getBoundingClientRect();
    const margin = 6;
    const width = tip.offsetWidth;
    const height = tip.offsetHeight;
    let left = Math.max(margin, Math.min(anchorRect.left, window.innerWidth - width - margin));
    let top = hostRect.bottom + margin;
    if (top + height > window.innerHeight - margin) top = Math.max(margin, hostRect.top - height - margin);
    tip.style.left = `${Math.round(left)}px`;
    tip.style.top = `${Math.round(top)}px`;
}

function show(host) {
    clearTimeout(showTimer);
    const text = host.getAttribute('data-tooltip');
    if (!text || !host.isConnected) return;
    const tip = tooltipElement();
    tip.replaceChildren();
    const label = labelText(host);
    if (label) {
        const heading = document.createElement('div');
        heading.className = 'field-tooltip-label';
        heading.textContent = label;
        tip.appendChild(heading);
    }
    const body = document.createElement('div');
    body.className = 'field-tooltip-text';
    body.textContent = text;
    tip.appendChild(body);
    tip.classList.add('visible');
    shownFor = host;
    place(host);
}

function hide() {
    clearTimeout(showTimer);
    showTimer = null;
    shownFor = null;
    if (tipElement) tipElement.classList.remove('visible');
}

function schedule(host) {
    clearTimeout(showTimer);
    showTimer = setTimeout(() => show(host), SHOW_DELAY_MS);
}

document.addEventListener('pointerover', (event) => {
    const host = hostOf(event.target);
    if (!host) return;
    quietNativeTitles(host);
    if (host === shownFor || host === suppressedFor) return;
    hide();
    schedule(host);
}, true);

document.addEventListener('pointerout', (event) => {
    const host = hostOf(event.target);
    if (!host || host.contains(event.relatedTarget)) return;
    if (host === suppressedFor) suppressedFor = null;
    if (host === shownFor || showTimer) hide();
}, true);

// A press means the person is using the control, not reading about it: hide until they leave it.
document.addEventListener('pointerdown', (event) => {
    const host = hostOf(event.target);
    suppressedFor = host;
    hide();
}, true);

document.addEventListener('focusin', (event) => {
    const host = hostOf(event.target);
    // A text field counts as focus-visible even when clicked; the press already said "using it".
    if (!host || host === suppressedFor || !event.target.matches?.(':focus-visible')) return;
    quietNativeTitles(host);
    show(host);
}, true);

document.addEventListener('focusout', (event) => {
    const host = hostOf(event.target);
    if (host && host === shownFor && !host.contains(event.relatedTarget)) hide();
}, true);

document.addEventListener('keydown', (event) => {
    if (shownFor && event.key !== 'Shift' && event.key !== 'Tab') hide();
}, true);

window.addEventListener('scroll', hide, true);
window.addEventListener('wheel', () => { if (shownFor) hide(); }, { capture: true, passive: true });
window.addEventListener('blur', hide);

// For probes and tests: what the tooltip currently says, or null.
window.fieldControlTooltipState = () => (tipElement && tipElement.classList.contains('visible'))
    ? { text: tipElement.textContent, for: shownFor?.dataset?.controlId || shownFor?.querySelector?.('[id]')?.id || null }
    : null;
window.showFieldControlTooltip = (element) => { const host = hostOf(element); if (host) { quietNativeTitles(host); show(host); } };
