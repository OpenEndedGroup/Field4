# A figure that does not fit inside itself — a document you can open

Open `metadata.edn` in Field (**File ▸ Open**, or `./field.sh <path to this folder>/metadata.edn`).

**Run box 2, "2 — the same figure, unclipped".** The plot collapses into an illegible stamp in one
corner of an empty rectangle. That is `sharp_edge.png`, the figure that shipped on
[`public/docs/tetgen.md`](../../../../public/docs/tetgen.md) over five green gates. The thing to
poke is the single word `clip` on the line the box marks with an arrow — box 1 is the same figure
with it left alone.

| box | what it does | what to notice |
| --- | --- | --- |
| **1 — the corner, clipped** | a 2 × 3 outline charted into a 0.24 × 0.24 window on one of its corners, with an explicit `xDomain`/`yDomain` | a legible corner plot. It prints `plotRect` and the bounds of everything drawn: `741x412` inside the `780x440` the figure asked for |
| **2 — the same figure, unclipped** | the identical figure with `clip = false` | drawn bounds `5815x4437` for a `780x440` frame, and the box says what that costs: **10% of its declared size**. A panel fits the geometry's *own* bounds, so nothing outside the frame gets cropped — it shrinks everything else |
| **3 — a note, wrapped and not** | the 142-character note the tetgen figure shipped with, wrapped and unwrapped | `clip = true` → 757 px in a 780 px frame → 100%. `clip = false` → 1240 px → **63%**. Change `figure(clip = true)` to `false` on the `showChart` line and re-run to see it |
| **4 — every mark kind, at the edge** | an area, a line, points and two rules, half of them outside an explicit window | `area` and `line` are 0.00 px outside `plotRect`; `points` is 3.00 px, which is the dot radius and not a clip failure — a dot is dropped when its centre leaves the window, never cut in half. The rule at `y = 99` on a `0..20` axis is not drawn at all |

Box 2 is the one to run first. Box 4 is the one that shows the fix is not just about lines.

`build.yaml` beside this file is the `OperationTranscript` that built the document. Replay it into a
fresh Field with:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --scratch --with-playwright --port 9477 \
  --command 'macro write chart_clip --file dev_docs/agents/demos/chart_clip/build.yaml' \
  --command 'macro run chart_clip'
```

The loose `*_code_.kt` files are what Field loads; `metadata.edn` carries portable inline copies of
the same text, and `./gradlew checkAgentDemos` fails if the two ever disagree.

The written-up version, with the falsification, the population and the rendered before/after pairs,
is [`dev_docs/chart_clip_and_note.md`](../../../chart_clip_and_note.md).
