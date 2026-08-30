package drowning

import afield.four.buffers.FrameBufferGroup
import afield.stdlib.Vec4
import afield.stdlib.vec

/**
 * Shared GPU state for dust_four's particle-to-image feedback loop.
 *
 * The atlas is deliberately a linear buffer rather than an image. Each cell owns one fixed-size
 * tile, so compute shaders can scatter integer atomics into [accumulation] and every draw shader can
 * sample [atlas] through the same buffer-reference vocabulary. Nothing here decodes an asset; the
 * mutable state below is only a shared carrier for values owned by the live-code/keyframe box.
 *
 * Atlas texels are `vec4(density, uvVelocity.x, uvVelocity.y, memory)`. Accumulation texels have four
 * scalar integer lanes `(weight, weightedVelocityX, weightedVelocityY, particleCount)` during one
 * render frame. They are deliberately scalars: SPIRV-Cross otherwise asks Metal to take the address
 * of an integer-vector component for atomics, which Apple Metal rejects.
 */
class DrowningFeedbackBuffers(
    val atlas: FrameBufferGroup,
    val accumulation: FrameBufferGroup,
    val width: Int = DEFAULT_WIDTH,
    val height: Int = DEFAULT_HEIGHT,
    val cells: Int = DEFAULT_CELLS,
) {
    init {
        require(width > 0 && height > 0 && cells > 0)
    }

    val pixelsPerCell: Int = width * height
    val pixels: Int = pixelsPerCell * cells
    val workgroups: Int = (pixels + 63) / 64

    fun index(cell: Int, x: Int, y: Int): Int {
        require(cell in 0 until cells && x in 0 until width && y in 0 until height)
        return cell * pixelsPerCell + y * width + x
    }

    fun describe(): String =
        "DrowningFeedbackBuffers(${width}x$height x $cells cells, $pixels texels, %.2f MiB)".format(
            pixels * 16.0 * 2.0 / (1024.0 * 1024.0)
        )

    companion object {
        const val DEFAULT_WIDTH = 96
        const val DEFAULT_HEIGHT = 54
        const val DEFAULT_CELLS = 15
    }
}

/**
 * Mutable artistic interpretation shared by every feedback consumer.
 *
 * The cue owns this object alongside the atlas buffers so geometry can bind a safe, neutral field
 * before the feedback compute box is launched. The live-coded feedback box and keyframer mutate the
 * four values later without forcing splat or MXG decoders to be restarted.
 */
class DrowningFeedbackState(
    var light: Float = 1.6f,
    var erase: Float = 0f,
    var warp: Float = 0.4f,
    var threshold: Float = 0f,
) {
    fun look(): Vec4 = drowningFeedbackLook(light, erase, warp, threshold)
}

/** x deposit weight, y UV-velocity gain, z radius in atlas texels, w enabled. */
fun drowningFeedbackDeposit(
    weight: Float = 0.7f,
    velocity: Float = 1f,
    radius: Float = 1.5f,
    enabled: Boolean = true,
): Vec4 = vec(weight, velocity, radius, if (enabled) 1f else 0f)

/** x previous-frame retention, y density response, z velocity response, w max UV step. */
fun drowningFeedbackMemory(
    retention: Float = 0.965f,
    density: Float = 0.32f,
    velocity: Float = 1f,
    maxUvStep: Float = 0.08f,
): Vec4 = vec(retention, density, velocity, maxUvStep)

/** x added light, y exponential erase, z UV warp, w density threshold. */
fun drowningFeedbackLook(
    light: Float = 1.6f,
    erase: Float = 0f,
    warp: Float = 0.4f,
    threshold: Float = 0f,
): Vec4 = vec(light, erase, warp, threshold)
