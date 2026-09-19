package afield.ml.tests

import afield.ml.FieldMLCompiler
import afield.ml.FieldMLRuntimePrelude
import afield.ml.ast.EBinary
import afield.ml.ast.ECall
import afield.ml.ast.EInt
import afield.ml.ast.ELambda
import afield.ml.ast.ELet
import afield.ml.ast.EVar
import afield.ml.ast.Expr
import afield.ml.core.CApp
import afield.ml.core.CBinary
import afield.ml.core.CInt
import afield.ml.core.CLam
import afield.ml.core.CLet
import afield.ml.core.CVar
import afield.ml.core.CoreExpr
import afield.ml.core.Evaluator
import afield.ml.core.Lowerer
import afield.ml.core.renderFieldMLValue
import afield.ml.lex.Lexer
import afield.ml.parse.Parser
import afield.ml.type.Inferencer
import afield.ml.type.TExpr
import afield.ml.type.TExprBinary
import afield.ml.type.TExprCall
import afield.ml.type.TExprInt
import afield.ml.type.TExprLambda
import afield.ml.type.TExprLet
import afield.ml.type.TExprVar
import afield.ml.type.TypePrinter
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `public/docs/fieldml_stages.md`, re-derived.
 *
 * ## What this guards, and why it is not a capture
 *
 * The stages page walks one FieldML program through the whole front end and prints what each stage
 * produced: the token list, the surface tree, the inferred types, the typed tree, the core tree, and
 * the value. Every one of those listings is machine output. A capture is output pasted onto a page
 * *once*, and it decays silently the first time the renderer it came from changes — nothing on the
 * page knows it is stale.
 *
 * This test removes that decay. It reads the published page, pulls the program out of the page's own
 * first FieldML fence, recomputes all six listings from that program text, and asserts each equals
 * the fence the page publishes. So the page is not a capture that CI happens to agree with; it is a
 * capture **CI re-derives on every run**. A digit on that page cannot go stale without this test
 * going red, and it cannot be fixed by editing the page to match a guess, because the comparison
 * runs the compiler.
 *
 * The renderers below are therefore *part of the page*, not test scaffolding. They are the only
 * thing that decides what the listings look like, which is why they live in one file with the
 * assertions rather than in `jvmMain`: the page's prose describes their output, and a change to
 * either has to be a change to both.
 *
 * ## Why these renderers are strict
 *
 * Each of the three tree renderers handles only the node kinds the published program actually uses
 * and calls `error()` on anything else, rather than falling back to a class name. A permissive
 * renderer would keep working if the program on the page were edited to use a construct the page
 * does not explain, and would quietly print a less informative tree. A strict one fails and names
 * the node. The page and the renderer are meant to be edited together.
 *
 * ## The one structural claim the page makes that prose cannot
 *
 * `|>` has no node of its own: the parser desugars it into the same [ECall] spine as juxtaposition.
 * [pipeAndJuxtapositionShareOneSurfaceTree] proves that by rendering both spellings and comparing
 * the trees, which is the readout the page publishes under `surface-pipe`. It is stated in
 * `FieldMLCompilerTest.parsesPipeAsFunctionApplicationToPreviousValue` as an assertion about three
 * nodes; here it is the whole tree, and it is what a reader sees.
 */
class FieldMLStagePageTest {
    // ---------------------------------------------------------------- the page

    /**
     * Every stage readout on the page, recomputed from the page's own program and compared.
     *
     * The program is read out of the page rather than duplicated here on purpose: if the two
     * disagreed there would be no way to tell which one the listings came from.
     */
    @Test
    fun everyStageReadoutOnThePageIsWhatTheCompilerProduces() {
        val page = stagePageText()
        val program = publishedProgram(page)

        val expected = stageReadouts(program)
        assertEquals(
            setOf("tokens", "surface", "types", "typed", "core", "value"),
            expected.keys,
            "the renderer set and the marker set must not drift apart",
        )

        expected.forEach { (marker, rendered) ->
            assertEquals(
                rendered,
                markedFence(page, marker),
                "public/docs/fieldml_stages.md: the `$marker` readout is not what the compiler " +
                    "produces for the program in the page's first FieldML fence",
            )
        }
    }

    /**
     * `5 |> twice bump` and `twice bump 5` parse to the *same* surface tree, and the page shows it.
     *
     * The page's `surface-pipe` fence is one rendering with both spellings named above it, so the
     * claim the fence makes is precisely this equality. Asserting it here is what makes publishing
     * a single tree for two programs honest.
     */
    @Test
    fun pipeAndJuxtapositionShareOneSurfaceTree() {
        val piped = surfaceOf("5 |> twice bump")
        val juxtaposed = surfaceOf("twice bump 5")

        assertEquals(
            renderSurface(piped),
            renderSurface(juxtaposed),
            "`|>` is supposed to desugar to application, so the two spellings must have one tree",
        )

        val page = stagePageText()
        assertEquals(
            renderSurface(piped),
            markedFence(page, "surface-pipe"),
            "public/docs/fieldml_stages.md: the `surface-pipe` readout is stale",
        )
    }

    /**
     * The page claims `twice` is inferred polymorphic without an annotation. That is a claim about
     * a `'a` in the printed scheme, so it is checked as one rather than left to the eye.
     */
    @Test
    fun twiceIsInferredPolymorphicWithoutAnAnnotation() {
        val typed = typedOf("let twice f x = f (f x)\ntwice")
        val printer = TypePrinter()
        val signature = printer.printSignature(typed.type)

        assertTrue(
            "'" in signature,
            "`twice` should infer a type with a quantified variable in it, printed as $signature",
        )
        assertEquals("('a -> 'a) -> 'a -> 'a", signature)
    }

    /**
     * The page states where the front end stops for a program that does not type, and that the
     * earlier trees survive. Both halves are read off one [afield.ml.CompilationResult].
     */
    @Test
    fun aTypeErrorStopsAtInferenceAndKeepsTheEarlierTrees() {
        val result = FieldMLCompiler.compile("let bump n = n + 1\nbump \"five\"")

        assertTrue(!result.successful, "`bump \"five\"` must not compile")
        assertNotNull(result.tokens, "the token list survives a type error")
        assertNotNull(result.surface, "the surface tree survives a type error")
        assertEquals(null, result.core, "lowering must not run after an inference error")
    }

    /**
     * The whole pipeline, stage by stage, with nothing skipped — the shape the page's prose
     * describes, run as four separate calls rather than through [FieldMLCompiler.compile].
     *
     * This is the test that makes `Lexer`, `Parser`, `Inferencer` and `Lowerer` *evaluated* symbols
     * rather than merely documented ones, which is what `DocumentedApiEvaluationTest` asks of a name
     * a published page teaches.
     */
    @Test
    fun theFourStagesRunByHandAgreeWithTheCompilerFacade() {
        val program = publishedProgram(stagePageText())

        val lexed = Lexer(program).lex()
        val parsed = Parser(lexed.tokens).parse()
        val inferred = Inferencer().infer(assertNotNull(parsed.expr))
        val core = Lowerer().lower(assertNotNull(inferred.typed))

        val facade = FieldMLCompiler.compile(program)
        assertTrue(facade.successful, "the published program must compile")
        assertEquals(facade.tokens, lexed.tokens)
        assertEquals(facade.surface, parsed.expr)
        assertEquals(facade.typed, inferred.typed)
        assertEquals(facade.core, core)

        val value = Evaluator(FieldMLRuntimePrelude.values()).evaluate(core)
        assertEquals("7", renderFieldMLValue(value), "bump (bump 5)")
    }

    // ------------------------------------------------------------- the stages

    /** Each marker on the page mapped to the text that stage produces for [program]. */
    private fun stageReadouts(program: String): Map<String, String> {
        val result = FieldMLCompiler.compile(program)
        assertTrue(
            result.successful,
            "the published program must compile; diagnostics: ${result.diagnostics.map { it.message }}",
        )
        val tokens = assertNotNull(result.tokens)
        val surface = assertNotNull(result.surface)
        val typed = assertNotNull(result.typed)
        val core = assertNotNull(result.core)
        val value = Evaluator(FieldMLRuntimePrelude.values()).evaluate(core)

        return linkedMapOf(
            "tokens" to renderTokens(tokens),
            "surface" to renderSurface(surface),
            "types" to renderBindingTypes(typed),
            "typed" to renderTyped(typed),
            "core" to renderCore(core),
            "value" to "${renderFieldMLValue(value)} : ${TypePrinter().printSignature(typed.type)}",
        )
    }

    private fun surfaceOf(source: String): Expr {
        val parsed = Parser(Lexer(source).lex().tokens).parse()
        return assertNotNull(parsed.expr, "$source should parse")
    }

    private fun typedOf(source: String): TExpr {
        val result = FieldMLCompiler.compile(source)
        assertTrue(result.successful, "$source should compile: ${result.diagnostics.map { it.message }}")
        return assertNotNull(result.typed)
    }

    // ----------------------------------------------------------- the renderers

    /**
     * One line per token: where it started, its kind, and the exact text it was read from.
     *
     * The position is the token's start only. A reader who wants to see that the lexer drops trivia
     * can read the column jumps — there is no token between `let` at 1:1 and `twice` at 1:5 — and
     * `EOF` prints with empty text because it was not read from the source at all.
     *
     * Each line is [String.trimEnd]ed, which matters only for `EOF`: its text is empty, so the
     * padded kind column would leave trailing spaces on the page's last token line. A published
     * fence with trailing whitespace is one an editor or a formatter silently repairs, and this
     * comparison is exact — so the whitespace must not be there in the first place.
     */
    private fun renderTokens(tokens: List<afield.ml.lex.Token>): String {
        val width = tokens.maxOf { it.kind.name.length }
        return tokens.joinToString("\n") { token ->
            val at = "${token.range.start.line}:${token.range.start.column}"
            "${at.padEnd(6)}${token.kind.name.padEnd(width + 2)}${token.text}".trimEnd()
        }
    }

    /** The surface tree, two spaces per level, each node labelled with what it wrote down. */
    private fun renderSurface(expr: Expr, depth: Int = 0): String {
        val pad = "  ".repeat(depth)
        return when (expr) {
            is ELet -> {
                val params = if (expr.parameters.isEmpty()) "" else " " + expr.parameters.joinToString(" ")
                listOf(
                    "${pad}ELet ${expr.name}$params",
                    renderSurface(expr.value, depth + 1),
                    renderSurface(expr.body, depth + 1),
                ).joinToString("\n")
            }
            is ELambda -> listOf(
                "${pad}ELambda ${expr.parameters.joinToString(" ")}",
                renderSurface(expr.body, depth + 1),
            ).joinToString("\n")
            is ECall -> listOf(
                "${pad}ECall",
                renderSurface(expr.function, depth + 1),
                renderSurface(expr.argument, depth + 1),
            ).joinToString("\n")
            is EBinary -> listOf(
                "${pad}EBinary ${expr.op.name}",
                renderSurface(expr.left, depth + 1),
                renderSurface(expr.right, depth + 1),
            ).joinToString("\n")
            is EVar -> "${pad}EVar ${expr.name}"
            is EInt -> "${pad}EInt ${expr.value}"
            else -> error("renderSurface has no case for ${expr::class.simpleName}; see this file's KDoc")
        }
    }

    /**
     * What inference concluded about each `let`, printed as a signature.
     *
     * One [TypePrinter] for the whole walk, so a `'a` in one line means the same variable as a `'a`
     * in the next. Two printers would each start again at `'a` and the listing would lie about
     * which variables are shared.
     */
    private fun renderBindingTypes(typed: TExpr): String {
        val printer = TypePrinter()
        val lines = mutableListOf<String>()
        fun walk(node: TExpr) {
            if (node is TExprLet) {
                lines += "${node.name} : ${printer.printSignature(node.value.type)}"
                walk(node.body)
            }
        }
        walk(typed)
        lines += "(result) : ${printer.printSignature(typed.type)}"
        return lines.joinToString("\n")
    }

    /** The typed tree: the same shape as the surface tree with an inferred type on every node. */
    private fun renderTyped(expr: TExpr, depth: Int = 0, printer: TypePrinter = TypePrinter()): String {
        val pad = "  ".repeat(depth)
        val type = printer.printSignature(expr.type)
        return when (expr) {
            is TExprLet -> listOf(
                "${pad}TExprLet ${expr.name}${expr.parameters.joinToString("") { " ${it.first}" }} : $type",
                renderTyped(expr.value, depth + 1, printer),
                renderTyped(expr.body, depth + 1, printer),
            ).joinToString("\n")
            is TExprLambda -> listOf(
                "${pad}TExprLambda ${expr.parameters.joinToString(" ") { it.first }} : $type",
                renderTyped(expr.body, depth + 1, printer),
            ).joinToString("\n")
            is TExprCall -> listOf(
                "${pad}TExprCall : $type",
                renderTyped(expr.function, depth + 1, printer),
                renderTyped(expr.argument, depth + 1, printer),
            ).joinToString("\n")
            is TExprBinary -> listOf(
                "${pad}TExprBinary ${expr.op.name} : $type",
                renderTyped(expr.left, depth + 1, printer),
                renderTyped(expr.right, depth + 1, printer),
            ).joinToString("\n")
            is TExprVar -> "${pad}TExprVar ${expr.name} : $type"
            is TExprInt -> "${pad}TExprInt ${expr.value} : $type"
            else -> error("renderTyped has no case for ${expr::class.simpleName}; see this file's KDoc")
        }
    }

    /**
     * The core tree: types kept, ranges dropped, and every multi-parameter lambda curried into
     * nested one-parameter [CLam]s. The curry is the whole point of showing this stage, so the
     * parameter goes on the node label.
     */
    private fun renderCore(expr: CoreExpr, depth: Int = 0, printer: TypePrinter = TypePrinter()): String {
        val pad = "  ".repeat(depth)
        val type = printer.printSignature(expr.type)
        return when (expr) {
            is CLet -> listOf(
                "${pad}CLet ${expr.name}${if (expr.recursive) " (recursive)" else ""} : $type",
                renderCore(expr.value, depth + 1, printer),
                renderCore(expr.body, depth + 1, printer),
            ).joinToString("\n")
            is CLam -> listOf(
                "${pad}CLam ${expr.parameter} : $type",
                renderCore(expr.body, depth + 1, printer),
            ).joinToString("\n")
            is CApp -> listOf(
                "${pad}CApp : $type",
                renderCore(expr.function, depth + 1, printer),
                renderCore(expr.argument, depth + 1, printer),
            ).joinToString("\n")
            is CBinary -> listOf(
                "${pad}CBinary ${expr.op.name} : $type",
                renderCore(expr.left, depth + 1, printer),
                renderCore(expr.right, depth + 1, printer),
            ).joinToString("\n")
            is CVar -> "${pad}CVar ${expr.name} : $type"
            is CInt -> "${pad}CInt ${expr.value} : $type"
            else -> error("renderCore has no case for ${expr::class.simpleName}; see this file's KDoc")
        }
    }

    // ------------------------------------------------------- reading the page

    private fun stagePageText(): String {
        val path = Path.of(PAGE)
        assertTrue(path.toFile().isFile, "$PAGE is missing")
        return path.toFile().readText()
    }

    /**
     * The program the page walks: the body of its first `data-lang=fieldml` fence.
     *
     * Deliberately positional rather than marked. The page's opening is the program, and a page
     * whose first FieldML fence were something else would not be the page this test describes.
     */
    private fun publishedProgram(page: String): String {
        val fence = Regex("""(?ms)^```diffstatic(?:_runner)?\s+\{[^}]*data-lang=fieldml[^}]*}\s*\n(.*?)^```""")
        val first = assertNotNull(fence.find(page), "$PAGE: expected a FieldML fence")
        return first.groupValues[1].trim()
    }

    /**
     * The body of the fence that follows `<!-- stage:NAME -->`.
     *
     * Marked with a comment rather than located by heading, because a heading can be reworded — and
     * renaming a section was a change no guard in the tree caught until `check_doc_site_links.py` gained
     * its `#fragment` check on 2026-09-18. A marker is invisible to a
     * reader and stable against every edit that is not about this test.
     */
    private fun markedFence(page: String, marker: String): String {
        val pattern = Regex(
            """(?ms)^<!--\s*stage:${Regex.escape(marker)}\s*-->\s*\n```diffstatic\s+\{[^}]*}\s*\n(.*?)^```""",
        )
        val match = assertNotNull(
            pattern.find(page),
            "$PAGE: no fence marked `<!-- stage:$marker -->`",
        )
        return match.groupValues[1].trimEnd('\n')
    }

    private companion object {
        const val PAGE = "public/docs/fieldml_stages.md"
    }
}
