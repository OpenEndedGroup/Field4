import {sendWithReply, clearReplyHandler} from './afield.mjs';
import {buildPublication} from './publication-capture.js';

let running = false, serial = 0;
function sourceDocument() {
    return new Promise((resolve, reject) => {
        const reply = `web-publication-${++serial}`;
        const timer = setTimeout(() => {clearReplyHandler(reply); reject(new Error('Document export timed out'));}, 180000);
        sendWithReply('export-web-document', {}, reply, (_message, response) => {
            clearTimeout(timer); resolve(response); return 'remove';
        });
    });
}
/** The same entry point serves the palette and the automation bridge. */
async function build(options = {}) {
    if (running) throw new Error('A publication is already being captured');
    running = true;
    try { return await buildPublication(await sourceDocument(), options); }
    finally { running = false; }
}
function download(blob, name) {
    const url = URL.createObjectURL(blob), link = document.createElement('a');
    link.href = url; link.download = `${name || 'document'}-web.zip`; link.click();
    setTimeout(() => URL.revokeObjectURL(url), 60000);
}
window.FieldPublication = Object.freeze({build});
window.fieldPublishDocument = async () => {
    const previous = document.getElementById('field-publication');
    if (running) return;
    previous?.remove();
    const panel = document.createElement('section'); panel.id = 'field-publication';
    panel.setAttribute('aria-label','Publish document');
    // A FieldPanel shell (fieldPanel.kt, .field-panel in field.css): title, status, × close.
    panel.className = 'field-panel is-visible'; panel.style.zIndex = '100000'; panel.style.width = 'min(440px, calc(100vw - 20px))';
    const head = document.createElement('div'); head.className = 'field-panel-head';
    const titles = document.createElement('div');
    const title = document.createElement('div'); title.className = 'field-panel-title'; title.textContent = 'Publish document';
    const status = document.createElement('div'); status.className = 'field-panel-status'; status.setAttribute('role','status');
    titles.append(title, status);
    const close = document.createElement('button'); close.type = 'button'; close.className = 'field-panel-icon'; close.textContent = '×';
    close.title = 'Close'; close.setAttribute('aria-label', 'Close'); close.onclick = () => panel.remove();
    head.append(titles, close);
    const body = document.createElement('div'); body.className = 'field-panel-body';
    const actions = document.createElement('div'); actions.className = 'field-panel-actions';
    body.append(actions);
    panel.append(head, body); document.body.append(panel);
    panel.addEventListener('keydown', e => {if(e.key === 'Escape') panel.remove(); e.stopPropagation();});
    panel.addEventListener('mousedown', e => e.stopPropagation());
    try {
        const result = await build({progress:text => {status.textContent = text;}});
        const captured = result.manifest.outputs.filter(o => o.path).length;
        status.textContent = `${captured}/${result.manifest.outputs.length} outputs captured. Code and notes included.`;
        const description = document.createElement('p');
        description.textContent = 'Download, unzip, and upload the folder to any static web host. Plot data and 3D geometry are frozen; Three.js views retain orbit, parts and X-ray.';
        body.insertBefore(description,actions);
        if (result.warnings.length) {
            const warnings = document.createElement('ul');
            for (const warning of result.warnings) {const row = document.createElement('li');row.textContent = warning;warnings.append(row);}
            body.insertBefore(warnings,actions);
        }
        const save = document.createElement('button'); save.type = 'button'; save.textContent = 'Download web publication (.zip)';
        save.onclick = () => download(result.blob,result.manifest.documentName);
        actions.append(save);
    } catch (error) {status.textContent = `Publication failed: ${error.message}`;}
};
