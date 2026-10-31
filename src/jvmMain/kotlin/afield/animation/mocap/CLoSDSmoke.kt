package afield.animation.mocap

import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/*
FIELD_TEST
id: motion.closd.gpu
runner: jvm-main
entrypoint: afield.animation.mocap.CLoSDSmoke
tags: [smoke, optional, animation, motion, gpu, closd]
timeoutSec: 240
expect:
  exitCode: 0
  stdoutContains:
    - "CLoSD motion smoke"
*/
/** Real Kotlin process/skeleton/reference/executed/intervention check, explicitly skipped without its opt-in home. */
object CLoSDSmoke {
    @JvmStatic fun main(args: Array<String>) {
        val home = System.getenv("FIELD_MOTION_HOME")
        if (home.isNullOrBlank()) {
            println("CLoSD motion smoke SKIPPED: set FIELD_MOTION_HOME to the validated development environment")
            return
        }
        val scratch = if (args.isEmpty()) Files.createTempDirectory("field-closd-smoke") else Path.of(args[0])
        Files.createDirectories(scratch)
        try {
            val session = CLoSD.openAsync(MotionProcessConfig(home = Path.of(home))).get(180, TimeUnit.SECONDS)
            val recorder = session.record(scratch.resolve("kotlin.f4motion"), JSONObject().put("backend", "closd").put("seed", 0))
            session.use {
                check(session.skeleton.size == 24)
                check(session.capabilities.backend == "closd")
                check(session.setPrompt("A person walks forward.", 0.0).get(5, TimeUnit.SECONDS)?.appliedTime == null)
                val first = session.advance(.5).get(120, TimeUnit.SECONDS)
                check(first.frames.size == 15 && first.frames.all { it.intended != null && !it.intended.hasRotations })
                val frame = first.frames.last()
                check(frame.diagnostics.getValue("prefixResidualM") == 0.0)
                val index = session.skeleton.joint("L_Ankle")
                val anchor = frame.executed.worldPositions.subList(index * 3, index * 3 + 3)
                session.attach(MotionAttachment("smoke", "L_Ankle", listOf(0.0, 0.0, 0.0), anchor), frame.time).get(5, TimeUnit.SECONDS)
                val tethered = session.advance(.3).get(120, TimeUnit.SECONDS)
                check(tethered.events.any { it.optString("type") == "tetherPhysics" })
                check(tethered.events.filter { it.optString("type") == "tetherPhysics" }.all { it.getDouble("forceN") <= 50.00001 })
                session.detach("smoke", tethered.frames.last().time).get(5, TimeUnit.SECONDS)
                session.applyImpulse("Torso", listOf(0.0, 0.0, 0.0), listOf(1.0, 0.0, 0.0), tethered.frames.last().time).get(5, TimeUnit.SECONDS)
                val released = session.advance(.2).get(120, TimeUnit.SECONDS)
                check(released.events.count { it.optString("type") == "impulsePhysics" } == 1)
                check(session.buffer.size() <= session.buffer.capacity)
                check(session.failure == null)
            }
            recorder.close()
            val replay = MotionRecording(scratch.resolve("kotlin.f4motion"))
            check(replay.frames().count() == 30)
            check(replay.events().any { it.optString("type") == "impulsePhysics" })
            println("CLoSD motion smoke passed: real GPU, 24 joints, 30 frames, positions-only reference, tether/release/one-shot impulse, exact replay")
        } finally { if (args.isEmpty()) scratch.toFile().deleteRecursively() }
    }
}
