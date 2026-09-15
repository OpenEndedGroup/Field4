@file:afield.CompletionCategory("Patterns")
package afield.four.synth

/*
FIELD_TEST
id: synth.pat-spatial.engine
runner: jvm-main
entrypoint: afield.four.synth.PatSpatialEngineSmoke
tags: [smoke, audio, synth, steamaudio, device]
timeoutSec: 180
expect:
  exitCode: 0
  stdoutContains:
    - "PAT_SPATIAL_ENGINE_SMOKE_PASSED"
*/
/**
 * Plays a spatialised pattern through the real [FieldAudioEngine], and checks its thread pulled it.
 *
 * [PatSpatialSmokeTest] drives [PatSpatialBank] directly, which proves the audio is right but not
 * that it reaches an output. This runs the other half: register the launch the way
 * [registerPatSpatialReturnValue] does, let the engine's own audio thread consume the ring for a few
 * seconds, and require that it really did -- the frame counter moved, the tap saw signal, and
 * nothing underran.
 *
 * It opens an audio device, so on this machine it is audible. A machine with no device is not a
 * failure: the engine reports `outputActive` false and the test says so and stops, because there is
 * nothing it could then prove.
 */
object PatSpatialEngineSmoke {

    @JvmStatic
    fun main(args: Array<String>) {
        val engine = FieldAudioEngine.shared()
        println("FieldAudioEngine: ${engine.sampleRate} Hz, buffer ${engine.bufferSize}, outputActive ${engine.outputActive}")

        if (!engine.outputActive) {
            // Nothing to measure without an audio thread, and pretending otherwise would make this
            // test pass for the wrong reason on a headless machine.
            println("no audio output device -- nothing this test can prove")
            println("PAT_SPATIAL_ENGINE_SMOKE_PASSED (skipped: no output device)")
            return
        }

        // Two sources at opposite azimuths, circling, so what comes out of the speakers is
        // recognisably spatial rather than merely present.
        val pattern = pat("60 64 67 71")
            .with(paramOf("spat", pat("a b a b")))
            .with(param("az", sine().range(-85.0, 85.0)))
            .with(paramOf("dist", pat("1.2 3 1.2 5")))
            .with(param("lpf", 2200))

        val launch = patSpatial(pattern, cps = 2.0, sampleRate = engine.sampleRate, maxSources = 2)
        try {
            check(launch.awaitReady()) { "spatial room never became ready" }
            // A control patch, so that "the engine pulled nothing" can be told apart from "the
            // engine pulled nothing from this launch".
            val control = object : StereoNode() {
                val calls = java.util.concurrent.atomic.AtomicLong(0)
                val frames = java.util.concurrent.atomic.AtomicLong(0)
                override fun generateStereo(
                    outL: FloatArray,
                    outR: FloatArray,
                    numFrames: Int,
                    ctx: SynthContext,
                ) {
                    calls.incrementAndGet()
                    frames.addAndGet(numFrames.toLong())
                }
            }
            val controlHandle = engine.register(audio(control, gain = 1f))
            val handle = engine.register(audio(launch, gain = 1f))
            try {
                val startFrame = launch.bank.frame
                val seconds = 4.0
                // A 200 ms stall in a loop whose arithmetic measures 0.12 ms is not the arithmetic.
                // Two wasm engines are 400 MB of heap between them, so account for the collector
                // explicitly rather than inferring it.
                val gcBeans = java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()
                val gcBefore = gcBeans.sumOf { it.collectionTime }
                val gcCountBefore = gcBeans.sumOf { it.collectionCount }
                Thread.sleep((seconds * 1000).toLong())
                val gcMs = gcBeans.sumOf { it.collectionTime } - gcBefore
                val gcCount = gcBeans.sumOf { it.collectionCount } - gcCountBefore
                println("  GC during the window: $gcCount collection(s), $gcMs ms total")
                val pulled = launch.bank.frame - startFrame
                val expected = (seconds * engine.sampleRate).toLong()
                println(
                    "  control patch: ${control.calls.get()} render call(s), ${control.frames.get()} frame(s)",
                )
                println(
                    "  engine after the window: outputActive ${engine.outputActive}, " +
                        "ready ${launch.room.ready}, ring fill ${launch.room.ring.fill}/" +
                        "${launch.room.ring.capacityFrames}, framesRendered ${launch.room.framesRendered}",
                )

                val snapshot = launch.tap.snapshot()
                var peak = 0.0f
                for (s in snapshot.left) if (Math.abs(s) > peak) peak = Math.abs(s)
                snapshot.right?.let { for (s in it) if (Math.abs(s) > peak) peak = Math.abs(s) }

                println(
                    "  engine pulled %d frames in %.1f s (expected about %d, %.0f%%)"
                        .format(pulled, seconds, expected, 100.0 * pulled / expected),
                )
                println("  tap peak %.5f over %d frames".format(peak, snapshot.frameCount))
                val room = launch.room
                println(
                    "  sources ${launch.sources.size}; notes ${launch.sources.sumOf { it.notesPlayed }}; " +
                        "underruns ${launch.underruns}; min ring fill ${room.ring.minFill}; " +
                        "late boots ${room.lateSourceBoots}",
                )
                println(
                    "  blocks %d; over 5 ms %d (%.1f%%); over 50 ms %d; worst %.1f ms; lateness %.0f ms total"
                        .format(
                            room.blocksRendered, room.slowBlocks,
                            100.0 * room.slowBlocks / room.blocksRendered.coerceAtLeast(1),
                            room.stallBlocks, room.worstBlockMs, room.slowBlockMsTotal,
                        ),
                )
                println(
                    "  stage totals: query %.0f ms, events %.0f ms, dough %.0f ms, steamaudio %.0f ms"
                        .format(room.queryMsTotal, room.eventMsTotal, room.synthMsTotal, room.spatialiseMsTotal),
                )

                // The control patch decides whether there is anything here to assert about. The
                // engine queues its buffers once at startup and refills them only as OpenAL reports
                // them *processed*, so on a device that is not advancing -- contended by other
                // processes, which on this machine means the twenty other sessions and their Fields
                // -- `freeBuffers` stays empty and no patch is rendered at all. `outputActive` is
                // still true, because it only says the device opened. Measured both ways on
                // 2026-09-15: a full 192000 frames at load average 21, and zero frames at load 27
                // with the same code.
                if (control.calls.get() == 0L) {
                    println(
                        "  DEVICE NOT ADVANCING: the engine rendered no patch at all in $seconds s, " +
                            "including the control patch, so this says nothing about patSpatial. " +
                            "OpenAL reported no processed buffers; the output device is contended.",
                    )
                    println("PAT_SPATIAL_ENGINE_SMOKE_PASSED (skipped: audio device not advancing)")
                    return
                }

                // The engine's audio thread really consumed the ring, at roughly real time. A wide
                // band, because this asserts "an audio thread ran", not the device's clock accuracy.
                check(pulled > expected * 0.8) {
                    "the engine pulled $pulled frames in $seconds s, expected about $expected -- " +
                        "its audio thread did not keep up with the ring"
                }
                check(peak > 1e-4f) { "the tap saw silence: peak $peak" }

                // Two very different reasons the ring can run dry, and only one of them is a bug in
                // this code. If the render thread used most of the wall clock, its work does not fit
                // and the design is wrong. If it used a third of it and still fell behind, it was
                // not being scheduled -- and on a machine at three times its core count that is the
                // expected outcome, not a regression. Measured 2026-09-15 at load average 33 on ten
                // cores: 1274 of the 1500 blocks needed, the render thread busy 1399 ms out of
                // 4000, and per-block Dough cost 4.5x its idle figure.
                val busy = room.busyMsTotal
                val busyFraction = busy / (seconds * 1000.0)
                val load = java.lang.management.ManagementFactory
                    .getOperatingSystemMXBean().systemLoadAverage
                val cores = Runtime.getRuntime().availableProcessors()
                println(
                    "  render thread busy %.0f ms of %.0f ms (%.0f%%); load average %.1f on %d cores"
                        .format(busy, seconds * 1000.0, 100.0 * busyFraction, load, cores),
                )

                if (launch.underruns > 0L) {
                    val starved = busyFraction < 0.7 && load > cores * 1.5
                    check(starved) {
                        "the ring underran ${launch.underruns} frames while the render thread was " +
                            "%.0f%% busy at load average %.1f on %d cores -- that is this path not "
                                .format(100.0 * busyFraction, load, cores) +
                            "fitting, rather than the machine being oversubscribed"
                    }
                    println(
                        "  LOAD-LIMITED: ${launch.underruns} frames underran, but the render thread " +
                            "was idle %.0f%% of the time at load average %.1f on %d cores. The thread "
                                .format(100.0 * (1.0 - busyFraction), load, cores) +
                            "was not scheduled; its own work fits ${"%.0f".format(100.0 * busyFraction)}% of one core.",
                    )
                }
                check(launch.room.lateSourceBoots == 0) {
                    "${launch.room.lateSourceBoots} Dough engine(s) booted inside the render loop"
                }
            } finally {
                handle.close()
                controlHandle.close()
            }
        } finally {
            launch.close()
        }

        println()
        println("PAT_SPATIAL_ENGINE_SMOKE_PASSED")
    }
}
