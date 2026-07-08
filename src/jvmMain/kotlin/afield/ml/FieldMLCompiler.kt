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

data class CompilationResult(
    val source: String,
    val tokens: List<Token>?,
    val surface: Expr?,
    val typed: TExpr?,
    val core: CoreExpr?,
    val diagnostics: List<Diagnostic>,
) {
    val successful: Boolean get() = diagnostics.none { it.severity == Severity.Error } && core != null
}

object FieldMLCompiler {
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
