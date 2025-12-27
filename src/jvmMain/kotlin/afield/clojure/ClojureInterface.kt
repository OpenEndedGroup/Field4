@file:Suppress("UNCHECKED_CAST")

package afield.clojure

import afield.*
import afield.Box
import afield.runtime.TopologyWatcher
import afield.runtime.applyRuntimeToggle
import afield.stdlib.Delegation
import clojure.java.api.Clojure
import clojure.lang.*
import java.io.PrintWriter
import java.io.Writer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

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
    val value: Any?, val valueClass: String? = value?.let { it::class.java.name }
)

sealed class EvalResult {
    data class Ok(val result: EvalValue) : EvalResult()
    data class Err(val error: CompileError) : EvalResult()
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
 * Very small tokenizer to get the "prefix" (symbol fragment) immediately before `cursor`.
 * Clojure symbol characters are fairly permissive; this is a pragmatic subset.
 */
private fun extractPrefix(code: String, cursor: Int): String {
    if (cursor <= 0 || cursor > code.length) return ""
    val allowed = "-+\\*/_?!.<>=:$%&^#'\"\\w".toRegex() // includes letters, digits, underscores, many punctuations
    var i = cursor - 1
    while (i >= 0 && code[i].toString().matches(allowed)) i--
    val start = (i + 1).coerceAtLeast(0)
    return code.substring(start, cursor)
}

/**
 * Evaluate a single Clojure expression: (load-string code) inside a namespace.
 * Returns the last value. If that value is IFn, we also invoke it with no args ("execute what returns").
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

    // compileOnly(...)
    fun compileOnly(code: String, ns: String = defaultNs): CompileOnlyResult {
        boot()
        initNsIfNeeded(ns)

        val errors = mutableListOf<CompileError>()
        var forms = 0

        requireNs("clojure.tools.reader")
        requireNs("clojure.tools.reader.reader-types")

        val readFn = Clojure.`var`("clojure.tools.reader", "read")
        val pushbackReaderFn = Clojure.`var`("clojure.tools.reader.reader-types", "indexing-push-back-reader")

        return try {
            withNs(ns) {
                val rdr = pushbackReaderFn.invoke(java.io.StringReader(code))
                val eof = Object()
                val opts = PersistentArrayMap.create(mapOf(Keyword.intern("eof") to eof))
                while (true) {
                    val form = readFn.invoke(opts, rdr)
                    if (form === eof) break
                    forms++
                }
            }
            if (errors.isEmpty()) CompileOnlyResult.Ok(forms) else CompileOnlyResult.Err(errors)
        } catch (t: Throwable) {
            errors += extractCompilerError(t)
            CompileOnlyResult.Err(errors)
        }
    }

    private val initted = ConcurrentHashMap.newKeySet<String>()

    private fun initNsIfNeeded(ns: String) {
        if (initted.add(ns)) {
            withNs(ns) {
                // (refer 'clojure.core)
                v("clojure.core", "refer").invoke(Clojure.read("clojure.core"))
            }
        }
    }

    fun evalPage(
        code: String,
        ns: String = defaultNs,
        executeIfFn: Boolean = true,
        onStdout: ((String) -> Unit)? = null,
        onStderr: ((String) -> Unit)? = null
    ): EvalResult {
        boot()
        return try {
            initNsIfNeeded(ns)
            val result = withNs(ns) {
                withClojureIO(onStdout, onStderr) {
                    // load-string will cause any (println ...) to flow into onStdout
                    Clojure.`var`("clojure.core", "load-string").invoke(code)
                }
            }
            val finalVal = if (executeIfFn && result is IFn) {
                try {
                    withNs(ns) {
                        withClojureIO(onStdout, onStderr) {
                            result.invoke()
                        }
                    }
                } catch (t: Throwable) {
                    return EvalResult.Err(extractCompilerError(t))
                }
            } else result

            EvalResult.Ok(EvalValue(finalVal))
        } catch (t: Throwable) {
            EvalResult.Err(extractCompilerError(t))
        }
    }


    data class CompletionItem(
        val candidate: String,
        val type: String? = null,
        val doc: String? = null,
        val ns: String? = null,
        val displayText: String? = null
    ) {

    }

    fun complete(code: String, cursor: Int, ns: String = defaultNs): List<CompletionItem> {
        boot()

        // Defensive: Compliment must be present
        val completionsVar = try {
            varv("compliment.core", "completions")
        } catch (_: Throwable) {
            throw IllegalStateException(
                "Compliment not found on classpath. Add `implementation(\"compliment:compliment:0.5.5\")` and the Clojars repo."
            )
        }

        // Determine effective namespace WITHOUT evaluating buffer
        val effectiveNs = extractNsNameFromCode(code) ?: ns

        // Build {:ns 'your.ns}
        val opts = PersistentArrayMap.create(
            mapOf(
                Keyword.intern("ns") to Symbol.intern(effectiveNs)
            )
        )

        // Determine the completion prefix at the cursor (pure string logic)
        val prefix = extractPrefix(code, cursor)

        // Ensure a clojure.lang.Namespace exists; safe and side-effect free
        ensureNamespaceExists(effectiveNs)

        // Ask compliment; no user code is run
        val res = withNs(effectiveNs) {
            // (compliment.core/completions prefix {:ns 'ns})
            completionsVar.invoke(prefix, opts)
        }

        val seq: ISeq = RT.seq(res) ?: return emptyList()
        val out = mutableListOf<CompletionItem>()
        var cur: ISeq? = seq
        while (cur != null) {
            val m = cur.first() as? IPersistentMap
            if (m != null) {
                fun k(key: String): String? =
                    (m as ILookup).valAt(Keyword.intern(key))?.toString()

                val cand = k("candidate")
                if (cand != null) {
                    out += CompletionItem(
                        candidate = if (cand.startsWith(prefix)) cand.substring(prefix.length) else cand,
                        type = k("type"),
                        doc = k("doc"),
                        ns = k("ns"),
                        cand
                    )
                }
            }
            cur = cur.next()
        }
        return out
    }

    /** Create the namespace symbolically if it doesn't exist; no requires, no side effects. */
    private fun ensureNamespaceExists(nsName: String) {
        val findNsVar = RT.`var`("clojure.core", "find-ns")
        val createNsVar = RT.`var`("clojure.core", "create-ns")
        val sym = Symbol.intern(nsName)
        val existing = findNsVar.invoke(sym)
        if (existing == null) {
            createNsVar.invoke(sym) // safe: just allocates a namespace object
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

@RunsAt(plugins)
fun install() {
    TopologyWatcher("clojure") { clojureTopologyNeedsUpdating(it) }.start()
}

class ClojureExecution(val inside: Box) : BoxExecution {
    val clj = ClojureHost(defaultNs = "live.user")

    init {
    }

    override fun executeFragment(inside: Box, execute: Execute, reply: (Message) -> Unit) {
        inMain {
            val wm = ++inside.fragmentGeneration

            configureOutputProxies(inside, reply)

            send(Message("is-running").payload(IsRunning(execute.uid, true, wm, "__fragment__")))

            try {
                when (val c = clj.compileOnly(execute.code)) {
                    is CompileOnlyResult.Ok -> println("OK, ${c.formsCount} forms")
                    is CompileOnlyResult.Err -> {
                        // show c.errors to the user with line/col
                        println("Syntax errs: ${c.errors}")
                        inside.error(c.errors[0].message, line = c.errors[0].line ?: -1, property = "code")
                    }
                }

                var hasOutput = false
                when (val e = clj.evalPage(execute.code, executeIfFn = false, onStderr = {
                    hasOutput = true
                    inside.error(
                        it, "code", -1
                    )
                }, onStdout = {
                    hasOutput = true
                    inside.output.proxy(
                        it, false, -1
                    )
                })) {
                    is EvalResult.Ok -> {
//                    println("Result: ${e.result.value} :: ${e.result.valueClass}")
                        if (!hasOutput)
                        inside.output.proxy(
                            e.result.value.toString(), false, execute.line + execute.code.split("\n").count()
                        )
                    }

                    is EvalResult.Err -> {
                        println("Runtime err: ${e.error}")
                        inside.error(e.error.message, line = e.error.line ?: -1, property = "code")
                    }
                }
            } finally {
                send(Message("is-running").payload(IsRunning(execute.uid, false, wm, "__fragment__")))
            }
        }

    }

    override fun executeAll(inside: Box, execute: Execute, reply: (Message) -> Unit) {
        executeFragment(inside, execute, reply)
    }

    override fun launchAll(
        inside: Box, execute: Execute, code: String, strand: String, reply: (Message) -> Unit
    ) {
        executeFragment(inside, execute, reply)
    }

    override fun stop(inside: Box) {
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
                icon = it.type?: "keyword",
                tail = it.type?: "keyword",
                documentation = it.doc?: "",
                text = it.candidate,
                displayText = it.displayText?: ""
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
}

fun clojureTopologyNeedsUpdating(values: Collection<Box>) {
    applyRuntimeToggle(
        boxes = values,
        isRoot = { it.isClojureRoot },
        runtimeClass = ClojureExecution::class,
        newExecution = { child -> ClojureExecution(child) },
        language = "Clojure",
        customization = """this.style.border = "1px dashed #8B4000"; this.style.margin = "-5px" """,
        notifyCategory = "clojure"
    )
}
