package afield.examples.steamaudio

import afield.four.audio.steamaudio.*
import java.lang.foreign.Arena
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Checks whether the Ambisonic encode actually produces directional components. */
fun main() {
    val settings = AudioSettings(samplingRate = 48_000, frameSize = 1024)
    SteamAudioContext().use { context ->
        Arena.ofShared().use { arena ->
            Hrtf.default(context, settings).use { hrtf ->
                for (order in 1..3) {
                    val channels = SteamAudio.ambisonicChannelCount(order)
                    AmbisonicsEncodeEffect(context, settings, maxOrder = order).use { encode ->
                        val mono = AudioBuffer(arena, 1, settings.frameSize)
                        val amb = AudioBuffer(arena, channels, settings.frameSize)
                        val p = AmbisonicsEncodeEffectParams(arena, order)
                        val az = 90.0 * PI / 180.0
                        p.setDirection(sin(az).toFloat(), 0f, (-cos(az)).toFloat())
                        // Settle: Steam Audio ramps parameter changes across a block, so an
                        // impulse at sample 0 is encoded with the PREVIOUS direction. Run a
                        // silent block first so the ramp reaches the target.
                        mono.clear(); amb.clear()
                        encode.apply(p, mono, amb)
                        mono.clear(); mono[0, 0] = 1f; amb.clear()
                        encode.apply(p, mono, amb)
                        val energies = (0 until channels).map { ch ->
                            val a = FloatArray(settings.frameSize)
                            amb.readChannel(ch, a)
                            sqrt(a.sumOf { it.toDouble() * it })
                        }
                        println("order $order ($channels ch) at az=90: " +
                            energies.joinToString(", ") { "%.3f".format(it) })
                    }
                }

                // Now decode order 1 and 3 and report ILD.
                for (order in 1..3) {
                    val channels = SteamAudio.ambisonicChannelCount(order)
                    AmbisonicsEncodeEffect(context, settings, maxOrder = order).use { encode ->
                        AmbisonicsDecodeEffect(context, settings, hrtf, maxOrder = order).use { decode ->
                            val mono = AudioBuffer(arena, 1, settings.frameSize)
                            val amb = AudioBuffer(arena, channels, settings.frameSize)
                            val st = AudioBuffer(arena, 2, settings.frameSize)
                            val ep = AmbisonicsEncodeEffectParams(arena, order)
                            val dp = AmbisonicsDecodeEffectParams(arena, order, binaural = true, hrtf = hrtf)
                            for (azDeg in listOf(0, 45, 90, 180, 270)) {
                                val az = azDeg * PI / 180.0
                                encode.reset(); decode.reset()
                                ep.setDirection(sin(az).toFloat(), 0f, (-cos(az)).toFloat())
                                mono.clear(); mono[0, 0] = 1f; amb.clear(); st.clear()
                                encode.apply(ep, mono, amb)
                                decode.apply(dp, amb, st)
                                val l = FloatArray(settings.frameSize); st.readChannel(0, l)
                                val r = FloatArray(settings.frameSize); st.readChannel(1, r)
                                val el = sqrt(l.sumOf { it.toDouble()*it }); val er = sqrt(r.sumOf { it.toDouble()*it })
                                val ild = 20*kotlin.math.log10((er+1e-12)/(el+1e-12))
                                println("order $order az=$azDeg: L=%.4f R=%.4f  energyILD=%+.2f dB  peakL=%.4f peakR=%.4f".format(
                                    el, er, ild, l.maxOf{kotlin.math.abs(it)}, r.maxOf{kotlin.math.abs(it)}))
                            }
                        }
                    }
                }
            }
        }
    }
}
