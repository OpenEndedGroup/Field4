package afield.graphics.scene.reading

import afield.geometry.path.FLine
import afield.graphics.text.Typefaces
import afield.graphics.text.slug.SlugPath
import afield.math.linalg.*
import kotlin.math.abs
import kotlin.test.*

class ReadingTextMorphTest {
    private val font = Typefaces.named("Monospaced", 100)

    // `ih` rewrites `sumTo f n` to `n * n` inside a larger goal: the rest of the line keeps its keys.
    private val before = "sumTo f n + (2 * n + 1) = (n + 1) * (n + 1)"
    private val after = "n * n + (2 * n + 1) = (n + 1) * (n + 1)"
    private fun keyed(text: String, vararg parts: Pair<String, Any?>): List<DiagramKeyedSpan> {
        var from = 0
        return parts.map { (s, k) ->
            val i = text.indexOf(s, from)
            require(i >= 0) { "'$s' not in '$text'" }
            from = i + s.length
            DiagramKeyedSpan(i, i + s.length, k)
        }
    }

    private fun player(seconds: Double = 2.0): ReadingDiagramPlayer {
        // CPU placement reads prepared layout and measurements; it never samples the atlas.
        val atlas = SlugPath.decode(requireNotNull(javaClass.getResource("/afield/slug/curved-hole.bin")).readBytes())
        val graph = readingDiagram {
            val p = point("p", 0, 0, pinned = true)
            textMorph(
                "ih",
                before, keyed(before, "sumTo f n" to "lhs", "(2 * n + 1)" to "gnomon", "=" to "eq", "(n + 1) * (n + 1)" to "rhs"),
                after, keyed(after, "n * n" to "lhs", "(2 * n + 1)" to "gnomon", "=" to "eq", "(n + 1) * (n + 1)" to "rhs"),
                p.anchor,
            ) { scale = .5f }
        }
        val shapes = graph.texts.map { it.text }.distinct().associateWith { text ->
            val layout = afield.graphics.text.layoutText(text, font)
            ReadingText(text, layout.advanceWidth.toFloat(), FLine(), atlas, layout)
        }
        val score = diagramScore(graph, 6.0) { morph(graph.textMorph("ih"), at = 1.0, seconds = seconds) }
        return ReadingDiagramPlayer(graph, font, shapes, score).apply {
            gravity = 0f
            wind = 0f
            playback.playing = false
        }
    }

    private fun ReadingDiagramPlayer.at(t: Double) = apply { playback.seek(t) }.currentFrame()

    private fun close(a: Vector3f, b: Vector3f, eps: Float = 1e-5f) =
        abs(a.x - b.x) < eps && abs(a.y - b.y) < eps && abs(a.z - b.z) < eps

    @Test
    fun piecesAreCutByKeyAndRoleFollowsTheMatch() {
        val m = player().diagram.textMorph("ih")
        val roles = m.pieces.groupBy({ it.role }, { it.text.text })
        // Same key, same characters: glide. Same key, different characters: a crossing pair.
        assertEquals(listOf("(2 * n + 1)", "=", "(n + 1) * (n + 1)"), roles[DiagramTextMorph.Role.GLIDE])
        assertEquals(listOf("sumTo f n"), roles[DiagramTextMorph.Role.CROSS_FROM])
        assertEquals(listOf("n * n"), roles[DiagramTextMorph.Role.CROSS_TO])
        // The unkeyed `+` on each side fades: nothing told the morph they are the same symbol.
        assertEquals(listOf("+"), roles[DiagramTextMorph.Role.FADE_OUT])
        assertEquals(listOf("+"), roles[DiagramTextMorph.Role.FADE_IN])
        assertEquals(4, m.matched)
        assertTrue(m.unmatchedKeys.isEmpty())
    }

    @Test
    fun aGlidingPieceStartsOnTheFirstLineAndEndsOnTheSecond() {
        val p = player()
        val m = p.diagram.textMorph("ih")
        val g = m.pieces.first { it.key == "gnomon" }
        val a = g.fromSpan!!
        val b = g.toSpan!!
        val start = p.at(.5)
        assertEquals(0f, m.progress(start))
        assertTrue(close(start.position(g.text.at), start.position(m.from.characters(a.start, a.endExclusive).baseline)))
        val end = p.at(3.5)
        assertEquals(1f, m.progress(end))
        assertTrue(close(end.position(g.text.at), end.position(m.to.characters(b.start, b.endExclusive).baseline)))
        // Halfway it is between the two, lifted toward the reader so crossing pieces pass in front.
        val mid = p.at(2.0)
        assertEquals(.5f, m.progress(mid), 1e-6f)
        val pm = mid.position(g.text.at)
        assertEquals(.02f, pm.z, 1e-5f)
        // It travels left (the shorter `n * n` pulls the line in), so it arcs below the straight path.
        val p0 = start.position(g.text.at)
        val p1 = end.position(g.text.at)
        assertTrue(p1.x < p0.x)
        assertEquals((p0.y + p1.y) / 2 - .025f, pm.y, 1e-5f)
    }

    @Test
    fun inAMonospacedFaceAPieceCoincidesWithItsCharactersInTheWholeLine() {
        val p = player()
        val m = p.diagram.textMorph("ih")
        val f = p.at(0.0)
        for (piece in m.pieces.filter { it.fromSpan != null && it.role != DiagramTextMorph.Role.CROSS_TO }) {
            val s = piece.fromSpan!!
            val whole = f.measure(m.from.characters(s.start, s.endExclusive))
            val inLine = f.textPoint(m.from, whole.advance.left, 0f)
            val alone = f.textPoint(piece.text, f.measure(piece.text.all).advance.left, 0f)
            assertTrue(close(inLine, alone), "piece '${piece.text.text}' at $alone, its characters in the line at $inLine")
        }
    }

    @Test
    fun opacityFollowsTheRoleAndTheLayoutRunsAreNeverSeen() {
        val p = player()
        val m = p.diagram.textMorph("ih")
        fun opacity(role: DiagramTextMorph.Role, t: Double) =
            p.playback.score.pose(m.pieces.first { it.role == role }.text, t).opacity
        assertEquals(1f, opacity(DiagramTextMorph.Role.FADE_OUT, .5))
        assertEquals(0f, opacity(DiagramTextMorph.Role.FADE_IN, .5))
        assertEquals(0f, opacity(DiagramTextMorph.Role.FADE_OUT, 3.5))
        assertEquals(1f, opacity(DiagramTextMorph.Role.FADE_IN, 3.5))
        assertEquals(1f, opacity(DiagramTextMorph.Role.GLIDE, 2.0))
        assertEquals(.5f, opacity(DiagramTextMorph.Role.CROSS_TO, 2.0), 1e-6f)
        assertEquals(0f, m.from.color.w)
        assertEquals(0f, m.to.color.w)
        assertFalse(m.from in m.group.items || m.to in m.group.items)
    }

    @Test
    fun aMorphWithoutACueShowsItsFirstLine() {
        val atlasless = readingDiagram {
            val p = point("p", 0, 0, pinned = true)
            textMorph("m", "a + b", listOf(DiagramKeyedSpan(0, 1, "a"), DiagramKeyedSpan(4, 5, "b")),
                "b + a", listOf(DiagramKeyedSpan(0, 1, "b"), DiagramKeyedSpan(4, 5, "a")), p.anchor)
        }
        val score = diagramScore(atlasless, 4.0) {}
        val m = atlasless.textMorph("m")
        for (piece in m.pieces) {
            val expected = if (piece.role == DiagramTextMorph.Role.FADE_IN || piece.role == DiagramTextMorph.Role.CROSS_TO) 0f else 1f
            assertEquals(expected, score.pose(piece.text, 2.0).opacity, piece.text.text)
        }
        assertEquals(0f, score.pose(m.from, 2.0).morph)
    }

    @Test
    fun overlappingSpansAndRepeatedKeysAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            readingDiagram {
                val p = point("p", 0, 0, pinned = true)
                textMorph("m", "abc", listOf(DiagramKeyedSpan(0, 2, 1), DiagramKeyedSpan(1, 3, 2)), "abc", emptyList(), p.anchor)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            readingDiagram {
                val p = point("p", 0, 0, pinned = true)
                textMorph("m", "a a", listOf(DiagramKeyedSpan(0, 1, "x"), DiagramKeyedSpan(2, 3, "x")), "a", emptyList(), p.anchor)
            }
        }
    }
}
