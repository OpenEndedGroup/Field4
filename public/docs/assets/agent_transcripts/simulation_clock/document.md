# Field Document: simulation_clock

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 1. Simulation — launch / stop

<!-- field-box id="simulation_owner" index="0" -->

```edn [field-box id="simulation_owner" role="properties"]
{"id" "simulation_owner" "index" 0 "name" "1. Simulation — launch / stop" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1. Simulation — launch / stop" "_tombstone" false "frame" #afield/rect [80.0 100.0 360.0 115.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "simulation_owner_code_.kt" "lines" 81 "characters" 3392 "sha256" "a6fabab7f9976cf7acd7fff91d3a1593528d12d2799d75faf9fc2aabdaea3c9b"}]}
```

```kotlin [field-property box="simulation_owner" name="code" path="simulation_owner_code_.kt"]
import kotlinx.coroutines.*

// Use the ordinary box launch/stop controls. No simulation service is left running.
// Exported for the replay assertion after Stop has cleaned up both children.
prop<Int>(simulationCleanup)
val runId = System.nanoTime()
var phase = 0.0
var pulse = 0.0
var checksum = 0.0
var childrenStopped = 0
val reuse = true
var points = Array(2048) { vec(0f, 0f, 0f) }
frames {
    val budget = frameProfile("Playground", targetFps = 60.0, allocationKiB = 128.0)
    try {
        withSimulationClock {
            // Both waits use simulation time: sleep takes seconds, delay takes milliseconds.
            launch(CoroutineName("Orbit")) {
                try {
                    while (isActive) {
                        phase += 0.045
                        sleep(stepSeconds)
                    }
                } finally {
                    childrenStopped++
                }
            }
            launch(CoroutineName("Pulse")) {
                try {
                    while (isActive) {
                        pulse = 1.0
                        delay(120)
                        pulse = 0.0
                        delay(350)
                    }
                } finally {
                    childrenStopped++
                }
            }
            runCurrent()
            var last = AnimationClock.nowSeconds()
            while (frame()) {
                budget.beginFrame()
                val now = AnimationClock.nowSeconds()
                budget.measure("Simulation") { advance((now - last).coerceAtLeast(0.0)) }
                last = now
                // Profiling workload: compare updating existing vectors with allocating new ones.
                // The sine loop adds deliberate CPU work so the budget is easy to inspect.
                budget.measure("Vectors") {
                    if (reuse) for (i in points.indices) points[i].set(i.toFloat(), phase.toFloat(), 0f)
                    else points = Array(points.size) { vec(it.toFloat(), phase.toFloat(), 0f) }
                    repeat(12000) { checksum += kotlin.math.sin(it.toDouble()) }
                }
                val x = 200 + 110 * kotlin.math.cos(phase)
                val y = 125 + 70 * kotlin.math.sin(phase)
                display(
                    "main",
                    """
                    <svg id="simulation-clock-view" data-run="$runId" data-time="$time"
                         width="420" height="270" viewBox="0 0 420 270" style="background:black">
                      <ellipse cx="200" cy="125" rx="110" ry="70" fill="none" stroke="#444"/>
                      <circle cx="$x" cy="$y" r="12" fill="#48c9ff"/>
                      <circle cx="200" cy="125" r="${8+18*pulse}" fill="#ddd"/>
                      <text x="20" y="250" fill="#aaa" font-family="Iosevka,monospace">
                        simulation ${"%.2f".format(time)} s · ${pendingTimers} timers
                      </text>
                    </svg>
                    """,
                )
            }
        }
    } finally {
        budget.close()
        display(
            "status",
            """<div id="simulation-clock-status" data-run="$runId">$childrenStopped children cleaned up</div>""",
        )
        simulationCleanup = childrenStopped
        println("SIMULATION_STOPPED: $childrenStopped children cleaned up")
    }
}
```

## 2. 2. Record and explore

<!-- field-box id="simulation_capture" index="1" -->

```edn [field-box id="simulation_capture" role="properties"]
{"id" "simulation_capture" "index" 1 "name" "2. Record and explore" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2. Record and explore" "_tombstone" false "frame" #afield/rect [550.0 100.0 360.0 115.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "simulation_capture_code_.kt" "lines" 3 "characters" 280 "sha256" "cd09ee4e36f84408134ebf5043a37f66a80653af6e505d8dea18186a16a63643"}]}
```

```kotlin [field-property box="simulation_capture" name="code" path="simulation_capture_code_.kt"]
CoroutineProfiles.request(thisBox.inside.filename, CoroutineProfileRequest("start", 3.0, stacks = true))
thisBox.inBrowser("window.showProfilingTimeline(); document.getElementById('profile-tab-frames').click()")
println("Recording frames, scopes and named simulation coroutines")
```

