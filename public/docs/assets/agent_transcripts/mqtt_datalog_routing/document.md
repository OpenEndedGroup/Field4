# Field Document: mqtt_datalog_routing

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["contents" "frame" "name" "code" "code_language" "box_kind" "textFrame" "textRunAnchor" "textRunSources" "textFrameReview" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "parentHint" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "watchdog" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "ai4_garden" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 1 · Derive and draw the routes

<!-- field-box id="mqtt_route_model" index="0" -->

```edn [field-box id="mqtt_route_model" role="properties"]
{"id" "mqtt_route_model" "index" 0 "name" "1 · Derive and draw the routes" "parents" ["root"] "children" ["mqtt_route_source"] "properties" {"children" #{#afield/box "mqtt_route_source"} "parents" #{#afield/box "root"} "name" "1 · Derive and draw the routes" "_tombstone" false "frame" #afield/rect [590.0 80.0 330.0 90.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "mqtt_route_model_code_.kt" "lines" 71 "characters" 2582 "sha256" "875f87e3f9e2654841d46b28ba1c3e15c52d0c6319e31fc364df5e731388d61a"}]}
```

```kotlin [field-property box="mqtt_route_model" name="code" path="mqtt_route_model_code_.kt"]
import afield.io.mqtt.mqtt
import java.util.UUID

prop<String>(routingTopic)
routingTopic = "field/routing/${UUID.randomUUID()}"

val direct = "direct" to ("console" to "screen")
val send = "send" to ("console" to "relay")
val forward = "forward" to ("relay" to "screen")
val links = [direct, send, forward]
val positions = mapOf("console" to vec(30, 55, 0), "relay" to vec(210, 165, 0), "screen" to vec(390, 55, 0))
val teal = vec(0.23, 0.83, 0.74, 1.0)
val grey = vec(0.35, 0.4, 0.47, 1.0)

val model = Datalog()
model.add(
    """
    reach(A, B) :- link(A, B).
    reach(A, C) :- reach(A, B), link(B, C).
    """,
)

fun showSnapshot(active: Set<String>) {
    val routes = model.rowsOf("reach")
    val proof = model.why("reach(console, screen)")
    val reachable = routes.filter { it[0] == "console" }.map { it[1] }.toSet() + "console"
    val diagram = buildList {
        links.forEach { (name, endpoints) ->
            val (from, to) = endpoints
            add(
                fline {
                    moveTo(positions.getValue(from))
                    lineTo(positions.getValue(to))
                    color = if (name in active) teal else grey
                    thickness = if (name in active) 4.0 else 1.0
                },
            )
        }
        positions.forEach { (name, at) ->
            add(
                fline {
                    circle(at.x.toDouble(), at.y.toDouble(), 9.0)
                    filled = true
                    color = if (name in reachable) teal else grey
                },
            )
        }
    }
    lines(diagram, w = 480.0, h = 170.0, fitToSize = true)
    val status = if (proof == null) "Screen unreachable" else "Screen reachable"
    val detail = proof?.render() ?: "No live route from the console."
    markdown("route", "### $status\n\nConsole (left), relay (bottom), screen (right).\n\n~~~\n$detail\n~~~")
    println(status)
}

markdown("route", "Waiting for a routing snapshot.")
var hasSnapshot = false
mqtt.subscribe(routingTopic, key = "routing") { _, message ->
    val flags = links.mapNotNull { (name, _) ->
        message.number(name)?.takeIf { it == 0.0 || it == 1.0 }?.let { name to it }
    }
    if (flags.size == links.size) {
        val active = flags.filter { it.second == 1.0 }.map { it.first }.toSet()
        val changed = model.replaceFacts(
            "link",
            links.filter { it.first in active }.map { (_, endpoints) -> [endpoints.first, endpoints.second] },
        )
        if (!hasSnapshot || changed) showSnapshot(active)
        hasSnapshot = true
    }
}
```

## 2. 2 · Switch the connections

<!-- field-box id="mqtt_route_source" index="1" -->

```edn [field-box id="mqtt_route_source" role="properties"]
{"id" "mqtt_route_source" "index" 1 "name" "2 · Switch the connections" "parents" ["root" "mqtt_route_model"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "mqtt_route_model"} "name" "2 · Switch the connections" "_tombstone" false "frame" #afield/rect [80.0 80.0 330.0 90.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "mqtt_route_source_code_.kt" "lines" 24 "characters" 546 "sha256" "791634b1037cf6df5c86740303aa32a96ac32f4958db4c6df6fcaa6910d0a548"}]}
```

```kotlin [field-property box="mqtt_route_source" name="code" path="mqtt_route_source_code_.kt"]
import afield.io.mqtt.mqtt

prop<String>(routingTopic)
var direct = false
var send = true
var forward = true

controlGroup("Links") {
    checkbox("Direct: console to screen", direct) { direct = it }
    checkbox("Console to relay", send) { send = it }
    checkbox("Relay to screen", forward) { forward = it }
}

loop {
    mqtt.publish(
        routingTopic,
        mapOf(
            "direct" to if (direct) 1 else 0,
            "send" to if (send) 1 else 0,
            "forward" to if (forward) 1 else 0,
        ),
    )
    delay(400)
}
```

