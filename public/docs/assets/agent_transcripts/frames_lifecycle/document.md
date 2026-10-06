# Field Document: frames_lifecycle

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 4 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_session" "fileManagerAccessHistory"]}
```

## 1. 3 · re-run replaces, it does not stack

<!-- field-box id="frames_lifecycle_rerun" index="0" -->

### Narrative

From `code`

Run this box. Then run it AGAIN, without stopping it first.

The counter restarts and the colour changes, and there is still exactly one loop.
Running a box bumps its run generation; the Frames from the previous run closes over
the OLD generation, notices the mismatch at the top of its next frame(), and cancels.

It gets one more body call before that happens, because shouldend is read at the top of
frame() and the suspended body is already halfway through a call that will return true.

Read the rate line too. That is the whole reason AnimationClock exists: frameNumber
counts work, and the amount of work a second holds is not a constant.

nanoTime, not Random(): a fresh script context can hand out the same first random value
twice, which would make this demo silently claim nothing.

```edn [field-box id="frames_lifecycle_rerun" role="properties"]
{"id" "frames_lifecycle_rerun" "index" 0 "name" "3 · re-run replaces, it does not stack" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 · re-run replaces, it does not stack" "_tombstone" false "frame" #afield/rect [60.0 620.0 560.0 150.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "frames_lifecycle_rerun_code_.kt" "lines" 47 "characters" 1724 "sha256" "21e8fce8d5a93561009f6bf185ed2b0a907a344327165614455c98a3e9436ad4"}]}
```

```kotlin [field-property box="frames_lifecycle_rerun" name="code" path="frames_lifecycle_rerun_code_.kt"]
/*
Run this box. Then run it AGAIN, without stopping it first.

The counter restarts and the colour changes, and there is still exactly one loop.
Running a box bumps its run generation; the Frames from the previous run closes over
the OLD generation, notices the mismatch at the top of its next frame(), and cancels.

It gets one more body call before that happens, because shouldend is read at the top of
frame() and the suspended body is already halfway through a call that will return true.

Read the rate line too. That is the whole reason AnimationClock exists: frameNumber
counts work, and the amount of work a second holds is not a constant.

nanoTime, not Random(): a fresh script context can hand out the same first random value
twice, which would make this demo silently claim nothing.
*/
val hue = ((System.nanoTime() / 1_000_000L) % 360L).toInt()
val swatch = "hsl($hue 70% 60%)"
val started = AnimationClock.nowSeconds()
var nextDraw = 0.0

/*
showRow puts the swatch beside the readout. The swatch is raw HTML because its colour
is this launch's data, not styling.
*/
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
{"id" "frames_lifecycle_forever" "index" 1 "name" "1 · runs until you stop it" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 · runs until you stop it" "_tombstone" false "frame" #afield/rect [60.0 60.0 560.0 150.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "frames_lifecycle_forever_code_.kt" "lines" 74 "characters" 2162 "sha256" "9b760ea61b09b548792c4492c1dd4d0446de0e0661b547f92d4cbe9fb8f89c0e"}]}
```

```kotlin [field-property box="frames_lifecycle_forever" name="code" path="frames_lifecycle_forever_code_.kt"]
// An animate { } with no end condition. It runs until you press stop, or until you
// re-run this box. The running indicator on the box stays lit the whole time.
val started = AnimationClock.nowSeconds()
/*
Trail length and resolution are a bandwidth decision, not a taste one: every redraw
serialises the whole trail to SVG and pushes it down the output socket. 8 s at 0.06
is ~130 points; 12 s at 0.02 is ~600, enough to saturate the connection.
*/
val trailSeconds = 8.0
val trailStep = 0.06
var nextDraw = 0.0

fun disc(cx: Double, cy: Double, r: Double, rgba: Vec4) = fline {
    (0..28).forEach { i ->
        val a = i / 28.0 * 2.0 * kotlin.math.PI
        val px = cx + r * kotlin.math.cos(a)
        val py = cy + r * kotlin.math.sin(a)
        if (i == 0) moveTo(px, py) else lineTo(px, py)
    }
    close()
    color = rgba
    filled = true
    stroked = false
}

fun border(rgba: Vec4) = fline {
    moveTo(8.0, 8.0)
    lineTo(552.0, 8.0)
    lineTo(552.0, 312.0)
    lineTo(8.0, 312.0)
    close()
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
        var first = true
        while (s <= t) {
            if (first) {
                moveTo(atX(s), atY(s))
                first = false
            } else lineTo(atX(s), atY(s))
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

NOT launched by the transcript, because the point is that you drive this one yourself.
Three things to try, in this order:

1. Run it and press STOP inside the eight seconds.
-> both lines, with .end { } first. Note that they appear on the frame AFTER the
button: cancelling the body resumes it through the dispatcher, so the end phase
lands on a later Runloop tick and stop is not a synchronous barrier for it.
2. Run it and let the eight seconds elapse.
-> only the finally line. The body returned; there was no cancellation to catch,
and a cancellation is the only route into .end { }.
3. Run it, and while it is still counting, RUN IT AGAIN.
-> both lines again. A re-run bumps the strand generation rather than the launch
epoch, but either way the end job registers and runs — and it runs from inside
frame()'s catch, before the cancellation unwinds the body's finally.

So .end { } means "when this animation is cancelled", not "when it finishes".

```edn [field-box id="frames_lifecycle_cleanup" role="properties"]
{"id" "frames_lifecycle_cleanup" "index" 2 "name" "4 · cleanup — stop it, wait it out, then re-run it" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "4 · cleanup — stop it, wait it out, then re-run it" "_tombstone" false "frame" #afield/rect [700.0 620.0 620.0 150.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "frames_lifecycle_cleanup_code_.kt" "lines" 56 "characters" 2366 "sha256" "d0f212d635a7408d864c0a70a19402f36514ab247d05cf7b30666320b296f321"}]}
```

```kotlin [field-property box="frames_lifecycle_cleanup" name="code" path="frames_lifecycle_cleanup_code_.kt"]
/*
NOT launched by the transcript, because the point is that you drive this one yourself.
Three things to try, in this order:

  1. Run it and press STOP inside the eight seconds.
     -> both lines, with .end { } first. Note that they appear on the frame AFTER the
        button: cancelling the body resumes it through the dispatcher, so the end phase
        lands on a later Runloop tick and stop is not a synchronous barrier for it.
  2. Run it and let the eight seconds elapse.
     -> only the finally line. The body returned; there was no cancellation to catch,
        and a cancellation is the only route into .end { }.
  3. Run it, and while it is still counting, RUN IT AGAIN.
     -> both lines again. A re-run bumps the strand generation rather than the launch
        epoch, but either way the end job registers and runs — and it runs from inside
        frame()'s catch, before the cancellation unwinds the body's finally.

So .end { } means "when this animation is cancelled", not "when it finishes".
*/
val started = AnimationClock.nowSeconds()
val runFor = 8.0
val notes = mutableListOf<String>()
var nextDraw = 0.0

val show: (String) -> Unit = { headline -> markdown("main", headline.md) }

/*
Cleanup goes to its OWN channel. Re-running the box immediately repaints "main" from
the new launch, which would clobber the old launch's cleanup line before you could
read it -- and the re-run case is the whole point of instruction 3.
{ok:} styles them as the theme's success colour.
*/
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

The same loop, with an end condition and a cleanup block. Watch it for six seconds:
the disc travels, the bar fills, and then everything freezes and turns red. Nothing
after that is the animation — it is the last frame the .end { } block painted.

```edn [field-box id="frames_lifecycle_stops" role="properties"]
{"id" "frames_lifecycle_stops" "index" 3 "name" "2 · stops itself after six seconds" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 · stops itself after six seconds" "_tombstone" false "frame" #afield/rect [700.0 60.0 620.0 150.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "frames_lifecycle_stops_code_.kt" "lines" 97 "characters" 2895 "sha256" "c14f3ae4b17a1a465222571e26662603db3465d627ce372b8074c13d14582593"}]}
```

```kotlin [field-property box="frames_lifecycle_stops" name="code" path="frames_lifecycle_stops_code_.kt"]
/*
The same loop, with an end condition and a cleanup block. Watch it for six seconds:
the disc travels, the bar fills, and then everything freezes and turns red. Nothing
after that is the animation — it is the last frame the .end { } block painted.
*/
val started = AnimationClock.nowSeconds()
val stopAfter = 6.0
var nextDraw = 0.0

fun disc(cx: Double, cy: Double, r: Double, rgba: Vec4) = fline {
    (0..28).forEach { i ->
        val a = i / 28.0 * 2.0 * kotlin.math.PI
        val px = cx + r * kotlin.math.cos(a)
        val py = cy + r * kotlin.math.sin(a)
        if (i == 0) moveTo(px, py) else lineTo(px, py)
    }
    close()
    color = rgba
    filled = true
    stroked = false
}

val draw: (Double, Boolean) -> Unit = { elapsed, stopped ->
    val accent = if (stopped) vec(1.0, 0.28, 0.30, 1.0) else vec(0.48, 0.85, 1.0, 1.0)
    val quiet = if (stopped) vec(1.0, 0.28, 0.30, 0.55) else vec(0.55, 0.62, 0.72, 0.7)
    val border = fline {
        moveTo(6.0, 6.0)
        lineTo(594.0, 6.0)
        lineTo(594.0, 244.0)
        lineTo(6.0, 244.0)
        close()
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
        moveTo(60.0, 165.0)
        lineTo(540.0, 165.0)
        lineTo(540.0, 205.0)
        lineTo(60.0, 205.0)
        close()
        color = quiet
        thickness = 1.0
        stroked = true
        filled = false
    }
    // How far through the six seconds we are. It fills, and then it stops filling.
    val progress = fline {
        val w = 480.0 * (elapsed / stopAfter).coerceIn(0.0, 1.0)
        moveTo(60.0, 165.0)
        lineTo(60.0 + w, 165.0)
        lineTo(60.0 + w, 205.0)
        lineTo(60.0, 205.0)
        close()
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

