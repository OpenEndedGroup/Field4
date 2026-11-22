// Positions are UTF-16 offsets in normalized Markdown, not offsets into the rendered DOM.
export function markdownCharacters(root) {
    const result = [];
    for (const el of root.querySelectorAll('[data-markdown-offsets],code[data-markdown-start]')) {
        // Math typesetting and syntax highlighting may replace the text nodes, so walk the result.
        const offsets = el.dataset.markdownOffsets?.split(',').map(Number);
        const start = Number(el.dataset.markdownStart);
        const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT);
        let index = 0;
        for (let node = walker.nextNode(); node; node = walker.nextNode()) {
            for (let i = 0; i < node.length; i++, index++) {
                const source = offsets ? offsets[index] : start + index;
                if (Number.isFinite(source)) result.push({node, offset: i, source, char: node.data[i]});
            }
        }
    }
    return result;
}

export function markdownRangeFromDOM(root, range) {
    if (!range || range.collapsed || !root.contains(range.startContainer) || !root.contains(range.endContainer)) return null;
    let start = Infinity, end = -1;
    for (const {node, offset, source} of markdownCharacters(root)) {
        if (range.comparePoint(node, offset) < 0 || range.comparePoint(node, offset + 1) > 0) continue;
        start = Math.min(start, source);
        end = Math.max(end, source + 1);
    }
    return start < end ? {start, end} : null;
}

export function domRangeForMarkdown(root, start, end) {
    let first, last;
    for (const item of markdownCharacters(root)) if (start <= item.source && item.source < end) {
        first ||= item;
        last = item;
    }
    if (!first) return null;
    const range = document.createRange();
    range.setStart(first.node, first.offset);
    range.setEnd(last.node, last.offset + 1);
    return range;
}
