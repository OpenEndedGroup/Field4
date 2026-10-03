# fieldcv

The user documentation is `public/docs/python_cv.md` (*Computer Vision in Python* on the site).

Webcam, movie and image-sequence computer vision for Field's Python boxes, using OpenCV and
mediapipe.

fieldcv grew out of *pythonhead*, the helper that older Field versions ran as a separate process,
reading its results back as JSON on stdout ([the contour drawing tutorial](https://openendedgroup.com/field/contourDrawingTutorial.html)).
fieldcv keeps the same detectors, but they run in Field's own Python interpreter, and their
results reach Kotlin and the GPU through shared memory instead of text.

## Installing

fieldcv is optional. Field puts this directory on `sys.path` at startup, and the libraries it
needs come from the `cv` dependency group. Install them into the venv Field's Python uses (the
one named in the `/welcome` Python settings):

```bash
UV_PROJECT_ENVIRONMENT=.venv uv sync --inexact --group cv --group docs --group backdoor
```

`--inexact` adds packages without removing anything else from the venv. mediapipe is pinned
below 1.0 because 1.0.x aborts the whole process on macOS when a landmarker is created, and inside
Field that process is the JVM.

The face, pose and hand models are downloaded on first use into `~/.afield/models/fieldcv` (or
`$FIELDCV_MODEL_DIR`), and each one is checked against a pinned SHA-256. Nothing large is
committed.

## In a Python box

```python
import fieldcv as cv4

p = cv4.Pipeline(cv4.Camera(0), cv4.Tracks(), cv4.Pose(model="lite")).start()
```

`.show()` draws every detector's findings into the box's output as it runs:
`cv4.Pipeline(cv4.sample("swing"), cv4.Pose(), cv4.Hands(), cv4.Face()).show().start()`. `cv4.sample(name)`
is one of the public-domain clips in `samples/` (credits in `samples.py`), so nothing needs a camera.

`start()` runs the pipeline on a worker thread, so the cell returns straight away and the box
stays responsive. Re-run a cell to look at `p.latest`, or at `p` itself, which shows its frame
count and fps. Stopping the box stops the pipelines it started, and re-running the cell replaces
the pipeline instead of starting a second one.

| detector | result keys |
| --- | --- |
| `Flow()` | `flow` (H, W, 2) in pixels, `mean_motion`, `motion_centroid`, `brightness_centroid`, `mean_bgr` |
| `Tracks(length=20)` | `tracks`: a list of (n, 2) arrays; `ids`: stable per track |
| `Background("MOG2", contours=True)` | `mask`, `centroid`, `coverage`, `contours` |
| `LumaKey(128, contours=True)` | the same as `Background` |
| `Lines()` | `segments` (N, 2, 2) |
| `Circles()` | `circles` (N, 3): cx, cy, r |
| `Face()` | `faces` (F, 478, 3) |
| `Pose(model="heavy" or "lite")` | `poses` (P, 33, 3), `visibility` (P, 33) |
| `Hands(max_hands=2)` | `hands` (H, 21, 3), `handedness` |

Coordinates are normalized: `(0, 0)` is the top-left corner of the frame and `(1, 1)` the
bottom-right. When nothing is found you get an empty array, or `None` for a centroid, never
`NaN`. Each detector's `draw(image, result)` paints its result onto a BGR image for a preview.

Sources are `Camera(0)`, `Video(path, loop=True)`, `Images("frames/*.png")` and
`Arrays(list_of_images)`. `open_source(spec)` chooses one from a string.

## Sharing memory with Kotlin

```python
from fieldcv import field

camera = field.image("camera", 720, 1280)   # a (720, 1280, 4) uint8 numpy array in JVM memory
tracks = field.lines("tracks")

def publish(r):
    camera.write(r["frame"].image)          # BGR into the shared BGRA memory, then publish
    tracks.publish(r["tracks"])

p = cv4.Pipeline(cv4.Camera(0), cv4.Tracks(), on_result=publish).start()
```

In a Kotlin box under the renderer:

```kotlin
prop<LoadedTexture>(camera) = NumpyShared.image("camera").texture()   // uploads only new frames
NumpyShared.lines("tracks").lines()                                   // List<FLine>, normalized
```

`camera.array` is the numpy array itself, so OpenCV can write into it directly
(`cv2.cvtColor(..., dst=camera.array)`). Every buffer exists three times over, so a reader never
sees half of one frame and half of the next. The reasons are in the KDoc of
`src/jvmMain/kotlin/afield/python/NumpyShared.kt`. `tracks.draw()` previews the lines as SVG in
the Python box's own output.

[`examples/shared_memory.yaml`](examples/shared_memory.yaml) is the whole thing as an
OperationTranscript macro. It builds a Python box that runs tracks and pose, a box that draws the
frame as a texture, and a box that draws the tracks and skeleton as a line mesh:

```bash
FIELDCV_SOURCE=movie.mp4 uv run --project . --group backdoor python tools/field_backdoor.py --json \
  --command 'macro write graphics_renderer_launch --file dev_docs/agents/transcripts/graphics_renderer_launch.yaml' \
  --command 'macro run graphics_renderer_launch' \
  --command 'wait-native-renderer agent_graphics_context --frames 3' \
  --command 'macro write fieldcv_shared_memory --file python/fieldcv/examples/shared_memory.yaml' \
  --command 'macro run fieldcv_shared_memory'
```

The line mesh shows two things that are easy to get wrong. Field's NDC is y-up, so an image's
`v` maps to `1 - 2v`. And a `Mesh` always draws through its index buffer, so a `lines` mesh needs
a `tri.add(a, b)` for every segment: `set(n)` without indices draws nothing at all.

## Outside Field

`python -m fieldcv` is pythonhead's old command line. It prints one JSON object per frame, and
`--save-frames DIR` writes an annotated preview of each frame:

```bash
python -m fieldcv --source movie.mp4 --tracks --pose --pose-model lite > out.jsonl
```

## Tests

```bash
uv run --project . --group cv python -m unittest tools/test_fieldcv.py   # detectors, on synthetic frames
./gradlew jvmTest --tests 'afield.python.NumpySharedTest'                # the publish/consume contract
```

The Python suite skips itself, and says why, when the `cv` group is not installed.

## Not carried over from pythonhead

- **The YOLO outline and Hugging Face face-attribute processors.** They need torch, which is far
  heavier than everything else here put together. They can come back as a separate extra.
- **The `tocsv.py` / `tosca.py` converters.**
- **Writing intermediate frames as JPEG or PNG on every run.** Use `--save-frames` when you want
  them.
