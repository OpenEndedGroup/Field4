import {outputRequests} from './output_requests.js';

const params = new URLSearchParams(location.search);
const binding = params.get('binding');
const width = Number(params.get('width'));
const height = Number(params.get('height'));
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
  say('Saved in Field document');
  return {psdBytes: psd.size, pngBytes: png.size};
}

async function load() {
  const info = await requests.request({op: 'info'});
  if (!info.psdLength) {
    painting.openProject({width, height, layers: [{name: 'Background', image: {fill: '#fff'}}]});
    say('New painting · Submit saves to Field');
    return;
  }
  let psd = '';
  for (let offset = 0; offset < info.psdLength; offset += info.chunkSize) {
    psd += await requests.request({op: 'read', offset});
  }
  painting.openProject(await painting.readPSD(binary(psd)));
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
  window.fieldKlecks = {save, getPNG: () => painting.getPNG(), getPSD: () => painting.getPSD()};
  load().catch(error => say(`Load failed: ${error.message || error}`, true));
}
