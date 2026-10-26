/**
 * A learned pen: a recurrent network that has watched people write and draws the next point.
 *
 * [PenModel] is a one-layer (or deeper) LSTM whose output is a mixture of 2D Gaussians for the next
 * pen displacement plus a probability that the pen lifts. [PenModel.distill] is the handwriting model
 * from Carter, Ha, Johnson and Olah, *Experiments in Handwriting with a Neural Network* (Distill,
 * 2016), bundled with Field. [PenModel.load] reads a model retrained or fine-tuned with the optional
 * Python package `python/fieldpen` and exported with `fieldpen.export_for_field`.
 *
 * The everyday call is [continueWith]: hand it an [FLine] and get back the next few hundred points
 * the pen would have drawn. [Pen] is the state underneath, for when you want to feed, step, fork
 * or edit the pen yourself. Tests: `PenModelTest`.
 */

package afield.ai.learning.pen

import afield.geometry.path.FLine
import afield.math.linalg.Vec3
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.SplittableRandom
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Anything that can continue a stroke one displacement at a time.
 *
 * [PenModel] is the one Field ships. Implement this to put a different model behind [continueWith]
 * and [PenLine]: they only feed it displacements and ask it for the next one.
 */
interface StrokeModel {
    /** A fresh pen that has seen nothing, sampling with a generator seeded by [seed]. */
    fun pen(seed: Long = 0): StrokePen
}

/** The running state of a [StrokeModel]: everything it has drawn or been shown so far. */
interface StrokePen {
    /** Show the pen a displacement in model units; [lift] means the pen comes up *after* this point. */
    fun feed(dx: Double, dy: Double, lift: Boolean)

    /** Sample the next displacement and feed it back, as though the pen had drawn it. */
    fun next(controls: PenControls = PenControls()): PenStep

    /** An independent copy: the same memory, its own random generator seeded by [seed]. */
    fun fork(seed: Long): StrokePen
}

/** One step of a pen, in model units: move by ([dx], [dy]), then lift if [lift]. */
data class PenStep(val dx: Double, val dy: Double, val lift: Boolean)

/**
 * How a pen samples its next step.
 *
 * @param temperature sharpens (below 1) or flattens (above 1) the choice between the mixture's
 *   components: low values keep the pen to its most likely habit, high ones let it change its mind.
 * @param noise multiplies the spread of the chosen component. 0 draws the component's mean, which is
 *   smooth and repetitive; 1 is the spread the model learned. The Distill article used one slider for
 *   both of these.
 * @param liftBias added to the pen-lift logit: positive lifts the pen more often, negative keeps it
 *   on the paper.
 * @param driftX added to every sampled step, in model units, to push the writing along.
 * @param driftY the same for y.
 */
data class PenControls(
    val temperature: Double = 0.65,
    val noise: Double = 0.65,
    val liftBias: Double = 0.0,
    val driftX: Double = 0.0,
    val driftY: Double = 0.0,
)

/**
 * The weights of an LSTM pen model, shared by every [Pen] made from it.
 *
 * Layout and gate order follow `torch.nn.LSTM` (input, forget, cell, output), so a model trained in
 * `python/fieldpen` loads unchanged. Inputs are `(dx, dy, lift)`; the head produces `1 + 6 * mixtures`
 * numbers: the lift logit, then the mixture logits, x means, y means, x log-sigmas, y log-sigmas and
 * correlations.
 */
class PenModel internal constructor(
    val hidden: Int,
    val mixtures: Int,
    val layers: Int,
    /** Per layer, `[4 * hidden][inputs + hidden]`, row-major: the input weights then the recurrent ones. */
    private val gates: List<FloatArray>,
    /** Per layer, `4 * hidden` biases (torch's two bias vectors summed). */
    private val gateBias: List<FloatArray>,
    private val headWeight: FloatArray,
    private val headBias: FloatArray,
) : StrokeModel {

    private val outputs = 1 + 6 * mixtures

    override fun pen(seed: Long): Pen = Pen(this, seed)

    /** A pen that has already been shown [primer] (see [Pen.feed] for [scale] and [step]). */
    fun pen(primer: FLine, scale: Double = 8.0, step: Double = 0.35, seed: Long = 0): Pen =
        pen(seed).also { it.feed(primer, scale, step) }

    internal fun inputs(layer: Int) = if (layer == 0) 3 else hidden

    /** One LSTM step for every layer, in place on [h] and [c]; writes the head's output into [raw]. */
    internal fun step(x: FloatArray, h: Array<FloatArray>, c: Array<FloatArray>, raw: FloatArray, scratch: FloatArray) {
        var input = x
        for (l in 0 until layers) {
            val n = inputs(l)
            val w = gates[l]
            val b = gateBias[l]
            val width = n + hidden
            val hl = h[l]
            for (r in 0 until 4 * hidden) {
                var s = b[r]
                val o = r * width
                for (i in 0 until n) s += w[o + i] * input[i]
                val o2 = o + n
                for (i in 0 until hidden) s += w[o2 + i] * hl[i]
                scratch[r] = s
            }
            val cl = c[l]
            for (i in 0 until hidden) {
                val ig = sigmoid(scratch[i])
                val fg = sigmoid(scratch[hidden + i])
                val gg = tanh(scratch[2 * hidden + i])
                val og = sigmoid(scratch[3 * hidden + i])
                cl[i] = fg * cl[i] + ig * gg
                hl[i] = og * tanh(cl[i])
            }
            input = hl
        }
        val top = h[layers - 1]
        for (r in 0 until outputs) {
            var s = headBias[r]
            val o = r * hidden
            for (i in 0 until hidden) s += headWeight[o + i] * top[i]
            raw[r] = s
        }
    }

    internal fun newRaw() = FloatArray(outputs)
    internal fun newScratch() = FloatArray(4 * hidden)

    companion object {
        /**
         * The Distill handwriting model: 500 cells, 20 mixture components, trained on the IAM On-Line
         * Handwriting Database. Loaded once, on first use, from Field's resources.
         */
        val distill: PenModel by lazy {
            val text = PenModel::class.java.getResourceAsStream("/afield/pen/distill-b64.json")
                ?.bufferedReader()?.readText()
                ?: error("the bundled Distill weights (afield/pen/distill-b64.json) are missing from the classpath")
            fromDistill(Json.parseToJsonElement(text))
        }

        /**
         * Read a model: a `.fieldpen.json` written by `fieldpen.export_for_field`, or a file in the
         * Distill article's own quantized format.
         */
        fun load(path: String): PenModel {
            val json = Json.parseToJsonElement(File(path).readText())
            return if (json is JsonObject) fromFieldPen(json) else fromDistill(json)
        }

        /** Distill's format: five base64 uint8 tensors, TensorFlow gate order (i, g, f, o), forget bias + 1. */
        internal fun fromDistill(json: JsonElement): PenModel {
            val scales = doubleArrayOf(1.5, 2.5, 2.0, 0.8, 1.5)
            val parts = json.jsonArray.mapIndexed { k, e -> decodeQuantized(e, scales[k]) }
            val (outW, outB, wx, wh, bias) = parts
            val hidden = wh.size
            val outputs = outB[0].size
            val mixtures = (outputs - 1) / 6
            // TensorFlow's block j holds torch's gate order[j]
            val tfBlockOf = intArrayOf(0, 2, 1, 3)
            val width = 3 + hidden
            val gates = FloatArray(4 * hidden * width)
            val b = FloatArray(4 * hidden)
            for (g in 0 until 4) for (i in 0 until hidden) {
                val tfCol = tfBlockOf[g] * hidden + i
                val r = g * hidden + i
                for (k in 0 until 3) gates[r * width + k] = wx[k][tfCol]
                for (k in 0 until hidden) gates[r * width + 3 + k] = wh[k][tfCol]
                b[r] = bias[0][tfCol] + if (g == 1) 1f else 0f
            }
            val headW = FloatArray(outputs * hidden)
            for (r in 0 until outputs) for (k in 0 until hidden) headW[r * hidden + k] = outW[k][r]
            return PenModel(hidden, mixtures, 1, listOf(gates), listOf(b), headW, outB[0])
        }

        private fun decodeQuantized(e: JsonElement, scale: Double): List<FloatArray> {
            val rows = if (e is JsonArray) e.map { it.jsonPrimitive.content } else listOf(e.jsonPrimitive.content)
            return rows.map { row ->
                val bytes = Base64.getDecoder().decode(row)
                FloatArray(bytes.size) { (((bytes[it].toInt() and 0xff) - 127) / 127.0 * scale).toFloat() }
            }
        }

        /** `fieldpen.export_for_field`: a torch state dict as little-endian float32 base64. */
        internal fun fromFieldPen(json: JsonObject): PenModel {
            require(json["format"]?.jsonPrimitive?.content == "fieldpen") { "not a .fieldpen.json model" }
            val config = json["config"]!!.jsonObject
            val hidden = config["hidden"]!!.jsonPrimitive.int
            val mixtures = config["mixtures"]!!.jsonPrimitive.int
            val layers = config["layers"]!!.jsonPrimitive.int
            val tensors = json["tensors"]!!.jsonObject
            fun tensor(name: String): FloatArray {
                val t = tensors[name]?.jsonObject ?: error("model has no tensor $name")
                val buf = ByteBuffer.wrap(Base64.getDecoder().decode(t["data"]!!.jsonPrimitive.content))
                    .order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                return FloatArray(buf.remaining()).also { buf.get(it) }
            }
            val gates = ArrayList<FloatArray>()
            val biases = ArrayList<FloatArray>()
            for (l in 0 until layers) {
                val n = if (l == 0) 3 else hidden
                val wih = tensor("rnn.weight_ih_l$l")
                val whh = tensor("rnn.weight_hh_l$l")
                val width = n + hidden
                val w = FloatArray(4 * hidden * width)
                for (r in 0 until 4 * hidden) {
                    System.arraycopy(wih, r * n, w, r * width, n)
                    System.arraycopy(whh, r * hidden, w, r * width + n, hidden)
                }
                val bi = tensor("rnn.bias_ih_l$l")
                val bh = tensor("rnn.bias_hh_l$l")
                gates += w
                biases += FloatArray(4 * hidden) { bi[it] + bh[it] }
            }
            return PenModel(hidden, mixtures, layers, gates, biases, tensor("head.weight"), tensor("head.bias"))
        }
    }
}

/**
 * The state of one [PenModel]: its memory, where it is, whether its pen is up, and its random generator.
 *
 * A new pen has seen only the start-of-sequence step. [feed] shows it displacements (or a whole
 * [FLine]); [next] samples one and feeds it back. Positions are in model units, where one handwritten
 * step is around a quarter of a unit; [PenLine] and [continueWith] convert to and from drawing units.
 */
class Pen internal constructor(val model: PenModel, seed: Long) : StrokePen {
    private var random = SplittableRandom(seed)
    private val h = Array(model.layers) { FloatArray(model.hidden) }
    private val c = Array(model.layers) { FloatArray(model.hidden) }
    private val raw = model.newRaw()
    private val scratch = model.newScratch()
    private val x = FloatArray(3)

    /** Pen position in model units, the sum of every displacement fed so far. */
    var x0 = 0.0
        private set

    /** The y of [x0]. */
    var y0 = 0.0
        private set

    /** Whether the pen is off the paper, so the next step is a move rather than a mark. */
    var penUp = true
        private set

    /** Steps fed or drawn, not counting the start-of-sequence step. */
    var count = 0
        private set

    init {
        model.step(x, h, c, raw, scratch)
    }

    override fun feed(dx: Double, dy: Double, lift: Boolean) {
        x[0] = dx.toFloat(); x[1] = dy.toFloat(); x[2] = if (lift) 1f else 0f
        model.step(x, h, c, raw, scratch)
        x0 += dx; y0 += dy
        penUp = lift
        count++
    }

    /**
     * Show the pen [line], so that what it draws next continues it.
     *
     * Each subpath is resampled every [step] model units; [scale] is how many of [line]'s units make
     * one model unit (8 suits a line drawn in canvas pixels at handwriting size). The pen lifts at
     * the end of every subpath except the last, which it is left touching, so [next] continues that
     * stroke. Pass `liftAtEnd = true` to have it start somewhere new instead. A fresh pen treats the
     * line's first point as its origin; a pen that has already drawn moves there from where it is.
     */
    fun feed(line: FLine, scale: Double = 8.0, step: Double = 0.35, liftAtEnd: Boolean = false) {
        // a pen that has drawn is at (x0, y0) model units, which is (x0, y0) * scale in line units
        feedLine(this, line, scale, step, liftAtEnd,
            if (count == 0) null else Vec3((x0 * scale).toFloat(), (y0 * scale).toFloat(), 0f))
    }

    override fun next(controls: PenControls): PenStep {
        val k = model.mixtures
        // pick a component
        val logits = DoubleArray(k) { raw[1 + it] / controls.temperature }
        val top = logits.max()
        var total = 0.0
        for (i in 0 until k) { logits[i] = exp(logits[i] - top); total += logits[i] }
        var u = random.nextDouble() * total
        var j = 0
        while (j < k - 1 && u >= logits[j]) { u -= logits[j]; j++ }
        val mx = raw[1 + k + j].toDouble()
        val my = raw[1 + 2 * k + j].toDouble()
        val sx = exp(raw[1 + 3 * k + j].toDouble().coerceIn(-9.0, 7.0)) * controls.noise
        val sy = exp(raw[1 + 4 * k + j].toDouble().coerceIn(-9.0, 7.0)) * controls.noise
        val rho = tanh(raw[1 + 5 * k + j].toDouble()).coerceIn(-0.9999, 0.9999)
        val e0 = gaussian()
        val e1 = gaussian()
        val dx = mx + sx * e0 + controls.driftX
        val dy = my + sy * (rho * e0 + sqrt(1 - rho * rho) * e1) + controls.driftY
        val lift = random.nextDouble() < sigmoid(raw[0] + controls.liftBias)
        feed(dx, dy, lift)
        return PenStep(dx, dy, lift)
    }

    override fun fork(seed: Long): Pen {
        val other = Pen(model, seed)
        for (l in 0 until model.layers) {
            h[l].copyInto(other.h[l]); c[l].copyInto(other.c[l])
        }
        raw.copyInto(other.raw)
        other.x0 = x0; other.y0 = y0; other.penUp = penUp; other.count = count
        return other
    }

    /** The probability that the pen lifts after its next step, before any [PenControls.liftBias]. */
    val liftProbability: Double get() = sigmoid(raw[0].toDouble())

    /** A copy of layer [layer]'s cell memory (the LSTM's `c`), one value per cell. */
    fun cells(layer: Int = model.layers - 1): FloatArray = c[layer].copyOf()

    /** A copy of layer [layer]'s hidden output (the LSTM's `h`). */
    fun hidden(layer: Int = model.layers - 1): FloatArray = h[layer].copyOf()

    /**
     * Overwrite one memory cell, the Distill article's fourth experiment. The change shows up from
     * the step after next: the head has already read the current output.
     */
    fun setCell(cell: Int, value: Double, layer: Int = model.layers - 1) {
        c[layer][cell] = value.toFloat()
    }

    /** The head's raw output for the next step (see [PenModel] for its layout). */
    fun rawOutput(): FloatArray = raw.copyOf()

    private var spare = Double.NaN
    private fun gaussian(): Double {
        if (!spare.isNaN()) return spare.also { spare = Double.NaN }
        var a: Double
        var b: Double
        var s: Double
        do {
            a = random.nextDouble() * 2 - 1
            b = random.nextDouble() * 2 - 1
            s = a * a + b * b
        } while (s >= 1 || s == 0.0)
        val m = sqrt(-2 * ln(s) / s)
        spare = b * m
        return a * m
    }
}

/**
 * A line that a pen keeps drawing: the primer you gave it, then everything the pen added.
 *
 * ```kotlin
 * val writer = PenLine(fline { moveTo(0, 0); lineTo(20, -10); lineTo(30, 5) })
 * writer.grow(400)
 * lines(writer.line)
 * ```
 *
 * [line] grows in place, so a box that redraws it every frame watches the pen write.
 *
 * @param primer the line the pen is shown first and continues; `null` starts on an empty page.
 * @param scale drawing units per model unit, fixed when the PenLine is made.
 * @param step how often [primer] is resampled, in model units.
 * @param start where a PenLine with no primer starts, in drawing units.
 */
class PenLine(
    primer: FLine? = null,
    val model: StrokeModel = PenModel.distill,
    val scale: Double = 8.0,
    var controls: PenControls = PenControls(),
    seed: Long = 0,
    step: Double = 0.35,
    start: Vec3 = Vec3(0f, 0f, 0f),
) {
    /** The pen underneath, for [Pen.setCell] or forking by hand. */
    var pen: StrokePen = model.pen(seed)
        private set

    /** The primer followed by what has been drawn so far. */
    val line: FLine = FLine()

    /** Only the pen's own contribution, without the primer. */
    val drawn: FLine = FLine()

    private var x: Double
    private var y: Double
    private var up: Boolean

    init {
        val p = pen
        val end = primer?.currentPoint
        if (primer != null && end != null) {
            primer.nodes.forEach { line.nodes.add(it.copy()) }
            feedLine(p, primer, scale, step, false, null)
            x = end.x.toDouble(); y = end.y.toDouble(); up = false
        } else {
            x = start.x.toDouble(); y = start.y.toDouble(); up = true
        }
        drawn.moveTo(x, y)
    }

    /** Draw [steps] more points onto [line] (and [drawn]); returns [line]. */
    fun grow(steps: Int = 1): FLine {
        repeat(steps) {
            val s = pen.next(controls)
            x += s.dx * scale; y += s.dy * scale
            if (up || line.nodes.isEmpty()) { line.moveTo(x, y); drawn.moveTo(x, y) } else { line.lineTo(x, y); drawn.lineTo(x, y) }
            up = s.lift
        }
        return line
    }

    /**
     * Ask what the pen might do next without letting it do it: [count] independent continuations of
     * [steps] points each, from here. [line] and the pen are unchanged.
     */
    fun futures(count: Int = 16, steps: Int = 40, seed: Long = 1): List<FLine> = (0 until count).map { k ->
        val f = pen.fork(seed + k)
        var fx = x
        var fy = y
        var fup = up
        val out = FLine().moveTo(fx, fy)
        repeat(steps) {
            val s = f.next(controls)
            fx += s.dx * scale; fy += s.dy * scale
            if (fup) out.moveTo(fx, fy) else out.lineTo(fx, fy)
            fup = s.lift
        }
        out
    }

    /** Make the pen lift now, so the next point starts a new stroke. */
    fun lift() {
        pen.feed(0.0, 0.0, true)
        up = true
    }
}

/**
 * Continue this line with a learned pen: the next [steps] points it would draw, as a new [FLine]
 * that starts where this one ends.
 *
 * ```kotlin
 * val start = fline { moveTo(0, 0); cubicTo(10, -30, 30, -30, 30, 0); lineTo(40, 10) }
 * lines(start, start.continueWith(steps = 300))
 * ```
 *
 * The pen is shown the whole line first, resampled every [step] model units, then left touching
 * the paper at the end, so it carries on rather than starting a new mark. [scale] is how many of
 * this line's units make one model unit; 8 makes canvas-pixel handwriting a sensible size. Same
 * [seed], same continuation.
 */
fun FLine.continueWith(
    model: StrokeModel = PenModel.distill,
    steps: Int = 300,
    scale: Double = 8.0,
    controls: PenControls = PenControls(),
    seed: Long = 0,
    step: Double = 0.35,
): FLine {
    val writer = PenLine(this, model, scale, controls, seed, step)
    writer.grow(steps)
    return writer.drawn
}

/**
 * Several continuations of this line at once: [count] different futures of [steps] points, each a
 * fresh [FLine] from this line's end. Futures share the pen's reading of the line and differ only in
 * the random choices after it.
 */
fun FLine.futuresWith(
    model: StrokeModel = PenModel.distill,
    count: Int = 16,
    steps: Int = 60,
    scale: Double = 8.0,
    controls: PenControls = PenControls(),
    seed: Long = 1,
    step: Double = 0.35,
): List<FLine> = PenLine(this, model, scale, controls, 0, step).futures(count, steps, seed)

private fun resample(piece: FLine, spacing: Double): List<Vec3> {
    if (piece.nodes.isEmpty()) return emptyList()
    val pts = ArrayList(piece.sampleByDistance(max(spacing, 1e-6).toFloat()))
    val end = piece.currentPoint
    if (end != null && (pts.isEmpty() || pts.last().distance(end) > spacing * 0.25)) pts.add(end)
    return pts
}

/**
 * Feed [line] to [pen] as displacements. [from] is where the pen is, in [line]'s units; `null` is a
 * fresh pen, whose first point is a zero step (the same as a training sequence's first row).
 */
private fun feedLine(pen: StrokePen, line: FLine, scale: Double, step: Double, liftAtEnd: Boolean, from: Vec3?) {
    val pieces = line.pieces().map { resample(it, step * scale) }.filter { it.isNotEmpty() }
    var last = from
    pieces.forEachIndexed { k, pts ->
        pts.forEachIndexed { i, p ->
            val lift = i == pts.size - 1 && (k < pieces.size - 1 || liftAtEnd)
            val l = last
            if (l == null) pen.feed(0.0, 0.0, lift) else pen.feed((p.x - l.x) / scale, (p.y - l.y) / scale, lift)
            last = p
        }
    }
}

private fun sigmoid(v: Float): Float = (1.0 / (1.0 + exp(-v.toDouble()))).toFloat()
private fun sigmoid(v: Double): Double = 1.0 / (1.0 + exp(-v))
