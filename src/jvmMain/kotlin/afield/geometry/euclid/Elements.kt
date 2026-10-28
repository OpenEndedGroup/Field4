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
 * lines += c.toFLines(upTo = 2)  // only the first two steps, for stepping through
 * lines += c.toFLines(style = ByrneStyle.WORKING)  // cited propositions' working too
 * ```
 *
 * Every step is built only from the intersections of circles and straight lines in `Euclid.kt`;
 * nothing is placed by a formula the proposition does not use. Where Euclid says "take any point",
 * the choice here is fixed so the same input always draws the same figure.
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
import kotlin.math.max
import kotlin.math.min

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
}

/** One drawn element of a construction: a [shape] in [color] playing [role], perhaps lettered with [label]. */
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
 * Cited propositions draw only what they produce; set [cited] to a pen to show their working as
 * well, faded behind the main figure ([WORKING] does this). [paper] is the sheet behind the figure,
 * or null for none. [thickness] is the base stroke width; null, the default, takes it from the
 * figure's size, so diagrams drawn at any scale (or fitted to an output) look alike. Letters are italic serif, [labelSize] thicknesses tall, set [labelDistance]
 * thicknesses up and to the right of their point.
 */
data class ByrneStyle(
    val thickness: Double? = null,
    val given: Pen = Pen(1.0),
    val result: Pen = Pen(1.6),
    val circles: Pen = Pen(0.6),
    val constructionLines: Pen = Pen(0.6, dash = 2.0),
    val cited: Pen? = null,
    val pointSize: Double = 0.8,
    val labels: Boolean = true,
    val labelSize: Double = 7.2,
    val labelDistance: Double = 2.76,
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
}

/** A four-sided figure by its corners in order. */
data class Quadrilateral2(val a: Point2, val b: Point2, val c: Point2, val d: Point2) {
    val sides get() = listOf(Segment2(a, b), Segment2(b, c), Segment2(c, d), Segment2(d, a))
}

/** The [result] of a proposition and the [steps] that construct it. */
class Construction<out T>(val proposition: String, val enunciation: String, val result: T, val steps: List<Step>) {
    val figures get() = steps.flatMap { it.figures }

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
        style.paper?.let { out += paperBehind(box, t, it) }
        for (step in shown) for (f in step.figures) {
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
            if (style.labels && p != null && f.label != null) out += letter(f.label, p, style, t)
        }
        return out
    }

    /** The enunciation and the numbered proof, as Markdown; cited steps are included when [cited] is true. */
    fun markdown(cited: Boolean = false) = buildString {
        appendLine("**$proposition.** *$enunciation*")
        appendLine()
        steps.filter { cited || it.cited == null }.forEachIndexed { i, s -> appendLine("${i + 1}. ${s.text}") }
    }

    private fun letter(text: String, at: Point2, style: ByrneStyle, t: Double): List<FLine> = try {
        val size = style.labelSize * t
        val off = style.labelDistance * t
        layoutText(text, typeface(Font.SERIF, size, italic = true)).toFLines(
            color = vec(Byrne.BLACK.r, Byrne.BLACK.g, Byrne.BLACK.b, 1.0), thickness = 0.0, filled = true, stroked = false,
        ).map { g -> g.transform { v -> Vec3((v.x + at.x + off).toFloat(), (v.y + at.y - off).toFloat(), v.z) } }
    } catch (_: Exception) {
        emptyList() // a headless JVM without fonts gets an unlettered diagram
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

private class Builder {
    val steps = mutableListOf<Step>()
    fun step(text: String, vararg figures: Figure) { steps += Step(text, figures.toList()) }

    /** Cites an earlier proposition: its steps, unlettered, under its name. Returns its result. */
    fun <T> by(c: Construction<T>): T {
        c.steps.forEach { s -> steps += Step("By ${c.proposition}: ${s.text}", s.figures.map { it.copy(label = null) }, c.proposition) }
        return c.result
    }
}

private fun Shape2.drawn(color: Byrne, role: Role = Role.GIVEN) = Figure(this, color, role = role)
private fun Shape2.mapped(k: Double, o: Point2): Shape2 = when (this) {
    is Point2 -> this * k + o
    is Line2 -> Line2(point * k + o, direction)
    is Ray2 -> Ray2(origin * k + o, direction)
    is Segment2 -> Segment2(a * k + o, b * k + o)
    is Circle2 -> Circle2(center * k + o, radius * k)
    is Arc2 -> Arc2(center * k + o, radius * k, start, sweep)
}

private fun Point2.named(label: String) = Figure(this, Byrne.BLACK, label)

private fun meet(a: Shape2, b: Shape2, what: String): List<Point2> =
    a.intersect(b).points.ifEmpty { error("$what: the figures do not meet") }

/** Of [points], one on the [side] (+1 left, -1 right) of [line]. */
private fun onSide(line: Line2, points: List<Point2>, side: Int) = points.maxBy { line.signedDistance(it) * side }

private fun Point2.dist(o: Point2) = (o - this).length

object Elements {

    /**
     * I.1. On a given finite straight line to construct an equilateral triangle. The apex is on the
     * left of [ab] going from `a` to `b` when [side] is +1, on the right when -1.
     */
    fun prop1(ab: Segment2, side: Int = 1): Construction<Triangle2> {
        val (a, b) = ab
        val s = Builder()
        val around = Circle2(a, a.dist(b))
        val around2 = Circle2(b, a.dist(b))
        s.step("Let AB be the given straight line.", ab.drawn(Byrne.BLACK), a.named("A"), b.named("B"))
        s.step("With centre A and radius AB describe the circle BCD.", around.drawn(Byrne.RED, Role.CONSTRUCTION))
        s.step("With centre B and radius BA describe the circle ACE.", around2.drawn(Byrne.BLUE, Role.CONSTRUCTION))
        val c = onSide(ab.line, meet(around, around2, "I.1"), side)
        s.step(
            "From C, where the circles cut one another, draw CA and CB. Then CA = AB = CB, and ABC is equilateral.",
            c.named("C"), Segment2(c, a).drawn(Byrne.YELLOW, Role.RESULT), Segment2(c, b).drawn(Byrne.RED, Role.RESULT),
        )
        return Construction("I.1", "On a given finite straight line to construct an equilateral triangle.", Triangle2(a, b, c), s.steps)
    }

    /** I.2. To place at a given point [a] a straight line equal to a given straight line [bc]. */
    fun prop2(a: Point2, bc: Segment2): Construction<Segment2> {
        val (b, c) = bc
        val s = Builder()
        val r = b.dist(c)
        s.step("Let A be the given point and BC the given straight line.", a.named("A"), bc.drawn(Byrne.BLUE), b.named("B"), c.named("C"))
        if (a.dist(b) <= Euclid.epsilon * (1 + r)) {
            s.step("A is at B already, so BC is the line required.", bc.drawn(Byrne.YELLOW, Role.RESULT))
            return Construction("I.2", ENUNCIATION_2, bc, s.steps)
        }
        s.step("Join AB.", Segment2(a, b).drawn(Byrne.BLACK, Role.CONSTRUCTION))
        val d = s.by(prop1(Segment2(a, b))).c
        s.step("On AB construct the equilateral triangle DAB.", Segment2(d, a).drawn(Byrne.BLACK, Role.CONSTRUCTION), Segment2(d, b).drawn(Byrne.BLACK, Role.CONSTRUCTION), d.named("D"))
        val circleB = Circle2(b, r)
        val g = meet(Ray2(b, b - d), circleB, "I.2").first()
        s.step(
            "With centre B and radius BC describe the circle CGH, and produce DB to meet it at G.",
            circleB.drawn(Byrne.BLUE, Role.CONSTRUCTION), Segment2(d, g).drawn(Byrne.BLACK, Role.CONSTRUCTION), g.named("G"),
        )
        val circleD = Circle2(d, d.dist(g))
        val l = meet(Ray2(a, a - d), circleD, "I.2").first()
        s.step(
            "With centre D and radius DG describe the circle GKL, and produce DA to meet it at L.",
            circleD.drawn(Byrne.RED, Role.CONSTRUCTION), Segment2(d, l).drawn(Byrne.BLACK, Role.CONSTRUCTION), l.named("L"),
        )
        val al = Segment2(a, l)
        s.step("DL = DG and DA = DB, so the remainder AL = BG = BC.", al.drawn(Byrne.YELLOW, Role.RESULT))
        return Construction("I.2", ENUNCIATION_2, al, s.steps)
    }

    private const val ENUNCIATION_2 = "To place at a given point a straight line equal to a given straight line."

    /** I.3. From the greater of two given straight lines to cut off a part equal to the less. */
    fun prop3(greater: Segment2, less: Segment2): Construction<Segment2> {
        require(greater.length >= less.length) { "I.3: the first line must be the greater" }
        val (a, b) = greater
        val s = Builder()
        s.step("Let AB be the greater and C the less.", greater.drawn(Byrne.BLACK), a.named("A"), b.named("B"), less.drawn(Byrne.BLUE))
        val ad = s.by(prop2(a, less))
        s.step("At A place AD equal to C.", ad.drawn(Byrne.BLUE), ad.b.named("D"))
        val circle = Circle2(a, ad.length)
        val e = meet(greater, circle, "I.3").first()
        val ae = Segment2(a, e)
        s.step("With centre A and radius AD describe the circle DEF, cutting AB at E. Then AE = AD = C.", circle.drawn(Byrne.RED, Role.CONSTRUCTION), e.named("E"), ae.drawn(Byrne.YELLOW, Role.RESULT))
        return Construction("I.3", "From the greater of two given straight lines to cut off a part equal to the less.", ae, s.steps)
    }

    /** I.9. To bisect a given rectilineal angle: the angle at [vertex] between the arms through [b] and [c]. */
    fun prop9(vertex: Point2, b: Point2, c: Point2): Construction<Ray2> {
        val a = vertex
        val s = Builder()
        s.step("Let BAC be the given angle.", Segment2(a, b).drawn(Byrne.BLACK), Segment2(a, c).drawn(Byrne.BLACK), a.named("A"), b.named("B"), c.named("C"))
        val r = minOf(a.dist(b), a.dist(c)) / 2
        val d = meet(Ray2(a, b - a), Circle2(a, r), "I.9").first()
        s.step("Take any point D on AB.", d.named("D"))
        val circle = Circle2(a, r)
        val e = meet(Ray2(a, c - a), circle, "I.9").first()
        s.step("From AC cut off AE equal to AD (I.3).", circle.drawn(Byrne.BLUE, Role.CONSTRUCTION), e.named("E"))
        val de = Segment2(d, e)
        s.step("Join DE.", de.drawn(Byrne.BLACK, Role.CONSTRUCTION))
        val f = s.by(prop1(de, -de.line.side(a).let { if (it == 0) 1 else it })).c
        val af = Ray2(a, f - a)
        s.step("On DE, on the side away from A, construct the equilateral triangle DEF, and join AF. AF bisects the angle BAC.", f.named("F"), Segment2(a, f).drawn(Byrne.YELLOW, Role.RESULT))
        return Construction("I.9", "To bisect a given rectilineal angle.", af, s.steps)
    }

    /** I.10. To bisect a given finite straight line. */
    fun prop10(ab: Segment2): Construction<Point2> {
        val (a, b) = ab
        val s = Builder()
        s.step("Let AB be the given straight line.", ab.drawn(Byrne.BLACK), a.named("A"), b.named("B"))
        val c = s.by(prop1(ab)).c
        s.step("On AB construct the equilateral triangle ABC.", Segment2(c, a).drawn(Byrne.BLACK, Role.CONSTRUCTION), Segment2(c, b).drawn(Byrne.BLACK, Role.CONSTRUCTION), c.named("C"))
        val bisector = s.by(prop9(c, a, b))
        val d = meet(bisector, ab, "I.10").first()
        s.step("Bisect the angle ACB by CD, meeting AB at D. Then AD = DB.", Segment2(c, d).drawn(Byrne.RED), d.named("D"), Segment2(a, d).drawn(Byrne.YELLOW, Role.RESULT), Segment2(d, b).drawn(Byrne.BLUE, Role.RESULT))
        return Construction("I.10", "To bisect a given finite straight line.", d, s.steps)
    }

    /**
     * I.11. To draw a straight line at right angles to a given straight line [ab] from a given point
     * [c] on it. The perpendicular goes to the left of `a`→`b` when [side] is +1.
     */
    fun prop11(ab: Segment2, c: Point2, side: Int = 1): Construction<Ray2> {
        val s = Builder()
        val line = ab.line
        s.step("Let AB be the given straight line and C the given point on it.", ab.drawn(Byrne.BLACK), ab.a.named("A"), ab.b.named("B"), c.named("C"))
        val circle = Circle2(c, ab.length / 4)
        val (d, e) = meet(line, circle, "I.11").sortedBy { line.direction dot it }
        s.step("Take any point D on AC, and make CE equal to CD (I.3).", circle.drawn(Byrne.BLUE, Role.CONSTRUCTION), d.named("D"), e.named("E"))
        val f = s.by(prop1(Segment2(d, e), side)).c
        val cf = Ray2(c, f - c)
        s.step("On DE construct the equilateral triangle FDE, and join FC. FC is at right angles to AB.", f.named("F"), Segment2(c, f).drawn(Byrne.YELLOW, Role.RESULT))
        return Construction("I.11", "To draw a straight line at right angles to a given straight line from a given point on it.", cf, s.steps)
    }

    /** I.12. To draw a perpendicular to a given infinite straight line [ab] from a given point [c] not on it. */
    fun prop12(ab: Line2, c: Point2): Construction<Segment2> {
        val s = Builder()
        require(ab.side(c) != 0) { "I.12: the point is on the line" }
        s.step("Let AB be the given straight line and C the given point not on it.", ab.drawn(Byrne.BLACK), c.named("C"))
        val d = ab.point + (ab.point - c) * 0.3
        s.step("Take any point D on the other side of AB.", d.named("D"))
        val circle = Circle2(c, c.dist(d))
        val (g, e) = meet(ab, circle, "I.12").sortedBy { ab.direction dot it }
        s.step("With centre C and radius CD describe the circle EFG, cutting AB at G and E.", circle.drawn(Byrne.BLUE, Role.CONSTRUCTION), g.named("G"), e.named("E"))
        val h = s.by(prop10(Segment2(g, e)))
        val ch = Segment2(c, h)
        s.step("Bisect GE at H, and join CH. CH is perpendicular to AB.", h.named("H"), ch.drawn(Byrne.YELLOW, Role.RESULT))
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
        val s = Builder()
        s.step(
            "Let AB be the given line, A the point on it, and DCE the given angle.",
            along.drawn(Byrne.BLACK), a.named("A"), Segment2(vertex, d).drawn(Byrne.BLACK), Segment2(vertex, e).drawn(Byrne.BLACK), vertex.named("C"),
        )
        val r = minOf(vertex.dist(d), vertex.dist(e))
        val gauge = Circle2(vertex, r)
        val d1 = meet(Ray2(vertex, d - vertex), gauge, "I.23").first()
        val e1 = meet(Ray2(vertex, e - vertex), gauge, "I.23").first()
        s.step("Cut CD and CE at D and E equally far from C, and join DE.", gauge.drawn(Byrne.BLUE, Role.CONSTRUCTION), d1.named("D"), e1.named("E"), Segment2(d1, e1).drawn(Byrne.RED))
        val copy = Circle2(a, r)
        val f = meet(along, copy, "I.23").first()
        s.step("On AB make AF equal to CD.", copy.drawn(Byrne.BLUE, Role.CONSTRUCTION), f.named("F"))
        val chord = Circle2(f, d1.dist(e1))
        val g = onSide(along.line, meet(copy, chord, "I.23"), side)
        val ag = Ray2(a, g - a)
        s.step(
            "With centre F and radius DE cut the circle at G, and join AG. The triangles AFG and CDE have their sides equal, so the angle FAG equals DCE.",
            chord.drawn(Byrne.RED, Role.CONSTRUCTION), g.named("G"), Segment2(f, g).drawn(Byrne.RED), Segment2(a, g).drawn(Byrne.YELLOW, Role.RESULT),
        )
        return Construction("I.23", "On a given straight line and at a point on it to construct a rectilineal angle equal to a given rectilineal angle.", ag, s.steps)
    }

    /** I.31. Through a given point [a] to draw a straight line parallel to a given straight line [bc]. */
    fun prop31(a: Point2, bc: Segment2): Construction<Line2> {
        val s = Builder()
        require(bc.line.side(a) != 0) { "I.31: the point is on the line" }
        s.step("Let A be the given point and BC the given straight line.", a.named("A"), bc.drawn(Byrne.BLACK), bc.a.named("B"), bc.b.named("C"))
        val d = bc.midpoint
        s.step("Take any point D on BC, and join AD.", d.named("D"), Segment2(a, d).drawn(Byrne.BLACK, Role.CONSTRUCTION))
        val ad = Line2.through(a, d)
        val arm = s.by(prop23(Ray2(a, d - a), d, a, bc.b, -ad.side(bc.b)))
        val e = arm.at(1.0)
        val parallel = Line2(a, arm.direction)
        s.step(
            "At A on DA make the angle DAE equal to the alternate angle ADC, and produce EA to F. EF is parallel to BC.",
            e.named("E"), parallel.drawn(Byrne.YELLOW, Role.RESULT),
        )
        return Construction("I.31", "Through a given point to draw a straight line parallel to a given straight line.", parallel, s.steps)
    }

    /** I.46. On a given straight line to describe a square, on the left of `a`→`b` when [side] is +1. */
    fun prop46(ab: Segment2, side: Int = 1): Construction<Quadrilateral2> {
        val (a, b) = ab
        val s = Builder()
        s.step("Let AB be the given straight line.", ab.drawn(Byrne.BLACK), a.named("A"), b.named("B"))
        val up = s.by(prop11(ab, a, side))
        val circle = Circle2(a, ab.length)
        val d = meet(up, circle, "I.46").first()
        s.step("Draw AC at right angles to AB from A, and cut off AD equal to AB.", up.drawn(Byrne.BLACK, Role.CONSTRUCTION), circle.drawn(Byrne.BLUE, Role.CONSTRUCTION), d.named("D"), Segment2(a, d).drawn(Byrne.RED))
        val de = s.by(prop31(d, ab))
        val be = s.by(prop31(b, Segment2(a, d)))
        val e = meet(de, be, "I.46").first()
        val square = Quadrilateral2(a, b, e, d)
        s.step(
            "Through D draw DE parallel to AB, and through B draw BE parallel to AD. ADEB is a square.",
            e.named("E"), Segment2(d, e).drawn(Byrne.BLUE, Role.RESULT), Segment2(b, e).drawn(Byrne.YELLOW, Role.RESULT),
        )
        return Construction("I.46", "On a given straight line to describe a square.", square, s.steps)
    }

    /** III.1. To find the centre of a given circle (drawn, so its centre is not consulted). */
    fun book3prop1(circle: Circle2): Construction<Point2> {
        val s = Builder()
        val a = circle.at(0.4); val b = circle.at(2.3)
        s.step("Let ABC be the given circle, and draw through it any straight line AB.", circle.drawn(Byrne.BLACK), Segment2(a, b).drawn(Byrne.BLUE), a.named("A"), b.named("B"))
        val d = s.by(prop10(Segment2(a, b)))
        val perp = s.by(prop11(Segment2(a, b), d))
        val (c, e) = meet(Line2(d, perp.direction), circle, "III.1")
        s.step("Bisect AB at D, and from D draw DC at right angles to AB, meeting the circle at C and E.", d.named("D"), Segment2(c, e).drawn(Byrne.RED), c.named("C"), e.named("E"))
        val f = s.by(prop10(Segment2(c, e)))
        s.step("Bisect CE at F. F is the centre of the circle.", f.named("F"))
        return Construction("III.1", "To find the centre of a given circle.", f, s.steps)
    }

    /** IV.4. In a given triangle to inscribe a circle. */
    fun book4prop4(t: Triangle2): Construction<Circle2> {
        val s = Builder()
        s.step("Let ABC be the given triangle.", *t.sides.map { it.drawn(Byrne.BLACK) }.toTypedArray(), t.a.named("A"), t.b.named("B"), t.c.named("C"))
        val atB = s.by(prop9(t.b, t.a, t.c))
        val atC = s.by(prop9(t.c, t.a, t.b))
        val d = meet(atB, atC, "IV.4").first()
        s.step("Bisect the angles ABC and ACB by BD and CD, meeting at D.", Segment2(t.b, d).drawn(Byrne.RED), Segment2(t.c, d).drawn(Byrne.BLUE), d.named("D"))
        val de = s.by(prop12(Line2.through(t.b, t.c), d))
        val circle = Circle2(d, de.length)
        s.step("From D draw DE perpendicular to BC. The circle with centre D and radius DE touches all three sides.", de.b.named("E"), circle.drawn(Byrne.YELLOW, Role.RESULT))
        return Construction("IV.4", "In a given triangle to inscribe a circle.", circle, s.steps)
    }

    /** IV.5. About a given triangle to circumscribe a circle. */
    fun book4prop5(t: Triangle2): Construction<Circle2> {
        val s = Builder()
        s.step("Let ABC be the given triangle.", *t.sides.map { it.drawn(Byrne.BLACK) }.toTypedArray(), t.a.named("A"), t.b.named("B"), t.c.named("C"))
        val d = s.by(prop10(Segment2(t.a, t.b)))
        val e = s.by(prop10(Segment2(t.a, t.c)))
        val fromD = s.by(prop11(Segment2(t.a, t.b), d))
        val fromE = s.by(prop11(Segment2(t.a, t.c), e))
        val f = meet(Line2(d, fromD.direction), Line2(e, fromE.direction), "IV.5").first()
        val circle = Circle2(f, f.dist(t.a))
        s.step(
            "Bisect AB and AC at D and E, and from them draw DF and EF at right angles, meeting at F. The circle with centre F and radius FA passes through A, B and C.",
            d.named("D"), e.named("E"), Segment2(d, f).drawn(Byrne.RED), Segment2(e, f).drawn(Byrne.BLUE), f.named("F"), circle.drawn(Byrne.YELLOW, Role.RESULT),
        )
        return Construction("IV.5", "About a given triangle to circumscribe a circle.", circle, s.steps)
    }

    /**
     * VI.13. To two given straight lines to find a mean proportional: [ac] is the two lines laid end
     * to end, meeting at [b]. The result BD has BD² = AB · BC.
     */
    fun book6prop13(ac: Segment2, b: Point2): Construction<Segment2> {
        val s = Builder()
        s.step("Let AB and BC be the two given straight lines, placed in a straight line.", Segment2(ac.a, b).drawn(Byrne.RED), Segment2(b, ac.b).drawn(Byrne.BLUE), ac.a.named("A"), b.named("B"), ac.b.named("C"))
        val centre = s.by(prop10(ac))
        val semicircle = Arc2(centre, centre.dist(ac.a), (ac.b - centre).angle, Math.PI)
        s.step("On AC describe the semicircle ADC.", semicircle.drawn(Byrne.BLACK))
        val up = s.by(prop11(ac, b))
        val d = meet(up, semicircle, "VI.13").first()
        val bd = Segment2(b, d)
        s.step("From B draw BD at right angles to AC, meeting the semicircle at D. BD is a mean proportional between AB and BC.", d.named("D"), bd.drawn(Byrne.YELLOW, Role.RESULT))
        return Construction("VI.13", "To two given straight lines to find a mean proportional.", bd, s.steps)
    }
}
