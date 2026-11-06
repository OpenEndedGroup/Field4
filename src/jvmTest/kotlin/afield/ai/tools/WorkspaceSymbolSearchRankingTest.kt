package afield.ai.tools

import afield.runtime.completion.KotlinSourceIndex
import afield.runtime.completion.KotlinSourceIndexDb
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkspaceSymbolSearchRankingTest {
    private fun body(source: String, line: Int) = KotlinSourceIndex.functionBodyText(source.trimIndent().lines(), line)

    /** 2026-11-06: `quat`'s expression body (in `assimpBrowsing.kt`) was reported as `fmtBytes`'s block. */
    @Test
    fun anExpressionBodyIsNotTheNextFunctionsBlock() {
        val source = """
            private fun quat(q: afield.math.linalg.Quaternionf): String =
                "(%.3f, %.3f, %.3f, %.3f)".format(q.x, q.y, q.z, q.w)

            private fun fmtBytes(n: Long): String {
                if (n < 1024) return "${'$'}n B"
                return "big"
            }
        """
        val quat = body(source, 1).orEmpty()
        assertContains(quat, "format(q.x, q.y, q.z, q.w)")
        assertFalse("1024" in quat, quat)
        assertEquals("{\n    if (n < 1024) return \"\$n B\"\n    return \"big\"\n}", body(source, 4))
    }

    @Test
    fun aLambdaDefaultInTheParametersIsNotTheBody() {
        val source = """
            fun later(delay: Int = 1, onDone: () -> Unit = {}, map: (Int) -> Int = { it * 2 }) {
                onDone()
            }
        """
        assertEquals("{\n    onDone()\n}", body(source, 1))
    }

    @Test
    fun aBraceInAStringOrCommentDoesNotOpenOrCloseTheBody() {
        val source = """
            fun label(name: String = "{") = "}" + name // {
            fun template(x: Int): String {
                val s = "}${'$'}{x + 1}{"
                return s /* } */
            }
            fun after() = 2
        """
        assertEquals("= \"}\" + name // {", body(source, 1))
        val t = body(source, 2).orEmpty()
        assertTrue(t.endsWith("return s /* } */\n}"), t)
    }

    @Test
    fun aMultiLineExpressionBodyEndsWhereItStopsContinuing() {
        val source = """
            fun total(xs: List<Int>) = xs
                .filter { it > 0 }
                .sum()
            fun next() = 1
        """
        assertEquals("= xs\n    .filter { it > 0 }\n    .sum()", body(source, 1))
        assertEquals("= 1", body(source, 4))
    }

    @Test
    fun anAbstractFunctionHasNoBody() {
        val source = """
            interface Shape {
                fun area(): Double
                fun name(): String = "shape"
            }
        """
        assertNull(body(source, 2))
        assertEquals("= \"shape\"", body(source, 3))
    }

    @Test
    fun aMemberOfAPrivateClassIsNotPublic() {
        val parsed = KotlinSourceIndex.parseTopLevelDefinitions("""
            private class Hidden {
                fun visible() = 1
            }
            class Open {
                internal fun inside() = 2
                fun outside() = 3
            }
        """.trimIndent())
        val vis = KotlinSourceIndex.withEffectiveVisibility(parsed).associate { (d, v) -> d.name to v }
        assertEquals(mapOf("Hidden" to "private", "Hidden.visible" to "private", "Open" to null, "Open.inside" to "internal", "Open.outside" to null), vis)
    }

    private fun indexOf(files: Map<String, String>): Pair<Path, KotlinSourceIndexDb> {
        val root = Files.createTempDirectory("symbol-ranking")
        files.forEach { (rel, text) ->
            val p = root.resolve(rel)
            Files.createDirectories(p.parent)
            Files.writeString(p, text.trimIndent())
        }
        val defs = KotlinSourceIndex.scanDefinitions(listOf(root.resolve("src/jvmMain/kotlin")), root)
        return root to KotlinSourceIndexDb(root = root.toString(), apiKeyHash = null, updatedAtEpochMs = Long.MAX_VALUE,
            files = emptyList(), definitions = defs)
    }

    /** 2026-11-06: `quat` returned the private assimp formatter before the public `quat(angle, axis)`. */
    @Test
    fun exactMatchesAreRankedWithPublicApiFirst() {
        val (root, index) = indexOf(mapOf(
            "src/jvmMain/kotlin/afield/app/server/assimp.kt" to "private fun quat(q: Any): String =\n    q.toString()\n",
            "src/jvmMain/kotlin/afield/math/linalg/linalg.kt" to "/** A rotation of [angle] radians about [axis]. */\nfun quat(angle: Number = 0.0, axis: Any? = null) = Any()\n",
        ))
        try {
            val ranked = rankWorkspaceSymbols(index, "quat", 5).definitions
            assertEquals("src/jvmMain/kotlin/afield/math/linalg/linalg.kt", ranked.first().filePath)
            assertEquals("assimp.kt", rankWorkspaceSymbols(index, "quat", 5, SymbolRankingOptions.LEGACY).definitions.first().filePath.substringAfterLast('/'))
        } finally { root.toFile().deleteRecursively() }
    }

    /** 2026-11-06: `Vector2f.rotate` answered with private top-level `rotate` helpers, which `v.rotate` cannot call. */
    @Test
    fun aReceiverQuerySaysWhenTheTypeHasNoSuchMember() {
        val (root, index) = indexOf(mapOf(
            "src/jvmMain/kotlin/afield/math/linalg/Vectors.kt" to """
                interface Vector2fc { fun x(): Float }
                open class Vector2f() : Vector2fc { override fun x() = 0f
                    fun length() = 0f
                }
                typealias Vec2 = Vector2f
                /** `p * q` for a planar point: rotates it by the quaternion. */
                operator fun Vector2fc.times(q: Quat): Vector2fc = this
                class Quat
            """,
            "src/jvmMain/kotlin/afield/rect/RectSL.kt" to "private fun rotate(p: Any, a: Double) = p\n",
            "src/jvmMain/kotlin/afield/geometry/Shapes.kt" to "class Shape { fun rotate(a: Double) = this }\n",
        ))
        try {
            val ranking = rankWorkspaceSymbols(index, "Vector2f.rotate", 5)
            assertEquals("Vector2fc.times", ranking.definitions.first().name, ranking.definitions.map { it.name }.toString())
            assertContains(ranking.note, "No `rotate` member or extension on Vector2f")
            assertContains(ranking.note, "Vector2fc")
            // The alias reaches the same family, though its name shares nothing with `Vector2fc.times`.
            val viaAlias = rankWorkspaceSymbols(index, "Vec2.rotate", 5)
            assertTrue("Vector2fc.times" in viaAlias.definitions.take(3).map { it.name }, viaAlias.definitions.map { it.name }.toString())
            assertContains(viaAlias.note, "No `rotate` member or extension on Vec2")
            // A member that exists gets no such note.
            val length = rankWorkspaceSymbols(index, "Vec2.length", 5)
            assertEquals("Vector2f.length", length.definitions.first().name)
            assertEquals("", length.note)
        } finally { root.toFile().deleteRecursively() }
    }
}
