(function () {
  const params = new URLSearchParams(window.location.search);
  const area = document.querySelector("#touch-area");
  const thumb = document.querySelector("#thumb");
  const connection = document.querySelector("#connection");
  const clients = document.querySelector("#clients");
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

  const state = {
    socket: null,
    reconnectTimer: null,
    controlPollTimer: null,
    activePointerId: null,
    activeControlId: null,
    last: { x: 0.5, y: 0.5 },
    page: params.get("page") === "controls" ? "controls" : "pad",
    axis: ["x", "y", "xy"].includes(params.get("axis")) ? params.get("axis") : "y",
    sendMode: ["absolute", "delta", "both"].includes(params.get("send")) ? params.get("send") : "absolute",
    debounceMs: clampNumber(Number.parseInt(params.get("debounceMs") || "200", 10), 0, 5000),
    maxExecutionMs: clampNumber(Number.parseInt(params.get("maxExecutionMs") || "750", 10), 0, 60000),
    controlsSignature: "",
    controlInfo: null,
  };

  function targetQuery() {
    const query = new URLSearchParams();
    const file = params.get("file") || params.get("document");
    if (file) query.set("file", file);
    return query.toString();
  }

  function connect() {
    const protocol = window.location.protocol === "https:" ? "wss" : "ws";
    const query = targetQuery();
    state.socket = new WebSocket(`${protocol}://${window.location.host}/controllers/ws?${query}`);

    state.socket.addEventListener("open", () => {
      connection.textContent = "online";
    });

    state.socket.addEventListener("message", (event) => {
      let message = null;
      try {
        message = JSON.parse(event.data);
      } catch (_error) {
        return;
      }
      if (message && message.type === "status") {
        const count = Number.parseInt(String(message.clients || "0"), 10) || 0;
        clients.textContent = `${count} client${count === 1 ? "" : "s"}`;
      }
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

  function setActive(selector, attr, value) {
    document.querySelectorAll(selector).forEach((button) => {
      button.classList.toggle("active", button.getAttribute(attr) === value);
    });
  }

  function setPage(page) {
    state.page = page === "controls" ? "controls" : "pad";
    document.querySelectorAll("[data-controller-page]").forEach((element) => {
      element.classList.toggle("active", element.getAttribute("data-controller-page") === state.page);
    });
    padControls.style.display = state.page === "pad" ? "" : "none";
    setActive("[data-page]", "data-page", state.page);
    if (state.page === "controls") {
      fetchControlGroup();
      renderGroupReadout();
    } else {
      renderPad({ x: state.last.x, y: state.last.y, dx: 0, dy: 0 });
    }
  }

  function controlsUrl() {
    const query = targetQuery();
    return query ? `/controllers/controls?${query}` : "/controllers/controls";
  }

  async function fetchControlGroup() {
    if (state.page !== "controls") return;
    try {
      const response = await fetch(controlsUrl(), { cache: "no-store" });
      if (!response.ok) return;
      const info = await response.json();
      state.controlInfo = info;
      renderControlGroup(info);
    } catch (_error) {
      if (!state.controlInfo) {
        groupState.textContent = "Controls unavailable";
      }
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
    const value = groupControls.querySelector(`[data-value-for="${CSS.escape(controlId)}"]`);
    if (value) value.textContent = text;
  }

  function renderSliderControl(control) {
    const min = Number(control.min ?? 0);
    const max = Number(control.max ?? 1);
    const step = Number(control.step ?? 0.01);
    const value = clampNumber(Number(control.value ?? min), Math.min(min, max), Math.max(min, max));
    const places = control.decimalPlaces ?? 2;
    const parts = controlShell(control, formatNumber(value, places));

    const input = document.createElement("input");
    input.className = "phone-range";
    input.type = "range";
    input.min = String(min);
    input.max = String(max);
    input.step = String(step);
    input.value = String(value);
    input.addEventListener("pointerdown", () => { state.activeControlId = control.id; });
    input.addEventListener("pointerup", () => { state.activeControlId = null; });
    input.addEventListener("pointercancel", () => { state.activeControlId = null; });
    input.addEventListener("input", () => {
      const next = Number(input.value);
      setControlValueText(control.id, formatNumber(next, places));
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

  for (const eventName of ["pointerup", "pointercancel", "lostpointercapture"]) {
    area.addEventListener(eventName, () => {
      state.activePointerId = null;
    });
  }

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
  state.controlPollTimer = window.setInterval(fetchControlGroup, 450);
  renderPad({ x: state.last.x, y: state.last.y, dx: 0, dy: 0 });
})();
