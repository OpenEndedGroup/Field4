/**
 * Field4 Offline Viewer
 * Renders Field documents in read-only mode without backend connection
 */

let monacoEditor = null;
let currentBoxes = [];
let selectedBox = null;
let currentProperty = 'code';
let ednFileUrl = null;
let panX = 0;
let panY = 0;
let isPanning = false;
let startPanX = 0;
let startPanY = 0;

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
        const data = parseEdn(ednText);

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

        // Setup the "Open in Field4" button
        setupOpenLocalButton(ednFileUrl);

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
    canvas.innerHTML = '';

    // Calculate canvas size to fit all boxes
    let maxX = 0, maxY = 0;

    boxes.forEach(box => {
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

    // Click on canvas to deselect
    canvas.addEventListener('click', (e) => {
        if (e.target === canvas) {
            deselectBox();
        }
    });

    // Setup pan controls
    setupPanControls();
}

function setupPanControls() {
    const viewport = document.getElementById('viewport');
    const canvas = document.getElementById('canvas');

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
            canvas.style.transform = `translate(${panX}px, ${panY}px)`;
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
            canvas.style.transform = `translate(${panX}px, ${panY}px)`;
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
                canvas.style.transform = `translate(${panX}px, ${panY}px)`;
            } else if (e.key === 'ArrowRight') {
                panX -= 50;
                canvas.style.transform = `translate(${panX}px, ${panY}px)`;
            } else if (e.key === 'ArrowUp') {
                panY += 50;
                canvas.style.transform = `translate(${panX}px, ${panY}px)`;
            } else if (e.key === 'ArrowDown') {
                panY -= 50;
                canvas.style.transform = `translate(${panX}px, ${panY}px)`;
            }
        }
    });
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
}

function showEditorForBox(box) {
    const container = document.getElementById('editor-container');
    const props = box.properties || {};

    // Get list of properties that contain code
    const codeProperties = Object.keys(props).filter(key => {
        const value = props[key];
        return typeof value === 'string' && 
               (key === 'code' || key === 'glsl' || key === 'python' || key === 'clojure' || key === 'javascript');
    });

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
    }
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
    }
}

function getLanguageForProperty(property) {
    const languageMap = {
        'code': 'kotlin',
        'glsl': 'glsl',
        'python': 'python',
        'clojure': 'clojure',
        'javascript': 'javascript'
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

function setupOpenLocalButton(fileUrl) {
    const button = document.getElementById('open-local');
    
    // Try to create a file:// URL or use the original URL
    let localUrl = fileUrl;
    
    // If it's a relative URL, try to convert to absolute
    if (!fileUrl.startsWith('http://') && !fileUrl.startsWith('https://') && !fileUrl.startsWith('file://')) {
        const currentUrl = new URL(window.location.href);
        localUrl = new URL(fileUrl, currentUrl.origin).href;
    }

    // Create the field:// URL (custom protocol that could be registered)
    // For now, we'll use a localhost URL with the file parameter
    const fieldUrl = `http://localhost:9092/index.html?file=${encodeURIComponent(fileUrl)}`;
    
    button.href = fieldUrl;
    button.style.display = 'inline-block';
    button.title = `Open ${fileUrl} in Field4 desktop application`;
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
    const boxCount = data.boxes ? data.boxes.length : 0;
    const version = data.version || 'unknown';
    
    banner.innerHTML = `
        📖 Offline Documentation Mode — Read Only 
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
        <div style="max-width: 600px; padding: 40px; background: rgba(30, 30, 30, 0.95); border-radius: 12px; border: 2px solid var(--field-cyan);">
            <h2 style="margin-top: 0; color: var(--field-cyan); font-size: 28px;">Offline Viewer Help</h2>
            <div style="line-height: 2; color: #d0d0d0;">
                <p><strong>Navigation:</strong></p>
                <p><kbd>Click box</kbd> — Select box and view code</p>
                <p><kbd>Middle mouse</kbd> or <kbd>Shift + Drag</kbd> — Pan canvas</p>
                <p><kbd>Mouse wheel</kbd> — Scroll canvas</p>
                <p><kbd>Arrow keys</kbd> — Pan canvas (when editor not focused)</p>
                <p><kbd>Escape</kbd> — Deselect box and hide editor</p>
                <p><kbd>?</kbd> — Show/hide this help</p>
                <p style="margin-top: 24px;"><strong>Features:</strong></p>
                <p>• Click property tabs to switch between code/glsl/python</p>
                <p>• Monaco editor provides syntax highlighting</p>
                <p>• Click "🚀 Open in Field4" to launch in desktop app</p>
                <p style="margin-top: 24px; opacity: 0.7; font-size: 13px;">
                    This is a read-only documentation mode. To edit or execute code, open in Field4 desktop application.
                </p>
            </div>
            <div style="text-align: center; margin-top: 24px;">
                <button style="padding: 10px 24px; background: var(--field-cyan); border: none; border-radius: 6px; color: #000; cursor: pointer; font-size: 14px; font-family: inherit;">
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
        if (e.key === '?' || e.key === 'h') {
            helpOverlay.style.display = helpVisible ? 'none' : 'flex';
            helpVisible = !helpVisible;
        }
        if (e.key === 'Escape' && helpVisible) {
            helpOverlay.style.display = 'none';
            helpVisible = false;
        }
    });
}
