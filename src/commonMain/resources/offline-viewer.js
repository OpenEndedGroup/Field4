/**
 * Field4 Offline Viewer
 * Renders Field documents in read-only mode without backend connection
 */

let monacoEditor = null;
let currentBoxes = [];
let selectedBox = null;
let currentProperty = 'code';
let ednFileUrl = null;

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

        // Initialize Monaco editor
        await initializeMonaco();

        // Setup the "Open in Field4" button
        setupOpenLocalButton(ednFileUrl);

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

// Handle window resize
window.addEventListener('resize', () => {
    if (monacoEditor) {
        monacoEditor.layout();
    }
});
