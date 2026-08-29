# Chromium as a native Field surface

Field can put a complete Chromium tab anywhere native graphics can put a texture. It is not a
desktop capture and it is not a special web page: navigation, cookies, cross-origin media, DOM,
fonts, audio and browser controls continue to work, while Chromium's composited pixels arrive in
Field as a sampled Vulkan image.

That makes this more than “a web page on a quad.” It is an always-available browser layer for a
Field scene: controllable from code, usable with a mouse and keyboard, aimable with an OpenXR
controller, and available to every ordinary mesh, shader and render pass.

The control half of it does not need the texture half. The same tab — navigation, input, JavaScript,
CDP, the page bridge — is drivable with no renderer and no GPU at all, which is what lets a test,
a docs build or a data box use a browser. See [a browser you do not have to
render](#a-browser-you-do-not-have-to-render).

## First, it is simply a browser

The least theatrical use is often the most important. `PuppetBrowserWindowPanel` fits a live tab to
a native Field window without changing its aspect ratio. Clicks, wheel motion, physical keys,
modifiers and text input go back to the original Chromium page.

```diffstatic_runner {data-lang=kotlin screenshot="assets/puppet_browser/youtube-desktop.png" wait="12" timeout="90" capture="native-window" native-wait-frames="8" embed=true name="youtube-desktop-browser" alt="A complete live YouTube page rendered into a native Field Vulkan window" macro-file="../../dev_docs/agents/transcripts/youtube_mouse_keyboard_browser.yaml" capture-box="youtube_desktop_renderer"}
val browser = FieldUiBrowserTexture(
    renderer = renderer,
    url = "https://www.youtube.com/watch?v=aqz-KE-bpKQ",
    width = 1280,
    height = 720,
    // Linux: Chromium's shared-texture export never fills the buffer (electron#53271).
    cpuBitmapFallback = FieldUiBrowserTexture.cpuBitmapFallbackRequired
)

val screen = PuppetBrowserWindowPanel(renderer, browser)
screen.build(takeFocus = true)
```

This is the full YouTube watch page, including the EU consent flow and player chrome. The native
window is the browser: click the consent button, type into search, scroll the page, use keyboard
shortcuts, and listen to its audio. Resizing the window letterboxes or pillarboxes the texture and
uses the same fitted rectangle for hit testing, so pixels and input cannot drift apart.

## Pixels without readback

The browser and renderer stay separate processes. Chromium produces an accelerated offscreen paint
with a native GPU handle and an explicit lease. Field imports that image, performs one GPU copy into
a stable resident `VkImage`, and returns the lease only after the graphics fence says the copy has
finished. The path stays on the GPU throughout: **Chromium compositor** → native shared texture and
lease → platform import → stable resident Vulkan image → **any Field mesh, shader, render target
or OpenXR panel**.

The copy is a lifetime boundary, not a CPU fallback. No screenshot, encoded frame or pixel array
travels between Electron and the shader. The resident image is imported as sRGB, so ordinary page
colour survives the trip into the renderer correctly.

On macOS the transferred object is an IOSurface sent through a Mach port and imported by MoltenVK.
The Windows receiver imports a duplicated D3D11 NT handle; the Linux receiver imports dma-buf file
descriptors and their DRM modifier layout. The macOS producer-to-renderer path is the currently
live-tested implementation. Windows and Linux retain their compile-reviewed receivers while their
Electron handle-transfer bridges and native validation are completed.

## The other half: `PuppetBrowser`

The texture comes with a control plane. `FieldUiBrowserTexture` implements
[`PuppetBrowser`](https://github.com/OpenEndedGroup/Field4/blob/main/src/jvmMain/kotlin/afield/four/browser/PuppetBrowser.kt),
so a neighbouring Field box can keep the browser as a property and operate it without reaching for
a desktop or exposing an Electron window.

| Surface | What is available |
| --- | --- |
| State | URL, title, loading, history availability, audio, focus, cursor, responsiveness, crash state and navigation generation |
| Navigation | Navigate, back, forward, reload with or without cache, stop, focus and blur |
| Pointer | Move, five mouse buttons, wheel, click count, modifiers, mouse or pen pressure, tilt and twist |
| Text input | Raw key down/up, repeat, physical key codes, Unicode text insertion and IME composition |
| Touch | Multi-point start, move, end and cancel events with radius, rotation and force |
| JavaScript | Promise-aware evaluation in the page or an isolated world, by value or retained handle, with timeout and user-gesture control |
| DevTools protocol | Any Chrome DevTools Protocol command, optional session id, and optional forwarding of the complete CDP event stream |
| Page bridge | Allowlisted asynchronous requests from sandboxed page JavaScript back into Field |

The fire-and-forget input calls preserve their order. `flushInput()` is a barrier when the next
operation must observe every earlier click or keystroke. Commands return `CompletableFuture`s, and
failures arrive as `PuppetBrowserCommandException` with the rejected command and Chromium details.

### JavaScript is a real request/response channel

Evaluation awaits returned promises. It can carry JSON-compatible values back to Kotlin, retain a
remote object in Chromium for later CDP work, or report the page's actual exception rather than
silently returning `undefined`:

```diffstatic {data-lang=kotlin}
browserControl.evaluate(
    "document.querySelector('video')?.play()",
    userGesture = true,
    timeoutMillis = 15_000
).whenComplete { result, error ->
    if (error != null) println("page rejected playback: ${error.message}")
    else println("Chromium returned ${result.value}")
}

browserControl.evaluate(
    "window.applicationState",
    world = BrowserJavascriptWorld.ISOLATED,
    returnMode = BrowserJavascriptReturnMode.HANDLE
).thenAccept { remote ->
    println("remote object: ${remote.objectId}")
    remote.objectId?.let(browserControl::releaseJavascriptObject)
}
```

`userGesture = true` matters for the same things it matters for in an ordinary browser: media
playback and APIs guarded by an active user gesture. The isolated world is useful for inspection or
instrumentation that should not add names to the page's own JavaScript environment.

### DevTools without putting DevTools in the scene

A docked DevTools window is not required to get at the useful machinery underneath it. Raw CDP is
available directly:

```diffstatic {data-lang=kotlin}
browserControl.addEventListener { event ->
    if (event.name == "console" || event.name == "javascript-exception")
        println("${event.name}: ${event.payload}")
}

browserControl.cdp("Network.enable")
browserControl.cdp("Performance.enable")
browserControl.cdp("Performance.getMetrics")
    .thenAccept { println(it) }

browserControl.forwardCdpEvents(true)
```

The normal event stream already includes state changes, navigation, load failures, page titles,
console messages, cursor changes, context menus, audio state, pop-up attempts, permission requests,
JavaScript dialogs and exceptions, responsiveness and renderer exits. Turning on CDP forwarding
adds protocol events as `cdp` messages for tools that want Network, Runtime, Debugger, Performance
or any other domain. In other words, Field can build the diagnostics it wants without first
recreating the visual DevTools shell in VR.

### A sandboxed page can call back into Field

The preload exposes only two JSON-cloning operations—`window.fieldPuppet.emit` and
`window.fieldPuppet.request`—rather than Node or an unrestricted IPC object. Field chooses the
request names it will accept:

```diffstatic {data-lang=kotlin}
val removeBridge = browserControl.onPageRequest("selected-point") { payload ->
    println("page selected $payload")
    CompletableFuture.completedFuture(
        buildJsonObject { put("accepted", true) }
    )
}
```

```diffstatic {data-lang=javascript}
const answer = await window.fieldPuppet.request("selected-point", {
    x: event.clientX,
    y: event.clientY
})
```

This is enough for a cooperative application to expose semantic actions while the generic pointer,
keyboard and CDP paths remain available for every other site.

## A browser you do not have to render

Everything in the table above is about the *tab*, not about its pixels. Until recently you could not
reach any of it without a renderer, because `PuppetBrowser` extended the texture interface: naming
the control plane obliged you to own a Vulkan image view, and therefore a window and a GPU.

It no longer does.
[`PuppetBrowserControl`](https://github.com/OpenEndedGroup/Field4/blob/main/src/jvmMain/kotlin/afield/four/browser/PuppetBrowser.kt)
is the control plane on its own, and
[`HeadlessPuppetBrowser`](https://github.com/OpenEndedGroup/Field4/blob/main/src/jvmMain/kotlin/afield/four/browser/HeadlessPuppetBrowser.kt)
implements it with no renderer, no Vulkan surface, no native handle bridge and no readback socket.
The Electron producer gains a third frame mode in which the paints are dropped at the source.

```diffstatic {data-lang=kotlin}
val browser = HeadlessPuppetBrowser("https://field.graphics/")
try {
    browser.loaded.get(30, TimeUnit.SECONDS)
    println("${browser.state.title} at ${browser.state.url}")
} finally {
    browser.close()
}
```

`PuppetBrowser` still extends `PuppetBrowserControl` and `ExternalBrowserSurface` together, so every
example on this page is unchanged and a box that publishes `browserControl` keeps publishing the
same thing. What is new is that a caller who only wants the tab can say so.

The producer, the wire protocol and every command are the ones the textured browser uses; only the
paint handler differs. That is what makes this more than a convenience: the browser stack now has a
test that can fail. `HeadlessPuppetBrowserTest` spawns a real Chromium, loads a `file://` fixture
committed in the repository, and asserts on values read back out of the live DOM — an `evaluate`
result, a page exception arriving as an exception rather than as `undefined`, an unsettled promise
being awaited, the isolated world, a `window.fieldPuppet.request` round trip in both directions,
`insertText` and a dispatched key both changing the page, `flushInput` as a barrier, and a raw CDP
command. No GPU and no network, so it runs anywhere the repository builds.

One thing that test found immediately: constructing a browser with a URL and navigating in the very
next statement could fail with `ERR_ABORTED`, because Chromium cancelled the in-flight first load
and Electron reported that cancellation against the *new* navigation. `navigate` now waits for the
producer's un-awaited first load to settle. The textured browser had the same race and no way to
notice it.

### Pages as data

Once a page can be driven without being drawn, the web becomes a source Field can read rather than
only a picture it can show. The queries are extensions on `PuppetBrowserControl`, so they work
equally on a headless browser and on a `FieldUiBrowserTexture` already in your scene:

```diffstatic {data-lang=kotlin}
val readings = browser.table("#readings").get(10, TimeUnit.SECONDS)

readings.header                       // [station, depth, count]
readings.column("station")            // [alpha, beta, gamma]
readings.numbers("depth")             // [12.0, 48.0, 93.0]

browser.text("h1")                    // the first heading, or null
browser.texts("p.note")               // every match, in document order
browser.attribute("#link", "href")    // the markup's value, not the resolved URL
browser.count("#readings tbody tr")   // 3
browser.waitFor("document.readyState === 'complete'")
```

Three deliberate choices. They evaluate in an isolated world, so a page that has shadowed
`document.querySelectorAll` or shipped an enthusiastic polyfill cannot change what Field reads out
of it. `numbers` returns null for a cell that is not a number rather than zero, because a missing
reading and a reading of zero are different facts and a plot that cannot tell them apart is the
reason to be careful here. And `waitFor` waits *in the page*, so the whole wait costs one round trip
and a condition that throws fails at once instead of being retried until the deadline.

`table` gives you two parallel numeric columns, which is exactly what a Plotly or FLine box wants.

## How desktop events land on the page

[`PuppetBrowserWindowInput`](https://github.com/OpenEndedGroup/Field4/blob/main/src/jvmMain/kotlin/afield/four/browser/PuppetBrowserInteraction.kt)
turns Field window events into Chromium input:

1. the panel's aspect-correct viewport hit-tests the native cursor;
2. the hit becomes normalized panel UVs;
3. UVs become Chromium CSS pixels using the tab's device scale factor;
4. buttons are captured until release, even if the cursor leaves the panel;
5. key transitions keep their modifiers, scan codes, repeat and keypad identity; and
6. the character callback separately inserts Unicode text, while focus loss releases every held
   button and blurs the page.

The mapping is replaceable. A planar window uses a rectangle, but a curved screen can unproject a
window ray, intersect its own geometry, and return whichever UV belongs under the pointer.

## Then put the browser in VR

`PuppetBrowserVrPanel` places the same browser texture on a correctly proportioned world-space
quad. It is rendered once per eye, so the panel has actual stereo distance instead of being pasted
over the final headset image.

```diffstatic_runner {data-lang=kotlin screenshot="assets/puppet_browser/youtube-stereo.png" wait="12" timeout="90" capture="native-window" native-wait-frames="8" embed=true name="youtube-vr-browser" alt="A full YouTube browser panel rendered for the left and right eyes in a side-by-side stereo Field window" macro-file="../../dev_docs/agents/transcripts/youtube_vr_browser.yaml" capture-box="youtube_vr_renderer"}
val stereo = SideBySideStereoTarget(renderer, openXR = renderer.openXRHmdTarget)
val panel = PuppetBrowserVrPanel(
    renderer = renderer,
    stereo = stereo,
    browser = browser,
    widthMeters = 1.6f,
    distanceMeters = 1.7f
)
panel.build()
panel.enableDesktopInteraction()
```

The packed image above shows both eyes, as in the [ImGui VR panel](imgui_vr_panel.md): on a headset
the two views go to the eye swapchains; on a desktop they remain side by side as the development
preview. Each eye sees the same 1.6 m × 0.9 m browser panel from a slightly different position.

Each frame the interaction layer:

1. takes the OpenXR controller pose and projects its forward ray;
2. intersects that ray with the panel plane and converts the local hit to browser UVs;
3. maps select or trigger to a captured primary click;
4. maps the touched thumbstick or trackpad to page scrolling; and
5. leaves physical keyboard and IME input available for search boxes, editors and forms.

With no headset, Field's mouse-driven controller stand-in exercises the same ray, hit test, hover,
click and scroll path. The fragment shader draws a small ring at the browser UV currently under the
ray, so the preview also shows what Chromium is about to receive.

## Field can even browse Field

The first end-to-end proof points Chromium back at the current Field document and samples that page
onto a Vulkan mesh. It performs a page-to-Field-to-JavaScript promise round trip and publishes its
live `browserControl` property for neighbouring boxes:

```diffstatic_runner {data-lang=kotlin screenshot="assets/browser_texture/field-ui-on-texture.png" wait="12" timeout="90" capture="native-window" native-wait-frames="8" embed=true hidden=true name="field-ui-browser-texture" alt="Field's own live UI, produced by Chromium and sampled back onto a Vulkan surface" macro-file="../../dev_docs/agents/transcripts/field_ui_browser_texture.yaml" capture-box="field_ui_texture_renderer"}
val browser = FieldUiBrowserTexture(
    renderer = renderer,
    url = FieldUiBrowserTexture.fieldDocumentUrl(thisBox),
    width = 1280,
    height = 720,
    // Linux: Chromium's shared-texture export never fills the buffer (electron#53271).
    cpuBitmapFallback = FieldUiBrowserTexture.cpuBitmapFallbackRequired
)
```

That recursive example is the **field_ui_browser_texture** starting point. It is also a useful
reminder that this is a usable Field surface, not a screenshot: the capture has selected the live
browser-texture box and opened its real Monaco editor inside Chromium. Any page Chromium can compose
can become part of a Field scene.

## And then: the kaleidoscope

Once the browser is an ordinary native texture, a rectangle is only a convention. The punchline
opens a YouTube watch page, handles the EU consent page, starts its real video element, and fractures
the complete tab into 224 animated triangles:

```diffstatic_runner {data-lang=kotlin screenshot="assets/puppet_browser/youtube-kaleidoscope.png" wait="12" timeout="90" capture="native-window" native-wait-frames="8" embed=true name="youtube-kaleidoscope" alt="A live YouTube page fractured into 224 animated triangular Vulkan shards" macro-file="../../dev_docs/agents/transcripts/youtube_kaleidoscope.yaml" capture-box="youtube_kaleidoscope_renderer"}
val shards = PuppetBrowserKaleidoscope(
    renderer = renderer,
    browser = browser,
    columns = 14,
    rows = 8
)
shards.motion = 1.0f
shards.facets = 5.0f
shards.chroma = 1.0f
shards.build()
```

The figure is generated by replaying
[`youtube_kaleidoscope.yaml`](https://github.com/OpenEndedGroup/Field4/blob/main/dev_docs/agents/transcripts/youtube_kaleidoscope.yaml),
not by feeding a still image to the shader. The replay waits for Chromium, captures the native
Vulkan window, and reports the video title, an advancing `currentTime`, `readyState = 4`, and the
tab's audible state.

`PuppetBrowserKaleidoscope` builds a 14 × 8 grid and cuts every cell diagonally. Duplicated vertices
let all 224 triangles move independently. The vertex stage opens moving cracks; the fragment stage
performs an aspect-correct polar fold, twist and zoom per shard, separates the RGB sample positions,
and uses barycentric distance for the narrow glass edge.

WebGL can of course apply a kaleidoscope to a cooperative `<video>` element. What is different here
is the source: the browser's **fully composited tab**—cross-origin video, consent UI, DOM, fonts,
controls and subsequent navigations—enters an arbitrary native Vulkan/OpenXR pipeline without
canvas pixel access or cooperation from the page.

## Try the starting points

Start Field normally with `./field.sh` (or `.\field.ps1` on Windows), then choose one of these from
Starting points:

- **youtube_mouse_keyboard_browser** — a conventional browser controlled directly by the native
  window's mouse and keyboard.
- **youtube_vr_browser** — the same kind of page at stereo depth, with controller ray, trigger and
  thumbstick/trackpad interaction.
- **field_ui_browser_texture** — Field's own UI sampled back into Field, including the bidirectional
  page bridge.
- **youtube_kaleidoscope** — the texture handed to a deliberately excessive native mesh and shader.

Run the renderer box first and then its connected browser box. Every example publishes
`browserControl`, so another box can immediately navigate it, listen to events, evaluate JavaScript
or send CDP commands.
