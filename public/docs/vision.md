# Field on Apple Vision Pro — work in progress

!!! warning "Work in progress"
    This is an early, honest snapshot of Field's native **visionOS** front end. Some pieces are
    solid and verified; others are half-built and named as such. Expect it to move.

Field's front end and its Vulkan renderer already talk to each other over JSON — the browser is
just one client of the Field server. That decoupling is what makes a spatial front end tractable:
instead of trying to cram a desktop UI into a headset, we give each half of Field a native home on
visionOS and let them float in the room.

- **Docks** — the existing Field web UI (editor, canvas, welcome) rendered in `WKWebView`s the
  system composites and drives. HTML stays HTML; we never texture it.
- **Islands** — native **RealityKit** geometry for the things that want to be genuinely 3D.
- **Streams** — the GPU renderer's output carried to the headset as native video.

All three are scoped projections of the *same* server-authoritative document, so nothing here forks
Field: the visionOS app is a third client alongside the browser, speaking the protocols Field
already has.

The web docks also behave as one workspace rather than isolated browser windows: selection follows
between a canvas and its floating editor, and canvas-owned panels are routed to the right surface.
For example, choosing **Show Document History** from the editor opens the full-width History panel
over the paired canvas instead of covering Monaco.

---

## Native 3D FLine viewer — working

The first native island: a RealityKit viewer for **FLines** (Field's line/geometry primitive), the
native equivalent of the in-browser three.js viewer. It subscribes to every live FLine view on the
Field server — the same views you get from `threeLines(...)` in a box — and renders them fully in 3D.

![The native FLine viewer showing a 3D helix, a filled triangle and a cubic curve inside a light bounding cage](assets/vision/fline-viewer.jpg)
*A live `threeLines(...)` view in the native viewer: strokes as swept 3D tubes, a translucent filled
triangle, a cubic curve, inside a light wireframe bounding cage.*

What it does today:

- **Real 3D geometry.** Strokes become swept **tubes** (with genuine thickness — a step up from the
  browser's flat 1px lines), fills become triangle meshes, and nodes become spheres. Colour and
  transparency come straight from the FLine.
- **Manipulation.** Drag to orbit, pinch to scale, one tap to reset the framing. The model
  auto-frames itself into its window when it arrives or changes.
- **A bounding cage.** A light wireframe cuboid tracks the model as you rotate and scale it, then
  crossfades to sit upright and window-aligned when you let go — a quiet spatial reference frame.
- **Interactivity.** Tapping an interactive FLine fires its server-side `onClick` callback with the
  nearest vertex, exactly as the browser viewer does.

### Editing

When a view is opened editable (`threeLines(..., editable = true)`), the viewer becomes a spatial
editor:

![Edit mode: amber anchor handles along the curves and cyan Bézier control-point handles with control-polygon lines](assets/vision/fline-editing.jpg)
*Edit mode. Amber spheres are node anchors; cyan spheres are cubic **Bézier control points** (with
faint control-polygon lines back to their anchors). Drag any of them to reshape the curve.*

- Drag an **anchor** to move a node; drag a **control point** to bend a Bézier tangent.
- Edits apply optimistically in the headset and are sent back to the server, so the authoritative
  FLine stays in sync.

It lives in a movable, resizable **volumetric window** — grab its bar to reposition it, drag its
corners to resize.

---

## Live Metal — programmable native graphics

The second native surface, and a different bargain from the FLine viewer: instead of Field sending
geometry to be displayed, the headset runs a **program** of yours. A box opens a mixed-immersion
Compositor Services scene with its own Metal render thread, and you live-code against it.

A program has three explicit parts, in three places:

| Part | Where you write it |
|---|---|
| **shader** | the box's `msl` property — Metal Shading Language, both entry points |
| **geometry** | `geometry { … }` in the box's Kotlin, which runs *in the headset* |
| **uniforms** | the `uniforms=` list on the box's `// {{ visionmetal }}` marker |

```diffstatic {data-lang=kotlin}
// {{ visionmetal uniforms=phase,amount,tint }}
onStart {
    geometry {
        color(1.0, 0.35, 0.15)
        cube(0.18, cx = 0.45, cz = -1.2, tintFaces = true)
    }
    uniform("amount", 0.025)
    onFrame { frame -> uniform("phase", frame.time * 3.0) }
}
```

The Kotlin compiles to JavaScript and runs in a small JavaScriptCore context *on the render thread* —
not in a web view, and not in the animation path over the network. `geometry { … }` builds an indexed
triangle mesh in the headset and hands the whole thing to Metal in one call, so nothing per-vertex
crosses the wire at frame rate. Alongside a raw `vertex`/`triangle`/`quad` builder there are `cube`,
`plane`, `sphere`, and `tube` — which sweeps a polygon along a polyline, the same real-thickness
treatment the FLine viewer gives a stroke. **[Building geometry in the headset](vision_geometry.md)**
covers the builder in full, with runnable starting points.

Each part reloads on its own. Editing the shader and pressing **Reload MSL** recompiles the pipeline
on the headset while your Kotlin, your geometry, and the frame clock all keep running — and a failed
compile leaves the last working shader on screen and reports the Metal error back into Field at your
own line number. The shader reads its uniforms by name (`u_phase`), because the head generates the
`#define`s from the declared list.

Field's Kotlin side sees MSL as a real language: `msl` is a box property with its own syntax
highlighting and completion (the ABI structs, the `u_NAME` uniforms *that box* declares, and snippets
for both entry points), and it saves beside the box as a `.metal` file.

**What's verified where.** The earlier proof-of-concept — one shader, one built-in cube, uniforms on
the compositor clock — runs on a physical Vision Pro, and the round trip from a Kotlin edit to a
changed image is immediate rather than laggy.

The three-part structure described above is newer, and most of it turns out to be checkable without a
headset, because it is orthogonal to the things a simulator can't do. A script drives the real app in
the **visionOS simulator** and asserts on the status messages the head sends back over the wire: that
Apple's Metal compiler accepts the generated shader preamble, that Kotlin-built geometry arrives as
the mesh the builder's arithmetic predicts, that a shader-only reload leaves the script and geometry
running, and that a broken shader reports the author's own line number. All of that passes. What the
simulator cannot judge — per-eye separation, spatial input, display timing — is device work, and this
particular slice barely touches it.

---

## RTT streaming — working (mono)

For everything that isn't lines — the full Vulkan-rendered scene — Field streams the render target
to the headset as native video. On macOS the Vulkan RTT is backed directly by an IOSurface that
VideoToolbox can wrap; on Linux the sender imports an exported Vulkan allocation through CUDA.
The encoded HEVC/H.264 access units travel over a small UDP protocol. The visionOS client
reassembles and decodes them, converts the decoded NV12 buffer into a RealityKit texture, and
presents it on a screen you can place in the room.

This spine is **verified end-to-end**: Field renders → encodes → streams → the visionOS app
reassembles, decodes, and displays it, with correct colour, and it survives the sender restarting.
The stream can be shown either on a world-locked "cinema" screen or in an ordinary movable/resizable
window.

---

## RTT + stereo — working

Stereoscopic output was the headline we were building toward, and it works. The capture and encode
path produces **side-by-side stereo frames**, and a Camera Index Switch ShaderGraph material samples
the left half of the frame for the left eye and the right half for the right.

This needed a real Vision Pro to confirm, because the simulator renders a single mono view — true
per-eye separation, the thing that makes stereo *stereo*, is invisible there. It is confirmed on
hardware.

Getting it smooth turned out to be a separate hunt from getting it correct, and worth recording: the
visible hiccup was **Wi-Fi contention from screen-sharing the Mac into the headset**, not the
pipeline. Every in-app counter stayed clean throughout, because contention delays datagrams rather
than losing them — so the metrics all looked fine while the image did not. Check what else is on the
link before you distrust the code. The hunt did surface real defects, all fixed: a frame pacer that
discarded the remainder on a late tick (29.56fps against a 30fps target — fatal on a 90Hz display,
where 30.00fps is exactly three refreshes and any deficit accumulates into a held frame), a
presentation timestamp taken from the wall clock at emit time rather than derived from the sequence
number, and the absence of a receiver-side buffer. The receiver now runs a depth-servoed presentation
clock, which deliberately contradicts the present-on-arrival rule the wire format started with — that
was the wrong trade for a 90Hz display.

---

## Status at a glance

| Capability | State |
|---|---|
| Native 3D FLine viewer — render, orbit/scale, auto-frame, bounding cage | **Working** |
| FLine interactivity (tap → server callback) | **Working** |
| FLine editing — node + Bézier control-point drag | **Working** |
| Web UI as spatial docks (editor / canvas / welcome) | **Working** |
| Shared dock selection + editor-to-canvas History panel routing | **Working** |
| Live Metal — live shader + uniforms on the compositor clock | **Working on device** |
| Live Metal — headset-built geometry, per-part reload, `msl` as a property | **Builds, but not yet tried on a headset** |
| RTT video streaming (mono), world-locked + windowed | **Working** |
| RTT stereo (per-eye present) | **Working on device** |
| On-device polish — spatial-input feel, present smoothness | **Working on device** |

---

## Trying it

The visionOS app is a standalone Xcode project (visionOS 26 SDK) that points at a running Field
server. In short:

1. Start a Field server and build/run the visionOS app against it.
2. In Field, run a box with `threeLines(myLines, editable = true)`.
3. In the headset, open the **FLine 3D** viewer — it discovers the live view and renders it. Toggle
   **Edit** to grab the handles.

The screenshots above are from the visionOS simulator. Spatial-input feel and video smoothness are
best judged on real hardware — the simulator can't drive spatial gestures and throttles the
immersive scene — so those remain the active on-device verification items.
