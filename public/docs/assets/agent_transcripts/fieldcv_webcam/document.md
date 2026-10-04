# Field Document: fieldcv_webcam

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 8 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_session" "fileManagerAccessHistory" "mainLanguage"]}
```

## 1. python

<!-- field-box id="cv_python" index="0" -->

```edn [field-box id="cv_python" role="properties"]
{"id" "cv_python" "index" 0 "name" "python" "parents" ["root"] "children" ["cv_source" "cv_play" "cv_status"] "properties" {"children" #{#afield/box "cv_source" #afield/box "cv_play" #afield/box "cv_status"} "parents" #{#afield/box "root"} "name" "python" "_tombstone" false "frame" #afield/rect [40.0 160.0 160.0 40.0] "mainLanguage" "python"} "blockProperties" []}
```

## 2. 3 · what's running

<!-- field-box id="cv_status" index="1" -->

```edn [field-box id="cv_status" role="properties"]
{"id" "cv_status" "index" 1 "name" "3 · what's running" "parents" ["root" "cv_python"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "cv_python"} "name" "3 · what's running" "_tombstone" false "frame" #afield/rect [40.0 560.0 340.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "cv_status_code_.kt" "lines" 7 "characters" 407 "sha256" "d3b6c637e67548c88a6d20e4013dddb15b18c37a05580d6b1293226d218a22ef"}]}
```

```kotlin [field-property box="cv_status" name="code" path="cv_status_code_.kt"]
# 3 · what's running: re-run to refresh
r = p.latest
print(p, "error:" if p.error else "", p.error or "")
print("ms per detector:", {k: round(v, 1) for k, v in r["timings"].items()})
print(f"{r['width']}x{r['height']} frames, "
      f"{len(r.get('poses', []))} people, {len(r.get('faces', []))} faces, "
      f"hands: {r.get('handedness', [])}, {len(r.get('tracks', []))} tracks, {len(strokes)} strokes")
```

## 3. 0 · window

<!-- field-box id="cv_renderer" index="2" -->

```edn [field-box id="cv_renderer" role="properties"]
{"id" "cv_renderer" "index" 2 "name" "0 · window" "parents" ["root"] "children" ["cv_camera" "cv_inset" "cv_overlay"] "properties" {"children" #{#afield/box "cv_camera" #afield/box "cv_inset" #afield/box "cv_overlay"} "parents" #{#afield/box "root"} "name" "0 · window" "_tombstone" false "frame" #afield/rect [40.0 40.0 260.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "cv_renderer_code_.kt" "lines" 14 "characters" 269 "sha256" "95c45c51a6c7666fecb83660024bae1101ba2a71a7e9f6513b9656570a5a6af0"}]}
```

```kotlin [field-property box="cv_renderer" name="code" path="cv_renderer_code_.kt"]
import afield.four.util.Options

// 0 · the native window everything below draws into. Run this first.
prop<Window>(window)
prop<Renderer>(renderer)

Options.enableHDR = false

once.renderer {
    window = Window("fieldcv")
    Renderer(window)
}

start(renderer::run)
```

## 4. lines (Kotlin)

<!-- field-box id="cv_overlay" index="3" -->

```edn [field-box id="cv_overlay" role="properties"]
{"id" "cv_overlay" "index" 3 "name" "lines (Kotlin)" "parents" ["root" "cv_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "cv_renderer"} "name" "lines (Kotlin)" "_tombstone" false "frame" #afield/rect [460.0 360.0 260.0 60.0]} "blockProperties" [{"name" "vertex" "language" "glsl" "path" "cv_overlay_vertex_.glsl" "lines" 5 "characters" 173 "sha256" "80fa3b414c968e2b80a9ea7c059047812096607a7fbfbcdc5e1b66beb479e851"} {"name" "fragment" "language" "glsl" "path" "cv_overlay_fragment_.glsl" "lines" 4 "characters" 125 "sha256" "de401ae26147903e7089ad1203f372e4d717f269ac30588aa855a98ef3b4cc83"} {"name" "code" "language" "kotlin" "path" "cv_overlay_code_.kt" "lines" 47 "characters" 1958 "sha256" "e456bbd817d81a906a2b860d656fb16cd1275d5198f491252bfc40783ceabb70"}]}
```

```glsl [field-property box="cv_overlay" name="vertex" path="cv_overlay_vertex_.glsl"]
#version 450
layout(location = 0) in vec3 pos;
layout(location = 1) in vec4 col;
layout(location = 0) out vec4 fcol;
void main() { fcol = col; gl_Position = vec4(pos, 1); }
```

```glsl [field-property box="cv_overlay" name="fragment" path="cv_overlay_fragment_.glsl"]
#version 450
layout(location = 0) in vec4 fcol;
layout(location = 0) out vec4 uFragColor;
void main() { uFragColor = fcol; }
```

```kotlin [field-property box="cv_overlay" name="code" path="cv_overlay_code_.kt"]
import afield.python.NumpyShared

// every polyline channel box 1 publishes, and the colour it is drawn in
val channels = listOf(
    "tracks" to vec(0.2, 1.0, 0.3, 0.8),
    "skeleton" to vec(1.0, 0.3, 0.8, 1.0),
    "hands" to vec(1.0, 0.9, 0.2, 1.0),
    "face" to vec(0.3, 0.9, 1.0, 0.9),
    "contours" to vec(1.0, 0.55, 0.1, 1.0),
    "strokes" to vec(1.0, 1.0, 1.0, 1.0),
).map { (name, colour) -> NumpyShared.lines(name) to colour }

var module = moduleHere { }
var m = Mesh()
module += m named "overlay"
renderer += module

val width = 0.003   // half the line's width, in NDC
val strokeWidth = 0.008

frames {
    while (frame()) {
        // one segment per pair of consecutive nodes, in normalized image coordinates
        val segs = channels.flatMap { (shared, colour) ->
            val w = if (shared.name == "strokes") strokeWidth else width
            shared.lines().flatMap { f -> f.nodes.zipWithNext().map { Triple(it, colour, w) } }
        }
        if (segs.isEmpty()) continue
        // each segment is a thin quad, two triangles, since GPU lines are a single pixel wide
        m.set(4 * segs.size, 2 * segs.size) {
            for ((i, s) in segs.withIndex()) {
                val (ab, c, w) = s
                // image (u, v), v down, to Field's y-up NDC (2u - 1, 1 - 2v)
                val ax = 2 * ab.first.to.x - 1
                val ay = 1 - 2 * ab.first.to.y
                val bx = 2 * ab.second.to.x - 1
                val by = 1 - 2 * ab.second.to.y
                val len = maxOf(Math.hypot((bx - ax).toDouble(), (by - ay).toDouble()), 1e-6)
                val nx = -(by - ay) / len * w
                val ny = (bx - ax) / len * w
                pos.add(ax + nx, ay + ny, 0.1, ax - nx, ay - ny, 0.1, bx - nx, by - ny, 0.1, bx + nx, by + ny, 0.1)
                4 { col.add(c.x, c.y, c.z, c.w) }
                tri.add(4 * i, 4 * i + 1, 4 * i + 2, 4 * i, 4 * i + 2, 4 * i + 3)
            }
        }
    }
}
```

## 5. 2 · things to try

<!-- field-box id="cv_play" index="4" -->

```edn [field-box id="cv_play" role="properties"]
{"id" "cv_play" "index" 4 "name" "2 · things to try" "parents" ["root" "cv_python"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "cv_python"} "name" "2 · things to try" "_tombstone" false "frame" #afield/rect [40.0 400.0 340.0 120.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "cv_play_code_.kt" "lines" 18 "characters" 993 "sha256" "2994b5a05d0afd774b116a984d74f9fe66016e2b15e4d4d6dc209541c95522b2"}]}
```

```kotlin [field-property box="cv_play" name="code" path="cv_play_code_.kt"]
# 2 · things to try - select a line and run it (Alt-Enter runs the line under the cursor)

use("hands")                                # just hands: pinch thumb and index to draw
clear()                                     # wipe the strokes

use("hands", "face", "pose")                # landmarks only
use("tracks", "flow")                       # motion: point tracks plus dense optical flow
inset_view = motion                         # ...and show the flow field in the inset

use("background", "tracks")                 # what's moving against the background; outlines in orange
inset_view = mask

# the inset is any function of the results that returns an image: write your own
inset_view = lambda r: cv2.applyColorMap(cv2.cvtColor(r["frame"].image, cv2.COLOR_BGR2GRAY), cv2.COLORMAP_TURBO)
inset_view = lambda r: cv2.stylization(r["frame"].image, sigma_s=40, sigma_r=0.3)   # slow: watch fps in box 3

inset_view = edges
use("tracks", "pose", "hands", "face")      # back to the start
```

## 6. 1 · webcam + detectors

<!-- field-box id="cv_source" index="5" -->

```edn [field-box id="cv_source" role="properties"]
{"id" "cv_source" "index" 5 "name" "1 · webcam + detectors" "parents" ["root" "cv_python"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "cv_python"} "name" "1 · webcam + detectors" "_tombstone" false "frame" #afield/rect [40.0 240.0 340.0 120.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "cv_source_code_.kt" "lines" 86 "characters" 3418 "sha256" "5cd9021af710030e436832a29f5d305a4f9e05c86d6753f4f0be9e45988a17fe"}]}
```

```kotlin [field-property box="cv_source" name="code" path="cv_source_code_.kt"]
# 1 · the webcam, and everything fieldcv can find in it
#
# Run this once. It starts a Pipeline on a worker thread and returns straight away; the
# frames and everything detected in them go to shared memory under the names below, where
# the graphics boxes on the right read them with NumpyShared.image(...) / .lines(...).
import os, math
import cv2, numpy as np
import fieldcv as cv4
from fieldcv import field

H, W = 360, 640                                    # what we publish; the camera is resized to fit

def open_input():
    try:
        return cv4.Camera(0, width=1280, height=720, mirror=True)
    except RuntimeError as e:
        movie = os.path.expanduser("~/Desktop/pythonhead/015.mp4")
        print(f"{e} - if macOS asked about the camera, allow it and re-run. Using {movie} meanwhile.")
        return cv4.Video(movie, loop=True)

# every detector, built once; `use(...)` in box 2 chooses which ones run
detectors = dict(
    tracks=cv4.Tracks(length=15, max_tracks=300),
    pose=cv4.Pose(model="lite"),
    hands=cv4.Hands(max_hands=2),
    face=cv4.Face(max_faces=2),
    flow=cv4.Flow(),
    background=cv4.Background("MOG2", contours=True, min_area=0.002),
)

camera = field.image("camera", H, W)               # the picture, full window
inset = field.image("inset", H, W)                 # a second picture, top right: see `inset_view`
channels = {n: field.lines(n) for n in ["tracks", "skeleton", "hands", "face", "contours", "strokes"]}

# --- the inset: any function of the frame and the results that returns an image -----------
def edges(r):
    return cv2.Canny(cv2.cvtColor(r["frame"].image, cv2.COLOR_BGR2GRAY), 60, 140)

def motion(r):
    return cv4.Flow.hsv(r["flow"]) if "flow" in r else edges(r)

def mask(r):
    return r["mask"] if "mask" in r else edges(r)

inset_view = edges

# --- pinch to draw: thumb tip (4) near index tip (8) paints, opening your fingers lifts the pen
strokes, pen_down = [], False

def draw_with_fingers(r):
    global pen_down
    hands = r.get("hands", np.zeros((0, 21, 3)))
    if len(hands) == 0:
        pen_down = False
        return
    tip, thumb = hands[0][8, :2], hands[0][4, :2]
    pinched = math.dist(tip * (W, H), thumb * (W, H)) < 0.06 * W
    if pinched and not pen_down:
        strokes.append([])
    if pinched:
        strokes[-1].append((tip + thumb) / 2)
    pen_down = pinched

def clear():
    strokes.clear()

# --- called on the worker thread for every frame ------------------------------------------
def publish(r):
    camera.write(r["frame"].image)
    inset.write(inset_view(r))
    draw_with_fingers(r)
    channels["tracks"].publish(r.get("tracks", []))
    channels["skeleton"].publish(cv4.edges(r["poses"], detectors["pose"].connections) if "poses" in r else [])
    channels["hands"].publish(cv4.edges(r["hands"], detectors["hands"].connections) if "hands" in r else [])
    channels["face"].publish(cv4.edges(r["faces"], detectors["face"].connections) if "faces" in r else [])
    channels["contours"].publish([np.vstack([c, c[:1]]) for c in r.get("contours", [])])
    channels["strokes"].publish([np.asarray(s) for s in strokes if len(s) > 1])

def use(*names):
    """Run just these detectors, e.g. use("hands", "face"). The pipeline keeps running."""
    p.detectors = [detectors[n] for n in names]
    return p

p = cv4.Pipeline(open_input(), on_result=publish)
use("tracks", "pose", "hands", "face")
p.start()
```

## 7. camera (Kotlin)

<!-- field-box id="cv_camera" index="6" -->

```edn [field-box id="cv_camera" role="properties"]
{"id" "cv_camera" "index" 6 "name" "camera (Kotlin)" "parents" ["root" "cv_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "cv_renderer"} "name" "camera (Kotlin)" "_tombstone" false "frame" #afield/rect [460.0 160.0 260.0 60.0]} "blockProperties" [{"name" "vertex" "language" "glsl" "path" "cv_camera_vertex_.glsl" "lines" 5 "characters" 169 "sha256" "80f69b3a9a1497ca4ac879a99dace896977ede4f5e829aca28e0619eb925662a"} {"name" "fragment" "language" "glsl" "path" "cv_camera_fragment_.glsl" "lines" 5 "characters" 210 "sha256" "4e3cb07b11d80dd5d9a507eb371c4bd872f041cfad03f189fce941c17fe2bf9c"} {"name" "code" "language" "kotlin" "path" "cv_camera_code_.kt" "lines" 16 "characters" 486 "sha256" "92e43d35e9ab95a21e020cffa5aea50cd63cc81cfa87dd9d301a981e080f66ed"}]}
```

```glsl [field-property box="cv_camera" name="vertex" path="cv_camera_vertex_.glsl"]
#version 450
layout(location = 0) in vec3 pos;
layout(location = 1) in vec2 tc;
layout(location = 0) out vec2 ftc;
void main() { ftc = tc; gl_Position = vec4(pos, 1); }
```

```glsl [field-property box="cv_camera" name="fragment" path="cv_camera_fragment_.glsl"]
#version 450
layout(location = 0) in vec2 ftc;
layout(location = 0) out vec4 uFragColor;
layout(set = 0, binding = 0) uniform sampler2D camera;
void main() { uFragColor = vec4(texture(camera, ftc).rgb, 1.0); }
```

```kotlin [field-property box="cv_camera" name="code" path="cv_camera_code_.kt"]
import afield.python.NumpyShared

// the webcam frame box 1 publishes, filling the window
prop<LoadedTexture>(camera) = NumpyShared.image("camera").texture()

var module = moduleHere { }
var m = Mesh()
module += m named "camera"
renderer += module

// Field's NDC is y-up and an image's v runs down, so the top edge (y = 1) samples v = 0
m.set(4, 6) {
    pos.add(-1, -1, 0.9,  1, -1, 0.9,  1, 1, 0.9,  -1, 1, 0.9)
    tc.add(0, 1,  1, 1,  1, 0,  0, 0)
    tri.add(0, 1, 2,  0, 2, 3)
}
```

## 8. inset (Kotlin)

<!-- field-box id="cv_inset" index="7" -->

```edn [field-box id="cv_inset" role="properties"]
{"id" "cv_inset" "index" 7 "name" "inset (Kotlin)" "parents" ["root" "cv_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "cv_renderer"} "name" "inset (Kotlin)" "_tombstone" false "frame" #afield/rect [460.0 260.0 260.0 60.0]} "blockProperties" [{"name" "vertex" "language" "glsl" "path" "cv_inset_vertex_.glsl" "lines" 5 "characters" 169 "sha256" "80f69b3a9a1497ca4ac879a99dace896977ede4f5e829aca28e0619eb925662a"} {"name" "fragment" "language" "glsl" "path" "cv_inset_fragment_.glsl" "lines" 5 "characters" 208 "sha256" "3821ca6bdadb9767bddfad46f91bddb93f46ed5a73988e4be5877d17c41afd8a"} {"name" "code" "language" "kotlin" "path" "cv_inset_code_.kt" "lines" 16 "characters" 499 "sha256" "17e0820737d04b94ce25dfafc1782de58cacf252c9da0cece21cf44fa12a9ead"}]}
```

```glsl [field-property box="cv_inset" name="vertex" path="cv_inset_vertex_.glsl"]
#version 450
layout(location = 0) in vec3 pos;
layout(location = 1) in vec2 tc;
layout(location = 0) out vec2 ftc;
void main() { ftc = tc; gl_Position = vec4(pos, 1); }
```

```glsl [field-property box="cv_inset" name="fragment" path="cv_inset_fragment_.glsl"]
#version 450
layout(location = 0) in vec2 ftc;
layout(location = 0) out vec4 uFragColor;
layout(set = 0, binding = 0) uniform sampler2D inset;
void main() { uFragColor = vec4(texture(inset, ftc).rgb, 1.0); }
```

```kotlin [field-property box="cv_inset" name="code" path="cv_inset_code_.kt"]
import afield.python.NumpyShared

// box 1's `inset_view`, in the top-right corner
prop<LoadedTexture>(inset) = NumpyShared.image("inset").texture()

var module = moduleHere { }
var m = Mesh()
module += m named "inset"
renderer += module

// Field's NDC is y-up and an image's v runs down, so the top edge (y = 0.98) samples v = 0
m.set(4, 6) {
    pos.add(0.45, 0.45, 0.5,  0.98, 0.45, 0.5,  0.98, 0.98, 0.5,  0.45, 0.98, 0.5)
    tc.add(0, 1,  1, 1,  1, 0,  0, 0)
    tri.add(0, 1, 2,  0, 2, 3)
}
```

