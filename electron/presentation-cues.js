const path = require('node:path');

function validRect(value) {
  if (!Array.isArray(value) || value.length !== 4 || !value.every(Number.isFinite)) return null;
  const [x, y, width, height] = value;
  return x >= 0 && y >= 0 && width > 0 && height > 0 && x + width <= 1 && y + height <= 1 ? value : null;
}

function validDocumentName(name) {
  return typeof name === 'string' && name.trim() === name && name.length > 0 &&
    name !== '.' && name !== '..' && !/[\\/\x00-\x1f]/.test(name) && path.basename(name) === name;
}

function fieldDocumentUrl(fieldUrl, document) {
  if (!validDocumentName(document)) return null;
  try {
    const url = new URL(fieldUrl);
    if (!['http:', 'https:'].includes(url.protocol) || !['localhost', '127.0.0.1'].includes(url.hostname)) return null;
    url.pathname = '/index.html';
    url.search = new URLSearchParams({file: document}).toString();
    url.hash = '';
    return url.href;
  } catch { return null; }
}

function guestUrl(slide, base, fieldUrl) {
  if (!slide || typeof slide !== 'object') return null;
  if (slide.type === 'field-document') return fieldDocumentUrl(fieldUrl, slide.document);
  if (slide.type === 'youtube') {
    if (!/^[A-Za-z0-9_-]{11}$/.test(slide.videoId || '')) return null;
    return `${base}/field-youtube.html?id=${encodeURIComponent(slide.videoId)}`;
  }
  if (!['webpage', 'video'].includes(slide.type) || typeof slide.url !== 'string') return null;
  try {
    const url = new URL(slide.url, base);
    return ['http:', 'https:'].includes(url.protocol) ? url.href : null;
  } catch { return null; }
}

function fieldActions(slide) {
  if (slide?.type !== 'field-document') return {guide: false, interactiveGuide: false, macro: null};
  const macro = slide.macro;
  if (!macro || typeof macro !== 'object' || typeof macro.name !== 'string' ||
      !macro.name.trim() || /[\x00-\x1f/\\]/.test(macro.name) ||
      !['document', 'project', 'workspace'].includes(macro.scope || 'document')) {
    return {guide: true, interactiveGuide: slide.interactiveGuide === true, macro: null};
  }
  return {guide: true, interactiveGuide: slide.interactiveGuide === true,
    macro: {name: macro.name, scope: macro.scope || 'document',
    project: macro.scope === 'project' && typeof macro.project === 'string' ? macro.project : ''}};
}

module.exports = {validRect, validDocumentName, fieldDocumentUrl, guestUrl, fieldActions};
