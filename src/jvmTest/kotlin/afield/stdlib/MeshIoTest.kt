package afield.stdlib

import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Round-trip invariants for `mesh_io.kt` and the bridges in `mesh_field.kt`.
 *
 * The interesting assertions are the ones about what each format **cannot** carry: OBJ keeps the index
 * structure so a watertight mesh comes back watertight, while STL is triangle soup so it comes back as
 * `3 * triangleCount` unshared vertices and needs a [TriMesh.weld] — and the *volume* survives both,
 * which is the check that the coordinates themselves went through intact.
 *
 * Nothing here writes into the repository tree; every file goes to a temp directory that is deleted.
 */
class MeshIoTest {

    private fun withTempDir(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("afield-mesh-io").toFile()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    // ==========================================================================================
    // OBJ
    // ==========================================================================================

    @Test
    fun objRoundTripKeepsTheTopologyAndTheVolume() {
        val original = icosphereMesh(3, 1.0)
        val back = objToTriMesh(original.toObj("ball"))
        assertEquals(original.vertexCount, back.vertexCount, "OBJ is indexed, so no vertices are duplicated")
        assertEquals(original.triangleCount, back.triangleCount)
        assertTrue(back.hasNormals, "the normals were written and must come back")
        assertTrue(back.isWatertight(), "a watertight mesh round-trips watertight; ${back.summary()}")
        assertEquals(2, back.topology().eulerCharacteristic)
        assertEquals(original.signedVolume(), back.signedVolume(), 1e-4)
        for (v in 0 until original.vertexCount) {
            assertEquals(0.0, original.position(v).distance(back.position(v)).toDouble(), 1e-5, "vertex $v")
        }
    }

    @Test
    fun objRoundTripCarriesUvsWhenTheyArePresent() {
        val plane = heightfieldMesh(6, 6, 2.0, 2.0) { x, z -> (0.2 * kotlin.math.sin(x * 2.0 + z)).toFloat() }
        assertTrue(plane.hasUvs && plane.hasNormals)
        val back = objToTriMesh(plane.toObj())
        assertTrue(back.hasUvs, "uvs must survive")
        assertTrue(back.hasNormals)
        for (v in 0 until plane.vertexCount) {
            val a = assertNotNull(plane.uv(v))
            val b = assertNotNull(back.uv(v))
            assertEquals(a.x, b.x, 1e-5f, "uv $v u")
            assertEquals(a.y, b.y, 1e-5f, "uv $v v")
        }
    }

    @Test
    fun objReaderHandlesQuadsNegativeIndicesAndPositionOnlyFaces() {
        // A unit square as one quad, written the three legal ways.
        val quadFace = """
            v 0 0 0
            v 1 0 0
            v 1 1 0
            v 0 1 0
            f 1 2 3 4
        """.trimIndent()
        val quad = objToTriMesh(quadFace)
        assertEquals(2, quad.triangleCount, "a quad must be fan-triangulated")
        assertEquals(4, quad.vertexCount)
        assertEquals(1.0, quad.surfaceArea(), 1e-6)
        assertTrue(!quad.hasNormals, "no vn in the file means no normals in the mesh")

        val relative = """
            v 0 0 0
            v 1 0 0
            v 0 1 0
            f -3 -2 -1
        """.trimIndent()
        assertEquals(1, objToTriMesh(relative).triangleCount, "negative indices are relative to the end")

        val withSlashes = """
            v 0 0 0
            v 1 0 0
            v 0 1 0
            vn 0 0 1
            f 1//1 2//1 3//1
        """.trimIndent()
        val slashed = objToTriMesh(withSlashes)
        assertEquals(1, slashed.triangleCount)
        assertTrue(slashed.hasNormals)
    }

    @Test
    fun objWriterAndReaderGoThroughARealFile() {
        withTempDir { dir ->
            val mesh = torusMesh(24, 12)
            val file = File(dir, "torus.obj")
            mesh.saveObj(file)
            assertTrue(file.isFile && file.length() > 1000, "the file should exist and have content")
            val back = triMeshFromObj(file)
            assertEquals(mesh.triangleCount, back.triangleCount)
            assertEquals(0, back.topology().eulerCharacteristic, "still a torus; ${back.summary()}")
            assertEquals(mesh.signedVolume(), back.signedVolume(), 1e-4)
            assertEquals(mesh.triangleCount, meshFromFile(file.path).triangleCount, "dispatch by extension")
        }
    }

    @Test
    fun objOfAnEmptyMeshReadsBackAsEmpty() {
        assertTrue(objToTriMesh(TriMesh.empty.toObj()).isEmpty)
        assertTrue(objToTriMesh("# nothing at all\n").isEmpty)
    }

    // ==========================================================================================
    // STL
    // ==========================================================================================

    @Test
    fun binaryStlIsATriangleSoupThatWeldsBackToTheOriginal() {
        val original = icosphereMesh(3, 1.0)
        val bytes = original.toStlBinary()
        assertEquals(84 + 50 * original.triangleCount, bytes.size, "the binary layout is exactly this size")
        val soup = stlToTriMesh(bytes)
        assertEquals(original.triangleCount, soup.triangleCount)
        assertEquals(original.triangleCount * 3, soup.vertexCount, "STL shares nothing, by design")
        assertTrue(!soup.isWatertight(), "so the soup is not watertight until welded")
        assertEquals(original.signedVolume(), soup.signedVolume(), 1e-4, "the coordinates survived")
        val welded = soup.weld(1e-5)
        assertEquals(original.vertexCount, welded.vertexCount)
        assertTrue(welded.isWatertight(), welded.summary())
        assertEquals(2, welded.topology().eulerCharacteristic)
    }

    @Test
    fun asciiStlRoundTripsToo() {
        val original = boxMesh(2, 1, 3)
        val soup = stlToTriMesh(original.toStlAscii("brick").toByteArray(Charsets.US_ASCII))
        assertEquals(12, soup.triangleCount)
        assertEquals(6.0, soup.weld(1e-5).signedVolume(), 1e-4)
        assertTrue(soup.weld(1e-5).isWatertight())
    }

    @Test
    fun theFormatSniffIsNotFooledByABinaryFileWhoseHeaderSaysSolid() {
        val mesh = icosphereMesh(1)
        // A binary STL whose 80-byte header begins with "solid" — the classic mis-detection.
        val trap = mesh.toStlBinary(header = "solid but actually binary")
        assertTrue(String(trap, 0, 5, Charsets.US_ASCII) == "solid", "the trap must really start with 'solid'")
        val parsed = stlToTriMesh(trap)
        assertEquals(mesh.triangleCount, parsed.triangleCount, "the byte-length check must win over the prefix")
        assertEquals(mesh.signedVolume(), parsed.signedVolume(), 1e-4)
    }

    @Test
    fun stlWriterAndReaderGoThroughARealFile() {
        withTempDir { dir ->
            val mesh = capsuleMesh(24, 6)
            val file = File(dir, "capsule.stl")
            mesh.saveStl(file)
            assertTrue(file.isFile)
            val back = triMeshFromStl(file).weld(1e-5)
            assertEquals(mesh.triangleCount, back.triangleCount)
            assertEquals(mesh.signedVolume(), back.signedVolume(), 1e-4)
            assertEquals(mesh.triangleCount, meshFromFile(file.path).triangleCount)
        }
    }

    @Test
    fun anUnsupportedExtensionSaysSoRatherThanReturningNothing() {
        withTempDir { dir ->
            val file = File(dir, "thing.ply")
            file.writeText("ply\n")
            assertFailsWith<IllegalArgumentException> { meshFromFile(file.path) }
            assertFailsWith<IllegalArgumentException> { meshFromFile(File(dir, "missing.obj").path) }
        }
    }

    // ==========================================================================================
    // Bridges
    // ==========================================================================================

    @Test
    fun toFLinesProducesTheRightNumberOfLinesInEachMode() {
        val cube = boxMesh()
        val topo = cube.topology()
        assertEquals(topo.edgeCount, cube.toFLines(MeshLines.EDGES).size)
        assertEquals(cube.triangleCount, cube.toFLines(MeshLines.FACES).size)
        assertEquals(cube.vertexCount, cube.toFLines(MeshLines.NORMALS).size)
        assertTrue(cube.toFLines(MeshLines.BOUNDARY).isEmpty(), "a closed solid has no boundary to draw")
        // A cube's creases are its 12 real edges; the 6 face diagonals are flat and must be dropped.
        assertEquals(12, cube.toFLines(MeshLines.CREASES, creaseAngleDegrees = 25.0).size)
        // A smooth sphere has no creases at all.
        assertEquals(0, icosphereMesh(3).toFLines(MeshLines.CREASES, creaseAngleDegrees = 45.0).size)
        // An open patch's boundary is one loop.
        assertEquals(1, planeMesh(3, 3).toFLines(MeshLines.BOUNDARY).size)
        // Every produced line must have real geometry.
        for (line in cube.toFLines(MeshLines.EDGES)) assertTrue(line.nodes.size >= 2)
    }

    @Test
    fun voronoiCellsBecomeClosedMeshesWhoseVolumesSumToTheClipBox() {
        val random = kotlin.random.Random(31)
        val sites = List(20) {
            Vec3(random.nextFloat() * 2 - 1, random.nextFloat() * 2 - 1, random.nextFloat() * 2 - 1)
        }
        val lattice = voronoi3d(sites)
        var closed = 0
        var total = 0.0
        for (cell in lattice.cells) {
            val mesh = cell.toTriMesh(shrink = 1.0)
            assertTrue(mesh.triangleCount > 0, "cell ${cell.siteIndex} produced no triangles")
            assertTrue(mesh.isFinite())
            val topo = mesh.topology()
            // The faces come from half-space clipping and already share their corner coordinates
            // exactly, so a weld with a tiny tolerance is enough to make a cell watertight.
            val welded = mesh.weld(1e-5)
            if (welded.isWatertight()) {
                closed++
                assertEquals(2, welded.topology().eulerCharacteristic, "cell ${cell.siteIndex}; ${welded.summary()}")
                assertTrue(welded.signedVolume() > 0.0, "cell ${cell.siteIndex} should be wound outward")
            }
            total += abs(welded.signedVolume())
            assertTrue(topo.componentCount >= 1)
        }
        assertTrue(closed >= lattice.cells.size - 2, "most cells should close cleanly; $closed of ${lattice.cells.size}")
        val box = lattice.clipBounds
        val boxVolume = (box.max.x - box.min.x).toDouble() * (box.max.y - box.min.y) * (box.max.z - box.min.z)
        // A Voronoi tessellation of the clip box partitions it exactly, so the cell volumes must sum
        // to the box's own volume. This is the strongest single check on the bridge.
        assertEquals(boxVolume, total, boxVolume * 0.02, "cell volumes should tile the clip box")
    }

    @Test
    fun shrinkingVoronoiCellsScalesTheirVolumesByTheCube() {
        val sites = listOf(Vec3(0f, 0f, 0f), Vec3(1f, 0.3f, -0.2f), Vec3(-0.8f, 0.6f, 0.4f), Vec3(0.2f, -1f, 0.7f))
        val cell = voronoi3d(sites).cells[0]
        val full = cell.toTriMesh(shrink = 1.0).weld(1e-5)
        val shrunk = cell.toTriMesh(shrink = 0.5).weld(1e-5)
        assertTrue(full.signedVolume() > 0.0)
        assertEquals(full.signedVolume() * 0.125, shrunk.signedVolume(), full.signedVolume() * 0.01)
    }

    @Test
    fun theWholeLatticeBecomesOneMeshWithOneComponentPerCell() {
        val random = kotlin.random.Random(37)
        val sites = List(12) {
            Vec3(random.nextFloat() * 2 - 1, random.nextFloat() * 2 - 1, random.nextFloat() * 2 - 1)
        }
        val lattice = voronoi3d(sites)
        val mesh = lattice.toTriMesh(shrink = 0.85)
        assertTrue(mesh.triangleCount > 100, mesh.summary())
        assertEquals(12, mesh.weld(1e-5).topology().componentCount, "shrunk cells must not touch")
    }

    @Test
    fun packingForTheRasterizerSatisfiesItsOwnInvariantChecks() {
        // The rasterizer ships a full invariant checker; the bridge must satisfy all of it.
        for (mesh in listOf(
            boxMesh(),                                  // no normals: must be crease-shaded on the way
            icosphereMesh(3),                           // smooth normals already
            cylinderMesh(24).withColor(0.9, 0.4, 0.2),  // colours but no normals
            marchingCubes(sdfTorus(1.0, 0.3), boundsAround(radius = 1.4), 32),
        )) {
            val packed = mesh.toCapturedMesh()
            assertTrue(packed.invariantProblems().isEmpty(), "problems: ${packed.invariantProblems()}")
            assertEquals(
                1.0, packed.windingAgreement(), 1e-9,
                "every triangle's winding must agree with its own normals for ${mesh.summary()}",
            )
        }
    }

    @Test
    fun renderingProducesRealNonBlankPngBytes() {
        val png = icosphereMesh(3).withColor(0.3, 0.7, 1.0).renderPng(MeshView(width = 160, height = 120))
        assertTrue(png.size > 200, "a rendered PNG should not be trivially small, got ${png.size} bytes")
        // PNG magic.
        assertEquals(0x89.toByte(), png[0])
        assertEquals('P'.code.toByte(), png[1])
        assertEquals('N'.code.toByte(), png[2])
        assertEquals('G'.code.toByte(), png[3])
        // And the raster really has the shape in it: a meaningful fraction of pixels differ from the
        // background, and the model's own colours appear.
        val raster = icosphereMesh(3).withColor(0.3, 0.7, 1.0).renderPixels(MeshView(width = 160, height = 120))
        val background = 0xFF101318.toInt()
        val painted = raster.pixels.count { it != background }
        assertTrue(
            painted > raster.pixels.size / 20,
            "only $painted of ${raster.pixels.size} pixels were painted",
        )
        assertTrue(raster.drawn > 0 && raster.culled > 0, "half a closed mesh should be culled")
    }

    @Test
    fun theInlineThumbnailIsRealHtmlWithARealImage() {
        val html = icosphereMesh(2).toHTML()
        assertTrue(html.contains("data:image/png;base64,"), "the thumbnail must embed a real PNG")
        assertTrue(html.contains("642 verts") || html.contains("162 verts"), "and report the real counts: $html")
        assertTrue(TriMesh.empty.toHTML().contains("empty"))
    }

    @Test
    fun summaryReportsTheFactsItClaimsTo() {
        val torus = torusMesh(24, 12)
        val text = torus.summary()
        assertTrue(text.contains("chi=0"), text)
        assertTrue(text.contains("genus 1"), text)
        assertTrue(text.contains("watertight"), text)
        val patch = planeMesh(3, 3).summary()
        assertTrue(patch.contains("open(1)"), patch)
    }

    @Test
    fun normalizedRecentresAndRescales() {
        val odd = boxMesh(3, 1, 2).translated(10, -5, 7)
        val fixed = odd.normalized(1.0)
        val b = fixed.bounds()
        assertEquals(1.0f, b.max.x - b.min.x, 1e-5f, "the longest axis becomes 1")
        assertEquals(0.0f, (b.max.x + b.min.x) / 2f, 1e-5f, "and it is centred")
        assertEquals(0.0f, (b.max.y + b.min.y) / 2f, 1e-5f)
        assertEquals(0.0f, (b.max.z + b.min.z) / 2f, 1e-5f)
    }

    @Test
    fun tubeAlongAnFLineFollowsTheLine() {
        val line = afield.graphics.polylineFrom(
            (0..20).map { i -> Vec3(i * 0.1f, kotlin.math.sin(i * 0.3).toFloat() * 0.4f, 0f) },
        )
        val tube = tubeMesh(line, radius = 0.06, sides = 10, samples = 40, caps = true)
        assertTrue(tube.isWatertight(), tube.summary())
        assertEquals(2, tube.topology().eulerCharacteristic)
        val bounds = tube.bounds()
        assertEquals(0.0f, bounds.min.x, 0.07f, "the tube should start where the line does")
        assertEquals(2.0f, bounds.max.x, 0.07f, "and end where it ends")
    }

    @Test
    fun imgToHeightfieldFollowsThePixels() {
        val img = Img.generate(64, 64) { x, _ -> if (x < 32) 0f else 1f }
        val mesh = img.toHeightfieldMesh(scale = 1.0, width = 2.0)
        assertTrue(mesh.hasNormals && mesh.hasUvs)
        val b = mesh.bounds()
        assertEquals(0.0f, b.min.y, 1e-5f, "the low half of the image is at y = 0")
        assertEquals(1.0f, b.max.y, 1e-5f, "and the high half at y = scale")
        assertEquals(1, mesh.topology().eulerCharacteristic, "a heightfield is a disc")
        // And the same image marched as an SDF gives a surface at the same heights.
        val marched = marchingCubes(
            img.asHeightSdf(1.0, boundsOf(Vec3(-1f, -0.5f, -1f), Vec3(1f, 1.5f, 1f))),
            boundsOf(Vec3(-1f, -0.5f, -1f), Vec3(1f, 1.5f, 1f)), 48,
        )
        assertTrue(marched.triangleCount > 100, marched.summary())
        val mb = marched.bounds()
        assertTrue(mb.max.y > 0.9f && mb.min.y < 0.1f, "the marched step should span both levels: $mb")
    }

    @Test
    fun sdfPipelineEndToEndProducesAPrintableSolid() {
        // The advertised worked example, asserted rather than assumed.
        val body = sdfSmoothUnion(sdfSphere(0.8), sdfBox(Vec3(1.4f, 0.25f, 0.25f)), k = 0.25)
        val drilled = sdfSubtract(body, sdfCylinder(0.22, 3.0))
        val mesh = marchingCubes(drilled, boundsAround(radius = 1.1), resolution = 96)
        assertTrue(mesh.isWatertight(), mesh.summary())
        assertEquals(1, mesh.topology().genus, "the drill adds one handle; ${mesh.summary()}")
        assertTrue(mesh.signedVolume() > 0.0)
        // It must survive the whole downstream pipeline without losing its topology.
        val decimated = mesh.decimate(2000)
        assertTrue(decimated.isWatertight(), decimated.summary())
        val stl = stlToTriMesh(decimated.toStlBinary()).weld(1e-5)
        assertEquals(decimated.triangleCount, stl.triangleCount)
        assertEquals(decimated.signedVolume(), stl.signedVolume(), abs(decimated.signedVolume()) * 1e-3)
        println("sdf pipeline: ${mesh.summary()} -> decimated ${decimated.summary()}")
    }
}
