package afield.graphics.scene.reading

import afield.geometry.path.FLine
import afield.graphics.interaction.PointerPhase
import afield.graphics.interaction.PointerState
import afield.graphics.interaction.Ray
import afield.graphics.interaction.mirrorEyePixel
import afield.graphics.text.Typefaces
import afield.graphics.text.slug.SlugPath
import afield.math.linalg.*
import kotlin.math.abs
import kotlin.test.*

class ReadingPickingTest {
    private fun model(): ReadingDiagramPlayer {
        // CPU picking reads prepared layout and measurements; it never samples the atlas.
        val atlas =
            SlugPath.decode(requireNotNull(javaClass.getResource("/afield/slug/curved-hole.bin")).readBytes())
        val font = Typefaces.named("Serif", 100)
        val graph = readingDiagram {
            val root = point("root", -.8, .4, pinned = true)
            val baseline = point("baseline", 0, 0, pinned = true)
            link(root, baseline)
            val sentence = text("sentence", "one breath and another breath", baseline)
            sentence.all.annotate("whole")
            sentence.word("another").annotate("another-word")
            sentence.word("breath", occurrence = 1).annotate("second-breath")
            underline("underline", sentence.word("breath", occurrence = 1))
            dot("beside", sentence.word("one").baselineLeft.offset(y = .12f)) { radius = .004f }
        }
        graph.path("underline").properties["ref"] = "lean:Gnomon.odd_sum"
        val text = graph.texts.single().text
        val layout = afield.graphics.text.layoutText(text, font)
        val shape = ReadingText(text, layout.advanceWidth.toFloat(), FLine(), atlas, layout)
        val score =
            diagramScore(graph, 10.0) {
                enter(graph.text("sentence"), at = 0.0, seconds = 1.0)
                drawIn(graph.path("underline"), at = 1.0, seconds = 1.0)
            }
        return ReadingDiagramPlayer(graph, font, mapOf(text to shape), score).apply {
            gravity = 0f
            wind = 0f
        }
    }

    /** A ray from 2 m in front of [target] (diagram +z), aimed straight at it. */
    private fun rayAt(target: Vector3f, from: Vector3f = Vector3f(0f, 0f, 2f)) =
        Ray.through(Vector3f(target).add(from), target)

    private fun ReadingDiagramPlayer.settled() = apply {
        playback.playing = true
        update(3.0)
        playback.playing = false
    }

    private fun DiagramFrame.inkCentre(span: DiagramTextSpan): Vector3f {
        val m = measure(span)
        return textPoint(span.run, m.ink.centerX, m.ink.centerY)
    }

    @Test
    fun aRayAtAWordHitsItsCharactersAndInnermostAnnotation() {
        val p = model().settled()
        val f = p.currentFrame()
        val run = p.diagram.text("sentence")
        val word = run.word("another")
        val hit = assertNotNull(f.pick(rayAt(f.inkCentre(word))))
        assertEquals(DiagramHitKind.TEXT, hit.kind)
        assertEquals("sentence", hit.id)
        val index = assertNotNull(hit.charIndex)
        assertTrue(index in word.start until word.endExclusive, "char $index not in 'another'")
        assertEquals(listOf("another-word", "whole"), hit.annotations.map { it.value })
        assertEquals("another-word", hit.annotation)
        assertEquals(2.0, hit.distance, 1e-3)
        assertEquals(0.0, hit.miss)
    }

    @Test
    fun outsideAnnotatedWordsOnlyTheEnclosingAnnotationRemains() {
        val p = model().settled()
        val f = p.currentFrame()
        val hit = assertNotNull(f.pick(rayAt(f.inkCentre(p.diagram.text("sentence").word("and")))))
        assertEquals(listOf("whole"), hit.annotations.map { it.value })
        val and = p.diagram.text("sentence").word("and")
        assertTrue(hit.charIndex!! in and.start until and.endExclusive)
    }

    @Test
    fun aRayThatMissesEverythingReturnsNothing() {
        val p = model().settled()
        assertNull(p.pick(rayAt(Vector3f(5f, 5f, 0f))))
        // Pointing away from the diagram never hits what is behind the origin.
        val f = p.currentFrame()
        val c = f.inkCentre(p.diagram.text("sentence").word("another"))
        assertNull(f.pick(Ray.through(Vector3f(c).add(0f, 0f, 2f), Vector3f(c).add(0f, 0f, 4f))))
    }

    @Test
    fun invisibleItemsAreNotThereToPointAt() {
        val p = model() // score time 0: the text has not entered and the underline has not drawn
        val f = p.currentFrame()
        assertNull(f.pick(rayAt(f.inkCentre(p.diagram.text("sentence").word("another")))))
        assertNull(f.pick(rayAt(f.path(p.diagram.path("underline")).nodes.first().to)))
    }

    @Test
    fun strokesAreReachableWithinTheirAngularReachAndReportWhereAlongTheyWereHit() {
        val p = model().settled()
        val f = p.currentFrame()
        val path = p.diagram.path("underline")
        val line = f.path(path)
        val a = line.nodes.first().to
        val b = line.nodes.last().to
        val mid = Vector3f(a).lerp(b, .25f)
        // 3 mm below a 1.5 mm stroke, seen from 2 m: inside 0.75 mm + 12 mm of reach, and below the text ink.
        val hit = assertNotNull(f.pick(rayAt(Vector3f(mid).add(0f, -.003f, 0f)), options = DiagramPickOptions(texts = false)))
        assertEquals(DiagramHitKind.PATH, hit.kind)
        assertEquals("underline", hit.id)
        assertEquals("lean:Gnomon.odd_sum", hit.properties["ref"])
        assertEquals(.25f, assertNotNull(hit.fraction), .02f)
        assertTrue(hit.miss > 0.0 && hit.miss < .003)
        // Far outside the reach: no hit.
        assertNull(f.pick(rayAt(Vector3f(mid).add(0f, -.05f, 0f)), options = DiagramPickOptions(texts = false)))
    }

    @Test
    fun aDotSittingOnTextWinsOverTheText() {
        val p = model().settled()
        val f = p.currentFrame()
        val dot = p.diagram.dot("beside")
        val c = f.position(dot.at)
        val hit = assertNotNull(f.pick(rayAt(c)))
        assertEquals(DiagramHitKind.DOT, hit.kind)
        assertEquals("beside", hit.id)
        val all = f.pickAll(rayAt(c))
        assertEquals("beside", all.first().id)
    }

    @Test
    fun picksFollowTheDiagramsPlacementInTheWorld() {
        val p = model().settled()
        val f = p.currentFrame()
        val placement = Matrix4f().translation(1f, 1.4f, -2.5f).rotateY(.6f)
        val target = f.inkCentre(p.diagram.text("sentence").word("another"))
        val world = placement.transformPosition(Vector3f(target))
        val eye = placement.transformPosition(Vector3f(target).add(0f, 0f, 2f))
        val hit = assertNotNull(f.pick(Ray.through(eye, world), placement))
        assertEquals("another-word", hit.annotation)
        assertTrue(hit.point.distance(world) < 1e-3f, "world contact ${hit.point} vs $world")
        assertTrue(hit.diagramPoint.distance(target) < 1e-3f)
        assertEquals(2.0, hit.distance, 1e-3)
    }

    @Test
    fun hoverMovesBetweenAnnotationsButNotWithinOne() {
        val p = model().settled()
        val f = p.currentFrame()
        val run = p.diagram.text("sentence")
        var ray: Ray? = null
        val interaction = DiagramPointerInteraction({ r -> p.pick(r) }, switchSamples = 2)
        fun at(span: DiagramTextSpan, fraction: Float): Ray {
            val m = f.measure(span)
            return rayAt(f.textPoint(run, m.ink.left + m.ink.width * fraction, m.ink.centerY))
        }
        val another = run.word("another")
        interaction.update(PointerState(at(another, .2f)))
        assertEquals(PointerPhase.HOVER, interaction.phase)
        assertEquals("another-word", interaction.hover?.annotation)
        val firstChar = interaction.hover?.charIndex
        // Within the same annotation: same key, the hover refreshes immediately.
        interaction.update(PointerState(at(another, .8f)))
        assertEquals("another-word", interaction.hover?.annotation)
        assertNotEquals(firstChar, interaction.hover?.charIndex)
        // Onto a different annotation: the dwell holds the old one for one sample, then switches.
        ray = at(run.word("breath", occurrence = 1), .5f)
        interaction.update(PointerState(ray))
        assertEquals("another-word", interaction.hover?.annotation)
        interaction.update(PointerState(ray))
        assertEquals("second-breath", interaction.hover?.annotation)
        // Press and release over it is a click on that hit.
        interaction.update(PointerState(ray, 1.0))
        assertTrue(interaction.justPressed)
        assertEquals("second-breath", interaction.grabbed?.annotation)
        interaction.update(PointerState(ray, 0.0))
        assertTrue(interaction.justReleased)
        assertTrue(interaction.toString().contains("sentence"))
    }

    @Test
    fun theMirrorFitInvertsTheShadersAspectFit() {
        // A 1600 x 1200 eye in a 2000 x 1000 window: pillarboxed, 1333 window units wide.
        val centre = assertNotNull(mirrorEyePixel(1000.0, 500.0, 2000.0, 1000.0, 1600, 1200, false))
        assertEquals(0, centre.first)
        assertEquals(800.0, centre.second, 1e-9)
        assertEquals(600.0, centre.third, 1e-9)
        val visibleHalf = 1000.0 * (1600.0 / 1200.0) / 2
        val leftEdge = assertNotNull(mirrorEyePixel(1000.0 - visibleHalf + 1e-6, 0.0, 2000.0, 1000.0, 1600, 1200, false))
        assertTrue(abs(leftEdge.second) < 1e-3)
        assertEquals(0.0, leftEdge.third, 1e-9)
        assertNull(mirrorEyePixel(10.0, 500.0, 2000.0, 1000.0, 1600, 1200, false), "the letterbox is not the scene")
        // Side by side: two 800 x 1000 eyes make a 1.6:1 picture, pillarboxed in a 2:1 window
        // (fit 1.25). Window x 1500 -> q 0.8125 -> the right eye, at 0.625 of its width.
        val right = assertNotNull(mirrorEyePixel(1500.0, 500.0, 2000.0, 1000.0, 800, 1000, true))
        assertEquals(1, right.first)
        assertEquals(500.0, right.second, 1e-6)
    }
}
