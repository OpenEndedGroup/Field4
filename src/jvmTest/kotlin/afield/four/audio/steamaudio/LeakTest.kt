package afield.four.audio.steamaudio

import java.lang.foreign.Arena
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Checks that closing a Steam Audio object actually returns its memory.
 *
 * Every object here is reference-counted native memory, and the wrappers are the only thing
 * standing between a missed `close()` and a leak that grows with play time. Impulse responses
 * and scenes are large enough that this matters within minutes, not hours.
 *
 * ### How these tests measure
 *
 * A [CountingAllocator] is installed on the context, so every internal allocation is counted.
 * The assertion is **not** that outstanding memory returns to zero: a context legitimately
 * keeps caches and thread-local scratch alive for its own lifetime, and the first object of a
 * given type often warms a shared lookup table.
 *
 * Instead each test creates and closes a batch, records the outstanding total as a baseline,
 * then repeats the batch many times and asserts the total comes back to that baseline. A real
 * leak grows without bound and fails; a one-off warm-up allocation is absorbed into the
 * baseline and does not.
 */
class LeakTest {

    /**
     * Whether the tests that touch the native library can run.
     *
     * Field does not commit the Steam Audio binaries, so a clone that has never run
     * `fetchSteamAudioNatives` has none. Skipping with a stated reason keeps `check` green there
     * and says what is missing, rather than failing on a link error that names no remedy.
     */
    private fun available(): Boolean {
        if (!SteamAudioNatives.isAvailable) {
            println("SKIPPED leak checks: ${SteamAudioNatives.describe()}")
            return false
        }
        return true
    }

    /** Iterations per steady-state check. Enough that a per-object leak is unmistakable. */
    private val iterations = 40

    private val settings = AudioSettings(samplingRate = 48_000, frameSize = 512)

    /**
     * Runs [body] once to warm up, snapshots, then runs it [iterations] more times and asserts
     * that outstanding memory returned to the snapshot.
     *
     * @param what named in the failure message.
     */
    private fun assertNoLeak(
        what: String,
        allocator: CountingAllocator,
        tolerance: Long = 0,
        body: () -> Unit,
    ) {
        // Warm-up: absorbs any first-use allocation that is genuinely cached for the context's
        // lifetime rather than owned by the object.
        body()
        val baseline = allocator.snapshot()

        repeat(iterations) { body() }
        val after = allocator.snapshot()

        val leakedBlocks = after.blocks - baseline.blocks
        val leakedBytes = after.bytes - baseline.bytes

        assertTrue(
            leakedBlocks <= tolerance,
            "$what leaked $leakedBlocks blocks / $leakedBytes bytes over $iterations " +
                "create-close cycles\n  baseline: $baseline\n  after:    $after\n" +
                "  that is ${leakedBlocks.toDouble() / iterations} blocks per cycle",
        )
        assertTrue(
            after.allocations > baseline.allocations,
            "$what allocated nothing at all -- the allocator is probably not installed",
        )
    }

    /** Runs [body] with a counting allocator installed on a fresh context. */
    private fun withCountedContext(body: (SteamAudioContext, CountingAllocator) -> Unit) {
        val allocator = CountingAllocator()
        SteamAudioContext(allocator = allocator).use { context ->
            body(context, allocator)
        }
    }

    @Test
    fun theAllocatorIsActuallyBeingUsed() {
        if (!available()) return
        val allocator = CountingAllocator()
        SteamAudioContext(allocator = allocator).use {
            assertTrue(
                allocator.allocationCount > 0,
                "creating a context allocated nothing through the custom allocator, so these " +
                    "tests would measure nothing",
            )
        }
        // Closing the context must return essentially everything. A handful of blocks may
        // outlive it if Steam Audio holds process-wide state, hence the loose bound rather
        // than an exact zero.
        assertTrue(
            allocator.outstandingBlocks < 64,
            "closing the context left ${allocator.outstandingBlocks} blocks " +
                "(${allocator.outstandingBytes} bytes) outstanding",
        )
    }

    @Test
    fun hrtfDoesNotLeak() {
        if (!available()) return
        withCountedContext { context, allocator ->
            assertNoLeak("Hrtf", allocator) {
                Hrtf.default(context, settings).close()
            }
        }
    }

    @Test
    fun binauralEffectDoesNotLeak() {
        if (!available()) return
        withCountedContext { context, allocator ->
            Hrtf.default(context, settings).use { hrtf ->
                assertNoLeak("BinauralEffect", allocator) {
                    BinauralEffect(context, settings, hrtf).close()
                }
            }
        }
    }

    @Test
    fun directEffectDoesNotLeak() {
        if (!available()) return
        withCountedContext { context, allocator ->
            assertNoLeak("DirectEffect", allocator) {
                DirectEffect(context, settings).close()
            }
        }
    }

    @Test
    fun reflectionEffectDoesNotLeak() {
        if (!available()) return
        withCountedContext { context, allocator ->
            assertNoLeak("ReflectionEffect", allocator) {
                ReflectionEffect(
                    context, settings,
                    type = ReflectionEffectType.HYBRID,
                    duration = 1.0f,
                    ambisonicOrder = 1,
                ).close()
            }
        }
    }

    @Test
    fun ambisonicsEffectsDoNotLeak() {
        if (!available()) return
        withCountedContext { context, allocator ->
            Hrtf.default(context, settings).use { hrtf ->
                assertNoLeak("AmbisonicsDecodeEffect", allocator) {
                    AmbisonicsDecodeEffect(context, settings, hrtf, maxOrder = 1).close()
                }
                assertNoLeak("AmbisonicsEncodeEffect", allocator) {
                    AmbisonicsEncodeEffect(context, settings, maxOrder = 1).close()
                }
                assertNoLeak("AmbisonicsRotationEffect", allocator) {
                    AmbisonicsRotationEffect(context, settings, maxOrder = 1).close()
                }
            }
        }
    }

    @Test
    fun pathEffectDoesNotLeak() {
        if (!available()) return
        withCountedContext { context, allocator ->
            Hrtf.default(context, settings).use { hrtf ->
                assertNoLeak("PathEffect", allocator) {
                    PathEffect(context, settings, hrtf, maxOrder = 1).close()
                }
            }
        }
    }

    @Test
    fun sceneAndMeshesDoNotLeak() {
        if (!available()) return
        withCountedContext { context, allocator ->
            val world = World2D()
            val walls = WallSet(
                world,
                listOf(
                    WallSegment(-5f, -5f, 5f, -5f),
                    WallSegment(5f, -5f, 5f, 5f),
                    WallSegment(5f, 5f, -5f, 5f),
                    WallSegment(-5f, 5f, -5f, -5f),
                ),
            )
            assertNoLeak("Scene + StaticMesh", allocator) {
                Scene(context).use { scene ->
                    walls.build(scene, bounds = floatArrayOf(-5f, -5f, 5f, 5f)).use { mesh ->
                        mesh.addTo(scene)
                        scene.commit()
                    }
                }
            }
        }
    }

    @Test
    fun instancedMeshDoesNotLeak() {
        if (!available()) return
        withCountedContext { context, allocator ->
            val world = World2D()
            Scene(context).use { scene ->
                Scene(context).use { subScene ->
                    StaticMesh.plane(
                        subScene,
                        Vector3(-1f, -1f, 0f), Vector3(1f, -1f, 0f),
                        Vector3(1f, 1f, 0f), Vector3(-1f, 1f, 0f),
                    ).use { panel ->
                        panel.addTo(subScene)
                        subScene.commit()

                        assertNoLeak("InstancedMesh", allocator) {
                            InstancedMesh(scene, subScene, Matrix4x4.IDENTITY).use { instance ->
                                instance.addTo(scene)
                                scene.commit()
                                instance.updateTransform(scene, Matrix4x4.translation(1f, 0f, 0f))
                                scene.commit()
                                instance.removeFrom(scene)
                                scene.commit()
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun simulatorAndSourcesDoNotLeak() {
        if (!available()) return
        withCountedContext { context, allocator ->
            Scene(context).use { scene ->
                scene.commit()
                val simulationSettings = SimulationSettings(
                    flags = SimulationFlags.DIRECT,
                    maxNumRays = 512,
                    maxDuration = 0.5f,
                    maxOrder = 1,
                    maxNumSources = 4,
                    samplingRate = settings.samplingRate,
                    frameSize = settings.frameSize,
                )
                assertNoLeak("Simulator + Source", allocator) {
                    Simulator(context, simulationSettings).use { simulator ->
                        simulator.setScene(scene)
                        simulator.commit()
                        Source(simulator, SimulationFlags.DIRECT).use { source ->
                            source.addTo(simulator)
                            simulator.commit()
                            source.removeFrom(simulator)
                            simulator.commit()
                        }
                    }
                }
            }
        }
    }

    @Test
    fun sourcesCreatedAndDestroyedRepeatedlyDoNotLeak() {
        if (!available()) return
        // The realistic pattern for a game: one long-lived simulator, sources coming and going
        // as entities spawn and die. This is where a leak would actually bite.
        withCountedContext { context, allocator ->
            Scene(context).use { scene ->
                scene.commit()
                Simulator(
                    context,
                    SimulationSettings(
                        flags = SimulationFlags.DIRECT,
                        maxNumRays = 512,
                        maxDuration = 0.5f,
                        maxNumSources = 8,
                        samplingRate = settings.samplingRate,
                        frameSize = settings.frameSize,
                    ),
                ).use { simulator ->
                    simulator.setScene(scene)
                    simulator.commit()
                    assertNoLeak("Source churn against a live Simulator", allocator) {
                        Source(simulator, SimulationFlags.DIRECT).use { source ->
                            source.addTo(simulator)
                            simulator.commit()
                            source.removeFrom(simulator)
                            simulator.commit()
                        }
                    }
                }
            }
        }
    }

    @Test
    fun energyFieldAndImpulseResponseDoNotLeak() {
        if (!available()) return
        withCountedContext { context, allocator ->
            assertNoLeak("EnergyField", allocator) {
                EnergyField(context, duration = 0.5f, order = 1).close()
            }
            assertNoLeak("ImpulseResponse", allocator) {
                ImpulseResponse(context, duration = 0.5f, order = 1).close()
            }
        }
    }

    @Test
    fun probesDoNotLeak() {
        if (!available()) return
        withCountedContext { context, allocator ->
            assertNoLeak("ProbeArray", allocator) {
                ProbeArray(context).close()
            }
            assertNoLeak("ProbeBatch", allocator) {
                ProbeBatch(context).use { batch ->
                    batch.addProbe(Sphere(Vector3.ZERO, 1f))
                    batch.commit()
                }
            }
        }
    }

    @Test
    fun serializedObjectDoesNotLeak() {
        if (!available()) return
        withCountedContext { context, allocator ->
            assertNoLeak("SerializedObject", allocator) {
                SerializedObject(context).close()
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Reference-count semantics
    // ---------------------------------------------------------------------------------------------

    @Test
    fun retainRequiresAMatchingClose() {
        if (!available()) return
        withCountedContext { context, allocator ->
            assertNoLeak("Hrtf retain/close pairs", allocator) {
                Hrtf.default(context, settings).use { first ->
                    // A retained wrapper shares the native object; both must be closed for the
                    // reference count to reach zero.
                    first.retain().close()
                }
            }
        }
    }

    @Test
    fun retainKeepsTheObjectAliveAfterTheOriginalCloses() {
        if (!available()) return
        withCountedContext { context, _ ->
            val original = Hrtf.default(context, settings)
            val retained = original.retain()
            original.close()

            assertTrue(original.isClosed)
            assertTrue(!retained.isClosed)

            // Still usable: the native object outlives the first wrapper because the retain
            // incremented its reference count.
            BinauralEffect(context, settings, retained).use { effect ->
                Arena.ofConfined().use { arena ->
                    val input = AudioBuffer(arena, 1, settings.frameSize)
                    val output = AudioBuffer(arena, 2, settings.frameSize)
                    val params = BinauralEffectParams(arena, retained)
                    input[0, 0] = 1f
                    effect.apply(params, input, output)
                    assertTrue(output.peak() > 0f, "a retained HRTF should still render")
                }
            }
            retained.close()
        }
    }

    @Test
    fun doubleCloseIsSafeAndDecrementsOnlyOnce() {
        if (!available()) return
        withCountedContext { context, allocator ->
            assertNoLeak("double close", allocator) {
                val hrtf = Hrtf.default(context, settings)
                hrtf.close()
                hrtf.close()
                hrtf.close()
            }
        }
    }

    @Test
    fun useAfterCloseThrowsRatherThanCrashing() {
        if (!available()) return
        withCountedContext { context, _ ->
            val hrtf = Hrtf.default(context, settings)
            hrtf.close()
            // The point of the liveHandle check: a use-after-close must produce a Kotlin
            // exception, not a native segfault that takes the JVM with it.
            val failure = runCatching { hrtf.retain() }.exceptionOrNull()
            assertTrue(
                failure is IllegalStateException,
                "expected IllegalStateException on use-after-close, got $failure",
            )
        }
    }

    @Test
    fun audioBufferLifetimeFollowsItsArena() {
        if (!available()) return
        // AudioBuffer is arena-backed rather than reference-counted, so there is nothing to
        // leak -- but confirm the arena really does reclaim it.
        val allocator = CountingAllocator()
        SteamAudioContext(allocator = allocator).use {
            repeat(200) {
                Arena.ofConfined().use { arena ->
                    val buffer = AudioBuffer(arena, 4, 1024)
                    buffer.clear()
                    assertEquals(4 * 1024, buffer.totalSamples)
                }
            }
        }
        // Buffers come from a JVM arena, not Steam Audio's allocator, so nothing should have
        // gone through the counter beyond context setup.
        assertTrue(
            allocator.outstandingBlocks < 64,
            "unexpected native allocations outstanding: ${allocator.snapshot()}",
        )
    }
}
