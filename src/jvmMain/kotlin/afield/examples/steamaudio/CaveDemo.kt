package afield.examples.steamaudio

import afield.four.audio.steamaudio.AmbisonicsDecodeEffect
import afield.four.audio.steamaudio.AmbisonicsDecodeEffectParams
import afield.four.audio.steamaudio.AudioBuffer
import afield.four.audio.steamaudio.AudioSettings
import afield.four.audio.steamaudio.BinauralEffect
import afield.four.audio.steamaudio.BinauralEffectParams
import afield.four.audio.steamaudio.Box
import afield.four.audio.steamaudio.CoordinateSpace3
import afield.four.audio.steamaudio.DirectEffect
import afield.four.audio.steamaudio.DirectEffectFlags
import afield.four.audio.steamaudio.DirectEffectParams
import afield.four.audio.steamaudio.DirectSimulationFlags
import afield.four.audio.steamaudio.Hrtf
import afield.four.audio.steamaudio.InstancedMesh
import afield.four.audio.steamaudio.Material
import afield.four.audio.steamaudio.Matrix4x4
import afield.four.audio.steamaudio.OcclusionType
import afield.four.audio.steamaudio.ReflectionEffect
import afield.four.audio.steamaudio.ReflectionEffectParams
import afield.four.audio.steamaudio.ReflectionEffectType
import afield.four.audio.steamaudio.Scene
import afield.four.audio.steamaudio.SimulationFlags
import afield.four.audio.steamaudio.SimulationInputs
import afield.four.audio.steamaudio.SimulationOutputs
import afield.four.audio.steamaudio.SimulationSettings
import afield.four.audio.steamaudio.SimulationSharedInputs
import afield.four.audio.steamaudio.Simulator
import afield.four.audio.steamaudio.StaticMesh
import afield.four.audio.steamaudio.SteamAudio
import afield.four.audio.steamaudio.SteamAudioContext
import afield.four.audio.steamaudio.Vector3
import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * A reverberant cave built from planes, with live-simulated reflections.
 *
 * Headphones. The source is a sonic probe: a sharp broadband ping every second and a half,
 * with silence in between so that what you hear between pings is entirely the space answering
 * back.
 *
 * Five movements, each isolating one thing:
 *
 *  1. **The space alone** -- listener and source both still, near the middle. Establishes what
 *     the cave sounds like. Pings arrive, then the reflections wash back off the rock.
 *  2. **The source recedes** -- it travels away down the long axis. The direct sound gets quiet
 *     and dull while the reverb stays, so the ratio between them shifts. That ratio is the main
 *     cue for distance in a reverberant space, far more than level alone.
 *  3. **Planes close in** -- four slabs swing inward until they nearly surround the listener.
 *     Reflections arrive sooner and tighter; the space audibly shrinks around you without
 *     anything else changing.
 *  4. **A curtain occludes** -- an absorbing plane slides between source and listener. The
 *     direct path is blocked, so what is left arrives indirectly, off the walls. This is the
 *     sound of something being *behind* geometry rather than merely far away.
 *  5. **The tail** -- the source stops. The reverb is drained to completion rather than cut,
 *     which in a space this live takes seconds.
 */
private const val SAMPLING_RATE = 48_000
private const val FRAME_SIZE = 512

/** Ambisonic order for the reflected field. Order 1 (4 channels) suits a diffuse tail. */
private const val AMBISONIC_ORDER = 1

/** How long a reverb tail to simulate and render, in seconds. */
private const val REVERB_DURATION = 3.0f

/**
 * How often the simulation runs, in Hz.
 *
 * Reflections change slowly compared with audio, so simulating far below block rate is normal
 * and is what keeps the cost manageable. This mirrors the real arrangement: a simulation thread
 * at ~15 Hz, the audio thread reading the latest completed result every block.
 */
private const val SIMULATION_HZ = 15.0

/** The cave: 26 m long, 20 m wide, 9 m high. */
private val CAVE = Box(Vector3(-10f, -2f, -13f), Vector3(10f, 7f, 13f))

fun main() {
    val settings = AudioSettings(samplingRate = SAMPLING_RATE, frameSize = FRAME_SIZE)
    val output = Path.of("build/demo/cave.wav")
    Files.createDirectories(output.parent)

    println("Steam Audio ${SteamAudio.VERSION} - reverberant cave demo")
    println("  ${settings.samplingRate} Hz, block ${settings.frameSize}, " +
        "Ambisonic order $AMBISONIC_ORDER, reverb ${REVERB_DURATION}s")

    SteamAudioContext().use { context ->
        Arena.ofShared().use { arena ->
            // ---------------------------------------------------------------------------------
            // Geometry: a rock shell, fixed slabs, and movable planes.
            // ---------------------------------------------------------------------------------
            Scene(context).use { scene ->
                val geometry = mutableListOf<AutoCloseable>()

                val shell = StaticMesh.box(scene, CAVE, Material.ROCK, inwardFacing = true)
                shell.addTo(scene)
                geometry += shell

                // Fixed slabs jutting into the space, so reflections are not just the six
                // walls of a box. An empty box sounds like a box; clutter sounds like a cave.
                val slabs = listOf(
                    quad(Vector3(-4f, -2f, -6f), Vector3(-4f, 4f, -6f), Vector3(-4f, 4f, 1f), Vector3(-4f, -2f, 1f)),
                    quad(Vector3(5f, -2f, -2f), Vector3(5f, 5f, -2f), Vector3(5f, 5f, 6f), Vector3(5f, -2f, 6f)),
                    quad(Vector3(-7f, 3f, 4f), Vector3(2f, 3f, 4f), Vector3(2f, 3f, 10f), Vector3(-7f, 3f, 10f)),
                )
                for ((a, b, c, d) in slabs) {
                    val slab = StaticMesh.plane(scene, a, b, c, d, Material.ROCK)
                    slab.addTo(scene)
                    geometry += slab
                }

                // Movable planes. Each lives in its own sub-scene and is instanced into the
                // main scene, so moving it is a transform update rather than a rebuild.
                val panelScene = Scene(context)
                geometry += panelScene
                val panel = StaticMesh.plane(
                    panelScene,
                    Vector3(-2.5f, -2.5f, 0f), Vector3(2.5f, -2.5f, 0f),
                    Vector3(2.5f, 2.5f, 0f), Vector3(-2.5f, 2.5f, 0f),
                    Material.CONCRETE,
                )
                panel.addTo(panelScene)
                panelScene.commit()
                geometry += panel

                val closers = List(4) { index ->
                    val instance =
                        InstancedMesh(scene, panelScene, panelTransform(index, PANEL_RESTING_RADIUS))
                    instance.addTo(scene)
                    geometry += instance
                    instance
                }

                // The occluding curtain: heavily absorbing, parked out of the way to begin with.
                val curtainScene = Scene(context)
                geometry += curtainScene
                val curtainMesh = StaticMesh.plane(
                    curtainScene,
                    Vector3(-3f, -2.5f, 0f), Vector3(3f, -2.5f, 0f),
                    Vector3(3f, 3.5f, 0f), Vector3(-3f, 3.5f, 0f),
                    Material.CURTAIN,
                )
                curtainMesh.addTo(curtainScene)
                curtainScene.commit()
                geometry += curtainMesh

                val curtain = InstancedMesh(scene, curtainScene, Matrix4x4.translation(0f, 0f, 40f))
                curtain.addTo(scene)
                geometry += curtain

                scene.commit()
                println("  scene committed: rock shell + ${slabs.size} slabs + " +
                    "${closers.size} movable panels + 1 curtain")

                // ---------------------------------------------------------------------------------
                // Simulation and effects.
                // ---------------------------------------------------------------------------------
                val simulationSettings = SimulationSettings(
                    flags = SimulationFlags.DIRECT or SimulationFlags.REFLECTIONS,
                    reflectionType = ReflectionEffectType.HYBRID,
                    // A big dark space needs plenty of rays, or surfaces get missed and the
                    // reverb flickers as things move.
                    maxNumRays = 8192,
                    numDiffuseSamples = 1024,
                    maxDuration = REVERB_DURATION,
                    maxOrder = AMBISONIC_ORDER,
                    maxNumSources = 4,
                    samplingRate = SAMPLING_RATE,
                    frameSize = FRAME_SIZE,
                )

                Simulator(context, simulationSettings).use { simulator ->
                    simulator.setScene(scene)
                    simulator.commit()

                    afield.four.audio.steamaudio.Source(
                        simulator,
                        SimulationFlags.DIRECT or SimulationFlags.REFLECTIONS,
                    ).use { source ->
                        source.addTo(simulator)
                        simulator.commit()

                        Hrtf.default(context, settings).use { hrtf ->
                            DirectEffect(context, settings, channelCount = 1).use { direct ->
                                BinauralEffect(context, settings, hrtf).use { binaural ->
                                    ReflectionEffect(
                                        context, settings,
                                        type = ReflectionEffectType.HYBRID,
                                        duration = REVERB_DURATION,
                                        ambisonicOrder = AMBISONIC_ORDER,
                                    ).use { reflection ->
                                        AmbisonicsDecodeEffect(
                                            context, settings, hrtf,
                                            maxOrder = AMBISONIC_ORDER,
                                        ).use { decode ->
                                            val cave = CaveRenderer(
                                                context, settings, arena,
                                                scene, simulator, source,
                                                direct, binaural, reflection, decode, hrtf,
                                                closers, curtain,
                                            )
                                            cave.run()
                                            cave.writeTo(output)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                geometry.reversed().forEach { it.close() }
            }
        }
    }
}

/** A plane's four corners. */
private data class Quad(val a: Vector3, val b: Vector3, val c: Vector3, val d: Vector3)

private fun quad(a: Vector3, b: Vector3, c: Vector3, d: Vector3) = Quad(a, b, c, d)

/**
 * Where the four movable panels sit at a given closing radius, arranged around the listener.
 *
 * They are deliberately kept **off the corridor between source and listener**, which runs along
 * `x = 0` towards `-z`: two panels to the left and right, two behind. An earlier version of this
 * demo parked a panel at `(0, 1, -9)`, squarely on that corridor, and the source passed behind
 * it partway through movement 2 -- which occluded the direct path and made a movement about
 * distance sound like a movement about occlusion. The simulation was right; the layout was not.
 * Occlusion belongs to movement 4, where it is the deliberate subject.
 */
private fun panelTransform(index: Int, radius: Double): Matrix4x4 {
    val listenerZ = 6.0
    val height = 1.6f
    return when (index) {
        0 -> Matrix4x4.translation(-radius.toFloat(), height, listenerZ.toFloat())
        1 -> Matrix4x4.translation(radius.toFloat(), height, listenerZ.toFloat())
        2 -> Matrix4x4.translation(
            (-radius * 0.7).toFloat(),
            height,
            (listenerZ + radius * 0.7).toFloat(),
        )
        else -> Matrix4x4.translation(
            (radius * 0.7).toFloat(),
            height,
            (listenerZ + radius * 0.7).toFloat(),
        )
    }
}

/** Radius at which the panels are parked, far enough out to barely colour the space. */
private const val PANEL_RESTING_RADIUS = 8.0

/** Radius at which the panels have closed in tight around the listener. */
private const val PANEL_CLOSED_RADIUS = 2.8

/**
 * Runs the cave render.
 *
 * The structure here mirrors how this is wired up in a real application, which is the point of
 * the demo as much as the sound is:
 *
 *  - Simulation runs at [SIMULATION_HZ], not block rate, and would live on its own thread.
 *  - The audio path runs every block and only ever *reads* the latest simulation result via
 *    [afield.four.audio.steamaudio.Source.getOutputs], which is cheap and non-blocking.
 *  - Every buffer and parameter block is allocated once, up front.
 */
private class CaveRenderer(
    private val context: SteamAudioContext,
    private val settings: AudioSettings,
    arena: Arena,
    private val scene: Scene,
    private val simulator: Simulator,
    private val source: afield.four.audio.steamaudio.Source,
    private val direct: DirectEffect,
    private val binaural: BinauralEffect,
    private val reflection: ReflectionEffect,
    private val decode: AmbisonicsDecodeEffect,
    hrtf: Hrtf,
    private val closers: List<InstancedMesh>,
    private val curtain: InstancedMesh,
) {
    private val ambisonicChannels = SteamAudio.ambisonicChannelCount(AMBISONIC_ORDER)

    private val dry = AudioBuffer(arena, 1, FRAME_SIZE)
    private val shaped = AudioBuffer(arena, 1, FRAME_SIZE)
    private val directStereo = AudioBuffer(arena, 2, FRAME_SIZE)
    private val ambisonic = AudioBuffer(arena, ambisonicChannels, FRAME_SIZE)
    private val reflectedStereo = AudioBuffer(arena, 2, FRAME_SIZE)

    private val directParams = DirectEffectParams(arena)
    private val binauralParams = BinauralEffectParams(arena, hrtf)
    private val reflectionParams = ReflectionEffectParams(arena).apply {
        // The simulator does not fill these three in -- they describe the effect, not the
        // simulation -- so they are set once from the effect itself.
        applyShapeOf(reflection)
    }
    private val decodeParams = AmbisonicsDecodeEffectParams(
        arena,
        order = AMBISONIC_ORDER,
        binaural = true,
        hrtf = hrtf,
    )

    private val simulationInputs = SimulationInputs(
        arena,
        flags = SimulationFlags.DIRECT or SimulationFlags.REFLECTIONS,
        directFlags = DirectSimulationFlags.DISTANCE_ATTENUATION or
            DirectSimulationFlags.AIR_ABSORPTION or
            DirectSimulationFlags.OCCLUSION or
            DirectSimulationFlags.TRANSMISSION,
    ).apply {
        // Volumetric occlusion gives partial shadowing at edges rather than a hard on/off,
        // which matters when a plane slides across the direct path.
        occlusionType = OcclusionType.VOLUMETRIC
        occlusionRadius = 0.6f
        numOcclusionSamples = 32
        hybridReverbTransitionTime = 0.5f
    }

    private val sharedInputs = SimulationSharedInputs(arena).apply {
        numRays = 8192
        // A live space is mostly high-order reflections; too few bounces sounds dead.
        numBounces = 32
        duration = REVERB_DURATION
        order = AMBISONIC_ORDER
        irradianceMinDistance = 0.5f
    }

    private val outputs = SimulationOutputs(arena)

    private val ping = PingGenerator(settings.samplingRate)
    private val collected = ArrayList<FloatArray>()
    private val interleaveScratch = FloatArray(FRAME_SIZE * 2)
    private val mix = FloatArray(FRAME_SIZE * 2)

    private val listener = CoordinateSpace3.lookingAt(
        origin = Vector3(0f, 1.6f, 6f),
        ahead = Vector3(0f, 0f, -1f),
    )

    private var sourcePosition = Vector3(0f, 1.6f, -2f)
    private var blocksRendered = 0
    private var simulationRuns = 0
    private var peak = 0f

    private val blocksPerSimulation =
        (settings.samplingRate / (FRAME_SIZE * SIMULATION_HZ)).toInt().coerceAtLeast(1)

    fun run() {
        sharedInputs.listener = listener

        println("  simulating every $blocksPerSimulation blocks " +
            "(~${"%.1f".format(settings.samplingRate.toDouble() / FRAME_SIZE / blocksPerSimulation)} Hz)")

        println("  movement 1/5: the space alone")
        render(6.0) { }

        println("  movement 2/5: the source recedes down the long axis")
        render(9.0) { progress ->
            sourcePosition = Vector3(0f, 1.6f, (-2.0 - 9.5 * progress).toFloat())
        }

        println("  movement 3/5: planes close in around the listener")
        // Bring the source back to a middle distance so the panels, not the distance, are what
        // changes in this movement.
        sourcePosition = Vector3(0f, 1.6f, -5f)
        render(9.0) { progress ->
            val closeness = smoothStep(progress)
            val radius = PANEL_RESTING_RADIUS -
                (PANEL_RESTING_RADIUS - PANEL_CLOSED_RADIUS) * closeness
            closers.forEachIndexed { index, instance ->
                instance.updateTransform(scene, panelTransform(index, radius))
            }
            // One commit for all four panels, not one each.
            scene.commit()
        }

        println("  movement 4/5: an absorbing curtain slides across the direct path")
        // Park the panels back out of the way so the curtain is the only thing changing.
        closers.forEachIndexed { index, instance ->
            instance.updateTransform(scene, panelTransform(index, PANEL_RESTING_RADIUS))
        }
        scene.commit()
        render(9.0) { progress ->
            val slide = smoothStep(progress)
            // Travels from off to one side, across the line between source and listener.
            curtain.updateTransform(
                scene,
                Matrix4x4.translation((-12.0 + 12.0 * slide).toFloat(), 1.0f, 1.0f),
            )
            scene.commit()
        }

        println("  movement 5/5: source stops, draining the reverb tail")
        drainTail()

        println("  rendered $blocksRendered blocks, $simulationRuns simulation runs, " +
            "peak ${"%.3f".format(peak)}")
    }

    /** Renders [seconds] of audio, calling [update] once per simulation tick. */
    private inline fun render(seconds: Double, update: (Double) -> Unit) {
        val blocks = (seconds * settings.samplingRate / FRAME_SIZE).toInt()
        for (block in 0 until blocks) {
            if (block % blocksPerSimulation == 0) {
                update(block.toDouble() / blocks)
                simulate()
            }
            renderBlock(silent = false)
        }
    }

    /**
     * One simulation tick: publish inputs, run, read results into the effect parameters.
     *
     * In a real application this whole method is what lives on the simulation thread.
     */
    private fun simulate() {
        simulationInputs.source = CoordinateSpace3.lookingAt(
            origin = sourcePosition,
            ahead = Vector3(0f, 0f, -1f),
        )
        source.setInputs(SimulationFlags.DIRECT or SimulationFlags.REFLECTIONS, simulationInputs)
        simulator.setSharedInputs(
            SimulationFlags.DIRECT or SimulationFlags.REFLECTIONS,
            sharedInputs,
        )

        simulator.runDirect()
        simulator.runReflections()          // blocking, expensive, off the audio thread

        source.getOutputs(SimulationFlags.DIRECT or SimulationFlags.REFLECTIONS, outputs)

        // Direct path: take the simulated values, then state explicitly which of them to apply.
        outputs.copyDirectInto(directParams)
        directParams.flags = DirectEffectFlags.APPLY_DISTANCE_ATTENUATION or
            DirectEffectFlags.APPLY_AIR_ABSORPTION or
            DirectEffectFlags.APPLY_OCCLUSION or
            DirectEffectFlags.APPLY_TRANSMISSION

        // Reflections: preserves the three shape fields the simulator does not own.
        reflectionParams.copySimulatedFrom(outputs)

        simulationRuns++
    }

    /** One audio block. Everything here is allocation-free and audio-thread safe. */
    private fun renderBlock(silent: Boolean) {
        if (silent) dry.clear() else ping.fill(dry)

        // Direct path: shaping, then spatialisation.
        direct.apply(directParams, dry, shaped)
        val direction = context.calculateRelativeDirection(
            sourcePosition = sourcePosition,
            listenerPosition = listener.origin,
            listenerAhead = listener.ahead,
            listenerUp = listener.up,
        )
        binauralParams.setDirection(direction.x, direction.y, direction.z)
        binaural.apply(binauralParams, shaped, directStereo)

        // Reflections: mono dry in, Ambisonic out, decoded binaurally to stereo.
        reflection.apply(reflectionParams, dry, ambisonic)
        decodeParams.orientation = listener
        decode.apply(decodeParams, ambisonic, reflectedStereo)

        emit()
    }

    /**
     * Drains both tails to completion.
     *
     * In a space with a three-second decay, cutting here instead of draining is immediately
     * audible as the reverb being chopped off.
     */
    private fun drainTail() {
        var blocks = 0
        val maxBlocks = (REVERB_DURATION * 1.5 * settings.samplingRate / FRAME_SIZE).toInt()
        var reflectionDone = false
        var binauralDone = false

        while (blocks < maxBlocks && !(reflectionDone && binauralDone)) {
            if (!binauralDone) {
                binauralDone = binaural.getTail(directStereo) ==
                    afield.four.audio.steamaudio.AudioEffectState.TAIL_COMPLETE
            } else {
                directStereo.clear()
            }

            if (!reflectionDone) {
                reflection.getTail(ambisonic)
                decode.apply(decodeParams, ambisonic, reflectedStereo)
                reflectionDone = reflection.tailSize <= 0
            } else {
                reflectedStereo.clear()
            }

            emit()
            blocks++
        }
        println("    drained $blocks tail blocks " +
            "(${"%.2f".format(blocks * FRAME_SIZE.toDouble() / settings.samplingRate)} s)")
    }

    /** Sums the direct and reflected stereo buses and appends the block to the render. */
    private fun emit() {
        directStereo.interleaveTo(context, interleaveScratch)
        System.arraycopy(interleaveScratch, 0, mix, 0, mix.size)
        reflectedStereo.interleaveTo(context, interleaveScratch)
        for (index in mix.indices) {
            val value = mix[index] + interleaveScratch[index]
            mix[index] = value
            val magnitude = kotlin.math.abs(value)
            if (magnitude > peak) peak = magnitude
        }
        collected.add(mix.copyOf())
        blocksRendered++
    }

    fun writeTo(path: Path) {
        val total = collected.sumOf { it.size }
        val samples = FloatArray(total)
        var offset = 0
        for (chunk in collected) {
            chunk.copyInto(samples, offset)
            offset += chunk.size
        }

        // Normalise to a comfortable level: the direct-plus-reverb sum has no fixed headroom.
        val loudest = samples.maxOf { kotlin.math.abs(it) }
        if (loudest > 0f) {
            val gain = 0.89f / loudest
            for (index in samples.indices) samples[index] *= gain
        }

        WavWriter.write(path, samples, channelCount = 2, samplingRate = SAMPLING_RATE)
        println()
        println("  wrote $path")
        println("  ${"%.1f".format(samples.size / 2.0 / SAMPLING_RATE)} s stereo, " +
            "normalised by ${"%.2f".format(0.89f / loudest)}x")
        println("  listen on headphones")
    }

    /** Smooth ease-in-out, so panel motion does not start and stop abruptly. */
    private fun smoothStep(t: Double): Double {
        val x = t.coerceIn(0.0, 1.0)
        return x * x * (3.0 - 2.0 * x)
    }
}

/**
 * A sonic probe: a sharp broadband ping, then silence.
 *
 * The silence is the point. A continuous source masks its own reverb; a ping followed by a
 * gap lets you hear the space answer, which is both how echolocation works and the only way to
 * actually judge whether a simulated room sounds right.
 */
internal class PingGenerator(private val samplingRate: Int) {
    private var sampleClock = 0L
    private var noiseState = 0x2545F491u

    private val periodSamples = (samplingRate * 1.5).toLong()
    private val attackSamples = (samplingRate * 0.002).toInt()
    private val bodySamples = (samplingRate * 0.09).toInt()

    fun fill(buffer: AudioBuffer) {
        val channel = buffer.channel(0)
        for (sample in 0 until buffer.sampleCount) {
            val into = (sampleClock % periodSamples).toInt()
            var value = 0.0

            if (into < bodySamples) {
                val t = into.toDouble() / samplingRate
                // Sharp noise transient for unambiguous arrival time and broadband ILD.
                if (into < attackSamples) {
                    value += nextNoise() * 0.9 * (1.0 - into.toDouble() / attackSamples)
                }
                // A short tonal body so the ping has pitch to follow through the reverb.
                val decay = exp(-24.0 * t)
                value += 0.55 * decay * (
                    sin(2.0 * PI * 900.0 * t) +
                        0.5 * sin(2.0 * PI * 1810.0 * t) +
                        0.25 * sin(2.0 * PI * 2710.0 * t)
                    ) / 1.75
            }

            channel.setAtIndex(
                ValueLayout.JAVA_FLOAT,
                sample.toLong(),
                value.toFloat().coerceIn(-1f, 1f),
            )
            sampleClock++
        }
    }

    private fun nextNoise(): Double {
        var x = noiseState
        x = x xor (x shl 13)
        x = x xor (x shr 17)
        x = x xor (x shl 5)
        noiseState = x
        return (x.toDouble() / UInt.MAX_VALUE.toDouble()) * 2.0 - 1.0
    }
}
