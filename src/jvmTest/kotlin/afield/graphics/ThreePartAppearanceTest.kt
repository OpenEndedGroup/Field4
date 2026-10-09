package afield.graphics

import afield.stdlib.TriMesh
import afield.Box
import afield.SessionInformation
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

class ThreePartAppearanceTest {
    private fun fixture(): Pair<Box, ThreeGeometryViewState> {
        val box = Box("shape")
        val info = SessionInformation(Files.createTempDirectory("part-style-").toString(), documentName = "style", root = box, ephemeral = true)
        box.inside = info; info.boxes[box.id] = box
        return box to box.three(name = "parts") { mesh(TriMesh(floatArrayOf(0f,0f,0f,1f,0f,0f,0f,1f,0f), intArrayOf(0,1,2)), id = "a", pickable = true) }
    }
    @Test fun stylingPreservesSourceGeometryAndRevisionAndSurvivesRebuild() {
        val (box, view) = fixture()
        val source = view.sourcePayload!!
        val revision = threeReviewRevision(source)
        val style = ThreePartAppearance(listOf(1.0,0.8,0.1), unlit = true, xrayOpacity = 1.0)
        view.styleParts(revision, mapOf("a" to style))
        val styled = applyPartAppearance(source, mapOf("a" to style))
        assertSame(source, view.sourcePayload)
        assertEquals(revision, view.reviews()["revision"]!!.jsonPrimitive.content)
        assertContentEquals(source.drawables[0].geometry.attributes["position"]!!.array, styled.drawables[0].geometry.attributes["position"]!!.array)
        assertSame(source.drawables[0].geometry.index, styled.drawables[0].geometry.index)
        assertEquals(1f, styled.drawables[0].style.xrayOpacity)
        assertTrue(styled.drawables[0].style.unlit)
        val saved = box.properties["geometryAppearance"]
        view.update(source)
        assertEquals(saved, box.properties["geometryAppearance"])
        assertEquals(revision, view.reviews()["revision"]!!.jsonPrimitive.content)
        view.styleParts(revision, emptyMap(), replace = true)
        assertNotEquals(saved, box.properties["geometryAppearance"])
    }
    @Test fun staleUnknownAndInvalidStylesCannotModifyStoredPresentation() {
        val (box, view) = fixture(); val revision = threeReviewRevision(view.sourcePayload!!)
        val good = ThreePartAppearance(listOf(1.0,0.0,0.0))
        assertFailsWith<IllegalStateException> { view.styleParts("old", mapOf("a" to good)) }
        assertFailsWith<IllegalArgumentException> { view.styleParts(revision, mapOf("unknown" to good)) }
        assertFailsWith<IllegalArgumentException> { view.styleParts(revision, mapOf("a" to good.copy(color = listOf(Double.NaN,0.0,0.0)))) }
        assertFailsWith<IllegalArgumentException> { view.styleParts(revision, mapOf("a" to good.copy(xrayOpacity = 2.0))) }
        assertNull(box.properties["geometryAppearance"])
    }
}
