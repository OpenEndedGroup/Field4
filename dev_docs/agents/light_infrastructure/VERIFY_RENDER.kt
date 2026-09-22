import afield.four.modules.fromContextWithNameOrNull
import afield.four.targets.*
import kotlinx.serialization.json.Json
import java.io.File

// Attach this temporary verification box to flow_resources in an isolated demo.
val reset = fromContextWithNameOrNull<SimulationReset>("resetFlow")!!
val travel = fromContextWithNameOrNull<java.util.concurrent.atomic.AtomicBoolean>("travel")!!
val surface = fromContextWithNameOrNull<RenderToTexture>("flowSurface")!!
val simulation = fromContextWithNameOrNull<ComputeTarget>("flowSimulation")!!
val buffer = fromContextWithNameOrNull<FrameBufferGroup>("flowA")!!
val folder = File("build/light-infrastructure").apply { mkdirs() }
frames {
    val hooks = renderer.geometryProducers.keys.toSet()
    val modules = simulation.modules.keys.toSet()
    var maxFrames = 0L
    repeat(32) {
        val started = renderer.frameCounter
        val ticket = reset.request()
        while (frame() && reset.completed < ticket) {
            check(renderer.frameCounter - started < 20) { "Reset did not reach a rendered frame" }
        }
        maxFrames = maxOf(maxFrames, renderer.frameCounter - started)
        check(reset.failure == null)
        check(fromContextWithNameOrNull<FrameBufferGroup>("flowA") === buffer)
        check(fromContextWithNameOrNull<RenderToTexture>("flowSurface") === surface)
        check(renderer.geometryProducers.keys == hooks)
        check(simulation.modules.keys == modules)
    }
    println("RESET_PASS: 32 resets, maximum $maxFrames rendered frames; same resources and hooks")
    File(folder, "reset.txt").writeText("32 resets; maximum $maxFrames rendered frames; same resources, modules and hooks\n")
    travel.set(true)
    try {
        repeat(2) { pass ->
            val ticket = reset.request()
            while (frame() && reset.completed < ticket) {}
            val warm = renderer.frameCounter
            while (frame() && renderer.frameCounter - warm < 60) {}
            CoroutineProfiles.request(thisBox.inside.filename, CoroutineProfileRequest("start", 30.0, stacks=true))
            val start = renderer.frameCounter
            while (frame() && renderer.frameCounter - start < 180) {}
            var result = CoroutineProfiles.request(thisBox.inside.filename, CoroutineProfileRequest("stop"))
            val stopped = System.nanoTime()
            while (frame() && result.stackState == "processing") {
                check(System.nanoTime() - stopped < 30_000_000_000L) { "JFR did not finish" }
                result = CoroutineProfiles.request(thisBox.inside.filename, CoroutineProfileRequest())
            }
            check(result.stackState != "failed") { result.stackError }
            check(result.frameRecords.size >= 170) { "Missing render-frame intervals: ${result.frameRecords.size}" }
            check(result.frameRecords.all { it.cpuMs >= 0 && it.allocatedBytes >= 0 })
            File(folder, "profile-$pass.json").writeText(Json.encodeToString(CoroutineProfileSnapshot.serializer(), result))
            println("PROFILE_PASS $pass: ${result.frameRecords.size} measured intervals")
        }
    } finally {
        CoroutineProfiles.request(thisBox.inside.filename, CoroutineProfileRequest("stop"))
        travel.set(false)
    }
    File(folder, "render-done.txt").writeText("PASS\n")
}
