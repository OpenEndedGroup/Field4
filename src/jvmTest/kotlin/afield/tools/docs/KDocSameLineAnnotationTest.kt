package afield.tools.docs

import afield.tools.docs.index.KDocExtractor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins that a KDoc attaches to a declaration that shares its line with an annotation.
 *
 * Until 2026-11-09 the extractor skipped every line beginning with `@`, so `@Volatile var x` and
 * `@Test fun t()` were stepped over as if they were bare annotations and their KDoc slid onto the next
 * symbol. `checkKDocAttachment` reported each one, and a fixer pass rewrote one branch a day to put the
 * annotation on its own line. Now the extractor reads past the annotations on such a line and attaches.
 */
class KDocSameLineAnnotationTest {

    private fun attachedNames(source: String): List<String?> =
        KDocExtractor.extractKDocs(source.trimIndent()).map { it.declarationName }

    /** The shapes that used to bounce, each documented and followed by a plain declaration it must not slide onto. */
    @Test
    fun sameLineAnnotationsAttachToTheirDeclaration() {
        val source = """
            /** Seconds before giving up. */
            @Volatile var timeoutSeconds = 150

            /** A test with its annotation inline. */
            @Test fun inlineTest() = Unit

            /** Use-site target and quoted argument. */
            @get:JvmName("sizeOf") val size: Int get() = 0

            /** Nested and quoted arguments. */
            @Deprecated("Use (other)", ReplaceWith("other()")) fun old() = Unit

            /** Two annotations. */
            @JvmStatic @Suppress("UNUSED") fun twice() = Unit

            val next = 1
        """
        assertEquals(listOf("timeoutSeconds", "inlineTest", "size", "old", "twice"), attachedNames(source))
    }

    /** An annotation alone on its line is still stepped over, including a multi-line argument list. */
    @Test
    fun annotationOnItsOwnLineIsStillTransparent() {
        val source = """
            /** Attached below the annotations. */
            @Suppress("x")
            @Target(
                AnnotationTarget.FUNCTION,
            )
            fun below() = Unit
        """
        assertEquals(listOf("below"), attachedNames(source))
        assertNull(KDocExtractor.parseDeclaration("@Target("))
    }
}
