package afield.ml

import afield.ml.ast.Expr
import afield.ml.core.CoreExpr
import afield.ml.core.Lowerer
import afield.ml.diag.Diagnostic
import afield.ml.diag.Severity
import afield.ml.lex.Lexer
import afield.ml.lex.Token
import afield.ml.parse.Parser
import afield.ml.type.Inferencer
import afield.ml.type.TExpr

/**
 * FieldML's front end behind one call: source text in, and whatever its four stages managed to
 * produce out.
 *
 * [FieldMLCompiler] runs the lexer, the parser, the inferencer and the lowerer in turn, stopping at
 * the first one to report an error. What the earlier stages produced is kept either way, so a
 * program that does not compile still carries a token list, usually a surface tree, and sometimes a
 * typed tree. [FieldMLCompletionEngine] is built on that: it compiles a half-written line and reads
 * the names in scope off the typed tree, or the constructor declarations off the surface tree when
 * inference has given up.
 *
 * Each stage lives in its own package under `afield.ml`, and this file is where the order they go
 * in is written down.
 */

/**
 * How far a compile got and what it found: one field per stage of the front end, null from the
 * stage that failed onwards.
 *
 * A null therefore means "not reached", and a non-null tree is not a claim that the program is
 * correct: a stage that produced a tree *and* reported an error fills its field and still stops the
 * run. Ask [successful] before evaluating anything; read the fields directly when a partial answer
 * is better than none.
 */
data class CompilationResult(
    /** The text that was compiled. A diagnostic's range indexes into it. */
    val source: String,
    /** Lexer output. Non-null unless lexing itself failed. */
    val tokens: List<Token>?,
    /** Parser output: the surface tree, one node per piece of written syntax. */
    val surface: Expr?,
    /** Inferencer output: the same shape as [surface] with a type on every node. */
    val typed: TExpr?,
    /** Lowerer output: the curried tree the evaluator runs. Non-null exactly when the compile worked. */
    val core: CoreExpr?,
    /** Everything every stage that ran had to say, in stage order. */
    val diagnostics: List<Diagnostic>,
) {
    /** True when the whole front end ran and nothing reported an error, so [core] can be evaluated. */
    val successful: Boolean get() = diagnostics.none { it.severity == Severity.Error } && core != null
}

/** Lex, parse, infer, lower. The entry point for the box runtime, the completion engine and the tests. */
object FieldMLCompiler {
    /**
     * Compile [source], reporting errors rather than throwing them.
     *
     * Returns as soon as a stage reports an error, with the trees built so far and the diagnostics
     * from every stage that ran.
     */
    fun compile(source: String): CompilationResult {
        val lexed = Lexer(source).lex()
        if (lexed.diagnostics.any { it.severity == Severity.Error }) {
            return CompilationResult(source, lexed.tokens, null, null, null, lexed.diagnostics)
        }

        val parsed = Parser(lexed.tokens).parse()
        if (parsed.expr == null || parsed.diagnostics.any { it.severity == Severity.Error }) {
            return CompilationResult(source, lexed.tokens, parsed.expr, null, null, lexed.diagnostics + parsed.diagnostics)
        }

        val inferred = Inferencer().infer(parsed.expr)
        if (inferred.typed == null || inferred.diagnostics.any { it.severity == Severity.Error }) {
            return CompilationResult(
                source,
                lexed.tokens,
                parsed.expr,
                inferred.typed,
                null,
                lexed.diagnostics + parsed.diagnostics + inferred.diagnostics,
            )
        }

        val core = Lowerer().lower(inferred.typed)
        return CompilationResult(
            source,
            lexed.tokens,
            parsed.expr,
            inferred.typed,
            core,
            lexed.diagnostics + parsed.diagnostics + inferred.diagnostics,
        )
    }
}
