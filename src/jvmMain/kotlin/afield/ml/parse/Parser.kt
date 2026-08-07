package afield.ml.parse

import afield.ml.ast.BinaryOp
import afield.ml.ast.ConstructorDecl
import afield.ml.ast.EBinary
import afield.ml.ast.EBool
import afield.ml.ast.ECall
import afield.ml.ast.EField
import afield.ml.ast.EFloat
import afield.ml.ast.EIf
import afield.ml.ast.EInt
import afield.ml.ast.ELambda
import afield.ml.ast.ELet
import afield.ml.ast.EList
import afield.ml.ast.EMatch
import afield.ml.ast.ENegate
import afield.ml.ast.ERecord
import afield.ml.ast.EString
import afield.ml.ast.ETuple
import afield.ml.ast.ETypeDecl
import afield.ml.ast.EVar
import afield.ml.ast.Expr
import afield.ml.ast.MatchCase
import afield.ml.ast.PBool
import afield.ml.ast.PCons
import afield.ml.ast.PConstructor
import afield.ml.ast.PInt
import afield.ml.ast.PList
import afield.ml.ast.PString
import afield.ml.ast.PTuple
import afield.ml.ast.PVar
import afield.ml.ast.PWildcard
import afield.ml.ast.Pattern
import afield.ml.ast.RecordField
import afield.ml.ast.TypeDecl
import afield.ml.ast.TypeRef
import afield.ml.ast.TypeRefName
import afield.ml.diag.CompileAbort
import afield.ml.diag.Diagnostic
import afield.ml.diag.Severity
import afield.ml.diag.SourceRange
import afield.ml.lex.Token
import afield.ml.lex.TokenKind

data class ParseResult(
    val expr: Expr?,
    val diagnostics: List<Diagnostic>,
)

class Parser(private val tokens: List<Token>) {
    private var cursor = 0
    private val diagnostics = mutableListOf<Diagnostic>()

    fun parse(): ParseResult =
        try {
            val expr = parseTopLevelExpression()
            expect(TokenKind.EOF, "Expected end of input")
            ParseResult(expr, diagnostics)
        } catch (abort: CompileAbort) {
            diagnostics += abort.diagnostic
            ParseResult(null, diagnostics)
        }

    private fun parseTopLevelExpression(): Expr {
        if (check(TokenKind.TYPE)) return parseTypeDecl()
        if (!check(TokenKind.LET)) return parseExpression(Stop.eof())
        val start = expect(TokenKind.LET, "Expected 'let'")
        val name = expect(TokenKind.IDENT, "Expected a binding name")
        val parameters = mutableListOf<String>()
        while (check(TokenKind.IDENT)) parameters += advance().text
        expect(TokenKind.EQ, "Expected '=' in let binding")
        val value = parseExpression(Stop.topLevelBinding(start) + Stop.until(TokenKind.IN))
        if (match(TokenKind.IN)) {
            val body = parseExpression(Stop.eof())
            return ELet(name.text, parameters, value, body, SourceRange(start.range.start, body.range.end))
        }
        val body = if (check(TokenKind.EOF)) {
            EVar("Unit", value.range)
        } else {
            parseTopLevelExpression()
        }
        return ELet(name.text, parameters, value, body, SourceRange(start.range.start, body.range.end))
    }

    private fun parseTypeDecl(): Expr {
        val start = expect(TokenKind.TYPE, "Expected 'type'")
        val name = expect(TokenKind.IDENT, "Expected a type name")
        val parameters = mutableListOf<String>()
        while (check(TokenKind.IDENT)) {
            parameters += advance().text
        }
        expect(TokenKind.EQ, "Expected '=' in type declaration")

        val constructors = mutableListOf<ConstructorDecl>()
        do {
            match(TokenKind.BAR)
            constructors += parseConstructorDecl(start)
        } while (check(TokenKind.BAR))

        if (constructors.isEmpty()) abort("Expected at least one constructor", name.range)
        val declRange = SourceRange(start.range.start, constructors.last().range.end)
        val body = if (check(TokenKind.EOF)) {
            abort("Type declarations must be followed by an expression", current().range)
        } else {
            parseTopLevelExpression()
        }
        return ETypeDecl(
            TypeDecl(name.text, parameters, constructors, declRange),
            body,
            SourceRange(start.range.start, body.range.end),
        )
    }

    private fun parseConstructorDecl(typeStart: Token): ConstructorDecl {
        val name = expect(TokenKind.IDENT, "Expected a constructor name")
        if (!name.text.first().isUpperCase()) {
            abort("Constructor names must start with an uppercase letter", name.range)
        }
        val arguments = mutableListOf<TypeRef>()
        while (!check(TokenKind.BAR) && !check(TokenKind.EOF) && !isTopLevelBoundary(typeStart)) {
            arguments += parseTypeRef()
        }
        val end = arguments.lastOrNull()?.range ?: name.range
        return ConstructorDecl(name.text, arguments, SourceRange(name.range.start, end.end))
    }

    private fun parseTypeRef(): TypeRef {
        val name = expect(TokenKind.IDENT, "Expected a type name")
        return TypeRefName(name.text, name.range)
    }

    private fun parseExpression(stop: Stop): Expr =
        when {
            stop.matches(current(), this) -> abort("Expected an expression", current().range)
            check(TokenKind.LET) -> parseLet(stop)
            check(TokenKind.IF) -> parseIf(stop)
            check(TokenKind.FUN) -> parseLambda(stop)
            check(TokenKind.MATCH) -> parseMatch(stop)
            else -> parsePipe(stop)
        }

    private fun parsePipe(stop: Stop): Expr {
        var left = parseBinary(0, stop)
        while (!stop.matches(current(), this) && match(TokenKind.PIPE_FORWARD)) {
            val right = parseBinary(0, stop)
            left = ECall(right, left, SourceRange.spanning(left.range, right.range))
        }
        return left
    }

    private fun parseLet(stop: Stop): Expr {
        val start = expect(TokenKind.LET, "Expected 'let'")
        val name = expect(TokenKind.IDENT, "Expected a binding name")
        val parameters = mutableListOf<String>()
        while (check(TokenKind.IDENT)) parameters += advance().text
        expect(TokenKind.EQ, "Expected '=' in let binding")
        val value = parseExpression(stop + Stop.until(TokenKind.IN))
        if (!match(TokenKind.IN)) {
            abort("Expected 'in' after local let binding", current().range)
        }
        val body = parseExpression(stop)
        return ELet(name.text, parameters, value, body, SourceRange(start.range.start, body.range.end))
    }

    private fun parseIf(stop: Stop): Expr {
        val start = expect(TokenKind.IF, "Expected 'if'")
        val condition = parseExpression(stop + Stop.until(TokenKind.THEN))
        expect(TokenKind.THEN, "Expected 'then'")
        val thenBranch = parseExpression(stop + Stop.until(TokenKind.ELSE))
        expect(TokenKind.ELSE, "Expected 'else'")
        val elseBranch = parseExpression(stop)
        return EIf(condition, thenBranch, elseBranch, SourceRange(start.range.start, elseBranch.range.end))
    }

    private fun parseLambda(stop: Stop): Expr {
        val start = expect(TokenKind.FUN, "Expected 'fun'")
        val parameters = mutableListOf<String>()
        parameters += expect(TokenKind.IDENT, "Expected a lambda parameter").text
        while (check(TokenKind.IDENT)) parameters += advance().text
        expect(TokenKind.ARROW, "Expected '->' after lambda parameters")
        val body = parseExpression(stop)
        return ELambda(parameters, body, SourceRange(start.range.start, body.range.end))
    }

    private fun parseMatch(stop: Stop): Expr {
        val start = expect(TokenKind.MATCH, "Expected 'match'")
        val scrutinee = parseExpression(stop + Stop.until(TokenKind.WITH))
        expect(TokenKind.WITH, "Expected 'with'")
        val cases = mutableListOf<MatchCase>()
        while (!stop.matches(current(), this) && !check(TokenKind.EOF)) {
            match(TokenKind.BAR)
            val pattern = parsePattern()
            expect(TokenKind.ARROW, "Expected '->' after match pattern")
            val body = parseExpression(stop + Stop.until(TokenKind.BAR))
            cases += MatchCase(pattern, body, SourceRange(pattern.range.start, body.range.end))
            if (!check(TokenKind.BAR)) break
        }
        if (cases.isEmpty()) abort("Expected at least one match case", current().range)
        return EMatch(scrutinee, cases, SourceRange(start.range.start, cases.last().range.end))
    }

    private fun parsePattern(): Pattern {
        val head = parseAppliedPattern()
        if (!match(TokenKind.COLONCOLON)) return head
        // Right associative: `a :: b :: rest`.
        val tail = parsePattern()
        return PCons(head, tail, SourceRange(head.range.start, tail.range.end))
    }

    private fun parseAppliedPattern(): Pattern {
        val first = parsePatternAtom()
        if (first is PConstructor) {
            val arguments = mutableListOf<Pattern>()
            while (startsPatternAtom(current().kind)) {
                arguments += parsePatternAtom()
            }
            if (arguments.isNotEmpty()) {
                return PConstructor(first.name, arguments, SourceRange(first.range.start, arguments.last().range.end))
            }
        }
        return first
    }

    private fun parsePatternAtom(): Pattern {
        val token = current()
        return when {
            match(TokenKind.INT) -> PInt(token.text.toLong(), token.range)
            match(TokenKind.TRUE) -> PBool(true, token.range)
            match(TokenKind.FALSE) -> PBool(false, token.range)
            match(TokenKind.STRING) -> PString(decodeString(token.text), token.range)
            match(TokenKind.IDENT) -> when {
                token.text == "_" -> PWildcard(token.range)
                token.text.first().isUpperCase() -> PConstructor(token.text, emptyList(), token.range)
                else -> PVar(token.text, token.range)
            }
            match(TokenKind.LPAREN) -> parseTuplePattern(token)
            match(TokenKind.LBRACKET) -> parseListPattern(token)
            else -> abort("Expected a pattern", token.range)
        }
    }

    private fun parseTuplePattern(open: Token): Pattern {
        val first = parsePattern()
        if (!match(TokenKind.COMMA)) {
            expect(TokenKind.RPAREN, "Expected ')' after pattern")
            return first
        }
        val elements = mutableListOf(first)
        do {
            elements += parsePattern()
        } while (match(TokenKind.COMMA))
        val close = expect(TokenKind.RPAREN, "Expected ')' after tuple pattern")
        return PTuple(elements, SourceRange(open.range.start, close.range.end))
    }

    private fun parseListPattern(open: Token): Pattern {
        val elements = mutableListOf<Pattern>()
        if (!check(TokenKind.RBRACKET)) {
            do {
                elements += parsePattern()
            } while (match(TokenKind.COMMA))
        }
        val close = expect(TokenKind.RBRACKET, "Expected ']' after list pattern")
        return PList(elements, SourceRange(open.range.start, close.range.end))
    }

    private fun parseBinary(minPrecedence: Int, stop: Stop): Expr {
        var left = parseUnary(stop)
        while (!stop.matches(current(), this)) {
            val op = binaryOp(current().kind) ?: break
            val precedence = precedence(op)
            if (precedence < minPrecedence) break
            advance()
            // `::` is right associative so `a :: b :: rest` builds the list the way it reads.
            val right = parseBinary(if (op == BinaryOp.CONS) precedence else precedence + 1, stop)
            left = EBinary(op, left, right, SourceRange.spanning(left.range, right.range))
        }
        return left
    }

    private fun parseUnary(stop: Stop): Expr {
        if (!stop.matches(current(), this) && check(TokenKind.MINUS)) {
            val start = advance()
            val value = parseUnary(stop)
            return ENegate(value, SourceRange(start.range.start, value.range.end))
        }
        return parseApplication(stop)
    }

    private fun parseApplication(stop: Stop): Expr {
        var expr = parsePostfix(stop)
        while (!stop.matches(current(), this) && startsAtom(current().kind)) {
            val argument = parsePostfix(stop)
            expr = ECall(expr, argument, SourceRange.spanning(expr.range, argument.range))
        }
        return expr
    }

    private fun parsePostfix(stop: Stop): Expr {
        var expr = parseAtom(stop)
        while (!stop.matches(current(), this) && match(TokenKind.DOT)) {
            val field = expect(TokenKind.IDENT, "Expected field name after '.'")
            expr = EField(expr, field.text, SourceRange(expr.range.start, field.range.end))
        }
        return expr
    }

    private fun parseAtom(stop: Stop): Expr {
        if (stop.matches(current(), this)) abort("Expected an expression", current().range)
        val token = current()
        return when {
            match(TokenKind.INT) -> EInt(token.text.toLong(), token.range)
            match(TokenKind.FLOAT) -> EFloat(token.text.toDouble(), token.range)
            match(TokenKind.TRUE) -> EBool(true, token.range)
            match(TokenKind.FALSE) -> EBool(false, token.range)
            match(TokenKind.STRING) -> EString(decodeString(token.text), token.range)
            match(TokenKind.IDENT) -> EVar(token.text, token.range)
            match(TokenKind.LPAREN) -> parseParenthesizedOrTuple(token)
            match(TokenKind.LBRACKET) -> parseList(token)
            match(TokenKind.LBRACE) -> parseRecord(token)
            else -> abort("Expected an expression", token.range)
        }
    }

    private fun parseParenthesizedOrTuple(open: Token): Expr {
        // `()` is the unit value. It had no spelling until the host bridge needed one: `hostCall`'s
        // n-ary form reads its arguments out of a tuple, and a call with no arguments at all has to be
        // writable as something.
        if (check(TokenKind.RPAREN)) {
            val close = advance()
            return EVar("Unit", SourceRange(open.range.start, close.range.end))
        }
        val first = parseExpression(Stop.until(TokenKind.COMMA) + Stop.until(TokenKind.RPAREN))
        if (!match(TokenKind.COMMA)) {
            expect(TokenKind.RPAREN, "Expected ')'")
            return first
        }
        val elements = mutableListOf(first)
        do {
            elements += parseExpression(Stop.until(TokenKind.COMMA) + Stop.until(TokenKind.RPAREN))
        } while (match(TokenKind.COMMA))
        val close = expect(TokenKind.RPAREN, "Expected ')' after tuple")
        return ETuple(elements, SourceRange(open.range.start, close.range.end))
    }

    private fun parseList(open: Token): Expr {
        val elements = mutableListOf<Expr>()
        if (!check(TokenKind.RBRACKET)) {
            do {
                elements += parseExpression(Stop.until(TokenKind.COMMA) + Stop.until(TokenKind.RBRACKET))
            } while (match(TokenKind.COMMA))
        }
        val close = expect(TokenKind.RBRACKET, "Expected ']' after list")
        return EList(elements, SourceRange(open.range.start, close.range.end))
    }

    private fun parseRecord(open: Token): Expr {
        val fields = mutableListOf<RecordField>()
        while (!check(TokenKind.RBRACE)) {
            val name = expect(TokenKind.IDENT, "Expected record field name")
            expect(TokenKind.EQ, "Expected '=' after record field name")
            val value = parseExpression(Stop.recordField(name.range.start.line))
            fields += RecordField(name.text, value, SourceRange(name.range.start, value.range.end))
            match(TokenKind.COMMA) || match(TokenKind.SEMICOLON)
        }
        val close = expect(TokenKind.RBRACE, "Expected '}'")
        val duplicate = fields.groupBy { it.name }.values.firstOrNull { it.size > 1 }?.last()
        if (duplicate != null) abort("Duplicate record field '${duplicate.name}'", duplicate.range)
        return ERecord(fields, SourceRange(open.range.start, close.range.end))
    }

    private fun startsAtom(kind: TokenKind): Boolean =
        kind in setOf(
            TokenKind.IDENT,
            TokenKind.INT,
            TokenKind.FLOAT,
            TokenKind.STRING,
            TokenKind.TRUE,
            TokenKind.FALSE,
            TokenKind.LPAREN,
            TokenKind.LBRACKET,
            TokenKind.LBRACE,
            TokenKind.MATCH,
        )

    private fun startsPatternAtom(kind: TokenKind): Boolean =
        kind in setOf(
            TokenKind.IDENT,
            TokenKind.INT,
            TokenKind.STRING,
            TokenKind.TRUE,
            TokenKind.FALSE,
            TokenKind.LPAREN,
            TokenKind.LBRACKET,
        )

    private fun binaryOp(kind: TokenKind): BinaryOp? =
        when (kind) {
            TokenKind.PLUS -> BinaryOp.ADD
            TokenKind.PLUSPLUS -> BinaryOp.CONCAT
            TokenKind.MINUS -> BinaryOp.SUB
            TokenKind.STAR -> BinaryOp.MUL
            TokenKind.SLASH -> BinaryOp.DIV
            TokenKind.COLONCOLON -> BinaryOp.CONS
            TokenKind.LT -> BinaryOp.LT
            TokenKind.LTE -> BinaryOp.LTE
            TokenKind.GT -> BinaryOp.GT
            TokenKind.GTE -> BinaryOp.GTE
            TokenKind.EQEQ -> BinaryOp.EQ
            TokenKind.NEQ -> BinaryOp.NEQ
            TokenKind.AMPAMP -> BinaryOp.AND
            TokenKind.BARBAR -> BinaryOp.OR
            else -> null
        }

    private fun precedence(op: BinaryOp): Int =
        when (op) {
            BinaryOp.MUL, BinaryOp.DIV -> 6
            BinaryOp.ADD, BinaryOp.SUB, BinaryOp.CONCAT -> 5
            BinaryOp.CONS -> 4
            BinaryOp.LT, BinaryOp.LTE, BinaryOp.GT, BinaryOp.GTE, BinaryOp.EQ, BinaryOp.NEQ -> 3
            BinaryOp.AND -> 2
            BinaryOp.OR -> 1
        }

    private fun decodeString(text: String): String {
        val raw = text.substring(1, text.length - 1)
        val out = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val c = raw[i++]
            if (c != '\\' || i >= raw.length) {
                out.append(c)
            } else {
                out.append(
                    when (val escaped = raw[i++]) {
                        'n' -> '\n'
                        'r' -> '\r'
                        't' -> '\t'
                        '"' -> '"'
                        '\\' -> '\\'
                        else -> escaped
                    },
                )
            }
        }
        return out.toString()
    }

    private fun match(kind: TokenKind): Boolean {
        if (!check(kind)) return false
        advance()
        return true
    }

    private fun expect(kind: TokenKind, message: String): Token {
        if (check(kind)) return advance()
        abort("$message, found ${current().kind}", current().range)
    }

    private fun abort(message: String, range: SourceRange): Nothing {
        throw CompileAbort(Diagnostic(Severity.Error, message, range))
    }

    private fun check(kind: TokenKind): Boolean = current().kind == kind
    private fun current(): Token = tokens[cursor.coerceAtMost(tokens.lastIndex)]
    private fun peek(): Token = tokens[(cursor + 1).coerceAtMost(tokens.lastIndex)]
    private fun advance(): Token = current().also { cursor++ }
    private fun isTopLevelBoundary(start: Token): Boolean =
        current().range.start.line > start.range.start.line &&
            current().range.start.column <= start.range.start.column &&
            current().kind !in setOf(TokenKind.BAR, TokenKind.EOF)

    private data class Stop(
        val predicates: List<(Token, Parser) -> Boolean>,
    ) {
        fun matches(token: Token, parser: Parser): Boolean = predicates.any { it(token, parser) }
        operator fun plus(other: Stop): Stop = Stop(predicates + other.predicates)

        companion object {
            fun eof(): Stop = until(TokenKind.EOF)

            fun until(kind: TokenKind): Stop = Stop(listOf({ token, _ -> token.kind == kind }))

            fun topLevelBinding(letToken: Token): Stop =
                Stop(
                    listOf(
                        { token, _ -> token.kind == TokenKind.EOF },
                        { token, _ ->
                            token.range.start.line > letToken.range.start.line &&
                                token.range.start.column <= letToken.range.start.column
                        },
                    ),
                )

            fun recordField(fieldLine: Int): Stop =
                Stop(
                    listOf(
                        { token, _ -> token.kind in setOf(TokenKind.RBRACE, TokenKind.COMMA, TokenKind.SEMICOLON) },
                        { token, parser ->
                            token.kind == TokenKind.IDENT &&
                                parser.peek().kind == TokenKind.EQ &&
                                token.range.start.line > fieldLine
                        },
                    ),
                )
        }
    }
}
