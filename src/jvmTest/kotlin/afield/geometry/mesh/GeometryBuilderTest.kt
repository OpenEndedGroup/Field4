package afield.geometry.mesh

import afield.graphics.color.hsl
import afield.graphics.three.ThreeSceneBuilder
import afield.graphics.three.geometry
import afield.math.linalg.vec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [GeometryBuilder], the madp geometry toolbox's triangle language, held to the toolbox's own numbers:
 * its triangle counts, its random stream and its noise, its transform order and its part rules.
 */
class GeometryBuilderTest {

    @Test
    fun `surface emits two triangles per grid cell, as the toolbox counts them`() {
        // The ripple terrain preset reports "11858 triangles / 1 meshes" in the browser.
        val scene = geometryScene { surface(78, 78) { u, v -> vertex(u, 0.0, v) } }
        assertEquals(11858, scene.triangleCount)
        assertEquals(1, scene.parts.size)
        assertEquals(11858 * 3, scene.parts[0].mesh.vertexCount)
    }

    @Test
    fun `rand is the toolbox's LCG and noise its Perlin table`() {
        // Reference values from 3d.html's GeometryRuntime.rand (seed 18) and noise2D, run in a browser.
        val b = GeometryBuilder()
        b.seed(18)
        val first = b.rand()
        // (1664525 * 18 + 1013904223) mod 2^32 / 2^32
        assertEquals(((1664525L * 18 + 1013904223L) and 0xffffffffL) / 4294967296.0, first, 1e-15)
        assertEquals(0.0, b.noise(0.0, 0.0), 1e-12)
        assertEquals(0.0, b.noise(3.0, 7.0), 1e-12, "noise is zero on lattice points")
        val n = b.noise(0.375, 1.625)
        assertTrue(n.isFinite() && abs(n) < 1.0)
        assertEquals(n, GeometryBuilder().noise(0.375, 1.625), "noise is a function of its arguments")
    }

    @Test
    fun `transforms apply innermost first and push and pop restore them`() {
        val scene = geometryScene {
            push()
            translate(1.0, 0.0, 0.0)
            rotateY(PI / 2)
            tri(vec(1, 0, 0), vec(0, 1, 0), vec(0, 0, 1))
            pop()
            tri(vec(1, 0, 0), vec(0, 1, 0), vec(0, 0, 1))
        }
        val p = scene.parts.single().mesh.positions
        // rotateY(90°) takes +x to -z, then translate moves it to (1, 0, -1).
        assertEquals(1f, p[0], 1e-6f); assertEquals(0f, p[1], 1e-6f); assertEquals(-1f, p[2], 1e-6f)
        // After pop() the second triangle is untransformed.
        assertEquals(1f, p[9], 1e-6f); assertEquals(0f, p[11], 1e-6f)
    }

    @Test
    fun `a material change after triangles starts a new part, and equal parts merge`() {
        val scene = geometryScene {
            mesh("body"); mat(roughness = 0.7)
            box(vec(0, 0, 0), 1.0)
            mesh("rim"); mat(color = "#f4d28a", metalness = 0.65)
            box(vec(0, 1, 0), 0.5)
            mesh("body"); mat(color = "#ffffff", metalness = 0.04)
            box(vec(2, 0, 0), 1.0)
        }
        assertEquals(listOf("body", "rim"), scene.parts.map { it.name })
        assertEquals(24, scene.parts[0].mesh.triangleCount, "the two body boxes are one part")
        assertEquals(0.65, scene.parts[1].material.metalness)
    }

    @Test
    fun `colours are stored linear, and hsl means what three's setHSL means`() {
        val scene = geometryScene { tri(vertex(0.0, 0.0, 0.0, hsl(0, 1, 0.25)), vertex(1.0, 0.0, 0.0), vertex(0.0, 1.0, 0.0)) }
        val c = scene.parts.single().mesh.colors!!
        // three.js Color.setHSL(0, 1, 0.25) in its linear working space is (0.5, 0, 0).
        assertEquals(0.5f, c[0], 2e-3f)
        assertEquals(0f, c[1], 1e-6f)
        // An uncoloured vertex takes the current colour, white.
        assertEquals(1f, c[3], 1e-6f)
    }

    @Test
    fun `revolve and tube make closed rings of quads`() {
        val vessel = geometryScene { revolve(listOf(profile(1.0, 0.0), profile(0.5, 1.0), profile(0.8, 2.0)), 12) }
        assertEquals(2 * 12 * 2, vessel.triangleCount)
        val knot = geometryScene { tube(40, 6) { t -> section(vec(sin(t * 2 * PI), 0.0, 0.0), 0.1) } }
        assertEquals(40 * 6 * 2, knot.triangleCount)
        val stem = geometryScene { tube(10, 6, closed = false) { t -> section(vec(0.0, t, 0.0), 0.1) } }
        assertEquals(9 * 6 * 2, stem.triangleCount)
    }

    @Test
    fun `every triangle carries its own face normal`() {
        val mesh = geometryScene { box(vec(0, 0, 0), 2.0) }.parts.single().mesh
        val n = mesh.normals!!
        for (t in 0 until mesh.triangleCount) {
            val o = t * 9
            for (k in 1 until 3) for (c in 0 until 3) assertEquals(n[o + c], n[o + k * 3 + c])
            assertEquals(1f, n[o] * n[o] + n[o + 1] * n[o + 1] + n[o + 2] * n[o + 2], 1e-5f)
        }
    }

    @Test
    fun `the three view carries each part's material and the studio`() {
        val scene = geometryScene {
            view(background = "#060807")
            mat(color = "#f4d28a", roughness = 0.38, metalness = 0.65)
            box(vec(0, 0, 0), 1.0)
        }
        val payload = ThreeSceneBuilder(48).apply { geometry(scene) }.payload("test")
        val m = assertNotNull(payload.drawables.single().style.material)
        assertEquals(0.38f, m.roughness)
        assertEquals(0.65f, m.metalness)
        assertEquals(srgbToLinear(0xf4 / 255f), m.color[0], 1e-6f)
        assertNotNull(payload.studio)
        assertEquals(-1.25, payload.studio!!.groundY)
        assertEquals("#060807", payload.background)
        assertEquals(false, payload.helpers)
    }

    @Test
    fun `a glb has a node, a material and the right accessors per part`() {
        val glb = geometryScene {
            mesh("a"); box(vec(0, 0, 0), 1.0)
            mesh("b"); mat(opacity = 0.5); box(vec(1, 0, 0), 1.0)
        }.toGlb()
        val json = glbJson(glb)
        assertEquals(2, json["nodes"]!!.jsonArray.size)
        assertEquals("BLEND", json["materials"]!!.jsonArray[1].jsonObject["alphaMode"]!!.jsonPrimitive.content)
        val position = json["meshes"]!!.jsonArray[0].jsonObject["primitives"]!!.jsonArray[0]
            .jsonObject["attributes"]!!.jsonObject["POSITION"]!!.jsonPrimitive.int
        assertEquals(36, json["accessors"]!!.jsonArray[position].jsonObject["count"]!!.jsonPrimitive.int)
        assertEquals(glb.size, ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN).getInt(8))
    }

    @Test
    fun `an animated glb is morph targets played by a weights animation`() {
        val frames = 6
        val glb = geometryAnimationGlb(frames = frames) {
            surface(4, 3) { u, v -> vertex(u, sin(u * 4 + t), v) }
        }
        val json = glbJson(glb)
        val primitive = json["meshes"]!!.jsonArray[0].jsonObject["primitives"]!!.jsonArray[0].jsonObject
        assertEquals(frames - 1, primitive["targets"]!!.jsonArray.size)
        val animation = json["animations"]!!.jsonArray.single().jsonObject
        val sampler = animation["samplers"]!!.jsonArray[0].jsonObject
        val output = json["accessors"]!!.jsonArray[sampler["output"]!!.jsonPrimitive.int].jsonObject
        assertEquals(frames * (frames - 1), output["count"]!!.jsonPrimitive.int)
        assertEquals("weights", animation["channels"]!!.jsonArray[0].jsonObject["target"]!!.jsonObject["path"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an animation whose topology changes is refused, naming the frame`() {
        val e = assertFailsWith<IllegalArgumentException> {
            geometryAnimationGlb(frames = 4) { surface(3 + frame, 3) { u, v -> vertex(u, 0.0, v) } }
        }
        assertTrue("frame 1" in e.message!!, e.message)
    }

    private fun glbJson(glb: ByteArray) = run {
        val b = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x46546C67, b.getInt(0))
        val length = b.getInt(12)
        assertEquals(0x4E4F534A, b.getInt(16))
        Json.parseToJsonElement(String(glb, 20, length, Charsets.UTF_8).trimEnd()).jsonObject
    }
}
