// One webRequest pipeline per Electron session.
//
// Electron keeps a single listener per webRequest event per session: registering a second
// `onHeadersReceived` silently replaces the first. Field has several things that want to see
// requests (framing relief, cookie relief, the content blocker, and later rules written from box
// code), so each session gets one pipeline that owns every listener and runs named handlers in
// order. Nothing else in Field should call `session.webRequest.on*` directly.
//
// Handlers return a result (or a promise of one) instead of calling Electron's callback:
//
//   onBeforeRequest      -> { cancel: true } | { redirectURL } | null. The first handler that
//                           cancels or redirects decides; later handlers are not asked.
//   onBeforeSendHeaders  -> { requestHeaders } | { cancel: true } | null. Headers thread through:
//                           each handler sees the previous handler's headers.
//   onHeadersReceived    -> { responseHeaders, statusLine? } | { cancel: true } | null. Same.
//
// Each handler gets its own copy of the details, with the headers as they stand after the
// handlers before it, so a handler that mutates its argument cannot leak into another one. A
// handler that throws or rejects is logged and skipped; the request goes on as if it had returned
// null. The observing events (onSendHeaders, onResponseStarted, onBeforeRedirect, onCompleted,
// onErrorOccurred) fan out to every observer and cannot change anything.

const DECIDING = ["onBeforeRequest", "onBeforeSendHeaders", "onHeadersReceived"];
const OBSERVING = ["onSendHeaders", "onResponseStarted", "onBeforeRedirect", "onCompleted", "onErrorOccurred"];
const HEADER_KEY = { onBeforeSendHeaders: "requestHeaders", onHeadersReceived: "responseHeaders" };

function cloneHeaders(headers) {
  if (!headers) return headers;
  const copy = {};
  for (const [name, value] of Object.entries(headers)) copy[name] = Array.isArray(value) ? [...value] : value;
  return copy;
}

class WebRequestPipeline {
  constructor(session, log = () => {}) {
    this.session = session;
    this.log = log;
    this.handlers = Object.fromEntries([...DECIDING, ...OBSERVING].map(event => [event, []]));
    this.installed = new Set();
  }

  // Adds [handler] for [event] under [name]; a second add with the same name replaces the first
  // in place, keeping its position. [order] (default 0) sorts handlers, lower first. Returns a
  // function that removes it.
  add(event, name, handler, { order = 0 } = {}) {
    const list = this.handlers[event];
    if (!list) throw new Error(`unknown webRequest event ${event}`);
    const entry = { name, handler, order };
    const existing = list.findIndex(h => h.name === name);
    if (existing >= 0) list[existing] = entry;
    else list.push(entry);
    list.sort((a, b) => a.order - b.order);
    this.installListener(event);
    return () => this.remove(event, name);
  }

  remove(event, name) {
    const list = this.handlers[event];
    const index = list ? list.findIndex(h => h.name === name) : -1;
    if (index >= 0) list.splice(index, 1);
  }

  // The names of the handlers on each event, in order: what `status` reports.
  describe() {
    const result = {};
    for (const [event, list] of Object.entries(this.handlers)) {
      if (list.length) result[event] = list.map(h => h.name);
    }
    return result;
  }

  installListener(event) {
    if (this.installed.has(event) || !this.session?.webRequest) return;
    this.installed.add(event);
    if (DECIDING.includes(event)) {
      this.session.webRequest[event]((details, callback) => {
        this.decide(event, details).then(callback, error => {
          this.log("web-request-pipeline-error", { event, error: String(error?.stack || error) });
          callback({});
        });
      });
    } else {
      this.session.webRequest[event](details => this.observe(event, details));
    }
  }

  // Runs the handlers for a deciding event and resolves to the object Electron's callback wants.
  async decide(event, details) {
    const key = HEADER_KEY[event];
    let headers = key ? details[key] : undefined;
    let statusLine;
    let changed = false;
    for (const { name, handler } of this.handlers[event]) {
      const view = key ? { ...details, [key]: cloneHeaders(headers) } : { ...details };
      let result;
      try {
        result = await handler(view);
      } catch (error) {
        this.log("web-request-handler-error", { event, handler: name, url: details.url, error: String(error?.stack || error) });
        continue;
      }
      if (!result) continue;
      if (result.cancel) return { cancel: true };
      if (event === "onBeforeRequest" && result.redirectURL) return { redirectURL: result.redirectURL };
      if (key && result[key]) { headers = result[key]; changed = true; }
      if (result.statusLine) { statusLine = result.statusLine; changed = true; }
    }
    if (!changed) return {};
    const reply = { [key]: headers };
    if (statusLine) reply.statusLine = statusLine;
    return reply;
  }

  observe(event, details) {
    for (const { name, handler } of this.handlers[event]) {
      try {
        handler(details);
      } catch (error) {
        this.log("web-request-handler-error", { event, handler: name, url: details.url, error: String(error?.stack || error) });
      }
    }
  }
}

// Adapts a handler written against Electron's (details, callback) signature, such as a
// third-party library's, to the pipeline's return-a-result form.
function fromCallbackHandler(handler) {
  return details => new Promise(resolve => handler(details, response => resolve(response || null)));
}

const pipelines = new WeakMap();

// The pipeline that owns [session]'s webRequest listeners, made on first use.
function pipelineFor(session, log) {
  let pipeline = pipelines.get(session);
  if (!pipeline) {
    pipeline = new WebRequestPipeline(session, log);
    pipelines.set(session, pipeline);
  }
  return pipeline;
}

module.exports = { WebRequestPipeline, pipelineFor, fromCallbackHandler, DECIDING, OBSERVING };
