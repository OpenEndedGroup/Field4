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

## RTT streaming — working (mono)

For everything that isn't lines — the full Vulkan-rendered scene — Field streams the render target
to the headset as native video. The render target is captured with zero extra copies, encoded to
HEVC, sent over a thin custom UDP link, decoded on-device, and presented on a screen you can place
in the room.

This spine is **verified end-to-end**: Field renders → encodes → streams → the visionOS app
reassembles, decodes, and displays it, with correct colour, and it survives the sender restarting.
The stream can be shown either on a world-locked "cinema" screen or in an ordinary movable/resizable
window.

---

## RTT + stereo — in progress

Stereoscopic output is the headline we're building toward (Field already renders side-by-side stereo
for other XR paths). The whole path is now wired: the **capture and encode path produces
side-by-side stereo frames**, and the **per-eye present material is authored** — a Camera Index
Switch ShaderGraph that samples the left half of the frame for the left eye and the right half for
the right. That material builds and loads cleanly.

What remains is a **device** check: the simulator renders a single mono view, so true per-eye
separation — the thing that makes stereo *stereo* — can only be confirmed on a real Vision Pro. So
stereo is wired end to end; verifying the per-eye result on hardware is the open piece.

---

## Status at a glance

| Capability | State |
|---|---|
| Native 3D FLine viewer — render, orbit/scale, auto-frame, bounding cage | **Working** |
| FLine interactivity (tap → server callback) | **Working** |
| FLine editing — node + Bézier control-point drag | **Working** |
| Web UI as spatial docks (editor / canvas / welcome) | **Working** |
| Shared dock selection + editor-to-canvas History panel routing | **Working, browser-regression tested** |
| RTT video streaming (mono), world-locked + windowed | **Working, verified end-to-end** |
| RTT stereo (per-eye present) | **Wired** — SBS pipeline + Camera Index Switch material load; per-eye separation pending device check |
| On-device polish — spatial-input feel, present smoothness | **Device verification ongoing** |

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
