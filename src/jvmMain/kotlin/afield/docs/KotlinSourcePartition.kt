package afield.docs

/**
 * Cuts a Kotlin source file into an ordered tree of line spans that **tiles the file exactly**.
 *
 * ### Why a partition, and not another parse
 *
 * [afield.ai.core.completion.KotlinSourceIndex] answers *"which declarations are in this file, and on
 * what line does each start?"* — which is all the completion panel, the semantic index and
 * [StdlibReferenceGenerator] have ever needed. Rendering a source *file* as a page needs a strictly
 * stronger fact: **every line has to land in exactly one span**, in order, with no gaps and no
 * overlaps. A page that lifts KDoc into prose and puts the bodies in code fences numbered from their
 * real source lines is only truthful if the spans it was cut from account for the whole file. Miss
 * three lines and the fence below them is numbered wrong — silently, on a published page, with
 * nothing to notice it.
 *
 * So the contract here is a *tiling*, and [verify] is the thing that proves it:
 *
 * - **Tile** — the top-level spans start at line 1, each begins where the previous one ended, and the
 *   last ends on the file's last line.
 * - **Nest** — a child span lies inside its parent and after its previous sibling. Children do *not*
 *   tile their parent: the lines between them (a class's signature, its closing brace, the blank line
 *   between two members) are the parent's own text, and [reconstruct] emits them from the gaps.
 * - **Reconstruct** — walking the tree and emitting each span's lines returns the original file, line
 *   for line. This is the assertion that actually has teeth; the two above are what make it debuggable
 *   when it fails.
 * - **Balance** — the code text of a [Kind.DECLARATION] span has balanced `()`, `[]` and `{}`. A span
 *   that stops in the middle of a body still tiles and still reconstructs, so this is the one check
 *   that can see a *mis-cut* rather than a lost line.
 *
 * ### Precision is measured, not assumed
 *
 * The end of a declaration is easy when it has a brace body and awkward when it does not — an
 * expression body can run over as many lines as it likes, and Kotlin's continuation rules are
 * genuinely ambiguous without a real parser. This deliberately does **not** guess: anything the
 * scanner cannot attribute to a declaration becomes [Kind.FILLER], which is a span like any other and
 * keeps the tiling total. So a heuristic that gives up early costs *precision* (lines in FILLER) and
 * never *correctness* (lines lost, or attributed to the wrong declaration). [SourcePartitionGuard]
 * fails the build on the invariants and prints the FILLER census as a number, so the imprecision is
 * something we can watch shrink instead of something we hope about.
 *
 * ### What it does not do
 *
 * Bodies are not descended into: a `fun` is one span, whatever is inside it. Only container
 * declarations — `class`, `interface`, `object`, `enum class`, `companion object` — recurse, because
 * only their contents are declarations a page would want headers for. A local class inside a function
 * body is therefore invisible here, on purpose.
 *
 * Enum entries are not recognised as declarations and land in FILLER; see the census.
 */
object KotlinSourcePartition {

    /** What a span of lines *is*, for a renderer deciding between prose and a code fence. */
    enum class Kind {
        /** `package`, `import` and `@file:` lines — a run of them, blank lines inside it included. */
        HEADER,

        /**
         * A standalone doc comment — the two-star block form — whose prose a page lifts out as
         * markdown. The markers are described rather than written: a comment marker inside a KDoc is a
         * comment marker inside a `.kt` file, and Kotlin nests block comments, so writing one here
         * unbalances the lexer for the rest of the file. That is not hypothetical — it is how this file
         * first failed to compile.
         */
        DOC,

        /** A standalone plain block comment, or a run of standalone `//` lines. Prose too, less formally. */
        COMMENT,

        /** One declaration: its annotations and modifiers, its signature, and its body. */
        DECLARATION,

        /** Everything else: blank lines, enum entries, and any shape the scanner could not name. */
        FILLER,
    }

    /**
     * One span of lines, 1-based and inclusive at both ends.
     *
     * [declarationLine] is where the *signature* starts, which is not always [startLine]: annotations
     * and modifiers written on their own lines above a declaration belong to its span, but a citation
     * (and a page's anchor) points at the keyword. Both are needed, so both are kept.
     *
     * [bodyOpenLine] and [bodyCloseLine] carry the brace body when there is one, so a renderer can
     * split signature from body without re-scanning. Zero means "no brace body" — an expression body,
     * an abstract member, a property.
     */
    data class Span(
        val kind: Kind,
        val startLine: Int,
        val endLine: Int,
        val declarationLine: Int = 0,
        val declKind: String = "",
        val name: String = "",
        val bodyOpenLine: Int = 0,
        val bodyCloseLine: Int = 0,
        val children: List<Span> = emptyList(),
    ) {
        /** How many lines this span covers, children included. */
        val lineCount: Int get() = endLine - startLine + 1

        /** This span and every descendant, parents before children. */
        fun flatten(): List<Span> {
            val out = mutableListOf<Span>()
            fun visit(span: Span) {
                out += span
                span.children.forEach(::visit)
            }
            visit(this)
            return out
        }
    }

    /**
     * A partitioned file: the original lines, the same lines with comments and string *contents*
     * blanked out ([code], which is what every syntactic decision here is made on), and the span tree.
     *
     * [unterminated] lists the declaration lines whose span ran to the end of its range without the
     * brackets ever balancing — a lexer or scanner failure, reported rather than papered over.
     */
    data class Partition(
        val lines: List<String>,
        val code: List<String>,
        val spans: List<Span>,
        val unterminated: List<Int>,
    ) {
        val lineCount: Int get() = lines.size

        /** Every span in the tree, in document order. */
        fun allSpans(): List<Span> = spans.flatMap { it.flatten() }
    }

    /** One broken invariant. [rule] is the contract name, [line] where to look. */
    data class Violation(val rule: String, val line: Int, val detail: String) {
        override fun toString(): String = "$rule at line $line: $detail"
    }

    /** Partitions [text]. Never throws on malformed input; malformation shows up in [verify]. */
    fun partition(text: String): Partition {
        val masked = Masked(text)
        val builder = Builder(masked)
        val spans = builder.build(0, masked.lines.size)
        return Partition(masked.lines, masked.code, spans, builder.unterminated)
    }

    /**
     * Walks the span tree and emits the lines it claims, in order — the inverse of [partition].
     *
     * A span's own text is whatever is *not* covered by a child: the lines before the first child, the
     * lines between two children, and the lines after the last. That is what makes the tree lossless
     * without every level having to tile itself.
     */
    fun reconstruct(partition: Partition): List<String> {
        val out = mutableListOf<String>()
        fun emitRange(fromLine: Int, untilLine: Int) {
            for (line in fromLine until untilLine) {
                if (line in 1..partition.lineCount) out += partition.lines[line - 1]
            }
        }
        fun emit(span: Span) {
            var cursor = span.startLine
            span.children.forEach { child ->
                emitRange(cursor, child.startLine)
                emit(child)
                cursor = child.endLine + 1
            }
            emitRange(cursor, span.endLine + 1)
        }
        partition.spans.forEach(::emit)
        return out
    }

    /** Every invariant, checked. An empty list is the only acceptable result for repository source. */
    fun verify(partition: Partition): List<Violation> {
        val out = mutableListOf<Violation>()

        var expected = 1
        partition.spans.forEach { span ->
            if (span.endLine < span.startLine) {
                out += Violation("TILE", span.startLine, "inverted span ${span.startLine}..${span.endLine}")
            }
            if (span.startLine != expected) {
                out += Violation(
                    "TILE", span.startLine,
                    "a span should start at line $expected but this one starts at ${span.startLine}"
                )
            }
            expected = span.endLine + 1
        }
        if (partition.lineCount > 0 && expected != partition.lineCount + 1) {
            out += Violation(
                "TILE", expected.coerceAtMost(partition.lineCount),
                "the spans stop at line ${expected - 1} but the file has ${partition.lineCount} lines"
            )
        }

        fun checkNesting(span: Span) {
            var cursor = span.startLine
            span.children.forEach { child ->
                if (child.startLine < cursor || child.endLine > span.endLine) {
                    out += Violation(
                        "NEST", child.startLine,
                        "child ${child.startLine}..${child.endLine} escapes " +
                            "${span.declKind} ${span.startLine}..${span.endLine} (cursor $cursor)"
                    )
                }
                cursor = child.endLine + 1
                checkNesting(child)
            }
        }
        partition.spans.forEach(::checkNesting)

        val rebuilt = reconstruct(partition)
        if (rebuilt != partition.lines) {
            val at = (0 until minOf(rebuilt.size, partition.lines.size))
                .firstOrNull { rebuilt[it] != partition.lines[it] }
                ?: minOf(rebuilt.size, partition.lines.size)
            out += Violation(
                "RECONSTRUCT", at + 1,
                "rebuilt ${rebuilt.size} line(s) from the spans, the file has ${partition.lines.size}; " +
                    "first divergence at line ${at + 1}"
            )
        }

        partition.allSpans().filter { it.kind == Kind.DECLARATION }.forEach { span ->
            val delta = bracketBalance(partition.code, span.startLine, span.endLine)
            if (delta != 0) {
                out += Violation(
                    "BALANCE", span.declarationLine.takeIf { it > 0 } ?: span.startLine,
                    "${span.declKind} ${span.name.ifEmpty { "<anonymous>" }} covers " +
                        "${span.startLine}..${span.endLine} with a bracket delta of $delta, so it is cut " +
                        "mid-declaration"
                )
            }
        }

        partition.unterminated.forEach { line ->
            out += Violation("UNTERMINATED", line, "the declaration here never closed its brackets")
        }

        return out
    }

    /** Net `()` + `[]` + `{}` over [fromLine]..[toLine] of already-masked [code]. */
    fun bracketBalance(code: List<String>, fromLine: Int, toLine: Int): Int {
        var delta = 0
        for (line in fromLine..toLine) {
            if (line !in 1..code.size) continue
            code[line - 1].forEach { ch ->
                when (ch) {
                    '(', '[', '{' -> delta++
                    ')', ']', '}' -> delta--
                }
            }
        }
        return delta
    }

    // ---------------------------------------------------------------------------------------------
    // Lexing
    // ---------------------------------------------------------------------------------------------

    /**
     * Every line of a file with its comments and its string *contents* replaced by spaces, plus a note
     * of where the standalone comment blocks are.
     *
     * Everything downstream — brace counting, declaration recognition, continuation guessing — reads
     * [code] and never the original text, which is what keeps a `{` inside a string or a `fun` inside
     * a comment from being mistaken for syntax. A `${…}` template hole is the exception and is
     * deliberately left as code: its braces are real, they balance, and blanking them would unbalance
     * every declaration containing an interpolated string.
     */
    private class Masked(text: String) {
        val lines: List<String> = text.split("\n")
        val code: List<String>

        /** For a line where a standalone block comment opens: the 0-based line its terminator sits on. */
        val blockEnd: IntArray

        /** Whether that standalone block is a KDoc — a doc-comment opener, not the empty-block form. */
        val blockKDoc: BooleanArray

        /** Whether this line is nothing but a `//` comment. */
        val lineComment: BooleanArray

        init {
            val n = lines.size
            val buffer = Array(n) { lines[it].toCharArray() }
            blockEnd = IntArray(n) { -1 }
            blockKDoc = BooleanArray(n)
            lineComment = BooleanArray(n)

            var state = NORMAL
            var blockDepth = 0
            var blockStartLine = -1
            var blockStandalone = false
            var blockIsKDoc = false
            var braceDepth = 0
            val interpolation = ArrayDeque<Pair<Int, Int>>()

            var index = 0
            while (index < n) {
                val line = lines[index]
                val buf = buffer[index]
                val len = line.length
                var at = 0
                while (at < len) {
                    val ch = line[at]
                    when (state) {
                        NORMAL -> when {
                            ch == '/' && at + 1 < len && line[at + 1] == '/' -> {
                                if (line.substring(0, at).isBlank()) lineComment[index] = true
                                for (k in at until len) buf[k] = ' '
                                at = len
                            }

                            ch == '/' && at + 1 < len && line[at + 1] == '*' -> {
                                blockStartLine = index
                                blockStandalone = line.substring(0, at).isBlank()
                                blockIsKDoc = at + 2 < len && line[at + 2] == '*' &&
                                    !(at + 3 < len && line[at + 3] == '/')
                                blockDepth = 1
                                state = BLOCK
                                buf[at] = ' '
                                buf[at + 1] = ' '
                                at += 2
                            }

                            ch == '"' && at + 2 < len && line[at + 1] == '"' && line[at + 2] == '"' -> {
                                state = RAW
                                buf[at] = ' '
                                buf[at + 1] = ' '
                                buf[at + 2] = ' '
                                at += 3
                            }

                            ch == '"' -> {
                                state = STRING
                                buf[at] = ' '
                                at++
                            }

                            ch == '\'' -> {
                                state = CHAR
                                buf[at] = ' '
                                at++
                            }

                            ch == '`' -> {
                                // A backtick-quoted name is an identifier, and anything at all can be
                                // inside it — including the apostrophe in
                                // `` `a builder's groups become the names a pick reports` ``, which
                                // read as the start of a character literal and blanked the rest of the
                                // line, brace and all. The declaration then ended on its own signature
                                // and its whole body became unattributed. The tiling never noticed:
                                // the body's `{` and `}` were blanked together, so the brackets stayed
                                // balanced. Only the census saw it.
                                var end = at + 1
                                while (end < len && line[end] != '`') {
                                    if (line[end] in BACKTICK_UNSAFE) buf[end] = ' '
                                    end++
                                }
                                at = if (end < len) end + 1 else len
                            }

                            else -> {
                                if (ch == '{') {
                                    braceDepth++
                                } else if (ch == '}') {
                                    val hole = interpolation.lastOrNull()
                                    if (hole != null && braceDepth == hole.second + 1) {
                                        braceDepth--
                                        state = hole.first
                                        interpolation.removeLast()
                                    } else if (braceDepth > 0) {
                                        braceDepth--
                                    }
                                }
                                at++
                            }
                        }

                        BLOCK -> when {
                            ch == '/' && at + 1 < len && line[at + 1] == '*' -> {
                                blockDepth++
                                buf[at] = ' '
                                buf[at + 1] = ' '
                                at += 2
                            }

                            ch == '*' && at + 1 < len && line[at + 1] == '/' -> {
                                blockDepth--
                                buf[at] = ' '
                                buf[at + 1] = ' '
                                at += 2
                                if (blockDepth <= 0) {
                                    state = NORMAL
                                    if (blockStandalone && blockStartLine >= 0) {
                                        blockEnd[blockStartLine] = index
                                        blockKDoc[blockStartLine] = blockIsKDoc
                                    }
                                }
                            }

                            else -> {
                                buf[at] = ' '
                                at++
                            }
                        }

                        STRING, RAW -> when {
                            state == STRING && ch == '\\' -> {
                                buf[at] = ' '
                                if (at + 1 < len) buf[at + 1] = ' '
                                at += 2
                            }

                            ch == '$' && at + 1 < len && line[at + 1] == '{' -> {
                                buf[at] = ' '
                                interpolation.addLast(state to braceDepth)
                                braceDepth++
                                state = NORMAL
                                at += 2
                            }

                            state == RAW && ch == '"' -> {
                                // A run of quotes inside a raw string closes it only if it is at least
                                // three long, and then the *last* three are the terminator — which is
                                // what makes `\"\"\"\"…\"\"\"\"` a raw string whose content is itself
                                // quoted, the idiom every regex over quoted text in this repository
                                // uses. Closing at the first three instead ate the rest of the line as
                                // string content, and the enclosing declaration's brackets stopped
                                // balancing 32 files deep.
                                var run = 0
                                while (at + run < len && line[at + run] == '"') run++
                                for (k in at until at + run) buf[k] = ' '
                                if (run >= 3) state = NORMAL
                                at += run
                            }

                            state == STRING && ch == '"' -> {
                                buf[at] = ' '
                                state = NORMAL
                                at++
                            }

                            else -> {
                                buf[at] = ' '
                                at++
                            }
                        }

                        else -> when {
                            ch == '\\' -> {
                                buf[at] = ' '
                                if (at + 1 < len) buf[at + 1] = ' '
                                at += 2
                            }

                            ch == '\'' -> {
                                buf[at] = ' '
                                state = NORMAL
                                at++
                            }

                            else -> {
                                buf[at] = ' '
                                at++
                            }
                        }
                    }
                }
                // A `"…"` or `'…'` cannot span a newline, so an unclosed one is a lexer wrong turn (or
                // source that would not compile); recovering at the line end keeps one bad line from
                // blanking the rest of the file.
                if (state == STRING || state == CHAR) state = NORMAL
                index++
            }

            code = buffer.map { String(it) }
        }

        companion object {
            /** Characters that must not survive inside a backtick-quoted name. */
            val BACKTICK_UNSAFE = setOf('(', ')', '[', ']', '{', '}', '"', '/', '*')

            const val NORMAL = 0
            const val BLOCK = 1
            const val STRING = 2
            const val RAW = 3
            const val CHAR = 4
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Span building
    // ---------------------------------------------------------------------------------------------

    /** A recognised declaration head: what it is, and where its signature begins. */
    private data class Head(val firstLine: Int, val keywordLine: Int, val decl: Decl)

    /** What a declaration keyword line declares. [container] means "recurse into its body". */
    private data class Decl(val kind: String, val name: String, val container: Boolean)

    /** Where a declaration ends, and its brace body if it has one. All 0-based. */
    private data class Extent(val endLine: Int, val bodyOpen: Int, val bodyClose: Int)

    private class Builder(val masked: Masked) {

        /** 1-based declaration lines whose brackets never balanced. */
        val unterminated = mutableListOf<Int>()

        /**
         * Partitions the 0-based half-open line range `[lo, hi)` into a tiling list of spans.
         *
         * [inEnum] is true for the body of an `enum class`, where a bare identifier at the start of a
         * line is an entry rather than an unreadable fragment. Entries are public API — the reference
         * generator reads them out of the class body with its own scanner for exactly that reason — so
         * a source page wants to name them, and they are the largest single population of otherwise
         * unattributed lines in the repository.
         */
        fun build(lo: Int, hi: Int, inEnum: Boolean = false): List<Span> {
            val out = mutableListOf<Span>()
            var index = lo
            while (index < hi) {
                val blockEnd = masked.blockEnd[index]
                if (blockEnd >= index) {
                    val end = minOf(blockEnd, hi - 1)
                    out += Span(
                        kind = if (masked.blockKDoc[index]) Kind.DOC else Kind.COMMENT,
                        startLine = index + 1,
                        endLine = end + 1,
                    )
                    index = end + 1
                    continue
                }

                if (masked.lineComment[index]) {
                    var last = index
                    while (last + 1 < hi && masked.lineComment[last + 1]) last++
                    out += Span(Kind.COMMENT, index + 1, last + 1)
                    index = last + 1
                    continue
                }

                if (isHeaderLine(index)) {
                    var scan = index
                    var last = index
                    while (scan < hi && (isHeaderLine(scan) || masked.lines[scan].isBlank())) {
                        if (isHeaderLine(scan)) last = scan
                        scan++
                    }
                    out += Span(Kind.HEADER, index + 1, last + 1)
                    index = last + 1
                    continue
                }

                val head = headAt(index, hi) ?: enumEntryAt(index, hi, inEnum)
                if (head != null) {
                    val extent = extentOf(head, hi, inEnum)
                    val children = if (
                        head.decl.container &&
                        extent.bodyOpen >= 0 &&
                        extent.bodyClose > extent.bodyOpen + 1
                    ) {
                        build(extent.bodyOpen + 1, extent.bodyClose, head.decl.kind == "enum class")
                    } else {
                        emptyList()
                    }
                    out += Span(
                        kind = Kind.DECLARATION,
                        startLine = head.firstLine + 1,
                        endLine = extent.endLine + 1,
                        declarationLine = head.keywordLine + 1,
                        declKind = head.decl.kind,
                        name = head.decl.name,
                        bodyOpenLine = if (extent.bodyOpen >= 0) extent.bodyOpen + 1 else 0,
                        bodyCloseLine = if (extent.bodyClose >= 0) extent.bodyClose + 1 else 0,
                        children = children,
                    )
                    index = extent.endLine + 1
                    continue
                }

                var end = index + 1
                while (end < hi && !startsSomething(end, hi, inEnum)) end++
                out += Span(Kind.FILLER, index + 1, end)
                index = end
            }
            return out
        }

        /** Whether a span of some kind opens at [index] — the stop condition for a FILLER run. */
        fun startsSomething(index: Int, hi: Int, inEnum: Boolean): Boolean =
            masked.blockEnd[index] >= index ||
                masked.lineComment[index] ||
                isHeaderLine(index) ||
                headAt(index, hi) != null ||
                enumEntryAt(index, hi, inEnum) != null

        /**
         * The enum entry starting at [index], or null when [inEnum] is false or the line is not one.
         *
         * An entry is whatever an enum body offers that is not a declaration and not a control-flow
         * keyword: a bare name, optionally with constructor arguments and optionally with a body of its
         * own. Recognition is deliberately last — [headAt] runs first, so an `override fun` or a
         * `companion object` inside an enum is still a declaration.
         */
        fun enumEntryAt(index: Int, hi: Int, inEnum: Boolean): Head? {
            if (!inEnum || index >= hi) return null
            if (!isEnumEntryLine(index)) return null
            val name = identifierIn(stripAnnotations(masked.code[index].trim()))
            if (name.isEmpty()) return null
            return Head(index, index, Decl("enum entry", name, container = false))
        }

        /** Whether [index] starts an enum entry, on the assumption that we are inside an enum body. */
        fun isEnumEntryLine(index: Int): Boolean {
            // `@SerialName("direct") DIRECT,` is an entry with an annotation on it, which is how every
            // serialized enum in the audio-AI protocol is written.
            val text = stripAnnotations(masked.code[index].trim())
            if (text.isEmpty()) return false
            if (!(text[0].isLetter() || text[0] == '_' || text[0] == '`')) return false
            if (recognize(text) != null) return false
            val word = text.takeWhile { it.isLetterOrDigit() || it == '_' }
            return word.isNotEmpty() && word !in NOT_ENUM_ENTRY_WORDS
        }

        fun isHeaderLine(index: Int): Boolean {
            val text = masked.code[index].trimStart()
            return text.startsWith("package ") || text.startsWith("import ") || text.startsWith("@file:")
        }

        /**
         * The declaration opening at [index], or null.
         *
         * Annotation-only and modifier-only lines above the keyword are part of the declaration, so the
         * span starts at the first of them while [Head.keywordLine] stays on the signature. A blank
         * line breaks the run: it is the author saying these are two different things.
         *
         * An annotation whose argument list runs over several lines is part of the run too — see
         * [multiLineAnnotationEnd], which is what lets the scan step over it.
         *
         * So is a `//` comment sitting *inside* the run, between an annotation and the keyword it
         * belongs to — see [lineCommentRunEnd]. Such a comment explains the declaration underneath it
         * (in the two places the repository does this it explains the *name*: why `@Test` below is not
         * backtick-quoted, and why an `FLine` carries the serializer it does), so the annotations above
         * it belong to the same declaration and not to a fragment of their own. Only a blank line
         * breaks the run; a comment is the author still talking about the same thing. Requiring a
         * prefix line first is what keeps this from turning every comment in the file into the start
         * of a declaration — a comment run reached with nothing above it is a [Kind.COMMENT] span, and
         * [build] has already claimed it by the time this is asked.
         */
        fun headAt(index: Int, hi: Int): Head? {
            var scan = index
            var sawPrefix = false
            while (scan < hi) {
                if (isPrefixOnlyLine(scan)) {
                    sawPrefix = true
                    scan++
                    continue
                }
                if (sawPrefix) {
                    val afterComment = lineCommentRunEnd(scan, hi)
                    if (afterComment != null) {
                        scan = afterComment
                        continue
                    }
                }
                scan = multiLineAnnotationEnd(scan, hi) ?: break
            }
            if (scan >= hi) return null
            val decl = recognize(masked.code[scan]) ?: return null
            return Head(firstLine = index, keywordLine = scan, decl = decl)
        }

        /**
         * The line after the run of `//`-comment lines starting at [index], or null when [index] is not
         * one.
         *
         * Deliberately blind to block comments and KDoc. A `/** … */` between an annotation and its
         * declaration is a different thing — [KDocAttachmentGuard] has an opinion about where a KDoc
         * may sit, and quietly absorbing one into a declaration span would hide the case it exists to
         * find. Neither shape occurs in the repository today; only the `//` one does.
         */
        fun lineCommentRunEnd(index: Int, hi: Int): Int? {
            if (masked.blockEnd[index] >= index) return null
            if (!masked.lineComment[index]) return null
            var last = index
            while (last + 1 < hi && masked.lineComment[last + 1]) last++
            return last + 1
        }

        /** A non-blank line carrying only annotations and/or modifiers — no declaration keyword. */
        fun isPrefixOnlyLine(index: Int): Boolean {
            val text = masked.code[index].trim()
            if (text.isEmpty()) return false
            val stripped = stripAnnotations(text)
            if (stripped.isEmpty()) return true
            val tokens = stripped.split(WHITESPACE).filter { it.isNotEmpty() }
            return tokens.isNotEmpty() && tokens.all { it in MODIFIERS }
        }

        /**
         * The line after an annotation whose argument list opens on [index] and closes on a later line,
         * or null when [index] is not that.
         *
         * [isPrefixOnlyLine] reads one line at a time, and [stripAnnotations] refuses to guess at an
         * argument list left open at the end of one, so a wrapped annotation was invisible to the run
         * scan in [headAt]: `@Target(` … `)` above `annotation class exported`, or a
         * `@JsonClassDescription("…" + "…" + "…")` paragraph above an AI tool's request class. The whole
         * run came out as unattributed FILLER and the declaration underneath began at its keyword
         * instead of at its first annotation, which a source page renders as an anonymous fragment
         * followed by a declaration missing its own annotations.
         *
         * Two deliberate refusals, both of them "return null and leave the run as FILLER", because a
         * wrong guess here does not lose a line — it moves a declaration's start into the middle of an
         * argument list, which is worse than not naming it:
         *
         *  - the opener has to be an annotation and nothing else. `@Foo(bar) fun baz(` also ends with
         *    brackets open, and stepping over its balance point would step over the whole signature and
         *    lose the function;
         *  - the closing bracket has to be the last thing on its line. `) fun baz(` would balance and
         *    then need the residue re-read as a fresh head, which is a second guess on top of the first.
         *    Every live instance in this repository closes on a line holding only `)`.
         */
        fun multiLineAnnotationEnd(index: Int, hi: Int): Int? {
            val open = openAnnotationBracket(masked.code[index]) ?: return null
            val close = if (open.first == '(') ')' else ']'
            var depth = open.second
            var line = index + 1
            // A bound, so a line that never balances cannot swallow the rest of the file while looking
            // like one declaration. No annotation in the repository wraps over more than nine lines.
            val last = minOf(hi, index + 1 + MAX_ANNOTATION_WRAP)
            while (line < last) {
                val text = masked.code[line]
                for (character in text) {
                    if (character == open.first) depth++ else if (character == close) depth--
                }
                if (depth <= 0) {
                    return if (text.trimEnd().endsWith(close)) line + 1 else null
                }
                line++
            }
            return null
        }

        /**
         * Where the declaration [head] ends.
         *
         * Bracket depth decides whenever it can: a declaration with a brace body ends on the line that
         * closes it. With no body the scan stops at the first line where the brackets are level and
         * neither [CONTINUES_AFTER] nor [CONTINUES_BEFORE] says the declaration keeps going — and it
         * stops unconditionally when the next thing is another declaration, which is what keeps a
         * greedy continuation guess from swallowing a sibling.
         */
        fun extentOf(head: Head, hi: Int, inEnum: Boolean): Extent {
            var depth = 0
            var bodyOpen = -1
            var bodyClose = -1
            var sawEquals = false
            var line = head.keywordLine
            while (line < hi) {
                val text = masked.code[line]
                var at = 0
                while (at < text.length) {
                    when (text[at]) {
                        '{' -> {
                            // Only a brace reached *without* an `=` at depth zero is the declaration's
                            // body. With one, this is an expression body and the brace is a lambda in
                            // the middle of it — treating that lambda's `}` as the end of the
                            // declaration cut `keywordItems` in FieldMLCompletionEngine.kt thirteen
                            // lines short and left the rest of its own chain unattributed.
                            if (depth == 0 && !sawEquals && bodyOpen < 0) bodyOpen = line
                            depth++
                        }

                        '(', '[' -> depth++

                        '}' -> {
                            depth--
                            if (depth == 0 && bodyOpen >= 0 && bodyClose < 0) bodyClose = line
                        }

                        ')', ']' -> depth--

                        '=' -> {
                            val previous = text.getOrNull(at - 1)
                            val next = text.getOrNull(at + 1)
                            val comparison = next == '=' ||
                                previous in setOf('=', '!', '<', '>', '+', '-', '*', '/', '%')
                            if (depth == 0 && !comparison) sawEquals = true
                        }
                    }
                    at++
                }
                if (depth <= 0) {
                    if (bodyOpen >= 0 && bodyClose >= 0) return Extent(line, bodyOpen, bodyClose)
                    if (!continues(line, hi, inEnum)) return Extent(line, bodyOpen, bodyClose)
                }
                line++
            }
            unterminated += head.keywordLine + 1
            return Extent(hi - 1, bodyOpen, bodyClose)
        }

        /** Whether the declaration ending textually at [line] carries on to the next code line. */
        fun continues(line: Int, hi: Int, inEnum: Boolean): Boolean {
            val next = nextCodeLine(line + 1, hi) ?: return false
            if (isPrefixOnlyLine(next)) return continuesIntoAnnotatedAccessor(next, hi)
            if (recognize(masked.code[next]) != null) return false
            // `A,` ends with a comma, which normally means "unfinished" — inside an enum body it means
            // the next entry, and continuing would swallow it.
            if (inEnum && isEnumEntryLine(next)) return false
            val trailing = masked.code[line].trimEnd()
            if (trailing.isNotEmpty() && CONTINUES_AFTER.containsMatchIn(trailing)) return true
            // Modifiers are stripped before the test so that `private set` on its own line reads as the
            // accessor it is. Doing this cannot capture a sibling declaration: a modifier run followed
            // by a keyword is a declaration, and the check above has already returned.
            return CONTINUES_BEFORE.containsMatchIn(stripModifiers(masked.code[next].trimStart()))
        }

        /**
         * Whether the annotation/modifier run starting at [index] belongs to an accessor of the
         * declaration above it, rather than starting the next declaration.
         *
         * An annotations-only line almost always opens the *next* declaration's head, which is why
         * [continues] stops at one. A property's accessor is the exception: `@Synchronized`,
         * `@JvmName("_getBuffer")` and `@OptIn(…)` are routinely written on their own line above the
         * `get()` or `set()` they annotate, and stopping there ends the property on its signature and
         * leaves both the annotation and the accessor unattributed. That shape accounts for 13 of the
         * repository's unattributed code lines across `stats.kt`, `VulkanBuffer.kt`, `keyframes.kt`
         * and `PersonalLayer.kt`.
         *
         * Reading past the run cannot swallow a sibling: if the line the run leads to declares
         * anything, [recognize] sees it and this returns false. `get`/`set` are not declaration
         * keywords, so the only way through is an actual accessor.
         */
        fun continuesIntoAnnotatedAccessor(index: Int, hi: Int): Boolean {
            var cursor = index
            while (cursor < hi && isPrefixOnlyLine(cursor)) {
                cursor = nextCodeLine(cursor + 1, hi) ?: return false
            }
            if (cursor >= hi) return false
            if (recognize(masked.code[cursor]) != null) return false
            return ACCESSOR_START.containsMatchIn(stripModifiers(masked.code[cursor].trimStart()))
        }

        /** The next line in `[from, hi)` carrying any code at all. */
        fun nextCodeLine(from: Int, hi: Int): Int? {
            var index = from
            while (index < hi) {
                if (masked.code[index].isNotBlank()) return index
                index++
            }
            return null
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Declaration recognition
    // ---------------------------------------------------------------------------------------------

    /**
     * What the code text of one line declares, if anything.
     *
     * Deliberately the same shape of rule as
     * [afield.ai.core.completion.KotlinSourceIndex]'s own line recogniser — annotations, then
     * modifiers, then a keyword — because [SourcePartitionGuard] asserts the two agree on every
     * declaration line in the repository, and two recognisers that agree by construction would prove
     * nothing. This one is independent, and slightly wider: it also sees `init`, `constructor` and
     * accessors' parents at any nesting depth.
     */
    private fun recognize(code: String): Decl? {
        val text = code.trim()
        if (text.isEmpty()) return null
        val stripped = stripAnnotations(text)
        if (stripped.isEmpty()) return null

        val tokens = stripped.split(WHITESPACE).filter { it.isNotEmpty() }
        var index = 0
        var isEnum = false
        var isCompanion = false
        while (index < tokens.size && tokens[index] in MODIFIERS) {
            if (tokens[index] == "enum") isEnum = true
            if (tokens[index] == "companion") isCompanion = true
            index++
        }
        if (index >= tokens.size) return null

        // `constructor(` and `init{` write the keyword flush against a bracket, so the keyword is the
        // leading word of the token rather than the whole token.
        val token = tokens[index]
        val word = token.takeWhile { it.isLetter() }
        if (word.isEmpty()) return null
        var keyword = word
        var rest = (token.substring(word.length) + " " + tokens.drop(index + 1).joinToString(" ")).trim()

        // `fun interface Foo` declares a SAM interface, not a function — the same special case
        // KotlinSourceIndex carries, for the same reason.
        if (keyword == "fun" && (rest == "interface" || rest.startsWith("interface "))) {
            keyword = "interface"
            rest = rest.removePrefix("interface").trimStart()
        }

        return when (keyword) {
            "class", "interface", "object" -> Decl(
                kind = when {
                    isEnum -> "enum class"
                    isCompanion && keyword == "object" -> "companion object"
                    else -> keyword
                },
                name = identifierIn(rest),
                container = true,
            )

            "fun" -> Decl("fun", identifierIn(rest.substringBefore('(')), container = false)
            "val", "var" -> Decl(keyword, identifierIn(rest), container = false)
            "typealias" -> Decl("typealias", identifierIn(rest), container = false)
            "init" -> if (rest.isEmpty() || rest.startsWith("{")) Decl("init", "init", false) else null
            "constructor" -> Decl("constructor", "constructor", container = false)
            else -> null
        }
    }

    /**
     * [text] with any leading annotation run removed.
     *
     * Scanned rather than matched with a regex because an annotation's arguments nest:
     * `@Deprecated("Use position", ReplaceWith("position.x.toDouble()"))` defeated a
     * `\([^)]*\)` pattern, which left a stray `)` behind, which meant the line did not read as
     * annotations-only, which meant the declaration underneath started at the wrong line — thirty
     * times over in `JoltPhysicsWorld.kt` alone.
     */
    private fun stripAnnotations(text: String): String {
        var rest = text.trimStart()
        while (rest.startsWith("@")) {
            var at = 1
            while (at < rest.length && (rest[at].isLetterOrDigit() || rest[at] == '_' ||
                    rest[at] == '.' || rest[at] == ':')
            ) {
                at++
            }
            if (at == 1) return rest
            if (at < rest.length && (rest[at] == '(' || rest[at] == '[')) {
                val open = rest[at]
                val close = if (open == '(') ')' else ']'
                var depth = 0
                while (at < rest.length) {
                    if (rest[at] == open) depth++ else if (rest[at] == close) depth--
                    at++
                    if (depth == 0) break
                }
                // An argument list left open at the end of the line is a multi-line annotation, which
                // is not something this can strip; leave the text alone rather than guess.
                if (depth != 0) return rest
            }
            rest = rest.substring(at).trimStart()
        }
        return rest
    }

    /** How many lines past its opener a wrapped annotation may close on. See [Builder.multiLineAnnotationEnd]. */
    private const val MAX_ANNOTATION_WRAP = 12

    /**
     * The bracket an annotation on [text] leaves open at the end of the line, and how deep, or null.
     *
     * Complete annotations in front of it are consumed by [stripAnnotations] first, so
     * `@Retention(RUNTIME) @Target(` reports the `@Target` bracket and `@Foo(bar) fun baz(` reports
     * nothing — the residue there is a declaration, not an annotation, which is the distinction
     * [Builder.multiLineAnnotationEnd] depends on.
     */
    private fun openAnnotationBracket(text: String): Pair<Char, Int>? {
        val rest = stripAnnotations(text.trim())
        if (!rest.startsWith("@")) return null
        var at = 1
        while (at < rest.length && (rest[at].isLetterOrDigit() || rest[at] == '_' ||
                rest[at] == '.' || rest[at] == ':')
        ) {
            at++
        }
        if (at == 1) return null
        if (at >= rest.length) return null
        val open = rest[at]
        if (open != '(' && open != '[') return null
        val close = if (open == '(') ')' else ']'
        var depth = 0
        while (at < rest.length) {
            if (rest[at] == open) depth++ else if (rest[at] == close) depth--
            at++
        }
        return if (depth > 0) open to depth else null
    }

    /** [text] with any leading run of modifier words removed. */
    private fun stripModifiers(text: String): String {
        var rest = text
        while (true) {
            val word = rest.takeWhile { it.isLetter() }
            if (word.isEmpty() || word !in MODIFIERS) return rest
            val after = rest.substring(word.length)
            if (after.isNotEmpty() && !after[0].isWhitespace()) return rest
            rest = after.trimStart()
        }
    }

    /**
     * The first identifier in [text], as a best-effort name.
     *
     * Best-effort is enough on purpose: nothing asserts on these, and a receiver (`val Foo.bar`) or a
     * type parameter list (`fun <T> f`) yields the wrong one. [StdlibReferenceGenerator] already reads
     * signatures out of the source text when it needs them exactly, and a renderer built on this
     * partition would do the same.
     */
    private fun identifierIn(text: String): String {
        val trimmed = text.trimStart().removePrefix("<").trimStart()
        val match = IDENTIFIER.find(trimmed) ?: return ""
        return if (match.range.first == 0) match.value.trim('`') else ""
    }

    /**
     * Words that begin a line inside an enum body without being an entry.
     *
     * Control flow can appear inside an entry's own body or an initialiser; a `where` clause and the
     * soft keywords below can appear in a member's signature that [recognize] did not match.
     */
    private val NOT_ENUM_ENTRY_WORDS = setOf(
        "if", "else", "when", "for", "while", "do", "return", "throw", "try", "catch", "finally",
        "get", "set", "by", "where", "is", "as", "in", "out", "this", "super", "it", "field",
    )

    private val WHITESPACE = Regex("\\s+")
    private val IDENTIFIER = Regex("^(`[^`]+`|[A-Za-z_][A-Za-z0-9_]*)")

    /**
     * Tokens that may precede a declaration keyword. `fun` is absent on purpose — it is a keyword, not
     * a modifier — and so are `in` and `out`, which are variance markers inside a type parameter list
     * and never introduce a declaration.
     */
    private val MODIFIERS = setOf(
        "public", "private", "internal", "protected",
        "open", "final", "abstract", "sealed", "override",
        "lateinit", "const", "inline", "noinline", "crossinline", "vararg", "tailrec",
        "operator", "infix", "external", "suspend",
        "annotation", "data", "enum", "inner", "companion", "value",
        "expect", "actual", "reified", "context",
    )

    /**
     * A line whose code ends like this is unfinished.
     *
     * `<`, `>`, `?` and `!` are deliberately absent: `val x: List<Int>`, `val x: Int?` and `val x = y!!`
     * all end with one and are complete, and treating them as continuations merged real declarations
     * into their neighbours.
     */
    private val CONTINUES_AFTER =
        Regex("""(?:[=+*/%&|^,.:\-]|->|\b(?:by|is|as|in|and|or|where)\b)\s*${'$'}""")

    /**
     * A line whose code starts like this continues the line above. `}`, `)` and `]` are absent: bracket
     * depth already accounts for those, and listing them here let a declaration swallow the closing
     * brace of the class it sits in.
     */
    private val CONTINUES_BEFORE =
        Regex("""^(?:\?\.|\?:|::|&&|\|\||[.=,{]|[+*/%]|->|:|by\b|where\b|else\b|catch\b|finally\b|get\b|set\b|@get:|@set:)""")

    /** A line that starts a property accessor. See [Builder.continuesIntoAnnotatedAccessor]. */
    private val ACCESSOR_START = Regex("""^(?:get\b|set\b)""")
}
