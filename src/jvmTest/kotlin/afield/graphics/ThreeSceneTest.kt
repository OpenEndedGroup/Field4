package afield.graphics

import afield.stdlib.TriMesh
import afield.stdlib.fline
import afield.stdlib.icosphereMesh
import afield.stdlib.vec
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The mesh → [ThreeDrawable] lowering, and the promise that adding it changed nothing.
 *
 * [ThreeScene]'s whole design bet is that the drawable layer is *additive*: the viewer's FLine path,
 * the editable path and the inspector were not to notice it existed. That is a claim about bytes on a
 * websocket, so it is tested as one.
 */
class ThreeSceneTest {

    /** The production encoder's configuration, from `ThreeJsViewer.kt`. */
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    /** A two-triangle square with per-vertex colours, and no normals — the interesting case. */
    private fun square() = TriMesh(
        positions = floatArrayOf(
            0f, 0f, 0f,
            1f, 0f, 0f,
            1f, 1f, 0f,
            0f, 1f, 0f,
        ),
        indices = intArrayOf(0, 1, 2, 0, 2, 3),
        colors = floatArrayOf(
            1f, 0f, 0f,
            0f, 1f, 0f,
            0f, 0f, 1f,
            1f, 1f, 0f,
        ),
    )

    // ------------------------------------------------------------------ the lowering

    @Test
    fun `positions, indices and colours survive the lowering unchanged`() {
        val mesh = square()
        val d = mesh.toThreeDrawable()

        val position = assertNotNull(d.geometry.attributes["position"], "a drawable must have positions")
        assertEquals(3, position.itemSize)
        assertTrue(
            mesh.positions.contentEquals(position.array),
            "positions were rewritten: ${position.array.toList()}",
        )

        val index = assertNotNull(d.geometry.index, "an indexed mesh must stay indexed")
        assertTrue(mesh.indices.contentEquals(index.array), "indices were rewritten")

        val color = assertNotNull(d.geometry.attributes["color"], "per-vertex colours must reach the GPU")
        assertTrue(mesh.colors!!.contentEquals(color.array), "colours were rewritten")
    }

    @Test
    fun `a mesh with no colours gets the same neutral grey the rasterizer uses`() {
        val plain = TriMesh(
            positions = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f),
            indices = intArrayOf(0, 1, 2),
        )
        val color = assertNotNull(plain.toThreeDrawable().geometry.attributes["color"])
        for (v in 0 until 3) {
            for (c in 0 until 3) {
                assertEquals(
                    THREE_DEFAULT_SURFACE[c], color.array[v * 3 + c], 1e-7f,
                    "vertex $v channel $c should be the rasterizer's DEFAULT_SURFACE",
                )
            }
        }
    }

    /**
     * The normals are *ours*, not three.js's.
     *
     * `computeVertexNormals()` is an unconditional smooth average, so it would round every hard edge
     * off a cube. The rasterizer runs `shadedNormals(35°)` instead, and the two renderers have to be
     * shading the same mesh — so the lowering does it here and the viewer is told not to.
     */
    @Test
    fun `a mesh with no normals gets crease-aware ones, and they are unit length`() {
        val d = square().toThreeDrawable()
        val normal = assertNotNull(d.geometry.attributes["normal"], "the GPU needs normals to shade")
        assertEquals(3, normal.itemSize)
        assertEquals(square().vertexCount * 3, normal.array.size)

        for (v in 0 until square().vertexCount) {
            val x = normal.array[v * 3]
            val y = normal.array[v * 3 + 1]
            val z = normal.array[v * 3 + 2]
            val length = sqrt(x * x + y * y + z * z)
            assertTrue(
                abs(length - 1f) < 1e-5f,
                "vertex $v's normal has length $length; the invariant checker rejects a nearly-unit " +
                    "normal because it is the signature of an un-normalised cross product",
            )
        }
        // A flat square in z = 0 faces +z or -z, and nothing else.
        for (v in 0 until square().vertexCount) {
            assertEquals(1f, abs(normal.array[v * 3 + 2]), 1e-5f, "vertex $v should face along z")
        }
    }

    @Test
    fun `an existing normal is kept rather than recomputed`() {
        val given = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f)
        val mesh = TriMesh(
            positions = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f),
            indices = intArrayOf(0, 1, 2),
            normals = given,
        )
        val normal = assertNotNull(mesh.toThreeDrawable().geometry.attributes["normal"])
        assertTrue(given.contentEquals(normal.array), "a mesh that brought its own normals kept them")
    }

    @Test
    fun `a big mesh gets a 32-bit index, a small one stays 16-bit`() {
        assertEquals("Uint16Array", square().toThreeDrawable().geometry.index!!.type)

        // A triangle strip just past the limit, rather than a subdivided sphere: an icosphere only
        // reaches 65,535 vertices at 7 subdivisions, which is 327,680 triangles to prove a one-line
        // branch. The index width is what is under test, not the generator.
        val n = 65_600
        val strip = TriMesh(
            positions = FloatArray(n * 3) { it.toFloat() * 1e-4f },
            indices = IntArray((n - 2) * 3) { i -> i / 3 + i % 3 },
        )
        assertTrue(strip.vertexCount > 65535, "this test needs a mesh past the 16-bit index limit")
        assertEquals(
            "Uint32Array", strip.toThreeDrawable().geometry.index!!.type,
            "a mesh with more than 65,535 vertices silently loses triangles on a 16-bit index",
        )
    }

    // ------------------------------------------------------------------ the styles

    /**
     * Back-face culling must survive the change of renderer.
     *
     * `MeshView.cullBackFaces` is on deliberately: a face wound the wrong way is *missing*, which is
     * the single most common "why is my mesh full of holes" cause and the diagnostic
     * `typography_examples.md` teaches. `DoubleSide` would quietly repair the very defect the figure
     * exists to show.
     */
    @Test
    fun `culling back faces means single-sided, and that is the default`() {
        assertFalse(
            ThreeDrawStyle.surface().doubleSided,
            "surface() must cull by default, as MeshView.cullBackFaces does",
        )
        assertTrue(ThreeDrawStyle.surface(cullBackFaces = false).doubleSided)
    }

    /**
     * A solid writes depth. Without this the far side of a sphere paints over the near side and the
     * result reads as a strange flat blob rather than as a bug.
     */
    @Test
    fun `a surface writes depth and an FLine fill does not`() {
        assertTrue(ThreeDrawStyle.surface().depthWrite, "a solid with no depth write has no self-occlusion")
        assertFalse(
            ThreeDrawStyle.flineFills.depthWrite,
            "FLine fills stack as translucent sheets and must not occlude each other",
        )
    }

    // ------------------------------------------------------------------ the compatibility promise

    /**
     * The payload is additive: a scene with no drawables encodes exactly as it did before any of this.
     *
     * `encodeDefaults = true` would normally put every new field on the wire, which is why they carry
     * `@EncodeDefault(NEVER)`. Without it every existing viewer, every existing capture and every
     * existing test would be handed five keys it never asked for.
     */
    @Test
    fun `an FLine payload carries none of the new keys`() {
        val line = fline {
            moveTo(vec(0.0, 0.0, 0.0))
            lineTo(vec(1.0, 1.0, 0.0))
        }
        val payload = buildThreeGeometryPayload("t", listOf(line), 12, autoFrame = true, editable = false)
        val encoded = json.encodeToString(payload)

        for (key in listOf("drawables", "panels", "columns", "background", "camera", "helpers")) {
            assertFalse(
                encoded.contains("\"$key\""),
                "an FLine payload should not mention `$key`; the drawable layer is additive",
            )
        }
    }

    @Test
    fun `a mesh scene does carry them`() {
        val payload = ThreeGeometryPayload(
            uid = "t",
            drawables = listOf(square().toThreeDrawable()),
            helpers = false,
        )
        val encoded = json.encodeToString(payload)
        assertTrue(encoded.contains("\"drawables\""), "a mesh scene must send its drawables")
        assertTrue(encoded.contains("\"helpers\":false"), "a mesh scene must turn the world grid off")
    }

    /**
     * The grid and the tripod are fixed world sizes; a mesh is whatever size its file says.
     *
     * The toy car in `geometry_from_disk.md` is 7cm across, so a 10-unit grid is 140 times its width.
     * The rasterizer draws neither, and a figure that changes renderer should not sprout furniture.
     */
    @Test
    fun `helpers follow the content unless asked otherwise`() {
        val meshOnly = ThreeSceneBuilder(48).apply { mesh(square()) }
        assertFalse(meshOnly.helpersEnabled(), "a mesh scene defaults to no grid and no axes")

        val linesOnly = ThreeSceneBuilder(48).apply { lines(fline { }) }
        assertTrue(linesOnly.helpersEnabled(), "an FLine scene keeps the grid it has always had")

        val forced = ThreeSceneBuilder(48).apply { mesh(square()); helpers(true) }
        assertTrue(forced.helpersEnabled(), "an explicit helpers(true) wins")
    }

    // ------------------------------------------------------------------ the scene builder

    @Test
    fun `a mixed scene keeps the FLine geometry and the mesh side by side`() {
        val line = fline {
            moveTo(vec(0.0, 0.0, 0.0))
            lineTo(vec(4.0, 0.0, 0.0))
        }
        val payload = ThreeSceneBuilder(48).apply {
            mesh(square())
            lines(line)
        }.payload("t")

        assertEquals(1, payload.drawables.size, "the mesh should be a drawable")
        assertNotNull(payload.lines, "the FLine should still go down the batched line path")
        // Bounds must span both: the square reaches x = 1, the line reaches x = 4.
        val bounds = assertNotNull(payload.bounds)
        assertEquals(4f, bounds.max[0], 1e-5f, "the scene's bounds must include the FLine")
        assertEquals(0f, bounds.min[0], 1e-5f)
    }

    @Test
    fun `a turntable uploads the mesh once and frames every panel identically`() {
        val mesh = icosphereMesh(1).withColor(0.3, 0.7, 1.0)
        val builder = ThreeSceneBuilder(48)
        val shared = mesh.toThreeBounds()
        builder.mesh(mesh, panel = -1)
        builder.columns(4)
        repeat(4) { i ->
            builder.panel(camera = ThreeCameraSpec(yawDegrees = 90.0 * i), bounds = shared)
        }
        val payload = builder.payload("t")

        assertEquals(1, payload.drawables.size, "a turntable must not upload the mesh once per view")
        assertEquals(-1, payload.drawables[0].panel, "panel = -1 is what makes it appear in all four")
        assertEquals(4, payload.panels.size)
        payload.panels.forEach { p ->
            val framed = assertNotNull(p.bounds, "a turntable panel must say what it frames")
            assertTrue(
                framed.max.contentEquals(shared.max) && framed.min.contentEquals(shared.min),
                "every turntable panel must frame the same bounds, or the shape breathes as it turns",
            )
        }
    }

    @Test
    fun `naming a camera turns auto-framing off`() {
        val withCamera = ThreeSceneBuilder(48).apply { mesh(square()); camera(yaw = 30.0) }.payload("t")
        assertFalse(withCamera.autoFrame, "a caller who named an angle means it")
        assertEquals(30.0, withCamera.camera!!.yawDegrees)

        val without = ThreeSceneBuilder(48).apply { mesh(square()) }.payload("t")
        assertTrue(without.autoFrame)
        assertNull(without.camera)
    }
}
