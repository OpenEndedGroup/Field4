package afield.docs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The shapes [KotlinSourcePartition] gets wrong when nobody is looking.
 *
 * [SourcePartitionGuard] is the real check — it runs the invariants over all 1,116 files in `src/` —
 * but a corpus guard tells you *that* something broke, not which shape did it. Every case here is a
 * shape that was genuinely mis-cut during the first run of that guard over the repository, kept as a
 * named fixture so the next change to the scanner says which one it broke.
 *
 * Line numbers are looked up by marker text rather than written as constants, so editing a fixture
 * cannot silently move what a test is asserting about.
 */
class KotlinSourcePartitionTest {

    @Test
    fun `an ordinary file tiles, reconstructs and balances`() {
        val source = """
            package afield.example

            import java.io.File

            /** A little class. */
            class Little(val name: String) {

                /** A member. */
                fun greet(): String = "hello ${'$'}name"

                val upper: String
                    get() = name.uppercase()
            }

            /** A top-level function. */
            fun helper(file: File): Int {
                return file.length().toInt()
            }
        """.trimIndent()

        val partition = KotlinSourcePartition.partition(source)
        assertEquals(emptyList(), KotlinSourcePartition.verify(partition), "invariants")
        assertEquals(source.split("\n"), KotlinSourcePartition.reconstruct(partition), "reconstruction")

        val kinds = partition.spans.map { it.kind }
        assertTrue(KotlinSourcePartition.Kind.HEADER in kinds, "package and import should be a header run")
        assertTrue(KotlinSourcePartition.Kind.DOC in kinds, "a KDoc block should be its own span")
    }

    @Test
    fun `a four quote raw string does not unbalance the enclosing declaration`() {
        // `Regex(""""([^"]+)"""")` — a raw string whose content is itself quoted. Closing it at the
        // first three quotes ate the rest of the line, and the declaration's brackets stopped
        // balancing, in 32 files across the repository. Built by concatenation because a fixture
        // containing a raw-string delimiter cannot itself live in a raw string.
        val q = "\""
        val source = listOf(
            "object Holder {",
            "    fun quoted(js: String): List<String> {",
            "        val words = Regex(${q}${q + q + q}([^${q}]+)${q + q + q}${q}).findAll(js).map { it.value }.toList()",
            "        return words",
            "    }",
            "} // end of Holder",
        ).joinToString("\n")

        val partition = KotlinSourcePartition.partition(source)
        assertEquals(emptyList(), KotlinSourcePartition.verify(partition), "invariants")
        assertEquals(
            lineOf(source, "end of Holder"),
            declaration(partition, "Holder").endLine,
            "the object should run to its own closing brace",
        )
    }

    @Test
    fun `a backticked name containing an apostrophe keeps its body`() {
        // The apostrophe read as the start of a character literal, which blanked the rest of the line
        // including the body's opening brace. The tiling never noticed — the body's braces were blanked
        // as a pair, so the brackets stayed balanced — and the whole body became unattributed.
        val source = """
            class Suite {
                fun `a builder's groups become the names a pick reports`() {
                    val builder = Builder()
                    builder.group("tall box")
                    assertEquals(2, builder.count)
                }
            }
        """.trimIndent()

        val partition = KotlinSourcePartition.partition(source)
        assertEquals(emptyList(), KotlinSourcePartition.verify(partition), "invariants")
        val test = partition.allSpans().single {
            it.kind == KotlinSourcePartition.Kind.DECLARATION && it.declKind == "fun"
        }
        assertEquals(lineOf(source, "fun `a builder"), test.declarationLine, "declaration line")
        assertEquals(lineOf(source, "assertEquals(2") + 1, test.endLine, "the body belongs to the function")
    }

    @Test
    fun `an expression body runs to the end of its chain`() {
        // The `{` of `.filter { … }` is not the declaration's body — an `=` came first. Treating it as
        // the body ended the declaration on its second line and left the rest of the chain behind.
        val source = """
            class Engine {
                private fun keywordItems(prefix: String): List<String> =
                    keywords
                        .filter { it.startsWith(prefix) }
                        .map {
                            wrap(
                                rank = 40,
                            )
                        }

                private fun next(): Int = 1
            }
        """.trimIndent()

        val partition = KotlinSourcePartition.partition(source)
        assertEquals(emptyList(), KotlinSourcePartition.verify(partition), "invariants")
        assertEquals(
            lineOf(source, "        }"),
            declaration(partition, "keywordItems").endLine,
            "the chain's last line belongs to the declaration",
        )
    }

    @Test
    fun `enum entries are declarations, annotated or not`() {
        val source = """
            enum class Kind {
                @SerialName("direct") DIRECT,
                COMPILED,
                FLOOR(
                    { floor(it) },
                ),
            }
        """.trimIndent()

        val partition = KotlinSourcePartition.partition(source)
        assertEquals(emptyList(), KotlinSourcePartition.verify(partition), "invariants")
        val entries = partition.allSpans().filter { it.declKind == "enum entry" }
        assertEquals(listOf("DIRECT", "COMPILED", "FLOOR"), entries.map { it.name }, "entries")
        assertEquals(
            lineOf(source, "),"),
            entries.last().endLine,
            "a multi-line entry ends on its own closing paren",
        )
    }

    @Test
    fun `an accessor written with a modifier stays inside its property`() {
        val source = """
            class Series {
                var count: Int = 0
                    private set

                val size: Int
                    get() = 3
            }
        """.trimIndent()

        val partition = KotlinSourcePartition.partition(source)
        assertEquals(emptyList(), KotlinSourcePartition.verify(partition), "invariants")
        assertEquals(
            lineOf(source, "private set"),
            declaration(partition, "count").endLine,
            "`private set` is part of the property above it",
        )
    }

    @Test
    fun `an accessor written with an annotation stays inside its property`() {
        // The annotation sibling of the case above, and the one the corpus actually had: an
        // annotations-only line normally opens the next declaration's head, so the scan stopped at
        // `@Synchronized` and left both it and the `get()` under it unattributed. Thirteen lines of
        // `src/` sat in that gap, eight of them the four repeats of this exact shape in
        // `stdlib/stats.kt`.
        val source = """
            class Series {
                val values = mutableListOf<Int>()

                val size: Int
                    @Synchronized
                    get() = values.size

                val first: Int
                    @JvmName("_getFirst")
                    get() = values.first()
            }
        """.trimIndent()

        val partition = KotlinSourcePartition.partition(source)
        assertEquals(emptyList(), KotlinSourcePartition.verify(partition), "invariants")
        assertEquals(source.split("\n"), KotlinSourcePartition.reconstruct(partition), "reconstruction")
        assertEquals(
            lineOf(source, "get() = values.size"),
            declaration(partition, "size").endLine,
            "the annotated accessor is part of the property above it",
        )
        // Blank lines between declarations are FILLER by design; unattributed *code* is what the
        // corpus guard ratchets on, and what this shape was contributing to it.
        assertEquals(
            emptyList(),
            partition.allSpans()
                .filter { it.kind == KotlinSourcePartition.Kind.FILLER }
                .flatMap { span -> (span.startLine..span.endLine).map { partition.lines[it - 1] } }
                .filter { it.isNotBlank() },
            "no unattributed code should be left over",
        )
    }

    @Test
    fun `an annotation run leading to a declaration still starts that declaration`() {
        // The refusal that makes the case above safe. Reading past an annotations-only run must stop
        // when the run leads to something that declares: `get`/`set` are not declaration keywords, so
        // only a real accessor may pull the run back into the property above.
        val source = """
            class Series {
                val size: Int = 0

                @Synchronized
                fun clear() {
                }
            }
        """.trimIndent()

        val partition = KotlinSourcePartition.partition(source)
        assertEquals(emptyList(), KotlinSourcePartition.verify(partition), "invariants")
        assertEquals(
            lineOf(source, "val size"),
            declaration(partition, "size").endLine,
            "the property must not reach forward over the annotation run",
        )
        assertEquals(
            lineOf(source, "@Synchronized"),
            declaration(partition, "clear").startLine,
            "the run belongs to the function it annotates",
        )
    }

    @Test
    fun `a declaration starts at its annotation, nested argument lists and all`() {
        // `@Deprecated("Use position", ReplaceWith("position.x.toDouble()"))` defeated a regex that
        // stopped at the first `)`, so the line did not read as annotations-only and the declaration
        // started one line late — thirty times in JoltPhysicsWorld.kt alone.
        val source = """
            class Body {
                @Deprecated("Use position", ReplaceWith("position.x.toDouble()"))
                val x: Double = 0.0
            }
        """.trimIndent()

        val partition = KotlinSourcePartition.partition(source)
        assertEquals(emptyList(), KotlinSourcePartition.verify(partition), "invariants")
        val property = declaration(partition, "x")
        assertEquals(lineOf(source, "@Deprecated"), property.startLine, "the span starts at the annotation")
        assertEquals(lineOf(source, "val x"), property.declarationLine, "the signature does not")
    }

    @Test
    fun `a declaration starts at an annotation whose arguments wrap over several lines`() {
        // `@Target(` … `)` above `annotation class exported`, and the same shape as a
        // `@JsonClassDescription("…" + "…")` paragraph above an AI tool's request class. The run scan
        // reads one line at a time, so an argument list left open at the end of one made the whole
        // annotation unattributed filler — 36 lines across four files, over half of everything the
        // partition could not name, and the movement that pushed SourcePartitionGuard past its ratchet.
        val source = """
            @Target(
                AnnotationTarget.FUNCTION,
                AnnotationTarget.PROPERTY
            )
            @Retention(AnnotationRetention.RUNTIME)
            annotation class exported
        """.trimIndent()

        val partition = KotlinSourcePartition.partition(source)
        assertEquals(emptyList(), KotlinSourcePartition.verify(partition), "invariants")
        assertEquals(source.split("\n"), KotlinSourcePartition.reconstruct(partition), "reconstruction")
        val declared = declaration(partition, "exported")
        assertEquals(lineOf(source, "@Target("), declared.startLine, "the span starts at the wrapped annotation")
        assertEquals(lineOf(source, "annotation class"), declared.declarationLine, "the signature does not")
        assertTrue(
            partition.allSpans().none { it.kind == KotlinSourcePartition.Kind.FILLER },
            "nothing should be left over: ${partition.spans}"
        )
    }

    @Test
    fun `an open bracket that is not an annotation does not swallow the declaration`() {
        // The refusal that makes the case above safe. `@JvmStatic fun of(` also ends with a bracket
        // open, and stepping over its balance point would step over the whole signature and lose the
        // function entirely — a far worse outcome than leaving a line unnamed.
        val source = """
            object Holder {
                @JvmStatic fun of(
                    value: Int
                ): Int = value
            }
        """.trimIndent()

        val partition = KotlinSourcePartition.partition(source)
        assertEquals(emptyList(), KotlinSourcePartition.verify(partition), "invariants")
        val function = declaration(partition, "of")
        assertEquals(lineOf(source, "@JvmStatic fun of("), function.declarationLine, "the signature line")
        assertEquals(lineOf(source, "): Int = value"), function.endLine, "the wrapped parameter list belongs to it")
    }

    @Test
    fun `members of a container are children, members of a function body are not`() {
        val source = """
            object Outer {
                fun visible(): Int {
                    fun hidden(): Int = 1
                    return hidden()
                }
            }
        """.trimIndent()

        val partition = KotlinSourcePartition.partition(source)
        assertEquals(emptyList(), KotlinSourcePartition.verify(partition), "invariants")
        val outer = declaration(partition, "Outer")
        assertEquals(listOf("visible"), outer.children.map { it.name }, "the object's members")
        assertEquals(emptyList(), outer.children.single().children, "a local function is body text, not a child")
    }

    @Test
    fun `verify catches a span that claims the wrong lines`() {
        val source = """
            fun a(): Int = 1

            fun b(): Int = 2
        """.trimIndent()

        val partition = KotlinSourcePartition.partition(source)
        assertEquals(emptyList(), KotlinSourcePartition.verify(partition), "the honest partition")

        val mangled = partition.copy(
            spans = partition.spans.mapIndexed { index, span ->
                if (index == 0) span.copy(endLine = span.endLine + 1) else span
            }
        )
        val rules = KotlinSourcePartition.verify(mangled).map { it.rule }.toSet()
        assertTrue("TILE" in rules, "a stretched span should break the tiling: $rules")
        assertTrue("RECONSTRUCT" in rules, "…and the reconstruction: $rules")
    }

    @Test
    fun `a declaration starts at an annotation a comment separates from the keyword`() {
        // `@Test`, then three `//` lines explaining why the function below is not backtick-quoted,
        // then `fun`. The run scan stopped at the comment because a masked comment line is
        // indistinguishable from a blank one, so the annotation was left as unattributed filler and a
        // source page would render it as an anonymous fragment above a declaration missing it. Both
        // of the repository's instances of this shape were stranded that way — the other is
        // `@Serializable(with = FLineSerializer::class)` in `FLine.kt` — which is the movement that
        // pushed SourcePartitionGuard one line past its ratchet on 2026-09-04.
        val source = """
            @Test
            // Named without backticks on purpose: a doc page cites this test as a reproducer anchor,
            // and the gate matches the anchor literally against the file text.
            fun reflectionCannotTell() {
                check(true)
            }
        """.trimIndent()

        val partition = KotlinSourcePartition.partition(source)
        assertEquals(emptyList(), KotlinSourcePartition.verify(partition), "invariants")
        assertEquals(source.split("\n"), KotlinSourcePartition.reconstruct(partition), "reconstruction")
        val declared = declaration(partition, "reflectionCannotTell")
        assertEquals(lineOf(source, "@Test"), declared.startLine, "the span starts at the annotation")
        assertEquals(lineOf(source, "fun reflectionCannotTell"), declared.declarationLine, "the signature does not")
        assertTrue(
            partition.allSpans().none { it.kind == KotlinSourcePartition.Kind.FILLER },
            "nothing should be left over: ${'$'}{partition.spans}"
        )
    }

    @Test
    fun `a blank line still breaks an annotation run, and a lone comment is still a comment`() {
        // The two refusals that make the case above safe. Stepping over a comment inside the run must
        // not become stepping over a blank line — a blank line is the author saying these are two
        // different things — and a comment reached with no annotation above it is a COMMENT span, not
        // the start of whatever follows it.
        val source = """
            @Deprecated("gone")

            // A note about something else entirely.
            fun later(): Int = 1
        """.trimIndent()

        val partition = KotlinSourcePartition.partition(source)
        assertEquals(emptyList(), KotlinSourcePartition.verify(partition), "invariants")
        assertEquals(source.split("\n"), KotlinSourcePartition.reconstruct(partition), "reconstruction")
        assertEquals(
            lineOf(source, "fun later"),
            declaration(partition, "later").startLine,
            "the declaration must not reach back past the blank line to the orphaned annotation"
        )
        assertTrue(
            partition.allSpans().any {
                it.kind == KotlinSourcePartition.Kind.COMMENT &&
                    it.startLine == lineOf(source, "// A note about")
            },
            "the lone comment stays a comment span: ${'$'}{partition.spans}"
        )
    }

    @Test
    fun `an annotated accessor belongs to its property, and a sibling's annotation does not`() {
        // An annotation run on its own line usually decorates the *next* declaration, and the scanner
        // used to stop at one for exactly that reason. On a property it can decorate the property's own
        // accessor instead, and then neither line is a declaration line — the annotation strips to
        // nothing and `get() = …` has no keyword — so both fell out as FILLER: four times in
        // `stats.kt`, and once each in `VulkanBuffer.kt` (`@JvmName`) and `keyframes.kt` (`@OptIn`).
        // Both spellings are here, and so is the sibling case that makes stepping over the run unsafe.
        val source = """
            class Counting {
                private val values = mutableListOf<Int>()

                val size: Int
                    @Synchronized
                    get() = values.size

                val first: Int
                    @Synchronized get() = values.first()

                @Deprecated("use size")
                fun count(): Int = values.size
            }
        """.trimIndent()

        val partition = KotlinSourcePartition.partition(source)
        assertEquals(emptyList(), KotlinSourcePartition.verify(partition), "invariants")
        assertEquals(source.split("\n"), KotlinSourcePartition.reconstruct(partition), "reconstruction")
        assertEquals(
            lineOf(source, "get() = values.size"),
            declaration(partition, "size").endLine,
            "the accessor under its own annotation line belongs to the property"
        )
        assertEquals(
            lineOf(source, "@Synchronized get()"),
            declaration(partition, "first").endLine,
            "so does an accessor behind an annotation on one line"
        )
        assertEquals(
            lineOf(source, "@Deprecated"),
            declaration(partition, "count").startLine,
            "but a sibling's own annotation must stay with the sibling"
        )
        assertEquals(
            emptyList(),
            strandedCode(partition),
            "no line of code should be left unattributed"
        )
    }

    @Test
    fun `an enum entry under its own annotation line is still its own entry`() {
        // The hazard in looking past an annotation run: inside an enum body the thing below the run is
        // the *next entry*, and swallowing it would lose a declaration rather than gain a line. The
        // entry line is not the same shape as an accessor, so the lookahead already stopped — this
        // fixture is here so that the enum question stays asked out loud in both paths.
        val source = """
            enum class Kind {
                FIRST,
                @SerialName("second")
                SECOND,
            }
        """.trimIndent()

        val partition = KotlinSourcePartition.partition(source)
        assertEquals(emptyList(), KotlinSourcePartition.verify(partition), "invariants")
        assertEquals(source.split("\n"), KotlinSourcePartition.reconstruct(partition), "reconstruction")
        assertEquals(
            listOf("FIRST", "SECOND"),
            partition.allSpans().filter { it.declKind == "enum entry" }.map { it.name },
            "both entries survive, and the annotated one is not part of the first"
        )
    }

    @Test
    fun `a soft modifier alone on a line does not end an expression body`() {
        // `value` is a modifier — `value class` — so a line holding nothing but the word reads as a
        // modifier-only line, which is what an expression body beginning with a parameter called
        // `value` looks like. The scanner stopped at it and left the whole body unattributed, six
        // lines of `escapeGlslHtml` in `GlslCompletionSupport.kt`. Looking past the run to the line it
        // decorates answers this one too: a leading `.` continues, a keyword would not.
        val source = """
            private fun escape(value: String): String =
                value
                    .replace("&", "&amp;")
                    .replace("<", "&lt;")
        """.trimIndent()

        val partition = KotlinSourcePartition.partition(source)
        assertEquals(emptyList(), KotlinSourcePartition.verify(partition), "invariants")
        assertEquals(source.split("\n"), KotlinSourcePartition.reconstruct(partition), "reconstruction")
        assertEquals(
            lineOf(source, ".replace(\"<\""),
            declaration(partition, "escape").endLine,
            "the chained expression body is the function's"
        )
        assertEquals(
            emptyList(),
            strandedCode(partition),
            "no line of code should be left unattributed"
        )
    }

    /**
     * The non-blank lines that landed in a [KotlinSourcePartition.Kind.FILLER] span.
     *
     * The distinction [SourcePartitionGuard] ratchets on. A blank line between two members is FILLER
     * by design and says nothing about precision; a line carrying code is a line the scanner could not
     * attribute, which is the thing worth failing a test over.
     */
    private fun strandedCode(partition: KotlinSourcePartition.Partition): List<String> =
        partition.allSpans()
            .filter { it.kind == KotlinSourcePartition.Kind.FILLER }
            .flatMap { span -> (span.startLine..span.endLine).map { partition.lines[it - 1] } }
            .filter { it.isNotBlank() }

    /** The 1-based line of the first line whose text contains [needle]. */
    private fun lineOf(source: String, needle: String): Int {
        val index = source.split("\n").indexOfFirst { needle in it }
        assertTrue(index >= 0, "no line in the fixture contains ${'"'}$needle${'"'}")
        return index + 1
    }

    /** The one declaration span named [name]. */
    private fun declaration(
        partition: KotlinSourcePartition.Partition,
        name: String,
    ): KotlinSourcePartition.Span = partition.allSpans().single {
        it.kind == KotlinSourcePartition.Kind.DECLARATION && it.name == name
    }
}
