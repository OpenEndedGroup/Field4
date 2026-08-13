(function () {
  const params = new URLSearchParams(window.location.search);
  const area = document.querySelector("#touch-area");
  const thumb = document.querySelector("#thumb");
  const connection = document.querySelector("#connection");
  const readout = document.querySelector("#readout");
  const saveValue = document.querySelector("#save-value");
  const restoreValue = document.querySelector("#restore-value");
  const lockBinding = document.querySelector("#lock-binding");
  const unlockBinding = document.querySelector("#unlock-binding");
  const launch = document.querySelector("#launch");
  const stop = document.querySelector("#stop");
  const padControls = document.querySelector("#pad-controls");
  const groupState = document.querySelector("#group-state");
  const groupControls = document.querySelector("#group-controls");
  const outputsState = document.querySelector("#outputs-state");
  const outputsControls = document.querySelector("#outputs-controls");
  const outputsList = document.querySelector("#outputs-list");
  const chatState = document.querySelector("#chat-state");
  const selectState = document.querySelector("#select-state");
  const editorState = document.querySelector("#editor-state");
  const chatFrameHost = document.querySelector("#chat-frame-host");
  const selectFrameHost = document.querySelector("#select-frame-host");
  const editorFrameHost = document.querySelector("#editor-frame-host");

  const pages = ["pad", "controls", "outputs", "chat", "select", "editor"];

  const state = {
    socket: null,
    reconnectTimer: null,
    controlPollTimer: null,
    activePointerId: null,
    activeControlId: null,
    last: { x: 0.5, y: 0.5 },
    page: pages.includes(params.get("page")) ? params.get("page") : "pad",
    axis: ["x", "y", "xy"].includes(params.get("axis")) ? params.get("axis") : "y",
    sendMode: ["absolute", "delta", "both"].includes(params.get("send")) ? params.get("send") : "absolute",
    debounceMs: clampNumber(Number.parseInt(params.get("debounceMs") || "200", 10), 0, 5000),
    maxExecutionMs: clampNumber(Number.parseInt(params.get("maxExecutionMs") || "750", 10), 0, 60000),
    controlsSignature: "",
    outputsSignature: "",
    controlInfo: null,
    outputsInfo: null,
    chatFrame: null,
    workspaceFrame: null,
    workspaceReady: false,
    resolvedDocument: params.get("file") || params.get("document") || "",
  };

  function targetQuery() {
    const query = new URLSearchParams();
    const file = params.get("file") || params.get("document");
    if (file) query.set("file", file);
    if (params.get("scratch")) query.set("scratch", params.get("scratch"));
    if (params.get("sessionKey")) query.set("sessionKey", params.get("sessionKey"));
    return query.toString();
  }

  function connect() {
    const protocol = window.location.protocol === "https:" ? "wss" : "ws";
    const query = targetQuery();
    state.socket = new WebSocket(`${protocol}://${window.location.host}/controllers/ws?${query}`);

    state.socket.addEventListener("open", () => {
      connection.textContent = "online";
    });

    state.socket.addEventListener("close", () => {
      connection.textContent = "offline";
      window.clearTimeout(state.reconnectTimer);
      state.reconnectTimer = window.setTimeout(connect, 500);
    });

    state.socket.addEventListener("error", () => {
      connection.textContent = "error";
    });
  }

  function clamp(value, min, max) {
    return Math.min(Math.max(value, min), max);
  }

  function clampNumber(value, min, max) {
    if (!Number.isFinite(value)) return min;
    return clamp(value, min, max);
  }

  function formatNumber(value, places) {
    if (!Number.isFinite(value)) return "0";
    const digits = clampNumber(Number.parseInt(String(places), 10), 0, 6);
    return Number(value).toFixed(digits);
  }

  function pointerToValues(event, phase) {
    const rect = area.getBoundingClientRect();
    const px = clamp(event.clientX - rect.left, 0, rect.width);
    const py = clamp(event.clientY - rect.top, 0, rect.height);
    const x = rect.width ? px / rect.width : 0;
    const y = rect.height ? 1 - py / rect.height : 0;
    const dx = phase === "start" ? 0 : x - state.last.x;
    const dy = phase === "start" ? 0 : y - state.last.y;

    state.last = { x, y };
    return { phase, x, y, dx, dy };
  }

  function renderPad(values) {
    thumb.style.left = `${clamp(values.x, 0, 1) * 100}%`;
    thumb.style.top = `${(1 - clamp(values.y, 0, 1)) * 100}%`;
    const ariaValue = state.axis === "x" ? values.x : values.y;
    thumb.setAttribute("aria-valuenow", String(Math.round(clamp(ariaValue, 0, 1) * 1000)));
    if (state.page === "pad") readout.textContent = `x ${values.x.toFixed(3)} y ${values.y.toFixed(3)}`;
  }

  function renderGroupReadout() {
    if (state.page !== "controls") return;
    const info = state.controlInfo;
    if (!info || !info.selectedUid) {
      readout.textContent = "no group";
      return;
    }
    readout.textContent = `${info.selectedName || info.selectedUid} ${info.controls.length}`;
  }

  function send(payload) {
    if (!state.socket || state.socket.readyState !== WebSocket.OPEN) return;
    state.socket.send(JSON.stringify({
      debounceMs: state.debounceMs,
      maxExecutionMs: state.maxExecutionMs,
      ...payload,
    }));
  }

  function sendTouch(values) {
    send({
      type: "touch",
      sendMode: state.sendMode,
      valueAxis: state.axis,
      ...values,
    });
  }

  function sendRelease(values) {
    send({
      type: "release",
      phase: "release",
      valueAxis: state.axis,
      x: values.x,
      y: values.y,
      dx: values.dx || 0,
      dy: values.dy || 0,
    });
  }

  function commandLabel(command) {
    switch (command) {
      case "save": return "saved";
      case "restore": return "restored";
      case "lock": return "locked";
      case "unlock": return "unlocked";
      case "launch": return "launch";
      case "stop": return "stop";
      default: return command;
    }
  }

  function flashCommandButton(button, command) {
    if (!button) return;
    button.classList.remove("command-feedback");
    void button.offsetWidth;
    button.classList.add("command-feedback");
    readout.textContent = commandLabel(command).toUpperCase();
    window.clearTimeout(button._fieldFeedbackTimer);
    button._fieldFeedbackTimer = window.setTimeout(() => {
      button.classList.remove("command-feedback");
      if (state.page === "pad") renderPad({ x: state.last.x, y: state.last.y, dx: 0, dy: 0 });
      else renderGroupReadout();
    }, 420);
  }

  function sendCommand(command, button) {
    flashCommandButton(button, command);
    send({
      type: "command",
      command,
      valueAxis: state.axis,
      x: 1,
      y: 1,
    });
  }

  function handlePointer(event, phase) {
    if (state.activePointerId !== null && event.pointerId !== state.activePointerId) return;
    event.preventDefault();
    const values = pointerToValues(event, phase);
    renderPad(values);
    sendTouch(values);
  }

  function releasePointer(event) {
    if (state.activePointerId === null || event.pointerId !== state.activePointerId) return;
    event.preventDefault();
    const values = pointerToValues(event, "release");
    renderPad(values);
    sendTouch(values);
    sendRelease(values);
    state.activePointerId = null;
  }

  function setActive(selector, attr, value) {
    document.querySelectorAll(selector).forEach((button) => {
      button.classList.toggle("active", button.getAttribute(attr) === value);
    });
  }

  function setPage(page) {
    state.page = pages.includes(page) ? page : "pad";
    const visiblePage = state.page === "editor" ? "select" : state.page;
    document.querySelectorAll("[data-controller-page]").forEach((element) => {
      element.classList.toggle("active", element.getAttribute("data-controller-page") === visiblePage);
    });
    padControls.style.display = state.page === "pad" ? "" : "none";
    setActive("[data-page]", "data-page", state.page);
    if (state.page === "controls") {
      fetchControlGroup();
      renderGroupReadout();
    } else if (state.page === "outputs") {
      fetchOutputs();
      renderGroupReadout();
    } else if (state.page === "chat") {
      ensureChatFrame();
    } else if (state.page === "select") {
      ensureWorkspaceFrame(selectFrameHost, true);
    } else if (state.page === "editor") {
      ensureWorkspaceFrame(selectFrameHost, false);
    } else {
      renderPad({ x: state.last.x, y: state.last.y, dx: 0, dy: 0 });
    }
  }

  async function resolveDocument() {
    if (state.resolvedDocument && state.controlInfo) return state.resolvedDocument;
    try {
      const response = await fetch(controlsUrl(), { cache: "no-store" });
      if (response.ok) {
        const info = await response.json();
        state.controlInfo = info;
        if (!state.resolvedDocument) state.resolvedDocument = String(info.document || "").trim();
      }
    } catch (_error) {
    }
    return state.resolvedDocument;
  }

  function fieldProjectionUrl(documentName, extra) {
    const query = new URLSearchParams();
    query.set("file", documentName);
    query.set("shaderSelectionContext", `controllers-mobile:${documentName}`);
    query.set("controllerMobile", "1");
    if (params.get("scratch")) query.set("scratch", params.get("scratch"));
    if (params.get("sessionKey")) query.set("sessionKey", params.get("sessionKey"));
    Object.entries(extra).forEach(([key, value]) => query.set(key, value));
    return `/index.html?${query.toString()}`;
  }

  function applyKnownSelection(frame) {
    const info = state.controlInfo;
    const win = frame && frame.contentWindow;
    if (!info || !info.selectedUid || !win || !win.fieldBackdoorUiReady) return;
    const selected = win.selectedList;
    const currentUid = selected && selected.length === 1 && selected[0] && selected[0].uid || "";
    if (currentUid === info.selectedUid && (win.selectedProperty || "code") === (info.selectedProperty || "code")) return;
    if (typeof win.navigateToBoxProperty === "function") {
      win.navigateToBoxProperty(info.selectedUid, info.selectedProperty || "code");
    }
  }

  async function ensureChatFrame() {
    const documentName = await resolveDocument();
    if (state.page !== "chat") return;
    if (!documentName) {
      chatState.textContent = "Open a Field document first";
      return;
    }
    chatState.textContent = "AI4 · follows the selected box";
    if (!state.chatFrame) {
      const frame = document.createElement("iframe");
      frame.className = "remote-frame";
      frame.title = "AI4 chat for the selected Field box";
      frame.allow = "clipboard-read; clipboard-write";
      frame.src = fieldProjectionUrl(documentName, { noeditor: "1", controllerChat: "1" });
      frame.addEventListener("load", () => {
        const ready = () => {
          const win = frame.contentWindow;
          if (!win || !win.fieldBackdoorUiReady) {
            window.setTimeout(ready, 100);
            return;
          }
          applyKnownSelection(frame);
        };
        ready();
      });
      state.chatFrame = frame;
    }
    if (!chatFrameHost.contains(state.chatFrame)) chatFrameHost.replaceChildren(state.chatFrame);
  }

  function workspaceWindow() {
    return state.workspaceFrame && state.workspaceFrame.contentWindow;
  }

  function selectedWorkspaceBox() {
    const win = workspaceWindow();
    const selected = win && win.selectedList;
    return selected && selected.length === 1 ? selected[0] : null;
  }

  function updateWorkspaceState() {
    const box = selectedWorkspaceBox();
    const win = workspaceWindow();
    const name = box && (box.name || box.getAttribute?.("data-name") || box.textContent || "");
    const property = win && win.selectedProperty || "code";
    const label = box ? `${String(name).trim() || "Selected box"} · ${property}` : "Select a box or property";
    selectState.textContent = label;
    editorState.textContent = box ? label : "Select a box to edit";
  }

  function openWorkspacePalette() {
    const win = workspaceWindow();
    if (!win || !state.workspaceReady) return;
    if (typeof win.openAnywherePalette === "function") {
      win.openAnywherePalette();
    } else {
      window.setTimeout(openWorkspacePalette, 120);
    }
  }

  function workspacePaletteIsOpen() {
    const win = workspaceWindow();
    const palette = win && win.document && win.document.querySelector(".afield-palette");
    return !!palette && win.getComputedStyle(palette).display !== "none";
  }

  function closeWorkspacePalette() {
    const win = workspaceWindow();
    if (!win) return;
    const palette = win.document && win.document.querySelector(".afield-palette");
    if (!palette || palette.style.display === "none") return;
    win.document.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape", bubbles: true }));
  }

  function attachWorkspaceListeners(frame) {
    const win = frame.contentWindow;
    if (!win) return;
    win.addEventListener("field-selection-changed", () => {
      window.setTimeout(() => {
        updateWorkspaceState();
        if (state.page === "select" && selectedWorkspaceBox() && !workspacePaletteIsOpen()) setPage("editor");
      }, 260);
    });
  }

  async function ensureWorkspaceFrame(host, openPalette) {
    const documentName = await resolveDocument();
    if (!["select", "editor"].includes(state.page) || host !== selectFrameHost) return;
    if (!documentName) {
      (openPalette ? selectState : editorState).textContent = "Open a Field document first";
      return;
    }
    if (!state.workspaceFrame) {
      const frame = document.createElement("iframe");
      frame.className = "remote-frame workspace-frame";
      frame.title = "Field box picker and editor";
      frame.allow = "clipboard-read; clipboard-write";
      frame.src = fieldProjectionUrl(documentName, { editorOnly: "1" });
      frame.addEventListener("load", () => {
        const ready = () => {
          const win = frame.contentWindow;
          if (!win || !win.fieldBackdoorUiReady) {
            window.setTimeout(ready, 100);
            return;
          }
          state.workspaceReady = true;
          attachWorkspaceListeners(frame);
          applyKnownSelection(frame);
          updateWorkspaceState();
          if (state.page === "select") openWorkspacePalette();
        };
        ready();
      });
      state.workspaceFrame = frame;
    }
    if (!host.contains(state.workspaceFrame)) host.replaceChildren(state.workspaceFrame);
    if (openPalette) openWorkspacePalette(); else closeWorkspacePalette();
    updateWorkspaceState();
  }

  function controlsUrl() {
    const query = targetQuery();
    return query ? `/controllers/controls?${query}` : "/controllers/controls";
  }

  function outputsUrl() {
    const query = targetQuery();
    return query ? `/controllers/outputs?${query}` : "/controllers/outputs";
  }

  async function fetchControlGroup() {
    if (state.page !== "controls") return;
    try {
      const response = await fetch(controlsUrl(), { cache: "no-store" });
      if (!response.ok) return;
      const info = await response.json();
      state.controlInfo = info;
      if (!state.resolvedDocument && info.document) state.resolvedDocument = String(info.document);
      renderControlGroup(info);
    } catch (_error) {
      if (!state.controlInfo) {
        groupState.textContent = "Controls unavailable";
      }
    }
  }

  async function fetchOutputs() {
    if (state.page !== "outputs") return;
    try {
      const response = await fetch(outputsUrl(), { cache: "no-store" });
      if (!response.ok) return;
      const info = await response.json();
      state.outputsInfo = info;
      if (!state.resolvedDocument && info.document) state.resolvedDocument = String(info.document);
      renderOutputs(info);
    } catch (_error) {
      if (!state.outputsInfo) outputsState.textContent = "Outputs unavailable";
    }
  }

  function renderOutputs(info) {
    const outputs = Array.isArray(info.outputs) ? info.outputs : [];
    const controls = Array.isArray(info.controls) ? info.controls : [];
    const visibleOutputs = outputs.filter((output) => !isNativeControlDuplicate(output, controls));
    const signature = JSON.stringify({ uid: info.selectedUid || "", outputs: visibleOutputs, controls });
    if (signature === state.outputsSignature || state.activeControlId) return;
    state.outputsSignature = signature;
    outputsControls.replaceChildren();
    outputsList.replaceChildren();
    if (!info.selectedUid) {
      outputsState.textContent = "Select one box in Field";
      return;
    }
    outputsState.textContent = `${info.selectedName || info.selectedUid} · ${visibleOutputs.length} output${visibleOutputs.length === 1 ? "" : "s"}`;
    controls.forEach((control) => outputsControls.appendChild(renderControl(control)));
    visibleOutputs.forEach((output) => outputsList.appendChild(renderOutputArea(output)));
    if (!controls.length && !visibleOutputs.length) outputsState.textContent = `${info.selectedName || "selected"} has no outputs yet`;
    if (controls.length && !visibleOutputs.length) outputsState.textContent = `${info.selectedName || "selected"} · controls only`;
  }

  function isNativeControlDuplicate(output, controls) {
    if (!controls.length) return false;
    const html = String(output && output.html || "");
    return html.includes("class=\"control-group\"") ||
      html.includes("class='control-group'") ||
      html.includes("field-control field-slider") ||
      html.includes("field-control field-vec2") ||
      html.includes("field-control field-color");
  }

  function isLinesOutput(output) {
    return String(output && output.html || "").includes("data-afield-lines-output=\"true\"");
  }

  function isFLineEditorOutput(output) {
    return String(output && output.html || "").includes("data-field-fline-editor=\"true\"");
  }

  function fLineEditorAspect(output) {
    const html = String(output && output.html || "");
    if (!html) return null;
    try {
      const doc = new DOMParser().parseFromString(html, "text/html");
      const sceneScript = doc.querySelector(".field-fline-editor-scene");
      const scene = sceneScript ? JSON.parse(sceneScript.textContent || "{}") : null;
      const width = Number(scene && scene.width);
      const height = Number(scene && scene.height);
      if (Number.isFinite(width) && Number.isFinite(height) && width > 0 && height > 0) {
        return { width, height, value: width / height };
      }
    } catch (_error) {
      return null;
    }
    return null;
  }

  function renderOutputArea(output) {
    const linesOutput = isLinesOutput(output);
    const flineEditorOutput = isFLineEditorOutput(output);
    const flineEditorFrame = flineEditorOutput ? fLineEditorAspect(output) : null;
    const shell = document.createElement("article");
    shell.className = "phone-output-card";
    shell.classList.toggle("phone-output-lines", linesOutput || flineEditorOutput);
    const title = document.createElement("div");
    title.className = "phone-output-title";
    title.textContent = output.channel || "output";
    const frame = document.createElement("iframe");
    frame.className = "phone-output-frame";
    if (linesOutput || flineEditorOutput) frame.classList.add("phone-output-frame-lines");
    if (flineEditorFrame) {
      frame.dataset.outputKind = "fline-editor";
      frame.dataset.flineEditorAspect = String(flineEditorFrame.value);
    }
    frame.setAttribute("sandbox", "allow-scripts allow-forms allow-same-origin allow-popups");
    frame.srcdoc = outputFrameHtml(output, linesOutput, flineEditorOutput, flineEditorFrame);
    frame.addEventListener("load", () => resizeOutputFrame(frame, true));
    shell.append(title, frame);
    return shell;
  }

  function outputFrameHtml(output, linesOutput, flineEditorOutput, flineEditorFrame) {
    const modeClass = [
      linesOutput ? "field-controller-lines-frame" : "",
      flineEditorOutput ? "field-controller-fline-editor-frame" : "",
    ].filter(Boolean).join(" ");
    const flineAspectStyle = flineEditorFrame
      ? ` style="--field-fline-editor-aspect:${flineEditorFrame.width} / ${flineEditorFrame.height}"`
      : "";
    const flineEditorRuntime = flineEditorOutput ? `<script src="/sliders.js"></script>` : "";
    const linesScript = linesOutput ? `
      <script>
        (function () {
          function number(value, fallback) {
            var n = Number(value);
            return Number.isFinite(n) ? n : fallback;
          }

          function fitLinesOutput() {
            document.querySelectorAll(".afield-lines-output").forEach(function (container) {
              var svg = container.querySelector("svg");
              if (!svg) return;
              if (container.__fieldControllerLinesState) return;

              var x = number(container.dataset.afieldBoundsX, 0);
              var y = number(container.dataset.afieldBoundsY, 0);
              var w = number(container.dataset.afieldBoundsW, 0);
              var h = number(container.dataset.afieldBoundsH, 0);

              if (!(w > 0) || !(h > 0)) {
                var existing = (svg.getAttribute("viewBox") || "").trim().split(/\\s+/).map(Number);
                if (existing.length === 4 && existing.every(Number.isFinite)) {
                  x = existing[0];
                  y = existing[1];
                  w = existing[2];
                  h = existing[3];
                } else {
                  w = number(svg.getAttribute("width"), svg.clientWidth || 1);
                  h = number(svg.getAttribute("height"), svg.clientHeight || 1);
                }
              }

              w = Math.max(w, 1);
              h = Math.max(h, 1);
              var pad = Math.max(2, Math.max(w, h) * 0.04);
              var viewX = x - pad;
              var viewY = y - pad;
              var viewW = w + pad * 2;
              var viewH = h + pad * 2;
              var state = {
                base: { x: viewX, y: viewY, w: viewW, h: viewH },
                view: { x: viewX, y: viewY, w: viewW, h: viewH },
                dragging: false,
                lastX: 0,
                lastY: 0
              };
              container.__fieldControllerLinesState = state;

              function applyView() {
                svg.setAttribute("viewBox", [state.view.x, state.view.y, state.view.w, state.view.h].join(" "));
                svg.style.aspectRatio = state.view.w + " / " + state.view.h;
                container.style.aspectRatio = state.view.w + " / " + state.view.h;
              }

              function svgPoint(event) {
                var rect = svg.getBoundingClientRect();
                var px = rect.width ? (event.clientX - rect.left) / rect.width : 0.5;
                var py = rect.height ? (event.clientY - rect.top) / rect.height : 0.5;
                return {
                  x: state.view.x + px * state.view.w,
                  y: state.view.y + py * state.view.h
                };
              }

              function zoomAt(event, factor) {
                var point = svgPoint(event);
                var nextW = Math.max(0.001, state.view.w * factor);
                var nextH = Math.max(0.001, state.view.h * factor);
                state.view.x = point.x - (point.x - state.view.x) * (nextW / state.view.w);
                state.view.y = point.y - (point.y - state.view.y) * (nextH / state.view.h);
                state.view.w = nextW;
                state.view.h = nextH;
                applyView();
              }

              svg.addEventListener("wheel", function (event) {
                event.preventDefault();
                zoomAt(event, Math.exp(event.deltaY * 0.001));
              }, { passive: false });

              svg.addEventListener("pointerdown", function (event) {
                if (event.button != null && event.button !== 0) return;
                state.dragging = true;
                state.lastX = event.clientX;
                state.lastY = event.clientY;
                svg.setPointerCapture && svg.setPointerCapture(event.pointerId);
                svg.style.cursor = "grabbing";
                event.preventDefault();
              });

              svg.addEventListener("pointermove", function (event) {
                if (!state.dragging) return;
                var rect = svg.getBoundingClientRect();
                var dx = rect.width ? (event.clientX - state.lastX) / rect.width * state.view.w : 0;
                var dy = rect.height ? (event.clientY - state.lastY) / rect.height * state.view.h : 0;
                state.view.x -= dx;
                state.view.y -= dy;
                state.lastX = event.clientX;
                state.lastY = event.clientY;
                applyView();
                event.preventDefault();
              });

              function endDrag(event) {
                state.dragging = false;
                svg.style.cursor = "grab";
                if (event && svg.releasePointerCapture) {
                  try { svg.releasePointerCapture(event.pointerId); } catch (_error) {}
                }
              }

              svg.addEventListener("pointerup", endDrag);
              svg.addEventListener("pointercancel", endDrag);
              svg.addEventListener("dblclick", function () {
                state.view = { x: state.base.x, y: state.base.y, w: state.base.w, h: state.base.h };
                applyView();
              });

              svg.setAttribute("preserveAspectRatio", "xMidYMid meet");
              svg.removeAttribute("height");
              svg.style.width = "100%";
              svg.style.height = "auto";
              svg.style.cursor = "grab";
              svg.style.touchAction = "none";
              applyView();
            });
          }

          if (document.readyState === "loading") {
            document.addEventListener("DOMContentLoaded", fitLinesOutput);
          } else {
            fitLinesOutput();
          }
          window.addEventListener("load", fitLinesOutput);
        })();
      </script>
    ` : "";
    return `<!doctype html><html><head><base target="_blank"><meta name="viewport" content="width=device-width,initial-scale=1"><style>
      html,body{margin:0;background:#050505;color:#ddd;font-family:system-ui,sans-serif;overflow-wrap:anywhere;}
      body{width:100%;min-width:0;}
      img,svg,canvas,video,iframe{max-width:100%;}
      iframe{width:100%;}
      table{max-width:100%;display:block;overflow:auto;}
      .field-controller-lines-frame{overflow:hidden;}
      .field-controller-lines-frame .afield-lines-output{display:block;width:100%;max-width:100%;overflow:hidden;}
      .field-controller-lines-frame .afield-lines-output>svg,
      .field-controller-lines-frame .afield-lines-output svg{display:block;width:100%!important;height:auto!important;max-width:100%;}
      .field-controller-fline-editor-frame{overflow:hidden;}
      .field-controller-fline-editor-frame .field-fline-editor-shell{width:100%!important;max-width:100%!important;overflow:hidden;aspect-ratio:var(--field-fline-editor-aspect,4 / 3);}
      .field-controller-fline-editor-frame .field-fline-editor-canvas{display:block!important;width:100%!important;height:100%!important;max-width:100%!important;}
    </style>${flineEditorRuntime}</head><body class="${modeClass}"${flineAspectStyle}>${output.html || ""}${linesScript}</body></html>`;
  }

  function resizeOutputFrame(frame, scheduleFollowup = false) {
    try {
      if (frame.dataset.outputKind === "fline-editor") {
        const aspect = Number(frame.dataset.flineEditorAspect || "0");
        const width = frame.clientWidth || frame.getBoundingClientRect().width || 320;
        if (Number.isFinite(aspect) && aspect > 0 && width > 0) {
          const height = Math.max(160, Math.min(900, Math.ceil(width / aspect)));
          frame.style.height = `${height + 8}px`;
          if (scheduleFollowup) {
            window.setTimeout(() => resizeOutputFrame(frame), 150);
            window.setTimeout(() => resizeOutputFrame(frame), 500);
          }
          return;
        }
      }
      const doc = frame.contentDocument;
      const root = doc.documentElement;
      const body = doc.body;
      const rootRect = root ? root.getBoundingClientRect() : { height: 0 };
      const bodyRect = body ? body.getBoundingClientRect() : { height: 0 };
      const rawHeight = Math.max(
        root ? root.scrollHeight : 0,
        body ? body.scrollHeight : 0,
        rootRect.height || 0,
        bodyRect.height || 0
      );
      const height = Math.max(96, Math.min(900, Math.ceil(rawHeight)));
      frame.style.height = `${height + 8}px`;
      if (scheduleFollowup) {
        window.setTimeout(() => resizeOutputFrame(frame), 150);
        window.setTimeout(() => resizeOutputFrame(frame), 500);
      }
    } catch (_error) {
      frame.style.height = "220px";
    }
  }

  function renderControlGroup(info) {
    const controls = Array.isArray(info.controls) ? info.controls : [];
    const signature = JSON.stringify({
      uid: info.selectedUid || "",
      property: info.selectedProperty || "",
      controls,
    });
    if (signature === state.controlsSignature || state.activeControlId) {
      renderGroupReadout();
      return;
    }
    state.controlsSignature = signature;
    groupControls.replaceChildren();

    if (!info.selectedUid) {
      groupState.textContent = "Select one box in Field";
      renderGroupReadout();
      return;
    }
    if (!controls.length) {
      groupState.textContent = `${info.selectedName || "selected"} has no controlGroup`;
      renderGroupReadout();
      return;
    }

    groupState.textContent = info.selectedName || info.selectedUid;
    controls.forEach((control) => {
      groupControls.appendChild(renderControl(control));
    });
    renderGroupReadout();
  }

  function renderControl(control) {
    switch (control.type) {
      case "vec2": return renderVec2Control(control);
      case "checkbox": return renderCheckboxControl(control);
      case "string": return renderStringControl(control);
      case "color": return renderColorControl(control);
      default: return renderSliderControl(control);
    }
  }

  function controlShell(control, valueText) {
    const shell = document.createElement("div");
    shell.className = `phone-control phone-control-${control.type || "slider"}`;

    const header = document.createElement("div");
    header.className = "phone-control-header";

    const label = document.createElement("div");
    label.className = "phone-control-label";
    label.textContent = control.label || control.id || "control";

    const value = document.createElement("div");
    value.className = "phone-control-value";
    value.textContent = valueText || "";
    value.dataset.valueFor = control.id;

    header.append(label, value);
    shell.appendChild(header);
    return { shell, value };
  }

  function setControlValueText(controlId, text) {
    document.querySelectorAll(`[data-value-for="${CSS.escape(controlId)}"]`).forEach((value) => { value.textContent = text; });
  }

  /**
   * The law a control record declares, via the module `controllers.html` loads ahead of this file.
   *
   * Returning `null` when `controlLaws.js` is missing is what keeps a stale cache from breaking the
   * tablet outright: every caller below falls back to the linear behaviour this file has always had.
   */
  function lawFor(control) {
    if (!window.FieldControlLaw) return null;
    const spec = window.FieldControlLaw.spec(control);
    return spec.law === "linear" && !spec.significantDigits ? null : spec;
  }

  /** A control's value as text, deferring to the law's adaptive decimals when it has one. */
  function controlValueText(control, spec, value) {
    if (spec) return window.FieldControlLaw.format(spec, value);
    return formatNumber(value, control.decimalPlaces ?? 2);
  }

  function renderSliderControl(control) {
    const min = Number(control.min ?? 0);
    const max = Number(control.max ?? 1);
    const step = Number(control.step ?? 0.01);
    const spec = lawFor(control);
    const value = clampNumber(Number(control.value ?? min), Math.min(min, max), Math.max(min, max));
    const parts = controlShell(control, controlValueText(control, spec, value));

    const input = document.createElement("input");
    input.className = "phone-range";
    input.type = "range";
    if (spec) {
      // A nonlinear control's <input type=range> runs over TRAVEL, 0..1, and the law turns travel into
      // the value — the same way the desktop slider treats a pointer position. Handing the native range
      // element the value bounds instead would put the handle in the wrong place, which is the bug being
      // fixed here rather than a cosmetic difference.
      input.min = "0";
      input.max = "1";
      input.step = "0.001";
      input.value = String(window.FieldControlLaw.normalizedFor(spec, value));
      input.dataset.law = spec.law;
    } else {
      input.min = String(min);
      input.max = String(max);
      input.step = String(step);
      input.value = String(value);
    }
    input.addEventListener("pointerdown", () => { state.activeControlId = control.id; });
    input.addEventListener("pointerup", () => { state.activeControlId = null; });
    input.addEventListener("pointercancel", () => { state.activeControlId = null; });
    input.addEventListener("input", () => {
      const raw = Number(input.value);
      const next = spec
        ? window.FieldControlLaw.quantize(spec, window.FieldControlLaw.valueAt(spec, raw))
        : raw;
      setControlValueText(control.id, controlValueText(control, spec, next));
      send({ type: "slider", controlId: control.id, value: next });
    });

    parts.shell.appendChild(input);
    return parts.shell;
  }

  function vec2Clamp(control, x, y) {
    return {
      x: clampNumber(x, Number(control.xMin ?? -1), Number(control.xMax ?? 1)),
      y: clampNumber(y, Number(control.yMin ?? -1), Number(control.yMax ?? 1)),
    };
  }

  function renderVec2Control(control) {
    const places = control.decimalPlaces ?? 2;
    const initial = vec2Clamp(control, Number(control.x ?? 0), Number(control.y ?? 0));
    const parts = controlShell(control, `${formatNumber(initial.x, places)} ${formatNumber(initial.y, places)}`);

    const pad = document.createElement("div");
    pad.className = "phone-vec2-pad";
    const handle = document.createElement("div");
    handle.className = "phone-vec2-handle";
    pad.appendChild(handle);

    function positionFor(value) {
      const xMin = Number(control.xMin ?? -1);
      const xMax = Number(control.xMax ?? 1);
      const yMin = Number(control.yMin ?? -1);
      const yMax = Number(control.yMax ?? 1);
      const xNorm = xMax === xMin ? 0.5 : (value.x - xMin) / (xMax - xMin);
      const yNorm = yMax === yMin ? 0.5 : (value.y - yMin) / (yMax - yMin);
      handle.style.left = `${clamp(xNorm, 0, 1) * 100}%`;
      handle.style.top = `${(1 - clamp(yNorm, 0, 1)) * 100}%`;
    }

    function valueFromPointer(event) {
      const rect = pad.getBoundingClientRect();
      const px = clamp(event.clientX - rect.left, 0, rect.width);
      const py = clamp(event.clientY - rect.top, 0, rect.height);
      const xMin = Number(control.xMin ?? -1);
      const xMax = Number(control.xMax ?? 1);
      const yMin = Number(control.yMin ?? -1);
      const yMax = Number(control.yMax ?? 1);
      return vec2Clamp(control, xMin + (px / rect.width) * (xMax - xMin), yMax - (py / rect.height) * (yMax - yMin));
    }

    function update(event) {
      event.preventDefault();
      const next = valueFromPointer(event);
      positionFor(next);
      setControlValueText(control.id, `${formatNumber(next.x, places)} ${formatNumber(next.y, places)}`);
      send({ type: "vec2", controlId: control.id, x: next.x, y: next.y });
    }

    pad.addEventListener("pointerdown", (event) => {
      state.activeControlId = control.id;
      pad.setPointerCapture(event.pointerId);
      update(event);
    });
    pad.addEventListener("pointermove", (event) => {
      if (state.activeControlId === control.id) update(event);
    });
    ["pointerup", "pointercancel", "lostpointercapture"].forEach((eventName) => {
      pad.addEventListener(eventName, () => {
        if (state.activeControlId === control.id) state.activeControlId = null;
      });
    });

    positionFor(initial);
    parts.shell.appendChild(pad);
    return parts.shell;
  }

  function renderCheckboxControl(control) {
    const checked = Boolean(control.checked);
    const parts = controlShell(control, checked ? "ON" : "OFF");
    const button = document.createElement("button");
    button.type = "button";
    button.className = "phone-toggle";
    button.classList.toggle("active", checked);
    button.textContent = checked ? "on" : "off";
    button.addEventListener("click", () => {
      const next = !button.classList.contains("active");
      button.classList.toggle("active", next);
      button.textContent = next ? "on" : "off";
      setControlValueText(control.id, next ? "ON" : "OFF");
      send({ type: "checkbox", controlId: control.id, checked: next });
    });
    parts.shell.appendChild(button);
    return parts.shell;
  }

  function renderStringControl(control) {
    const text = control.text || "";
    const parts = controlShell(control, "");
    const input = document.createElement("input");
    input.className = "phone-text-input";
    input.type = "text";
    input.value = text;
    input.addEventListener("change", () => {
      send({ type: "string", controlId: control.id, text: input.value });
    });
    parts.shell.appendChild(input);
    return parts.shell;
  }

  function colorToHex(control) {
    const r = Math.round(clampNumber(Number(control.r ?? 1), 0, 1) * 255);
    const g = Math.round(clampNumber(Number(control.g ?? 1), 0, 1) * 255);
    const b = Math.round(clampNumber(Number(control.b ?? 1), 0, 1) * 255);
    return `#${[r, g, b].map((v) => v.toString(16).padStart(2, "0")).join("")}`;
  }

  function hexToRgb(hex) {
    const clean = String(hex || "#ffffff").replace("#", "");
    const value = Number.parseInt(clean, 16);
    if (!Number.isFinite(value)) return { r: 1, g: 1, b: 1 };
    return {
      r: ((value >> 16) & 255) / 255,
      g: ((value >> 8) & 255) / 255,
      b: (value & 255) / 255,
    };
  }

  function renderColorControl(control) {
    const alpha = clampNumber(Number(control.a ?? 1), 0, 1);
    const parts = controlShell(control, formatNumber(alpha, 2));
    const row = document.createElement("div");
    row.className = "phone-color-row";

    const color = document.createElement("input");
    color.type = "color";
    color.className = "phone-color-input";
    color.value = colorToHex(control);

    const alphaInput = document.createElement("input");
    alphaInput.className = "phone-range";
    alphaInput.type = "range";
    alphaInput.min = "0";
    alphaInput.max = "1";
    alphaInput.step = "0.01";
    alphaInput.value = String(alpha);

    function sendColor() {
      const rgb = hexToRgb(color.value);
      const a = Number(alphaInput.value);
      setControlValueText(control.id, formatNumber(a, 2));
      send({ type: "color", controlId: control.id, r: rgb.r, g: rgb.g, b: rgb.b, a });
    }

    color.addEventListener("input", sendColor);
    alphaInput.addEventListener("input", sendColor);
    row.append(color, alphaInput);
    parts.shell.appendChild(row);
    return parts.shell;
  }

  area.addEventListener("pointerdown", (event) => {
    state.activePointerId = event.pointerId;
    area.setPointerCapture(state.activePointerId);
    handlePointer(event, "start");
  });

  area.addEventListener("pointermove", (event) => {
    if (event.pointerId === state.activePointerId) handlePointer(event, "move");
  });

  area.addEventListener("pointerup", releasePointer);
  area.addEventListener("pointercancel", releasePointer);
  area.addEventListener("lostpointercapture", () => {
    if (state.activePointerId !== null) {
      sendRelease({ x: state.last.x, y: state.last.y, dx: 0, dy: 0 });
      state.activePointerId = null;
    }
  });

  thumb.addEventListener("keydown", (event) => {
    const step = event.shiftKey ? 0.05 : 0.01;
    const keys = {
      ArrowLeft: [-step, 0],
      ArrowRight: [step, 0],
      ArrowUp: [0, step],
      ArrowDown: [0, -step],
    };

    if (!keys[event.key]) return;
    event.preventDefault();
    const [dx, dy] = keys[event.key];
    const next = {
      x: clamp(state.last.x + dx, 0, 1),
      y: clamp(state.last.y + dy, 0, 1),
    };
    const values = {
      phase: "move",
      x: next.x,
      y: next.y,
      dx: next.x - state.last.x,
      dy: next.y - state.last.y,
    };
    state.last = next;
    renderPad(values);
    sendTouch(values);
  });

  document.querySelectorAll("[data-page]").forEach((button) => {
    button.addEventListener("click", () => {
      setPage(button.getAttribute("data-page"));
    });
  });

  document.querySelectorAll("[data-axis]").forEach((button) => {
    button.addEventListener("click", () => {
      const nextAxis = button.getAttribute("data-axis");
      state.axis = ["x", "y", "xy"].includes(nextAxis) ? nextAxis : "y";
      setActive("[data-axis]", "data-axis", state.axis);
      renderPad({ x: state.last.x, y: state.last.y, dx: 0, dy: 0 });
    });
  });

  document.querySelectorAll("[data-mode]").forEach((button) => {
    button.addEventListener("click", () => {
      const nextMode = button.getAttribute("data-mode");
      state.sendMode = ["absolute", "delta", "both"].includes(nextMode) ? nextMode : "absolute";
      setActive("[data-mode]", "data-mode", state.sendMode);
    });
  });

  saveValue.addEventListener("click", () => sendCommand("save", saveValue));
  restoreValue.addEventListener("click", () => sendCommand("restore", restoreValue));
  lockBinding.addEventListener("click", () => sendCommand("lock", lockBinding));
  unlockBinding.addEventListener("click", () => sendCommand("unlock", unlockBinding));
  launch.addEventListener("click", () => sendCommand("launch", launch));
  stop.addEventListener("click", () => sendCommand("stop", stop));

  setActive("[data-axis]", "data-axis", state.axis);
  setActive("[data-mode]", "data-mode", state.sendMode);
  connect();
  setPage(state.page);
  state.controlPollTimer = window.setInterval(() => { fetchControlGroup(); fetchOutputs(); }, 450);
  renderPad({ x: state.last.x, y: state.last.y, dx: 0, dy: 0 });
})();
