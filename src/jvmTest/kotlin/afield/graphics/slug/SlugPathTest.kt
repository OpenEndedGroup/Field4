package afield.graphics.slug

import afield.graphics.FLine
import afield.four.modules.Introspection
import afield.four.modules.ShaderKind
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.*

class SlugPathTest {
    private fun fixture() = requireNotNull(javaClass.getResource("/afield/slug/curved-hole.bin")).readBytes()
    private fun changed(word: Int, value: Int) = fixture().also {
        ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(word * 4, value)
    }
    @Test fun nativeFixtureDecodesAndCannotBeMutated() {
        val path = SlugPath.decode(fixture())
        assertTrue(path.curveCount > 8)
        assertEquals(fixture().size.toLong(), path.byteSize)
        path.words[20] = 0
        assertTrue(path.curveCount > 8)
    }
    @Test fun rejectsMalformedHeaders() {
        for ((word, value) in listOf(0 to 0, 1 to 2, 2 to 256, 3 to Int.MAX_VALUE,
            6 to 0, 7 to 0, 8 to Float.NaN.toBits(), 12 to 0, 16 to 511,
            17 to Int.MAX_VALUE, 18 to 32, 19 to -1, 20 to 0, 21 to -1)) {
            assertFailsWith<IllegalArgumentException>("word $word") { SlugPath.decode(changed(word, value)) }
        }
        assertFailsWith<IllegalArgumentException> { SlugPath.decode(fixture().copyOf(95)) }
        assertFailsWith<IllegalArgumentException> { SlugPath.decode(fixture().copyOf(fixture().size - 4)) }
    }
    @Test fun rejectsInvalidGpuAddressesAndNonfiniteCurves() {
        val w = SlugPath.decode(fixture()).words
        val origin = w[7] + w[17] * 512 + w[16]
        assertFailsWith<IllegalArgumentException> { SlugPath.decode(changed(origin, 65535)) }
        assertFailsWith<IllegalArgumentException> { SlugPath.decode(changed(origin + 64, -1)) }
        val firstList = origin + (w[origin + 64] ushr 16)
        assertFailsWith<IllegalArgumentException> { SlugPath.decode(changed(firstList, -1)) }
        val xy = w[firstList]
        val curve = w[6] + ((xy ushr 16) * 512 + (xy and 65535)) * 4
        assertFailsWith<IllegalArgumentException> { SlugPath.decode(changed(curve, Float.NaN.toBits())) }
    }
    @Test fun serializesAllContoursAndRejectsInvalidGeometry() {
        val line = FLine().moveTo(0, 0).lineTo(1, 0).lineTo(1, 1).close()
            .moveTo(0.2, 0.2).lineTo(0.2, 0.3).lineTo(0.3, 0.2).close()
        val source = SlugPath.commands(line, 0.001f)
        assertEquals(2, source.lines().count { it == "Z" })
        assertEquals(2, source.lines().count { it.startsWith("M ") })
        for (t in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY))
            assertFailsWith<IllegalArgumentException> { SlugPath.commands(line, t) }
        assertFailsWith<IllegalArgumentException> { SlugPath.commands(FLine(), 0.001f) }
        line.nodes[1].storage[0].z = 1f
        assertFailsWith<IllegalArgumentException> { SlugPath.commands(line, 0.001f) }
        line.nodes[1].storage[0].z = 0f
        line.nodes[1].storage[0].x = Float.NaN
        assertFailsWith<IllegalArgumentException> { SlugPath.commands(line, 0.001f) }
    }
    @Test fun canonicalStarterLoadsWithRendererAndMatchingShaders() {
        val root = java.io.File("public/docs/assets/canonical_graphics")
        val uid = "628f3f96-42d0-48dd-93ea-ea4d9f03b865"
        val renderer = "611072da-1af6-4d2d-85bf-07a9ba191aca"
        val boxes = afield.SavingContext().readEdnTransfers(root.absolutePath).associateBy { it.id }
        assertEquals("5e. Slug filled paths", boxes.getValue(uid).properties["name"])
        assertTrue(renderer in boxes.getValue(uid).properties["parents"].toString())
        assertTrue(uid in boxes.getValue(renderer).properties["children"].toString())
        assertEquals(SlugShaders.vertex, java.io.File(root, "${uid}_vertex_.glsl").readText())
        assertEquals(SlugShaders.fragment, java.io.File(root, "${uid}_fragment_.glsl").readText())
        assertEquals(java.io.File("dev_docs/graphics/slug-prototype/code.kt").readText(),
            java.io.File(root, "${uid}_code_.kt").readText())
    }
    @Test fun shadersCompileWithMatchingPortablePushConstants() {
        for ((source, kind) in listOf(SlugShaders.vertex to ShaderKind.VERTEX_SHADER,
            SlugShaders.fragment to ShaderKind.FRAGMENT_SHADER)) {
            val shader = Introspection(source, kind)
            try {
                assertTrue(shader.valid)
                val pc = shader.pushConstants.single()
                assertEquals(96, pc.size)
                assertEquals(listOf("mvp", "slugData", "viewport", "tint"), pc.members.map { it.name })
                assertEquals(listOf(0, 64, 72, 80), pc.members.map { it.offset })
            } finally { shader.code.free() }
        }
    }
}
