import {clearReplyHandler, sendWithReply} from './afield.mjs';
import {domRangeForMarkdown} from './markdownTextRuns.js';

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

function diffParts({original, replacement}) {
    const before = Array.from(original), after = Array.from(replacement);
    let prefix = 0, suffix = 0;
    const length = Math.min(before.length, after.length);
    while (prefix < length && before[prefix] === after[prefix]) prefix++;
    while (suffix < length - prefix && before.at(-1 - suffix) === after.at(-1 - suffix)) suffix++;
    return {
        prefix: before.slice(0, prefix).join(''),
        removed: before.slice(prefix, before.length - suffix).join(''),
        added: after.slice(prefix, after.length - suffix).join(''),
        suffix: suffix ? before.slice(-suffix).join('') : '',
    };
}

function diffPreview(change) {
    const {prefix, removed, added, suffix} = diffParts(change);
    const result = element('p', '', 'text-frame-review-diff');
    result.append(prefix);
    for (const [tag, text] of [['del', removed], ['ins', added]]) {
        if (!text) continue;
        const part = element(tag, text);
        if (pending(change) && change.start != null) {
            part.tabIndex = 0;
            part.dataset.suggestion = change.id;
            part.dataset.control = tag;
        }
        result.append(part);
    }
    result.append(suffix);
    return result;
}

const reviewWidth = 540;
const actionIcons = {
    Accept: '<path d="m3 8 3 3 7-7"/>',
    Reject: '<path d="m4 4 8 8m0-8-8 8"/>',
    Comment: '<path d="M3 3h10v7H7l-4 3z"/>',
};

function iconButton(label, icon, action) {
    const result = button('', action);
    result.className = 'text-frame-review-icon';
    result.title = label;
    result.setAttribute('aria-label', label);
    result.innerHTML = '<svg viewBox="0 0 16 16" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.35" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' + actionIcons[icon] + '</svg>';
    return result;
}

function contextExcerpt(text, start, end, mode) {
    const preceding = text.slice(0, start);
    const paragraphStart = preceding.lastIndexOf('\n\n');
    const following = text.indexOf('\n\n', end);
    const paragraphFrom = paragraphStart < 0 ? 0 : paragraphStart + 2;
    const paragraphTo = following < 0 ? text.length : following;
    const startLimit = Math.max(paragraphFrom, start - 112);
    const endLimit = Math.min(paragraphTo, end + 112);
    const clip = text.slice(start, Math.min(end, start + 100)) + (end - start > 100 ? '…' : '');
    const marker = element('mark', clip, mode === 'insert' ? 'text-frame-review-new-context' : '');
    const before = (startLimit > paragraphFrom ? '…' : '') + text.slice(startLimit, start);
    const after = text.slice(end, endLimit) + (endLimit < paragraphTo ? '…' : '');
    return [before, marker, after];
}

const pending = change => !change.status || change.status === 'pending';

export function mountTextFrameReview(box, prepare, source) {
    let changes = [], seen, dialog, list, error, acceptAll, rejectAll, context, busy = false;
    let hovered, focusedAt, preferFocus = false;
    let revealTimer, savedScroll;
    const sourceHighlight = 'field-text-frame-review';
    const insertedHighlight = 'field-text-frame-review-insert';
    function clearHighlight() {
        window.CSS?.highlights?.delete(sourceHighlight);
        window.CSS?.highlights?.delete(insertedHighlight);
    }
    const drafts = new Map();
    const badge = button('Review edits', open);
    badge.className = 'text-frame-review-badge';
    badge.hidden = true;
    for (const event of ['pointerdown', 'mousedown', 'dblclick', 'contextmenu']) {
        badge.addEventListener(event, e => e.stopPropagation());
    }

    function restoreScroll() {
        clearTimeout(revealTimer);
        if (savedScroll) savedScroll.root.scrollTop = savedScroll.top;
        savedScroll = null;
    }

    function revealRange(root, range, original) {
        clearTimeout(revealTimer);
        revealTimer = setTimeout(() => {
            if (!dialog?.open || !root.isConnected) return;
            const line = () => Array.from(range.getClientRects()).find(rect => rect.width > 0 && rect.height > 0);
            const rect = line();
            if (!rect) return;
            const frame = root.getBoundingClientRect();
            const scale = frame.height / root.offsetHeight;
            if (scale > 0 && (rect.top < frame.top || rect.bottom > frame.bottom)) {
                if (root === original) savedScroll ||= {root, top: root.scrollTop};
                root.scrollTop += (rect.top - frame.top - frame.height * .24) / scale;
            }
            const target = line();
            if (target) window.revealTextReviewPassage?.(box, target, dialog);
        }, 100);
    }

    function updateContext() {
        if (!dialog?.open) return;
        clearTimeout(revealTimer);
        const active = preferFocus ? focusedAt || hovered : hovered || focusedAt;
        const change = changes.find(entry => entry.id === active?.id && pending(entry));
        const {text, root} = source.read();
        const start = change?.start;
        const usable = start != null && text.startsWith(change.original, start);
        for (const card of list.children) card.classList.toggle('is-active', usable && card.dataset.reviewId === change.id);
        clearHighlight();
        if (!usable) {
            restoreScroll();
            source.preview(null);
            context.textContent = 'Hover or focus a change to see it in context.';
            return;
        }
        const parts = diffParts(change);
        const insertion = active.mode === 'insert' && parts.added.length > 0;
        const end = start + change.original.length;
        const markdown = insertion ? text.slice(0, start) + change.replacement + text.slice(end) : text;
        const changed = insertion ? parts.added : parts.removed;
        const from = changed ? start + parts.prefix.length : start;
        const to = changed ? from + changed.length : end;
        const preview = source.preview(insertion ? markdown : null);
        const display = insertion ? preview : root;
        const range = display && domRangeForMarkdown(display, from, to);
        if (range) {
            window.CSS?.highlights?.set(insertion ? insertedHighlight : sourceHighlight, new Highlight(range));
            revealRange(display, range, root);
        }
        context.replaceChildren(...contextExcerpt(markdown, from, to, insertion ? 'insert' : 'remove'));
    }

    function targetAt(node) {
        const card = node?.closest?.('[data-review-id]');
        if (!card || !list.contains(card)) return null;
        return {id: card.dataset.reviewId, mode: node.closest('ins') ? 'insert' : 'source'};
    }

    function position() {
        if (!dialog?.open) return;
        dialog.style.maxHeight = '';
        const sourceBox = box.getBoundingClientRect();
        const gutter = 18;
        const left = Math.min(sourceBox.left, window.innerWidth);
        const right = Math.max(sourceBox.right, 0);
        const leftRoom = left - 2 * gutter;
        const rightRoom = window.innerWidth - right - 2 * gutter;
        const dockRight = rightRoom >= reviewWidth || rightRoom >= leftRoom;
        const room = dockRight ? rightRoom : leftRoom;
        if (room >= 360) {
            const width = Math.min(reviewWidth, room);
            dialog.style.width = width + 'px';
            dialog.style.left = (dockRight ? right + gutter : left - gutter - width) + 'px';
            const top = Math.max(gutter, Math.min(sourceBox.top, window.innerHeight - dialog.offsetHeight - gutter));
            dialog.style.top = top + 'px';
            dialog.style.bottom = 'auto';
            dialog.style.maxHeight = `min(78vh, 780px, ${window.innerHeight - top - gutter}px)`;
        } else {
            dialog.style.width = '';
            dialog.style.left = Math.max(gutter, (window.innerWidth - dialog.offsetWidth) / 2) + 'px';
            dialog.style.top = 'auto';
            dialog.style.bottom = gutter + 'px';
        }
    }

    function updateBulkControls() {
        const remaining = changes.filter(pending);
        const hidden = window.currentDocumentReadOnly || !remaining.length;
        acceptAll.hidden = rejectAll.hidden = hidden;
        acceptAll.disabled = busy || !remaining.some(change => change.start != null);
        rejectAll.disabled = busy || !remaining.length;
    }

    async function submit(action, id = '', comment = '') {
        if (busy) return;
        busy = true;
        error.textContent = '';
        list.inert = true;
        updateBulkControls();
        let result;
        try {
            if ((action === 'accept' || action === 'accept-all') && !prepare()) {
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
            updateBulkControls();
        }
    }

    function render() {
        if (!dialog?.open) return;
        const scrollTop = list.scrollTop;
        const focused = list.contains(document.activeElement) ? document.activeElement : null;
        const suggestion = focused?.dataset.suggestion;
        const control = focused?.dataset.control;
        const selection = focused?.tagName === 'TEXTAREA' ? [focused.selectionStart, focused.selectionEnd] : null;
        const readOnly = window.currentDocumentReadOnly;
        const remaining = changes.filter(pending);
        updateBulkControls();
        const cards = [...remaining, ...changes.filter(change => !pending(change)).reverse()].map(change => {
            const card = element('section', '', 'text-frame-review-change');
            card.dataset.reviewId = change.id;
            const title = element('div', '', 'text-frame-review-card-heading');
            title.append(diffPreview(change));
            card.append(title);
            const state = pending(change) ? (change.start == null ? 'Source changed' : '') :
                ({accepted: 'Accepted', rejected: 'Rejected', superseded: 'Superseded'}[change.status] || '');
            if (state) card.append(element('div', state, 'text-frame-review-state'));
            if (change.reason) card.append(element('p', change.reason, 'text-frame-review-reason'));
            for (const comment of change.comments || []) {
                card.append(element('p', comment, 'text-frame-review-comment'));
            }
            if (readOnly) return card;
            const actions = element('div', '', 'text-frame-review-actions');
            function act(label, callback) {
                const result = iconButton(label, label, callback);
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
            title.append(actions);
            card.append(form);
            return card;
        });
        list.replaceChildren(...cards);
        list.scrollTop = scrollTop;
        if (suggestion) {
            const replacement = Array.from(list.querySelectorAll('[data-control]')).find(entry =>
                entry.dataset.suggestion === suggestion && entry.dataset.control === control && !entry.disabled);
            if (replacement) {
                replacement.focus({preventScroll: true});
                if (selection) replacement.setSelectionRange(...selection);
            }
        }
        updateContext();
    }

    function open() {
        if (dialog?.open) return;
        source.open();
        dialog = element('dialog', '', 'text-frame-review');
        const title = element('h2', 'Review edits');
        if (box.name) title.append(element('span', box.name));
        title.tabIndex = -1;
        title.autofocus = true;
        dialog.setAttribute('aria-label', box.name ? 'Review edits for ' + box.name : 'Review edits');
        const controls = element('div', '', 'text-frame-review-actions');
        acceptAll = button('Accept all', () => submit('accept-all'));
        acceptAll.title = 'Accept all suggestions that still match the source';
        rejectAll = button('Reject all', () => submit('reject-all'));
        const close = iconButton('Close', 'Reject', () => { if (!busy) dialog.close(); });
        controls.append(acceptAll, rejectAll, close);
        const header = element('header');
        header.append(title, controls);
        error = element('div', '', 'text-frame-review-error');
        error.setAttribute('role', 'status');
        list = element('div', '', 'text-frame-review-list');
        context = element('div', '', 'text-frame-review-context');
        function pointAt(node) {
            const next = targetAt(node);
            if (!preferFocus && next?.id === hovered?.id && next?.mode === hovered?.mode) return;
            hovered = next;
            preferFocus = false;
            updateContext();
        }
        list.addEventListener('pointerover', event => pointAt(event.target));
        list.addEventListener('pointerout', event => pointAt(event.relatedTarget));
        list.addEventListener('focusin', event => { focusedAt = targetAt(event.target); preferFocus = true; updateContext(); });
        list.addEventListener('focusout', event => { focusedAt = targetAt(event.relatedTarget); updateContext(); });
        dialog.append(header, error, list, context);
        for (const event of ['keydown', 'keyup', 'pointerdown', 'mousedown', 'dblclick', 'wheel']) {
            dialog.addEventListener(event, e => e.stopPropagation());
        }
        dialog.addEventListener('cancel', event => { if (busy) event.preventDefault(); });
        const opened = dialog;
        dialog.addEventListener('close', () => {
            opened.remove();
            if (dialog !== opened) return;
            window.removeEventListener('resize', position);
            clearHighlight();
            restoreScroll();
            source.preview(null);
            hovered = focusedAt = null;
            preferFocus = false;
            dialog = undefined;
        });
        document.body.append(dialog);
        dialog.showModal();
        window.addEventListener('resize', position);
        render();
        position();
    }

    return {
        badge,
        refreshSource: updateContext,
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
