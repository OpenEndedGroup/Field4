package afield.onnx

import afield.Box
import afield.KotlinInterface2
import afield.attic
import afield.macros.atticHasClipBlock
import afield.macros.clipValName
import afield.macros.standardExecutionPractice
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.min
import kotlin.script.experimental.api.KotlinType
import kotlin.script.experimental.api.ResultValue

/*
FIELD_TEST
id: onnx.clip.smoke
runner: jvm-main
entrypoint: afield.onnx.ClipSmokeTest
tags: [smoke, onnx, ml, clip, macros, cpu, network]
timeoutSec: 1200
expect:
  exitCode: 0
  stdoutContains:
    - "CLIP smoke tests passed"
*/
/**
 * CLIP end to end, including the compile-time baking that is the point of it.
 *
 * Two halves. The first is a numerical contract with two third-party ONNX graphs and a BPE vocabulary,
 * checked against reference values produced independently under Python's `onnxruntime` and `tokenizers`
 * on the same weights. The second drives a real [KotlinInterface2] to prove that `clip("…")` actually
 * disappears from the source it was written in and reappears as a constant in the box attic.
 *
 * What each assertion is defending:
 *
 *  * **The tokenizer agrees across implementations.** DJL wraps the same Rust `tokenizers` crate Python
 *    uses, but nothing guarantees identical configuration — and CLIP's text tower takes *no attention
 *    mask*, so it locates the end-of-text token by value. Wrong special tokens would mean pooling at the
 *    wrong position and a plausible-looking but meaningless vector.
 *  * **Both towers emit already-projected 512-d output.** Many CLIP exports expose only the unprojected
 *    hidden state, which would be 768-d and in the wrong space; this pins the ones that do not.
 *  * **CLIP's normalisation constants, not ImageNet's.** The two differ by a few hundredths and produce
 *    subtly wrong embeddings rather than obviously broken ones.
 *  * **The modality gap is real and is not a bug.** Image-text cosine sits near 0.21 while text-text sits
 *    near 0.88; the test asserts that ordering survives, because ordering is the only thing that carries
 *    information.
 *  * **Baking is cached.** A second expansion of the same prompt must not touch the model, which is the
 *    entire performance claim.
 *  * **Preview expansion never bakes.** Otherwise a caret moving over half-typed source would download
 *    242MB and fill the attic with prefixes of a word.
 *
 * `network` is a real tag: the first run fetches ~600MB of encoders plus a 2MB vocabulary.
 */
object ClipSmokeTest {

    // -- Reference values, from Python onnxruntime + tokenizers on these exact files --

    private val REF_IDS_STORM = longArrayOf(49406, 2819, 49407)
    private val REF_IDS_CAT = longArrayOf(49406, 320, 1125, 539, 320, 2368, 49407)

    private const val REF_COS_STORM_CALM = 0.881320f
    private const val REF_COS_CAT_DOG = 0.930980f
    private const val REF_COS_STORM_CAT = 0.803661f
    private const val REF_COS_CALM_DOG = 0.821835f

    private const val REF_IMAGE_COS_STORM = 0.210213f
    private const val REF_IMAGE_COS_CALM = 0.212032f
    private const val REF_IMAGE_COS_CAT = 0.224287f
    private const val REF_IMAGE_COS_DOG = 0.227066f

    /** First components of the normalised "storm" embedding. */
    private val REF_STORM_FIRST4 = floatArrayOf(-0.003122f, 0.013538f, 0.012194f, 0.026039f)

    /** Cosines are sums over 512 terms; this is tight enough to catch a wrong constant or channel. */
    private const val TOLERANCE = 1e-3f

    private var failures = 0

    @JvmStatic
    fun main(args: Array<String>) {
        println("CLIP smoke: cache root is ${OnnxModel.cacheRoot}")

        testVectorAlgebra()
        testBase64RoundTrip()
        testValNameIsStableAndModelScoped()

        println("CLIP smoke: ensuring encoders (text=${Clip.TEXT_MODEL.isPresent}, vision=${Clip.VISION_MODEL.isPresent})")
        Clip.TOKENIZER.ensureLocal()
        Clip.TEXT_MODEL.ensureLocal()
        Clip.VISION_MODEL.ensureLocal()

        testTokenizerMatchesPython()
        val texts = testTextEmbeddings()
        testSemanticAxis(texts)
        testImageEmbeddingAndModalityGap(texts)
        testClassifyIsSoftmaxOverCandidates(texts)
        testMacroBakesAndCaches()
        testMacroCompilesAndEvaluates()
        testPreviewExpansionDoesNotBake()
        testMacroRejectsNonLiteral()

        if (failures > 0) {
            System.err.println("CLIP smoke: $failures check(s) failed")
            OnnxRuntime.closeAll()
            kotlin.system.exitProcess(1)
        }

        println("CLIP smoke tests passed")
        // ORT's intra-op pool is non-daemon; a jvm-main entrypoint must let the JVM exit on its own.
        OnnxRuntime.closeAll()
    }

    // -- Pure, no model needed --

    private fun testVectorAlgebra() {
        val a = ClipVector(FloatArray(Clip.DIMENSION) { if (it == 0) 1f else 0f })
        val b = ClipVector(FloatArray(Clip.DIMENSION) { if (it == 1) 1f else 0f })
        near("orthogonal unit vectors have cosine 0", a.cosine(b), 0f)
        near("a unit vector has cosine 1 with itself", a.cosine(a), 1f)
        near("unit norm", a.norm, 1f)

        val axis = a axisTo b
        near("axis between orthogonal units is unit length", axis.norm, 1f)
        // (b - a)/sqrt(2) projected onto b is 1/sqrt(2).
        near("projection of the target onto the axis", b.project(axis), 0.70710678f)
        near("projection of the origin onto the axis", a.project(axis), -0.70710678f)

        val mean = ClipVector.mean(listOf(a, b))
        near("mean of two orthogonal units is normalised", mean.norm, 1f)
        near("mean sits equidistant from both", mean.cosine(a) - mean.cosine(b), 0f)

        val scaled = a * 3f
        near("scaling scales the norm", scaled.norm, 3f)
        near("cosine ignores scale", scaled.cosine(a), 1f)

        check("wrong dimensionality is rejected", runCatching { ClipVector(FloatArray(7)) }.isFailure) {
            "a 7-element ClipVector was accepted"
        }
    }

    private fun testBase64RoundTrip() {
        val original = ClipVector(FloatArray(Clip.DIMENSION) { (it - 256) / 512f })
        val encoded = original.toBase64()
        check("base64 of 512 floats is 2732 chars", encoded.length == 2732) { "got ${encoded.length}" }
        val decoded = ClipVector.fromBase64(encoded)
        var worst = 0f
        for (i in 0 until Clip.DIMENSION) worst = maxOf(worst, abs(original.values[i] - decoded.values[i]))
        check("fp32 base64 round trip is exact", worst == 0f) { "worst component delta $worst" }
        check("truncated base64 is rejected", runCatching { ClipVector.fromBase64("AAAA") }.isFailure) {
            "a 3-byte payload was accepted as a 512-vector"
        }
    }

    private fun testValNameIsStableAndModelScoped() {
        val a = clipValName("model-x", "storm")
        val b = clipValName("model-x", "storm")
        val c = clipValName("model-x", "calm")
        val d = clipValName("model-y", "storm")
        check("the same model and prompt give the same name", a == b) { "$a vs $b" }
        check("a different prompt gives a different name", a != c) { "both $a" }
        check("a different model gives a different name", a != d) { "both $a" }
        check("the name is a legal Kotlin identifier", Regex("""^__clip_[0-9a-f]{12}$""").matches(a)) { a }
    }

    // -- Against the Python oracle --

    private fun testTokenizerMatchesPython() {
        val storm = ClipText.tokenize("storm")
        check("\"storm\" tokenizes exactly as Python does", storm.contentEquals(REF_IDS_STORM)) {
            "got ${storm.toList()}, expected ${REF_IDS_STORM.toList()}"
        }
        val cat = ClipText.tokenize("a photo of a cat")
        check("a longer prompt tokenizes exactly as Python does", cat.contentEquals(REF_IDS_CAT)) {
            "got ${cat.toList()}, expected ${REF_IDS_CAT.toList()}"
        }
        check("start-of-text is first", storm.first() == 49406L) { "got ${storm.first()}" }
        check("end-of-text is last", storm.last() == 49407L) { "got ${storm.last()}" }
        println("  tokenizer: \"storm\" -> ${storm.toList()}")
    }

    private fun testTextEmbeddings(): Map<String, ClipVector> {
        val prompts = listOf("storm", "calm", "a photo of a cat", "a photo of a dog")
        val embeddings = ClipText.encodeAll(prompts)

        embeddings.forEach { (prompt, v) ->
            near("\"$prompt\" is unit length", v.norm, 1f)
            check("\"$prompt\" is all finite", v.values.all { it.isFinite() }) { "found NaN or Inf" }
        }

        val storm = embeddings.getValue("storm")
        for (i in REF_STORM_FIRST4.indices) {
            near("storm component $i matches Python", storm.values[i], REF_STORM_FIRST4[i])
        }

        fun cos(a: String, b: String) = embeddings.getValue(a).cosine(embeddings.getValue(b))
        near("cos(storm, calm)", cos("storm", "calm"), REF_COS_STORM_CALM)
        near("cos(cat, dog)", cos("a photo of a cat", "a photo of a dog"), REF_COS_CAT_DOG)
        near("cos(storm, cat)", cos("storm", "a photo of a cat"), REF_COS_STORM_CAT)
        near("cos(calm, dog)", cos("calm", "a photo of a dog"), REF_COS_CALM_DOG)

        // The property that makes an absolute threshold useless, asserted rather than described.
        check(
            "text-text cosines all sit in a narrow high cone",
            listOf(
                cos("storm", "calm"), cos("storm", "a photo of a cat"),
                cos("calm", "a photo of a dog"), cos("a photo of a cat", "a photo of a dog")
            ).all { it > 0.75f }
        ) { "expected every text-text cosine above 0.75" }
        check(
            "two phrasings of an animal photo are closer than storm is to calm",
            cos("a photo of a cat", "a photo of a dog") > cos("storm", "calm")
        ) { "cat/dog ${cos("a photo of a cat", "a photo of a dog")} vs storm/calm ${cos("storm", "calm")}" }

        println("  text: cos(storm,calm)=${"%.6f".format(cos("storm", "calm"))} " +
            "cos(cat,dog)=${"%.6f".format(cos("a photo of a cat", "a photo of a dog"))}")
        return embeddings
    }

    private fun testSemanticAxis(texts: Map<String, ClipVector>) {
        val axis = texts.getValue("calm") axisTo texts.getValue("storm")
        near("the axis is unit length", axis.norm, 1f)

        val stormward = texts.getValue("storm").project(axis)
        val calmward = texts.getValue("calm").project(axis)
        check("the axis orders its own endpoints", stormward > calmward) {
            "storm projects to $stormward, calm to $calmward"
        }
        // Antisymmetry: reversing the endpoints negates every projection.
        val reversed = texts.getValue("storm") axisTo texts.getValue("calm")
        near("reversing the axis negates the projection", texts.getValue("storm").project(reversed), -stormward)
    }

    private fun testImageEmbeddingAndModalityGap(texts: Map<String, ClipVector>) {
        val encoder = ClipImage()
        val image = encoder.encode(syntheticRgba(Clip.IMAGE_SIZE), Clip.IMAGE_SIZE, Clip.IMAGE_SIZE)
        near("the image embedding is unit length", image.norm, 1f)
        check("the image embedding is all finite", image.values.all { it.isFinite() }) { "found NaN or Inf" }

        near("cos(image, storm)", image.cosine(texts.getValue("storm")), REF_IMAGE_COS_STORM)
        near("cos(image, calm)", image.cosine(texts.getValue("calm")), REF_IMAGE_COS_CALM)
        near("cos(image, cat)", image.cosine(texts.getValue("a photo of a cat")), REF_IMAGE_COS_CAT)
        near("cos(image, dog)", image.cosine(texts.getValue("a photo of a dog")), REF_IMAGE_COS_DOG)

        // The modality gap: every image-text cosine is far below every text-text cosine. This is the
        // reason `classify` compares candidates against each other instead of against a constant.
        val imageTextMax = texts.values.maxOf { image.cosine(it) }
        check("every image-text cosine is far below the text-text cone", imageTextMax < 0.5f) {
            "highest image-text cosine was $imageTextMax"
        }
        println("  image: ${encoder.lastInferenceMs}ms on ${encoder.activeProvider}, " +
            "best text cosine ${"%.6f".format(imageTextMax)}")
    }

    private fun testClassifyIsSoftmaxOverCandidates(texts: Map<String, ClipVector>) {
        val image = ClipImage().encode(syntheticRgba(Clip.IMAGE_SIZE), Clip.IMAGE_SIZE, Clip.IMAGE_SIZE)
        val ranked = image.classify(texts)
        check("classify returns every candidate", ranked.size == texts.size) { "got ${ranked.size}" }
        near("probabilities sum to 1", ranked.sumOf { it.second.toDouble() }.toFloat(), 1f)
        check("classify is sorted most likely first", ranked.zipWithNext().all { it.first.second >= it.second.second }) {
            "not descending: ${ranked.map { it.second }}"
        }
        check("best() agrees with classify()", image.best(texts) == ranked.first()) {
            "best=${image.best(texts)} first=${ranked.first()}"
        }
        // Ranking must follow cosine order and be independent of the temperature.
        val byCosine = texts.entries.sortedByDescending { image.cosine(it.value) }.map { it.key }
        check("ranking follows cosine order", ranked.map { it.first } == byCosine) {
            "classify ${ranked.map { it.first }} vs cosine $byCosine"
        }
        val hotter = image.classify(texts, logitScale = 500f).map { it.first }
        check("temperature does not change ranking", hotter == ranked.map { it.first }) {
            "at 500: $hotter, at default: ${ranked.map { it.first }}"
        }
        check("classify of nothing is empty", image.classify(emptyMap()).isEmpty()) { "expected empty" }
        println("  classify: ${ranked.joinToString { "${it.first}=${"%.4f".format(it.second)}" }}")
    }

    // -- The macro, end to end through a real REPL --

    /**
     * The claim that matters: `clip("storm")` leaves the source, and a second pass costs nothing.
     *
     * Cache-hit is proven by wall time rather than a counter, because the thing being asserted is that
     * no session and no tokenizer were touched — and a hit that still loaded the model would pass a
     * counter-based check while failing the actual promise. The bound is deliberately loose (50ms
     * against a ≈5ms encode plus session lookup) so it cannot flake on a loaded machine.
     */
    private fun testMacroBakesAndCaches() = runBlocking {
        val ki = KotlinInterface2(filePrefix = "clip-macro")
        val box = Box("clip-macro")
        val expected = clipValName(Clip.TEXT_MODEL.id, "storm")

        val out = ki.applyMacros(box, """clip("storm")""")
        check("the call site becomes the attic val", out.trim() == expected) { "expanded to `${out.trim()}`" }
        check("the prompt literal is gone from the expansion", "\"storm\"" !in out) { out }

        val attic = box.attic
        check("the attic carries a keyed block", atticHasClipBlock(attic, expected)) { attic.take(200) }
        check("the attic declares the val", "val $expected" in attic) { attic.take(200) }
        check("the attic records the prompt for readers", "prompt: storm" in attic) { attic.take(200) }
        check("the attic records the model", "model: ${Clip.TEXT_MODEL.id}" in attic) { attic.take(200) }

        // The baked constant must equal what the encoder actually produces.
        val bakedBase64 = Regex("""fromBase64\("([^"]+)"\)""").find(attic)?.groupValues?.get(1)
        check("a base64 payload was written", bakedBase64 != null) { "no fromBase64 in attic" }
        if (bakedBase64 != null) {
            val baked = ClipVector.fromBase64(bakedBase64)
            near("the baked vector is unit length", baked.norm, 1f)
            near("the baked vector equals a fresh encode", baked.cosine(ClipText.encode("storm")), 1f)
            for (i in REF_STORM_FIRST4.indices) {
                near("baked component $i matches Python", baked.values[i], REF_STORM_FIRST4[i])
            }
        }

        val atticAfterFirst = box.attic
        val started = System.nanoTime()
        val second = ki.applyMacros(box, """clip("storm")""")
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        check("the second expansion is identical", second.trim() == expected) { "got `${second.trim()}`" }
        check("the attic is untouched on a cache hit", box.attic == atticAfterFirst) { "attic changed" }
        check("a cache hit is fast (no model, no tokenizer)", elapsedMs < 50) { "took ${elapsedMs}ms" }

        // A different prompt appends rather than replacing, so several constants coexist.
        ki.applyMacros(box, """clip("calm")""")
        val calmName = clipValName(Clip.TEXT_MODEL.id, "calm")
        check("a second prompt is appended", atticHasClipBlock(box.attic, calmName)) { box.attic.take(300) }
        check("the first prompt survives", atticHasClipBlock(box.attic, expected)) { box.attic.take(300) }

        // Two prompts in one expression both resolve.
        val both = ki.applyMacros(box, """clip("storm") to clip("calm")""")
        check("both call sites resolve", expected in both && calmName in both) { both }
        println("  macro: baked $expected, cache hit in ${elapsedMs}ms, attic ${box.attic.length} chars")
    }

    /** Preview expansion must never encode, download, or write the attic. */
    private fun testPreviewExpansionDoesNotBake() = runBlocking {
        val ki = KotlinInterface2(filePrefix = "clip-preview")
        val box = Box("clip-preview")
        // A prompt nothing has baked, deliberately looking like something half-typed.
        val out = ki.applyMacros(box, """clip("sto")""", emptySet(), true)
        check("preview leaves the attic empty", box.attic.isBlank()) { "attic became: ${box.attic.take(200)}" }
        check("preview leaves the call as written", """clip("sto")""" in out) { "expanded to: $out" }

        // But a prompt already in the attic still resolves in preview, so the display stays useful.
        val name = clipValName(Clip.TEXT_MODEL.id, "storm")
        box.setPropertyRaw(
            "attic",
            afield.macros.appendClipBlock("", name, Clip.TEXT_MODEL.id, "storm", ClipText.encode("storm").toBase64()),
            doResync = false
        )
        val cached = ki.applyMacros(box, """clip("storm")""", emptySet(), true)
        check("preview resolves an already-baked prompt", cached.trim() == name) { "got `${cached.trim()}`" }
    }

    /**
     * The emitted attic source is real Kotlin, and the baked constant survives the REPL.
     *
     * Everything above proves the macro rewrites *text*. This proves the text **compiles**: the attic
     * declaration evaluates, the rewritten body resolves the identifier it was given, and what comes back
     * is a unit-length embedding. A macro that emitted subtly invalid source — a stray quote inside the
     * base64, a wrong fully-qualified name, a `val` the REPL will not accept — passes every earlier
     * assertion and fails here.
     *
     * The two `exec` calls are what [afield.macros.checkAtticAndExecute] and the following body execution
     * do, run against the engine this test owns. `standardExecutionPractice(runSetupBlocks = true)` is
     * *not* used, and this is the interesting part: that path reaches for `box.ki` — the box's own lazily
     * created engine — whose startup is dispatched asynchronously and needs a pumping runloop that a
     * headless `jvm-main` smoke does not have, so it simply hangs. The existing pattern-rewrite smoke
     * sidesteps the same wall with `runSetupBlocks = false`. What is therefore *not* covered here is the
     * `box.ki` plumbing between attic and body — long-standing infrastructure that `ai()` already depends
     * on, and not something this macro can break.
     */
    private fun testMacroCompilesAndEvaluates() = runBlocking {
        val ki = KotlinInterface2(
            implicitReceivers = listOf(KotlinType(Box::class)),
            filePrefix = "clip-exec"
        )
        val box = Box("clip-exec")
        withTimeout(180_000) { ki.exec("1 + 1", implicits = listOf(box)) }

        val expanded = withTimeout(180_000) { ki.applyMacros(box, """clip("storm").norm""") }
        check("the body was rewritten to reference the constant", "clip(" !in expanded) { expanded }

        var compileErr = ""
        var evalErr = ""
        var evalException: Throwable? = null
        fun collect(): Triple<
            (List<kotlin.script.experimental.api.ScriptDiagnostic>) -> Unit,
            (List<kotlin.script.experimental.api.ScriptDiagnostic>) -> Unit,
            (Throwable) -> Unit
            > = Triple(
            { ds -> compileErr += ds.joinToString { it.message } },
            { ds -> evalErr += ds.joinToString { it.message } },
            { t: Throwable -> evalException = t }
        )

        // 1. The attic, exactly as checkAtticAndExecute would run it.
        val (onCompile, onEval, onThrow) = collect()
        withTimeout(180_000) { ki.exec(box.attic, onCompile, onEval, onThrow, listOf(box)) }
        check("the baked attic compiles", compileErr.isBlank()) { compileErr }
        check("the baked attic evaluates", evalErr.isBlank() && evalException == null) {
            "$evalErr / ${evalException?.message}"
        }

        // 2. The rewritten body, against the REPL state the attic just established.
        val result = withTimeout(180_000) { ki.exec(expanded, onCompile, onEval, onThrow, listOf(box)) }
        check("the rewritten body compiles", compileErr.isBlank()) { compileErr }
        check("the rewritten body evaluates", evalErr.isBlank() && evalException == null) {
            "$evalErr / ${evalException?.message}"
        }

        val value = (result as? ResultValue.Value)?.value
        check("the expression returns a value", value != null) { "result=$result" }
        val norm = (value as? Float) ?: (value as? Double)?.toFloat()
        check("the value is the norm of a real embedding", norm != null) { "got ${value?.javaClass}: $value" }
        if (norm != null) near("a baked vector round-trips through the REPL as unit length", norm, 1f)

        check(
            "the attic holds the baked constant",
            atticHasClipBlock(box.attic, clipValName(Clip.TEXT_MODEL.id, "storm"))
        ) { box.attic.take(200) }
        println("  exec path: clip(\"storm\").norm = $norm")
    }

    private fun testMacroRejectsNonLiteral() = runBlocking {
        val ki = KotlinInterface2(filePrefix = "clip-nonliteral")
        val box = Box("clip-nonliteral")
        val outcome = runCatching { ki.applyMacros(box, """val p = "x"; clip(p)""") }
        check("a non-literal prompt is a clear error", outcome.isFailure) { "clip(p) was accepted" }
        val message = outcome.exceptionOrNull()?.message ?: ""
        check("the error points at clipEncode", "clipEncode" in message) { "message was: $message" }
        check("nothing was baked", box.attic.isBlank()) { "attic became: ${box.attic.take(200)}" }
    }

    /** Integer-only, so Kotlin and NumPy build a byte-identical frame. See `OnnxSmokeTest`. */
    private fun syntheticRgba(n: Int): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(n * n * 4).order(ByteOrder.nativeOrder())
        val span = n - 1
        val radial = span.toLong() * span.toLong()
        for (y in 0 until n) {
            for (x in 0 until n) {
                val r = x * 255 / span
                val g = y * 255 / span
                val b = min(255L, (x.toLong() * x.toLong() + y.toLong() * y.toLong()) * 255L / radial).toInt()
                val o = (y * n + x) shl 2
                buffer.put(o, r.toByte())
                buffer.put(o + 1, g.toByte())
                buffer.put(o + 2, b.toByte())
                buffer.put(o + 3, 255.toByte())
            }
        }
        return buffer
    }

    private fun near(what: String, actual: Float, expected: Float, tolerance: Float = TOLERANCE) {
        check(what, abs(actual - expected) <= tolerance) {
            "expected $expected +/- $tolerance, got $actual"
        }
    }

    private inline fun check(what: String, condition: Boolean, detail: () -> String) {
        if (condition) return
        failures++
        System.err.println("FAIL: $what -- ${detail()}")
    }
}
