package afield.geometry.euclid

/**
 * Propositions from Euclid's *Elements*, constructed with compass and straightedge in the manner of
 * Oliver Byrne's coloured edition (1847).
 *
 * Each proposition takes geometry in and returns a [Construction]: its [Construction.result] (the
 * triangle, segment, line or circle the proposition asks for) and the numbered [Construction.steps]
 * that build it, each holding the coloured circles, lines and lettered points it draws. A
 * proposition that cites an earlier one carries that one's steps inside its own, prefixed with the
 * citation, just as the text says "by I.1".
 *
 * ```kotlin
 * val c = Elements.prop1(Segment2(point2(100, 200), point2(220, 200)))
 * val triangle = c.result
 * lines(c.toFLines(width = 520.0, height = 400.0), w = 520.0, h = 400.0)  // the plate, fitted to the output
 * ```
 *
 * `toFLines(upTo = 2)` draws only the first two steps, for stepping through a proof, and
 * `toFLines(style = ByrneStyle.WORKING)` shows the working of the propositions this one cites.
 *
 * Every step is built only from the intersections of circles and straight lines in `Euclid.kt`;
 * nothing is placed by a formula the proposition does not use. Where Euclid says "take any point",
 * the choice here is fixed, so the same input always draws the same figure; [Elements.solutions]
 * enumerates the others.
 */

import afield.geometry.path.FLine
import afield.geometry.path.byDashing
import afield.graphics.render.color
import afield.graphics.render.label
import afield.graphics.render.pointSize
import afield.graphics.render.pointed
import afield.graphics.render.thickness
import afield.graphics.render.filled
import afield.graphics.render.stroked
import afield.graphics.text.layoutText
import afield.graphics.text.toFLines
import afield.graphics.text.typeface
import afield.math.linalg.Vec3
import afield.math.linalg.vec
import java.awt.Font
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Byrne's palette, plus black. */
enum class Byrne(val r: Double, val g: Double, val b: Double) {
    RED(0.84, 0.17, 0.12),
    YELLOW(0.96, 0.75, 0.05),
    BLUE(0.11, 0.31, 0.57),
    BLACK(0.1, 0.1, 0.1),
}

/**
 * What a drawn element is for. A proposition says only this; [ByrneStyle] decides how each role
 * looks, so every diagram is styled in one place.
 */
enum class Role {
    /** What the proposition is handed, and lines the proof draws and keeps. */
    GIVEN,
    /** Circles and lines drawn only to find a point: the compass work. */
    CONSTRUCTION,
    /** What the proposition was asked to make. */
    RESULT,
    /** A region filled with colour: a square, a rectangle, a gnomon. Drawn under every line. */
    AREA,
}

/** One drawn element of a construction: a `shape` in `color` playing `role`, perhaps lettered with `label`. */
data class Figure(val shape: Shape2, val color: Byrne, val label: String? = null, val role: Role = Role.GIVEN)

/**
 * How one role is stroked: [width] in multiples of [ByrneStyle.thickness], dashed when [dash] is
 * above zero (dash and gap both that many thicknesses long), and faded by [alpha].
 */
data class Pen(val width: Double, val dash: Double = 0.0, val alpha: Double = 1.0)

/**
 * The look of a [Construction] diagram.
 *
 * The defaults follow Byrne's plates: given lines solid, the result heavier, compass circles thin,
 * and straight construction lines (the joins and productions a proof makes on the way) dotted.
 * Letters are italic serif. Every size here is in multiples of [thickness], so a diagram drawn at
 * any scale, or fitted to an output, looks the same.
 */
data class ByrneStyle(
    /** Base stroke width; null, the default, takes it from the size of the figure, or of the output. */
    val thickness: Double? = null,
    /** Pen for what the proposition is handed, and for the lines its proof keeps. */
    val given: Pen = Pen(1.0),
    /** Pen for what the proposition was asked to make. */
    val result: Pen = Pen(1.6),
    /** Pen for the compass circles and arcs a construction draws. */
    val circles: Pen = Pen(0.6),
    /** Pen for the straight lines a construction draws: its joins and productions. */
    val constructionLines: Pen = Pen(0.6, dash = 2.0),
    /**
     * Pen for the steps of a cited proposition; null, the default, drops them, so a citation shows
     * only what it produced. [WORKING] sets it, faded behind the main figure.
     */
    val cited: Pen? = null,
    /** Fill for coloured areas; only its [Pen.alpha] is read, since an area is filled rather than stroked. */
    val areas: Pen = Pen(0.0, alpha = 0.8),
    /** The dot drawn at a lettered point. */
    val pointSize: Double = 0.8,
    /** Whether a point's letter is drawn beside it. */
    val labels: Boolean = true,
    /** Letter height. */
    val labelSize: Double = 7.2,
    /** How far up and to the right of its point a letter sits. */
    val labelDistance: Double = 2.76,
    /**
     * The sheet behind the figure, as red, green and blue, and the colour of the halo behind each
     * letter; null for neither.
     */
    val paper: Triple<Double, Double, Double>? = Triple(0.96, 0.93, 0.85),
) {
    companion object {
        /** The default, Byrne's look. */
        val PLATE = ByrneStyle()

        /** Every step, including the working of cited propositions, faded behind the figure. */
        val WORKING = ByrneStyle(cited = Pen(0.5, alpha = 0.3))
    }
}

/** One sentence of a proof and what it draws; [cited] names the earlier proposition it comes from, if any. */
data class Step(val text: String, val figures: List<Figure>, val cited: String? = null)

/** A triangle by its three corners. */
data class Triangle2(val a: Point2, val b: Point2, val c: Point2) {
    val sides get() = listOf(Segment2(a, b), Segment2(b, c), Segment2(c, a))
    val polygon get() = Polygon2(a, b, c)
}

/** A four-sided figure by its corners in order. */
data class Quadrilateral2(val a: Point2, val b: Point2, val c: Point2, val d: Point2) {
    val sides get() = listOf(Segment2(a, b), Segment2(b, c), Segment2(c, d), Segment2(d, a))
    val polygon get() = Polygon2(a, b, c, d)
}

/** The [result] of a proposition and the [steps] that construct it. */
class Construction<out T>(val proposition: String, val enunciation: String, val result: T, val steps: List<Step>) {
    val figures get() = steps.flatMap { it.figures }

    /** Where the result is: the mean of its points (a line's foot, a circle's centre). */
    val location: Point2
        get() = pointsOf(result).let { ps -> if (ps.isEmpty()) Point2.ORIGIN else ps.reduce { a, b -> a + b } / ps.size.toDouble() }

    /**
     * The diagram as coloured FLines through step [upTo] (all of them by default), drawn in [style].
     * Lines and rays are drawn [extent] long.
     *
     * Given [width] and [height], the diagram is scaled and centred to fill that many pixels, and
     * strokes, dots and letters are sized for that output, so every plate looks alike whatever the
     * size of its geometry. Pass the same numbers to `lines(..., w = width, h = height)`.
     */
    fun toFLines(
        upTo: Int = steps.size,
        style: ByrneStyle = ByrneStyle.PLATE,
        extent: Double = 400.0,
        width: Double? = null,
        height: Double? = null,
    ): List<FLine> {
        var shown = steps.take(upTo).filter { style.cited != null || it.cited == null }
        var box = bounds(shown.flatMap { it.figures }, extent)
        var reach = extent
        val t: Double
        if (width != null && height != null) {
            t = style.thickness ?: (max(width, height) / 220)
            val m = 12 * t
            val k = min((width - 2 * m) / (box[2] - box[0]).coerceAtLeast(1e-9), (height - 2 * m) / (box[3] - box[1]).coerceAtLeast(1e-9))
            val o = Point2(width / 2 - k * (box[0] + box[2]) / 2, height / 2 - k * (box[1] + box[3]) / 2)
            shown = shown.map { st -> st.copy(figures = st.figures.map { it.copy(shape = it.shape.mapped(k, o)) }) }
            reach = extent * k
            box = doubleArrayOf(m, m, width - m, height - m) // the paper is the whole output, so every plate shares one scale
        } else {
            t = style.thickness ?: (max(box[2] - box[0], box[3] - box[1]) / 220).coerceAtLeast(1e-6)
        }
        val out = mutableListOf<FLine>()
        val letters = mutableListOf<FLine>() // on top of everything, so no stroke hides a letter
        val halos = mutableListOf<FLine>() // under every letter, over every line
        style.paper?.let { out += paperBehind(box, t, it) }
        // areas lie under every line, as Byrne's coloured fields lie under his lines
        for (step in shown) for (f in step.figures) if (f.role == Role.AREA && step.cited == null) {
            out += f.shape.toFLine(reach).also {
                it.color = vec(f.color.r, f.color.g, f.color.b, style.areas.alpha)
                it.filled = true; it.stroked = false
            }
        }
        for (step in shown) for (f in step.figures) {
            if (f.role == Role.AREA) continue
            if (step.cited != null && f.shape is Point2) continue // a cited step's points are unlettered clutter
            val pen = when {
                step.cited != null -> style.cited!!
                f.role == Role.RESULT -> style.result
                f.role == Role.GIVEN -> style.given
                f.shape is Circle2 || f.shape is Arc2 -> style.circles
                else -> style.constructionLines
            }
            val line = f.shape.toFLine(reach)
            val pieces = if (pen.dash > 0 && f.shape !is Point2) line.byDashing((pen.dash * t).toFloat(), (pen.dash * t).toFloat()) else listOf(line)
            for (piece in pieces) {
                piece.color = vec(f.color.r, f.color.g, f.color.b, pen.alpha)
                piece.thickness = pen.width * t
                if (f.shape is Point2) { piece.pointed = true; piece.pointSize = style.pointSize * t }
                f.label?.let { piece.label = it }
                out += piece
            }
            val p = f.shape as? Point2
            if (style.labels && p != null && f.label != null) letter(f.label, p, style, t).let { (h, g) -> halos += h; letters += g }
        }
        return out + halos + letters
    }

    /** The enunciation and the numbered proof, as Markdown; cited steps are included when [cited] is true. */
    fun markdown(cited: Boolean = false) = buildString {
        appendLine("**$proposition.** *$enunciation*")
        appendLine()
        steps.filter { cited || it.cited == null }.forEachIndexed { i, s -> appendLine("${i + 1}. ${s.text}") }
    }

    /**
     * The glyphs of [text] beside [at]: a halo of each in the paper colour, so a letter that lands
     * on a line still reads, and the black letter itself.
     */
    private fun letter(text: String, at: Point2, style: ByrneStyle, t: Double): Pair<List<FLine>, List<FLine>> = try {
        val size = style.labelSize * t
        val off = style.labelDistance * t
        val glyphs = layoutText(text, typeface(Font.SERIF, size, italic = true)).toFLines(
            color = vec(Byrne.BLACK.r, Byrne.BLACK.g, Byrne.BLACK.b, 1.0), thickness = 0.0, filled = true, stroked = false,
        ).map { g -> g.transform { v -> Vec3((v.x + at.x + off).toFloat(), (v.y + at.y - off).toFloat(), v.z) } }
        val halos = style.paper?.let { (r, g, b) ->
            glyphs.map { glyph ->
                glyph.transform { it }.also {
                    it.color = vec(r, g, b, 1.0); it.filled = true; it.stroked = true; it.thickness = 1.2 * t
                }
            }
        } ?: emptyList()
        halos to glyphs
    } catch (_: Exception) {
        emptyList<FLine>() to emptyList() // a headless JVM without fonts gets an unlettered diagram
    }

    /** `[x0, y0, x1, y1]` around everything in [shown]. */
    private fun bounds(shown: List<Figure>, extent: Double): DoubleArray {
        var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
        fun take(p: Point2, r: Double = 0.0) { x0 = min(x0, p.x - r); y0 = min(y0, p.y - r); x1 = max(x1, p.x + r); y1 = max(y1, p.y + r) }
        for (f in shown) when (val s = f.shape) {
            is Point2 -> take(s)
            is Segment2 -> { take(s.a); take(s.b) }
            is Circle2 -> take(s.center, s.radius)
            is Arc2 -> take(s.center, s.radius)
            is Polygon2 -> s.points.forEach { take(it) }
            is Ray2 -> { take(s.origin); take(s.at(extent / s.direction.length)) }
            is Line2 -> { val u = s.direction.normalized() * extent; take(s.point - u); take(s.point + u) }
        }
        return if (x0 > x1) doubleArrayOf(0.0, 0.0, 1.0, 1.0) else doubleArrayOf(x0, y0, x1, y1)
    }

    private fun paperBehind(box: DoubleArray, thickness: Double, rgb: Triple<Double, Double, Double>): FLine {
        val (x0, y0, x1, y1) = box.toList()
        val m = 12 * thickness
        return FLine().moveTo(x0 - m, y0 - m).lineTo(x1 + m, y0 - m).lineTo(x1 + m, y1 + m).lineTo(x0 - m, y1 + m).close().also {
            it.color = vec(rgb.first, rgb.second, rgb.third, 1.0); it.filled = true; it.stroked = false
        }
    }

    override fun toString() = buildString {
        appendLine("$proposition. $enunciation")
        steps.forEachIndexed { i, s -> appendLine("  ${i + 1}. ${s.text}") }
    }
}

/** A branch whose choices break the proposition, or whose figures do not meet; the search drops it. */
private class DeadBranch(why: String) : IllegalStateException(why)

/**
 * The choices one run of a construction made, as `[index, count]` pairs, replayed and advanced like
 * an odometer so that successive runs visit every combination.
 */
private class Chooser {
    val made = mutableListOf<IntArray>()
    var at = 0

    fun next(count: Int): Int {
        if (at < made.size) {
            val d = made[at]
            if (d[1] != count) { // this branch reached the choice with a different number of options
                d[0] = minOf(d[0], count - 1); d[1] = count
                while (made.size > at + 1) made.removeAt(made.size - 1)
            }
            at++
            return d[0]
        }
        made += intArrayOf(0, count); at++
        return 0
    }

    fun advance(): Boolean {
        at = 0
        while (made.isNotEmpty()) {
            val d = made.last()
            if (d[0] + 1 < d[1]) { d[0]++; return true }
            made.removeAt(made.size - 1)
        }
        return false
    }
}

private val chooser = ThreadLocal<Chooser?>()

/** Every distinct valid outcome of [block], its default first, at most [limit] of them. */
private fun <T> enumerate(limit: Int, block: () -> Construction<T>): List<Construction<T>> {
    val saved = chooser.get()
    val c = Chooser()
    chooser.set(c)
    try {
        val out = mutableListOf<Construction<T>>()
        var runs = 0
        do {
            c.at = 0
            try {
                val found = block()
                if (out.none { sameResult(it.result, found.result) }) out += found
            } catch (_: DeadBranch) {
            }
        } while (++runs < 20_000 && out.size < limit && c.advance())
        return out
    } finally {
        chooser.set(saved)
    }
}

/**
 * Writes a proposition of your own, the way every proposition in [Elements] is written: [build]
 * draws it step by step with [Proof.step] and returns its result.
 *
 * ```kotlin
 * fun midpoint(ab: Segment2) = construction("Mid", "To bisect a line.") {
 *     val d = by { Elements.prop10(ab) }        // cite an earlier proposition
 *     step("D halves AB.", d.named("D"))
 *     d
 * }
 * ```
 */
fun <T> construction(proposition: String, enunciation: String, build: Proof.() -> T): Construction<T> {
    val proof = Proof()
    val result = proof.build()
    return Construction(proposition, enunciation, result, proof.steps)
}

/**
 * What a proposition is written with inside [construction]: numbered steps, the figures they draw,
 * the choices it makes ([pick], [pickSide]), the claim it checks ([check]) and the propositions it
 * cites ([by]), plus the small measures proofs keep needing.
 */
class Proof internal constructor() {
    internal val steps = mutableListOf<Step>()

    /** A figure: this shape in [color], playing [role]. */
    fun Shape2.drawn(color: Byrne, role: Role = Role.GIVEN) = Figure(this, color, role = role)

    /** A lettered point. */
    fun Point2.named(label: String) = Figure(this, Byrne.BLACK, label)

    /** Where [a] and [b] cross; a branch where they do not is dropped (or, called plainly, fails naming [what]). */
    fun meet(a: Shape2, b: Shape2, what: String = "construction"): List<Point2> = afield.geometry.euclid.meet(a, b, what)

    /** The distance from this point to [o]. */
    fun Point2.dist(o: Point2) = (o - this).length

    /** Equal to a relative tolerance, for [check]. */
    fun eq(x: Double, y: Double) = afield.geometry.euclid.eq(x, y)

    /** The angle at [v] between the arms through [a] and [b], in [0, π]. */
    fun angleAt(v: Point2, a: Point2, b: Point2) = afield.geometry.euclid.angleAt(v, a, b)

    /** The mean of [ps]. */
    fun centroid(vararg ps: Point2) = afield.geometry.euclid.centroid(*ps)

    /** True when [q]'s four sides are equal and its diagonal is one side times √2, for [check]. */
    fun isSquare(q: Quadrilateral2) = afield.geometry.euclid.isSquare(q)

    /** True when [p] has [n] equal, non-zero sides and every interior angle of an [n]-gon, for [check]. */
    fun isRegular(p: Polygon2, n: Int) = afield.geometry.euclid.isRegular(p, n)

    /** Of [points], the one farthest from [from]: where a line through [from] leaves a circle. */
    fun far(points: List<Point2>, from: Point2) = afield.geometry.euclid.far(points, from)

    /** How far round the circle about [o], counter-clockwise from [p], the point [q] is, in [0, 2π). */
    fun offset(o: Point2, p: Point2, q: Point2) = afield.geometry.euclid.offset(o, p, q)

    /** The polygon through [points] in order round [o], starting at angle [at]. */
    fun aroundCentre(o: Point2, at: Double, points: List<Point2>) = afield.geometry.euclid.aroundCentre(o, at, points)

    /** One numbered sentence of the proof, drawing [figures]. */
    fun step(text: String, vararg figures: Figure) { steps += Step(text, figures.toList()) }

    /**
     * One of [options]. A plain call takes [preferred] (or the first); inside [Elements.solutions]
     * every option is tried in turn, [preferred] first.
     */
    fun <T> pick(options: List<T>, preferred: T? = null): T {
        if (options.isEmpty()) throw DeadBranch("nothing to choose from")
        val first = preferred ?: options.first()
        val ordered = listOf(first) + options.filter { it != first }
        val c = chooser.get() ?: return ordered[0]
        return ordered[c.next(ordered.size)]
    }

    /** Of [points], the one on the [side] (+1 left, -1 right) of [line] by default; the others when searching. */
    fun pickSide(line: Line2, points: List<Point2>, side: Int) = pick(points, onSide(line, points, side))

    /** Drops this branch unless [ok]: the proposition's claim, checked on the geometry it made. */
    fun check(ok: Boolean, what: String) { if (!ok) throw DeadBranch(what) }

    /**
     * Cites an earlier proposition: its steps, unlettered, under its name. Returns its result. Inside
     * [Elements.solutions], each distinct valid outcome of the cited proposition is one choice here.
     */
    fun <T> by(cited: () -> Construction<T>): T {
        val c = if (chooser.get() == null) cited() else pick(enumerate(64, cited))
        c.steps.forEach { s -> steps += Step("By ${c.proposition}: ${s.text}", s.figures.map { it.copy(label = null) }, c.proposition) }
        return c.result
    }
}

private const val SAME = 1e-7

/** Whether two results put their geometry in the same place (to a relative tolerance). */
private fun sameResult(a: Any?, b: Any?): Boolean {
    val x = numbers(a); val y = numbers(b)
    if (x == null || y == null) return a == b
    if (x.size != y.size) return false
    val scale = 1 + (x + y).maxOf { abs(it) }
    return x.indices.all { abs(x[it] - y[it]) <= SAME * scale }
}

/** A result flattened to numbers that say where it is; lines and rays by a canonical form. */
private fun numbers(r: Any?): List<Double>? = when (r) {
    is Point2 -> listOf(r.x, r.y)
    is Segment2 -> listOf(r.a.x, r.a.y, r.b.x, r.b.y)
    is Ray2 -> r.direction.normalized().let { listOf(r.origin.x, r.origin.y, it.x, it.y) }
    is Line2 -> {
        val u = r.direction.normalized().let { if (it.x < 0 || (it.x == 0.0 && it.y < 0)) -it else it }
        val foot = r.project(Point2.ORIGIN)
        listOf(foot.x, foot.y, u.x, u.y)
    }
    is Circle2 -> listOf(r.center.x, r.center.y, r.radius)
    is Arc2 -> listOf(r.center.x, r.center.y, r.radius, r.startPoint.x, r.startPoint.y, r.endPoint.x, r.endPoint.y)
    is Polygon2 -> r.points.flatMap { listOf(it.x, it.y) }
    is Triangle2 -> listOf(r.a, r.b, r.c).flatMap { listOf(it.x, it.y) }
    is Quadrilateral2 -> listOf(r.a, r.b, r.c, r.d).flatMap { listOf(it.x, it.y) }
    is Pair<*, *> -> numbers(r.first)?.let { f -> numbers(r.second)?.let { f + it } }
    is Triple<*, *, *> -> listOf(r.first, r.second, r.third).map { numbers(it) ?: return null }.flatten()
    is List<*> -> r.map { numbers(it) ?: return null }.flatten()
    else -> null
}

/** The points a result is made of, for asking where it ended up. */
private fun pointsOf(r: Any?): List<Point2> = when (r) {
    is Line2 -> listOf(r.project(Point2.ORIGIN))
    is Ray2 -> listOf(r.origin, r.origin + r.direction) // a construction's ray runs to the point that made it
    is Circle2 -> listOf(r.center)
    is Arc2 -> listOf(r.midPoint)
    is Pair<*, *> -> pointsOf(r.first) + pointsOf(r.second)
    is Triple<*, *, *> -> pointsOf(r.first) + pointsOf(r.second) + pointsOf(r.third)
    is List<*> -> r.flatMap { pointsOf(it) }
    else -> numbers(r)?.chunked(2)?.filter { it.size == 2 }?.map { Point2(it[0], it[1]) } ?: emptyList()
}

private fun Shape2.drawn(color: Byrne, role: Role = Role.GIVEN) = Figure(this, color, role = role)
private fun Shape2.mapped(k: Double, o: Point2): Shape2 = when (this) {
    is Point2 -> this * k + o
    is Line2 -> Line2(point * k + o, direction)
    is Ray2 -> Ray2(origin * k + o, direction)
    is Segment2 -> Segment2(a * k + o, b * k + o)
    is Circle2 -> Circle2(center * k + o, radius * k)
    is Arc2 -> Arc2(center * k + o, radius * k, start, sweep)
    is Polygon2 -> Polygon2(points.map { it * k + o })
}

private fun Point2.named(label: String) = Figure(this, Byrne.BLACK, label)

private fun meet(a: Shape2, b: Shape2, what: String): List<Point2> =
    a.intersect(b).points.ifEmpty { throw DeadBranch("$what: the figures do not meet") }

/** Of [points], one on the [side] (+1 left, -1 right) of [line]. */
private fun onSide(line: Line2, points: List<Point2>, side: Int) = points.maxBy { line.signedDistance(it) * side }

private fun Point2.dist(o: Point2) = (o - this).length

private fun eq(x: Double, y: Double) = abs(x - y) <= 1e-6 * (1 + abs(x) + abs(y))

/** The angle at [v] between the arms through [a] and [b], in [0, π]. */
private fun angleAt(v: Point2, a: Point2, b: Point2) = acos(((a - v).normalized() dot (b - v).normalized()).coerceIn(-1.0, 1.0))

private fun centroid(vararg ps: Point2) = ps.reduce { x, y -> x + y } / ps.size.toDouble()

private fun isSquare(q: Quadrilateral2) = q.sides.all { eq(it.length, q.sides[0].length) } && eq(q.a.dist(q.c), q.sides[0].length * sqrt(2.0))

private fun isRegular(p: Polygon2, n: Int) = p.points.size == n && p.edges.all { eq(it.length, p.edges[0].length) && it.length > 0 } &&
    p.points.indices.all { eq(angleAt(p.points[it], p.points[(it + n - 1) % n], p.points[(it + 1) % n]), PI * (n - 2) / n) }

object Elements {

    /**
     * Every distinct way [proposition] can come out, the usual one first.
     *
     * A construction chooses as it goes: which of two crossing points of circles to take, which
     * side of a line to build on, and the choices of each proposition it cites. Called plainly, a
     * proposition takes Euclid's choice (or the one its `side` asks for); inside `solutions`, every
     * combination is tried, the ones whose geometry breaks the proposition's claim are dropped,
     * and those that end in the same place are kept once. Pick one by where its result lies:
     *
     * ```kotlin
     * val all = Elements.solutions { prop2(point2(0, 0), Segment2(point2(90, 40), point2(150, 100))) }
     * val leftmost = all.minBy { it.location.x }
     * val nearMouse = all.nearest(mouse)
     * ```
     *
     * At most [limit] solutions are returned.
     */
    fun <T> solutions(limit: Int = 64, proposition: Elements.() -> Construction<T>): List<Construction<T>> =
        enumerate(limit) { proposition() }

    /**
     * I.1. On a given finite straight line to construct an equilateral triangle. The apex is on the
     * left of [ab] going from `a` to `b` when [side] is +1, on the right when -1.
     */
    fun prop1(ab: Segment2, side: Int = 1): Construction<Triangle2> {
        val (a, b) = ab
        val s = Proof()
        val around = Circle2(a, a.dist(b))
        val around2 = Circle2(b, a.dist(b))
        s.step("Let AB be the given straight line.", ab.drawn(Byrne.BLACK), a.named("A"), b.named("B"))
        s.step("With centre A and radius AB describe the circle BCD.", around.drawn(Byrne.RED, Role.CONSTRUCTION))
        s.step("With centre B and radius BA describe the circle ACE.", around2.drawn(Byrne.BLUE, Role.CONSTRUCTION))
        val c = s.pickSide(ab.line, meet(around, around2, "I.1"), side)
        s.step(
            "From C, where the circles cut one another, draw CA and CB. Then CA = AB = CB, and ABC is equilateral.",
            c.named("C"), Segment2(c, a).drawn(Byrne.YELLOW, Role.RESULT), Segment2(c, b).drawn(Byrne.RED, Role.RESULT),
        )
        return Construction("I.1", "On a given finite straight line to construct an equilateral triangle.", Triangle2(a, b, c), s.steps)
    }

    /** I.2. To place at a given point [a] a straight line equal to a given straight line [bc]. */
    fun prop2(a: Point2, bc: Segment2): Construction<Segment2> {
        val (b, c) = bc
        val s = Proof()
        val r = b.dist(c)
        s.step("Let A be the given point and BC the given straight line.", a.named("A"), bc.drawn(Byrne.BLUE), b.named("B"), c.named("C"))
        if (a.dist(b) <= Euclid.epsilon * (1 + r)) {
            s.step("A is at B already, so BC is the line required.", bc.drawn(Byrne.YELLOW, Role.RESULT))
            return Construction("I.2", ENUNCIATION_2, bc, s.steps)
        }
        s.step("Join AB.", Segment2(a, b).drawn(Byrne.BLACK, Role.CONSTRUCTION))
        val d = s.by { prop1(Segment2(a, b)) }.c
        s.step("On AB construct the equilateral triangle DAB.", Segment2(d, a).drawn(Byrne.BLACK, Role.CONSTRUCTION), Segment2(d, b).drawn(Byrne.BLACK, Role.CONSTRUCTION), d.named("D"))
        val circleB = Circle2(b, r)
        val gs = meet(Line2.through(d, b), circleB, "I.2")
        val g = s.pick(gs, gs.maxBy { (it - d) dot (b - d) }) // Euclid's G is beyond B; the other works too
        s.step(
            "With centre B and radius BC describe the circle CGH, and produce DB to meet it at G.",
            circleB.drawn(Byrne.BLUE, Role.CONSTRUCTION), Segment2(d, g).drawn(Byrne.BLACK, Role.CONSTRUCTION), g.named("G"),
        )
        val circleD = Circle2(d, d.dist(g))
        val ls = meet(Line2.through(d, a), circleD, "I.2")
        val l = s.pick(ls, ls.maxBy { (it - d) dot (a - d) })
        s.step(
            "With centre D and radius DG describe the circle GKL, and produce DA to meet it at L.",
            circleD.drawn(Byrne.RED, Role.CONSTRUCTION), Segment2(d, l).drawn(Byrne.BLACK, Role.CONSTRUCTION), l.named("L"),
        )
        val al = Segment2(a, l)
        s.step("DL = DG and DA = DB, so the remainder AL = BG = BC.", al.drawn(Byrne.YELLOW, Role.RESULT))
        s.check(eq(al.length, r), "I.2: AL is not equal to BC")
        return Construction("I.2", ENUNCIATION_2, al, s.steps)
    }

    private const val ENUNCIATION_2 = "To place at a given point a straight line equal to a given straight line."

    /** I.3. From the greater of two given straight lines to cut off a part equal to the less. */
    fun prop3(greater: Segment2, less: Segment2): Construction<Segment2> {
        require(greater.length >= less.length) { "I.3: the first line must be the greater" }
        val (a, b) = greater
        val s = Proof()
        s.step("Let AB be the greater and C the less.", greater.drawn(Byrne.BLACK), a.named("A"), b.named("B"), less.drawn(Byrne.BLUE))
        val ad = s.by { prop2(a, less) }
        s.step("At A place AD equal to C.", ad.drawn(Byrne.BLUE), ad.b.named("D"))
        val circle = Circle2(a, ad.length)
        val e = s.pick(meet(greater, circle, "I.3"))
        val ae = Segment2(a, e)
        s.step("With centre A and radius AD describe the circle DEF, cutting AB at E. Then AE = AD = C.", circle.drawn(Byrne.RED, Role.CONSTRUCTION), e.named("E"), ae.drawn(Byrne.YELLOW, Role.RESULT))
        s.check(eq(ae.length, less.length), "I.3: AE is not equal to C")
        return Construction("I.3", "From the greater of two given straight lines to cut off a part equal to the less.", ae, s.steps)
    }

    /** I.9. To bisect a given rectilineal angle: the angle at [vertex] between the arms through [b] and [c]. */
    fun prop9(vertex: Point2, b: Point2, c: Point2): Construction<Ray2> {
        val a = vertex
        val s = Proof()
        s.step("Let BAC be the given angle.", Segment2(a, b).drawn(Byrne.BLACK), Segment2(a, c).drawn(Byrne.BLACK), a.named("A"), b.named("B"), c.named("C"))
        val r = minOf(a.dist(b), a.dist(c)) / 2
        val d = s.pick(meet(Ray2(a, b - a), Circle2(a, r), "I.9"))
        s.step("Take any point D on AB.", d.named("D"))
        val circle = Circle2(a, r)
        val e = s.pick(meet(Ray2(a, c - a), circle, "I.9"))
        s.step("From AC cut off AE equal to AD (I.3).", circle.drawn(Byrne.BLUE, Role.CONSTRUCTION), e.named("E"))
        val de = Segment2(d, e)
        s.step("Join DE.", de.drawn(Byrne.BLACK, Role.CONSTRUCTION))
        val f = s.by { prop1(de, -de.line.side(a).let { if (it == 0) 1 else it }) }.c
        val af = Ray2(a, f - a)
        s.step("On DE, on the side away from A, construct the equilateral triangle DEF, and join AF. AF bisects the angle BAC.", f.named("F"), Segment2(a, f).drawn(Byrne.YELLOW, Role.RESULT))
        s.check(eq(2 * angleAt(a, b, f), angleAt(a, b, c)) && eq(angleAt(a, b, f), angleAt(a, f, c)), "I.9: AF does not halve the angle")
        return Construction("I.9", "To bisect a given rectilineal angle.", af, s.steps)
    }

    /** I.10. To bisect a given finite straight line. */
    fun prop10(ab: Segment2): Construction<Point2> {
        val (a, b) = ab
        val s = Proof()
        s.step("Let AB be the given straight line.", ab.drawn(Byrne.BLACK), a.named("A"), b.named("B"))
        val c = s.by { prop1(ab) }.c
        s.step("On AB construct the equilateral triangle ABC.", Segment2(c, a).drawn(Byrne.BLACK, Role.CONSTRUCTION), Segment2(c, b).drawn(Byrne.BLACK, Role.CONSTRUCTION), c.named("C"))
        val bisector = s.by { prop9(c, a, b) }
        val d = s.pick(meet(bisector, ab, "I.10"))
        s.step("Bisect the angle ACB by CD, meeting AB at D. Then AD = DB.", Segment2(c, d).drawn(Byrne.RED), d.named("D"), Segment2(a, d).drawn(Byrne.YELLOW, Role.RESULT), Segment2(d, b).drawn(Byrne.BLUE, Role.RESULT))
        s.check(eq(a.dist(d), d.dist(b)), "I.10: D is not the midpoint")
        return Construction("I.10", "To bisect a given finite straight line.", d, s.steps)
    }

    /**
     * I.11. To draw a straight line at right angles to a given straight line [ab] from a given point
     * [c] on it. The perpendicular goes to the left of `a`→`b` when [side] is +1.
     */
    fun prop11(ab: Segment2, c: Point2, side: Int = 1): Construction<Ray2> {
        val s = Proof()
        val line = ab.line
        s.step("Let AB be the given straight line and C the given point on it.", ab.drawn(Byrne.BLACK), ab.a.named("A"), ab.b.named("B"), c.named("C"))
        val circle = Circle2(c, ab.length / 4)
        val (d, e) = meet(line, circle, "I.11").sortedBy { line.direction dot it }
        s.step("Take any point D on AC, and make CE equal to CD (I.3).", circle.drawn(Byrne.BLUE, Role.CONSTRUCTION), d.named("D"), e.named("E"))
        val f = s.by { prop1(Segment2(d, e), side) }.c
        val cf = Ray2(c, f - c)
        s.step("On DE construct the equilateral triangle FDE, and join FC. FC is at right angles to AB.", f.named("F"), Segment2(c, f).drawn(Byrne.YELLOW, Role.RESULT))
        s.check(eq(cf.direction.normalized() dot line.direction.normalized(), 0.0), "I.11: CF is not at right angles")
        return Construction("I.11", "To draw a straight line at right angles to a given straight line from a given point on it.", cf, s.steps)
    }

    /** I.12. To draw a perpendicular to a given infinite straight line [ab] from a given point [c] not on it. */
    fun prop12(ab: Line2, c: Point2): Construction<Segment2> {
        val s = Proof()
        require(ab.side(c) != 0) { "I.12: the point is on the line" }
        s.step("Let AB be the given straight line and C the given point not on it.", ab.drawn(Byrne.BLACK), c.named("C"))
        val d = ab.point + (ab.point - c) * 0.3
        s.step("Take any point D on the other side of AB.", d.named("D"))
        val circle = Circle2(c, c.dist(d))
        val (g, e) = meet(ab, circle, "I.12").sortedBy { ab.direction dot it }
        s.step("With centre C and radius CD describe the circle EFG, cutting AB at G and E.", circle.drawn(Byrne.BLUE, Role.CONSTRUCTION), g.named("G"), e.named("E"))
        val h = s.by { prop10(Segment2(g, e)) }
        val ch = Segment2(c, h)
        s.step("Bisect GE at H, and join CH. CH is perpendicular to AB.", h.named("H"), ch.drawn(Byrne.YELLOW, Role.RESULT))
        s.check(eq((ch.b - ch.a).normalized() dot ab.direction.normalized(), 0.0), "I.12: CH is not perpendicular")
        return Construction("I.12", "To draw a straight line perpendicular to a given infinite straight line from a given point not on it.", ch, s.steps)
    }

    /**
     * I.23. On a given straight line [along], at the point on it, to construct an angle equal to the
     * angle at [vertex] between the arms through [d] and [e]. The new arm is on the left of
     * [along] when [side] is +1.
     *
     * Euclid makes the copy with the triangle of I.22; this draws the same triangle with two circles.
     */
    fun prop23(along: Ray2, vertex: Point2, d: Point2, e: Point2, side: Int = 1): Construction<Ray2> {
        val a = along.origin
        val s = Proof()
        s.step(
            "Let AB be the given line, A the point on it, and DCE the given angle.",
            along.drawn(Byrne.BLACK), a.named("A"), Segment2(vertex, d).drawn(Byrne.BLACK), Segment2(vertex, e).drawn(Byrne.BLACK), vertex.named("C"),
        )
        val r = minOf(vertex.dist(d), vertex.dist(e))
        val gauge = Circle2(vertex, r)
        val d1 = s.pick(meet(Ray2(vertex, d - vertex), gauge, "I.23"))
        val e1 = s.pick(meet(Ray2(vertex, e - vertex), gauge, "I.23"))
        s.step("Cut CD and CE at D and E equally far from C, and join DE.", gauge.drawn(Byrne.BLUE, Role.CONSTRUCTION), d1.named("D"), e1.named("E"), Segment2(d1, e1).drawn(Byrne.RED))
        val copy = Circle2(a, r)
        val f = s.pick(meet(along, copy, "I.23"))
        s.step("On AB make AF equal to CD.", copy.drawn(Byrne.BLUE, Role.CONSTRUCTION), f.named("F"))
        val chord = Circle2(f, d1.dist(e1))
        val g = s.pickSide(along.line, meet(copy, chord, "I.23"), side)
        val ag = Ray2(a, g - a)
        s.step(
            "With centre F and radius DE cut the circle at G, and join AG. The triangles AFG and CDE have their sides equal, so the angle FAG equals DCE.",
            chord.drawn(Byrne.RED, Role.CONSTRUCTION), g.named("G"), Segment2(f, g).drawn(Byrne.RED), Segment2(a, g).drawn(Byrne.YELLOW, Role.RESULT),
        )
        s.check(eq(angleAt(a, a + along.direction, g), angleAt(vertex, d, e)), "I.23: the angles differ")
        return Construction("I.23", "On a given straight line and at a point on it to construct a rectilineal angle equal to a given rectilineal angle.", ag, s.steps)
    }

    /** I.31. Through a given point [a] to draw a straight line parallel to a given straight line [bc]. */
    fun prop31(a: Point2, bc: Segment2): Construction<Line2> {
        val s = Proof()
        require(bc.line.side(a) != 0) { "I.31: the point is on the line" }
        s.step("Let A be the given point and BC the given straight line.", a.named("A"), bc.drawn(Byrne.BLACK), bc.a.named("B"), bc.b.named("C"))
        val d = bc.midpoint
        s.step("Take any point D on BC, and join AD.", d.named("D"), Segment2(a, d).drawn(Byrne.BLACK, Role.CONSTRUCTION))
        val ad = Line2.through(a, d)
        val arm = s.by { prop23(Ray2(a, d - a), d, a, bc.b, -ad.side(bc.b)) }
        val e = arm.at(1.0)
        val parallel = Line2(a, arm.direction)
        s.step(
            "At A on DA make the angle DAE equal to the alternate angle ADC, and produce EA to F. EF is parallel to BC.",
            e.named("E"), parallel.drawn(Byrne.YELLOW, Role.RESULT),
        )
        s.check(eq(parallel.direction.normalized() cross bc.direction.normalized(), 0.0), "I.31: EF is not parallel")
        return Construction("I.31", "Through a given point to draw a straight line parallel to a given straight line.", parallel, s.steps)
    }

    /** I.46. On a given straight line to describe a square, on the left of `a`→`b` when [side] is +1. */
    fun prop46(ab: Segment2, side: Int = 1): Construction<Quadrilateral2> {
        val (a, b) = ab
        val s = Proof()
        s.step("Let AB be the given straight line.", ab.drawn(Byrne.BLACK), a.named("A"), b.named("B"))
        val up = s.by { prop11(ab, a, side) }
        val circle = Circle2(a, ab.length)
        val d = s.pick(meet(up, circle, "I.46"))
        s.step("Draw AC at right angles to AB from A, and cut off AD equal to AB.", up.drawn(Byrne.BLACK, Role.CONSTRUCTION), circle.drawn(Byrne.BLUE, Role.CONSTRUCTION), d.named("D"), Segment2(a, d).drawn(Byrne.RED))
        val de = s.by { prop31(d, ab) }
        val be = s.by { prop31(b, Segment2(a, d)) }
        val e = s.pick(meet(de, be, "I.46"))
        val square = Quadrilateral2(a, b, e, d)
        s.step(
            "Through D draw DE parallel to AB, and through B draw BE parallel to AD. ADEB is a square.",
            e.named("E"), Segment2(d, e).drawn(Byrne.BLUE, Role.RESULT), Segment2(b, e).drawn(Byrne.YELLOW, Role.RESULT),
        )
        s.check(isSquare(square), "I.46: ADEB is not a square")
        return Construction("I.46", "On a given straight line to describe a square.", square, s.steps)
    }

    /** III.1. To find the centre of a given circle (drawn, so its centre is not consulted). */
    fun book3prop1(circle: Circle2): Construction<Point2> {
        val s = Proof()
        val a = circle.at(0.4); val b = circle.at(2.3)
        s.step("Let ABC be the given circle, and draw through it any straight line AB.", circle.drawn(Byrne.BLACK), Segment2(a, b).drawn(Byrne.BLUE), a.named("A"), b.named("B"))
        val d = s.by { prop10(Segment2(a, b)) }
        val perp = s.by { prop11(Segment2(a, b), d) }
        val (c, e) = meet(Line2(d, perp.direction), circle, "III.1")
        s.step("Bisect AB at D, and from D draw DC at right angles to AB, meeting the circle at C and E.", d.named("D"), Segment2(c, e).drawn(Byrne.RED), c.named("C"), e.named("E"))
        val f = s.by { prop10(Segment2(c, e)) }
        s.step("Bisect CE at F. F is the centre of the circle.", f.named("F"))
        s.check(eq(f.dist(a), f.dist(b)) && eq(f.dist(a), f.dist(c)), "III.1: F is not the centre")
        return Construction("III.1", "To find the centre of a given circle.", f, s.steps)
    }

    /** IV.4. In a given triangle to inscribe a circle. */
    fun book4prop4(t: Triangle2): Construction<Circle2> {
        val s = Proof()
        s.step("Let ABC be the given triangle.", *t.sides.map { it.drawn(Byrne.BLACK) }.toTypedArray(), t.a.named("A"), t.b.named("B"), t.c.named("C"))
        val atB = s.by { prop9(t.b, t.a, t.c) }
        val atC = s.by { prop9(t.c, t.a, t.b) }
        val d = s.pick(meet(atB, atC, "IV.4"))
        s.step("Bisect the angles ABC and ACB by BD and CD, meeting at D.", Segment2(t.b, d).drawn(Byrne.RED), Segment2(t.c, d).drawn(Byrne.BLUE), d.named("D"))
        val de = s.by { prop12(Line2.through(t.b, t.c), d) }
        val circle = Circle2(d, de.length)
        s.step("From D draw DE perpendicular to BC. The circle with centre D and radius DE touches all three sides.", de.b.named("E"), circle.drawn(Byrne.YELLOW, Role.RESULT))
        s.check(t.sides.all { eq(it.distanceTo(circle.center), circle.radius) }, "IV.4: the circle does not touch every side")
        return Construction("IV.4", "In a given triangle to inscribe a circle.", circle, s.steps)
    }

    /** IV.5. About a given triangle to circumscribe a circle. */
    fun book4prop5(t: Triangle2): Construction<Circle2> {
        val s = Proof()
        s.step("Let ABC be the given triangle.", *t.sides.map { it.drawn(Byrne.BLACK) }.toTypedArray(), t.a.named("A"), t.b.named("B"), t.c.named("C"))
        val d = s.by { prop10(Segment2(t.a, t.b)) }
        val e = s.by { prop10(Segment2(t.a, t.c)) }
        val fromD = s.by { prop11(Segment2(t.a, t.b), d) }
        val fromE = s.by { prop11(Segment2(t.a, t.c), e) }
        val f = s.pick(meet(Line2(d, fromD.direction), Line2(e, fromE.direction), "IV.5"))
        val circle = Circle2(f, f.dist(t.a))
        s.step(
            "Bisect AB and AC at D and E, and from them draw DF and EF at right angles, meeting at F. The circle with centre F and radius FA passes through A, B and C.",
            d.named("D"), e.named("E"), Segment2(d, f).drawn(Byrne.RED), Segment2(e, f).drawn(Byrne.BLUE), f.named("F"), circle.drawn(Byrne.YELLOW, Role.RESULT),
        )
        s.check(listOf(t.a, t.b, t.c).all { eq(it.dist(circle.center), circle.radius) }, "IV.5: the circle misses a corner")
        return Construction("IV.5", "About a given triangle to circumscribe a circle.", circle, s.steps)
    }

    /**
     * VI.13. To two given straight lines to find a mean proportional: [ac] is the two lines laid end
     * to end, meeting at [b]. The result BD has BD² = AB · BC.
     */
    fun book6prop13(ac: Segment2, b: Point2): Construction<Segment2> {
        val s = Proof()
        s.step("Let AB and BC be the two given straight lines, placed in a straight line.", Segment2(ac.a, b).drawn(Byrne.RED), Segment2(b, ac.b).drawn(Byrne.BLUE), ac.a.named("A"), b.named("B"), ac.b.named("C"))
        val centre = s.by { prop10(ac) }
        val semicircle = Arc2(centre, centre.dist(ac.a), (ac.b - centre).angle, Math.PI)
        s.step("On AC describe the semicircle ADC.", semicircle.drawn(Byrne.BLACK))
        val up = s.by { prop11(ac, b) }
        val d = s.pick(meet(up, semicircle, "VI.13"))
        val bd = Segment2(b, d)
        s.step("From B draw BD at right angles to AC, meeting the semicircle at D. BD is a mean proportional between AB and BC.", d.named("D"), bd.drawn(Byrne.YELLOW, Role.RESULT))
        s.check(eq(bd.length * bd.length, ac.a.dist(b) * b.dist(ac.b)), "VI.13: BD is not the mean proportional")
        return Construction("VI.13", "To two given straight lines to find a mean proportional.", bd, s.steps)
    }

    // ---- Book I, the last proposition ---------------------------------------------------------

    /**
     * I.47. In right-angled triangles the square on the side opposite the right angle equals the
     * squares on the sides containing it. The right angle of [t] is at `t.a`. The result is the
     * three squares: on BC, on BA and on AC.
     */
    fun prop47(t: Triangle2): Construction<Triple<Quadrilateral2, Quadrilateral2, Quadrilateral2>> {
        val (a, b, c) = t
        val s = Proof()
        s.step("Let ABC be a right-angled triangle with the right angle at A.", *t.sides.map { it.drawn(Byrne.BLACK) }.toTypedArray(), a.named("A"), b.named("B"), c.named("C"))
        val onBC = s.by { prop46(Segment2(b, c), -Line2.through(b, c).side(a)) }
        val onBA = s.by { prop46(Segment2(b, a), -Line2.through(b, a).side(c)) }
        val onAC = s.by { prop46(Segment2(a, c), -Line2.through(a, c).side(b)) }
        val d = onBC.d; val e = onBC.c; val f = onBA.d; val g = onBA.c; val h = onAC.d; val k = onAC.c
        s.step(
            "On BC, BA and AC describe the squares BDEC, BAGF and ACKH (I.46).",
            onBA.polygon.drawn(Byrne.RED, Role.AREA), onAC.polygon.drawn(Byrne.BLUE, Role.AREA),
            *(onBC.sides + onBA.sides + onAC.sides).map { it.drawn(Byrne.BLACK) }.toTypedArray(),
            d.named("D"), e.named("E"), f.named("F"), g.named("G"), h.named("H"), k.named("K"),
        )
        val al = s.by { prop31(a, Segment2(b, d)) }
        val l = s.pick(meet(al, Segment2(d, e), "I.47"))
        val m = s.pick(meet(al, Segment2(b, c), "I.47"))
        s.step("Through A draw AL parallel to BD, cutting BC at M and DE at L.", Segment2(a, l).drawn(Byrne.BLACK), l.named("L"), m.named("M"))
        s.step(
            "Join AD and FC. The triangles ABD and FBC are equal, and each is half of the rectangle BL and of the square on BA, so these are equal; in the same way CL equals the square on AC.",
            Segment2(a, d).drawn(Byrne.BLACK, Role.CONSTRUCTION), Segment2(f, c).drawn(Byrne.BLACK, Role.CONSTRUCTION),
            Polygon2(b, d, l, m).drawn(Byrne.RED, Role.AREA), Polygon2(m, l, e, c).drawn(Byrne.BLUE, Role.AREA),
        )
        s.step("So the whole square on BC equals the squares on BA and AC.", *onBC.sides.map { it.drawn(Byrne.YELLOW, Role.RESULT) }.toTypedArray())
        s.check(listOf(onBC, onBA, onAC).none { centroid(a, b, c) in it.polygon }, "I.47: a square lies over the triangle")
        return Construction(
            "I.47", "In right-angled triangles the square on the side opposite the right angle equals the squares on the sides containing the right angle.",
            Triple(onBC, onBA, onAC), s.steps,
        )
    }

    // ---- Book II: areas ------------------------------------------------------------------------

    /**
     * II.5. If a straight line [ab] be cut into equal and unequal segments, the rectangle on the
     * unequal segments with the square on the line between the points of section equals the square
     * on the half. The result is the gnomon, the L-shaped region equal to the rectangle.
     */
    fun book2prop5(ab: Segment2, side: Int = 1): Construction<Polygon2> {
        val (a, b) = ab
        val s = Proof()
        s.step("Let AB be the given straight line.", ab.drawn(Byrne.BLACK), a.named("A"), b.named("B"))
        val c = s.by { prop10(ab) }
        val d = c.lerp(b, 0.4)
        s.step("Bisect AB at C, and take any point D between C and B.", c.named("C"), d.named("D"))
        val sq = s.by { prop46(Segment2(c, b), side) }
        val f = sq.c; val e = sq.d
        s.step("On CB describe the square CEFB, and join BE.", *sq.sides.map { it.drawn(Byrne.BLACK) }.toTypedArray(), Segment2(b, e).drawn(Byrne.BLACK, Role.CONSTRUCTION), e.named("E"), f.named("F"))
        val dg = s.by { prop31(d, Segment2(c, e)) }
        val g = s.pick(meet(dg, Segment2(e, f), "II.5"))
        val h = s.pick(meet(dg, Segment2(b, e), "II.5"))
        s.step("Through D draw DG parallel to CE, cutting BE at H.", Segment2(d, g).drawn(Byrne.BLACK), g.named("G"), h.named("H"))
        val km = s.by { prop31(h, ab) }
        val l = s.pick(meet(km, Line2.through(c, e), "II.5"))
        val m = s.pick(meet(km, Line2.through(b, f), "II.5"))
        val k = s.pick(meet(s.by { prop31(a, Segment2(c, l)) }, km, "II.5"))
        s.step(
            "Through H draw KLM parallel to AB, and through A draw AK parallel to CL.",
            Segment2(k, m).drawn(Byrne.BLACK), Segment2(a, k).drawn(Byrne.BLACK), k.named("K"), l.named("L"), m.named("M"),
        )
        val gnomon = Polygon2(c, b, f, g, h, l)
        s.step(
            "AL equals DF, so the rectangle AH, which is AD·DB, equals the gnomon CBFGHL. Add to each the square LG on CD: the rectangle AD·DB with the square on CD equals the square on CB.",
            Polygon2(a, c, l, k).drawn(Byrne.RED, Role.AREA), Polygon2(d, b, f, g).drawn(Byrne.RED, Role.AREA),
            Polygon2(c, d, h, l).drawn(Byrne.BLUE, Role.AREA), Polygon2(l, h, g, e).drawn(Byrne.YELLOW, Role.AREA),
        )
        s.check(eq(gnomon.area, a.dist(d) * d.dist(b)), "II.5: the gnomon is not AD·DB")
        return Construction(
            "II.5", "If a straight line be cut into equal and unequal segments, the rectangle contained by the unequal segments, together with the square on the straight line between the points of section, equals the square on the half.",
            gnomon, s.steps,
        )
    }

    /**
     * II.6. If a straight line [ab] be bisected and produced, the rectangle on the whole line with
     * the added part, and the added part, with the square on the half, equals the square on the half
     * with the added part. The result is the gnomon.
     */
    fun book2prop6(ab: Segment2, side: Int = 1): Construction<Polygon2> {
        val (a, b) = ab
        val s = Proof()
        s.step("Let AB be the given straight line.", ab.drawn(Byrne.BLACK), a.named("A"), b.named("B"))
        val c = s.by { prop10(ab) }
        val d = s.pick(meet(Ray2(b, b - a), Circle2(b, ab.length * 0.35), "II.6"))
        s.step("Bisect AB at C, and produce it to any point D.", Segment2(b, d).drawn(Byrne.BLACK), c.named("C"), d.named("D"))
        val sq = s.by { prop46(Segment2(c, d), side) }
        val f = sq.c; val e = sq.d
        s.step("On CD describe the square CEFD, and join DE.", *sq.sides.map { it.drawn(Byrne.BLACK) }.toTypedArray(), Segment2(d, e).drawn(Byrne.BLACK, Role.CONSTRUCTION), e.named("E"), f.named("F"))
        val bg = s.by { prop31(b, Segment2(c, e)) }
        val g = s.pick(meet(bg, Segment2(e, f), "II.6"))
        val h = s.pick(meet(bg, Segment2(d, e), "II.6"))
        s.step("Through B draw BG parallel to CE, cutting DE at H.", Segment2(b, g).drawn(Byrne.BLACK), g.named("G"), h.named("H"))
        val km = s.by { prop31(h, ab) }
        val l = s.pick(meet(km, Line2.through(c, e), "II.6"))
        val m = s.pick(meet(km, Line2.through(d, f), "II.6"))
        val k = s.pick(meet(s.by { prop31(a, Segment2(c, l)) }, km, "II.6"))
        s.step(
            "Through H draw KLM parallel to AB, and through A draw AK parallel to CL.",
            Segment2(k, m).drawn(Byrne.BLACK), Segment2(a, k).drawn(Byrne.BLACK), k.named("K"), l.named("L"), m.named("M"),
        )
        val gnomon = Polygon2(c, d, f, g, h, l)
        s.step(
            "AL equals HF, so the rectangle AM, which is AD·DB, equals the gnomon CDFGHL. Add to each the square LG on CB: the rectangle AD·DB with the square on CB equals the square on CD.",
            Polygon2(a, c, l, k).drawn(Byrne.RED, Role.AREA), Polygon2(h, m, f, g).drawn(Byrne.RED, Role.AREA),
            Polygon2(c, d, m, l).drawn(Byrne.BLUE, Role.AREA), Polygon2(l, h, g, e).drawn(Byrne.YELLOW, Role.AREA),
        )
        s.check(eq(gnomon.area, a.dist(d) * d.dist(b)), "II.6: the gnomon is not AD·DB")
        return Construction(
            "II.6", "If a straight line be bisected and a straight line be added to it, the rectangle contained by the whole with the added straight line and the added straight line, together with the square on the half, equals the square on the straight line made up of the half and the added straight line.",
            gnomon, s.steps,
        )
    }

    /**
     * II.11. To cut a given straight line [ab] so that the rectangle on the whole and one segment
     * equals the square on the other: the golden section. The result is the point of section H,
     * with AH² = AB·BH.
     */
    fun book2prop11(ab: Segment2, side: Int = 1): Construction<Point2> {
        val (a, b) = ab
        val s = Proof()
        s.step("Let AB be the given straight line.", ab.drawn(Byrne.BLACK), a.named("A"), b.named("B"))
        val sq = s.by { prop46(ab, side) }
        val c = sq.d; val d = sq.c
        s.step("On AB describe the square ABDC.", *sq.sides.map { it.drawn(Byrne.BLACK) }.toTypedArray(), c.named("C"), d.named("D"))
        val e = s.by { prop10(Segment2(a, c)) }
        s.step("Bisect AC at E, and join BE.", e.named("E"), Segment2(b, e).drawn(Byrne.BLACK, Role.CONSTRUCTION))
        val circle = Circle2(e, e.dist(b))
        val f = s.pick(meet(Ray2(a, a - c), circle, "II.11"))
        s.step("Produce CA to F, making EF equal to EB.", circle.drawn(Byrne.BLUE, Role.CONSTRUCTION), Segment2(a, f).drawn(Byrne.BLACK, Role.CONSTRUCTION), f.named("F"))
        val sqF = s.by { prop46(Segment2(a, f), Line2.through(a, f).side(b)) }
        val g = sqF.c; val h = sqF.d
        val k = s.pick(meet(Line2.through(g, h), Line2.through(c, d), "II.11"))
        s.step(
            "On AF describe the square FGHA, and produce GH to K. AB is cut at H so that the rectangle AB·BH, which is HD, equals the square AG on AH.",
            sqF.polygon.drawn(Byrne.YELLOW, Role.AREA), Polygon2(h, b, d, k).drawn(Byrne.RED, Role.AREA),
            Segment2(g, k).drawn(Byrne.BLACK), *sqF.sides.map { it.drawn(Byrne.BLACK) }.toTypedArray(),
            g.named("G"), h.named("H"), k.named("K"), Segment2(a, h).drawn(Byrne.YELLOW, Role.RESULT),
        )
        s.check(eq(a.dist(h) * a.dist(h), ab.length * h.dist(b)) && eq(a.dist(h) + h.dist(b), ab.length), "II.11: H is not the section")
        return Construction("II.11", "To cut a given straight line so that the rectangle contained by the whole and one of the segments equals the square on the remaining segment.", h, s.steps)
    }

    /**
     * II.14. To construct a square equal to a given rectilineal figure. Euclid first makes a
     * rectangle equal to the figure (I.45); this starts from that rectangle, [rect], whose corners in
     * order are B, E, D, C. The result is the square on EH.
     */
    fun book2prop14(rect: Quadrilateral2): Construction<Quadrilateral2> {
        val b = rect.a; val e = rect.b; val d = rect.c; val c = rect.d
        val s = Proof()
        s.step(
            "Let BEDC be the rectangle equal to the given rectilineal figure (I.45).",
            rect.polygon.drawn(Byrne.RED, Role.AREA), *rect.sides.map { it.drawn(Byrne.BLACK) }.toTypedArray(),
            b.named("B"), e.named("E"), d.named("D"), c.named("C"),
        )
        val circle = Circle2(e, e.dist(d))
        val f = s.pick(meet(Ray2(e, e - b), circle, "II.14"))
        s.step("Produce BE to F, making EF equal to ED.", circle.drawn(Byrne.BLUE, Role.CONSTRUCTION), Segment2(e, f).drawn(Byrne.BLACK, Role.CONSTRUCTION), f.named("F"))
        val g = s.by { prop10(Segment2(b, f)) }
        s.step("Bisect BF at G.", g.named("G"))
        val bf = Line2.through(b, f)
        val up = Arc2(g, g.dist(f), (f - g).angle, PI)
        val semi = if (bf.side(up.midPoint) == bf.side(d)) Arc2(g, g.dist(f), (f - g).angle, -PI) else up
        s.step("With centre G and radius GF describe the semicircle BHF.", semi.drawn(Byrne.BLACK))
        val h = s.pick(meet(Ray2(e, e - d), semi, "II.14"))
        s.step("Produce DE to meet the semicircle at H.", Segment2(e, h).drawn(Byrne.BLACK, Role.CONSTRUCTION), h.named("H"))
        val sq = s.by { prop46(Segment2(e, h), -Line2.through(e, h).side(b)) }
        s.step(
            "The square on EH equals the rectangle BD, and so the given figure.",
            sq.polygon.drawn(Byrne.YELLOW, Role.AREA), *sq.sides.map { it.drawn(Byrne.YELLOW, Role.RESULT) }.toTypedArray(),
        )
        s.check(isSquare(sq) && eq(sq.polygon.area, rect.polygon.area), "II.14: the square is not equal to the rectangle")
        return Construction("II.14", "To construct a square equal to a given rectilineal figure.", sq, s.steps)
    }

    // ---- Book III: the circle ------------------------------------------------------------------

    /** III.17. From a given point [a] to draw a straight line touching a given circle. */
    fun book3prop17(circle: Circle2, a: Point2): Construction<Segment2> {
        val e = circle.center
        val s = Proof()
        require(a !in circle) { "III.17: the point is inside the circle" }
        s.step("Let BCD be the given circle, E its centre, and A the given point.", circle.drawn(Byrne.BLACK), e.named("E"), a.named("A"))
        val d = s.pick(meet(Segment2(a, e), circle, "III.17"))
        s.step("Join AE, cutting the circle at D.", Segment2(a, e).drawn(Byrne.BLACK, Role.CONSTRUCTION), d.named("D"))
        val big = Circle2(e, e.dist(a))
        s.step("With centre E and radius EA describe the circle AFG.", big.drawn(Byrne.BLUE, Role.CONSTRUCTION))
        val up = s.by { prop11(Segment2(e, a), d) }
        val f = s.pick(meet(up, big, "III.17"))
        s.step("From D draw DF at right angles to EA, meeting that circle at F.", Segment2(d, f).drawn(Byrne.RED), f.named("F"))
        val b = s.pick(meet(Segment2(e, f), circle, "III.17"))
        val ab = Segment2(a, b)
        s.step(
            "Join EF, cutting the first circle at B, and join AB. The triangles EAB and EFD are equal, so the angle at B is right, and AB touches the circle.",
            Segment2(e, f).drawn(Byrne.BLACK, Role.CONSTRUCTION), b.named("B"), ab.drawn(Byrne.YELLOW, Role.RESULT),
        )
        s.check(eq((b - e).normalized() dot (a - b).normalized(), 0.0), "III.17: AB does not touch the circle")
        return Construction("III.17", "From a given point to draw a straight line touching a given circle.", ab, s.steps)
    }

    // ---- Book IV: figures in circles -----------------------------------------------------------

    /**
     * IV.10. To construct an isosceles triangle having each of the angles at the base double the
     * remaining one: the golden triangle, apex `ab.a`, with sides AB = AD.
     */
    fun book4prop10(ab: Segment2): Construction<Triangle2> {
        val (a, b) = ab
        val s = Proof()
        s.step("Let AB be any straight line.", ab.drawn(Byrne.BLACK), a.named("A"), b.named("B"))
        val c = s.by { book2prop11(ab) }
        s.step("Cut AB at C so that the rectangle AB·BC equals the square on AC (II.11).", c.named("C"), Segment2(a, c).drawn(Byrne.RED))
        val circle = Circle2(a, ab.length)
        s.step("With centre A and radius AB describe the circle BDE.", circle.drawn(Byrne.BLUE, Role.CONSTRUCTION))
        val chord = Circle2(b, a.dist(c))
        val d = s.pick(meet(circle, chord, "IV.10"))
        s.step(
            "In it fit BD equal to AC (IV.1), and join AD and DC. The angles at B and D are each double the angle at A.",
            chord.drawn(Byrne.RED, Role.CONSTRUCTION), d.named("D"), Segment2(d, c).drawn(Byrne.BLACK, Role.CONSTRUCTION),
            Segment2(a, d).drawn(Byrne.YELLOW, Role.RESULT), Segment2(b, d).drawn(Byrne.RED, Role.RESULT),
        )
        s.check(eq(angleAt(b, a, d), 2 * angleAt(a, b, d)), "IV.10: the base angles are not double")
        return Construction("IV.10", "To construct an isosceles triangle having each of the angles at the base double the remaining one.", Triangle2(a, b, d), s.steps)
    }

    /**
     * IV.11. In a given circle to inscribe an equilateral and equiangular pentagon, with a corner at
     * angle [at] from the centre.
     */
    fun book4prop11(circle: Circle2, at: Double = -PI / 2): Construction<Polygon2> {
        val o = circle.center; val r = circle.radius
        val s = Proof()
        val a = circle.at(at)
        s.step("Let ABCDE be the given circle.", circle.drawn(Byrne.BLACK), a.named("A"))
        val gt = s.by { book4prop10(Segment2(o + Point2(-2.5 * r, -0.95 * r), o + Point2(-2.5 * r, 0.95 * r))) }
        val (f, g, h) = gt
        s.step(
            "Set out the triangle FGH, with each angle at G and H double the angle at F (IV.10).",
            *gt.sides.map { it.drawn(Byrne.BLACK) }.toTypedArray(), f.named("F"), g.named("G"), h.named("H"),
        )
        val touch = s.by { prop11(Segment2(o, a), a) }
        val tangent = Line2(a, touch.direction)
        val k = a + touch.direction.normalized() * r; val l = a - touch.direction.normalized() * r
        s.step("Draw KAL touching the circle at A.", Segment2(k, l).drawn(Byrne.BLACK, Role.CONSTRUCTION), k.named("K"), l.named("L"))
        val toC = s.by { prop23(Ray2(a, l - a), g, f, h, Line2(a, l - a).side(o)) }
        val toD = s.by { prop23(Ray2(a, k - a), h, f, g, Line2(a, k - a).side(o)) }
        val c = far(meet(toC, circle, "IV.11"), a); val d = far(meet(toD, circle, "IV.11"), a)
        s.step(
            "Make the angle LAC equal to the angle at G, and KAD equal to the angle at H (I.23), and join CD. The triangle ACD has the angles of FGH (III.32).",
            Segment2(a, c).drawn(Byrne.RED), Segment2(a, d).drawn(Byrne.RED), Segment2(c, d).drawn(Byrne.BLUE), c.named("C"), d.named("D"),
        )
        val e = far(meet(s.by { prop9(c, a, d) }, circle, "IV.11"), c)
        val b = far(meet(s.by { prop9(d, a, c) }, circle, "IV.11"), d)
        val pentagon = aroundCentre(o, at, listOf(a, b, c, d, e))
        s.step(
            "Bisect the angles ACD and CDA by CE and DB (I.9), and join AB, BC, DE, EA. The five arcs are equal, and ABCDE is the regular pentagon.",
            Segment2(c, e).drawn(Byrne.BLACK, Role.CONSTRUCTION), Segment2(d, b).drawn(Byrne.BLACK, Role.CONSTRUCTION),
            b.named("B"), e.named("E"), pentagon.drawn(Byrne.YELLOW, Role.RESULT),
        )
        require(tangent.side(o) != 0)
        s.check(isRegular(pentagon, 5), "IV.11: ABCDE is not regular")
        return Construction("IV.11", "In a given circle to inscribe an equilateral and equiangular pentagon.", pentagon, s.steps)
    }

    /** IV.15. In a given circle to inscribe an equilateral and equiangular hexagon, a corner at angle [at]. */
    fun book4prop15(circle: Circle2, at: Double = -PI / 2): Construction<Polygon2> {
        val g = circle.center
        val s = Proof()
        val a = circle.at(at)
        s.step("Let ABCDEF be the given circle, and G its centre.", circle.drawn(Byrne.BLACK), g.named("G"), a.named("A"))
        val d = far(meet(Line2.through(a, g), circle, "IV.15"), a)
        s.step("Draw the diameter AGD.", Segment2(a, d).drawn(Byrne.BLACK, Role.CONSTRUCTION), d.named("D"))
        val around = Circle2(d, circle.radius)
        val (c, e) = meet(around, circle, "IV.15")
        s.step("With centre D and radius DG describe the circle EGCH, cutting the given circle at C and E.", around.drawn(Byrne.RED, Role.CONSTRUCTION), c.named("C"), e.named("E"))
        val f = far(meet(Line2.through(c, g), circle, "IV.15"), c)
        val b = far(meet(Line2.through(e, g), circle, "IV.15"), e)
        val hexagon = aroundCentre(g, at, listOf(a, b, c, d, e, f))
        s.step(
            "Join CG and EG and produce them to F and B. The six arcs are equal, and ABCDEF is the regular hexagon.",
            Segment2(c, f).drawn(Byrne.BLACK, Role.CONSTRUCTION), Segment2(e, b).drawn(Byrne.BLACK, Role.CONSTRUCTION),
            f.named("F"), b.named("B"), hexagon.drawn(Byrne.YELLOW, Role.RESULT),
        )
        s.check(isRegular(hexagon, 6), "IV.15: ABCDEF is not regular")
        return Construction("IV.15", "In a given circle to inscribe an equilateral and equiangular hexagon.", hexagon, s.steps)
    }

    /** IV.16. In a given circle to inscribe a fifteen-angled figure, equilateral and equiangular. */
    fun book4prop16(circle: Circle2, at: Double = -PI / 2): Construction<Polygon2> {
        val o = circle.center
        val s = Proof()
        val a = circle.at(at)
        s.step("Let ABCD be the given circle.", circle.drawn(Byrne.BLACK), a.named("A"))
        val hexagon = s.by { book4prop15(circle, at) }.points
        val c = hexagon[2]
        s.step(
            "Inscribe an equilateral triangle with a corner at A (IV.2, here taking every other corner of the hexagon of IV.15); let AC be its side.",
            *Triangle2(hexagon[0], hexagon[2], hexagon[4]).sides.map { it.drawn(Byrne.BLUE) }.toTypedArray(), c.named("C"),
        )
        val pentagon = s.by { book4prop11(circle, at) }.points
        val b = pentagon[1]
        s.step(
            "Inscribe a regular pentagon with a corner at A (IV.11); let AB be its side. Of the fifteen equal parts of the circle, the arc AC holds five and AB three, so BC holds two.",
            pentagon.let { Polygon2(it) }.drawn(Byrne.RED), b.named("B"),
        )
        val mid = s.by { prop10(Segment2(b, c)) }
        val up = s.by { prop11(Segment2(b, c), mid) }
        val e = meet(Line2(mid, up.direction), circle, "IV.16").minBy { it.dist(b) }
        s.step("Bisect the arc BC at E (III.30). BE and EC are each a fifteenth of the circle.", Segment2(mid, e).drawn(Byrne.BLACK, Role.CONSTRUCTION), e.named("E"))
        val side = b.dist(e)
        val corners = mutableListOf(a)
        val steps = mutableListOf<Figure>()
        while (corners.size < 15) {
            val p = corners.last()
            val chord = Circle2(p, side)
            steps += chord.drawn(Byrne.YELLOW, Role.CONSTRUCTION)
            corners += meet(chord, circle, "IV.16").minBy { offset(o, p, it) }
        }
        val figure = Polygon2(corners)
        s.step("Fit straight lines equal to BE round the whole circle (IV.1): the figure is equilateral and equiangular.", *steps.toTypedArray(), figure.drawn(Byrne.YELLOW, Role.RESULT))
        s.check(isRegular(figure, 15), "IV.16: the figure is not regular")
        return Construction("IV.16", "In a given circle to inscribe a fifteen-angled figure which shall be both equilateral and equiangular.", figure, s.steps)
    }

    // ---- Book VI: proportion -------------------------------------------------------------------

    /** VI.9. From a given straight line [ab] to cut off a prescribed part, a [parts]-th. */
    fun book6prop9(ab: Segment2, parts: Int = 3): Construction<Segment2> {
        require(parts >= 2) { "VI.9: cut off at least a half" }
        val (a, b) = ab
        val s = Proof()
        s.step("Let AB be the given straight line.", ab.drawn(Byrne.BLACK), a.named("A"), b.named("B"))
        val dir = ab.direction.rotated(-PI / 6).normalized()
        val unit = ab.length * 0.9 / parts
        val marks = mutableListOf<Point2>(); val circles = mutableListOf<Figure>()
        var p = a
        repeat(parts) {
            val circle = Circle2(p, unit)
            circles += circle.drawn(Byrne.BLUE, Role.CONSTRUCTION)
            p = s.pick(meet(Ray2(p, dir), circle, "VI.9")); marks += p
        }
        val c = marks.last(); val d = marks.first()
        val letters = "DEGHIKLMNOPQRSTUVWXYZ"
        s.step(
            "Draw AC at any angle to AB, and cut off equal parts AD, DE, ... along it, as many as the part has (I.3).",
            Segment2(a, c).drawn(Byrne.BLACK), *circles.toTypedArray(),
            *marks.dropLast(1).mapIndexed { i, m -> m.named(letters[i].toString()) }.toTypedArray(), c.named("C"),
        )
        val df = s.by { prop31(d, Segment2(b, c)) }
        val f = s.pick(meet(df, ab, "VI.9"))
        val af = Segment2(a, f)
        s.step(
            "Join BC, and through D draw DF parallel to it (I.31). AF is the part required (VI.2).",
            Segment2(b, c).drawn(Byrne.RED), Segment2(d, f).drawn(Byrne.RED, Role.CONSTRUCTION), f.named("F"), af.drawn(Byrne.YELLOW, Role.RESULT),
        )
        s.check(eq(af.length * parts, ab.length), "VI.9: AF is not the part")
        return Construction("VI.9", "From a given straight line to cut off a prescribed part.", af, s.steps)
    }

    /**
     * VI.10. To cut a given uncut straight line [ab] similarly to a given cut straight line [ac]
     * (which starts at A too), cut at [cuts]. The result is the points of section on AB, in order.
     */
    fun book6prop10(ab: Segment2, ac: Segment2, cuts: List<Point2>): Construction<List<Point2>> {
        val a = ab.a; val b = ab.b; val c = ac.b
        val s = Proof()
        val colors = listOf(Byrne.RED, Byrne.YELLOW, Byrne.BLUE, Byrne.BLACK)
        val along = (listOf(a) + cuts + c)
        s.step(
            "Let AB be the uncut line and AC the cut one, set at an angle, cut at D, E, ....",
            ab.drawn(Byrne.BLACK), *along.zipWithNext().mapIndexed { i, (p, q) -> Segment2(p, q).drawn(colors[i % colors.size]) }.toTypedArray(),
            a.named("A"), b.named("B"), c.named("C"), *cuts.mapIndexed { i, p -> p.named("DEGHK"[i % 5].toString()) }.toTypedArray(),
        )
        s.step("Join BC.", Segment2(b, c).drawn(Byrne.BLACK, Role.CONSTRUCTION))
        val feet = cuts.map { p -> s.pick(meet(s.by { prop31(p, Segment2(b, c)) }, ab, "VI.10")) }
        val onAB = listOf(a) + feet + b
        s.step(
            "Through D, E, ... draw parallels to BC (I.31), meeting AB at F, G, .... AB is cut as AC is (VI.2).",
            *cuts.zip(feet).map { (p, q) -> Segment2(p, q).drawn(Byrne.BLACK, Role.CONSTRUCTION) }.toTypedArray(),
            *onAB.zipWithNext().mapIndexed { i, (p, q) -> Segment2(p, q).drawn(colors[i % colors.size], Role.RESULT) }.toTypedArray(),
            *feet.mapIndexed { i, p -> p.named("FLMNO"[i % 5].toString()) }.toTypedArray(),
        )
        s.check(feet.indices.all { eq(a.dist(feet[it]) / ab.length, a.dist(cuts[it]) / ac.length) }, "VI.10: AB is not cut as AC is")
        return Construction("VI.10", "To cut a given uncut straight line similarly to a given cut straight line.", feet, s.steps)
    }

    /**
     * VI.11. To two given straight lines [ab] and [ac], set at an angle from A, to find a third
     * proportional: CE, with AB : AC = AC : CE.
     */
    fun book6prop11(ab: Segment2, ac: Segment2): Construction<Segment2> {
        val a = ab.a; val b = ab.b; val c = ac.b
        val s = Proof()
        s.step("Let AB and AC be the two given straight lines, set at any angle.", ab.drawn(Byrne.RED), ac.drawn(Byrne.BLUE), a.named("A"), b.named("B"), c.named("C"))
        val circle = Circle2(b, ac.length)
        val d = s.pick(meet(Ray2(b, b - a), circle, "VI.11"))
        s.step("Produce AB to D, making BD equal to AC.", circle.drawn(Byrne.BLUE, Role.CONSTRUCTION), Segment2(b, d).drawn(Byrne.BLUE), d.named("D"))
        s.step("Join BC.", Segment2(b, c).drawn(Byrne.BLACK))
        val de = s.by { prop31(d, Segment2(b, c)) }
        val e = s.pick(meet(de, Ray2(a, c - a), "VI.11"))
        val ce = Segment2(c, e)
        s.step(
            "Through D draw DE parallel to BC, meeting AC produced at E. As AB is to BD, so AC is to CE (VI.2), and BD is AC.",
            Segment2(d, e).drawn(Byrne.BLACK, Role.CONSTRUCTION), e.named("E"), ce.drawn(Byrne.YELLOW, Role.RESULT),
        )
        s.check(eq(ab.length * ce.length, ac.length * ac.length), "VI.11: CE is not the third proportional")
        return Construction("VI.11", "To two given straight lines to find a third proportional.", ce, s.steps)
    }

    /**
     * VI.12. To three given straight lines [first], [second] and [third] to find a fourth
     * proportional HL, with first : second = third : HL. The working is laid out from [at].
     */
    fun book6prop12(first: Segment2, second: Segment2, third: Segment2, at: Point2 = Point2.ORIGIN): Construction<Segment2> {
        val d = at
        val s = Proof()
        s.step(
            "Let A, B, C be the three given straight lines, and DE, DF two straight lines from D at any angle.",
            first.drawn(Byrne.RED), second.drawn(Byrne.BLUE), third.drawn(Byrne.BLACK), d.named("D"),
        )
        val along = Point2(1.0, 0.0); val across = Point2(cos(-PI / 4), sin(-PI / 4))
        val c1 = Circle2(d, first.length)
        val g = s.pick(meet(Ray2(d, along), c1, "VI.12"))
        val c2 = Circle2(g, second.length)
        val e = s.pick(meet(Ray2(g, along), c2, "VI.12"))
        val c3 = Circle2(d, third.length)
        val h = s.pick(meet(Ray2(d, across), c3, "VI.12"))
        s.step(
            "Cut off DG equal to A, GE equal to B, and DH equal to C (I.3).",
            c1.drawn(Byrne.RED, Role.CONSTRUCTION), c2.drawn(Byrne.BLUE, Role.CONSTRUCTION), c3.drawn(Byrne.BLACK, Role.CONSTRUCTION),
            Segment2(d, g).drawn(Byrne.RED), Segment2(g, e).drawn(Byrne.BLUE), Segment2(d, h).drawn(Byrne.BLACK),
            g.named("G"), e.named("E"), h.named("H"),
        )
        s.step("Join GH.", Segment2(g, h).drawn(Byrne.BLACK, Role.CONSTRUCTION))
        val el = s.by { prop31(e, Segment2(g, h)) }
        val l = s.pick(meet(el, Ray2(d, across), "VI.12"))
        val hl = Segment2(h, l)
        s.step(
            "Through E draw EL parallel to GH. As DG is to GE, so DH is to HL (VI.2): A is to B as C is to HL.",
            Segment2(e, l).drawn(Byrne.BLACK, Role.CONSTRUCTION), l.named("L"), hl.drawn(Byrne.YELLOW, Role.RESULT),
        )
        s.check(eq(first.length * hl.length, second.length * third.length), "VI.12: HL is not the fourth proportional")
        return Construction("VI.12", "To three given straight lines to find a fourth proportional.", hl, s.steps)
    }

    /**
     * VI.30. To cut a given finite straight line [ab] in extreme and mean ratio. Euclid applies to
     * AB a rectangle equal to its square and exceeding by a square (VI.29); for a square that comes
     * down to the circle drawn here. The result is the point of section E, with AB : AE = AE : EB.
     */
    fun book6prop30(ab: Segment2, side: Int = 1): Construction<Point2> {
        val (a, b) = ab
        val s = Proof()
        s.step("Let AB be the given straight line.", ab.drawn(Byrne.BLACK), a.named("A"), b.named("B"))
        val sq = s.by { prop46(ab, side) }
        val f = sq.c; val g = sq.d
        s.step("On AB describe the square ABFG.", *sq.sides.map { it.drawn(Byrne.BLACK) }.toTypedArray(), f.named("F"), g.named("G"))
        val c = s.by { prop10(ab) }
        s.step("Bisect AB at C, and join CG.", c.named("C"), Segment2(c, g).drawn(Byrne.BLACK, Role.CONSTRUCTION))
        val circle = Circle2(c, c.dist(g))
        val d = s.pick(meet(Ray2(b, b - a), circle, "VI.30"))
        s.step(
            "With centre C and radius CG cut AB produced at D. Then the rectangle on AD with height BD equals the square on AB, and exceeds AB by the square on BD (VI.29, II.6).",
            circle.drawn(Byrne.BLUE, Role.CONSTRUCTION), Segment2(b, d).drawn(Byrne.BLACK), d.named("D"),
        )
        val up = (g - a).normalized() * b.dist(d)
        val m = d + up; val n = b + up; val k = a + up
        val e = s.pick(meet(ab, Circle2(a, b.dist(d)), "VI.30"))
        s.step(
            "Complete the rectangle ADMK and the square BDMN. Taking away the common ABNK, the square BDMN equals KNFG. Cut off AE equal to BD: AB is cut at E in extreme and mean ratio.",
            Polygon2(a, b, n, k).drawn(Byrne.YELLOW, Role.AREA), Polygon2(k, n, f, g).drawn(Byrne.RED, Role.AREA), Polygon2(b, d, m, n).drawn(Byrne.RED, Role.AREA),
            Segment2(d, m).drawn(Byrne.BLACK), Segment2(m, k).drawn(Byrne.BLACK), Segment2(b, n).drawn(Byrne.BLACK),
            m.named("M"), n.named("N"), k.named("K"), e.named("E"), Segment2(a, e).drawn(Byrne.YELLOW, Role.RESULT),
        )
        s.check(eq(ab.length * e.dist(b), a.dist(e) * a.dist(e)), "VI.30: E is not the section")
        return Construction("VI.30", "To cut a given finite straight line in extreme and mean ratio.", e, s.steps)
    }
}

/** Of [points], the one farthest from [from]: where a line through [from] leaves a circle. */
private fun far(points: List<Point2>, from: Point2) = points.maxBy { it.dist(from) }

/** How far round the circle about [o], counter-clockwise from [p], the point [q] is, in [0, 2π). */
private fun offset(o: Point2, p: Point2, q: Point2): Double {
    val d = (q - o).angle - (p - o).angle
    return ((d % (2 * PI)) + 2 * PI) % (2 * PI)
}

/** The polygon through [points] in order round [o], starting at angle [at]. */
private fun aroundCentre(o: Point2, at: Double, points: List<Point2>): Polygon2 {
    val start = Point2.polar(1.0, at, o)
    return Polygon2(points.sortedBy { offset(o, start, it).let { a -> if (a > 2 * PI - 1e-9) 0.0 else a } })
}

/** The solution whose result lies nearest [p]. */
fun <T> List<Construction<T>>.nearest(p: Point2): Construction<T> = minBy { (it.location - p).length }

/** The solution whose result lies farthest from [p]. */
fun <T> List<Construction<T>>.farthest(p: Point2): Construction<T> = maxBy { (it.location - p).length }
