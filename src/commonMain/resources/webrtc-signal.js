// Page half of Field's WebRTC sources and sinks (dev_docs/WEBRTC_SOURCES_SINKS.md).
//
// Chromium does all of WebRTC. Field only relays signalling: every page connects to Field's own
// /ws/webrtc-signaling?network=<id>, which is either Field's local relay or a pipe to an aaaframe
// server's /ws/shadertrinket-signaling. The messages are aaaframe's ShaderTrinket messages, so a
// Field page and a browser ShaderTrinket see each other as ordinary publishers and viewers.

const fieldOrigin = () => (location.protocol === "https:" ? "wss:" : "ws:") + "//" + location.host;

export async function webrtcConfig(network) {
  const response = await fetch("/api/webrtc-config?network=" + encodeURIComponent(network || ""));
  if (!response.ok) throw new Error("Field has no WebRTC network '" + network + "' (" + response.status + ")");
  return response.json();
}

// One signalling socket. `on(type, fn)` listens; `send(message)` queues until open.
export class FieldSignal {
  constructor(network, onStatus = () => {}) {
    this.network = network || "";
    this.onStatus = onStatus;
    this.listeners = new Map();
    this.queue = [];
    this.publishers = new Set();
    this.connectionId = "";
    this.username = "";
    this.closed = false;
    this.ready = new Promise((resolve, reject) => {
      this.resolveReady = resolve;
      this.rejectReady = reject;
    });
    this.open();
  }

  open() {
    const url = fieldOrigin() + "/ws/webrtc-signaling?network=" + encodeURIComponent(this.network);
    const socket = new WebSocket(url);
    this.socket = socket;
    socket.addEventListener("open", () => {
      for (const message of this.queue.splice(0)) socket.send(JSON.stringify(message));
    });
    socket.addEventListener("message", (event) => {
      let message;
      try { message = JSON.parse(event.data); } catch (_) { return; }
      if (!message || typeof message !== "object") return;
      const type = String(message.type || "");
      if (type === "hello") {
        this.connectionId = String(message.connectionId || "");
        this.username = String(message.username || "");
        this.publishers = new Set(message.publishers || []);
        this.resolveReady(this);
      } else if (type === "publisher-online") {
        this.publishers.add(String(message.username || ""));
      } else if (type === "publisher-offline") {
        this.publishers.delete(String(message.username || ""));
      } else if (type === "field-error") {
        this.onStatus("error: " + message.error);
      }
      for (const fn of this.listeners.get(type) || []) fn(message);
      for (const fn of this.listeners.get("*") || []) fn(message);
    });
    socket.addEventListener("close", (event) => {
      if (this.closed) return;
      this.rejectReady(new Error("signalling closed " + event.code + " " + event.reason));
      this.onStatus("signalling closed" + (event.reason ? ": " + event.reason : "") + "; retrying");
      // Reconnect: a fresh hello, a fresh connection id; the owner re-publishes or re-watches.
      setTimeout(() => {
        if (this.closed) return;
        this.ready = new Promise((resolve, reject) => { this.resolveReady = resolve; this.rejectReady = reject; });
        this.ready.then(() => { for (const fn of this.listeners.get("reconnected") || []) fn(); }).catch(() => {});
        this.open();
      }, 2000);
    });
  }

  on(type, fn) {
    if (!this.listeners.has(type)) this.listeners.set(type, []);
    this.listeners.get(type).push(fn);
  }

  send(message) {
    if (this.socket && this.socket.readyState === WebSocket.OPEN) this.socket.send(JSON.stringify(message));
    else this.queue.push(message);
  }

  close() {
    this.closed = true;
    try { this.socket.close(); } catch (_) {}
  }
}

const peerKey = (connectionId, requestId) => connectionId + ":" + requestId;

function makePeer(signal, iceServers, toConnectionId, requestId) {
  const pc = new RTCPeerConnection({ iceServers });
  pc.onicecandidate = (event) => {
    if (event.candidate) signal.send({ type: "candidate", toConnectionId, requestId, candidate: event.candidate });
  };
  return pc;
}

// Publishes `stream` as `login/suffix`, answering each viewer's watch with an offer.
export async function publish({ network, stream, suffix, onStatus = () => {} }) {
  if (!window.RTCPeerConnection) throw new Error("this browser has no WebRTC");
  const config = await webrtcConfig(network);
  if (config.error) onStatus(config.error);
  const iceServers = config.iceServers || [];
  const signal = new FieldSignal(config.network, onStatus);
  const peers = new Map();
  let name = "";
  const start = () => signal.send({ type: "publish-start", suffix: suffix || "" });

  signal.on("publish-ready", (m) => { name = String(m.username || ""); onStatus("publishing @" + name, name); });
  signal.on("reconnected", start);
  signal.on("watch", async (m) => {
    const to = String(m.fromConnectionId || ""), requestId = String(m.requestId || "");
    if (!to || !requestId) return;
    const key = peerKey(to, requestId);
    peers.get(key)?.close();
    const pc = makePeer(signal, iceServers, to, requestId);
    peers.set(key, pc);
    pc.onconnectionstatechange = () => {
      if (["failed", "closed"].includes(pc.connectionState)) peers.delete(key);
      onStatus("publishing @" + name + " to " + [...peers.values()].filter((p) => p.connectionState === "connected").length + " viewer(s)", name);
    };
    for (const track of stream.getTracks()) pc.addTrack(track, stream);
    await pc.setLocalDescription(await pc.createOffer());
    signal.send({ type: "offer", toConnectionId: to, requestId, sdp: pc.localDescription });
  });
  signal.on("answer", async (m) => {
    const pc = peers.get(peerKey(String(m.fromConnectionId || ""), String(m.requestId || "")));
    if (pc && m.sdp) await pc.setRemoteDescription(m.sdp);
  });
  signal.on("candidate", async (m) => {
    const pc = peers.get(peerKey(String(m.fromConnectionId || ""), String(m.requestId || "")));
    if (pc && m.candidate) await pc.addIceCandidate(m.candidate).catch(() => {});
  });
  signal.on("hangup", (m) => {
    const key = peerKey(String(m.fromConnectionId || ""), String(m.requestId || ""));
    peers.get(key)?.close();
    peers.delete(key);
  });

  await signal.ready;
  start();
  return {
    signal,
    get name() { return name; },
    get viewers() { return [...peers.values()].filter((p) => p.connectionState === "connected").length; },
    stop() {
      signal.send({ type: "publish-stop" });
      for (const pc of peers.values()) pc.close();
      peers.clear();
      setTimeout(() => signal.close(), 50);
    },
  };
}

// Watches a publisher; `onStream(stream, name)` gets its video. Re-watches when the publisher comes
// back online, so a student restarting their ShaderTrinket reappears by itself.
export async function watch({ network, target, onStream, onStatus = () => {} }) {
  if (!window.RTCPeerConnection) throw new Error("this browser has no WebRTC");
  const config = await webrtcConfig(network);
  if (config.error) onStatus(config.error);
  const iceServers = config.iceServers || [];
  const signal = new FieldSignal(config.network, onStatus);
  let current = normalizeName(target);
  let requestId = "";
  let pc = null;

  const hangup = () => {
    if (pc) pc.close();
    pc = null;
  };
  const request = () => {
    hangup();
    if (!current) { onStatus("no publisher chosen"); return; }
    requestId = Date.now().toString(36) + Math.random().toString(36).slice(2);
    signal.send({ type: "watch", target: current, requestId });
    onStatus("requesting @" + current);
  };

  signal.on("publisher-unavailable", (m) => {
    if (String(m.requestId || "") === requestId) onStatus("waiting for @" + current + " to publish");
  });
  signal.on("publisher-online", (m) => { if (String(m.username || "") === current) request(); });
  signal.on("publisher-offline", (m) => {
    if (String(m.username || "") === current) { hangup(); onStatus("@" + current + " stopped publishing"); }
  });
  signal.on("reconnected", request);
  signal.on("offer", async (m) => {
    if (String(m.requestId || "") !== requestId || !m.sdp) return;
    const from = String(m.fromConnectionId || "");
    hangup();
    pc = makePeer(signal, iceServers, from, requestId);
    const mine = pc;
    pc.ontrack = (event) => {
      const stream = event.streams && event.streams[0] ? event.streams[0] : new MediaStream([event.track]);
      onStream(stream, current);
    };
    pc.onconnectionstatechange = () => {
      if (mine !== pc) return;
      if (mine.connectionState === "connected") onStatus("receiving @" + current);
      if (mine.connectionState === "failed") onStatus("connection to @" + current + " failed (STUN only: is the peer reachable?)");
    };
    await pc.setRemoteDescription(m.sdp);
    await pc.setLocalDescription(await pc.createAnswer());
    signal.send({ type: "answer", toConnectionId: from, requestId, sdp: pc.localDescription });
  });
  signal.on("candidate", async (m) => {
    if (pc && String(m.requestId || "") === requestId && m.candidate) await pc.addIceCandidate(m.candidate).catch(() => {});
  });

  await signal.ready;
  request();
  return {
    signal,
    get target() { return current; },
    get publishers() { return [...signal.publishers].sort(); },
    retarget(name) { current = normalizeName(name); request(); },
    stop() { hangup(); signal.close(); },
  };
}

// aaaframe's normalize_shader_publish_name, close enough for matching broadcasts.
export function normalizeName(value) {
  const raw = String(value || "").trim().toLowerCase().replace(/^@/, "");
  const [login, ...rest] = raw.split("/");
  const l = login.replace(/[^a-z0-9-]+/g, "-").replace(/^-+|-+$/g, "");
  if (!l) return "";
  const suffix = rest.join("/").replace(/\\/g, "/").replace(/^\/+|\/+$/g, "")
    .replace(/[^a-z0-9_.-]+/g, "-").replace(/^[-._]+|[-._]+$/g, "").slice(0, 48);
  return suffix ? l + "/" + suffix : l;
}
