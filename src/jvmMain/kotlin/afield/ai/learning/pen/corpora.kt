/**
 * Synthetic drawings to teach a learned pen: Hershey plotter fonts and four drawing generators,
 * and a writer that turns a list of drawings into a corpus `python/fieldpen` can train on.
 *
 * Every generator draws in the order a hand (or a plotter) would, which is the one thing a pen
 * needs from its training data and the one thing a scanned drawing cannot supply. [PenCorpus]
 * resamples each drawing exactly the way [Pen.feed] reads a line, so a pen trained on a corpus sees
 * the same spacing when it is later shown a line. Tests: `PenCorporaTest`.
 */

package afield.ai.learning.pen

import afield.geometry.path.FLine
import afield.math.linalg.Vec3
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * One of the Hershey fonts: single-stroke vector lettering from 1967, drawn as a pen plotter draws
 * it, stroke by stroke.
 *
 * ```kotlin
 * lines(HersheyFont.load("scripts").text("a learned pen", size = 24.0))
 * ```
 *
 * The fonts were created by Dr. A. V. Hershey at the U. S. National Bureau of Standards; the data
 * format is James Hurt's (Cognition, Inc.). The acknowledgement travels with the data in
 * `afield/pen/hershey/HERSHEY-LICENSE`.
 */
class HersheyFont private constructor(val name: String, private val glyphs: List<Glyph>) {

    private class Glyph(val left: Int, val right: Int, val strokes: List<List<IntArray>>)

    /** The strokes of [c] in font units (y down, about 32 units from ascender to descender), or null. */
    fun strokes(c: Char): List<List<IntArray>>? = glyphs.getOrNull(c.code - 32)?.strokes

    /**
     * [text] as one [FLine], a subpath per stroke, starting at [at]. [size] is the height of a
     * capital in drawing units.
     */
    fun text(text: String, size: Double = 24.0, at: Vec3 = Vec3(0f, 0f, 0f)): FLine {
        val k = size / 21.0
        val out = FLine()
        var pen = at.x.toDouble()
        for (c in text) {
            val g = glyphs.getOrNull(c.code - 32) ?: continue
            val ox = pen - g.left * k
            for (stroke in g.strokes) {
                stroke.forEachIndexed { i, p ->
                    val x = ox + p[0] * k
                    val y = at.y + p[1] * k
                    if (i == 0) out.moveTo(x, y) else out.lineTo(x, y)
                }
            }
            pen += (g.right - g.left) * k
        }
        return out
    }

    companion object {
        /** The fonts that ship with Field. */
        val names = listOf("futural", "rowmans", "scripts", "scriptc", "cursive", "timesr", "timesi", "gothiceng", "greek")

        private val cache = ConcurrentHashMap<String, HersheyFont>()

        /** Load one of [names]. */
        fun load(name: String): HersheyFont = cache.getOrPut(name) {
            val text = HersheyFont::class.java.getResourceAsStream("/afield/pen/hershey/$name.jhf")
                ?.bufferedReader()?.readText() ?: error("no Hershey font called \"$name\"; try one of $names")
            HersheyFont(name, text.lines().filter { it.length >= 10 }.map(::parseGlyph))
        }

        // A .jhf line: 5 characters of id, 3 of vertex count, then one pair of letters per vertex,
        // each coordinate offset from 'R'. The first pair is the glyph's left and right; " R" lifts.
        private fun parseGlyph(line: String): Glyph {
            val pairs = (8 until line.length - 1 step 2).map { line[it] to line[it + 1] }
            val strokes = ArrayList<List<IntArray>>()
            var current = ArrayList<IntArray>()
            for ((a, b) in pairs.drop(1)) {
                if (a == ' ' && b == 'R') {
                    if (current.isNotEmpty()) strokes += current
                    current = ArrayList()
                } else current += intArrayOf(a - 'R', b - 'R')
            }
            if (current.isNotEmpty()) strokes += current
            return Glyph(pairs[0].first - 'R', pairs[0].second - 'R', strokes)
        }
    }
}

/**
 * Generators of ordered drawings for training a pen. Each takes a seed and returns one drawing
 * at a size that suits the default scale of 8 drawing units per model unit.
 */
object PenDrawings {

    private val vowels = "aeiou"
    private val consonants = "bcdfghklmnprstvwy"

    /** A made-up word of [length] letters, alternating consonants and vowels often enough to look like one. */
    fun word(random: Random, length: Int = random.nextInt(2, 8)): String = buildString {
        repeat(length) { i ->
            val vowel = if (i % 2 == 1) random.nextDouble() < 0.8 else random.nextDouble() < 0.25
            append(if (vowel) vowels.random(random) else consonants.random(random))
        }
    }

    /** A line of made-up words in a Hershey font: [words] words, capitals [size] units tall. */
    fun hershey(seed: Long, font: String = "scripts", words: Int = 5, size: Double = 22.0): FLine {
        val r = Random(seed)
        val text = (0 until words).joinToString(" ") { word(r) }.replaceFirstChar { if (r.nextBoolean()) it.uppercaseChar() else it }
        return HersheyFont.load(font).text(text, size)
    }

    /**
     * A harmonograph: two damped pendulums per axis, summed, drawn as one long decaying stroke.
     * The frequencies sit near small whole-number ratios, which is what makes the figure close up.
     */
    fun harmonograph(seed: Long, radius: Double = 70.0, turns: Double = 14.0): FLine {
        val r = Random(seed)
        val ratios = listOf(1.0 to 2.0, 2.0 to 3.0, 3.0 to 4.0, 1.0 to 3.0, 3.0 to 5.0)
        val (fa, fb) = ratios[r.nextInt(ratios.size)]
        val detune = { 1 + (r.nextDouble() - 0.5) * 0.02 }
        val f = listOf(fa * detune(), fb * detune(), fb * detune(), fa * detune())
        val phase = DoubleArray(4) { r.nextDouble() * 2 * PI }
        val damp = 0.04 + r.nextDouble() * 0.05
        val out = FLine()
        val n = (turns * 60).toInt()
        for (i in 0..n) {
            val t = i * 2 * PI / 60
            val e = exp(-damp * t)
            val x = radius * 0.5 * e * (sin(f[0] * t + phase[0]) + sin(f[1] * t + phase[1]))
            val y = radius * 0.5 * e * (sin(f[2] * t + phase[2]) + sin(f[3] * t + phase[3]))
            if (i == 0) out.moveTo(x, y) else out.lineTo(x, y)
        }
        return out
    }

    /**
     * A branching plant from an L-system, drawn by a turtle in one unbroken stroke: each branch is
     * drawn out to its tip and back down to its fork, the way a hand draws a twig.
     *
     * Lifting the pen at a tip and jumping back to the fork is the obvious order, and a pen cannot
     * learn it: it sees only displacements, so the jump means remembering exactly where the fork
     * was, and a pen trained that way climbs forever and never branches.
     */
    fun plant(seed: Long, generations: Int = 4, height: Double = 140.0): FLine {
        val r = Random(seed)
        var s = "X"
        repeat(generations) {
            s = buildString {
                for (c in s) append(when (c) {
                    'X' -> if (r.nextBoolean()) "F[+X]F[-X]+X" else "F[-X][+X]FX"
                    'F' -> "FF"
                    else -> c.toString()
                })
            }
        }
        val step = height / (1 shl generations) / 2.2
        val turn = (20.0 + r.nextDouble() * 12.0) * PI / 180
        val out = FLine().moveTo(0, 0)
        var x = 0.0
        var y = 0.0
        var a = -PI / 2
        // each open branch keeps the points drawn since its fork, to walk back down them at ']'
        val stack = ArrayDeque<Pair<Double, MutableList<Pair<Double, Double>>>>()
        val path = ArrayDeque<MutableList<Pair<Double, Double>>>().apply { addLast(ArrayList()) }
        for (c in s) when (c) {
            'F' -> {
                val jitter = 1 + (r.nextDouble() - 0.5) * 0.3
                path.last() += x to y
                x += cos(a) * step * jitter; y += sin(a) * step * jitter
                out.lineTo(x, y)
            }
            '+' -> a += turn * (0.8 + r.nextDouble() * 0.4)
            '-' -> a -= turn * (0.8 + r.nextDouble() * 0.4)
            '[' -> { stack.addLast(a to ArrayList()); path.addLast(ArrayList()) }
            ']' -> {
                val back = path.removeLast()
                for ((px, py) in back.asReversed()) out.lineTo(px, py)
                if (back.isNotEmpty()) { x = back[0].first; y = back[0].second }
                a = stack.removeLast().first
            }
        }
        return out
    }

    /**
     * Truchet tiles: a grid of squares, each holding two quarter-circle arcs one way or the other,
     * whose arcs join into long winding curves. Each curve is one stroke, traced end to end.
     */
    fun truchet(seed: Long, columns: Int = 9, rows: Int = 5, cell: Double = 18.0): FLine {
        val r = Random(seed)
        val tile = Array(rows) { BooleanArray(columns) { r.nextBoolean() } }
        // Edge midpoints: side 0 top, 1 right, 2 bottom, 3 left. A tile joins top-left and
        // bottom-right (false) or top-right and bottom-left (true).
        fun partner(flip: Boolean, side: Int) = if (!flip) intArrayOf(3, 2, 1, 0)[side] else intArrayOf(1, 0, 3, 2)[side]
        val used = HashSet<Triple<Int, Int, Int>>()
        val out = FLine()
        fun mid(cx: Int, cy: Int, side: Int): Pair<Double, Double> = when (side) {
            0 -> (cx + 0.5) * cell to cy * cell
            1 -> (cx + 1.0) * cell to (cy + 0.5) * cell
            2 -> (cx + 0.5) * cell to (cy + 1.0) * cell
            else -> cx * cell to (cy + 0.5) * cell
        }
        fun corner(side: Int, other: Int): Pair<Int, Int> {
            val s = setOf(side, other)
            return (if (1 in s) 1 else 0) to (if (2 in s) 1 else 0)
        }
        for (sy in 0 until rows) for (sx in 0 until columns) for (startSide in 0..3) {
            if (Triple(sx, sy, startSide) in used) continue
            var cx = sx
            var cy = sy
            var side = startSide
            var first = true
            while (cx in 0 until columns && cy in 0 until rows && Triple(cx, cy, side) !in used) {
                val other = partner(tile[cy][cx], side)
                used += Triple(cx, cy, side); used += Triple(cx, cy, other)
                val (ccx, ccy) = corner(side, other)
                val ox = (cx + ccx) * cell
                val oy = (cy + ccy) * cell
                val (ax, ay) = mid(cx, cy, side)
                val (bx, by) = mid(cx, cy, other)
                val a0 = atan2(ay - oy, ax - ox)
                var a1 = atan2(by - oy, bx - ox)
                while (a1 - a0 > PI) a1 -= 2 * PI
                while (a0 - a1 > PI) a1 += 2 * PI
                for (k in 0..6) {
                    val t = a0 + (a1 - a0) * k / 6
                    val x = ox + cos(t) * cell / 2
                    val y = oy + sin(t) * cell / 2
                    if (first) { out.moveTo(x, y); first = false } else if (k > 0) out.lineTo(x, y)
                }
                when (other) {
                    0 -> { cy -= 1; side = 2 }
                    1 -> { cx += 1; side = 3 }
                    2 -> { cy += 1; side = 0 }
                    else -> { cx -= 1; side = 1 }
                }
            }
        }
        return out
    }

    /**
     * Flocking trails: a dozen boids that steer towards each other, align and keep apart, wandering
     * a box. Each boid's path is one stroke, drawn one boid after another.
     */
    fun boids(seed: Long, count: Int = 10, steps: Int = 160, size: Double = 160.0): FLine {
        val r = Random(seed)
        val px = DoubleArray(count) { r.nextDouble() * size }
        val py = DoubleArray(count) { r.nextDouble() * size * 0.6 }
        val vx = DoubleArray(count) { r.nextDouble() - 0.5 }
        val vy = DoubleArray(count) { r.nextDouble() - 0.5 }
        val trails = Array(count) { ArrayList<Pair<Double, Double>>() }
        repeat(steps) {
            for (i in 0 until count) {
                var cx = 0.0; var cy = 0.0; var ax = 0.0; var ay = 0.0; var sx = 0.0; var sy = 0.0
                for (j in 0 until count) if (j != i) {
                    cx += px[j]; cy += py[j]; ax += vx[j]; ay += vy[j]
                    val d = hypot(px[j] - px[i], py[j] - py[i])
                    if (d < 22) { sx -= (px[j] - px[i]) / max(d, 1.0); sy -= (py[j] - py[i]) / max(d, 1.0) }
                }
                val n = count - 1.0
                vx[i] += (cx / n - px[i]) * 0.0008 + (ax / n - vx[i]) * 0.05 + sx * 0.08 + (r.nextDouble() - 0.5) * 0.15
                vy[i] += (cy / n - py[i]) * 0.0008 + (ay / n - vy[i]) * 0.05 + sy * 0.08 + (r.nextDouble() - 0.5) * 0.15
                if (px[i] < 0) vx[i] += 0.1
                if (px[i] > size) vx[i] -= 0.1
                if (py[i] < 0) vy[i] += 0.1
                if (py[i] > size * 0.6) vy[i] -= 0.1
                val speed = hypot(vx[i], vy[i])
                if (speed > 2.5) { vx[i] *= 2.5 / speed; vy[i] *= 2.5 / speed }
            }
            for (i in 0 until count) { px[i] += vx[i]; py[i] += vy[i]; trails[i] += px[i] to py[i] }
        }
        val out = FLine()
        for (t in trails) t.forEachIndexed { k, (x, y) -> if (k == 0) out.moveTo(x, y) else out.lineTo(x, y) }
        return out
    }

    /**
     * A line in two hands: words that are either chains of small loops (hand 0) or sharp zigzags
     * (hand 1), the hand changing every word or two. [hands] gets the hand of each word, one entry
     * per subpath of the result, which is what a probe needs to find the cell that tracks it.
     */
    fun twoHands(seed: Long, words: Int = 6, hands: MutableList<Int>? = null): FLine {
        val r = Random(seed)
        val out = FLine()
        var x = 0.0
        var hand = r.nextInt(2)
        repeat(words) {
            if (r.nextDouble() < 0.6) hand = 1 - hand
            val strokes = r.nextInt(3, 8)
            val h = 10.0 + r.nextDouble() * 4
            out.moveTo(x, 0.0)
            if (hand == 0) {
                // loops: a prolate cycloid, travelling right
                val n = strokes * 16
                for (i in 1..n) {
                    val t = i * 2 * PI / 16
                    out.lineTo(x + t * 2.2 - sin(t) * 6, -h / 2 + cos(t) * h / 2)
                }
                x += strokes * 2 * PI * 2.2
            } else {
                for (i in 1..strokes * 2) {
                    val px = x + i * 4.5
                    out.lineTo(px, if (i % 2 == 1) -h else 0.0)
                }
                x += strokes * 2 * 4.5
            }
            hands?.add(hand)
            x += 14 + r.nextDouble() * 8
        }
        return out
    }
}

/**
 * A corpus of drawings for `python/fieldpen`: one JSON file per drawing, split into `train/` and
 * `valid/` under a folder.
 *
 * ```kotlin
 * PenCorpus.write("/tmp/plants", (0 until 400).map { PenDrawings.plant(it.toLong()) })
 * ```
 *
 * then `python -m fieldpen.prepare /tmp/plants/train train.npz --scale 8` and so on (the *Teaching a
 * pen* docs page has the whole run). Each subpath is resampled every [step] model units at [scale],
 * the spacing [Pen.feed] uses, so pass the same scale to `prepare`.
 */
object PenCorpus {
    /**
     * Write [drawings] under [folder], every [validEvery]th one into `valid/`. Returns the number of
     * points written.
     */
    fun write(folder: String, drawings: List<FLine>, scale: Double = 8.0, step: Double = 0.35, validEvery: Int = 10): Int {
        val root = File(folder)
        File(root, "train").mkdirs(); File(root, "valid").mkdirs()
        var points = 0
        drawings.forEachIndexed { i, d ->
            val strokes = strokes(d, scale, step)
            points += strokes.sumOf { it.size }
            val json = strokes.joinToString(",", "{\"strokes\":[", "]}") { s ->
                s.joinToString(",", "[", "]") { "[${"%.3f".format(it.x)},${"%.3f".format(it.y)}]" }
            }
            File(root, if (i % validEvery == validEvery - 1) "valid" else "train").resolve("d%05d.json".format(i)).writeText(json)
        }
        return points
    }

    /** [line]'s subpaths resampled every [step] model units: what the pen is shown. */
    fun strokes(line: FLine, scale: Double = 8.0, step: Double = 0.35): List<List<Vec3>> =
        line.pieces().map { resampleForPen(it, step * scale) }.filter { it.size >= 2 }
}

/**
 * Mean absolute turn per unit length along [line]'s marks, a rough measure of how curly it is,
 * taken after resampling the way a pen reads a line ([PenCorpus.strokes]). Measured on the raw
 * points instead, it mostly counts the small jitter between close points, and choosing by it picks
 * the shakiest lines rather than the curliest. Marks of fewer than [shortest] resampled points are
 * left out, so a line of dots does not count as curly either.
 */
fun curliness(line: FLine, scale: Double = 8.0, step: Double = 0.35, shortest: Int = 6): Double {
    var turn = 0.0
    var length = 0.0
    PenCorpus.strokes(line, scale, step).forEach { p ->
        if (p.size < shortest) return@forEach
        for (i in 1 until p.size - 1) {
            val a = atan2((p[i].y - p[i - 1].y).toDouble(), (p[i].x - p[i - 1].x).toDouble())
            val b = atan2((p[i + 1].y - p[i].y).toDouble(), (p[i + 1].x - p[i].x).toDouble())
            var d = b - a
            while (d > PI) d -= 2 * PI
            while (d < -PI) d += 2 * PI
            turn += abs(d)
            length += p[i].distance(p[i - 1])
        }
    }
    return if (length == 0.0) 0.0 else turn / length
}

/**
 * Which memory cells track a label: show [model] each of [drawings] (a fresh pen per drawing), and
 * correlate every cell's output with the label of the subpath being drawn, `labels[d][s]` for
 * subpath `s` of drawing `d`. Returns every cell with its correlation, strongest (positive or
 * negative) first.
 *
 * This is how to find the cell that knows which hand it is writing in, for drawings made by
 * [PenDrawings.twoHands]: it does not look for meaning, only for agreement with a label you supply.
 */
fun PenModel.probe(drawings: List<FLine>, labels: List<List<Int>>, scale: Double = 8.0, step: Double = 0.35): List<Pair<Int, Double>> {
    val n = hidden
    val sum = DoubleArray(n)
    val sumSq = DoubleArray(n)
    val sumXY = DoubleArray(n)
    var count = 0
    var sumY = 0.0
    var sumYY = 0.0
    drawings.forEachIndexed { d, drawing ->
        val pen = pen(d.toLong())
        var last: Vec3? = null
        PenCorpus.strokes(drawing, scale, step).forEachIndexed { s, points ->
            val y = labels[d][s].toDouble()
            points.forEachIndexed { i, p ->
                val l = last
                val lift = i == points.size - 1
                if (l == null) pen.feed(0.0, 0.0, lift) else pen.feed((p.x - l.x) / scale, (p.y - l.y) / scale, lift)
                last = p
                val h = pen.hidden()
                for (c in 0 until n) { sum[c] += h[c]; sumSq[c] += h[c] * h[c]; sumXY[c] += h[c] * y }
                sumY += y; sumYY += y * y; count++
            }
        }
    }
    val my = sumY / count
    val vy = sumYY / count - my * my
    return (0 until n).map { c ->
        val mx = sum[c] / count
        val vx = sumSq[c] / count - mx * mx
        val cov = sumXY[c] / count - mx * my
        c to if (vx > 1e-12 && vy > 1e-12) cov / kotlin.math.sqrt(vx * vy) else 0.0
    }.sortedByDescending { abs(it.second) }
}

/**
 * Writes the corpora Field's bundled synthetic pens were trained on:
 * `run-jvm-main afield.ai.learning.pen.PenCorpora --arg <folder>` makes one sub-folder per corpus,
 * which `tools/train_pen_corpora.py` then trains and exports.
 */
object PenCorpora {
    /** Every corpus by name: the drawings to write. */
    fun all(): Map<String, () -> List<FLine>> = linkedMapOf(
        "hershey" to { (0 until 900).map { PenDrawings.hershey(it.toLong(), HersheyFont.names[it % HersheyFont.names.size]) } },
        "harmonograph" to { (0 until 300).map { PenDrawings.harmonograph(it.toLong()) } },
        "plants" to { (0 until 300).map { PenDrawings.plant(it.toLong()) } },
        "truchet" to { (0 until 300).map { PenDrawings.truchet(it.toLong()) } },
        "boids" to { (0 until 200).map { PenDrawings.boids(it.toLong()) } },
        "mixed" to { (0 until 600).map { PenDrawings.twoHands(it.toLong()) } },
        "rounder" to { rounder() },
    )

    /**
     * The handwriting pen's own output, kept only where it is curliest: 2400 short unprimed
     * scribbles of 120 points, the top eighth by [curliness]. Fine-tuning on it is a pen learning a
     * taste from its own work.
     */
    fun rounder(candidates: Int = 2400, keep: Int = 300): List<FLine> =
        (0 until candidates).map { seed ->
            PenLine(seed = seed.toLong()).also { it.grow(120) }.drawn
        }.sortedByDescending { curliness(it) }.take(keep)

    @JvmStatic
    fun main(args: Array<String>) {
        val root = args.firstOrNull() ?: "build/pen-corpora"
        val only = args.drop(1).toSet()
        for ((name, make) in all()) {
            if (only.isNotEmpty() && name !in only) continue
            val drawings = make()
            val points = PenCorpus.write("$root/$name", drawings)
            println("$name: ${drawings.size} drawings, $points points -> $root/$name")
        }
    }
}
