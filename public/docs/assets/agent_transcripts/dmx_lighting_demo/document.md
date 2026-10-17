# Field Document: dmx_lighting_demo

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 4 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 4. sACN: an image on an RGBW matrix (launch)

<!-- field-box id="dmx-sacn-matrix" index="0" -->

```edn [field-box id="dmx-sacn-matrix" role="properties"]
{"id" "dmx-sacn-matrix" "index" 0 "name" "4. sACN: an image on an RGBW matrix (launch)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "4. sACN: an image on an RGBW matrix (launch)" "_tombstone" false "frame" #afield/rect [1200.0 60.0 380.0 70.0] "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "dmx-sacn-matrix_code_.kt" "lines" 35 "characters" 1414 "sha256" "20528880aa64a679daa52484bc498fd454107fc480c43f84ca8d098aada3439f"}]}
```

```kotlin [field-property box="dmx-sacn-matrix" name="code" path="dmx-sacn-matrix_code_.kt"]
import afield.io.dmx.*

// sACN (E1.31) out and back in, in one box: an image shown on a 16 x 8 matrix of RGBW fixtures.
// 128 RGBW fixtures x 4 slots fill universe 10 exactly; the matrix is wired serpentine, as most are.
val patch = Patch(startUniverse = 10)
patch.grid(16, 8, spacing = 10.0, profile = FixtureProfile.RGBW, serpentine = true)

// Any Img will do (imgFromFile("...") for a file); here a small gradient made in place.
val img = Img(64, 32, 3, FloatArray(64 * 32 * 3) { i ->
    val x = (i / 3) % 64 / 63f
    val y = (i / 3) / 64 / 31f
    when (i % 3) {
        0 -> x
        1 -> y
        else -> 1f - x
    }
})

val out = sacnOut("127.0.0.1")   // host = null sends to multicast 239.255.0.10 instead
val node = sacnIn()              // Field as an sACN receiver on UDP 5568

// On the sender's clock: sample the image, then sweep a wave of brightness across it.
patch.animate(out) { t ->
    mapImage(img)
    fixtures.forEach { it.dimmer = 0.55 + 0.45 * sin(t * 3.0 - it.position.x * 0.05) }
}

// What we patched, and universe 10 as it came back over the network, decoded as RGBW fixtures in
// wire order — every other row runs backwards, because the matrix is wired serpentine.
loop {
    if (frameNumber % 6 == 0) {
        showPatch(patch, channel = "matrix", w = 480.0, h = 260.0)
        showUniverse(node, 10, profile = FixtureProfile.RGBW, cols = 16, w = 480.0, h = 260.0)
    }
}
```

## 2. 3. ArtPoll: who is out there (run)

<!-- field-box id="dmx-artpoll" index="1" -->

```edn [field-box id="dmx-artpoll" role="properties"]
{"id" "dmx-artpoll" "index" 1 "name" "3. ArtPoll: who is out there (run)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3. ArtPoll: who is out there (run)" "_tombstone" false "frame" #afield/rect [580.0 520.0 380.0 70.0] "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "dmx-artpoll_code_.kt" "lines" 5 "characters" 255 "sha256" "f3bd791f443b5a7663b2a88144e9710c188defb46d9ec2a5323583c7876023a1"}]}
```

```kotlin [field-property box="dmx-artpoll" name="code" path="dmx-artpoll_code_.kt"]
import afield.io.dmx.*

// Art-Net discovery: send one ArtPoll and list every node that answers within a second.
// With the receiver box running, Field itself answers. Try "255.255.255.255" on a real network.
artPoll("127.0.0.1").forEach { println(it) }
```

## 3. 1. Art-Net receiver: Field as a node (launch first)

<!-- field-box id="dmx-receiver" index="2" -->

```edn [field-box id="dmx-receiver" role="properties"]
{"id" "dmx-receiver" "index" 2 "name" "1. Art-Net receiver: Field as a node (launch first)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1. Art-Net receiver: Field as a node (launch first)" "_tombstone" false "frame" #afield/rect [60.0 60.0 380.0 70.0] "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "dmx-receiver_code_.kt" "lines" 16 "characters" 505 "sha256" "d56f2ed8595b422b59bad16da9c00e015baf26a4d1dc7da06968a4e5817da639"}]}
```

```kotlin [field-property box="dmx-receiver" name="code" path="dmx-receiver_code_.kt"]
import afield.io.dmx.*

// Field as an Art-Net node: listen on UDP 6454, merge what arrives (HTP), answer ArtPoll.
// Launch this first, then launch the sender. Stopping this box closes the socket.
val input = artnetIn()

// Redraw the two received universes as RGB cells whenever new data has arrived.
var seen = -1L
loop {
    val v = input.version(1) + input.version(2)
    if (v != seen && frameNumber % 4 == 0) {
        seen = v
        showUniverse(input, 1)
        showUniverse(input, 2)
    }
}
```

## 4. 2. Pixel mapper: 200 px on a curve (launch)

<!-- field-box id="dmx-sender" index="3" -->

```edn [field-box id="dmx-sender" role="properties"]
{"id" "dmx-sender" "index" 3 "name" "2. Pixel mapper: 200 px on a curve (launch)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2. Pixel mapper: 200 px on a curve (launch)" "_tombstone" false "frame" #afield/rect [580.0 60.0 380.0 70.0] "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "dmx-sender_code_.kt" "lines" 24 "characters" 867 "sha256" "14c11139b6150fac4acf541a8af3d354d4247685979770797a2fd25587d94011"}]}
```

```kotlin [field-property box="dmx-sender" name="code" path="dmx-sender_code_.kt"]
import afield.io.dmx.*

// 200 RGB pixels of LED tape stuck along a curve. 170 fit in universe 1 (510 slots);
// the patch rolls the other 30 into universe 2 by itself.
val tape = FLine().moveTo(0, 0).cubicTo(x0 = 150, y0 = -160, x1 = 250, y1 = 160, x = 400, y = 0)
val patch = Patch(startUniverse = 1)
patch.stripAlong(tape, 200)

// Art-Net to this machine; use a node's IP (or a broadcast address) to drive real lights.
val out = artnetOut("127.0.0.1")

// A colour field over (x, y, t), evaluated on the DMX sender's own 44 Hz clock.
patch.animate(out) { t ->
    map(t) { x, y, time ->
        vec(0.5 + 0.5 * sin(x * 0.03 - time * 2.0),
            0.5 + 0.5 * sin(x * 0.03 - time * 2.0 + 2.1),
            0.5 + 0.5 * sin(y * 0.03 + time))
    }
}

// Draw the virtual fixtures in their current colours.
loop {
    if (frameNumber % 4 == 0) showPatch(patch)
}
```

