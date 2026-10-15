# Field Document: frames_lifecycle

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 4 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 3 · re-run replaces, it does not stack

<!-- field-box id="frames_lifecycle_rerun" index="0" -->

### Narrative

From `code`

Run twice without stopping: the counter restarts, the colour changes, and one loop
remains. The old run cancels at its next frame(), after its suspended body resumes.
Compare frameNumber with elapsed time: the counter measures work, not seconds.
A time-derived hue makes the new launch visible even with a fresh script context.

```edn [field-box id="frames_lifecycle_rerun" role="properties"]
{"id" "frames_lifecycle_rerun" "index" 0 "name" "3 · re-run replaces, it does not stack" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 · re-run replaces, it does not stack" "_tombstone" false "frame" #afield/rect [60.0 620.0 560.0 150.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "frames_lifecycle_rerun_code_.kt" "lines" 34 "characters" 1209 "sha256" "a324c8f045748d66157d4950a71b162a55c5721df2c11d3d24aceb9be52a2e2f"}]}
```

```kotlin [field-property box="frames_lifecycle_rerun" name="code" path="frames_lifecycle_rerun_code_.kt"]
/*
Run twice without stopping: the counter restarts, the colour changes, and one loop
remains. The old run cancels at its next frame(), after its suspended body resumes.
Compare frameNumber with elapsed time: the counter measures work, not seconds.
A time-derived hue makes the new launch visible even with a fresh script context.
*/
val hue = ((System.nanoTime() / 1_000_000L) % 360L).toInt()
val swatch = "hsl($hue 70% 60%)"
val started = AnimationClock.nowSeconds()
var nextDraw = 0.0

// Keep this launch's swatch beside its frame count and elapsed-time readout.
val show: (Int, Double) -> Unit = { n, elapsed ->
    val rate = if (elapsed > 0.5) "%,.0f frames a second".format(n / elapsed) else "measuring"
    showRow(
        "main",
        "<span style='display:inline-block;width:58px;height:58px;border-radius:6px;background:$swatch'></span>",
        """
        ### frame ${"%,d".format(n)}

        {muted:$rate · ${"%.1f".format(elapsed)} s elapsed}

        {muted:this launch's colour is $swatch}
        """,
    )
}

animate {
    val elapsed = AnimationClock.nowSeconds() - started
    if (elapsed >= nextDraw) {
        show(frameNumber, elapsed)
        nextDraw = elapsed + 0.1
    }
}
```

## 2. 1 · runs until you stop it

<!-- field-box id="frames_lifecycle_forever" index="1" -->

### Narrative

From `code`

An animate { } with no end condition. It runs until you press stop, or until you
re-run this box. The running indicator on the box stays lit the whole time.

```edn [field-box id="frames_lifecycle_forever" role="properties"]
{"id" "frames_lifecycle_forever" "index" 1 "name" "1 · runs until you stop it" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 · runs until you stop it" "_tombstone" false "frame" #afield/rect [60.0 60.0 560.0 150.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "frames_lifecycle_forever_code_.kt" "lines" 58 "characters" 1724 "sha256" "0a5e718f1080349852796506a5ae5d658eececf7cce8cb77ff7d224e59b9ff3e"}]}
```

```kotlin [field-property box="frames_lifecycle_forever" name="code" path="frames_lifecycle_forever_code_.kt"]
// An animate { } with no end condition. It runs until you press stop, or until you
// re-run this box. The running indicator on the box stays lit the whole time.
val started = AnimationClock.nowSeconds()
// Each redraw sends the whole trail as SVG. Keep its duration and sample count bounded.
// At these settings it carries about 130 points.
val trailSeconds = 8.0
val trailStep = 0.06
var nextDraw = 0.0

fun disc(cx: Double, cy: Double, r: Double, rgba: Vec4) = fline {
    circle(cx, cy, r)
    color = rgba
    filled = true
    stroked = false
}

fun border(rgba: Vec4) = fline {
    rect(8.0, 8.0, 544.0, 304.0)
    color = rgba
    thickness = 1.2
    stroked = true
    filled = false
}

fun atX(s: Double) = 280.0 + 230.0 * kotlin.math.cos(s * 1.1)
fun atY(s: Double) = 160.0 + 120.0 * kotlin.math.sin(s * 1.7)

val draw: (Double) -> Unit = { t ->
    val trail = fline {
        var s = kotlin.math.max(0.0, t - trailSeconds)
        breakNext() // The first sample starts the trail; later samples extend it.
        while (s <= t) {
            lineTo(atX(s), atY(s))
            s += trailStep
        }
        color = vec(0.30, 0.52, 0.68, 0.55)
        thickness = 1.2
    }
    lines(
        border(vec(0.45, 0.52, 0.62, 0.6)),
        trail,
        disc(atX(t), atY(t), 13.0, vec(0.48, 0.85, 1.0, 1.0)),
        channel = "main",
        w = 560.0,
        h = 320.0,
        fitToSize = true,
    )
}

animate {
    val t = AnimationClock.nowSeconds() - started
    // Gate the redraw on the clock. The loop still advances every Runloop frame —
    // frameNumber proves it — but nobody can see 3000 pictures a second.
    if (t >= nextDraw) {
        draw(t)
        nextDraw = t + 1.0 / 30.0
    }
}
```

## 3. 4 · cleanup — stop it, wait it out, then re-run it

<!-- field-box id="frames_lifecycle_cleanup" index="2" -->

### Narrative

From `code`

Launch this box yourself and try three exits:
1. Stop before eight seconds: .end runs, then finally, on a later Runloop tick.
2. Let eight seconds elapse: the body returns and only finally runs.
3. Re-run while it is counting: the old run prints .end, then finally.
The .end block handles cancellation; finally also handles a normal return.

```edn [field-box id="frames_lifecycle_cleanup" role="properties"]
{"id" "frames_lifecycle_cleanup" "index" 2 "name" "4 · cleanup — stop it, wait it out, then re-run it" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "4 · cleanup — stop it, wait it out, then re-run it" "_tombstone" false "frame" #afield/rect [700.0 620.0 620.0 150.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "frames_lifecycle_cleanup_code_.kt" "lines" 40 "characters" 1481 "sha256" "e6cc078c3dd42333986f192e14f02917d257a665a0fbdfbbffcd4785ab21c124"}]}
```

```kotlin [field-property box="frames_lifecycle_cleanup" name="code" path="frames_lifecycle_cleanup_code_.kt"]
/*
Launch this box yourself and try three exits:
1. Stop before eight seconds: .end runs, then finally, on a later Runloop tick.
2. Let eight seconds elapse: the body returns and only finally runs.
3. Re-run while it is counting: the old run prints .end, then finally.
The .end block handles cancellation; finally also handles a normal return.
*/
val started = AnimationClock.nowSeconds()
val runFor = 8.0
val notes = mutableListOf<String>()
var nextDraw = 0.0

val show: (String) -> Unit = { headline -> markdown("main", headline.md) }

// Keep cleanup in its own channel so a new launch's readout leaves it visible.
val note: (String) -> Unit = { line ->
    notes += line
    markdown("cleanup", notes.joinToString("\n\n") { "{ok:${it.md}}" })
}

frames {
    try {
        var elapsed = AnimationClock.nowSeconds() - started
        while (elapsed < runFor) {
            if (elapsed >= nextDraw) {
                show("running - %.1f s of %.0f, frame %,d".format(elapsed, runFor, frameNumber))
                nextDraw = elapsed + 0.1
            }
            frame()
            elapsed = AnimationClock.nowSeconds() - started
        }
        show("the body returned normally at frame %,d".format(frameNumber))
        note("returned, not cancelled - so .end { } will not run")
    } finally {
        note("finally inside the body - runs whatever happens")
    }
}
    .end {
        note(".end { } - on a cancellation (stop, re-run or end()), and it runs first")
    }
```

## 4. 2 · stops itself after six seconds

<!-- field-box id="frames_lifecycle_stops" index="3" -->

### Narrative

From `code`

After six seconds, end() cancels this animation. Its .end block paints the red
final frame before the loop stops.

```edn [field-box id="frames_lifecycle_stops" role="properties"]
{"id" "frames_lifecycle_stops" "index" 3 "name" "2 · stops itself after six seconds" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 · stops itself after six seconds" "_tombstone" false "frame" #afield/rect [700.0 60.0 620.0 150.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "frames_lifecycle_stops_code_.kt" "lines" 76 "characters" 2267 "sha256" "c681f8682e866f7f7602c2b103070cd22581e0aa9a550f1e0ad28f00ab9e4ea0"}]}
```

```kotlin [field-property box="frames_lifecycle_stops" name="code" path="frames_lifecycle_stops_code_.kt"]
// After six seconds, end() cancels this animation. Its .end block paints the red
// final frame before the loop stops.
val started = AnimationClock.nowSeconds()
val stopAfter = 6.0
var nextDraw = 0.0

fun disc(cx: Double, cy: Double, r: Double, rgba: Vec4) = fline {
    circle(cx, cy, r)
    color = rgba
    filled = true
    stroked = false
}

val draw: (Double, Boolean) -> Unit = { elapsed, stopped ->
    val accent = if (stopped) vec(1.0, 0.28, 0.30, 1.0) else vec(0.48, 0.85, 1.0, 1.0)
    val quiet = if (stopped) vec(1.0, 0.28, 0.30, 0.55) else vec(0.55, 0.62, 0.72, 0.7)
    val border = fline {
        rect(6.0, 6.0, 588.0, 238.0)
        color = quiet
        thickness = 1.4
        stroked = true
        filled = false
    }
    val track = fline {
        moveTo(60.0, 90.0)
        lineTo(540.0, 90.0)
        color = quiet
        thickness = 1.4
    }
    val gutter = fline {
        rect(60.0, 165.0, 480.0, 40.0)
        color = quiet
        thickness = 1.0
        stroked = true
        filled = false
    }
    // How far through the six seconds we are. It fills, and then it stops filling.
    val progress = fline {
        val w = 480.0 * (elapsed / stopAfter).coerceIn(0.0, 1.0)
        rect(60.0, 165.0, w, 40.0)
        color = accent
        filled = true
        stroked = false
    }
    // Position from the clock, not from frameNumber — so it moves at the same speed
    // whatever rate this machine's Runloop happens to be ticking at.
    val x = 60.0 + 480.0 * (0.5 - 0.5 * kotlin.math.cos(elapsed * 0.55))
    lines(
        border,
        track,
        gutter,
        progress,
        disc(x, 90.0, 14.0, accent),
        channel = "main",
        w = 600.0,
        h = 250.0,
        fitToSize = true,
    )
}

animate {
    val elapsed = AnimationClock.nowSeconds() - started
    if (elapsed >= nextDraw) {
        draw(elapsed, false)
        nextDraw = elapsed + 1.0 / 30.0
    }
    if (elapsed >= stopAfter) end()
}
    .end {
        // .end { } runs on cancellation — which end() has just caused. Two frames of it, so
        // the red state is definitely painted before the job goes away.
        repeat(2) {
            draw(AnimationClock.nowSeconds() - started, true)
            frame()
        }
    }
```

