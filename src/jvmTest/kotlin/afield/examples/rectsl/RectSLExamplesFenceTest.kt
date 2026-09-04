package afield.examples.rectsl

import kotlin.test.Test
import kotlin.test.assertEquals

class RectSLExamplesFenceTest {
    @Test
    fun `the docs compiler owns RectSL fences and not plain text`() {
        val page = """
            ```diffstatic {data-lang=text}
            observed output, not a program
            ```

            ```diffstatic {data-lang=rectsl}
            #rectsl
            ->wall 4 3 abstract
            ```
        """.trimIndent()

        val blocks = RectSLExamples.rectslCodeBlocks(page)

        assertEquals(1, blocks.size)
        assertEquals("#rectsl\n->wall 4 3 abstract\n", blocks.single().second)
    }
}
