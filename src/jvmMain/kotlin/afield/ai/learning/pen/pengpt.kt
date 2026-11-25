/**
 * PenGPT: a small transformer that holds a pen, after Sam Greydanus's *PenGPT* (2026,
 * https://github.com/greydanus/pengpt, Apache 2.0).
 *
 * Where [PenModel] predicts the next displacement from a mixture of Gaussians, PenGPT walks a grid in
 * eight directions and emits that walk the way a language model emits words: one token at a time, with
 * runs of common moves merged into single tokens (byte-pair encoding). It is a plain decoder that
 * cross-attends to what it is told. The handwriting model ([PenGpt.hand], 6M parameters) is told the text
 * and a writer, and writes that text in any of 1,192 hands. The drawing model ([PenGpt.sketch], 54M
 * parameters) is told a SigLIP 2 vector for a phrase ([SiglipText]) and draws it.
 *
 * Both models also continue what they are shown. A few words in one hand, given as a prefix, make every
 * word after them look like those ([PenGpt.writeLike]); a drawing begun by hand, given as a prefix, is
 * carried on ([PenGpt.continueDrawing]). [PenGpt.strokes] puts either model behind [StrokeModel], so
 * [PenLine] and [continueWith] drive it the way they drive the LSTM pens.
 *
 * The weights are not in Field: they download from the PenGPT release into `~/.afield/onnx/models/pengpt/`
 * the first time they are asked for (6 MB for handwriting, 50 MB for drawing, 375 MB more for the SigLIP
 * text tower that typed prompts need). This file is a port of the reference `inference/pen.py` of that
 * repository. Tests: `PenGptTest`.
 */

package afield.ai.learning.pen

import afield.ai.inference.onnx.OnnxModel
import afield.geometry.path.FLine
import afield.lang.python.NpyArray
import afield.lang.python.NpyKind
import afield.lang.python.NumpyIO
import afield.math.linalg.Vec3
import jdk.incubator.vector.FloatVector
import jdk.incubator.vector.VectorOperators
import jdk.incubator.vector.VectorSpecies
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.SplittableRandom
import java.util.stream.IntStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * What a [PenGpt] is told besides its prefix: a writer or source, and either text (handwriting) or a
 * phrase vector (drawing).
 *
 * @param text for the handwriting model, the words to write, separated by spaces, at most 50 bytes. For
 *   the drawing model, a phrase to draw, which [SiglipText] turns into a vector the first time it is used;
 *   for a drawing model trained on labels (format 1), one of those labels.
 * @param writer a writer (handwriting) or source (drawing) by name, from [PenGpt.writers]. `""` is the
 *   model's "anyone".
 * @param vector a SigLIP 2 vector to draw, instead of [text]; `null` uses [text].
 * @param style a writer vector of your own, instead of [writer]; `null` uses [writer].
 * @param ink for the drawing model, how much ink to ask for, in lengths of the drawing's longer side;
 *   `null` leaves it to the model.
 * @param strokes for the drawing model, how many strokes to ask for; `null` leaves it to the model.
 * @param context for the drawing model, rows to attend to in place of what [text] or [vector] would
 *   give, each as wide as the model: a prompt learned directly, as `tools/pengpt_fit_style.py
 *   --mode context` fits one. `null` uses [text] or [vector].
 */
data class PenGptPrompt(
    val text: String = "",
    val writer: String = "",
    val vector: FloatArray? = null,
    val style: FloatArray? = null,
    val ink: Double? = null,
    val strokes: Int? = null,
    val context: List<FloatArray>? = null,
)

/**
 * How a [PenGpt] picks each token.
 *
 * @param temperature 0 takes the most likely token every time, which for handwriting is the cleanest
 *   hand; higher values loosen it. Drawings want some, 0.6 by default.
 * @param topK keep only this many of the likeliest tokens before sampling; `null` keeps them all.
 * @param guidance for the drawing model, how hard a drawing is pushed towards its prompt (classifier-free
 *   guidance); 1 is not at all, `null` is the model's default of 2.
 */
data class PenGptSampling(
    val temperature: Double = 0.0,
    val topK: Int? = null,
    val guidance: Double? = null,
)

/**
 * A PenGPT model: weights, tokenizer and sampler. Load one with [PenGpt.hand], [PenGpt.sketch] or
 * [PenGpt.load].
 *
 * ```kotlin
 * val words = PenGpt.hand.write("The quick brown fox", writer = PenGpt.hand.writers[40])
 * lines(words)
 * ```
 */
class PenGpt private constructor(arrays: Map<String, NpyArray>) {

    /** Model-file format: 1 is told text bytes (handwriting, or labels to draw), 2 is told a phrase vector (drawing). */
    val format: Int

    /**
     * True for a model that writes words (handwriting), false for one that draws. A model file says
     * which with `"kind"` in its meta; one that does not is a handwriting model when told text bytes.
     */
    val writesText: Boolean

    /** True when the model is told text bytes (format 1) rather than a vector (format 2). */
    internal val toldText: Boolean get() = format == 1

    /**
     * The writers (handwriting) or sources (drawing) this model knows, by name. The first, `""`, is
     * "anyone".
     */
    val writers: List<String>

    /** The side of one grid cell, in model units: words have an x-height of about 0.12, drawings a longer side of 1. */
    val grid: Double

    /** The longest sequence of tokens the model reads and writes, prefix included. */
    val contextLength: Int

    /** The most bytes of text the model can be told at once. */
    val maxTextLength: Int

    internal val nLayer: Int
    internal val nHead: Int
    internal val width: Int
    internal val headDim: Int
    private val bands: Int
    private val penRotary: Boolean
    private val textEnd: Boolean
    private val vectorWidth: Int
    private val labelDrop: Double
    private val hint: Double

    internal val merges: List<IntArray>
    internal val pad: Int
    internal val end: Int
    internal val word: Int
    internal val bos: Int
    internal val vocab: Int

    /** For each token, the unit moves it stands for, as cumulative (x, y) cells. */
    internal val paths: Array<IntArray>

    /** For each token, its total move in cells: `(dx, dy)` at `2 * token`. */
    internal val deltas: IntArray

    private val tokEmb: Mat
    private val head: Mat
    private val writerEmb: Mat
    private val penPosProj: Mat?
    private val ctxEmb: Mat?
    private val ctxPosEmb: Mat?
    private val ctxProj: Mat?
    private val ctxSlot: FloatArray?
    private val hintProj: Mat?
    private val residScale: FloatArray
    private val x0Scale: FloatArray
    internal val layers: List<Layer>

    private val cosTable: FloatArray
    private val sinTable: FloatArray
    private val posFreq: FloatArray
    private val seqFreq: FloatArray
    private val penFreq: FloatArray

    internal class Layer(
        val q: Mat, val kv: Mat, val proj: Mat, val qNorm: FloatArray, val kNorm: FloatArray,
        val xq: Mat, val xkv: Mat, val xproj: Mat, val xqNorm: FloatArray, val xkNorm: FloatArray,
        val fc: Mat, val fcOut: Mat,
    )

    init {
        val meta = Json.parseToJsonElement(arrays.getValue("meta").asStrings().single()).jsonObject
        fun int(name: String, default: Int = 0) = meta[name]?.jsonPrimitive?.int ?: default
        fun double(name: String, default: Double = 0.0) = meta[name]?.jsonPrimitive?.double ?: default
        fun bool(name: String) = meta[name]?.jsonPrimitive?.boolean ?: false
        format = int("format", 1)
        require(format <= 2) { "PenGPT model format $format is newer than this reader (2)" }
        writesText = meta["kind"]?.jsonPrimitive?.content?.let { it == "hand" } ?: (format == 1)
        nLayer = int("n_layer"); nHead = int("n_head"); bands = int("bands")
        contextLength = min(int("max_seq_length"), int("block_size"))
        grid = double("grid"); maxTextLength = int("max_text_length")
        textEnd = bool("text_end"); penRotary = bool("pen_rotary")
        vectorWidth = int("vector"); labelDrop = double("label_drop"); hint = double("hint")
        writers = arrays.getValue("writers").asStrings()

        val m = arrays.getValue("merges").asLongArray()
        merges = List(m.size / 3) { intArrayOf(m[3 * it].toInt(), m[3 * it + 1].toInt(), m[3 * it + 2].toInt()) }
        val n = N_BASE + merges.size
        pad = n; end = n + 1; word = n + 2; bos = n + 3; vocab = n + 4

        val raw = Array(vocab) { t -> if (t < 8) intArrayOf(DIRECTIONS[2 * t], DIRECTIONS[2 * t + 1]) else IntArray(0) }
        for (mg in merges) raw[mg[2]] = raw[mg[0]] + raw[mg[1]]
        deltas = IntArray(2 * vocab)
        paths = Array(vocab) { t ->
            val r = raw[t]
            var x = 0; var y = 0
            IntArray(r.size) { i -> if (i % 2 == 0) { x += r[i]; x } else { y += r[i]; y } }.also {
                deltas[2 * t] = x; deltas[2 * t + 1] = y
            }
        }

        fun mat(name: String) = Mat.of(arrays, name)
        fun matOrNull(name: String) = if (arrays.containsKey(name)) mat(name) else null
        fun vec(name: String) = arrays.getValue(name).floats()
        tokEmb = mat("tok_emb.weight"); head = mat("head.weight"); writerEmb = mat("writer_emb.weight")
        width = tokEmb.cols; headDim = width / nHead
        penPosProj = if (bands > 0) mat("pen_pos_proj.weight") else null
        ctxEmb = matOrNull("ctx_emb.weight"); ctxPosEmb = matOrNull("ctx_pos_emb.weight")
        ctxProj = matOrNull("ctx_proj.weight"); hintProj = matOrNull("hint_proj.weight")
        ctxSlot = matOrNull("ctx_slot.weight")?.row(0)
        residScale = vec("resid_scale"); x0Scale = vec("x0_scale")
        layers = List(nLayer) { i ->
            fun w(k: String) = "blocks.$i.$k.weight"
            Layer(
                mat(w("attn.q")), mat(w("attn.kv")), mat(w("attn.proj")), vec(w("attn.q_norm")), vec(w("attn.k_norm")),
                mat(w("cross_attn.q")), mat(w("cross_attn.kv")), mat(w("cross_attn.proj")),
                vec(w("cross_attn.q_norm")), vec(w("cross_attn.k_norm")),
                mat(w("fc")), mat(w("fc_out")),
            )
        }

        val half = headDim / 2
        val block = int("block_size")
        cosTable = FloatArray(block * half); sinTable = FloatArray(block * half)
        for (t in 0 until block) for (j in 0 until half) {
            val a = (t * 10_000.0.pow(-(2.0 * j) / headDim)).toFloat()
            cosTable[t * half + j] = cos(a); sinTable[t * half + j] = sin(a)
        }
        posFreq = FloatArray(bands) { (PI / 2.0.pow(it + 1)).toFloat() }
        val n4 = headDim / 4
        val m2 = (half - n4) / 2
        seqFreq = FloatArray(n4) { 10_000.0.pow(-(it.toDouble() / n4)).toFloat() }
        penFreq = FloatArray(m2) { (2 * PI / (4.0 * 128.0.pow(it.toDouble() / max(m2 - 1, 1)))).toFloat() }
    }

    // --- tokens -------------------------------------------------------------------------------------

    /**
     * Turn ink into tokens, the way PenGPT's tokenizer does: every pen-down point snapped to the grid,
     * the walk between cells broken into the eight directions, pen-down and pen-up marked, then common
     * runs merged. [points] are `(x, y, down)` triples in model units, `down` 1 or 0, the way
     * [decode] gives them back; a stroke is a run of down points.
     */
    fun encode(points: List<DoubleArray>): IntArray = merge(encodeBase(points))

    internal fun encodeBase(points: List<DoubleArray>): IntArray {
        val out = ArrayList<Int>()
        var cx = 0L; var cy = 0L
        val downs = points.indices.filter { points[it][2] == 1.0 }
        // a point starts a stroke when the point before it was not down
        fun hop(i: Int) = i == 0 || points[i - 1][2] != 1.0
        for ((k, i) in downs.withIndex()) {
            val p = points[i]
            val hop = hop(i)
            val x = Math.rint(p[0] / grid).toLong(); val y = Math.rint(p[1] / grid).toLong()
            val dx = x - cx; val dy = y - cy
            cx = x; cy = y
            val long = max(abs(dx), abs(dy))
            val short = min(abs(dx), abs(dy))
            fun minor(j: Long) = -Math.floorDiv(long - 2 * j * short, 2 * long)
            for (j in 0 until long) {
                val ux = if (abs(dx) == long) sign(dx.toDouble()).toInt() else (sign(dx.toDouble()) * (minor(j + 1) - minor(j))).toInt()
                val uy = if (abs(dy) == long) sign(dy.toDouble()).toInt() else (sign(dy.toDouble()) * (minor(j + 1) - minor(j))).toInt()
                out += DIR_LOOKUP[(ux + 1) * 3 + (uy + 1)]
            }
            if (hop) out += DOWN
            if (k == downs.size - 1 || hop(downs[k + 1])) out += UP
        }
        check(out.none { it < 0 }) { "a move that is not one of the eight directions" }
        return out.toIntArray()
    }

    internal fun merge(tokens: IntArray): IntArray {
        var out = tokens
        for (mg in merges) {
            val a = mg[0]; val b = mg[1]
            var n = 0
            val next = IntArray(out.size)
            var i = 0
            while (i < out.size) {
                if (i + 1 < out.size && out[i] == a && out[i + 1] == b) { next[n++] = mg[2]; i += 2 } else next[n++] = out[i++]
            }
            out = next.copyOf(n)
        }
        return out
    }

    /**
     * Turn tokens back into `(x, y, down)` points in model units, smoothed as PenGPT smooths them (each
     * interior point of a stroke averaged with its neighbours). Tokens that are not moves are skipped.
     */
    fun decode(tokens: IntArray): List<DoubleArray> {
        val pts = ArrayList<DoubleArray>()
        var x = 0; var y = 0; var down = false
        for (t in tokens) {
            when {
                t == DOWN -> { down = true; pts += doubleArrayOf(x.toDouble(), y.toDouble(), 1.0) }
                t == UP -> { if (down) pts += doubleArrayOf(x.toDouble(), y.toDouble(), 0.0); down = false }
                t < vocab && paths[t].isNotEmpty() -> {
                    val p = paths[t]
                    if (down) for (i in 0 until p.size / 2) pts += doubleArrayOf((x + p[2 * i]).toDouble(), (y + p[2 * i + 1]).toDouble(), 1.0)
                    x += p[p.size - 2]; y += p[p.size - 1]
                }
            }
        }
        for (p in pts) { p[0] *= grid; p[1] *= grid }
        var a = 0
        while (a < pts.size) {
            if (pts[a][2] != 1.0) { a++; continue }
            var b = a
            while (b < pts.size && pts[b][2] == 1.0) b++
            if (b - a >= 3) {
                val xs = DoubleArray(b - a) { pts[a + it][0] }; val ys = DoubleArray(b - a) { pts[a + it][1] }
                for (i in 1 until b - a - 1) {
                    pts[a + i][0] = (xs[i - 1] + xs[i] + xs[i + 1]) / 3
                    pts[a + i][1] = (ys[i - 1] + ys[i] + ys[i + 1]) / 3
                }
            }
            a = b
        }
        return pts
    }

    // --- context ------------------------------------------------------------------------------------

    /** The cross-attention keys and values for one prompt, per layer. */
    internal class Context(val rows: Int, val keys: List<FloatArray>, val values: List<FloatArray>)

    /**
     * A writer or source vector mixed from the ones the model knows, each by name with its weight:
     * `style("michaux" to 0.5, "plants" to 0.5)`. Pass it as [PenGptPrompt.style] to write or draw in
     * a manner between them.
     */
    fun style(vararg mix: Pair<String, Double>): FloatArray {
        require(mix.isNotEmpty()) { "style needs at least one writer or source" }
        val out = FloatArray(writerEmb.cols)
        for ((name, weight) in mix) {
            val i = writers.indexOf(name)
            require(i >= 0) { "no writer or source called '$name'; see writers" }
            val row = writerEmb.row(i)
            for (c in out.indices) out[c] += (weight * row[c]).toFloat()
        }
        return out
    }

    private fun styleVector(prompt: PenGptPrompt): FloatArray = prompt.style?.copyOf() ?: run {
        val i = writers.indexOf(prompt.writer)
        require(i >= 0) { "no writer or source called '${prompt.writer}'; see writers" }
        writerEmb.row(i)
    }

    private fun hints(prompt: PenGptPrompt): List<Pair<Int, Int>> {
        if (hint == 0.0) return emptyList()
        val asked = listOf(prompt.ink?.let { Math.rint(2 * it).toInt().coerceIn(0, 99) }, prompt.strokes?.coerceIn(0, 99))
        val digits = asked.flatMap { n -> if (n == null) listOf(null, null) else listOf(n / 10, n % 10) }
        return digits.mapIndexedNotNull { k, d ->
            d?.let { (maxTextLength + (if (textEnd) 1 else 0) - 4 + k) to (258 + 10 * k + d) }
        }
    }

    internal fun vectorOf(prompt: PenGptPrompt): FloatArray? =
        prompt.vector ?: if (prompt.text.isEmpty()) null else SiglipText.default.encode(prompt.text)

    internal fun context(prompt: PenGptPrompt, told: FloatArray?): Context {
        val rows = ArrayList<FloatArray>()
        rows += rms(styleVector(prompt))
        val hs = hints(prompt)
        if (!toldText) {
            val learned = prompt.context
            if (learned != null) {
                for (r in learned) { require(r.size == width) { "a context row has ${r.size} numbers; this model is $width wide" }; rows += rms(r) }
            } else if (told != null && told.any { it != 0f }) {
                val p = ctxProj!!.times(told)
                val slot = ctxSlot!!
                for (i in p.indices) p[i] += slot[i]
                for (r in 0 until p.size / width) rows += rms(p.copyOfRange(r * width, (r + 1) * width))
            }
            if (hs.isNotEmpty()) {
                val flags = FloatArray(hintProj!!.cols)
                for ((_, id) in hs) flags[id - 258] = 1f
                rows += rms(hintProj.times(flags))
            }
        } else {
            val bytes = prompt.text.encodeToByteArray()
            val ids = ArrayList<Int>()
            val keep = maxTextLength - if (hint != 0.0) 4 else 0
            for (b in bytes.take(keep)) ids += (b.toInt() and 0xff) + 1
            if (textEnd) ids += 257
            val at = ArrayList<Int>(ids.indices.toList())
            for ((pos, id) in hs) { at += pos; ids += id }
            for (k in ids.indices) {
                val e = ctxEmb!!.row(ids[k])
                val pe = ctxPosEmb!!.row(at[k])
                for (i in e.indices) e[i] += pe[i]
                rows += rms(e)
            }
        }
        val keys = ArrayList<FloatArray>(); val values = ArrayList<FloatArray>()
        for (l in layers) {
            // keys and values laid out [head][row][headDim]
            val k = FloatArray(nHead * rows.size * headDim); val v = FloatArray(nHead * rows.size * headDim)
            for ((r, c) in rows.withIndex()) {
                val kv = l.xkv.times(c)
                for (h in 0 until nHead) {
                    val o = h * headDim
                    rmsInto(kv, o, headDim, k, (h * rows.size + r) * headDim, l.xkNorm)
                    System.arraycopy(kv, width + o, v, (h * rows.size + r) * headDim, headDim)
                }
            }
            keys += k; values += v
        }
        return Context(rows.size, keys, values)
    }

    // --- the decoder --------------------------------------------------------------------------------

    /**
     * A running sequence: the tokens so far and the attention cache that goes with them, for one or two
     * contexts (two when guidance compares the prompt with no prompt).
     */
    internal inner class State(val contexts: List<Context>) {
        val s = contexts.size
        val tokens = ArrayList<Int>()
        var penX = 0; var penY = 0
        val keys = Array(nLayer) { Array(s) { FloatArray(nHead * contextLength * headDim) } }
        val values = Array(nLayer) { Array(s) { FloatArray(nHead * contextLength * headDim) } }
        /** Log-probabilities over the vocabulary after the last token, per context. */
        var logits: Array<FloatArray> = emptyArray()

        val length get() = tokens.size
        val full get() = tokens.size >= contextLength

        fun copy(): State = State(contexts).also { c ->
            c.tokens += tokens; c.penX = penX; c.penY = penY
            for (l in 0 until nLayer) for (i in 0 until s) for (h in 0 until nHead) {
                val o = h * contextLength * headDim
                System.arraycopy(keys[l][i], o, c.keys[l][i], o, tokens.size * headDim)
                System.arraycopy(values[l][i], o, c.values[l][i], o, tokens.size * headDim)
            }
            c.logits = Array(logits.size) { logits[it].copyOf() }
        }

        /** Read one more token; [wantLogits] computes what the model expects after it. */
        fun push(token: Int, wantLogits: Boolean = true) {
            check(!full) { "the sequence is already $contextLength tokens long" }
            val t = tokens.size
            tokens += token
            penX += deltas[2 * token]; penY += deltas[2 * token + 1]
            val x0 = tokEmb.row(token)
            if (penPosProj != null) {
                val f = FloatArray(4 * bands)
                for (b in 0 until bands) {
                    val ax = penX * posFreq[b]; val ay = penY * posFreq[b]
                    f[b] = sin(ax); f[bands + b] = cos(ax); f[2 * bands + b] = sin(ay); f[3 * bands + b] = cos(ay)
                }
                val p = penPosProj.times(f)
                for (i in x0.indices) x0[i] += p[i]
            }
            rmsInPlace(x0)
            val half = headDim / 2
            val rc = FloatArray(half); val rs = FloatArray(half)
            if (!penRotary) {
                System.arraycopy(cosTable, t * half, rc, 0, half); System.arraycopy(sinTable, t * half, rs, 0, half)
            } else {
                var j = 0
                for (f in seqFreq) { rc[j] = cos(t * f); rs[j] = sin(t * f); j++ }
                for (f in penFreq) { rc[j] = cos(penX * f); rs[j] = sin(penX * f); j++ }
                for (f in penFreq) { rc[j] = cos(penY * f); rs[j] = sin(penY * f); j++ }
            }
            val xs = Array(s) { x0.copyOf() }
            val scores = FloatArray(max(contextLength, contexts.maxOf { it.rows }))
            val att = Array(s) { FloatArray(width) }
            for ((i, l) in layers.withIndex()) {
                for (x in xs) for (k in x.indices) x[k] = residScale[i] * x[k] + x0Scale[i] * x0[k]
                // every matrix multiplies all the contexts at once, so its weights are read once a token
                val a = Array(s) { rms(xs[it]) }
                val kv = l.kv.times(a)
                val q = l.q.times(a)
                for (c in 0 until s) {
                    val kc = keys[i][c]; val vc = values[i][c]
                    for (h in 0 until nHead) {
                        val o = h * headDim
                        rmsInto(q[c], o, headDim, q[c], o, l.qNorm)
                        val ko = (h * contextLength + t) * headDim
                        rmsInto(kv[c], o, headDim, kc, ko, l.kNorm)
                        rotate(q[c], o, rc, rs)
                        rotate(kc, ko, rc, rs)
                        System.arraycopy(kv[c], width + o, vc, ko, headDim)
                    }
                    attend(q[c], kc, vc, t + 1, contextLength, scores, att[c])
                }
                l.proj.times(att).forEachIndexed { c, y -> add(xs[c], y) }
                val cq = l.xq.times(Array(s) { rms(xs[it]) })
                for (c in 0 until s) {
                    for (h in 0 until nHead) rmsInto(cq[c], h * headDim, headDim, cq[c], h * headDim, l.xqNorm)
                    val ctx = contexts[c]
                    attend(cq[c], ctx.keys[i], ctx.values[i], ctx.rows, ctx.rows, scores, att[c])
                }
                l.xproj.times(att).forEachIndexed { c, y -> add(xs[c], y) }
                val hdn = l.fc.times(Array(s) { rms(xs[it]) })
                for (row in hdn) for (k in row.indices) { val r = max(row[k], 0f); row[k] = r * r }
                l.fcOut.times(hdn).forEachIndexed { c, y -> add(xs[c], y) }
            }
            logits = if (!wantLogits) emptyArray() else head.times(Array(s) { rms(xs[it]) }).map { lg ->
                var mx = Float.NEGATIVE_INFINITY
                for (k in lg.indices) { lg[k] = 15f * tanh(lg[k] / 15f); mx = max(mx, lg[k]) }
                var sum = 0.0
                for (k in lg.indices) sum += exp((lg[k] - mx).toDouble())
                val lse = mx + ln(sum).toFloat()
                for (k in lg.indices) lg[k] -= lse
                lg
            }.toTypedArray()
        }

        private fun attend(q: FloatArray, k: FloatArray, v: FloatArray, n: Int, stride: Int, scores: FloatArray, out: FloatArray) {
            val scale = 1f / sqrt(headDim.toFloat())
            for (h in 0 until nHead) {
                val qo = h * headDim
                var mx = Float.NEGATIVE_INFINITY
                for (j in 0 until n) {
                    val s = dot(q, qo, k, (h * stride + j) * headDim, headDim) * scale
                    scores[j] = s; if (s > mx) mx = s
                }
                var sum = 0f
                for (j in 0 until n) { scores[j] = exp(scores[j] - mx); sum += scores[j] }
                java.util.Arrays.fill(out, qo, qo + headDim, 0f)
                for (j in 0 until n) {
                    val w = scores[j] / sum
                    val vo = (h * stride + j) * headDim
                    for (d in 0 until headDim) out[qo + d] += w * v[vo + d]
                }
            }
        }

        /** The guided mixture of the contexts' log-probabilities. */
        fun mixed(guidance: Double): FloatArray =
            if (s == 1) logits[0].copyOf()
            else FloatArray(vocab) { (logits[1][it] + guidance * (logits[0][it] - logits[1][it])).toFloat() }
    }

    internal fun sample(state: State, sampling: PenGptSampling, random: SplittableRandom): Int {
        val mixed = state.mixed(guidanceOf(sampling))
        if (sampling.temperature <= 0) return mixed.indices.maxBy { mixed[it] }
        val t = sampling.temperature.toFloat()
        for (i in mixed.indices) mixed[i] /= t
        sampling.topK?.let { k ->
            val cut = mixed.sortedDescending()[min(k, mixed.size) - 1]
            for (i in mixed.indices) if (mixed[i] < cut) mixed[i] = Float.NEGATIVE_INFINITY
        }
        val mx = mixed.max()
        val p = DoubleArray(mixed.size) { exp((mixed[it] - mx).toDouble()) }
        var u = random.nextDouble() * p.sum()
        for (i in p.indices) { u -= p[i]; if (u <= 0) return i }
        return p.indices.last { p[it] > 0 }
    }

    private fun guidanceOf(sampling: PenGptSampling): Double {
        val g = sampling.guidance ?: if (labelDrop > 0) 2.0 else 1.0
        require(g == 1.0 || labelDrop > 0) { "guidance needs a model trained with prompts dropped; this one was not" }
        return g
    }

    /** A fresh sequence told [prompt]: the BOS token read, nothing else. */
    internal fun start(prompt: PenGptPrompt, sampling: PenGptSampling): State {
        val told = if (toldText || prompt.context != null) null else vectorOf(prompt)
        val main = context(prompt, told)
        val prompted = if (toldText) prompt.text.isNotEmpty() else told != null || prompt.context != null
        val guided = !writesText && prompted && guidanceOf(sampling) != 1.0
        val contexts = if (guided) listOf(main, context(prompt.copy(text = "", vector = null, context = null), null)) else listOf(main)
        return State(contexts).also { it.push(bos) }
    }

    /**
     * Tell the model [prompt], show it [prefix], and let it carry on until it ends the sequence or runs
     * out of room. Returns only the new tokens; the last is [end] when the model finished.
     */
    fun generate(prompt: PenGptPrompt, prefix: IntArray = IntArray(0), sampling: PenGptSampling = PenGptSampling(), seed: Long = 0): IntArray {
        val random = SplittableRandom(seed)
        val state = start(prompt, sampling)
        for ((i, t) in prefix.withIndex()) state.push(t, wantLogits = i == prefix.size - 1)
        val out = ArrayList<Int>()
        while (!state.full) {
            val t = sample(state, sampling, random)
            out += t
            if (t == end || state.full) break
            state.push(t)
        }
        return out.toIntArray()
    }

    /**
     * The tokens of each word the model writes for [prompt]'s text after [prefixWords], split at the
     * word separators. An unfinished last word is dropped.
     */
    internal fun wordTokens(prompt: PenGptPrompt, prefixWords: List<IntArray>, sampling: PenGptSampling, seed: Long): List<IntArray> {
        val prefix = prefixWords.flatMap { it.toList() + listOf(word, word) }.toIntArray()
        val new = generate(prompt, prefix, sampling, seed)
        val done = new.isNotEmpty() && new.last() == end
        val seq = if (done) new.copyOf(new.size - 1) else new
        val words = ArrayList<IntArray>()
        var cur = ArrayList<Int>()
        for (t in seq) {
            if (t == word) { if (cur.isNotEmpty()) words += cur.toIntArray(); cur = ArrayList() } else cur += t
        }
        if (cur.isNotEmpty()) words += cur.toIntArray()
        if (!done && words.isNotEmpty()) words.removeAt(words.size - 1)
        return words
    }

    // --- writing ------------------------------------------------------------------------------------

    /**
     * Write [text] by hand: one [FLine] per word, laid out in lines no wider than [width] drawing units,
     * at [at]. [scale] is drawing units per model unit; a word's x-height is about `0.12 * scale`.
     *
     * The model is told two words at a time, with the word before them shown as a prefix, so a long
     * text keeps one hand from start to end. Same [seed], same writing.
     *
     * @param writer one of [writers], or `""` for no one in particular.
     * @param temperature 0 writes the most likely ink, a writer's tidiest hand; 0.5 to 1 loosens it.
     */
    fun write(
        text: String,
        writer: String = "",
        temperature: Double = 0.0,
        topK: Int? = null,
        seed: Long = 0,
        scale: Double = 100.0,
        width: Double = 8.0 * scale,
        at: Vec3 = Vec3(0f, 0f, 0f),
        style: FloatArray? = null,
    ): List<FLine> = layout(writeWords(text.split(Regex("\\s+")).filter { it.isNotEmpty() }, PenGptPrompt(writer = writer, style = style),
        PenGptSampling(temperature, topK), seed, emptyList()), scale, width, at)

    /**
     * Write [text] in the hand of [examples]: words someone wrote, each paired with what it says. The
     * examples are shown to the model before every pair of new words, as though the same person had
     * written them a moment earlier, and the model carries on in that hand.
     *
     * [scale] is the examples' drawing units per model unit, and the scale of the result; `null`
     * estimates it from the examples' height. [baseline] is the y the examples' letters sit on; `null`
     * takes, word by word, the y that 95% of the ink is above. Writing is laid out from [at] (by default
     * the left of the first example's baseline) in lines [width] wide.
     */
    fun writeLike(
        examples: List<Pair<String, FLine>>,
        text: String,
        temperature: Double = 0.0,
        topK: Int? = null,
        seed: Long = 0,
        scale: Double? = null,
        baseline: Double? = null,
        width: Double? = null,
        at: Vec3? = null,
        writer: String = "",
    ): List<FLine> {
        require(writesText) { "writeLike needs the handwriting model" }
        require(examples.isNotEmpty()) { "give at least one example word" }
        val s = scale ?: estimateScale(examples.map { it.second })
        val prefix = examples.map { (_, line) -> encode(toModelWord(line, s, baseline)) }
        val first = examples.first().second
        val start = at ?: Vec3(boundsOf(first)[0].toFloat(), (baseline ?: baselineOf(first)).toFloat(), 0f)
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        return layout(writeWords(words, PenGptPrompt(writer = writer), PenGptSampling(temperature, topK), seed,
            examples.map { it.first } zip prefix), s, width ?: (8.0 * s), start)
    }

    private fun writeWords(texts: List<String>, prompt: PenGptPrompt, sampling: PenGptSampling, seed: Long, shown: List<Pair<String, IntArray>>): List<List<DoubleArray>> {
        val out = ArrayList<List<DoubleArray>>()
        var prior: List<Pair<String, IntArray>> = shown
        var i = 0
        var k = 0L
        while (i < texts.size) {
            val chunk = texts.subList(i, min(i + 2, texts.size))
            // what fits: at most maxTextLength bytes of text and half the context of prefix
            var p = prior
            while (p.isNotEmpty() && ((p.map { it.first } + chunk).joinToString(" ").encodeToByteArray().size > maxTextLength ||
                    p.sumOf { it.second.size + 2 } > contextLength / 2)) p = p.drop(1)
            val told = prompt.copy(text = (p.map { it.first } + chunk).joinToString(" "))
            val tokens = ArrayList(wordTokens(told, p.map { it.second }, sampling, seed * 7919 + k++))
            // a word the model skipped or left unfinished is asked for again on its own
            for (w in chunk.drop(tokens.size)) tokens += wordTokens(prompt.copy(text = w), emptyList(), sampling, seed * 7919 + k++).firstOrNull() ?: IntArray(0)
            val written = tokens.take(chunk.size)
            out += written.map { decode(it) }
            prior = if (shown.isNotEmpty()) shown
            else if (written.all { it.isNotEmpty() }) listOf(chunk.last() to written.last()) else emptyList()
            i += chunk.size
        }
        return out
    }

    private fun layout(words: List<List<DoubleArray>>, scale: Double, width: Double, at: Vec3, lineHeight: Double = 0.55, space: Double = 0.16): List<FLine> {
        val out = ArrayList<FLine>()
        var x = 0.0; var y = 0.0
        for (w in words) {
            val downs = w.filter { it[2] == 1.0 }
            if (downs.isEmpty()) { out += FLine(); continue }
            val lo = downs.minOf { it[0] }; val hi = downs.maxOf { it[0] }
            if (x > 0 && (x + hi - lo) * scale > width) { x = 0.0; y += lineHeight }
            out += toFLine(w, scale, at.x + (x - lo) * scale, at.y + y * scale)
            x += hi - lo + space
        }
        return out
    }

    // --- drawing ------------------------------------------------------------------------------------

    /**
     * Draw [prompt] (a phrase, or a [PenGptPrompt] with a vector, source and hints), [size] drawing units
     * on its longer side, centred on [at]. An empty phrase draws whatever the model likes.
     */
    fun draw(
        prompt: PenGptPrompt,
        temperature: Double = 0.6,
        topK: Int? = null,
        guidance: Double? = null,
        seed: Long = 0,
        size: Double = 200.0,
        at: Vec3 = Vec3(0f, 0f, 0f),
    ): FLine {
        require(!writesText) { "draw needs the drawing model" }
        val tokens = generate(prompt, IntArray(0), PenGptSampling(temperature, topK, guidance), seed)
        return toFLine(decode(tokens.filter { it < pad }.toIntArray()), size, at.x.toDouble(), at.y.toDouble())
    }

    /** Draw a phrase; see the other [draw]. */
    fun draw(
        phrase: String = "",
        source: String = "",
        temperature: Double = 0.6,
        seed: Long = 0,
        size: Double = 200.0,
        at: Vec3 = Vec3(0f, 0f, 0f),
        ink: Double? = null,
        strokes: Int? = null,
        guidance: Double? = null,
    ): FLine = draw(PenGptPrompt(phrase, source, ink = ink, strokes = strokes), temperature, null, guidance, seed, size, at)

    /**
     * Carry on a drawing begun by hand: [drawing]'s strokes are shown to the model as the start of a
     * drawing of [prompt], and the model adds strokes until it thinks the drawing is done. Returns only
     * the new strokes, in [drawing]'s units.
     *
     * The model was trained on whole drawings centred on the origin with their longer side 1, so it
     * needs to know where the finished drawing will sit: [frame] is that square's centre and side, in
     * drawing units; by default it is [drawing]'s own bounds grown by [grow].
     */
    fun continueDrawing(
        drawing: FLine,
        prompt: PenGptPrompt,
        temperature: Double = 0.6,
        topK: Int? = null,
        guidance: Double? = null,
        seed: Long = 0,
        frame: Pair<Vec3, Double>? = null,
        grow: Double = 1.5,
    ): FLine {
        require(!writesText) { "continueDrawing needs the drawing model; for handwriting use writeLike" }
        val (centre, side) = frame ?: boundsOf(drawing).let { b ->
            Vec3(((b[0] + b[2]) / 2).toFloat(), ((b[1] + b[3]) / 2).toFloat(), 0f) to max(b[2] - b[0], b[3] - b[1]) * grow
        }
        val pts = strokesOf(drawing, side * grid / 2).flatMap { stroke ->
            stroke.map { p -> doubleArrayOf((p.x - centre.x) / side, (p.y - centre.y) / side, 1.0) } +
                listOf(doubleArrayOf((stroke.last().x - centre.x) / side, (stroke.last().y - centre.y) / side, 0.0))
        }
        val prefix = encode(pts)
        val new = generate(prompt, prefix, PenGptSampling(temperature, topK, guidance), seed)
        val all = decode(prefix + new.filter { it < pad })
        // the prefix decodes to its own points first; keep what comes after
        val shown = decode(prefix).size
        return toFLine(all.drop(shown), side, centre.x.toDouble(), centre.y.toDouble())
    }

    /** Carry on [drawing] as a drawing of [phrase]; see the other [continueDrawing]. */
    fun continueDrawing(
        drawing: FLine,
        phrase: String = "",
        source: String = "",
        temperature: Double = 0.6,
        seed: Long = 0,
        frame: Pair<Vec3, Double>? = null,
        grow: Double = 1.5,
    ): FLine = continueDrawing(drawing, PenGptPrompt(phrase, source), temperature, null, null, seed, frame, grow)

    /**
     * This model as a [StrokeModel], told [prompt], for [PenLine], [continueWith] and [futuresWith].
     *
     * Each grid move becomes one [PenStep] of `grid` model units (0.02 for handwriting), so a
     * [PenLine]'s `scale` should be about 100 for canvas-sized writing and the size of the drawing for
     * the drawing model. [start] is where, in model units, the pen's first point sits in the model's
     * frame: the baseline's left end for handwriting, near the top left of a centred drawing.
     */
    fun strokes(
        prompt: PenGptPrompt,
        sampling: PenGptSampling = PenGptSampling(if (writesText) 0.0 else 0.6),
        start: Pair<Double, Double> = if (writesText) 0.0 to 0.0 else -0.3 to -0.3,
    ): StrokeModel = PenGptStrokes(this, prompt, sampling, start)

    /**
     * This model as a [StrokeModel], told [text] (the words to write, or the phrase to draw) and
     * [writer] (a writer or source from [writers]); see the other [strokes].
     */
    fun strokes(text: String = "", writer: String = "", temperature: Double = if (writesText) 0.0 else 0.6): StrokeModel =
        strokes(PenGptPrompt(text, writer), PenGptSampling(temperature))

    // --- corpora -------------------------------------------------------------------------------------

    /**
     * [drawing] in this model's frame, as `(x, y, down)` points: centred on its bounds and scaled so
     * its longer side is 1, the way the drawing model was trained, then resampled every half grid cell.
     * Each stroke ends with a pen-up point.
     */
    fun inFrame(drawing: FLine): List<DoubleArray> {
        val b = boundsOf(drawing)
        val side = max(b[2] - b[0], b[3] - b[1])
        val cx = (b[0] + b[2]) / 2; val cy = (b[1] + b[3]) / 2
        return strokesOf(drawing, side * grid / 2).flatMap { stroke ->
            stroke.map { doubleArrayOf((it.x - cx) / side, (it.y - cy) / side, 1.0) } +
                listOf(doubleArrayOf((stroke.last().x - cx) / side, (stroke.last().y - cy) / side, 0.0))
        }
    }

    /**
     * Write [drawings] as a corpus for training or fitting this model: one JSON line per drawing in
     * [file], holding its points in the model's frame ([inFrame]) and its tokens ([encode]), and
     * [label] when it is not empty. Returns each drawing's token count; a drawing longer than
     * [contextLength] is cut short when the model reads it. [withPoints] `false` leaves the points
     * out, for a large corpus whose tokens are all that training reads.
     */
    fun writeCorpus(file: File, drawings: List<FLine>, label: String = "", withPoints: Boolean = true): List<Int> {
        file.parentFile?.mkdirs()
        val counts = ArrayList<Int>()
        file.bufferedWriter().use { w ->
            for (d in drawings) {
                // round first, so the numbers written are the numbers encoded
                val pts = inFrame(d).map { p -> doubleArrayOf(Math.round(p[0] * 1e6) / 1e6, Math.round(p[1] * 1e6) / 1e6, p[2]) }
                val tokens = encode(pts)
                counts += tokens.size
                w.write("{")
                if (label.isNotEmpty()) w.write("\"label\":${Json.encodeToString(String.serializer(), label)},")
                if (withPoints) w.write(pts.joinToString(",", "\"points\":[", "],") { "[${"%.6f".format(it[0])},${"%.6f".format(it[1])},${it[2].toInt()}]" })
                w.write(tokens.joinToString(",", "\"tokens\":[", "]}\n"))
            }
        }
        return counts
    }

    // --- shared helpers -----------------------------------------------------------------------------

    private fun strokesOf(line: FLine, spacing: Double): List<List<Vec3>> =
        line.pieces().map { resampleForPen(it, spacing) }.filter { it.isNotEmpty() }

    private fun toModelWord(line: FLine, scale: Double, baseline: Double?): List<DoubleArray> {
        val x0 = boundsOf(line)[0]
        val y0 = baseline ?: baselineOf(line)
        return strokesOf(line, scale * grid / 2).flatMap { stroke ->
            stroke.map { doubleArrayOf((it.x - x0) / scale, (it.y - y0) / scale, 1.0) } +
                listOf(doubleArrayOf((stroke.last().x - x0) / scale, (stroke.last().y - y0) / scale, 0.0))
        }
    }

    /** `minX, minY, maxX, maxY` of [line]'s ink, curves included. */
    private fun boundsOf(line: FLine): DoubleArray {
        val pts = line.pieces().flatMap { resampleForPen(it, 1.0) }
        require(pts.isNotEmpty()) { "the line holds no ink" }
        return doubleArrayOf(pts.minOf { it.x.toDouble() }, pts.minOf { it.y.toDouble() }, pts.maxOf { it.x.toDouble() }, pts.maxOf { it.y.toDouble() })
    }

    private fun baselineOf(line: FLine): Double {
        val ys = line.pieces().flatMap { resampleForPen(it, 1.0) }.map { it.y.toDouble() }.sorted()
        return if (ys.isEmpty()) 0.0 else ys[((ys.size - 1) * 0.95).toInt()]
    }

    private fun estimateScale(lines: List<FLine>): Double {
        val heights = lines.map { l ->
            val ys = l.pieces().flatMap { resampleForPen(it, 1.0) }.map { it.y.toDouble() }.sorted()
            if (ys.size < 2) 0.0 else ys[((ys.size - 1) * 0.98).toInt()] - ys[((ys.size - 1) * 0.02).toInt()]
        }.filter { it > 0 }.sorted()
        require(heights.isNotEmpty()) { "the examples hold no ink" }
        return heights[heights.size / 2] / INK_HEIGHT
    }

    private fun toFLine(points: List<DoubleArray>, scale: Double, ox: Double, oy: Double): FLine {
        val f = FLine()
        var down = false
        for (p in points) {
            val x = ox + p[0] * scale; val y = oy + p[1] * scale
            if (p[2] == 1.0) { if (down) f.lineTo(x, y) else f.moveTo(x, y); down = true } else down = false
        }
        return f
    }

    companion object {
        internal val DIRECTIONS = intArrayOf(1, 0, 1, 1, 0, 1, -1, 1, -1, 0, -1, -1, 0, -1, 1, -1)
        internal const val DOWN = 8
        internal const val UP = 9
        internal const val N_BASE = 10

        /** The median height of a word's ink in the handwriting model's units. */
        internal const val INK_HEIGHT = 0.22

        private val DIR_LOOKUP = IntArray(9) { -1 }.also { l ->
            for (i in 0 until 8) l[(DIRECTIONS[2 * i] + 1) * 3 + DIRECTIONS[2 * i + 1] + 1] = i
        }

        private const val RELEASE = "https://github.com/greydanus/pengpt/releases/download/models-2026-11-16"

        /** Where the handwriting weights come from and are cached. */
        val HAND_WEIGHTS = OnnxModel(
            id = "pengpt-hand", url = "$RELEASE/hand.npz", fileName = "pengpt/hand.npz",
            sha256 = "4470544dc428d1da41a7c5c6096652556b1dc6afc4c87d1ccd1ee62716153514",
            note = "PenGPT handwriting model, 6M parameters, 8-bit, 1,192 writers (Apache 2.0)",
        )

        /** Where the drawing weights come from and are cached. */
        val SKETCH_WEIGHTS = OnnxModel(
            id = "pengpt-sketch", url = "$RELEASE/sketch.npz", fileName = "pengpt/sketch.npz",
            sha256 = "096c56ab634897bd500ed67aa80692e4edcf9fed697ed9beb1beebfb0fa5ce2a",
            note = "PenGPT drawing model, 54M parameters, 8-bit, told SigLIP 2 vectors (Apache 2.0)",
        )

        /** The handwriting model, downloaded (6 MB) and loaded on first use. */
        val hand: PenGpt by lazy { load(HAND_WEIGHTS.ensureLocal().toFile()) }

        /** The drawing model, downloaded (50 MB) and loaded on first use. */
        val sketch: PenGpt by lazy { load(SKETCH_WEIGHTS.ensureLocal().toFile()) }

        /** Load a PenGPT model packed by `inference/pen.py export`. */
        fun load(file: File): PenGpt = PenGpt(NumpyIO.readNpz(file))

        /** Load a PenGPT model packed by `inference/pen.py export`. */
        fun load(path: String): PenGpt = load(File(path))

        /**
         * The folder Field keeps PenGPT models in, `~/.afield/onnx/models/pengpt/`: the release's
         * weights, and any you fine-tune with `tools/pengpt_fit_style.py`.
         */
        val folder: File get() = OnnxModel.cacheRoot.resolve("pengpt").toFile()

        /** The model `<name>.npz` in [folder], such as one you fine-tuned, loaded once. */
        fun named(name: String): PenGpt = namedModels.getOrPut(name) {
            val file = File(folder, "$name.npz")
            require(file.isFile) { "no PenGPT model called \"$name\" ($file)" }
            load(file)
        }

        private val namedModels = java.util.concurrent.ConcurrentHashMap<String, PenGpt>()

        /**
         * One small drawing model (9 million parameters) trained on the corpora Field's synthetic pens
         * learned from, each as a source of its own and a label: `"hershey"`, `"harmonograph"`,
         * `"plants"`, `"truchet"`, `"boids"`, `"mixed"`, `"michaux"`, `"cracks"` and `"echoes"`. It
         * ships with Field, so it needs no download.
         */
        val corpora: PenGpt by lazy { bundled("corpora") }

        /**
         * A PenGPT model that ships with Field, from `afield/pen/models/pengpt-<name>.npz` on the
         * classpath (stored in Git LFS), loaded once.
         */
        fun bundled(name: String): PenGpt = bundledModels.getOrPut(name) {
            val path = "/afield/pen/models/pengpt-$name.npz"
            val bytes = PenGpt::class.java.getResourceAsStream(path)?.use { it.readBytes() }
                ?: error("no bundled PenGPT model called \"$name\" ($path)")
            check(!String(bytes, 0, minOf(bytes.size, 40), Charsets.US_ASCII).startsWith("version https://git-lfs")) {
                "$path is a Git LFS pointer, not the model: run `git lfs pull` in the checkout"
            }
            PenGpt(NumpyIO.readNpz(bytes.inputStream(), path))
        }

        private val bundledModels = java.util.concurrent.ConcurrentHashMap<String, PenGpt>()
    }
}

/** The [StrokeModel] face of a [PenGpt]; see [PenGpt.strokes]. */
internal class PenGptStrokes(
    val gpt: PenGpt,
    val prompt: PenGptPrompt,
    val sampling: PenGptSampling,
    val start: Pair<Double, Double>,
) : StrokeModel {
    // the prompt's context is the expensive part of starting: share it between pens
    private val base by lazy { gpt.start(prompt, sampling) }

    override fun pen(seed: Long): StrokePen = PenGptPen(this, base.copy(), SplittableRandom(seed))
}

/**
 * A [StrokePen] over a [PenGpt]. It keeps the ink it is shown as points in the model's frame and
 * encodes them into tokens when it next has to draw. Each token it samples is turned back into points,
 * smoothed as [PenGpt.decode] smooths them (so each point waits for the next), and handed out one
 * [PenStep] at a time, with a whole pen-up journey as one step. For handwriting, each word after the
 * first is set beside the one before it, a space apart, on the same baseline.
 */
internal class PenGptPen(
    private val owner: PenGptStrokes,
    private var state: PenGpt.State,
    private val random: SplittableRandom,
) : StrokePen {
    private val gpt = owner.gpt
    private val grid = gpt.grid

    // ink shown from outside since the model last caught up, in model units
    private val shown = ArrayList<DoubleArray>()
    private var fedX = owner.start.first
    private var fedY = owner.start.second
    private var fedAny = false

    // where PenLine believes the pen is, in model units
    private var atX = owner.start.first
    private var atY = owner.start.second

    // the model's cells are drawn at cell * grid + offset; words move the offset
    private var offX = owner.start.first
    private var offY = owner.start.second
    private var drawing = false
    private var finished = false
    private var wordLeft = Double.NaN
    private var wordRight = Double.NaN
    private var lastWasWord = false

    // smoothing: the down points of the stroke so far that have not been handed out
    private var before: DoubleArray? = null
    private var current: DoubleArray? = null
    private val queue = ArrayDeque<PenStep>()

    override fun feed(dx: Double, dy: Double, lift: Boolean) {
        if (fedAny) { fedX += dx; fedY += dy } else fedAny = true
        shown += doubleArrayOf(fedX, fedY, 1.0)
        if (lift) shown += doubleArrayOf(fedX, fedY, 0.0)
        atX = fedX; atY = fedY
        queue.clear(); before = null; current = null
    }

    private fun catchUp() {
        if (shown.isEmpty()) return
        val tokens = gpt.encode(shown).toMutableList()
        val open = shown.last()[2] == 1.0
        // a stroke left open keeps the pen down
        if (open && tokens.lastOrNull() == PenGpt.UP) tokens.removeAt(tokens.size - 1)
        for ((i, t) in tokens.withIndex()) {
            if (state.full) break
            state.push(t, wantLogits = i == tokens.size - 1)
        }
        offX = 0.0; offY = 0.0
        drawing = open
        if (open) { before = null; current = doubleArrayOf(atX, atY) }
        for (p in shown) if (p[2] == 1.0) extend(p[0])
        shown.clear()
    }

    private fun extend(x: Double) {
        wordLeft = if (wordLeft.isNaN()) x else min(wordLeft, x)
        wordRight = if (wordRight.isNaN()) x else max(wordRight, x)
    }

    override fun next(controls: PenControls): PenStep {
        while (queue.isEmpty() && !finished) refill()
        return queue.removeFirstOrNull() ?: PenStep(0.0, 0.0, true)
    }

    private fun stepTo(x: Double, y: Double, lift: Boolean) {
        queue += PenStep(x - atX, y - atY, lift)
        atX = x; atY = y
    }

    private fun downPoint(x: Double, y: Double) {
        extend(x)
        val b = before; val c = current
        if (c != null && b != null) stepTo((b[0] + c[0] + x) / 3, (b[1] + c[1] + y) / 3, false)
        before = c; current = doubleArrayOf(x, y)
    }

    private fun refill() {
        catchUp()
        if (state.full) { endStroke(); finished = true; return }
        val t = gpt.sample(state, owner.sampling, random)
        if (t == gpt.end) { endStroke(); finished = true; return }
        val fromX = state.penX; val fromY = state.penY
        state.push(t)
        when {
            t == PenGpt.DOWN -> {
                drawing = true
                val x = fromX * grid + offX; val y = fromY * grid + offY
                stepTo(x, y, false)
                extend(x)
                before = null; current = doubleArrayOf(x, y)
            }
            t == PenGpt.UP -> endStroke()
            t == gpt.word -> {
                endStroke()
                // the first of a pair of separators: set the next word after this one
                if (!lastWasWord && !wordRight.isNaN()) {
                    offX = wordRight + WORD_SPACE - state.penX * grid
                    offY = owner.start.second - state.penY * grid
                    wordLeft = Double.NaN; wordRight = Double.NaN
                }
            }
            t < gpt.vocab && gpt.paths[t].isNotEmpty() && drawing -> {
                val p = gpt.paths[t]
                for (i in 0 until p.size / 2) downPoint((fromX + p[2 * i]) * grid + offX, (fromY + p[2 * i + 1]) * grid + offY)
            }
        }
        lastWasWord = t == gpt.word
    }

    private fun endStroke() {
        if (!drawing) return
        drawing = false
        val c = current
        if (c != null) stepTo(c[0], c[1], true) else queue += PenStep(0.0, 0.0, true)
        before = null; current = null
    }

    override fun fork(seed: Long): StrokePen {
        catchUp()
        return PenGptPen(owner, state.copy(), SplittableRandom(seed)).also {
            it.fedX = fedX; it.fedY = fedY; it.fedAny = fedAny; it.atX = atX; it.atY = atY
            it.offX = offX; it.offY = offY; it.drawing = drawing; it.finished = finished
            it.wordLeft = wordLeft; it.wordRight = wordRight; it.lastWasWord = lastWasWord
            it.before = before?.copyOf(); it.current = current?.copyOf()
            it.queue.addAll(queue)
        }
    }

    private companion object {
        /** The gap between words, in model units, as PenGPT's own layout leaves it. */
        const val WORD_SPACE = 0.16
    }
}

// --- arithmetic ---------------------------------------------------------------------------------

private val SPECIES: VectorSpecies<Float> = FloatVector.SPECIES_PREFERRED
private const val EPS = 1.1920929e-7f

internal fun dot(a: FloatArray, ao: Int, b: FloatArray, bo: Int, n: Int): Float {
    var acc = FloatVector.zero(SPECIES)
    var i = 0
    val bound = SPECIES.loopBound(n)
    while (i < bound) {
        acc = FloatVector.fromArray(SPECIES, a, ao + i).fma(FloatVector.fromArray(SPECIES, b, bo + i), acc)
        i += SPECIES.length()
    }
    var s = acc.reduceLanes(VectorOperators.ADD)
    while (i < n) { s += a[ao + i] * b[bo + i]; i++ }
    return s
}

private fun rms(x: FloatArray): FloatArray = x.copyOf().also { rmsInPlace(it) }

private fun rmsInPlace(x: FloatArray) = rmsInto(x, 0, x.size, x, 0, null)

/** `out[oo..] = x[xo..] / rms(x[xo..xo+n]) * (gain ?: 1)`. */
private fun rmsInto(x: FloatArray, xo: Int, n: Int, out: FloatArray, oo: Int, gain: FloatArray?) {
    val ms = dot(x, xo, x, xo, n) / n
    val r = 1f / sqrt(ms + EPS)
    for (i in 0 until n) out[oo + i] = x[xo + i] * r * (gain?.get(i) ?: 1f)
}

private fun rotate(u: FloatArray, o: Int, c: FloatArray, s: FloatArray) {
    val half = c.size
    for (j in 0 until half) {
        val a = u[o + j]; val b = u[o + half + j]
        u[o + j] = a * c[j] + b * s[j]
        u[o + half + j] = b * c[j] - a * s[j]
    }
}

private fun add(x: FloatArray, y: FloatArray) { for (i in x.indices) x[i] += y[i] }

/** A row-major float matrix, `rows x cols`, multiplied by vectors with the Vector API. */
internal class Mat(val rows: Int, val cols: Int, val w: FloatArray) {
    fun row(r: Int): FloatArray = w.copyOfRange(r * cols, (r + 1) * cols)

    fun times(x: FloatArray): FloatArray = times(arrayOf(x))[0]

    /** `this @ x` for each of [xs], reading the weights once. */
    fun times(xs: Array<FloatArray>): Array<FloatArray> {
        val out = Array(xs.size) { FloatArray(rows) }
        fun block(b: Int) {
            for (r in b * 64 until min(rows, b * 64 + 64)) for (i in xs.indices) out[i][r] = dot(w, r * cols, xs[i], 0, cols)
        }
        val blocks = (rows + 63) / 64
        if (rows.toLong() * cols * xs.size >= PARALLEL) IntStream.range(0, blocks).parallel().forEach { block(it) }
        else for (b in 0 until blocks) block(b)
        return out
    }

    companion object {
        private const val PARALLEL = 1L shl 19

        /** A matrix from an `.npz`, dequantized when it was saved as int8 rows with a `.scale` beside it. */
        fun of(arrays: Map<String, NpyArray>, name: String): Mat {
            val a = arrays.getValue(name)
            require(a.rank == 2) { "$name is not a matrix" }
            val rows = a.shape[0]; val cols = a.shape[1]
            val w = a.floats()
            arrays["$name.scale"]?.floats()?.let { s -> for (r in 0 until rows) for (c in 0 until cols) w[r * cols + c] *= s[r] }
            return Mat(rows, cols, w)
        }
    }
}

/** The array as floats, fast for int8 and float32, and through doubles for anything else. */
internal fun NpyArray.floats(): FloatArray {
    val d = dtype.descriptor
    if (fortranOrder && rank > 1) return asFloatArray()
    return when {
        dtype.kind == NpyKind.SIGNED_INT && dtype.itemSize == 1 -> copyBytes().let { b -> FloatArray(b.size) { b[it].toFloat() } }
        dtype.kind == NpyKind.FLOAT && dtype.itemSize == 4 -> {
            val bb = ByteBuffer.wrap(copyBytes()).order(if (d.startsWith(">")) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
            FloatArray(elementCount).also { bb.asFloatBuffer().get(it) }
        }
        else -> asFloatArray()
    }
}
