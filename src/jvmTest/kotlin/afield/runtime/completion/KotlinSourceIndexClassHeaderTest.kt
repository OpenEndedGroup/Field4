package afield.runtime.completion

import afield.ai.knowledge.main

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which class [KotlinSourceIndex] attributes a declaration to, once the lexer has the brace depth
 * right.
 *
 * [KotlinSourceIndexBraceTrackingTest] covers the lexical half: strings, comments and backticks that
 * made the depth drift. This covers the other half. When the index sees `class Name`, it holds the
 * name *pending* until the brace that opens the class body, so that a header spanning several lines
 * still gives its members an owner. If the pending name outlives a header that ended without a body,
 * the next brace in the file takes it, whatever that brace opens, and the locals inside are reported
 * as members of the wrong class. If the name is dropped too soon, the members of a real multi-line
 * header lose their owner and are published as top-level names.
 *
 * As in the brace-tracking test, each case asserts the *whole* set of symbols emitted, so a parser
 * that indexed nothing could not pass.
 */
class KotlinSourceIndexClassHeaderTest {

    /** Every declaration the index emits, as `kind name`, in source order. */
    private fun indexed(source: String): List<String> =
        KotlinSourceIndex.parseTopLevelDefinitions(source.trimIndent()).map { "${it.kind} ${it.name}" }

    @Test
    fun `a bodyless class does not adopt the next brace in the file`() {
        // Reduced from `four/device/CommandBuffer.kt`, where a bodyless `data class
        // SecondaryInheritance(…)` sits above an `init {}` block. The pending class took the `init`
        // brace for its body, and `vkDevice` was indexed as `CommandBuffer.SecondaryInheritance.vkDevice`.
        // `init` is not a declaration the recogniser knows, so the "a new declaration appeared"
        // reset never fired. This failed on main until `595bd42eb` taught the index that a line
        // opening `init {` ends a pending header.
        val source = """
            class CommandBuffer(pool: Pool) {
                data class SecondaryInheritance(
                    val renderPass: Long = 0,
                    val subPass: Int = 0
                )

                init {
                    val vkDevice = pool.device.vkDevice
                    use(vkDevice)
                }
            }
        """
        assertEquals(
            listOf(
                "class CommandBuffer",
                "class CommandBuffer.SecondaryInheritance",
                "val CommandBuffer.SecondaryInheritance.renderPass",
                "val CommandBuffer.SecondaryInheritance.subPass",
            ),
            indexed(source),
            "a local in an init block is not a member of the class above it",
        )
    }

    @Test
    fun `a class header may still span lines before its body opens`() {
        // The other side of that expiry: dropping the pending class too early would strip the owner
        // from every member below and publish them as top-level names.
        val source = """
            data class Point(
                val x: Double,
                val y: Double,
            ) : Comparable<Point>,
                Serializable
            {
                fun length(): Double = 0.0
            }
        """
        assertEquals(
            listOf("class Point", "val Point.x", "val Point.y", "fun Point.length"),
            indexed(source),
        )
    }

    @Test
    fun `a bodyless class at file scope leaves the next declaration top level`() {
        val source = """
            class Marker

            fun free() {
                val local = 1
            }
        """
        assertEquals(listOf("class Marker", "fun free"), indexed(source))
    }

    @Test
    fun `local classes, objects and functions inside a function body are never reported`() {
        // The brace-tracking test checks that local `val`s stay out; a local `class` or `object`
        // also starts a pending class, and must not make its own body look like a class body.
        val source = """
            fun outer() {
                val local = 1
                var mutable = 2
                fun nested() = 3
                class Local
                object Deep {
                    val deeper = 4
                }
            }
        """
        assertEquals(listOf("fun outer"), indexed(source))
    }
}
