import {textFrameMarkdownToHtml} from './afield.mjs';
import {markdownCharacters} from './markdownTextRuns.js';

const proseSelector = 'p,h1,h2,h3,h4,h5,h6';
const imageSource = /^!\[([^\]]*)\]\(([^)]+)\)/;
const inlineTags = 'span,b,strong,i,em,br,img';
const passageLink = 'a[href^="field://passage/"]';
const children = (node, render) => [...node.childNodes].map(render).join('');

function inlineMarkdown(node, marks = '') {
    if (node.nodeType === Node.TEXT_NODE) {
        if (!marks) return node.data;
        // Keep the delimiters adjacent to the marked text, even when the selection includes spaces.
        return node.data.replace(/\S(?:.*\S)?/g, value => marks + value + marks);
    }
    if (node.nodeType !== Node.ELEMENT_NODE) return '';
    if (node.matches('img,' + passageLink)) return node.dataset.richMarkdown || '';
    if (node.matches('br')) return ' ';
    const bold = marks.length >= 2 || node.matches('b,strong') || /^(bold|[6-9]00)$/.test(node.style.fontWeight);
    const italic = marks.length % 2 === 1 || node.matches('i,em') || node.style.fontStyle === 'italic';
    return children(node, child => inlineMarkdown(child, '*'.repeat((bold ? 2 : 0) + (italic ? 1 : 0))));
}

function blockMarkdown(host) {
    const blocks = [];
    let pending = '';
    const flush = () => { if (pending.trim()) blocks.push(pending.trim()); pending = ''; };
    const visit = node => {
        if (node.nodeType === Node.ELEMENT_NODE && node.matches(proseSelector + ',div')) {
            flush();
            const heading = /^H[1-6]$/.test(node.tagName) ? Number(node.tagName[1]) : 0;
            if ([...node.children].some(child => child.matches(proseSelector + ',div'))) {
                [...node.childNodes].forEach(visit);
                flush();
            } else {
                const value = children(node, child => inlineMarkdown(child)).trim();
                if (value) blocks.push((heading ? '#'.repeat(heading) + ' ' : '') + value);
            }
        } else pending += inlineMarkdown(node);
    };
    [...host.childNodes].forEach(visit);
    flush();
    return blocks.join('\n\n');
}

// The reader already maps visible text back to source. Use that mapping to make independent
// editing hosts for simple blocks. Everything between those ranges stays byte-for-byte intact.
function editableRange(block, source) {
    if (!block.matches(proseSelector) || [...block.querySelectorAll('*')].some(el => !el.matches(inlineTags + ',' + passageLink))) return null;
    for (const link of block.querySelectorAll(passageLink)) {
        link.dataset.richMarkdown = `[Source passage](${link.getAttribute('href')})`;
    }
    let first = Infinity, last = -1;
    for (const el of block.querySelectorAll('[data-markdown-offsets],img[data-markdown-start]')) {
        if (el.matches('img')) {
            const start = Number(el.dataset.markdownStart);
            const match = imageSource.exec(source.slice(start));
            if (!match) return null;
            el.dataset.richMarkdown = match[0];
            first = Math.min(first, start);
            last = Math.max(last, start + match[0].length - 1);
        } else {
            const offsets = el.dataset.markdownOffsets.split(',').map(Number);
            first = Math.min(first, offsets[0]);
            last = Math.max(last, offsets.at(-1));
        }
    }
    if (!Number.isFinite(first) || last < first) return null;
    const start = source.lastIndexOf('\n', first - 1) + 1;
    const nextLine = source.indexOf('\n', last + 1);
    const end = nextLine < 0 ? source.length : nextLine;
    // A reader block can share a source line with a fence or unrendered syntax. Leave it alone
    // unless serializing the visible DOM accounts for the entire original range.
    const canonical = children(block, child => inlineMarkdown(child));
    const heading = block.matches('p') ? '' : '#'.repeat(Number(block.tagName[1])) + ' ';
    const original = source.slice(start, end);
    if (original.trim().replace(/\n[ \t]*/g, ' ') !== heading + canonical) return null;
    return {start, end};
}

export function mountRichText(root, markdown) {
    const source = markdown.replace(/\r\n?/g, '\n');
    if (!source.trim()) root.innerHTML = '<div class="ai-chat-markdown"><p><br></p></div>';
    const reader = root.querySelector('.ai-chat-markdown');
    const blocks = [];
    for (const block of [...reader.children]) {
        const range = source.trim() ? editableRange(block, source) : {start: 0, end: source.length};
        if (!range) continue;
        const host = document.createElement('div');
        host.className = 'text-frame-rich-block';
        host.contentEditable = 'true';
        host.spellcheck = true;
        host.setAttribute('aria-label', 'Frame text');
        host.setAttribute('role', 'textbox');
        host.setAttribute('aria-multiline', 'true');
        block.replaceWith(host);
        host.append(block);
        for (const link of host.querySelectorAll(passageLink)) link.contentEditable = 'false';
        for (const span of host.querySelectorAll('span[data-markdown-offsets]')) span.replaceWith(...span.childNodes);
        blocks.push({...range, host, baseline: blockMarkdown(host)});
    }
    if (!blocks.length) {
        const host = document.createElement('div');
        host.className = 'text-frame-rich-block';
        host.contentEditable = 'true';
        host.spellcheck = true;
        host.setAttribute('aria-label', 'Add frame text');
        host.innerHTML = '<p><br></p>';
        reader.append(host);
        blocks.push({start: source.length, end: source.length, host, baseline: '', prefix: '\n\n'});
    }
    function serialize() {
        let result = '', cursor = 0, changed = false;
        const regions = [];
        for (const {start, end, host, baseline, prefix = ''} of blocks) {
            const value = blockMarkdown(host);
            result += source.slice(cursor, start);
            const next = value === baseline ? source.slice(start, end) : value;
            if (value !== baseline) { result += prefix; changed = true; }
            regions.push({host, start: result.length, raw: next});
            result += next;
            cursor = end;
        }
        return {text: changed ? result + source.slice(cursor) : source, regions};
    }
    const prose = value => value.replace(/\s/g, '');
    const plainProse = root => {
        root.querySelectorAll(passageLink).forEach(link => link.remove());
        return prose(root.textContent);
    };
    const prosePositions = raw => {
        const detached = document.createElement('div');
        detached.innerHTML = textFrameMarkdownToHtml(raw);
        return markdownCharacters(detached).filter(item => !/\s/.test(item.char) && !item.node.parentElement.closest(passageLink));
    };
    return {
        first: blocks[0].host,
        markdown: () => serialize().text,
        sourceRange(range, allowCaret = false) {
            if (range.collapsed && !allowCaret) return null;
            const {regions} = serialize();
            const map = (node, offset, end) => {
                const region = regions.find(({host}) => host.contains(node));
                if (!region) return null;
                // Compare prose characters rather than DOM structure: the browser may split
                // text nodes, and the Markdown renderer can collapse line breaks and spaces.
                const before = document.createRange();
                before.selectNodeContents(region.host);
                before.setEnd(node, offset);
                const prefix = before.toString();
                const index = plainProse(before.cloneContents()).length;
                const chars = prosePositions(region.raw);
                if (chars.map(item => item.char).join('') !== plainProse(region.host.cloneNode(true))) return null;
                if (range.collapsed) {
                    const link = (node.nodeType === Node.TEXT_NODE ? node.parentElement : node).closest?.(passageLink);
                    if (link) return null;
                    if (!chars.length) {
                        if (!region.raw.trim() && !source.trim()) return region.start + region.raw.length;
                        if (region.host.querySelector(passageLink)) return region.start + region.raw.length;
                        return null;
                    }
                    const left = chars[index - 1]?.source;
                    const right = chars[index]?.source;
                    if (left == null) return region.start + right;
                    if (right == null) return region.start + left + 1;
                    const between = region.raw.slice(left + 1, right);
                    const typedSpace = prefix.match(/\s*$/)[0].length;
                    return region.start + (/^\s*$/.test(between) ? Math.min(left + 1 + typedSpace, right)
                        : typedSpace ? right : left + 1);
                }
                const item = chars[index - (end ? 1 : 0)];
                return item ? region.start + item.source + (end ? 1 : 0) : null;
            };
            const start = map(range.startContainer, range.startOffset, false);
            const end = range.collapsed ? start : map(range.endContainer, range.endOffset, true);
            return start != null && end != null && (allowCaret ? start <= end : start < end) ? {start, end} : null;
        },
        placeAtSourceOffset(offset) {
            const {regions} = serialize();
            const region = regions.find(({start, raw}) => start <= offset && offset <= start + raw.length) ||
                regions.find(({start}) => start > offset) || regions.at(-1);
            const {host, start, raw} = region;
            let remaining = prosePositions(raw).filter(item => item.source < offset - start).length;
            const walker = document.createTreeWalker(host, NodeFilter.SHOW_TEXT);
            const caret = document.createRange();
            caret.selectNodeContents(host);
            caret.collapse(offset <= start);
            search: for (let node = walker.nextNode(); node; node = walker.nextNode()) {
                if (node.parentElement.closest(passageLink)) continue;
                for (let j = 0; j < node.length; j++) {
                    if (/\s/.test(node.data[j])) continue;
                    if (remaining === 0) { caret.setStart(node, j); caret.collapse(true); break search; }
                    remaining--;
                }
            }
            host.focus({preventScroll: true});
            const selection = window.getSelection();
            selection.removeAllRanges();
            selection.addRange(caret);
        }
    };
}

export function placeRichCaret(root, point, fallback) {
    const caret = point && document.caretRangeFromPoint?.(point.clientX, point.clientY);
    const target = caret?.startContainer;
    const element = target?.nodeType === Node.ELEMENT_NODE ? target : target?.parentElement;
    const host = root.contains(element) && element.closest('.text-frame-rich-block') || fallback;
    host.focus({preventScroll: true});
    const selection = window.getSelection();
    const range = caret && host.contains(caret.startContainer) ? caret : document.createRange();
    if (range !== caret) { range.selectNodeContents(host); range.collapse(false); }
    selection.removeAllRanges();
    selection.addRange(range);
}

function lineRects(host) {
    const walker = document.createTreeWalker(host, NodeFilter.SHOW_TEXT | NodeFilter.SHOW_ELEMENT);
    const range = document.createRange();
    const rects = [];
    for (let node = walker.nextNode(); node; node = walker.nextNode()) {
        if (node.nodeType === Node.TEXT_NODE) {
            range.selectNodeContents(node);
            rects.push(...range.getClientRects());
        } else if (node.matches('br,img')) rects.push(...node.getClientRects());
    }
    return rects.filter(rect => rect.height > 0);
}

// Chromium's caret stops at each contenteditable boundary. Bridge those boundaries while
// leaving movement within a paragraph (including wrapped lines) to the browser.
export function moveBetweenRichBlocks(event, root) {
    const {key} = event;
    if (event.shiftKey || event.altKey || event.metaKey || event.ctrlKey ||
        !['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown'].includes(key)) return;
    const host = event.target.closest('.text-frame-rich-block');
    const selection = window.getSelection();
    if (!host || !selection?.isCollapsed || !selection.rangeCount || !host.contains(selection.anchorNode)) return;
    const backwards = key === 'ArrowLeft' || key === 'ArrowUp';
    const vertical = key === 'ArrowUp' || key === 'ArrowDown';
    const hosts = [...root.querySelectorAll('.text-frame-rich-block')];
    const next = hosts[hosts.indexOf(host) + (backwards ? -1 : 1)];
    if (!next) return;

    const caret = selection.getRangeAt(0);
    const edge = document.createRange();
    edge.selectNodeContents(host);
    if (backwards) edge.setEnd(caret.startContainer, caret.startOffset);
    else edge.setStart(caret.startContainer, caret.startOffset);
    const atEdge = !edge.toString() && !edge.cloneContents().querySelector('img');
    let destination;
    if (vertical) {
        const caretRect = caret.getClientRects()[0];
        if (!caretRect && !atEdge) return;
        if (caretRect) {
            const middle = (caretRect.top + caretRect.bottom) / 2;
            if (lineRects(host).some(rect => backwards ? rect.bottom < middle : rect.top > middle)) return;
            const target = lineRects(next).reduce((best, rect) =>
                !best || (backwards ? rect.bottom > best.bottom : rect.top < best.top) ? rect : best, null);
            if (target) {
                const x = Math.max(next.getBoundingClientRect().left, Math.min(caretRect.left, next.getBoundingClientRect().right - 1));
                destination = document.caretRangeFromPoint?.(x, (target.top + target.bottom) / 2);
            }
        }
    } else if (!atEdge) return;

    event.preventDefault();
    next.focus({preventScroll: true});
    if (!destination || !next.contains(destination.startContainer)) {
        const walker = document.createTreeWalker(next, NodeFilter.SHOW_TEXT | NodeFilter.SHOW_ELEMENT, {
            acceptNode: node => node.nodeType === Node.TEXT_NODE ? (node.length ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_SKIP) :
                (node.matches('img,br') ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_SKIP)
        });
        const nodes = [];
        for (let node = walker.nextNode(); node; node = walker.nextNode()) nodes.push(node);
        const leaf = backwards ? nodes.at(-1) : nodes[0];
        destination = document.createRange();
        if (leaf?.nodeType === Node.TEXT_NODE) destination.setStart(leaf, backwards ? leaf.length : 0);
        else if (leaf && backwards && leaf.matches('img')) destination.setStartAfter(leaf);
        else if (leaf) destination.setStartBefore(leaf);
        else destination.selectNodeContents(next);
        destination.collapse(true);
    }
    selection.removeAllRanges();
    selection.addRange(destination);
    const pane = root.getBoundingClientRect(), block = next.getBoundingClientRect();
    const target = destination.getClientRects()[0] || block;
    if (target.top < pane.top || target.bottom > pane.bottom) {
        const scale = pane.height / root.offsetHeight || 1;
        root.scrollTop += (target.top < pane.top ? target.top - pane.top : target.bottom - pane.bottom) / scale;
    }
}

export async function pasteRichText(event, root, isCurrent, changed) {
    event.preventDefault();
    const selection = window.getSelection();
    const host = event.target.closest('.text-frame-rich-block');
    if (!host || !selection.rangeCount || !host.contains(selection.anchorNode) || !host.contains(selection.focusNode)) return;
    const clipboard = event.clipboardData;
    const image = [...(clipboard?.files || [])].find(file => /^image\/(png|jpeg|gif|webp)$/.test(file.type));
    if (!image) {
        const text = clipboard?.getData('text/plain');
        if (text) document.execCommand('insertText', false, text);
        return;
    }
    const range = selection.getRangeAt(0).cloneRange();
    if (image.size > 1024 * 1024) {
        window.notify?.('Paste supports images up to 1 MiB. Use a Markdown file link for larger images.');
        return;
    }
    const reader = new FileReader();
    try {
        await new Promise((resolve, reject) => {
            reader.onload = resolve;
            reader.onerror = reject;
            reader.readAsDataURL(image);
        });
        if (!isCurrent() || !root.contains(host) || document.activeElement !== host ||
            !selection.rangeCount) return;
        const current = selection.getRangeAt(0);
        if (current.compareBoundaryPoints(Range.START_TO_START, range) !== 0 ||
            current.compareBoundaryPoints(Range.END_TO_END, range) !== 0) return;
        const img = document.createElement('img');
        img.src = reader.result;
        img.alt = 'Pasted image';
        img.dataset.richMarkdown = `![Pasted image](${reader.result})`;
        document.execCommand('insertHTML', false, img.outerHTML);
        changed();
    } catch (_) {
        window.notify?.('Could not paste the image.');
    }
}
