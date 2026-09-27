package afield.stacksl.cpu

import java.io.File

// ---------------------------------------------------------------------------------------------
// The oracle: 31 committed StackSL programs, the committed fragment template beside each one,
// and 31 committed 512x512 PNGs that a real GPU produced.
//
// `public/docs/assets/stacksl_gallery/` holds, per gallery entry `<name>`:
//   <name>_stackSL_.stacksl    the program (editable Field content, not a literal in any .kt)
//   <name>_fragment_.glsl      the fragment shader, with the literal `{{ stack.auto.program }}`
//   <name>_metadata_.yaml      docOrder / animate / timeSpeed / feedback
// and `public/docs/assets/stacksl/stacksl-<name>.png` is what the GPU drew for it.
//
// Two things about this oracle are better than they look and one is worse.
//
// BETTER: the fragment file is the *template*, not a dump of expanded GLSL, so a CPU render that
// reads it is forced back through the live `StackSLCompiler`. A stale dump would have let the two
// backends agree with each other while both disagreed with today's emitter.
//
// BETTER: 29 of the 31 templates are byte-identical (`fragmentTemplate(1.75f)` in
// `StackSLGalleryHarness`); the other 2 are the feedback template. So the render convention is
// pinned by the files rather than by this code.
//
// WORSE: the ground truth is 8-bit. The harness renders into `VK_FORMAT_R8G8B8A8_SRGB`, the
// window's encoding, so the GPU's answer is known to one sRGB level, and the harness's `main()`
// clamps to [0,1] first, so any disagreement smaller than half a level is invisible and any claim
// of "bit-exact" would be unfalsifiable. Everything below is therefore measured in 8-bit sRGB
// levels — the CPU side is encoded the same way ([galleryArgb]) — and "exact" means "quantises
// to the same byte".
// ---------------------------------------------------------------------------------------------

/** One gallery entry as it exists on disk. */
class GalleryEntry(
    val name: String,
    val program: String,
    val fragmentTemplate: String,
    val docOrder: Int,
    val animate: Boolean,
    val feedback: Boolean,
    /** The committed GPU still, or `null` if there is none. */
    val gpuPng: File?
)

/**
 * What the two backends did to each other, per program, in 8-bit levels.
 *
 * Everything is measured over the three colour channels of every pixel; alpha is excluded because
 * the harness's `main()` writes a constant `1.0` there and comparing a constant is decoration.
 *
 * [maxLevels] is the number that matters and the number a global epsilon would hide: a program can
 * have a mean under a hundredth of a level and still contain a handful of pixels that differ by
 * 200, because `floor()` inside a noise lattice turns a one-ulp disagreement into a different
 * lattice cell. [withinOneFraction] is the honest summary for that case, and [worstPixel] says
 * where to look.
 */
class GalleryAgreement(
    val name: String,
    val tier: AgreementTier,
    val meanLevels: Double,
    val maxLevels: Int,
    /** Fraction of pixels whose three channels are all within one 8-bit level. */
    val withinOneFraction: Double,
    /** Fraction of pixels that quantise to exactly the same three bytes. */
    val exactFraction: Double,
    val worstPixel: Pair<Int, Int>,
    val pixels: Int
) {
    override fun toString(): String =
        "%-22s %-11s mean=%7.4f max=%3d within1=%7.4f%% exact=%7.4f%% worst=(%d,%d)".format(
            name, tier.label, meanLevels, maxLevels, withinOneFraction * 100, exactFraction * 100,
            worstPixel.first, worstPixel.second
        )
}

/**
 * Why a program is or is not entitled to agree tightly, decided from the **emitted GLSL text**
 * rather than from a hand-kept list, so a program that gains an `fbm` gets reclassified without
 * anyone remembering to.
 *
 * The ordering matters: a program is put in the *weakest* tier any of its ingredients earns.
 */
enum class AgreementTier(val label: String, val why: String) {
    /**
     * Only operations IEEE-754 specifies exactly: `+ - * /`, `floor`, `fract`, `abs`, `min`,
     * `max`, `clamp`, `mix`, `step`, `dot`, matrix products. Two conforming implementations must
     * produce the same bits unless one of them contracts a multiply-add, so a disagreement here
     * is a real defect and is asserted as such.
     */
    EXACT_OPS("exact-ops", "arithmetic only — IEEE-754 exact, so agreement is required"),

    /**
     * Reaches `sin`, `cos`, `exp`, `log`, `pow`, `atan` or `sqrt`. GLSL leaves the accuracy of
     * every one of those **implementation-defined**, so no CPU implementation can be bit-exact
     * with a GPU even in principle. But they are all smooth, so the error stays the size of the
     * last bits of their argument: a tolerance of a level or two is the honest claim.
     */
    SMOOTH_TRANSCENDENTAL("transcendental", "sin/cos/exp/pow are implementation-defined in GLSL but smooth"),

    /**
     * Reaches `_hash21` (and therefore `noise`/`fbm`). `_hash21` is multiply/add/fract only and
     * so **exact operation by operation** — but it is called on `floor(p)`, and `_fbm` walks `p`
     * through five `mat2` warps before that `floor`. When accumulated rounding puts `p` on the
     * other side of an integer, the lattice cell changes and the value jumps by O(1). The mean
     * stays tiny and a few pixels differ enormously; that is chaos, not error, and it is reported
     * as a distribution rather than bounded by an epsilon.
     */
    HASH21_AMPLIFIED("hash21-chaos", "exact per op, but `fract`/`floor` amplify one ulp to O(1)"),

    /**
     * Reaches `_hash11`, which is `fract(sin(p * 127.1) * 43758.5453123)`: an
     * implementation-defined `sin` multiplied by 43758 and then `fract`ed. Both mechanisms at
     * once, and the worse one is unavoidable — this cannot agree even in principle.
     */
    HASH11_UNBOUNDED("hash11-chaos", "`fract(sin(x) * 43758)` — implementation-defined AND amplifying"),

    /** Samples a texture, so it has no still-image ground truth without the ping-pong history. */
    FEEDBACK("feedback", "samples the previous frame; needs iterated state, not a single pass");

    companion object {
        private val transcendentals = Regex("""\b(sin|cos|tan|asin|acos|atan|exp|exp2|log|log2|pow|sqrt|inversesqrt|normalize|length|distance|smoothstep)\s*\(""")

        /** Classify from the emitted GLSL of a program. */
        fun of(emittedGlsl: String): AgreementTier = when {
            Regex("""\btexture\s*\(""").containsMatchIn(emittedGlsl) -> FEEDBACK
            emittedGlsl.contains("_hash11(") -> HASH11_UNBOUNDED
            // `_hash31`/`_hash41` are the volumetric family's (fbm3, fbm4, worley3, …) versions of the
            // same fract-lattice construction in more dimensions, and amplify the same way.
            emittedGlsl.contains("_hash21(") || emittedGlsl.contains("_hash31(") || emittedGlsl.contains("_hash41(") -> HASH21_AMPLIFIED
            transcendentals.containsMatchIn(emittedGlsl) -> SMOOTH_TRANSCENDENTAL
            else -> EXACT_OPS
        }
    }
}

/**
 * Load the gallery from a checkout and compare the CPU backend against the committed GPU stills.
 *
 * This is a library object, not a test and not a `main`: the ratchet test drives it into a temp
 * directory and the contact-sheet tool drives it into the worktree, and neither duplicates the
 * loading, the tiering or the statistics.
 */
object StackSLCpuGallery {

    /** Where the programs and templates live, relative to a checkout root. */
    const val WORKSPACE_DIR = "public/docs/assets/stacksl_gallery"

    /** Where the committed GPU stills live, relative to a checkout root. */
    const val GPU_STILL_DIR = "public/docs/assets/stacksl"

    /** The side of the committed GPU stills; `StackSLGalleryHarness.OUTPUT_DIM`. */
    const val DIM = 512

    /** Load every entry, in the docs' own order. */
    fun load(repoRoot: File): List<GalleryEntry> {
        val workspace = File(repoRoot, WORKSPACE_DIR)
        val files = workspace.listFiles { f: File -> f.name.endsWith("_stackSL_.stacksl") }
            ?: error("StackSL gallery workspace not found: ${workspace.absolutePath}")
        require(files.isNotEmpty()) { "no *_stackSL_.stacksl files under ${workspace.absolutePath}" }
        return files.map { f ->
            val name = f.name.removeSuffix("_stackSL_.stacksl")
            val meta = File(workspace, "${name}_metadata_.yaml").takeIf { it.exists() }?.readLines() ?: emptyList()
            fun value(key: String): String? =
                meta.firstNotNullOfOrNull { Regex("""^\s*$key:\s*(.+?)\s*$""").find(it)?.groupValues?.get(1) }
            val template = File(workspace, "${name}_fragment_.glsl")
            require(template.exists()) { "no committed fragment template for gallery entry '$name'" }
            val png = File(repoRoot, "$GPU_STILL_DIR/stacksl-$name.png").takeIf { it.exists() }
            GalleryEntry(
                name = name,
                program = f.readText().trim(),
                fragmentTemplate = template.readText(),
                docOrder = value("docOrder")?.toIntOrNull() ?: Int.MAX_VALUE,
                animate = value("animate")?.toBooleanStrictOrNull() ?: false,
                feedback = value("feedback")?.toBooleanStrictOrNull() ?: false,
                gpuPng = png
            )
        }.sortedWith(compareBy({ it.docOrder }, { it.name }))
    }

    /** Render one entry on the CPU at [dim] square, through the committed template. */
    fun render(entry: GalleryEntry, dim: Int = DIM, parallel: Boolean = true): CpuRaster =
        StackSLCpu.renderProgram(
            program = entry.program,
            template = entry.fragmentTemplate,
            width = dim,
            height = dim,
            // The feedback template has a push-constant time; the still template bakes 1.75 in.
            pushParams = if (entry.feedback || entry.animate) floatArrayOf(0f, 0f, 0f, 0f) else null,
            samplers = if (entry.feedback) blackSampler else GlslSamplers.REFUSE,
            parallel = parallel
        )

    /**
     * The tier an entry falls in, from its *emitted* GLSL.
     *
     * A `feedback: true` metadata flag and a `texture()` call in the emitted GLSL are two
     * independent facts, and they are checked to agree — a program that samples but is not marked
     * feedback would be rendered against a still that the GPU never drew.
     */
    fun tierOf(entry: GalleryEntry): AgreementTier {
        val emitted = StackSLCpu.compile(entry.program).glsl
        val tier = AgreementTier.of(emitted)
        require((tier == AgreementTier.FEEDBACK) == entry.feedback) {
            "gallery entry '${entry.name}' is marked feedback=${entry.feedback} but its emitted GLSL " +
                (if (tier == AgreementTier.FEEDBACK) "does" else "does not") + " sample a texture"
        }
        return tier
    }

    /**
     * A CPU raster as the gallery's GPU target stores it: 8-bit sRGB
     * (`StackSLGalleryHarness.GALLERY_FORMAT`). Anything compared with or drawn beside a committed
     * still goes through here, so the two sides are never in different encodings.
     */
    fun galleryArgb(cpu: CpuRaster): IntArray = cpu.toSrgbArgb()

    /**
     * Compare a CPU raster against a committed GPU PNG.
     *
     * Both sides are reduced to 8-bit sRGB before comparing, because that is all the GPU side has.
     */
    fun compare(name: String, tier: AgreementTier, cpu: CpuRaster, gpuArgb: IntArray, gpuWidth: Int): GalleryAgreement {
        require(cpu.width == gpuWidth) { "$name: CPU raster is ${cpu.width} wide, GPU still is $gpuWidth" }
        val cpuArgb = galleryArgb(cpu)
        require(cpuArgb.size == gpuArgb.size) { "$name: ${cpuArgb.size} CPU pixels vs ${gpuArgb.size} GPU pixels" }

        var total = 0L
        var max = 0
        var worst = 0
        var within = 0
        var exact = 0
        for (i in cpuArgb.indices) {
            val a = cpuArgb[i]
            val b = gpuArgb[i]
            val dr = Math.abs(((a shr 16) and 0xff) - ((b shr 16) and 0xff))
            val dg = Math.abs(((a shr 8) and 0xff) - ((b shr 8) and 0xff))
            val db = Math.abs((a and 0xff) - (b and 0xff))
            total += (dr + dg + db).toLong()
            val worstHere = maxOf(dr, dg, db)
            if (worstHere > max) { max = worstHere; worst = i }
            if (worstHere <= 1) within++
            if (worstHere == 0) exact++
        }
        val n = cpuArgb.size
        return GalleryAgreement(
            name = name,
            tier = tier,
            meanLevels = total.toDouble() / (n * 3.0),
            maxLevels = max,
            withinOneFraction = within.toDouble() / n,
            exactFraction = exact.toDouble() / n,
            worstPixel = (worst % gpuWidth) to (worst / gpuWidth),
            pixels = n
        )
    }

    /**
     * A sampler that reports black with alpha 1.
     *
     * Only used to make a feedback program *renderable* for the contact sheet's sake — the first
     * frame of a ping-pong whose history is a cleared black target, which is exactly what the GPU
     * harness starts from. It is not a claim that the result matches the committed still, and the
     * feedback entries are excluded from every asserted statistic for that reason.
     */
    val blackSampler = GlslSamplers { _, _ -> GlslValue.vec4(0f, 0f, 0f, 1f) }
}
