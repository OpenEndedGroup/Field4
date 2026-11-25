// The bar above a `Box.web(...)` page: back, forward and an address field (`.field-web`, written
// by `webPanel` in LiveOutputIframe.kt). The page is a cross-origin iframe, so this page cannot
// read its location; Field's Electron window can, through the live output-iframe bridge, which
// evaluates `location.href` inside the frame. Elsewhere the address is what the bar last loaded.
//
// History is the bar's own, one list per page: the tab's history would also step through every
// other output iframe and could take the Field page itself back.
(() => {
  const PREFIX = "field-live-iframe-";
  const pages = new WeakMap();

  function stateOf(frame) {
    let state = pages.get(frame);
    if (!state) {
      // `Box.web` names the frame in its HTML; Electron knows a frame only by that first name.
      const name = frame.getAttribute("data-field-live-name") || "";
      state = { history: [frame.src], index: 0, pending: frame.src, asking: false,
        name: name.startsWith(PREFIX) ? name : null };
      pages.set(frame, state);
    }
    return state;
  }

  function partsOf(frame) {
    const panel = frame.closest(".field-web");
    return panel && {
      back: panel.querySelector('[data-field-web="back"]'),
      forward: panel.querySelector('[data-field-web="forward"]'),
      address: panel.querySelector(".field-web-url"),
    };
  }

  function show(frame) {
    const state = stateOf(frame);
    const parts = partsOf(frame);
    if (!parts) return;
    parts.back.disabled = state.index <= 0;
    parts.forward.disabled = state.index >= state.history.length - 1;
    if (document.activeElement !== parts.address) parts.address.value = state.history[state.index];
  }

  // A navigation this bar asked for settles into its own entry (following any redirect); any
  // other change of address is a link followed inside the page, and becomes a new entry.
  function observed(frame, url) {
    if (!url || url === "about:blank") return;
    const state = stateOf(frame);
    if (state.pending !== null) {
      state.history[state.index] = url;
      state.pending = null;
    } else if (url !== state.history[state.index]) {
      state.history.splice(state.index + 1);
      state.history.push(url);
      state.index = state.history.length - 1;
    }
    show(frame);
  }

  async function ask(frame) {
    const state = stateOf(frame);
    const bridge = window.fieldElectron?.outputIframe;
    if (!bridge || !state.name || state.asking || !frame.isConnected) return;
    state.asking = true;
    try {
      const answer = await bridge({
        id: state.name.slice(PREFIX.length), frameName: state.name, frameUrl: state.history[state.index],
        operation: "evaluate", javascript: "location.href", timeoutMillis: 2000,
      });
      if (typeof answer?.value === "string") observed(frame, answer.value);
    } catch (_) {
      // A page mid-navigation, or a tab that is not the active one: the next look will do.
    } finally {
      state.asking = false;
    }
  }

  function go(frame, url) {
    const state = stateOf(frame);
    state.pending = url;
    frame.src = url;
    show(frame);
  }

  function address(text) {
    const trimmed = text.trim();
    if (!trimmed) return null;
    return /^[a-zA-Z][a-zA-Z0-9+.-]*:/.test(trimmed) ? trimmed : "https://" + trimmed;
  }

  const frameOf = element => element.closest(".field-web")?.querySelector("iframe.field-web-page");

  document.addEventListener("click", event => {
    const button = event.target.closest?.("[data-field-web]");
    const frame = button && frameOf(button);
    if (!frame || button.disabled) return;
    const state = stateOf(frame);
    const step = button.dataset.fieldWeb === "back" ? -1 : 1;
    const index = state.index + step;
    if (index < 0 || index >= state.history.length) return;
    state.index = index;
    go(frame, state.history[index]);
  });

  document.addEventListener("keydown", event => {
    const input = event.target.closest?.(".field-web-url");
    const frame = input && frameOf(input);
    if (!frame) return;
    if (event.key === "Enter") {
      const url = address(input.value);
      if (!url) return;
      const state = stateOf(frame);
      state.history.splice(state.index + 1);
      state.history.push(url);
      state.index = state.history.length - 1;
      go(frame, url);
      input.blur();
    } else if (event.key === "Escape") {
      const state = stateOf(frame);
      input.value = state.history[state.index];
      input.blur();
    }
    event.stopPropagation();
  }, true);

  // Load does not bubble, but a capturing listener on the document hears every iframe's.
  document.addEventListener("load", event => {
    const frame = event.target;
    if (!(frame instanceof HTMLIFrameElement) || !frame.classList.contains("field-web-page")) return;
    stateOf(frame);
    if (window.fieldElectron?.outputIframe) ask(frame);
    else observed(frame, frame.src);
  }, true);

  // Sites that change their address without a load (history.pushState) are only seen by looking.
  setInterval(() => {
    if (document.hidden) return;
    for (const frame of document.querySelectorAll("iframe.field-web-page")) ask(frame);
  }, 1500);
})();
