# Field Document: oscquery_demo

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 6 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 4. Mirror as sliders (run)

<!-- field-box id="oscq-mirror" index="0" -->

```edn [field-box id="oscq-mirror" role="properties"]
{"id" "oscq-mirror" "index" 0 "name" "4. Mirror as sliders (run)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "4. Mirror as sliders (run)" "_tombstone" false "frame" #afield/rect [60.0 220.0 380.0 70.0] "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "oscq-mirror_code_.kt" "lines" 6 "characters" 340 "sha256" "54efd71725e68a51fd2300804f2bf18f1796865f3c40d2fbd533e39409508e33"}]}
```

```kotlin [field-property box="oscq-mirror" name="code" path="oscq-mirror_code_.kt"]
import afield.io.osc.query.*

// A slider or checkbox for every number and boolean the remote publishes. Moving one sets the
// remote; when the remote moves (box 5 animates /demo/lfo), the slider follows over LISTEN.
val remote = oscQueryRemote("127.0.0.1", 9020).refresh()
oscQueryMirror(remote, "mirror") { it.path.startsWith("/demo") }
```

## 2. 2. Field controls, published (run)

<!-- field-box id="oscq-controls" index="1" -->

```edn [field-box id="oscq-controls" role="properties"]
{"id" "oscq-controls" "index" 1 "name" "2. Field controls, published (run)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2. Field controls, published (run)" "_tombstone" false "frame" #afield/rect [520.0 60.0 380.0 70.0] "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "oscq-controls_code_.kt" "lines" 10 "characters" 398 "sha256" "52afc59d4aeca62525e63032ee120f3b0025b1f2c5fe8ec4caa0c722a229b260"}]}
```

```kotlin [field-property box="oscq-controls" name="code" path="oscq-controls_code_.kt"]
import afield.io.osc.query.*

// Field controls that are also OSCQuery parameters, under /surface. Drag them here and every
// listening remote sees the change; set them from a remote and the Field control moves.
var gain = 0.5
var mute = false
oscQuery("Field demo").controls("/surface") {
    slider("gain", 0.0, 1.0, initialValue = gain) { gain = it }
    checkbox("mute", mute) { mute = it }
}
```

## 3. 3. Read it back as a remote (run)

<!-- field-box id="oscq-remote" index="2" -->

```edn [field-box id="oscq-remote" role="properties"]
{"id" "oscq-remote" "index" 2 "name" "3. Read it back as a remote (run)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3. Read it back as a remote (run)" "_tombstone" false "frame" #afield/rect [980.0 60.0 380.0 70.0] "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "oscq-remote_code_.kt" "lines" 12 "characters" 611 "sha256" "14384a8abec77e3192e8ba3bd81bece52721943b161713259483307ff0c66594"}]}
```

```kotlin [field-property box="oscq-remote" name="code" path="oscq-remote_code_.kt"]
import afield.io.osc.query.*

// The other direction: read an OSCQuery service as a remote. Here it is this Field's own,
// so run box 1 first; point it at TouchDesigner or Chataigne by changing the host and port.
val remote = oscQueryRemote("127.0.0.1", 9020).refresh()
remote["/demo/cutoff"].set(2500.0) // sent as OSC over UDP to the service's OSC_PORT
remote["/demo/scene"].set("night")

markdown(
    "**${remote.name}** — OSC on port ${remote.oscPort}, LISTEN: ${remote.supportsListen}\n\n" +
        remote.params.joinToString("\n") { "- `${it.path}` ${it.type} ${it.range} ${it.description ?: ""}" },
)
```

## 4. 5. Animate /demo/lfo (launch)

<!-- field-box id="oscq-lfo" index="3" -->

```edn [field-box id="oscq-lfo" role="properties"]
{"id" "oscq-lfo" "index" 3 "name" "5. Animate /demo/lfo (launch)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "5. Animate /demo/lfo (launch)" "_tombstone" false "frame" #afield/rect [520.0 220.0 380.0 70.0] "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "oscq-lfo_code_.kt" "lines" 10 "characters" 389 "sha256" "9479e78dfea604c084a2a4fced0dd80c343aa02f858e0b7a0354113e360cf719"}]}
```

```kotlin [field-property box="oscq-lfo" name="code" path="oscq-lfo_code_.kt"]
import afield.io.osc.query.*

// A value Field changes by itself: every listener (box 4's slider, TouchDesigner) follows it.
val q = oscQuery("Field demo")
val lfo = q.float("/demo/lfo", 0.0..1.0, 0.5, "a slow sine, written by Field", access = OscAccess.READ)
val start = AnimationClock.nowSeconds()
loop {
    lfo.set(0.5 + 0.5 * sin(AnimationClock.nowSeconds() - start))
    delay(50)
}
```

## 5. 1. Publish parameters (run first)

<!-- field-box id="oscq-publish" index="4" -->

```edn [field-box id="oscq-publish" role="properties"]
{"id" "oscq-publish" "index" 4 "name" "1. Publish parameters (run first)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1. Publish parameters (run first)" "_tombstone" false "frame" #afield/rect [60.0 60.0 380.0 70.0] "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "oscq-publish_code_.kt" "lines" 24 "characters" 983 "sha256" "739df92b719db1fb79b378cd510f925944e24153e46c19c2bc43a8909a16e2f0"}]}
```

```kotlin [field-property box="oscq-publish" name="code" path="oscq-publish_code_.kt"]
import afield.io.osc.query.*

/*
Publish four parameters as an OSCQuery service on http port 9020 (OSC UDP on 9021),
advertised over DNS-SD so TouchDesigner, Max, Chataigne and VDMX list it as "Field demo".
The callbacks run here, on Field's main loop, whenever one of them sets a value.
*/
val q = oscQuery("Field demo")
q.describe("/demo", "parameters published by the OSCQuery demo document")

fun show() = markdown(
    "values",
    q.server.all.values
        .sortedBy { it.path }
        .joinToString("\n") { "- `${it.path}` (${it.type}) = **${it.value.joinToString(", ")}**" },
)

q.float("/demo/cutoff", 20.0..20000.0, 1000.0, "filter cutoff in Hz") { show() }
q.bool("/demo/on", true, "sound on") { show() }
q.menu("/demo/scene", ["dawn", "dusk", "night"], "dusk") { show() }
q.vector("/demo/xy", [0.5, 0.5], description = "a point on a pad") { show() }

show()
markdown("Browse **http://localhost:${q.httpPort}/** for the namespace, or **?HOST_INFO** for the service.")
```

## 6. 6. Discover on the network (run)

<!-- field-box id="oscq-discover" index="5" -->

```edn [field-box id="oscq-discover" role="properties"]
{"id" "oscq-discover" "index" 5 "name" "6. Discover on the network (run)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "6. Discover on the network (run)" "_tombstone" false "frame" #afield/rect [980.0 220.0 380.0 70.0] "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "oscq-discover_code_.kt" "lines" 9 "characters" 414 "sha256" "7cf351987021563789981564c0f56077dd817a42be936c5cf27d8336f0385391"}]}
```

```kotlin [field-property box="oscq-discover" name="code" path="oscq-discover_code_.kt"]
import afield.io.osc.query.*

// OSCQuery services announcing themselves on this network (DNS-SD _oscjson._tcp), this Field's
// included once box 1 has run. Each one can be opened with oscQueryRemote(address, port).
val found = oscQueryDiscover(2000)
markdown(
    if (found.isEmpty()) "No OSCQuery services answered."
    else found.joinToString("\n") { "- **${it.instance}** at `${it.address}:${it.port}`" },
)
```

