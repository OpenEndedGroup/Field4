package afield.datalog

/**
 * Source text to abstract syntax: the lexer and recursive-descent parser every Datalog program in
 * Field goes through, and the two word lists that decide what the notation reserves.
 *
 * There is no error recovery. The first thing the parser cannot read raises a [DlException]
 * carrying the line and column it stopped at, and the box language turns those into an error marker
 * on the offending line, so a program is either whole or it is a single message.
 *
 * Two of the tables outlive the parse. `DatalogParser.functions` and `DatalogParser.aggregateOps`
 * are what the box language offers in its completion popup, and the browser-side highlighter keeps
 * its own copy of both; `afield.examples.datalog.DatalogEditorExtensionWorkout` runs the shipped
 * JavaScript against these sets and fails when the two have drifted apart.
 */

/**
 * The concrete syntax. One hand-written lexer + recursive-descent parser, no dependencies.
 *
 * ```datalog
 * % facts — a bare lowercase identifier is just a string, so these two are the same fact
 * edge(a, b).   edge("b", "c").
 *
 * % rules, recursion, negation
 * path(X, Y) :- edge(X, Y).
 * path(X, Z) :- path(X, Y), edge(Y, Z).
 * unreachable(X, Y) :- node(X), node(Y), !path(X, Y).
 *
 * % arithmetic: `=` binds when the left side is not bound yet, and compares when it is
 * doubled(X, D) :- val(X, V), D = V * 2, D < 100.
 *
 * % aggregates, grouped by the variables the enclosing rule has already bound
 * headcount(D, N) :- dept(D), N = count : { emp(_, D, _) }.
 * payroll(D, S)   :- dept(D), S = sum P : { emp(_, D, P) }.
 *
 * ?- path(a, Z).        % a query directive
 * ```
 *
 * Two deliberate reservations, both in the service of readable rules rather than a clean grammar:
 * the aggregate keywords (`count sum min max mean collect`) are reserved immediately after `=`, and
 * a call to a known builtin *function* (`contains(N, "x")`) is a test rather than a predicate call,
 * so builtin function names are not available as predicate names.
 */
object DatalogParser {

    val aggregateOps = setOf("count", "sum", "min", "max", "mean", "collect")

    /**
     * The embedded-region header. `#datalog` on its own line marks a program, and is ignored by the
     * parser; `datalog.js` looks for the same word to decide which part of a Kotlin file to
     * highlight as Datalog.
     */
    const val LANGUAGE_TAG = "datalog"

    /**
     * The builtin functions usable inside expressions. A name here cannot also be a predicate — see
     * the class KDoc; the parser resolves `f(...)` towards an expression when `f` is in this set.
     */
    val functions = setOf(
        "abs", "sign", "sqrt", "floor", "ceil", "round", "pow", "exp", "log",
        "sin", "cos", "tan", "atan2", "hypot",
        "min", "max",
        "len", "upper", "lower", "cat", "substr", "indexOf",
        "contains", "startsWith", "endsWith", "trim", "split",
        "num", "str", "int", "not",
    )

    fun parse(source: String): DlUnit = Impl(source).unit()

    /**
     * [source]'s statements in order, each with the lines it spans — what a line note needs to know
     * which statement a line belongs to. Throws, as [parse] does, at the first statement that does
     * not parse.
     */
    fun statements(source: String): List<DlStatement> = Impl(source).statements()

    /** Parse a body conjunction on its own — how [Datalog.query] and `#why` read their goal. */
    fun parseGoal(source: String): List<DlLiteral> {
        val text = source.trim().removeSuffix(".")
        val impl = Impl("$text.")
        return impl.goalOnly()
    }

    // ── lexer ───────────────────────────────────────────────────────────────────────────────────

    private enum class K { IDENT, VARIABLE, NUMBER, STRING, PUNCT, EOF }

    private data class Tok(val kind: K, val text: String, val value: Any? = null, val line: Int = 1, val col: Int = 1)

    private class Impl(val source: String) {
        private val toks = ArrayList<Tok>()
        private var at = 0

        init {
            lex()
        }

        private fun err(message: String, tok: Tok? = null): Nothing {
            val t = tok ?: peek()
            throw DlException(message, t.line, t.col)
        }

        private fun lex() {
            var i = 0
            var line = 1
            var lineStart = 0
            fun col() = i - lineStart + 1
            while (i < source.length) {
                val c = source[i]
                when {
                    c == '\n' -> { i++; line++; lineStart = i }
                    c.isWhitespace() -> i++
                    c == '%' -> { while (i < source.length && source[i] != '\n') i++ }
                    c == '/' && i + 1 < source.length && source[i + 1] == '/' -> {
                        while (i < source.length && source[i] != '\n') i++
                    }
                    c == '/' && i + 1 < source.length && source[i + 1] == '*' -> {
                        val startLine = line
                        i += 2
                        while (i + 1 < source.length && !(source[i] == '*' && source[i + 1] == '/')) {
                            if (source[i] == '\n') { line++; lineStart = i + 1 }
                            i++
                        }
                        if (i + 1 >= source.length) throw DlException("unterminated /* comment", startLine, 1)
                        i += 2
                    }
                    c == '"' || c == '\'' -> {
                        val quote = c
                        val startCol = col()
                        val sb = StringBuilder()
                        i++
                        while (i < source.length && source[i] != quote) {
                            if (source[i] == '\\' && i + 1 < source.length) {
                                i++
                                sb.append(
                                    when (source[i]) {
                                        'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'
                                        '\\' -> '\\'; '"' -> '"'; '\'' -> '\''
                                        else -> source[i]
                                    }
                                )
                            } else {
                                if (source[i] == '\n') { line++; lineStart = i + 1 }
                                sb.append(source[i])
                            }
                            i++
                        }
                        if (i >= source.length) throw DlException("unterminated string", line, startCol)
                        i++
                        toks.add(Tok(K.STRING, sb.toString(), sb.toString(), line, startCol))
                    }
                    c.isDigit() -> {
                        val startCol = col()
                        val start = i
                        while (i < source.length && (source[i].isDigit() || source[i] == '.')) {
                            // a '.' only continues the number when a digit follows, so `f(1).` ends cleanly
                            if (source[i] == '.' && (i + 1 >= source.length || !source[i + 1].isDigit())) break
                            i++
                        }
                        if (i < source.length && (source[i] == 'e' || source[i] == 'E')) {
                            val save = i
                            i++
                            if (i < source.length && (source[i] == '+' || source[i] == '-')) i++
                            if (i < source.length && source[i].isDigit()) {
                                while (i < source.length && source[i].isDigit()) i++
                            } else i = save
                        }
                        val text = source.substring(start, i)
                        val value = dlNormalize(if (text.contains('.') || text.contains('e') || text.contains('E')) text.toDouble() else text.toLong())
                        toks.add(Tok(K.NUMBER, text, value, line, startCol))
                    }
                    c.isLetter() || c == '_' -> {
                        val startCol = col()
                        val start = i
                        while (i < source.length && (source[i].isLetterOrDigit() || source[i] == '_')) i++
                        val text = source.substring(start, i)
                        val kind = if (text[0].isUpperCase() || text[0] == '_') K.VARIABLE else K.IDENT
                        toks.add(Tok(kind, text, text, line, startCol))
                    }
                    else -> {
                        val startCol = col()
                        val two = if (i + 1 < source.length) source.substring(i, i + 2) else ""
                        val text = when {
                            two == ":-" || two == "?-" || two == "<=" || two == ">=" || two == "!=" || two == "<>" -> two
                            else -> c.toString()
                        }
                        i += text.length
                        toks.add(Tok(K.PUNCT, text, text, line, startCol))
                    }
                }
            }
            toks.add(Tok(K.EOF, "<end>", null, line, i - lineStart + 1))
        }

        private fun peek(n: Int = 0): Tok = toks[minOf(at + n, toks.size - 1)]
        private fun next(): Tok = toks[minOf(at++, toks.size - 1)]
        private fun isPunct(text: String, n: Int = 0): Boolean = peek(n).kind == K.PUNCT && peek(n).text == text
        private fun accept(text: String): Boolean = if (isPunct(text)) { at++; true } else false
        private fun expect(text: String): Tok =
            if (isPunct(text)) next() else err("expected '$text' but found '${peek().text}'")

        private var anonymous = 0
        private fun freshAnonymous() = DlVar("_${++anonymous}")

        // ── grammar ─────────────────────────────────────────────────────────────────────────────

        fun unit(): DlUnit {
            val rules = ArrayList<DlRule>()
            val queries = ArrayList<DlQuery>()
            val directives = ArrayList<DlDirective>()
            while (peek().kind != K.EOF) {
                when {
                    isPunct("#") -> {
                        val parsed = directive()
                        // `#datalog` is a language tag, not a command — the same header the editor
                        // looks for to highlight an embedded region (see `datalog.js`), and the same
                        // one `#stacksl` / `#rectsl` / `#petri` use. Accepting and dropping it here
                        // is what lets one piece of text be pasted into a Datalog box, a Kotlin
                        // """…""" string and a docs fence without editing.
                        if (parsed.name != LANGUAGE_TAG) directives.add(parsed)
                    }
                    isPunct("?-") -> {
                        val start = at
                        next()
                        val body = body()
                        expect(".")
                        queries.add(DlQuery(body, textOf(start, at)))
                    }
                    else -> rules.add(rule())
                }
            }
            return DlUnit(rules, queries, directives)
        }

        fun statements(): List<DlStatement> {
            val out = ArrayList<DlStatement>()
            while (peek().kind != K.EOF) {
                val first = peek().line
                when {
                    isPunct("#") -> directive().let { out += DlStatement(first, it.line, directive = it) }
                    isPunct("?-") -> {
                        val start = at
                        next()
                        val body = body()
                        expect(".")
                        out += DlStatement(first, toks[at - 1].line, query = DlQuery(body, textOf(start, at)))
                    }
                    else -> rule().let { out += DlStatement(first, toks[at - 1].line, rule = it) }
                }
            }
            return out
        }

        fun goalOnly(): List<DlLiteral> {
            val body = body()
            expect(".")
            if (peek().kind != K.EOF) err("unexpected '${peek().text}' after goal")
            return body
        }

        /** `#name rest-of-line` — consumed as raw text, since directives take free-form arguments. */
        private fun directive(): DlDirective {
            val hash = next()
            val nameTok = next()
            if (nameTok.kind != K.IDENT && nameTok.kind != K.VARIABLE) err("expected a directive name after '#'", nameTok)
            // Re-slice the raw source: the argument may be a goal, a path, anything.
            val lineText = source.lineSequence().drop(hash.line - 1).firstOrNull() ?: ""
            val argument = lineText.substringAfter("#" + nameTok.text, "").trim().removeSuffix(".").trim()
            while (peek().kind != K.EOF && peek().line == hash.line) at++
            return DlDirective(nameTok.text.lowercase(), argument, hash.line)
        }

        private fun textOf(from: Int, to: Int): String =
            toks.subList(from, minOf(to, toks.size)).joinToString(" ") { it.text }

        private fun rule(): DlRule {
            val start = at
            val head = atom(allowNegation = false)
            if (accept(".")) return DlRule(head, emptyList(), textOf(start, at))
            expect(":-")
            val body = body()
            expect(".")
            return DlRule(head, body, textOf(start, at))
        }

        private fun body(): List<DlLiteral> {
            val out = ArrayList<DlLiteral>()
            out.add(literal())
            while (accept(",")) out.add(literal())
            return out
        }

        private fun literal(): DlLiteral {
            if (isPunct("!") || (peek().kind == K.IDENT && peek().text == "not" && isPunct("(", 1).not() && peek(1).kind == K.IDENT)) {
                next()
                return atom(allowNegation = true).copy(negated = true)
            }
            if (startsAtom()) return atom(allowNegation = false)
            return comparisonOrAggregate()
        }

        /** Is the upcoming token stream a predicate call rather than an expression? */
        private fun startsAtom(): Boolean {
            val t = peek()
            if (t.kind != K.IDENT) return false
            if (t.text in functions || t.text == "true" || t.text == "false") return false
            if (!isPunct("(", 1)) {
                // a nullary predicate: `ready.` / `…, ready, …`
                return isPunct(".", 1) || isPunct(",", 1) || isPunct(":-", 1) || isPunct("}", 1)
            }
            // `p(...)` — an atom unless a comparison operator follows the closing paren
            var depth = 0
            var i = 1
            while (true) {
                val tok = peek(i)
                if (tok.kind == K.EOF) return true
                if (tok.kind == K.PUNCT) {
                    when (tok.text) {
                        "(" -> depth++
                        ")" -> { depth--; if (depth == 0) break }
                    }
                }
                i++
            }
            val after = peek(i + 1)
            return !(after.kind == K.PUNCT && after.text in comparisonOps)
        }

        private fun atom(allowNegation: Boolean): DlAtom {
            val nameTok = next()
            if (nameTok.kind != K.IDENT) err("expected a predicate name but found '${nameTok.text}'", nameTok)
            if (!allowNegation && nameTok.text in functions) {
                err("'${nameTok.text}' is a builtin function, not a predicate", nameTok)
            }
            val terms = ArrayList<DlTerm>()
            if (accept("(")) {
                if (!isPunct(")")) {
                    do terms.add(term()) while (accept(","))
                }
                expect(")")
            }
            return DlAtom(nameTok.text, terms)
        }

        private fun term(): DlTerm {
            val t = peek()
            return when {
                t.kind == K.VARIABLE -> { next(); if (t.text == "_") freshAnonymous() else DlVar(t.text) }
                t.kind == K.NUMBER || t.kind == K.STRING -> { next(); DlConst(t.value) }
                t.kind == K.IDENT && t.text == "true" -> { next(); DlConst(true) }
                t.kind == K.IDENT && t.text == "false" -> { next(); DlConst(false) }
                t.kind == K.IDENT && t.text == "null" -> { next(); DlConst(null) }
                t.kind == K.IDENT -> { next(); DlConst(t.text) }
                isPunct("-") && peek(1).kind == K.NUMBER -> {
                    next()
                    val n = next()
                    val magnitude = n.value
                    DlConst(dlNormalize(if (magnitude is Long) -magnitude else -(magnitude as Double)))
                }
                else -> err("expected a term but found '${t.text}'")
            }
        }

        private val comparisonOps = setOf("=", "!=", "<>", "<", "<=", ">", ">=")

        private fun comparisonOrAggregate(): DlLiteral {
            val startTok = peek()
            val left = arith()
            if (isPunct("=") && peek(1).kind == K.IDENT && peek(1).text in aggregateOps && aggregateAhead()) {
                val target = (left as? DlAValue)?.term as? DlVar
                    ?: err("the left side of an aggregate must be a variable", startTok)
                next()
                val op = next().text
                val over = if (isPunct(":")) null else arith()
                if (op == "count" && over != null) err("count takes no expression — write 'N = count : { … }'", startTok)
                if (op != "count" && over == null) err("$op needs an expression — write 'X = $op Expr : { … }'", startTok)
                expect(":")
                expect("{")
                val body = body()
                expect("}")
                return DlAggregate(target, op, over, body)
            }
            if (peek().kind == K.PUNCT && peek().text in comparisonOps) {
                val op = next().text
                val right = arith()
                return DlCompare(if (op == "<>") "!=" else op, left, right)
            }
            // A bare builtin call used as a test: `contains(Name, "x")`
            if (left is DlACall || (left is DlAValue && (left.term as? DlConst)?.value is Boolean)) {
                return DlCompare("=", left, DlAValue(DlConst(true)))
            }
            err("expected a comparison operator after this expression", startTok)
        }

        /** Distinguish `X = sum P : { … }` from a comparison against something merely named `sum`. */
        private fun aggregateAhead(): Boolean {
            var i = 1
            var guard = 0
            while (guard++ < 512) {
                val tok = peek(i)
                if (tok.kind == K.EOF) return false
                if (tok.kind == K.PUNCT) {
                    when (tok.text) {
                        ":" -> return peek(i + 1).kind == K.PUNCT && peek(i + 1).text == "{"
                        "." , "," -> return false
                    }
                }
                i++
            }
            return false
        }

        private fun arith(): DlArith {
            var left = arithTerm()
            while (isPunct("+") || isPunct("-")) {
                val op = next().text
                left = DlABinary(op, left, arithTerm())
            }
            return left
        }

        private fun arithTerm(): DlArith {
            var left = arithFactor()
            while (isPunct("*") || isPunct("/") ||
                (peek().kind == K.IDENT && (peek().text == "mod" || peek().text == "div"))
            ) {
                val op = next().text
                left = DlABinary(op, left, arithFactor())
            }
            return left
        }

        private fun arithFactor(): DlArith {
            if (isPunct("-")) { next(); return DlAUnary("-", arithFactor()) }
            if (isPunct("+")) { next(); return arithFactor() }
            return arithPrimary()
        }

        private fun arithPrimary(): DlArith {
            val t = peek()
            if (isPunct("(")) {
                next()
                val inner = arith()
                expect(")")
                return inner
            }
            if (t.kind == K.IDENT && isPunct("(", 1)) {
                if (t.text !in functions) err("unknown function '${t.text}'", t)
                next(); next()
                val args = ArrayList<DlArith>()
                if (!isPunct(")")) {
                    do args.add(arith()) while (accept(","))
                }
                expect(")")
                return DlACall(t.text, args)
            }
            return DlAValue(term())
        }
    }
}
