package afield.rectsl

import afield.rectsl.study.CpuRectSLRasterizer
import afield.rectsl.study.RectSLDrawable
import afield.rectsl.study.RectSLStudy
import afield.stdlib.Img
import kotlin.math.pow

/**
 * A RectSL scene as a sequence of [Img]s: the bridge from the rectangle language to everything in
 * Field that reads an image — `afield.stdlib` filters, `showImg`, and `Patch.mapImage`, which puts
 * a scene on a light rig.
 *
 * Frames come from the CPU reference rasterizer the RectSL study tools use
 * ([CpuRectSLRasterizer]), so no window or graphics context is needed: the scene's background, its
 * rectangles blended as the GPU blends them into an 8-bit target, and its StackSL materials run
 * through the StackSL GLSL interpreter. One sample per pixel, at the pixel's centre; the wall fills
 * the frame whatever its `->fit` says, row 0 at the top of the wall.
 *
 * The source is compiled once, here, including any `{{ … }}` Kotlin escapes; a scene that does not
 * compile throws, naming its errors.
 *
 * ```kotlin
 * val scene = RectSLImager(source)
 * val img = scene.frame(1.5, 256, 128)          // the colours as typed, 0..1
 * patch.mapImage(img.resize(16, 8))             // one fixture per cell, area-averaged
 * ```
 *
 * **sRGB or light.** By default a frame holds the colours *as typed*: `0x808080` is 0.502, which is
 * what the RectSL pictures show and what an 8-bit target stores. Pass `linear = true` for the light
 * those values stand for (0.216 for that grey). Averaging, blurring or down-sizing a frame is only
 * physically right on linear values, and an LED's PWM is linear in light; so a rig driven from a
 * linear frame at gamma 1 and a rig driven from an as-typed frame at `withGamma(2.2)` both show
 * roughly the right brightness, but only the first averages a thin line to the right energy.
 *
 * @property source the RectSL program, with or without the `#rectsl` first line.
 */
class RectSLImager(val source: String) {
    private val drawable: RectSLDrawable = RectSLStudy.drawable(source)

    /** The compiled scene: its wall, duration, fps and rectangles. */
    val scene: SceneProgram get() = drawable.scene

    /** The scene's `->duration`, in seconds. */
    val duration: Double get() = scene.duration

    /** The scene's `->fps`. */
    val fps: Double get() = scene.fps

    /** Rows for a frame [width] pixels across that keeps the wall's aspect (at least 1). */
    fun heightFor(width: Int): Int = maxOf(1, Math.round(width * scene.wall.height / scene.wall.width).toInt())

    /**
     * The scene at [time] seconds as a 3-channel [Img], [width] × [height] pixels, row 0 at the top.
     * Colours are as typed unless [linear] (see the class notes). [time] is not wrapped: past the
     * scene's duration its tracks hold their last values, as `SceneProgram.sample` does.
     */
    fun frame(time: Double, width: Int, height: Int = heightFor(width), linear: Boolean = false): Img {
        require(width >= 1 && height >= 1) { "a frame is at least 1 x 1, got $width x $height" }
        var raster: afield.stacksl.cpu.CpuRaster? = null
        CpuRectSLRasterizer.render(drawable, width, height, listOf(time)) { _, r -> raster = r }
        val rgba = requireNotNull(raster).rgba
        val out = FloatArray(width * height * 3)
        for (p in 0 until width * height) for (c in 0 until 3) {
            val v = rgba[p * 4 + c].toDouble()
            out[p * 3 + c] = (if (linear) v else encodeSrgb(v)).toFloat()
        }
        return Img(width, height, 3, out)
    }

    /** [count] frames evenly spaced over the scene's duration, the last one at `duration`. */
    fun frames(count: Int, width: Int, height: Int = heightFor(width), linear: Boolean = false): List<Img> {
        require(count >= 1)
        return (0 until count).map { i -> frame(if (count == 1) 0.0 else duration * i / (count - 1.0), width, height, linear) }
    }

    override fun toString() = "RectSLImager(${scene.wall.width} x ${scene.wall.height} wall, ${scene.duration}s, ${scene.nodes.size} nodes)"

    companion object {
        /** The sRGB transfer function: linear light 0..1 to the value a colour picker shows. */
        fun encodeSrgb(v: Double): Double {
            val c = v.coerceIn(0.0, 1.0)
            return if (c <= 0.0031308) c * 12.92 else 1.055 * c.pow(1 / 2.4) - 0.055
        }
    }
}

/** [source] at [time] as an [Img] — [RectSLImager] for one frame. Compiles the scene every call. */
fun rectSLImage(source: String, time: Double, width: Int, height: Int? = null, linear: Boolean = false): Img {
    val imager = RectSLImager(source)
    return imager.frame(time, width, height ?: imager.heightFor(width), linear)
}
