/** Capture the rendered output boundary. Live transports and original scripts never enter the export. */
import { zipFiles } from './publication-zip.js';
import { prepareThreePublication, threePublicationDocument } from './publication-three.js';

const literal = value => JSON.stringify(value, (_, v) => ArrayBuffer.isView(v) ? Array.from(v) : v).replace(/</g, '\\u003c');
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
const MAX_NODES = 30000;
const MAX_ASSET_BYTES = 32 * 1024 * 1024;

async function fetchBytes(url) {
    const response = await fetch(url, {signal: AbortSignal.timeout(15000)});
    if (!response.ok) throw new Error(`${url}: HTTP ${response.status}`);
    const bytes = new Uint8Array(await response.arrayBuffer());
    if (bytes.length > MAX_ASSET_BYTES) throw new Error(`Asset exceeds 32 MB: ${url}`);
    return {bytes, type:response.headers.get('content-type')?.split(';')[0] || 'application/octet-stream'};
}
function dataUrl(bytes, type) {
    const parts = [];
    for (let i = 0; i < bytes.length; i += 8192) parts.push(String.fromCharCode(...bytes.subarray(i, i + 8192)));
    return `data:${type};base64,${btoa(parts.join(''))}`;
}
function failure(ctx, text) { ctx.warnings.add(text); }
function notice(doc, text) {
    const node = doc.createElement('div'); node.textContent = text;
    node.style.cssText = 'padding:12px;color:#bbb;background:#111;border:1px solid #444;font:12px monospace';
    return node;
}
async function embedUrl(raw, base, ctx) {
    if (!raw || raw.startsWith('#') || raw.startsWith('data:')) return raw;
    const url = new URL(raw, base).href;
    const parsed = new URL(url), origin = new URL(base);
    if (parsed.hash && parsed.origin === origin.origin && parsed.pathname === origin.pathname && parsed.search === origin.search) return parsed.hash;
    if (!ctx.resources.has(url)) ctx.resources.set(url, fetchBytes(url).then(({bytes,type}) => dataUrl(bytes,type)));
    return ctx.resources.get(url);
}
async function freezeCss(css, base, ctx) {
    const matches = [...css.matchAll(/url\(\s*["']?([^"')]+)["']?\s*\)/g)];
    for (const match of matches) {
        if (Date.now() > ctx.deadline) throw new Error('Output capture exceeded 30 seconds');
        try { css = css.replace(match[0], `url("${await embedUrl(match[1], base, ctx)}")`); }
        catch (error) { failure(ctx, `Image omitted: ${error.message}`); css = css.replace(match[0], 'none'); }
    }
    return css;
}

/** Copies computed presentation and current values, including content inside same-origin iframes. */
async function freezeNode(node, ctx, depth = 0) {
    if (++ctx.nodes > MAX_NODES) throw new Error('Output exceeds the 30,000 element capture limit');
    if (Date.now() > ctx.deadline) throw new Error('Output capture exceeded 30 seconds');
    if (node.nodeType === 3) return node.cloneNode();
    if (node.nodeType !== 1) return null;
    const doc = node.ownerDocument, win = doc.defaultView, tag = node.localName.toLowerCase();
    if (['script','style','link','meta','base','object','embed','noscript'].includes(tag)) return null;
    if (tag === 'iframe') {
        if (depth >= 4) { failure(ctx, 'Nested iframe omitted (depth limit)'); return notice(doc, 'Nested frame omitted'); }
        try {
            const child = node.contentDocument;
            if (!child?.body || !child.defaultView || child.readyState === 'loading') throw new Error('frame has not loaded or is from another origin');
            const frame = doc.createElement('iframe');
            frame.setAttribute('sandbox', 'allow-scripts allow-downloads'); frame.setAttribute('title', node.title || 'Captured output');
            frame.style.cssText = `border:0;width:${node.clientWidth || 640}px;height:${node.clientHeight || 420}px;display:block;background:#000`;
            const viewer = child.defaultView.FieldViewer;
            if (viewer) {
                try {
                    if (!viewer.snapshotForPublication || !ctx.three) throw new Error('Rebuild Field to export interactive Three.js views');
                    const snapshot = viewer.snapshotForPublication();
                    frame.srcdoc = threePublicationDocument(snapshot,ctx.three,ctx.fontCss);
                    ctx.kinds.add('three');
                } catch (error) {
                    // A still fallback contains only the real canvas image, never inert viewer controls.
                    const image = doc.createElement('img'); image.src = viewer.snapshotImage();
                    image.alt = 'Three.js view at publication time'; image.style.cssText='display:block;width:100%;height:100%;object-fit:contain';
                    image.style.background = child.defaultView.getComputedStyle(child.querySelector('#canvas-wrap') || child.body).backgroundColor;
                    frame.srcdoc = '<!doctype html><body style="margin:0;background:#000">' + image.outerHTML;
                    ctx.kinds.add('three-image'); failure(ctx,`Three.js still image: ${error.message}`);
                }
                return frame;
            }
            frame.srcdoc = await freezeDocument(child.body, ctx, depth + 1);
            if (/\/(h2-explorer|tree|browse)(\?|$)/.test(node.getAttribute('src') || '')) {
                failure(ctx, 'Lazy inspector: only the materialized rows and current view are captured');
            }
            return frame;
        } catch (error) {
            failure(ctx, `Frame omitted: ${error.message}`); return notice(doc, 'Frame could not be captured');
        }
    }
    if (node.classList.contains('js-plotly-plot') && Array.isArray(node.data)) {
        const needsExternalData = (node.layout?.images || []).some(image => image.source && !image.source.startsWith('data:')) ||
            node.data.some(trace => /map|geo|choropleth/.test(trace.type || '') || (typeof trace.source === 'string' && !trace.source.startsWith('data:')));
        if (needsExternalData) {
            const image = doc.createElement('img');
            image.src = await win.Plotly.toImage(node,{format:'png',width:node.clientWidth,height:node.clientHeight});
            image.alt = 'Plotly view at publication time'; image.style.maxWidth = '100%';
            ctx.kinds.add('plotly-image');
            failure(ctx, 'Plotly chart captured as an image because it depends on external image or map data');
            return image;
        }
        const id = `published-plot-${ctx.plots.length}`, placeholder = doc.createElement('div');
        placeholder.id = id; placeholder.style.cssText = `width:${node.clientWidth || 640}px;height:${node.clientHeight || 420}px;max-width:100%`;
        // Copy now: an asynchronous publication must not follow later Plotly updates.
        ctx.plots.push(JSON.parse(literal({id, data:node.data, layout:node.layout || {}})));
        ctx.kinds.add('plotly'); return placeholder;
    }
    if (tag === 'canvas') {
        try {
            const image = doc.createElement('img');
            const viewer = win.FieldViewer;
            if (!viewer?.snapshotImage && !node.getContext('2d')) throw new Error('This WebGL canvas needs a publication capture adapter');
            image.src = viewer?.snapshotImage && node === doc.querySelector('#canvas-wrap canvas')
                ? viewer.snapshotImage() : node.toDataURL('image/png');
            image.style.cssText = `width:${node.clientWidth || node.width}px;height:${node.clientHeight || node.height}px;max-width:100%`;
            image.alt = viewer ? 'Three.js view at publication time' : 'Canvas at publication time';
            ctx.kinds.add(viewer ? 'three-image' : 'canvas-image');
            return image;
        } catch (error) { failure(ctx, `Canvas omitted: ${error.message}`); return notice(doc, 'Canvas could not be captured'); }
    }
    if (tag === 'video' || tag === 'audio') {
        failure(ctx, 'Live media omitted'); return notice(doc, 'Live media is not included in this snapshot');
    }
    const clone = node.cloneNode(false);
    for (const attr of [...clone.attributes]) {
        const name = attr.name.toLowerCase();
        if (name.startsWith('on') || name.startsWith('data-') || ['src','srcset','href','xlink:href','action','formaction','autofocus','contenteditable','nonce'].includes(name)) clone.removeAttribute(attr.name);
    }
    const computed = win.getComputedStyle(node);
    clone.removeAttribute('style');
    for (const property of computed) {
        const value = computed.getPropertyValue(property);
        clone.style.setProperty(property, value.includes('url(') ? await freezeCss(value, doc.baseURI, ctx) : value);
    }
    clone.style.animation = 'none'; clone.style.transition = 'none'; clone.style.caretColor = 'transparent';
    if (tag === 'a') {
        const href = node.getAttribute('href') || '';
        if (/^https?:\/\//.test(href) && new URL(href).origin !== new URL(doc.baseURI).origin) { clone.setAttribute('href', href); clone.setAttribute('target','_blank'); clone.setAttribute('rel','noopener noreferrer'); }
        else clone.style.cursor = 'default';
    }
    if (tag === 'img' || (tag === 'image' && node.namespaceURI === 'http://www.w3.org/2000/svg')) {
        try {
            const src = node.currentSrc || node.getAttribute('src') || node.getAttribute('href') || node.getAttribute('xlink:href');
            if (src) clone.setAttribute(tag === 'img' ? 'src' : 'href', await embedUrl(src, doc.baseURI, ctx));
        } catch (error) { failure(ctx, `Image omitted: ${error.message}`); return notice(doc, 'Image could not be captured'); }
    }
    if (tag === 'use') {
        const href = node.getAttribute('href') || node.getAttribute('xlink:href');
        if (href?.startsWith('#')) clone.setAttribute('href', href);
        else if (href) failure(ctx, 'External SVG reference omitted');
    }
    if (['input','textarea','select','button'].includes(tag)) {
        clone.disabled = true;
        clone.style.cursor = 'default';
        if (tag === 'input') { clone.setAttribute('value', node.value); if (node.checked) clone.setAttribute('checked',''); else clone.removeAttribute('checked'); }
        if (tag === 'textarea') { clone.textContent = node.value; return clone; }
    }
    if (tag === 'option') { if (node.selected) clone.setAttribute('selected',''); else clone.removeAttribute('selected'); }
    if (node.scrollTop || node.scrollLeft) { clone.setAttribute('data-frozen-scroll', `${node.scrollLeft},${node.scrollTop}`); }
    if (tag === 'svg') ctx.kinds.add('svg');
    for (const child of node.childNodes) { const frozen = await freezeNode(child, ctx, depth); if (frozen) clone.append(frozen); }
    return clone;
}

async function freezeDocument(root, parent, depth = 0) {
    // Each iframe owns its plot IDs and initialization; resource cache and warnings are shared.
    const ctx = {...parent, plots:[]};
    const frozen = await freezeNode(root, ctx, depth);
    parent.nodes = ctx.nodes;
    frozen.style.margin = '0'; frozen.style.transform = 'none';
    const plotScripts = ctx.plots.length ? '<script src="../plotly-2.24.1.min.js"></script>' : '';
    const initialize = `Promise.resolve().then(()=>Promise.all(${literal(ctx.plots)}.map(p=>Plotly.newPlot(p.id,p.data,p.layout,{responsive:true,displaylogo:false})))).then(()=>{document.querySelectorAll('[data-frozen-scroll]').forEach(n=>{const [x,y]=n.dataset.frozenScroll.split(',').map(Number);n.scrollTo(x,y)});document.documentElement.dataset.captureReady='true'}).catch(e=>{document.documentElement.dataset.captureError=e.message;document.body.append(document.createTextNode('Plot could not be loaded: '+e.message))})`;
    return '<!doctype html><html><head><meta charset="utf-8"><meta name="color-scheme" content="dark">' +
        '<meta http-equiv="Content-Security-Policy" content="default-src \'none\'; img-src data: blob:; font-src data:; style-src \'unsafe-inline\'; script-src \'self\' \'unsafe-inline\' blob:; connect-src \'none\'; base-uri \'none\'; form-action \'none\'">' +
        `<style>${parent.fontCss}html,body{margin:0;background:#000;color:#ddd;font-family:'Iosevka Web',monospace}</style></head><body>` +
        frozen.outerHTML + plotScripts + `<script>${initialize}</script></body></html>`;
}

async function waitForContent(root, deadline) {
    const frames = [...root.querySelectorAll('iframe')];
    for (const frame of frames) {
        frame.loading = 'eager';
        let ready = false;
        while (Date.now() < deadline) {
            try {
                const doc = frame.contentDocument, viewer = frame.contentWindow?.FieldViewer;
                const status = doc?.querySelector('#status')?.textContent.trim() || '';
                if (viewer?.captureStatus().error) throw new Error(viewer.captureStatus().error);
                if (doc?.readyState === 'complete' && doc.body?.children.length && (!viewer || viewer.captureStatus().ready) && !/^(connecting|loading)/i.test(status)) {ready = true; break;}
                if (!doc) {ready = true; break;} // Cross-origin content is reported by freezeNode.
            } catch (error) {
                if (error.name === 'SecurityError') {ready = true; break;}
                throw new Error(`Output frame is not ready: ${error.message}`);
            }
            await pause(60);
        }
        if (!ready) throw new Error('Output frame did not finish loading before publication');
    }
    await Promise.race([root.ownerDocument.fonts?.ready || Promise.resolve(), pause(1000)]);
}

/** Each channel gets an isolated HTML file and explicit fidelity/warning information. */
export async function captureOutputs(sources, {fontCss = '', three = null, progress = () => {}} = {}) {
    // Some panels are installed directly by frontend tools or outlive the server replay budget.
    sources = [...sources];
    for (const box of document.querySelectorAll('.box')) {
        if (!box.uid) continue;
        for (const panel of box.querySelectorAll('.outputArea .disclosure')) {
            const channel = panel.__disclosureId;
            if (typeof channel === 'string' && !sources.some(s => s.uid === box.uid && s.channel === channel)) sources.push({uid:box.uid,channel});
        }
    }
    const outputs = [], files = new Map(), resources = new Map();
    for (let i = 0; i < sources.length; i++) {
        const source = sources[i]; progress(`Capturing ${i + 1}/${sources.length}: ${source.channel}`);
        const box = [...document.querySelectorAll('.box')].find(n => n.uid === source.uid);
        const disclosure = [...(box?.querySelectorAll('.disclosure') || [])].find(n => n.__disclosureId === source.channel);
        const api = box?.querySelector('.disclosure-group')?.__disclosureAPI;
        const wasOpen = api?.isOpen(source.channel);
        const root = disclosure?.querySelector('.disclosure-content');
        const entry = {uid:source.uid, channel:source.channel, open:!!wasOpen, warnings:[], kinds:[]};
        if (!root) {
            entry.warnings.push('No rendered output in this browser. Open the document and render this channel before publishing.');
            outputs.push(entry); continue;
        }
        try {
            api?.open(source.channel);
            await waitForContent(root, Date.now() + 12000);
            entry.width = Math.max(160, Math.min(4096, root.offsetWidth || 640));
            entry.height = Math.max(40, Math.min(8192, root.offsetHeight || 420));
            const ctx = {resources, warnings:new Set(), kinds:new Set(), nodes:0, deadline:Date.now() + 30000, fontCss, three};
            const html = await freezeDocument(root, ctx);
            entry.path = `outputs/${i}.html`; entry.kinds = [...ctx.kinds]; entry.warnings = [...ctx.warnings];
            if (root.offsetWidth > 4096 || root.offsetHeight > 8192) entry.warnings.push('Output viewport limited to 4096 × 8192 pixels');
            files.set(entry.path, html);
        } catch (error) { entry.warnings.push(error.message); }
        finally { if (!wasOpen) api?.close(source.channel); }
        outputs.push(entry);
    }
    return {outputs, files};
}

/** Build a relocatable static site; callers may save the Blob or inspect its files before download. */
export async function buildPublication(source, {progress = () => {}} = {}) {
    const files = new Map();
    progress('Bundling the viewer and local fonts');
    // Fetch in small batches rather than issuing hundreds of simultaneous requests for Monaco.
    for (let i = 0; i < source.assets.length; i += 8) {
        await Promise.all(source.assets.slice(i,i+8).map(async path => {
            const {bytes} = await fetchBytes(new URL(path, location.href)); files.set(path, bytes);
        }));
    }
    const decoder = new TextDecoder();
    const css = decoder.decode(files.get('iosevka.css')).replace(/@font-face\s*\{[^}]+\}/g, face => {
        const path = face.match(/url\(['"]?([^)'" ]+)/)?.[1];
        return path && files.has(path) ? face : '';
    });
    files.set('iosevka.css', css);
    const font = files.get('woff2/iosevka-semicondensedlight.woff2');
    const fontCss = font ? `@font-face{font-family:'Iosevka Web';font-weight:300;src:url('${dataUrl(font,'font/woff2')}') format('woff2')}` : '';
    const three = files.has('threejs_viewer.html') ? prepareThreePublication(files) : null;
    if (three) {
        files.set('three-publication-runtime.js',three.runtime);
        for (const path of three.sourcePaths) files.delete(path);
    }
    const captured = await captureOutputs(source.outputs, {fontCss, three, progress});
    for (const [path, html] of captured.files) files.set(path, html);
    const manifest = {version:1, capturedAt:new Date().toISOString(), documentName:source.document.filename,
        outputs:captured.outputs};
    files.set('document/metadata.edn', source.document.edn);
    files.set('document/outputs.json', JSON.stringify(manifest,null,2));
    files.set('index.html', '<!doctype html><meta charset="utf-8"><meta http-equiv="refresh" content="0;url=offline.html?file=document/metadata.edn&amp;outputs=document/outputs.json"><a href="offline.html?file=document/metadata.edn&amp;outputs=document/outputs.json">Open published Field document</a>');
    files.set('README.md', `# ${source.document.filename}\n\nUnzip and serve this directory with any static web server. Open index.html.\n\nCode, notes and captured outputs are included. Field is not required.\nThree.js views retain orbit, zoom, parts, X-ray and local GLB download over frozen geometry. Field-only controls are hidden.\nUnsupported custom viewer tools and animated shots use an image fallback with no controls.\nLazy inspectors contain the rows materialized in the captured browser.\nSee document/outputs.json for capture warnings.\n`);
    const warnings = manifest.outputs.flatMap(o => o.warnings.map(w => `${o.channel}: ${w}`));
    progress('Packing the publication');
    return {blob:await zipFiles(files), files, manifest, warnings};
}
