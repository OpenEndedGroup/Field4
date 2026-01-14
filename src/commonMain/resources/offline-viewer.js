/**
 * Field4 Offline Viewer
 * Renders Field documents in read-only mode without backend connection
 */

let monacoEditor = null;
let currentBoxes = [];
let selectedBox = null;
let currentProperty = 'code';
let ednFileUrl = null;
let ednFileText = '';
let currentDocumentData = null;
let panX = 0;
let panY = 0;
let isPanning = false;
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

// Initialize the viewer when page loads
window.addEventListener('DOMContentLoaded', async () => {
    try {
        // Get EDN file URL from query parameter
        const params = new URLSearchParams(window.location.search);
        ednFileUrl = params.get('file');

        if (!ednFileUrl) {
            showError('No file specified. Add ?file=path/to/metadata.edn to the URL');
            return;
        }

        // Load and parse the EDN file
        const ednText = await fetchEdnFile(ednFileUrl);
        ednFileText = ednText;
        const data = parseEdn(ednText);
        currentDocumentData = data;

        if (!data || !data.boxes) {
            showError('Invalid EDN format: missing boxes array');
            return;
        }

        // Render the boxes
        currentBoxes = data.boxes;
        renderBoxes(data.boxes);

        // Show document info
        showDocumentInfo(data, ednFileUrl);

        // Initialize Monaco editor
        await initializeMonaco();

        // Cache literate view element
        literateViewEl = document.getElementById('literate-view');

        // Setup view toggle
        setupLiterateToggle();

        // Setup the "Open in Field4" button
        setupOpenLocalButton(ednFileUrl, ednText, data);

        // Setup help overlay
        setupHelpOverlay();

        // Hide loading indicator
        document.getElementById('loading').style.display = 'none';

    } catch (error) {
        console.error('Failed to load document:', error);
        showError(`Failed to load document: ${error.message}`);
    }
});

async function fetchEdnFile(url) {
    // Try to fetch the file
    try {
        const response = await fetch(url);
        if (!response.ok) {
            throw new Error(`HTTP ${response.status}: ${response.statusText}`);
        }
        return await response.text();
    } catch (error) {
        throw new Error(`Failed to fetch ${url}: ${error.message}`);
    }
}

function renderBoxes(boxes) {
    const canvas = document.getElementById('canvas');
    const connections = document.getElementById('connections');
    canvas.innerHTML = '';
    if (connections) connections.innerHTML = '';

    visibleBoxes = boxes.filter(box => {
        const props = box.properties || {};
        return props._tombstone !== true && box.id !== 'root';
    });

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
    canvas.addEventListener('click', (e) => {
        if (e.target === canvas) {
            deselectBox();
        }
    });

    // Setup pan controls
    setupPanControls();

    applyPanTransform();
    drawConnections();
}

function setupPanControls() {
    const viewport = document.getElementById('viewport');
    const canvas = document.getElementById('canvas');
    const connections = document.getElementById('connections');

    // Middle mouse button or space+drag to pan
    viewport.addEventListener('mousedown', (e) => {
        if (e.button === 1 || (e.button === 0 && e.shiftKey)) {
            e.preventDefault();
            isPanning = true;
            startPanX = e.clientX - panX;
            startPanY = e.clientY - panY;
            viewport.style.cursor = 'grabbing';
        }
    });

    viewport.addEventListener('mousemove', (e) => {
        if (isPanning) {
            panX = e.clientX - startPanX;
            panY = e.clientY - startPanY;
            applyPanTransform();
        }
    });

    viewport.addEventListener('mouseup', () => {
        isPanning = false;
        viewport.style.cursor = 'default';
    });

    viewport.addEventListener('mouseleave', () => {
        isPanning = false;
        viewport.style.cursor = 'default';
    });

    // Scroll wheel to pan
    viewport.addEventListener('wheel', (e) => {
        if (!e.ctrlKey) {
            e.preventDefault();
            panX -= e.deltaX;
            panY -= e.deltaY;
            applyPanTransform();
        }
    });

    // Keyboard shortcuts
    document.addEventListener('keydown', (e) => {
        // Escape to deselect
        if (e.key === 'Escape') {
            deselectBox();
        }
        // Arrow keys to pan when no editor is focused
        if (!isEditorFocused()) {
            if (e.key === 'ArrowLeft') {
                panX += 50;
                applyPanTransform();
            } else if (e.key === 'ArrowRight') {
                panX -= 50;
                applyPanTransform();
            } else if (e.key === 'ArrowUp') {
                panY += 50;
                applyPanTransform();
            } else if (e.key === 'ArrowDown') {
                panY -= 50;
                applyPanTransform();
            }
        }
    });
}

function applyPanTransform() {
    const canvas = document.getElementById('canvas');
    const connections = document.getElementById('connections');
    if (canvas) {
        canvas.style.transform = `translate(${panX}px, ${panY}px)`;
    }
    if (connections) {
        connections.style.transform = `translate(${panX}px, ${panY}px)`;
    }
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

function selectBox(box, boxEl) {
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

    drawConnections();
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

function showEditorForBox(box) {
    const container = document.getElementById('editor-container');
    const props = box.properties || {};

    const editablePropsOrder = ['code', 'glsl', 'python', 'clojure', 'javascript', 'vertex', 'fragment', 'task', 'mesh', 'compute'];

    // Get list of properties that contain code
    const codeProperties = editablePropsOrder.filter(key => typeof props[key] === 'string');

    if (codeProperties.length > 0 && !codeProperties.includes(currentProperty)) {
        currentProperty = codeProperties[0];
    }

    // Create property tabs
    const tabsContainer = document.getElementById('property-tabs');
    tabsContainer.innerHTML = '';

    if (codeProperties.length > 0) {
        codeProperties.forEach(prop => {
            const tab = document.createElement('div');
            tab.className = 'property-tab';
            if (prop === currentProperty || (!codeProperties.includes(currentProperty) && prop === codeProperties[0])) {
                tab.classList.add('active');
                currentProperty = prop;
            }
            tab.textContent = prop;
            tab.addEventListener('click', () => {
                switchProperty(prop, box);
            });
            tabsContainer.appendChild(tab);
        });
    } else {
        // No code properties, just show 'code' tab
        currentProperty = 'code';
        const tab = document.createElement('div');
        tab.className = 'property-tab active';
        tab.textContent = 'code';
        tabsContainer.appendChild(tab);
    }

    // Show the editor
    container.classList.add('visible');

    // Load content into Monaco
    if (monacoEditor) {
        const content = props[currentProperty] || '';
        const language = getLanguageForProperty(currentProperty);
        monaco.editor.setModelLanguage(monacoEditor.getModel(), language);
        monacoEditor.setValue(content);
        renderLiterateView(content, language);
    }

    updateEditorVisibility();
}

function switchProperty(property, box) {
    currentProperty = property;

    // Update tab active state
    document.querySelectorAll('.property-tab').forEach(tab => {
        if (tab.textContent === property) {
            tab.classList.add('active');
        } else {
            tab.classList.remove('active');
        }
    });

    // Update editor content
    if (monacoEditor && box) {
        const props = box.properties || {};
        const content = props[property] || '';
        const language = getLanguageForProperty(property);
        monaco.editor.setModelLanguage(monacoEditor.getModel(), language);
        monacoEditor.setValue(content);
        renderLiterateView(content, language);
    }
}

function getLanguageForProperty(property) {
    const languageMap = {
        'code': 'kotlin',
        'glsl': 'cpp',
        'python': 'python',
        'clojure': 'clojure',
        'javascript': 'javascript',
        'vertex': 'cpp',
        'fragment': 'cpp',
        'task': 'cpp',
        'mesh': 'cpp',
        'compute': 'cpp'
    };
    return languageMap[property] || 'kotlin';
}

async function initializeMonaco() {
    return new Promise((resolve, reject) => {
        require.config({ paths: { vs: './min/vs' } });
        require(['vs/editor/editor.main'], () => {
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
        toggle.textContent = literateMode ? 'Editor' : 'Reader';
        updateEditorVisibility();
        // Re-render current content to ensure view is fresh
        if (selectedBox) {
            const props = selectedBox.properties || {};
            const content = props[currentProperty] || '';
            const language = getLanguageForProperty(currentProperty);
            renderLiterateView(content, language);
        }
    });
}

function updateEditorVisibility() {
    const editorEl = document.getElementById('editor');
    if (!editorEl || !literateViewEl) return;
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

    lines.forEach(line => {
        const comment = isCommentLine(line);
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

function setupOpenLocalButton(fileUrl, ednText, data) {
    const button = document.getElementById('open-local');
    button.style.display = 'inline-block';
    button.href = '#';
    button.title = 'Send to Field4 desktop application';

    let busy = false;

    button.addEventListener('click', async (e) => {
        e.preventDefault();
        if (busy) return;
        busy = true;
        const originalLabel = button.textContent;
        button.textContent = 'Sending to Field4...';
        try {
            const recommendedName = deriveRecommendedName(fileUrl);
            const response = await fetch('http://localhost:9092/api/import-offline-doc', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    recommendedName,
                    ednText,
                    sourceUrl: window.location.href
                })
            });

            if (!response.ok) {
                throw new Error(`HTTP ${response.status}: ${response.statusText}`);
            }

            const result = await response.json();
            const localEdnUrl = result.projectUrl || `http://localhost:9092/${encodeURIComponent(result.documentName)}/metadata.edn`;
            const openUrl = result.openUrl || `http://localhost:9092/index.html?file=${encodeURIComponent(result.documentName)}`;

            // Update state so further loads use the local copy
            ednFileUrl = localEdnUrl;
            ednFileText = ednText;
            if (data) {
                showDocumentInfo(data, localEdnUrl);
            }

            // Update URL bar so this offline page now points at the local copy
            window.history.pushState({}, '', `?file=${encodeURIComponent(localEdnUrl)}`);

            // Convert button back into a normal link to Field4
            button.href = openUrl;
            button.textContent = 'Open in Field4';
            button.title = `Open ${result.documentName} in Field4`;

            // Launch Field4
            window.location.href = openUrl;
        } catch (error) {
            showError(`Failed to send to Field4: ${error.message}`);
            button.textContent = originalLabel;
        } finally {
            busy = false;
        }
    });
}

function deriveRecommendedName(fileUrl) {
    try {
        const url = new URL(fileUrl, window.location.href);
        const parts = url.pathname.split('/').filter(Boolean);
        if (parts.length === 0) return 'offline-document';
        const last = parts[parts.length - 1];
        return last.replace(/\.edn$/i, '') || 'offline-document';
    } catch (_e) {
        // Fallback for plain file names
        const simple = fileUrl.split('/').pop() || 'offline-document';
        return simple.replace(/\.edn$/i, '') || 'offline-document';
    }
}

function showError(message) {
    const loading = document.getElementById('loading');
    const error = document.getElementById('error');
    
    loading.style.display = 'none';
    error.style.display = 'block';
    error.innerHTML = `
        <h3>Error</h3>
        <p>${message}</p>
        <p style="margin-top: 16px; font-size: 12px; opacity: 0.7;">
            Make sure the EDN file exists and is accessible from this page.
        </p>
    `;
}

function showDocumentInfo(data, fileUrl) {
    const banner = document.querySelector('.offline-banner');
    const fileName = fileUrl.split('/').pop();
    const boxCount = data.boxes ? data.boxes.filter(b => !(b.properties || {})._tombstone).length : 0;
    const version = data.version || 'unknown';
    
    banner.innerHTML = `
        Offline Documentation Mode — Read Only 
        <span style="opacity: 0.6; margin-left: 12px; font-size: 11px;">
            ${fileName} • ${boxCount} box${boxCount !== 1 ? 'es' : ''} • v${version}
        </span>
        <span style="opacity: 0.6; margin-left: 12px;">Press <kbd style="padding: 2px 6px; background: rgba(255,255,255,0.1); border-radius: 3px;">?</kbd> for help</span>
    `;
}

// Handle window resize
window.addEventListener('resize', () => {
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
                <p style="margin: 0 0 6px 0;"><kbd>Click box</kbd> — Select box and view code</p>
                <p style="margin: 0 0 6px 0;"><kbd>Middle mouse</kbd> or <kbd>Shift + Drag</kbd> — Pan canvas</p>
                <p style="margin: 0 0 6px 0;"><kbd>Mouse wheel</kbd> — Scroll canvas</p>
                <p style="margin: 0 0 6px 0;"><kbd>Arrow keys</kbd> — Pan canvas (when editor not focused)</p>
                <p style="margin: 0 0 12px 0;"><kbd>Escape</kbd> — Deselect box and hide editor</p>
                <p style="margin: 12px 0 8px 0;"><strong>Features</strong></p>
                <p style="margin: 0 0 6px 0;">• Click property tabs to switch between code/GLSL/python</p>
                <p style="margin: 0 0 6px 0;">• Monaco editor provides syntax highlighting</p>
                <p style="margin: 0 0 16px 0;">• Click "Open in Field4" to launch in desktop app</p>
                <p style="margin: 0; opacity: 0.8; font-size: 13px;">
                    This is a read-only documentation mode. To edit or execute code, open in Field4 desktop application.
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
