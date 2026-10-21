@file:Suppress("MemberVisibilityCanBePrivate")

package afield.simulation.particles

import afield.document.context.fromContext
import afield.document.graph.Box
import afield.graphics.color.rgba
import afield.graphics.render.ComputeMesh
import afield.graphics.render.ComputeModulePointer
import afield.graphics.render.Mesh
import afield.graphics.render.Module
import afield.graphics.render.ModulePointer
import afield.graphics.render.computeMemoryBarrier
import afield.graphics.render.fullscreenTriangle
import afield.graphics.render.graphicsMemoryBarrier
import afield.graphics.render.parameters
import afield.graphics.render.pointer
import afield.graphics.shader.fromViews
import afield.graphics.texture.TextureSampler
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.Target
import afield.graphics.vulkan.buffers.FrameBufferGroup
import afield.graphics.vulkan.buffers.defaultBuffer
import afield.graphics.vulkan.device.CommandBuffer
import afield.graphics.vulkan.targets.PointAccumulation
import afield.graphics.vulkan.targets.PointBlend
import afield.graphics.vulkan.targets.PointShaders
import afield.graphics.vulkan.util.GpuNeighbourGrid
import afield.graphics.vulkan.util.WindowPointer
import afield.math.linalg.Rectanglef
import afield.math.linalg.Vec4
import afield.math.linalg.Vector2f
import afield.math.linalg.vec
import afield.ui.output.controlGroup
import afield.ui.output.displayOpen
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_READ_BIT
import org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_WRITE_BIT
import org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT
import org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT
import org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT
import org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_DEPENDENCY_INFO
import org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_MEMORY_BARRIER_2
import org.lwjgl.vulkan.VK13.vkCmdPipelineBarrier2
import org.lwjgl.vulkan.VkDependencyInfo
import org.lwjgl.vulkan.VkMemoryBarrier2
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Particle sketching on the GPU: particles you paint into the renderer window by dragging, each
 * moved every frame by a compute shader written against `#include "particles/sketch"`, drawn as
 * glowing dots with threads between them and trails behind them. The Field port of the madp
 * Particles demo; `dev_docs/madp/particles.md` has the mapping from its JS hooks.
 */

/**
 * The world a [ParticleSketch] runs in: madp's `world` object, set where madp's `init()` set it.
 * Everything here except [name], [capacity] and [maxStrokes] can change while the sketch runs;
 * every frame reads it afresh.
 */
class ParticleSketchSettings {
    /** Names the buffers, passes and pointer handler; a second sketch under the same name replaces the first. */
    var name: String = "particles"

    /** The most particles alive at once. Painting past it replaces the oldest. */
    var capacity: Int = 16_384

    /** The most links and overlay shapes drawn in a frame; past it the rest are dropped. */
    var maxStrokes: Int = 131_072

    /** Acceleration every particle starts each frame with, in pixels per second per second (y down). */
    var gravity: Vector2f = vec(0, 0)

    /** The fraction of its velocity a particle keeps each frame, after the forces. */
    var damping: Double = 0.995

    /** The world's speed limit in pixels per second, applied after damping. */
    var maxSpeed: Double = Double.POSITIVE_INFINITY

    /** The ground, as an sRGB colour: `hexColor("#03060a")`. madp's `fadeColor` is the same colour. */
    var background: Vec4 = rgba(2, 5, 7, 1.0)

    /** The colour a particle starts with before `spawn` sets one, sRGB with straight alpha. */
    var particleColor: Vec4 = rgba(190, 255, 236, 0.92)

    /** The radius a particle starts with before `spawn` sets one, in pixels. */
    var particleRadius: Double = 3.0

    /**
     * madp's Trail: how much of the previous frame stays on screen, 0..1, per 60th of a second
     * (madp's frame), whatever the renderer's frame rate. 0 clears every frame; 0.3 leaves short
     * motion-blurred tails.
     */
    var trail: Double = 0.0

    /** How far apart two particles can be for `eachPair` to see them; 0 runs no pairs (madp's `eachPair(r, ...)`). */
    var pairRadius: Double = 0.0

    /** Pixels between the particles a drag lays down (madp's paint spacing). */
    var paintSpacing: Double = 8.0

    /** Whether dragging in the renderer window paints particles. */
    var paintWithMouse: Boolean = true

    /**
     * Steps per second; 0 steps on every frame the renderer draws. madp runs at the browser's 60,
     * and its per-frame constants assume it: `drag(0.02)`, [damping] and the trail are all "per
     * frame", so at the thousands of frames a second an unthrottled window can draw they would
     * stop everything dead. Between steps the picture holds still.
     */
    var fps: Double = 60.0

    /** Holds the simulation still; the picture keeps drawing, and painting still adds particles. */
    var paused: Boolean = false

    /** Changes every random number the shader draws. */
    var seed: Int = 1
}

/**
 * Hosts a particle sketch whose behaviour is a compute shader built on
 * `#include "particles/sketch"`. The shader defines madp's hooks as GLSL —
 * `spawn(at)`, `eachPair(other, pair)`, `update()` and `draw()` — and this class runs them:
 * it keeps the particles in two GPU buffers that swap every frame, rebuilds a
 * [GpuNeighbourGrid] over them (72-pixel cells, as madp's spatial hash), steps every particle,
 * and draws links, dots and overlays into a [PointAccumulation] that fades by `1 - trail` each
 * frame, shown over [ParticleSketchSettings.background].
 *
 * ```
 * var module = computeModuleHere {}
 * val sketch = ParticleSketch(module) {
 *     background = hexColor("#03060a")
 *     damping = 0.998
 *     maxSpeed = 190.0
 *     pairRadius = 30.0
 *     trail = 0.22
 * }
 * showControls(sketch)
 * ```
 *
 * The world starts empty. Drag in the renderer window to paint particles in, or call [paint]
 * from code. Coordinates are the window's, in points: origin top left, y down, as on a canvas.
 *
 * Re-running the box that made a sketch keeps its particles and lets the new code take them over,
 * as madp does when you recompile; a sketch from a different box under the same name starts
 * empty. [reset] empties it.
 */
class ParticleSketch(
    /** The compute module holding the sketch's shader, usually `computeModuleHere {}`. */
    val module: ComputeModulePointer,
    /** The renderer to run on; by default the one this box is running in. */
    val renderer: Renderer = fromContext(),
    configure: ParticleSketchSettings.() -> Unit = {}
) {
    /** The world, after `configure` ran; change it to steer the running sketch. */
    val settings = ParticleSketchSettings().apply(configure)

    /** The most particles alive at once, fixed for the life of the sketch. */
    val capacity: Int = settings.capacity

    private val resources: Resources

    /** The two particle buffers; [latest] is the one holding the current frame. */
    val particles: List<FrameBufferGroup> get() = resources.particles

    /** The picture: links, dots and overlays piling up with a fade. */
    val accumulation: PointAccumulation get() = resources.accumulation

    /**
     * This frame's links and overlays: a `uint` count, three of padding, then one `Stroke`
     * (three vec4s: points, colour, shape) for each.
     */
    val strokes: FrameBufferGroup get() = resources.strokes

    /** The neighbour grid `eachPair`, `flock`, `clump` and `neighbors` search. */
    val grid: GpuNeighbourGrid get() = resources.grid

    /** How many particles are alive. */
    val count: Int get() = resources.count()

    /** True when there are no particles and none waiting to be added, as on a fresh start or after [reset]. */
    val isEmpty: Boolean get() = resources.count() == 0 && synchronized(resources.queue) { resources.queue.isEmpty() }

    /** Seconds the sketch has run, madp's `t`; it stops while paused. */
    val time: Double get() = resources.time

    /** The buffer holding the current frame's particles. */
    val latest: FrameBufferGroup get() = resources.particles[resources.current]

    private val mesh: ComputeMesh

    init {
        require(capacity > 0) { "capacity must be positive, got $capacity" }
        require(settings.maxStrokes > 0) { "maxStrokes must be positive, got ${settings.maxStrokes}" }

        // Nothing frees a GPU buffer or target once made, so a re-run of the same shape takes the
        // previous one's back; that is also what keeps the particles across a re-run.
        val shape = Shape(settings.name, capacity, settings.maxStrokes)
        val previous = synchronized(kept) { kept.getOrPut(renderer) { HashMap() }[shape] }
        resources = previous ?: Resources(renderer, shape).also {
            synchronized(kept) { kept.getOrPut(renderer) { HashMap() }[shape] = it }
        }
        if (resources.owner != null && resources.owner !== module) resources.reset()
        resources.activate()
        resources.owner = module
        resources.settings = settings

        mesh = ComputeMesh(renderer, module)
        mesh.push.bind("src") { resources.particles[resources.current] }
        mesh.push.bind("dst") { resources.particles[1 - resources.current] }
        mesh.push.bind("strokes") { resources.strokes }
        mesh.push.bind("spawns") { resources.spawns }
        mesh.push.bind("world") { resources.world }
        mesh.push.bind("gridValues") { resources.grid.values }
        mesh.push.bind("cellStart") { resources.grid.cellStart }
        mesh.push.bind("cellEnd") { resources.grid.cellEnd }
        mesh.push.bind("phase") { resources.phase }
        mesh.x = 1
        mesh.y = 1
        mesh.z = 1
        module += "${settings.name}-sketch" to mesh

        resources.install(module, mesh)
    }

    /** Empty the world and the picture; `t` starts again from 0. */
    fun reset() = resources.reset()

    /** Paint one particle at ([x], [y]), as a click in the window does. */
    fun paint(x: Double, y: Double) = resources.enqueue(x, y)

    /**
     * Paint along the line from ([x0], [y0]) to ([x1], [y1]) as a drag does: a particle every
     * [ParticleSketchSettings.paintSpacing] pixels, the first one spacing in from the start.
     */
    fun paint(x0: Double, y0: Double, x1: Double, y1: Double) = resources.paintAlong(x0, y0, x1, y1)

    /** Paint along a polyline of window points, as one drag through them. */
    fun paint(points: List<Vector2f>) {
        if (points.isEmpty()) return
        paint(points[0].x.toDouble(), points[0].y.toDouble())
        for (i in 1 until points.size) {
            paint(points[i - 1].x.toDouble(), points[i - 1].y.toDouble(), points[i].x.toDouble(), points[i].y.toDouble())
        }
    }

    /** The world's size in window points, as `width`/`height` read in the shader. */
    val size: Vector2f get() = vec(resources.worldWidth, resources.worldHeight)

    private data class Shape(val name: String, val capacity: Int, val maxStrokes: Int)

    private companion object {
        /** The resources each shape of sketch was last built with, per renderer. */
        val kept = java.util.WeakHashMap<Renderer, HashMap<Shape, Resources>>()

        const val LOCAL_SIZE = 64
        const val PARTICLE_FLOATS = 24
        const val STROKE_BYTES = 48
        const val WORLD_BYTES = 128
        const val SPAWNS_PER_FRAME = 4096
        const val CELL_SIZE = 72f
        const val CELLS = 64
        const val GRID_MARGIN = 2 * CELL_SIZE
        const val MAX_DT = 0.033
    }

    /**
     * Everything a sketch keeps between re-runs, and the frame it runs. One per (renderer, name,
     * capacity, stroke capacity).
     */
    private class Resources(val renderer: Renderer, val shape: Shape) : Target {
        var settings = ParticleSketchSettings()
        var owner: ComputeModulePointer? = null

        val particles: List<FrameBufferGroup> = List(2) { i ->
            defaultBuffer(name = "${shape.name}-particles-$i") {
                size = shape.capacity.toLong() * PARTICLE_FLOATS * 4
            }.toBuffer(renderer)
        }

        val strokes: FrameBufferGroup = defaultBuffer(name = "${shape.name}-strokes") {
            size = 16L + shape.maxStrokes.toLong() * STROKE_BYTES
        }.toBuffer(renderer)

        // Written by the CPU every frame, so one per frame in flight.
        val world: FrameBufferGroup = defaultBuffer(name = "${shape.name}-world") {
            size = WORLD_BYTES.toLong()
            perFrame = true
        }.toBuffer(renderer)

        val spawns: FrameBufferGroup = defaultBuffer(name = "${shape.name}-spawns") {
            size = SPAWNS_PER_FRAME * 8L
            perFrame = true
        }.toBuffer(renderer)

        val grid = GpuNeighbourGrid(
            capacity = shape.capacity,
            cellsPerSide = CELLS,
            domainMin = Vector2f(-GRID_MARGIN, -GRID_MARGIN),
            domainSize = CELLS * CELL_SIZE,
            renderer = renderer,
            name = "${shape.name}-grid",
        )

        val accumulation = PointAccumulation(renderer) {
            name = "${shape.name}-picture"
            blend = PointBlend.ALPHA
            fade = 1.0
        }

        private val gridTarget = grid.target({ particles[current] }, { count() }, PARTICLE_FLOATS, 0)

        @Volatile var current = 0
        @Volatile var spawned = 0L
        @Volatile var time = 0.0
        var phase = 0
        private var frame = 0
        private var lastNanos = 0L
        @Volatile private var resetPending = false

        val queue = ArrayDeque<Float>()

        // the pointer and the window's size in points, read on the window's poll
        private val brush = SpawnBrush()
        val windowPointer = WindowPointer(renderer.window, "${shape.name}-pointer") { p ->
            brush.spacing = settings.paintSpacing
            brush.step(p.x.toDouble(), p.y.toDouble(), p.down && settings.paintWithMouse, p.inside, ::enqueue)
        }
        val worldWidth get() = windowPointer.width
        val worldHeight get() = windowPointer.height

        private var module: ComputeModulePointer? = null
        private var mesh: ComputeMesh? = null
        private val bounds = Rectanglef(0f, 0f, 0f, 0f)
        private val sampler = TextureSampler(renderer, 1, false)

        init {
            buildDrawing()
        }

        /** Particles alive; 0 as soon as [reset] is asked for, though the frame applies it. */
        fun count(): Int = if (resetPending) 0 else min(spawned, shape.capacity.toLong()).toInt()

        fun install(module: ComputeModulePointer, mesh: ComputeMesh) {
            this.module = module
            this.mesh = mesh
        }

        fun reset() {
            synchronized(queue) { queue.clear() }
            resetPending = true
            accumulation.clear()
        }

        fun enqueue(x: Double, y: Double) {
            synchronized(queue) {
                queue.addLast(x.toFloat())
                queue.addLast(y.toFloat())
            }
        }

        fun paintAlong(x0: Double, y0: Double, x1: Double, y1: Double) =
            SpawnBrush.along(x0, y0, x1, y1, settings.paintSpacing, ::enqueue)

        private lateinit var strokesUnder: Mesh
        private lateinit var dots: Mesh
        private lateinit var strokesOver: Mesh

        private fun buildDrawing() {
            strokesUnder = strokeLayer("${shape.name}-links", 0f)
            dots = dotLayer("${shape.name}-dots")
            strokesOver = strokeLayer("${shape.name}-overlays", 1f)
        }

        private fun targetSize() = vec(accumulation.width.toFloat(), accumulation.height.toFloat())

        private fun strokeLayer(name: String, layer: Float): Mesh {
            val m = Module(accumulation.pointParameters(name) {
                vertex = ParticleShaders.source("strokes_vertex.glsl")
                fragment = ParticleShaders.source("shape_fragment.glsl")
            }).pointer()
            val mesh = Mesh(renderer, m).resize(shape.maxStrokes * 6)
            mesh.push.bind("strokes") { strokes }
            mesh.push.bind("size") { vec(worldWidth, worldHeight) }
            mesh.push.bind("targetSize") { targetSize() }
            mesh.push.bind("capacity") { shape.maxStrokes }
            mesh.push.bind("layer") { layer }
            m += "$name-quads" to mesh
            accumulation.add(name, m)
            return mesh
        }

        private fun dotLayer(name: String): Mesh {
            val m = Module(accumulation.pointParameters(name) {
                vertex = ParticleShaders.source("dots_vertex.glsl")
                fragment = ParticleShaders.source("shape_fragment.glsl")
            }).pointer()
            val mesh = Mesh(renderer, m).resize(shape.capacity * 6)
            mesh.push.bind("particles") { particles[current] }
            mesh.push.bind("size") { vec(worldWidth, worldHeight) }
            mesh.push.bind("targetSize") { targetSize() }
            mesh.push.bind("count") { count() }
            m += "$name-quads" to mesh
            accumulation.add(name, m)
            return mesh
        }

        private val displayModule: ModulePointer by lazy { buildDisplay() }

        /**
         * Make this the sketch running under its name: its frame and its display. Resources kept
         * for a re-run are activated again, because another shape of sketch under the same name
         * may have taken both in between.
         */
        fun activate() {
            renderer.renderList.preTargets["${shape.name}-sketch"] = this
            renderer.renderList += "${shape.name}-display" to displayModule
        }

        private fun buildDisplay(): ModulePointer {
            val name = "${shape.name}-display"
            val m = Module(
                parameters(renderer) {
                    this.name = name
                    depthTest = false
                    depthWrite = false
                    blend = false
                    vertex = PointShaders.source("fullscreen_vertex.glsl")
                    fragment = ParticleShaders.source("display_fragment.glsl")
                    textureResolver = { tex ->
                        if (tex.name == "accumulation") fromViews(listOf(accumulation.latestImageView), tex.name, sampler) else null
                    }
                }
            ).pointer()
            m += "$name-quad" to fullscreenTriangle(m).apply {
                push.bind("background") { settings.background }
            }
            return m
        }

        /** Takes the spawns queued since the last frame, at most a frame's worth. */
        private fun drainQueue(): FloatArray = synchronized(queue) {
            val n = min(queue.size / 2, SPAWNS_PER_FRAME)
            FloatArray(n * 2) { queue.removeFirst() }
        }

        override fun render(command: CommandBuffer, index: Int) {
            val module = module ?: return
            val mesh = mesh ?: return
            val s = settings

            if (resetPending) {
                spawned = 0L
                time = 0.0
                resetPending = false
            }

            val now = System.nanoTime()
            // Between steps nothing is recorded at all: the picture, and so the window, hold still.
            if (s.fps > 0 && lastNanos != 0L && now - lastNanos < (1e9 / s.fps).toLong()) return
            val frameSeconds = if (lastNanos == 0L) 1.0 / 60.0 else ((now - lastNanos) / 1e9).coerceIn(0.0, 1.0)
            val dt = frameSeconds.coerceAtMost(MAX_DT)
            lastNanos = now
            val stepping = !s.paused
            if (stepping) time += dt
            frame++

            var batch = drainQueue()
            // More paint in one frame than there is room for: only the newest capacity's worth can
            // survive, and two spawns writing one slot in the same dispatch would race, so the
            // older ones are skipped (they still count, so ids and the ring stay as if painted).
            val overflow = batch.size / 2 - shape.capacity
            if (overflow > 0) {
                spawned += overflow
                batch = batch.copyOfRange(overflow * 2, batch.size)
            }
            val spawnCount = batch.size / 2
            val spawnBase = (spawned % shape.capacity).toInt()
            val spawnId = spawned + 1
            spawned += spawnCount
            val alive = count()

            spawns.mapAndWrite { put(batch) }
            world.mapAndWrite_bytes {
                order(ByteOrder.nativeOrder())
                val c = s.particleColor
                putFloat(c.x); putFloat(c.y); putFloat(c.z); putFloat(c.w)
                putFloat(worldWidth); putFloat(worldHeight)
                putFloat(windowPointer.x); putFloat(windowPointer.y)
                putFloat(s.gravity.x); putFloat(s.gravity.y)
                putFloat(-GRID_MARGIN); putFloat(-GRID_MARGIN)
                putFloat(time.toFloat())
                putFloat(if (stepping) dt.toFloat() else 0f)
                putFloat(s.damping.toFloat())
                putFloat(s.maxSpeed.toFloat())
                putFloat(s.pairRadius.toFloat())
                putFloat(s.particleRadius.toFloat())
                putFloat(1f / CELL_SIZE)
                putInt(CELLS)
                putInt(if (windowPointer.inside) 1 else 0)
                putInt(if (windowPointer.down) 1 else 0)
                putInt(alive)
                putInt(shape.capacity)
                putInt(spawnBase)
                putInt(spawnCount)
                putInt(spawnId.toInt())
                putInt(shape.maxStrokes)
                putInt(frame)
                putInt(s.seed)
            }

            // last frame's draws read the buffers this frame's passes write
            graphicsToComputeBarrier(command)

            // 1. spawn: the paint queue becomes particles; the stroke list is cleared; draw() runs
            phase = 0
            mesh.x = ceil(max(spawnCount, 1) / LOCAL_SIZE.toDouble()).toInt()
            module.render(command, bounds)
            computeMemoryBarrier(command)

            if (stepping && alive > 0) {
                // 2. hash: the neighbour grid over every particle (it ends with its own barrier)
                gridTarget.render(command, index)
                // 3. step: eachPair, update, integrate -- from one buffer into the other
                phase = 1
                mesh.x = ceil(alive / LOCAL_SIZE.toDouble()).toInt()
                module.render(command, bounds)
                current = 1 - current
            }
            graphicsMemoryBarrier(command)

            // 4. draw: fade, links, dots, overlays
            // madp fades by 1 - trail once per frame at 60 frames a second; scaled to this step's
            // length so the trail is as long in seconds whatever fps is. Wall-clock time, so a
            // paused picture fades as madp's does.
            val keep = s.trail.coerceIn(0.0, 1.0).pow(frameSeconds * 60.0)
            accumulation.settings.fade = (1.0 - keep).coerceIn(0.0, 1.0)
            accumulation.render(command, index)
        }

        private fun graphicsToComputeBarrier(command: CommandBuffer) {
            MemoryStack.stackPush().use { stack ->
                val m = VkMemoryBarrier2.calloc(1, stack)
                m[0]
                    .sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER_2)
                    .srcStageMask(VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT or VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT)
                    .srcAccessMask(VK_ACCESS_2_SHADER_READ_BIT)
                    .dstStageMask(VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT)
                    .dstAccessMask(VK_ACCESS_2_SHADER_READ_BIT or VK_ACCESS_2_SHADER_WRITE_BIT)
                val dep = VkDependencyInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_DEPENDENCY_INFO).pMemoryBarriers(m)
                vkCmdPipelineBarrier2(command.vkCommandBuffer, dep)
            }
        }
    }
}

/**
 * Turns the pointer, read once a frame, into paint, as madp's canvas does with mouse events: a
 * press inside the window lays one particle, and moving with the button held lays one every
 * [spacing] pixels along the way (at least one per move). A drag that starts inside keeps painting
 * when it leaves the window; one that starts outside paints nothing.
 */
class SpawnBrush(var spacing: Double = 8.0) {
    private var painting = false
    private var wasDown = false
    private var lastX = 0.0
    private var lastY = 0.0

    /** One frame's pointer: where it is, whether the button is held, whether it is over the window. */
    fun step(x: Double, y: Double, down: Boolean, inside: Boolean, paint: (Double, Double) -> Unit) {
        val pressed = down && !wasDown
        if (pressed && inside) {
            painting = true
            paint(x, y)
        } else if (down && painting && (x != lastX || y != lastY)) {
            along(lastX, lastY, x, y, spacing, paint)
        } else if (!down) {
            painting = false
        }
        wasDown = down
        lastX = x
        lastY = y
    }

    companion object {
        /**
         * madp's paintAlong: particles every [spacing] pixels from just past ([x0], [y0]) to
         * ([x1], [y1]), at least one; a move shorter than 1e-4 pixels lays one at the end.
         */
        fun along(x0: Double, y0: Double, x1: Double, y1: Double, spacing: Double, paint: (Double, Double) -> Unit) {
            val dx = x1 - x0
            val dy = y1 - y0
            val dist = sqrt(dx * dx + dy * dy)
            if (dist < 1e-4) return paint(x1, y1)
            val steps = max(1, floor(dist / spacing.coerceAtLeast(0.5)).toInt())
            for (i in 1..steps) {
                val f = i.toDouble() / steps
                paint(x0 + dx * f, y0 + dy * f)
            }
        }
    }
}

/**
 * The packaged GLSL behind [ParticleSketch]. `#include "particles/sketch"` in a compute shader
 * reaches `sketch.glsl`, the harness with the particle struct, the hooks and the verbs.
 */
object ParticleShaders {
    private val names = setOf(
        "sketch.glsl", "strokes_vertex.glsl", "dots_vertex.glsl", "shape_fragment.glsl", "display_fragment.glsl"
    )

    /** Whether `particles/<name>` names one of these sources, with or without `.glsl`. */
    fun has(name: String): Boolean = withExtension(name) in names

    /** The source of `name` (`sketch`, `sketch.glsl`, ...). */
    fun source(name: String): String {
        val file = withExtension(name)
        require(file in names) { "Unknown particles shader: $name (have ${names.sorted()})" }
        return checkNotNull(javaClass.getResourceAsStream("/afield/four/particles/$file")) {
            "Missing packaged particles shader: $file"
        }.bufferedReader().use { it.readText() }
    }

    private fun withExtension(name: String) = if (name.endsWith(".glsl")) name else "$name.glsl"
}

/**
 * madp's toolbar for [sketch], in this box's `controls` output: the trail, a pause, and a reset
 * that empties the world. The panel opens, as the demo's toolbar is always in view.
 */
fun Box.showControls(sketch: ParticleSketch, channel: String = "controls") {
    val s = sketch.settings
    controlGroup(channel) {
        slider("trail", min = 0.0, max = 0.98, step = 0.01, initialValue = s.trail) { s.trail = it }
        checkbox("pause", initialValue = s.paused) { s.paused = it }
        button("reset") { down -> if (down) sketch.reset() }
    }
    displayOpen(channel)
}
