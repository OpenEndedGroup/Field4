package afield.graphics.scene.reading

import afield.graphics.text.*
import afield.graphics.text.slug.SlugPath
import afield.math.linalg.*
import kotlin.test.*

class ReadingFrostTest {
    @Test
    fun clippingFacesEitherViewerAndExcludesCoplanarInk() {
        val world = Matrix4f().translation(.2f, .3f, -.4f).rotateY(.7f).rotateZ(.2f)
        val normal = world.transformDirection(Vector3f(0f, 0f, 1f))
        val origin = world.transformPosition(Vector3f())
        for (side in listOf(-1f, 1f)) {
            val eye = Vector3f(origin).fma(side * 2f, normal)
            val plane = readingFrostClipPlane(world, eye, .002f)
            fun sample(p: Vector3f) = plane.dot(Vector4f(p, 1f))
            assertTrue(sample(origin) < 0f)
            assertTrue(sample(Vector3f(origin).fma(side * .01f, normal)) < 0f)
            assertTrue(sample(Vector3f(origin).fma(-side * .01f, normal)) > 0f)
        }
    }

    @Test
    fun surfaceAndUnionStayInTheSameMetresAcrossPoseAndScaleChanges() {
        lateinit var border: DiagramTextFrame
        val graph = readingDiagram {
            val run = text("text", "HI\ngyp", point("p", .2, .3, .1)) {
                scale = 1.7f
                rotation = .45f
                orientation.rotateY(.3f)
            }
            border = textFrame("frame", run, .02f, DiagramTextFrameMode.LINE_UNION)
        }
        val face = Typefaces.named("Serif", 100)
        val atlas = SlugPath.decode(requireNotNull(javaClass.getResource("/afield/slug/curved-hole.bin")).readBytes())
        val run = graph.text("text")
        val layout = layoutText(run.text, face)
        val shape = ReadingText(run.text, layout.advanceWidth.toFloat(), layout.toFLine(), atlas, layout)
        val player =
            ReadingDiagramPlayer(graph, face, mapOf(run.id to shape), diagramScore(graph, 10.0) {}, mapOf(run to shape))
        player.playback.playing = false
        assertTrue(player.frostSurfaces.isEmpty())
        border.frost.enabled = true
        assertEquals(listOf(border), graph.textFrames)
        for (scale in listOf(.001f, .002f)) {
            player.textScale = scale
            val f = player.frame()
            val surface = player.frostSurfaces.single()
            val g = surface.geometry
            for (edge in g.edges) {
                val p = edge.a
                val actual = surface.localToDiagram.transformPosition(Vector3f(p.x.toFloat(), p.y.toFloat(), 0f))
                val expected = f.textPoint(run, 0f, 0f).add(f.textDirection(run, p.x.toFloat(), p.y.toFloat(), 0f))
                assertTrue(actual.distance(expected) < 1e-6f)
            }
        }
        border.frost.opacity = 0f
        assertTrue(player.frostSurfaces.isEmpty())
    }
}
