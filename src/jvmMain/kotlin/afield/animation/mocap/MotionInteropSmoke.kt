package afield.animation.mocap

import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/*
FIELD_TEST
id: motion.recording.interop
runner: jvm-main
entrypoint: afield.animation.mocap.MotionInteropSmoke
tags: [smoke, animation, motion, python]
timeoutSec: 30
expect:
  exitCode: 0
  stdoutContains:
    - "Motion recording interoperability passed"
*/
/** Real Kotlin -> Python -> Kotlin binary round trip; no network inference is claimed. */
object MotionInteropSmoke {
    @JvmStatic fun main(args: Array<String>) {
        val root = Files.createTempDirectory("field-motion-interop")
        try {
            val source = root.resolve("kotlin.f4motion")
            val target = root.resolve("python.f4motion")
            MotionExamples.recordContractClip(source)
            val script = """
                import sys
                from field_motion.recording import Recording, RecordingWriter
                r = Recording(sys.argv[1])
                frames = list(r.frames())
                assert len(frames) == 81
                assert {f['episode'] for f in frames} == {0, 1}
                with RecordingWriter(sys.argv[2], r.skeleton, {'backend': 'interop-test'}, chunk_frames=7) as w:
                    for f in frames: w.append(f)
                    for e in r.events(): w.event(e)
                print(r.skeleton['hash'])
            """.trimIndent()
            val interpreter = System.getenv("FIELD_MOTION_TOOLS_PYTHON") ?: if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3"
            val log = root.resolve("python.log")
            val process = ProcessBuilder(interpreter, "-c", script, source.toString(), target.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).apply {
                    environment()["PYTHONPATH"] = Path.of("optional/motion/python").toAbsolutePath().toString()
                }.start()
            if (!process.waitFor(20, TimeUnit.SECONDS)) { process.destroyForcibly(); error("Motion interop Python timed out") }
            check(process.exitValue() == 0) { Files.readString(log) }
            val a = MotionRecording(source); val b = MotionRecording(target)
            check(a.skeleton.hash == b.skeleton.hash)
            val before = a.frames().toList(); val after = b.frames().toList()
            check(before.size == after.size)
            before.zip(after).forEach { (x, y) ->
                check(x.time == y.time && x.index == y.index && x.episode == y.episode)
                check(x.executed.root == y.executed.root && x.executed.localRotations == y.executed.localRotations && x.executed.worldPositions == y.executed.worldPositions)
            }
            check(b.events().single().getString("type") == "reset")
            println("Motion recording interoperability passed: 81 frames, two episodes, identical float payloads")
            // Optional retained recording for benchmarking/inspection outside a test.
            if (args.isNotEmpty()) MotionExamples.recordContractClip(Path.of(args[0]))
        } finally { root.toFile().deleteRecursively() }
    }
}
