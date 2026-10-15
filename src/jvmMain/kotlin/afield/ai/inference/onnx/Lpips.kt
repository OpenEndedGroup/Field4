@file:afield.runtime.completion.CompletionCategory("Machine learning")

package afield.ai.inference.onnx

import afield.app.diagnostics.logging.lprintln
import afield.media.image.Img
import afield.media.image.resize
import afield.media.image.toRGB
import ai.onnxruntime.OnnxTensor
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * LPIPS: how different two pictures look, as a learned perceptual distance.
 *
 * `0` is identical, and larger is more different; for natural images most distances fall between 0
 * and about 0.8. It is Zhang et al.'s "Learned Perceptual Image Patch Similarity" (CVPR 2018,
 * [richzhang/PerceptualSimilarity](https://github.com/richzhang/PerceptualSimilarity), BSD-2),
 * version 0.1 with the AlexNet backbone: both images go through AlexNet's five convolution stages,
 * each stage's activations are normalised per position, and the squared differences are weighted
 * by a linear layer trained on human judgements of which of two distortions looks closer to the
 * original. So it compares edges, blobs and textures where they are, not pixel colours.
 *
 * ```kotlin
 * val d = Lpips.distance(imgFromFile("a.png"), imgFromFile("b.png"))
 * val ds = Lpips.distances(candidates, target)   // many against one, the target computed once
 * ```
 *
 * Both images must be the same size, at least 32 pixels on each side, RGB or grey in `0..1`.
 *
 * ## Size matters
 *
 * AlexNet's first stage has stride 4 and the network halves twice more, so at 32x32 the three
 * deepest stages are 1x1: they still see the image, but only as a whole. Measured on fixed
 * pairs (see `tools/export_lpips_onnx.py`), a yellow disc against a colour ramp is 0.42 at 32
 * pixels, 0.68 at 64 and 0.83 at 128; the same pair, the same verdict, a compressed scale.
 * Compare distances made at one size with each other, not across sizes.
 *
 * ## Where the model comes from
 *
 * No prebuilt AlexNet LPIPS ONNX file was found from a source worth trusting, so Field exports its
 * own: `tools/export_lpips_onnx.py` builds it from the `lpips` package on PyPI (0.1.4, which ships
 * the trained linear weights) and torchvision's ImageNet AlexNet, checks the ONNX graph against the
 * PyTorch module (agreement 1.2e-7 on twelve pairs), and installs a 9.9MB file in the
 * [OnnxModel] cache. [ensureModel] runs that script through `uv` the first time it is needed — a
 * one-off of about a minute that downloads PyTorch if `uv` has not cached it — and checks the result
 * against [MODEL]'s pinned sha256, which two exports with the pinned package versions reproduced
 * byte for byte.
 *
 * ## Its gradient
 *
 * [distanceAndGradientPlanar] also returns d distance / d candidate pixel, from a second exported
 * graph ([GRAD_MODEL]) that the same script builds and checks against PyTorch's autograd. That is
 * what lets a fit descend on LPIPS: `afield.lang.stacksl.cpu.StackSLFit` seeds its per-pixel tapes with
 * it.
 */
object Lpips {

    /** The exported model: `candidate [N,3,H,W]`, `reference [N or 1,3,H,W]` in, `distance [N]` out. */
    val MODEL = OnnxModel(
        id = "lpips-alex",
        url = "",
        fileName = "lpips-alex/lpips_alex.onnx",
        sha256 = "6b99961b1b2b5a2c475d74fcaed30e26151833c06db4fdd48e81468b70ff35c3",
        note = "LPIPS v0.1, AlexNet backbone, fp32, exported by tools/export_lpips_onnx.py."
    )

    /**
     * The gradient model: the same inputs, `distance [N]` and `gradient [N,3,H,W]` out, the
     * gradient being d `distance[i]` / d `candidate[i]` on the `0..1` pixels.
     *
     * Exported by the same script (`--model grad`) as a hand-written vector-Jacobian product, since
     * an ONNX export traces only forward code. The script checks it against `torch.autograd.grad`
     * on the PyTorch LPIPS module and refuses a disagreement beyond 1e-4 relative; measured, the
     * worst of fourteen pairs was 5.4e-6. Max-pool ties are the one wrinkle: see [distanceAndGradientPlanar].
     */
    val GRAD_MODEL = OnnxModel(
        id = "lpips-alex-grad",
        url = "",
        fileName = "lpips-alex/lpips_alex_grad.onnx",
        sha256 = "8f9569d2fabe9f68cc5fc0e6d071d88347832ff2733589e725d840df53986e7c",
        note = "LPIPS v0.1 AlexNet distance and its gradient w.r.t. the candidate, fp32, exported by tools/export_lpips_onnx.py --model grad."
    )

    /** Smallest side the network accepts: AlexNet's deepest stage is 1x1 at 32. */
    const val MIN_SIDE: Int = 32

    /** The command [ensureModel] runs, with the package versions the pinned digest was made with. */
    val EXPORT_ARGS: List<String> = listOf(
        "run", "--no-project", "--python", "3.12",
        "--with", "torch==2.14.1", "--with", "torchvision==0.29.1", "--with", "lpips==0.1.4",
        "--with", "onnx==1.23.2", "--with", "onnxruntime==1.30.0", "--with", "numpy==2.5.3",
        "python", "tools/export_lpips_onnx.py", "--out"
    )

    /** The command [ensureGradModel] runs: [EXPORT_ARGS]' environment, the gradient graph only. */
    val GRAD_EXPORT_ARGS: List<String> = EXPORT_ARGS.dropLast(1) + listOf("--model", "grad", "--grad-out")

    @Volatile
    private var verified: Path? = null

    @Volatile
    private var verifiedGrad: Path? = null

    /**
     * The model file, exporting it first if it is not in the cache, and checked against [MODEL]'s
     * sha256 once per process.
     *
     * @throws IllegalStateException if the export fails, or produces a file with another digest
     */
    @Synchronized
    fun ensureModel(): Path {
        verified?.let { return it }
        return ensure(MODEL, EXPORT_ARGS).also { verified = it }
    }

    /**
     * The gradient model file, exported on first use and checked against [GRAD_MODEL]'s sha256,
     * exactly as [ensureModel] does for the forward model.
     */
    @Synchronized
    fun ensureGradModel(): Path {
        verifiedGrad?.let { return it }
        return ensure(GRAD_MODEL, GRAD_EXPORT_ARGS).also { verifiedGrad = it }
    }

    private fun ensure(model: OnnxModel, args: List<String>): Path {
        val path = model.localPath
        if (!Files.isRegularFile(path)) export(path, args)
        val actual = sha256(path)
        check(actual.equals(model.sha256, ignoreCase = true)) {
            "LPIPS model at $path has sha256 $actual, expected ${model.sha256}. Delete it and let " +
                "Field export it again, or run tools/export_lpips_onnx.py by hand to see why it differs."
        }
        return path
    }

    private fun export(path: Path, args: List<String>) {
        val root = repoRoot()
        val uv = uvExecutable()
        val command = listOf(uv) + args + path.toString()
        lprintln("info,onnx", "exporting LPIPS to $path: ${command.joinToString(" ")}")
        val process = ProcessBuilder(command).directory(root).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(30, TimeUnit.MINUTES) && process.exitValue() == 0) {
            "LPIPS export failed (exit ${runCatching { process.exitValue() }.getOrNull()}):\n${output.takeLast(4000)}"
        }
        check(Files.isRegularFile(path)) { "LPIPS export reported success but wrote no $path:\n${output.takeLast(2000)}" }
    }

    /** The cached session. */
    val session: OnnxRuntime.LoadedSession get() = OnnxRuntime.session(ensureModel())

    /** The cached session of the gradient model. */
    val gradSession: OnnxRuntime.LoadedSession get() = OnnxRuntime.session(ensureGradModel())

    /** LPIPS distance between [a] and [b], which must be the same size. */
    fun distance(a: Img, b: Img): Double = distances(listOf(a), b)[0]

    /** LPIPS distance from each of [candidates] to [reference]; all must be the same size. */
    fun distances(candidates: List<Img>, reference: Img): DoubleArray {
        require(candidates.isNotEmpty()) { "need at least one candidate" }
        val w = reference.width
        val h = reference.height
        for (c in candidates) require(c.width == w && c.height == h) {
            "candidate is ${c.width}x${c.height} but the reference is ${w}x$h"
        }
        val n = candidates.size
        val batch = FloatArray(n * 3 * w * h)
        for ((i, c) in candidates.withIndex()) planar(c, batch, i * 3 * w * h)
        val ref = FloatArray(3 * w * h)
        planar(reference, ref, 0)
        return distancesPlanar(batch, n, w, h, ref)
    }

    /**
     * The same, on planar data already laid out as the network wants it: [candidates] is
     * `n x 3 x height x width` floats in `0..1`, [reference] is one `3 x height x width` image.
     * For callers scoring thousands of renders, who should not pay for an [Img] per render.
     */
    fun distancesPlanar(candidates: FloatArray, n: Int, width: Int, height: Int, reference: FloatArray): DoubleArray {
        require(width >= MIN_SIDE && height >= MIN_SIDE) { "LPIPS needs at least ${MIN_SIDE}x$MIN_SIDE, got ${width}x$height" }
        require(candidates.size == n * 3 * width * height) { "candidates hold ${candidates.size} floats, expected ${n * 3 * width * height}" }
        require(reference.size == 3 * width * height) { "reference holds ${reference.size} floats, expected ${3 * width * height}" }
        val loaded = session
        val env = OnnxRuntime.env
        return OnnxTensor.createTensor(env, buffer(candidates), longArrayOf(n.toLong(), 3, height.toLong(), width.toLong())).use { a ->
            OnnxTensor.createTensor(env, buffer(reference), longArrayOf(1, 3, height.toLong(), width.toLong())).use { b ->
                loaded.session.run(mapOf("candidate" to a, "reference" to b)).use { result ->
                    val out = (result.get("distance").orElse(null) ?: result.get(0)) as OnnxTensor
                    val fb = out.floatBuffer
                    DoubleArray(n) { fb.get(it).toDouble() }
                }
            }
        }
    }

    /**
     * The LPIPS distance from [candidate] to [reference] and its gradient with respect to every
     * candidate value: planar `3 x height x width` floats in `0..1`, as [distancesPlanar] takes
     * them. The gradient has the candidate's layout.
     *
     * It is the derivative of exactly the distance [distancesPlanar] computes (the two models agree
     * on the distance to 1e-7), through the network's `0..1 -> -1..1` input mapping, so a caller
     * chains it straight onto whatever produced the pixels. It is the gradient of the function as
     * the network sees it, so two things a caller may expect are not in it: a value outside `0..1`
     * is not clamped here (clamp before, and carry the clamp's zero derivative yourself), and where
     * two positions in an AlexNet max-pool window are exactly equal — common on flat-coloured
     * pictures — the gradient goes to the one ONNX Runtime picks, which can differ from PyTorch's.
     * Either is a valid subgradient; `tools/export_lpips_onnx.py` checks that every disagreement it
     * sees is such a tie.
     */
    fun distanceAndGradientPlanar(candidate: FloatArray, width: Int, height: Int, reference: FloatArray): Pair<Double, FloatArray> {
        val (d, g) = distancesAndGradientsPlanar(candidate, 1, width, height, reference, 1)
        return d[0] to g
    }

    /**
     * Batched [distanceAndGradientPlanar]: [n] candidates against [references] of batch [m], which
     * is `1` (one broadcast reference) or [n] (one each, as the regions of a tiled image are). The
     * gradients come back concatenated, `n x 3 x height x width`.
     */
    fun distancesAndGradientsPlanar(
        candidates: FloatArray, n: Int, width: Int, height: Int, references: FloatArray, m: Int = 1
    ): Pair<DoubleArray, FloatArray> {
        require(width >= MIN_SIDE && height >= MIN_SIDE) { "LPIPS needs at least ${MIN_SIDE}x$MIN_SIDE, got ${width}x$height" }
        require(m == 1 || m == n) { "references must be a batch of 1 or of $n, got $m" }
        val one = 3 * width * height
        require(candidates.size == n * one) { "candidates hold ${candidates.size} floats, expected ${n * one}" }
        require(references.size == m * one) { "references hold ${references.size} floats, expected ${m * one}" }
        val loaded = gradSession
        val env = OnnxRuntime.env
        return OnnxTensor.createTensor(env, buffer(candidates), longArrayOf(n.toLong(), 3, height.toLong(), width.toLong())).use { a ->
            OnnxTensor.createTensor(env, buffer(references), longArrayOf(m.toLong(), 3, height.toLong(), width.toLong())).use { b ->
                loaded.session.run(mapOf("candidate" to a, "reference" to b)).use { result ->
                    val dist = (result.get("distance").orElse(null) as OnnxTensor).floatBuffer
                    val grad = (result.get("gradient").orElse(null) as OnnxTensor).floatBuffer
                    val g = FloatArray(n * one)
                    grad.get(g)
                    DoubleArray(n) { dist.get(it).toDouble() } to g
                }
            }
        }
    }

    /**
     * [img] resampled to [width] x [height] and expanded to RGB: the usual way to bring two
     * pictures to one size before comparing them.
     */
    fun conform(img: Img, width: Int, height: Int): Img {
        val rgb = if (img.channels == 3) img else img.toRGB()
        return if (rgb.width == width && rgb.height == height) rgb else rgb.resize(width, height)
    }

    private fun planar(img: Img, out: FloatArray, offset: Int) {
        val rgb = if (img.channels == 3) img else img.toRGB()
        val plane = rgb.width * rgb.height
        for (p in 0 until plane) for (c in 0 until 3) {
            out[offset + c * plane + p] = rgb.data[p * 3 + c].coerceIn(0f, 1f)
        }
    }

    private fun buffer(values: FloatArray) =
        ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().also {
            it.put(values)
            it.rewind()
        }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { s ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val r = s.read(buf)
                if (r <= 0) break
                digest.update(buf, 0, r)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun repoRoot(): File {
        var at: File? = File(System.getProperty("user.dir")).absoluteFile
        while (at != null) {
            if (File(at, "tools/export_lpips_onnx.py").isFile) return at
            at = at.parentFile
        }
        error("cannot export the LPIPS model: tools/export_lpips_onnx.py is not above ${System.getProperty("user.dir")}")
    }

    /** `uv`, looked for by path as well as on `PATH`: a JVM launched from a GUI rarely has it on `PATH`. */
    private fun uvExecutable(): String {
        System.getenv("AFIELD_UV")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val searched = System.getenv("PATH").orEmpty().split(File.pathSeparator).filter { it.isNotBlank() }.map { Paths.get(it, "uv") } +
            listOf(Paths.get("/opt/homebrew/bin/uv"), Paths.get("/usr/local/bin/uv"), Paths.get(System.getProperty("user.home"), ".local", "bin", "uv"))
        return searched.firstOrNull { Files.isExecutable(it) }?.toString()
            ?: error("cannot export the LPIPS model: no `uv` found (set AFIELD_UV to its path)")
    }
}
