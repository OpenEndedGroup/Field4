# Field Document: chain_scene_to_light

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_session" "fileManagerAccessHistory"]}
```

## 1. 3. Measure: round trip and aliasing (run)

<!-- field-box id="rig_measure" index="0" -->

```edn [field-box id="rig_measure" role="properties"]
{"id" "rig_measure" "index" 0 "name" "3. Measure: round trip and aliasing (run)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3. Measure: round trip and aliasing (run)" "_tombstone" false "frame" #afield/rect [60.0 560.0 380.0 70.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rig_measure_code_.kt" "lines" 11 "characters" 666 "sha256" "1ffaff1f3df7ae5c2c0c78b5230f510890f202043aeac00643e6e7a7560cd97e"}]}
```

```kotlin [field-property box="rig_measure" name="code" path="rig_measure_code_.kt"]
import afield.dmx.*

// The page's measurements, on this machine: nine frames sent and received back on a free port the
// system picks (so this can run beside boxes 1 and 2), and a thin line swept across the rig,
// sampled two ways.
val trip = SceneOnARig.roundTrip()
println(trip)

val (point, area) = SceneOnARig.aliasing()
println("point samples: dark in ${point.darkFrames} of ${point.total.size} frames, light on the rig ${"%.2f".format(point.min)}..${"%.2f".format(point.max)}")
println("area average:  dark in ${area.darkFrames} frames, light on the rig ${"%.3f".format(area.min)}..${"%.3f".format(area.max)} (the line is worth ${SceneOnARig.LINE_TRUTH})")
```

## 2. 2. Receiver: the rig lit by what arrived (launch)

<!-- field-box id="rig_receiver" index="1" -->

```edn [field-box id="rig_receiver" role="properties"]
{"id" "rig_receiver" "index" 1 "name" "2. Receiver: the rig lit by what arrived (launch)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2. Receiver: the rig lit by what arrived (launch)" "_tombstone" false "frame" #afield/rect [1120.0 60.0 380.0 70.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rig_receiver_code_.kt" "lines" 15 "characters" 634 "sha256" "264d3814c5bc396336f6d18414f18b119e1272fd1eef80478da74c1abceda3aa"}]}
```

```kotlin [field-property box="rig_receiver" name="code" path="rig_receiver_code_.kt"]
import afield.dmx.*

// Field as an sACN node on the sender's port (35690, fixed so the two boxes agree; change both
// together). It knows where the fixtures are (the same layout),
// and nothing else: every colour drawn here was decoded from the universes that arrived.
val node = sacnIn(port = 35690)
val layout = SceneOnARig.Rig()

// Redrawn ten times a second: the rig lit by what arrived, then universe 1 in wire order
// (128 RGB pixels, then the unused slots).
loop {
    showReceived(layout.patch, node, channel = "received", w = 480.0, h = 300.0)
    showUniverse(node, 1, cols = 16, w = 480.0, h = 300.0)
    delay(100)
}
```

## 3. 1. Scene on the rig: RectSL to sACN (launch)

<!-- field-box id="scene_sender" index="2" -->

```edn [field-box id="scene_sender" role="properties"]
{"id" "scene_sender" "index" 2 "name" "1. Scene on the rig: RectSL to sACN (launch)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1. Scene on the rig: RectSL to sACN (launch)" "_tombstone" false "frame" #afield/rect [60.0 60.0 380.0 70.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "scene_sender_code_.kt" "lines" 52 "characters" 1535 "sha256" "7195d09ca2bbc4bd89afb41e12a287fc8b2234351828da7bebbd1640613e98cc"}]}
```

```kotlin [field-property box="scene_sender" name="code" path="scene_sender_code_.kt"]
import afield.dmx.*
import afield.rectsl.*

// A RectSL scene, drawn on the CPU, on a light rig. Edit the scene and launch again: the receiver
// box shows what arrives. (Try the line's width, 0.25 ->wh below, or area = false further down.)
val scene = RectSLImager("""
#rectsl
->wall 16 8 abstract
->background 0x0c1024
->duration 4
->fps 30

"sun" rect
  4 4 ->wh
  0xff7a1a solid ->fill
  '( 0=-5  2=5  4=-5 ) ->x
  '( 0=0  4=3.14159 ) ->rot
;

"band" rect
  16 1.5 ->wh
  0 -3.25 ->xy
  0x2a9df4 solid ->fill
  '( 0=0.15  2=1  4=0.15 ) ->alpha
;

"sweep" rect
  0.25 8 ->wh
  0xffffff solid ->fill
  '( 0=-8  4=8 ) linear ->x
;
""")

// 16 x 8 RGB pixels in universe 1 and four RGBWA washes in universe 2: one fixture per wall unit.
val rig = SceneOnARig.Rig()

// sACN, unicast to this machine. The port is fixed at 35690 on purpose, so that box 2 can listen on
// the same number without the two boxes having to find each other; change both if it is taken.
val out = sacnOut("127.0.0.1", port = 35690)

// On the sender's own 44 Hz clock: draw the scene at t, shrink it to one pixel per fixture
// (area averaging), colour the fixtures, send. area = false samples the full frame at each
// fixture instead, and the thin line flickers as it crosses the rig.
rig.patch.animate(out) { t ->
    rig.show(scene.frame(t % scene.duration, 256, 128), area = true)
}

// The look that is being sent, in the rig's layout, redrawn ten times a second.
loop {
    showPatch(rig.patch, channel = "sent", w = 480.0, h = 300.0)
    delay(100)
}
```

