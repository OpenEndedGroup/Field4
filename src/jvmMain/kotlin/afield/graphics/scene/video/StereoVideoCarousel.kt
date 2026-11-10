package afield.graphics.scene.video

import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.Target
import afield.graphics.vulkan.targets.MultiviewStereoTarget
import afield.math.linalg.Vector3f

/** Curved room-fixed screens looking inward, with one decoder and independent delayed exposures. */
class StereoVideoCarousel(
    private val renderer: Renderer,
    private val stereo: MultiviewStereoTarget,
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
    @Volatile
    var screenLayouts: List<StereoVideoScreenLayout> = List(count) { StereoVideoScreenLayout() }
        set(value) { require(value.size == surfaces.size); field = value.toList() }

    private val sortTarget = Target { _, _ ->
        val order = stereoVideoBackToFront(surfaces.map { it.placement }, stereo.eyeViewMatrix(0), stereo.eyeViewMatrix(1))
        val modules = stereo.layered.modules
        val names = surfaces.indices.map { "carousel-screen-$it" }.toSet()
        val ordered = order.mapNotNull { i -> modules["carousel-screen-$i"]?.let { "carousel-screen-$i" to it } }
        val overlays = modules.filterKeys { it !in names }
        if (modules.keys.toList() != ordered.map { it.first } + overlays.keys) {
            modules.clear()
            modules.putAll(ordered)
            modules.putAll(overlays) // Field/browser overlays keep their relative order above the screens.
        }
    }

    fun layout(): Unit = renderer.runOnQueueThread {
        require(radius.isFinite() && fill.isFinite())
        require(listOf(center.x, center.y, center.z, azimuthDegrees).all { it.isFinite() })
        // Prepare every placement before publishing any, so invalid arithmetic cannot partially move the ring.
        val placements = screenLayouts.mapIndexed { i, settings ->
            settings.placement(i, surfaces.size, center, radius, azimuthDegrees, fill)
        }
        surfaces.forEachIndexed { i, screen ->
            val next = placements[i]
            screen.placement.apply {
                center.set(next.center)
                rotationDegrees.set(next.rotationDegrees)
                widthMeters = next.widthMeters
                bendDegrees = next.bendDegrees
            }
        }
    }

    /** Queue an atomic control edit; an invalid result restores the previous global and per-screen settings. */
    fun editLayout(edit: StereoVideoCarousel.() -> Unit): Unit = renderer.runOnQueueThread {
        val oldCenter = Vector3f(center)
        val oldRadius = radius
        val oldAngle = azimuthDegrees
        val oldFill = fill
        val oldScreens = screenLayouts
        try { edit(); layout() } catch (failure: Exception) {
            center.set(oldCenter); radius = oldRadius; azimuthDegrees = oldAngle; fill = oldFill; screenLayouts = oldScreens
            throw failure
        }
    }

    init {
        layout()
        stereo.beforeScene["carousel-screen-order"] = sortTarget
    }

    fun load(file: String): Boolean {
        val loaded = if (surfaces.first().load(file)) true else return false
        // Library-only callers get a pipeline for every screen, all sharing the loaded source.
        surfaces.drop(1).forEach { it.ensureDrawing() }
        surfaces.forEach { it.placement.aspect = source.nativeAspect }
        return loaded
    }

    override fun close() {
        renderer.runOnQueueThread { stereo.beforeScene.remove("carousel-screen-order", sortTarget) }
        surfaces.asReversed().forEach { it.close() }
        source.close()
    }
}
