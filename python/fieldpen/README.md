# fieldpen

The user documentation is `public/docs/learned_pen.md` (*A Learned Pen* on the site).

The optional Python half of Field's learned pen: the same LSTM handwriting model as
`afield.ai.learning.pen`, in PyTorch, for training, fine-tuning and poking at its memory in a
browser. **Continuing an FLine does not need any of this.** Kotlin runs the model itself;
`line.continueWith()` works in a fresh checkout with nothing installed.

The model is the one from Carter, Ha, Johnson and Olah, [*Experiments in Handwriting with a Neural
Network*](https://distill.pub/2016/handwriting/) (Distill, 2016): a 500-cell LSTM whose output is a
mixture of 20 Gaussians for the next pen displacement plus a pen-lift probability, trained on the IAM
On-Line Handwriting Database. Its published weights are in
`src/jvmMain/resources/afield/pen/distill-b64.json`, one copy shared by Kotlin and Python. This
PyTorch port was written with ChatGPT in October 2026 and is Apache-2.0 (`LICENSE`, `NOTICE`); the
Distill repository's licence is next to the weights.

## Installing

Field puts this directory on `sys.path` at startup. Install torch into the venv Field's Python uses:

```bash
UV_PROJECT_ENVIRONMENT=.venv uv sync --inexact --group pen --group docs --group backdoor
```

## In a Python box

```python
from fieldpen import load_model, Session, Controls

pen = Session(load_model(), seed=42)      # the bundled Distill weights
pen.consume([[0.1, 0.03, 0], [0.08, -0.04, 1]])
out = pen.generate(200, Controls(mixture_temperature=0.65, noise_scale=0.4))
futures = pen.branches(branches=32, steps=40)   # does not advance `pen`
pen.intervene(cell=136, value=2.0, state="c")   # edit one memory cell
```

Rows are `[dx, dy, lift]` in model units, where `lift` belongs to the point just reached: the
*next* displacement is the move to the following stroke. A handwritten step is about a quarter of a
unit; Kotlin's default of 8 canvas units per model unit makes that two pixels.

## Training your own pen

`public/docs/teaching_a_pen.md` (*Teaching a Pen*) is the long version: six synthetic corpora, the
recipe that makes a pen draw rather than drift, and the pens Field ships. In short:

Record strokes as JSON files of `{"strokes": [[[x, y], ...], ...]}` (one ordered pen-down path per
inner list), keep training and validation drawings in separate folders, and:

```bash
uv run --project . --group pen python -m fieldpen.prepare drawings/train train.npz --scale 8
uv run --project . --group pen python -m fieldpen.prepare drawings/valid valid.npz --scale 8
uv run --project . --group pen python -m fieldpen.train --train train.npz --valid valid.npz \
    --out runs/mine --init src/jvmMain/resources/afield/pen/distill-b64.json \
    --crop 256 --samples 2048 --batch 64 --input-noise 0.02 --device mps
```

`--scale` is drawing units per model unit, the same number you pass Kotlin. `--init` fine-tunes
from the handwriting model; leave it out (and set `--hidden`, `--layers`, `--mixtures`) to start from
nothing. Hold out whole drawings, not crops of drawings you train on.

Then hand the result to Kotlin, which does not read `.pt` files:

```python
import fieldpen
fieldpen.export_for_field(fieldpen.load_model("runs/mine/best.pt"), "mine.fieldpen.json", half=True)
```

```kotlin
val mine = PenModel.load("/path/to/mine.fieldpen.json")
lines(start, start.continueWith(mine))
```

## The lab

```bash
uv run --project . --group pen python -m fieldpen.lab
```

opens a page at `http://127.0.0.1:8765/` that draws with the model, shows 32 futures, lets you draw
your own primer, and edit cells while it writes. `python -m fieldpen.server` is the same session
protocol over JSON lines on stdin/stdout.

## Tests

```bash
uv run --project . --group pen python -m unittest tools/test_fieldpen.py
./gradlew jvmTest --tests 'afield.ai.learning.pen.PenModelTest'   # Kotlin against torch
```

`src/jvmTest/resources/afield/pen/*_reference.json` were written by torch from this package; if the
model code changes, regenerate them with `tools/fieldpen_reference_fixture.py`.
