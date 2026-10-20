import afield.animation.mocap.*
import java.nio.file.Files
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import kotlin.math.*

val experiment = "intentions" // Starting-point presets: path, intentions, foot.
val actions = ArrayBlockingQueue<String>(8)
val prompt = AtomicReference(if (experiment == "intentions") "A person skips forward." else "A person walks forward.")
val routeName = AtomicReference("line")
val routeDuration = AtomicReference(6.0)
val easeSpeed = AtomicBoolean(false)
val heading = AtomicBoolean(true)
val orientation = AtomicBoolean(false)
val jointName = AtomicReference("LeftFoot")
val pinDuration = AtomicReference(2.0)
val playing = AtomicBoolean(true)
val follow = AtomicBoolean(true)
val followHalfLife = AtomicReference(.4)
val notice = AtomicReference("Opening ARDY…")
fun action(value: String, down: Boolean) { if (down && !actions.offer(value)) notice.set("Controls queue full") }

controlGroup(columns=2) {
    checkbox("Follow body", initialValue=true) { follow.set(it) }
    slider("Follow half-life seconds", .05, 2.0, initialValue=.4) { followHalfLife.set(it) }
    stringInput("Intention", initialValue=prompt.get()) { prompt.set(it) }
    button("Apply intention") { action("prompt", it) }
    button("Skip") { action("skip", it) }
    button("Run") { action("run", it) }
    button("Sneak") { action("sneak", it) }
    button("Recoil") { action("recoil", it) }
    button("Stop") { action("stop", it) }
    stringInput("Route: line, arc, figure8, sharp, stop, reversal", initialValue="line") { routeName.set(it) }
    slider("Route duration seconds", 2.0, 20.0, initialValue=6.0) { routeDuration.set(it) }
    checkbox("Ease speed instead of constant arc-length speed", initialValue=false) { easeSpeed.set(it) }
    checkbox("Condition heading", initialValue=true) { heading.set(it) }
    button("Apply timed route from current root") { action("route", it) }
    button("Clear route and targets") { action("clear", it) }
    stringInput("Constraint joint", initialValue="LeftFoot") { jointName.set(it) }
    slider("Future restriction seconds", .2, 6.0, initialValue=2.0) { pinDuration.set(it) }
    checkbox("Also constrain global orientation", initialValue=false) { orientation.set(it) }
    button("Restrict joint at first editable frame") { action("pin", it) }
    button("Remove joint targets") { action("unpin", it) }
    button("Reset episode") { action("reset", it) }
    checkbox("Advance", initialValue=true) { playing.set(it) }
}

frames {
    val opening = ARDY.openAsync()
    var session: MotionSession? = null
    try {
        while (!opening.isDone) { markdown("status", "Opening ARDY worker…"); delay(33) }
        val worker = opening.get(); session = worker
        val root = "motion-recordings".path; Files.createDirectories(root)
        val output = root.resolve("ardy-${System.currentTimeMillis()}.f4motion")
        worker.record(output, JSONObject().put("backend", "ardy").put("seed", 0).put("postprocess", false)
            .put("capabilities", worker.capabilities.details))
        worker.setPrompt(prompt.get(), 0.0)
        val step = worker.capabilities.details.getDouble("minimumAdvanceSeconds")
        val fps = worker.capabilities.details.getDouble("fps")
        var pending: CompletableFuture<MotionUpdate>? = null
        var displayTime = 0.0; var lastWall = AnimationClock.nowSeconds()
        var nextTime = 0.0
        var pathOverlay = emptyList<afield.geometry.path.FLine>()
        var activeRoute = emptyList<TimedRootPoint>()
        var anchor: List<Double>? = null
        var pinnedJoint = "LeftFoot"
        var pinUntil = -1.0
        if (experiment != "intentions") actions.offer("route")
        notice.set("Ready · $experiment · recording $output")
        while (true) {
            val now = AnimationClock.nowSeconds()
            if (playing.get()) displayTime += (now - lastWall).coerceAtMost(.1)
            lastWall = now
            if (pending?.isDone == true) { pending.get(); pending = null }
            val latest = worker.latest
            var command = actions.poll()
            while (command != null) {
                try {
                    when (command) {
                        "prompt", "skip", "run", "sneak", "recoil", "stop" -> {
                            val text = when (command) {
                                "skip" -> "A person skips forward."
                                "run" -> "A person runs forward."
                                "sneak" -> "A person walks stealthily and crouches."
                                "recoil" -> "A person recoils and steps backwards with raised hands."
                                "stop" -> "A person stops and stands still."
                                else -> prompt.get()
                            }
                            worker.setPrompt(text, nextTime)
                        }
                        "route" -> {
                            val origin = latest?.executed?.root ?: listOf(0.0, 0.0, 0.0)
                            val kind = routeName.get().trim()
                            check(kind in setOf("line", "arc", "figure8", "sharp", "stop", "reversal")) { "Unknown route type" }
                            val path = fline {
                                repeat(121) { i ->
                                    val t = i / 120.0
                                    val x = when (kind) { "arc" -> 2 * (1 - cos(t * PI)); "figure8" -> sin(t * 2 * PI); "sharp" -> if (t < .5) 0.0 else (t - .5) * 6; else -> 0.0 }
                                    val z = when (kind) { "arc" -> 2 * sin(t * PI); "figure8" -> sin(t * 4 * PI); "sharp" -> min(t, .5) * 6; "reversal" -> if (t < .5) t * 6 else (1 - t) * 6; else -> t * 3.6 }
                                    if (i == 0) moveTo(origin[0] + x, 0.0, origin[2] + z) else lineTo(origin[0] + x, 0.0, origin[2] + z)
                                }
                                color = vec(.9, .7, .2, 1.0); thickness = 2.0
                            }
                            activeRoute = path.motionRoute(nextTime, routeDuration.get(), count=(routeDuration.get() * fps).toInt() + 1,
                                headings=heading.get(), distanceFraction={ t ->
                                    when { kind == "stop" -> min(1.0, t * 2); easeSpeed.get() -> t * t * (3 - 2 * t); else -> t }
                                })
                            worker.setJointTargets(emptyList(), nextTime); anchor = null
                            worker.setRootPlan(activeRoute, nextTime); pathOverlay = listOf(path)
                        }
                        "clear" -> { worker.setJointTargets(emptyList(), nextTime); worker.setRootPlan(emptyList(), nextTime); activeRoute = emptyList(); pathOverlay = emptyList(); anchor = null }
                        "pin" -> {
                            val pose = latest?.executed ?: kotlin.error("Wait for a generated pose")
                            check(activeRoute.isNotEmpty() && activeRoute.last().time >= nextTime + pinDuration.get()) { "Apply a route covering the complete restriction interval first" }
                            pinnedJoint = jointName.get()
                            val index = worker.skeleton.joint(pinnedJoint)
                            val target = pose.worldPositions.subList(index * 3, index * 3 + 3)
                            var rotation: List<Double>? = null
                            if (orientation.get()) {
                                val world = ArrayList<afield.math.linalg.Quat>()
                                repeat(worker.skeleton.size) { i ->
                                    val r = pose.rotation(i)
                                    val parent = worker.skeleton.parents[i]
                                    world.add(if (parent < 0) r else afield.math.linalg.Quat(world[parent]).mul(r))
                                }
                                val r = world[index]; rotation = listOf(r.x.toDouble(), r.y.toDouble(), r.z.toDouble(), r.w.toDouble())
                            }
                            worker.setJointTargets((0 until (pinDuration.get() * fps).toInt()).map { JointTarget(pinnedJoint, nextTime + it / fps, target, rotation) }, nextTime)
                            anchor = target; pinUntil = nextTime + pinDuration.get()
                        }
                        "unpin" -> { worker.setJointTargets(emptyList(), nextTime); anchor = null }
                        "reset" -> { worker.reset(nextTime); activeRoute = emptyList(); pathOverlay = emptyList(); anchor = null }
                    }
                    notice.set("$command · first editable time ${"%.2f".format(nextTime)} s · $output")
                } catch (e: Exception) { notice.set(e.message ?: e.toString()) }
                command = actions.poll()
            }
            if (playing.get() && pending == null && nextTime < displayTime + step) {
                pending = worker.advance(step); nextTime += step
            }
            worker.buffer.sample(displayTime)?.let { sample ->
                val target = anchor
                val lines = if (target != null && sample.frame.time < pinUntil) pathOverlay + fline {
                    val p = sample.frame.executed.position(worker.skeleton.joint(pinnedJoint))
                    moveTo(p); lineTo(target[0], target[1], target[2]); color = vec(1.0, .3, .4, 1.0); thickness = 3.0
                } else pathOverlay
                showMotion(sample.frame, worker.skeleton, reference=false, overlays=lines, follow=follow.get(), followHalfLife=followHalfLife.get())
                val residual = if (target == null || sample.frame.time >= pinUntil) "" else {
                    val p = sample.frame.executed.position(worker.skeleton.joint(pinnedJoint))
                    val error = sqrt((p.x - target[0]).pow(2) + (p.y - target[1]).pow(2) + (p.z - target[2]).pow(2))
                    " · raw target residual ${"%.3f".format(error)} m"
                }
                markdown("status", "${notice.get()}\n\nSession ${"%.2f".format(sample.frame.time)} s · episode ${sample.frame.episode} · lag ${"%.3f".format(sample.lagSeconds)} s$residual. " +
                    "Yellow: requested path. Red: model conditioning residual. Global joint targets also condition the explicit root path; this is not a physical attachment. No postprocessing.")
            }
            worker.failure?.let { throw it }
            displayOpen("motion"); displayOpen("status")
            delay(16)
        }
    } finally { session?.close(); if (session == null) opening.cancel(false) }
}
