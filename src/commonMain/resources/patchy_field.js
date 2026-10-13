import {outputRequests} from './output_requests.js';

const binding = new URLSearchParams(location.search).get('binding');
const frame = document.getElementById('patchy');
const status = document.getElementById('status');
const requests = binding ? outputRequests(binding) : null;
const page = crypto.randomUUID();
let ready = false;
let connected = false;
let inFlight = false;
let result = null;
let commandInFlight = false;
const commandQueue = [];

function show(message) { status.textContent = message; status.hidden = !message; }

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
      ready = !!data.message.result;
      show(ready ? '' : 'Patchy control could not initialize');
    } else {
      result = data.message;
      commandInFlight = false;
    }
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
    if (ready) show('');
    commandQueue.push(...(reply?.commands || []));
    if (!commandInFlight && !result && commandQueue.length) {
      commandInFlight = true;
      frame.contentWindow.postMessage({type: 'field-patchy-request', message: commandQueue.shift()}, location.origin);
    }
  } catch (error) {
    connected = false;
    show(`Field connection: ${error.message || error}`);
  } finally {
    inFlight = false;
  }
}

frame.addEventListener('load', () => {
  ready = false;
  commandInFlight = false;
  commandQueue.length = 0;
  if (!ready && !crossOriginIsolated) show('Patchy needs Field cross-origin isolation');
});
if (!binding) show('Missing Field output binding');
else { poll(); setInterval(poll, 150); }

window.fieldPatchy = {
  get ready() { return ready && connected; },
  get iframe() { return frame; },
};
