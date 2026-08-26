package afield.stdlib

import afield.stdlib.linalg.Matrix4f
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The spatial pointer, verified to pixels — and, in the first test, verified against a completely
 * different implementation of the same question.
 *
 * # The differential
 *
 * `the rasterizer and the ray cast agree about where the surface is` is the strongest evidence in
 * this branch, and it works because two unrelated pieces of code answer *"how far away is the surface
 * at this pixel"*:
 *
 *  - **A scanline rasterizer.** `VisionGeometryPreview.renderPixels` projects triangles to the frame,
 *    walks their bounding boxes, and writes a perspective-correct view-space depth into a z-buffer.
 *  - **A BVH ray cast.** [MeshViewRayCamera.rayThrough] unprojects the pixel and
 *    [MeshBvh.raycast] intersects the triangles analytically with Möller-Trumbore in double
 *    precision.
 *
 * They share the [afield.graphics.VisionGeometryPreview.ViewFrame] that defines *where the camera is*
 * — deliberately, since that is the thing being tested as an inverse — and share nothing whatever
 * about how a surface is found. So a flipped axis, a caption band, a supersample offset, a wrong
 * aspect denominator or a transposed basis all show up as a wholesale disagreement, and none of them
 * can hide.
 *
 * # And then the pictures
 *
 * The rest of the file renders the picture a person will actually look at, **decodes the PNG back out
 * and reads samples from it**, and checks the two things that no return value can tell you: that the
 * right part is lit, and that moving the pointer lights a different one. An all-black PNG passes
 * "the bytes are a PNG" and fails everything here.
 *
 * Set `AFIELD_POINTER_CAPTURE_DIR` to have the picture tests also write their PNGs somewhere you can
 * open them. Unset — which is how CI runs — they go to a temp directory and are deleted, because a
 * test must never write a committed asset.
 */
class SpatialPointerPixelTest {

    // ------------------------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------------------------

    private fun boxAt(cx: Double, cy: Double, cz: Double, w: Double, h: Double, d: Double): TriMesh {
        val hx = (w / 2).toFloat(); val hy = (h / 2).toFloat(); val hz = (d / 2).toFloat()
        val b = TriMeshBuilder()
        val at = listOf(
            Vec3(-hx, -hy, -hz), Vec3(hx, -hy, -hz), Vec3(hx, hy, -hz), Vec3(-hx, hy, -hz),
            Vec3(-hx, -hy, hz), Vec3(hx, -hy, hz), Vec3(hx, hy, hz), Vec3(-hx, hy, hz),
        ).map { b.vertex(Vec3(it.x + cx.toFloat(), it.y + cy.toFloat(), it.z + cz.toFloat())) }
        b.quad(at[3], at[2], at[1], at[0]); b.quad(at[4], at[5], at[6], at[7])
        b.quad(at[0], at[1], at[5], at[4]); b.quad(at[7], at[6], at[2], at[3])
        b.quad(at[0], at[4], at[7], at[3]); b.quad(at[1], at[2], at[6], at[5])
        return b.build()
    }

    /**
     * A little building: floor, two walls at right angles, a roof slab, and a lamp box floating above
     * it. Named parts, asymmetric in all three axes, and with real occlusion — so a wrong answer
     * cannot pass by symmetry and the depth differential has something to be wrong about.
     */
    private fun house(): Pair<TriMesh, PickParts> {
        val b = TriMeshBuilder()
        b.group("floor"); b.add(boxAt(0.0, -0.05, 0.0, 3.0, 0.1, 2.0))
        b.group("wall north"); b.add(boxAt(0.0, 0.7, -0.95, 3.0, 1.4, 0.1))
        b.group("wall west"); b.add(boxAt(-1.45, 0.7, 0.0, 0.1, 1.4, 2.0))
        b.group("roof"); b.add(boxAt(0.0, 1.45, 0.0, 3.2, 0.1, 2.2))
        b.group("chimney"); b.add(boxAt(0.9, 1.8, 0.4, 0.35, 0.6, 0.35))
        return b.build() to b.groups()
    }

    private fun houseScene(): PickScene {
        val (mesh, parts) = house()
        return PickScene(listOf(Pickable("house", mesh, parts)))
    }

    /** A view with nothing that would confuse a depth comparison: no wireframe, no caption band. */
    private fun cleanView(width: Int = 480, height: Int = 360, supersample: Int = 1) = MeshView(
        yawDegrees = 38.0,
        pitchDegrees = 26.0,
        width = width,
        height = height,
        wireframe = false,
        supersample = supersample,
    )

    private class Decoded(val width: Int, val height: Int, val argb: IntArray) {
        fun at(x: Int, y: Int): Int = argb[y * width + x]
        fun red(x: Int, y: Int): Int = (at(x, y) shr 16) and 0xFF
        fun green(x: Int, y: Int): Int = (at(x, y) shr 8) and 0xFF
        fun blue(x: Int, y: Int): Int = at(x, y) and 0xFF
    }

    private fun decode(png: ByteArray): Decoded {
        assertEquals(0x89.toByte(), png[0], "PNG magic")
        assertEquals('P'.code.toByte(), png[1])
        val image = ImageIO.read(ByteArrayInputStream(png))
        assertNotNull(image, "ImageIO could not decode the ${png.size}-byte PNG")
        val argb = IntArray(image.width * image.height)
        image.getRGB(0, 0, image.width, image.height, argb, 0, image.width)
        return Decoded(image.width, image.height, argb)
    }

    /**
     * Where the capture PNGs go. A directory from the environment when one is set, so a human can
     * open them; otherwise a temp directory that is deleted, because a test must never write a
     * committed asset.
     */
    private fun captureDir(): Pair<File, Boolean> {
        val fromEnv = System.getenv("AFIELD_POINTER_CAPTURE_DIR")
        if (fromEnv != null && fromEnv.isNotBlank()) {
            val dir = File(fromEnv)
            dir.mkdirs()
            return dir to false
        }
        return Files.createTempDirectory("field-spatial-pointer-").toFile() to true
    }

    private fun write(dir: File, name: String, png: ByteArray): File =
        File(dir, name).also { it.writeBytes(png); println("wrote ${it.absolutePath} (${png.size} bytes)") }

    /** Is this pixel the highlight orange rather than the neutral grey or the background? */
    private fun isLit(d: Decoded, x: Int, y: Int): Boolean {
        val r = d.red(x, y); val g = d.green(x, y); val b = d.blue(x, y)
        // The highlight is (1.0, 0.62, 0.16) before shading; shading scales all three together, so the
        // discriminator is the *ratio*, which survives any lighting term.
        return r > 60 && r > b * 1.8 && r > g * 1.25
    }

    private fun litPixels(d: Decoded): Int {
        var n = 0
        for (y in 0 until d.height) for (x in 0 until d.width) if (isLit(d, x, y)) n++
        return n
    }

    // ------------------------------------------------------------------------------------------
    // The differential
    // ------------------------------------------------------------------------------------------

    @Test
    fun `the rasterizer and the ray cast agree about where the surface is`() {
        val scene = houseScene()
        val merged = scene.mergedMesh()
        val view = cleanView()
        val camera = view.rayCamera(merged)
        assertTrue(camera.isNonDegenerate, "premise: the camera spreads rays across the frame")

        val raster = merged.renderPixels(view)
        assertEquals(1, raster.sampleWidth / raster.width, "this view is unsupersampled, so samples are pixels")
        assertTrue(raster.depth.isNotEmpty(), "premise: the raster must hand out its z-buffer")
        assertTrue(raster.drawn > 20, "premise: the rasterizer must have drawn something; drawn=${raster.drawn}")

        val bvh = merged.bvh()
        var bothSurface = 0
        var bothEmpty = 0
        var onlyRaster = 0
        var onlyRay = 0
        var worstDepthError = 0.0
        var worstAt = ""

        for (y in 0 until view.height) {
            for (x in 0 until view.width) {
                val rasterDepth = raster.depthAtSample(x, y)
                val rasterHasSurface = rasterDepth < Double.MAX_VALUE / 2
                val ray = camera.rayThroughPixel(x, y)
                // Front faces only, matching the rasterizer's default cull.
                val hit = bvh.raycast(ray, cullBackFaces = true)
                if (hit == null) {
                    if (rasterHasSurface) onlyRaster++ else bothEmpty++
                    continue
                }
                if (!rasterHasSurface) { onlyRay++; continue }
                bothSurface++
                // The ray's distance is along a unit direction; the z-buffer holds view-space z. Turn
                // one into the other by projecting the hit point back through the same frame.
                val viewZ = camera.pixelOf(hit.point)?.depth ?: continue
                val error = abs(viewZ - rasterDepth)
                if (error > worstDepthError) {
                    worstDepthError = error
                    worstAt = "($x, $y): raster ${"%.6f".format(rasterDepth)} vs ray ${"%.6f".format(viewZ)}"
                }
            }
        }

        val total = bothSurface + bothEmpty + onlyRaster + onlyRay
        val agreement = (bothSurface + bothEmpty).toDouble() / total
        val disagreements = onlyRaster + onlyRay
        println(
            "depth differential over $total pixels: both-surface=$bothSurface both-empty=$bothEmpty " +
                "raster-only=$onlyRaster ray-only=$onlyRay agreement=${"%.5f".format(agreement)} " +
                "worst depth error=${"%.3e".format(worstDepthError)} at $worstAt"
        )

        // Premise: the frame must actually contain a surface *and* some background, or "they agree"
        // is agreement about an empty picture.
        assertTrue(bothSurface > total / 12, "premise: the model must cover a real part of the frame ($bothSurface / $total)")
        assertTrue(bothEmpty > total / 12, "premise: and leave a real part of it empty ($bothEmpty / $total)")

        // Measured: **exactly zero** disagreements over all 172,800 pixels of a 480x360 frame. Not
        // "almost everywhere" — the scanline coverage rule (all three edge functions non-positive)
        // and Möller-Trumbore agree even on the silhouette, and Java's floating point is
        // deterministic, so this is a reproducible equality rather than a lucky rounding. Asserted as
        // an equality on purpose: if a change makes it 1, that is a fact somebody should have to look
        // at and relax deliberately, not a wobble under a lenient floor.
        assertEquals(
            0, disagreements,
            "the rasterizer's z-buffer and the unprojected ray cast must agree about the surface at " +
                "every pixel; agreement was ${"%.6f".format(agreement)} with $disagreements " +
                "disagreeing of $total (raster-only=$onlyRaster ray-only=$onlyRay)",
        )

        // Every disagreement must be adjacent to one — i.e. on a silhouette. A systematic error (a
        // flipped axis, a caption band) produces disagreements in the *interior*, which this catches
        // even when the count happens to stay small.
        var interiorDisagreements = 0
        for (y in 1 until view.height - 1) {
            for (x in 1 until view.width - 1) {
                val here = raster.depthAtSample(x, y) < Double.MAX_VALUE / 2
                val hit = bvh.raycast(camera.rayThroughPixel(x, y), cullBackFaces = true) != null
                if (here == hit) continue
                // Is any 4-neighbour on the other side of the silhouette in the raster?
                val onEdge = listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1).any { (dx, dy) ->
                    (raster.depthAtSample(x + dx, y + dy) < Double.MAX_VALUE / 2) != here
                }
                if (!onEdge) interiorDisagreements++
            }
        }
        assertEquals(
            0, interiorDisagreements,
            "every disagreement must be on a silhouette; $interiorDisagreements were in the interior, " +
                "which is what a systematically wrong unprojection looks like",
        )

        // And where both saw the surface, the depths must be the *same* depth. Measured at 6.4e-7 on
        // depths of order 6 — a relative error of 1e-7, which is exactly single-precision noise: the
        // mesh's positions are Float, and both implementations widen the same Float corners to Double.
        // The floor sits three times above the measurement, which is headroom for a different mesh
        // and not headroom for an algebraic mistake.
        assertTrue(
            worstDepthError < 2e-6,
            "where both found a surface they must agree on its depth; worst error was " +
                "${"%.3e".format(worstDepthError)} at $worstAt",
        )
    }

    @Test
    fun `a wrong unprojection would fail that differential, not squeak past it`() {
        // The differential is only worth anything if a plausible mistake breaks it. The classic
        // mistake is dividing sy by the frame height instead of the content height — which is exactly
        // what happens if `rayCamera` is "simplified" to ignore its caption argument. Reproduce it by
        // building the camera with the wrong reservedBottom and measuring how badly it disagrees.
        val scene = houseScene()
        val merged = scene.mergedMesh()
        val view = cleanView()
        val right = view.rayCamera(merged)
        val wrong = view.rayCamera(merged, caption = "a caption reserves the bottom band")

        val raster = merged.renderPixels(view)   // rendered with NO caption, so `right` is right
        val bvh = merged.bvh()

        fun interiorDisagreements(camera: MeshViewRayCamera): Int {
            var n = 0
            for (y in 1 until view.height - 1) {
                for (x in 1 until view.width - 1) {
                    val here = raster.depthAtSample(x, y) < Double.MAX_VALUE / 2
                    val hit = bvh.raycast(camera.rayThroughPixel(x, y), cullBackFaces = true) != null
                    if (here == hit) continue
                    val onEdge = listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1).any { (dx, dy) ->
                        (raster.depthAtSample(x + dx, y + dy) < Double.MAX_VALUE / 2) != here
                    }
                    if (!onEdge) n++
                }
            }
            return n
        }

        val good = interiorDisagreements(right)
        val bad = interiorDisagreements(wrong)
        println("interior disagreements: matching camera=$good, caption-band-off-by-one camera=$bad")
        assertEquals(0, good, "the matching camera must be clean")
        // Measured: 28,276 interior disagreements against the correct camera's 0.
        assertTrue(
            bad > 20_000,
            "an off-by-a-caption-band camera must fail the differential loudly, or the differential " +
                "is not measuring anything. It produced only $bad interior disagreements",
        )
    }

    // ------------------------------------------------------------------------------------------
    // The picture
    // ------------------------------------------------------------------------------------------

    @Test
    fun `the picked part is the part that lights up, and moving the pointer moves the light`() {
        val scene = houseScene()
        val camera = cleanView(720, 540).rayCamera(scene.mergedMesh())
        assertTrue(camera.isNonDegenerate, "premise")
        val view = MeshView(
            yawDegrees = 38.0, pitchDegrees = 26.0, width = 720, height = 540,
            wireframe = true, supersample = 2,
        )

        // Find two pixels that hit *different* named parts, by sweeping rather than by guessing —
        // a hard-coded pixel is a fixture that rots the first time the camera changes.
        val byPart = LinkedHashMap<String, Vec2>()
        val seen = LinkedHashMap<String, Int>()
        var y = 40
        while (y < camera.pixelHeight - 40) {
            var x = 40
            while (x < camera.pixelWidth - 40) {
                val hit = scene.pick(camera.rayThroughPixel(x, y))
                if (hit != null) {
                    byPart.putIfAbsent(hit.partName, Vec2(x + 0.5f, y + 0.5f))
                    seen[hit.partName] = (seen[hit.partName] ?: 0) + 1
                }
                x += 7
            }
            y += 7
        }
        assertTrue(
            byPart.size >= 3,
            "premise: the sweep must find at least three named parts to point at; found ${byPart.keys}",
        )

        val (dir, temporary) = captureDir()
        // The two parts with the most visible area, so the lit-region floors below can be tight
        // rather than generous enough to accommodate whichever sliver the sweep happened to meet
        // first. Deterministic: the sweep is a fixed grid over a fixed camera.
        val ranked = seen.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        val first = ranked[0].key
        val second = ranked[1].key
        println("visible-area ranking from the sweep: ${ranked.map { it.key to it.value }}")

        val a = scene.renderPointer(camera, byPart.getValue(first), view)
        val b = scene.renderPointer(camera, byPart.getValue(second), view)
        assertEquals(first, a.hit?.partName, "the capture must have picked the part the sweep found")
        assertEquals(second, b.hit?.partName)

        val fileA = write(dir, "pointer-on-$first.png".replace(' ', '-'), a.toPng())
        val fileB = write(dir, "pointer-on-$second.png".replace(' ', '-'), b.toPng())
        println("A: ${a.caption()}")
        println("B: ${b.caption()}")

        val da = decode(fileA.readBytes())
        val db = decode(fileB.readBytes())
        assertEquals(720, da.width)
        assertEquals(540, da.height)

        // Not black, and not flat. The failure mode of a headless capture in this project is an
        // all-black PNG that passes every check that is not about the pixels — so this checks two
        // independent things about the actual samples. Measured on this fixture: 306 and 291 distinct
        // colours, and 31.3% of the frame painted. A black frame has one colour and 0% painted; a
        // frame that lost its geometry has a handful and 0%.
        val distinctA = da.argb.toHashSet().size
        val distinctB = db.argb.toHashSet().size
        assertTrue(
            distinctA > 200 && distinctB > 200,
            "a capture with $distinctA / $distinctB distinct colours is a black or flat frame, not a " +
                "render of a shaded, wireframed model",
        )
        val paintedA = da.argb.count { (it and 0xFFFFFF).let { c ->
            ((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF) > 70
        } }
        val fraction = paintedA.toDouble() / (da.width * da.height)
        assertTrue(
            fraction > 0.15 && fraction < 0.80,
            "the model must cover a real part of the frame and not all of it; it painted " +
                "${"%.1f".format(fraction * 100)}%",
        )

        // The right part is lit, and the lit region is a real region rather than a speck or the whole
        // picture.
        val litA = litPixels(da)
        val litB = litPixels(db)
        val frame = da.width * da.height
        println("lit pixels: $first=$litA ($first ${"%.2f".format(100.0 * litA / frame)}%), $second=$litB")
        // Measured: 11.8% and 12.4% of the frame for the two largest parts of this fixture. The floor
        // is 4%, which is a third of the measurement — tight enough that a highlight shrinking to a
        // sliver fails, loose enough to survive a shading change.
        assertTrue(
            litA > frame / 25,
            "'$first' must be visibly lit; only $litA of $frame pixels " +
                "(${"%.2f".format(100.0 * litA / frame)}%) were highlight-coloured",
        )
        assertTrue(
            litA < frame / 3,
            "…and only that part, not the whole frame; $litA of $frame were lit",
        )
        assertTrue(
            litB > frame / 25 && litB < frame / 3,
            "and the same for '$second': $litB of $frame (${"%.2f".format(100.0 * litB / frame)}%)",
        )

        // The pointer's own pixel is on the lit part — modulo the crosshair, which deliberately
        // leaves a gap at the centre, so sample just outside the gap and inside the arm's span.
        val pa = byPart.getValue(first)
        assertTrue(
            (-2..2).any { dx -> (-2..2).any { dy -> isLit(da, pa.x.toInt() + dx, pa.y.toInt() + dy) } },
            "the pixel the pointer was aimed at must be on the lit part",
        )

        // The counterfactual, as a number: the two pictures must light *different* pixels, and the
        // overlap must be small. Two captures of the same part would have overlap ~1.
        var both = 0
        var either = 0
        for (yy in 0 until da.height) for (xx in 0 until da.width) {
            val la = isLit(da, xx, yy); val lb = isLit(db, xx, yy)
            if (la || lb) either++
            if (la && lb) both++
        }
        val overlap = both.toDouble() / either
        println("lit-region overlap between the two pointer positions: ${"%.5f".format(overlap)}")
        assertTrue(
            overlap < 0.02,
            "moving the pointer to a different part must light a different region; the two lit " +
                "regions overlapped by ${"%.4f".format(overlap)} ($both of $either pixels)",
        )

        if (temporary) {
            fileA.delete(); fileB.delete(); dir.delete()
        }
    }

    @Test
    fun `what getting the camera wrong looks like, as a picture`() {
        // The number — 28,276 interior disagreements — is convincing to someone who already believes
        // the differential. This is the same mistake as a picture, so that nobody has to.
        //
        // Both frames aim at the same pixel and render with the same view. One uses the camera that
        // matches the render; the other uses a camera built with a caption band the render does not
        // have, which is exactly what "simplifying" `rayCamera` to drop its caption argument produces.
        // The crosshair lands in the same place in both. A different part lights up.
        val scene = houseScene()
        val merged = scene.mergedMesh()
        val view = MeshView(
            yawDegrees = 38.0, pitchDegrees = 26.0, width = 560, height = 420,
            wireframe = true, supersample = 2,
        )
        val right = view.rayCamera(merged)
        val wrong = view.rayCamera(merged, caption = "a caption reserves the bottom band")

        // Find a pixel where the two cameras genuinely disagree about the part, low in the frame where
        // the caption band's error is largest. Swept rather than hard-coded so the fixture cannot rot.
        var found: Vec2? = null
        var y = view.height - 60
        while (y > view.height / 2 && found == null) {
            var x = 30
            while (x < view.width - 30) {
                val a = scene.pick(right.rayThroughPixel(x, y))?.partName
                val b = scene.pick(wrong.rayThroughPixel(x, y))?.partName
                if (a != null && b != null && a != b) { found = Vec2(x + 0.5f, y + 0.5f); break }
                x += 3
            }
            y -= 3
        }
        assertNotNull(
            found,
            "premise: the two cameras must disagree about *some* pixel's part, or there is no mistake " +
                "to illustrate",
        )

        val (dir, temporary) = captureDir()
        val ok = scene.renderPointer(right, found, view)
        // The wrong camera picks with the wrong ray, but the *picture* is rendered by the same
        // rasterizer either way — which is the whole trap: the render looks completely normal and the
        // highlight is simply on the wrong thing.
        val bad = scene.renderPointer(wrong, found, view)
        println("adversarial capture at (${found.x}, ${found.y}): matching camera says " +
            "'${ok.hit?.partName}', caption-band-off camera says '${bad.hit?.partName}'")
        assertTrue(
            ok.hit?.partName != bad.hit?.partName,
            "the two captures must light different parts, or the picture illustrates nothing",
        )

        val fileOk = write(dir, "adversarial-right.png", ok.toPng())
        val fileBad = write(dir, "adversarial-wrong.png", bad.toPng())

        val dOk = decode(fileOk.readBytes())
        val dBad = decode(fileBad.readBytes())
        // Both must be real renders — a black frame would "differ" too, and prove nothing.
        assertTrue(dOk.argb.toHashSet().size > 200 && dBad.argb.toHashSet().size > 200,
            "both captures must be real renders, not black frames")
        val litOk = litPixels(dOk)
        val litBad = litPixels(dBad)
        assertTrue(litOk > 500 && litBad > 500, "both must light something: $litOk and $litBad pixels")

        var both = 0
        var either = 0
        for (yy in 0 until dOk.height) for (xx in 0 until dOk.width) {
            val a = isLit(dOk, xx, yy); val b = isLit(dBad, xx, yy)
            if (a || b) either++
            if (a && b) both++
        }
        val overlap = both.toDouble() / either
        println("adversarial lit-region overlap: ${"%.5f".format(overlap)} ($litOk vs $litBad lit pixels)")
        // Measured: exactly 0. The two cameras land on *different parts*, and two parts are disjoint
        // sets of triangles, so any overlap at all would mean the highlight had leaked — which is a
        // different bug and worth failing on rather than tolerating under a percentage.
        assertEquals(
            0, both,
            "the wrong camera must light a wholly different region, not a shifted one; " +
                "$both pixels were lit in both (overlap ${"%.4f".format(overlap)})",
        )

        if (temporary) { fileOk.delete(); fileBad.delete(); dir.delete() }
    }

    @Test
    fun `pointing at nothing lights nothing, and says so`() {
        val scene = houseScene()
        val camera = cleanView(400, 300).rayCamera(scene.mergedMesh())
        // A corner of the frame, which this camera leaves empty.
        val corner = Vec2(6f, 6f)
        assertTrue(scene.pick(camera.rayThrough(6.0, 6.0)) == null, "premise: the corner is empty")

        val raster = scene.renderPointer(camera, corner, cleanView(400, 300))
        assertTrue(raster.hit == null)
        assertTrue(raster.caption().endsWith("nothing"), "the caption must say so: ${raster.caption()}")

        val d = decode(raster.toPng())
        assertEquals(
            0, litPixels(d),
            "nothing under the pointer must light nothing — a picker that lights part 0 by default " +
                "is worse than one that lights nothing",
        )
        // But the model is still drawn, so this is not a black frame with nothing in it — which is
        // the other way to make "nothing is lit" true. Checked by painted area rather than by colour
        // count, because with no highlight the whole model is one neutral grey and a colour count is
        // legitimately small.
        val painted = d.argb.count { c ->
            ((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF) > 70
        }
        val fraction = painted.toDouble() / (d.width * d.height)
        assertTrue(
            fraction > 0.15,
            "the model must still be rendered; only ${"%.1f".format(fraction * 100)}% of the frame " +
                "was painted",
        )
    }

    @Test
    fun `a part assignment is visible before anybody points at anything`() {
        // coloredByPart's other job: seeing that a `byComponent` found twelve parts and not one.
        val (mesh, parts) = house()
        assertEquals(5, parts.partCount, "premise: the house has five named pieces")
        val painted = mesh.coloredByPart(parts)
        assertEquals(mesh.triangleCount * 3, painted.vertexCount, "coloredByPart unwelds")
        assertEquals(mesh.triangleCount, painted.triangleCount)

        val d = decode(painted.renderPng(cleanView(400, 300), caption = "", title = null))
        // Five parts, distinct hues: count how many well-separated hues survive to the picture.
        val hues = HashSet<Int>()
        for (y in 0 until d.height) for (x in 0 until d.width) {
            val r = d.red(x, y); val g = d.green(x, y); val b = d.blue(x, y)
            if (r + g + b < 90) continue   // background
            // Quantise the hue into 12 buckets; shading changes value, not hue.
            val max = maxOf(r, g, b); val min = minOf(r, g, b)
            if (max - min < 25) continue   // grey: the unassigned colour or a very dark face
            val h = when (max) {
                r -> (g - b) * 60.0 / (max - min)
                g -> 120 + (b - r) * 60.0 / (max - min)
                else -> 240 + (r - g) * 60.0 / (max - min)
            }
            hues += (((h + 360) % 360) / 30).toInt()
        }
        assertTrue(
            hues.size >= 3,
            "a five-part assignment must show as several distinct hues; only ${hues.size} hue " +
                "bucket(s) survived: $hues. One hue means the assignment collapsed.",
        )
    }

    @Test
    fun `the clickable projection and the JVM picker name the same part for the same click`() {
        // What this exercises, precisely, because it would be easy to overclaim: the *polygon set* and
        // the *topmost-wins rule* that `clickableProjection` hands the browser, checked against the
        // JVM's own ray cast. It replays `sliders.js`'s point-in-polygon rule here rather than running
        // `sliders.js` — that would need the node probe harness `FLineHitRecordProbeTest` uses. So this
        // pins the geometry and the ordering, which is where a convention mismatch would live, and
        // does not pin the browser's own arithmetic.
        val scene = houseScene()
        val camera = cleanView(400, 300).rayCamera(scene.mergedMesh())
        assertTrue(camera.isNonDegenerate, "premise")

        var browserAnswers = 0
        var jvmAnswers = 0
        var agree = 0
        var disagree = 0
        val disagreements = ArrayList<String>()

        val lines = scene.clickableProjection(camera)
        assertTrue(lines.size > 20, "premise: the projection must emit real polygons; got ${lines.size}")
        // Every emitted line must carry a name, or the browser has nothing to report.
        assertTrue(
            lines.all { !it.label.isNullOrBlank() },
            "every projected face must carry a label; ${lines.count { it.label.isNullOrBlank() }} did not",
        )

        // Pull the polygons back out of the FLines, in emission order — far to near.
        // `Close` carries no point of its own, so skip it — the ring is already the three corners.
        val polygons = lines.map { line ->
            line.nodes.mapNotNull { node -> node.toOrNull?.let { p -> Vec2(p.x, p.y) } } to (line.label ?: "")
        }
        assertTrue(
            polygons.all { it.first.size == 3 },
            "every projected face must be a triangle; sizes were ${polygons.map { it.first.size }.distinct()}",
        )

        fun contains(poly: List<Vec2>, x: Double, y: Double): Boolean {
            var inside = false
            var j = poly.size - 1
            for (i in poly.indices) {
                val xi = poly[i].x.toDouble(); val yi = poly[i].y.toDouble()
                val xj = poly[j].x.toDouble(); val yj = poly[j].y.toDouble()
                if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
                j = i
            }
            return inside
        }

        var y = 3
        while (y < camera.pixelHeight - 3) {
            var x = 3
            while (x < camera.pixelWidth - 3) {
                val px = x + 0.5
                val py = y + 0.5
                // Topmost wins: the SVG draws in list order, so scan from the end.
                val browser = polygons.lastOrNull { contains(it.first, px, py) }?.second
                val jvm = scene.pick(camera.rayThrough(px, py))
                    ?.let { "${it.objectName} / ${it.partName}" }
                if (browser != null) browserAnswers++
                if (jvm != null) jvmAnswers++
                if (browser != null && jvm != null) {
                    if (browser == jvm) agree++ else {
                        disagree++
                        if (disagreements.size < 8) disagreements += "($x, $y): svg='$browser' jvm='$jvm'"
                    }
                }
                x += 3
            }
            y += 3
        }

        val both = agree + disagree
        val rate = agree.toDouble() / both
        println(
            "clickable-projection agreement: $agree of $both shared answers " +
                "(${"%.5f".format(rate)}); browser-only=${browserAnswers - both} " +
                "jvm-only=${jvmAnswers - both}; first disagreements: $disagreements"
        )
        assertTrue(both > 2000, "premise: the sweep must find plenty of shared answers; only $both")
        // Measured: 4143 of 4154, i.e. 0.99735. The 11 that disagree are **not** edge noise, and it is
        // worth being precise about what they are, because it is a real and permanent limitation
        // rather than a rounding artefact: every one of them lies along the strip where the roof slab
        // overhangs the north wall, and every one says `svg='house / wall north'` where the JVM says
        // `house / roof`. That is the painter's sort. `clickableProjection` orders whole triangles by
        // centroid depth, and where two surfaces nearly touch the centroid of the larger triangle can
        // sort in front of the near edge of the smaller one. An SVG has no per-pixel depth, so this
        // cannot be fixed there; the JVM half is exact, which is why the disagreement is informative
        // rather than embarrassing.
        assertTrue(
            rate > 0.995,
            "the polygons handed to the browser and the JVM's own ray cast must name the same part " +
                "for the same click; agreement was ${"%.5f".format(rate)} " +
                "($disagree of $both disagreed) — $disagreements",
        )

        // The structural claim, which is the one that distinguishes "a painter's sort tie" from "a
        // convention mismatch": every disagreement must be between the *same small set* of part pairs.
        // A flipped y or a caption-band offset scatters disagreements across many unrelated pairs even
        // when the count stays low; a depth-sort tie concentrates them on the pair that touches.
        var y2 = 3
        val pairs = HashSet<Pair<String, String>>()
        while (y2 < camera.pixelHeight - 3) {
            var x2 = 3
            while (x2 < camera.pixelWidth - 3) {
                val px = x2 + 0.5
                val py = y2 + 0.5
                val browser = polygons.lastOrNull { contains(it.first, px, py) }?.second
                val jvm = scene.pick(camera.rayThrough(px, py))?.let { "${it.objectName} / ${it.partName}" }
                if (browser != null && jvm != null && browser != jvm) pairs += browser to jvm
                x2 += 3
            }
            y2 += 3
        }
        println("distinct disagreeing part pairs: $pairs")
        assertTrue(
            pairs.size <= 2,
            "every disagreement should concentrate on the surfaces that touch — a depth-sort tie. " +
                "${pairs.size} distinct pairs disagreed, which is what a convention mismatch looks " +
                "like: $pairs",
        )
    }

    @Test
    fun `a transformed pickable is lit in the place it actually stands`() {
        // The composition most likely to be silently wrong: a highlight painted on the untransformed
        // mesh while the picture shows the transformed one.
        val cube = boxAt(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)
        val scene = pickScene {
            add("left", cube, at = Matrix4f().identity().translate(-1.5f, 0f, 0f))
            add("right", cube, at = Matrix4f().identity().translate(1.5f, 0f, 0f))
        }
        val view = MeshView(yawDegrees = 0.0, pitchDegrees = 0.0, width = 400, height = 200, wireframe = false)
        val camera = view.rayCamera(scene.mergedMesh())

        // Aim at the left cube and check the lit pixels are on the left of the frame.
        val leftHit = (0 until camera.pixelWidth).firstNotNullOfOrNull { x ->
            scene.pick(camera.rayThroughPixel(x, camera.pixelHeight / 2))?.takeIf { it.objectName == "left" }
                ?.let { x }
        }
        assertNotNull(leftHit, "premise: some pixel must hit the left cube")
        val raster = scene.renderPointer(camera, Vec2(leftHit + 0.5f, camera.pixelHeight / 2f), view)
        assertEquals("left", raster.hit?.objectName)

        val d = decode(raster.toPng())
        var litLeft = 0
        var litRight = 0
        for (y in 0 until d.height) for (x in 0 until d.width) {
            if (!isLit(d, x, y)) continue
            if (x < d.width / 2) litLeft++ else litRight++
        }
        println("transformed highlight: $litLeft lit pixels left of centre, $litRight right")
        assertTrue(litLeft > 100, "the left cube must be lit; only $litLeft lit pixels on the left")
        assertTrue(
            litRight * 20 < litLeft,
            "and the light must not be on the other cube — $litRight lit pixels on the right against " +
                "$litLeft on the left, which is what a highlight painted before the transform looks like",
        )
    }
}
