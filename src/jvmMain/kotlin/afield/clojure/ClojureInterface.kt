@file:Suppress("UNCHECKED_CAST")

package afield.clojure

import afield.*
import afield.Box
import afield.logger.lprintln
import afield.runtime.TopologyWatcher
import afield.runtime.MainLanguageRuntime
import afield.runtime.applyRuntimeToggle
import afield.runtime.isMainLanguage
import afield.runtime.registerMainLanguageRuntime
import afield.Frames
import afield.eachFrame
import afield.graphics.FLine
import afield.graphics.lines
import afield.stdlib.Delegation
import afield.stdlib.color
import afield.stdlib.thickness
import afield.stdlib.vec
import clojure.java.api.Clojure
import clojure.lang.*
import java.io.PrintWriter
import java.io.Writer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Clojure as a Field main language: a box whose source is Clojure, evaluated by the real Clojure
 * runtime on this JVM.
 *
 * Nothing here implements a Lisp. `clojure.jar` is on the classpath and this file is the adapter
 * between Field's box-execution contract and `clojure.java.api.Clojure` — which is why the whole
 * language is one file while `afield.datalog` and `afield.rectsl`, whose compilers Field owns, are
 * packages.
 *
 * Read it in four layers:
 *
 *  - [ClojureHost] is the interpreter, and the only part with no Field in it. It reads one top-level
 *    form at a time and evaluates it, which is `load-string`'s own loop written out so that the
 *    *line* each form started on is available while that form runs: that line is what lets printed
 *    output land beside the form that printed it, the way it does in every other kind of Field box.
 *    It returns the value of the last form, and `executeIfFn` will invoke that value when it is an
 *    `IFn` — a host convenience, off on the box path ([ClojureExecution] passes `false`), so a box
 *    whose last form evaluates to a function prints the function rather than calling it.
 *    `compileOnly` is the same read loop with evaluation withheld, which is where the editor's
 *    syntax errors come from, carrying line and column.
 *  - [ClojureInterface] is the host facade: the box currently running, and the handful of Field
 *    verbs that are awkward to reach by raw interop because they are Kotlin extension functions
 *    with default arguments. `field/core.clj` (in `src/jvmMain/resources`) is the Clojure side of
 *    it, `require`d and `refer`red into every box namespace, so a box can say `(lines ls :fit true)`
 *    rather than naming `SomeSkikoKt`.
 *  - [ClojureExecution] is the `BoxExecution`, so it is the half that knows about boxes: output
 *    proxying, `is-running`, per-fragment generation counting, and `complete`, whose candidates
 *    come from Compliment if that namespace loads and from nothing if it does not — completion is
 *    optional here by design rather than by omission.
 *  - [installClojure] and [clojureTopologyNeedsUpdating] are the registration, and they are
 *    identical in shape to every other main language: one `registerMainLanguageRuntime` call, one
 *    `TopologyWatcher` that swaps a child box's execution when the graph changes.
 *
 * One namespace, not one per box: every Clojure box in every open document evaluates in `live.user`
 * (Clojure namespaces are per-JVM, and [ClojureExecution] asks for that one name), so two Clojure
 * boxes are two cells of one notebook. `public/docs/clojure.md` is the page that says so to a
 * reader; `dev_docs/small_language_anatomy.md` is the map of the integration points.
 */

class StreamingWriter(
    private val onText: (String) -> Unit
) : Writer() {
    private val buf = StringBuilder()

    override fun write(cbuf: CharArray, off: Int, len: Int) {
        buf.append(cbuf, off, len)
        // flush on newline to reduce latency in consoles
        var i = 0
        while (true) {
            val idx = buf.indexOf("\n", i)
            if (idx == -1) break
            onText(buf.substring(0, idx + 1))
            buf.delete(0, idx + 1)
            i = 0
        }
    }

    override fun flush() {
        if (buf.isNotEmpty()) {
            onText(buf.toString())
            buf.setLength(0)
        }
    }

    override fun close() {
        flush()
    }
}

// ---------- Result types ----------
data class CompileError(
    val message: String, val line: Int? = null, val column: Int? = null, val causeClass: String? = null
)

sealed class CompileOnlyResult {
    data class Ok(val formsCount: Int) : CompileOnlyResult()
    data class Err(val errors: List<CompileError>) : CompileOnlyResult()
}

data class EvalValue(
    val value: Any?,
    val valueClass: String? = value?.let { it::class.java.name },
    /**
     * 1-based line, within the evaluated text, of the last top-level form — the one whose value
     * this is. `-1` when nothing was evaluated. The box path anchors the echoed value to it so the
     * answer appears beside the form that produced it rather than under the whole box.
     */
    val line: Int = -1,
    /** `pr-str` of [value], so a Clojure box prints `[1 2 3]` and `nil` rather than Java's `toString`. */
    val printed: String? = null
)

sealed class EvalResult {
    data class Ok(val result: EvalValue) : EvalResult()
    data class Err(val error: CompileError) : EvalResult()
}

/**
 * Compliment's kind of thing, said in the completion popup's own vocabulary.
 *
 * `Metadata.category` is not a label: it is the key `CATEGORY_WEIGHT` in
 * `src/commonMain/resources/fieldSuggest.js` is looked up by, and a name that table does not know
 * scores **0** — the same as "other". Handing Compliment's own words over unmapped therefore looked
 * fine, because the popup will title-case anything into a group header (`class` got a group called
 * "Class"), while ranking every Clojure candidate as bottom-of-the-list filler. `local` is the one
 * that matters most: that table scores it 28, above every other category, on the grounds that a name
 * the reader bound two lines up is the best answer a popup can give. It was arriving with no weight
 * at all.
 *
 * A type this does not know keeps its own spelling rather than collapsing to "other", so a new
 * Compliment source appears under its own header instead of joining a pile. [ClojureHostTest] pins
 * every mapping here against the shipped table, so a rename on either side is a test failure rather
 * than a silent demotion.
 */
internal fun clojureCompletionCategory(type: String?): String = when (val kind = type?.trim()?.lowercase()) {
    null, "" -> ""
    "local" -> "local"
    "function", "macro", "special-form", "method", "static-method" -> "functions"
    "var", "keyword", "field", "static-field" -> "values"
    "class", "import" -> "types"
    "namespace" -> "imports"
    else -> kind
}


// ---------- Helper to safely call Clojure Vars ----------
private fun varv(ns: String, name: String): Var = Clojure.`var`(ns, name) as Var

private fun <T> withClojureIO(
    onStdout: ((String) -> Unit)? = null, onStderr: ((String) -> Unit)? = null, body: () -> T
): T {
    // Vars to bind
    val outVar: Var = Clojure.`var`("clojure.core", "*out*") as Var
    val errVar: Var = Clojure.`var`("clojure.core", "*err*") as Var
    val printReadably: Var = Clojure.`var`("clojure.core", "*print-readably*") as Var
    val flushOnNewline: Var = Clojure.`var`("clojure.core", "*flush-on-newline*") as Var

    // Writers -> PrintWriter (Clojure prints to java.io.Writer via PrintWriter)
    val outWriter = onStdout?.let { PrintWriter(StreamingWriter(it), /*autoFlush=*/true) }
    val errWriter = onStderr?.let { PrintWriter(StreamingWriter(it), true) }

    // Build binding map (only include when provided)
    val pairs = mutableListOf<Any>()
    if (outWriter != null) {
        pairs += outVar; pairs += outWriter
    }
    if (errWriter != null) {
        pairs += errVar; pairs += errWriter
    }
    // Nice defaults for UIs
    pairs += flushOnNewline; pairs += java.lang.Boolean.TRUE
    // Usually you want human-friendly printed values
    pairs += printReadably; pairs += java.lang.Boolean.TRUE

    Var.pushThreadBindings(RT.map(*pairs.toTypedArray()))
    return try {
        body()
    } finally {
        try {
            outWriter?.flush(); errWriter?.flush()
        } catch (_: Throwable) {
        }
        Var.popThreadBindings()
    }
}

/**
 * Extract line/column from Clojure's nested exceptions if present.
 */
private fun extractCompilerError(t: Throwable): CompileError {
    // clojure.lang.Compiler$CompilerException exposes line/column; fall back to cause chain
    var cur: Throwable? = t
    var line: Int? = null
    var col: Int? = null
    while (cur != null) {
        val cls = cur.javaClass
        if (cls.name.startsWith("clojure.lang.Compiler\$CompilerException")) {
            try {
                // getLine/getColumn are available on recent versions
                val getLine = cls.methods.firstOrNull { it.name == "getLine" && it.parameterCount == 0 }
                val getCol = cls.methods.firstOrNull { it.name == "getColumn" && it.parameterCount == 0 }
                line = (getLine?.invoke(cur) as? Int) ?: line
                col = (getCol?.invoke(cur) as? Int) ?: col
            } catch (_: Throwable) { /* ignore */
            }
            break
        }
        cur = cur.cause
    }
    return CompileError(
        message = t.message ?: t.toString(), line = line, column = col, causeClass = t.javaClass.name
    )
}

/**
 * [error] with [line] filled in when the exception did not carry one of its own.
 *
 * Clojure reports a line for anything the reader or the compiler rejected, and nothing for a
 * runtime throw from inside a function — `(/ 1 0)` is an `ArithmeticException` with no position at
 * all. The line of the top-level form that was running is the best answer available there, and it
 * is the one a reader of the box wants: it points at the form they ran.
 */
private fun atLine(error: CompileError, line: Int): CompileError =
    if (error.line != null || line < 1) error else error.copy(line = line)

/**
 * A Clojure evaluator for one namespace: read a form, evaluate it, repeat, report the last value.
 *
 * The part of the language with no Field in it, so it is unit-testable on its own
 * ([afield.clojure.ClojureHostTest]) and knows nothing about boxes. Its one unusual commitment is
 * that everything it reports — printed text, the last value, an error — carries the line of the
 * top-level form responsible. See [evalPage].
 *
 * `boot` loads Compliment if it is there and shrugs if it is not. Namespaces are initialised lazily
 * and once, with `clojure.core` and `field.core` referred in: see [initNsIfNeeded].
 */
class ClojureHost(private val defaultNs: String = "user") {

    private val bootOnce = AtomicBoolean(false)

    private fun boot() {
        if (bootOnce.getAndSet(true)) return
        try {
            requireNs("compliment.core")
        } catch (_: Throwable) { /* completions optional */
        }
    }


    private fun ensureNs(ns: String): Namespace = Namespace.findOrCreate(Symbol.intern(ns))

    private fun v(ns: String, name: String): Var = Clojure.`var`(ns, name) as Var

    private fun <T> withNs(ns: String, f: () -> T): T {
        val nsObj = ensureNs(ns)
        Var.pushThreadBindings(RT.map(RT.CURRENT_NS, nsObj))  // dynamic *ns*
        return try {
            f()
        } finally {
            Var.popThreadBindings()
        }
    }

    /**
     * Optional helper: require a namespace by symbol, in the current dynamic *ns*.
     * Safer than trying to bindRoot on *ns*.
     */
    private fun requireNs(sym: String) {
        val require = Clojure.`var`("clojure.core", "require")
        require.invoke(Clojure.read(sym))
    }

    /**
     * One top-level form, and the 1-based line of the evaluated text it started on.
     *
     * The line is read from the form's own reader metadata rather than counted afterwards. A form
     * that carries none — a bare literal, so `42` or `"s"` or `:k` — falls back to the reader's
     * current line, which for a literal that fits on one line is the same number.
     */
    private data class ReadForm(val form: Any?, val line: Int)

    /**
     * Reads `code` one top-level form at a time, with line numbers.
     *
     * Read and eval are interleaved rather than read-all-then-eval-all, which is what `load-string`
     * does and what a box wants: the forms above a bad one have already run and printed by the time
     * the bad one is reported. Construct inside [withNs] — `::keywords` and reader aliases resolve
     * against the current namespace.
     */
    private inner class FormSource(code: String) {
        // First, and before any `Clojure.var` below: `var` resolves a name in a namespace, it does
        // not load the namespace, so resolving `clojure.tools.reader/read` on a fresh JVM without
        // this hands back an unbound var that throws on invoke.
        init {
            requireNs("clojure.tools.reader")
            requireNs("clojure.tools.reader.reader-types")
        }

        private val readFn = Clojure.`var`("clojure.tools.reader", "read")
        private val getLineNumber = Clojure.`var`("clojure.tools.reader.reader-types", "get-line-number")
        private val metaFn = Clojure.`var`("clojure.core", "meta")
        private val eof = Any()
        private val opts = PersistentArrayMap.create(mapOf(Keyword.intern("eof") to eof))
        private val reader = Clojure
            .`var`("clojure.tools.reader.reader-types", "indexing-push-back-reader")
            .invoke(java.io.StringReader(code))

        /** The next form, or `null` at end of input. Throws what the reader throws. */
        fun next(): ReadForm? {
            val form = readFn.invoke(opts, reader)
            if (form === eof) return null
            val fromMeta = ((metaFn.invoke(form) as? ILookup)?.valAt(Keyword.intern("line")) as? Number)?.toInt()
            val line = fromMeta ?: (getLineNumber.invoke(reader) as? Number)?.toInt() ?: 1
            return ReadForm(form, line)
        }
    }

    /**
     * Read `code` without evaluating any of it: the editor's syntax check.
     *
     * Reports the number of top-level forms, or the first read error with its line and column. It
     * cannot report anything a *compiler* would say — an unresolvable symbol, a macro that throws,
     * a wrong arity — because none of that is known until the form is evaluated.
     */
    fun compileOnly(code: String, ns: String = defaultNs): CompileOnlyResult {
        boot()
        initNsIfNeeded(ns)

        var forms = 0
        return try {
            withNs(ns) {
                val source = FormSource(code)
                while (source.next() != null) forms++
            }
            CompileOnlyResult.Ok(forms)
        } catch (t: Throwable) {
            CompileOnlyResult.Err(listOf(extractCompilerError(t)))
        }
    }

    private val initted = ConcurrentHashMap.newKeySet<String>()

    private fun initNsIfNeeded(ns: String) {
        if (initted.add(ns)) {
            withNs(ns) {
                // (refer 'clojure.core)
                v("clojure.core", "refer").invoke(Clojure.read("clojure.core"))
                // (require 'field.core) (refer 'field.core) — the box's view of Field, from
                // `src/jvmMain/resources/field/core.clj`. Referred rather than aliased so that
                // `(lines …)` and `(display …)` read the way the Kotlin equivalents do. Guarded
                // the same way Compliment is: a Field whose resources did not ship still runs
                // Clojure, it just has no `field/core` in it.
                try {
                    requireNs("field.core")
                    v("clojure.core", "refer").invoke(Clojure.read("field.core"))
                } catch (t: Throwable) {
                    lprintln("error,clojure", "field.core unavailable in $ns: $t")
                }
            }
        }
    }

    /**
     * Evaluate every top-level form in [code], in order, and report the value of the last one.
     *
     * [onStdout] and [onStderr] are handed the text *and the line of the form that was running when
     * it was written*, which is the whole reason this is a read-eval loop rather than one
     * `load-string` call. Text printed without a newline is buffered by [StreamingWriter] until the
     * next newline or the end of the run, so a bare `(print …)` is attributed to whichever form was
     * running when it finally flushed — usually, but not always, its own. [onForm] is the same line
     * announced *before* the form runs, for a caller that needs it during evaluation rather than
     * after: [ClojureInterface.animate] reads it to remember where a launch was written.
     *
     * A form that throws stops the run: the forms above it have already printed, the ones below it
     * do not run, and the error carries the failing form's line when the exception does not carry
     * one of its own.
     */
    fun evalPage(
        code: String,
        ns: String = defaultNs,
        executeIfFn: Boolean = true,
        onStdout: ((String, Int) -> Unit)? = null,
        onStderr: ((String, Int) -> Unit)? = null,
        onForm: ((Int) -> Unit)? = null
    ): EvalResult {
        boot()
        val evalFn = Clojure.`var`("clojure.core", "eval")
        val prStr = Clojure.`var`("clojure.core", "pr-str")
        // Read by the IO callbacks below, written by the loop: "the line Clojure is on right now".
        var activeLine = -1
        return try {
            initNsIfNeeded(ns)
            var last: Any? = null
            var lastLine = -1
            withNs(ns) {
                withClojureIO(
                    onStdout = onStdout?.let { f -> { text: String -> f(text, activeLine) } },
                    onStderr = onStderr?.let { f -> { text: String -> f(text, activeLine) } }
                ) {
                    val source = FormSource(code)
                    while (true) {
                        val read = try {
                            source.next() ?: break
                        } catch (t: Throwable) {
                            return@withClojureIO EvalResult.Err(atLine(extractCompilerError(t), activeLine))
                        }
                        activeLine = read.line
                        lastLine = read.line
                        onForm?.invoke(read.line)
                        try {
                            last = evalFn.invoke(read.form)
                        } catch (t: Throwable) {
                            return@withClojureIO EvalResult.Err(atLine(extractCompilerError(t), read.line))
                        }
                    }
                    if (executeIfFn && last is IFn) {
                        try {
                            last = (last as IFn).invoke()
                        } catch (t: Throwable) {
                            return@withClojureIO EvalResult.Err(atLine(extractCompilerError(t), lastLine))
                        }
                    }
                    null
                }
            }?.let { return it }

            EvalResult.Ok(EvalValue(last, line = lastLine, printed = printedForm(prStr, last)))
        } catch (t: Throwable) {
            EvalResult.Err(atLine(extractCompilerError(t), activeLine))
        }
    }

    /** `pr-str` of [value], falling back to Java's `toString` if printing it throws. */
    private fun printedForm(prStr: IFn, value: Any?): String = try {
        prStr.invoke(value) as? String ?: value.toString()
    } catch (t: Throwable) {
        value.toString()
    }


    /**
     * One candidate, as Field's popup needs it.
     *
     * [candidate] is the *remainder* — what is left of the name once what the user has typed is
     * taken off the front — because the box path inserts it at the caret rather than replacing a
     * range. [displayText] is the whole name, which is what the popup shows.
     */
    data class CompletionItem(
        val candidate: String,
        val type: String? = null,
        val doc: String? = null,
        val ns: String? = null,
        val displayText: String? = null,
        /** Argument lists, as Clojure prints them: `([coll] [n coll])`. Empty for a non-function. */
        val arglists: String? = null
    )

    /**
     * Candidates at [cursor], from Compliment.
     *
     * Three things here are not obvious and all three are what makes the popup useful rather than
     * merely present:
     *
     *  - The namespace is *initialised*, not just created. Compliment completes what a namespace can
     *    see, and a bare `create-ns` sees nothing at all — not even `clojure.core` — so completion
     *    in a box that has never been run used to return `map` and `defn` and `lines` for nobody.
     *    [initNsIfNeeded] is the same call the evaluator makes.
     *  - `:extra-metadata` is asked for. Without it Compliment returns names and types and no
     *    documentation whatsoever, which is why [CompletionItem.doc] used to be uniformly null and
     *    the documentation pane beside the popup uniformly empty.
     *  - The surrounding form is passed as `:context`, with the prefix replaced by Compliment's
     *    `__prefix__` marker. That is what lets it offer *local* names — a `let` binding, a `fn`
     *    parameter, a `doseq` variable — which no amount of namespace introspection can know. It is
     *    also the part that can fail, because half-typed code does not read: on any throw this
     *    retries once without the context, so the answer degrades to "globals only" rather than to
     *    nothing.
     *
     * Returns an empty list when Compliment is not on the classpath, rather than throwing.
     * Completion is the one part of this language that is allowed to be absent.
     */
    fun complete(code: String, cursor: Int, ns: String = defaultNs): List<CompletionItem> {
        boot()

        val completionsVar = try {
            varv("compliment.core", "completions")
        } catch (t: Throwable) {
            lprintln("error,clojure", "no Compliment on the classpath, so no completions: $t")
            return emptyList()
        }

        // Determine effective namespace WITHOUT evaluating the buffer.
        val effectiveNs = extractNsNameFromCode(code) ?: ns
        val prefix = extractPrefix(code, cursor)
        initNsIfNeeded(effectiveNs)

        val candidates = withNs(effectiveNs) {
            val withContext = try {
                completionsVar.invoke(prefix, completionOptions(effectiveNs, completionContext(code, cursor, prefix)))
            } catch (t: Throwable) {
                lprintln("debug,clojure", "completion context rejected, retrying without it: $t")
                null
            }
            withContext ?: try {
                completionsVar.invoke(prefix, completionOptions(effectiveNs, null))
            } catch (t: Throwable) {
                lprintln("error,clojure", "Compliment threw: $t")
                null
            }
        }

        val seq: ISeq = RT.seq(candidates) ?: return emptyList()
        val out = mutableListOf<CompletionItem>()
        var cur: ISeq? = seq
        while (cur != null) {
            val m = cur.first() as? ILookup
            if (m != null) {
                fun k(key: String): String? = m.valAt(Keyword.intern(key))?.toString()

                val cand = k("candidate")
                if (cand != null) {
                    out += CompletionItem(
                        candidate = if (cand.startsWith(prefix)) cand.substring(prefix.length) else cand,
                        type = k("type")?.removePrefix(":"),
                        doc = k("doc"),
                        ns = k("ns"),
                        displayText = cand,
                        arglists = arglistsText(m.valAt(Keyword.intern("arglists")))
                    )
                }
            }
            cur = cur.next()
        }
        return out
    }

    /**
     * Compliment's `:arglists` as one line of the thing a reader wants: `[coll] [f coll]`.
     *
     * It arrives as a Clojure *collection of strings*, each string being a printed argument vector,
     * so `toString` on it gives `("[coll]" "[f coll]")` — quotes, parens and all, which is what the
     * completion popup was showing in the grey tail beside every function name. `null` for a
     * candidate that is not callable, which is most of them.
     */
    private fun arglistsText(raw: Any?): String? {
        val items = when (raw) {
            null -> return null
            is Iterable<*> -> raw.mapNotNull { it?.toString() }
            else -> listOf(raw.toString())
        }
        return items.joinToString(" ").takeIf { it.isNotBlank() }
    }

    /** `{:ns 'the.ns :extra-metadata #{:doc :arglists} :context "…"}` for `compliment.core/completions`. */
    private fun completionOptions(ns: String, context: String?): IPersistentMap {
        val extras = PersistentHashSet.create(Keyword.intern("doc"), Keyword.intern("arglists"))
        val pairs = mutableMapOf<Any, Any>(
            Keyword.intern("ns") to Symbol.intern(ns),
            Keyword.intern("extra-metadata") to extras
        )
        if (context != null) pairs[Keyword.intern("context")] = context
        return PersistentArrayMap.create(pairs)
    }

    /**
     * The code **up to** the cursor, with the prefix replaced by `__prefix__`, closed.
     *
     * Compliment reads this to work out what the caret is inside, which is the only way it can know
     * about a local: a `let` binding, a `fn` parameter, a `doseq` variable. It has to *read* it, and
     * code being typed rarely reads — a caret at the end of `(let [spiral 1]\n  (lines [sp` sits
     * inside three unclosed brackets. So the brackets still open at the caret are closed, by walking
     * the text with a bracket stack and appending their closers.
     *
     * **The text after the caret is dropped, and that is the load-bearing half.** Every form the
     * caret is inside opens *before* it, so the tail carries no scope — and in a real editor the
     * tail is not the reader's text at all, it is whatever Monaco's auto-closing left there. The
     * buffer measured from a live Clojure box on 2026-09-13 was
     * `(let [spline (polyline [[0 0] [10 10]])]\n  (lines [sp)` — note the final `)`, auto-inserted
     * for `(lines` and now sitting where the `[` wants its `]`. Kept, that tail makes the context
     * `[__prefix__)))`, which does not read, and Compliment answers a context it cannot read with
     * globals only and no complaint: the popup listed functions, a class and a var, and not the
     * local two lines up. Dropped, the same caret gets `(lines [__prefix__]))` and the local is
     * there. Every unit test that passes a string ending at the caret was already exercising the
     * good case, which is why this survived one.
     *
     * Strings, character literals and `;` comments are skipped rather than scanned, so a `"("` or a
     * `\(` does not open anything. The caller still treats a rejection as "no context" — this makes
     * the good case common, it does not promise the string reads.
     */
    private fun completionContext(code: String, cursor: Int, prefix: String): String {
        val caret = cursor.coerceIn(0, code.length)
        val start = (caret - prefix.length).coerceIn(0, caret)
        val marked = code.substring(0, start) + "__prefix__"
        return marked + closersFor(marked)
    }

    /** The closing brackets [text] is missing, innermost first: `"))"`, `"])"`, `""`. */
    private fun closersFor(text: String): String {
        val open = ArrayDeque<Char>()
        var i = 0
        while (i < text.length) {
            when (val c = text[i]) {
                ';' -> while (i < text.length && text[i] != '\n') i++
                '"' -> {
                    i++
                    while (i < text.length && text[i] != '"') {
                        if (text[i] == '\\') i++
                        i++
                    }
                    i++
                }
                // A character literal: `\(`, `\"`, `\newline`. Only the first character after the
                // backslash can be a bracket, and skipping it is enough — the rest of a named
                // character is ordinary symbol text.
                '\\' -> i += 2
                '(', '[', '{' -> {
                    open.addLast(c)
                    i++
                }
                ')', ']', '}' -> {
                    open.removeLastOrNull()
                    i++
                }
                else -> i++
            }
        }
        return open.reversed().joinToString("") {
            when (it) {
                '(' -> ")"
                '[' -> "]"
                else -> "}"
            }
        }
    }

    /**
     * Very small parser: find (ns foo.bar …) and return "foo.bar".
     * This is tolerant of leading whitespace/comments and line breaks,
     * but intentionally does NOT read/eval the form.
     */
    private fun extractNsNameFromCode(code: String): String? {
        // Regex looks for: (ns <sym or dotted-sym>
        // Keep it conservative to avoid false positives in comments/strings.
        val nsRegex = Regex("""\(\s*ns\s+([a-zA-Z_][\w\.\-\+]*)""")
        val match = nsRegex.find(code)
        return match?.groups?.get(1)?.value
    }

    /**
     * Extract a completion prefix ending at `cursor`.
     * Supports qualified symbols like "str/joi" and member access ".toStr".
     * Purely lexical: no reading or eval.
     */
    private fun extractPrefix(code: String, cursor: Int): String {
        val i = cursor.coerceIn(0, code.length)
        if (i == 0) return ""
        // Allowed chars inside a Clojure symbol segment
        fun isSymChar(ch: Char): Boolean =
            ch.isLetterOrDigit() || ch in listOf('-', '_', '!', '?', '*', '\'', '+', '=', '<', '>', '$')

        // Walk left to include "/seg" for aliases/qualified, and "." for member/method start
        var start = i - 1
        var sawSlash = false
        var sawDot = false

        while (start >= 0) {
            val ch = code[start]
            if (isSymChar(ch)) {
                start--
                continue
            }
            if (ch == '/' && !sawSlash) {
                sawSlash = true
                start--
                continue
            }
            if (ch == '.' && !sawDot) {
                sawDot = true
                start--
                continue
            }
            break
        }
        val s = (start + 1).coerceAtMost(i)
        return code.substring(s, i)
    }

}

/**
 * What a Clojure box can reach of Field, and the Kotlin half of `field/core.clj`.
 *
 * Clojure's Java interop is good enough that most of Field needs nothing here: `(afield.graphics.FLine.)`
 * constructs an `FLine`, `(.moveTo f 0 0 0)` builds it, and `(.getChildren (box))` walks the
 * document. What interop is *bad* at is the two shapes Field's Kotlin API uses most — an extension
 * function (compiled to a static whose class name is a file name, `SomeSkikoKt`) and a default
 * argument (not a default at all from outside Kotlin, so every parameter has to be passed). So this
 * is a thin facade over exactly those, and deliberately nothing else: everything below is a
 * one-liner, and a verb that reads well as raw interop does not belong here.
 *
 * [thisBox] is the same device as `PythonInterface.thisBox` and carries the same caveat: box code
 * runs on the main coroutine, one fragment at a time, so a single slot is enough — but it *is* a
 * single slot, set for the duration of one fragment and restored afterwards.
 */
object ClojureInterface {

    /**
     * The box whose code is running, or `null` outside a box.
     *
     * A `@JvmField` rather than a property so that Clojure can read it as `ClojureInterface/thisBox`
     * instead of `(ClojureInterface/getThisBox)`, which is what `(box)` in `field.core` does.
     */
    @JvmField
    var thisBox: Box? = null

    /**
     * The box line of the top-level form being evaluated, or `-1` outside one.
     *
     * Written by [ClojureExecution] as it walks the forms, and read by anything host-side that has
     * to report a position *later* — [animate] is the one that needs it, because a function that
     * throws on frame 900 has a stack trace full of Clojure's own source and nothing that points
     * back at the box.
     */
    @JvmField
    var thisLine: Int = -1

    /**
     * The `Frames` whose body is running, or `null` outside a frame.
     *
     * The receiver a Kotlin `animate { }` block gets for free. Clojure's function has no receiver,
     * so the two things a body actually reaches for — the frame number and "stop now" — come from
     * here, via `(frame)` and `(end)` in `field.core`.
     */
    @JvmField
    var currentFrames: Frames? = null

    /**
     * A `Frames` that calls [body] once per frame: `animate { … }` for a Clojure box.
     *
     * Returning one of these from a box is what launches it, by exactly the rule a Kotlin box
     * follows — `ReturnValueRegistry` sees a `Frames` come back and drives it from the Runloop. Code
     * before the `(animate …)` form runs once, on the run; [body] runs once per frame after that.
     *
     * A throw from [body] is caught here rather than by the launcher, for the sake of the line
     * number: the launcher digs one out of the stack trace by matching the box id against
     * `StackTraceElement.fileName`, which works for compiled box Kotlin and never matches a Clojure
     * fn, leaving it to report the first frame of Clojure's internals instead. So this reports
     * against the line the `(animate …)` form was on and then ends the animation, which is what a
     * Kotlin launch does when its body throws.
     */
    @JvmStatic
    fun animate(body: IFn): Frames {
        // Captured when the animation is *built*, which is while the box is running and these are
        // set; re-established below for each frame, because by then the run has finished and put
        // them back.
        val box = thisBox
        val line = thisLine
        return eachFrame {
            val previousFrames = currentFrames
            val previousBox = thisBox
            val previousLine = thisLine
            currentFrames = this
            thisBox = box
            thisLine = line
            try {
                body.invoke()
            } catch (t: Throwable) {
                box?.error(t.message ?: t.javaClass.name, "code", line)
                end()
            } finally {
                currentFrames = previousFrames
                thisBox = previousBox
                thisLine = previousLine
            }
        }
    }

    /**
     * Stop the animation whose frame is running — `(end)` in `field.core`.
     *
     * `Frames.end()` is a suspend function, so Clojure cannot call it. Cancelling the job is what it
     * does for a launch that has started, and inside a frame body it always has. Outside a frame
     * body this does nothing rather than throwing: "end what is running" and nothing is.
     */
    @JvmStatic
    fun endAnimation() {
        currentFrames?.job?.cancel()
    }

    /** The frame number of the animation being run, or `0` outside one. */
    @JvmStatic
    fun frameNumber(): Int = currentFrames?.frameNumber ?: 0

    /**
     * `Box.lines(…)` with every argument spelled out, for `(lines …)` in `field.core`.
     *
     * @param m the lines to draw; a Clojure vector of `FLine`s arrives as a `List`.
     * @param channel output channel (disclosure panel) to draw into.
     * @param w output width in pixels, `-1.0` to derive it from the geometry.
     * @param h output height in pixels, `-1.0` to derive it from the geometry.
     * @param fitToSize scale and centre the geometry into [w] x [h].
     */
    @JvmStatic
    fun lines(box: Box, m: List<FLine>, channel: String, w: Double, h: Double, fitToSize: Boolean) {
        box.lines(m, channel = channel, w = w, h = h, fitToSize = fitToSize)
    }

    /**
     * An [FLine] through [points], each a 2- or 3-element sequence of numbers.
     *
     * The one piece of geometry building that is worth a facade, because it is the one every example
     * starts with and `(doto (FLine.) (.moveTo 0 0 0) (.lineTo 1 1 0))` puts the shape of the loop
     * ahead of the shape of the line. Anything richer — cubics, per-node colour — is `FLine`'s own
     * API, reached by interop.
     *
     * @throws IllegalArgumentException if a point is not a sequence of two or three numbers.
     */
    @JvmStatic
    fun polyline(points: List<*>): FLine {
        val f = FLine()
        points.forEachIndexed { index, point ->
            val xyz = (point as? Iterable<*>)?.map { it as? Number ?: badPoint(index, point) }
                ?: badPoint(index, point)
            if (xyz.size !in 2..3) badPoint(index, point)
            val z = xyz.getOrNull(2) ?: 0
            if (index == 0) f.moveTo(xyz[0], xyz[1], z) else f.lineTo(xyz[0], xyz[1], z)
        }
        return f
    }

    private fun badPoint(index: Int, point: Any?): Nothing = throw IllegalArgumentException(
        "point $index is $point; polyline wants a sequence of [x y] or [x y z] numbers"
    )

    /**
     * [line] with `FLine.color` set, returned so it can be threaded. Alpha included: lines blend.
     *
     * Spelled `colour` here and `color` in `field.core`, because the extension property this sets
     * is `color` and a facade that renames the thing it wraps is a facade you have to look up.
     */
    @JvmStatic
    fun colour(line: FLine, r: Double, g: Double, b: Double, a: Double): FLine {
        line.color = vec(r, g, b, a)
        return line
    }

    /** [line] with `FLine.thickness` set, returned so it can be threaded. */
    @JvmStatic
    fun thickness(line: FLine, width: Double): FLine {
        line.thickness = width
        return line
    }
}

@RunsAt(plugins)
fun install() {
    TopologyWatcher("clojure") { clojureTopologyNeedsUpdating(it) }.start()
}

/**
 * A Clojure line, in the box's coordinates.
 *
 * Clojure counts the lines of the text it was handed from 1; `execute.line` is where that text
 * starts in the box, counted from 0. So the sum is the box line the editor draws against, which is
 * the same arithmetic `PythonInterface.resultLineFor` does (`startLine + count` of the lines) and
 * the reason running one form reports beside *that* form rather than at the top of the box.
 *
 * Returns `-1` — "no line, put it under the box" — for a line Clojure could not supply.
 */
private fun boxLineFor(execute: Execute): (Int?) -> Int =
    { line -> if (line != null && line >= 1) execute.line + line else -1 }

/** The two launch modes the front end knows, spelled the way every other language spells them. */
private const val PENDING_LAUNCH_MODE = "launching"
private const val ACTIVE_LAUNCH_MODE = "launch"

class ClojureExecution(val inside: Box) : BoxExecution {
    val clj = ClojureHost(defaultNs = "live.user")

    /**
     * What one evaluation of a box came to: whether it got to the end, and the value of its last
     * form with the text and line the echo would use.
     *
     * [printedAnything] is the one non-obvious member and it is why this type exists rather than a
     * bare `Any?`: the conditional echo ("show the last value, unless the box already said
     * something") is a decision each caller makes, and it cannot be made from the value alone.
     */
    private class EvalOutcome(
        val ok: Boolean,
        val value: Any? = null,
        val printed: String? = null,
        val line: Int = -1,
        val printedAnything: Boolean = false
    )

    /**
     * Read, then evaluate, `execute.code`, reporting output and errors against [inside].
     *
     * Everything a Clojure box does, both halves of it: the reader check that turns an unbalanced
     * paren into a marker on the right line, and the evaluation whose printed output goes beside the
     * form that printed it. The one thing it does *not* do is publish the last value — that is the
     * only thing [executeFragment] and [launchAll] disagree about, so it is theirs to decide.
     */
    private fun evaluateReporting(inside: Box, execute: Execute): EvalOutcome {
        val boxLine = boxLineFor(execute)

        val compiled = clj.compileOnly(execute.code)
        if (compiled is CompileOnlyResult.Err) {
            // Unreadable, so there is nothing to evaluate — and evaluating anyway would report the
            // identical reader error a second time.
            lprintln("error,clojure", "Syntax errs: ${compiled.errors}")
            val first = compiled.errors[0]
            inside.error(first.message, line = boxLine(first.line), property = "code")
            return EvalOutcome(ok = false)
        }
        lprintln("info,clojure", "OK, ${(compiled as CompileOnlyResult.Ok).formsCount} forms")

        var hasOutput = false
        var wroteToStderr = false
        val previousBox = ClojureInterface.thisBox
        val previousLine = ClojureInterface.thisLine
        ClojureInterface.thisBox = inside
        try {
            return when (val e = clj.evalPage(
                execute.code,
                executeIfFn = false,
                onStderr = { text, line ->
                    hasOutput = true
                    wroteToStderr = true
                    inside.error(text, "code", boxLine(line))
                },
                onStdout = { text, line ->
                    hasOutput = true
                    inside.output.proxy(text, false, boxLine(line))
                },
                onForm = { line -> ClojureInterface.thisLine = boxLine(line) }
            )) {
                is EvalResult.Ok -> {
                    // Clear the marker a *previous* failed run left, so fixing a typo and re-running
                    // takes the red away. Skipped when this run wrote to `*err*`, which arrives as
                    // an error and would otherwise be cleared by its own success.
                    if (!wroteToStderr) inside.error(null, "code", -1)
                    EvalOutcome(
                        ok = true,
                        value = e.result.value,
                        printed = e.result.printed ?: e.result.value.toString(),
                        line = boxLine(e.result.line),
                        printedAnything = hasOutput
                    )
                }

                is EvalResult.Err -> {
                    lprintln("error,clojure", "Runtime err: ${e.error}")
                    inside.error(e.error.message, line = boxLine(e.error.line), property = "code")
                    EvalOutcome(ok = false, printedAnything = hasOutput)
                }
            }
        } finally {
            ClojureInterface.thisBox = previousBox
            ClojureInterface.thisLine = previousLine
        }
    }

    /**
     * The last value, beside the form that produced it — but only if there is one worth showing.
     *
     * Two suppressions, both of them the ones a Python cell makes. A box that has already printed
     * does not also get told what its last form returned. And a box whose last form returned `nil`
     * gets nothing: `nil` is what `println` and `(lines …)` evaluate to, so echoing it would put
     * the word "nil" beside the last line of almost every box that draws something. (`def` is not
     * one of those — it evaluates to the var — so a box that only defines things still echoes.)
     */
    private fun echoValue(inside: Box, outcome: EvalOutcome) {
        if (outcome.ok && !outcome.printedAnything && outcome.value != null) {
            inside.output.proxy(outcome.printed ?: outcome.value.toString(), false, outcome.line)
        }
    }

    override fun executeFragment(inside: Box, execute: Execute, reply: (Message) -> Unit) {
        // Captured out here, like DatalogExecution does: `thisSession` is per-thread and `inMain`
        // resumes on the runloop's thread, where it is empty.
        evaluateOnce(inside, execute, "fragment", reply)
    }

    /**
     * One evaluation of the box, reported under [mode].
     *
     * `executeFragment` and `executeAll` do the same thing here — Clojure has no separate
     * whole-buffer path — but they must not claim the same thing: `ExecutionReports` only records a
     * *setup* from a mode that is not `"fragment"`, so running a box would otherwise never register
     * as having been set up.
     */
    private fun evaluateOnce(inside: Box, execute: Execute, mode: String, reply: (Message) -> Unit) {
        val originSession = thisSession.get()
        val trace = ExecutionTrace.forEngine(inside, execute, mode, reply)
        inMain {
            val wm = ++inside.fragmentGeneration
            configureOutputProxies(inside, trace.reply, originSession)
            send(Message("is-running").payload(IsRunning(execute.uid, true, wm, "__fragment__")))
            var outcome: EvalOutcome? = null
            try {
                outcome = evaluateReporting(inside, execute)
                echoValue(inside, outcome)
            } finally {
                trace.finish(outcome?.ok == true)
                send(Message("is-running").payload(IsRunning(execute.uid, false, wm, "__fragment__")))
            }
        }
    }

    override fun executeAll(inside: Box, execute: Execute, reply: (Message) -> Unit) {
        evaluateOnce(inside, execute, "run", reply)
    }

    /**
     * Launch: run the box once, then hand its last value to the same registry a Kotlin box's return
     * value goes through.
     *
     * So `(animate (fn [] …))` as the last form animates, for exactly the reason it does in Kotlin —
     * the value is a `Frames` and `ReturnValueRegistry` knows how to drive one from the Runloop —
     * and no special case here says the word "animate". A value the registry does not recognise is
     * not an error: the box reports it the way a run would and goes quiet, which is what a launch of
     * a box that turned out not to be an animation should look like.
     */
    override fun launchAll(
        inside: Box, execute: Execute, code: String, strand: String, reply: (Message) -> Unit
    ) {
        val originSession = thisSession.get()
        val launchEpoch = inside.captureLaunchEpoch()
        val trace = ExecutionTrace.forEngine(inside, execute, "launch", reply)
        inMain {
            // A stop that landed between the request and here cancels the launch rather than
            // racing it. Same guard, same reason, as FieldMLExecution.launchAll.
            if (!inside.isLaunchEpochCurrent(launchEpoch)) {
                // The awaiting caller still needs an answer, and "cancelled" is the honest one.
                trace.finish(false, "launch cancelled by a stop before it started")
                return@inMain
            }
            configureOutputProxies(inside, trace.reply, originSession)

            val generation = inside.runGeneration.getOrPut(strand) { -1 } + 1
            inside.runGeneration[strand] = generation
            inside.currentRunningStates[strand] = RunningState(generation, PENDING_LAUNCH_MODE)
            send(Message("is-running").payload(IsRunning(execute.uid, true, generation, strand, PENDING_LAUNCH_MODE)))

            val outcome = evaluateReporting(inside, execute.copy(code = code))
            val consumed = outcome.ok && ReturnValueRegistry.launchWithGeneration(
                inside,
                outcome.value,
                strand,
                generation,
                launchEpoch,
            ) { isRunning, notifiedStrand, watermark ->
                if (isRunning) {
                    inside.currentRunningStates[notifiedStrand] = RunningState(watermark, ACTIVE_LAUNCH_MODE)
                } else if (inside.currentRunningStates[notifiedStrand]?.watermark == watermark) {
                    inside.currentRunningStates.remove(notifiedStrand)
                }
                send(
                    Message("is-running")
                        .payload(IsRunning(execute.uid, isRunning, watermark, notifiedStrand, ACTIVE_LAUNCH_MODE))
                )
            }

            if (!consumed) {
                echoValue(inside, outcome)
                if (inside.currentRunningStates[strand]?.watermark == generation) {
                    inside.currentRunningStates.remove(strand)
                }
                send(
                    Message("is-running")
                        .payload(IsRunning(execute.uid, false, generation, strand, PENDING_LAUNCH_MODE))
                )
            }
            // Setup is what a launch receipt is about, exactly as it is for Kotlin: the box
            // evaluated and its value was either taken up by the registry or echoed. Whether an
            // animation the registry is now driving stays healthy is not something this can claim.
            trace.finish(outcome.ok && inside.isLaunchEpochCurrent(launchEpoch))
        }
    }

    /**
     * Stop: cancel the jobs this box launched.
     *
     * It does not interrupt an *evaluation*. A Clojure form runs to completion on the runloop, so a
     * box in an infinite `loop` is not stoppable by this or by anything else short of killing
     * Field — the same as a Kotlin box in a `while (true)` that never suspends. What this does stop
     * is every launched animation, by the standard barrier: bump the launch epoch, bump every
     * strand's generation (which is what each `Frames`'s `shouldend` is watching), cancel the jobs.
     */
    override fun stop(inside: Box) {
        inside.stopTrackedJobs()
    }

    override fun complete(
        inside: Box, completionRequest: CompletionRequest, reply: (Message) -> Unit
    ) {
        // Convert (line, column) to absolute cursor index for clj.complete
        val code = completionRequest.allCode
        val cursor = try {
            val pos = completionRequest.position
            if (pos >= 0 && pos <= code.length) {
                pos
            } else {
                val lineIdx = (completionRequest.line - 1).coerceAtLeast(0)
                val col0 = (completionRequest.column - 1).coerceAtLeast(0)
                val parts = code.split("\n", limit = -1)
                val safeLineIdx = lineIdx.coerceAtMost(parts.size - 1)
                val priorChars = if (safeLineIdx <= 0) 0 else (0 until safeLineIdx).sumOf { parts[it].length + 1 }
                val safeCol = if (safeLineIdx in parts.indices) col0.coerceAtMost(parts[safeLineIdx].length) else 0
                priorChars + safeCol
            }
        } catch (_: Throwable) { 0 }

        val metadata = clj.complete(code, cursor).map {
            Metadata(
                icon = it.type ?: "keyword",
                // The tail is the grey text after the name in the popup, so it wants the most
                // useful short thing available: how to call it beats what kind of thing it is.
                tail = it.arglists ?: it.type ?: "keyword",
                documentation = completionDocumentation(it),
                text = it.candidate,
                displayText = it.displayText ?: "",
                category = clojureCompletionCategory(it.type),
                prefix = it.ns ?: ""
            )
        }
        if (metadata.isNotEmpty()) {
            reply(
                Message("complete").payload(

                    Completion(
                        boundsStart = -1,
                        boundsEnd = -1,
                        lineStart = completionRequest.line,
                        columnsStart = completionRequest.column - 1,
                        useLineColumns = true,
                        metadata = metadata
                    )
                )
            )
        }
    }

    /**
     * What the documentation pane beside the popup shows: where the name lives, how to call it, and
     * its docstring, in that order — the three lines `clojure.repl/doc` prints, minus the ceremony.
     */
    private fun completionDocumentation(item: ClojureHost.CompletionItem): String = buildString {
        val qualified = listOfNotNull(item.ns?.takeIf { it.isNotBlank() }, item.displayText).joinToString("/")
        if (qualified.isNotBlank()) appendLine(qualified)
        item.arglists?.takeIf { it.isNotBlank() }?.let { appendLine(it) }
        item.doc?.takeIf { it.isNotBlank() }?.let { append(it) }
    }.trim()

    override fun executeSymbol(inside: Box, executeSymbol: ExecuteSymbol, reply: (Message) -> Unit) {
        // For Clojure, construct a function call: (symbolName)
        val functionCall = "(${executeSymbol.symbol})"
        
        // Create an Execute object for executeFragment
        val exec = Execute(
            uid = executeSymbol.uid,
            type = "fragment",
            line = executeSymbol.line,
            code = functionCall,
            singleLine = true
        )
        
        // Execute as a fragment
        executeFragment(inside, exec, reply)
    }

    override val languageName: String
        get() = "clojure"

}

var Box.isClojureRoot by Delegation<Boolean?>()

@RunsAt(early)
fun installClojure() {
    Box.ioPersistentProperties.add("isClojureRoot")
    registerMainLanguageRuntime(
        MainLanguageRuntime(
            id = "clojure",
            label = "Clojure",
            runtimeClass = ClojureExecution::class,
            setLegacyRoot = { box, enabled -> box.isClojureRoot = enabled },
            applyTopology = ::clojureTopologyNeedsUpdating,
        ),
    )
}

fun clojureTopologyNeedsUpdating(values: Collection<Box>) {
    applyRuntimeToggle(
        boxes = values,
        isRoot = { it.isClojureRoot == true || it.isMainLanguage("clojure") },
        runtimeClass = ClojureExecution::class,
        newExecution = { child -> ClojureExecution(child) },
        language = "Clojure",
        customization = "",
        notifyCategory = "clojure"
    )
}
