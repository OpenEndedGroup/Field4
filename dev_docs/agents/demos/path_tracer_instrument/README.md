# The path tracer, with an instrument — a document you can open

Open `metadata.edn` in Field (**File ▸ Open**, or
`./field.sh dev_docs/agents/demos/path_tracer_instrument --read-only`).

`--read-only` matters: this folder is in the repository, and opening a document normally makes Field
write a backup, a screenshot, history metrics and an operation transcript into it.

**Run the renderer box, then box 1, then box 2.** Box 2 is one line — `display("output", tracer)` —
and until 2026-09-11 that line produced

```
PathTracingRenderer afield.four.fabricated.PathTracingRenderer@19266ca3
```

and nothing else. 376 bytes, roughly 330 of them the wrapper `<div>`. `PathTracingRenderer` is not a
data class and had no `registerValuePrinter` entry, so `ValueVisualizer` fell all the way to
`formatFallbackObject`. All 26 of the `afield.four` types the tracer is *built out of* — `Renderer`,
`Camera`, `ComputeModulePointer`, `RenderToTexture`, `VulkanBuffer` — have had printers in
`FourVisualizers.kt` since they were written.

| box | what it does | what to notice |
| --- | --- | --- |
| **path tracing renderer** | the window and the Vulkan renderer the rest inherit | launch this one first, as a `renderer` strand |
| **1 — the tracer, with a log** | the shipped materials scene at 640×360, plus `recordConvergence()` | prints `COMPUTE_BVH: 6626 triangles, 640 x 360` on an M1 Max. The log is the new line: the tracer appends to it from inside its own `render`, so it costs no frames |
| **2 — the instrument, in one line** | `display("output", tracer)` | nine rows. The one to read is **`up`** — see below. Re-run it any time; it reads the live tracer |
| **3 — the convergence curve** | `showConvergence(log)` | samples against wall seconds, with a labelled dashed rule at every reset. Run box 4, then re-run this, and the cliff appears with `integrator reloaded` in the legend |
| **4 — swap the integrator, live** | three `reloadIntegrator` calls | three *different* answers: `UNCHANGED`, `FAILED`, `RELOADED`. The third multiplies the direct and indirect terms where the integrator adds them, so the sunset drops to black and the three spheres become the only lit things in the frame. The camera does not move by a single bit. This is the line to edit — both terms are `vec3` and in scope, so anything you write over them is in the window a second later |
| **5 — put the integrator back** | `reloadIntegrator()` again | the same no-argument call that answered `UNCHANGED` in box 4 now `RELOADED`, because the live pipeline is the patched one |

## The row that is the point

```
up   declared (0, 1, 0) ≠ effective (-0.087, 0.978, -0.187) (11.94° apart)
```

The shipped demo's own camera declares an up of `(0, 1, 0)`. The tracer does not use it:
`updateFrame` builds its own up as `right × forward`, i.e. the component of the declared up that is
perpendicular to the view direction, normalised. On this camera that is **11.94° away** from what
was written down.

That row exists because of how this feature came to be written. A live session on 2026-09-10 left
five scratch boxes beside a 1920×1080 copy of the hidden-environment demo. Four were printf loops —
`frames { repeat(180) { frame() } }` with a `println` inside, two of them byte-identical
copy-pastes of the other two — and two of the four printed `camera.up` and then its three
components as `toRawBits()`, which is what you do when a number is not what you expect and you have
run out of ideas. They were watching a vector the tracer never reads, and no property tree could
have told them: the value is a local inside a private method. The row appears only when the declared
and effective vectors disagree, so on a camera where they agree it is not there to read past.

## Why the boxes are so far apart

800 px of vertical pitch, which looks excessive until you measure the panels. `screenshot-box`
reports a clip of **290 px** for box 2's `.h` card and **518 px** for box 3's plot, each including
2 × 20 px of padding. Every gap therefore clears the widest of them by at least **190 px**, in
either reading order.

Last night's demo in this folder put its boxes 116 px apart over a 267 px output panel, so running
the box its README told you to run first covered the two it told you to run next. Every gate was
green over it, because no gate measures a panel against the next box.

## What is not in here

`tracer.h2` — the drill-down. It is a paged explorer rather than a panel, so it does not screenshot
usefully; `public/docs/path_tracing.md` covers it, along with what the reflective `.h2` already did
before any of this (it rendered `PathTracingRenderer · 33 properties`, which is exactly the class's
public property count, so the adapter had to be a superset rather than a replacement).

The loose `*_code_.kt` files are what Field loads; `metadata.edn` carries portable inline copies of
the same text, generated from those files so the two cannot disagree.
