import {outputRequests} from './output_requests.js';

const params = new URLSearchParams(location.search);
const binding = params.get('binding');
const width = Number(params.get('width'));
const height = Number(params.get('height'));
const draftKey = params.get('draft');
const status = document.getElementById('field-klecks-status');
const CHUNK = 400000;

function say(message, error = false) {
  status.textContent = message;
  status.style.color = error ? '#ff8b8b' : '#ddd';
}

function base64(blob) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result).split(',', 2)[1]);
    reader.onerror = () => reject(reader.error);
    reader.readAsDataURL(blob);
  });
}

function binary(value) {
  const bytes = new Uint8Array((value.length * 3) / 4);
  const decoded = atob(value);
  for (let i = 0; i < decoded.length; i++) bytes[i] = decoded.charCodeAt(i);
  return bytes.slice(0, decoded.length).buffer;
}

const requests = outputRequests(binding);
let painting;

// Unsubmitted strokes, kept in IndexedDB under the box and channel so a re-run or reload of the
// panel does not lose them. Submit clears the draft.
function drafts() {
  return new Promise((resolve, reject) => {
    const open = indexedDB.open('field-klecks-drafts', 1);
    open.onupgradeneeded = () => open.result.createObjectStore('drafts');
    open.onsuccess = () => resolve(open.result);
    open.onerror = () => reject(open.error);
  });
}

async function draftOp(mode, fn) {
  if (!draftKey) return undefined;
  const db = await drafts();
  return new Promise((resolve, reject) => {
    const tx = db.transaction('drafts', mode);
    const request = fn(tx.objectStore('drafts'));
    tx.oncomplete = () => resolve(request?.result);
    tx.onerror = () => reject(tx.error);
  });
}

let dirty = false;
let draftTimer = null;
let loaded = false;

async function keepDraft() {
  if (!dirty || !loaded) return;
  dirty = false;
  const psd = await painting.getPSD();
  await draftOp('readwrite', store => store.put(psd, draftKey));
}

function noteChange() {
  if (!loaded) return;
  dirty = true;
  clearTimeout(draftTimer);
  draftTimer = setTimeout(() => keepDraft().catch(error => say(`Draft not kept: ${error.message || error}`, true)), 800);
}

async function save() {
  say('Saving layers and image…');
  const [psd, png] = await Promise.all([painting.getPSD(), painting.getPNG()]);
  const [psdText, pngText] = await Promise.all([base64(psd), base64(png)]);
  const id = crypto.randomUUID();
  await requests.request({op: 'begin', id, psdLength: psdText.length, pngLength: pngText.length});
  for (const [kind, value] of [['psd', psdText], ['png', pngText]]) {
    for (let offset = 0; offset < value.length; offset += CHUNK) {
      await requests.request({op: 'part', id, kind, value: value.slice(offset, offset + CHUNK)});
    }
  }
  await requests.request({op: 'commit', id});
  dirty = false;
  clearTimeout(draftTimer);
  await draftOp('readwrite', store => store.delete(draftKey)).catch(() => {});
  say('Saved in Field document');
  return {psdBytes: psd.size, pngBytes: png.size};
}

async function load() {
  const draft = await draftOp('readonly', store => store.get(draftKey)).catch(() => undefined);
  if (draft) {
    painting.openProject(await painting.readPSD(await draft.arrayBuffer()));
    loaded = true;
    say('Restored strokes not yet submitted · Submit saves to Field');
    return;
  }
  const info = await requests.request({op: 'info'});
  if (!info.psdLength) {
    painting.openProject({width, height, layers: [{name: 'Background', image: {fill: '#fff'}}]});
    loaded = true;
    say('New painting · Submit saves to Field');
    return;
  }
  let psd = '';
  for (let offset = 0; offset < info.psdLength; offset += info.chunkSize) {
    psd += await requests.request({op: 'read', offset});
  }
  painting.openProject(await painting.readPSD(binary(psd)));
  loaded = true;
  say('Restored layers · Submit saves to Field');
}

if (!binding || !Number.isInteger(width) || !Number.isInteger(height)) {
  say('Invalid Klecks output', true);
} else {
  painting = new Klecks({
    onSubmit: (onSuccess, onError) => {
      save().then(onSuccess).catch(error => { say(`Save failed: ${error.message || error}`, true); onError(); });
    },
  });
  window.fieldKlecks = {save, keepDraft, getPNG: () => painting.getPNG(), getPSD: () => painting.getPSD()};
  for (const type of ['pointerup', 'keyup']) document.addEventListener(type, noteChange, true);
  load().catch(error => say(`Load failed: ${error.message || error}`, true));
}
