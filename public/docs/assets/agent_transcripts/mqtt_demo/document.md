# Field Document: mqtt_demo

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 4 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 3. Plot the recent values (launch)

<!-- field-box id="mqtt-plot" index="0" -->

```edn [field-box id="mqtt-plot" role="properties"]
{"id" "mqtt-plot" "index" 0 "name" "3. Plot the recent values (launch)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3. Plot the recent values (launch)" "_tombstone" false "frame" #afield/rect [60.0 220.0 380.0 70.0] "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "mqtt-plot_code_.kt" "lines" 22 "characters" 747 "sha256" "ed3fa8cfcdec2ea42fb9c60079b932867ba42b6c1b3a105689f18af886fe3a3e"}]}
```

```kotlin [field-property box="mqtt-plot" name="code" path="mqtt-plot_code_.kt"]
import afield.io.mqtt.*

// Keep the latest value and recent history of everything under sensors/, and plot it twice a second.
// `series` holds the last 512 numbers per topic; `number(topic, "temp")` reads a field out of JSON.
mqtt.track("sensors/#")
loop {
    showSignals(
        "kitchen" to mqtt.series("sensors/kitchen/temp"),
        "hall" to mqtt.series("sensors/hall/temp"),
        "garden" to mqtt.series("sensors/garden/env", "temp"),
        title = "Temperatures (°C)",
    )
    markdown(
        "info",
        "garden: %.1f °C, %.0f %% humidity"
            .format(
                mqtt.number("sensors/garden/env", "temp"),
                mqtt.number("sensors/garden/env", "humidity"),
            ),
    )
    delay(500)
}
```

## 2. 4. Retained scene message (run)

<!-- field-box id="mqtt-retained" index="1" -->

```edn [field-box id="mqtt-retained" role="properties"]
{"id" "mqtt-retained" "index" 1 "name" "4. Retained scene message (run)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "4. Retained scene message (run)" "_tombstone" false "frame" #afield/rect [980.0 60.0 380.0 70.0] "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "mqtt-retained_code_.kt" "lines" 12 "characters" 490 "sha256" "b289a098f969ee7bfb7383d582f708e13381930d3607f234e6de9b010070ff88"}]}
```

```kotlin [field-property box="mqtt-retained" name="code" path="mqtt-retained_code_.kt"]
import afield.io.mqtt.*

// A retained message stays on the broker: anything that subscribes later gets it straight away.
// This is how a device learns the current scene when it boots.
mqtt.publish("installation/scene", mapOf("name" to "dusk", "brightness" to 0.4), qos = 1, retain = true)

mqtt.subscribe("installation/#") { topic, message ->
    markdown(
        "`$topic`: **${message.string("name")}** at ${message.number("brightness")} (retained: ${message.retained})",
    )
}
mqtt
```

## 3. 2. Listen: sensors/+/temp (run)

<!-- field-box id="mqtt-listen" index="2" -->

```edn [field-box id="mqtt-listen" role="properties"]
{"id" "mqtt-listen" "index" 2 "name" "2. Listen: sensors/+/temp (run)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2. Listen: sensors/+/temp (run)" "_tombstone" false "frame" #afield/rect [520.0 60.0 380.0 70.0] "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "mqtt-listen_code_.kt" "lines" 12 "characters" 432 "sha256" "8e27e688afeae9b29e1470d6994fd7f35156c2a53325158515ac4c503ec7fcbd"}]}
```

```kotlin [field-property box="mqtt-listen" name="code" path="mqtt-listen_code_.kt"]
import afield.io.mqtt.*

/*
Every temperature, whichever room it comes from: `+` stands for one level of the topic.
The callback runs on Field's main loop, so it can update outputs directly.
Re-running this box replaces the callback rather than adding a second one.
*/
var count = 0
mqtt.subscribe("sensors/+/temp") { topic, message ->
    count++
    markdown("**$topic** = ${"%.2f".format(message.number)}  _(message $count)_")
}
```

## 4. 1. Pretend sensors (launch first)

<!-- field-box id="mqtt-sensors" index="3" -->

```edn [field-box id="mqtt-sensors" role="properties"]
{"id" "mqtt-sensors" "index" 3 "name" "1. Pretend sensors (launch first)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1. Pretend sensors (launch first)" "_tombstone" false "frame" #afield/rect [60.0 60.0 380.0 70.0] "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "mqtt-sensors_code_.kt" "lines" 15 "characters" 643 "sha256" "0474e4d28d9b973528ccd08047ab05fd6333a821e5095acfac212f67ec421d15"}]}
```

```kotlin [field-property box="mqtt-sensors" name="code" path="mqtt-sensors_code_.kt"]
import afield.io.mqtt.*

/*
Three pretend sensors publishing to the embedded broker ten times a second.
The kitchen and hall send plain numbers; the garden sends JSON, as many ESP32 sketches do.
Point real devices at this machine's broker instead and delete this box.
*/
val start = AnimationClock.nowSeconds()
loop {
    val t = AnimationClock.nowSeconds() - start
    mqtt.publish("sensors/kitchen/temp", 21.0 + sin(t * 0.7) * 1.5)
    mqtt.publish("sensors/hall/temp", 18.0 + sin(t * 0.3 + 1.0) * 2.0)
    mqtt.publish("sensors/garden/env", mapOf("temp" to 12.0 + sin(t * 0.2) * 4.0, "humidity" to 60 + 20 * cos(t * 0.5)))
    delay(100)
}
```

