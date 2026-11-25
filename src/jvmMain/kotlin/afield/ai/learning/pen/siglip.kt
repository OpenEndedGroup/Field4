/**
 * The text half of SigLIP 2 (so400m, patch 14, 224), which turns a phrase into the 1152-number vector
 * [PenGpt.sketch] is told to draw. A port of what `inference/words.py` in the PenGPT repository does with
 * `transformers`: the same weights, packed by PenGPT at 4 to 8 bits a row, and the same tokenizer.
 * Tests: `PenGptTest`.
 */

package afield.ai.learning.pen

import afield.ai.inference.onnx.OnnxModel
import afield.lang.python.NpyArray
import afield.lang.python.NumpyIO
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.stream.IntStream
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * A SigLIP 2 text encoder: [encode] a phrase into a unit vector. Loading takes a few seconds and about
 * 500 MB of memory; each new phrase about a second; a phrase seen before, nothing.
 *
 * ```kotlin
 * val v = SiglipText.default.encode("a cat wearing a top hat")
 * lines(PenGpt.sketch.draw(PenGptPrompt(vector = v)))
 * ```
 */
class SiglipText private constructor(arrays: Map<String, NpyArray>, tokenizerFile: File) {

    private val width: Int
    private val heads: Int
    private val length: Int
    private val epsilon: Float
    private val padId: Long
    private val remap: IntArray?
    private val tokenEmbedding: QMat
    private val positionEmbedding: QMat
    private val layers: List<Layer>
    private val finalNorm: Pair<FloatArray, FloatArray>
    private val head: QMat
    private val headBias: FloatArray
    private val tokenizer: HuggingFaceTokenizer
    private val cache = ConcurrentHashMap<String, FloatArray>()

    private class Layer(
        val norm1: Pair<FloatArray, FloatArray>, val q: QMat, val qb: FloatArray, val k: QMat, val kb: FloatArray,
        val v: QMat, val vb: FloatArray, val out: QMat, val outb: FloatArray,
        val norm2: Pair<FloatArray, FloatArray>, val fc1: QMat, val fc1b: FloatArray, val fc2: QMat, val fc2b: FloatArray,
    )

    init {
        val meta = Json.parseToJsonElement(arrays.getValue("meta").asStrings().single()).jsonObject
        val config = meta.getValue("config").jsonObject
        width = config.getValue("hidden_size").jsonPrimitive.int
        heads = config.getValue("num_attention_heads").jsonPrimitive.int
        length = config.getValue("max_position_embeddings").jsonPrimitive.int
        epsilon = config["layer_norm_eps"]?.jsonPrimitive?.content?.toFloat() ?: 1e-6f
        val n = config.getValue("num_hidden_layers").jsonPrimitive.int
        remap = arrays["remap"]?.asLongArray()?.let { r -> IntArray(r.size) { r[it].toInt() } }
        val tokenizerJson = Json.parseToJsonElement(File(tokenizerFile.path).readText()).jsonObject
        padId = tokenizerJson["added_tokens"]?.jsonArray?.firstOrNull { it.jsonObject["content"]?.jsonPrimitive?.content == "<pad>" }
            ?.jsonObject?.get("id")?.jsonPrimitive?.int?.toLong() ?: 0L

        fun key(name: String) = if (arrays.containsKey("text_model.$name")) "text_model.$name" else name
        fun q(name: String) = QMat.of(arrays, key("$name.weight"))
        fun v(name: String) = arrays.getValue(key(name)).floats()
        fun norm(name: String) = v("$name.weight") to v("$name.bias")
        tokenEmbedding = q("embeddings.token_embedding")
        positionEmbedding = q("embeddings.position_embedding")
        layers = List(n) { i ->
            val p = "encoder.layers.$i"
            Layer(
                norm("$p.layer_norm1"), q("$p.self_attn.q_proj"), v("$p.self_attn.q_proj.bias"),
                q("$p.self_attn.k_proj"), v("$p.self_attn.k_proj.bias"), q("$p.self_attn.v_proj"), v("$p.self_attn.v_proj.bias"),
                q("$p.self_attn.out_proj"), v("$p.self_attn.out_proj.bias"),
                norm("$p.layer_norm2"), q("$p.mlp.fc1"), v("$p.mlp.fc1.bias"), q("$p.mlp.fc2"), v("$p.mlp.fc2.bias"),
            )
        }
        finalNorm = norm("final_layer_norm")
        head = q("head"); headBias = v("head.bias")
        tokenizer = HuggingFaceTokenizer.builder()
            .optTokenizerPath(tokenizerFile.toPath())
            .optPadding(false)
            .optTruncation(true)
            .optMaxLength(length)
            .build()
    }

    /** Token ids for [text], lower-cased, ending with `<eos>` and padded to the model's 64. */
    fun tokenize(text: String): LongArray {
        val ids = tokenizer.encode(text.lowercase()).ids
        return LongArray(length) { if (it < ids.size) ids[it] else padId }
    }

    /** [text] as a unit-length vector, the pooled output of the last token passed through the head. */
    fun encode(text: String): FloatArray = cache.computeIfAbsent(text) { compute(it) }.copyOf()

    private fun compute(text: String): FloatArray {
        val ids = tokenize(text)
        val t = length
        val x = Array(t) { i ->
            val id = ids[i].toInt().let { remap?.get(it) ?: it }
            val e = tokenEmbedding.row(id)
            val p = positionEmbedding.row(i)
            for (k in e.indices) e[k] += p[k]
            e
        }
        val hd = width / heads
        val scale = 1f / sqrt(hd.toFloat())
        for (l in layers) {
            val h = Array(t) { layerNorm(x[it], l.norm1) }
            val q = l.q.times(h, l.qb); val k = l.k.times(h, l.kb); val v = l.v.times(h, l.vb)
            val att = Array(t) { FloatArray(width) }
            IntStream.range(0, heads * t).parallel().forEach { job ->
                val hh = job / t; val i = job % t
                val o = hh * hd
                val s = FloatArray(t)
                var mx = Float.NEGATIVE_INFINITY
                for (j in 0 until t) { s[j] = dot(q[i], o, k[j], o, hd) * scale; if (s[j] > mx) mx = s[j] }
                var sum = 0f
                for (j in 0 until t) { s[j] = exp(s[j] - mx); sum += s[j] }
                val out = att[i]
                for (j in 0 until t) { val w = s[j] / sum; val vj = v[j]; for (d in 0 until hd) out[o + d] += w * vj[o + d] }
            }
            val a = l.out.times(att, l.outb)
            for (i in 0 until t) for (c in 0 until width) x[i][c] += a[i][c]
            val h2 = Array(t) { layerNorm(x[it], l.norm2) }
            val f = l.fc1.times(h2, l.fc1b)
            for (row in f) for (c in row.indices) row[c] = gelu(row[c])
            val m = l.fc2.times(f, l.fc2b)
            for (i in 0 until t) for (c in 0 until width) x[i][c] += m[i][c]
        }
        val pooled = layerNorm(x[t - 1], finalNorm)
        val out = head.times(arrayOf(pooled), headBias)[0]
        val norm = sqrt(dot(out, 0, out, 0, out.size))
        for (i in out.indices) out[i] /= norm
        return out
    }

    private fun layerNorm(x: FloatArray, wb: Pair<FloatArray, FloatArray>): FloatArray {
        var mean = 0.0
        for (v in x) mean += v
        mean /= x.size
        var varSum = 0.0
        for (v in x) { val d = v - mean; varSum += d * d }
        val r = 1.0 / sqrt(varSum / x.size + epsilon)
        val (w, b) = wb
        return FloatArray(x.size) { ((x[it] - mean) * r * w[it] + b[it]).toFloat() }
    }

    private fun gelu(v: Float): Float = 0.5f * v * (1f + tanh(0.7978845608f * (v + 0.044715f * v * v * v)))

    companion object {
        /** Where the SigLIP 2 text weights come from and are cached. */
        val WEIGHTS = OnnxModel(
            id = "pengpt-siglip-text",
            url = "https://github.com/greydanus/pengpt/releases/download/models-2026-11-16/siglip-text.npz",
            fileName = "pengpt/siglip-text.npz",
            sha256 = "a32a7afd5c091144cb37d5357f1df7b727611b11dd146282ea7e78104c64e1b4",
            note = "SigLIP 2 so400m text tower, packed by PenGPT at 4-8 bits (Apache 2.0)",
        )

        /** The encoder from PenGPT's release, downloaded (375 MB) and loaded on first use. */
        val default: SiglipText by lazy { load(WEIGHTS.ensureLocal().toFile()) }

        /** Load a SigLIP text tower packed the way PenGPT packs `siglip-text.npz`. */
        fun load(file: File): SiglipText {
            val arrays = NumpyIO.readNpz(file)
            val tokenizerFile = File(file.parentFile, file.nameWithoutExtension + ".tokenizer.json")
            if (!tokenizerFile.isFile) {
                val meta = Json.parseToJsonElement(arrays.getValue("meta").asStrings().single()).jsonObject
                val part = File(tokenizerFile.path + ".part")
                part.writeText(meta.getValue("tokenizer").jsonPrimitive.content)
                Files.move(part.toPath(), tokenizerFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
            return SiglipText(arrays, tokenizerFile)
        }
    }
}

/**
 * An int8 matrix with one scale per row, kept quantized (a quarter of the memory of floats) and
 * dequantized a row at a time as it is multiplied.
 */
internal class QMat(val rows: Int, val cols: Int, private val q: ByteArray, private val scale: FloatArray) {

    fun row(r: Int): FloatArray = FloatArray(cols) { q[r * cols + it] * scale[r] }

    /** `x @ this.T + bias` for every row of [x]. */
    fun times(x: Array<FloatArray>, bias: FloatArray?): Array<FloatArray> {
        val out = Array(x.size) { FloatArray(rows) }
        IntStream.range(0, (rows + 31) / 32).parallel().forEach { b ->
            val w = FloatArray(cols)
            for (r in b * 32 until min(rows, b * 32 + 32)) {
                val s = scale[r]; val o = r * cols
                for (c in 0 until cols) w[c] = q[o + c] * s
                val bb = bias?.get(r) ?: 0f
                for (i in x.indices) out[i][r] = dot(w, 0, x[i], 0, cols) + bb
            }
        }
        return out
    }

    companion object {
        fun of(arrays: Map<String, NpyArray>, name: String): QMat {
            val a = arrays.getValue(name)
            require(a.rank == 2 && a.dtype.itemSize == 1) { "$name is not an int8 matrix" }
            val s = arrays.getValue("$name.scale").floats()
            return QMat(a.shape[0], a.shape[1], a.copyBytes(), s)
        }
    }
}
