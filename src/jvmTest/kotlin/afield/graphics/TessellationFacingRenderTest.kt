package afield.graphics

import afield.stdlib.Vec3
import afield.util.PngEncoder
import java.io.File
import java.nio.file.Files
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The pixel proof for [FLine.tessellate]'s projection.
 *
 * "It compiles" and "the assertions pass" are not verification for a rendering path, and the
 * winding defect this branch fixes is *invisible* in Field's own default viewers: the Vulkan mesh
 * pipeline sets `VK_CULL_MODE_NONE` (`four/modules/MeshModule.kt:466`, `four/modules/Module.kt:803`)
 * and the three.js viewer uses `THREE.DoubleSide` (`threejs_viewer.html:900`). Both draw a
 * back-to-front triangle exactly as happily as a front-to-back one, so nothing on screen complains.
 *
 * That is precisely why it needs a picture taken with an instrument that *does* care. This test
 * renders a cube — six filled FLines, one per face, each with a square hole, each wound so that its
 * own normal points out of the cube — through a perspective camera with **back-face culling** and
 * flat diffuse shading, and writes a PNG.
 *
 * A correct tessellation shows three lit faces, each with a hole in it. A tessellation whose facing
 * is decided by the projected frame rather than by the polygon shows some of those faces missing:
 * you see straight through the cube to the inside of the far wall.
 *
 * The test itself asserts the same thing numerically, so it fails in CI without anyone looking.
 * To *look*, regenerate the pair:
 *
 * ```
 * AFIELD_TESSELLATION_RENDER_OUT=dev_docs/agents/artifacts/tessellate_facing \
 *     ./gradlew jvmTest --tests 'afield.graphics.TessellationFacingRenderTest'
 * ```
 *
 * and, for the "before" half, with `src/jvmMain/kotlin/afield/graphics/FLine.kt` reverted to
 * `590e1f781` first. The committed pair and the exact commands are in
 * `dev_docs/agents/artifacts/tessellate_facing/README.md`.
 */
class TessellationFacingRenderTest {

    // ------------------------------------------------------------------------------------- scene

    /** One cube face: the outward-wound square ring, and a square hole inside it. */
    private fun face(origin: Vec3, u: Vec3, v: Vec3): List<List<Vec3>> {
        fun at(a: Float, b: Float) = Vec3(
            origin.x + u.x * a + v.x * b,
            origin.y + u.y * a + v.y * b,
            origin.z + u.z * a + v.z * b
        )
        val outer = listOf(at(0f, 0f), at(1f, 0f), at(1f, 1f), at(0f, 1f))
        val hole = listOf(at(0.3f, 0.3f), at(0.3f, 0.7f), at(0.7f, 0.7f), at(0.7f, 0.3f))
        return listOf(outer, hole)
    }

    /**
     * A unit cube centred on the origin, as six independent filled FLines. Every face is wound
     * counter-clockwise *seen from outside*, which is the only convention a mesh can be built on.
     */
    private fun cubeFaces(): List<FLine> {
        val h = 0.5f
        val specs = listOf(
            // +x                     origin              u                     v
            Triple(Vec3(h, -h, h), Vec3(0f, 0f, -1f), Vec3(0f, 1f, 0f)),
            // -x
            Triple(Vec3(-h, -h, -h), Vec3(0f, 0f, 1f), Vec3(0f, 1f, 0f)),
            // +y
            Triple(Vec3(-h, h, h), Vec3(1f, 0f, 0f), Vec3(0f, 0f, -1f)),
            // -y
            Triple(Vec3(-h, -h, -h), Vec3(1f, 0f, 0f), Vec3(0f, 0f, 1f)),
            // +z
            Triple(Vec3(-h, -h, h), Vec3(1f, 0f, 0f), Vec3(0f, 1f, 0f)),
            // -z
            Triple(Vec3(h, -h, -h), Vec3(-1f, 0f, 0f), Vec3(0f, 1f, 0f))
        )
        return specs.map { (o, u, v) ->
            val f = FLine()
            face(o, u, v).forEach { ring ->
                f.moveTo(ring[0])
                ring.drop(1).forEach { f.lineTo(it) }
                f.close()
            }
            f
        }
    }

    /** The outward unit normal each face is *supposed* to have, in the same order as [cubeFaces]. */
    private fun expectedNormals(): List<Vec3> = listOf(
        Vec3(1f, 0f, 0f), Vec3(-1f, 0f, 0f),
        Vec3(0f, 1f, 0f), Vec3(0f, -1f, 0f),
        Vec3(0f, 0f, 1f), Vec3(0f, 0f, -1f)
    )

    // ------------------------------------------------------------------------------------ render

    private class Tri(val a: Vec3, val b: Vec3, val c: Vec3, val faceIndex: Int)

    private fun triangles(): List<Tri> {
        val out = ArrayList<Tri>()
        cubeFaces().forEachIndexed { faceIndex, line ->
            line.tessellate(4) { verts, index ->
                var i = 0
                while (i + 2 < index.size) {
                    fun v(k: Int) = Vec3(verts[3 * k], verts[3 * k + 1], verts[3 * k + 2])
                    out.add(Tri(v(index[i]), v(index[i + 1]), v(index[i + 2]), faceIndex))
                    i += 3
                }
            }
        }
        return out
    }

    private fun cross(a: Vec3, b: Vec3) =
        Vec3(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x)

    private fun sub(a: Vec3, b: Vec3) = Vec3(a.x - b.x, a.y - b.y, a.z - b.z)

    private fun dot(a: Vec3, b: Vec3) = a.x * b.x + a.y * b.y + a.z * b.z

    private fun normalize(a: Vec3): Vec3 {
        val l = sqrt(dot(a, a))
        return if (l == 0f) a else Vec3(a.x / l, a.y / l, a.z / l)
    }

    private fun triNormal(t: Tri) = normalize(cross(sub(t.b, t.a), sub(t.c, t.a)))

    private val width = 640
    private val height = 480
    private val eye = Vec3(2.4f, 1.9f, 2.8f)

    /** World -> screen, through a look-at-the-origin perspective camera. */
    private fun camera(): (Vec3) -> FloatArray {
        val forward = normalize(Vec3(-eye.x, -eye.y, -eye.z))
        val right = normalize(cross(forward, Vec3(0f, 1f, 0f)))
        val up = cross(right, forward)
        val focal = 3.0f
        return { p ->
            val d = sub(p, eye)
            val x = dot(d, right)
            val y = dot(d, up)
            val z = dot(d, forward)
            floatArrayOf(
                width / 2f + focal * x / z * (height / 2f),
                height / 2f - focal * y / z * (height / 2f),
                z
            )
        }
    }

    /**
     * Rasterise with a z-buffer and back-face culling. Returns the image and, per source face, how
     * many pixels of it survived culling — which is the number the assertions read.
     */
    private fun render(tris: List<Tri>): Pair<IntArray, IntArray> {
        val pixels = IntArray(width * height) { 0xFF101418.toInt() }
        val depth = FloatArray(width * height) { Float.MAX_VALUE }
        val owner = IntArray(width * height) { -1 }
        val toScreen = camera()
        val light = normalize(Vec3(0.5f, 0.9f, 0.7f))
        val tint = listOf(
            Triple(0.95f, 0.45f, 0.35f), Triple(0.55f, 0.30f, 0.25f),
            Triple(0.45f, 0.90f, 0.55f), Triple(0.28f, 0.55f, 0.35f),
            Triple(0.45f, 0.65f, 0.98f), Triple(0.28f, 0.40f, 0.62f)
        )

        tris.forEach { t ->
            val n = triNormal(t)
            // back-face cull against the eye: this is the whole instrument
            if (dot(n, sub(t.a, eye)) >= 0f) return@forEach

            val sa = toScreen(t.a)
            val sb = toScreen(t.b)
            val sc = toScreen(t.c)
            if (sa[2] <= 0f || sb[2] <= 0f || sc[2] <= 0f) return@forEach

            val lambert = 0.25f + 0.75f * maxOf(0f, dot(n, light))
            val (r, g, b) = tint[t.faceIndex]
            val colour = 0xFF000000.toInt() or
                    (((r * lambert * 255).toInt().coerceIn(0, 255)) shl 16) or
                    (((g * lambert * 255).toInt().coerceIn(0, 255)) shl 8) or
                    ((b * lambert * 255).toInt().coerceIn(0, 255))

            val minX = maxOf(0, minOf(sa[0], sb[0], sc[0]).toInt())
            val maxX = minOf(width - 1, maxOf(sa[0], sb[0], sc[0]).toInt() + 1)
            val minY = maxOf(0, minOf(sa[1], sb[1], sc[1]).toInt())
            val maxY = minOf(height - 1, maxOf(sa[1], sb[1], sc[1]).toInt() + 1)
            val denom = (sb[1] - sc[1]) * (sa[0] - sc[0]) + (sc[0] - sb[0]) * (sa[1] - sc[1])
            if (denom == 0f) return@forEach

            for (py in minY..maxY) for (px in minX..maxX) {
                val fx = px + 0.5f
                val fy = py + 0.5f
                val w0 = ((sb[1] - sc[1]) * (fx - sc[0]) + (sc[0] - sb[0]) * (fy - sc[1])) / denom
                val w1 = ((sc[1] - sa[1]) * (fx - sc[0]) + (sa[0] - sc[0]) * (fy - sc[1])) / denom
                val w2 = 1f - w0 - w1
                if (w0 < 0f || w1 < 0f || w2 < 0f) continue
                val z = w0 * sa[2] + w1 * sb[2] + w2 * sc[2]
                val at = py * width + px
                if (z >= depth[at]) continue
                depth[at] = z
                pixels[at] = colour
                owner[at] = t.faceIndex
            }
        }
        // count off the finished frame, so pixels that were later overdrawn do not inflate the tally
        val cover = IntArray(6)
        owner.forEach { if (it >= 0) cover[it]++ }
        return pixels to cover
    }

    // ------------------------------------------------------------------------------------- tests

    /**
     * Every face's tessellated triangles must be wound with the face's own outward normal. This is
     * the assertion the picture illustrates.
     */
    @Test
    fun everyCubeFaceIsWoundOutward() {
        val normals = expectedNormals()
        val wrong = triangles().filter { dot(triNormal(it), normals[it.faceIndex]) < 0.99f }
        assertTrue(
            wrong.isEmpty(),
            "${wrong.size} triangles are wound against their face: " +
                    wrong.map { it.faceIndex }.distinct().sorted()
        )
    }

    /**
     * And the same fact read off the rendered pixels: with back-face culling on, exactly the three
     * faces pointing at the camera are drawn, each of them substantially, and none of the three
     * facing away leaks through.
     */
    @Test
    fun theCubeRendersAsThreeLitFacesWithHoles() {
        val (pixels, cover) = render(triangles())

        val out = System.getenv("AFIELD_TESSELLATION_RENDER_OUT")
            ?: System.getProperty("afield.tessellation.render.out")
        val dir = if (out != null) File(out).also { it.mkdirs() }
        else Files.createTempDirectory("tessellate-facing").toFile()
        val png = File(dir, "cube_faces.png")
        png.writeBytes(PngEncoder.encode(pixels, width, height))

        // eye is at (+x, +y, +z), so faces 0 (+x), 2 (+y) and 4 (+z) face the camera
        val visible = listOf(0, 2, 4)
        val hidden = listOf(1, 3, 5)
        val message = "wrote $png; coverage by face = ${cover.toList()}"

        visible.forEach {
            assertTrue(cover[it] > 4000, "face $it should be a large lit quad but covers ${cover[it]}px. $message")
        }
        hidden.forEach {
            assertTrue(cover[it] == 0, "face $it points away and must be culled, but covers ${cover[it]}px. $message")
        }

        // the holes: the three visible faces cannot cover the whole silhouette
        val background = pixels.count { it == 0xFF101418.toInt() }
        assertTrue(
            background > width * height / 3,
            "the holes and the surround should leave plenty of background, only $background px. $message"
        )
        println(message)
    }
}
