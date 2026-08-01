# Field Document: keyframer_worked_example

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 4 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "fileManagerAccessHistory"]}
```

## 1. What a scope watches — capture, edit, apply

<!-- field-box id="keyframe_targets" index="0" -->

### Narrative

From `code`

A KeyframeScope holds no values of its own. It holds *targets*: places to read from and
write to. The simplest is a uniform Structure — and none of this needs a GPU, because
Structure is constructible bare and bind/bindings are plain maps.

```edn [field-box id="keyframe_targets" role="properties"]
{"id" "keyframe_targets" "index" 0 "name" "What a scope watches — capture, edit, apply" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "What a scope watches — capture, edit, apply" "_tombstone" false "frame" #afield/rect [80.0 80.0 880.0 420.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "keyframe_targets_code_.kt" "lines" 80 "characters" 3839 "sha256" "f2628f4b09133d7a209991df3ac693f6216804331bbde11b64cc1c5a031cc0c0"}]}
```

```kotlin [field-property box="keyframe_targets" name="code" path="keyframe_targets_code_.kt"]
// A KeyframeScope holds no values of its own. It holds *targets*: places to read from and
// write to. The simplest is a uniform Structure — and none of this needs a GPU, because
// Structure is constructible bare and bind/bindings are plain maps.
import afield.four.buffers.Structure
import afield.stdlib.KeyframeScope

val glow = Structure()
val blur = Structure()
glow.bind("radius") { 4.0f }
glow.bind("intensity") { 0.35f }
blur.bind("radius") { 12.0f }

val scope = KeyframeScope()
// The part after the last '@' is the property driven on the structure. Both passes have a
// `radius`; the qualifier is the only thing keeping them apart inside the key.
scope.target("glow@radius", glow)
scope.target("glow@intensity", glow)
scope.target("blur@radius", blur)

// A snapshot. Reading only — capture() writes nothing anywhere.
val captured = scope.capture()
println("captured: " + captured.values.toSortedMap())

// Fiddle, as you would with the sketch running, then make it so.
val edited = captured.copy().update {
    this["glow@radius"] = 9.0f
    this["glow@intensity"] = 0.80f
}
edited()
println(captured.diffText(edited, "captured", "edited"))

// Read the live structures back. Note the write landed on the BARE property name.
println("glow.radius    = " + glow.bindings["radius"]?.invoke())
println("glow.intensity = " + glow.bindings["intensity"]?.invoke())
println("blur.radius    = " + blur.bindings["radius"]?.invoke())
println("glow.'glow@radius' = " + glow.bindings["glow@radius"]?.invoke() + "  <- never bound")

// THE TRAP: capture() reads Structure.bindings and never Structure.values. A property
// written by value rather than bound is invisible to a snapshot, and says nothing.
val sneaky = Structure()
sneaky.bind("bound") { 1.0f }
sneaky["set"] = 2.0f
val trapScope = KeyframeScope()
trapScope.target("bound", sneaky)
trapScope.target("set", sneaky)
val trap = trapScope.capture()
println("bound -> " + trap["bound"] + " ; set -> " + trap["set"] + " (present: " + ("set" in trap) + ")")

// Put the original back and re-capture: a Key is a complete description of the scope.
captured()
val restored = scope.capture()

fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
fun row(k: String, v: Any?) =
    "<tr><td style='padding:1px 14px 1px 0;color:#9cdcfe'>" + esc(k) +
        "</td><td style='color:#d4d4d4'>" + esc(v.toString()) + "</td></tr>"

display(
    "keys",
    "<div style='font:12px/1.5 Iosevka, Menlo, monospace;color:#d4d4d4;display:grid;gap:14px;width:800px'>" +
        "<div><div style='color:#c586c0;margin-bottom:4px'>1 &mdash; capture()</div>" +
        captured.h + "</div>" +
        "<div><div style='color:#c586c0;margin-bottom:4px'>2 &mdash; edited, then applied</div>" +
        captured.diffHtml(edited, "captured", "edited") + "</div>" +
        "<div><div style='color:#c586c0;margin-bottom:4px'>3 &mdash; the live structures, read back</div>" +
        "<table style='border-collapse:collapse'>" +
        row("glow.radius", glow.bindings["radius"]?.invoke()) +
        row("glow.intensity", glow.bindings["intensity"]?.invoke()) +
        row("blur.radius", blur.bindings["radius"]?.invoke()) +
        row("glow.glow@radius", glow.bindings["glow@radius"]?.invoke() ?: "<unbound>") +
        "</table></div>" +
        "<div><div style='color:#c586c0;margin-bottom:4px'>4 &mdash; the bindings/values trap</div>" +
        "<table style='border-collapse:collapse'>" +
        row("bound (via bind)", trap["bound"]) +
        row("set (via values)", trap["set"].toString() + "  <- captured as null, silently") +
        "</table></div>" +
        "<div><div style='color:#c586c0;margin-bottom:4px'>5 &mdash; captured() again, then re-captured</div>" +
        esc(captured.diffText(restored, "captured", "restored")) + "</div>" +
    "</div>"
)
```

## 2. Saving is always additive history

<!-- field-box id="keyframe_persist" index="1" -->

### Narrative

From `code`

saveKey writes pretty JSON — and, every single time, ALSO appends a numbered copy to a
<file>.versions directory with a manifest. Nothing prunes it, so a hundred saves leave a
hundred files. What survives the trip is a closed set; everything else is dropped silently.

```edn [field-box id="keyframe_persist" role="properties"]
{"id" "keyframe_persist" "index" 1 "name" "Saving is always additive history" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Saving is always additive history" "_tombstone" false "frame" #afield/rect [1100.0 80.0 900.0 520.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "keyframe_persist_code_.kt" "lines" 79 "characters" 4084 "sha256" "d06115ec621767d75fea40ec6d5c5c55a272c0c8171927c56b3855c3bfcc8c6e"}]}
```

```kotlin [field-property box="keyframe_persist" name="code" path="keyframe_persist_code_.kt"]
// saveKey writes pretty JSON — and, every single time, ALSO appends a numbered copy to a
// <file>.versions directory with a manifest. Nothing prunes it, so a hundred saves leave a
// hundred files. What survives the trip is a closed set; everything else is dropped silently.
import afield.stdlib.KeyframeScope
import afield.stdlib.stackSLString
import afield.stdlib.linalg.Vector3f
import java.nio.file.Files

val dir = Files.createTempDirectory("keyframer-demo")
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

// A third save that also carries values the KValue union cannot represent.
val lossy = scope.Key(mutableMapOf(
    "gain" to 0.90f,
    "enabled" to true,          // a Boolean is outside the union
    "taps" to listOf(1, 2, 3),  // so is a list
    "absent" to null            // and a null is dropped too
))
scope.saveKey(path, lossy, note = "with unsupported values")

val versions = scope.keyVersions(path)
versions.forEach { println("version ${it.index}  ${it.file}  ${it.note ?: "—"}") }

val reloaded = scope.loadKey(path)
val dropped = lossy.values.keys.sorted().filter { it !in reloaded.values.keys }
println("third save kept: " + reloaded.values.keys.sorted())
println("dropped in silence: " + dropped + "  <- no exception, no log line")

// An older snapshot loaded into a scope that has since grown a parameter.
val newer = KeyframeScope(schemaVersion = 1)
newer.missingKey("wet", 0.5f)              // applies to every file this scope loads
newer.missingKey(1, "mode", "legacy-v1")   // only to files stamped schemaVersion = 1
val migrated = newer.loadKey(path, version = 0)
println("version 0 migrated: " + migrated.values.toSortedMap())

fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
val versionRows = versions.joinToString("") { v ->
    "<tr><td style='padding:1px 14px 1px 0;color:#9cdcfe'>${v.index}</td>" +
        "<td style='padding-right:14px;color:#888'>${v.file}</td>" +
        "<td style='color:#d4d4d4'>${esc(v.note ?: "—")}</td></tr>"
}
// Rendered as flat text rather than through `migrated.h`: the Key's own HTML gives a
// Vector3f an interactive 3D axis widget, which is lovely in a box and reflows the card.
val migratedRows = migrated.values.entries.sortedBy { it.key }.joinToString("") { (k, v) ->
    "<tr><td style='padding:1px 14px 1px 0;color:#9cdcfe'>" + esc(k) + "</td>" +
        "<td style='color:#d4d4d4'>" + esc(v.toString()) + "</td></tr>"
}

display(
    "persist",
    "<div style='font:12px/1.5 Iosevka, Menlo, monospace;color:#d4d4d4;display:grid;gap:14px;width:820px'>" +
        "<div><div style='color:#c586c0;margin-bottom:4px'>version history after three saves</div>" +
        "<table style='border-collapse:collapse'>" + versionRows + "</table></div>" +
        "<div><div style='color:#c586c0;margin-bottom:4px'>third save: what survived</div>" +
        "kept: <b>" + reloaded.values.keys.sorted().joinToString(", ") + "</b><br>" +
        "dropped in silence: <b style='color:#f88'>" + dropped.joinToString(", ") + "</b></div>" +
        "<div><div style='color:#c586c0;margin-bottom:4px'>version 0, reloaded into a scope that " +
        "has since grown a 'wet' parameter (" + migrated.values.size + " values)</div>" +
        "<table style='border-collapse:collapse'>" + migratedRows + "</table></div>" +
        "<div><div style='color:#c586c0;margin-bottom:4px'>version 0 on disk, as written</div>" +
        "<pre style='margin:0;padding:6px 8px;background:rgba(255,255,255,0.04);" +
        "border-left:2px solid #555;white-space:pre-wrap;width:800px'>" +
        esc(Files.readString(dir.resolve("look.json.versions").resolve("000000.json"))) +
        "</pre></div>" +
    "</div>"
)
```

## 3. A captured look becomes source

<!-- field-box id="keyframe_updatecode" index="2" -->

### Narrative

From `code`

The last step of the loop closes it: Key.updateCode() emits the snapshot as pasteable
Kotlin, so a state you arrived at by fiddling becomes a diff you can commit. Every
literal is chosen so the paste round-trips as the same TYPE, not merely the same number.

```edn [field-box id="keyframe_updatecode" role="properties"]
{"id" "keyframe_updatecode" "index" 2 "name" "A captured look becomes source" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "A captured look becomes source" "_tombstone" false "frame" #afield/rect [1100.0 640.0 900.0 360.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "keyframe_updatecode_code_.kt" "lines" 40 "characters" 1936 "sha256" "ca5f8c40fbbfeb38352834bdaf0f8c745823808e551b4d21aba0b03b2e2ba7a1"}]}
```

```kotlin [field-property box="keyframe_updatecode" name="code" path="keyframe_updatecode_code_.kt"]
// The last step of the loop closes it: Key.updateCode() emits the snapshot as pasteable
// Kotlin, so a state you arrived at by fiddling becomes a diff you can commit. Every
// literal is chosen so the paste round-trips as the same TYPE, not merely the same number.
import afield.stdlib.KeyframeScope
import afield.stdlib.SourceBackedBlock
import afield.stdlib.stackSLString
import afield.stdlib.linalg.Quaternionf
import afield.stdlib.linalg.Vector3f

val scope = KeyframeScope()
val key = scope.Key(mutableMapOf(
    "gain" to 0.65f,           // a Float — and the 'f' suffix is load-bearing
    "steps" to 12,
    "seed" to 99L,
    "pos" to Vector3f(1.5f, 0f, -2.25f),
    "spin" to Quaternionf().setAngleAxis(0.7f, 0f, 1f, 0f),
    "voice" to stackSLString("sin 220 * fold"),
    // A source block whose body mentions a '$'. Emitting this as a Kotlin *raw* string
    // would have let the '$' interpolate at paste time — a real defect, fixed alongside
    // public/docs/keyframer_examples.md. It must come out escaped.
    "block" to SourceBackedBlock<Any?>("box-1", "val total = 2\nprintln(\"sum: \$total\")"),
    "notes" to listOf("unsupported", "on purpose")
))

val code = key.updateCode("look")
println(code)

// The round trip is about types: without the 'f', a captured Float pastes back a Double,
// and Key.set deliberately does not re-coerce numbers.
println("Float keeps its suffix: " + code.contains("0.65f"))
println("Long keeps its suffix:  " + code.contains("99L"))
println("no bare '\$' escaped the emitter: " + Regex("""(?<!\\)\$""").findAll(code).none())

fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
display(
    "code",
    "<pre style='font:12px/1.5 Iosevka, Menlo, monospace;color:#d4d4d4;margin:0;padding:8px 10px;" +
        "background:rgba(255,255,255,0.04);border-left:2px solid #555;white-space:pre-wrap;" +
        "width:820px'>" + esc(code) + "</pre>"
)
```

## 4. A camera move is a screw motion

<!-- field-box id="keyframe_camera" index="3" -->

### Narrative

From `code`

KeyframeScope.camera stores a CameraPose — a rigid transform as a dual quaternion, plus
the focus distance — rather than a position and a target point. Interpolating that is a
SCREW MOTION, and between two cameras looking at the same subject it is a very different
move from lerping the two positions. Draw both and the argument makes itself.

```edn [field-box id="keyframe_camera" role="properties"]
{"id" "keyframe_camera" "index" 3 "name" "A camera move is a screw motion" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "A camera move is a screw motion" "_tombstone" false "frame" #afield/rect [80.0 540.0 980.0 460.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "keyframe_camera_code_.kt" "lines" 103 "characters" 4383 "sha256" "f84ee18ca267c51c289778e0ffaa3953fa512e4b9ace0f4201aa5bcfd76ea804"}]}
```

```kotlin [field-property box="keyframe_camera" name="code" path="keyframe_camera_code_.kt"]
// KeyframeScope.camera stores a CameraPose — a rigid transform as a dual quaternion, plus
// the focus distance — rather than a position and a target point. Interpolating that is a
// SCREW MOTION, and between two cameras looking at the same subject it is a very different
// move from lerping the two positions. Draw both and the argument makes itself.
import afield.four.util.CameraPose
import afield.stdlib.KeyframeScope
import afield.stdlib.linalg.Vector3f

val scope = KeyframeScope()
val a = scope.Key(mutableMapOf("camera" to CameraPose.lookAt(Vector3f(8f, 2f, 0f), Vector3f(0f, 0f, 0f))))
val b = scope.Key(mutableMapOf("camera" to CameraPose.lookAt(Vector3f(0f, 2f, 8f), Vector3f(0f, 0f, 0f))))
val pa = (a["camera"] as CameraPose).position
val pb = (b["camera"] as CameraPose).position

val n = 60
fun sclerpAt(t: Double) = (scope.interpolate(a, b, t)["camera"] as CameraPose)
fun lerpAt(t: Double) = Vector3f(pa).lerp(pb, t.toFloat())

// ---- panel 1: plan view, looking down y. World units scaled up to figure units.
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
// Sight lines from the sclerp path to the subject: every one is exactly the same length.
val sight = fline {
    for (i in 0..10) {
        val p = sclerpAt(i / 10.0).position
        moveTo(p.x * S, p.z * S); lineTo(0.0, 0.0)
    }
    color = vec(0.36, 0.84, 1.0, 0.22); thickness = 1.0
}
// …and from the lerped positions, which are visibly shorter through the middle.
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

// Every number in the captions is measured here, never asserted from memory.
val rMin = (0..n).minOf { sclerpAt(it.toDouble() / n).position.length() }
val rMax = (0..n).maxOf { sclerpAt(it.toDouble() / n).position.length() }
val worstAim = (0..n).maxOf { sclerpAt(it.toDouble() / n).target.length() }
val lerpMid = lerpAt(0.5).length()
val closer = (1.0 - lerpMid / rMax) * 100.0
println("sclerp distance to subject: %.4f .. %.4f".format(rMin, rMax))
println("straight-line midpoint:     %.4f  (%.1f%% nearer)".format(lerpMid, closer))
println("worst-case aiming error:    %.6f world units".format(worstAim))

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

