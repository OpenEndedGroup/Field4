# `chart()` — a document you can open

Open `metadata.edn` in Field (**File ▸ Open**, or `./field.sh <path to this folder>/metadata.edn`)
and run the four boxes. No GPU, no window, no sidecar: `Chart` lowers to `List<FLine>` and goes out
through `Box.lines`' own SVG renderer, so everything here is the same path any drawn figure in Field
takes.

| box | what it does | what to notice |
| --- | --- | --- |
| **1 — a chart, in one call** | `showChart("damped sine" to …, "envelope" to …, x = t)` | axes with real tick labels, ticks on round numbers, a legend, a rotated y label — and the labels are **glyph outlines**, not a raster and not HTML |
| **2 — a DataFrame charts itself** | `showChart(runs)` over a `dataFrameOf(…)`, then the frame itself as the last expression | the chart *and* the table, from one frame. `iteration` became the x axis and the other two numeric columns became series, with no column named |
| **3 — hover a bar, click a datum** | `bars(…)` plus a `c.onPick { … }` handler | hover a bar and it lights up; click one and `println` runs **in this box**. Nothing configured that: marks set `onClick`/`hoverColor`, which is what `Box.lines` looks for (`someSkiko.kt:37`) |
| **4 — an assertion you can watch fail** | `.expect(max = 1e-6, because = "converged below 1e-6")` | the claim is *drawn*: a band, its named bound ruled, the four real overshoots recoloured, "4 points outside expectation" in the footnote — and `deviations` hands the same four back to the `facts()` table below |

Box 3 is the one to actually touch. Box 4 is the one that says why the primitive earns its keep: the
difference between showing a plot and showing an assertion you can watch fail.

`build.yaml` beside this file is the `OperationTranscript` that built the document. Replay it into a
fresh Field with:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --scratch --with-playwright --port 9477 \
  --command 'macro write chart_primitive --file dev_docs/agents/demos/chart_primitive/build.yaml' \
  --command 'macro run chart_primitive'
```

The loose `*_code_.kt` files are what Field loads; `metadata.edn` carries portable inline copies of
the same text, and `./gradlew checkAgentDemos` fails if the two ever disagree.

The written-up version, with the falsification behind it and the rendered captures, is
[`dev_docs/chart_primitive.md`](../../../chart_primitive.md).
