// Stand-in for the compiled Kotlin/JS bundle that `fieldSuggest.js` imports three things from.
//
// This is the harness's one deliberate seam. `afield.mjs` is webpack output — depending on it would
// make a unit test require a Kotlin/JS compile, and it would drag in the whole editor. So the
// transport is replaced by a table of canned server replies (which is what makes a payload-in →
// popup-HTML-out assertion possible at all), and the markdown renderer by a marker-emitting stub.
//
// The cost is stated rather than hidden: what markdown the doc panel renders is NOT covered here,
// only that the panel calls the renderer, in the right order, around the right chunks.

/** name -> (payload) => reply. Set by the probe before it opens the popup. */
export const rpc = new Map();

/** Every request the popup made, in order: `{ name, payload, replyName }`. */
export const requests = [];

/**
 * Default: an unregistered RPC answers `null`, the way a server that has nothing to say does. That
 * matters mechanically as well as semantically — `requestWithReply` arms a 15 second timeout, and
 * never answering would keep node's event loop alive for 15s per unanswered request. Register the
 * string `"never"` for a case that genuinely wants to test the timeout path.
 */
export const sendWithReply = (name, payload, replyName, callback) => {
  requests.push({ name, payload, replyName });
  const responder = rpc.has(name) ? rpc.get(name) : null;
  if (responder === "never") return;
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
