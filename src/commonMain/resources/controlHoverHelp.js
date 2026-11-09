// Shared by the Field page and the phone controller view.
function refreshControlHoverHelp(root) {
    const labels = Array.from(root.querySelectorAll('[data-hover-text]'));
    if (root.matches?.('[data-hover-text]')) labels.unshift(root);
    labels.forEach((label) => {
        const control = label.closest('.field-control, .phone-control');
        const target = control?.matches('[tabindex]') ? control
            : control?.querySelector('input:not([type="hidden"]), select, button, textarea, [tabindex]');
        const accessible = target || label;
        if (!target) label.tabIndex = 0;
        accessible.setAttribute('aria-description', safeControlHoverMarkdown(label.dataset.hoverHtml).textContent);
    });
}

// FieldMarkdown renders the same GFM as output cards. Copy only formatting elements and safe links
// into the live page: a control's help cannot install scripts, event handlers, or remote images.
function safeControlHoverMarkdown(html) {
    const template = document.createElement('template');
    template.innerHTML = html || '';
    const allowed = new Set(['P', 'BR', 'EM', 'I', 'STRONG', 'B', 'S', 'DEL', 'CODE', 'PRE',
        'UL', 'OL', 'LI', 'A', 'BLOCKQUOTE', 'H1', 'H2', 'H3', 'H4', 'H5', 'H6', 'SPAN']);
    const blocked = new Set(['SCRIPT', 'STYLE', 'IFRAME', 'OBJECT', 'EMBED', 'SVG', 'MATH', 'IMG']);
    const fragment = document.createDocumentFragment();
    const copy = (node, parent) => {
        if (node.nodeType === 3) { parent.appendChild(document.createTextNode(node.nodeValue)); return; }
        if (node.nodeType !== 1 || blocked.has(node.tagName)) return;
        let element = allowed.has(node.tagName) ? document.createElement(node.tagName.toLowerCase()) : parent;
        if (node.tagName === 'A') {
            let safe = false;
            try {
                const href = node.getAttribute('href') || '';
                const url = new URL(href, window.location.href);
                if (href && href !== '#' && ['http:', 'https:', 'mailto:', 'field:', 'field-docs:'].includes(url.protocol)) {
                    element.setAttribute('href', href);
                    element.setAttribute('target', '_blank');
                    element.setAttribute('rel', 'noopener noreferrer');
                    safe = true;
                }
            } catch (_) { /* An invalid link remains readable text. */ }
            if (!safe) element = parent;
        }
        for (const child of node.childNodes) copy(child, element);
        if (element !== parent) parent.appendChild(element);
    };
    for (const child of template.content.childNodes) copy(child, fragment);
    return fragment;
}

// A real DOM tooltip stays visible in the browser texture used by Field in VR. It is kept at the
// document root so a scrolling output disclosure cannot clip it.
function addControlHoverHelp() {
    let popup = null;
    let pending = null;
    let closing = null;
    let anchor = null;
    const hide = () => {
        clearTimeout(pending);
        clearTimeout(closing);
        pending = null;
        closing = null;
        anchor = null;
        if (popup) popup.hidden = true;
    };
    const scheduleHide = () => {
        clearTimeout(closing);
        closing = setTimeout(hide, 220);
    };
    const show = (label) => {
        if (!label?.dataset.hoverText) return;
        clearTimeout(closing);
        closing = null;
        if (anchor === label) return;
        hide();
        anchor = label;
        pending = setTimeout(() => {
            if (anchor !== label || !label.isConnected) return;
            if (!popup) {
                popup = document.createElement('div');
                popup.className = 'field-control-tooltip';
                popup.setAttribute('role', 'note');
                popup.addEventListener('pointerover', () => clearTimeout(closing));
                popup.addEventListener('pointerout', (event) => {
                    if (!popup.contains(event.relatedTarget) && !anchor?.contains(event.relatedTarget)) scheduleHide();
                });
                document.body.appendChild(popup);
            }
            const name = document.createElement('div');
            name.className = 'field-control-tooltip-label';
            name.textContent = label.textContent;
            const help = document.createElement('div');
            help.className = 'field-control-tooltip-markdown';
            help.appendChild(safeControlHoverMarkdown(label.dataset.hoverHtml));
            popup.replaceChildren(name, help);
            popup.hidden = false;
            popup.style.left = '0px';
            popup.style.top = '0px';
            const r = label.getBoundingClientRect();
            const tip = popup.getBoundingClientRect();
            const left = Math.max(8, Math.min(r.left, window.innerWidth - tip.width - 8));
            const below = r.bottom + 8;
            const top = below + tip.height <= window.innerHeight - 8
                ? below : Math.max(8, r.top - tip.height - 8);
            popup.style.left = `${left}px`;
            popup.style.top = `${top}px`;
        }, 400);
    };
    document.addEventListener('pointerover', (event) => {
        const label = event.target?.closest?.('[data-hover-text]');
        if (label) { clearTimeout(closing); show(label); }
    });
    document.addEventListener('pointerout', (event) => {
        if (anchor && (event.target === anchor || anchor.contains(event.target)) &&
            !anchor.contains(event.relatedTarget) && !popup?.contains(event.relatedTarget)) scheduleHide();
    });
    document.addEventListener('focusin', (event) => {
        const label = event.target.matches('[data-hover-text]') ? event.target
            : event.target.closest?.('.field-control, .phone-control')?.querySelector('[data-hover-text]');
        if (label) show(label);
    });
    document.addEventListener('focusout', (event) => {
        if (!popup?.contains(event.relatedTarget)) scheduleHide();
    });
    document.addEventListener('pointerdown', (event) => { if (!popup?.contains(event.target)) hide(); }, true);
    document.addEventListener('keydown', (event) => { if (event.key === 'Escape') hide(); });
    window.addEventListener('scroll', (event) => { if (!popup?.contains(event.target)) hide(); }, true);
    window.addEventListener('resize', hide);
    window.addEventListener('blur', hide);
}

function initializeControlHoverHelp() {
    refreshControlHoverHelp(document);
    addControlHoverHelp();
    if (window.MutationObserver) {
        new MutationObserver((mutations) => mutations.forEach((mutation) =>
            mutation.addedNodes.forEach((node) => { if (node.nodeType === 1) refreshControlHoverHelp(node); })
        )).observe(document.body, { childList: true, subtree: true });
    }
}
if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', initializeControlHoverHelp);
else initializeControlHoverHelp();
