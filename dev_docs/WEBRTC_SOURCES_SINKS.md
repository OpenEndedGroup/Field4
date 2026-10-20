# WebRTC sources and sinks

A remote WebRTC video stream becomes a Field texture (a **source**), and a Field render target
becomes a published stream (a **sink**). The first network is aaaframe's classroom ShaderTrinket
network, so a Field box shows up in the browser ShaderTrinkets' publisher lists and any student's
stream can be sampled in Field. The second is a relay on Field's own server, with the same messages,
for a LAN of Field instances and no aaaframe.

## The rule: the JVM never touches WebRTC

Chromium implements WebRTC; Field already moves GPU frames in and out of Chromium without readback.

- **Sink.** `ElectronRTTView` lands a `RenderToTexture` in a canvas in the box's output area
  (`dev_docs/ELECTRON_RTT_VIEW.md`). The sink's page script calls `canvas.captureStream(30)` on that
  canvas and adds its track to one `RTCPeerConnection` per viewer.
- **Source.** A hidden puppet Electron page (`FieldUiBrowserTexture`, `dev_docs/external_browser_surfaces.md`)
  loads `webrtc-source.html`, which watches a publisher and plays the track full-window in a
  `<video>`. Its paints are the texture: IOSurface on macOS, the CPU-bitmap fallback on Linux.

The JVM's part is JSON: it serves the pages, relays or forwards the signalling messages, and keeps
a publisher list. It never sees an SDP's media or a frame.

## Signalling: pages talk only to Field

Both pages open `wss://<field>/ws/webrtc-signaling?network=<id>`. That socket is either

- **`local`**: an in-process relay implementing aaaframe's messages (below), or
- **an aaaframe network** registered by `WebRTCNetwork.aaaframe(baseUrl, session)`: the JVM opens
  one upstream `…/ws/shadertrinket-signaling` socket per page socket and copies text frames verbatim
  in both directions. aaaframe assigns the connection id and the login; Field adds nothing.

Why not let the pages connect to aaaframe directly:

1. aaaframe's login is a Quart session cookie, `HttpOnly` and `SameSite=Lax`. A WebSocket opened
   by a page on `localhost:9092` is cross-site to aaaframe, so Chromium does not send a Lax cookie
   with it, even if the student is logged in in the same Electron profile.
2. The source page runs in the puppet Electron process, a separate Chromium profile from the
   Field app's window, so it cannot see a login made in the Field app anyway.
3. Field's own certificate is self-signed and aaaframe may be plain HTTP on a LAN; one origin
   for the pages avoids mixed-content rules.

The bridge sidesteps all three, and makes the "local" and "aaaframe" cases the same page code with
a different `network` parameter.

### Messages (aaaframe `handle_shader_signal_message`)

Server → client: `hello {username, connectionId, publishers[]}`, `publish-ready {username, replaced}`,
`publisher-online {username}`, `publisher-offline {username}`,
`publisher-unavailable {username, requestId}`, `watch {from, fromConnectionId, requestId, target}`,
and relayed `offer` / `answer` / `candidate` / `hangup` with `from` and `fromConnectionId` added.

Client → server: `publish-start {suffix}` (publishes as `login` or `login/suffix`), `publish-stop`,
`watch {target, requestId}`, and `offer` / `answer` / `candidate` / `hangup` with
`toConnectionId` and `requestId`.

The publisher answers a `watch` with an offer, the viewer answers, both trickle ICE. One peer
connection per `(connectionId, requestId)`. aaaframe's WHIP publishers (server-side aiortc) look
the same to a viewer: their offers come from the server.

The local relay's login is `AFIELD_WEBRTC_LOGIN`, else the OS user name, normalised as aaaframe
normalises logins.

## Login and configuration

- **A LAN aaaframe in local-auth mode** (`AAAFRAME_LOCAL_AUTH=1`): nothing. Every client is the
  same login, so publishers differ by suffix (`student/field-orbit`).
- **An OAuth aaaframe** (GitHub login): Field needs the student's `session` cookie value, once.
  `WebRTCNetwork.aaaframe(url, session = "...")`, or `AFIELD_AAAFRAME_SESSION`. The cookie is
  `HttpOnly`, so neither page script nor Field can read it from a browser; the student copies it
  from the browser's devtools. (A Field-side login window that captures it is the obvious next
  step, not done.)
- ICE servers come from aaaframe's `/api/shadertrinket-config` (`webrtc.iceServers`), fetched by
  the JVM with the same cookie; the local relay uses the same two public STUN servers.

`AFIELD_WEBRTC_SERVER` sets the default network for boxes that don't name one (`local` if unset).

## Box API (`afield.media.webrtc`)

- `webrtcSink(name, width, height)`: a `WebRTCSink` whose `rtt` you render into;
  `display("main", sink.html())` shows the view and publishes it as `login/name`.
- `webrtcSource(name, width, height)`: a `WebRTCSource`, a texture (`textures("remote" to source)`)
  showing that publisher's stream; `source.watch(other)` switches without reloading.
- `webrtcPublishers()`: the live publisher list; `html()` shows it with a button per publisher,
  and `onPick { name -> }` receives clicks.

## Scope cuts

Video only; STUN only (no TURN: peers must reach each other's host or server-reflexive
candidates); the sink needs the Field Electron app (a plain browser shows "needs the Field
Electron app"); the source needs Electron installed for the puppet producer; Linux sources use
the CPU-bitmap fallback; no native libwebrtc in the JVM.

## Measurements

See "Measured round trip" below, filled in from real runs.
