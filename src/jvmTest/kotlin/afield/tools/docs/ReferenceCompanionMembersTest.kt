package afield.tools.docs

import afield.runtime.completion.KotlinSourceIndex
import kotlin.test.Test
import kotlin.test.assertEquals

/** Factory entry points need reference rows without exposing private companion implementation. */
class ReferenceCompanionMembersTest {
    private fun members(source: String): List<String> {
        val text = source.trimIndent()
        return StdlibReferenceGenerator.referenceMembers(
            KotlinSourceIndex.parseTopLevelDefinitions(text), text.lines(), "Example",
        ).map { it.name }
    }

    @Test
    fun `anonymous companion factories are included and locals stay hidden`() {
        assertEquals(
            listOf("Example.render", "Example.Companion.train"),
            members("""
                class Example {
                    fun render() = Unit
                    companion object {
                        fun train() {
                            val local = 1
                        }
                        private fun implementation() = Unit
                    }
                }
            """),
        )
    }

    @Test
    fun `named public companions contribute factories but private companions do not`() {
        assertEquals(
            listOf("Example.Factory.build"),
            members("""
                class Example {
                    companion object Factory {
                        fun build() = Unit
                    }
                }
            """),
        )
        assertEquals(
            emptyList(),
            members("""
                class Example {
                    private companion object {
                        fun implementation() = Unit
                    }
                }
            """),
        )
    }
}
