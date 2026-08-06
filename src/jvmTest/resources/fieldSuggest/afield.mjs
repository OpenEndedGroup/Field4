// Stand-in for the compiled Kotlin/JS bundle that `fieldSuggest.js` imports three things from.
//
// This is the harness's one deliberate seam. `afield.mjs` is webpack output — depending on it would
// make a unit test require a Kotlin/JS compile, and it would drag in the whole editor. So the
// transport is replaced by a table of canned server replies (which is what makes a payload-in →
// popup-HTML-out assertion possible at all), and the markdown renderer by a marker-emitting stub.
//
// The cost is stated rather than hidden: what markdown the doc panel renders is NOT covered here,
// only that the panel calls the renderer, in the right order, around the right chunks.

/**
 * name -> reply, `(payload) => reply`, `defer(…)` to park it, or the string `"never"`. Set by the
 * probe before it opens the popup. An unregistered name answers `null`.
 */
export const rpc = new Map();

/** Every request the popup made, in order: `{ name, payload, replyName }`. */
export const requests = [];

/**
 * Default: an unregistered RPC answers `null`, the way a server that has nothing to say does. That
 * matters mechanically as well as semantically — `requestWithReply` arms a 15 second timeout, and
 * never answering would keep node's event loop alive for 15s per unanswered request. Register the
 * string `"never"` for a case that genuinely wants to test the timeout path.
 */
/** Replies parked by a `defer(…)` responder, oldest first. Release them with [flush]. */
const deferred = [];

/**
 * A responder whose reply only lands when the probe says so. Needed to observe the popup's
 * intermediate states at all — above all the one its own comments care about, that a slow AI round
 * trip must never hold up the base list.
 */
export const defer = (responder) => ({ __deferred: responder });

/** Release parked replies (all of them, or only those for `name`). Returns how many fired. */
export const flush = (name = null) => {
  const ready = deferred.filter((entry) => name === null || entry.name === name);
  for (const entry of ready) {
    deferred.splice(deferred.indexOf(entry), 1);
    entry.fire();
  }
  return ready.length;
};

export const sendWithReply = (name, payload, replyName, callback) => {
  requests.push({ name, payload, replyName });
  const responder = rpc.has(name) ? rpc.get(name) : null;
  if (responder === "never") return;
  if (responder && responder.__deferred !== undefined) {
    const inner = responder.__deferred;
    deferred.push({
      name,
      fire: () => callback(null, typeof inner === "function" ? inner(payload) : inner),
    });
    return;
  }
  const reply = typeof responder === "function" ? responder(payload) : responder;
  if (reply === undefined) return;
  // Synchronous reply. `requestWithReply` resolves its promise here and clears its 15s timer, so a
  // finished probe lets node exit naturally instead of idling for the RPC timeout.
  callback(null, reply);
};

export const send = (name, payload) => {
  requests.push({ name, payload, replyName: null });
};

export const clearReplyHandler = () => {};

/**
 * Marker stub for the real markdown renderer. Wrapping rather than dropping the text keeps the doc
 * panel's chunk order and content assertable while making it obvious in any failure message that
 * this is not the real renderer.
 */
export const aiChatMarkdownToHtml = (markdown) =>
  `<div class="stub-markdown">${String(markdown ?? "")}</div>`;

export const aiChatHistoryToHtml = () => "";
export const monacoHandleOutput = () => {};
export const clearEditorOutputsForBoxBeforeLaunch = () => {};
