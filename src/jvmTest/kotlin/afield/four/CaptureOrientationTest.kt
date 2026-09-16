package afield.four

import afield.util.PngEncoder
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the per-path capture row order declared in [CaptureOrientation], in three layers that
 * check each other.
 *
 * The bug this exists to prevent is not "someone flips Y the wrong way" — it is "someone reasons
 * about row order from a neighbouring comment". `62fd65d0a` fixed the swapchain capture and its
 * commit message asserted that the offscreen `writeJpeg` path was genuinely bottom-up; it was not,
 * and the render-target captures stayed inverted for another day because nothing could contradict
 * the claim. So:
 *
 * - **Layer A, premise.** Drive a corner-marked buffer through the *real* encode primitives and
 *   decode the result back, asserting which corner landed where. This is deliberately not a
 *   restatement of the source: if LWJGL, STB or [PngEncoder] ever changes what `flipY` means, this
 *   layer goes red rather than quietly turning the other two into tautologies.
 * - **Layer B, declaration matches code.** Every [CapturePathSpec] must find its symbol in its
 *   file, carrying exactly the flip its declared [CapturePathSpec.sourceRowOrder] implies.
 * - **Layer C, completeness.** Scan the `afield.four` tree for flip-bearing encode calls and fail on any
 *   that [CaptureOrientation] does not declare, so a path added without declaring its row order
 *   cannot land.
 *
 * Layers B and C share one scanner, and [scannerReportsAnUndeclaredSite] /
 * [scannerReportsAMismatchedToken] are its negative controls — a filter that cannot fail is a
 * blindfold, so the scanner is shown catching both kinds of breakage on synthetic input.
 *
 * No Vulkan device is needed. What a device would add is confidence that
 * `vkCmdCopyImageToBuffer` is top-down, and that is a spec guarantee plus `62fd65d0a`'s check of
 * the swapchain against the on-screen window — not something a unit test can or should re-derive.
 */
class CaptureOrientationTest {

    private val repoRoot: Path = Path.of(System.getProperty("user.dir"))

    // ── Layer A: what the encode primitives actually do to known rows ────────────────────────

    private val red = intArrayOf(255, 0, 0)
    private val green = intArrayOf(0, 255, 0)
    private val blue = intArrayOf(0, 0, 255)
    private val white = intArrayOf(255, 255, 255)

    private val markedWidth = 5
    private val markedHeight = 3

    /**
     * A 5x3 RGBA buffer with four *pairwise distinct* corners on a black field:
     *
     * ```
     * red    . . .  green      <- row 0
     * .      . . .  .
     * blue   . . .  white      <- row 2
     * ```
     *
     * Pairwise distinctness is the point — a symmetric marker would let a flipped result pass.
     */
    private fun cornerMarkedRgba(): ByteArray {
        val rgba = ByteArray(markedWidth * markedHeight * 4)
        fun set(x: Int, y: Int, c: IntArray) {
            val o = (y * markedWidth + x) * 4
            rgba[o] = c[0].toByte()
            rgba[o + 1] = c[1].toByte()
            rgba[o + 2] = c[2].toByte()
            rgba[o + 3] = 255.toByte()
        }
        for (y in 0 until markedHeight) for (x in 0 until markedWidth) set(x, y, intArrayOf(0, 0, 0))
        set(0, 0, red)
        set(markedWidth - 1, 0, green)
        set(0, markedHeight - 1, blue)
        set(markedWidth - 1, markedHeight - 1, white)
        return rgba
    }

    @Test
    fun cornerMarkersArePairwiseDistinct() {
        // Guards the guard: if these ever collapse, every orientation assertion below goes vacuous.
        val corners = listOf(red, green, blue, white).map { it.toList() }
        assertEquals(4, corners.toSet().size, "corner markers must be pairwise distinct: $corners")
    }

    private fun decodeCorners(png: ByteArray): Map<String, List<Int>> {
        val image = assertNotNull(ImageIO.read(png.inputStream()), "PNG did not decode")
        assertEquals(markedWidth, image.width)
        assertEquals(markedHeight, image.height)
        fun at(x: Int, y: Int): List<Int> {
            val p = image.getRGB(x, y)
            return listOf((p shr 16) and 0xff, (p shr 8) and 0xff, p and 0xff)
        }
        return mapOf(
            "top-left" to at(0, 0),
            "top-right" to at(markedWidth - 1, 0),
            "bottom-left" to at(0, markedHeight - 1),
            "bottom-right" to at(markedWidth - 1, markedHeight - 1)
        )
    }

    @Test
    fun pngEncoderWithoutFlipPutsBufferRowZeroAtTheTop() {
        val png = PngEncoder.encodeFromBuffer(
            ByteBuffer.wrap(cornerMarkedRgba()), markedWidth, markedHeight, flipY = false
        )
        val corners = decodeCorners(png)
        assertEquals(red.toList(), corners["top-left"], "buffer row 0 must decode as the top row")
        assertEquals(green.toList(), corners["top-right"])
        assertEquals(blue.toList(), corners["bottom-left"])
        assertEquals(white.toList(), corners["bottom-right"])
    }

    @Test
    fun pngEncoderWithFlipPutsBufferRowZeroAtTheBottom() {
        // The two-sided half: `flipY = true` has to genuinely invert, or asserting `false`
        // elsewhere proves nothing about the parameter being read at all.
        val png = PngEncoder.encodeFromBuffer(
            ByteBuffer.wrap(cornerMarkedRgba()), markedWidth, markedHeight, flipY = true
        )
        val corners = decodeCorners(png)
        assertEquals(blue.toList(), corners["top-left"], "flipY = true must invert row order")
        assertEquals(white.toList(), corners["top-right"])
        assertEquals(red.toList(), corners["bottom-left"])
        assertEquals(green.toList(), corners["bottom-right"])
        // And X must be untouched: a flip is not a rotation.
        assertTrue(
            corners["top-left"] != corners["top-right"],
            "flipY must not mirror X as well"
        )
    }

    @Test
    fun committedTraditionalTriangleHasTheReviewedWindowOrientation() {
        val file = File("public/docs/assets/afield_four_graphics/00-traditional-triangle.png")
        val image = assertNotNull(ImageIO.read(file), "committed orientation witness did not decode: $file")
        fun colouredAt(y: Int): Int = (0 until image.width).count { x ->
            val rgb = image.getRGB(x, y)
            ((rgb shr 16) and 0xff) + ((rgb shr 8) and 0xff) + (rgb and 0xff) > 40
        }
        val nearTop = colouredAt(image.height / 12)
        val nearBottom = colouredAt(image.height * 11 / 12)
        assertTrue(
            nearBottom > nearTop * 4,
            "the committed window capture appears vertically mirrored: coloured pixels near top=" +
                "$nearTop, near bottom=$nearBottom. Regenerate through writeWindowCapturePng and review it."
        )
    }

    private fun firstPixelOf(rgb: ByteBuffer): List<Int> =
        listOf(rgb.get(0).toInt() and 0xff, rgb.get(1).toInt() and 0xff, rgb.get(2).toInt() and 0xff)

    @Test
    fun topDownPackerKeepsRowZeroFirstAndBottomUpPackerDoesNot() {
        val rgba = cornerMarkedRgba()
        assertEquals(
            red.toList(),
            firstPixelOf(GpuImageCapture.packRgbaToRgbTopDown(rgba, markedWidth, markedHeight)),
            "packRgbaToRgbTopDown must emit buffer row 0 first"
        )
        assertEquals(
            blue.toList(),
            firstPixelOf(GpuImageCapture.packRgbaToRgbBottomUp(rgba, markedWidth, markedHeight)),
            "packRgbaToRgbBottomUp must emit the last buffer row first"
        )
    }

    /**
     * The whole fixed production function, end to end, through the real STB JPEG writer and back.
     *
     * Renders to a temp directory and inspects it — nothing under version control is written.
     * JPEG is lossy and 5x3 is smaller than one 8x8 DCT block, so this asserts the *corner
     * ordering* (which marker is nearer which corner) rather than exact bytes.
     */
    @Test
    fun writeJpegRoundTripsWithRowZeroAtTheTop() {
        val dir = Files.createTempDirectory("capture-orientation")
        try {
            val out = dir.resolve("corner-marked.jpg")
            GpuImageCapture.writeJpeg(out.toString(), cornerMarkedRgba(), markedWidth, markedHeight, 95)

            assertTrue(Files.size(out) > 0L, "writeJpeg produced an empty file")
            val image = assertNotNull(ImageIO.read(out.toFile()), "JPEG did not decode")
            assertEquals(markedWidth, image.width)
            assertEquals(markedHeight, image.height)

            fun redness(x: Int, y: Int): Int {
                val p = image.getRGB(x, y)
                val r = (p shr 16) and 0xff
                val b = p and 0xff
                return r - b
            }
            // Top-left was red (r high, b low); bottom-left was blue (r low, b high). Whichever way
            // JPEG smears the values, the sign of r-b must still separate them.
            assertTrue(
                redness(0, 0) > redness(0, markedHeight - 1),
                "writeJpeg inverted row order: top-left redness ${redness(0, 0)} " +
                        "should exceed bottom-left redness ${redness(0, markedHeight - 1)}"
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ── the scanner shared by layers B and C ────────────────────────────────────────────────

    private data class FlipSite(val file: String, val symbol: String, val line: Int, val token: String)

    /**
     * Find flip-bearing encode calls in one Kotlin source file and attribute each to its enclosing
     * `fun`.
     *
     * Comment and KDoc lines are skipped, so prose and `[symbol]` references do not register.
     * Declarations (`fun packRgbaToRgb...`) are skipped too — only call sites count.
     */
    private fun scanForFlipSites(relativePath: String, content: String): List<FlipSite> {
        val lines = content.lines()
        val flipAssignment = Regex("""flipY\s*=\s*(true|false)""")
        val packCall = Regex("""\bpackRgbaToRgb(TopDown|BottomUp)\s*\(""")
        val jpegWrite = Regex("""\bstbi_write_jpg\s*\(""")
        val funDecl = Regex("""\bfun\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(""")

        fun enclosingSymbol(index: Int): String {
            for (i in index downTo 0) {
                val l = lines[i]
                val t = l.trimStart()
                if (t.startsWith("*") || t.startsWith("//")) continue
                funDecl.find(l)?.let { return it.groupValues[1] }
            }
            return "<file scope>"
        }

        val found = mutableListOf<FlipSite>()
        lines.forEachIndexed { index, line ->
            val trimmed = line.trimStart()
            if (trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")) return@forEachIndexed

            val tokens = mutableListOf<String>()
            flipAssignment.find(line)?.let { tokens += "flipY = ${it.groupValues[1]}" }
            if (!Regex("""\bfun\s+packRgbaToRgb""").containsMatchIn(line)) {
                packCall.find(line)?.let { tokens += "packRgbaToRgb${it.groupValues[1]}" }
            }
            if (jpegWrite.containsMatchIn(line)) tokens += "stbi_write_jpg"

            tokens.forEach { token ->
                found += FlipSite(relativePath, enclosingSymbol(index), index + 1, token)
            }
        }
        return found
    }

    /**
     * Every flip-bearing site under [CaptureOrientation.scannedRoots].
     *
     * `CaptureOrientation.kt` itself is excluded: it holds the tokens as string constants, so it
     * would otherwise report itself as a pile of undeclared paths.
     */
    private fun scanAllSites(): List<FlipSite> {
        val sites = mutableListOf<FlipSite>()
        CaptureOrientation.scannedRoots.forEach { root ->
            val dir = repoRoot.resolve(root)
            assertTrue(Files.isDirectory(dir), "declared scan root does not exist: $dir")
            Files.walk(dir).use { stream ->
                stream.filter { it.toString().endsWith(".kt") }
                    .filter { it.fileName.toString() != "CaptureOrientation.kt" }
                    .sorted()
                    .forEach { file ->
                        val relative = repoRoot.relativize(file).toString()
                        sites += scanForFlipSites(relative, file.readText())
                    }
            }
        }
        return sites
    }

    // ── Layer B: each declaration matches the code it names ─────────────────────────────────

    @Test
    fun everyDeclaredPathCarriesTheFlipItsRowOrderImplies() {
        val sites = scanAllSites()
        var checked = 0

        CaptureOrientation.paths.forEach { spec ->
            val file = repoRoot.resolve(spec.file)
            assertTrue(Files.isRegularFile(file), "${spec.id}: declared file missing: ${spec.file}")
            val content = file.readText()
            assertTrue(
                Regex("""\bfun\s+${Regex.escape(spec.symbol)}\s*\(""").containsMatchIn(content),
                "${spec.id}: declared symbol ${spec.symbol} not found in ${spec.file}"
            )

            val mine = sites.filter { it.file == spec.file && it.symbol == spec.symbol }
            if (spec.expectedToken == null) {
                assertTrue(
                    mine.isEmpty(),
                    "${spec.id} is declared to make no orientation decision, but carries $mine"
                )
            } else {
                assertTrue(
                    mine.isNotEmpty(),
                    "${spec.id}: expected '${spec.expectedToken}' in ${spec.symbol}, found no flip site at all"
                )
                mine.forEach { site ->
                    assertEquals(
                        spec.expectedToken,
                        site.token,
                        "${spec.id} (${spec.file}:${site.line}) declares ${spec.sourceRowOrder} rows, " +
                                "which implies '${spec.expectedToken}', but the code says '${site.token}'"
                    )
                }
                checked++
            }
        }

        // A floor near the number actually checked, so a spec list that quietly shrinks fails.
        // Lowered by one on 2026-09-16 with `virtual-texture-thumbnail`, whose writer went away
        // when `VirtualTextureSystem` was replaced by `afield.four.texture.mega` — that system
        // caches raw RGBA pages rather than re-encoding JPEG thumbnails, so it has no row order to
        // declare. Lower these only alongside a path that has genuinely gone.
        assertTrue(checked >= 7, "only $checked declared paths carried a checkable token")
        assertTrue(
            CaptureOrientation.paths.size >= 8,
            "the path table shrank to ${CaptureOrientation.paths.size}"
        )
    }

    // ── Layer C: no undeclared paths ────────────────────────────────────────────────────────

    @Test
    fun everyFlipBearingSiteInFourIsDeclared() {
        val sites = scanAllSites()
        val declared = CaptureOrientation.paths.associateBy { it.file to it.symbol }

        val undeclared = sites.filter { (it.file to it.symbol) !in declared }
        assertTrue(
            undeclared.isEmpty(),
            "these capture sites are not declared in CaptureOrientation. Add a CapturePathSpec " +
                    "saying which row order the source delivers and why:\n" +
                    undeclared.joinToString("\n") { "  ${it.file}:${it.line} in ${it.symbol} -> ${it.token}" }
        )

        // Floor near the real number, so the scan going blind (bad regex, wrong root, renamed
        // directory) reads as a failure instead of a clean pass over nothing. Lowered by one on
        // 2026-09-16 along with the `virtual-texture-thumbnail` path — see the sibling floor above.
        assertTrue(sites.size >= 7, "the scan found only ${sites.size} flip sites; it has gone blind")
    }

    // ── negative controls for the scanner ───────────────────────────────────────────────────

    @Test
    fun scannerReportsAnUndeclaredSite() {
        val synthetic = """
            package afield.four
            object Sneaky {
                fun captureSomethingNew(buffer: ByteBuffer, w: Int, h: Int) {
                    val png = PngEncoder.encodeFromBuffer(buffer, w, h, flipY = true)
                }
            }
        """.trimIndent()

        val sites = scanForFlipSites("src/jvmMain/kotlin/afield/four/Sneaky.kt", synthetic)
        assertEquals(1, sites.size, "scanner missed a new flip site: $sites")
        assertEquals("captureSomethingNew", sites[0].symbol)
        assertEquals("flipY = true", sites[0].token)

        val declared = CaptureOrientation.paths.associateBy { it.file to it.symbol }
        assertTrue(
            (sites[0].file to sites[0].symbol) !in declared,
            "the synthetic control must not be declared, or it proves nothing"
        )
    }

    @Test
    fun scannerReportsAMismatchedToken() {
        // The historical bug, planted: a top-down source packed bottom-up.
        val synthetic = """
            package afield.four
            object Regressed {
                fun writeJpeg(path: String, rgba: ByteArray, w: Int, h: Int, q: Int) {
                    writeJpegRgb(path, packRgbaToRgbBottomUp(rgba, w, h), w, h, q)
                }
            }
        """.trimIndent()

        val sites = scanForFlipSites("src/jvmMain/kotlin/afield/four/GpuImageCapture.kt", synthetic)
        val jpeg = sites.single { it.token.startsWith("packRgbaToRgb") }
        assertEquals("writeJpeg", jpeg.symbol)

        val spec = CaptureOrientation.paths.single { it.id == "render-to-texture-jpeg" }
        assertEquals(RowOrder.TopDown, spec.sourceRowOrder)
        assertTrue(
            jpeg.token != spec.expectedToken,
            "the planted regression must disagree with the declared token, or layer B is a tautology"
        )
    }

    @Test
    fun scannerIgnoresCommentaryAndDeclarations() {
        val synthetic = """
            package afield.four
            /**
             * Prose mentioning flipY = true and [packRgbaToRgbBottomUp] must not register.
             */
            object Doc {
                // nor should flipY = true in a line comment
                fun packRgbaToRgbTopDown(rgba: ByteArray, w: Int, h: Int) {}
            }
        """.trimIndent()

        assertEquals(
            emptyList(),
            scanForFlipSites("src/jvmMain/kotlin/afield/four/Doc.kt", synthetic),
            "the scanner registered commentary or a declaration as a call site"
        )
    }

    // ── the premise under the premise: the shared negative-height viewport ──────────────────

    private data class ViewportFinding(
        val file: String,
        val symbol: String,
        val line: Int,
        val flipsY: Boolean
    )

    /**
     * Find every `VkViewport` setup in one file and report whether it flips Y.
     *
     * "Flips Y" means the enclosing function contains at least one negative `height(...)`. It is
     * deliberately *at least one* rather than *all*: these functions also set a scissor rectangle,
     * whose `height` is legitimately positive, so requiring every height to be negative would fail
     * on correct code.
     */
    private fun scanForViewportSites(relativePath: String, content: String): List<ViewportFinding> {
        val lines = content.lines()
        val funDecl = Regex("""\bfun\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
        val negativeHeight = Regex("""\.height\(\s*\(?\s*-""")

        fun isCommentary(l: String): Boolean {
            val t = l.trimStart()
            return t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")
        }

        // Index each line to its enclosing fun, so a viewport and its heights agree on an owner.
        val owners = arrayOfNulls<String>(lines.size)
        var current: String? = null
        lines.forEachIndexed { i, l ->
            if (!isCommentary(l)) funDecl.find(l)?.let { current = it.groupValues[1] }
            owners[i] = current
        }

        val viewportOwners = linkedMapOf<String, Int>()
        lines.forEachIndexed { i, l ->
            if (isCommentary(l)) return@forEachIndexed
            if (l.contains("VkViewport.calloc")) {
                val owner = owners[i] ?: "<file scope>"
                viewportOwners.putIfAbsent(owner, i + 1)
            }
        }

        return viewportOwners.map { (symbol, line) ->
            val flips = lines.indices.any { i ->
                owners[i] == symbol && !isCommentary(lines[i]) && negativeHeight.containsMatchIn(lines[i])
            }
            ViewportFinding(relativePath, symbol, line, flips)
        }
    }

    private fun scanAllViewportSites(): List<ViewportFinding> {
        val sites = mutableListOf<ViewportFinding>()
        CaptureOrientation.scannedRoots.forEach { root ->
            Files.walk(repoRoot.resolve(root)).use { stream ->
                stream.filter { it.toString().endsWith(".kt") }
                    .filter { it.fileName.toString() != "CaptureOrientation.kt" }
                    .sorted()
                    .forEach { file ->
                        sites += scanForViewportSites(repoRoot.relativize(file).toString(), file.readText())
                    }
            }
        }
        return sites
    }

    /**
     * The table above concludes `TopDown` for the render targets by arguing that they share the
     * swapchain's viewport convention. This is that argument, made checkable.
     */
    @Test
    fun everyDeclaredRenderPathFlipsYInItsViewport() {
        val found = scanAllViewportSites()

        CaptureOrientation.viewportConventionSites.forEach { site ->
            val file = repoRoot.resolve(site.file)
            assertTrue(Files.isRegularFile(file), "declared viewport file missing: ${site.file}")
            val mine = found.singleOrNull { it.file == site.file && it.symbol == site.symbol }
            assertNotNull(
                mine,
                "no VkViewport setup found in ${site.symbol} (${site.file}); found instead: " +
                        found.filter { it.file == site.file }.map { it.symbol }
            )
            assertTrue(
                mine.flipsY,
                "${site.file}:${mine.line} (${site.symbol}) sets up a VkViewport with a " +
                        "non-negative height. Every TopDown row in CaptureOrientation.paths assumes " +
                        "the negative-height Y-flip is universal; if this path genuinely renders " +
                        "y-down, the capture paths reading its images need re-deriving, not just " +
                        "this assertion relaxing."
            )
            assertTrue(site.why.length >= 40, "${site.symbol}: 'why' is too thin: ${site.why}")
        }

        assertTrue(
            CaptureOrientation.viewportConventionSites.size >= 5,
            "the viewport site list shrank to ${CaptureOrientation.viewportConventionSites.size}"
        )
    }

    @Test
    fun everyViewportSetupInFourIsDeclared() {
        val found = scanAllViewportSites()
        val declared = CaptureOrientation.viewportConventionSites.map { it.file to it.symbol }.toSet()

        val undeclared = found.filter { (it.file to it.symbol) !in declared }
        assertTrue(
            undeclared.isEmpty(),
            "these render paths set up a VkViewport but are not declared in " +
                    "CaptureOrientation.viewportConventionSites. The row-order table's TopDown " +
                    "conclusions depend on every render path sharing the Y-flip, so a new one has " +
                    "to be checked:\n" +
                    undeclared.joinToString("\n") { "  ${it.file}:${it.line} in ${it.symbol} (flipsY=${it.flipsY})" }
        )
        assertTrue(found.size >= 5, "the viewport scan found only ${found.size} sites; it has gone blind")
    }

    @Test
    fun viewportScannerSpotsAPositiveHeightViewport() {
        // Negative control: the exact thing that would silently falsify the table.
        val synthetic = """
            package afield.four
            object NewPath {
                fun setViewportYDown(stack: MemoryStack, w: Int, h: Int) {
                    val viewport = VkViewport.calloc(1, stack)
                        .x(0f)
                        .y(0f)
                        .width(w.toFloat())
                        .height(h.toFloat())
                }
            }
        """.trimIndent()

        val found = scanForViewportSites("src/jvmMain/kotlin/afield/four/NewPath.kt", synthetic)
        assertEquals(1, found.size, "scanner missed a viewport setup: $found")
        assertEquals("setViewportYDown", found[0].symbol)
        assertTrue(!found[0].flipsY, "a positive-height viewport must not read as flipping Y")
    }

    @Test
    fun viewportScannerIsNotFooledByAPositiveScissorHeight() {
        // The real shape of the in-tree code: a flipped viewport next to an upright scissor.
        val synthetic = """
            package afield.four
            object RealShape {
                fun setViewportAndScissor(stack: MemoryStack, rect: Rect) {
                    val viewport = VkViewport.calloc(1, stack)
                        .y((rect.y + rect.h).toFloat())
                        .height((-rect.h).toFloat())
                    val scissor = VkRect2D.calloc(1, stack)
                        .extent(VkExtent2D.calloc(stack).width(rect.w.toInt()).height(rect.h.toInt()))
                }
            }
        """.trimIndent()

        val found = scanForViewportSites("src/jvmMain/kotlin/afield/four/RealShape.kt", synthetic)
        assertEquals(1, found.size)
        assertTrue(found[0].flipsY, "the positive scissor height must not mask the flipped viewport")
    }

    @Test
    fun everyPathSpecExplainsItself() {
        CaptureOrientation.paths.forEach { spec ->
            assertTrue(spec.why.length >= 60, "${spec.id}: 'why' is too thin to be a reason: ${spec.why}")
            assertTrue(spec.id.isNotBlank())
        }
        assertEquals(
            CaptureOrientation.paths.size,
            CaptureOrientation.paths.map { it.id }.toSet().size,
            "duplicate path ids"
        )
    }
}
