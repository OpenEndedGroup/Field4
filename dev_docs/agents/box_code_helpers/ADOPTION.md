# Corpus adoption of the approved helpers

The corpus edit pass that [README.md](README.md) and [OLDER_APPROVALS.md](OLDER_APPROVALS.md)
deferred. Each approved facility is listed with the verbose form it replaces, how often that form
appeared in teaching code before and after this pass, and the `tools/check_box_idioms.py` rule that
now keeps it out.

Counts are findings of the checker over its corpus (tracked transcripts, demos, templates and
`public/docs`, excluding the generated `public/docs/assets/agent_transcripts`, the KDoc-generated
`public/docs/reference` and recorded listings), taken on 2026-10-31. `keyframer_examples.md` is
excluded from the edit (another branch is revising it by hand) but its findings are counted.
`src/jvmMain/kotlin/afield/examples` is outside the checker's corpus: it is JVM code compiled with
its own imports, not box code, and none of the old forms below appeared there as teaching code.

| Helper | Old form | Before | After | Rule | Auto-fix? |
| --- | --- | --- | --- | --- | --- |
| `scope.Key("a" to x)` | `scope.Key(mutableMapOf<String, Any?>("a" to x))` | 24 | 0 | `key-pairs` | yes |
| `x.f0`..`x.f9` | `"%.3f".format(x)` | 547 lines / 132 files (plus `functions_examples.md`) | 0 | `fixed-format` | yes |
| `Interpolant.startAt` | `(q as NeedsStartingValue<T>).startAt(v)` | 1 | 0 | `starting-value-cast` | yes |
| `uint` push members take `Int` | `m.count = { n.toUInt() }` | 8 | 0 | `uint-push-lambda` | yes in a module/mesh box; else a note |
| `computeBarrier(graphics = ...)` | `computeTask { _, cmd, _ -> computeMemoryBarrier(cmd); graphicsMemoryBarrier(cmd) }` | 4 | 0 | `compute-barrier` | yes |
| `WallSpec.worldToClip()` | `m.push["worldToClip"] = Matrix4f().ortho(-ww / 2f, ww / 2f, ...)` | 4 | 0 | `wall-projection` | no (failure) |
| `fullscreenTriangle(module)` | `val t = Mesh(r, m); t.resize(3, 0, m)` | 12 | 0 | `fullscreen-triangle` | no (note) |
| `bestTimeMs` | a local `bestMs` warm-up/`nanoTime` closure | 0 | 0 | none | adopted earlier |
| `markdownAppend` | `notes += line; markdown(ch, notes.joinToString(...))` | 0 | 0 | none | adopted earlier |
| `showInteractionStages` / `stage` | hand-laid `Rect`s, arrows, `fitInto`, `showInteractionOverview` | 0 | 0 | none | adopted earlier |
| `compare`, `integrate`, `randomPoints` | `println("%.5f ... %.5f")`, area-weighted loop, seeded `List(n) { vec(rand...) }` | 0 | 0 | none | adopted earlier |
| `FaustPatternRender.wav()` | `WavFloatArrayWriter.encodeStereo16(r.left, r.right, r.sampleRate, p)` | 0 | 0 | none | see below |
| `runAsync` / `trackAnalysis` | `submit(...).thenCompose { watch ...; awaitAnalysis ... }` | 0 | 0 | none | adopted earlier |
| `osc(port).on(addr, runOwned = true)` | manual `remove` / reset of a persistent handler | 0 | 0 | none | opt-in, not a rewrite |
| `syphonClient(name)` cache | a client opened per run | 0 | 0 | none | behaviour of the existing call |
| `solid.sampleSlices` | a midpoint loop that slices and closes each section | 0 | 0 | none | adopted earlier |
| `FLine.grid(bounds, nx, ny)` | nested `moveTo`/`lineTo` loops over cell lines | 0 | 0 | none | adopted earlier |
| `bindPush(m1, m2) { }` | `for (m in [a, b]) { m.x = { ... } }` | 0 | 0 | none | adopted earlier |

"Adopted earlier" means the proposal sites named in
[helper-proposals.json](../box_code_first_pass/helper-proposals.json) already call the helper and a
search for the old form finds no other teaching copy, so there is no regex worth gating on.

## What is left, and why

Nothing that fails: `check_box_idioms.py` over the whole corpus is clean, apart from notes that predate
this pass. `keyframer_examples.md` was converted on its own branch. Captures of the docs pages and
asset documents whose fences changed are left for the strict full-site build, which recaptures any
fence whose digest changed. Before that, each changed docs fence, asset sidecar and `docs_capture/`
block was compiled through the `checkBoxCodeCompiles` path, before and after the edit. Errors that
appeared only after the edit came from `Double?` and `Any?` receivers and are fixed (`?.fN`,
`(it as Number).fN`).

`FaustPatternRender.wav()` was not adopted: the four remaining `encodeStereo16` calls encode Pat or
Dirt renders, not a `FaustPatternRender`. There is no rule for it, because a line check cannot see
the receiver's type.

## Gate scope

All rules run in `./gradlew checkBoxIdioms` with the checker's default scope, which a landing
narrows to the files it changed. Unlike `list-literal`, none is limited to hand-written docs pages:
they apply to transcripts, demos, templates and asset `.kt` sidecars too. Two rules are notes
(`fullscreen-triangle`, and `uint-push-lambda` outside a module/mesh box) because the old form is
sometimes right. Listings between `<!-- X-source:N -->` markers are skipped, as for `list-literal`:
they quote recorded source, and regenerating that source fixes them.

A `fixed-format` rewrite is a claim about types the checker cannot see. `"%.2f".format(x)` takes
any `Any?`, and `x.f2` needs a `Number`. Two transcripts formatted a `Double?` and a map value; they
are now `render.durationSeconds?.f2` and `(it["volume"] as Number).f1`, which print what the old
code printed. Run `./gradlew checkBoxCodeCompiles` after `--fix`, as the checker says.
