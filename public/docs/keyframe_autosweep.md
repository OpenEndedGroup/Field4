# Keyframe autosweeping: exploring a saved space while you sleep

*The declarations used below are listed, with their KDoc, in the codebase reference:
[`keyframeSweep.kt`](reference/graphics.md#keyframesweep-kt) and
[`keyframeSweepCapture.kt`](reference/graphics.md#keyframesweepcapture-kt). This page assumes
[Capturing & Replaying Live State](keyframer_examples.md), which is where a `KeyframeScope`, a `Key`
and `interpolate` come from.*

A `KeyframeScope` gives you one look at a time: capture it, edit it, put it back. A directory of
saved keys is a different kind of object — a *space*, whose interesting points are mostly the ones
nobody has visited. Twelve keys with ten lanes each is not twelve looks; it is a ten-dimensional
region with twelve labelled points in it, and an evening of clicking between those twelve is an
evening spent at the labels.

`KeyframeSweep` visits the rest of it overnight. Each pass draws a few keys from the pool, blends
them at random weights — sometimes past either end of the hull they span — applies the blend to the
live scope, makes a directory, and hands that directory to a `suspend` callback of yours, which runs
to completion before the next pass starts. What the callback writes there is its business: a still, a
short clip, a mesh, a CSV. What the sweep writes there is the *provenance* — the blended key as
ordinary key JSON, and a manifest saying which keys went in at which weight.

The point is what happens next. A night of this is a few thousand directories to categorise, cluster,
score or hand to a model, and everything in the design below is in service of making that second pass
possible.

## The whole of it

```diffstatic_runner {data-lang=kotlin screenshot="assets/keyframe_autosweep/contact-sheet.png" capture="box" open-outputs="true" execution="launch" wait="45" timeout="300" graphics-context=true name="keyframe-autosweep-sheet" padding="16" w="1100" h="1000"}
import afield.four.fabricated.StandardRenderer
import afield.four.fabricated.applyScenePlan
import afield.stdlib.KeyframeScope
import afield.stdlib.KeyframeSweep
import afield.stdlib.SweepConfig
import afield.stdlib.awaitFrames
import afield.stdlib.captureStill
import afield.stdlib.linalg.Matrix4f
import afield.stdlib.linalg.Vector3f
import afield.stdlib.readSweepArchive
import java.nio.file.Files
import java.util.Base64
import kotlin.io.path.readBytes
import kotlin.io.path.writeText

val r: Renderer = fromContext()
val dim = 420

:::fold the scene, and the ten lanes of it that a key can drive
// The half you would replace with your own sketch: a grid of spheres on a floor, and a
// `KeyframeScope` over ten places in it. Nothing below this block ever writes those ten places —
// only a blended key does, which is what makes the contact sheet evidence rather than decoration.
val bundle = StandardRenderer.build(
    r,
    StandardRenderer.Config(renderWidth = dim, renderHeight = dim, backgroundColor = Vector3f(0.02f, 0.02f, 0.03f))
)
val scene = bundle.scene

// A square target must be projected square. `Projection(renderer)`'s `auto` lambda re-resizes to the
// *window* inside every evaluateUniform, so a one-off `resize` is overwritten on the next frame and
// every sphere comes out an ellipse. Replace the lambda; do not call `resize` once.
scene.projection.auto = { scene.projection.resize(dim, dim) }
scene.projection.resize(dim, dim)

scene.camera.at.set(0f, 2.0f, -3.2f)
scene.camera.target.set(0f, 0f, 0f)
scene.lightDirection.set(Vector3f(-0.5f, -1f, -0.3f).normalize())
scene.lightIntensity = 3.4f

val grid = 3
val spheres = (0 until grid * grid).map {
    bundle.registerSphere(name = "ball-$it", radius = 0.3f, slices = 24, stacks = 24, renderer = r)
}
// A floor, and not for decoration: `shadow.strength` and `ao.strength` are two of the ten lanes, and
// with nothing under the spheres to receive a shadow they are two lanes a sweep could move all night
// without changing a pixel.
bundle.registerCube(name = "floor", size = 1f, renderer = r)
    .setModel(Matrix4f().translate(0f, -0.34f, 0f).scale(11f, 0.04f, 11f))
bundle.applyScenePlan()

var spread = 0.85f
var visible = grid * grid
// Hidden from the outside in. Hiding the tail of a row-major grid takes its last row away and shoves
// the rest off to one side, which would make `grid.visible` half a camera move as well as a count.
val centreOut = (0 until grid * grid).sortedBy {
    val ix = it / grid - (grid - 1) / 2
    val iz = it % grid - (grid - 1) / 2
    ix * ix + iz * iz
}
val layout = {
    centreOut.forEachIndexed { rank, i ->
        val ix = i / grid - (grid - 1) / 2
        val iz = i % grid - (grid - 1) / 2
        spheres[i].setModel(
            if (rank >= visible) Matrix4f().scale(0f)
            else Matrix4f().translate(ix * spread, 0f, iz * spread)
        )
    }
}
layout()

val palettes = mapOf(
    "warm" to (Vector3f(0.10f, 0.05f, 0.03f) to Vector3f(1.0f, 0.78f, 0.55f)),
    "cool" to (Vector3f(0.03f, 0.05f, 0.11f) to Vector3f(0.62f, 0.78f, 1.0f)),
    "acid" to (Vector3f(0.04f, 0.10f, 0.04f) to Vector3f(0.75f, 1.0f, 0.35f))
)
var palette = "warm"
val applyPalette = { name: String ->
    val chosen = palettes[name] ?: palettes.getValue("warm")
    scene.ambientColor.set(chosen.first)
    scene.lightColor.set(chosen.second)
    palette = name
}
applyPalette(palette)

val keys = KeyframeScope(schemaVersion = 1).apply {
    vector3Target("camera.at", { scene.camera.at }) { scene.camera.at.set(it) }
    vector3Target("camera.target", { scene.camera.target }) { scene.camera.target.set(it) }
    vector3Target("light.direction", { scene.lightDirection }) {
        scene.lightDirection.set(if (it.lengthSquared() > 1e-8f) Vector3f(it).normalize() else it)
    }
    floatTarget("light.intensity", { scene.lightIntensity }) { scene.lightIntensity = it }
    vector3Target("background", { scene.backgroundColor }) { scene.backgroundColor.set(it) }
    floatTarget("ao.strength", { scene.aoStrength }) { scene.aoStrength = it }
    floatTarget("shadow.strength", { scene.shadowStrength }) { scene.shadowStrength = it }
    floatTarget("grid.spread", { spread }) { spread = it; layout() }
    // An `Int` lane, because a subdivision level or an instance count is one, and it interpolates
    // as a float lane so it can travel smoothly between two looks.
    intTarget("grid.visible", { visible }, 1..(grid * grid)) { visible = it; layout() }
    // The discrete lane: a name that selects a whole sub-state. There is no midpoint between "warm"
    // and "acid", which is the case the sweep has to have a policy about.
    stringTarget("palette", { palette }) { applyPalette(it) }
}

bundle.present(renderer = r)
bundle.attachTo(r)
:::endfold

:::fold four keys, as a few evenings of clicking would have left them
// In your own document these are files you saved with `scope.capture().save(…)`. The fourth is
// deliberately *incomplete* — it predates two of the lanes, the way a key saved three weeks ago
// does — because that is the case the blend has to have an answer for.
val pool = Files.createTempDirectory("autosweep-pool")
val look = { name: String, values: Map<String, Any?> ->
    pool.resolve(name).writeText(keys.encodeKeyJson(keys.Key(values.toMutableMap())))
}
look("high-and-cool", mapOf(
    "camera.at" to Vector3f(0f, 3.0f, -3.0f), "camera.target" to Vector3f(0f, 0f, 0f),
    "light.direction" to Vector3f(-0.5f, -1f, -0.3f).normalize(), "light.intensity" to 3.4f,
    "background" to Vector3f(0.012f, 0.020f, 0.050f), "ao.strength" to 0.85f,
    "shadow.strength" to 1.0f, "grid.spread" to 0.85f, "grid.visible" to 9, "palette" to "cool"))
look("low-and-warm", mapOf(
    "camera.at" to Vector3f(1.8f, 1.2f, -2.6f), "camera.target" to Vector3f(0f, 0.1f, 0f),
    "light.direction" to Vector3f(0.7f, -0.4f, -0.5f).normalize(), "light.intensity" to 6.2f,
    "background" to Vector3f(0.050f, 0.025f, 0.012f), "ao.strength" to 0.35f,
    "shadow.strength" to 0.6f, "grid.spread" to 1.45f, "grid.visible" to 4, "palette" to "warm"))
look("flat-and-acid", mapOf(
    "camera.at" to Vector3f(-2.2f, 1.8f, -2.4f), "camera.target" to Vector3f(0.2f, 0.2f, 0f),
    "light.direction" to Vector3f(-0.1f, -0.95f, 0.1f).normalize(), "light.intensity" to 1.9f,
    "background" to Vector3f(0.010f, 0.045f, 0.015f), "ao.strength" to 1.0f,
    "shadow.strength" to 0.15f, "grid.spread" to 0.55f, "grid.visible" to 9, "palette" to "acid"))
look("older-partial-key", mapOf(
    "camera.at" to Vector3f(0f, 1.6f, -3.6f), "camera.target" to Vector3f(0f, 0f, 0f),
    "light.direction" to Vector3f(-0.8f, -0.6f, 0.1f).normalize(), "light.intensity" to 4.6f,
    "background" to Vector3f(0.020f, 0.020f, 0.020f),
    "shadow.strength" to 0.8f, "grid.spread" to 1.05f, "grid.visible" to 6))
:::endfold

// ── and here is the sweep. Everything above is the sketch; this is the whole feature. ──
val archive = Files.createTempDirectory("autosweep-archive")
val sweep = KeyframeSweep(
    keys, pool, archive,
    SweepConfig(
        seed = 20260916,
        count = 8,
        minArity = 2,
        maxArity = 3,
        // Below 1, a blend is mostly one key with the others as perturbation. Well above it, every
        // blend is the average of its participants — a night of mush.
        concentration = 0.7,
        extrapolationProbability = 0.3
    )
)

// `frames { }` puts the run on the Runloop, so the callback can wait for real frames and the stop
// key cancels it between renders. (`frames` rather than `start`: `start` has a receiver overload and
// a receiverless one, and a bare lambda satisfies both, so `start { … }` does not compile anywhere —
// every existing call site passes a callable reference instead, which is why nobody has hit it.)
frames {
    val report = sweep.run { render ->
        // The blend is already applied when this is called. Wait for it to be drawn, then read the
        // target back into the render's own directory.
        r.awaitFrames(6)
        render.captureStill(r, bundle.postStack.output(), settleFrames = 0)
    }
    println("$report")

:::fold the morning: read the archive back and show what went into each render
    // Nothing here touches the renderer or the scope. An archive describes itself, which is the
    // whole point of the manifests — this is the code an offline categorising pass starts from.
    val rows = readSweepArchive(archive)
    val sheet = rows.joinToString("") { m ->
        val still = archive.resolve("renders/${m.id}/still.png")
        val img = "<img src='data:image/png;base64,${Base64.getEncoder().encodeToString(still.readBytes())}' " +
            "width='120' style='border:1px solid #333;display:block'/>"
        "<div style='display:inline-block;margin:2px;font:10px Iosevka,monospace;color:#999'>" +
            "$img${m.index}${m.extrapolationGain?.let { " ✦" } ?: ""} ${m.anchor.take(12)}</div>"
    }
    display("sheet", "<div style='width:520px'>$sheet</div>")

    markdown("what went in", """
        | # | keys blended, and at what weight | pushed past the hull | `palette` taken from |
        |---|----------------------------------|----------------------|----------------------|
        ${rows.joinToString("\n") { m ->
            "| ${m.index} | ${m.parents.joinToString("<br>") { "${it.key} ${"%.2f".format(it.weight)}" }} " +
                "| ${m.extrapolationGain?.let { g -> "x${"%.2f".format(g)} away from<br>${m.extrapolationPole}" } ?: "—"} " +
                "| ${m.discreteLanes.values.distinct().joinToString(", ")} |"
        }}
    """, width = 520)
:::endfold
}
```

![A contact sheet of eight GPU renders of a grid of spheres on a floor, each a different blend of camera, light, palette and layout, four of them marked as extrapolated, above a table giving each render's parent keys and weights, which of them it was pushed away from, and which parent supplied its palette](assets/keyframe_autosweep/contact-sheet.png)

Read the table against the sheet. Render 0 is `low-and-warm` at 1.10, `high-and-cool` at 0.17 and
`older-partial-key` at **−0.27**: the weights still sum to 1, and one of them is negative, which is
what "outside the hull" means. Nobody authored that look, and nothing in the pool is a fifth of the
way past `older-partial-key` in the direction of the other two.

## What a sweep leaves on disk

```diffstatic {data-lang=text}
archive/
  sweep.json            the run's own configuration, pool census and outcome
  index.jsonl           one compact manifest per render — the file an analysis pass reads
  renders/000123-4f2a9c/
      key.json          the blend, as ordinary key JSON
      manifest.json     parents and weights, the anchor, the pole, back-fills, artifacts, timings
      still.png         whatever your callback wrote
      clip.mp4
      frames/
```

Three properties of that layout are what a second pass depends on.

**`key.json` is an ordinary key**, written by the same serializer `saveKey` uses, so
`scope.loadKey(…)` reads it and `KeyframeCollection` browses it. Which means **`renders/` is itself a
valid pool**: point tonight's `pool` at last night's `renders` with `pattern = "*/key.json"`, and the
space explores its own interesting regions rather than the original twelve labels. It is written flat
— no `.versions` history — through
[`encodeKeyJson`](reference/graphics.md#keyframer-kt), because `saveKey` is *always* additive and ten
thousand write-once renders do not want ten thousand one-entry histories.

**`manifest.json` is the render's whole provenance**, and it is written *before* the callback runs and
rewritten after. A manifest still reading `"status": "running"` is a render whose process died inside
it, which is the distinction a resume needs.

**`index.jsonl` is a projection, not the record.** It is appended as renders finish, so a process
killed between a manifest write and its append leaves it one line short.
[`readSweepArchive`](reference/graphics.md#keyframesweep-kt) falls back to walking the manifests, and
[`KeyframeSweep.reindex`](reference/graphics.md#keyframesweep-kt) rebuilds it; the manifests are
authoritative.

## The blend is a fold, not new arithmetic

`KeyframeScope.interpolate` already knows how every lane type blends — numbers and vectors lerp,
quaternions slerp, `CameraPose` sclerps, code blends or steps. An N-way blend here is therefore a
*pairwise fold* over it rather than a second type table that would immediately drift from the first.
With weights sorted descending and running sums `S`, folding `t = w / S` at each step gives exactly
the affine weighted mean for every linear lane.

Sorting descending is what makes that safe rather than merely tidy. The weights sum to 1, so the
running sums start at the largest weight and end at 1 — rising while the terms are positive, falling
once they go negative — and the minimum is `min(w_max, 1)`, which is always positive. No divisor ever
approaches zero, including under extrapolation, where some weights *are* negative.

??? aside "Picking the three lane policies in `SweepConfig`"

    Three kinds of lane need a policy the fold cannot supply. Each is a field on
    [`SweepConfig`](reference/graphics.md#keyframesweep-kt), and each is recorded per render.

    **Lanes with no midpoint** — a string, or a code block. `interpolate` *steps* them to the
    right-hand value once `t >= 1`, and inside a fold `t = w / S` can exceed 1 whenever the weights
    have mixed signs, which is what extrapolation produces. `DiscreteBlend.ANCHOR` gives every
    discrete lane to one parent — the **anchor**, drawn by roulette over the final weights, so the
    key dominating the picture usually supplies the code — and the manifest names it.
    `ROULETTE_PER_LANE` draws each lane independently, which recombines code across keys and breaks
    any pair of lanes that had to agree. `AS_INTERPOLATED` leaves the fold's answer: pick it when the
    scope has a `codeBlend` configured and arity is 2, because then the fold really is a code blend.

    **Participants that disagree about which lanes exist**, which a pool accumulating over weeks
    guarantees. `interpolate` drops any name the two sides do not share, so a naive fold shrinks to
    the intersection of everything it touched and the oldest key in the pool decides how much of the
    look gets explored. `MissingSlots.FILL_FROM_LIVE` back-fills from `scope.capture()` and lists
    every fill in the manifest; `FILL_FROM_ANCHOR`, `INTERSECT` and `REQUIRE_ALL` are the
    alternatives, and `REQUIRE_ALL` reports how many keys it excluded.

    **Numeric type.** `interpolate` returns `Double` unless *both* sides were `Float`, so an `Int`
    lane comes back as a `Double` an `Int` uniform will refuse. `preserveNumericKind` rounds each
    lane back to the anchor's type — rounded, but *not* clamped, because an extrapolated blend can
    put an `Int` lane outside the range its `intTarget` declares and the clamp belongs in the target,
    on apply. The key then records the point actually visited and still reproduces the render.

## Extrapolation is measured in hull-crossings

An extrapolated look is the one nobody would have authored: past the end of every key you saved rather
than between them. With probability `extrapolationProbability`, one participant is drawn as the
**pole** — the key the blend is pushed away from — and the other weights are scaled so that the pole's
own weight becomes exactly `1 - gain`.

That makes `gain` read directly. At `1.0` the pole's weight is zero and the blend sits exactly on the
far face of the hull; at `1.5` it is half as far again beyond that face; anything above 1 is genuinely
outside. `minExtrapolationGain` is therefore floored at `1.0`, because a "gain" that sometimes
extrapolated and sometimes did not would make the archive's `extrapolationGain` field mean nothing.

Two details in that are easy to get wrong.

The pole is a *different draw* from the anchor. One key doing both jobs produces blends wearing the
palette of the look they have just fled; keeping them separate is visible in the table above as a
render whose `palette` comes from the key with the largest weight while the pole is the one with the
negative one.

And a blend already dominated by one key is left convex. The nearer the pole's weight is to 1, the
larger the push needed to leave the hull at all, and past about 0.9 that push is a blast rather than
an exploration. Such a render keeps convex weights, and the null `extrapolationGain` in its manifest
says so.

??? aside "Why the weights are Dirichlet and not just normalised uniforms"

    Drawing `n` weights by normalising `n` uniforms — the obvious thing — concentrates hard on the
    centre of the simplex as `n` grows. Every blend of five keys is then nearly their average, and a
    night of it is a night of mush with no way to turn the dial. A symmetric Dirichlet is the
    distribution that is genuinely uniform over the simplex at `α = 1`, and whose corner bias is a
    parameter rather than an accident.

    `concentration` is that `α`. `KeyframeSweepTest` pins what it buys, over five participants, by
    measuring the mean largest weight: **0.81 at `α = 0.1`** (a blend is mostly one key), **0.46 at
    `α = 1`** — which has a closed form, `(1/n)·Σᵢ 1/i` — and **0.23 at `α = 50`** (every blend is
    the average). Start at 0.6 if the point is variety you can still attribute to something.

## What a night survives

Nothing in the sweeper trusts the run to finish, because the failure modes of an unattended eight-hour
job are not the failure modes of a demo.

- **A resume does not change the draw.** Each render's randomness comes from `seed` and its *index*,
  nothing accumulating, so skipping four thousand finished renders leaves the unfinished ones drawing
  exactly what they would have drawn. A directory left `"running"` by a dead process is redone rather
  than skipped: its callback got some way through writing files nobody can vouch for.
- **A throwing callback costs one render.** It is recorded with its first error line, its directory is
  kept for forensics, and the run continues — until `maxConsecutiveFailures` in a row says the night
  is broken rather than the render unlucky, which is what stops a broken sweep writing ten thousand
  empty directories.
- **The disk floor stops the sweep before the disk does.** `minFreeBytes` is checked per render
  against the archive's own filesystem and defaults to 8 GB, generously, because an overnight sweep of
  GPU stills is a *rate* of consumption: what goes wrong is never the archive being large, it is the
  machine having nothing left at 4am. `sweep.json` then says `"stoppedBecause": "disk"`, which the
  render count alone would not.
- **Cancellation is clean.** The stop key marks the in-flight render `cancelled`, restores the scope
  to what it was before the run, and rethrows.
- **`maxDurationMs` is the "stop at 7am" knob**, and the callback itself is never interrupted: it runs
  to completion, one render at a time. There is a `perRenderTimeout`, off by default; switching it on
  accepts a partial render as the price of a bounded one.

## Stills and clips

The sweeper is renderer-free; it blends, records, and calls your callback. But most callbacks do the
same two things, and those two have traps in them, so they live in
[`keyframeSweepCapture.kt`](reference/graphics.md#keyframesweepcapture-kt) once rather than in every
sweep.

```diffstatic {data-lang=kotlin}
sweep.run { render ->
    r.awaitFrames(6)
    render.captureStill(r, bundle.postStack.output(), settleFrames = 0)
    render.captureClip(r, bundle.postStack.output(), frames = 24, fps = 12, settleFrames = 0) { _, t ->
        // Where the callback moves whatever should move — a camera, a phase, a simulation step.
        // Called before the readback, so frame i shows the state this established.
        orbitAround(render.key["camera.at"] as Vector3f, (t - 0.5) * 0.5)
        r.awaitFrames(2)
    }
}
```

`awaitFrames` is the unit to wait in. `delay(16)` waits on a wall clock that has nothing to do with
whether the GPU drew anything: a uniform written this instant is visible only once the frame that read
it has been submitted, so "wait two frames" is the correct instruction and "sleep 32 ms" is a guess
that goes wrong exactly when the machine is busy — which, on the night of a long sweep, is always.

Neither helper will invent a file. A readback that comes back empty and a missing `ffmpeg` are both
recorded as an artifact with a null path and a stated reason, and the frame PNGs are left on disk, so
`muxSweepArchiveClips` can mux the whole archive the next morning without re-rendering the night. An
archive that says what it is missing can still be analysed; one with a plausible-looking wrong file in
it cannot.

!!! warning "One renderer and one target for the whole run"

    Both helpers assume the pattern the RectSL and StackSL gallery harnesses established: build the
    `RenderToTexture` once, outside the sweep, and change only the uniforms. Building a target per
    render is what makes an overnight sweep run out of device memory at 3am.

## Reading a night back

The archive is designed to be read by something that was not there when it was written — which in
practice means a second process, often not Kotlin. `index.jsonl` is one JSON object per line, so the
Python end of an offline categorising pass is three lines:

```diffstatic {data-lang=python}
import json, pathlib
rows = [json.loads(l) for l in (pathlib.Path(archive) / "index.jsonl").read_text().splitlines() if l.strip()]
still = lambda row: pathlib.Path(archive) / "renders" / row["id"] / "still.png"
```

Every field a categoriser needs to *attribute* a result is in that row: `parents` with weights,
`anchor`, `extrapolationPole` and `extrapolationGain`, `discreteLanes`, the `filled` lanes per parent,
`durationMs`, and `artifacts` with a size per file. Score the stills however you like, join the scores
back to the weights, and the question "which region of this space is worth another night" has an
answer.

From inside Field, `sweepArchiveSummary(archive)` is the one-line version for the morning: how many
renders, how they ended, how many left the hull, the widest single weight, the median render time, how
much of the pool got used, and any lane that was dropped or back-filled.

## Where it is exercised

`keyframeSweep.kt` is a CPU layer and its tests are too: `afield.stdlib.KeyframeSweepTest` recomputes
`Σ wᵢ·vᵢ` from each manifest and requires it to reproduce the `key.json` beside it, which is the whole
provenance contract, then pins the reproducibility, the resume, the three lane policies, the failure
isolation, the disk floor and the sampling distribution.

Every one of those assertions would still hold if applying a blend reached no uniform at all. So the
claim that the *picture* is a function of the blend is checked on a GPU instead, by
`tools/run_field_tests.py --id keyframe.sweep.smoke`: it runs a real six-render sweep and requires
pixel distance to correlate with key distance across every pair of renders, at better than 0.6. What
it does *not* require is that consecutive renders differ, since two independent draws can land next
to each other in the space legitimately. The same run also re-applies a saved key and re-renders it;
on the runs observed so far that has reproduced the sweep's own still exactly, which is more than the
test asks for, given that the renderer carries a frame of temporal history.

The loadable version of this page is the `keyframe_autosweep` transcript — four boxes, a renderer, the
rig, the sweep and the morning box — listed with the rest in
[Agent transcripts](agent_transcripts.md#keyframe_autosweep).
