@file:Suppress("MemberVisibilityCanBePrivate")

package afield.simulation.particles

import afield.document.context.fromContext
import afield.document.graph.Box
import afield.graphics.render.ComputeMesh
import afield.graphics.render.ComputeModulePointer
import afield.graphics.render.computeBarrier
import afield.graphics.render.computeTask
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.buffers.FrameBufferGroup
import afield.graphics.vulkan.buffers.defaultBuffer
import afield.graphics.vulkan.targets.PointAccumulation
import afield.graphics.vulkan.targets.PointBlend
import afield.graphics.vulkan.targets.PointAccumulationSettings
import afield.ui.controls.ControlLaw
import afield.ui.output.controlGroup
import afield.ui.output.displayOpen

/**
 * Iterated systems: many particles, each stepped by the same rule every frame and plotted after
 * every step, so their visits pile up into the shape the rule keeps returning to — the chaos game
 * behind an IFS fern, the flow of a strange attractor, a fractal flame.
 */

/**
 * Settings for an [IteratedSystem]: the [PointAccumulation] settings (viewport, background, fade,
 * blend, tone map, exposure) plus how many particles there are and how they are stepped.
 * [particles] and [maxSteps] are fixed when the system is built; the rest can change while it runs.
 */
class IteratedSystemSettings : PointAccumulationSettings() {
    init {
        name = "iterated-system"
    }

    /** How many particles. Each is one GPU invocation, so a million is fine. */
    var particles: Int = 100_000

    /** Steps per particle per frame. Every step is plotted. */
    var steps: Int = 1

    /** The most [steps] can be raised to while running; 0 means four, or [steps] if that is more. */
    var maxSteps: Int = 0

    /** Frames stepped per second; 0 steps every frame the renderer draws. Lower shows the build-up. */
    var fps: Double = 0.0

    /** The `dt` the shader's `step` is given. */
    var dt: Double = 1.0

    /** Changes every particle's random numbers. */
    var seed: Int = 1
}

/**
 * Hosts an iterated system whose rule is a compute shader built on `#include "points/system"`: the
 * shader defines `initParticle`, `step`, `project`, `shouldDraw`, `color` and `pointSize`, and the
 * include's `main()` steps every particle [IteratedSystemSettings.steps] times a frame and writes a
 * point per step. This class allocates the particle and point buffers, dispatches the shader in the
 * box's compute module, and plots the points into a [PointAccumulation] it installs on the
 * renderer.
 *
 * ```
 * var module = computeModuleHere {}
 * val fern = IteratedSystem(module) {
 *     viewport(-3.1, 3.1, 0.0, 10.4)
 *     particles = 200_000
 *     fade = 0.06
 * }
 * ```
 *
 * Every system uses the same module names unless [IteratedSystemSettings.name] says otherwise, so
 * launching one replaces the one that was showing. [reset] starts every particle again and clears
 * the picture.
 */
class IteratedSystem(
    /** The compute module holding the system's shader, usually `computeModuleHere {}`. */
    val module: ComputeModulePointer,
    /** The renderer to run on; by default the one this box is running in. */
    val renderer: Renderer = fromContext(),
    configure: IteratedSystemSettings.() -> Unit = {}
) {
    /** The settings after `configure` ran; change them to steer the running system. */
    val settings = IteratedSystemSettings().apply(configure)

    /** How many particles, fixed for the life of the system. */
    val particles: Int = settings.particles

    /** The room per particle in [points], fixed for the life of the system. */
    val maxSteps: Int = (settings.maxSteps.takeIf { it > 0 } ?: maxOf(settings.steps, 4)).coerceAtLeast(settings.steps)

    /** Two vec4s per particle: `(pos, age)` and `data`. */
    val state: FrameBufferGroup

    /** [maxSteps] `AccumulatedPoint` records per particle, rewritten every stepped frame. */
    val points: FrameBufferGroup

    /** Where the points pile up. */
    val accumulation: PointAccumulation

    /** The dispatch in [module] that runs the shader. */
    val mesh: ComputeMesh

    /** Frames stepped so far. */
    var frame: Int = 0
        private set

    private var resetPending = true
    private var resetThisFrame = false
    private var stepping = true
    private var lastStepNanos = 0L

    init {
        require(particles > 0) { "particles must be positive, got $particles" }
        require(settings.steps >= 1) { "steps must be at least 1, got ${settings.steps}" }
        // Nothing frees a GPU buffer or target once made, so a re-run of the same shape takes the
        // last one's back instead of allocating its own; resetPending starts the particles afresh.
        val shape = Shape(settings.name, particles, maxSteps, settings.width, settings.height, settings.format, settings.blend)
        val previous = synchronized(kept) { kept.getOrPut(renderer) { HashMap() }[shape] }
        if (previous != null) {
            state = previous.state
            points = previous.points
            accumulation = previous.accumulation
            accumulation.settings = settings
            accumulation.clear()
        } else {
            state = defaultBuffer(name = "${settings.name}-state") {
                size = (particles * 2).vec4s
                zeroInit()
            }.toBuffer(renderer)
            points = defaultBuffer(name = "${settings.name}-points") {
                size = (particles.toLong() * maxSteps * 2 * 16)
                zeroInit()
            }.toBuffer(renderer)
            accumulation = PointAccumulation(renderer, settings)
            accumulation.plot(points, particles * maxSteps)
            synchronized(kept) { kept.getOrPut(renderer) { HashMap() }[shape] = Resources(state, points, accumulation) }
        }
        accumulation.advance = { stepping }

        mesh = ComputeMesh(renderer, module)
        mesh.push.bind("particles") { state }
        mesh.push.bind("points") { points }
        mesh.push.bind("count") { particles }
        mesh.push.bind("steps") { settings.steps.coerceIn(1, maxSteps) }
        mesh.push.bind("maxSteps") { maxSteps }
        mesh.push.bind("frame") { frame }
        mesh.push.bind("seed") { settings.seed }
        mesh.push.bind("reset") { if (resetThisFrame) 1 else 0 }
        mesh.push.bind("dt") { settings.dt.toFloat() }
        mesh.x = (particles + 63) / 64
        mesh.y = 1
        mesh.z = 1

        // whether to step is decided once a frame, here, so the compute pass and the accumulation agree
        module += "${settings.name}-step" to computeTask { inside, cmd, push ->
            tick()
            if (stepping) mesh.compute(inside, cmd, push)
        }
        module += "${settings.name}-publish" to computeBarrier(graphics = true)
        renderer += "${settings.name}-compute" to module
        accumulation.installInto()
    }

    private data class Shape(
        val name: String, val particles: Int, val maxSteps: Int,
        val width: Int, val height: Int, val format: Int, val blend: PointBlend
    )

    private class Resources(val state: FrameBufferGroup, val points: FrameBufferGroup, val accumulation: PointAccumulation)

    private companion object {
        /** The resources each shape of system was last built with, per renderer. */
        val kept = java.util.WeakHashMap<Renderer, HashMap<Shape, Resources>>()
    }

    /** Start every particle again with `initParticle`, and clear the picture. */
    fun reset() {
        resetPending = true
        accumulation.clear()
    }

    private fun tick() {
        val now = System.nanoTime()
        val fps = settings.fps
        stepping = fps <= 0.0 || lastStepNanos == 0L || now - lastStepNanos >= (1e9 / fps).toLong()
        resetThisFrame = stepping && resetPending
        if (stepping) {
            resetPending = false
            lastStepNanos = now
            frame++
        }
    }
}

/**
 * The fern demo's toolbar for [system], in this box's `controls` output: frames per second, steps
 * per frame, fade and exposure, each steering the running system, and a reset button. The panel
 * opens, as the demo's toolbar is always in view.
 */
fun Box.showControls(system: IteratedSystem, channel: String = "controls") {
    val s = system.settings
    controlGroup(channel) {
        // 0 fps steps on every frame the renderer draws
        slider("fps", min = 0.0, max = 120.0, step = 1.0, initialValue = s.fps) { s.fps = it }
        slider("steps per frame", min = 1.0, max = system.maxSteps.toDouble(), step = 1.0,
            initialValue = s.steps.toDouble()) { s.steps = it.toInt() }
        slider("fade", min = 0.0, max = 1.0, step = 0.005, initialValue = s.fade) { s.fade = it }
        slider("exposure", min = 0.05, max = 20.0, initialValue = s.exposure, law = ControlLaw.LOGARITHMIC) { s.exposure = it }
        button("reset") { down -> if (down) system.reset() }
    }
    displayOpen(channel)
}
