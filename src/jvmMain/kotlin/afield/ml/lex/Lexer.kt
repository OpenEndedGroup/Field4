package afield.ml.lex

import afield.ml.diag.Diagnostic
import afield.ml.diag.Severity
import afield.ml.diag.SourcePos
import afield.ml.diag.SourceRange

/**
 * FieldML's first stage: source text in, a flat list of [Token]s out, and a diagnostic for anything
 * that could not be read.
 *
 * The scan never stops early. A character the lexer cannot use costs one diagnostic and no token,
 * and the scan carries on, so a typo halfway down a box does not hide what is below it. Whether the
 * *compile* carries on is a separate decision, made by `FieldMLCompiler`, which does not reach the
 * parser at all once lexing has reported an error.
 *
 * Trivia is skipped rather than emitted: `//` to the end of the line, and `(* … *)` blocks, which
 * nest by depth count, so a region commented out this way can contain comments of its own. An
 * unterminated block is reported at the position it was opened.
 *
 * The shapes a literal can take are all decided here. A number is digits, and a `.` opens a
 * fraction only when a digit follows it, which leaves `.` free to be the field-access token and
 * makes `1.` an `INT` and then a `DOT`; there is no exponent form, and a leading `-` is always a
 * token of its own rather than part of the literal. An identifier starts with a letter or `_` and
 * may carry `'` after that. A string runs to the next unescaped `"`, and one that reaches the end
 * of the source first is reported rather than closed. `&`, `:` and `!` are errors on their own,
 * since only `&&`, `::` and `!=` exist, and the first two say so in the message.
 */

/**
 * The whole of what one scan produced.
 *
 * Both lists are complete: [tokens] ends with `EOF` even when [diagnostics] is not empty, because
 * the lexer reports and continues rather than returning at the first problem.
 */
data class LexResult(
    /** The tokens, in source order, ending with `EOF`. Trivia is not among them. */
    val tokens: List<Token>,
    /** One entry per thing the lexer could not read, in source order. All of them are errors. */
    val diagnostics: List<Diagnostic>,
)

/**
 * A single-use scanner over one string.
 *
 * The cursor, the line and the column are instance state and [lex] does not reset them, so one
 * instance scans one source once: a second call finds itself already at the end and appends a
 * second `EOF` to the same list. Build another instance instead.
 */
class Lexer(private val source: String) {
    private var index = 0
    private var line = 1
    private var column = 1
    private val tokens = mutableListOf<Token>()
    private val diagnostics = mutableListOf<Diagnostic>()

    /** Scan to the end of the source, appending the `EOF` token. Reports rather than throws. */
    fun lex(): LexResult {
        while (!atEnd()) {
            skipTrivia()
            if (!atEnd()) lexToken()
        }
        val end = pos()
        tokens += Token(TokenKind.EOF, "", SourceRange(end, end))
        return LexResult(tokens, diagnostics)
    }

    private fun lexToken() {
        val start = pos()
        val c = peek()
        when {
            c.isLetter() || c == '_' -> lexIdentifier(start)
            c.isDigit() -> lexNumber(start)
            c == '"' -> lexString(start)
            else -> when (c) {
                '(' -> single(TokenKind.LPAREN, start)
                ')' -> single(TokenKind.RPAREN, start)
                '[' -> single(TokenKind.LBRACKET, start)
                ']' -> single(TokenKind.RBRACKET, start)
                '{' -> single(TokenKind.LBRACE, start)
                '}' -> single(TokenKind.RBRACE, start)
                ',' -> single(TokenKind.COMMA, start)
                ';' -> single(TokenKind.SEMICOLON, start)
                '|' -> {
                    advance()
                    when {
                        match('>') -> emit(TokenKind.PIPE_FORWARD, start)
                        match('|') -> emit(TokenKind.BARBAR, start)
                        else -> emit(TokenKind.BAR, start)
                    }
                }
                '&' -> {
                    advance()
                    if (match('&')) emit(TokenKind.AMPAMP, start) else errorAt(start, "Unexpected character '&' (did you mean '&&'?)")
                }
                ':' -> {
                    advance()
                    if (match(':')) emit(TokenKind.COLONCOLON, start) else errorAt(start, "Unexpected character ':' (did you mean '::'?)")
                }
                '.' -> single(TokenKind.DOT, start)
                '+' -> {
                    advance()
                    if (match('+')) emit(TokenKind.PLUSPLUS, start) else emit(TokenKind.PLUS, start)
                }
                '*' -> single(TokenKind.STAR, start)
                '/' -> single(TokenKind.SLASH, start)
                '-' -> {
                    advance()
                    if (match('>')) emit(TokenKind.ARROW, start) else emit(TokenKind.MINUS, start)
                }
                '=' -> {
                    advance()
                    if (match('=')) emit(TokenKind.EQEQ, start) else emit(TokenKind.EQ, start)
                }
                '!' -> {
                    advance()
                    if (match('=')) emit(TokenKind.NEQ, start) else errorAt(start, "Unexpected character '!'")
                }
                '<' -> {
                    advance()
                    if (match('=')) emit(TokenKind.LTE, start) else emit(TokenKind.LT, start)
                }
                '>' -> {
                    advance()
                    if (match('=')) emit(TokenKind.GTE, start) else emit(TokenKind.GT, start)
                }
                else -> {
                    advance()
                    errorAt(start, "Unexpected character '$c'")
                }
            }
        }
    }

    private fun skipTrivia() {
        while (!atEnd()) {
            when {
                peek().isWhitespace() -> advance()
                peek() == '/' && peek(1) == '/' -> {
                    while (!atEnd() && peek() != '\n') advance()
                }
                peek() == '(' && peek(1) == '*' -> skipBlockComment()
                else -> return
            }
        }
    }

    private fun skipBlockComment() {
        val start = pos()
        advance()
        advance()
        var depth = 1
        while (!atEnd() && depth > 0) {
            when {
                peek() == '(' && peek(1) == '*' -> {
                    advance()
                    advance()
                    depth++
                }
                peek() == '*' && peek(1) == ')' -> {
                    advance()
                    advance()
                    depth--
                }
                else -> advance()
            }
        }
        if (depth != 0) errorAt(start, "Unterminated block comment")
    }

    private fun lexIdentifier(start: SourcePos) {
        while (!atEnd() && (peek().isLetterOrDigit() || peek() == '_' || peek() == '\'')) advance()
        val text = source.substring(start.index, index)
        val kind = when (text) {
            "let" -> TokenKind.LET
            "type" -> TokenKind.TYPE
            "in" -> TokenKind.IN
            "if" -> TokenKind.IF
            "then" -> TokenKind.THEN
            "else" -> TokenKind.ELSE
            "fun" -> TokenKind.FUN
            "match" -> TokenKind.MATCH
            "with" -> TokenKind.WITH
            "true" -> TokenKind.TRUE
            "false" -> TokenKind.FALSE
            else -> TokenKind.IDENT
        }
        emit(kind, start)
    }

    private fun lexNumber(start: SourcePos) {
        while (!atEnd() && peek().isDigit()) advance()
        var isFloat = false
        if (peek() == '.' && peek(1).isDigit()) {
            isFloat = true
            advance()
            while (!atEnd() && peek().isDigit()) advance()
        }
        emit(if (isFloat) TokenKind.FLOAT else TokenKind.INT, start)
    }

    private fun lexString(start: SourcePos) {
        advance()
        while (!atEnd() && peek() != '"') {
            if (peek() == '\\') {
                advance()
                if (!atEnd()) advance()
            } else {
                advance()
            }
        }
        if (atEnd()) {
            errorAt(start, "Unterminated string literal")
            return
        }
        advance()
        emit(TokenKind.STRING, start)
    }

    private fun single(kind: TokenKind, start: SourcePos) {
        advance()
        emit(kind, start)
    }

    private fun emit(kind: TokenKind, start: SourcePos) {
        val end = pos()
        tokens += Token(kind, source.substring(start.index, end.index), SourceRange(start, end))
    }

    private fun errorAt(start: SourcePos, message: String) {
        diagnostics += Diagnostic(Severity.Error, message, SourceRange(start, pos()))
    }

    private fun match(expected: Char): Boolean {
        if (atEnd() || peek() != expected) return false
        advance()
        return true
    }

    private fun peek(offset: Int = 0): Char = source.getOrNull(index + offset) ?: '\u0000'
    private fun atEnd(): Boolean = index >= source.length

    private fun advance(): Char {
        val c = source[index++]
        if (c == '\n') {
            line++
            column = 1
        } else {
            column++
        }
        return c
    }

    private fun pos(): SourcePos = SourcePos(index, line, column)
}
