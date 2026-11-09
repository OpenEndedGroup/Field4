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

// A live figure on an HTML slide: one box's output (the Field page's ?figure= mode), in the
// presentation's own document. `document` is that document's name, taken from the source page.
function figureUrl(fieldUrl, document, figure, background) {
  const base = fieldDocumentUrl(fieldUrl, document);
  if (!base || !validFigure(figure)) return null;
  const url = new URL(base);
  url.searchParams.set('figure', figure.box);
  if (figure.channels?.length) url.searchParams.set('channels', figure.channels.join(','));
  if (typeof background === 'string' && /^(#[0-9A-Fa-f]{3,8}|rgba?\([0-9., ]+\))$/.test(background)) {
    url.searchParams.set('bg', background);
  }
  return url.href;
}

function validFigure(figure) {
  return !!figure && typeof figure === 'object' &&
    typeof figure.id === 'string' && /^[A-Za-z0-9_-]{1,64}$/.test(figure.id) &&
    typeof figure.box === 'string' && /^[A-Za-z0-9_.:-]{1,128}$/.test(figure.box) &&
    (figure.name == null || (typeof figure.name === 'string' && figure.name.length <= 256)) &&
    (figure.channels == null || (Array.isArray(figure.channels) && figure.channels.length <= 16 &&
      figure.channels.every(c => typeof c === 'string' && c.length > 0 && c.length <= 128 && !/[\x00-\x1f,]/.test(c)))) &&
    (figure.rect == null || validRect(figure.rect) !== null);
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

// The sender is checked by the Electron host; validate the wire data before opening any windows.
function validateGuideState(state) {
  // Kotlin's wire encoder omits values equal to their declared defaults.
  state = {transitionMs: 450, cueIndex: 0, cueTitles: [], cueAction: false, checkpoint: '', ...state};
  if (state.content) state.content = {type: 'html', html: '', ...state.content};
  if (!state || typeof state !== 'object' || typeof state.slideshowId !== 'string' || !state.slideshowId ||
      typeof state.hostUid !== 'string' || !state.hostUid || typeof state.title !== 'string' ||
      !Array.isArray(state.titles) || !state.titles.length || state.titles.length > 1000 ||
      !state.titles.every(title => typeof title === 'string' && title.length <= 4096) ||
      !Number.isInteger(state.index) || state.index < -1 || state.index >= state.titles.length ||
      !Number.isInteger(state.transitionMs) || state.transitionMs < 0 || state.transitionMs > 2000) {
    throw new Error('Invalid guide presentation state');
  }
  if (state.sectionPaths != null && (!Array.isArray(state.sectionPaths) ||
      (state.sectionPaths.length !== 0 && state.sectionPaths.length !== state.titles.length) ||
      !state.sectionPaths.every(path => Array.isArray(path) && path.length <= 16 &&
        path.every(title => typeof title === 'string' && title.trim() && title.length <= 4096)))) {
    throw new Error('Invalid guide presentation sections');
  }
  if (state.stepIds != null && (!Array.isArray(state.stepIds) ||
      (state.stepIds.length !== 0 && state.stepIds.length !== state.titles.length) ||
      !state.stepIds.every(id => typeof id === 'string' && id.trim() && id.length <= 256) ||
      new Set(state.stepIds).size !== state.stepIds.length)) throw new Error('Invalid guide step IDs');
  if (state.searchTexts != null && (!Array.isArray(state.searchTexts) ||
      (state.searchTexts.length !== 0 && state.searchTexts.length !== state.titles.length) ||
      !state.searchTexts.every(text => typeof text === 'string' && text.length <= 16384))) {
    throw new Error('Invalid guide search text');
  }
  if (state.index === -1) return state;
  if (!Array.isArray(state.cueTitles) || state.cueTitles.length > 100 ||
      !state.cueTitles.every(title => typeof title === 'string' && title.trim() && title.length <= 4096) ||
      !Number.isInteger(state.cueIndex) || state.cueIndex < 0 || state.cueIndex > state.cueTitles.length ||
      typeof state.cueAction !== 'boolean' || typeof state.checkpoint !== 'string' || state.checkpoint.length > 4096) {
    throw new Error('Invalid guide cue state');
  }
  const content = state.content;
  if (!content || (content.type === 'html'
    ? typeof content.html !== 'string' || content.html.length > 1000000
    : !guestUrl(content, 'http://127.0.0.1', 'https://localhost/index.html'))) {
    throw new Error('Invalid guide audience content');
  }
  if (content.rect != null && !validRect(content.rect)) throw new Error('Invalid guide audience rect');
  if (content.figures != null && (content.type !== 'html' || !Array.isArray(content.figures) ||
      content.figures.length > 8 || !content.figures.every(validFigure) ||
      new Set(content.figures.map(f => f.id)).size !== content.figures.length)) {
    throw new Error('Invalid guide audience figures');
  }
  return state;
}

module.exports = {validRect, validDocumentName, fieldDocumentUrl, figureUrl, validFigure, guestUrl, fieldActions, validateGuideState};
