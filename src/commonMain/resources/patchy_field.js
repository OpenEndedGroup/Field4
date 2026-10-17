import {outputRequests} from './output_requests.js';

const params = new URLSearchParams(location.search);
const binding = params.get('binding');
const stored = params.get('stored') === '1';
const autosave = stored && params.get('autosave') === '1';
const storageUrl = `/api/patchy-document/${encodeURIComponent(binding || '')}`;
const frame = document.getElementById('patchy');
const status = document.getElementById('status');
const requests = binding ? outputRequests(binding) : null;
const page = crypto.randomUUID();
let ready = false;
let rpcReady = false;
let connected = false;
let inFlight = false;
let result = null;
let commandInFlight = false;
const commandQueue = [];
const internalQueue = [];
let currentInternal = null;
let fileWaiter = null;
let revision = 'missing';
let savedSignature = null;
let observedSignature = null;
let changedAt = 0;
let saving = false;
let saveConflict = false;
let retryAfter = 0;
let storageMessage = '';

function show(message) { status.textContent = message; status.hidden = !message; }
function updateStatus() { show(storageMessage || (!crossOriginIsolated ? 'Patchy needs Field cross-origin isolation' : '')); }

function internalCall(method, params = {}) {
  return new Promise((resolve, reject) => {
    const message = {jsonrpc: '2.0', id: `field-storage-${crypto.randomUUID()}`, method, params};
    internalQueue.push({message, resolve, reject});
    dispatchNext();
  });
}

async function patchyTool(name, args = {}) {
  const reply = await internalCall('tools/call', {name, arguments: args});
  if (reply.error || reply.result?.isError) {
    const detail = reply.error || reply.result?.structuredContent || {};
    const error = new Error(detail.message || JSON.stringify(detail));
    error.code = detail.error || detail.code;
    throw error;
  }
  return reply.result?.structuredContent || {};
}

function dispatchNext() {
  if (!rpcReady || commandInFlight || result) return;
  currentInternal = internalQueue.shift() || null;
  const message = currentInternal?.message || (ready ? commandQueue.shift() : null);
  if (!message) return;
  commandInFlight = true;
  frame.contentWindow.postMessage({type: 'field-patchy-request', message}, location.origin);
}

function fileExchange(type, bytes) {
  return new Promise((resolve, reject) => {
    if (fileWaiter) { reject(new Error('Patchy file transfer already in progress')); return; }
    fileWaiter = {resolve, reject};
    const message = {type, bytes};
    frame.contentWindow.postMessage(message, location.origin, bytes ? [bytes] : []);
  });
}

function signature(state) {
  return JSON.stringify((state.documents || []).map(doc => [doc.id, doc.historyStateId]));
}

async function restore() {
  if (stored) {
    storageMessage = 'Loading Field painting…'; updateStatus();
    const response = await fetch(storageUrl, {cache: 'no-store'});
    if (response.status === 404) {
      revision = response.headers.get('X-Patchy-Revision') || 'missing';
    } else {
      if (!response.ok) throw new Error(`Field load returned ${response.status}`);
      revision = response.headers.get('X-Patchy-Revision') || 'missing';
      const bytes = await response.arrayBuffer();
      const loaded = await fileExchange('field-patchy-load-file', bytes);
      if (!loaded.ok) throw new Error('Patchy could not load the PSD bytes');
      const state = await patchyTool('get_state');
      await patchyTool('execute_script', {
        code: "patchy.setResult(app.open('/field/painting.psd').id);",
        name: 'Field restore', expectedState: state.stateToken
      });
    }
    savedSignature = signature(await patchyTool('get_state'));
    observedSignature = savedSignature;
  }
  ready = true;
  storageMessage = ''; updateStatus();
  dispatchNext();
}

async function saveIfChanged() {
  if (!autosave || !ready || saving || saveConflict || Date.now() < retryAfter) return;
  saving = true;
  try {
    const state = await patchyTool('get_state');
    const current = signature(state);
    if (current !== observedSignature) { observedSignature = current; changedAt = Date.now(); }
    if (current === savedSignature || Date.now() - changedAt < 1500) return;
    const doc = (state.documents || []).find(d => d.id === state.activeDocumentId);
    if (!doc) return;
    storageMessage = 'Saving painting to Field…'; updateStatus();
    const saved = await patchyTool('execute_script', {
      code: `patchy.setResult(app.getDocument(${JSON.stringify(doc.id)}).saveAs('/field/painting.psd'));`,
      name: 'Field autosave', expectedState: state.stateToken
    });
    if (saved.result !== true) throw new Error('Patchy could not write the temporary PSD');
    const file = await fileExchange('field-patchy-read-file');
    if (!(file.bytes instanceof ArrayBuffer)) throw new Error('Patchy returned no PSD bytes');
    const response = await fetch(storageUrl, {
      method: 'PUT', headers: {'Content-Type': 'application/octet-stream', 'X-Patchy-Revision': revision}, body: file.bytes
    });
    if (response.status === 409) saveConflict = true;
    if (!response.ok) throw new Error(`Field save returned ${response.status}: ${await response.text()}`);
    revision = response.headers.get('X-Patchy-Revision') || revision;
    savedSignature = current;
    storageMessage = ''; updateStatus();
  } catch (error) {
    retryAfter = Date.now() + (error.code === 'busy' ? 1000 : 5000);
    storageMessage = error.code === 'busy' ? '' : `Painting not saved: ${error.message || error}`;
    updateStatus();
  } finally { saving = false; }
}

window.addEventListener('message', event => {
  if (event.source !== frame.contentWindow || event.origin !== location.origin) return;
  const data = event.data;
  if (data?.type === 'field-patchy-ready') {
    frame.contentWindow.postMessage({type: 'field-patchy-request', message: {
      jsonrpc: '2.0', id: 'field-init', method: 'initialize',
      params: {protocolVersion: '2025-06-18', clientInfo: {name: 'Field Kotlin', version: '1'}, capabilities: {}}
    }}, location.origin);
  } else if (data?.type === 'field-patchy-response') {
    if (data.message?.id === 'field-init') {
      rpcReady = !!data.message.result;
      if (rpcReady) restore().catch(error => {
        storageMessage = `Painting not loaded: ${error.message || error}`; updateStatus();
      });
      else show('Patchy control could not initialize');
    } else {
      commandInFlight = false;
      if (currentInternal && data.message?.id === currentInternal.message.id) {
        currentInternal.resolve(data.message);
        currentInternal = null;
      } else result = data.message;
      dispatchNext();
    }
  } else if (data?.type === 'field-patchy-file-loaded' || data?.type === 'field-patchy-file-read') {
    const waiter = fileWaiter;
    fileWaiter = null;
    waiter?.resolve(data);
  }
});

async function poll() {
  if (!requests || inFlight) return;
  inFlight = true;
  const report = {page, ready, result};
  try {
    const reply = await requests.request(report);
    if (result === report.result) result = null;
    connected = true;
    if (ready) updateStatus();
    commandQueue.push(...(reply?.commands || []));
    dispatchNext();
  } catch (error) {
    connected = false;
    show(`Field connection: ${error.message || error}`);
  } finally {
    inFlight = false;
  }
}

frame.addEventListener('load', () => {
  ready = false;
  rpcReady = false;
  commandInFlight = false;
  commandQueue.length = 0;
  if (!ready && !crossOriginIsolated) show('Patchy needs Field cross-origin isolation');
});
if (!binding) show('Missing Field output binding');
else { poll(); setInterval(poll, 150); if (autosave) setInterval(saveIfChanged, 1000); }

window.fieldPatchy = {
  get ready() { return ready && connected; },
  get iframe() { return frame; },
};
