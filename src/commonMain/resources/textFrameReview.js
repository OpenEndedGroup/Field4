import {clearReplyHandler, sendWithReply} from './afield.mjs';

function element(tag, text = '', className = '') {
    const result = document.createElement(tag);
    result.className = className;
    result.textContent = text;
    return result;
}

function button(label, action) {
    const result = element('button', label);
    result.type = 'button';
    result.addEventListener('click', action);
    return result;
}

let nextReply = 0;

function review(uid, action, id, comment) {
    return new Promise(resolve => {
        const key = 'text-frame-review-' + ++nextReply;
        const timer = setTimeout(() => {
            clearReplyHandler(key);
            resolve({ok: false, error: 'Field did not reply. Check the review before trying again.'});
        }, 10_000);
        sendWithReply('text-frame-review', {uid, action, id, comment}, key, (_message, response) => {
            clearTimeout(timer);
            resolve(response);
            return 'remove';
        });
    });
}

function diffPreview({original, replacement}) {
    const before = Array.from(original), after = Array.from(replacement);
    let prefix = 0, suffix = 0;
    const length = Math.min(before.length, after.length);
    while (prefix < length && before[prefix] === after[prefix]) prefix++;
    while (suffix < length - prefix && before.at(-1 - suffix) === after.at(-1 - suffix)) suffix++;
    const result = element('p', '', 'text-frame-review-diff');
    result.append(before.slice(0, prefix).join(''));
    if (prefix + suffix < before.length) result.append(element('del', before.slice(prefix, before.length - suffix).join('')));
    if (prefix + suffix < after.length) result.append(element('ins', after.slice(prefix, after.length - suffix).join('')));
    if (suffix) result.append(before.slice(-suffix).join(''));
    return result;
}

const pending = change => !change.status || change.status === 'pending';

export function mountTextFrameReview(box, prepare) {
    let changes = [], seen, dialog, list, error, rejectAll, busy = false;
    const drafts = new Map();
    const badge = button('Review edits', open);
    badge.className = 'text-frame-review-badge';
    badge.hidden = true;
    for (const event of ['pointerdown', 'mousedown', 'dblclick', 'contextmenu']) {
        badge.addEventListener(event, e => e.stopPropagation());
    }

    async function submit(action, id = '', comment = '') {
        if (busy) return;
        busy = true;
        error.textContent = '';
        list.inert = rejectAll.disabled = true;
        let result;
        try {
            if (action === 'accept' && !prepare()) {
                result = {ok: false, error: 'Resolve the draft conflict on the frame before accepting an edit.'};
            } else result = await review(box.uid, action, id, comment);
            if (result?.ok && action === 'comment') {
                drafts.delete(id);
                const input = Array.from(list.querySelectorAll('textarea')).find(entry => entry.dataset.suggestion === id);
                if (input) input.value = '';
            }
            if (!result?.ok) error.textContent = result?.error || 'Could not update the review.';
        } catch {
            error.textContent = 'Could not connect to Field.';
        } finally {
            busy = false;
            list.inert = false;
            rejectAll.disabled = !changes.some(pending);
        }
    }

    function render() {
        if (!dialog?.open) return;
        const focused = list.contains(document.activeElement) ? document.activeElement : null;
        const suggestion = focused?.dataset.suggestion;
        const control = focused?.dataset.control;
        const selection = focused?.tagName === 'TEXTAREA' ? [focused.selectionStart, focused.selectionEnd] : null;
        const readOnly = window.currentDocumentReadOnly;
        const remaining = changes.filter(pending);
        rejectAll.hidden = readOnly || !remaining.length;
        rejectAll.disabled = busy;
        const cards = [...remaining, ...changes.filter(change => !pending(change)).reverse()].map(change => {
            const card = element('section', '', 'text-frame-review-change');
            const state = pending(change) ? (change.start == null ? 'Source changed' : '') :
                ({accepted: 'Accepted', rejected: 'Rejected', superseded: 'Superseded'}[change.status] || '');
            if (state) card.append(element('div', state, 'text-frame-review-state'));
            if (change.reason) card.append(element('p', change.reason, 'text-frame-review-reason'));
            card.append(diffPreview(change));
            for (const comment of change.comments || []) {
                card.append(element('p', comment, 'text-frame-review-comment'));
            }
            if (readOnly) return card;
            const actions = element('div', '', 'text-frame-review-actions');
            function act(label, callback) {
                const result = button(label, callback);
                result.dataset.suggestion = change.id;
                result.dataset.control = label;
                return result;
            }
            const form = element('form', '', 'text-frame-review-comment-form');
            form.hidden = !drafts.has(change.id);
            const comment = element('textarea');
            comment.rows = 2;
            comment.maxLength = 4000;
            comment.placeholder = 'Comment for AI4…';
            comment.setAttribute('aria-label', 'Comment on this suggestion');
            comment.dataset.suggestion = change.id;
            comment.dataset.control = 'draft';
            comment.value = drafts.get(change.id) || '';
            comment.addEventListener('input', () => drafts.set(change.id, comment.value));
            const save = element('button', 'Save comment');
            save.type = 'submit';
            form.append(comment, save);
            form.addEventListener('submit', event => {
                event.preventDefault();
                if (comment.value.trim()) submit('comment', change.id, comment.value);
            });
            if (pending(change)) {
                const accept = act('Accept', () => submit('accept', change.id));
                accept.disabled = change.start == null;
                actions.append(accept, act('Reject', () => submit('reject', change.id)));
            }
            actions.append(act('Comment', () => {
                form.hidden = false;
                drafts.set(change.id, comment.value);
                comment.focus();
            }));
            card.append(actions, form);
            return card;
        });
        list.replaceChildren(...cards);
        if (suggestion) {
            const replacement = Array.from(list.querySelectorAll('[data-control]')).find(entry =>
                entry.dataset.suggestion === suggestion && entry.dataset.control === control && !entry.disabled);
            if (replacement) {
                replacement.focus({preventScroll: true});
                if (selection) replacement.setSelectionRange(...selection);
            }
        }
    }

    function open() {
        if (dialog?.open) return;
        dialog = element('dialog', '', 'text-frame-review');
        const title = element('h2', box.name ? 'Review edits · ' + box.name : 'Review edits');
        dialog.setAttribute('aria-label', title.textContent);
        const controls = element('div', '', 'text-frame-review-actions');
        rejectAll = button('Reject all', () => submit('reject-all'));
        const close = button('Close', () => { if (!busy) dialog.close(); });
        close.autofocus = true;
        controls.append(rejectAll, close);
        const header = element('header');
        header.append(title, controls);
        error = element('div', '', 'text-frame-review-error');
        error.setAttribute('role', 'status');
        list = element('div', '', 'text-frame-review-list');
        dialog.append(header, error, list);
        for (const event of ['keydown', 'keyup', 'pointerdown', 'mousedown', 'dblclick', 'wheel']) {
            dialog.addEventListener(event, e => e.stopPropagation());
        }
        dialog.addEventListener('cancel', event => { if (busy) event.preventDefault(); });
        const opened = dialog;
        dialog.addEventListener('close', () => {
            opened.remove();
            if (dialog === opened) dialog = undefined;
        });
        document.body.append(dialog);
        dialog.showModal();
        render();
    }

    return {
        badge,
        sync() {
            if (seen === box.textFrameReview) return;
            seen = box.textFrameReview;
            try { changes = JSON.parse(seen) || []; } catch { changes = []; }
            if (!Array.isArray(changes)) changes = [];
            const count = changes.filter(pending).length;
            badge.hidden = !changes.length;
            badge.textContent = count ? `Review edits (${count})` : 'Review history';
            render();
        },
        dispose() {
            badge.remove();
            if (dialog?.open) dialog.close();
        },
    };
}
