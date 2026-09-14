package afield.four.audio.steamaudio

import kotlin.test.Test

/**
 * Not an assertion -- a report. Prints what each object type costs in native allocations, so
 * the leak tests' baselines can be read in context and an unexpected growth in footprint after
 * an SDK bump is visible.
 */
class AllocationReportTest {

    /**
     * Whether the tests that touch the native library can run.
     *
     * Field does not commit the Steam Audio binaries, so a clone that has never run
     * `fetchSteamAudioNatives` has none. Skipping with a stated reason keeps `check` green there
     * and says what is missing, rather than failing on a link error that names no remedy.
     */
    private fun available(): Boolean {
        if (!SteamAudioNatives.isAvailable) {
            println("SKIPPED allocation report: ${SteamAudioNatives.describe()}")
            return false
        }
        return true
    }

    private val settings = AudioSettings(samplingRate = 48_000, frameSize = 512)

    @Test
    fun reportAllocationFootprints() {
        if (!available()) return
        val rows = mutableListOf<Triple<String, Long, Long>>()

        fun measure(what: String, body: (SteamAudioContext) -> Unit) {
            val allocator = CountingAllocator()
            SteamAudioContext(allocator = allocator).use { context ->
                val before = allocator.snapshot()
                body(context)
                val after = allocator.snapshot()
                rows += Triple(
                    what,
                    after.allocations - before.allocations,
                    after.bytes - before.bytes,
                )
            }
        }

        measure("context only") { }
        measure("Hrtf.default (create+close)") { Hrtf.default(it, settings).close() }
        measure("Hrtf.default (left open)") { Hrtf.default(it, settings) }
        measure("BinauralEffect (create+close)") { context ->
            Hrtf.default(context, settings).use { hrtf ->
                BinauralEffect(context, settings, hrtf).close()
            }
        }
        measure("ReflectionEffect 1s order1") { context ->
            ReflectionEffect(context, settings, duration = 1f, ambisonicOrder = 1).close()
        }
        measure("ReflectionEffect 3s order2") { context ->
            ReflectionEffect(context, settings, duration = 3f, ambisonicOrder = 2).close()
        }
        measure("Scene + 4-wall room") { context ->
            val walls = WallSet(
                World2D(),
                listOf(
                    WallSegment(-5f, -5f, 5f, -5f), WallSegment(5f, -5f, 5f, 5f),
                    WallSegment(5f, 5f, -5f, 5f), WallSegment(-5f, 5f, -5f, -5f),
                ),
            )
            Scene(context).use { scene ->
                walls.build(scene, bounds = floatArrayOf(-5f, -5f, 5f, 5f)).use { mesh ->
                    mesh.addTo(scene); scene.commit()
                }
            }
        }
        measure("Simulator (direct only)") { context ->
            Scene(context).use { scene ->
                scene.commit()
                Simulator(
                    context,
                    SimulationSettings(
                        flags = SimulationFlags.DIRECT, maxNumRays = 512, maxDuration = 0.5f,
                        samplingRate = settings.samplingRate, frameSize = settings.frameSize,
                    ),
                ).use { it.setScene(scene); it.commit() }
            }
        }

        println()
        println("Native allocation footprint (through a counting allocator)")
        println("  %-34s %12s %14s".format("operation", "allocations", "bytes left"))
        println("  " + "-".repeat(62))
        for ((what, allocations, bytes) in rows) {
            println("  %-34s %12d %14d".format(what, allocations, bytes))
        }
        println()
        println("  'bytes left' is what remained outstanding after the operation. A create+close")
        println("  pair should leave ~0; a 'left open' row shows the object's actual footprint.")
    }
}
