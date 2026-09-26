@file:afield.CompletionCategory("Synthesis")
package afield.four.synth

import afield.stdlib.Functions

/**
 * A [Functions.Fn] of time, in seconds, heard as a [Signal] — evaluated at every sample.
 *
 * Sample `i` of a block is `fn(time + i / sampleRate)`, where `time` is [SynthContext.time], the
 * start of the block. So a function used as an amplitude envelope or a frequency contour reaches the
 * audio at the instant it names, with no hold, no ramp and no delay.
 *
 * That is the difference from the other way to bring a function into a graph, `Param { fn(time) }`.
 * A `Param` supplier is evaluated **once per block**, at the block's start, and [Param] ramps from the
 * previous block's value to this one across the block — so what the audio hears at `t` is, to first
 * order, the function at `t` minus one block. At Dough's 128-frame block and 48 kHz that is 2.67 ms.
 * `chain_fn_sound_roundtrip.md` measures both routes from the rendered `.wav`.
 *
 * The cost is one [Functions.Fn.eval] per sample, and an `Fn` evaluates its whole graph each time, so
 * a deep graph is expensive at audio rate. Values are narrowed to `Float` on the way out. The
 * function's domain is not consulted — like [Functions.Fn.eval], a time outside it is passed through.
 */
class FnSignal(val fn: Functions.Fn) : Signal() {
    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        val t0 = ctx.time.toDouble()
        val dt = 1.0 / ctx.sampleRate.toDouble()
        for (i in 0 until numFrames) output[i] = fn.eval(t0 + i * dt).toFloat()
    }
}

/** This function of time, in seconds, as a [Signal] evaluated at every sample — see [FnSignal]. */
fun Functions.Fn.toSignal(): Signal = FnSignal(this)
