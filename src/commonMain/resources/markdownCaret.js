// Source positions come from the Markdown reader, before highlighting or math changes its DOM.
export function markdownOffsetAtPoint(root, x, y, target) {
    const atomic = target?.closest('img[data-markdown-start],.ai-math[data-markdown-start]');
    if (atomic && root.contains(atomic)) return Number(atomic.dataset.markdownStart);
    let caret;
    if (document.caretPositionFromPoint) {
        const point = document.caretPositionFromPoint(x, y);
        if (point) caret = {node: point.offsetNode, offset: point.offset};
    } else if (document.caretRangeFromPoint) {
        const range = document.caretRangeFromPoint(x, y);
        if (range) caret = {node: range.startContainer, offset: range.startOffset};
    }
    if (caret && root.contains(caret.node)) {
        let element = caret.node.nodeType === Node.TEXT_NODE ? caret.node.parentElement : caret.node;
        let mapped = element.closest('[data-markdown-offsets],[data-markdown-start]');
        if (!mapped) {
            // A block boundary can be represented as an element/child index rather than a text caret.
            const child = element.childNodes[caret.offset] || element.childNodes[caret.offset - 1];
            element = child?.nodeType === Node.TEXT_NODE ? child.parentElement : child;
            mapped = element?.closest?.('[data-markdown-offsets],[data-markdown-start]') ||
                element?.querySelector?.('[data-markdown-offsets],[data-markdown-start]');
            if (mapped) caret = {node: mapped, offset: 0};
        }
        if (mapped && root.contains(mapped)) {
            const range = document.createRange();
            range.selectNodeContents(mapped);
            range.setEnd(caret.node, caret.offset);
            const offset = range.toString().length;
            if (mapped.hasAttribute('data-markdown-offsets')) {
                const offsets = mapped.dataset.markdownOffsets.split(',').map(Number);
                return offsets[Math.min(offset, offsets.length - 1)] + (offset >= offsets.length ? 1 : 0);
            }
            return Number(mapped.dataset.markdownStart) + (mapped.matches('.ai-math,img') ? 0 : offset);
        }
    }
    // Clicking a block's padding still chooses the closest passage, rather than the document end.
    let nearest = null, distance = Infinity;
    for (const mapped of root.querySelectorAll('[data-markdown-offsets],[data-markdown-start]')) {
        for (const rect of mapped.getClientRects()) {
            const d = Math.hypot(Math.max(rect.left - x, 0, x - rect.right), Math.max(rect.top - y, 0, y - rect.bottom));
            if (d < distance) { distance = d; nearest = mapped; }
        }
    }
    if (!nearest) return 0;
    if (nearest.hasAttribute('data-markdown-start')) return Number(nearest.dataset.markdownStart);
    const offsets = nearest.dataset.markdownOffsets.split(',').map(Number);
    return y > nearest.getBoundingClientRect().bottom ? offsets.at(-1) + 1 : offsets[0];
}

export function revealTextareaCaret(editor) {
    // setSelectionRange alone does not reliably scroll an already-focused textarea in Chromium.
    // Measure in unscaled CSS pixels, so this also works in zoomed or split canvas panes.
    const mirror = document.createElement('div');
    const style = getComputedStyle(editor);
    for (const property of ['fontFamily', 'fontSize', 'fontWeight', 'fontStyle', 'fontStretch', 'fontFeatureSettings',
        'lineHeight', 'letterSpacing', 'textIndent', 'tabSize', 'paddingTop', 'paddingRight', 'paddingBottom',
        'paddingLeft', 'wordBreak', 'overflowWrap', 'direction', 'textAlign']) mirror.style[property] = style[property];
    Object.assign(mirror.style, {position: 'fixed', left: '-100000px', top: '0', visibility: 'hidden',
        pointerEvents: 'none', boxSizing: 'border-box', width: `${editor.clientWidth}px`, whiteSpace: 'pre-wrap'});
    const caret = editor.selectionDirection === 'backward' ? editor.selectionStart : editor.selectionEnd;
    mirror.textContent = editor.value.slice(0, caret);
    const marker = document.createElement('span');
    marker.textContent = editor.value[caret] || '\u200b';
    mirror.append(marker); document.body.append(mirror);
    const top = marker.offsetTop, height = marker.offsetHeight || parseFloat(style.lineHeight) || 21;
    if (top < editor.scrollTop || top + height > editor.scrollTop + editor.clientHeight) {
        editor.scrollTop = Math.max(0, top - (editor.clientHeight - height) / 2);
    }
    mirror.remove();
}
