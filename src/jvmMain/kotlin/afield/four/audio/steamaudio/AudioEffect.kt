package afield.four.audio.steamaudio

import java.lang.foreign.MemorySegment

/**
 * Common shape for Steam Audio's audio effects.
 *
 * Every effect in the API follows the same contract: [reset] clears internal state, `apply`
 * renders a block, and [tailSize] / [getTail] flush whatever the effect has left over once its
 * input stops.
 *
 * ### Tails
 *
 * In a reverberant space the tail is not a detail -- it is most of what you hear. When a
 * source stops, or moves out of a region, the effect still holds audio that has not come out
 * yet. Dropping it truncates the reverb audibly. The contract is:
 *
 *  - Keep calling `apply` while the source is producing input.
 *  - Once input stops, call [getTail] each block until it returns
 *    [AudioEffectState.TAIL_COMPLETE].
 *  - Only then is it safe to stop rendering the effect (or to close it).
 *
 * [drainTail] wraps that loop for the common case.
 *
 * ### Parameters are ramped across a block
 *
 * Steam Audio interpolates parameter changes over the block rather than applying them at sample
 * zero -- which is what stops a moving source from clicking. The consequence is that **the
 * first samples of a block are rendered with something closer to the previous parameters**.
 *
 * This is invisible for continuous audio and it is exactly what you want there. It matters in
 * two cases:
 *
 *  - **Measuring an impulse response.** An impulse at sample 0 of the same block in which you
 *    set the direction is rendered with the *old* direction. Measured directly: an Ambisonics
 *    encode given a fresh direction and an impulse at sample 0 emits only the omnidirectional
 *    component, all directional components zero. Run one block of the new parameters first,
 *    then send the impulse in the block after.
 *  - **Very short one-shots.** A click or tick shorter than a block, triggered in the same
 *    block that positions it, is partly rendered at the previous position. If precise placement
 *    of short sounds matters, set the parameters a block early.
 *
 * ### Threads
 *
 * `apply`, [getTail] and [tailSize] are the real-time-safe surface: they do not allocate, do
 * not block, and are meant to be called from the audio callback. They are not thread-safe
 * across *different* threads for the same effect, though -- one effect belongs to one audio
 * thread. Simulation, by contrast, happens elsewhere; see [Simulator].
 */
public abstract class AudioEffect internal constructor(
    handle: MemorySegment,
) : SteamAudioObject(handle) {

    /**
     * Clears internal state, including any pending tail.
     *
     * Use this when a source is recycled for unrelated audio, so the previous source's reverb
     * does not bleed into the new one.
     */
    public abstract fun reset()

    /**
     * Number of samples of tail the effect currently holds.
     *
     * For convolution-based reflections in a long-decay space this can be large -- seconds of
     * audio -- so treat it as a hint for how long [drainTail] will take, not a small number.
     */
    public abstract val tailSize: Int

    /**
     * Renders one block of tail audio into [output], returning whether more remains.
     *
     * Call this instead of `apply` once the source has stopped feeding the effect.
     */
    public abstract fun getTail(output: AudioBuffer): AudioEffectState

    /**
     * Repeatedly renders tail blocks into [scratch], handing each to [onBlock], until the
     * effect reports [AudioEffectState.TAIL_COMPLETE].
     *
     * @param scratch a buffer of the effect's output shape, reused for every block.
     * @param maxBlocks a safety bound, so a misbehaving effect cannot spin forever. The
     *   default allows roughly 30 seconds at a 1024-sample block size.
     * @return the number of blocks rendered.
     */
    public fun drainTail(
        scratch: AudioBuffer,
        maxBlocks: Int = 1400,
        onBlock: (AudioBuffer) -> Unit,
    ): Int {
        var blocks = 0
        while (blocks < maxBlocks) {
            val state = getTail(scratch)
            onBlock(scratch)
            blocks++
            if (state == AudioEffectState.TAIL_COMPLETE) break
        }
        return blocks
    }
}
