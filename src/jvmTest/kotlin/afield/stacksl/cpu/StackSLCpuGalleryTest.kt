package afield.stacksl.cpu

import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The CPU backend against 31 frames a real GPU drew.
 *
 * `public/docs/assets/stacksl_gallery/` holds each program and the fragment template it was
 * rendered through; `public/docs/assets/stacksl/stacksl-<name>.png` is the 512x512 still the GPU
 * produced. So every one of these numbers is a comparison against committed bytes, which has one
 * consequence worth stating because it is what makes the bounds below tight instead of generous:
 * **the ground truth cannot move.** There is no driver variance to leave headroom for. A bound here
 * can therefore sit one level above the measurement rather than an order of magnitude above it,
 * and any change that moves a pixel goes red.
 *
 * WHY THE BOUNDS ARE PER MECHANISM AND NOT ONE EPSILON. A single global tolerance would have to be
 * loose enough to admit `posterize-shaper`, which differs by a full 255 levels at 118 pixels out of
 * 262144 — and at that tolerance the test would no longer be able to fail for `uv-coordinates`,
 * which is bit-identical. So each program is classified by [AgreementTier] from its own emitted
 * GLSL, and each tier is held to what its ingredients actually permit:
 *
 *   - arithmetic only: IEEE-754 exact, so agreement is REQUIRED;
 *   - `sin`/`cos`/`exp`/`pow`: implementation-defined in GLSL, but smooth, so within a level;
 *   - `_hash21`: exact per operation but `floor`-amplified, so a distribution rather than a bound;
 *   - `_hash11` = `fract(sin(x) * 43758)`: cannot agree even in principle, so no bound at all —
 *     what is asserted instead is the STRUCTURE, which does agree, and that is the evidence that
 *     the disagreement is the hash and not the backend.
 *
 * Nothing here writes into the worktree. The contact sheet a person actually looks at is produced
 * by `StackSLCpuContactSheet`'s `main`, run by hand.
 */
class StackSLCpuGalleryTest {

    private val root = File(System.getProperty("user.dir"))

    private fun readArgb(file: File): Pair<IntArray, Int> {
        val image = ImageIO.read(file) ?: error("could not read ${file.absolutePath}")
        val out = IntArray(image.width * image.height)
        image.getRGB(0, 0, image.width, image.height, out, 0, image.width)
        return out to image.width
    }

    /** Render every non-feedback entry once and measure it. Shared by the assertions below. */
    private fun measureAll(): List<GalleryAgreement> {
        val entries = StackSLCpuGallery.load(root)
        return entries.mapNotNull { entry ->
            val tier = StackSLCpuGallery.tierOf(entry)
            if (tier == AgreementTier.FEEDBACK) return@mapNotNull null
            val png = entry.gpuPng ?: return@mapNotNull null
            val (gpu, width) = readArgb(png)
            if (width != StackSLCpuGallery.DIM) return@mapNotNull null
            val cpu = StackSLCpuGallery.render(entry)
            StackSLCpuGallery.compare(entry.name, tier, cpu, gpu, width)
        }
    }

    @Test
    fun `the CPU backend reproduces the committed GPU gallery, per mechanism`() {
        val stats = measureAll()
        stats.sortedWith(compareBy({ it.tier.ordinal }, { -it.maxLevels })).forEach { println(it) }

        // The oracle itself. If the gallery shrinks, this test quietly stops testing most of it.
        assertEquals(29, stats.size, "expected 29 non-feedback gallery entries with a GPU still")

        fun tier(t: AgreementTier) = stats.filter { it.tier == t }

        // Arithmetic only: exact is required, and a single level of slack is allowed ONLY because
        // the GPU may contract a multiply-add that this interpreter performs unfused.
        tier(AgreementTier.EXACT_OPS).also { assertEquals(2, it.size) }.forEach {
            assertTrue(it.maxLevels <= 1, "$it: arithmetic-only programs must agree to within a level")
            assertEquals(1.0, it.withinOneFraction, 0.0, "$it")
        }

        // Implementation-defined but smooth. Measured max: 1 level, on all five.
        tier(AgreementTier.SMOOTH_TRANSCENDENTAL).also { assertEquals(5, it.size) }.forEach {
            assertTrue(it.maxLevels <= 1, "$it: a smooth transcendental should not move a pixel by more than a level")
            assertEquals(1.0, it.withinOneFraction, 0.0, "$it")
        }

        // `_hash21`: exact per operation, `floor`-amplified. Measured worst mean 0.1673 levels and
        // worst within-one 96.26%; the bounds are just outside those, and the *mean* is the thing
        // being bounded because the max legitimately reaches 255 on a hard threshold.
        val hash21 = tier(AgreementTier.HASH21_AMPLIFIED).also { assertEquals(21, it.size) }
        hash21.forEach {
            assertTrue(it.meanLevels < 0.20, "$it: mean disagreement should stay far below one level")
            assertTrue(it.withinOneFraction > 0.96, "$it: nearly every pixel should agree to within a level")
        }
        // And most of them do not merely nearly agree — they agree everywhere. Measured: 17 of 21.
        val perfect = hash21.count { it.maxLevels <= 1 }
        println("hash21 programs agreeing at EVERY pixel to within one level: $perfect of ${hash21.size}")
        assertTrue(perfect >= 17, "expected at least 17 of ${hash21.size} to agree everywhere, got $perfect")

        // Across the whole gallery: measured 24 of 29 agree at every pixel to within one level.
        val everywhere = stats.count { it.maxLevels <= 1 }
        println("gallery programs agreeing at EVERY pixel to within one level: $everywhere of ${stats.size}")
        assertTrue(everywhere >= 24, "expected at least 24 of ${stats.size}, got $everywhere")

        // And 28 of 29 agree at 96% of pixels or better — everything except `sample-hold`.
        val nearlyAll = stats.count { it.withinOneFraction > 0.96 }
        assertTrue(nearlyAll >= 28, "expected at least 28 of ${stats.size} at >96% within one level, got $nearlyAll")
    }

    /**
     * `uv-coordinates` is `uv saturate`: nothing but a swizzle and a clamp. It must be **bit
     * identical**, all 262144 pixels, and that is also the only proof of the raster convention
     * that cannot be argued with — a Y flip, a half-pixel offset or a truncating quantiser each
     * makes this impossible.
     */
    @Test
    fun `the simplest program is bit-identical, which pins the raster convention`() {
        val entry = StackSLCpuGallery.load(root).single { it.name == "uv-coordinates" }
        val (gpu, width) = readArgb(entry.gpuPng!!)
        val cpu = StackSLCpuGallery.render(entry)
        val agreement = StackSLCpuGallery.compare(entry.name, AgreementTier.EXACT_OPS, cpu, gpu, width)
        println(agreement)
        assertEquals(0, agreement.maxLevels, "uv saturate must be bit-identical")
        assertEquals(1.0, agreement.exactFraction, 0.0)
        assertEquals(StackSLCpuGallery.DIM * StackSLCpuGallery.DIM, agreement.pixels)
    }

    /**
     * The one program that cannot agree, and the evidence that its disagreement is the hash.
     *
     * `sample-hold` reaches `_hash11` = `fract(sin(p * 127.1) * 43758.5453123)`, evaluated at a
     * large argument. `sin` has no accuracy requirement in GLSL, and multiplying its result by
     * 43758 before taking `fract` destroys whatever agreement there was — mean disagreement 96.7
     * levels out of 255, i.e. unrelated colours.
     *
     * But the sample-and-hold LATTICE is `floor` of an exactly-computed phase, so it is the same on
     * both backends, and that is checkable: the columns at which the image changes are identical.
     * Same structure, different values. If the backend were simply wrong the boundaries would move
     * too, so this assertion is what separates "the hash is unbounded" from "we have a bug".
     */
    @Test
    fun `sample-hold disagrees in value but not in structure`() {
        val entry = StackSLCpuGallery.load(root).single { it.name == "sample-hold" }
        assertEquals(AgreementTier.HASH11_UNBOUNDED, StackSLCpuGallery.tierOf(entry))
        val (gpu, width) = readArgb(entry.gpuPng!!)
        val cpu = StackSLCpuGallery.render(entry).toArgb()

        fun boundaries(argb: IntArray, row: Int): List<Int> =
            (1 until width).filter { argb[row * width + it] != argb[row * width + it - 1] }

        val row = StackSLCpuGallery.DIM / 2
        val cpuEdges = boundaries(cpu, row)
        val gpuEdges = boundaries(gpu, row)
        println("sample-hold band boundaries: cpu=${cpuEdges.size} gpu=${gpuEdges.size}")
        assertTrue(cpuEdges.size > 20, "expected a banded image, found ${cpuEdges.size} boundaries")
        assertEquals(gpuEdges, cpuEdges, "the sample-and-hold lattice must be identical even though the values are not")
    }

    /**
     * The two feedback entries are excluded, and this says why rather than leaving a silent gap.
     *
     * They sample the previous frame, so a still is the end of a 60-frame ping-pong and not a
     * function of `fragUV`. The CPU backend can run them — [CpuTexture2D] exists and the word
     * coverage test exercises all 61 sampling words — but reproducing the GPU's still would mean
     * reproducing the GPU's sampler and 60 frames of divergence, which is not a claim worth making.
     *
     * The exclusion is checked from two independent facts that must agree: the `feedback: true`
     * metadata flag, and a `texture()` call in the emitted GLSL.
     */
    @Test
    fun `feedback entries are excluded, from two facts that agree`() {
        val entries = StackSLCpuGallery.load(root)
        assertEquals(31, entries.size)
        val feedback = entries.filter { StackSLCpuGallery.tierOf(entry = it) == AgreementTier.FEEDBACK }
        assertEquals(listOf("diffusion-feedback", "feedback-trails"), feedback.map { it.name }.sorted())
        feedback.forEach { assertTrue(it.feedback, "${it.name} samples a texture but is not marked feedback") }
    }

    /**
     * A feedback program runs on the CPU under a real ping-pong, with no GPU and no graphics
     * context at all.
     *
     * This is not compared against anything — see above for why. What it establishes is that the
     * backend is not *structurally* unable to do feedback: two [CpuTexture2D] buffers swapped each
     * frame reproduce `PingPongRenderToTexture`'s idea, and after 12 iterations the image has
     * developed structure rather than collapsing to a constant, which a broken sampler would do.
     */
    @Test
    fun `a feedback program develops structure under a CPU ping-pong`() {
        val entry = StackSLCpuGallery.load(root).single { it.name == "feedback-trails" }
        val dim = 64
        var previous = CpuTexture2D(dim, dim, FloatArray(dim * dim * 4) { if (it % 4 == 3) 1f else 0f })
        var raster: CpuRaster? = null
        repeat(12) { frame ->
            raster = StackSLCpu.renderProgram(
                program = entry.program,
                template = entry.fragmentTemplate,
                width = dim, height = dim,
                pushParams = floatArrayOf(frame / 24f, 0f, 0f, 0f),
                samplers = previous,
                parallel = false
            )
            previous = CpuTexture2D(raster!!)
        }
        val out = raster!!
        assertTrue(out.rgba.all { it.isFinite() }, "a CPU feedback loop must stay finite")
        val distinct = out.toArgb().toSet().size
        println("CPU ping-pong feedback after 12 frames: $distinct distinct colours in ${dim * dim} pixels")
        assertTrue(distinct > 8, "expected the feedback loop to develop structure, got $distinct distinct colours")
    }

    /**
     * The contact sheet generator runs end to end, into a temporary directory.
     *
     * A test must never write a committed asset, so this proves the tool works without being the
     * thing that produces the committed picture. It is also the only place the AWT half of the
     * backend is exercised at all.
     */
    @Test
    fun `the contact sheet generator produces a sheet and a table`() {
        val temp = Files.createTempDirectory("stacksl-cpu-sheet").toFile()
        try {
            val stats = StackSLCpuContactSheet.build(root, temp)
            val sheet = File(temp, "gallery_contact_sheet.png")
            val table = File(temp, "gallery_agreement.md")
            assertTrue(sheet.length() > 10_000, "contact sheet is only ${sheet.length()} bytes")
            assertTrue(table.readText().contains("| `uv-coordinates` |"), "table is missing entries")
            assertEquals(31, stats.size, "every entry with a GPU still should appear in the sheet's statistics")
            val image = ImageIO.read(sheet)
            // Not black: the sheet has to be a picture, and "wrote a file" is not verification.
            val pixels = IntArray(image.width * image.height)
            image.getRGB(0, 0, image.width, image.height, pixels, 0, image.width)
            val bright = pixels.count { ((it shr 16) and 0xff) + ((it shr 8) and 0xff) + (it and 0xff) > 96 }
            assertTrue(bright > pixels.size / 20, "the contact sheet is nearly black: only $bright of ${pixels.size} lit")
        } finally {
            temp.deleteRecursively()
        }
    }
}
