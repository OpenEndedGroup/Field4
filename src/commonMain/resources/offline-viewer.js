/**
 * Static, read-only renderer for the Field metadata subset parsed by
 * `edn-parser.js`.
 *
 * The viewer fetches the `?file=` document (and its loose sidecar files, which
 * win over the inline copies, as they do in Field), omits tombstones/root/unnamed
 * or empty boxes, and draws, in world coordinates under one camera
 * (`screen = world * zoom + translation`, the canvas convention): the root
 * box's Excalidraw `drawing` as static SVG, parent edges, and box frames. A
 * selected box shows its `notes` as Markdown above its sidecar properties in a
 * read-only Monaco model (or a graph-only fallback when Monaco cannot load).
 * Bookmarks jump the camera; layers filter boxes. It does not connect to
 * Field's websocket or execute box code.
 *
 * URL parameters: `file` (required), `select=<box id or name>`, `reader=1`,
 * `bookmark=<slot>`, `layer=<name>`, `drawing=0`.
 *
 * `Open in Field·4` is deliberately outside that read-only boundary. A click
 * first asks an embedding Field docs frame to import the EDN, then falls back to
 * POSTing it to a local Field's `/api/import-offline-doc` (see
 * `fieldImportBases`). That operation creates a new workspace document; callers
 * must therefore treat remotely supplied EDN as untrusted even though viewing it
 * here does not execute it.
 */

let monacoEditor = null;
let currentBoxes = [];
let selectedBox = null;
let currentProperty = 'code';
let ednFileUrl = null;
let ednFileText = '';
let currentDocumentData = null;
// The canvas camera: screen = world * zoom + (panX, panY), as in Field's CanvasCamera.
let panX = 0;
let panY = 0;
let zoom = 1;
const MIN_ZOOM = 0.1;
const MAX_ZOOM = 4;
let isPanning = false;
let ednBaseUrl = null;
let drawingInfo = null;
let showDrawing = true;
let activeLayer = null;
let bookmarks = [];
let startPanX = 0;
let startPanY = 0;
let literateMode = false;
let literateViewEl = null;
let visibleBoxes = [];

function cubicPoint(p0, p1, p2, p3, t) {
    const mt = 1 - t;
    const mt2 = mt * mt;
    const t2 = t * t;
    return {
        x: mt2 * mt * p0.x + 3 * mt2 * t * p1.x + 3 * mt * t2 * p2.x + t2 * t * p3.x,
        y: mt2 * mt * p0.y + 3 * mt2 * t * p1.y + 3 * mt * t2 * p2.y + t2 * t * p3.y
    };
}

function cubicTangent(p0, p1, p2, p3, t) {
    const mt = 1 - t;
    return {
        x: 3 * mt * mt * (p1.x - p0.x) + 6 * mt * t * (p2.x - p1.x) + 3 * t * t * (p3.x - p2.x),
        y: 3 * mt * mt * (p1.y - p0.y) + 6 * mt * t * (p2.y - p1.y) + 3 * t * t * (p3.y - p2.y)
    };
}


// ---------------------------------------------------------------------------
// Markdown (box `notes`) — a small, escaping renderer for the subset the notes use:
// ATX headings, paragraphs, emphasis, inline code, links and images, fenced code,
// block quotes, ordered/unordered lists (nested by indent), pipe tables and rules.
// Everything is HTML-escaped first; no raw HTML from the note reaches the page.
// ---------------------------------------------------------------------------

function escapeMarkdownHtml(str) {
    return String(str)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

function safeMarkdownUrl(url, baseUrl) {
    const trimmed = String(url).trim();
    if (/^(javascript|vbscript|data):/i.test(trimmed) && !/^data:image\//i.test(trimmed)) return '#';
    if (!baseUrl || /^([a-z][a-z0-9+.-]*:|#|\/)/i.test(trimmed)) return trimmed;
    try {
        return new URL(trimmed, baseUrl).href;
    } catch (_) {
        return trimmed;
    }
}

function renderMarkdownInline(text, baseUrl) {
    // Pull code spans out first so their contents are not formatted.
    const codes = [];
    let s = String(text).replace(/(`+)([\s\S]*?[^`])\1(?!`)/g, (_, ticks, body) => {
        codes.push(body.trim());
        return `\u0000${codes.length - 1}\u0000`;
    });
    s = escapeMarkdownHtml(s);
    s = s.replace(/!\[([^\]]*)\]\(([^)\s]+)(?:\s+&quot;[^&]*&quot;)?\)/g, (_, alt, url) =>
        `<img src="${safeMarkdownUrl(url.replace(/&amp;/g, '&'), baseUrl)}" alt="${alt}">`);
    s = s.replace(/\[([^\]]+)\]\(([^)\s]+)(?:\s+&quot;[^&]*&quot;)?\)/g, (_, label, url) =>
        `<a href="${safeMarkdownUrl(url.replace(/&amp;/g, '&'), baseUrl)}" target="_blank" rel="noopener">${label}</a>`);
    s = s.replace(/(^|[\s(])(https?:\/\/[^\s<)]+)/g, '$1<a href="$2" target="_blank" rel="noopener">$2</a>');
    s = s.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
    s = s.replace(/__([^_]+)__/g, '<strong>$1</strong>');
    s = s.replace(/(^|[^*\w])\*([^*\s][^*]*?)\*(?!\w)/g, '$1<em>$2</em>');
    s = s.replace(/(^|[^_\w])_([^_\s][^_]*?)_(?!\w)/g, '$1<em>$2</em>');
    s = s.replace(/~~([^~]+)~~/g, '<del>$1</del>');
    s = s.replace(/ {2,}\n/g, '<br>');
    return s.replace(/\u0000(\d+)\u0000/g, (_, i) => `<code>${escapeMarkdownHtml(codes[Number(i)])}</code>`);
}

function splitTableRow(line) {
    let row = line.trim();
    if (row.startsWith('|')) row = row.slice(1);
    if (row.endsWith('|') && !row.endsWith('\\|')) row = row.slice(0, -1);
    const cells = [];
    let cell = '';
    let inCode = false;
    for (let i = 0; i < row.length; i++) {
        const c = row[i];
        if (c === '`') inCode = !inCode;
        if (c === '\\' && row[i + 1] === '|') { cell += '|'; i++; continue; }
        if (c === '|' && !inCode) { cells.push(cell.trim()); cell = ''; continue; }
        cell += c;
    }
    cells.push(cell.trim());
    return cells;
}

/**
 * Markdown to HTML. `options.baseUrl` resolves relative links and images (a note's images sit
 * beside metadata.edn); `options.highlight(code, lang)` may return highlighted HTML for a fence.
 */
function renderMarkdown(markdown, options = {}) {
    const baseUrl = options.baseUrl || null;
    const highlight = options.highlight || null;
    const lines = String(markdown || '').replace(/\r\n?/g, '\n').split('\n');
    const out = [];
    let i = 0;

    const isBlank = (l) => /^\s*$/.test(l);
    const isFence = (l) => /^\s*(```|~~~)/.test(l);
    const isHeading = (l) => /^\s{0,3}#{1,6}(\s|$)/.test(l);
    const isRule = (l) => /^\s{0,3}([-*_])(\s*\1){2,}\s*$/.test(l);
    const isQuote = (l) => /^\s{0,3}>/.test(l);
    const listMatch = (l) => /^(\s*)([-*+]|\d+[.)])\s+(.*)$/.exec(l);
    const isTableStart = (k) => k + 1 < lines.length && lines[k].includes('|') &&
        /^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$/.test(lines[k + 1]);
    const startsBlock = (k) => isFence(lines[k]) || isHeading(lines[k]) || isRule(lines[k]) ||
        isQuote(lines[k]) || listMatch(lines[k]) || isTableStart(k);

    function renderList(startIndent) {
        const first = listMatch(lines[i]);
        const ordered = /\d/.test(first[2]);
        const items = [];
        while (i < lines.length) {
            const m = listMatch(lines[i]);
            if (!m || m[1].length < startIndent) break;
            if (m[1].length === startIndent && /\d/.test(m[2]) !== ordered) break;
            if (m[1].length > startIndent && items.length) {
                items[items.length - 1].children.push(renderList(m[1].length));
                continue;
            }
            const item = { text: [m[3]], children: [] };
            items.push(item);
            i++;
            // Continuation lines (indented, not a new item) belong to this item.
            while (i < lines.length && !isBlank(lines[i]) && !listMatch(lines[i]) && /^\s+\S/.test(lines[i])) {
                item.text.push(lines[i].trim());
                i++;
            }
            if (i < lines.length && isBlank(lines[i]) && i + 1 < lines.length) {
                const next = listMatch(lines[i + 1]);
                if (next && next[1].length >= startIndent) i++;
            }
        }
        const tag = ordered ? 'ol' : 'ul';
        const body = items.map(it => {
            let text = it.text.join('\n');
            let check = '';
            const task = /^\[([ xX])\]\s+/.exec(text);
            if (task) {
                check = `<input type="checkbox" disabled${task[1] === ' ' ? '' : ' checked'}> `;
                text = text.slice(task[0].length);
            }
            return `<li>${check}${renderMarkdownInline(text, baseUrl)}${it.children.join('')}</li>`;
        }).join('');
        return `<${tag}>${body}</${tag}>`;
    }

    while (i < lines.length) {
        const line = lines[i];
        if (isBlank(line)) { i++; continue; }

        if (isFence(line)) {
            const fence = /^\s*(```+|~~~+)\s*([^\s`{]*)/.exec(line);
            const marker = fence[1];
            const lang = fence[2] || '';
            const body = [];
            i++;
            while (i < lines.length && !lines[i].trim().startsWith(marker)) body.push(lines[i++]);
            i++;
            const code = body.join('\n');
            const highlighted = highlight ? highlight(code, lang) : null;
            out.push(`<pre class="md-code"${lang ? ` data-lang="${escapeMarkdownHtml(lang)}"` : ''}><code>${highlighted || escapeMarkdownHtml(code)}</code></pre>`);
            continue;
        }

        const heading = /^\s{0,3}(#{1,6})\s*(.*?)\s*#*\s*$/.exec(line);
        if (heading && isHeading(line)) {
            const level = heading[1].length;
            out.push(`<h${level}>${renderMarkdownInline(heading[2], baseUrl)}</h${level}>`);
            i++;
            continue;
        }

        if (isRule(line)) { out.push('<hr>'); i++; continue; }

        if (isQuote(line)) {
            const body = [];
            while (i < lines.length && !isBlank(lines[i]) && (isQuote(lines[i]) || !startsBlock(i))) {
                body.push(lines[i].replace(/^\s{0,3}>\s?/, ''));
                i++;
            }
            out.push(`<blockquote>${renderMarkdown(body.join('\n'), options)}</blockquote>`);
            continue;
        }

        if (isTableStart(i)) {
            const header = splitTableRow(lines[i]);
            const aligns = splitTableRow(lines[i + 1]).map(c =>
                c.startsWith(':') && c.endsWith(':') ? 'center' : c.endsWith(':') ? 'right' : c.startsWith(':') ? 'left' : '');
            i += 2;
            const rows = [];
            while (i < lines.length && !isBlank(lines[i]) && lines[i].includes('|')) rows.push(splitTableRow(lines[i++]));
            const cell = (tag, text, k) => `<${tag}${aligns[k] ? ` style="text-align:${aligns[k]}"` : ''}>${renderMarkdownInline(text, baseUrl)}</${tag}>`;
            const headerHasText = header.some(h => h.length > 0);
            out.push('<table>' +
                (headerHasText ? `<thead><tr>${header.map((h, k) => cell('th', h, k)).join('')}</tr></thead>` : '') +
                `<tbody>${rows.map(r => `<tr>${header.map((_, k) => cell('td', r[k] || '', k)).join('')}</tr>`).join('')}</tbody></table>`);
            continue;
        }

        if (listMatch(line)) {
            out.push(renderList(listMatch(line)[1].length));
            continue;
        }

        const para = [];
        while (i < lines.length && !isBlank(lines[i]) && !(para.length && startsBlock(i))) para.push(lines[i++]);
        out.push(`<p>${renderMarkdownInline(para.join('\n'), baseUrl)}</p>`);
    }
    return out.join('\n');
}

// ---------------------------------------------------------------------------
// Drawing layer — a static SVG of the root box's Excalidraw scene.
//
// Field keeps one Excalidraw scene per document in the root `drawing` property, in world
// coordinates (the same space as box frames). Field shows it with Excalidraw's dark theme, which
// draws through the CSS filter `invert(93%) hue-rotate(180deg)`; the viewer applies the same filter
// to the SVG group, so stored colours come out as they do in Field (see DarkThemeColour.kt).
// Shapes are drawn smooth rather than hand-drawn (no rough.js).
// ---------------------------------------------------------------------------

const EXCALIDRAW_FONTS = {
    1: '"Virgil", "Excalifont", "Comic Sans MS", cursive',
    2: '"Helvetica", "Arial", sans-serif',
    3: '"Cascadia", "Iosevka Web", monospace',
    5: '"Excalifont", "Virgil", "Comic Sans MS", cursive',
    6: '"Nunito", "Helvetica", sans-serif',
    7: '"Lilita One", "Helvetica", sans-serif',
    8: '"Comic Shanns", "Iosevka Web", monospace',
    9: '"Liberation Sans", "Arial", sans-serif'
};

/** The scene's live elements: `drawing` may be a JSON string, a scene object or an element array. */
function excalidrawElements(drawing) {
    let scene = drawing;
    if (typeof scene === 'string') {
        if (!scene.trim()) return [];
        try {
            scene = JSON.parse(scene);
        } catch (_) {
            return [];
        }
    }
    const elements = Array.isArray(scene) ? scene : (scene && Array.isArray(scene.elements) ? scene.elements : []);
    return elements.filter(e => e && typeof e === 'object' && !e.isDeleted);
}

function svgNum(n) {
    return Number.isFinite(n) ? Math.round(n * 100) / 100 : 0;
}

function excalidrawStrokeDash(el) {
    const w = el.strokeWidth || 1;
    if (el.strokeStyle === 'dashed') return ` stroke-dasharray="${svgNum(8 + w)} ${svgNum(8 + w)}"`;
    if (el.strokeStyle === 'dotted') return ` stroke-dasharray="${svgNum(1.5)} ${svgNum(6 + w)}"`;
    return '';
}

function excalidrawFill(el) {
    const bg = el.backgroundColor;
    if (!bg || bg === 'transparent') return 'none';
    return bg;
}

/** A smooth path through points (Catmull-Rom as cubic Béziers), for freedraw and curved lines. */
function smoothPath(points) {
    if (points.length === 0) return '';
    if (points.length === 1) {
        const [x, y] = points[0];
        return `M ${svgNum(x)} ${svgNum(y)} l 0.01 0`;
    }
    if (points.length === 2) return `M ${svgNum(points[0][0])} ${svgNum(points[0][1])} L ${svgNum(points[1][0])} ${svgNum(points[1][1])}`;
    let d = `M ${svgNum(points[0][0])} ${svgNum(points[0][1])}`;
    for (let k = 0; k < points.length - 1; k++) {
        const p0 = points[k - 1] || points[k];
        const p1 = points[k];
        const p2 = points[k + 1];
        const p3 = points[k + 2] || p2;
        const c1 = [p1[0] + (p2[0] - p0[0]) / 6, p1[1] + (p2[1] - p0[1]) / 6];
        const c2 = [p2[0] - (p3[0] - p1[0]) / 6, p2[1] - (p3[1] - p1[1]) / 6];
        d += ` C ${svgNum(c1[0])} ${svgNum(c1[1])}, ${svgNum(c2[0])} ${svgNum(c2[1])}, ${svgNum(p2[0])} ${svgNum(p2[1])}`;
    }
    return d;
}

function arrowheadSvg(kind, tip, from, el, color) {
    if (!kind) return '';
    const dx = tip[0] - from[0];
    const dy = tip[1] - from[1];
    const len = Math.hypot(dx, dy) || 1;
    const ux = dx / len;
    const uy = dy / len;
    const size = Math.min(30, Math.max(10, (el.strokeWidth || 1) * 6 + 6), len * 0.9 + 6);
    const w = el.strokeWidth || 1;
    const back = [tip[0] - ux * size, tip[1] - uy * size];
    const px = -uy;
    const py = ux;
    const spread = kind === 'triangle' || kind === 'triangle_outline' ? 0.5 : 0.45;
    const left = [back[0] + px * size * spread, back[1] + py * size * spread];
    const right = [back[0] - px * size * spread, back[1] - py * size * spread];
    const pt = (p) => `${svgNum(p[0])} ${svgNum(p[1])}`;
    switch (kind) {
        case 'triangle':
            return `<path d="M ${pt(tip)} L ${pt(left)} L ${pt(right)} Z" fill="${color}" stroke="${color}" stroke-width="${w}" stroke-linejoin="round"/>`;
        case 'triangle_outline':
            return `<path d="M ${pt(tip)} L ${pt(left)} L ${pt(right)} Z" fill="none" stroke="${color}" stroke-width="${w}" stroke-linejoin="round"/>`;
        case 'dot':
        case 'circle':
        case 'circle_outline': {
            const r = Math.max(3, size / 4);
            const fill = kind === 'circle_outline' ? 'none' : color;
            return `<circle cx="${svgNum(tip[0] - ux * r)}" cy="${svgNum(tip[1] - uy * r)}" r="${svgNum(r)}" fill="${fill}" stroke="${color}" stroke-width="${w}"/>`;
        }
        case 'bar':
            return `<path d="M ${pt([tip[0] + px * size / 2, tip[1] + py * size / 2])} L ${pt([tip[0] - px * size / 2, tip[1] - py * size / 2])}" stroke="${color}" stroke-width="${w}" stroke-linecap="round"/>`;
        default: // 'arrow' and anything newer: an open chevron
            return `<path d="M ${pt(left)} L ${pt(tip)} L ${pt(right)}" fill="none" stroke="${color}" stroke-width="${w}" stroke-linecap="round" stroke-linejoin="round"/>`;
    }
}

// The canvas background (#141414) as stored under the dark-theme filter: a halo in this colour reads
// as a gap, the way Excalidraw breaks an arrow around its label.
const EXCALIDRAW_GAP_COLOUR = '#fdfdfd';

/** SVG markup for one Excalidraw element, in its own world coordinates. */
function excalidrawElementSvg(el, byId = null) {
    const x = Number(el.x) || 0;
    const y = Number(el.y) || 0;
    const w = Number(el.width) || 0;
    const h = Number(el.height) || 0;
    const stroke = el.strokeColor || '#1e1e1e';
    const strokeWidth = el.strokeWidth || 1;
    const opacity = el.opacity == null ? 1 : el.opacity / 100;
    const angle = Number(el.angle) || 0;
    const transform = angle ? ` transform="rotate(${svgNum(angle * 180 / Math.PI)} ${svgNum(x + w / 2)} ${svgNum(y + h / 2)})"` : '';
    const common = `stroke="${escapeMarkdownHtml(stroke)}" stroke-width="${strokeWidth}"${excalidrawStrokeDash(el)} stroke-linecap="round" stroke-linejoin="round"`;
    const fill = escapeMarkdownHtml(excalidrawFill(el));
    const fillOpacity = el.fillStyle && el.fillStyle !== 'solid' && fill !== 'none' ? ' fill-opacity="0.45"' : '';
    let body = '';
    switch (el.type) {
        case 'rectangle':
        case 'frame':
        case 'magicframe':
        case 'embeddable':
        case 'iframe': {
            const r = el.roundness ? Math.min(32, Math.min(w, h) * 0.25) : 0;
            const frameStyle = el.type === 'rectangle' ? '' : ' stroke-dasharray="4 4"';
            body = `<rect x="${svgNum(x)}" y="${svgNum(y)}" width="${svgNum(w)}" height="${svgNum(h)}" rx="${svgNum(r)}" fill="${fill}"${fillOpacity} ${common}${frameStyle}/>`;
            if (el.type !== 'rectangle' && el.name) {
                body += `<text x="${svgNum(x)}" y="${svgNum(y - 6)}" font-size="14" fill="${escapeMarkdownHtml(stroke)}" font-family='${EXCALIDRAW_FONTS[2]}'>${escapeMarkdownHtml(el.name)}</text>`;
            }
            break;
        }
        case 'ellipse':
            body = `<ellipse cx="${svgNum(x + w / 2)}" cy="${svgNum(y + h / 2)}" rx="${svgNum(Math.abs(w) / 2)}" ry="${svgNum(Math.abs(h) / 2)}" fill="${fill}"${fillOpacity} ${common}/>`;
            break;
        case 'diamond':
            body = `<path d="M ${svgNum(x + w / 2)} ${svgNum(y)} L ${svgNum(x + w)} ${svgNum(y + h / 2)} L ${svgNum(x + w / 2)} ${svgNum(y + h)} L ${svgNum(x)} ${svgNum(y + h / 2)} Z" fill="${fill}"${fillOpacity} ${common}/>`;
            break;
        case 'line':
        case 'arrow': {
            const pts = (Array.isArray(el.points) && el.points.length ? el.points : [[0, 0], [w, h]])
                .map(p => [x + (Number(p[0]) || 0), y + (Number(p[1]) || 0)]);
            const curved = el.roundness && el.elbowed !== true && pts.length > 2;
            const d = curved ? smoothPath(pts) : 'M ' + pts.map(p => `${svgNum(p[0])} ${svgNum(p[1])}`).join(' L ');
            const closed = el.type === 'line' && pts.length > 2 && Math.hypot(pts[0][0] - pts[pts.length - 1][0], pts[0][1] - pts[pts.length - 1][1]) < 1;
            body = `<path d="${d}${closed ? ' Z' : ''}" fill="${closed ? fill : 'none'}"${closed ? fillOpacity : ''} ${common}/>`;
            if (el.type === 'arrow' && pts.length >= 2) {
                const color = escapeMarkdownHtml(stroke);
                const endKind = el.endArrowhead === undefined ? 'arrow' : el.endArrowhead;
                body += arrowheadSvg(endKind, pts[pts.length - 1], pts[pts.length - 2], el, color);
                body += arrowheadSvg(el.startArrowhead, pts[0], pts[1], el, color);
            }
            break;
        }
        case 'freedraw': {
            const pts = (Array.isArray(el.points) ? el.points : []).map(p => [x + (Number(p[0]) || 0), y + (Number(p[1]) || 0)]);
            // Excalidraw draws freedraw as a filled outline about 1.5x the stroke width wide.
            const width = Math.max(1, strokeWidth * 1.6);
            body = `<path d="${smoothPath(pts)}" fill="none" stroke="${escapeMarkdownHtml(stroke)}" stroke-width="${svgNum(width)}" stroke-linecap="round" stroke-linejoin="round"/>`;
            break;
        }
        case 'text': {
            const fontSize = Number(el.fontSize) || 20;
            const lineHeight = (Number(el.lineHeight) || 1.25) * fontSize;
            const text = String(el.text != null ? el.text : (el.originalText || ''));
            const anchor = el.textAlign === 'center' ? 'middle' : el.textAlign === 'right' ? 'end' : 'start';
            const tx = el.textAlign === 'center' ? x + w / 2 : el.textAlign === 'right' ? x + w : x;
            const textLines = text.split('\n');
            const blockHeight = textLines.length * lineHeight;
            const top = el.verticalAlign === 'middle' && h ? y + (h - blockHeight) / 2 : y;
            const font = EXCALIDRAW_FONTS[el.fontFamily] || EXCALIDRAW_FONTS[1];
            const tspans = textLines.map((line, k) =>
                `<tspan x="${svgNum(tx)}" y="${svgNum(top + k * lineHeight + fontSize * 0.95)}">${escapeMarkdownHtml(line) || ' '}</tspan>`).join('');
            const container = byId && el.containerId ? byId.get(el.containerId) : null;
            const halo = container && (container.type === 'arrow' || container.type === 'line')
                ? ` stroke="${EXCALIDRAW_GAP_COLOUR}" stroke-width="${svgNum(fontSize * 0.6)}" stroke-linejoin="round" paint-order="stroke"` : '';
            body = `<text font-size="${svgNum(fontSize)}" font-family='${font}' fill="${escapeMarkdownHtml(stroke)}" text-anchor="${anchor}"${halo} xml:space="preserve">${tspans}</text>`;
            break;
        }
        case 'image':
            body = `<rect x="${svgNum(x)}" y="${svgNum(y)}" width="${svgNum(w)}" height="${svgNum(h)}" fill="none" stroke="${escapeMarkdownHtml(stroke)}" stroke-dasharray="6 4" stroke-width="1"/>`;
            break;
        default:
            return '';
    }
    const id = el.id ? ` data-element-id="${escapeMarkdownHtml(el.id)}"` : '';
    return `<g${id} data-type="${escapeMarkdownHtml(el.type)}" opacity="${svgNum(opacity)}"${transform}>${body}</g>`;
}

/** World-space bounds of the scene's elements, or null when it is empty. */
function excalidrawBounds(elements) {
    let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
    const add = (px, py) => {
        if (!Number.isFinite(px) || !Number.isFinite(py)) return;
        minX = Math.min(minX, px); minY = Math.min(minY, py);
        maxX = Math.max(maxX, px); maxY = Math.max(maxY, py);
    };
    for (const el of elements) {
        const x = Number(el.x) || 0;
        const y = Number(el.y) || 0;
        if (Array.isArray(el.points) && el.points.length) {
            el.points.forEach(p => add(x + (Number(p[0]) || 0), y + (Number(p[1]) || 0)));
        } else {
            add(x, y);
            add(x + (Number(el.width) || 0), y + (Number(el.height) || 0));
        }
    }
    return Number.isFinite(minX) ? { x: minX, y: minY, w: maxX - minX, h: maxY - minY } : null;
}

/** Inner SVG markup for the whole scene, in Excalidraw's z-order (array order). */
function excalidrawSceneSvg(drawing) {
    const elements = excalidrawElements(drawing);
    const byId = new Map(elements.map(e => [e.id, e]));
    return { markup: elements.map(e => excalidrawElementSvg(e, byId)).join(''), count: elements.length, bounds: excalidrawBounds(elements) };
}

// Initialize the viewer when page loads
if (typeof window !== 'undefined') window.addEventListener('DOMContentLoaded', async () => {
    try {
        // Get EDN file URL from query parameter
        const params = new URLSearchParams(window.location.search);
        ednFileUrl = params.get('file');

        if (!ednFileUrl) {
            showError('No file specified. Add ?file=path/to/metadata.edn to the URL');
            return;
        }

        // Load and parse the EDN file
        const { text: ednText, url: resolvedUrl } = await fetchEdnFile(ednFileUrl);
        ednFileText = ednText;
        ednBaseUrl = new URL(resolvedUrl, window.location.href).href;
        const data = parseEdn(ednText);
        currentDocumentData = data;

        if (!data || !data.boxes) {
            showError('Invalid EDN format: missing boxes array');
            return;
        }

        // `#afield/file` sidecars (too large to inline) live only beside metadata.edn.
        const sidecarStatus = await resolveSidecars(data, ednBaseUrl, fetchSidecarText);

        showDrawing = params.get('drawing') !== '0';
        activeLayer = params.get('layer');
        bookmarks = readBookmarks(data);

        // Render the boxes
        currentBoxes = data.boxes;
        renderBoxes(data.boxes);
        fitCameraToContent();

        // Show document info
        showDocumentInfo(data, ednFileUrl, sidecarStatus);
        setupDocumentPanel(data);

        // Initialize Monaco editor. A failure here (e.g. the Monaco bundle isn't
        // deployed alongside the viewer) must not blank the already-rendered box
        // graph -- degrade to a graph-only, editor-less view instead.
        try {
            await initializeMonaco();
        } catch (monacoError) {
            console.warn('Monaco editor unavailable; showing box graph only.', monacoError);
        }

        // Cache literate view element
        literateViewEl = document.getElementById('literate-view');

        // Setup view toggle
        setupLiterateToggle();

        // Setup the "Open in Field·4" button
        setupOpenLocalButton(ednFileUrl, ednText, data);

        // Setup help overlay
        setupHelpOverlay();

        // Hide loading indicator
        document.getElementById('loading').style.display = 'none';

        applyUrlState(params);

        // Field loads a sidecar from its loose file and keeps the inline copy as a fallback; read
        // the loose files now, and refresh the open box if one of them differs.
        resolveSidecars(data, ednBaseUrl, fetchSidecarText, { includeInline: true }).then(status => {
            if (status.refreshed > 0) {
                showDocumentInfo(data, ednFileUrl, { ...sidecarStatus, refreshed: status.refreshed });
                if (selectedBox) showEditorForBox(selectedBox);
            }
        });

    } catch (error) {
        console.error('Failed to load document:', error);
        showError(`Failed to load document: ${error.message}`);
    }
});

// The `?file=` value is authored as an absolute site path (e.g.
// `/field4/assets/foo/metadata.edn`) so it resolves when the docs are deployed
// under a `/field4/` mount, or served by the running Field app. But a plain
// static serving of the built site roots *at* the site dir, so the same asset
// lives at `/assets/foo/metadata.edn` (no `/field4` prefix) and the offline
// viewer itself sits at `/offline.html`. Rather than bake one mount point into
// every doc iframe, derive candidate URLs and try each so the same page works
// whether it is served at the site root or under a sub-path.
function ednFileCandidates(fileParam) {
    const candidates = [];
    const seen = new Set();
    const add = (value) => {
        if (typeof value !== 'string' || value.length === 0) return;
        if (seen.has(value)) return;
        seen.add(value);
        candidates.push(value);
    };

    add(fileParam);

    // The build emits the offline viewer into the site dir, so `/offline.html`
    // and `assets/...` share a root. Strip a leading `/<site>/` segment so an
    // absolute `/field4/assets/...` path also resolves when that dir *is* the
    // server root (asset then lives at `/assets/...`).
    if (fileParam.startsWith('/')) {
        const withoutLeadingSlash = fileParam.replace(/^\/+/, '');
        const firstSlash = withoutLeadingSlash.indexOf('/');
        if (firstSlash > 0) {
            add('/' + withoutLeadingSlash.slice(firstSlash + 1));
        }
        // Also try resolving relative to the viewer's own location.
        add(withoutLeadingSlash);
    }

    return candidates;
}

async function fetchEdnFile(url) {
    const candidates = ednFileCandidates(url);
    const failures = [];

    for (const candidate of candidates) {
        try {
            const response = await fetch(candidate);
            if (!response.ok) {
                failures.push(`${candidate} → HTTP ${response.status}`);
                continue;
            }
            return { text: await response.text(), url: response.url || candidate };
        } catch (error) {
            failures.push(`${candidate} → ${error.message}`);
        }
    }

    throw new Error(`Failed to fetch ${url} (tried ${candidates.length}: ${failures.join('; ')})`);
}

async function fetchSidecarText(url) {
    const response = await fetch(url);
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const type = response.headers.get('content-type') || '';
    // A static server's HTML 404 page served with status 200 is not a sidecar.
    if (/text\/html/i.test(type) && !/\.html?$/i.test(new URL(url).pathname)) throw new Error('not a sidecar');
    return await response.text();
}

function hasDisplayableCode(props = {}) {
    if (typeof props.notes === 'string' && props.notes.trim().length > 0) return true;
    const code = props.code;
    if (typeof code !== 'string') {
        return true;
    }
    return code.trim().length > 0;
}

/** A box's layers (`layers` is comma-separated; a box with none is on "default"). */
function boxLayers(props = {}) {
    const raw = typeof props.layers === 'string' ? props.layers : 'default';
    const names = raw.split(',').map(s => s.trim()).filter(Boolean);
    return names.length ? names : ['default'];
}

function documentLayers(boxes) {
    const names = new Set();
    boxes.forEach(box => {
        const props = box.properties || {};
        if (props._tombstone === true || box.id === 'root') return;
        boxLayers(props).forEach(n => names.add(n));
    });
    return Array.from(names);
}

function rootProperties(data) {
    const root = (data.boxes || []).find(b => b.id === 'root');
    return (root && root.properties) || {};
}

/** Bookmarks from the root `bookmarks` property (JSON BookmarkData), by slot. */
function readBookmarks(data) {
    const raw = rootProperties(data).bookmarks;
    if (typeof raw !== 'string' || !raw.trim()) return [];
    try {
        const parsed = JSON.parse(raw);
        return (parsed.bookmarks || [])
            .filter(b => b && b.scrollPosition)
            .map(b => ({ ...b, slot: Number(String(b.id || '').replace('bookmark_', '')) || null }))
            .sort((a, b) => (a.slot || 99) - (b.slot || 99));
    } catch (e) {
        console.warn('Unreadable bookmarks property', e);
        return [];
    }
}

function hasDisplayableName(props = {}) {
    const name = props.name;
    return typeof name === 'string' && name.trim().length > 0;
}

function renderBoxes(boxes) {
    const canvas = document.getElementById('canvas');
    const connections = document.getElementById('connections');
    canvas.innerHTML = '';
    if (connections) connections.innerHTML = '';

    visibleBoxes = boxes.filter(box => {
        const props = box.properties || {};
        return props._tombstone !== true && box.id !== 'root' && hasDisplayableCode(props) && hasDisplayableName(props) &&
            (!activeLayer || boxLayers(props).includes(activeLayer));
    });

    renderDrawing(canvas);

    // Calculate canvas size to fit all boxes
    let maxX = 0, maxY = 0;

    visibleBoxes.forEach(box => {
        const props = box.properties || {};
        const frame = props.frame || [0, 0, 300, 200];
        const [x, y, w, h] = frame;

        // Create box element
        const boxEl = document.createElement('div');
        boxEl.className = 'box';
        boxEl.style.left = `${x}px`;
        boxEl.style.top = `${y}px`;
        boxEl.style.width = `${w}px`;
        boxEl.style.height = `${h}px`;
        boxEl.setAttribute('data-box-id', box.id);

        // Box name
        const nameEl = document.createElement('div');
        nameEl.className = 'box-name';
        nameEl.textContent = props.name || box.id;
        boxEl.appendChild(nameEl);

        if (typeof props.notes === 'string' && props.notes.trim()) {
            boxEl.classList.add('has-notes');
            const badge = document.createElement('div');
            badge.className = 'box-notes-badge';
            badge.title = 'This box has notes';
            badge.textContent = '¶';
            boxEl.appendChild(badge);
        }

        // Click handler to select box
        boxEl.addEventListener('click', (e) => {
            e.stopPropagation();
            selectBox(box, boxEl);
        });

        canvas.appendChild(boxEl);

        // Update canvas size
        maxX = Math.max(maxX, x + w);
        maxY = Math.max(maxY, y + h);
    });

    // Set canvas size
    canvas.style.width = `${Math.max(maxX + 100, window.innerWidth)}px`;
    canvas.style.height = `${Math.max(maxY + 100, window.innerHeight)}px`;
    if (connections) {
        connections.setAttribute('width', `${Math.max(maxX + 100, window.innerWidth)}`);
        connections.setAttribute('height', `${Math.max(maxY + 100, window.innerHeight)}`);
    }

    // Click on canvas to deselect
    if (!canvas.dataset.wired) {
        canvas.dataset.wired = '1';
        canvas.addEventListener('click', (e) => {
            if (document.getElementById('viewport').dataset.panMoved) return;
            if (e.target === canvas || e.target.closest('#drawing-layer')) {
                deselectBox();
            }
        });
        setupPanControls();
    }

    applyPanTransform();
    drawConnections();
}

function setupPanControls() {
    const viewport = document.getElementById('viewport');

    // Drag on empty canvas (or with the middle button, or Shift) to pan.
    viewport.addEventListener('mousedown', (e) => {
        const onEmpty = e.button === 0 && !e.target.closest('.box');
        if (e.button === 1 || (e.button === 0 && e.shiftKey) || onEmpty) {
            e.preventDefault();
            isPanning = true;
            startPanX = e.clientX - panX;
            startPanY = e.clientY - panY;
            viewport.dataset.panMoved = '';
            viewport.style.cursor = 'grabbing';
        }
    });

    viewport.addEventListener('mousemove', (e) => {
        if (isPanning) {
            const nx = e.clientX - startPanX;
            const ny = e.clientY - startPanY;
            if (Math.abs(nx - panX) + Math.abs(ny - panY) > 0) viewport.dataset.panMoved = '1';
            panX = nx;
            panY = ny;
            applyPanTransform();
        }
    });

    const endPan = () => {
        isPanning = false;
        viewport.style.cursor = 'default';
    };
    viewport.addEventListener('mouseup', endPan);
    viewport.addEventListener('mouseleave', endPan);

    // Wheel pans; Ctrl/pinch-wheel zooms about the cursor (as on Field's canvas).
    viewport.addEventListener('wheel', (e) => {
        e.preventDefault();
        if (e.ctrlKey || e.metaKey) {
            zoomAt(zoom * Math.exp(-e.deltaY * 0.01), e.clientX, e.clientY);
        } else {
            panX -= e.deltaX;
            panY -= e.deltaY;
            applyPanTransform();
        }
    }, { passive: false });

    document.addEventListener('keydown', (e) => {
        if (e.key === 'Escape') {
            deselectBox();
        }
        if (isEditorFocused()) return;
        const step = 50;
        if (e.key === 'ArrowLeft') { panX += step; applyPanTransform(); }
        else if (e.key === 'ArrowRight') { panX -= step; applyPanTransform(); }
        else if (e.key === 'ArrowUp') { panY += step; applyPanTransform(); }
        else if (e.key === 'ArrowDown') { panY -= step; applyPanTransform(); }
        else if (e.key === '=' || e.key === '+') zoomAt(zoom * 1.2, window.innerWidth / 2, window.innerHeight / 2);
        else if (e.key === '-') zoomAt(zoom / 1.2, window.innerWidth / 2, window.innerHeight / 2);
        else if (e.key === '0') fitCameraToContent();
        else if (e.key === 'd') toggleDrawing();
        else if (/^[1-9]$/.test(e.key)) {
            const bookmark = bookmarks.find(b => b.slot === Number(e.key));
            if (bookmark) jumpToBookmark(bookmark);
        }
    });
}

function clampZoom(z) {
    return Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, z));
}

/** Zoom to `z`, keeping the world point under screen (sx, sy) fixed. */
function zoomAt(z, sx, sy) {
    const next = clampZoom(z);
    const wx = (sx - panX) / zoom;
    const wy = (sy - panY) / zoom;
    zoom = next;
    panX = sx - wx * zoom;
    panY = sy - wy * zoom;
    applyPanTransform();
}

function applyPanTransform() {
    const transform = `translate(${panX}px, ${panY}px) scale(${zoom})`;
    const canvas = document.getElementById('canvas');
    const connections = document.getElementById('connections');
    if (canvas) canvas.style.transform = transform;
    if (connections) connections.style.transform = transform;
    const zoomEl = document.getElementById('zoom-readout');
    if (zoomEl) zoomEl.textContent = `${Math.round(zoom * 100)}%`;
}

/** The canvas area not covered by the open editor panel, in screen pixels. */
function visibleCanvasRect() {
    const panel = document.getElementById('editor-container');
    const right = panel && panel.classList.contains('visible') ? panel.getBoundingClientRect().left : window.innerWidth;
    return { x: 0, y: 0, w: Math.max(200, right), h: window.innerHeight };
}

/** Frame a world rectangle in the visible canvas, zooming out (never past 100% in) to fit. */
function frameWorldRect(rect, maxZoom = 1, margin = 60) {
    if (!rect || !(rect.w >= 0) || !(rect.h >= 0)) return;
    const view = visibleCanvasRect();
    const z = clampZoom(Math.min(maxZoom, (view.w - 2 * margin) / Math.max(rect.w, 1), (view.h - 2 * margin) / Math.max(rect.h, 1)));
    zoom = z;
    panX = view.x + (view.w - rect.w * z) / 2 - rect.x * z;
    panY = view.y + (view.h - rect.h * z) / 2 - rect.y * z;
    applyPanTransform();
}

function contentBounds() {
    let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
    const add = (r) => {
        if (!r) return;
        minX = Math.min(minX, r.x); minY = Math.min(minY, r.y);
        maxX = Math.max(maxX, r.x + r.w); maxY = Math.max(maxY, r.y + r.h);
    };
    visibleBoxes.forEach(box => {
        const [x, y, w, h] = (box.properties || {}).frame || [0, 0, 300, 200];
        add({ x, y, w, h });
    });
    if (showDrawing && drawingInfo) add(drawingInfo.bounds);
    return Number.isFinite(minX) ? { x: minX, y: minY, w: maxX - minX, h: maxY - minY } : null;
}

function fitCameraToContent() {
    const bounds = contentBounds();
    if (bounds) frameWorldRect(bounds, 1, 40);
}

/** Draw the root box's Excalidraw scene under the boxes, in world coordinates. */
function renderDrawing(canvas) {
    const scene = excalidrawSceneSvg(rootProperties(currentDocumentData || {}).drawing);
    drawingInfo = scene.count ? scene : null;
    if (!drawingInfo) return;
    const b = scene.bounds || { x: 0, y: 0, w: 0, h: 0 };
    const pad = 64;
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.id = 'drawing-layer';
    svg.setAttribute('class', 'drawing-layer');
    // The SVG's own box covers the scene; its viewBox keeps world coordinates.
    svg.style.left = `${b.x - pad}px`;
    svg.style.top = `${b.y - pad}px`;
    svg.setAttribute('width', String(b.w + 2 * pad));
    svg.setAttribute('height', String(b.h + 2 * pad));
    svg.setAttribute('viewBox', `${b.x - pad} ${b.y - pad} ${b.w + 2 * pad} ${b.h + 2 * pad}`);
    svg.innerHTML = `<g class="drawing-theme">${scene.markup}</g>`;
    svg.style.display = showDrawing ? '' : 'none';
    canvas.insertBefore(svg, canvas.firstChild);
}

function toggleDrawing(force) {
    showDrawing = typeof force === 'boolean' ? force : !showDrawing;
    const svg = document.getElementById('drawing-layer');
    if (svg) svg.style.display = showDrawing ? '' : 'none';
    const toggle = document.getElementById('drawing-toggle');
    if (toggle) toggle.checked = showDrawing;
}

/**
 * Restore a bookmark's view. A bookmark records the camera (`x`, `y` = translation, `zoom`) and,
 * when saved by a recent Field, the visible world rect (`viewX..viewH`), which is framed instead so
 * the same region shows in a window of a different size. A bookmark's selected box is selected.
 */
function jumpToBookmark(bookmark) {
    const pos = bookmark.scrollPosition || {};
    // Select first, so the view is framed in the canvas area the editor panel leaves.
    if (bookmark.restoreSelection !== false && bookmark.selectedBox) {
        const box = visibleBoxes.find(b => b.id === bookmark.selectedBox);
        const el = box && document.querySelector(`.box[data-box-id="${CSS.escape(box.id)}"]`);
        if (box && el) selectBox(box, el, false);
    }
    if (bookmark.restoreView !== false) {
        if (Number.isFinite(pos.viewW) && pos.viewW > 0 && Number.isFinite(pos.viewH)) {
            frameWorldRect({ x: pos.viewX, y: pos.viewY, w: pos.viewW, h: pos.viewH }, MAX_ZOOM, 0);
        } else {
            zoom = clampZoom(Number.isFinite(pos.zoom) ? pos.zoom : 1);
            panX = Number(pos.x) || 0;
            panY = Number(pos.y) || 0;
            applyPanTransform();
        }
    }
}

function findBoxByIdOrName(key) {
    if (!key) return null;
    return visibleBoxes.find(b => b.id === key) ||
        visibleBoxes.find(b => ((b.properties || {}).name || '').trim() === key) || null;
}

function applyUrlState(params) {
    const bookmarkSlot = Number(params.get('bookmark'));
    if (bookmarkSlot) {
        const bookmark = bookmarks.find(b => b.slot === bookmarkSlot);
        if (bookmark) jumpToBookmark(bookmark);
    }
    if (params.get('reader') === '1') {
        literateMode = true;
        const toggle = document.getElementById('literate-toggle');
        if (toggle) toggle.textContent = 'editor';
    }
    const box = findBoxByIdOrName(params.get('select'));
    if (box) {
        const el = document.querySelector(`.box[data-box-id="${CSS.escape(box.id)}"]`);
        if (el) selectBox(box, el, false);
        // Re-frame everything into the canvas area the editor panel leaves.
        if (!bookmarkSlot) fitCameraToContent();
    }
}

/** The document panel: zoom, drawing visibility, layers and bookmarks. */
function setupDocumentPanel(data) {
    const panel = document.getElementById('document-panel');
    if (!panel) return;
    panel.replaceChildren();
    // Collapsed by default: the controls stay one click (or a key) away
    // instead of sitting over the canvas.
    const toggle = document.createElement('button');
    toggle.type = 'button';
    toggle.className = 'doc-toggle';
    toggle.title = 'Zoom, drawing, layers and bookmarks';
    const body = document.createElement('div');
    body.className = 'doc-body';
    const setOpen = (open) => {
        panel.classList.toggle('open', open);
        toggle.textContent = open ? 'view ▾' : 'view ▸';
        toggle.setAttribute('aria-expanded', String(open));
    };
    toggle.addEventListener('click', (e) => { e.stopPropagation(); setOpen(!panel.classList.contains('open')); });
    panel.append(toggle, body);
    setOpen(false);
    const row = (cls) => {
        const el = document.createElement('div');
        el.className = `doc-row ${cls || ''}`;
        body.appendChild(el);
        return el;
    };
    const button = (parent, label, title, onClick) => {
        const el = document.createElement('button');
        el.type = 'button';
        el.textContent = label;
        if (title) el.title = title;
        el.addEventListener('click', (e) => { e.stopPropagation(); onClick(el); });
        parent.appendChild(el);
        return el;
    };

    const view = row('doc-view');
    button(view, '−', 'Zoom out (-)', () => zoomAt(zoom / 1.2, window.innerWidth / 2, window.innerHeight / 2));
    const readout = document.createElement('span');
    readout.id = 'zoom-readout';
    view.appendChild(readout);
    button(view, '+', 'Zoom in (+)', () => zoomAt(zoom * 1.2, window.innerWidth / 2, window.innerHeight / 2));
    button(view, 'fit', 'Fit everything (0)', () => fitCameraToContent());
    if (drawingInfo) {
        const label = document.createElement('label');
        label.title = 'Show the drawing layer (d)';
        const box = document.createElement('input');
        box.type = 'checkbox';
        box.id = 'drawing-toggle';
        box.checked = showDrawing;
        box.addEventListener('change', () => toggleDrawing(box.checked));
        label.append(box, document.createTextNode(` drawing (${drawingInfo.count})`));
        view.appendChild(label);
    }

    const layers = documentLayers(data.boxes || []);
    if (layers.length > 1) {
        const layerRow = row('doc-layers');
        const title = document.createElement('span');
        title.className = 'doc-label';
        title.textContent = 'layers';
        layerRow.appendChild(title);
        const chips = [];
        const choose = (name) => {
            activeLayer = name;
            chips.forEach(c => c.classList.toggle('active', (c.dataset.layer || null) === (name || null)));
            deselectBox();
            renderBoxes(currentBoxes);
        };
        const all = button(layerRow, 'all', 'Show every layer', () => choose(null));
        chips.push(all);
        layers.forEach(name => {
            const chip = button(layerRow, name, `Show only boxes on layer "${name}"`, () => choose(name));
            chip.dataset.layer = name;
            chips.push(chip);
        });
        chips.forEach(c => c.classList.toggle('active', (c.dataset.layer || null) === (activeLayer || null)));
    }

    if (bookmarks.length) {
        const bookmarkRow = row('doc-bookmarks');
        const title = document.createElement('span');
        title.className = 'doc-label';
        title.textContent = 'bookmarks';
        bookmarkRow.appendChild(title);
        bookmarks.forEach(b => {
            const name = b.name || `slot ${b.slot}`;
            button(bookmarkRow, b.slot ? `${b.slot} ${name}` : name, `Jump to "${name}"${b.slot ? ` (${b.slot})` : ''}`, () => jumpToBookmark(b));
        });
    }
    applyPanTransform();
}

function rectIntersectionParams(start, end, rect) {
    const [x, y, w, h] = rect;
    const xMin = x;
    const xMax = x + w;
    const yMin = y;
    const yMax = y + h;
    let t0 = 0;
    let t1 = 1;
    const dx = end.x - start.x;
    const dy = end.y - start.y;

    const tests = [
        { p: -dx, q: start.x - xMin },
        { p: dx, q: xMax - start.x },
        { p: -dy, q: start.y - yMin },
        { p: dy, q: yMax - start.y }
    ];

    for (const { p, q } of tests) {
        if (p === 0) {
            if (q < 0) return null;
        } else {
            const r = q / p;
            if (p < 0) {
                if (r > t1) return null;
                if (r > t0) t0 = r;
            } else {
                if (r < t0) return null;
                if (r < t1) t1 = r;
            }
        }
    }

    return { t0, t1 };
}

function isEditorFocused() {
    const active = document.activeElement;
    return active && active.closest('.monaco-editor-container');
}

function selectBox(box, boxEl, reveal = false) {
    // Deselect previous box
    if (selectedBox) {
        const prevBoxEl = document.querySelector(`.box[data-box-id="${selectedBox.id}"]`);
        if (prevBoxEl) {
            prevBoxEl.classList.remove('selected');
        }
    }

    // Select new box
    selectedBox = box;
    boxEl.classList.add('selected');

    // Show editor with box properties
    showEditorForBox(box);

    if (reveal) revealBox(box);
    drawConnections();
}

/** Pan (and zoom out if needed) so the box sits in the canvas area beside the editor panel. */
function revealBox(box) {
    const [x, y, w, h] = (box.properties || {}).frame || [0, 0, 300, 200];
    const view = visibleCanvasRect();
    const sx = x * zoom + panX;
    const sy = y * zoom + panY;
    const inside = sx >= view.x && sy >= view.y && sx + w * zoom <= view.x + view.w && sy + h * zoom <= view.y + view.h;
    if (!inside) frameWorldRect({ x, y, w, h }, zoom, 80);
}

function deselectBox() {
    if (selectedBox) {
        const boxEl = document.querySelector(`.box[data-box-id="${selectedBox.id}"]`);
        if (boxEl) {
            boxEl.classList.remove('selected');
        }
        selectedBox = null;
    }

    // Hide editor
    document.getElementById('editor-container').classList.remove('visible');

    drawConnections();
}

// Text properties that are never shown as tabs: structure, not content.
const NON_CONTENT_PROPERTIES = new Set(['name', 'notes', 'frame', 'children', 'parents', 'nested', 'nestedIn', '_tombstone',
    'layers', 'layerFrames', 'code_language', 'contents_language', 'default_property', 'templateId', 'source_id', 'kitRole',
    'drawing', 'bookmarks', 'panes', 'file_proxy_mode', 'file_proxy_path', 'file_proxy_original_path', 'file_proxy_mime', 'filename']);

const KNOWN_CODE_PROPERTIES = ['code', 'glsl', 'python', 'clojure', 'javascript', 'customization', 'vertex', 'vertex_fragment', 'vertex_s',
    'fragment', 'fragment_s', 'stackSL', 'rectSL', 'rectsl', 'msl', 'task', 'task_s', 'mesh', 'mesh_s', 'compute', 'compute_s',
    'raygen', 'raygen_s', 'miss', 'miss_s', 'closesthit', 'closesthit_s', 'anyhit', 'anyhit_s', 'intersection', 'intersection_s',
    'callable', 'callable_s', 'metadata', 'ai4_chat_history', 'ai4_session'];

/**
 * The properties a box shows as tabs: the known code properties, then any other property that
 * was saved as a sidecar (the parser records those in `__sidecars`), in that order. `notes` is
 * shown as Markdown above the tabs, not as a tab.
 */
function contentProperties(props = {}) {
    const sidecars = props.__sidecars || {};
    const shown = (key) => (typeof props[key] === 'string' && props[key].length > 0) || props[key] instanceof AfieldFileRef;
    const out = KNOWN_CODE_PROPERTIES.filter(shown);
    Object.keys(sidecars).forEach(key => {
        if (!out.includes(key) && !NON_CONTENT_PROPERTIES.has(key) && shown(key)) out.push(key);
    });
    return out;
}

function propertyText(props, property) {
    const value = props[property];
    if (typeof value === 'string') return value;
    if (value instanceof AfieldFileRef) return `// ${property} lives in the sidecar file ${value.afieldFile}, which was not found next to this document.`;
    return '';
}

function renderNotes(box) {
    const notesEl = document.getElementById('notes-view');
    if (!notesEl) return;
    const props = box.properties || {};
    const notes = typeof props.notes === 'string' ? props.notes : '';
    if (!notes.trim()) {
        notesEl.style.display = 'none';
        notesEl.innerHTML = '';
        return;
    }
    notesEl.style.display = 'block';
    notesEl.innerHTML = renderMarkdown(notes, { baseUrl: ednBaseUrl });
    notesEl.scrollTop = 0;
    // Colour fenced code once Monaco is available.
    if (typeof monaco !== 'undefined' && monaco.editor && monaco.editor.colorize) {
        notesEl.querySelectorAll('pre.md-code').forEach(async pre => {
            const lang = normalizeFenceLanguage(pre.dataset.lang || '');
            if (!lang) return;
            try {
                pre.firstChild.innerHTML = await monaco.editor.colorize(pre.textContent, lang, { tabSize: 4 });
            } catch (_) { /* leave plain */ }
        });
    }
}

function normalizeFenceLanguage(lang) {
    const map = { kt: 'kotlin', kts: 'kotlin', kotlin: 'kotlin', glsl: 'cpp', hlsl: 'cpp', slang: 'cpp', c: 'cpp', cpp: 'cpp',
        py: 'python', python: 'python', js: 'javascript', javascript: 'javascript', ts: 'typescript', json: 'json',
        yaml: 'yaml', yml: 'yaml', clj: 'clojure', clojure: 'clojure', sh: 'shell', bash: 'shell', md: 'markdown' };
    return map[String(lang).toLowerCase()] || null;
}

function showEditorForBox(box) {
    const container = document.getElementById('editor-container');
    const props = box.properties || {};

    const codeProperties = contentProperties(props);
    if (codeProperties.length > 0 && !codeProperties.includes(currentProperty)) {
        currentProperty = codeProperties[0];
    }

    const tabsContainer = document.getElementById('property-tabs');
    tabsContainer.innerHTML = '';

    const title = document.createElement('div');
    title.className = 'panel-title';
    title.textContent = props.name || box.id;
    tabsContainer.appendChild(title);

    if (codeProperties.length > 0) {
        codeProperties.forEach(prop => {
            const tab = document.createElement('div');
            tab.className = 'property-tab';
            tab.dataset.property = prop;
            if (prop === currentProperty) tab.classList.add('active');
            tab.textContent = prop;
            const sidecar = (props.__sidecars || {})[prop];
            if (sidecar) tab.title = sidecar.path;
            tab.addEventListener('click', () => {
                switchProperty(prop, box);
            });
            tabsContainer.appendChild(tab);
        });
    } else {
        currentProperty = null;
    }

    container.classList.add('visible');
    container.classList.toggle('no-code', codeProperties.length === 0);
    renderNotes(box);

    if (currentProperty) loadProperty(box, currentProperty);
    updateEditorVisibility();
}

function loadProperty(box, property) {
    const props = box.properties || {};
    const content = propertyText(props, property);
    const language = getLanguageForProperty(property, props);
    if (monacoEditor) {
        monaco.editor.setModelLanguage(monacoEditor.getModel(), language);
        monacoEditor.setValue(content);
    }
    renderLiterateView(content, language);
}

function switchProperty(property, box) {
    currentProperty = property;
    document.querySelectorAll('.property-tab').forEach(tab => {
        tab.classList.toggle('active', tab.dataset.property === property);
    });
    if (box) loadProperty(box, property);
}

function getLanguageForProperty(property, props = {}) {
    if (property === 'code' && typeof props.code_language === 'string') {
        const byBoxLanguage = { kotlin: 'kotlin', python: 'python', clojure: 'clojure', javascript: 'javascript',
            remotejs: 'javascript', glsl: 'cpp', forth: 'plaintext', datalog: 'plaintext', csound: 'plaintext' };
        const lang = byBoxLanguage[props.code_language.toLowerCase()];
        if (lang) return lang;
    }
    const languageMap = {
        'code': 'kotlin',
        'glsl': 'cpp',
        'python': 'python',
        'clojure': 'clojure',
        'javascript': 'javascript',
        'vertex': 'cpp',
        'vertex_fragment': 'cpp',
        'vertex_s': 'cpp',
        'fragment': 'cpp',
        'stackSL': 'stacksl',
        'rectSL': 'rectsl',
        'fragment_s': 'cpp',
        'task': 'cpp',
        'task_s': 'cpp',
        'mesh': 'cpp',
        'mesh_s': 'cpp',
        'compute': 'cpp',
        'compute_s': 'cpp',
        'raygen': 'cpp',
        'raygen_s': 'cpp',
        'miss': 'cpp',
        'miss_s': 'cpp',
        'closesthit': 'cpp',
        'closesthit_s': 'cpp',
        'anyhit': 'cpp',
        'anyhit_s': 'cpp',
        'intersection': 'cpp',
        'intersection_s': 'cpp',
        'callable': 'cpp',
        'callable_s': 'cpp',
        'rectsl': 'rectsl',
        'msl': 'cpp',
        'customization': 'javascript',
        'metadata': 'yaml',
        'ai4_chat_history': 'markdown',
        'ai4_session': 'json'
    };
    if (languageMap[property]) return languageMap[property];
    const sidecar = (props.__sidecars || {})[property];
    const ext = sidecar ? sidecar.path.split('.').pop().toLowerCase() : '';
    const byExtension = { kt: 'kotlin', glsl: 'cpp', slang: 'cpp', metal: 'cpp', js: 'javascript', py: 'python',
        md: 'markdown', yaml: 'yaml', yml: 'yaml', json: 'json', jsonl: 'json', clj: 'clojure' };
    return byExtension[ext] || (sidecar ? 'plaintext' : 'kotlin');
}

async function initializeMonaco() {
    return new Promise((resolve, reject) => {
        // Monaco's AMD loader (min/vs/loader.js) is a separate <script>. If it
        // failed to load (e.g. the viewer bundle isn't fully deployed), `require`
        // is undefined and its callback would otherwise never fire -- leaving the
        // viewer spinning. Guard both the missing-loader case and a loader that
        // never resolves so the box graph still renders read-only without an editor.
        if (typeof require === 'undefined') {
            reject(new Error('Monaco loader (min/vs/loader.js) is not available'));
            return;
        }
        let settled = false;
        const timer = setTimeout(() => {
            if (settled) return;
            settled = true;
            reject(new Error('Monaco editor timed out while loading'));
        }, 15000);
        require.config({ paths: { vs: './min/vs' } });
        require(['vs/editor/editor.main'], () => {
            if (settled) return;
            settled = true;
            clearTimeout(timer);
            try {
                monacoEditor = monaco.editor.create(document.getElementById('editor'), {
                    value: '',
                    language: 'kotlin',
                    theme: 'vs-dark',
                    readOnly: true,
                    minimap: { enabled: false },
                    scrollBeyondLastLine: false,
                    fontSize: 13,
                    fontFamily: '"Iosevka Web", monospace',
                    fontWeight: '300',
                    fontLigatures: '"liga" on, "calt" on, "dlig" on, "TXTR" on',
                    lineNumbers: 'on',
                    glyphMargin: false,
                    folding: true,
                    lineDecorationsWidth: 0,
                    lineNumbersMinChars: 3,
                    renderLineHighlight: 'all',
                    automaticLayout: true
                });
                resolve();
            } catch (error) {
                reject(error);
            }
        });
    });
}

function setupLiterateToggle() {
    const toggle = document.getElementById('literate-toggle');
    if (!toggle) return;
    toggle.addEventListener('click', () => {
        literateMode = !literateMode;
        toggle.textContent = literateMode ? 'editor' : 'reader';
        updateEditorVisibility();
        // Re-render current content to ensure view is fresh
        if (selectedBox && currentProperty) loadProperty(selectedBox, currentProperty);
    });
}

function updateEditorVisibility() {
    const editorEl = document.getElementById('editor');
    if (!editorEl || !literateViewEl) return;
    if (!currentProperty || (selectedBox && contentProperties(selectedBox.properties || {}).length === 0)) {
        editorEl.style.display = 'none';
        literateViewEl.style.display = 'none';
        return;
    }
    if (literateMode) {
        editorEl.style.display = 'none';
        literateViewEl.style.display = 'block';
    } else {
        editorEl.style.display = 'block';
        literateViewEl.style.display = 'none';
        if (monacoEditor) {
            monacoEditor.layout();
        }
    }
}

function parseLiterateSegments(text) {
    const lines = text.split(/\r?\n/);
    const segments = [];
    let current = { type: null, lines: [] };

    const pushCurrent = () => {
        if (current.type && current.lines.length) {
            segments.push({ type: current.type, text: current.lines.join('\n') });
        }
        current = { type: null, lines: [] };
    };

    const isCommentLine = (line) => {
        const trimmed = line.trim();
        return /^\/\//.test(trimmed) || /^#/.test(trimmed) || /^;/.test(trimmed);
    };

    // Track whether we're inside a Kotlin triple-quoted string. Lines inside
    // such a string are always code, even when they look like comments — an
    // embedded RectSL/StackSL program uses `#rectsl` headers and lone `;`
    // terminators, which would otherwise be yanked out as prose and desync the
    // code highlighting across the `"""` boundaries.
    let insideTriple = false;

    lines.forEach(line => {
        const startedInsideTriple = insideTriple;
        const tripleCount = (line.match(/"""/g) || []).length;
        if (tripleCount % 2 === 1) insideTriple = !insideTriple;

        const comment = !startedInsideTriple && isCommentLine(line);
        if (comment) {
            if (current.type !== 'comment') pushCurrent();
            current.type = 'comment';
            current.lines.push(line.replace(/^\s*(\/\/|#|;)/, '').trim());
        } else {
            if (current.type !== 'code') pushCurrent();
            current.type = 'code';
            current.lines.push(line);
        }
    });

    pushCurrent();
    return segments;
}

function escapeHtml(str) {
    return str
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

async function renderLiterateView(content, language) {
    if (!literateViewEl) return;
    const segments = parseLiterateSegments(content);
    const htmlParts = [];

    for (const segment of segments) {
        if (segment.type === 'comment') {
            const text = escapeHtml(segment.text);
            htmlParts.push(`<div class="lit-comment">${text}</div>`);
        } else {
            try {
                const highlighted = await monaco.editor.colorize(segment.text, language, { tabSize: 4 });
                htmlParts.push(`<pre class="lit-code-block"><code>${highlighted}</code></pre>`);
            } catch (e) {
                htmlParts.push(`<pre class="lit-code-block"><code>${escapeHtml(segment.text)}</code></pre>`);
            }
        }
    }

    literateViewEl.innerHTML = htmlParts.join('\n');
}

function normalizeBoxRef(ref) {
    if (ref == null) return null;
    if (typeof ref === 'string') return ref;
    if (typeof ref === 'object') {
        if (ref.id) return ref.id;
        if (ref.value) return ref.value;
        if (ref.tag && ref.value) return ref.value;
    }
    return null;
}

function boxCenter(box) {
    const props = box.properties || {};
    const frame = props.frame || [0, 0, 0, 0];
    const [x, y, w, h] = frame;
    return { x: x + w / 2, y: y + h / 2 };
}

function drawConnectionLine(parent, child, svg) {
    const parentProps = parent.properties || {};
    const childProps = child.properties || {};
    const pf = parentProps.frame || [0, 0, 0, 0];
    const cf = childProps.frame || [0, 0, 0, 0];
    const startCenter = boxCenter(parent);
    const endCenter = boxCenter(child);
    const dx = endCenter.x - startCenter.x;
    const dy = endCenter.y - startCenter.y;
    const len = Math.hypot(dx, dy) || 1;
    const dir = { x: dx / len, y: dy / len };

    const pParams = rectIntersectionParams(startCenter, endCenter, pf);
    const cParams = rectIntersectionParams(startCenter, endCenter, cf);

    const startT = pParams ? Math.min(Math.max(pParams.t1, 0), 1) : 0;
    const endT = cParams ? Math.min(Math.max(cParams.t0, 0), 1) : 1;

    const start = {
        x: startCenter.x + dir.x * len * startT,
        y: startCenter.y + dir.y * len * startT
    };
    const end = {
        x: startCenter.x + dir.x * len * endT,
        y: startCenter.y + dir.y * len * endT
    };
    const dxLine = end.x - start.x;
    const dyLine = end.y - start.y;
    const curve = 0.22;
    const c1x = start.x + dxLine * curve;
    const c1y = start.y + dyLine * curve * 0.25;
    const c2x = end.x - dxLine * curve;
    const c2y = end.y - dyLine * curve * 0.25;
    const isSelected = selectedBox && (selectedBox.id === parent.id || selectedBox.id === child.id);
    const color = isSelected ? 'var(--field-cyan)' : 'rgba(255,255,255,0.45)';
    const width = isSelected ? 1.6 : 1.1;
    const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
    path.setAttribute('d', `M ${start.x} ${start.y} C ${c1x} ${c1y}, ${c2x} ${c2y}, ${end.x} ${end.y}`);
    path.setAttribute('fill', 'none');
    path.setAttribute('stroke', color);
    path.setAttribute('stroke-width', width.toString());
    path.setAttribute('stroke-linecap', 'round');
    path.setAttribute('stroke-linejoin', 'round');
    path.setAttribute('opacity', isSelected ? '1.0' : '0.75');
    svg.appendChild(path);

    // Arrowhead at mid-curve
    const t = 0.5;
    const mid = cubicPoint(start, { x: c1x, y: c1y }, { x: c2x, y: c2y }, end, t);
    const tan = cubicTangent(start, { x: c1x, y: c1y }, { x: c2x, y: c2y }, end, t);
    const norm = Math.hypot(tan.x, tan.y) || 1;
    const dirTan = { x: tan.x / norm, y: tan.y / norm };
    const size = isSelected ? 7 : 6;
    const perp = { x: -dirTan.y, y: dirTan.x };
    const tip = { x: mid.x + dirTan.x * size, y: mid.y + dirTan.y * size };
    const baseOffset = size * 0.58;
    const baseHalf = size * 0.52;
    const left = { x: mid.x - dirTan.x * baseOffset + perp.x * baseHalf, y: mid.y - dirTan.y * baseOffset + perp.y * baseHalf };
    const right = { x: mid.x - dirTan.x * baseOffset - perp.x * baseHalf, y: mid.y - dirTan.y * baseOffset - perp.y * baseHalf };
    const arrow = document.createElementNS('http://www.w3.org/2000/svg', 'path');
    arrow.setAttribute('d', `M ${tip.x} ${tip.y} L ${left.x} ${left.y} L ${right.x} ${right.y} Z`);
    arrow.setAttribute('fill', color);
    arrow.setAttribute('opacity', isSelected ? '1.0' : '0.75');
    svg.appendChild(arrow);
}

function drawConnections() {
    const svg = document.getElementById('connections');
    if (!svg) return;
    svg.innerHTML = '';
    if (!visibleBoxes || visibleBoxes.length === 0) return;

    const idMap = new Map();
    visibleBoxes.forEach(b => idMap.set(b.id, b));

    visibleBoxes.forEach(child => {
        const props = child.properties || {};
        const parents = props.parents;
        if (!Array.isArray(parents)) return;
        parents.forEach(p => {
            const pid = normalizeBoxRef(p);
            if (!pid) return;
            const parent = idMap.get(pid);
            if (!parent) return;
            drawConnectionLine(parent, child, svg);
        });
    });
}

function makeHostRequestId(prefix) {
    return `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

function requestFieldHost(messageType, payload, options = {}) {
    if (window.top === window) {
        return Promise.reject(new Error('not embedded in Field'));
    }

    const messageId = makeHostRequestId(messageType);
    const responseType = `${messageType}-response`;
    const timeoutMs = options.timeoutMs || 1800;

    return new Promise((resolve, reject) => {
        let settled = false;

        const cleanup = () => {
            window.removeEventListener('message', onMessage);
            window.clearTimeout(timer);
        };

        const onMessage = (event) => {
            const data = event.data || {};
            if (data.type !== responseType || data.messageId !== messageId) {
                return;
            }
            settled = true;
            cleanup();
            if (data.ok === false) {
                reject(new Error(data.error || `${messageType} failed`));
                return;
            }
            resolve(data.result);
        };

        const timer = window.setTimeout(() => {
            if (settled) return;
            cleanup();
            reject(new Error('Field host request timed out'));
        }, timeoutMs);

        window.addEventListener('message', onMessage);
        window.top.postMessage({ type: messageType, messageId, ...payload }, '*');
    });
}

const DEFAULT_FIELD_HTTPS_PORT = 9092;
// Server.kt `loopbackHttpPortFor`: the plain-HTTP loopback connector sits at the
// HTTPS port + 10000 unless AFIELD_HTTP_PORT overrides it.
const FIELD_LOOPBACK_HTTP_PORT_OFFSET = 10000;

function readPortParam(params, name) {
    const raw = params.get(name);
    if (!raw) return null;
    const port = Number.parseInt(raw, 10);
    return Number.isInteger(port) && port >= 1 && port <= 65535 ? port : null;
}

/**
 * Base URLs to try, in order, when importing into a local Field.
 *
 * Field's HTTPS certificate is self-signed, so a cross-origin `fetch` to
 * https://localhost:9092 fails outright with ERR_CERT_AUTHORITY_INVALID unless
 * that browser profile has already accepted the exception — and a fetch cannot
 * show the interstitial that would let the user accept it. When this page is
 * itself served over plain HTTP we can talk to Field's loopback HTTP connector
 * instead and sidestep the certificate entirely. From an HTTPS page that is not
 * an option (mixed content), so only the HTTPS base is offered there.
 */
function fieldImportBases() {
    const params = new URLSearchParams(window.location.search);
    const httpsPort = readPortParam(params, 'fieldPort') || DEFAULT_FIELD_HTTPS_PORT;
    const httpPort = readPortParam(params, 'fieldHttpPort') || httpsPort + FIELD_LOOPBACK_HTTP_PORT_OFFSET;

    const bases = [];
    if (window.location.protocol !== 'https:' && httpPort <= 65535) {
        bases.push(`http://127.0.0.1:${httpPort}`);
    }
    bases.push(`https://localhost:${httpsPort}`);
    return bases;
}

async function importOfflineDocViaLocalhost(recommendedName, ednText) {
    const bases = fieldImportBases();
    const body = JSON.stringify({
        recommendedName,
        ednText,
        sourceUrl: window.location.href
    });

    const unreachable = [];
    for (const localBase of bases) {
        let response;
        try {
            response = await fetch(`${localBase}/api/import-offline-doc`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body
            });
        } catch (networkError) {
            // Refused connection, rejected certificate, blocked by the browser —
            // all surface as an opaque TypeError. Try the next base.
            unreachable.push(localBase);
            console.info(`offline-viewer import unreachable at ${localBase}`, networkError);
            continue;
        }

        if (!response.ok) {
            const detail = await response.text().catch(() => '');
            throw new Error(`${localBase} returned HTTP ${response.status} ${response.statusText}${detail ? `: ${detail}` : ''}`);
        }

        return {
            localBase,
            result: await response.json()
        };
    }

    const httpsBase = bases.find((base) => base.startsWith('https://'));
    throw new Error(
        `could not reach Field·4 at ${unreachable.join(' or ')}. Is Field running? ` +
        `If it is, open ${httpsBase}/health once and accept the self-signed certificate.`
    );
}

function setupOpenLocalButton(fileUrl, ednText, data) {
    const button = document.getElementById('open-local');
    button.style.display = 'inline-block';
    button.href = '#';
    button.title = 'Send to Field·4 desktop application';

    let busy = false;

    button.addEventListener('click', async (e) => {
        e.preventDefault();
        if (busy) return;
        busy = true;
        const originalLabel = button.textContent;
        button.textContent = 'Sending to Field·4...';
        try {
            const recommendedName = deriveRecommendedName(fileUrl);
            let localBase = fieldImportBases()[0];
            let result = null;
            let hostOpened = false;

            try {
                result = await requestFieldHost('field-import-offline-doc', {
                    recommendedName,
                    ednText,
                    sourceUrl: window.location.href,
                    openInHost: true
                });
                hostOpened = true;
            } catch (hostError) {
                console.info('offline-viewer host import unavailable, falling back to localhost import', hostError);
                const fallback = await importOfflineDocViaLocalhost(recommendedName, ednText);
                localBase = fallback.localBase;
                result = fallback.result;
            }

            const localEdnUrl = result.projectUrl || `${localBase}/${encodeURIComponent(result.documentName)}/metadata.edn`;
            const openUrl = result.openUrl || `${localBase}/index.html?file=${encodeURIComponent(result.documentName)}`;

            // Update state so further loads use the local copy
            ednFileUrl = localEdnUrl;
            ednFileText = ednText;
            if (data) {
                showDocumentInfo(data, localEdnUrl);
            }

            // Update URL bar so this offline page now points at the local copy
            window.history.pushState({}, '', `?file=${encodeURIComponent(localEdnUrl)}`);

            // Convert button back into a normal link to Field·4
            button.href = openUrl;
            button.target = '_blank';
            button.rel = 'noopener';
            button.textContent = 'Open in Field·4';
            button.title = `Open ${result.documentName} in Field·4`;

            if (hostOpened) {
                button.textContent = 'Opening in Field·4...';
                return;
            }

            // Launch Field·4 in a new tab when we imported over localhost ourselves.
            window.open(openUrl, '_blank', 'noopener');
        } catch (error) {
            showError(`Failed to send to Field·4: ${error.message}`);
            button.textContent = originalLabel;
        } finally {
            busy = false;
        }
    });
}

// Field stores each document as `<name>/metadata.edn` (SavingContext.METADATA_EDN),
// so the last path segment of a document URL is a constant and the directory above
// it carries the name. Fall back to the file stem only for standalone .edn files.
function nameFromPathSegments(segments) {
    if (segments.length === 0) return null;
    const last = segments[segments.length - 1];
    if (last.toLowerCase() === 'metadata.edn' && segments.length > 1) {
        const parent = segments[segments.length - 2];
        if (parent) return parent;
    }
    return last.replace(/\.edn$/i, '') || null;
}

function deriveRecommendedName(fileUrl) {
    try {
        const url = new URL(fileUrl, window.location.href);
        const parts = url.pathname.split('/').filter(Boolean);
        return nameFromPathSegments(parts) || 'offline-document';
    } catch (_e) {
        // Fallback for plain file names
        const parts = fileUrl.split('/').filter(Boolean);
        return nameFromPathSegments(parts) || 'offline-document';
    }
}

function showError(message) {
    const loading = document.getElementById('loading');
    const error = document.getElementById('error');

    loading.style.display = 'none';
    error.style.display = 'block';
    error.replaceChildren();
    const heading = document.createElement('h3');
    heading.textContent = 'Error';
    const detail = document.createElement('p');
    detail.textContent = String(message);
    const hint = document.createElement('p');
    hint.style.cssText = 'margin-top: 16px; font-size: 12px; opacity: 0.7;';
    hint.textContent = 'Make sure the EDN file exists and is accessible from this page.';
    error.append(heading, detail, hint);
}

function showDocumentInfo(data, fileUrl, sidecarStatus = {}) {
    const banner = document.querySelector('.offline-banner');
    const fileName = fileUrl.split('/').pop();
    const boxCount = data.boxes ? data.boxes.filter(b => b.id !== 'root' && !(b.properties || {})._tombstone).length : 0;
    const version = data.version || 'unknown';

    const summary = document.createElement('span');
    summary.style.cssText = 'opacity: 0.7; font-size: 11px;';
    const docName = deriveRecommendedName(fileUrl);
    const notesCount = data.boxes ? data.boxes.filter(b => {
        const p = b.properties || {};
        return !p._tombstone && typeof p.notes === 'string' && p.notes.trim();
    }).length : 0;
    const parts = [`${docName} • ${boxCount} box${boxCount !== 1 ? 'es' : ''}`];
    if (notesCount) parts.push(`${notesCount} with notes`);
    if (drawingInfo) parts.push(`drawing: ${drawingInfo.count} element${drawingInfo.count !== 1 ? 's' : ''}`);
    parts.push(`v${version}`);
    if (sidecarStatus.missing) parts.push(`${sidecarStatus.missing} sidecar${sidecarStatus.missing !== 1 ? 's' : ''} missing`);
    if (sidecarStatus.refreshed) parts.push(`${sidecarStatus.refreshed} read from newer sidecar files`);
    summary.textContent = parts.join(' • ');
    summary.title = fileName;
    banner.replaceChildren(summary);
}

// Handle window resize
if (typeof window !== 'undefined') window.addEventListener('resize', () => {
    if (monacoEditor) {
        monacoEditor.layout();
    }
});

function setupHelpOverlay() {
    // Add help overlay that shows on ? key
    let helpVisible = false;

    const helpOverlay = document.createElement('div');
    helpOverlay.id = 'help-overlay';
    helpOverlay.style.cssText = `
        position: fixed;
        top: 0;
        left: 0;
        width: 100vw;
        height: 100vh;
        background: rgba(0, 0, 0, 0.9);
        z-index: 1000;
        display: none;
        align-items: center;
        justify-content: center;
        backdrop-filter: blur(10px);
    `;

    helpOverlay.innerHTML = `
        <div style="max-width: 620px; padding: 28px; background: rgba(32, 32, 32, 0.92); border-radius: 12px; border: 1.5px solid var(--field-cyan);">
            <h2 style="margin: 0 0 12px 0; color: var(--field-cyan); font-size: 26px;">Offline Viewer Help</h2>
            <div style="line-height: 1.6; color: #e1ecff;">
                <p style="margin: 0 0 8px 0;"><strong>Navigation</strong></p>
                <p style="margin: 0 0 6px 0;">• <kbd>Click box</kbd> — Select box and view code</p>
                <p style="margin: 0 0 6px 0;">• <kbd>Drag</kbd> empty canvas, <kbd>Middle mouse</kbd> or <kbd>Shift + Drag</kbd> — Pan canvas</p>
                <p style="margin: 0 0 6px 0;">• <kbd>Mouse wheel</kbd> — Scroll canvas; <kbd>Ctrl/pinch + wheel</kbd> — Zoom about the cursor</p>
                <p style="margin: 0 0 6px 0;">• <kbd>+</kbd> / <kbd>-</kbd> zoom, <kbd>0</kbd> fit everything, <kbd>d</kbd> show/hide the drawing</p>
                <p style="margin: 0 0 6px 0;">• <kbd>1</kbd>–<kbd>9</kbd> — Jump to a bookmark</p>
                <p style="margin: 0 0 6px 0;">• <kbd>Arrow keys</kbd> — Pan canvas (when editor not focused)</p>
                <p style="margin: 0 0 12px 0;">• <kbd>Escape</kbd> — Deselect box and hide editor</p>
                <p style="margin: 12px 0 8px 0;"><strong>Features</strong></p>
                <p style="margin: 0 0 6px 0;">• A box marked ¶ has notes, shown as Markdown above its code</p>
                <p style="margin: 0 0 6px 0;">• Click property tabs to switch between code, shaders and other sidecars</p>
                <p style="margin: 0 0 6px 0;">• Monaco editor provides syntax highlighting</p>
                <p style="margin: 0 0 16px 0;">• Click "Open in Field·4" to launch in an already running desktop app</p>
                <p style="margin: 0; opacity: 0.8; font-size: 13px;">
                    This is a <b>read-only</b> documentation mode. To edit or execute code, launch Field·4 and click on the link in the top left.
                </p>
            </div>
            <div style="text-align: center; margin-top: 18px;">
                <button style="padding: 9px 22px; background: var(--field-cyan); border: none; border-radius: 6px; color: #000; cursor: pointer; font-size: 14px; font-family: inherit;">
                    Close (Esc)
                </button>
            </div>
        </div>
    `;

    document.body.appendChild(helpOverlay);

    const button = helpOverlay.querySelector('button');
    button.addEventListener('click', () => {
        helpOverlay.style.display = 'none';
        helpVisible = false;
    });

    helpOverlay.addEventListener('click', (e) => {
        if (e.target === helpOverlay) {
            helpOverlay.style.display = 'none';
            helpVisible = false;
        }
    });

    document.addEventListener('keydown', (e) => {
        // Don't process help keys if user is typing in an input field
        const isTyping = document.activeElement &&
                        (document.activeElement.tagName === 'INPUT' ||
                         document.activeElement.tagName === 'TEXTAREA' ||
                         document.activeElement.classList.contains('monaco-mouse-cursor-text'));

        if ((e.key === '?' || e.key === 'h') && !isTyping) {
            e.preventDefault();
            helpOverlay.style.display = helpVisible ? 'none' : 'flex';
            helpVisible = !helpVisible;
        }
        if (e.key === 'Escape' && helpVisible) {
            e.preventDefault();
            helpOverlay.style.display = 'none';
            helpVisible = false;
        }
    });
}

// Node tests (tools/test_offline_viewer_render.js) exercise the pure renderers.
if (typeof module !== 'undefined' && module.exports) {
    module.exports = { renderMarkdown, excalidrawElements, excalidrawElementSvg, excalidrawSceneSvg, excalidrawBounds, readBookmarks, boxLayers, contentProperties };
}
