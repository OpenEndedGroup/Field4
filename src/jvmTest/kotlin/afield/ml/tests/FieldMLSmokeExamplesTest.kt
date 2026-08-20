package afield.ml.tests

import afield.Box
import afield.ml.FieldMLCompiler
import afield.ml.CompilationResult
import afield.ml.FieldMLRuntimePrelude
import afield.ml.core.Evaluator
import afield.ml.core.renderFieldMLValue
import afield.ml.type.Inferencer
import afield.ml.type.TypePrinter
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FieldMLSmokeExamplesTest {
    data class Example(
        val name: String,
        val source: String,
        val value: String,
        val type: String,
    )

    data class ErrorExample(
        val name: String,
        val source: String,
        val messageContains: String,
    )

    @Test
    fun fullLanguageExampleCatalogCompilesTypesAndEvaluates() {
        examples.forEach { example ->
            val result = compileOk(example.name, example.source)
            val actualValue = renderFieldMLValue(Evaluator(FieldMLRuntimePrelude.values()).evaluate(result.core!!))
            val actualType = TypePrinter().print(result.typed!!.type)

            assertEquals(example.value, actualValue, "${example.name}: value")
            assertEquals(example.type, actualType, "${example.name}: type")
            assertNotNull(result.tokens, "${example.name}: tokens")
            assertNotNull(result.surface, "${example.name}: surface AST")
            assertNotNull(result.typed, "${example.name}: typed AST")
            assertNotNull(result.core, "${example.name}: core")
        }
    }

    @Test
    fun expectedFailuresProduceUsefulDiagnostics() {
        errorExamples.forEach { example ->
            val result = FieldMLCompiler.compile(example.source)

            assertTrue(!result.successful, "${example.name}: should fail")
            assertTrue(
                result.diagnostics.any { example.messageContains in it.message },
                "${example.name}: expected diagnostic containing '${example.messageContains}', got ${
                    result.diagnostics.joinToString { it.message }
                }",
            )
        }
    }

    @Test
    fun docsFieldMLExamplesCompileAndEvaluate() {
        val docsPages = listOf(
            Path.of("public/docs/fieldml_examples.md"),
            Path.of("public/docs/fieldml_cheatsheet.md"),
            Path.of("public/docs/fieldml_host_bridge.md"),
        )

        docsPages.forEach { docsPath ->
            val source = docsPath.toFile().readText()
            val snippets = fieldMLDocSnippets(source)

            assertTrue(snippets.isNotEmpty(), "Expected FieldML snippets in $docsPath")
            snippets.forEachIndexed { index, snippet ->
                val name = "${docsPath.fileName} snippet ${index + 1}"
                val result = compileOk(name, snippet)
                val box = Box("fieldml-docs-smoke-${docsPath.fileName}-$index")
                Evaluator(FieldMLRuntimePrelude.values(box = box)).evaluate(result.core!!)
            }
        }
    }

    /**
     * A loadable demo document is an artifact a human opens in a fresh Field, so a broken box in one
     * is a broken promise. Every box's code is compiled and run here, the same guard the docs pages
     * get — which is what makes the demos trustworthy without opening them.
     */
    @Test
    fun demoDocumentBoxesCompileAndEvaluate() {
        val demos = listOf(
            Path.of("public/docs/assets/fieldml_host_bridge_demo/metadata.edn"),
            Path.of("public/docs/assets/fieldml_lambda_demo/metadata.edn"),
        )

        demos.forEach { demo ->
            val sources = ednCodeStrings(demo.toFile().readText())
            assertTrue(sources.size >= 4, "Expected at least four FieldML boxes in $demo, found ${sources.size}")
            sources.forEachIndexed { index, source ->
                val name = "$demo box ${index + 1}"
                val result = compileOk(name, source)
                val box = Box("${demo.parent.fileName}-$index")
                Evaluator(FieldMLRuntimePrelude.values(box = box)).evaluate(result.core!!)
            }
        }
    }

    /**
     * The source-macro demo, run with its **own topology** rather than a fresh box per box.
     *
     * It gets its own test because its subject *is* the topology: the drawing box never writes
     * `swirl`, it inherits it, so running it against a fresh box would prove the opposite of the
     * demo's point. Boxes are run in document order down the real parent chain, and the geometry the
     * inherited parameter produced is measured — the drawn line has to have the 320 nodes the
     * `linspace` asked for, which is the cheapest check that the parameter arrived as a number and not
     * as an error.
     *
     * The counterfactual is the second half: the drawing box run **without** its parent having written
     * the slot must fail, and fail naming the slot. That is what makes the inheritance load-bearing
     * rather than decorative.
     */
    @Test
    fun theSourceMacroDemoRunsDownItsOwnParentChain() {
        val demo = Path.of("public/docs/assets/fieldml_macro_demo/metadata.edn")
        val sources = ednCodeStrings(demo.toFile().readText())
        assertEquals(4, sources.size, "expected four FieldML boxes in $demo")
        val (parameter, drawing, printing, expanding) = sources

        // The document's shape. Every content box is a *direct* child of the FieldML root, because
        // `applyRuntimeToggle` only converts direct children (LanguageRuntimeSupport.kt:57) and a
        // grandchild would silently run as Kotlin — which is how this demo first failed in a real
        // Field while passing here. The second parent is what carries the inherited parameter.
        val root = Box("fieldml-macro-root")
        val parameterBox = Box("fieldml-macro-parameter")
        val drawingBox = Box("fieldml-macro-drawing")
        val printingBox = Box("fieldml-macro-println")
        val expandingBox = Box("fieldml-macro-expansion")
        parameterBox.parents.add(root)
        drawingBox.parents.addAll(listOf(root, parameterBox))
        printingBox.parents.add(root)
        expandingBox.parents.addAll(listOf(root, printingBox))

        // And a drift guard on the *document*, which is what actually broke: every content box has to
        // appear in the FieldML root's own `:children`, or Field will run it as Kotlin however the
        // rest of the graph is wired. This is not checkable from the code strings, so it reads the edn.
        val rootChildren = Regex(""":children\s*#\{([^}]*)\}""")
            .find(demo.toFile().readText())!!
            .groupValues[1]
        listOf(parameterBox, drawingBox, printingBox, expandingBox).forEach { box ->
            assertTrue(
                "\"${box.id}\"" in rootChildren,
                "${box.id} is not a direct child of the FieldML root in $demo, so Field will run it as " +
                    "Kotlin (LanguageRuntimeSupport.kt:57). Root children are: $rootChildren",
            )
        }

        fun run(name: String, source: String, box: Box): Any? =
            afield.ml.core.fieldMLValueToHost(
                Evaluator(FieldMLRuntimePrelude.values(box = box)).evaluate(compileOk(name, source).core!!),
            )

        // The drawing box before the parameter box has run: the slot is empty all the way up.
        val premature = runCatching { run("$demo drawing (early)", drawing, drawingBox) }.exceptionOrNull()
        assertNotNull(premature, "reading an unwritten prop should fail — otherwise inheritance is not the subject")
        assertTrue(
            "swirl" in premature.message.orEmpty(),
            "the refusal should name the slot, got: ${premature.message}",
        )

        run("$demo parameter", parameter, parameterBox)
        assertEquals(0.85, parameterBox.getPropertyRaw("swirl"), "the parameter box should have written the slot")
        assertEquals(null, drawingBox.getPropertyRaw("swirl"), "and only on itself — the child inherits it")

        val line = run("$demo drawing", drawing, drawingBox)
        val nodes = line?.javaClass?.getMethod("getNodes")?.invoke(line) as? List<*>
        assertEquals(320, nodes?.size, "the inherited parameter should have driven 320 nodes of geometry")

        // The two macro boxes, whose subject is `hostMacro`/`hostMacroExpand` rather than inheritance.
        val printed = mutableListOf<Any?>()
        printingBox.output.proxy = { payload, _, _ -> printed += payload }
        val printResult = run("$demo println", printing, printingBox)
        assertTrue(
            printed.any { it == "this line came out of Field's println macro" },
            "the demo's println box must actually print through Field's macro, got $printed",
        )
        assertTrue(
            "_printlnHere" in printResult.toString(),
            "the box should report the function the macro rewrote to, got $printResult",
        )

        val expandResult = run("$demo expand", expanding, expandingBox).toString()
        assertTrue(
            "by Inheritance<Double>()" in expandResult,
            "the expansion box should show the Kotlin `prop` generates, got $expandResult",
        )
        assertTrue(
            "SourceBackedBlock" in expandResult,
            "and the bound it cannot cross, got $expandResult",
        )
    }

    /**
     * The `afield.four` demo is the one document here that *cannot* be evaluated: it needs a Vulkan
     * device, and a headless test JVM has none. So it is compiled and type-checked only — which is
     * still worth doing, because everything that went wrong while it was being written went wrong at
     * compile time (`Module has no method 'pointer'`, `Parameters has no member 'vertexx'`), and a
     * silent rot in it would be exactly that kind of error.
     *
     * It was *run* on 2026-08-08 in a `field_backdoor.py --graphics-context` session and rendered a
     * triangle into the native window; `triangle.png` beside it is that capture. Nothing here can
     * check that, and nothing here pretends to.
     */
    @Test
    fun theAfieldFourDemoTypeChecksEvenThoughItCannotRunHere() {
        val demo = Path.of("public/docs/assets/fieldml_afield_four_demo/metadata.edn")
        val sources = ednCodeStrings(demo.toFile().readText())

        assertEquals(1, sources.size, "expected one FieldML box in $demo")
        val result = compileOk("$demo box 1", sources.single())
        assertNotNull(result.core, "$demo box 1: core")
        assertTrue(
            "hostAs \"" !in sources.single(),
            "$demo uses hostAs, which is the thing it exists to show is unnecessary",
        )
    }

    /**
     * The *moving* `afield.four` demo — the same still life plus mechanism D — and it cannot be
     * evaluated here either, for the same reason.
     *
     * Two things are checked beyond "it compiles", and both are drift guards on the demo's *point*
     * rather than on its syntax. It has to still reach a suspend function type, or it is the
     * still-life demo again with a longer comment; and it has to keep declaring, in the box a reader
     * opens first, why the scaffolding box is Kotlin. A demo that quietly loses its subject is worse
     * than one that fails to compile, because nothing tells you.
     *
     * It was *run* on 2026-08-19 in a `field_backdoor.py` session against a real Vulkan device;
     * `spin-0.png` … `spin-3.png` beside it are four frames of the result, and they differ.
     */
    @Test
    fun theFramesDemoTypeChecksAndKeepsItsSubject() {
        val demo = Path.of("public/docs/assets/fieldml_frames_demo/metadata.edn")
        val sources = ednCodeStrings(demo.toFile().readText())

        assertEquals(2, sources.size, "expected a Kotlin scaffolding box and a FieldML box in $demo")
        val scaffolding = sources[0]
        val fieldML = sources[1]

        val result = compileOk("$demo FieldML box", fieldML)
        assertNotNull(result.core, "$demo FieldML box: core")
        assertTrue(
            "hostAs \"" !in fieldML,
            "$demo uses hostAs, which is the thing these demos exist to show is unnecessary",
        )
        listOf("eachFrame", "launchFrames").forEach { name ->
            assertTrue(
                name in fieldML,
                "$demo no longer calls $name, so it no longer demonstrates a suspend function type",
            )
        }
        assertTrue(
            "suspend" in scaffolding && "SOURCE MACRO" in scaffolding,
            "$demo's Kotlin box must keep saying why it is Kotlin: `prop`/`once` are macros and " +
                "`Renderer.run` is a suspend member the bridge can satisfy but not call into",
        )
    }

    /**
     * The mesh-writing demo — the wall past the moving one, and the last thing
     * `dev_docs/FIELDML_HOST_BRIDGE.md` named as out of reach downstream of a device.
     *
     * Its subject is a vertex shader with **real inputs** whose data is recomputed in FieldML every
     * frame, so the drift guards are on exactly that: it must still declare two `layout(location = …)
     * in` variables, still write both of them through `Mesh.write`, and still use one positional and
     * one **named** call to do it — the named one being the shape that omits `Mesh.write`'s
     * `inside` default, which is why mechanisms B and C had to close before this box could be
     * written the way it is.
     *
     * Run on 2026-08-19 against a real Vulkan device; `ring-*.png` beside it are the captures.
     */
    @Test
    fun theMeshDemoTypeChecksAndKeepsItsSubject() {
        val demo = Path.of("public/docs/assets/fieldml_mesh_demo/metadata.edn")
        val sources = ednCodeStrings(demo.toFile().readText())

        assertEquals(2, sources.size, "expected a Kotlin scaffolding box and a FieldML box in $demo")
        val fieldML = sources[1]

        val result = compileOk("$demo FieldML box", fieldML)
        assertNotNull(result.core, "$demo FieldML box: core")
        assertTrue("hostAs \"" !in fieldML, "$demo uses hostAs, which these demos exist to show is unnecessary")
        listOf("layout(location = 0) in vec2 pos;", "layout(location = 1) in float hue;").forEach { input ->
            assertTrue(
                input in fieldML,
                "$demo must keep declaring `$input` — a vertex shader with real inputs is its whole subject",
            )
        }
        assertTrue(
            """hostCall "write" (0, 2,""" in fieldML,
            "$demo must keep writing location 0 positionally, three arguments with a trailing lambda",
        )
        assertTrue(
            """hostCall "write" {name = "hue", builder =""" in fieldML,
            "$demo must keep writing location 1 BY NAME — that call omits Mesh.write's `inside` " +
                "default, which is the thing no positional call can do",
        )
    }

    /**
     * The lambda demo's promise is specifically that it needs no `hostAs` — so if one drifts back in
     * the demo has stopped showing the thing it exists to show, even though every box still runs.
     *
     * Exactly one box is allowed one, and it is the box whose subject *is* the case that still needs
     * it: a receiver that is a function parameter has no known class, so the callback inside
     * `let f line = …` is still on its own. That box marks itself with the sentence below.
     */
    @Test
    fun theLambdaDemoContainsNoErasureEscapesExceptTheOneItDocuments() {
        val demo = Path.of("public/docs/assets/fieldml_lambda_demo/metadata.edn")
        val sources = ednCodeStrings(demo.toFile().readText())
        val marker = "remains the way out of that one"

        sources.forEachIndexed { index, source ->
            if (marker in source) return@forEachIndexed
            assertTrue(
                "hostAs \"" !in source,
                "$demo box ${index + 1} uses hostAs, which is the thing this demo shows is unnecessary",
            )
        }
        assertEquals(
            1,
            sources.count { marker in it },
            "expected exactly one box in $demo to document the residual case",
        )
    }

    /**
     * The inferencer's one caching invariant, checked over every FieldML program in the repository
     * rather than argued about in a comment.
     *
     * `Inferencer.preInferHostSpine` computes a result in one environment and hands it to a walk that
     * consumes it elsewhere; that is sound only while the two environments are the same, and it is
     * kept sound structurally by threading the memo only along paths that leave the environment
     * alone. `SpineMemo.take` counts every time it is reached under a different one. The count is
     * absolute, not a delta, so this test does not care what order it runs in — and if it ever fails,
     * the fix is in the inferencer, never here.
     */
    @Test
    fun noHostSpineResultIsEverReusedUnderADifferentEnvironment() {
        examples.forEach { FieldMLCompiler.compile(it.source.trimIndent()) }
        errorExamples.forEach { FieldMLCompiler.compile(it.source.trimIndent()) }
        listOf(
            Path.of("public/docs/fieldml_examples.md"),
            Path.of("public/docs/fieldml_cheatsheet.md"),
            Path.of("public/docs/fieldml_host_bridge.md"),
        ).forEach { page ->
            fieldMLDocSnippets(page.toFile().readText()).forEach { FieldMLCompiler.compile(it) }
        }
        listOf(
            Path.of("public/docs/assets/fieldml_host_bridge_demo/metadata.edn"),
            Path.of("public/docs/assets/fieldml_lambda_demo/metadata.edn"),
        ).forEach { demo ->
            ednCodeStrings(demo.toFile().readText()).forEach { FieldMLCompiler.compile(it) }
        }

        assertEquals(
            0L,
            Inferencer.environmentMismatches.get(),
            "a pre-inferred host-call argument was reused under an environment it was not computed in",
        )
    }

    /**
     * The `:code "…"` values of a saved Field document, with EDN's string escapes undone.
     *
     * Scanned by hand rather than with `Regex(""":code\s+"((?:[^"\\]|\\.)*)"""")`, which is what
     * this was and which **overflowed the stack**. Java compiles a `*` over a group into a
     * recursive `Pattern$Loop.match`, so a quoted string costs one JVM frame per character; a box's
     * `:code` runs to thousands. It passed run alone and threw `StackOverflowError` inside the full
     * suite, where the stack is already deep — the worst shape of failure, because the test looks
     * flaky and the stack trace names `java.util.regex`, not this line. A single forward pass has
     * no such limit. (Found on 2026-08-19 by the first full `jvmTest` run after the merge.)
     */
    private fun ednCodeStrings(text: String): List<String> {
        val key = ":code"
        val out = mutableListOf<String>()
        var i = 0
        while (true) {
            val at = text.indexOf(key, i)
            if (at < 0) break
            var j = at + key.length
            while (j < text.length && text[j].isWhitespace()) j++
            if (j >= text.length || text[j] != '"') { i = at + key.length; continue }
            j++
            val sb = StringBuilder()
            while (j < text.length && text[j] != '"') {
                val c = text[j]
                if (c == '\\' && j + 1 < text.length) {
                    when (val escaped = text[j + 1]) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        else -> sb.append('\\').append(escaped)
                    }
                    j += 2
                } else {
                    sb.append(c)
                    j++
                }
            }
            out += sb.toString()
            i = if (j < text.length) j + 1 else text.length
        }
        return out
    }

    private fun compileOk(name: String, source: String): CompilationResult {
        val result = FieldMLCompiler.compile(source.trimIndent())
        assertTrue(
            result.successful,
            "$name failed:\n${result.diagnostics.joinToString("\n") { "${it.severity}: ${it.message}" }}",
        )
        return result
    }

    private fun fieldMLDocSnippets(source: String): List<String> {
        val fence = Regex(
            pattern = """(?ms)^```diffstatic(?:_runner)?\s+\{[^}]*data-lang=fieldml[^}]*}\s*\n(.*?)^```""",
        )
        return fence.findAll(source)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }
            .toList()
    }

    companion object {
        val examples = listOf(
            Example(
                name = "integer arithmetic and precedence",
                source = "10 + 2 * 16",
                value = "42",
                type = "Int",
            ),
            Example(
                name = "float arithmetic",
                source = "10.5 * 4.0",
                value = "42.0",
                type = "Float",
            ),
            Example(
                name = "boolean comparison and if",
                source = "if 20 > 10 then true else false",
                value = "true",
                type = "Bool",
            ),
            Example(
                name = "top-level immutable lets",
                source = """
                    let x = 10
                    let y = 32
                    x + y
                """,
                value = "42",
                type = "Int",
            ),
            Example(
                name = "local let expression",
                source = "let x = 6 in let y = 7 in x * y",
                value = "42",
                type = "Int",
            ),
            Example(
                name = "curried function",
                source = """
                    let add a b = a + b
                    add 20 22
                """,
                value = "42",
                type = "Int",
            ),
            Example(
                name = "anonymous function",
                source = "(fun x -> x + 1) 41",
                value = "42",
                type = "Int",
            ),
            Example(
                name = "polymorphic identity over record",
                source = """
                    let id x = x
                    {
                      a = id 10
                      b = id true
                      c = id "field"
                    }
                """,
                value = """{a = 10, b = true, c = "field"}""",
                type = "{a: Int, b: Bool, c: String}",
            ),
            Example(
                name = "record construction and field access",
                source = """
                    let p = {
                      x = 10.0
                      y = 20.0
                    }
                    {
                      x = p.x * 2.0
                      y = p.y * 2.0
                    }
                """,
                value = "{x = 20.0, y = 40.0}",
                type = "{x: Float, y: Float}",
            ),
            Example(
                name = "nested records",
                source = """
                    let p = {
                      position = { x = 10.0, y = 20.0 }
                      label = "seed"
                    }
                    (p.label, p.position.x + p.position.y)
                """,
                value = """("seed", 30.0)""",
                type = "(String, Float)",
            ),
            Example(
                name = "tuple literal",
                source = "(10, true, \"ok\")",
                value = """(10, true, "ok")""",
                type = "(Int, Bool, String)",
            ),
            Example(
                name = "homogeneous list literal",
                source = "[10, 20, 12]",
                value = "[10, 20, 12]",
                type = "[Int]",
            ),
            Example(
                name = "tuple pattern",
                source = "match (20, 22) with | (a, b) -> a + b",
                value = "42",
                type = "Int",
            ),
            Example(
                name = "list pattern exact shape",
                source = "match [10, 32] with | [a, b] -> a + b | _ -> 0",
                value = "42",
                type = "Int",
            ),
            Example(
                name = "literal string pattern",
                source = """match "field" with | "field" -> 42 | _ -> 0""",
                value = "42",
                type = "Int",
            ),
            Example(
                name = "literal bool pattern",
                source = "match true with | true -> 42 | false -> 0",
                value = "42",
                type = "Int",
            ),
            Example(
                name = "option adt none",
                source = """
                    type Option a = None | Some a
                    match None with
                    | Some x -> x
                    | None -> 42
                """,
                value = "42",
                type = "Int",
            ),
            Example(
                name = "option adt some",
                source = """
                    type Option a = None | Some a
                    match Some 42 with
                    | Some x -> x
                    | None -> 0
                """,
                value = "42",
                type = "Int",
            ),
            Example(
                name = "adt carries tuple and list",
                source = """
                    type Option a = None | Some a
                    match Some (1, [2, 3]) with
                    | Some pair -> pair
                    | None -> (0, [])
                """,
                value = "(1, [2, 3])",
                type = "(Int, [Int])",
            ),
            Example(
                name = "result adt with record payload",
                source = """
                    type Result a = Err String | Ok a
                    let payload = { x = 20, y = 22 }
                    match Ok payload with
                    | Ok p -> p.x + p.y
                    | Err message -> 0
                """,
                value = "42",
                type = "Int",
            ),
            Example(
                name = "nested constructor pattern",
                source = """
                    type Option a = None | Some a
                    match Some (Some 42) with
                    | Some (Some x) -> x
                    | Some None -> 0
                    | None -> 0
                """,
                value = "42",
                type = "Int",
            ),
            Example(
                name = "constructor pattern with tuple payload",
                source = """
                    type PairBox a = PairBox a
                    match PairBox (20, 22) with
                    | PairBox (a, b) -> a + b
                """,
                value = "42",
                type = "Int",
            ),
            Example(
                name = "adt polymorphic constructor reused at different types",
                source = """
                    type Option a = None | Some a
                    let id x = x
                    {
                      number = id (Some 42)
                      flag = id (Some true)
                    }
                """,
                value = "{number = Some 42, flag = Some true}",
                type = "{number: Option Int, flag: Option Bool}",
            ),
            Example(
                name = "comparison function",
                source = """
                    let max a b =
                      if a > b then a else b
                    max 10 42
                """,
                value = "42",
                type = "Int",
            ),
            Example(
                name = "pipe into anonymous function",
                source = "10 |> (fun x -> x + 32)",
                value = "42",
                type = "Int",
            ),
            Example(
                name = "pipe into curried function with existing argument",
                source = """
                    let add a b = a + b
                    10 |> add 32
                """,
                value = "42",
                type = "Int",
            ),
            Example(
                name = "stdlib output taps compose with pipe",
                source = """42 |> display "fieldml" |> show""",
                value = "\"42\"",
                type = "String",
            ),
            Example(
                name = "small expressive sketch payload",
                source = """
                    type Mark a = Mark a
                    let marks = [
                      Mark { x = 10.0, y = 20.0 },
                      Mark { x = 4.0, y = 5.0 }
                    ]
                    match marks with
                    | [Mark a, Mark b] -> (
                        { x = a.x * 2.0, y = a.y * 2.0 },
                        { x = b.x * 4.0, y = b.y * 4.0 }
                      )
                    | _ -> ({ x = 0.0, y = 0.0 }, { x = 0.0, y = 0.0 })
                """,
                value = "({x = 20.0, y = 40.0}, {x = 16.0, y = 20.0})",
                type = "({x: Float, y: Float}, {x: Float, y: Float})",
            ),
        )

        val errorExamples = listOf(
            ErrorExample(
                name = "mixed numeric operands",
                source = "10 + 1.0",
                messageContains = "Type mismatch",
            ),
            ErrorExample(
                name = "mixed list element types",
                source = "[1, true]",
                messageContains = "Type mismatch",
            ),
            ErrorExample(
                name = "missing record field",
                source = """
                    let p = { x = 10.0 }
                    p.y
                """,
                messageContains = "missing field 'y'",
            ),
            ErrorExample(
                name = "match branch mismatch",
                source = """
                    type Option a = None | Some a
                    match Some 10 with
                    | Some x -> x
                    | None -> false
                """,
                messageContains = "Type mismatch",
            ),
            ErrorExample(
                name = "constructor arity mismatch in pattern",
                source = """
                    type Option a = None | Some a
                    match Some 10 with
                    | Some -> 0
                """,
                messageContains = "expects 1 pattern argument",
            ),
            ErrorExample(
                name = "tuple arity mismatch",
                source = "match (1, 2) with | (a, b, c) -> a",
                messageContains = "Tuple arity mismatch",
            ),
        )
    }
}
