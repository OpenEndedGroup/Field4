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
    panel.style.cssText = 'position:fixed;right:24px;top:70px;z-index:100000;max-width:440px;max-height:75vh;overflow:auto;padding:20px;background:#080808;color:#ddd;border:1px solid #555;font:13px "Iosevka Web",monospace;box-shadow:0 8px 32px #000';
    const title = document.createElement('strong'); title.textContent = 'Publish document';
    const status = document.createElement('p'); status.setAttribute('role','status');
    const close = document.createElement('button'); close.textContent = 'Close'; close.onclick = () => panel.remove();
    close.style.cssText = 'background:#111;color:#ddd;border:1px solid #555;padding:6px 12px;font:inherit';
    panel.append(title,status,close); document.body.append(panel);
    panel.addEventListener('keydown', e => {if(e.key === 'Escape') panel.remove(); e.stopPropagation();});
    panel.addEventListener('mousedown', e => e.stopPropagation());
    try {
        const result = await build({progress:text => {status.textContent = text;}});
        const captured = result.manifest.outputs.filter(o => o.path).length;
        status.textContent = `${captured}/${result.manifest.outputs.length} outputs captured. Code and notes included.`;
        const description = document.createElement('p');
        description.textContent = 'Download, unzip, and upload the folder to any static web host. Plot data and 3D geometry are frozen; Three.js views retain orbit, parts and X-ray.';
        panel.insertBefore(description,close);
        if (result.warnings.length) {
            const warnings = document.createElement('ul');
            for (const warning of result.warnings) {const row = document.createElement('li');row.textContent = warning;warnings.append(row);}
            panel.insertBefore(warnings,close);
        }
        const save = close.cloneNode(); save.textContent = 'Download web publication (.zip)';
        save.onclick = () => download(result.blob,result.manifest.documentName);
        panel.insertBefore(save,close); close.style.marginLeft = '8px';
    } catch (error) {status.textContent = `Publication failed: ${error.message}`;}
};
