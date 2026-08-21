package afield

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/*
FIELD_TEST
id: kotlin.dataframe-plugin.spike
runner: jvm-main
entrypoint: afield.DataFramePluginSpike
tags: [spike, kotlin, repl, dataframe]
timeoutSec: 600
expect:
  exitCode: 0
  stdoutContains:
    - "DataFrame plugin spike complete"
*/
/**
 * Measures what the Kotlin DataFrame compiler plugin can actually do inside Field's K2 REPL.
 *
 * The plugin hangs off [FirFunctionCallRefinementExtension][org.jetbrains.kotlin.fir.extensions.FirFunctionCallRefinementExtension],
 * which the compiler itself documents as "highly unstable and not recommended to use". At a call to
 * a `@Refine`-annotated function it statically interprets the arguments, computes the resulting
 * schema, and rewrites `call()` into `run { class Token; class Scope; call() as DataFrame<Token> }` —
 * with **local** classes, because the extension runs at body-resolve and cannot create top-level
 * declarations. The column accessors are not declarations anywhere: they are a per-call-site local
 * type plus an implicit extension receiver injected by `ReturnTypeBasedReceiverInjector`.
 *
 * That design is what makes the REPL question interesting rather than obvious:
 *
 *  - [probeWithinOneSnippet] — does refinement fire at all here? A `dataFrameOf(...)` and the column
 *    access in the *same* snippet. If this fails, nothing else is worth reading.
 *  - [probeAcrossSnippets] — THE measurement. The same two statements split across two evaluations
 *    of one engine, which is the boundary Field actually has: box re-execution, not cross-box (boxes
 *    have separate engines entirely — see [SharedTypes]). `addReceiversFromExtensions` bails unless
 *    the enclosing container is a `FirCallableSymbol` or `FirCodeFragmentSymbol`, and
 *    `FirReplSnippetSymbol` is neither, so the injected receiver is expected NOT to survive. Worth
 *    measuring rather than asserting: the schema state rides on the FIR declaration via
 *    `FirDeclarationDataRegistry` rather than a session component, which is the mechanism that could
 *    make it work anyway.
 *  - [probeRefinedOperation] — `add("c") { a + b }`, the case the plugin exists for: a schema that
 *    *changes*, with the new column named only in the returned type.
 *
 * Upstream's own REPL work targets the Jupyter kernel, where cross-cell persistence is done by
 * runtime codegen of real top-level `@DataSchema` interfaces and the plugin's top-level generator is
 * switched off to avoid duplicating them (KT-77437). So a within-snippet-only result here would match
 * their design rather than contradict it.
 *
 * A spike: the two invariants fail loudly (no plugin, no runtime = measuring nothing), the probes
 * report either way.
 */
object DataFramePluginSpike {

    private const val IMPORTS = "import org.jetbrains.kotlinx.dataframe.api.*"

    private var withinOneSnippet = false
    private var acrossSnippets = false
    private var refinedOperation = false
    private var sharedSchemaSurvives = false
    private var snippetSchemaSameSnippet = false
    private var snippetSchemaAcrossSnippets = false

    fun runTests(): Boolean {
        return try {
            println("Running DataFrame plugin spike...")

            assertPluginPassedToCompiler()
            assertRuntimeOnClasspath()

            probeWithinOneSnippet()
            probeRefinedOperation()
            probeAcrossSnippets()
            probeSharedDataSchema()
            probeSnippetDeclaredSchema()

            println("DataFrame plugin spike complete")
            report()
            true
        } catch (t: Throwable) {
            println("❌ DataFrame plugin spike failed: ${t.message}")
            t.printStackTrace()
            false
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        check(runTests()) { "DataFrame plugin spike failed" }
    }

    /** Invariant: without the -Xplugin argument every probe below would measure plain Kotlin. */
    private fun assertPluginPassedToCompiler() {
        val args = KotlinInterface2.compilerPluginArgs
        val dataframe = args.filter { it.startsWith("-Xplugin=") && it.endsWith("dataframe-plugin.jar") }
        require(dataframe.isNotEmpty()) {
            "the DataFrame plugin is not being passed to the compiler (args=$args) — every probe " +
                "below would silently measure plain Kotlin. Is libs/dataframe-plugin.jar present?"
        }
        println("  plugin arg: ${dataframe.single()}")
    }

    /** Invariant: the runtime has to be resolvable, or the probes fail for an uninteresting reason. */
    private fun assertRuntimeOnClasspath() {
        val df = runCatching { Class.forName("org.jetbrains.kotlinx.dataframe.DataFrame") }
        require(df.isSuccess) { "dataframe-core is not on the classpath: ${df.exceptionOrNull()}" }
        val refine = runCatching { Class.forName("org.jetbrains.kotlinx.dataframe.annotations.Refine") }
        println("  runtime present; @Refine annotation resolvable = ${refine.isSuccess}")
    }

    /** Does refinement fire at all? Both statements in one snippet. */
    private fun probeWithinOneSnippet() {
        val ki = KotlinInterface2(filePrefix = "dataframe-spike-one")
        val outcome = eval(
            ki,
            """
            $IMPORTS
            val df = dataFrameOf("a", "b")(1, 2)
            df.a.toString() + "/" + df.b.toString()
            """.trimIndent()
        )
        withinOneSnippet = outcome.contains("1") && outcome.contains("2")
        println("  probe A (one snippet, df.a / df.b): fired=$withinOneSnippet outcome=$outcome")
    }

    /**
     * The case the plugin exists for: `add` returns a schema with a column that only the refined
     * return type names, so this cannot work by accident.
     */
    private fun probeRefinedOperation() {
        val ki = KotlinInterface2(filePrefix = "dataframe-spike-add")
        val outcome = eval(
            ki,
            """
            $IMPORTS
            val df = dataFrameOf("a", "b")(1, 2).add("c") { a + b }
            df.c.toString()
            """.trimIndent()
        )
        refinedOperation = outcome.contains("3")
        println("  probe C (add(\"c\") { a + b } then df.c): fired=$refinedOperation outcome=$outcome")
    }

    /**
     * THE measurement. Same engine, two evaluations — Field's real snippet boundary (a box being
     * re-executed). The `val df` is declared in snippet 1 and the column accessed in snippet 2.
     */
    private fun probeAcrossSnippets() {
        val ki = KotlinInterface2(filePrefix = "dataframe-spike-split")
        val first = eval(ki, "$IMPORTS\nval df = dataFrameOf(\"a\", \"b\")(1, 2)")
        println("  probe B setup (snippet 1 declares df): $first")
        val second = eval(ki, "df.a.toString()")
        acrossSnippets = second.contains("1")
        println("  probe B (snippet 2 reads df.a): survived=$acrossSnippets outcome=$second")
    }

    /**
     * The obvious way out of probe B — and it does not work, for a reason worth recording.
     *
     * A `@DataSchema` interface is a real named top-level type, so unlike the inferred local token it
     * *does* survive: the measured result is `DataFrame<Person>` with the plugin reporting a schema of
     * `age: Int`, where probe B decayed to `DataFrame<Any>` and `<empty compile time schema>`. The
     * type and the schema both cross. The accessor still does not.
     *
     * The cause is `TopLevelExtensionsGenerator`, which finds schemas with
     * `session.predicateBasedProvider.getSymbolsByPredicate(annotated(DataSchema))`. K2's
     * predicate-based provider indexes **source** declarations only, so a `@DataSchema` compiled into
     * a directory on the classpath — which is exactly what [SharedTypes] produces — is invisible to
     * it. Adding `import shared.*` does not help; the generated callables were never created.
     *
     * That is also why upstream ships a second, separate mechanism for schemas that arrive from
     * outside the module: `ImportedSchemasGenerator` + `ImportedSchemasService`, gated on the
     * `schemasPath` plugin option pointing at a directory of schema JSON produced by a build-time
     * generator. Wiring that is the next thing to try if persistent typed columns are wanted; it is
     * not reachable by declaring the schema and hoping.
     */
    private fun probeSharedDataSchema() {
        if (KotlinInterface2.sharedClassLoader == null) {
            KotlinInterface2.sharedClassLoader = java.net.URLClassLoader(
                arrayOf(), Thread.currentThread().contextClassLoader
            )
        }
        val workspace = java.nio.file.Files.createTempDirectory("dataframe-spike").toFile()
        val published = SharedTypes.publish(
            """
            package shared

            import org.jetbrains.kotlinx.dataframe.annotations.DataSchema

            @DataSchema
            interface Person {
                val name: String
                val age: Int
            }
            """,
            workspace, except = null, refreshOthers = false
        )
        require(published.ok) { "publishing the @DataSchema failed: ${published.log}" }
        println("  probe D setup: published ${published.classFiles}")

        val ki = KotlinInterface2(filePrefix = "dataframe-spike-schema")
        val first = eval(
            ki,
            """
            $IMPORTS
            import shared.*
            val people = dataFrameOf("name", "age")("Alice", 15, "Bob", 30).cast<shared.Person>()
            """.trimIndent()
        )
        println("  probe D setup (snippet 1 declares a cast df): $first")
        // The generated accessors are top-level callables in the SCHEMA's package
        // (TopLevelExtensionsGenerator uses the schema's own CallableId packageName), so the
        // consuming snippet has to import that package — being able to name the type is not enough.
        val second = eval(ki, "import shared.*\npeople.age.values().sum()")
        sharedSchemaSurvives = second.contains("45")
        println("  probe D (snippet 2 reads people.age): survived=$sharedSchemaSurvives outcome=$second")
    }

    /**
     * Decides how much codegen a live-coding story would need.
     *
     * `TopLevelExtensionsGenerator` discovers schemas through K2's predicate-based provider, which is
     * source-only — that is why probe D's compiled schema generated nothing. A schema declared *in a
     * snippet* is source, so it should work within that snippet. The question is the next one: does a
     * previous snippet's declaration still count as source to the provider, or is each snippet its own
     * compilation as far as predicates are concerned?
     *
     *  - both true  → Field need only emit `@DataSchema interface X { ... }` from a frame's runtime
     *    schema and eval it; the plugin generates every accessor.
     *  - first only → Field must emit the accessors itself (plain `val DataFrame<X>.age get() =
     *    this["age"] as DataColumn<Int>`), which is more source but needs no plugin cooperation.
     */
    private fun probeSnippetDeclaredSchema() {
        val ki = KotlinInterface2(filePrefix = "dataframe-spike-snippet-schema")
        val schema = """
            $IMPORTS
            import org.jetbrains.kotlinx.dataframe.annotations.DataSchema

            @DataSchema
            interface Rows { val a: Int; val b: Int }
        """.trimIndent()

        val together = eval(
            ki,
            schema + "\n" + """val d = dataFrameOf("a", "b")(1, 2).cast<Rows>(); d.a.toString()"""
        )
        snippetSchemaSameSnippet = together.contains("1")
        println("  probe E1 (@DataSchema declared and used in ONE snippet): fired=$snippetSchemaSameSnippet outcome=$together")

        val later = eval(ki, """val d2 = dataFrameOf("a", "b")(3, 4).cast<Rows>(); d2.a.toString()""")
        snippetSchemaAcrossSnippets = later.contains("3")
        println("  probe E2 (same schema used in a LATER snippet): fired=$snippetSchemaAcrossSnippets outcome=$later")
    }

    private fun report() {
        println(
            if (!withinOneSnippet) {
                "VERDICT: refinement does not fire in this REPL at all — the plugin is loaded but its " +
                    "call interception is not reaching box code. Read probe A's outcome before anything else."
            } else if (acrossSnippets) {
                "VERDICT: refinement fires AND survives a snippet boundary — typed columns work across " +
                    "re-executions of a box, which is more than upstream's own REPL story promises."
            } else {
                "VERDICT: refinement fires WITHIN a snippet but does not survive a snippet boundary. " +
                    "That makes this a within-execution feature: typed columns for a chain written in " +
                    "one go, not for a value left in a box and used by a later run. Matches upstream, " +
                    "whose cross-cell story is the Jupyter kernel's runtime codegen, not the plugin."
            }
        )
        println(
            if (sharedSchemaSurvives) {
                "VERDICT (schema route): a @DataSchema interface published through SharedTypes DOES " +
                    "survive the snippet boundary — name the schema and typed columns persist, which is " +
                    "the usable shape of this feature in Field."
            } else {
                "VERDICT (schema route): the named schema crosses but its ACCESSORS do not. The type " +
                    "arrives as DataFrame<Person> with a known schema, so this is not the local-token " +
                    "problem of probe B — TopLevelExtensionsGenerator discovers schemas through K2's " +
                    "predicate-based provider, which is source-only, so a compiled-elsewhere @DataSchema " +
                    "generates nothing. The designed route for outside schemas is the plugin's " +
                    "schemasPath option + ImportedSchemasGenerator, which this spike does not wire."
            }
        )
        println(
            if (snippetSchemaAcrossSnippets) {
                "VERDICT (live-coding route): a @DataSchema declared in an EARLIER snippet still gets " +
                    "accessors generated — so Field can make this live-codeable by emitting just the " +
                    "interface from a frame's runtime schema and evaluating it into the box's engine."
            } else if (snippetSchemaSameSnippet) {
                "VERDICT (live-coding route): a snippet-declared @DataSchema works only in the snippet " +
                    "that declares it, so predicate discovery does not reach REPL history. Field would " +
                    "have to emit the accessors as ordinary Kotlin too — more generated source, but no " +
                    "plugin cooperation needed, and it persists like any other declaration."
            } else {
                "VERDICT (live-coding route): even a snippet-declared @DataSchema generates nothing; " +
                    "read probe E1 before designing anything on top of the plugin."
            }
        )
        println(
            "  summary: withinOneSnippet=$withinOneSnippet refinedOperation=$refinedOperation " +
                "acrossSnippets=$acrossSnippets sharedSchemaSurvives=$sharedSchemaSurvives " +
                "snippetSchemaSameSnippet=$snippetSchemaSameSnippet " +
                "snippetSchemaAcrossSnippets=$snippetSchemaAcrossSnippets"
        )
    }

    private fun eval(ki: KotlinInterface2, code: String): String =
        try {
            runBlocking { withTimeout(240_000) { ki.exec(code) } }?.toString() ?: "<null>"
        } catch (t: Throwable) {
            "threw ${t::class.simpleName}: ${t.message}"
        }
}
