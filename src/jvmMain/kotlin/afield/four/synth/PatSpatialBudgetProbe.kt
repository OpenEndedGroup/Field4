@file:afield.CompletionCategory("Patterns")
package afield.four.synth

import afield.four.audio.steamaudio.AudioBuffer
import afield.four.audio.steamaudio.AudioSettings
import afield.four.audio.steamaudio.BinauralEffect
import afield.four.audio.steamaudio.BinauralEffectParams
import afield.four.audio.steamaudio.DirectEffect
import afield.four.audio.steamaudio.DirectEffectFlags
import afield.four.audio.steamaudio.DirectEffectParams
import afield.four.audio.steamaudio.Hrtf
import afield.four.audio.steamaudio.HrtfInterpolation
import afield.four.audio.steamaudio.SteamAudioContext
import java.lang.foreign.Arena
import java.nio.file.Paths

/*
FIELD_TEST
id: synth.pat-spatial.budget
runner: jvm-main
entrypoint: afield.four.synth.PatSpatialBudgetProbe
tags: [probe, audio, synth, steamaudio, cpu]
timeoutSec: 300
expect:
  exitCode: 0
  stdoutContains:
    - "PAT_SPATIAL_BUDGET_MEASURED"
*/
/**
 * Measures the cost of one [DoughEngine] per spatial source, against one audio block's deadline.
 *
 * This is the measurement [patSpatial]'s documentation quotes, and the reason [PatSpatialRoom]
 * renders on its own thread rather than on the audio thread. The number that decides it is not the
 * median -- which fits several times over -- but the worst block, because one late block is a click.
 *
 * Run it with `tools/run_field_tests.py --id synth.pat-spatial.budget`. Timings are machine- and
 * load-dependent; what should reproduce is the *shape*, a median far inside budget and a tail far
 * outside it.
 */
object PatSpatialBudgetProbe {

    private const val SR = 44_100
    private const val BLOCK = DoughEngine.BLOCK_SIZE
    private const val BLOCKS = 8000

    @JvmStatic
    fun main(args: Array<String>) {
        val budgetMs = BLOCK * 1000.0 / SR
        println("block $BLOCK @ $SR Hz -> one block is %.3f ms of audio".format(budgetMs))

        val wasm = Paths.get("./src/jvmMain/resources/dough.wasm")

        for (n in intArrayOf(1, 2, 4)) {
            val bootStart = System.nanoTime()
            val engines = (0 until n).map { DoughEngine(wasmPath = wasm, sampleRate = SR) }
            val bootMs = (System.nanoTime() - bootStart) / 1e6
            println()
            println("$n source(s): boot %.0f ms total, %.0f ms each".format(bootMs, bootMs / n))

            try {
                // A held chord per engine, so each one is doing real polyphonic work.
                engines.forEachIndexed { i, e ->
                    for (semi in 0 until 3) {
                        e.evaluatePath("/dough/play/s/saw/note/${48 + i * 5 + semi * 4}/velocity/.4/lpf/1800/pan/0")
                    }
                }

                val settings = AudioSettings(samplingRate = SR, frameSize = BLOCK)
                SteamAudioContext().use { ctx ->
                    Hrtf.default(ctx, settings, volume = 1.0f).use { hrtf ->
                        Arena.ofShared().use { arena ->
                            val directs = (0 until n).map { DirectEffect(ctx, settings, channelCount = 1) }
                            val binaurals = (0 until n).map { BinauralEffect(ctx, settings, hrtf) }
                            val dry = (0 until n).map { AudioBuffer(arena, 1, BLOCK) }
                            val shaped = (0 until n).map { AudioBuffer(arena, 1, BLOCK) }
                            val stereo = (0 until n).map { AudioBuffer(arena, 2, BLOCK) }
                            val directParams = (0 until n).map {
                                DirectEffectParams(arena, flags = DirectEffectFlags.APPLY_DISTANCE_ATTENUATION)
                            }
                            val binauralParams = (0 until n).map {
                                BinauralEffectParams(arena, hrtf = hrtf, interpolation = HrtfInterpolation.BILINEAR)
                            }
                            // Steam Audio's inverse-distance curve, evaluated arithmetically:
                            // DistanceAttenuationModel.calculate allocates a scratch Arena per call.
                            val minDistance = 0.4f

                            val blockL = FloatArray(BLOCK)
                            val blockR = FloatArray(BLOCK)
                            val mono = FloatArray(BLOCK)
                            val outL = FloatArray(BLOCK)
                            val outR = FloatArray(BLOCK)
                            val lane = FloatArray(BLOCK * 2)

                            var doughNs = 0L
                            var steamNs = 0L
                            val perBlockMs = DoubleArray(BLOCKS)

                            for (b in 0 until BLOCKS) {
                                val t0 = System.nanoTime()
                                for (i in 0 until n) {
                                    engines[i].renderBlock(blockL, blockR)
                                    System.arraycopy(blockL, 0, mono, 0, BLOCK)
                                    dry[i].writeChannel(0, mono)
                                }
                                val t1 = System.nanoTime()

                                java.util.Arrays.fill(outL, 0f)
                                java.util.Arrays.fill(outR, 0f)
                                for (i in 0 until n) {
                                    // A circling source, so the direction really changes per block.
                                    val ang = 2.0 * Math.PI * ((b / 120.0) + i.toDouble() / n)
                                    val px = (1.5 * Math.sin(ang)).toFloat()
                                    val pz = (-1.5 * Math.cos(ang)).toFloat()
                                    val dist = Math.sqrt((px * px + pz * pz).toDouble()).toFloat()

                                    directParams[i].distanceAttenuation = minDistance / Math.max(dist, minDistance)
                                    directs[i].apply(directParams[i], dry[i], shaped[i])
                                    binauralParams[i].setDirection(px / dist, 0f, pz / dist)
                                    binaurals[i].apply(binauralParams[i], shaped[i], stereo[i])

                                    stereo[i].interleaveTo(ctx, lane)
                                    for (f in 0 until BLOCK) {
                                        outL[f] += lane[f * 2]
                                        outR[f] += lane[f * 2 + 1]
                                    }
                                }
                                val t2 = System.nanoTime()

                                doughNs += t1 - t0
                                steamNs += t2 - t1
                                perBlockMs[b] = (t2 - t0) / 1e6
                            }

                            // Discard the first tenth: the JIT has not settled before that, and a
                            // warmup spike is not what the audio thread would live with.
                            val settled = perBlockMs.drop(BLOCKS / 10).sorted()
                            val over = settled.count { it > budgetMs }
                            println("  mean dough %.3f ms/blk, mean steam %.3f ms/blk"
                                .format(doughNs / 1e6 / BLOCKS, steamNs / 1e6 / BLOCKS))
                            println("  median %.3f ms (%.0f%% of budget)  p99 %.3f  p99.9 %.3f  worst %.3f"
                                .format(settled[settled.size / 2], 100.0 * settled[settled.size / 2] / budgetMs,
                                    settled[(settled.size * 99) / 100],
                                    settled[(settled.size * 999) / 1000], settled.last()))
                            println("  %d of %d settled blocks over budget (%.2f%%)"
                                .format(over, settled.size, 100.0 * over / settled.size))
                            val overIdx = perBlockMs.indices.filter { perBlockMs[it] > budgetMs }
                            println("  overruns total ${overIdx.size}; last at block ${overIdx.lastOrNull()} of $BLOCKS")

                            binaurals.forEach { it.close() }
                            directs.forEach { it.close() }
                        }
                    }
                }
            } finally {
                engines.forEach { it.close() }
            }
        }

        println()
        println("PAT_SPATIAL_BUDGET_MEASURED")
    }
}
