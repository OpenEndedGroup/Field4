@file:afield.CompletionCategory("ALife")

package afield.stdlib

import afield.graphics.FLine
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Structural spatial ALife: a bracketed, parametric, optionally stochastic L-system with a turtle that
 * emits [FLine]s, plus space-colonization branching.
 *
 * See `alife.kt` for the family overview.
 *
 * L-systems are the odd one out in this family and belong in it for a precise reason: they are the one
 * generative system here whose output can be checked **exactly and combinatorially** rather than
 * statistically. Lindenmayer's original algae system has Fibonacci-length derivations; the Koch
 * *snowflake* has exactly `3 * 4^n` forward symbols after `n` rewrites (the quadratic Koch island this
 * file also ships, [LSYSTEM_KOCH_QUADRATIC], has `5^n` — check which one you are asserting about); the
 * Koch snowflake's turtle path closes to a
 * measurable area. Those are equalities, not distributions, so they are the sharpest evidence in the
 * whole family — and they exercise the same turtle that draws the pretty trees.
 */

// ==============================================================================================
// Rewriting
// ==============================================================================================

/**
 * One production of an [LSystem]: replace [symbol] with [replacement], with relative [weight] when
 * several productions compete for the same symbol.
 *
 * @property symbol the single character this production rewrites
 * @property replacement what it becomes
 * @property weight relative probability among the productions sharing [symbol]; equal weights make a
 *   uniform choice, and a single production per symbol makes the system deterministic
 */
data class LProduction(val symbol: Char, val replacement: String, val weight: Double = 1.0) {
    init { require(weight > 0.0) { "production weight must be > 0, got $weight" } }
}

/**
 * A Lindenmayer system: an axiom plus productions, rewritten in parallel.
 *
 * "In parallel" is the defining property and the usual first bug: every symbol of generation `n` is
 * rewritten using generation `n`'s string, so the productions cannot see each other's output. A
 * left-to-right in-place rewrite is a different (and much less interesting) formalism.
 *
 * Symbols with no production are **constants** and pass through unchanged, which is how the turtle
 * commands (`+`, `-`, `[`, `]`) survive rewriting.
 *
 * @property axiom the generation-0 string
 * @property productions the rewrite rules
 */
class LSystem(val axiom: String, val productions: List<LProduction>) {

    /** Productions grouped by the symbol they rewrite. */
    private val bySymbol: Map<Char, List<LProduction>> = productions.groupBy { it.symbol }

    /** `true` when no symbol has more than one production, so derivation needs no randomness. */
    val deterministic: Boolean get() = bySymbol.values.all { it.size == 1 }

    /**
     * One parallel rewrite of [s].
     *
     * @param rng used only when a symbol has several productions; a deterministic system ignores it
     */
    fun rewrite(s: String, rng: kotlin.random.Random = kotlin.random.Random(0)): String {
        val b = StringBuilder(s.length * 2)
        for (ch in s) {
            val ps = bySymbol[ch]
            if (ps == null) { b.append(ch); continue }
            if (ps.size == 1) { b.append(ps[0].replacement); continue }
            val total = ps.sumOf { it.weight }
            var r = rng.nextDouble() * total
            var chosen = ps.last()
            for (p in ps) { r -= p.weight; if (r <= 0.0) { chosen = p; break } }
            b.append(chosen.replacement)
        }
        return b.toString()
    }

    /**
     * Derive [generations] rewrites from the axiom, returning the final string.
     *
     * @param maxLength abort with an exception if the string exceeds this. L-system growth is
     *   exponential — the Koch curve quadruples every generation — and the difference between
     *   "generation 8" and "generation 14" is the difference between 65 k symbols and 268 M. A cap that
     *   fails loudly is much kinder than an OutOfMemoryError in a live box.
     */
    fun derive(
        generations: Int,
        rng: kotlin.random.Random = kotlin.random.Random(0),
        maxLength: Int = 4_000_000
    ): String {
        require(generations >= 0) { "generations must be >= 0, got $generations" }
        var s = axiom
        repeat(generations) {
            s = rewrite(s, rng)
            require(s.length <= maxLength) {
                "L-system exceeded $maxLength symbols during derivation — lower `generations`"
            }
        }
        return s
    }

    /** Every generation from the axiom to [generations] inclusive, so the growth itself is inspectable. */
    fun derivations(generations: Int, rng: kotlin.random.Random = kotlin.random.Random(0)): List<String> {
        require(generations >= 0) { "generations must be >= 0, got $generations" }
        val out = ArrayList<String>(generations + 1)
        var s = axiom
        out.add(s)
        repeat(generations) { s = rewrite(s, rng); out.add(s) }
        return out
    }

    override fun toString(): String =
        "LSystem('$axiom', ${productions.size} production(s)${if (deterministic) "" else ", stochastic"})"
}

/**
 * Build an [LSystem] from an axiom and `symbol to replacement` pairs — the terse form.
 *
 * ```
 * val koch = lSystem("F", 'F' to "F+F-F-F+F")
 * ```
 * Repeat a symbol to make it stochastic; the weights are then all 1.
 */
fun lSystem(axiom: String, vararg rules: Pair<Char, String>): LSystem =
    LSystem(axiom, rules.map { LProduction(it.first, it.second) })

/**
 * Lindenmayer's original algae system: `A -> AB`, `B -> A`, from the axiom `A`.
 *
 * The generation lengths are the Fibonacci numbers `1, 2, 3, 5, 8, 13, …`, which is the reason this
 * system is in every textbook and the reason it is in this file: it gives the rewriting engine an exact
 * arithmetic fact to be checked against, independent of any turtle or any picture.
 */
val LSYSTEM_ALGAE: LSystem = lSystem("A", 'A' to "AB", 'B' to "A")

/**
 * The Koch curve (quadratic island form): `F -> F+F-F-F+F`, drawn with 90° turns.
 *
 * Each rewrite replaces one `F` with five, so generation `n` has exactly `5^n` forward symbols. (The
 * *classic* triangular Koch curve, `F -> F+F--F+F` at 60°, quadruples instead; both are provided —
 * see [LSYSTEM_KOCH_SNOWFLAKE].)
 */
val LSYSTEM_KOCH_QUADRATIC: LSystem = lSystem("F", 'F' to "F+F-F-F+F")

/**
 * The Koch snowflake: axiom `F--F--F` with `F -> F+F--F+F`, drawn with 60° turns.
 *
 * Exactly `3 * 4^n` forward symbols at generation `n`, and its turtle path is **closed** — the pen
 * returns to where it started, which is a geometric equality the turtle can be checked against rather
 * than a picture that looks about right. See [turtleClosureError].
 */
val LSYSTEM_KOCH_SNOWFLAKE: LSystem = lSystem("F--F--F", 'F' to "F+F--F+F")

/**
 * The Sierpiński arrowhead curve: `A -> B-A-B`, `B -> A+B+A`, 60° turns, drawn from `A`.
 *
 * Both `A` and `B` are **drawing** symbols here, so it must be interpreted with
 * `TurtleParams(drawSymbols = "AB")`; with the default `"FG"` alphabet it renders nothing.
 */
val LSYSTEM_SIERPINSKI_ARROWHEAD: LSystem = LSystem(
    "A",
    listOf(LProduction('A', "B-A-B"), LProduction('B', "A+B+A"))
)

/** The dragon curve: `X -> X+YF+`, `Y -> -FX-Y`, 90° turns, axiom `FX`. */
val LSYSTEM_DRAGON: LSystem = LSystem(
    "FX",
    listOf(LProduction('X', "X+YF+"), LProduction('Y', "-FX-Y"))
)

/**
 * A bracketed binary tree: `F -> F[+F]F[-F]F`, axiom `F`, drawn with a ~25° branch angle.
 *
 * The bracket pair is what makes an L-system a *plant* rather than a curve: `[` pushes the turtle state
 * and `]` pops it, so a production can grow a side branch and return. Everything botanical about
 * L-systems is that one feature.
 */
val LSYSTEM_BINARY_TREE: LSystem = lSystem("F", 'F' to "F[+F]F[-F]F")

/** Prusinkiewicz's bushy plant: `X -> F[+X][-X]FX`, `F -> FF`, axiom `X`, ~25° turns. */
val LSYSTEM_BUSH: LSystem = LSystem(
    "X",
    listOf(LProduction('X', "F[+X][-X]FX"), LProduction('F', "FF"))
)

/**
 * A stochastic plant: the same symbol `X` has three competing productions, so two derivations with
 * different seeds are different plants of the same species.
 *
 * This is the right way to get variation. Jittering the turtle's angles produces a wobbly copy of one
 * plant; competing productions produce genuinely different *topologies*, which is what varies between
 * two real plants of a species.
 */
val LSYSTEM_STOCHASTIC_PLANT: LSystem = LSystem(
    "X",
    listOf(
        LProduction('X', "F[+X][-X]FX", weight = 1.0),
        LProduction('X', "F[+X]FX", weight = 1.0),
        LProduction('X', "F[-X]FX", weight = 1.0),
        LProduction('F', "FF")
    )
)

// ==============================================================================================
// Turtle
// ==============================================================================================

/**
 * Turtle-graphics parameters for interpreting an L-system string.
 *
 * The command alphabet, which is the de-facto standard one:
 *
 * | symbol | meaning |
 * |---|---|
 * | any of [drawSymbols] (`F`, `G` by default) | move forward drawing a line |
 * | `f` | move forward **without** drawing (a pen-up jump) |
 * | `+` | turn left by [angle] |
 * | `-` | turn right by [angle] |
 * | `\|` | turn 180° |
 * | `[` | push position, heading, step and width |
 * | `]` | pop them |
 * | `!` | multiply the line width by [widthScale] |
 * | `"` | multiply the step length by [stepScale] |
 * | anything else | ignored (it is a rewriting-only symbol like `X`) |
 *
 * @property angle turn per `+`/`-`, in radians
 * @property step initial forward distance
 * @property stepScale factor applied by `"` and, when [scaleOnBranch], on every `[`
 * @property widthScale factor applied by `!` and, when [scaleOnBranch], on every `[`
 * @property scaleOnBranch when `true`, entering a branch shortens and thins it automatically, which is
 *   what makes an unadorned `F -> F[+F]F[-F]F` look like a plant instead of a wire diagram
 * @property angleJitter random fraction of [angle] added to each turn; 0 is exactly reproducible
 * @property drawSymbols which characters mean "move forward drawing".
 *
 *   `F`/`G` covers most systems, but not all: the Sierpiński **arrowhead** curve is written
 *   `A -> B-A-B`, `B -> A+B+A` where `A` and `B` are *both* drawing symbols, and interpreting it with
 *   the default alphabet renders a blank image (which is how this parameter came to exist). A symbol
 *   that is neither here nor a turtle command is a rewriting-only non-terminal and is skipped.
 */
data class TurtleParams(
    val angle: Double = PI / 8.0,
    val step: Double = 1.0,
    val stepScale: Double = 0.62,
    val widthScale: Double = 0.7,
    val scaleOnBranch: Boolean = false,
    val angleJitter: Double = 0.0,
    val drawSymbols: String = "FG"
) {
    init {
        require(step > 0.0) { "step must be > 0, got $step" }
        require(stepScale > 0.0) { "stepScale must be > 0, got $stepScale" }
        require(widthScale > 0.0) { "widthScale must be > 0, got $widthScale" }
        require(angleJitter >= 0.0) { "angleJitter must be >= 0, got $angleJitter" }
    }
}

/**
 * The result of interpreting an L-system string with a turtle.
 *
 * @property lines one [FLine] per unbroken drawn run — a branch, in plant terms. Bracketed side
 *   branches and pen-up jumps each start a new line, so the list *is* the branching structure.
 * @property widths the line width in force for each entry of [lines], parallel to it, so a renderer can
 *   taper a tree without re-walking the string
 * @property finalX/[finalY] where the pen ended up
 * @property startX/[startY] where it started
 * @property drawnSegments how many forward-drawing moves were made
 * @property maxDepth deepest bracket nesting reached
 */
class TurtleResult(
    val lines: List<FLine>,
    val widths: List<Double>,
    val startX: Double,
    val startY: Double,
    val finalX: Double,
    val finalY: Double,
    val drawnSegments: Int,
    val maxDepth: Int
) {
    /**
     * Distance from the pen's start to its end — 0 for a closed curve. This is the geometric equality
     * a closed L-system (the Koch snowflake) is checked against; see [turtleClosureError].
     */
    val closureError: Double get() = hypot(finalX - startX, finalY - startY)

    override fun toString(): String =
        "TurtleResult(${lines.size} line(s), $drawnSegments segment(s), depth $maxDepth, closure %.3g)"
            .format(closureError)
}

/**
 * Walk an L-system string with a turtle and return the [FLine]s it drew.
 *
 * @param s the derived L-system string
 * @param p turtle parameters
 * @param startHeading initial heading in radians; `-PI/2` points up the screen (y grows downward, the
 *   image convention the rest of the family uses)
 * @param rng used only for [TurtleParams.angleJitter]
 */
fun turtleDraw(
    s: String,
    p: TurtleParams = TurtleParams(),
    startX: Double = 0.0,
    startY: Double = 0.0,
    startHeading: Double = -PI / 2.0,
    rng: kotlin.random.Random = kotlin.random.Random(0)
): TurtleResult {
    var x = startX
    var y = startY
    var heading = startHeading
    var step = p.step
    var width = 1.0
    var depth = 0
    var maxDepth = 0
    var drawn = 0
    val lines = ArrayList<FLine>()
    val widths = ArrayList<Double>()
    var current: FLine? = null
    var currentWidth = width

    // (x, y, heading, step, width)
    val stack = ArrayList<DoubleArray>()

    fun beginLine() {
        if (current == null) {
            current = FLine().also { it.moveTo(x, y, 0) }
            currentWidth = width
        }
    }

    fun endLine() {
        current?.let { lines.add(it); widths.add(currentWidth) }
        current = null
    }

    fun turn(sign: Double) {
        val jitter = if (p.angleJitter <= 0.0) 0.0 else (rng.nextDouble() - 0.5) * 2.0 * p.angleJitter * p.angle
        heading += sign * (p.angle + jitter)
    }

    for (ch in s) {
        when {
            ch in p.drawSymbols -> {
                beginLine()
                x += cos(heading) * step
                y += sin(heading) * step
                current!!.lineTo(x, y, 0)
                drawn++
            }
            ch == 'f' -> {
                endLine()
                x += cos(heading) * step
                y += sin(heading) * step
            }
            ch == '+' -> turn(-1.0)
            ch == '-' -> turn(+1.0)
            ch == '|' -> heading += PI
            ch == '!' -> width *= p.widthScale
            ch == '"' -> step *= p.stepScale
            ch == '[' -> {
                endLine()
                stack.add(doubleArrayOf(x, y, heading, step, width))
                depth++
                if (depth > maxDepth) maxDepth = depth
                if (p.scaleOnBranch) { step *= p.stepScale; width *= p.widthScale }
            }
            ch == ']' -> {
                endLine()
                if (stack.isNotEmpty()) {
                    val st = stack.removeAt(stack.size - 1)
                    x = st[0]; y = st[1]; heading = st[2]; step = st[3]; width = st[4]
                    depth--
                }
            }
            else -> Unit
        }
    }
    endLine()
    return TurtleResult(lines, widths, startX, startY, x, y, drawn, maxDepth)
}

/**
 * Derive [system] for [generations] and draw it in one call — the ordinary entry point.
 *
 * ```
 * val tree = drawLSystem(LSYSTEM_BUSH, 5, TurtleParams(angle = Math.toRadians(25.0), scaleOnBranch = true))
 * lines(tree.lines)
 * ```
 */
fun drawLSystem(
    system: LSystem,
    generations: Int,
    p: TurtleParams = TurtleParams(),
    seed: Long = 1L,
    startX: Double = 0.0,
    startY: Double = 0.0,
    startHeading: Double = -PI / 2.0
): TurtleResult {
    val rng = kotlin.random.Random(seed)
    val s = system.derive(generations, rng)
    return turtleDraw(s, p, startX, startY, startHeading, rng)
}

/**
 * How far a turtle path fails to close, as a fraction of one step — a scale-free version of
 * [TurtleResult.closureError].
 *
 * The Koch snowflake, the Sierpiński arrowhead's closed forms and every "island" L-system return the
 * pen to its origin exactly (in real arithmetic). Measuring the residual in *steps* rather than in
 * absolute units is what makes "closes to within 1e-12 of a step" a claim that means the same thing at
 * every generation, since the step length changes with the figure's scale.
 */
fun turtleClosureError(r: TurtleResult, step: Double): Double {
    require(step > 0.0) { "step must be > 0, got $step" }
    return r.closureError / step
}

/**
 * Axis-aligned bounds of every point in [lines] as `(minX, minY, maxX, maxY)`, or `null` when there is
 * nothing drawn.
 */
fun flineBounds(lines: List<FLine>): DoubleArray? {
    var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE
    var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
    var any = false
    for (l in lines) for (n in l.nodes) {
        if (n is FLine.Close) continue  // Node(0): carries no coordinate, and closing adds no new point
        val px = n.to.x.toDouble()
        val py = n.to.y.toDouble()
        any = true
        minX = min(minX, px); minY = min(minY, py)
        maxX = max(maxX, px); maxY = max(maxY, py)
    }
    return if (any) doubleArrayOf(minX, minY, maxX, maxY) else null
}

/**
 * Total drawn length of [lines] — the sum of the straight-line distances between consecutive nodes
 * within each line.
 *
 * The quantity a Koch construction has an exact value for — but mind which construction. The textbook
 * `4/3` per generation is the ratio when the figure is held at constant *diameter*, i.e. the segment
 * length is divided by 3 as the count is multiplied by 4. [turtleDraw] holds the **step** constant
 * instead, so the figure triples in size each generation and the drawn length grows by exactly **4x**.
 * That is what `AlifeGallery` asserts (to `1e-5`, at every generation), and a turtle that has miscounted
 * a turn reproduces neither ratio.
 */
fun flineTotalLength(lines: List<FLine>): Double {
    var total = 0.0
    for (l in lines) {
        // Walk the pen rather than consecutive node pairs. A [FLine.Close] carries no coordinate at all
        // (`Node(0)`, so `to` throws), and it means "segment back to this subpath's start" — which is real
        // length, not something to skip. Walking the pen also stops a second subpath's [FLine.MoveTo] from
        // contributing a spurious jump from wherever the previous one ended.
        var pen: Vec3? = null
        var subpathStart: Vec3? = null
        for (n in l.nodes) {
            when (n) {
                is FLine.Close -> {
                    val from = pen
                    val back = subpathStart
                    if (from != null && back != null) {
                        total += hypot((back.x - from.x).toDouble(), (back.y - from.y).toDouble())
                        pen = back
                    }
                }

                is FLine.MoveTo -> {
                    pen = n.to
                    subpathStart = n.to
                }

                else -> {
                    val from = pen
                    if (from != null) {
                        total += hypot((n.to.x - from.x).toDouble(), (n.to.y - from.y).toDouble())
                    } else {
                        subpathStart = n.to
                    }
                    pen = n.to
                }
            }
        }
    }
    return total
}

/**
 * Signed area enclosed by the polygon through every node of [lines], by the shoelace formula. Meaningful
 * only for a single closed curve, which is exactly the case it is used for: a Koch snowflake's area is a
 * known multiple of its generating triangle's, so it is a second independent check on the turtle.
 */
fun flineSignedArea(lines: List<FLine>): Double {
    val pts = ArrayList<Pair<Double, Double>>()
    // Skip [FLine.Close]: it is `Node(0)`, so `to` throws, and the shoelace sum below already wraps from
    // the last point back to the first — an explicit close contributes no new vertex.
    for (l in lines) for (n in l.nodes) if (n !is FLine.Close) pts.add(n.to.x.toDouble() to n.to.y.toDouble())
    if (pts.size < 3) return 0.0
    var a = 0.0
    for (i in pts.indices) {
        val (x1, y1) = pts[i]
        val (x2, y2) = pts[(i + 1) % pts.size]
        a += x1 * y2 - x2 * y1
    }
    return a / 2.0
}

/**
 * Rasterise [lines] into a 1-channel [Img], scaled and centred to fit with a [margin]-pixel border —
 * the bridge from turtle geometry back into the raster half of the family, so an L-system can be
 * measured with [Img.blobShapes], skeletonised, or fed to a CA as a seed.
 *
 * Lines are drawn with a plain Bresenham stroke of width 1; this is a measurement and documentation
 * raster, not a renderer (Field's own drawing layer is the renderer).
 */
fun rasterizeFLines(lines: List<FLine>, width: Int, height: Int, margin: Int = 4): Img {
    require(width >= 8 && height >= 8) { "raster must be at least 8x8, got ${width}x$height" }
    require(margin >= 0) { "margin must be >= 0, got $margin" }
    val out = Img.zeros(width, height, 1)
    val b = flineBounds(lines) ?: return out
    val bw = max(1e-9, b[2] - b[0])
    val bh = max(1e-9, b[3] - b[1])
    val s = min((width - 2.0 * margin) / bw, (height - 2.0 * margin) / bh)
    val ox = margin + ((width - 2.0 * margin) - bw * s) / 2.0
    val oy = margin + ((height - 2.0 * margin) - bh * s) / 2.0
    fun px(v: Double) = ((v - b[0]) * s + ox)
    fun py(v: Double) = ((v - b[1]) * s + oy)
    fun plot(xi: Int, yi: Int) {
        if (xi in 0 until width && yi in 0 until height) out.data[yi * width + xi] = 1f
    }
    for (l in lines) {
        // Walk the pen, so a Close draws the closing segment back to the subpath start (and does not
        // dereference its own absent coordinate), and a second subpath's MoveTo does not draw a stroke
        // from wherever the previous subpath ended.
        var pen: FLine.Node? = null
        var subpathStart: FLine.Node? = null
        for (n in l.nodes) {
            val target: FLine.Node? = when {
                n is FLine.Close -> subpathStart
                n is FLine.MoveTo -> { pen = n; subpathStart = n; null }
                else -> n
            }
            val from = pen
            if (target == null || from == null) {
                if (n !is FLine.Close && n !is FLine.MoveTo) { pen = n; subpathStart = subpathStart ?: n }
                continue
            }
            var x0 = Math.round(px(from.to.x.toDouble())).toInt()
            var y0 = Math.round(py(from.to.y.toDouble())).toInt()
            val x1 = Math.round(px(target.to.x.toDouble())).toInt()
            val y1 = Math.round(py(target.to.y.toDouble())).toInt()
            pen = target
            val dx = abs(x1 - x0); val sx = if (x0 < x1) 1 else -1
            val dy = -abs(y1 - y0); val sy = if (y0 < y1) 1 else -1
            var err = dx + dy
            while (true) {
                plot(x0, y0)
                if (x0 == x1 && y0 == y1) break
                val e2 = 2 * err
                if (e2 >= dy) { err += dy; x0 += sx }
                if (e2 <= dx) { err += dx; y0 += sy }
            }
        }
    }
    return out
}

// ==============================================================================================
// Space colonization
// ==============================================================================================

/**
 * A branching structure grown by space colonization.
 *
 * @property x node x coordinates
 * @property y node y coordinates
 * @property parent parent index per node, `-1` for the root
 * @property consumedAttractors how many attractors were reached and removed
 * @property remainingAttractors attractors the structure never got close enough to
 */
class ColonizationTree(
    val x: DoubleArray,
    val y: DoubleArray,
    val parent: IntArray,
    val consumedAttractors: Int,
    val remainingAttractors: List<Vec2>
) {
    init {
        require(x.size == y.size && x.size == parent.size) { "ColonizationTree arrays must match" }
        require(x.isNotEmpty()) { "a tree needs at least a root" }
    }

    /** How many nodes. */
    val nodeCount: Int get() = x.size

    /** How many parent→child edges — exactly `nodeCount - 1` for a tree, which is worth checking. */
    val edgeCount: Int get() = parent.count { it >= 0 }

    /**
     * `true` when the structure really is a tree: exactly one root, `nodeCount - 1` edges, every
     * non-root parent index valid and smaller than its child (so there can be no cycle).
     *
     * A growth algorithm producing a graph with a cycle is a common and near-invisible bug — the picture
     * looks the same — so this is asserted rather than assumed.
     */
    fun isTree(): Boolean {
        if (parent.count { it < 0 } != 1) return false
        if (edgeCount != nodeCount - 1) return false
        for (i in parent.indices) {
            val p = parent[i]
            if (p < 0) continue
            if (p >= i) return false
        }
        return true
    }

    /** One [FLine] per parent→child edge. */
    fun toFLines(z: Number = 0): List<FLine> = (0 until nodeCount).mapNotNull { i ->
        val p = parent[i]
        if (p < 0) null else FLine().also { it.moveTo(x[p], y[p], z); it.lineTo(x[i], y[i], z) }
    }

    /**
     * Depth of every node from the root, in edges — the "order" of a branch, which is what a renderer
     * needs to taper a tree.
     */
    fun depths(): IntArray {
        val d = IntArray(nodeCount)
        for (i in 0 until nodeCount) {
            val p = parent[i]
            d[i] = if (p < 0) 0 else d[p] + 1
        }
        return d
    }

    override fun toString(): String =
        "ColonizationTree($nodeCount nodes, $consumedAttractors attractors consumed, " +
            "${remainingAttractors.size} left)"
}

/**
 * Space colonization (Runions et al.): grow a tree toward a cloud of attractors.
 *
 * Each iteration, every attractor within [influenceRadius] of some node votes for its nearest node; each
 * node with votes grows one new child of length [stepSize] in the average direction of its voters; any
 * attractor within [killRadius] of a node is consumed. Growth stops when the cloud is exhausted or when
 * an iteration adds no node — and it really does stop: the loop used to be a `repeat` whose early exits
 * were `break@grow`, which is `continue` rather than `break`, so a finished tree idled through the
 * remaining iterations re-allocating three per-node arrays each time.
 *
 * The reason this is in an ALife family rather than a modelling one: the *form* is not authored. Give
 * the algorithm a cloud shaped like a maple leaf and it grows maple venation; give it a sparse cloud and
 * it grows a sparse tree. The branching angles, the tapering of branch density and the competition
 * between branches for the same space all fall out of "grow toward the nearest unclaimed space", which
 * is the same principle that produces leaf venation and coral.
 *
 * @param attractors the space to be colonised
 * @param rootX/[rootY] where the trunk starts
 * @param rootHeading initial growth direction in radians
 * @param influenceRadius how far an attractor can pull a node
 * @param killRadius how close a node must get to consume an attractor; must be smaller than
 *   [influenceRadius] or an attractor is consumed before it can steer anything
 * @param maxIterations safety bound
 */
fun spaceColonization(
    attractors: List<Vec2>,
    rootX: Double,
    rootY: Double,
    rootHeading: Double = -PI / 2.0,
    influenceRadius: Double = 24.0,
    killRadius: Double = 6.0,
    stepSize: Double = 4.0,
    maxIterations: Int = 400
): ColonizationTree {
    require(influenceRadius > killRadius) {
        "influenceRadius ($influenceRadius) must exceed killRadius ($killRadius), or attractors are " +
            "consumed before they can steer anything"
    }
    require(stepSize > 0.0) { "stepSize must be > 0, got $stepSize" }
    require(maxIterations >= 1) { "maxIterations must be >= 1, got $maxIterations" }

    val xs = ArrayList<Double>()
    val ys = ArrayList<Double>()
    val ps = ArrayList<Int>()
    xs.add(rootX); ys.add(rootY); ps.add(-1)
    // A short trunk in the root heading, so the first growth step has a direction to inherit.
    xs.add(rootX + cos(rootHeading) * stepSize)
    ys.add(rootY + sin(rootHeading) * stepSize)
    ps.add(0)

    val live = attractors.toMutableList()
    var consumed = 0
    val inf2 = influenceRadius * influenceRadius
    val kill2 = killRadius * killRadius

    grow@ for (iteration in 0 until maxIterations) {
        if (live.isEmpty()) break@grow
        val dirX = DoubleArray(xs.size)
        val dirY = DoubleArray(xs.size)
        val votes = IntArray(xs.size)
        for (a in live) {
            var best = -1
            var bestD2 = Double.MAX_VALUE
            for (i in xs.indices) {
                val dx = a.x - xs[i]
                val dy = a.y - ys[i]
                val d2 = dx * dx + dy * dy
                if (d2 < bestD2) { bestD2 = d2; best = i }
            }
            if (best < 0 || bestD2 > inf2) continue
            val dx = a.x - xs[best]
            val dy = a.y - ys[best]
            val d = hypot(dx, dy).coerceAtLeast(1e-9)
            dirX[best] += dx / d
            dirY[best] += dy / d
            votes[best]++
        }
        val grownFrom = ArrayList<Int>()
        for (i in xs.indices) if (votes[i] > 0) grownFrom.add(i)
        if (grownFrom.isEmpty()) break@grow
        for (i in grownFrom) {
            val d = hypot(dirX[i], dirY[i]).coerceAtLeast(1e-9)
            xs.add(xs[i] + dirX[i] / d * stepSize)
            ys.add(ys[i] + dirY[i] / d * stepSize)
            ps.add(i)
        }
        val before = live.size
        live.removeAll { a ->
            xs.indices.any { i ->
                val dx = a.x - xs[i]
                val dy = a.y - ys[i]
                dx * dx + dy * dy <= kill2
            }
        }
        consumed += before - live.size
    }
    return ColonizationTree(
        xs.toDoubleArray(), ys.toDoubleArray(), ps.toIntArray(), consumed, live.toList()
    )
}

/**
 * A cloud of attractors filling a disc — the simplest colonisation target, and the one that makes the
 * "the form comes from the space" claim easiest to see against a leaf-shaped or ring-shaped cloud.
 */
fun discAttractors(count: Int, centerX: Double, centerY: Double, radius: Double, seed: Long = 1L): List<Vec2> {
    require(count >= 1) { "count must be >= 1, got $count" }
    require(radius > 0.0) { "radius must be > 0, got $radius" }
    val rng = kotlin.random.Random(seed)
    return (0 until count).map {
        val a = rng.nextDouble() * 2.0 * PI
        val r = radius * kotlin.math.sqrt(rng.nextDouble())
        Vec2((centerX + r * cos(a)).toFloat(), (centerY + r * sin(a)).toFloat())
    }
}

/**
 * A cloud of attractors filling an **annulus** — a shape a tree cannot fill by growing straight, so the
 * branching really has to follow the space. The clearest demonstration that space colonization is not
 * simply "grow upward with noise".
 */
fun annulusAttractors(
    count: Int,
    centerX: Double,
    centerY: Double,
    innerRadius: Double,
    outerRadius: Double,
    seed: Long = 1L
): List<Vec2> {
    require(outerRadius > innerRadius && innerRadius >= 0.0) {
        "need 0 <= innerRadius < outerRadius, got $innerRadius..$outerRadius"
    }
    val rng = kotlin.random.Random(seed)
    return (0 until count).map {
        val a = rng.nextDouble() * 2.0 * PI
        val u = rng.nextDouble()
        val r = kotlin.math.sqrt(innerRadius * innerRadius + u * (outerRadius * outerRadius - innerRadius * innerRadius))
        Vec2((centerX + r * cos(a)).toFloat(), (centerY + r * sin(a)).toFloat())
    }
}
