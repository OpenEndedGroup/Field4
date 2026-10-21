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
- **An OAuth aaaframe** (GitHub login): `WebRTCNetwork.join(url)` once, then nothing.

### The sign-in window

1. `join(url)` registers the network, makes it `WebRTCNetwork.default`, and records it as the
   class in `~/.afield/webrtc/aaaframe-sign-ins.json`. Later runs of Field start with that class
   as the default (`AFIELD_WEBRTC_SERVER` still wins).
2. With no usable session, aaaframe closes the bridged socket with 4401. The publisher list's
   payload then carries `signIn: <url>` and the sink's status line contains "(4401)"; both mount
   `webrtc-sign-in.js`, which prompts once per Field page per server and leaves a
   **Sign in to aaaframe** button.
3. The control calls `fieldElectron.aaaframeSignIn(url)` (preload) → `field4-aaaframe-sign-in` in
   Electron main (`electron/aaaframe-sign-in.js`). Main first checks the `persist:field-aaaframe`
   partition for a cookie that aaaframe already accepts (no window); otherwise it opens a
   `BrowserWindow` on that partition at `<url>/login/github?next=/api/shadertrinket-config`
   (skipping aaaframe's own login page, one click fewer). On every `session` cookie change and
   navigation it reads the cookie with `session.cookies.get` and tests it with a main-process
   `fetch` of `/api/shadertrinket-config`: aaaframe writes a `session` cookie *before* login (it
   holds the PKCE state), so only a 200 with a `user.login` counts. Then it closes the window.
4. The page sends `webrtc-aaaframe-signed-in {network, session, login}` to Field. The JVM saves it
   (directory 0700, file 0600, written atomically), sets the network's session, drops the cached
   config, and reconnects the publisher lobby at once. Page sockets retry every 2 s on their own and
   pick up the new cookie at their next connect.

A remembered login that aaaframe refuses (expired, or the server restarted with a new
`AAAFRAME_SESSION_SECRET`) produces the same 4401, so the same prompt re-signs. A click on the
button forces a fresh sign-in (it drops the partition's cookie first).

Precedence for a server's login: `aaaframe(url, session = ...)`, then `AFIELD_AAAFRAME_SESSION`,
then the remembered sign-in. Tests point the store elsewhere with `-Dafield.webrtc.signIns=<file>`.

Only the cookie *value* crosses to the JVM, over Field's own localhost WebSocket. No page script
reads it from a cookie jar; the sign-in window runs aaaframe's pages sandboxed, with no preload.

ICE servers come from aaaframe's `/api/shadertrinket-config` (`webrtc.iceServers`), fetched by the
JVM with the same cookie; the local relay uses the same two public STUN servers.

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

## Verified (2026-10-20, M-series Mac, KosmicKrisp, Electron 44.5.1)

All in the Field Electron app on port 9820, against a local `worldbuild_server` on 8790
(`AAAFRAME_LOCAL_AUTH=1`) and a second one on 8791 in GitHub-login mode.

- **Field → Field, local relay.** `webrtc_shader_jam` as written: the sink publishes
  `fieldbox/field-shader`, the receiving box's list shows it, and the renderer window draws the
  stream (`public/docs/assets/webrtc/received-in-window.png`).
- **Field → browser ShaderTrinket, aaaframe.** A headed Playwright Chromium on
  `http://127.0.0.1:8790/shadertrinket` (`dev_docs/webrtc_latency/shadertrinket_driver.py`)
  received `@student/field-shader` into `iChannel0`: 640×360, 21–29 fps decoded.
- **Browser ShaderTrinket → Field, aaaframe.** The same page published `@student/browser`; clicking
  it in the Field publisher list switched the source, and the window drew it.
- **OAuth-mode aaaframe.** With a `session` cookie for `alice` the sink published as `alice/field`
  and the list read `[alice/field]`; with no cookie the list's `error` was
  "aaaframe refused the login (4401): the session cookie is missing or expired".
- `WebRTCSignalingTest`: the local relay's message shapes, the WebSocket route, and the bridge
  forwarding verbatim with the cookie (and the 4401 path), against a stand-in aaaframe.

## Sign-in verified (2026-10-20)

Against aaaframe in GitHub-login mode on 8791, launched through
`dev_docs/webrtc_latency/aaaframe_with_fake_github.py`. That wrapper replaces only GitHub: the
redirect to `github.com/login/oauth/authorize` goes to a stand-in that returns to aaaframe's
callback with a code (`FAKE_GITHUB_AUTO=1`: at once, as GitHub does for an app already authorised),
and the callback's token and `/user` calls are answered by an httpx mock. aaaframe's `/login/github`,
PKCE state, `/auth/github/callback`, `session["user"]` and Set-Cookie all ran unmodified.

- **First join.** A box ran `WebRTCNetwork.join("http://127.0.0.1:8791")`, then `webrtc_shader_jam`.
  The lobby got 4401, the panel opened the window, main captured the cookie, Field logged
  "signed in to http://127.0.0.1:8791 as @alice", and the store was written 0600 in a 0700
  directory. The sink published as `@alice/field-shader`; the list showed it.
- **Expiry re-prompt.** aaaframe restarted with a different secret: the stored cookie got 4401 and
  Field signed in again by itself (`savedAt` advanced).
- **Zero-config next run.** Field restarted, aaaframe's secret rotated again, and only the starting
  point's boxes run, no `join`: the default was the remembered class, Field re-signed, published as
  `@alice/field-shader`, and the window drew the received stream.
- **Plain browser.** In a headless Chromium on Field's page, the control reads "signing in to
  aaaframe needs the Field Electron app".
- `WebRTCSignalingTest`: the store's permissions, precedence, one network object per server, and
  `join`.

Not exercised: GitHub's real login and consent pages, the real token exchange, and the
interactive Authorize click in the window (only the auto-redirect path ran in the window; the
click page was checked with curl).

## Measured round trip

`dev_docs/webrtc_latency/`: a sink draws its wall clock (ms mod 2^24) as 24 bars across the middle
of the frame; a source with `measure = true` decodes each frame in `requestVideoFrameCallback` and
subtracts from the same clock. So the number is Field render (CPU record time) → encode → network
→ decode → presented in the receiving page. It excludes the last hop into the Field texture (the
browser-texture paint and import), which is a frame or less.

| path | load average | frames | p50 | p90 | p99 | max |
| --- | --- | --- | --- | --- | --- | --- |
| local relay | ~85 (just after a reboot) | 600 | 58.8 | 106.0 | 215.1 | 344.0 ms |
| local aaaframe | ~30 | 600 | 46.5 | 62.7 | 71.2 | 86.5 ms |

`captureStream(30)` alone accounts for up to 33 ms: a frame waits for the next capture tick.
Chromium gave no `receiveTime`, so the network/decode split is not available. An idle machine has
not been measured.

## Bugs found on the way

- **IOSurface import size.** `IOSurfaceHostBuffer` required the surface size to be a multiple of
  the 16 KiB host-pointer alignment. Chromium sizes surfaces exactly, so only sizes like 1280×720
  imported; 640×360 failed every frame on KosmicKrisp. It now imports up to the page boundary.
- **A static canvas publishes nothing.** `captureStream` emits a frame only when the canvas
  changes, so ShaderTrinket's default (time-independent) shader publishes an empty stream. Field's
  views redraw every frame; the docs say so for the browser end.
- **Watch before load.** A `watch()` sent before the hidden page loaded was lost; the page now
  queues it, and the source re-sends when the page reports a different target.

- **A page opened before sign-in never recovered.** `FieldSignal.ready` rejected on the first
  refused socket, so a page that started before the student signed in never got its viewer. It now
  resolves at the first hello, however many refusals come first.
- **Shadowed target.** The source's "page reports a different target" check compared against the
  constructor argument (`target` in `init`), not the property, so the watch-before-load fix never
  fired when the source started on a placeholder.
- **Reuse across documents.** Source reuse was keyed by box uid, which recurs across documents
  opened from one transcript; the second document sampled an image view from the first one's
  Vulkan device. It is keyed by the Box object and the renderer now.

## Gaps

- No TURN: peers on two NAT-blocked networks cannot connect.
- The sign-in window has not been run against real GitHub (no registered OAuth app locally): see
  "Sign-in verified" for what the stand-in covers.
- Browser ShaderTrinkets can join Field's local relay only if Field serves ShaderTrinket itself
  (its signalling URL is its own host): that is the wave-2 port.
- Linux sources use the CPU-bitmap fallback; Windows is untested; no audio.
- The local relay has no login: anything that can reach this Field's server publishes as its user.
