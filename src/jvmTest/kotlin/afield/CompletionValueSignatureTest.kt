package afield

import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A documented `val` used to arrive in the completion doc panel as `ROPE: String` — its type, and
 * nothing else. For a constant the value is usually the thing the panel was opened for: a program
 * string, a magic number, a default. These pin which declarations give up their initializer and
 * which are left as a bare signature, because the panel is a signature and not a code dump.
 */
class CompletionValueSignatureTest {

    private fun initializerOf(source: String, line: Int = 1): String? {
        val file = Files.createTempFile("declaration-", ".kt")
        return try {
            file.writeText(source)
            KotlinCompletionSupport.readDeclarationInitializer(file, line)
        } finally {
            Files.deleteIfExists(file)
        }
    }

    /** The declaration from the report: a stack-language program held in a `val`. */
    @Test
    fun `a string constant gives up its value`() {
        assertEquals(
            "\"input x =e <e <e * <e * 160 *\"",
            initializerOf("""    val ROPE = "input x =e <e <e * <e * 160 *"""")
        )
    }

    @Test
    fun `an inner equals does not split the initializer early`() {
        // `=e` is a mark in that language, and `x =e` has a space on one side only. A naive split on
        // the first `=` would have cut the value in half.
        val value = initializerOf("""val M = "a =e b"""")
        assertEquals("\"a =e b\"", value)
    }

    @Test
    fun `modifiers, annotations and a declared type are all fine`() {
        assertEquals("160", initializerOf("    private const val MAX = 160"))
        assertEquals("7", initializerOf("@JvmField val tagged = 7"))
        assertEquals("mapOf()", initializerOf("val m: Map<String, Int> = mapOf()"))
        assertEquals("0", initializerOf("var count = 0"))
    }

    @Test
    fun `the right line of the file is read`() {
        val source = "val first = 1\nval second = 2\nval third = 3\n"
        assertEquals("2", initializerOf(source, line = 2))
        assertEquals("3", initializerOf(source, line = 3))
        assertNull(initializerOf(source, line = 9), "past the end of the file")
    }

    /** A body is not a signature: these belong behind the panel's "source" footer, not in the fence. */
    @Test
    fun `a value that carries on below this line is left alone`() {
        assertNull(initializerOf("val rows = listOf("), "an unclosed bracket continues below")
        assertNull(initializerOf("val text = buildString {"), "a block body continues below")
        assertNull(initializerOf("val long = \"${"x".repeat(300)}\""), "too long to be a signature")
    }

    @Test
    fun `declarations with no value to show are left alone`() {
        assertNull(initializerOf("val plain: String"), "no initializer")
        assertNull(initializerOf("val x get() = 3"), "a computed getter is not a stored value")
        assertNull(initializerOf("fun thing() = 4"), "not a property")
        assertNull(initializerOf("val (a, b) = pair"), "a destructuring declaration has no one name")
    }

    // ---- splicing it into the signature fence ---------------------------------------------------

    private val fence = "```kotlin\nROPE: String\n```"

    @Test
    fun `the value is spliced into the leading fence, keeping the type`() {
        assertEquals(
            "```kotlin\nROPE: String = \"input x\"\n```",
            KotlinCompletionSupport.withDeclarationInitializer(fence, "\"input x\"")
        )
    }

    @Test
    fun `documentation below the fence survives`() {
        val spliced = KotlinCompletionSupport.withDeclarationInitializer("$fence\n\nA rope.", "\"v\"")
        assertEquals("```kotlin\nROPE: String = \"v\"\n```\n\nA rope.", spliced)
    }

    @Test
    fun `a fence with nothing to add is left untouched`() {
        val already = "```kotlin\nROPE: String = \"already\"\n```"
        assertEquals(
            already,
            KotlinCompletionSupport.withDeclarationInitializer(already, "\"v\""),
            "a signature that already carries a value keeps it"
        )
        val prose = "Just documentation, no fence."
        assertEquals(prose, KotlinCompletionSupport.withDeclarationInitializer(prose, "\"v\""))
        val multiline = "```kotlin\nfun f(\n  a: Int\n)\n```"
        assertEquals(
            multiline,
            KotlinCompletionSupport.withDeclarationInitializer(multiline, "\"v\""),
            "a signature spanning lines is a body, not a declaration to splice"
        )
    }

    /** The guard on [KotlinCompletionSupport.declarationInitializer]: only files under the repo. */
    @Test
    fun `a path outside the working directory is refused`() {
        assertNull(KotlinCompletionSupport.declarationInitializer("../../../etc/passwd", 1))
        assertNull(KotlinCompletionSupport.declarationInitializer("", 1))
        assertNull(KotlinCompletionSupport.declarationInitializer("src/does/not/exist.kt", 1))
    }
}
