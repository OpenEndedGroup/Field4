package afield.ml.tests

import afield.Completion
import afield.CompletionRequest
import afield.ml.FieldMLCompletionEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FieldMLCompletionEngineTest {
    @Test
    fun completesPreludeAndSnippetsAtEmptyCursor() {
        val completion = complete("")
        assertHas(completion, "fline")
        assertHas(completion, "let")
        assertHas(completion, "hostTap")
    }

    @Test
    fun completesLocalBindingsWithInferredTypes() {
        val completion = complete(
            """
            let samples = [1, 2, 3]
            sam
            """,
        )

        val item = assertHas(completion, "samples")
        assertEquals("[Int]", item.tail)
        assertEquals("local", item.category)
    }

    @Test
    fun ranksPipeFriendlyFLineFunctionsEarly() {
        val completion = complete(
            """
            fline
              |> 
            """,
        )

        val labels = completion.metadata.map { it.displayText }
        assertTrue(labels.indexOf("moveTo") in 0..12, labels.take(20).joinToString())
        assertTrue(labels.indexOf("lineTo") in 0..12, labels.take(20).joinToString())
        assertTrue(labels.indexOf("color") in 0..12, labels.take(20).joinToString())
    }

    @Test
    fun completesRecordFieldsAfterDot() {
        val completion = complete(
            """
            let model = { label = "run", active = true, count = 4 }
            model.
            """,
        )

        assertHas(completion, "label")
        assertHas(completion, "active")
        assertHas(completion, "count")
    }

    @Test
    fun completesConstructorsFromLocalTypeDeclarations() {
        val completion = complete(
            """
            type Option a = None | Some a

            So
            """,
        )

        val item = assertHas(completion, "Some")
        assertEquals("constructor", item.tail)
    }

    @Test
    fun completesHostTapTargetsFromKotlinSource() {
        val completion = complete("""hostTap "afield.graphics.li""")

        assertTrue(
            completion.metadata.any { it.displayText == "afield.graphics.lines" },
            completion.metadata.take(20).joinToString { it.displayText },
        )
    }

    @Test
    fun completesHostTapNamedArguments() {
        val completion = complete("""hostTap "afield.graphics.lines" { ch""")

        val item = assertHas(completion, "channel")
        assertEquals("channel = ", item.text)
        assertEquals("values", item.category)
    }

    private fun complete(source: String): Completion {
        val code = source.trimIndent()
        return assertNotNull(
            FieldMLCompletionEngine.complete(
                CompletionRequest(
                    uid = "test",
                    line = 1,
                    allCode = code,
                    column = 1,
                    position = code.length,
                    property = "code",
                ),
            ),
            "Expected completions for:\n$code",
        )
    }

    private fun assertHas(completion: Completion, displayText: String) =
        assertNotNull(
            completion.metadata.firstOrNull { it.displayText == displayText },
            "Missing '$displayText' in ${completion.metadata.map { it.displayText }}",
        )
}
