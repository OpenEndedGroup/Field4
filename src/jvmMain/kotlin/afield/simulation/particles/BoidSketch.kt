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
import afield.graphics.render.Parameters
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
import afield.runtime.lifecycle.Frames
import afield.runtime.lifecycle.loop
import afield.ui.output.controlGroup
import afield.ui.output.displayOpen
import afield.ui.output.markdown
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_ONE
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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Boids as a declarative spec: kinds of boid, each with a count, speed limits, a field of view, a
 * look and a list of Reynolds steering rules, all stepped together on the GPU over a neighbour
 * grid and drawn with tapered trails. The Field port of the boids page
 * (`~/temp/characters/boids.html`); `dev_docs/madp/boids.md` maps its `flock({...})` spec.
 */

/** What happens at the window's edges. */
enum class BoidEdges {
    /** Leave one side, come back in at the other; neighbours are found across the seam. */
    WRAP,

    /** Bounce off the walls. */
    BOUNCE
}

/** How a kind's body is drawn. */
enum class BoidShape { ARROW, KITE, DOT, LINE, NONE }

/** How trails and bodies combine with what is under them. */
enum class BoidBlend {
    /** Paint over, like a canvas's default `source-over`. */
    NORMAL,

    /** Add light, the page's `'lighter'`: overlapping trails glow. */
    LIGHTER
}

/**
 * One steering rule of a kind, made by the rule functions on [BoidKind] (`separate(12, 1.7)`).
 * Its force is the Reynolds steering toward what the rule wants, times [weight].
 */
class BoidRule internal constructor(
    /** The rule's name: `separate`, `align`, `chase`, ... */
    val type: String,
    internal val code: Int,
    /** How far it looks for neighbours, in pixels; 0 for a rule that does not look. */
    val radius: Double,
    val weight: Double,
    /** Which kinds it sees: `"all"`, `"same"`, `"other"`, a kind's name, or a list of names. */
    val among: Any,
    /** Whether it only sees neighbours inside its kind's field of view. */
    val fov: Boolean,
    internal val params: DoubleArray = DoubleArray(4),
    /** For seek and orbit: the point, as fractions of the window, as a function of `t` in steps. */
    internal val point: ((Double) -> Vector2f)? = null,
)

/**
 * A kind of boid: how many, how fast, what they see and how they look, and their [rules]. The
 * rule functions here (`separate`, `align`, `cohere`, `flee`, `chase`, `eat`, `wander`, `seek`,
 * `orbit`, `flow`, `avoidRocks`, `avoidEdges`, `mouse`, `cruise`, and `custom` for its `rule`) are the page's, with its
 * defaults; units are pixels and steps.
 */
class BoidKind(val name: String) {
    var count: Int = 100
    /** Pixels per step. */
    var maxSpeed: Double = 3.0
    var minSpeed: Double = 0.5
    /** The largest steering force a rule can apply, per step. */
    var maxForce: Double = 0.06
    /** The fraction of velocity lost every step. */
    var drag: Double = 0.0
    /** Field of view in degrees; 360 sees all round. */
    var fov: Double = 360.0
    var shape: BoidShape = BoidShape.ARROW
    /** Body size in pixels. */
    var size: Double = 3.0
    /** This kind's trail, as a multiple of the flock's [BoidSketchSettings.trail]. */
    var trail: Double = 1.0
    var trailAlpha: Double = 0.5
    var trailWidth: Double = 1.0
    /** Start in a disc at the centre (`"center"` on the page) rather than anywhere. */
    var spawnCentre: Boolean = false

    /** The rules, in the order their forces are added. */
    val rules = mutableListOf<BoidRule>()

    /** Set the rules, replacing any before. */
    fun rules(vararg r: BoidRule) {
        rules.clear()
        rules += r
    }

    private fun d(n: Number) = n.toDouble()

    /** Steer away from neighbours closer than [radius], as `1/d` (sees all kinds by default). */
    fun separate(radius: Number, weight: Number = 1, among: Any = "all", fov: Boolean = false) =
        BoidRule("separate", 0, d(radius), d(weight), among, fov)

    /** Match the average velocity of neighbours (own kind, in view, by default). */
    fun align(radius: Number, weight: Number = 1, among: Any = "same", fov: Boolean = true) =
        BoidRule("align", 1, d(radius), d(weight), among, fov)

    /** Steer toward the centre of neighbours (own kind, in view, by default). */
    fun cohere(radius: Number, weight: Number = 1, among: Any = "same", fov: Boolean = true) =
        BoidRule("cohere", 2, d(radius), d(weight), among, fov)

    /** Run from boids of [kind] within [radius], harder the closer they are. */
    fun flee(kind: Any, radius: Number, weight: Number = 1) =
        BoidRule("flee", 3, d(radius), d(weight), kind, false)

    /** Chase the nearest boid of [kind] within [radius], leading its motion. */
    fun chase(kind: Any, radius: Number, weight: Number = 1) =
        BoidRule("chase", 4, d(radius), d(weight), kind, false)

    /** Eat a boid of [kind] within [reach]; it respawns at an edge. Then rest for [rest] steps. */
    fun eat(kind: Any, reach: Number, rest: Number = 60) =
        BoidRule("eat", 5, d(reach), 0.0, kind, false, doubleArrayOf(d(rest), 0.0, 0.0, 0.0))

    /** Smooth random turning; [jitter] is how fast the turn drifts. */
    fun wander(weight: Number = 1, jitter: Number = 0.3) =
        BoidRule("wander", 6, 0.0, d(weight), "all", false, doubleArrayOf(d(jitter), 0.0, 0.0, 0.0))

    /** Steer toward a point given as fractions of the window. */
    fun seek(x: Number, y: Number, weight: Number = 1) = seek(weight) { vec(x, y) }

    /** Steer toward a moving point: fractions of the window, as a function of `t` in steps. */
    fun seek(weight: Number = 1, at: (Double) -> Vector2f) =
        BoidRule("seek", 7, 0.0, d(weight), "all", false, point = at)

    /** Circle a point (fractions of the window) at [radius] pixels; [dir] 1 or -1. */
    fun orbit(x: Number, y: Number, radius: Number, weight: Number = 1, dir: Number = 1) =
        BoidRule("orbit", 8, 0.0, d(weight), "all", false, doubleArrayOf(0.0, 0.0, d(radius), d(dir)), point = { vec(x, y) })

    /** Follow the angle field the compute tab's `flowAngle(uv, t)` returns (declare `FLOW_FIELD`). */
    fun flow(weight: Number = 1) = BoidRule("flow", 9, 0.0, d(weight), "all", false)

    /** Steer around the flock's rocks, starting [margin] pixels out. */
    fun avoidRocks(weight: Number = 1, margin: Number = 30) =
        BoidRule("avoidRocks", 10, 0.0, d(weight), "all", false, doubleArrayOf(d(margin), 0.0, 0.0, 0.0))

    /** Steer off the walls, starting [margin] pixels in. */
    fun avoidEdges(margin: Number, weight: Number = 1) =
        BoidRule("avoidEdges", 11, 0.0, d(weight), "all", false, doubleArrayOf(d(margin), 0.0, 0.0, 0.0))

    /** Toward the pointer (positive [weight]) or away from it (negative), within [radius]. */
    fun mouse(weight: Number, radius: Number = 120) =
        BoidRule("mouse", 12, 0.0, abs(d(weight)), "all", false, doubleArrayOf(d(radius), if (d(weight) < 0) -1.0 else 1.0, 0.0, 0.0))

    /** Hold a preferred [speed] along the current heading. */
    fun cruise(speed: Number, weight: Number = 1) =
        BoidRule("cruise", 13, 0.0, d(weight), "all", false, doubleArrayOf(d(speed), 0.0, 0.0, 0.0))

    /**
     * A hand-written rule over the neighbours within [radius], the page's `rule(r, fn)`: the
     * compute tab's `customStart`, `customNeighbour` and `customForce` (declare `CUSTOM_RULE`). Its
     * force is used as returned, times [weight]. (Box code reserves the name `rule` for rewrite
     * macros, hence `custom`.)
     */
    fun custom(radius: Number, weight: Number = 1, among: Any = "all", fov: Boolean = true) =
        BoidRule("custom", 14, d(radius), d(weight), among, fov)
}

/** A round obstacle at ([x], [y]) as fractions of the window, [r] pixels across from its centre. */
data class BoidRock(val x: Double, val y: Double, val r: Double)

/**
 * A flock: the page's `flock({...})` spec. Everything but the kinds' counts can change while it
 * runs; the flock reads it every frame.
 */
class BoidSketchSettings {
    /** Names the buffers and passes; a second flock under the same name replaces the first. */
    var name: String = "boids"
    var background: Vec4 = rgba(5, 6, 7, 1.0)
    var blend: BoidBlend = BoidBlend.NORMAL
    var edges: BoidEdges = BoidEdges.WRAP
    val rocks = mutableListOf<BoidRock>()
    /** Frames per second; the flock steps [steps] times in each. */
    var fps: Double = 60.0
    var steps: Int = 1
    /** Trail length in steps (each kind's [BoidKind.trail] multiplies it), at most 120. */
    var trail: Int = 14
    /** How much of the picture each frame paints over with the background: 1 clears it, 0.01 keeps it. */
    var fade: Double = 1.0
    /** Draw the inspected boid's field of view, rule radii and neighbours; click a boid to pick it. */
    var inspect: Boolean = false
    var paused: Boolean = false
    var seed: Int = 1
    internal val kinds = LinkedHashMap<String, BoidKind>()

    /** Declare a kind; kinds are numbered from 0 in the order they are declared, for the compute tab. */
    fun kind(name: String, configure: BoidKind.() -> Unit) {
        kinds[name] = BoidKind(name).apply(configure)
    }

    /** A rock at ([x], [y]) fractions of the window, [r] pixels in radius. */
    fun rock(x: Number, y: Number, r: Number) {
        rocks += BoidRock(x.toDouble(), y.toDouble(), r.toDouble())
    }
}

/**
 * The tables a flock's spec becomes on the GPU, worked out the way the page's `flock()` works them
 * out: each rule's `among` as a bit mask over kinds, each kind's query radius (its largest rule
 * radius) and its reach to every kind (the largest radius of its rules that see that kind), and
 * the grid's cell size (the most populous kind's query radius, at least 16).
 */
class BoidTables(settings: BoidSketchSettings) {
    val kinds: List<BoidKind> = settings.kinds.values.toList()
    val names: List<String> = kinds.map { it.name }

    init {
        require(kinds.isNotEmpty()) { "a flock needs at least one kind" }
        require(kinds.size <= MAX_KINDS) { "a flock has at most $MAX_KINDS kinds, not ${kinds.size}" }
        kinds.forEach { k ->
            require(k.rules.size <= MAX_RULES_PER_KIND) { "kind ${k.name} has ${k.rules.size} rules; at most $MAX_RULES_PER_KIND" }
        }
        require(kinds.sumOf { it.rules.size } <= MAX_RULES) { "a flock has at most $MAX_RULES rules" }
    }

    /** The bit mask of kinds [rule] of kind [self] sees. */
    fun among(rule: BoidRule, self: Int): Int = when (val a = rule.among) {
        "all" -> (1 shl kinds.size) - 1
        "same" -> 1 shl self
        "other" -> ((1 shl kinds.size) - 1) and (1 shl self).inv()
        is String -> 1 shl index(a)
        is Iterable<*> -> a.fold(0) { m, n -> m or (1 shl index(n.toString())) }
        else -> error("among must be all, same, other, a kind's name or a list of names, not $a")
    }

    private fun index(name: String): Int {
        val i = names.indexOf(name)
        require(i >= 0) { "a rule refers to an unknown kind \"$name\" (have ${names.joinToString()})" }
        return i
    }

    /** The largest rule radius of each kind. */
    val queryRadius: List<Double> = kinds.map { k -> k.rules.maxOfOrNull { it.radius } ?: 0.0 }

    /** reach[self][other]: how far kind self looks at kind other, squared. */
    val reachSquared: List<List<Double>> = kinds.mapIndexed { i, k ->
        kinds.indices.map { j ->
            val r = k.rules.filter { it.radius > 0 && (among(it, i) shr j) and 1 == 1 }.maxOfOrNull { it.radius } ?: 0.0
            r * r
        }
    }

    /** Cells are sized for the most populous kind; kinds that see further scan more of them. */
    val cellSize: Double = run {
        val maxR = max(10.0, queryRadius.maxOrNull() ?: 0.0)
        val busiest = kinds.indices.maxByOrNull { kinds[it].count } ?: 0
        max(queryRadius[busiest].takeIf { it > 0 } ?: maxR, 16.0)
    }

    /** The first boid of each kind; boids are laid out kind by kind. */
    val first: List<Int> = kinds.runningFold(0) { a, k -> a + max(0, k.count) }.dropLast(1)

    val count: Int = kinds.sumOf { max(0, it.count) }

    companion object {
        const val MAX_KINDS = 8
        const val MAX_RULES = 64
        const val MAX_RULES_PER_KIND = 12
        const val MAX_ROCKS = 8
    }
}

/**
 * Hosts a flock whose look is a compute shader built on `#include "boids/flock"`: the shader
 * defines `vec4 color(Boid b)` (and `flowAngle` or the custom-rule hooks when the spec uses them),
 * and this class does everything else: it writes the spec into the rule and kind tables, places
 * the boids, steps them [BoidSketchSettings.steps] times a frame at [BoidSketchSettings.fps] over a
 * [GpuNeighbourGrid], keeps a 121-step history of each, and draws rocks, trails, bodies and the
 * inspector into a [PointAccumulation] faded by [BoidSketchSettings.fade] each frame.
 *
 * ```
 * var module = computeModuleHere {}
 * val sim = BoidSketch(module) {
 *     background = hexColor("#06080b")
 *     kind("starling") {
 *         count = 800
 *         maxSpeed = 3.2; minSpeed = 1.6; maxForce = 0.07; fov = 280.0
 *         rules(separate(12, 1.7), align(32, 1.1), cohere(44, 0.95), wander(0.15), mouse(-3, 110))
 *     }
 * }
 * showControls(sim)
 * ```
 *
 * Coordinates are the renderer window's, in points, origin top left, y down; speeds are pixels
 * per step. Building a flock (running its box) starts it afresh, as the page does on compile.
 */
class BoidSketch(
    /** The compute module holding the flock's shader, usually `computeModuleHere {}`. */
    val module: ComputeModulePointer,
    val renderer: Renderer = fromContext(),
    configure: BoidSketchSettings.() -> Unit = {}
) {
    /** The spec, after `configure` ran; change it to steer the running flock. */
    val settings = BoidSketchSettings().apply(configure)

    /** The spec worked out into tables, as built. */
    val tables = BoidTables(settings)

    /** How many boids, fixed for the life of the flock. */
    val count: Int = tables.count

    private val resources: Resources

    /** The buffer holding the latest step's boids. */
    val latest: FrameBufferGroup get() = resources.boids[resources.current]

    /** The world's size in window points: the page's `W` and `H`. */
    val size: Vector2f get() = vec(resources.windowPointer.width, resources.windowPointer.height)

    /** Steps taken since the flock started, the page's `t`. */
    val steps: Long get() = resources.stepCount

    /** The 121-step history of every boid, `vec2`s, boid by boid. */
    val history: FrameBufferGroup get() = resources.history

    /** This frame's inspector and rock strokes, in [ParticleSketch.strokes]' format. */
    val strokes: FrameBufferGroup get() = resources.strokes

    init {
        require(count in 1..MAX_BOIDS) { "a flock has 1..$MAX_BOIDS boids, not $count" }
        val shape = Shape(settings.name, capacityFor(count), tables.cellSize)
        val previous = synchronized(kept) { kept.getOrPut(renderer) { HashMap() }[shape] }
        resources = previous ?: Resources(renderer, shape).also {
            synchronized(kept) { kept.getOrPut(renderer) { HashMap() }[shape] = it }
        }
        resources.flock = this
        resources.activate()

        val mesh = ComputeMesh(renderer, module)
        mesh.push.bind("src") { resources.boids[resources.current] }
        mesh.push.bind("dst") { resources.boids[1 - resources.current] }
        mesh.push.bind("history") { resources.history }
        mesh.push.bind("eaten") { resources.eaten }
        mesh.push.bind("pick") { resources.pick }
        mesh.push.bind("stats") { resources.stats }
        mesh.push.bind("strokes") { resources.strokes }
        mesh.push.bind("world") { resources.world }
        mesh.push.bind("gridValues") { resources.grid.values }
        mesh.push.bind("cellStart") { resources.grid.cellStart }
        mesh.push.bind("cellEnd") { resources.grid.cellEnd }
        mesh.push.bind("phase") { resources.phase }
        mesh.push.bind("step") { resources.stepInFrame }
        mesh.push.bind("lastStep") { if (resources.lastStep) 1 else 0 }
        mesh.push.bind("t") { resources.stepCount.toFloat() }
        mesh.x = 1; mesh.y = 1; mesh.z = 1
        module += "${settings.name}-flock" to mesh
        resources.install(module, mesh)
        resources.reset()
    }

    /** Place every boid afresh, as the page's reset (R). */
    fun reset() = resources.reset()

    /**
     * The metrics card, from the latest frame: polarization (how aligned the flock is, 0..1), mean
     * speed in pixels per step, visible flockmates per boid, boids per kind, how many were eaten,
     * and the step.
     */
    fun metrics(): BoidMetrics = resources.metrics()

    private data class Shape(val name: String, val capacity: Int, val cellSize: Double)

    private companion object {
        val kept = java.util.WeakHashMap<Renderer, HashMap<Shape, Resources>>()

        const val MAX_BOIDS = 8191
        const val BOID_FLOATS = 16
        const val TRAIL_CAP = 121
        const val WORLD_WORDS = 1012
        const val STROKES = 65536
        const val STROKE_BYTES = 48
        const val LOCAL_SIZE = 64
        const val GRID_EXTENT = 4096.0

        fun capacityFor(n: Int): Int {
            var c = 256
            while (c < n) c *= 2
            return min(c, MAX_BOIDS)
        }
    }

    private class Resources(val renderer: Renderer, val shape: Shape) : Target {
        lateinit var flock: BoidSketch
        val capacity = shape.capacity
        val settings get() = flock.settings

        val boids: List<FrameBufferGroup> = List(2) { i ->
            defaultBuffer(name = "${shape.name}-boids-$i") { size = capacity.toLong() * BOID_FLOATS * 4 }.toBuffer(renderer)
        }
        val history: FrameBufferGroup = defaultBuffer(name = "${shape.name}-history") {
            size = capacity.toLong() * TRAIL_CAP * 8
        }.toBuffer(renderer)
        val eaten: FrameBufferGroup = defaultBuffer(name = "${shape.name}-eaten") { size = capacity * 4L }.toBuffer(renderer)
        val pick: FrameBufferGroup = defaultBuffer(name = "${shape.name}-pick") { size = 16L }.toBuffer(renderer)
        val stats: FrameBufferGroup = defaultBuffer(name = "${shape.name}-stats") { size = 64L }.toBuffer(renderer)
        val strokes: FrameBufferGroup = defaultBuffer(name = "${shape.name}-strokes") {
            size = 16L + STROKES.toLong() * STROKE_BYTES
        }.toBuffer(renderer)
        // written by the CPU every frame, so one per frame in flight
        val world: FrameBufferGroup = defaultBuffer(name = "${shape.name}-world") {
            size = WORLD_WORDS * 4L
            perFrame = true
        }.toBuffer(renderer)

        val cellsPerSide = ceil(GRID_EXTENT / shape.cellSize).toInt().coerceIn(2, 1024)
        val grid = GpuNeighbourGrid(
            capacity = capacity,
            cellsPerSide = cellsPerSide,
            domainMin = Vector2f(0f, 0f),
            domainSize = (cellsPerSide * shape.cellSize).toFloat(),
            renderer = renderer,
            name = "${shape.name}-grid",
        )
        private val gridTarget = grid.target({ boids[current] }, { flock.count }, BOID_FLOATS, 0)

        val accumulation = PointAccumulation(renderer) {
            name = "${shape.name}-picture"
            blend = PointBlend.ALPHA
            fade = 1.0
        }

        @Volatile var current = 0
        @Volatile var stepCount = 0L
        var phase = 0
        var stepInFrame = 0
        var lastStep = false
        @Volatile private var resetPending = true
        @Volatile private var pickPending: Vector2f? = null
        private var lastNanos = 0L

        val windowPointer = WindowPointer(renderer.window, "${shape.name}-pointer") { p ->
            if (p.down && !wasDown && p.inside && settings.inspect) pickPending = vec(p.x, p.y)
            wasDown = p.down
        }
        private var wasDown = false

        private var module: ComputeModulePointer? = null
        private var mesh: ComputeMesh? = null
        private val bounds = Rectanglef(0f, 0f, 0f, 0f)
        private val sampler = TextureSampler(renderer, 1, false)

        init {
            strokeLayer("${shape.name}-rocks", 0f)
            trailLayer("${shape.name}-trails")
            bodyLayer("${shape.name}-bodies")
            strokeLayer("${shape.name}-inspector", 1f)
        }

        fun install(module: ComputeModulePointer, mesh: ComputeMesh) {
            this.module = module
            this.mesh = mesh
        }

        fun reset() {
            resetPending = true
            accumulation.clear()
        }

        private fun targetSize() = vec(accumulation.width.toFloat(), accumulation.height.toFloat())
        private fun worldSize() = vec(windowPointer.width, windowPointer.height)

        private fun strokeLayer(name: String, layer: Float) {
            val m = Module(accumulation.pointParameters(name) {
                vertex = ParticleShaders.source("strokes_vertex.glsl")
                fragment = ParticleShaders.source("shape_fragment.glsl")
            }).pointer()
            val mesh = Mesh(renderer, m).resize(STROKES * 6)
            mesh.push.bind("strokes") { strokes }
            mesh.push.bind("size") { worldSize() }
            mesh.push.bind("targetSize") { targetSize() }
            mesh.push.bind("capacity") { STROKES }
            mesh.push.bind("layer") { layer }
            m += "$name-quads" to mesh
            accumulation.add(name, m)
        }

        private fun trailLayer(name: String) {
            // the blend is fixed when the pipeline is built, and the same shape can be rebuilt
            // with another flock, so additive trails get their own module
            listOf(BoidBlend.NORMAL, BoidBlend.LIGHTER).forEach { mode ->
                val m = Module(accumulation.pointParameters("$name-$mode") {
                    vertex = BoidShaders.source("trails_vertex.glsl")
                    fragment = BoidShaders.source("trail_fragment.glsl")
                    if (mode == BoidBlend.LIGHTER) { dstColor = VK_BLEND_FACTOR_ONE; dstAlpha = VK_BLEND_FACTOR_ONE }
                }).pointer()
                val mesh = Mesh(renderer, m).resize(capacity * (TRAIL_CAP - 1) * 6)
                mesh.push.bind("boids") { boids[current] }
                mesh.push.bind("history") { history }
                mesh.push.bind("world") { world }
                mesh.push.bind("targetSize") { targetSize() }
                mesh.push.bind("count") { if (settings.blend == mode) flock.count else 0 }
                m += "$name-$mode-quads" to mesh
                accumulation.add("$name-$mode", m)
            }
        }

        private fun bodyLayer(name: String) {
            listOf(BoidBlend.NORMAL, BoidBlend.LIGHTER).forEach { mode ->
                val m = Module(accumulation.pointParameters("$name-$mode") {
                    vertex = BoidShaders.source("bodies_vertex.glsl")
                    fragment = BoidShaders.source("body_fragment.glsl")
                    if (mode == BoidBlend.LIGHTER) { dstColor = VK_BLEND_FACTOR_ONE; dstAlpha = VK_BLEND_FACTOR_ONE }
                }).pointer()
                val mesh = Mesh(renderer, m).resize(capacity * 6)
                mesh.push.bind("boids") { boids[current] }
                mesh.push.bind("world") { world }
                mesh.push.bind("targetSize") { targetSize() }
                mesh.push.bind("count") { if (settings.blend == mode) flock.count else 0 }
                m += "$name-$mode-quads" to mesh
                accumulation.add("$name-$mode", m)
            }
        }

        private val displayModule: ModulePointer by lazy { buildDisplay() }

        /**
         * Make this the flock running under its name: its frame and its display. Resources kept
         * for a re-run are activated again, because another shape of flock under the same name
         * may have taken both in between.
         */
        fun activate() {
            renderer.renderList.preTargets["${shape.name}-flock"] = this
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

        fun metrics(): BoidMetrics {
            val s = IntArray(16)
            stats.get().mapAndRead_ints { get(s) }
            val p = IntArray(4)
            pick.get().mapAndRead_ints { get(p) }
            val n = max(1, s[0])
            return BoidMetrics(
                boids = s[0],
                perKind = flock.tables.names.mapIndexed { i, name -> name to s[5 + i] },
                polarization = sqrt((s[1] / 1e4).let { it * it } + (s[2] / 1e4).let { it * it }) / n,
                meanSpeed = s[3] / 1e4 / n,
                visibleFlockmates = s[4].toDouble() / n,
                caught = p[2],
                step = stepCount,
                inspected = if (settings.inspect) p[1] else -1,
            )
        }

        private fun writeWorld(buffer: ByteBuffer, pickAt: Vector2f?) {
            val t = flock.tables
            val s = settings
            val w = windowPointer.width
            val h = windowPointer.height
            buffer.order(ByteOrder.nativeOrder())
            val f = buffer.asFloatBuffer()
            val i = buffer.asIntBuffer()
            fun pf(at: Int, v: Number) { f.put(at, v.toFloat()) }
            fun pi(at: Int, v: Int) { i.put(at, v) }
            pf(0, w); pf(1, h); pf(2, windowPointer.x); pf(3, windowPointer.y)
            pi(4, if (windowPointer.inside) 1 else 0)
            pi(5, if (s.edges == BoidEdges.WRAP) 1 else 0)
            pi(6, t.kinds.size)
            pi(7, flock.count)
            val rocks = s.rocks.take(BoidTables.MAX_ROCKS)
            pi(8, rocks.size)
            pi(9, ceil(w / t.cellSize).toInt().coerceIn(1, cellsPerSide))
            pi(10, ceil(h / t.cellSize).toInt().coerceIn(1, cellsPerSide))
            pi(11, if (s.inspect) 1 else 0)
            pf(12, t.cellSize); pf(13, 1.0 / t.cellSize)
            pi(14, cellsPerSide)
            pi(15, if (pickAt != null) 1 else 0)
            pf(16, pickAt?.x ?: 0f); pf(17, pickAt?.y ?: 0f)
            pi(18, s.trail.coerceIn(0, TRAIL_CAP - 1))
            pi(19, s.seed)
            rocks.forEachIndexed { k, r -> pf(20 + k * 4, r.x * w); pf(21 + k * 4, r.y * h); pf(22 + k * 4, r.r); pf(23 + k * 4, 0) }
            var ruleIndex = 0
            t.kinds.forEachIndexed { k, kind ->
                val b = 52 + k * 24
                pf(b, kind.maxSpeed); pf(b + 1, kind.minSpeed); pf(b + 2, kind.maxForce); pf(b + 3, kind.drag)
                pf(b + 4, if (kind.fov >= 360) -2.0 else cos(kind.fov / 2 * PI / 180))
                pf(b + 5, t.queryRadius[k]); pf(b + 6, kind.size); pf(b + 7, kind.trail)
                pf(b + 8, kind.trailAlpha); pf(b + 9, kind.trailWidth)
                pi(b + 10, kind.shape.ordinal); pi(b + 11, if (kind.spawnCentre) 1 else 0)
                pi(b + 12, ruleIndex); pi(b + 13, kind.rules.size)
                pi(b + 14, t.first[k]); pi(b + 15, max(0, kind.count))
                for (o in 0 until 8) pf(b + 16 + o, if (o < t.kinds.size) t.reachSquared[k][o] else 0.0)
                kind.rules.forEach { r ->
                    val rb = 244 + ruleIndex * 12
                    pi(rb, r.code); pf(rb + 1, r.radius); pf(rb + 2, r.weight); pi(rb + 3, t.among(r, k))
                    pi(rb + 4, if (r.fov) 1 else 0); pi(rb + 5, 0); pi(rb + 6, 0); pi(rb + 7, 0)
                    val p = r.params
                    val at = r.point?.invoke(stepCount.toDouble())
                    pf(rb + 8, if (at != null) at.x * w else p[0])
                    pf(rb + 9, if (at != null) at.y * h else p[1])
                    pf(rb + 10, p[2]); pf(rb + 11, p[3])
                    ruleIndex++
                }
            }
        }

        private fun dispatch(command: CommandBuffer, phase: Int, items: Int) {
            val module = module ?: return
            val mesh = mesh ?: return
            this.phase = phase
            mesh.x = ceil(max(items, 1) / LOCAL_SIZE.toDouble()).toInt()
            module.render(command, bounds)
            computeMemoryBarrier(command)
        }

        override fun render(command: CommandBuffer, index: Int) {
            // boids are placed by the window's size in points, which the first poll brings
            if (module == null || !::flock.isInitialized || !windowPointer.polled) return
            val s = settings
            val now = System.nanoTime()
            if (s.fps > 0 && lastNanos != 0L && now - lastNanos < (1e9 / s.fps).toLong()) return
            lastNanos = now

            val pickAt = pickPending
            pickPending = null
            world.mapAndWrite_bytes { writeWorld(this, pickAt) }

            graphicsToComputeBarrier(command)
            if (resetPending) {
                resetPending = false
                stepCount = 0
                dispatch(command, 0, flock.count)
            }
            // paused, the picture holds and the metrics keep the last frame's numbers
            if (s.paused) return
            dispatch(command, 1, 1)
            val n = s.steps.coerceIn(1, 16)
            for (k in 0 until n) {
                stepInFrame = k
                lastStep = k == n - 1
                gridTarget.render(command, index)
                dispatch(command, 2, flock.count)
                dispatch(command, 3, flock.count)
                current = 1 - current
                stepCount++
            }
            graphicsMemoryBarrier(command)
            accumulation.settings.fade = s.fade.coerceIn(0.0, 1.0)
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

/** The page's metrics card. */
data class BoidMetrics(
    val boids: Int,
    val perKind: List<Pair<String, Int>>,
    /** The length of the mean heading, 0 (every way) to 1 (all one way). */
    val polarization: Double,
    /** Pixels per step. */
    val meanSpeed: Double,
    /** Flockmates of its own kind each boid sees, on average. */
    val visibleFlockmates: Double,
    val caught: Int,
    val step: Long,
    /** The inspected boid, or -1. */
    val inspected: Int,
) {
    /** The card as Markdown. */
    fun markdown(): String = buildString {
        append("**boids** ").append(perKind.joinToString(", ") { (n, c) -> "$c $n" }).append("  \n")
        append("**polarization** %.3f  \n".format(polarization))
        append("**mean speed** %.2f px/step  \n".format(meanSpeed))
        append("**visible flockmates** %.1f  \n".format(visibleFlockmates))
        if (caught > 0) append("**caught** $caught  \n")
        append("**step** $step")
        if (inspected >= 0) append("  \n**inspecting** #$inspected (click a boid in the window to pick another)")
    }
}

/**
 * The packaged GLSL behind [BoidSketch]. `#include "boids/flock"` in a compute shader reaches
 * `flock.glsl`, the harness with the boid struct, the tables, every rule and the step.
 */
object BoidShaders {
    private val names = setOf("flock.glsl", "trails_vertex.glsl", "trail_fragment.glsl", "bodies_vertex.glsl", "body_fragment.glsl")

    /** Whether `boids/<name>` names one of these sources, with or without `.glsl`. */
    fun has(name: String): Boolean = withExtension(name) in names

    /** The source of `name` (`flock`, `flock.glsl`, ...). */
    fun source(name: String): String {
        val file = withExtension(name)
        require(file in names) { "Unknown boids shader: $name (have ${names.sorted()})" }
        return checkNotNull(javaClass.getResourceAsStream("/afield/four/boids/$file")) {
            "Missing packaged boids shader: $file"
        }.bufferedReader().use { it.readText() }
    }

    private fun withExtension(name: String) = if (name.endsWith(".glsl")) name else "$name.glsl"
}

/**
 * The page's toolbar and metrics card for [flock], in this box's `controls` and `metrics` outputs:
 * fps, steps per frame, trail, fade, pause, inspect and reset. Return it from the box (make it the
 * last line) and the metrics card refreshes twice a second.
 */
fun Box.showControls(flock: BoidSketch, channel: String = "controls"): Frames {
    val s = flock.settings
    controlGroup(channel) {
        slider("fps", min = 1.0, max = 120.0, step = 1.0, initialValue = s.fps) { s.fps = it }
        slider("steps", min = 1.0, max = 16.0, step = 1.0, initialValue = s.steps.toDouble()) { s.steps = it.toInt() }
        slider("trail", min = 0.0, max = 120.0, step = 1.0, initialValue = s.trail.toDouble()) { s.trail = it.toInt() }
        slider("fade", min = 0.0, max = 1.0, step = 0.01, initialValue = s.fade) { s.fade = it }
        checkbox("pause", initialValue = s.paused) { s.paused = it }
        checkbox("inspect", initialValue = s.inspect) { s.inspect = it }
        button("reset") { down -> if (down) flock.reset() }
    }
    displayOpen(channel)
    return loop {
        markdown("metrics", flock.metrics().markdown())
        delay(500)
    }
}
