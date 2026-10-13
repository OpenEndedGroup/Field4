package afield.graphics.three

import afield.geometry.path.div
import afield.geometry.path.end
import afield.geometry.path.minus
import afield.geometry.path.plus
import afield.geometry.path.start
import afield.geometry.path.times
import afield.lang.remotejs.JsFun

import afield.geometry.mesh.TriMesh
import afield.geometry.mesh.icosphereMesh
import afield.geometry.mesh.withVertexNormals
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [ThreeSceneBuilder.shot] and the two per-mesh things it animates: explode offsets and a solid opacity.
 *
 * The viewer side (the clock, the easing, the explode arithmetic) is tools/test_three_shot.mjs; this is the
 * payload the viewer is given, and the one non-obvious step on the way: explode offsets belong to vertices,
 * and the crease shading in [toThreeDrawable] splits vertices, so the offsets have to follow them.
 */
class ThreeShotTest {
    private val json = Json { encodeDefaults = false }

    /** A unit cube with no normals: shading splits every corner into three, one per face. */
    private fun cube(): TriMesh {
        val p = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 1f, 1f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 1f, 0f, 1f, 1f, 1f, 1f, 0f, 1f, 1f)
        val i = intArrayOf(0, 2, 1, 0, 3, 2, 4, 5, 6, 4, 6, 7, 0, 1, 5, 0, 5, 4, 2, 3, 7, 2, 7, 6, 1, 2, 6, 1, 6, 5, 0, 4, 7, 0, 7, 3)
        return TriMesh(p, i)
    }

    @Test
    fun `a shot reaches the payload with its keys and the default is the money shot`() {
        val builder = ThreeSceneBuilder(48)
        builder.mesh(icosphereMesh(1))
        builder.shot()
        val payload = builder.payload("t")
        val shot = assertNotNull(payload.shot)
        assertEquals(ThreeShotSpec.MONEY_SHOT, shot.keys)
        assertEquals(0.0, shot.keys.first().at); assertEquals(1.0, shot.keys.last().at)
        // The loop closes: its end looks like its start.
        assertEquals(shot.keys.first().copy(at = 1.0), shot.keys.last())
        assertTrue(json.encodeToString(ThreeGeometryPayload.serializer(), payload).contains("\"shot\""))
    }

    @Test
    fun `a scene without a shot does not mention one`() {
        val builder = ThreeSceneBuilder(48)
        builder.mesh(icosphereMesh(1))
        val encoded = json.encodeToString(ThreeGeometryPayload.serializer(), builder.payload("t"))
        assertFalse(encoded.contains("\"shot\""))
        assertFalse(encoded.contains("\"solidOpacity\""))
        assertFalse(encoded.contains("\"explode\""))
    }

    @Test
    fun `bad keys are refused with what was wrong`() {
        val b = ThreeSceneBuilder(48)
        assertFailsWith<IllegalArgumentException> { b.shot(keys = listOf(ThreeShotKey(0.0))) }
        assertFailsWith<IllegalArgumentException> { b.shot(keys = listOf(ThreeShotKey(0.1), ThreeShotKey(1.0))) }
        assertFailsWith<IllegalArgumentException> { b.shot(keys = listOf(ThreeShotKey(0.0), ThreeShotKey(.6), ThreeShotKey(.4), ThreeShotKey(1.0))) }
        assertFailsWith<IllegalArgumentException> { b.shot(keys = listOf(ThreeShotKey(0.0, distance = 0.0), ThreeShotKey(1.0))) }
        assertFailsWith<IllegalArgumentException> { b.shot(keys = listOf(ThreeShotKey(0.0, xray = 1.5), ThreeShotKey(1.0))) }
        assertFailsWith<IllegalArgumentException> { b.shot(periodSeconds = 0.5) }
        assertNull(b.payload("t").shot, "a refused shot leaves none behind")
        assertFailsWith<IllegalArgumentException> { b.mesh(cube(), solidOpacity = 1.5) }
        assertFailsWith<IllegalArgumentException> { b.mesh(cube(), explode = FloatArray(5)) }
    }

    @Test
    fun `solid opacity rides on the style and the authored opacity is the see-through one`() {
        val builder = ThreeSceneBuilder(48)
        builder.mesh(cube(), opacity = .4, solidOpacity = 1.0)
        val style = builder.payload("t").drawables.single().style
        assertEquals(.4f, style.opacity)
        assertEquals(1f, style.solidOpacity)
        assertFalse(style.depthWrite, "the authored, see-through state does not write depth")
    }

    @Test
    fun `explode offsets follow their vertices through the crease shading`() {
        val mesh = cube()
        // Every vertex of the top face (z = 1) moves up 2; the bottom ones stay.
        val explode = FloatArray(mesh.vertexCount * 3).also { e -> for (v in 0 until mesh.vertexCount) if (mesh.positions[v * 3 + 2] == 1f) e[v * 3 + 2] = 2f }
        val drawable = mesh.toThreeDrawable(explode = explode)
        val positions = drawable.geometry.attributes.getValue("position").array
        val offsets = assertNotNull(drawable.geometry.attributes["explode"]).array
        assertTrue(positions.size > mesh.positions.size, "the cube's corners were split by the shading")
        assertEquals(positions.size, offsets.size, "one offset per emitted vertex")
        for (v in 0 until positions.size / 3) {
            assertEquals(if (positions[v * 3 + 2] == 1f) 2f else 0f, offsets[v * 3 + 2], "vertex $v")
            assertEquals(0f, offsets[v * 3]); assertEquals(0f, offsets[v * 3 + 1])
        }
    }

    @Test
    fun `a mesh with its own normals keeps its vertices and its offsets as given`() {
        val mesh = icosphereMesh(1).withVertexNormals()
        assertTrue(mesh.hasNormals)
        val explode = FloatArray(mesh.vertexCount * 3) { (it % 7).toFloat() }
        val drawable = mesh.toThreeDrawable(explode = explode)
        assertContentEquals(explode, drawable.geometry.attributes.getValue("explode").array)
    }
}
