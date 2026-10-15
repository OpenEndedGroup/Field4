@file:afield.runtime.completion.CompletionCategory("Synthesis")
package afield.audio.synth

import kotlin.math.abs

/**
 * Analyze [source] once per graph block, retaining a reusable state for each sample. Several
 * [synthesize] outputs can share this analysis in one graph without advancing its tracker twice.
 * Like other stateful signals, this belongs to one audio context; inspect published values rather
 * than rendering the graph from a UI loop. Rendering this node itself returns the analysis input.
 */
class TrackedPhaseAnalysis(val source: Signal, val tracker: PhaseTracker = QuadPhaseTracker()) : Signal() {
    internal var states: Array<TrackedPhaseState> = emptyArray()
        private set

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        require(abs(ctx.sampleRate - tracker.sampleRate) < 0.5) {
            "TrackedPhaseAnalysis: tracker built for ${tracker.sampleRate} Hz, graph runs at ${ctx.sampleRate} Hz"
        }
        if (states.size < numFrames) states = Array(numFrames) { TrackedPhaseState(tracker.sampleRate) }
        source.render(output, numFrames, ctx)
        for (n in 0 until numFrames) states[n].copyFrom(tracker.process(output[n]))
    }

    /** Render a bank from this analysis. Each output needs its own bank and voice instances. */
    fun synthesize(bank: TrackedPhaseBank): Signal {
        require(bank.tracker == null) { "The analysis owns the tracker; supply a bank without one" }
        return object : Signal() {
            private var scratch = FloatArray(0)
            override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
                if (scratch.size < numFrames) scratch = FloatArray(numFrames)
                this@TrackedPhaseAnalysis.render(scratch, numFrames, ctx)
                for (n in 0 until numFrames) output[n] = bank.render(states[n]).toFloat()
            }
        }
    }
}
