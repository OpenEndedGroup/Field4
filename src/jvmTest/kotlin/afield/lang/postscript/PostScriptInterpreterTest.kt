package afield.lang.postscript

import afield.graphics.render.filled
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [PostScriptInterpreter] against the examples in John Earnest's "Let's Write a PostScript
 * Interpreter" (https://beyondloom.com/blog/postscript.html), each giving the stack or the `==`
 * output the post says it gives, and against the graphics operators the post leaves out.
 */
class PostScriptInterpreterTest {

    private fun run(src: String): Pair<List<String>, String> {
        val ps = PostScriptInterpreter()
        val out = StringBuilder()
        ps.onOutput = { out.append(it) }
        ps.eval(src)
        return ps.stackStrings() to out.toString()
    }

    private fun stack(src: String) = run(src).first
    private fun printed(src: String) = run(src).second.trim()

    @Test
    fun tokenizerSplitsOnSelfDelimitingCharacters() {
        val ps = PostScriptInterpreter()
        assertEquals(listOf("/double", "{", "2", "mul", "}", "def", "3", "double"),
            ps.tokenize("% This is a line comment\n/double {2 mul} def\n\n3 double").map { it.text })
        assertEquals(listOf("[", "[", "1", "2", "]", "[", "3", "4", "]", "]"), ps.tokenize("[[1 2][3 4]]").map { it.text })
    }

    @Test
    fun doubleDefinesAndRuns() = assertEquals(listOf("6"), stack("/double {2 mul} def\n3 double"))

    @Test
    fun arraysAreBuiltBetweenAMarkAndTheBracket() {
        assertEquals(listOf("[]", "[1 2]", "[[1 2] [3 4]]", "[5]"), stack("[] [1 2] [[1 2][3 4]] [2 3 add]"))
    }

    @Test
    fun replExamples() {
        assertEquals("5", printed("2 3 add =="))
        assertEquals("[110 220 330]", printed("[[11 22 33] {10 mul} forall] =="))
        assertEquals(listOf("20", "40", "60"), stack("[10 20 30] {2 mul} forall"))
    }

    @Test
    fun theDefinedHelpers() {
        val lib = "/if {{} ifelse} def /neg {-1 mul} def /abs {dup 0 lt {neg} if} def /min {2 copy gt {exch} if pop} def\n"
        assertEquals(listOf("5", "3"), stack(lib + "-5 abs 3 abs"))
        assertEquals(listOf("2"), stack(lib + "7 2 min"))
    }

    @Test
    fun fibonacciTwoWays() {
        val want = "[0 1 1 2 3 5 8 13 21 34 55]"
        assertEquals(want, printed("/fib {dup 2 ge {1 sub dup 1 sub fib exch fib add} {} ifelse} def\n[0 1 10 {fib} for] =="))
        assertEquals(want, printed("[0 1 9 {2 copy add} repeat] =="))
    }

    @Test
    fun reverseAndConcat() {
        assertEquals("[33 22 11]", printed("/reverse {[exch dup length 1 sub -1 0 {1 index exch get exch} for pop]} def\n[11 22 33] reverse =="))
        assertEquals("[11 22 33 44 55]", printed("/concat {[3 1 roll exch {exch} forall {} forall]} def\n[11 22 33] [44 55] concat =="))
    }

    @Test
    fun dictionaryScopeAndLocal() {
        // the post's hypotenuse: 3 4 under a two-entry dictionary
        assertEquals(listOf("5.0"), stack("3 4\n2 dict begin\n /b exch def\n /a exch def\n a a mul\n b b mul\n add sqrt\nend"))
        assertEquals(listOf("50"), stack("/local {1 dict begin 3 1 roll exch def exec end} def\n25 /a {a a add} local"))
    }

    @Test
    fun loopAndExit() {
        assertEquals(listOf("10"), stack("0 { 1 add dup 10 ge {exit} if } loop"))
        assertEquals(listOf("0", "1", "2"), stack("0 1 10 { dup 2 gt {pop exit} if } for"))
    }

    @Test
    fun errorsCarryTheirLine() {
        val e = assertFailsWith<PostScriptInterpreter.PsError> { PostScriptInterpreter().eval("1 2 add\n3 nosuchword\n") }
        assertEquals(2, e.line)
        assertTrue(e.message!!.contains("nosuchword"))
        assertEquals(1, assertFailsWith<PostScriptInterpreter.PsError> { PostScriptInterpreter().eval("pop") }.line)
        assertTrue(assertFailsWith<PostScriptInterpreter.PsError> { PostScriptInterpreter().eval("{ } loop") }.message!!.contains("steps"))
    }

    @Test
    fun strokeAndFillMakeFLinesInDeviceSpace() {
        val ps = PostScriptInterpreter()
        ps.eval("newpath 10 20 moveto 30 0 rlineto stroke 100 100 translate 90 rotate 0 0 moveto 10 0 lineto 0 10 lineto closepath fill")
        assertEquals(2, ps.lines.size)
        val stroke = ps.lines[0].nodes.map { it.storage[0].x.toDouble() to it.storage[0].y.toDouble() }
        assertEquals(listOf(10.0 to -20.0, 40.0 to -20.0), stroke)
        // rotated 90°: user (10, 0) lands at device (100, 110), flipped to y = -110
        val tri = ps.lines[1].nodes[1].storage[0]
        assertEquals(100.0, tri.x.toDouble(), 1e-4)
        assertEquals(-110.0, tri.y.toDouble(), 1e-4)
        assertTrue(ps.lines[1].filled)
    }

    @Test
    fun gsaveGrestoreAndCurrentpoint() {
        val ps = PostScriptInterpreter()
        ps.eval("gsave 50 50 translate 0 0 moveto currentpoint grestore 0 0 moveto currentpoint")
        assertEquals(listOf("0.0", "0.0", "0.0", "0.0"), ps.stackStrings())
        ps.reset()
        ps.eval("newpath 0 0 50 0 360 arc stroke")
        assertEquals(5, ps.lines[0].nodes.size) // moveto + four quarter-turn cubics
    }

    @Test
    fun recursiveTreeAndRosetteDraw() {
        val ps = PostScriptInterpreter()
        ps.eval(TREE)
        assertEquals(255, ps.lines.size) // 2^8 - 1 branches
        ps.reset()
        ps.eval(ROSETTE)
        assertEquals(36, ps.lines.size)
    }

    /**
     * The tree's bounding box against Ghostscript's `bbox` device on the same program. Ghostscript's
     * box includes half the line width and rounds caps, so they agree to within a point or two. Skipped
     * (not passed) when `gs` is not installed.
     */
    @Test
    fun treeBoundingBoxMatchesGhostscript() {
        val gs = listOf("/opt/homebrew/bin/gs", "/usr/local/bin/gs", "/usr/bin/gs").firstOrNull { java.io.File(it).canExecute() }
        org.junit.Assume.assumeTrue("Ghostscript is not installed", gs != null)
        val file = kotlin.io.path.createTempFile("tree", ".ps").toFile()
        try {
            file.writeText(TREE + "\nshowpage\n")
            val proc = ProcessBuilder(gs, "-q", "-dSAFER", "-dBATCH", "-dNOPAUSE", "-sDEVICE=bbox", file.path).redirectErrorStream(true).start()
            val text = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            val hi = Regex("%%HiResBoundingBox: ([\\d.]+) ([\\d.]+) ([\\d.]+) ([\\d.]+)").find(text)!!.groupValues.drop(1).map { it.toDouble() }
            val ps = PostScriptInterpreter()
            ps.eval(TREE)
            val pts = ps.lines.flatMap { l -> l.nodes.map { it.storage[0].x.toDouble() to -it.storage[0].y.toDouble() } }
            val mine = listOf(pts.minOf { it.first }, pts.minOf { it.second }, pts.maxOf { it.first }, pts.maxOf { it.second })
            mine.zip(hi).forEach { (a, b) -> assertEquals(b, a, 2.0, "ours $mine, Ghostscript's $hi") }
        } finally {
            file.delete()
        }
    }

    companion object {
        val TREE = """
            /branch { % len depth branch
              dup 0 gt {
                exch dup 0 0 moveto 0 exch lineto stroke
                dup 0 exch translate
                0.7 mul exch 1 sub
                gsave 25 rotate 2 copy branch grestore
                gsave -25 rotate branch grestore
              } { pop pop } ifelse
            } def
            300 50 translate
            100 8 branch
        """.trimIndent()

        val ROSETTE = """
            200 200 translate
            36 {
              newpath 0 0 moveto 120 0 60 40 0 60 curveto stroke
              10 rotate
            } repeat
        """.trimIndent()
    }
}
