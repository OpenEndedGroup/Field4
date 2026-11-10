package afield.graphics.scene.video

import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.targets.MultiviewStereoTarget
import afield.math.linalg.Vector3f
import kotlin.math.*

/** Curved room-fixed screens looking inward, with one decoder and independent delayed exposures. */
class StereoVideoCarousel(
    renderer: Renderer,
    stereo: MultiviewStereoTarget,
    count: Int = 8,
    spacingFrames: Int = 1,
    automaticDrawing: Boolean = true,
) : AutoCloseable {
    init { require(count in 2..16 && spacingFrames >= 0 && (count - 1L) * spacingFrames < 256) }
    val source = StereoVideoSource(renderer, stereo, "carousel-source", (count - 1) * spacingFrames + 1)
    val surfaces = List(count) { i ->
        StereoVideoSurface(renderer, stereo, name = "carousel-screen-$i", source = source,
            automaticDrawing = automaticDrawing).also { it.delayFrames = i * spacingFrames }
    }
    /** Spacing is in published pictures, not headset frames; changing it refills the bounded history. */
    var spacingFrames = spacingFrames
        set(value) {
            require(value >= 0 && (surfaces.size - 1L) * value < 256)
            field = value
            source.historyFrames = (surfaces.size - 1) * value + 1
            surfaces.forEachIndexed { i, screen -> screen.delayFrames = i * value }
        }
    val center = Vector3f()
    var radius = 2.5f
    var azimuthDegrees = 0f
    /** Fraction of each angular sector occupied by its screen; one makes a continuous cylinder. */
    var fill = .9f

    fun layout() {
        require(radius.isFinite() && radius > 0 && fill.isFinite() && fill > 0 && fill <= 1)
        require(listOf(center.x, center.y, center.z, azimuthDegrees).all { it.isFinite() })
        val arc = (2 * PI / surfaces.size * fill).toFloat()
        surfaces.forEachIndexed { i, screen ->
            val angle = Math.toRadians(azimuthDegrees.toDouble()) + i * 2 * PI / surfaces.size
            screen.placement.apply {
                this.center.set(this@StereoVideoCarousel.center).add(radius * sin(angle).toFloat(), 0f, -radius * cos(angle).toFloat())
                rotationDegrees.set(0f, -Math.toDegrees(angle).toFloat(), 0f)
                widthMeters = radius * arc
                bendDegrees = Math.toDegrees(arc.toDouble()).toFloat().coerceAtMost(180f)
            }
        }
    }

    init { layout() }

    fun load(file: String): Boolean {
        val loaded = if (surfaces.first().load(file)) true else return false
        // Library-only callers get a pipeline for every screen, all sharing the loaded source.
        surfaces.drop(1).forEach { it.ensureDrawing() }
        surfaces.forEach { it.placement.aspect = source.nativeAspect }
        return loaded
    }

    override fun close() {
        surfaces.asReversed().forEach { it.close() }
        source.close()
    }
}
