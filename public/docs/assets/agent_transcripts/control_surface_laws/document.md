# Field Document: control_surface_laws

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. a level you can see

<!-- field-box id="control_surface_meter" index="0" -->

### Narrative

From `code`

Two meters, both fed by a `source` lambda. Nothing here loops: the box says where the
number comes from, and a single shared daemon thread samples it at 30 Hz. The sampler is
retired when this box runs again without declaring the meter, so it does not outlive it.

A meter is an output. It is not cached across a rerun (there is nothing to preserve), it
posts nothing when you press it, and it is the one control here that reads rather than
writes.

```edn [field-box id="control_surface_meter" role="properties"]
{"id" "control_surface_meter" "index" 0 "name" "a level you can see" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "a level you can see" "_tombstone" false "frame" #afield/rect [80.0 520.0 320.0 105.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "control_surface_meter_code_.kt" "lines" 32 "characters" 1385 "sha256" "4accb0c4da5ef517213525672c50f834fcdac0b4a4b1e9fa0e27a0c85a76e47a"}]}
```

```kotlin [field-property box="control_surface_meter" name="code" path="control_surface_meter_code_.kt"]
/*
Two meters, both fed by a `source` lambda. Nothing here loops: the box says where the
number comes from, and a single shared daemon thread samples it at 30 Hz. The sampler is
retired when this box runs again without declaring the meter, so it does not outlive it.

A meter is an output. It is not cached across a rerun (there is nothing to preserve), it
posts nothing when you press it, and it is the one control here that reads rather than
writes.
*/
val start = System.nanoTime()
fun envelope(): Double {
    val seconds = (System.nanoTime() - start) / 1.0e9
    return 0.5 + 0.48 * Math.sin(seconds * 2.2)
}

controlGroup("levels") {
    meter("level", min = 0.0, max = 1.0, source = { envelope() })
    // The same signal on a logarithmic meter, which is how a level is actually read: the
    // interesting 20 dB near the top gets most of the bar.
    meter(
        "level, log",
        min = 0.001,
        max = 1.0,
        law = ControlLaw.LOGARITHMIC,
        source = { envelope().coerceAtLeast(0.001) },
    )
    // And a slider, so you can see the meter is not one: this posts, the meters do not.
    slider("trim", min = -24.0, max = 24.0, step = 0.1, initialValue = 0.0) {}
}

println("two meters, sampled at 30 Hz from a source lambda. Nothing in this box loops.")
println("the remote tablet polls at 450 ms, so there they are indicators rather than VU meters.")
```

## 2. a law for the travel

<!-- field-box id="control_surface_laws" index="1" -->

### Narrative

From `code`

Four sliders demonstrate logarithmic, linear, exponential and bipolar laws.
The cutoff pair spans three decades, 20–20000 Hz. Open the output and drag them.

The point is the pair `cutoff` / `cutoff, linear`: identical ranges, and 1 kHz sits at 57%
of the log track against 4.9% of the linear one. On the linear slider every note below A2
is inside the first pixel of travel, which is not a resolution problem — no number of
pixels fixes a 200 Hz jump per pixel — it is the wrong shape.

```edn [field-box id="control_surface_laws" role="properties"]
{"id" "control_surface_laws" "index" 1 "name" "a law for the travel" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "a law for the travel" "_tombstone" false "frame" #afield/rect [80.0 80.0 320.0 105.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "control_surface_laws_code_.kt" "lines" 53 "characters" 2318 "sha256" "3e85408e8f8c33aff076cf3b23ee955827912dc8d43093eddacd840d33575483"}]}
```

```kotlin [field-property box="control_surface_laws" name="code" path="control_surface_laws_code_.kt"]
/*
Four sliders demonstrate logarithmic, linear, exponential and bipolar laws.
The cutoff pair spans three decades, 20–20000 Hz. Open the output and drag them.

The point is the pair `cutoff` / `cutoff, linear`: identical ranges, and 1 kHz sits at 57%
of the log track against 4.9% of the linear one. On the linear slider every note below A2
is inside the first pixel of travel, which is not a resolution problem — no number of
pixels fixes a 200 Hz jump per pixel — it is the wrong shape.
*/
var cutoff = 1000.0
var attack = 0.05
var pan = 0.0
var linearCutoff = 1000.0

controlGroup("laws") {
    // Equal travel is equal ratio. Note the readout: 20.00 at the bottom and 20000 at the
    // top, from one control, because a nonlinear law formats by significant digits.
    slider("cutoff", min = 20.0, max = 20000.0, initialValue = cutoff, law = ControlLaw.LOGARITHMIC) {
        cutoff = it
    }
    slider("cutoff, linear", min = 20.0, max = 20000.0, initialValue = linearCutoff) {
        linearCutoff = it
    }
    // A taper: happy with a zero minimum, where a logarithmic law is not. Most of the track
    // is spent on the short attacks, which is where the ear is.
    slider("attack", min = 0.0, max = 2.0, initialValue = attack, law = ControlLaw.EXPONENTIAL, lawExponent = 3.0) {
        attack = it
    }
    // Two tapers about a centre, so dead centre is exactly mid-travel and you can land on it.
    slider("pan", min = -1.0, max = 1.0, initialValue = pan, law = ControlLaw.BIPOLAR) {
        pan = it
    }
}

// Where each law puts a handle, in percent of the track. The same numbers the browser uses.
val log = ControlLawSpec(ControlLaw.LOGARITHMIC, 20.0, 20000.0)
val linear = ControlLawSpec(ControlLaw.LINEAR, 20.0, 20000.0)
[20.0, 100.0, 440.0, 1000.0, 5000.0, 20000.0].forEach { hz ->
    println(
        "%8.1f Hz   log %5.1f%%   linear %5.1f%%"
            .format(
                hz,
                log.normalizedFor(hz) * 100,
                linear.normalizedFor(hz) * 100,
            ),
    )
}
println()
println("a LOGARITHMIC law over a range that starts at zero is refused, not repaired:")
println(
    runCatching { ControlLawSpec(ControlLaw.LOGARITHMIC, 0.0, 20000.0).validate("cutoff") }.exceptionOrNull()?.message
        ?: "(nothing was thrown — that would be the bug)",
)
```

## 3. hold, don't latch

<!-- field-box id="control_surface_button" index="2" -->

### Narrative

From `code`

A momentary button beside a checkbox, so the difference is visible rather than described.

Try this: press the button, drag the pointer well off it, and let go. It still releases.
The release is listened for on the *window* — `pointerup`, `pointercancel`, `touchend`,
`touchcancel` and `blur` — because a gate that never closes is the defect this control
class is famous for. Cmd-tab away mid-press and it releases then too.

Also: rerun this box while holding nothing. The checkbox keeps its state (that is the
value cache, and it is what live-coding wants). The button does not, on purpose — a
remembered press would re-fire on every rerun, so editing one character of a DSP would
retrigger the note.

```edn [field-box id="control_surface_button" role="properties"]
{"id" "control_surface_button" "index" 2 "name" "hold, don't latch" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "hold, don't latch" "_tombstone" false "frame" #afield/rect [80.0 300.0 320.0 105.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "control_surface_button_code_.kt" "lines" 27 "characters" 1151 "sha256" "7352dfd642d0d7f6fdef7188f40fd24b4798725681168c5d5c5e1beafb71d1c3"}]}
```

```kotlin [field-property box="control_surface_button" name="code" path="control_surface_button_code_.kt"]
/*
A momentary button beside a checkbox, so the difference is visible rather than described.

Try this: press the button, drag the pointer well off it, and let go. It still releases.
The release is listened for on the *window* — `pointerup`, `pointercancel`, `touchend`,
`touchcancel` and `blur` — because a gate that never closes is the defect this control
class is famous for. Cmd-tab away mid-press and it releases then too.

Also: rerun this box while holding nothing. The checkbox keeps its state (that is the
value cache, and it is what live-coding wants). The button does not, on purpose — a
remembered press would re-fire on every rerun, so editing one character of a DSP would
retrigger the note.
*/
val log = mutableListOf<String>()

controlGroup("gesture") {
    button("gate") { down ->
        log += if (down) "gate down" else "gate up"
        if (log.size > 6) log.removeAt(0)
        println(log.joinToString("  "))
    }
    checkbox("bypass", initialValue = false) { on ->
        println("bypass ${if (on) "on" else "off"}")
    }
}

println("press the button: two callbacks, down then up. tick the checkbox: one, and it sticks.")
```

