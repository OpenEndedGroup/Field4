# Keyframes: capturing, persisting and replaying live state

*Every `keyframer.kt` declaration used below is listed, with its KDoc, in the codebase reference:
[`keyframer.kt`](reference/graphics.md#keyframer-kt). The two supporting types the examples lean on,
`afield.four.buffers.Structure` and `afield.four.util.CameraPose`, are **not** — the reference covers
`afield.stdlib` and `afield.visualization` only, and `afield.four` is documented from
[afield.four Graphics](afield_four_graphics.md) instead
([`reference/index.md`](reference/index.md) says so at the foot of its file table).*

Live coding has a specific kind of loss built into it. You push sliders around for twenty minutes,
arrive somewhere good, and then the thing you arrived at exists only as the current contents of a
running process. Reload the shader and it is gone. Close the document and it is gone. Try to
reproduce it tomorrow from memory and you get something that is *nearly* it, which is worse.

`keyframer.kt` is the answer to that: a `KeyframeScope` is a set of named live targets, `capture()`
reads all of them into a `Key`, and invoking that `Key` writes them all back. Once a look is a
`Key`, it is an ordinary value — you can diff it against another one, blend between them, write it
to disk with a version history, or turn it into Kotlin source you can paste into a box and commit.

The blending half of this — numbers and vectors easing while *code* steps discretely — already has
a worked example over on
[Values over time](interpolants_examples.md#keyframes-morphing-a-whole-parameter-state-including-code).
This page is the other three quarters, which had no public documentation at all: **what a scope can
watch**, **why a camera move is a screw motion**, **how saving works and what survives it**, and
**how a captured look becomes source**.

Almost everything below runs on the CPU. `Structure` is constructible bare
(`Structure.kt:112`) and its `bind`/`bindings` are plain map operations
(`Structure.kt:176`, `Structure.kt:136`), so a keyframe scope can be exercised — and tested —
without a GPU anywhere in sight. The exception is
[the GPU still](#the-same-thing-driving-a-live-gpu-uniform), which is a real Vulkan render, and the
targets that need a renderer, which are listed at the [foot of the page](#what-is-actually-tested).

## What a scope watches

A scope holds no values of its own. It holds *targets*: places to read from and write to. The
simplest is a uniform `Structure`, and the interesting wrinkle is the `@` in a key name — everything
after the last `@` is the property driven on the target, so a glow pass and a blur pass can both own
a `radius` without colliding.

```diffstatic_runner {data-lang=kotlin screenshot="assets/keyframer_examples/capture_apply.png" wait="2.0" capture="box" open-outputs="true" w="900" h="620" padding="16"}
:::static
import afield.four.buffers.Structure
import afield.stdlib.KeyframeScope

// Two independent uniform structures, as a glow pass and a blur pass would each have.
val glow = Structure()
val blur = Structure()
glow.bind("radius") { 4.0f }
glow.bind("intensity") { 0.35f }
blur.bind("radius") { 12.0f }

val scope = KeyframeScope()
// The part after the last '@' is the property driven on the structure. Both passes have a
// `radius`; the qualifier is what keeps them apart in the key.
scope.target("glow@radius", glow)
scope.target("glow@intensity", glow)
scope.target("blur@radius", blur)

// The snapshot. Reading only — nothing is written anywhere.
val captured = scope.capture()

// Now fiddle, the way you would with the sketch running, and make it so.
val edited = captured.copy().update {
    this["glow@radius"] = 9.0f
    this["glow@intensity"] = 0.80f
}
edited()

// Read the live structures back to prove the write actually landed — and that it landed on the
// bare property name, not on the qualified key name.
fun live(s: Structure, p: String) = s.bindings[p]?.invoke()?.toString() ?: "&lt;unbound&gt;"
val liveRows = listOf(
    "glow.radius" to live(glow, "radius"),
    "glow.intensity" to live(glow, "intensity"),
    "blur.radius" to live(blur, "radius"),
    "glow.<i>glow@radius</i>" to live(glow, "glow@radius")
).joinToString("") { (k, v) ->
    "<tr><td style='padding:2px 12px 2px 0;color:#9cdcfe'>$k</td>" +
        "<td style='color:#d4d4d4'>$v</td></tr>"
}

// Put the original back, and diff to show the scope is exactly where it started.
captured()
val restored = scope.capture()

// The card's width is pinned so the figure stays legible — see the persistence example below.
display(
    "keys",
    "<div style='font:12px/1.5 Iosevka, Menlo, monospace;color:#d4d4d4;display:grid;gap:14px;" +
        "width:820px;max-width:820px'>" +
        "<div><div style='color:#c586c0;margin-bottom:4px'>1 &mdash; capture()</div>" + captured.h + "</div>" +
        "<div><div style='color:#c586c0;margin-bottom:4px'>2 &mdash; edited, then applied</div>" +
        captured.diffHtml(edited, "captured", "edited") + "</div>" +
        "<div><div style='color:#c586c0;margin-bottom:4px'>3 &mdash; the live structures, read back</div>" +
        "<table style='border-collapse:collapse'>" + liveRows + "</table></div>" +
        "<div><div style='color:#c586c0;margin-bottom:4px'>4 &mdash; captured() again, then re-captured</div>" +
        captured.diffText(restored, "captured", "restored") + "</div>" +
    "</div>"
)
```

![Four stacked panels: the captured key of three values, a diff showing glow@radius and glow@intensity changed, a read-back of the live structures showing radius 9.0 and intensity 0.8 on the unqualified property names with glow@radius itself unbound, and the text "No key differences." after restoring](assets/keyframer_examples/capture_apply.png)

Panel 3 is the one to read carefully. The edited key wrote `9.0` to `glow`'s **`radius`**, not to
anything called `glow@radius` — that name is unbound on the structure, and applying the key does not
create it, because the write goes through the target's bind function, which was built with the
`@`-stripped property name. The qualifier exists purely so one key can address two structures that
use the same uniform name.

Panel 4 is the round trip closing: re-applying the original snapshot and re-capturing gives
`No key differences.`, so a `Key` really is a complete description of what the scope watches.

!!! info "The module-pointer targets are not exercised on this page"

    Besides a `Structure`, a scope can target a live module's uniform structures —
    `target(module: ModulePointer, name)` and its `ComputeModulePointer` / `MeshModulePointer`
    siblings (`keyframer.kt:580`, `:584`, `:588`). Those need a real renderer, so every fence here
    uses the plain `Structure` form instead, and no figure on this page demonstrates them.

    Worth knowing what the source says before you reach for one. All three are one-line wrappers
    over the lazily-resolved `target(name) { … }` (`keyframer.kt:562`), and they pass the *same*
    `@`-suffixed string to two different lookups: it selects the structure, via
    `module.current.liveUniformStructures[…]`, **and** it is the member name bound inside that
    structure (`keyframer.kt:566-567`). That map is keyed by the **uniform block's name**
    (`Module.kt:559` for a graphics module, `ComputeModule.kt:163` for a compute one), while the
    members inside it are the block's *fields*. So the key can only address a member whose name
    equals the block's own name — not an arbitrary field within the block, and not the independent
    structure-and-property pair the `@` qualifier gives a plain `Structure` target.

    This is a reading of three source files, not a measurement — nothing here executes those
    overloads. The working way to keyframe a module's uniform is the plain `Structure` overload
    against `module.<block>`, which resolves to that same live structure
    (`Module.kt:217`), and that **is** demonstrated, on a real GPU, in the next section.

!!! warning "`capture()` sees *bound* properties, not *set* values"

    A `Structure` can hold a property two ways: `structure.bind("radius") { 4f }` installs a
    binding, and `structure["radius"] = 4f` stores a value (and removes any binding). `capture()`
    reads `Structure.bindings` only, so **a property written by value is invisible to a snapshot**
    and captures as `null`, with nothing logged. The key still lists the name — it is just empty.
    Bind the things you intend to keyframe.

## The same thing, driving a live GPU uniform

Everything so far ran on bare `Structure`s, which is honest but leaves the obvious question open:
does a `Key` actually move a uniform on the GPU? This fence is a real Vulkan render — a native
window, a `moduleHere()` graphics module, a fan of **white** triangles — where the only source of
colour in the entire frame is a `vec4 tint` in the shader's uniform block, and the only thing that
ever writes that uniform is a keyframe.

```diffstatic_runner {data-lang=kotlin screenshot="assets/keyframer_examples/gpu_tint.png" wait="0.25" timeout="20" capture="native-window" native-wait-frames="4" trim-native=true graphics-context=true name="keyframer-gpu-tint" source-box="assets/canonical_graphics/keyframer-look" source-box-properties="vertex,fragment" source-box-wait="0.1"}
import afield.four.Renderer
import afield.four.modules.fromContext
import afield.stdlib.KeyframeScope

val renderer: Renderer = fromContext()

var m = Mesh()
var module = moduleHere()

module += "mainMesh" to m
renderer += module

// Fully qualified on purpose: a uniform block makes moduleHere() generate a Kotlin class of the
// same (capitalised) name straight into this box's preamble, and a declaration in the box beats
// any import. The block here is `look`, so the trap would bite anything called `Look`.
val camera = afield.four.util.Camera()
camera.at = vec(0, 0, 7)
camera.target = vec(0, 0, 0)

module.look.p = camera.p()
module.look.m = camera.m()

// A fan of WHITE triangles. Every colour in the picture comes from the tint, and nothing else.
val N = 48
m.set(3 * N, 1 * N) {
    N {
        val a0 = it / N.toDouble() * Math.PI * 2.0
        val a1 = (it + 0.62) / N.toDouble() * Math.PI * 2.0
        pos.add(
            0.0, 0.0, 0.0,
            2.4 * kotlin.math.cos(a0), 2.4 * kotlin.math.sin(a0), 0.0,
            2.4 * kotlin.math.cos(a1), 2.4 * kotlin.math.sin(a1), 0.0
        )
        3 { col.add(1, 1, 1, 1) }
        tri.add(0 + 3 * it, 1 + 3 * it, 2 + 3 * it)
    }
}

// ---- the keyframe part -------------------------------------------------------------------
// `module.look` resolves to the block's live uniform Structure (Module.kt:217), so the ordinary
// Structure target drives it. This is the overload to use for a module uniform.
val scope = KeyframeScope()
scope.target("tint", module.look)

val cool = scope.Key(mutableMapOf("tint" to vec(0.20, 0.65, 1.00, 1.0)))
val warm = scope.Key(mutableMapOf("tint" to vec(1.00, 0.50, 0.12, 1.0)))

// A quarter of the way from cool to warm — deliberately NOT the midpoint. These two are nearly
// complementary, so t = 0.5 blends to (0.60, 0.575, 0.56): a grey indistinguishable from an
// untinted fan, which would make the picture prove nothing. t = 0.25 is (0.40, 0.6125, 0.78).
scope.interpolate(cool, warm, 0.25)()
```

![A radial fan of forty-eight wedges on black, every wedge the same light blue, rendered in a native Vulkan window](assets/keyframer_examples/gpu_tint.png)

The fan is blue, and that is the whole argument: the mesh colours are all `(1, 1, 1, 1)`, so a fan
that is *any* colour other than white can only have got it from the uniform, and an unwritten
uniform would have left it black.

It is worth being exact, because "it looks blue" is not evidence. The capture contains precisely two
colours — black background and `rgb(170, 205, 229)` across all 497,142 fan pixels. The keyframed
value is the linear `(0.40, 0.6125, 0.78)`, and sRGB-encoding that gives `(170, 205, 229)`, to the
byte, on all three channels. So the pixels on screen are the interpolated keyframe and nothing else:
not the `cool` endpoint, not the `warm` one, not white.

This also demonstrates the one thing a unit test cannot: `Key.invoke()` writing through
`Structure.bind` into a uniform that a live Vulkan pipeline then reads.

## A camera move is a screw motion, not a straight line

`KeyframeScope.camera` registers a `Camera` as a target. What it stores is not a position and a
target point but a `CameraPose`: the camera's rigid transform as a dual quaternion, plus the focus
distance. That choice is the whole reason the feature is worth having, and it is easiest to see by
drawing what the two options actually do.

Interpolating a dual quaternion is a *screw motion* (`CameraPose.sclerp`, `CameraPose.kt:45`, over
`DualQuaternionf.sclerp`, `DualQuaternionf.kt:158`) — a rotation and a translation about one axis,
performed together. Interpolating a position is a straight line. Between two cameras that both look
at the same subject, those are very different moves:

```diffstatic_runner {data-lang=kotlin screenshot="assets/keyframer_examples/camera_screw.png" wait="2.5" capture="box" open-outputs="true" w="1000" h="520" padding="16"}
:::static
import afield.four.util.CameraPose
import afield.stdlib.KeyframeScope
import afield.stdlib.linalg.Vector3f

val scope = KeyframeScope()
// Two cameras, both two units up, both looking at the origin, a quarter turn apart.
val a = scope.Key(mutableMapOf("camera" to CameraPose.lookAt(Vector3f(8f, 2f, 0f), Vector3f(0f, 0f, 0f))))
val b = scope.Key(mutableMapOf("camera" to CameraPose.lookAt(Vector3f(0f, 2f, 8f), Vector3f(0f, 0f, 0f))))
val pa = (a["camera"] as CameraPose).position
val pb = (b["camera"] as CameraPose).position

val n = 60
fun sclerpAt(t: Double) = (scope.interpolate(a, b, t)["camera"] as CameraPose)
fun lerpAt(t: Double) = Vector3f(pa).lerp(pb, t.toFloat())

// ---- panel 1: plan view, looking down the y axis. World units scaled up to figure units.
val S = 40.0
val screw = fline {
    for (i in 0..n) {
        val p = sclerpAt(i.toDouble() / n).position
        if (i == 0) moveTo(p.x * S, p.z * S) else lineTo(p.x * S, p.z * S)
    }
    color = vec(0.36, 0.84, 1.0, 1.0); thickness = 3.0
}
val chord = fline {
    moveTo(pa.x * S, pa.z * S); lineTo(pb.x * S, pb.z * S)
    color = vec(1.0, 0.62, 0.26, 1.0); thickness = 3.0
}
// Sight lines from the sclerp path to the subject: every one is the same length.
val sight = fline {
    for (i in 0..10) {
        val p = sclerpAt(i / 10.0).position
        moveTo(p.x * S, p.z * S); lineTo(0.0, 0.0)
    }
    color = vec(0.36, 0.84, 1.0, 0.22); thickness = 1.0
}
// …and from the lerped positions, which are visibly shorter in the middle.
val sightLerp = fline {
    for (i in 0..10) {
        val p = lerpAt(i / 10.0)
        moveTo(p.x * S, p.z * S); lineTo(0.0, 0.0)
    }
    color = vec(1.0, 0.62, 0.26, 0.22); thickness = 1.0
}
val subject = fline {
    val r = 14.0
    for (i in 0..48) {
        val ang = i / 48.0 * Math.PI * 2.0
        if (i == 0) moveTo(r * kotlin.math.cos(ang), r * kotlin.math.sin(ang))
        else lineTo(r * kotlin.math.cos(ang), r * kotlin.math.sin(ang))
    }
    color = vec(0.95, 0.95, 0.95, 0.9); thickness = 2.0
}

// ---- panel 2: distance from the camera to the subject, against t.
fun plotY(d: Double) = 330.0 - (d - 5.5) * 120.0
val dScrew = fline {
    for (i in 0..n) {
        val t = i.toDouble() / n
        if (i == 0) moveTo(0.0, plotY(sclerpAt(t).position.length().toDouble()))
        else lineTo(t * 400.0, plotY(sclerpAt(t).position.length().toDouble()))
    }
    color = vec(0.36, 0.84, 1.0, 1.0); thickness = 3.0
}
val dChord = fline {
    for (i in 0..n) {
        val t = i.toDouble() / n
        if (i == 0) moveTo(0.0, plotY(lerpAt(t).length().toDouble()))
        else lineTo(t * 400.0, plotY(lerpAt(t).length().toDouble()))
    }
    color = vec(1.0, 0.62, 0.26, 1.0); thickness = 3.0
}

// Every number in the captions is measured here, not asserted from memory.
val rMin = (0..n).minOf { sclerpAt(it.toDouble() / n).position.length() }
val rMax = (0..n).maxOf { sclerpAt(it.toDouble() / n).position.length() }
val worstAim = (0..n).maxOf { sclerpAt(it.toDouble() / n).target.length() }
val lerpMid = lerpAt(0.5).length()
val closer = (1.0 - lerpMid / rMax) * 100.0

showPlaneGrid(
    ("plan view, looking down y\n" +
        "blue: sclerp, a quarter circle. orange: the straight line between the same two positions") to
        listOf(sight, sightLerp, subject, chord, screw),
    ("distance to the subject, over the move\n" +
        "sclerp holds %.4f..%.4f; the straight line dips to %.4f — %.1f%% nearer".format(
            rMin, rMax, lerpMid, closer)) to
        listOf(dScrew, dChord),
    columns = 2, cellWidth = 440, cellHeight = 380
)
display(
    "aim",
    "<div style='font:12px/1.5 Iosevka, Menlo, monospace;color:#d4d4d4;padding:8px 0'>" +
        "worst-case aiming error along the sclerp path: <b>%.6f</b> world units &mdash; ".format(worstAim) +
        "the subject never leaves the centre of frame." +
    "</div>"
)
```

![Two panels. Left, a plan view: a blue quarter-circle arc from one camera position to the other with faint equal-length sight lines to a circle at the origin, and an orange straight chord cutting across it whose sight lines are visibly shorter in the middle. Right, distance against time: a flat blue line at 8.2462 and an orange curve dipping to 6.0 at the midpoint](assets/keyframer_examples/camera_screw.png)

The blue path is a circle. Not approximately — the distance from the camera to the subject is
constant at **8.2462** for the entire move, and the height stays at exactly 2.0, so the screw
degenerates to a pure rotation about the vertical axis through the subject. The orange path is what
you get by lerping the two positions: it cuts the corner, and at the midpoint the camera is
**6.0000** away instead of 8.2462 — **27.2% nearer**. On screen that reads as the subject lunging
toward the viewer and retreating again, which is not a move anyone asked for.

The right panel is the same fact plotted. And the aiming error printed underneath is the part that
makes this a *camera* feature rather than a maths curiosity: over the whole move, the furthest the
sclerp path's aim ever strays from the origin is **0.000003 world units** — three millionths, which
is `Float` rounding and not a wobble. The subject stays framed for free, with no look-at constraint
anywhere in the loop.

All of this is asserted in `aCameraPoseSclerpsItsTransformAndLerpsItsFocusDistance`: the constant
distance, the constant height and the aim are each checked at **eleven** sample points across the
move, and the midpoint figures — 8/√2 on each axis for the sclerp, 6.0000 and 27.24% for the
straight line — once each, where they are defined.

!!! note "The camera target itself needs a renderer"

    `KeyframeScope.camera(camera)` takes a live `Camera`, and `Camera`'s constructor is
    `Camera(val renderer: Renderer = fromContext())` (`Camera.kt:16`) — it resolves a renderer from
    the box context. So the *registration* is one of the eight declarations no unit test reaches
    (the full list is at the [foot of the page](#what-is-actually-tested)); the pose arithmetic
    above, which is all of the behaviour, is tested. Note also that the target accepts a
    `CameraPose` or a bare `DualQuaternionf` and **silently ignores any other type**
    (`keyframer.kt:647-650` is a `when` with no `else`), so a `Vector3f` "camera position" key will
    appear to apply and do nothing.

## Saving: every save is history

`saveKey` writes pretty JSON — and, every single time, also appends a numbered copy to a
`<file>.versions` directory with a manifest. Saving is always additive; nothing prunes it.

What survives the trip is a closed set. `KValue.fromAny` recognises numbers, `vec2`/`3`/`4`,
quaternion, dual quaternion, camera pose, string, StackSL source and source-backed block. Anything
else — a `Boolean`, a list, a null — is **dropped in silence**, and the key loads back one value
short with no warning anywhere.

```diffstatic_runner {data-lang=kotlin screenshot="assets/keyframer_examples/persistence.png" wait="3.0" capture="box" open-outputs="true" w="960" h="760" padding="16"}
:::static
import afield.stdlib.KeyframeScope
import afield.stdlib.stackSLString
import afield.stdlib.linalg.Vector3f
import java.nio.file.Files

val dir = Files.createTempDirectory("keyframer-docs")
val path = dir.resolve("look.json")

val scope = KeyframeScope(schemaVersion = 1)
scope.saveKey(path, scope.Key(mutableMapOf(
    "gain" to 0.20f,
    "pos" to Vector3f(1f, 2f, 3f),
    "voice" to stackSLString("saw 110 *")
)), note = "first pass")

scope.saveKey(path, scope.Key(mutableMapOf(
    "gain" to 0.65f,
    "pos" to Vector3f(4f, 5f, 6f),
    "voice" to stackSLString("sin 220 * fold")
)), note = "brighter")

// A third save that also carries values the union cannot represent.
val lossy = scope.Key(mutableMapOf(
    "gain" to 0.90f,
    "enabled" to true,          // a Boolean is outside the union
    "taps" to listOf(1, 2, 3),  // so is a list
    "absent" to null            // and a null is dropped too
))
scope.saveKey(path, lossy, note = "with unsupported values")

val versions = scope.keyVersions(path)
val versionRows = versions.joinToString("") { v ->
    "<tr><td style='padding:1px 14px 1px 0;color:#9cdcfe'>${v.index}</td>" +
        "<td style='padding-right:14px;color:#888'>${v.file}</td>" +
        "<td style='color:#d4d4d4'>${v.note ?: "&mdash;"}</td></tr>"
}

val reloaded = scope.loadKey(path)
val kept = reloaded.values.keys.sorted().joinToString(", ")
val droppedNames = lossy.values.keys.sorted().filter { it !in reloaded.values.keys }

// An older snapshot loaded into a scope that has since grown a parameter.
val newer = KeyframeScope(schemaVersion = 1)
newer.missingKey("wet", 0.5f)                  // applies to every file this scope loads
newer.missingKey(1, "mode", "legacy-v1")       // only to files stamped schemaVersion = 1
val migrated = newer.loadKey(path, version = 0)

fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

// Rendered as flat text rather than through `migrated.h`. The Key's own HTML gives a Vector3f the
// interactive 3D axis widget, which is lovely in a box and wrong here: it loads asynchronously and
// reflowed the card mid-screenshot, so the first capture of this figure came out clipped.
val migratedRows = migrated.values.entries.sortedBy { it.key }.joinToString("") { (k, v) ->
    "<tr><td style='padding:1px 14px 1px 0;color:#9cdcfe'>" + esc(k) + "</td>" +
        "<td style='color:#d4d4d4'>" + esc(v.toString()) + "</td></tr>"
}

// The width is pinned on purpose. A `capture="box"` screenshot clips to the output card, and an
// unpinned card sized by its widest unbreakable line loses everything past the clip.
display(
    "persist",
    "<div style='font:12px/1.5 Iosevka, Menlo, monospace;color:#d4d4d4;display:grid;gap:14px;" +
        "width:820px;max-width:820px'>" +
        "<div><div style='color:#c586c0;margin-bottom:4px'>version history after three saves</div>" +
        "<table style='border-collapse:collapse'>" + versionRows + "</table></div>" +
        "<div><div style='color:#c586c0;margin-bottom:4px'>third save: what survived</div>" +
        "kept: <b>" + kept + "</b><br>dropped in silence: <b style='color:#f88'>" +
        droppedNames.joinToString(", ") + "</b></div>" +
        "<div><div style='color:#c586c0;margin-bottom:4px'>version 0, reloaded into a scope that has " +
        "since grown a 'wet' parameter (" + migrated.values.size + " values)</div>" +
        "<table style='border-collapse:collapse'>" + migratedRows + "</table></div>" +
        "<div><div style='color:#c586c0;margin-bottom:4px'>version 0 on disk, as written</div>" +
        "<pre style='margin:0;padding:6px 8px;background:rgba(255,255,255,0.04);" +
        "border-left:2px solid #555;white-space:pre-wrap;width:800px;max-width:800px'>" +
        esc(java.nio.file.Files.readString(dir.resolve("look.json.versions").resolve("000000.json"))) +
        "</pre></div>" +
    "</div>"
)
```

![Four panels: a three-row version history numbered 0 to 2 with notes; a line reading kept gain and dropped absent, enabled, taps; a reloaded key showing gain, pos, voice plus the filled-in wet and mode defaults; and the pretty-printed JSON of version zero](assets/keyframer_examples/persistence.png)

Three things worth pulling out of that.

**The drop is silent and total.** The third save went in with four names and came back with one. No
exception, no log line, no marker in the file. If you keyframe a `Boolean` you will simply never see
it again.

**`missingKey` is how a scope outgrows its own files.** Version 0 was written before `wet` existed;
loading it into a scope that declares `missingKey("wet", 0.5f)` fills the gap. The schema-qualified
form, `missingKey(1, "mode", …)`, applies only to files stamped with that `schemaVersion`, and it
is applied *after* the version-agnostic defaults, so for a matching file it wins. Nothing ever
*rejects* a file for having a different schema version — the stamp only selects defaults.

**A name present in the file always beats a default**, even when its value is null.

One detail in that JSON is worth not being alarmed by. `gain` was saved as the `Float` `0.20f` and
appears on disk as `0.20000000298023224`, because every number goes over the wire as a `Double`.
The round trip is still exact: `"kind": "FLOAT"` is carried alongside, and `KValue.toAny` uses it to
narrow the value back to a `Float`, recovering `0.2f` precisely — asserted, against that exact
on-disk string, in `theFloatOnDiskNarrowsBackToExactlyTheFloatThatWasSaved`.

The `Double` wire format does lose information in two cases, though. A `Long` past 2^53 has more
mantissa than a `Double` can hold and comes back changed. And any `Number` that is *not* one of the
six `NumKind`s — a `BigDecimal`, a `BigInteger` — is stored as a plain `DOUBLE` and comes back a
`Double`, so even where the value survives the **type** does not.

!!! danger "A corrupt version manifest silently restarts numbering"

    `keyVersions` reads `<file>.versions/manifest.json` inside a `runCatching` that swallows the
    failure and returns an empty list. Since the next `saveKey` numbers itself from the manifest,
    a manifest that cannot be parsed makes the next save call itself version 0 and **overwrite
    `000000.json`** — the oldest snapshot in the history. This is real, not theoretical; it is
    pinned by `aCorruptManifestSilentlyRestartsNumberingAndOverwritesVersionZero`. If the history
    matters, back up the directory, not just the key file.

## A captured look becomes source

The last step of the loop closes it. `Key.updateCode()` emits the snapshot as pasteable Kotlin —
an `update { … }` or `updateAndApply { … }` block, sorted by name — so a state you arrived at by
fiddling becomes a diff you can commit.

```diffstatic_runner {data-lang=kotlin screenshot="assets/keyframer_examples/update_code.png" wait="2.0" capture="box" open-outputs="true" w="900" h="560" padding="16"}
:::static
import afield.stdlib.KeyframeScope
import afield.stdlib.SourceBackedBlock
import afield.stdlib.stackSLString
import afield.stdlib.linalg.Vector3f
import afield.stdlib.linalg.Quaternionf

val scope = KeyframeScope()
val key = scope.Key(mutableMapOf(
    "gain" to 0.65f,           // a Float, and the 'f' suffix matters
    "steps" to 12,
    "seed" to 99L,
    "pos" to Vector3f(1.5f, 0f, -2.25f),
    "spin" to Quaternionf().setAngleAxis(0.7f, 0f, 1f, 0f),
    "voice" to stackSLString("sin 220 * fold"),
    // A source block whose body mentions a '$'. Emitting this as a Kotlin *raw* string would
    // have let the '$' interpolate at paste time; it now falls back to an escaped literal.
    "block" to SourceBackedBlock<Any?>("box-1", "val total = 2\nprintln(\"sum: \$total\")"),
    "notes" to listOf("unsupported", "on purpose")
))

fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
display(
    "code",
    "<pre style='font:12px/1.5 Iosevka, Menlo, monospace;color:#d4d4d4;margin:0;padding:8px 10px;" +
        "background:rgba(255,255,255,0.04);border-left:2px solid #555;white-space:pre-wrap'>" +
        esc(key.updateCode("look")) + "</pre>"
)
```

![The emitted Kotlin: look.updateAndApply with assignments sorted by name, showing an escaped source-block string with a backslash before its dollar, gain as 0.65f, a fully-qualified Quaternionf constructor, seed as 99L, and the unsupported list emitted as null behind a block comment](assets/keyframer_examples/update_code.png)

Every literal is chosen so the paste round-trips as the same *type*, not merely the same number.
`0.65f` keeps its `f`, because without it a captured `Float` comes back as a `Double` literal and
`Key.set` deliberately does not re-coerce numbers. `99L` keeps its `L`; bytes and shorts get
`.toByte()` / `.toShort()`. Vectors come out as `vec(…)`, quaternions and poses as fully-qualified
constructor calls, and StackSL as `stackSLString(…)` so it stays *code* rather than decaying to a
string. A value outside that set — the list here — becomes `null` behind an `/* unsupported */`
comment: the emitted code still compiles, and quietly nulls that one value.

The escaped `\$` in the source block is a fix this page's own research turned up.
`kotlinRawStringLiteral` preferred a Kotlin raw string for a source block, guarding against `"""`
and a trailing quote — but not against `$`, which a raw string still interpolates. Any block whose
body mentioned `$name` or `${…}` therefore emitted code that either failed to compile or silently
interpolated something else, defeating the exact round trip `updateCode` exists for. It now falls
through to the escaped literal in that case, and `updateCodeEscapesDollarInASourceBlock` keeps it
that way.

`copyUpdateCodeToClipboard()` is the same string, put on the system clipboard. That one goes
through AWT, so it throws on a headless JVM and it replaces whatever the user had copied.

!!! warning "`diff` disagrees with itself about `NaN` and about signed zero"

    Two comparison rules meet inside `keyValuesEqual`, and they are opposites on exactly the two
    values where floating point is awkward. A **bare number** is compared with `==` on an `Any?`
    receiver, which is `java.lang.Double.equals` — a *bit* comparison. A **vector or quaternion
    component** is compared with primitive `Float ==`, which is IEEE. So:

    | value | bare number | inside a vector |
    | --- | --- | --- |
    | `NaN` vs `NaN` | equal — no change reported | **not** equal — a phantom change |
    | `-0.0` vs `0.0` | **not** equal — a change reported | equal — the sign flip is hidden |

    A key holding `NaN` in a `Vector3f` therefore diffs as *changed* against an identical copy of
    itself. Pinned by `diffTreatsNaNAndSignedZeroOppositelyForANumberAndForAVectorComponent`, and
    left alone rather than unified: either rule is defensible on its own, and changing one would move
    what every `KeyDiff` table shows.

## Where to go next

- [Values over time](interpolants_examples.md#keyframes-morphing-a-whole-parameter-state-including-code)
  — blending two keys, including code values that step rather than smear.

### What is actually tested

`KeyframerTest` is 66 tests written against this page, so every number stated here is asserted
there. A companion ledger, `KeyframerCoverageTest`, enumerates `keyframer.kt`'s public surface **by
reflection** and requires each declaration to be classified, so the claim below is measured rather
than asserted and cannot quietly rot — adding a public declaration fails the build until it is
accounted for:

**93 of 101 public behavioural declarations are exercised (92.1%).** Data-class members the Kotlin
compiler generates are excluded, on the same grounds the
[codebase reference](reference/index.md) excludes them.

The remaining **8** are the ones a unit test cannot reach, each for the same two reasons:

| uncovered | why |
| --- | --- |
| `target(ModulePointer/ComputeModulePointer/MeshModulePointer, name)` | resolves `module.current.liveUniformStructures`; needs a live `Renderer` |
| `stackAuto(…)`, all three overloads | writes shader source through `stackAutoShaderSource`; needs a live `Renderer` |
| `camera(Camera, keyName)` | `Camera(val renderer: Renderer = fromContext())` (`Camera.kt:16`) |
| `Key.copyUpdateCodeToClipboard(…)` | the AWT system clipboard throws on a headless JVM |

That is the whole gap, and it is the same gap the two notes above describe. Closing it needs a
renderer in the loop, not more unit tests.
