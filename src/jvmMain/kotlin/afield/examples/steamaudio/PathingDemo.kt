package afield.examples.steamaudio

import afield.four.audio.steamaudio.AudioBuffer
import afield.four.audio.steamaudio.AudioSettings
import afield.four.audio.steamaudio.BinauralEffect
import afield.four.audio.steamaudio.BinauralEffectParams
import afield.four.audio.steamaudio.DirectEffect
import afield.four.audio.steamaudio.DirectEffectFlags
import afield.four.audio.steamaudio.DirectEffectParams
import afield.four.audio.steamaudio.DirectSimulationFlags
import afield.four.audio.steamaudio.Hrtf
import afield.four.audio.steamaudio.Material
import afield.four.audio.steamaudio.Matrix4x4
import afield.four.audio.steamaudio.OcclusionType
import afield.four.audio.steamaudio.PathBakeSettings
import afield.four.audio.steamaudio.PathBaker
import afield.four.audio.steamaudio.PathEffect
import afield.four.audio.steamaudio.PathEffectParams
import afield.four.audio.steamaudio.ProbeArray
import afield.four.audio.steamaudio.ProbeBatch
import afield.four.audio.steamaudio.ProbeGenerationParams
import afield.four.audio.steamaudio.Scene
import afield.four.audio.steamaudio.SimulationFlags
import afield.four.audio.steamaudio.SimulationInputs
import afield.four.audio.steamaudio.SimulationOutputs
import afield.four.audio.steamaudio.SimulationSettings
import afield.four.audio.steamaudio.SimulationSharedInputs
import afield.four.audio.steamaudio.Simulator
import afield.four.audio.steamaudio.Source
import afield.four.audio.steamaudio.SpeakerLayout
import afield.four.audio.steamaudio.SteamAudio
import afield.four.audio.steamaudio.SteamAudioContext
import afield.four.audio.steamaudio.WallSegment
import afield.four.audio.steamaudio.WallSet
import afield.four.audio.steamaudio.World2D
import java.lang.foreign.Arena
import java.nio.file.Files
import java.nio.file.Path

/**
 * Two rooms, one doorway, and a source you cannot see.
 *
 * Headphones. This isolates what pathing adds over occlusion. The geometry is a single
 * rectangular space divided by a wall, with a gap near one end:
 *
 * ```
 *        y=+5  +-------------------+-------------------+
 *                                  :   <- doorway (y 3..5)
 *                                  |
 *              |   listener room   |   source room     |
 *              |        L->        |        S          |
 *                                  |
 *        y=-5  +-------------------+-------------------+
 *            x=-10               x=0                x=+10
 * ```
 *
 * The listener faces east, straight at the dividing wall. The source sits directly opposite,
 * behind it. The only way sound can reach the listener is around through the doorway, which
 * from the listener's position lies ahead and to the **left** (north).
 *
 * Three movements:
 *
 *  1. **Same room** -- source on the listener's side. Reference: an unobstructed direct path.
 *  2. **Occlusion only** -- source moved behind the wall, direct simulation only. What you hear
 *     is the little that transmits *through* the wall: quiet, and dull, and coming from
 *     straight ahead because that is where the wall is.
 *  3. **Occlusion plus pathing** -- identical geometry, pathing enabled. Sound now arrives via
 *     the doorway: louder, and from the left rather than from dead ahead. That shift is the
 *     whole point of pathing, and it is the difference between "muffled" and "round there".
 */
private const val SAMPLING_RATE = 48_000
private const val FRAME_SIZE = 512
/**
 * Ambisonic order for the pathed field, overridable with `-Dsteamaudio.demo.order`.
 *
 * Unlike a diffuse reverb tail, a pathed arrival has a definite direction -- the opening it
 * came through -- so this is the case where order should matter.
 */
private val AMBISONIC_ORDER: Int = System.getProperty("steamaudio.demo.order")?.toInt() ?: 1

/** Mute the direct path, leaving only the pathed contribution. */
private val PATHING_ONLY: Boolean =
    System.getProperty("steamaudio.demo.pathingOnly")?.toBoolean() ?: false

/** Probe spacing, in metres. Closer spacing finds more routes but costs bake time and memory. */
private const val PROBE_SPACING = 1.5f

fun main() {
    val settings = AudioSettings(samplingRate = SAMPLING_RATE, frameSize = FRAME_SIZE)
    val suffix = buildString {
        if (System.getProperty("steamaudio.demo.order") != null) append("-order$AMBISONIC_ORDER")
        if (PATHING_ONLY) append("-pathonly")
    }
    val output = Path.of("build/demo/pathing$suffix.wav")
    Files.createDirectories(output.parent)

    // Both ears in the plane of the geometry; the extrusion is the acoustic thickness.
    val world = World2D(floorDepth = -1.75f, ceilingDepth = 1.75f, earDepth = 0f)

    println("Steam Audio ${SteamAudio.VERSION} - pathing demo (2D, two rooms and a doorway)")

    SteamAudioContext().use { context ->
        Arena.ofShared().use { arena ->
            Scene(context).use { scene ->
                // ---- Geometry: outer boundary plus a divider with a gap. ----
                val walls = WallSet(
                    world,
                    listOf(
                        // Outer boundary.
                        WallSegment(-10f, -5f, 10f, -5f, Material.CONCRETE),
                        WallSegment(10f, -5f, 10f, 5f, Material.CONCRETE),
                        WallSegment(10f, 5f, -10f, 5f, Material.CONCRETE),
                        WallSegment(-10f, 5f, -10f, -5f, Material.CONCRETE),
                        // Divider, stopping short of the top so a doorway is left at y 3..5.
                        WallSegment(0f, -5f, 0f, 3f, Material.CONCRETE),
                    ),
                )
                println("  ${walls.segments.size} wall segments -> ${walls.triangleCount()} triangles")

                val mesh = walls.build(scene, bounds = floatArrayOf(-10f, -5f, 10f, 5f))
                mesh.addTo(scene)
                scene.commit()

                // ---- Probes and the pathing bake. ----
                // Pathing searches a precomputed visibility graph, so without probes and a
                // bake there is nothing to search and the effect renders silence.
                ProbeArray(context).use { probeArray ->
                    probeArray.generate(
                        scene,
                        ProbeGenerationParams(
                            spacing = PROBE_SPACING,
                            // Probes sit this far above the floor cap, which places them at
                            // ear level given the world above.
                            height = 1.75f,
                            // Maps the unit cube onto the room: 20 x thickness x 10.
                            transform = Matrix4x4.translation(0f, 0f, 0f) *
                                Matrix4x4.scale(20f, world.thickness, 10f),
                        ),
                    )
                    println("  generated ${probeArray.size} probes at ${PROBE_SPACING} m spacing")
                    check(probeArray.size > 0) {
                        "no probes were generated -- pathing cannot work. Check that the floor " +
                            "cap exists and that the generation transform covers the room."
                    }

                    ProbeBatch(context).use { probeBatch ->
                        probeBatch.addProbeArray(probeArray)
                        probeBatch.commit()

                        print("  baking pathing visibility graph")
                        var lastDecile = -1
                        PathBaker.bake(
                            context,
                            PathBakeSettings(
                                scene = scene,
                                probeBatch = probeBatch,
                                numSamples = 4,
                                radius = 1.0f,
                                threshold = 0.1f,
                                visRange = 30f,
                                pathRange = 60f,
                            ),
                        ) { progress ->
                            val decile = (progress * 10).toInt()
                            if (decile > lastDecile) {
                                lastDecile = decile
                                print(".")
                                System.out.flush()
                            }
                        }
                        println(" done")

                        val dataSize = probeBatch.dataSize(
                            afield.four.audio.steamaudio.BakedDataIdentifier.PATHING,
                        )
                        println("  baked pathing data: $dataSize bytes")
                        check(dataSize > 0) { "the bake produced no pathing data" }

                        render(context, settings, world, arena, scene, probeBatch, output)
                    }
                }
            }
        }
    }
}

private fun render(
    context: SteamAudioContext,
    settings: AudioSettings,
    world: World2D,
    arena: Arena,
    scene: Scene,
    probeBatch: ProbeBatch,
    output: Path,
) {
    val simulationSettings = SimulationSettings(
        flags = SimulationFlags.DIRECT or SimulationFlags.PATHING,
        maxNumRays = 4096,
        maxDuration = 1.0f,
        maxOrder = AMBISONIC_ORDER,
        maxNumSources = 2,
        numVisSamples = 4,
        samplingRate = SAMPLING_RATE,
        frameSize = FRAME_SIZE,
    )

    Simulator(context, simulationSettings).use { simulator ->
        simulator.setScene(scene)
        simulator.addProbeBatch(probeBatch)
        simulator.commit()

        Source(simulator, SimulationFlags.DIRECT or SimulationFlags.PATHING).use { source ->
            source.addTo(simulator)
            simulator.commit()

            Hrtf.default(context, settings).use { hrtf ->
                DirectEffect(context, settings, channelCount = 1).use { direct ->
                    BinauralEffect(context, settings, hrtf).use { binaural ->
                        PathEffect(
                            context, settings, hrtf,
                            maxOrder = AMBISONIC_ORDER,
                            speakerLayout = SpeakerLayout.STEREO,
                        ).use { path ->
                            val renderer = PathingRenderer(
                                context, settings, world, arena,
                                simulator, source, direct, binaural, path, hrtf, probeBatch,
                            )
                            renderer.run()
                            renderer.writeTo(output)
                        }
                    }
                }
            }
        }
    }
}

private class PathingRenderer(
    private val context: SteamAudioContext,
    private val settings: AudioSettings,
    private val world: World2D,
    arena: Arena,
    private val simulator: Simulator,
    private val source: Source,
    private val direct: DirectEffect,
    private val binaural: BinauralEffect,
    private val path: PathEffect,
    hrtf: Hrtf,
    probeBatch: ProbeBatch,
) {
    private val dry = AudioBuffer(arena, 1, FRAME_SIZE)
    private val shaped = AudioBuffer(arena, 1, FRAME_SIZE)
    private val directStereo = AudioBuffer(arena, 2, FRAME_SIZE)
    private val pathStereo = AudioBuffer(arena, 2, FRAME_SIZE)

    private val directParams = DirectEffectParams(arena)
    private val binauralParams = BinauralEffectParams(arena, hrtf)
    private val pathParams = PathEffectParams(arena, order = AMBISONIC_ORDER).apply {
        this.hrtf = hrtf
        binaural = true
        normalizeEq = true
    }

    private val inputs = SimulationInputs(
        arena,
        flags = SimulationFlags.DIRECT or SimulationFlags.PATHING,
        directFlags = DirectSimulationFlags.DISTANCE_ATTENUATION or
            DirectSimulationFlags.AIR_ABSORPTION or
            DirectSimulationFlags.OCCLUSION or
            DirectSimulationFlags.TRANSMISSION,
    ).apply {
        occlusionType = OcclusionType.VOLUMETRIC
        occlusionRadius = 0.4f
        numOcclusionSamples = 32
        setPathingProbes(probeBatch)
        visRadius = 1.0f
        visThreshold = 0.1f
        visRange = 30f
        pathingOrder = AMBISONIC_ORDER
        findAlternatePaths = true
    }

    private val shared = SimulationSharedInputs(arena).apply {
        numRays = 4096
        numBounces = 8
        duration = 1.0f
        order = AMBISONIC_ORDER
    }

    private val outputs = SimulationOutputs(arena)
    private val ping = PingGenerator(settings.samplingRate)

    private val collected = ArrayList<FloatArray>()
    private val scratch = FloatArray(FRAME_SIZE * 2)
    private val mix = FloatArray(FRAME_SIZE * 2)

    /** Listener in the left-hand room, facing east, straight at the dividing wall. */
    private val listenerX = -5f
    private val listenerY = 0f

    private var sourceX = -2f
    private var sourceY = 0f
    private var pathingEnabled = false

    /** Diagnostics gathered per movement. */
    val notes = mutableListOf<String>()

    fun run() {
        shared.listener = world.listenerFacing(listenerX, listenerY, 1f, 0f)
        pathParams.listener = shared.listener

        println()
        println("  listener at ($listenerX, $listenerY) facing east; doorway is at (0, 3..5)")
        println()

        println("  movement 1/3: source in the same room, direct path clear")
        sourceX = -2f; sourceY = 0f; pathingEnabled = false
        renderSeconds(6.0, "same room")

        println("  movement 2/3: source behind the wall, occlusion only")
        sourceX = 5f; sourceY = 0f; pathingEnabled = false
        renderSeconds(6.0, "occluded, no pathing")

        println("  movement 3/3: same position, pathing enabled")
        sourceX = 5f; sourceY = 0f; pathingEnabled = true
        renderSeconds(7.5, "occluded, pathing on")
    }

    private fun renderSeconds(seconds: Double, label: String) {
        val blocks = (seconds * settings.samplingRate / FRAME_SIZE).toInt()
        var reported = false
        for (block in 0 until blocks) {
            if (block % 8 == 0) {
                simulate()
                if (!reported) {
                    reported = true
                    val noPath = pathParams.hasNoPath()
                    val eq = (0 until SteamAudio.NUM_BANDS).map { pathParams.eqCoefficient(it) }
                    notes += "%-22s occlusion %.3f  pathFound %-5s  pathEQ %s".format(
                        label,
                        outputs.occlusion,
                        (!noPath).toString(),
                        eq.joinToString(", ") { "%.3f".format(it) },
                    )
                    println("    occlusion ${"%.3f".format(outputs.occlusion)}, " +
                        "path ${if (noPath) "not found" else "found"}, " +
                        "pathEQ ${eq.joinToString(", ") { "%.2f".format(it) }}")
                }
            }
            renderBlock()
        }
    }

    private fun simulate() {
        inputs.source = world.listenerFacing(sourceX, sourceY, 1f, 0f)
        inputs.flags = if (pathingEnabled) {
            SimulationFlags.DIRECT or SimulationFlags.PATHING
        } else {
            SimulationFlags.DIRECT
        }
        source.setInputs(SimulationFlags.DIRECT or SimulationFlags.PATHING, inputs)
        simulator.setSharedInputs(SimulationFlags.DIRECT or SimulationFlags.PATHING, shared)

        simulator.runDirect()
        if (pathingEnabled) simulator.runPathing()

        source.getOutputs(SimulationFlags.DIRECT or SimulationFlags.PATHING, outputs)

        outputs.copyDirectInto(directParams)
        directParams.flags = DirectEffectFlags.APPLY_DISTANCE_ATTENUATION or
            DirectEffectFlags.APPLY_AIR_ABSORPTION or
            DirectEffectFlags.APPLY_OCCLUSION or
            DirectEffectFlags.APPLY_TRANSMISSION

        if (pathingEnabled) {
            pathParams.copySimulatedFrom(outputs)
            pathParams.listener = shared.listener
        }
    }

    private fun renderBlock() {
        ping.fill(dry)

        // Direct path: attenuated and dulled by the wall it has to pass through.
        direct.apply(directParams, dry, shaped)
        val direction = context.calculateRelativeDirection(
            sourcePosition = world.point(sourceX, sourceY),
            listenerPosition = shared.listener.origin,
            listenerAhead = shared.listener.ahead,
            listenerUp = shared.listener.up,
        )
        binauralParams.setDirection(direction.x, direction.y, direction.z)
        binaural.apply(binauralParams, shaped, directStereo)
        if (PATHING_ONLY) directStereo.clear()

        // Pathed contribution: the route around through the doorway.
        if (pathingEnabled) {
            path.apply(pathParams, dry, pathStereo)
        } else {
            pathStereo.clear()
        }

        emit()
    }

    private fun emit() {
        directStereo.interleaveTo(context, scratch)
        System.arraycopy(scratch, 0, mix, 0, mix.size)
        pathStereo.interleaveTo(context, scratch)
        for (index in mix.indices) mix[index] += scratch[index]
        collected.add(mix.copyOf())
    }

    fun writeTo(path: Path) {
        val total = collected.sumOf { it.size }
        val samples = FloatArray(total)
        var offset = 0
        for (chunk in collected) {
            chunk.copyInto(samples, offset)
            offset += chunk.size
        }
        // Deliberately NOT normalised per movement: the level differences between occluded and
        // pathed are the thing being demonstrated, so one gain is applied to the whole file.
        val loudest = samples.maxOf { kotlin.math.abs(it) }
        if (loudest > 0f) {
            val gain = 0.89f / loudest
            for (index in samples.indices) samples[index] *= gain
        }
        WavWriter.write(path, samples, channelCount = 2, samplingRate = SAMPLING_RATE)

        println()
        notes.forEach { println("  $it") }
        println()
        println("  wrote $path")
        println("  ${"%.1f".format(samples.size / 2.0 / SAMPLING_RATE)} s stereo, one gain for the whole file")
        println("  listen on headphones")
    }
}
