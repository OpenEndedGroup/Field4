package afield.examples.steamaudio

import afield.four.audio.steamaudio.AmbisonicsDecodeEffect
import afield.four.audio.steamaudio.AmbisonicsDecodeEffectParams
import afield.four.audio.steamaudio.AudioBuffer
import afield.four.audio.steamaudio.AudioSettings
import afield.four.audio.steamaudio.Box
import afield.four.audio.steamaudio.CoordinateSpace3
import afield.four.audio.steamaudio.Hrtf
import afield.four.audio.steamaudio.InstancedMesh
import afield.four.audio.steamaudio.Material
import afield.four.audio.steamaudio.Matrix4x4
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
import afield.four.audio.steamaudio.Source
import afield.four.audio.steamaudio.StaticMesh
import afield.four.audio.steamaudio.SteamAudio
import afield.four.audio.steamaudio.SteamAudioContext
import afield.four.audio.steamaudio.Vector3
import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout
import kotlin.math.log10

/**
 * Measures which ear the *reflected* field arrives from, for a reflector on a known side.
 *
 * The direct binaural path has `HrtfProbe` and `World2DHandednessTest` standing behind it. The
 * reflected path had nothing, which is how a mirrored reverb could go unnoticed: the field that
 * governs it is not one `iplCalculateRelativeDirection` reads, so every existing test stayed
 * green either way.
 *
 * Three measurements, because they isolate different links in the chain:
 *
 *  1. A **static** reflector to the right, then to the left. Tests the simulation and the
 *     Ambisonic decode with geometry baked straight into the scene.
 *  2. An **instanced** reflector placed by `updateTransform` at the same two positions. Any
 *     disagreement with (1) is in the transform path, not in the acoustics.
 *  3. The instanced reflector **swept** across the listener's front. This is exactly what the
 *     cave demo's panels and curtain do, and the measured ILD must sweep the same way.
 *
 * Only the reflection bus is measured; the direct path is never mixed in, so there is nothing
 * to dilute the result. One reflector and nothing else, so the answer is not a matter of degree.
 */
internal const val PROBE_SAMPLING_RATE = 48_000
internal const val PROBE_FRAME_SIZE = 1024
internal const val PROBE_ORDER = 1
internal const val PROBE_DURATION = 2.0f

fun main() {
    println("Reflected-field handedness probe")
    println("  one rock reflector and nothing else; reflection bus only, no direct path")
    println("  positive ILD means the right ear; the reflector's side is what it must agree with")
    println()

    SteamAudioContext().use { context ->
        val settings = AudioSettings(samplingRate = PROBE_SAMPLING_RATE, frameSize = PROBE_FRAME_SIZE)
        Arena.ofShared().use { arena ->
            println(" placement                          ILD dB   heard      expected")
            println(" " + "-".repeat(66))

            for (x in listOf(-4f, 4f)) {
                val ild = Rig(context, settings, arena, instanced = false, placeAt = x).use { it.measure() }
                report("static mesh at x = %+.0f".format(x), ild, x)
            }

            for (x in listOf(-4f, 4f)) {
                val ild = Rig(context, settings, arena, instanced = true, placeAt = x).use { it.measure() }
                report("instanced, updateTransform %+.0f".format(x), ild, x)
            }

            println()
            println(" swept, as the cave demo's panels and curtain move:")
            Rig(context, settings, arena, instanced = true, placeAt = -6f).use { rig ->
                for (x in listOf(-6f, -3f, 0f, 3f, 6f)) {
                    rig.moveTo(x)
                    report("  swept to x = %+.0f".format(x), rig.measure(), x)
                }
            }

            CaveWindowProbe(context, arena).use { it.run() }
        }
    }
}

private fun report(label: String, ild: Double, reflectorX: Float) {
    val heard = if (ild > 0) "RIGHT" else "LEFT"
    val expected = when {
        reflectorX > 0.5f -> "RIGHT"
        reflectorX < -0.5f -> "LEFT"
        else -> "centre"
    }
    val verdict = when {
        expected == "centre" -> ""
        heard == expected -> "  ok"
        else -> "  MIRRORED"
    }
    println(" %-32s %+8.2f   %-10s %s%s".format(label, ild, heard, expected, verdict))
}

/**
 * One scene, simulator and effect chain holding a single reflector.
 *
 * Built per placement rather than reused, so a stale committed scene cannot be mistaken for a
 * measurement -- except in the sweep, where reusing it is the whole point.
 */
internal class Rig(
    private val context: SteamAudioContext,
    settings: AudioSettings,
    arena: Arena,
    instanced: Boolean,
    placeAt: Float,
) : AutoCloseable {

    private val closeables = mutableListOf<AutoCloseable>()
    private val scene = Scene(context).also { closeables += it }
    private var instance: InstancedMesh? = null

    private val listenerOrigin = Vector3(0f, 1.6f, 0f)
    private val ahead = Vector3(0f, 0f, -1f)
    private val listener = CoordinateSpace3.lookingAt(origin = listenerOrigin, ahead = ahead)

    // Straight ahead, so the source direction carries no left/right information of its own and
    // anything lateral in the output can only have come off the reflector.
    private val sourcePosition = Vector3(0f, 1.6f, -6f)

    init {
        if (instanced) {
            val sub = Scene(context).also { closeables += it }
            val quad = reflectorQuad(sub, 0f).also { closeables += it }
            quad.addTo(sub)
            sub.commit()
            instance = InstancedMesh(scene, sub, Matrix4x4.translation(placeAt, 0f, 0f))
                .also { closeables += it }
            instance!!.addTo(scene)
        } else {
            val mesh = reflectorQuad(scene, placeAt).also { closeables += it }
            mesh.addTo(scene)
        }
        scene.commit()
    }

    private val simulationSettings = SimulationSettings(
        flags = SimulationFlags.REFLECTIONS,
        reflectionType = ReflectionEffectType.CONVOLUTION,
        maxNumRays = 16384,
        numDiffuseSamples = 2048,
        maxDuration = PROBE_DURATION,
        maxOrder = PROBE_ORDER,
        maxNumSources = 1,
        samplingRate = PROBE_SAMPLING_RATE,
        frameSize = PROBE_FRAME_SIZE,
    )

    private val simulator = Simulator(context, simulationSettings).also { closeables += it }
    private val source: Source
    private val hrtf: Hrtf
    private val reflection: ReflectionEffect
    private val decode: AmbisonicsDecodeEffect

    private val channels = SteamAudio.ambisonicChannelCount(PROBE_ORDER)
    private val dry = AudioBuffer(arena, 1, PROBE_FRAME_SIZE)
    private val ambisonic = AudioBuffer(arena, channels, PROBE_FRAME_SIZE)
    private val stereo = AudioBuffer(arena, 2, PROBE_FRAME_SIZE)
    private val reflectionParams: ReflectionEffectParams
    private val decodeParams: AmbisonicsDecodeEffectParams
    private val simulationInputs = SimulationInputs(arena, flags = SimulationFlags.REFLECTIONS)
    private val sharedInputs = SimulationSharedInputs(arena).apply {
        numRays = 16384
        numBounces = 16
        duration = PROBE_DURATION
        order = PROBE_ORDER
        irradianceMinDistance = 0.5f
    }
    private val outputs = SimulationOutputs(arena)

    init {
        simulator.setScene(scene)
        simulator.commit()
        source = Source(simulator, SimulationFlags.REFLECTIONS).also { closeables += it }
        source.addTo(simulator)
        simulator.commit()

        hrtf = Hrtf.default(context, AudioSettings(PROBE_SAMPLING_RATE, PROBE_FRAME_SIZE))
            .also { closeables += it }
        reflection = ReflectionEffect(
            context, AudioSettings(PROBE_SAMPLING_RATE, PROBE_FRAME_SIZE),
            type = ReflectionEffectType.CONVOLUTION,
            duration = PROBE_DURATION,
            ambisonicOrder = PROBE_ORDER,
        ).also { closeables += it }
        decode = AmbisonicsDecodeEffect(
            context, AudioSettings(PROBE_SAMPLING_RATE, PROBE_FRAME_SIZE), hrtf,
            maxOrder = PROBE_ORDER,
        ).also { closeables += it }

        reflectionParams = ReflectionEffectParams(arena).apply { applyShapeOf(reflection) }
        decodeParams = AmbisonicsDecodeEffectParams(
            arena, order = PROBE_ORDER, binaural = true, hrtf = hrtf,
        )
    }

    private fun reflectorQuad(into: Scene, x: Float): StaticMesh = StaticMesh.plane(
        into,
        Vector3(x, -6f, -14f),
        Vector3(x, -6f, 14f),
        Vector3(x, 10f, 14f),
        Vector3(x, 10f, -14f),
        Material.ROCK,
        doubleSided = true,
    )

    /** Moves the instanced reflector, exactly as the cave demo moves a panel. */
    fun moveTo(x: Float) {
        val moving = requireNotNull(instance) { "only an instanced rig can move" }
        moving.updateTransform(scene, Matrix4x4.translation(x, 0f, 0f))
        scene.commit()
    }

    /** Interaural level difference of the reflected field, in dB. Positive means right. */
    fun measure(): Double {
        simulationInputs.source = CoordinateSpace3.lookingAt(origin = sourcePosition, ahead = ahead)
        source.setInputs(SimulationFlags.REFLECTIONS, simulationInputs)
        sharedInputs.listener = listener
        simulator.setSharedInputs(SimulationFlags.REFLECTIONS, sharedInputs)
        simulator.runReflections()
        source.getOutputs(SimulationFlags.REFLECTIONS, outputs)
        reflectionParams.copySimulatedFrom(outputs)

        reflection.reset()
        decode.reset()
        decodeParams.orientation = listener

        var energyLeft = 0.0
        var energyRight = 0.0
        val samples = FloatArray(PROBE_FRAME_SIZE)

        repeat(48) { block ->
            fillNoise(dry)
            reflection.apply(reflectionParams, dry, ambisonic)
            decode.apply(decodeParams, ambisonic, stereo)
            // Skip the early blocks: the convolution tail has not filled yet.
            if (block >= 16) {
                stereo.readChannel(0, samples)
                for (value in samples) energyLeft += value.toDouble() * value.toDouble()
                stereo.readChannel(1, samples)
                for (value in samples) energyRight += value.toDouble() * value.toDouble()
            }
        }

        return 10.0 * log10((energyRight + 1e-20) / (energyLeft + 1e-20))
    }

    override fun close() {
        closeables.reversed().forEach { it.close() }
    }
}

private var noiseState = 0x9E3779B97F4A7C15uL

private fun nextNoise(): Float {
    noiseState = noiseState * 6364136223846793005uL + 1442695040888963407uL
    return ((noiseState shr 40).toLong().toFloat() / (1L shl 23).toFloat()) - 1f
}

private fun fillNoise(buffer: AudioBuffer) {
    val channel = buffer.channel(0)
    for (sample in 0 until buffer.sampleCount) {
        channel.setAtIndex(ValueLayout.JAVA_FLOAT, sample.toLong(), nextNoise() * 0.5f)
    }
}

// ---------------------------------------------------------------------------------------------
// The cave's own geometry, time-resolved.
//
// The rig above answers "is the chain mirrored". This answers the different question a listener
// actually asks of cave.wav: which side does each ping's reflected energy arrive from, and when.
//
// The cave is not symmetric. Of its three slabs only one produces a valid first-order reflection
// for the movement-1 layout -- the others' reflection points fall outside their own extent, so
// the geometry is simply not there to bounce off. That makes a lateral bias in the early field
// the expected result rather than a symptom, and the arrival times say which side goes first.
// ---------------------------------------------------------------------------------------------

/** Impulse response of the cave's reflected field, split into arrival-time windows. */
private class CaveWindowProbe(
    private val context: SteamAudioContext,
    arena: Arena,
) : AutoCloseable {

    private val closeables = mutableListOf<AutoCloseable>()
    private val settings = AudioSettings(PROBE_SAMPLING_RATE, PROBE_FRAME_SIZE)

    // Exactly movement 1 of the cave demo: shell, three slabs, listener and source both still.
    private val cave = Box(Vector3(-10f, -2f, -13f), Vector3(10f, 7f, 13f))
    private val listenerOrigin = Vector3(0f, 1.6f, 6f)
    private val ahead = Vector3(0f, 0f, -1f)
    private val listener = CoordinateSpace3.lookingAt(origin = listenerOrigin, ahead = ahead)
    private val sourcePosition = Vector3(0f, 1.6f, -2f)

    private val scene = Scene(context).also { closeables += it }

    init {
        val shell = StaticMesh.box(scene, cave, Material.ROCK, inwardFacing = true)
            .also { closeables += it }
        shell.addTo(scene)
        val slabs = listOf(
            listOf(Vector3(-4f, -2f, -6f), Vector3(-4f, 4f, -6f), Vector3(-4f, 4f, 1f), Vector3(-4f, -2f, 1f)),
            listOf(Vector3(5f, -2f, -2f), Vector3(5f, 5f, -2f), Vector3(5f, 5f, 6f), Vector3(5f, -2f, 6f)),
            listOf(Vector3(-7f, 3f, 4f), Vector3(2f, 3f, 4f), Vector3(2f, 3f, 10f), Vector3(-7f, 3f, 10f)),
        )
        for (corners in slabs) {
            val slab = StaticMesh.plane(
                scene, corners[0], corners[1], corners[2], corners[3], Material.ROCK,
            ).also { closeables += it }
            slab.addTo(scene)
        }
        scene.commit()
    }

    private val simulator = Simulator(
        context,
        SimulationSettings(
            flags = SimulationFlags.REFLECTIONS,
            reflectionType = ReflectionEffectType.CONVOLUTION,
            maxNumRays = 16384,
            numDiffuseSamples = 2048,
            maxDuration = PROBE_DURATION,
            maxOrder = PROBE_ORDER,
            maxNumSources = 1,
            samplingRate = PROBE_SAMPLING_RATE,
            frameSize = PROBE_FRAME_SIZE,
        ),
    ).also { closeables += it }

    private val source: Source
    private val reflection: ReflectionEffect
    private val decode: AmbisonicsDecodeEffect

    private val dry = AudioBuffer(arena, 1, PROBE_FRAME_SIZE)
    private val ambisonic = AudioBuffer(arena, SteamAudio.ambisonicChannelCount(PROBE_ORDER), PROBE_FRAME_SIZE)
    private val stereo = AudioBuffer(arena, 2, PROBE_FRAME_SIZE)
    private val reflectionParams: ReflectionEffectParams
    private val decodeParams: AmbisonicsDecodeEffectParams
    private val simulationInputs = SimulationInputs(arena, flags = SimulationFlags.REFLECTIONS)
    private val sharedInputs = SimulationSharedInputs(arena).apply {
        numRays = 16384
        numBounces = 32
        duration = PROBE_DURATION
        order = PROBE_ORDER
        irradianceMinDistance = 0.5f
    }
    private val outputs = SimulationOutputs(arena)

    init {
        simulator.setScene(scene)
        simulator.commit()
        source = Source(simulator, SimulationFlags.REFLECTIONS).also { closeables += it }
        source.addTo(simulator)
        simulator.commit()

        val hrtf = Hrtf.default(context, settings).also { closeables += it }
        reflection = ReflectionEffect(
            context, settings,
            type = ReflectionEffectType.CONVOLUTION,
            duration = PROBE_DURATION,
            ambisonicOrder = PROBE_ORDER,
        ).also { closeables += it }
        decode = AmbisonicsDecodeEffect(context, settings, hrtf, maxOrder = PROBE_ORDER)
            .also { closeables += it }
        reflectionParams = ReflectionEffectParams(arena).apply { applyShapeOf(reflection) }
        decodeParams = AmbisonicsDecodeEffectParams(
            arena, order = PROBE_ORDER, binaural = true, hrtf = hrtf,
        )
    }

    fun run() {
        simulationInputs.source = CoordinateSpace3.lookingAt(origin = sourcePosition, ahead = ahead)
        source.setInputs(SimulationFlags.REFLECTIONS, simulationInputs)
        sharedInputs.listener = listener
        simulator.setSharedInputs(SimulationFlags.REFLECTIONS, sharedInputs)
        simulator.runReflections()
        source.getOutputs(SimulationFlags.REFLECTIONS, outputs)
        reflectionParams.copySimulatedFrom(outputs)

        reflection.reset()
        decode.reset()
        decodeParams.orientation = listener

        // One impulse, then silence, so what comes out is the reflected impulse response and
        // sample index maps directly to arrival time.
        val left = ArrayList<Float>()
        val right = ArrayList<Float>()
        val scratch = FloatArray(PROBE_FRAME_SIZE)
        val blocks = (PROBE_SAMPLING_RATE * 0.35 / PROBE_FRAME_SIZE).toInt() + 1

        repeat(blocks) { block ->
            dry.clear()
            if (block == 0) dry.channel(0).setAtIndex(ValueLayout.JAVA_FLOAT, 0L, 1.0f)
            reflection.apply(reflectionParams, dry, ambisonic)
            decode.apply(decodeParams, ambisonic, stereo)
            stereo.readChannel(0, scratch); left.addAll(scratch.toList())
            stereo.readChannel(1, scratch); right.addAll(scratch.toList())
        }

        println()
        println(" cave movement-1 geometry, reflected impulse response by arrival time:")
        println(" window          energy L    energy R    ILD dB   side")
        println(" " + "-".repeat(58))

        val windows = listOf(
            "0-20 ms" to (0.0 to 0.020),
            "20-40 ms" to (0.020 to 0.040),
            "40-80 ms" to (0.040 to 0.080),
            "80-160 ms" to (0.080 to 0.160),
            "160-320 ms" to (0.160 to 0.320),
        )
        for ((label, range) in windows) {
            val from = (range.first * PROBE_SAMPLING_RATE).toInt()
            val to = minOf((range.second * PROBE_SAMPLING_RATE).toInt(), left.size)
            var energyLeft = 0.0
            var energyRight = 0.0
            for (index in from until to) {
                energyLeft += left[index].toDouble() * left[index]
                energyRight += right[index].toDouble() * right[index]
            }
            val ild = 10.0 * log10((energyRight + 1e-20) / (energyLeft + 1e-20))
            val side = when {
                kotlin.math.abs(ild) < 1.0 -> "balanced"
                ild > 0 -> "RIGHT"
                else -> "LEFT"
            }
            println(" %-12s %11.3e %11.3e %+9.2f   %s".format(label, energyLeft, energyRight, ild, side))
        }
    }

    override fun close() {
        closeables.reversed().forEach { it.close() }
    }
}
