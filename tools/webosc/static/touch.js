const config = window.WEBOSC_CONFIG || { normalized: true, sendMode: "absolute" };
const area = document.querySelector("#touch-area");
const slider = document.querySelector("#slider");
const connection = document.querySelector("#connection");
const readout = document.querySelector("#readout");

let socket;
let reconnectTimer;
let activePointerId = null;
let last = { x: 0.5, y: 0.5 };

function connect() {
  const protocol = window.location.protocol === "https:" ? "wss" : "ws";
  socket = new WebSocket(`${protocol}://${window.location.host}/ws`);

  socket.addEventListener("open", () => {
    connection.textContent = "online";
  });

  socket.addEventListener("close", () => {
    connection.textContent = "offline";
    window.clearTimeout(reconnectTimer);
    reconnectTimer = window.setTimeout(connect, 500);
  });

  socket.addEventListener("error", () => {
    connection.textContent = "error";
  });
}

function clamp(value, min, max) {
  return Math.min(Math.max(value, min), max);
}

function pointerToValues(event, phase = "move") {
  const rect = area.getBoundingClientRect();
  const px = clamp(event.clientX - rect.left, 0, rect.width);
  const py = clamp(event.clientY - rect.top, 0, rect.height);
  const x = rect.width ? px / rect.width : 0;
  const y = rect.height ? 1 - py / rect.height : 0;
  const dx = phase === "start" ? 0 : x - last.x;
  const dy = phase === "start" ? 0 : y - last.y;

  last = { x, y };

  return config.normalized
    ? { phase, x, y, dx, dy }
    : { phase, x: px, y: rect.height - py, dx: dx * rect.width, dy: dy * rect.height };
}

function render(values) {
  const rect = area.getBoundingClientRect();
  const nx = config.normalized ? values.x : values.x / rect.width;
  const ny = config.normalized ? values.y : values.y / rect.height;
  slider.style.left = `${clamp(nx, 0, 1) * 100}%`;
  slider.style.top = `${(1 - clamp(ny, 0, 1)) * 100}%`;
  slider.setAttribute("aria-valuenow", String(Math.round(clamp(nx, 0, 1) * 1000)));
  readout.textContent = `x ${values.x.toFixed(3)} y ${values.y.toFixed(3)}`;
}

function send(values) {
  if (!socket || socket.readyState !== WebSocket.OPEN) {
    return;
  }

  socket.send(JSON.stringify({ type: "touch", ...values }));
}

function handlePointer(event, phase = "move") {
  if (activePointerId !== null && event.pointerId !== activePointerId) {
    return;
  }

  event.preventDefault();
  const values = pointerToValues(event, phase);
  render(values);
  send(values);
}

area.addEventListener("pointerdown", (event) => {
  activePointerId = event.pointerId;
  area.setPointerCapture(activePointerId);
  handlePointer(event, "start");
});

area.addEventListener("pointermove", (event) => {
  if (event.pointerId === activePointerId) {
    handlePointer(event);
  }
});

for (const eventName of ["pointerup", "pointercancel", "lostpointercapture"]) {
  area.addEventListener(eventName, () => {
    activePointerId = null;
  });
}

slider.addEventListener("keydown", (event) => {
  const step = event.shiftKey ? 0.05 : 0.01;
  const keys = {
    ArrowLeft: [-step, 0],
    ArrowRight: [step, 0],
    ArrowUp: [0, -step],
    ArrowDown: [0, step],
  };

  if (!keys[event.key]) {
    return;
  }

  event.preventDefault();
  const [dx, dy] = keys[event.key];
  last = { x: clamp(last.x + dx, 0, 1), y: clamp(last.y + dy, 0, 1) };
  const rect = area.getBoundingClientRect();
  const values = config.normalized
    ? { phase: "move", x: last.x, y: last.y, dx, dy }
    : { phase: "move", x: last.x * rect.width, y: last.y * rect.height, dx: dx * rect.width, dy: dy * rect.height };
  render(values);
  send(values);
});

connect();
render({ x: 0.5, y: 0.5, dx: 0, dy: 0 });
