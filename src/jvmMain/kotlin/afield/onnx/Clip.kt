@file:afield.CompletionCategory("Machine learning")

package afield.onnx

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.onnxruntime.OnnxTensor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sqrt

/**
 * CLIP, as a source of *directions in meaning*.
 *
 * [CLIP](https://openai.com/research/clip) embeds images and text into one 512-dimensional space. Two
 * separate ONNX graphs — `text_model.onnx` and `vision_model.onnx` — each emit an already-projected
 * 512-vector, so no head has to be reimplemented here.
 *
 * Three things fall out, and the third is the one nothing else offers:
 *
 *  1. **Zero-shot classification with no training.** Encode a handful of prompts once, cosine them
 *     against the live frame, take the best. See [ClipVector.classify].
 *  2. **Identity by similarity.** A stored vector plus a threshold is a recogniser.
 *  3. **A knob whose units are meaning.** `clipEncode("storm") - clipEncode("calm")` is a *direction*, and
 *     projecting the current frame onto it is a continuous parameter that tracks a semantic axis
 *     rather than a pixel statistic. See [ClipVector.axisTo].
 *
 * Encode prompts once, into a `val`, and the text tower is simply not in the per-frame path — there is
 * nothing cleverer to do about it than that.
 *
 * ## Cosine values are not comparable across modalities
 *
 * The single most misread property of CLIP, and the reason [classify] exists rather than a bare
 * `cosine` threshold. Measured on this checkpoint:
 *
 *  * **text against text** sits in a narrow high cone — `cos("storm","calm") = 0.881`,
 *    `cos("a photo of a cat","a photo of a dog") = 0.931`. Nothing is ever near 0.
 *  * **image against text** sits far lower — around `0.21` for every prompt tried, because the two
 *    towers occupy offset regions of the sphere (the "modality gap").
 *
 * So `0.21` does not mean "unrelated" and `0.88` does not mean "the same". Only *rank* and
 * *difference* carry information: compare one image against several prompts, or track how a
 * projection moves over time. An absolute threshold on a raw cosine will not survive contact.
 *
 * ## Cost
 *
 * Text is ≈5ms per prompt, after the one-off cost of loading the encoder. Vision is ≈36ms at
 * 224x224 on a CPU provider —
 * far cheaper than [DepthAnything], but still not free, so drive it from a worker if it is per-frame.
 *
 * @see ClipText for the text tower
 * @see ClipImage for the vision tower
 */
object Clip {

    /** The dimensionality of the shared embedding space for this checkpoint. */
    const val DIMENSION = 512

    /** Side length the vision tower requires; ViT-B/32's position embeddings are fixed at 7x7+1. */
    const val IMAGE_SIZE = 224

    /**
     * CLIP's own channel means — **not** ImageNet's, which [DepthAnything] uses.
     *
     * Mixing the two up produces embeddings that are subtly wrong rather than obviously broken, which
     * is the worst failure mode available, so they are named distinctly on purpose.
     */
    val CLIP_MEAN = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)

    /** CLIP's own channel standard deviations. */
    val CLIP_STD = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)

    /**
     * The trained temperature for CLIP logits, `logit_scale.exp()`.
     *
     * Affects only how peaked [ClipVector.classify]'s distribution is; it cannot change the *ranking*,
     * which is why it is a plain default rather than something read out of the checkpoint.
     */
    const val LOGIT_SCALE = 100f

    /** Text tower: `input_ids` to a projected 512-vector. */
    val TEXT_MODEL = OnnxModel(
        id = "clip-vit-base-patch32-text",
        url = "https://huggingface.co/Xenova/clip-vit-base-patch32/resolve/main/onnx/text_model.onnx",
        fileName = "clip-vit-base-patch32/text_model.onnx",
        sha256 = "3f6571f5bad13a97c469c1622e1cfc4d9aef78b79fdbfcff804ca357bfada8cc",
        note = "CLIP ViT-B/32 text encoder, fp32, already projected to 512-d."
    )

    /** Vision tower: `pixel_values` to a projected 512-vector. */
    val VISION_MODEL = OnnxModel(
        id = "clip-vit-base-patch32-vision",
        url = "https://huggingface.co/Xenova/clip-vit-base-patch32/resolve/main/onnx/vision_model.onnx",
        fileName = "clip-vit-base-patch32/vision_model.onnx",
        sha256 = "fd6e1402a588279d1723c7534d4bcba5bc0b14b47dfab0e46f8c47b8270d7d40",
        note = "CLIP ViT-B/32 vision encoder, fp32, already projected to 512-d."
    )

    /**
     * The BPE tokenizer, as a plain file rather than a model.
     *
     * Reuses [OnnxModel]'s fetch-and-cache because it is the same problem — a large asset that must not
     * live in the repository — even though nothing about it is ONNX.
     */
    val TOKENIZER = OnnxModel(
        id = "clip-vit-base-patch32-tokenizer",
        url = "https://huggingface.co/Xenova/clip-vit-base-patch32/resolve/main/tokenizer.json",
        fileName = "clip-vit-base-patch32/tokenizer.json",
        sha256 = "f7f3b7af117d467b58374797691a6438d3e6b9e9cef800dfd5dced7f697a90cd",
        note = "CLIP BPE vocabulary and merges; sot=49406, eot=49407."
    )
}

/**
 * A point, or a direction, in CLIP's 512-dimensional space.
 *
 * Immutable and small enough to pass around freely. [values] is not copied on construction, so do not
 * mutate an array after handing it over.
 *
 * Most operations assume unit length — [normalized] is applied by both encoders before they return, so
 * anything straight out of [ClipText] or [ClipImage] is already normalised. Arithmetic
 * ([plus]/[minus]/[times]) is not renormalised, because a difference of two unit vectors is a
 * direction whose length is meaningful.
 */
class ClipVector(val values: FloatArray) {

    init {
        require(values.size == Clip.DIMENSION) {
            "a CLIP vector has ${Clip.DIMENSION} dimensions, got ${values.size}"
        }
    }

    /** Euclidean length. 1.0, to within float error, for anything an encoder returned. */
    val norm: Float get() = sqrt(dot(this))

    /** Unit-length copy, or a copy of this if the length is too small to divide by. */
    fun normalized(): ClipVector {
        val n = norm
        if (n < 1e-12f) return ClipVector(values.copyOf())
        return ClipVector(FloatArray(Clip.DIMENSION) { values[it] / n })
    }

    /** Inner product. Equal to the cosine when both sides are unit length. */
    fun dot(other: ClipVector): Float {
        var sum = 0f
        for (i in 0 until Clip.DIMENSION) sum += values[i] * other.values[i]
        return sum
    }

    /**
     * Cosine similarity, normalising both sides first.
     *
     * Read the modality-gap warning on [Clip] before comparing this against a fixed threshold.
     */
    fun cosine(other: ClipVector): Float {
        val a = norm
        val b = other.norm
        if (a < 1e-12f || b < 1e-12f) return 0f
        return dot(other) / (a * b)
    }

    operator fun plus(other: ClipVector) =
        ClipVector(FloatArray(Clip.DIMENSION) { values[it] + other.values[it] })

    operator fun minus(other: ClipVector) =
        ClipVector(FloatArray(Clip.DIMENSION) { values[it] - other.values[it] })

    operator fun times(scale: Float) = ClipVector(FloatArray(Clip.DIMENSION) { values[it] * scale })

    /**
     * The unit direction from this towards [other] — a semantic axis.
     *
     * ```kotlin
     * val weather = clipEncode("calm") axisTo clipEncode("storm")
     * val howStormy = frame.project(weather)     // a continuous knob, in units of meaning
     * ```
     */
    infix fun axisTo(other: ClipVector): ClipVector = (other - this).normalized()

    /**
     * Scalar projection of this onto [axis], assuming [axis] is unit length.
     *
     * The useful signal is how this *moves*, not its absolute value — see the modality-gap note. Expect
     * a small number near zero and look at its variation.
     */
    fun project(axis: ClipVector): Float = dot(axis)

    /**
     * Softmax over labelled candidates — the correct way to ask "which of these?".
     *
     * @param candidates label to embedding; typically prompts encoded once with [clipEncode]
     * @param logitScale see [Clip.LOGIT_SCALE]; changes sharpness, never order
     * @return probabilities summing to 1, ordered most likely first
     */
    fun classify(
        candidates: Map<String, ClipVector>,
        logitScale: Float = Clip.LOGIT_SCALE
    ): List<Pair<String, Float>> {
        if (candidates.isEmpty()) return emptyList()
        val logits = candidates.map { (label, vector) -> label to cosine(vector) * logitScale }
        val peak = logits.maxOf { it.second }
        val exps = logits.map { (label, logit) -> label to exp((logit - peak).toDouble()) }
        val total = exps.sumOf { it.second }
        return exps.map { (label, e) -> label to (e / total).toFloat() }
            .sortedByDescending { it.second }
    }

    /** The best-matching label, or null for no candidates. Shorthand for [classify]`.first()`. */
    fun best(candidates: Map<String, ClipVector>): Pair<String, Float>? =
        classify(candidates).firstOrNull()

    /**
     * Little-endian fp32, base64 encoded — 2732 characters.
     *
     * For persisting an embedding — a stored identity to cosine against later, a prompt a document
     * wants to carry rather than recompute. Exact: fp32 round-trips without loss.
     */
    fun toBase64(): String {
        val buffer = ByteBuffer.allocate(Clip.DIMENSION * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach { buffer.putFloat(it) }
        return Base64.getEncoder().encodeToString(buffer.array())
    }

    override fun toString(): String =
        "ClipVector(norm=${"%.4f".format(norm)}, [${values.take(4).joinToString { "%.4f".format(it) }}, …])"

    companion object {
        /** Inverse of [toBase64]. */
        fun fromBase64(encoded: String): ClipVector {
            val bytes = Base64.getDecoder().decode(encoded)
            require(bytes.size == Clip.DIMENSION * Float.SIZE_BYTES) {
                "expected ${Clip.DIMENSION * Float.SIZE_BYTES} bytes, got ${bytes.size}"
            }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            return ClipVector(FloatArray(Clip.DIMENSION) { buffer.getFloat() })
        }

        /** The mean of several vectors, renormalised — a prompt ensemble, which CLIP responds well to. */
        fun mean(vectors: List<ClipVector>): ClipVector {
            require(vectors.isNotEmpty()) { "cannot average no vectors" }
            val out = FloatArray(Clip.DIMENSION)
            vectors.forEach { v -> for (i in 0 until Clip.DIMENSION) out[i] += v.values[i] }
            for (i in out.indices) out[i] /= vectors.size.toFloat()
            return ClipVector(out).normalized()
        }
    }
}

/**
 * The text tower.
 *
 * Reached through [clipEncode] in box code. Encode a prompt once and keep the vector; the cost is the
 * model load, not the encode.
 *
 * The tokenizer and session are process-global and never closed, for the same reason [OnnxRuntime]
 * caches sessions: this is a 242MB model whose whole cost is loading.
 */
object ClipText {

    private val tokenizer: HuggingFaceTokenizer by lazy {
        val path: Path = Clip.TOKENIZER.ensureLocal()
        check(Files.isRegularFile(path)) { "CLIP tokenizer missing at $path" }
        // No padding: batch is 1 and the sequence axis is dynamic, so padding would only introduce a
        // question about where the EOS lands. Truncation at 77 is CLIP's trained context length.
        HuggingFaceTokenizer.builder()
            .optTokenizerPath(path)
            .optPadding(false)
            .optTruncation(true)
            .optMaxLength(77)
            .build()
    }

    private val loaded by lazy { Clip.TEXT_MODEL.session() }

    /** Token ids the tokenizer produces for [text], including CLIP's start and end markers. */
    fun tokenize(text: String): LongArray = tokenizer.encode(text).ids

    /**
     * Encode one prompt to a unit-length 512-vector.
     *
     * First call in a process pays the model load — a second or two — then roughly 5ms each.
     */
    fun encode(text: String): ClipVector {
        val ids = tokenize(text)
        require(ids.isNotEmpty()) { "tokenizer produced no ids for ${"\""}$text${"\""}" }

        val buffer = ByteBuffer.allocateDirect(ids.size * Long.SIZE_BYTES)
            .order(ByteOrder.nativeOrder()).asLongBuffer()
        buffer.put(ids)
        buffer.rewind()

        return OnnxTensor.createTensor(
            OnnxRuntime.env, buffer, longArrayOf(1, ids.size.toLong())
        ).use { input ->
            loaded.session.run(mapOf("input_ids" to input)).use { result ->
                val tensor = (result.get("text_embeds").orElse(null) ?: result.get(0)) as OnnxTensor
                val out = tensor.floatBuffer
                check(out.remaining() == Clip.DIMENSION) {
                    "expected ${Clip.DIMENSION} text dims, got ${out.remaining()}"
                }
                ClipVector(FloatArray(Clip.DIMENSION) { out.get(it) }).normalized()
            }
        }
    }

    /** [encode] for several prompts, sharing the loaded model. Sequential — the model is the cost. */
    fun encodeAll(texts: List<String>): Map<String, ClipVector> =
        texts.associateWith { encode(it) }
}

/**
 * The vision tower.
 *
 * ```kotlin
 * val clipImage = ClipImage()
 * val frame = clipImage.encode(webcamRgba, 640, 480)
 * frame.classify(mapOf("indoors" to clipEncode("indoors"), "outdoors" to clipEncode("outdoors")))
 * ```
 *
 * Buffers are allocated once and refilled, like [DepthAnything], so this is safe to call per frame if
 * ≈36ms fits the budget — though on a render thread it does not, and a worker is the answer.
 *
 * @param provider execution provider to request
 */
class ClipImage(
    val provider: OnnxRuntime.OnnxProvider = OnnxRuntime.OnnxProvider.AUTO
) {
    private val loaded = Clip.VISION_MODEL.session(provider)

    /** The provider actually in force. */
    val activeProvider: String get() = loaded.provider

    private val size = Clip.IMAGE_SIZE
    private val inputShape = longArrayOf(1, 3, size.toLong(), size.toLong())
    private val inputTensorData =
        ByteBuffer.allocateDirect(3 * size * size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()

    private var tapSrcW = -1
    private var tapSrcH = -1
    private lateinit var xLo: IntArray
    private lateinit var xHi: IntArray
    private lateinit var xFrac: FloatArray
    private lateinit var yLo: IntArray
    private lateinit var yHi: IntArray
    private lateinit var yFrac: FloatArray

    /** Wall time of the last [encode], in milliseconds. */
    var lastInferenceMs: Long = 0L
        private set

    /**
     * Encode one frame to a unit-length 512-vector.
     *
     * The source is resized so its *shorter* edge reaches 224 and then centre-cropped, which is CLIP's
     * own preprocessing — done as a single resample here rather than two, since a crop of a resize is
     * just a resample of a sub-rectangle. A non-square frame therefore loses its edges, not its aspect
     * ratio, which is the right trade for a classifier.
     *
     * @param rgba tightly packed RGBA8, `srcW*srcH*4` bytes from index 0; alpha ignored, not retained
     */
    fun encode(rgba: ByteBuffer, srcW: Int, srcH: Int): ClipVector {
        require(srcW > 0 && srcH > 0) { "source must be non-empty, got ${srcW}x$srcH" }
        val needed = srcW * srcH * 4
        require(rgba.capacity() >= needed) {
            "RGBA buffer too small: need $needed bytes for ${srcW}x$srcH, have ${rgba.capacity()}"
        }

        val started = System.nanoTime()
        ensureTaps(srcW, srcH)
        preprocess(rgba, srcW)
        inputTensorData.rewind()

        val vector = OnnxTensor.createTensor(OnnxRuntime.env, inputTensorData, inputShape).use { input ->
            loaded.session.run(mapOf("pixel_values" to input)).use { result ->
                val tensor = (result.get("image_embeds").orElse(null) ?: result.get(0)) as OnnxTensor
                val out = tensor.floatBuffer
                check(out.remaining() == Clip.DIMENSION) {
                    "expected ${Clip.DIMENSION} image dims, got ${out.remaining()}"
                }
                ClipVector(FloatArray(Clip.DIMENSION) { out.get(it) }).normalized()
            }
        }
        lastInferenceMs = (System.nanoTime() - started) / 1_000_000
        return vector
    }

    private fun ensureTaps(srcW: Int, srcH: Int) {
        if (srcW == tapSrcW && srcH == tapSrcH) return
        xLo = IntArray(size); xHi = IntArray(size); xFrac = FloatArray(size)
        yLo = IntArray(size); yHi = IntArray(size); yFrac = FloatArray(size)
        // The centred square of side min(srcW, srcH) is exactly what "resize shortest edge then centre
        // crop" keeps, so map the destination onto that sub-rectangle directly.
        val crop = min(srcW, srcH)
        buildTaps(crop, (srcW - crop) / 2, size, xLo, xHi, xFrac, srcW)
        buildTaps(crop, (srcH - crop) / 2, size, yLo, yHi, yFrac, srcH)
        tapSrcW = srcW
        tapSrcH = srcH
    }

    private fun buildTaps(
        cropSize: Int,
        cropOffset: Int,
        dstSize: Int,
        lo: IntArray,
        hi: IntArray,
        frac: FloatArray,
        srcLimit: Int
    ) {
        val scale = cropSize.toFloat() / dstSize.toFloat()
        for (d in 0 until dstSize) {
            val s = (d + 0.5f) * scale - 0.5f + cropOffset
            val base = kotlin.math.floor(s.toDouble()).toInt()
            val f = s - base
            lo[d] = base.coerceIn(0, srcLimit - 1)
            hi[d] = (base + 1).coerceIn(0, srcLimit - 1)
            frac[d] = if (f < 0f) 0f else if (f > 1f) 1f else f
        }
    }

    private fun preprocess(rgba: ByteBuffer, srcW: Int) {
        val plane = size * size
        for (c in 0 until 3) {
            val mean = Clip.CLIP_MEAN[c]
            val invStd = 1f / Clip.CLIP_STD[c]
            val channelBase = c * plane
            for (dy in 0 until size) {
                val row0 = yLo[dy] * srcW
                val row1 = yHi[dy] * srcW
                val fy = yFrac[dy]
                val outRow = channelBase + dy * size
                for (dx in 0 until size) {
                    val x0 = xLo[dx]
                    val x1 = xHi[dx]
                    val fx = xFrac[dx]
                    val p00 = (rgba.get(((row0 + x0) shl 2) + c).toInt() and 0xff).toFloat()
                    val p01 = (rgba.get(((row0 + x1) shl 2) + c).toInt() and 0xff).toFloat()
                    val p10 = (rgba.get(((row1 + x0) shl 2) + c).toInt() and 0xff).toFloat()
                    val p11 = (rgba.get(((row1 + x1) shl 2) + c).toInt() and 0xff).toFloat()
                    val top = p00 + (p01 - p00) * fx
                    val bottom = p10 + (p11 - p10) * fx
                    val v = (top + (bottom - top) * fy) * (1f / 255f)
                    inputTensorData.put(outRow + dx, (v - mean) * invStd)
                }
            }
        }
    }
}

/**
 * Encode a prompt to a unit-length 512-vector.
 *
 * First call in a session loads the 242MB text encoder; after that each prompt is a few milliseconds.
 * Hoist it into a `val` rather than calling it per frame — not because the encode is expensive, but
 * because nothing about a fixed prompt changes between frames.
 */
fun clipEncode(text: String): ClipVector = ClipText.encode(text)
