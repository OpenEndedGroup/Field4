# Field Document: motion_closd_live

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 1 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. CLoSD — intention and interference

<!-- field-box id="motion_closd_live" index="0" -->

```edn [field-box id="motion_closd_live" role="properties"]
{"id" "motion_closd_live" "index" 0 "name" "CLoSD — intention and interference" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "CLoSD — intention and interference" "_tombstone" false "frame" #afield/rect [80.0 80.0 600.0 110.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "motion_closd_live_code_.kt" "lines" 146 "characters" 8170 "sha256" "068cbd2af730cbd58ffb29eca6de7e18dc980d5279244b0e8f4f42352b3d6913"}]}
```

```kotlin [field-property box="motion_closd_live" name="code" path="motion_closd_live_code_.kt"]
import afield.animation.mocap.CLoSD
import afield.animation.mocap.MotionAttachment
import afield.animation.mocap.MotionProcessConfig
import afield.animation.mocap.MotionSession
import afield.animation.mocap.MotionUpdate
import afield.animation.mocap.showMotion
import java.nio.file.Files
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

val actions = ArrayBlockingQueue<String>(8)
val prompt = AtomicReference("A person walks forward.")
val bodyName = AtomicReference("L_Ankle")
val stiffness = AtomicReference(150.0)
val damping = AtomicReference(15.0)
val cap = AtomicReference(150.0)
val breakable = AtomicBoolean(false)
val running = AtomicBoolean(true)
val anchorSpeed = AtomicReference(0.0)
val notice = AtomicReference("Opening CLoSD…")
fun action(name: String, pressed: Boolean) { if (pressed && !actions.offer(name)) notice.set("Controls queue full; retry the action.") }

controlGroup(columns=2) {
    stringInput("Intention", initialValue=prompt.get()) { prompt.set(it) }
    button("Apply intention") { action("prompt", it) }
    stringInput("Rigid body", initialValue=bodyName.get()) { bodyName.set(it) }
    slider("Stiffness N/m", 0.0, 3000.0, initialValue=150.0) { stiffness.set(it) }
    slider("Damping N s/m", 0.0, 150.0, initialValue=15.0) { damping.set(it) }
    slider("Force cap N", 1.0, 800.0, initialValue=150.0) { cap.set(it) }
    checkbox("Break after demanded 30 N persists for 0.1 s", initialValue=false) { breakable.set(it) }
    button("Attach at current body position") { action("attach", it) }
    slider("Moving anchor +X m/s", -0.5, 0.5, initialValue=0.0) { anchorSpeed.set(it) }
    button("Move anchor +X 0.1 m") { action("move", it) }
    button("Update tether parameters") { action("update", it) }
    button("Release") { action("detach", it) }
    button("Torso impulse +X, 10 N s") { action("impulse", it) }
    button("Reset character") { action("reset", it) }
    checkbox("Advance simulation", initialValue=true) { running.set(it) }
}

frames {
    val opening = CLoSD.openAsync(MotionProcessConfig()) // Set FIELD_MOTION_HOME before launching Field.
    var session: MotionSession? = null
    try {
        while (!opening.isDone) {
            markdown("status", "Starting the optional CLoSD worker…")
            delay(16)
        }
        val worker = opening.get()
        session = worker
        val root = "motion-recordings".path
        Files.createDirectories(root)
        val recording = root.resolve("closd-${System.currentTimeMillis()}.f4motion")
        worker.record(recording, JSONObject().put("backend", "closd").put("seed", 0)
            .put("capabilities", worker.capabilities.details))
        worker.setPrompt(prompt.get(), 0.0)
        var pending: CompletableFuture<MotionUpdate>? = null
        var attachment: MotionAttachment? = null
        var attachmentTime = 0.0
        fun currentAnchor(time: Double, target: MotionAttachment) = (0..2).map { target.anchor[it] + target.anchorVelocity[it] * (time - attachmentTime).coerceAtLeast(0.0) }
        var displayTime = 0.0
        var lastWall = AnimationClock.nowSeconds()
        notice.set("Ready. Recording to $recording")
        while (true) {
            val now = AnimationClock.nowSeconds()
            if (running.get()) displayTime += (now - lastWall).coerceAtMost(.1)
            lastWall = now
            val ready = pending
            if (ready != null && ready.isDone) {
                val update = ready.get()
                if (update.events.any { it.optString("type") in setOf("reset", "tetherBreak") }) attachment = null
                pending = null
            }
            val latest = worker.latest
            var command = actions.poll()
            while (command != null) {
                val time = latest?.time ?: 0.0
                try {
                    when (command) {
                        "prompt" -> worker.setPrompt(prompt.get(), time)
                        "attach" -> {
                            val pose = latest ?: kotlin.error("Wait for the first pose")
                            val mapping = worker.skeleton.body(bodyName.get())
                            val index = worker.skeleton.joint(mapping.joint)
                            val anchor = pose.executed.worldPositions.subList(index * 3, index * 3 + 3)
                            val next = MotionAttachment("tether", mapping.name, listOf(0.0, 0.0, 0.0), anchor,
                                stiffness=stiffness.get(), damping=damping.get(), maximumForce=cap.get(), anchorVelocity=listOf(anchorSpeed.get(), 0.0, 0.0),
                                breakForce=if (breakable.get()) 30.0 else null)
                            if (attachment != null) worker.detach("tether", time)
                            worker.attach(next, time)
                            attachment = next
                            attachmentTime = time
                        }
                        "move" -> {
                            val old = attachment ?: kotlin.error("Attach a body first")
                            val a = currentAnchor(time, old)
                            val next = old.copy(anchor=listOf(a[0] + .1, a[1], a[2]))
                            worker.updateAttachment(next, time)
                            attachment = next
                            attachmentTime = time
                        }
                        "update" -> {
                            val old = attachment ?: kotlin.error("Attach a body first")
                            val next = old.copy(anchor=currentAnchor(time, old), stiffness=stiffness.get(), damping=damping.get(), maximumForce=cap.get(), anchorVelocity=listOf(anchorSpeed.get(), 0.0, 0.0),
                                breakForce=if (breakable.get()) 30.0 else null)
                            worker.updateAttachment(next, time)
                            attachment = next
                            attachmentTime = time
                        }
                        "detach" -> { check(attachment != null) { "No active tether" }; worker.detach("tether", time); attachment = null }
                        "impulse" -> worker.applyImpulse("Torso", listOf(0.0, 0.0, 0.0), listOf(10.0, 0.0, 0.0), time)
                        "reset" -> { worker.reset(time); attachment = null }
                    }
                    notice.set("$command at session ${"%.2f".format(time)} s · recording $recording")
                } catch (e: Exception) { notice.set(e.message ?: e.toString()) }
                command = actions.poll()
            }
            if (running.get() && pending == null && (latest == null || latest.time < displayTime + .1)) pending = worker.advance(.1)
            worker.buffer.sample(displayTime)?.let { snapshot ->
                val target = attachment
                val overlays = if (target != null) listOf(fline {
                    val joint = worker.skeleton.joint(worker.skeleton.body(target.body).joint)
                    val p = snapshot.frame.executed.position(joint)
                    moveTo(p)
                    val a = currentAnchor(snapshot.frame.time, target)
                    lineTo(a[0], a[1], a[2])
                    color = vec(1.0, 0.3, 0.4, 1.0); thickness = 3.0
                }) else emptyList()
                showMotion(snapshot.frame, worker.skeleton, overlays=overlays)
                markdown("status", "${notice.get()}\n\nSession ${"%.2f".format(snapshot.frame.time)} s · episode ${snapshot.frame.episode} · " +
                    "lag ${"%.3f".format(snapshot.lagSeconds)} s · held ${snapshot.held} · dropped ${worker.buffer.dropped}. " +
                    "White: executed. Cyan: reference. Red: point tether. Contact marks: measured force > 1 N.")
            }
            worker.failure?.let { throw it }
            displayOpen("motion")
            displayOpen("status")
            delay(16)
        }
    } finally {
        session?.close()
        if (!opening.isDone) opening.cancel(false)
    }
}
```

