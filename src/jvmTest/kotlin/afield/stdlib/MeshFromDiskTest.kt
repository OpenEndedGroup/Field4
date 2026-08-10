package afield.stdlib

import afield.four.modules.GltfSupport
import afield.four.modules.PlySupport
import afield.four.modules.assimpToTriMesh
import afield.four.modules.meshFromAnyFile
import afield.four.modules.toTriMesh
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The numbers `public/docs/geometry_from_disk.md` publishes, asserted — plus the two bridges that page
 * documents (`MeshFromDisk.kt`), end to end from a file on disk to a rendered raster.
 *
 * A docs page that quotes a count nothing checks is how docs pages rot, so every figure in the page's
 * round-trip fidelity table appears here as an exact value or a floor. If one of these fails, the page
 * is wrong and this test says which row.
 *
 * Nothing here writes into the repository tree; every file goes to a temp directory that is deleted.
 * The one file it *reads* from the tree is `public/docs/assets/fabricate_demo/ToyCar.glb`, the same GLB
 * `fabricate_renderer.md` renders — deliberately, so the two pages cannot disagree about it.
 */
class MeshFromDiskTest {

    private fun withTempDir(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("afield-mesh-from-disk").toFile()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    /** The page's worked example is this exact mesh, so its counts are quoted there. */
    private fun canonical() = icosphereMesh(3, 1.0).withColor(0.3, 0.7, 1.0)

    // ==========================================================================================
    // The fidelity table
    // ==========================================================================================

    @Test
    fun theFidelityTableOnThePageIsWhatTheCodeActuallyDoes() {
        withTempDir { dir ->
            val source = canonical()
            // Row: source.
            assertEquals(642, source.vertexCount, "the page quotes 642 vertices")
            assertEquals(1280, source.triangleCount, "the page quotes 1280 triangles")
            assertTrue(source.hasNormals && source.hasColors && !source.hasUvs)
            assertEquals(2, source.topology().eulerCharacteristic)
            assertTrue(source.isWatertight())

            val objFile = File(dir, "ball.obj").also { source.saveObj(it) }
            val stlFile = File(dir, "ball.stl").also { source.saveStl(it) }

            // Row: via .obj -- everything survives except the colours, which OBJ has no standard for.
            val viaObj = meshFromFile(objFile.path)
            assertEquals(642, viaObj.vertexCount, "OBJ is indexed: no vertices are duplicated")
            assertEquals(1280, viaObj.triangleCount)
            assertTrue(viaObj.hasNormals, "OBJ carries vn")
            assertTrue(!viaObj.hasColors, "OBJ has no standard per-vertex colour, so they are lost")
            assertEquals(2, viaObj.topology().eulerCharacteristic)
            assertTrue(viaObj.isWatertight())
            assertEquals(source.surfaceArea(), viaObj.surfaceArea(), 1e-5)
            assertEquals(source.signedVolume(), viaObj.signedVolume(), 1e-5)

            // Row: via .stl -- triangle soup, exactly 3 vertices per triangle, and chi becomes the
            // triangle count because every triangle is its own component.
            val viaStl = meshFromFile(stlFile.path)
            assertEquals(3840, viaStl.vertexCount, "3 * 1280 unshared vertices; the page quotes 3840")
            assertEquals(1280, viaStl.triangleCount)
            assertTrue(!viaStl.hasNormals, "STL's per-facet normal is redundant with the winding and is dropped")
            assertEquals(1280, viaStl.topology().eulerCharacteristic, "1280 separate discs; the page quotes chi=1280")
            assertTrue(!viaStl.isWatertight())
            assertEquals(1280, viaStl.topology().boundaryLoops.size, "one boundary loop per loose triangle")
            // ...and yet the geometry is intact, which is the point of the row.
            assertEquals(source.surfaceArea(), viaStl.surfaceArea(), 1e-5)
            assertEquals(source.signedVolume(), viaStl.signedVolume(), 1e-5)

            // Row: via .stl, welded.
            val welded = viaStl.weld(1e-5)
            assertEquals(642, welded.vertexCount, "welding recovers the original vertex count exactly")
            assertEquals(2, welded.topology().eulerCharacteristic)
            assertTrue(welded.isWatertight())
            assertTrue(!welded.hasNormals, "welding recovers topology, never the normals STL never stored")

            // File sizes, to one significant figure of what the page says.
            assertTrue(stlFile.length() == 84L + 50L * 1280, "binary STL is exactly 84 + 50n bytes")
            assertTrue(
                source.toStlAscii().length > stlFile.length() * 4,
                "ASCII STL is several times binary; the page says roughly 5x",
            )
        }
    }

    @Test
    fun summaryDropsTheVolumeAfterAnStlRoundTripEvenThoughTheVolumeIsRight() {
        // The page warns about this: summary() computes topology on what you hand it, so the same solid
        // read back from STL reports 3x the vertices, chi=1280, "open" -- and omits the volume line
        // entirely, because it only prints one for a watertight mesh. signedVolume() is still exact.
        val source = canonical()
        val soup = stlToTriMesh(source.toStlBinary())
        val text = soup.summary()
        assertTrue(text.contains("3840 verts"), text)
        assertTrue(text.contains("chi=1280"), text)
        assertTrue(text.contains("open(1280)"), text)
        assertTrue(!text.contains("volume"), "the volume line is dropped for a non-watertight mesh: $text")
        assertEquals(source.signedVolume(), soup.signedVolume(), 1e-5, "and yet it is exactly right")
        assertTrue(soup.weld(1e-5).summary().contains("volume"), "welding brings the line back")
    }

    @Test
    fun shadingBeforeWritingIsWhatChangesTheVertexCountNotTheFormat() {
        // The page's second warning: an un-welded mesh round-trips faithfully un-welded, so a "wrong"
        // count after an OBJ trip is usually flatShaded()/shadedNormals() upstream, not the file.
        val cube = boxMesh()
        assertEquals(8, cube.vertexCount)
        assertEquals(36, cube.flatShaded().vertexCount, "one normal per triangle: 3 * 12")
        assertEquals(24, cube.shadedNormals(35.0).vertexCount, "split only at creases: 4 per face")
        assertEquals(8, objToTriMesh(cube.toObj()).vertexCount, "OBJ preserves 8")
        assertEquals(24, objToTriMesh(cube.shadedNormals(35.0).toObj()).vertexCount, "and preserves 24")
    }

    // ==========================================================================================
    // PLY
    // ==========================================================================================

    /** A four-vertex two-triangle coloured square, with no normals -- so the defaulting path is live. */
    private val asciiPly = """
        ply
        format ascii 1.0
        element vertex 4
        property float x
        property float y
        property float z
        property uchar red
        property uchar green
        property uchar blue
        element face 2
        property list uchar int vertex_indices
        end_header
        0 0 0 255 0 0
        1 0 0 0 255 0
        1 1 0 0 0 255
        0 1 0 255 255 0
        3 0 1 2
        3 0 2 3
    """.trimIndent() + "\n"

    @Test
    fun plyLoadsWithNoRendererAndBridgesToATriMeshWithItsColours() {
        withTempDir { dir ->
            val file = File(dir, "square.ply").also { it.writeText(asciiPly) }
            // The claim the page makes: loadPly needs no renderer, no Box, no Vulkan.
            val model = PlySupport.loadPly(file.path)
            val b = model.buffers
            assertEquals(4, b.vertexCount)
            assertEquals(6, b.indexCount)
            assertTrue(b.hasColors)
            assertTrue(!b.hasNormals, "this file has no nx/ny/nz")
            assertTrue(!b.isPointCloud)
            assertEquals(4 * 10 * 4, b.vertexData.capacity(), "10 floats per vertex, as the page says")

            val mesh = model.toTriMesh()
            assertEquals(4, mesh.vertexCount)
            assertEquals(2, mesh.triangleCount)
            assertEquals(1.0, mesh.surfaceArea(), 1e-6, "a unit square")
            assertTrue(mesh.hasColors, "the PLY's per-vertex colours must survive the bridge")
            assertTrue(
                !mesh.hasNormals,
                "the loader's invented (0,0,1) for a file with no normals is dropped, not passed on",
            )
            val red = mesh.color(0)!!
            assertEquals(1.0f, red.x, 1e-6f, "vertex 0 is 255,0,0")
            assertEquals(0.0f, red.y, 1e-6f)

            // And the dispatcher routes .ply here rather than throwing.
            assertEquals(2, meshFromAnyFile(file.path).triangleCount)
        }
    }

    @Test
    fun aPointCloudPlySaysSoRatherThanReturningAMeshWithNoTriangles() {
        withTempDir { dir ->
            val file = File(dir, "cloud.ply")
            file.writeText(
                """
                ply
                format ascii 1.0
                element vertex 3
                property float x
                property float y
                property float z
                end_header
                0 0 0
                1 0 0
                0 1 0
                """.trimIndent() + "\n",
            )
            val model = PlySupport.loadPly(file.path)
            assertTrue(model.buffers.isPointCloud)
            val failure = assertFailsWith<IllegalArgumentException> { model.toTriMesh() }
            assertTrue(failure.message!!.contains("point cloud"), failure.message!!)
        }
    }

    // ==========================================================================================
    // glTF / GLB, through Assimp
    // ==========================================================================================

    private fun toyCar(): File? =
        File("public/docs/assets/fabricate_demo/ToyCar.glb").takeIf { it.isFile }

    @Test
    fun theDefaultGltfConfigCannotLoadHeadlessAndThatIsWhyTheBridgePassesLoadTexturesFalse() {
        val glb = toyCar() ?: return
        // The page states this as a trap: loadGltfBasic's default config asks the box context for a
        // Renderer to build stub textures with, so it cannot run outside a live one. If this ever
        // starts passing, the page's paragraph about it is stale.
        val failure = runCatching { GltfSupport.loadGltfBasic(glb.path) }.exceptionOrNull()
        assertTrue(
            failure != null,
            "loadGltfBasic(path) with default arguments is expected to fail with no renderer in context",
        )
        // The same file, geometry only, works.
        val ok = GltfSupport.loadGltfBasic(glb.path, GltfSupport.GltfLoadConfig(loadTextures = false))
        assertEquals(3, ok.meshes.size, "ToyCar has three mesh/material buckets")
        for (m in ok.meshes) {
            org.lwjgl.system.MemoryUtil.memFree(m.buffers.vertexData)
            org.lwjgl.system.MemoryUtil.memFree(m.buffers.indexData)
            m.buffers.tangentData?.let { org.lwjgl.system.MemoryUtil.memFree(it) }
        }
    }

    @Test
    fun aRealGlbBecomesATriMeshWithTheCountsThePageQuotes() {
        val glb = toyCar() ?: return
        val mesh = assimpToTriMesh(glb.path)
        // Exact, because the file is committed and Assimp's post-processing flags are fixed.
        // fabricate_renderer.md quotes "77k vertices, 326k indices" for the same file; these are those
        // numbers to the vertex, and the two pages must not drift apart.
        assertEquals(77429, mesh.vertexCount, "the page quotes 77,429 vertices")
        assertEquals(108936, mesh.triangleCount, "the page quotes 108,936 triangles (326,808 indices)")
        assertTrue(mesh.hasNormals, "Assimp generates normals when the file has none")
        assertTrue(mesh.isFinite(), "no NaN got through the world-transform bake")
        // Assembled, not heaped at the origin: the parts are placed by their node transforms, so the
        // model has real extent in all three axes.
        val b = mesh.bounds()
        for (axis in listOf(b.max.x - b.min.x, b.max.y - b.min.y, b.max.z - b.min.z)) {
            assertTrue(axis > 0.01f, "ToyCar should have extent on every axis, got $b")
        }
        assertTrue(mesh.surfaceArea() > 0.0)
        println("ToyCar.glb -> ${mesh.summary()}")
    }

    @Test
    fun aGlbGoesAllTheWayToPixelsThroughTheCpuRenderer() {
        val glb = toyCar() ?: return
        // The success criterion for the page: a real file on disk, through the bridge, through the same
        // z-buffer renderer showMesh uses, and the raster is not blank.
        val mesh = assimpToTriMesh(glb.path).normalized(2.0)
        val raster = mesh.renderPixels(MeshView(width = 240, height = 180, wireframe = false))
        val background = 0xFF101318.toInt()
        val painted = raster.pixels.count { it != background }
        assertTrue(
            painted > raster.pixels.size / 20,
            "only $painted of ${raster.pixels.size} pixels were painted by a 108k-triangle model",
        )
        assertTrue(raster.drawn > 0 && raster.culled > 0, "a closed-ish model should cull some faces")
        val png = mesh.renderPng(MeshView(width = 240, height = 180))
        assertEquals(0x89.toByte(), png[0])
        assertTrue(png.size > 5000, "a real render of a car should not be a few hundred bytes, got ${png.size}")
    }

    @Test
    fun assimpReadsTheFormatsTheOtherTwoLoadersDoToo() {
        // The page's claim that Assimp is a superset, which is why the dispatcher's `else` branch is
        // not a gamble -- and why the .obj/.stl/.ply special cases are a choice, not a necessity.
        withTempDir { dir ->
            val source = icosphereMesh(2, 1.0)
            val obj = File(dir, "ball.obj").also { source.saveObj(it) }
            val stl = File(dir, "ball.stl").also { source.saveStl(it) }
            val ply = File(dir, "square.ply").also { it.writeText(asciiPly) }
            for (f in listOf(obj, stl, ply)) {
                val viaAssimp = assimpToTriMesh(f.path)
                assertTrue(viaAssimp.triangleCount > 0, "Assimp should read ${f.name}, got an empty mesh")
            }
            assertEquals(source.triangleCount, assimpToTriMesh(obj.path).triangleCount)
            assertEquals(2, assimpToTriMesh(ply.path).triangleCount)
        }
    }

    // ==========================================================================================
    // Dispatch
    // ==========================================================================================

    @Test
    fun theDispatcherRoutesEachExtensionWhereThePageSaysItDoes() {
        withTempDir { dir ->
            val source = torusMesh(24, 12)
            val obj = File(dir, "t.obj").also { source.saveObj(it) }
            val stl = File(dir, "t.stl").also { source.saveStl(it) }
            // .obj and .stl go to the dependency-free stdlib reader, so the OBJ trip is index-for-index.
            assertEquals(source.vertexCount, meshFromAnyFile(obj.path).vertexCount)
            assertEquals(0, meshFromAnyFile(obj.path).topology().eulerCharacteristic, "still a torus")
            assertEquals(source.triangleCount * 3, meshFromAnyFile(stl.path).vertexCount, "still soup")
            // A missing file is a missing file, not an empty mesh.
            assertFailsWith<IllegalArgumentException> { meshFromAnyFile(File(dir, "nope.obj").path) }
        }
    }

    @Test
    fun theStdlibReaderStillRefusesWhatItAlwaysRefused() {
        // meshFromFile is unchanged and deliberately narrow; meshFromAnyFile is the wide door. The page
        // says which to reach for, and this pins the difference.
        withTempDir { dir ->
            val ply = File(dir, "x.ply").also { it.writeText(asciiPly) }
            assertFailsWith<IllegalArgumentException> { meshFromFile(ply.path) }
            assertEquals(2, meshFromAnyFile(ply.path).triangleCount)
        }
    }

    // ==========================================================================================
    // The troubleshooting table
    // ==========================================================================================

    @Test
    fun aFileWhoseContentsAreNotWhatItsExtensionSaysParsesToNothingWithoutComplaining() {
        // The page's first troubleshooting row, and the one that costs an hour: meshFromFile is loud
        // about a missing file and about an extension it does not read, and *silent* about a file whose
        // contents make no sense. That is why the page says to print summary() before drawing.
        withTempDir { dir ->
            for (name in listOf("notreally.obj", "notreally.stl")) {
                val f = File(dir, name).also { it.writeText("this is not a mesh at all\n") }
                val mesh = meshFromAnyFile(f.path)
                assertTrue(mesh.isEmpty, "$name should parse to an empty mesh, got ${mesh.summary()}")
                assertEquals(0, mesh.vertexCount)
                assertEquals(0, mesh.triangleCount)
            }
        }
        // And the diagnosis the page tells you to reach for really is that exact string.
        assertEquals("TriMesh(empty)", TriMesh.empty.summary())
        // showMesh on an empty mesh does not throw; it renders a blank panel, which is the symptom.
        assertTrue(TriMesh.empty.toFigureHtml().contains("<img"), "an empty mesh still produces a figure")
    }

    @Test
    fun anInsideOutOpenMeshIsInvisibleRatherThanDark() {
        // The page states this as a number: a flipped open patch paints *zero* non-background pixels
        // with every triangle culled. That is what makes "holes" the symptom of bad winding.
        val patch = planeMesh(8, 8, 2.0, 2.0)
        val view = MeshView(width = 120, height = 90)
        val background = 0xFF101318.toInt()
        val right = patch.renderPixels(view)
        val wrong = patch.flipped().renderPixels(view)
        assertTrue(right.pixels.count { it != background } > 1000, "the correctly-wound patch must be visible")
        assertEquals(0, right.culled, "and nothing culled")
        assertEquals(0, wrong.pixels.count { it != background }, "the flipped patch must paint nothing at all")
        assertEquals(0, wrong.drawn)
        assertEquals(patch.triangleCount, wrong.culled, "every triangle culled")
        // A closed solid is the opposite case: the render is unchanged and only the sign of the volume
        // tells you, which is why the page says the picture is no help there.
        val solid = icosphereMesh(2)
        assertTrue(solid.signedVolume() > 0.0)
        assertEquals(-solid.signedVolume(), solid.flipped().signedVolume(), 1e-9)
    }

    @Test
    fun copyWithANullAttributeKeepsItWhichIsWhyThePageSaysWithoutAttributes() {
        // A correction the audit made to its own first draft: copy(normals = null) does NOT drop them.
        val ball = icosphereMesh(2).withColor(0.4, 0.6, 0.9)
        assertTrue(ball.hasNormals && ball.hasColors)
        assertTrue(ball.copy(normals = null).hasNormals, "null means 'unchanged' in copy(), not 'drop'")
        assertTrue(!ball.withoutAttributes().hasNormals, "withoutAttributes is the one that drops")
        assertTrue(!ball.withoutAttributes().hasColors)
        assertEquals(ball.triangleCount, ball.withoutAttributes().triangleCount)
    }

    @Test
    fun readingAMeshOffDiskStaysWellUnderAnyPlausibleBudget() {
        val glb = toyCar() ?: return
        // A deliberately loose ceiling: ToyCar measures ~83 ms on an M-series laptop, so this catches a
        // 60x regression (a re-parse per mesh, a per-vertex allocation storm) and will not flake on a
        // loaded CI box. The page quotes the measured number and says which machine it came from.
        repeat(2) { assimpToTriMesh(glb.path) }
        val start = System.nanoTime()
        val mesh = assimpToTriMesh(glb.path)
        val millis = (System.nanoTime() - start) / 1e6
        assertEquals(108936, mesh.triangleCount)
        assertTrue(millis < 5000.0, "reading ToyCar.glb took ${millis.toInt()} ms; expected well under 5 s")
    }

    @Test
    fun thereIsNoStepReaderAndTheErrorSaysSoUsefully() {
        // The page's "what genuinely does not exist" list, asserted so it cannot quietly become wrong.
        withTempDir { dir ->
            val step = File(dir, "part.step").also { it.writeText("ISO-10303-21;\nHEADER;\n") }
            assertFailsWith<IllegalArgumentException> { meshFromFile(step.path) }
            // meshFromAnyFile hands it to Assimp, which has no STEP importer either -- so it fails,
            // rather than silently returning nothing.
            val failure = runCatching { meshFromAnyFile(step.path) }.exceptionOrNull()
            assertTrue(failure != null, "a STEP file must not quietly produce an empty mesh")
        }
    }
}
