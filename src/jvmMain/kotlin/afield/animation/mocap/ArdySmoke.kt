package afield.animation.mocap

import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/*
FIELD_TEST
id: motion.ardy.gpu
runner: jvm-main
entrypoint: afield.animation.mocap.ArdySmoke
tags: [smoke, optional, animation, motion, gpu, ardy]
timeoutSec: 240
expect:
  exitCode: 0
  stdoutContains:
    - "ARDY motion smoke"
*/
/** Actual model run, never substituted with a fixture. Cached prompt embeddings can run without the encoder service. */
object ArdySmoke {
    @JvmStatic fun main(args: Array<String>) {
        val home = System.getenv("FIELD_MOTION_HOME")
        if (home.isNullOrBlank()) {
            println("ARDY motion smoke SKIPPED: set FIELD_MOTION_HOME and start the CPU encoder service or cache prompts")
            return
        }
        val scratch = Files.createTempDirectory("field-ardy-smoke")
        try {
            val session = ARDY.openAsync(MotionProcessConfig(backend = "ardy", home = Path.of(home))).get(180, TimeUnit.SECONDS)
            val recorder = session.record(scratch.resolve("native.f4motion"), JSONObject().put("backend", "ardy").put("seed", 0))
            session.use {
                check(session.skeleton.size == 27)
                check(session.capabilities.details.getInt("commitFrames") == 4)
                session.setPrompt("A person walks forward.", 0.0).get(5, TimeUnit.SECONDS)
                session.setRootPlan((0..40).map { TimedRootPoint(it * .05, 0.0, it * .03, 0.0) }, 0.0).get(5, TimeUnit.SECONDS)
                val first = session.advance(.4).get(120, TimeUnit.SECONDS)
                check(first.frames.size == 8 && first.frames.all { it.contactProvenance == "ardy.predicted" })
                val joint = session.skeleton.joint("LeftFoot")
                val anchor = first.frames.last().executed.worldPositions.subList(joint * 3, joint * 3 + 3)
                session.setJointTargets((8..15).map { JointTarget("LeftFoot", it * .05, anchor) }, .4).get(5, TimeUnit.SECONDS)
                val pinned = session.advance(.4).get(120, TimeUnit.SECONDS)
                check(pinned.frames.size == 8 && pinned.events.any { it.optString("type") == "generation" })
                check(runCatching { session.applyImpulse("LeftFoot", listOf(0.0, 0.0, 0.0), listOf(1.0, 0.0, 0.0), .8) }.isFailure)
                session.reset(.8).get(5, TimeUnit.SECONDS)
                val reset = session.advance(.2).get(120, TimeUnit.SECONDS)
                check(reset.frames.all { it.episode == 1L } && reset.events.any { it.optString("type") == "reset" })
                check(session.failure == null)
            }
            recorder.close()
            val replay = MotionRecording(scratch.resolve("native.f4motion"))
            check(replay.frames().count() == 20)
            println("ARDY motion smoke passed: real GPU, 27 joints, 20 frames, prompt/path/sparse foot target, reset and exact replay")
        } finally { scratch.toFile().deleteRecursively() }
    }
}
