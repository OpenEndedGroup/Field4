package afield.runtime.completion

import java.nio.file.Files
import kotlin.reflect.full.memberFunctions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

object StringLiteralCompletionFixtures {
    object Fruit : StringCompletions {
        override fun candidates(context: StringCompletionContext) = listOf("banana/peach", "banana/pear", "apple")
    }

    fun pick(count: Int, @CompletesWith(Fruit::class) argme: String, @CompletesPath("*.csv") table: String = "") = Unit
}

class StringLiteralCompletionTest {
    private fun at(codeWithCaret: String): Pair<String, Int> =
        codeWithCaret.replace("|", "") to codeWithCaret.indexOf('|')

    @Test
    fun `the caret inside a string literal reports what has been typed in it`() {
        val (code, pos) = at("Rhino3dm.load(\"/Users/marc/D|\")")
        assertEquals("/Users/marc/D", StringLiteralCompletion.stringPrefixAt(code, pos))
    }

    @Test
    fun `outside a string, after one closes, or in a comment there is no literal`() {
        at("val x = foo|").let { (c, p) -> assertNull(StringLiteralCompletion.stringPrefixAt(c, p)) }
        at("val x = \"/tmp\" + y|").let { (c, p) -> assertNull(StringLiteralCompletion.stringPrefixAt(c, p)) }
        at("// \"/tmp/|").let { (c, p) -> assertNull(StringLiteralCompletion.stringPrefixAt(c, p)) }
    }

    @Test
    fun `escaped quotes and raw strings are followed`() {
        at("val s = \"a\\\"b /x|").let { (c, p) -> assertEquals("a\\\"b /x", StringLiteralCompletion.stringPrefixAt(c, p)) }
        at("val s = \"\"\"/tmp/|").let { (c, p) -> assertEquals("/tmp/", StringLiteralCompletion.stringPrefixAt(c, p)) }
    }

    private fun callAt(codeWithCaret: String): StringLiteralCompletion.CallSite? {
        val (code, pos) = at(codeWithCaret)
        return StringLiteralCompletion.callSiteOf(code, StringLiteralCompletion.literalAt(code, pos)!!.quote)
    }

    @Test
    fun `the call site names the receiver, callee and the argument's name or position`() {
        val named = callAt("foo.bar(1, argme=\"banana/peac|")!!
        assertEquals(Triple("foo", "bar", "argme"), Triple(named.receiver, named.callee, named.parameterName))

        val positional = callAt("val m = afield.geometry.rhino.Rhino3dm.load(\"x\".length.toString(), listOf(\"a,b\") { it }, \"|")!!
        assertEquals("afield.geometry.rhino.Rhino3dm", positional.receiver)
        assertEquals(2, positional.index)
        assertNull(positional.parameterName)

        val nested = callAt("outer(1, inner(\"/tmp/|")!!
        assertEquals("inner" to 0, nested.callee to nested.index)

        assertNull(callAt("val s = \"/tmp/|"))
        assertNull(callAt("foo(\"a\" + \"/tmp/|"))
    }

    @Test
    fun `a parameter's annotation is found by name or by position`() {
        val functions = StringLiteralCompletionFixtures::class.memberFunctions.filter { it.name == "pick" }
        fun annotationFor(source: String) = StringLiteralCompletion.annotationIn(functions.asSequence(), callAt(source)!!)

        assertIs<CompletesWith>(annotationFor("F.pick(3, argme = \"ban|"))
        assertIs<CompletesWith>(annotationFor("F.pick(3, \"ban|"))
        assertEquals("*.csv", assertIs<CompletesPath>(annotationFor("F.pick(3, \"x\", \"da|")).mask)
        assertNull(annotationFor("F.pick(count = \"3|"))
    }

    @Test
    fun `an annotated library parameter resolves from its receiver, with no box`() {
        val code = "import afield.geometry.rhino.Rhino3dm\nRhino3dm.load(\"/tmp/"
        val call = StringLiteralCompletion.callSiteOf(code, StringLiteralCompletion.literalAt(code, code.length)!!.quote)!!
        assertEquals("*.3dm", assertIs<CompletesPath>(StringLiteralCompletion.parameterAnnotation(null, code, call)).mask)
    }

    @Test
    fun `an absolute path completes from the directory, directories first, replacing the whole literal`() {
        val dir = Files.createTempDirectory("string-literal-completion").toFile()
        try {
            dir.resolve("Documents").mkdir()
            dir.resolve("Desktop").mkdir()
            dir.resolve("data.3dm").writeText("x")
            dir.resolve("other.txt").writeText("x")
            dir.resolve(".hidden").mkdir()
            val base = dir.canonicalPath.trimEnd('/') + "/"
            val (code, pos) = at("val x = foo(\"${base}D|\")")
            val completion = StringLiteralCompletion.complete(null, code, pos)!!
            assertEquals(listOf("Desktop/", "Documents/"), completion.metadata.map { it.displayText }.take(2))
            assertEquals("${base}Desktop/", completion.metadata.first().text)
            assertEquals(code.indexOf(base), completion.boundsStart)
            assertEquals(pos, completion.boundsEnd)
            assertTrue(completion.metadata.all { it.category == StringLiteralCompletion.PATHS })

            // Rhino3dm.load's @CompletesPath("*.3dm") hides other.txt; a plain string shows it.
            val (rhino, rhinoPos) = at("import afield.geometry.rhino.Rhino3dm\nRhino3dm.load(\"$base|\")")
            val shown = StringLiteralCompletion.complete(null, rhino, rhinoPos)!!.metadata.map { it.displayText }
            assertEquals(listOf("../", "Desktop/", "Documents/", "data.3dm"), shown)
            val (plain, plainPos) = at("println(\"$base|\")")
            assertTrue("other.txt" in StringLiteralCompletion.complete(null, plain, plainPos)!!.metadata.map { it.displayText })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `ordinary strings, unknown directories and templates are left to Kotlin completion`() {
        listOf(
            "display(\"main\", \"<div>|\")",
            "load(\"no-such-directory-anywhere-for-this-test/|\")",
            "load(\"https://example.com/|\")",
            "load(\"\${home}/|\")",
            "load(\"/no/such/directory/anywhere/|\")",
            "String.format(\"%.2f|\", x)",
        ).forEach { source ->
            val (code, pos) = at(source)
            assertNull(StringLiteralCompletion.complete(null, code, pos), source)
        }
    }
}
