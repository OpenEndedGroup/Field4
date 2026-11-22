package afield.examples.steamaudio

import afield.audio.engine.WavFloatArrayWriter
import afield.audio.spatial.steamaudio.AudioBuffer
import afield.audio.spatial.steamaudio.AudioSettings
import afield.audio.spatial.steamaudio.BinauralEffect
import afield.audio.spatial.steamaudio.BinauralEffectParams
import afield.audio.spatial.steamaudio.CoordinateSpace3
import afield.audio.spatial.steamaudio.DirectEffect
import afield.audio.spatial.steamaudio.DirectEffectFlags
import afield.audio.spatial.steamaudio.DirectEffectParams
import afield.audio.spatial.steamaudio.DirectSimulationFlags
import afield.audio.spatial.steamaudio.Hrtf
import afield.audio.spatial.steamaudio.Material
import afield.audio.spatial.steamaudio.OcclusionType
import afield.audio.spatial.steamaudio.Scene
import afield.audio.spatial.steamaudio.SimulationFlags
import afield.audio.spatial.steamaudio.SimulationInputs
import afield.audio.spatial.steamaudio.SimulationOutputs
import afield.audio.spatial.steamaudio.SimulationSettings
import afield.audio.spatial.steamaudio.SimulationSharedInputs
import afield.audio.spatial.steamaudio.Simulator
import afield.audio.spatial.steamaudio.Source
import afield.audio.spatial.steamaudio.StaticMesh
import afield.audio.spatial.steamaudio.SteamAudioContext
import afield.audio.spatial.steamaudio.TransmissionType
import afield.audio.spatial.steamaudio.Vector3
import afield.audio.synth.DirtRender
import afield.audio.synth.StereoAudioSample
import java.lang.foreign.Arena
import java.nio.file.Files
import java.nio.file.Path

/** A controlled comparison of the same direct sound with a screen in or out of its path. */
object SteamAudioScreenStudy {
    val listenerPosition = Vector3(0f, 1.6f, 0f)
    val sourcePosition = Vector3(1.6f, 1.6f, -3f)
    const val SCREEN_DEPTH = -1.5f
    const val SCREEN_HALF_WIDTH = 0.9f
    const val CLEAR_OFFSET = 2.4f
    private const val MONITOR_GAIN = 16f

    private val settings = AudioSettings(samplingRate = 48_000, frameSize = 512)
    private val clipFrames = (settings.samplingRate * 3 / 2 / settings.frameSize) * settings.frameSize
    private val forward = Vector3(0f, 0f, -1f)

    /** The stereo result and the unfiltered direct-path measurements for one screen position. */
    class Take internal constructor(
        val left: FloatArray,
        val right: FloatArray,
        val occlusion: Float,
        val transmission: FloatArray,
    ) {
        /** Encode at the original render level so attenuation survives a comparison. */
        fun wav(): ByteArray = WavFloatArrayWriter.encodeStereo16(left, right, settings.samplingRate)
    }

    /** Render the pinned hi-hat through the screen at [offsetMeters] from the centre of the direct ray. */
    fun render(offsetMeters: Float, material: Material = Material.WOOD): Take {
        require(offsetMeters.isFinite()) { "Screen offset must be finite" }
        val sample = requireNotNull(DirtRender.requirePinnedLibrary().sample("hh:0"))
        val centre = (listenerPosition.x + sourcePosition.x) / 2f + offsetMeters
        val minX = centre - SCREEN_HALF_WIDTH
        val maxX = centre + SCREEN_HALF_WIDTH

        SteamAudioContext().use { context ->
            Scene(context).use { scene ->
                StaticMesh.plane(
                    scene,
                    Vector3(minX, 0f, SCREEN_DEPTH),
                    Vector3(maxX, 0f, SCREEN_DEPTH),
                    Vector3(maxX, 2.6f, SCREEN_DEPTH),
                    Vector3(minX, 2.6f, SCREEN_DEPTH),
                    material,
                    doubleSided = false,
                ).use { screen ->
                    screen.addTo(scene)
                    scene.commit()
                    val simulation = SimulationSettings(
                        flags = SimulationFlags.DIRECT,
                        maxNumSources = 1,
                        samplingRate = settings.samplingRate,
                        frameSize = settings.frameSize,
                    )
                    Simulator(context, simulation).use { simulator ->
                        simulator.setScene(scene)
                        Source(simulator, SimulationFlags.DIRECT).use { source ->
                            source.addTo(simulator)
                            simulator.commit()
                            return renderDirect(context, simulator, source, sample)
                        }
                    }
                }
            }
        }
    }

    private fun renderDirect(
        context: SteamAudioContext,
        simulator: Simulator,
        source: Source,
        sample: StereoAudioSample,
    ): Take {
        Arena.ofConfined().use { arena ->
            val listener = CoordinateSpace3.lookingAt(listenerPosition, forward)
            val shared = SimulationSharedInputs(arena).apply { this.listener = listener }
            val inputs = SimulationInputs(
                arena,
                flags = SimulationFlags.DIRECT,
                directFlags = DirectSimulationFlags.DISTANCE_ATTENUATION or
                    DirectSimulationFlags.OCCLUSION or DirectSimulationFlags.TRANSMISSION,
            ).apply {
                this.source = CoordinateSpace3.lookingAt(sourcePosition, forward)
                occlusionType = OcclusionType.RAYCAST
                numTransmissionRays = 1
            }
            simulator.setSharedInputs(SimulationFlags.DIRECT, shared)
            source.setInputs(SimulationFlags.DIRECT, inputs)
            simulator.runDirect()

            val outputs = SimulationOutputs(arena)
            source.getOutputs(SimulationFlags.DIRECT, outputs)
            val directParams = DirectEffectParams(arena)
            outputs.copyDirectInto(directParams)
            directParams.flags = DirectEffectFlags.APPLY_DISTANCE_ATTENUATION or
                DirectEffectFlags.APPLY_OCCLUSION or DirectEffectFlags.APPLY_TRANSMISSION
            directParams.transmissionType = TransmissionType.FREQUENCY_DEPENDENT

            Hrtf.default(context, settings).use { hrtf ->
                DirectEffect(context, settings, channelCount = 1).use { direct ->
                    BinauralEffect(context, settings, hrtf).use { binaural ->
                        val direction = context.calculateRelativeDirection(
                            sourcePosition, listener.origin, listener.ahead, listener.up,
                        )
                        val binauralParams = BinauralEffectParams(arena, hrtf).apply {
                            setDirection(direction.x, direction.y, direction.z)
                        }
                        val dry = AudioBuffer(arena, 1, settings.frameSize)
                        val shaped = AudioBuffer(arena, 1, settings.frameSize)
                        val stereo = AudioBuffer(arena, 2, settings.frameSize)
                        val left = FloatArray(clipFrames)
                        val right = FloatArray(clipFrames)
                        val block = FloatArray(settings.frameSize)
                        val hitSpacing = settings.samplingRate / 2
                        val rateRatio = sample.sampleRate.toDouble() / settings.samplingRate
                        for (offset in left.indices step settings.frameSize) {
                            for (i in block.indices) {
                                val frame = offset + i
                                val position = (frame % hitSpacing) * rateRatio
                                block[i] = if (frame < 2 * hitSpacing && position < sample.lengthSamples) {
                                    0.35f * (sample.readLeft(position) + sample.readRight(position))
                                } else 0f
                            }
                            dry.writeChannel(0, block)
                            direct.apply(directParams, dry, shaped)
                            binaural.apply(binauralParams, shaped, stereo)
                            stereo.readChannel(0, left, offset)
                            stereo.readChannel(1, right, offset)
                        }
                        // One playback gain for every take. Leave the attenuation from the screen intact.
                        for (i in left.indices) {
                            left[i] *= MONITOR_GAIN
                            right[i] *= MONITOR_GAIN
                        }
                        return Take(left, right, outputs.occlusion, FloatArray(3, directParams::transmission))
                    }
                }
            }
        }
    }

    /** Documentation capture entry point. Both takes use the same input and render level. */
    fun renderWav(name: String, outPath: String) {
        val offset = when (name) {
            "clear" -> CLEAR_OFFSET
            "screen" -> 0f
            else -> error("Unknown Steam Audio screen take: $name")
        }
        Files.write(Path.of(outPath), render(offset).wav())
    }
}
