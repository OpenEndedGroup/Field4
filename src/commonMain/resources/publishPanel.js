// The Publish document panel. The bundling lives in publishDocument.js, whose bytes are part of
// docs_publications.capture_digest; this file is only the panel around it (a FieldPanel shell:
// title, status, × close, Escape), so it can change without making every publication stale.
// Loaded after publishDocument.js and replaces the plain panel that file still defines.
import './publishDocument.js';

let running = false;
function download(blob, name) {
    const url = URL.createObjectURL(blob), link = document.createElement('a');
    link.href = url; link.download = `${name || 'document'}-web.zip`; link.click();
    setTimeout(() => URL.revokeObjectURL(url), 60000);
}
const el = (tag, cls, text) => { const n = document.createElement(tag); if (cls) n.className = cls; if (text !== undefined) n.textContent = text; return n; };

window.fieldPublishDocument = async () => {
    if (running) return;
    document.getElementById('field-publication')?.remove();
    const panel = el('section', 'field-panel is-visible'); panel.id = 'field-publication';
    panel.setAttribute('aria-label', 'Publish document');
    panel.style.zIndex = '100000'; panel.style.width = 'min(440px, calc(100vw - 20px))';
    const head = el('div', 'field-panel-head'), titles = el('div');
    const status = el('div', 'field-panel-status'); status.setAttribute('role', 'status');
    titles.append(el('div', 'field-panel-title', 'Publish document'), status);
    const close = el('button', 'field-panel-icon', '×'); close.type = 'button';
    close.title = 'Close'; close.setAttribute('aria-label', 'Close'); close.onclick = () => panel.remove();
    head.append(titles, close);
    const body = el('div', 'field-panel-body'), actions = el('div', 'field-panel-actions');
    body.append(actions); panel.append(head, body); document.body.append(panel);
    panel.addEventListener('keydown', e => { if (e.key === 'Escape') panel.remove(); e.stopPropagation(); });
    panel.addEventListener('mousedown', e => e.stopPropagation());
    running = true;
    try {
        const result = await window.FieldPublication.build({progress: text => { status.textContent = text; }});
        const captured = result.manifest.outputs.filter(o => o.path).length;
        status.textContent = `${captured}/${result.manifest.outputs.length} outputs captured. Code and notes included.`;
        body.insertBefore(el('p', '', 'Download, unzip, and upload the folder to any static web host. Plot data and 3D geometry are frozen; Three.js views retain orbit, parts and X-ray.'), actions);
        if (result.warnings.length) {
            const warnings = el('ul');
            for (const warning of result.warnings) warnings.append(el('li', '', warning));
            body.insertBefore(warnings, actions);
        }
        const save = el('button', '', 'Download web publication (.zip)'); save.type = 'button';
        save.onclick = () => download(result.blob, result.manifest.documentName);
        actions.append(save);
    } catch (error) { status.textContent = `Publication failed: ${error.message}`; }
    finally { running = false; }
};
