package afield.runtime.bytecode

import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

/**
 * Where rewritten box code came from, and the numbered sites the rewrite passes bake into it.
 *
 * [SnippetOrigin] is one compiled Kotlin snippet: which box and property it was compiled for, the
 * text the compiler actually saw, and the loader its call targets resolve against. [SnippetSites]
 * hands out *site ids*: small ints a pass pushes as a constant in front of the static call it
 * inserts, so the runtime side of the pass can find out, from the int alone, which box, class,
 * method and line it is running for. [SourceLineMap] turns a line of the compiled text into a line
 * of the box's code as it is now, and refuses (answers -1) when it cannot prove the two are the
 * same line. `dev_docs/BOX_BYTECODE_PASSES.md` has the design.
 */

/**
 * One compiled snippet, as the rewrite passes see it.
 *
 * [sourceText] is the text the compiler compiled, which is not the box's code: a selection run is
 * padded with blank lines, a box with `//%-` breaks is compiled one fragment at a time (so line 1 of
 * the second fragment is some later line of the box), and macro expansion may change lines. The
 * bytecode's line numbers are lines of [sourceText]. [boxLine] maps them onto [currentCode].
 *
 * [currentCode] reads the box's source property now (it is a function because the box is held
 * weakly and its code changes while it runs). [classLoader] is the loader the snippet's call targets
 * resolve against; [CallTargets] reads class bytes from it as resources, never loading a class.
 * [owner] is the box [label] names (the engine's own box), also held weakly; the runaway-box
 * watchdog reads its `watchdog` property from another thread.
 */
class SnippetOrigin(
    val boxId: String?,
    val property: String,
    /** How reports name the box: `'name' (id)`. */
    val label: String,
    val sourceText: String?,
    classLoader: ClassLoader?,
    owner: afield.document.graph.Box? = null,
    // Last, so callers keep passing it as a trailing lambda.
    private val currentCode: () -> String? = { null },
) {
    // Weak: the engine's loader must not outlive its box because a site or class record names it.
    private val loaderRef = classLoader?.let { WeakReference(it) }
    private val ownerRef = owner?.let { WeakReference(it) }

    /** The box [label] names, while it is alive; null for detached snippets. */
    val owner: afield.document.graph.Box? get() = ownerRef?.get()

    /** The loader the snippet's call targets resolve against, while it is alive. */
    val classLoader: ClassLoader? get() = loaderRef?.get()

    /** Lines in [sourceText]; 0 when unknown. Bytecode lines above this are inlined library code. */
    val sourceLineCount: Int = sourceText?.let { countLines(it) } ?: 0

    @Volatile private var cachedFor: String? = null
    @Volatile private var cachedMap: IntArray? = null

    /** The box's source property as it is now, or null. */
    fun currentCode(): String? = runCatching { currentCode.invoke() }.getOrNull()

    /**
     * The 1-based line of the box's current code that compiled line [snippetLine] came from, or -1
     * when that is not proven: the line is not in [sourceText] (inlined library code reports lines
     * past its end), the box has been edited so the text no longer matches, or macro expansion
     * rewrote it. Computed once per version of the box's code, then an array read.
     */
    fun boxLine(snippetLine: Int): Int {
        val src = sourceText ?: return -1
        if (snippetLine < 1 || snippetLine > sourceLineCount) return -1
        val code = currentCode() ?: return -1
        var map = cachedMap
        if (map == null || cachedFor !== code) {
            map = SourceLineMap.map(src, code)
            cachedMap = map
            cachedFor = code
        }
        return map[snippetLine]
    }

    override fun toString(): String = "SnippetOrigin($label, $property, ${sourceLineCount} lines)"

    companion object {
        /** An origin with no box, for tests and for code compiled outside a box. */
        fun detached(label: String, sourceText: String? = null, loader: ClassLoader? = null, code: String? = sourceText) =
            SnippetOrigin(null, "code", label, sourceText, loader) { code }

        internal fun countLines(s: String): Int = if (s.isEmpty()) 0 else s.count { it == '\n' } + 1
    }
}

/**
 * Compiled lines onto box lines, by text: a compiled line maps to a box line only when the two
 * lines are character-identical (after trimming trailing whitespace), which is the same rule the
 * profiling timeline's gutter uses (`mappedProfileLine` in `profileTimelineModel.js`).
 *
 * The offset between them is found, not assumed: the most common offset among lines whose text
 * occurs exactly once on each side (a fragment's whole offset, or zero), then walked forward,
 * re-anchoring within a small window when a macro expansion has shifted the lines below it.
 * Blank lines never map; they do not execute.
 */
object SourceLineMap {
    private const val WINDOW = 12

    /** Index `i` (1-based) holds the box line for compiled line `i`, or -1. Index 0 is unused. */
    fun map(compiled: String, box: String): IntArray {
        val c = lines(compiled)
        val b = lines(box)
        val out = IntArray(c.size + 1) { -1 }
        if (c.isEmpty() || b.isEmpty()) return out
        val cCount = HashMap<String, Int>()
        c.forEach { if (it.isNotBlank()) cCount.merge(it, 1, Int::plus) }
        val bIndex = HashMap<String, MutableList<Int>>()
        b.forEachIndexed { i, t -> if (t.isNotBlank()) bIndex.getOrPut(t) { ArrayList(1) } += i }
        val votes = HashMap<Int, Int>()
        c.forEachIndexed { i, t ->
            if (t.isBlank() || t.trim().length < 3 || cCount[t] != 1) return@forEachIndexed
            val at = bIndex[t] ?: return@forEachIndexed
            if (at.size == 1) votes.merge(at[0] - i, 1, Int::plus)
        }
        var d = votes.maxByOrNull { it.value }?.key ?: 0
        for (i in c.indices) {
            val t = c[i]
            if (t.isBlank()) continue
            val k = i + d
            if (k in b.indices && b[k] == t) { out[i + 1] = k + 1; continue }
            // A `}` or `)` matches anywhere, so it never moves the offset; it maps only in place.
            if (!distinctive(t)) continue
            // Re-anchor: the nearest identical line within the window becomes the new offset.
            var found = -1
            for (r in 1..WINDOW) {
                if (k + r in b.indices && b[k + r] == t) { found = k + r; break }
                if (k - r in b.indices && b[k - r] == t) { found = k - r; break }
            }
            if (found >= 0) { d = found - i; out[i + 1] = found + 1 }
        }
        return out
    }

    // `//%-` is distinctive; `})`, `});` and `} else {`-free brace runs are not.
    private fun distinctive(t: String): Boolean = t.trim().let { s -> s.length >= 3 && s.any { it !in "{}()[];,. " } }

    private fun lines(s: String): List<String> =
        s.replace("\r\n", "\n").split("\n").map { it.trimEnd() }
}

/**
 * One place in rewritten box code that a pass's inserted call stands for.
 *
 * [line] is the compiled line ([SnippetOrigin.sourceText]) the bytecode reported there, 0 when the
 * code had no line yet. [kind] names the pass that made it; [detail] is that pass's own data (the
 * called method for a call site, the method for a cost probe), kept as a string or a small object.
 */
class Site(
    val id: Int,
    val origin: SnippetOrigin,
    val className: String,
    val methodName: String,
    val methodDesc: String,
    val line: Int,
    val kind: String,
    val detail: Any? = null,
) {
    /** The box line this site is on now, or -1 (see [SnippetOrigin.boxLine]). */
    fun boxLine(): Int = origin.boxLine(line)

    override fun toString(): String = "Site#$id($kind ${origin.label} ${className.substringAfterLast('/')}.$methodName line $line)"
}

/**
 * Every [Site] handed out, by id. Ids are dense from 0, so a pass can keep its runtime data
 * (counters, timings) in arrays indexed by site id. Allocation is synchronized and happens at rewrite
 * time; [get] is a lock-free array read and is what the runtime side calls.
 *
 * Sites are never freed: a snippet's classes stay loaded for as long as the K2 REPL history holds
 * the snippet, which is the life of the engine, so a site's code can run for that long too. A site
 * is ~100 bytes and a pass makes at most a few per line, so this grows with the REPL history it
 * shadows.
 */
object SnippetSites {
    @Volatile private var table: Array<Site?> = arrayOfNulls(1024)
    @Volatile private var count = 0

    /** Allocate a site; its id is the int the pass bakes into the bytecode. */
    @Synchronized
    fun allocate(origin: SnippetOrigin, className: String, methodName: String, methodDesc: String, line: Int, kind: String, detail: Any? = null): Site {
        val id = count
        if (id == table.size) table = table.copyOf(table.size * 2)
        val site = Site(id, origin, className, methodName, methodDesc, line, kind, detail)
        table[id] = site
        count = id + 1
        return site
    }

    /** The site with [id], or null for an id this process never handed out. */
    operator fun get(id: Int): Site? {
        val t = table
        return if (id in 0 until count && id < t.size) t[id] else null
    }

    /** How many sites exist; every id is below this. */
    val size: Int get() = count
}

/**
 * Snippet classes by internal name, with the origin they were rewritten for: how a thread's stack
 * (or a `StackTraceElement`) is turned back into a box. Every class any pass rewrote is here.
 */
object SnippetClasses {
    // Strong, like the sites: an origin holds its box and loader weakly, so this keeps only text.
    private val origins = ConcurrentHashMap<String, SnippetOrigin>()

    internal fun record(className: String, origin: SnippetOrigin) {
        origins[className] = origin
    }

    /** The origin of the snippet class with internal (`a/b/C`) or binary (`a.b.C`) name [className]. */
    fun originOf(className: String): SnippetOrigin? = origins[className.replace('.', '/')]

    /** The innermost frame of [stack] that is rewritten box code, with its origin. */
    fun innermost(stack: Array<StackTraceElement>): Pair<StackTraceElement, SnippetOrigin>? {
        for (e in stack) originOf(e.className)?.let { return e to it }
        return null
    }

    /**
     * The innermost frame of [stack] in box code that maps to a line of its box's current code: for
     * a report made on a failure path ("line 14 renders a Signal from the Runloop"). Frames whose
     * line is unproven (inlined library code, an edited box) are passed over for the next one out.
     */
    fun boxFrame(stack: Array<StackTraceElement> = Thread.currentThread().stackTrace): BoxFrame? {
        for (e in stack) {
            val o = originOf(e.className) ?: continue
            val line = o.boxLine(e.lineNumber)
            if (line > 0) return BoxFrame(o, line, e)
        }
        return null
    }
}

/** A frame of box code located in its box: [line] is a line of the box's current [SnippetOrigin.property]. */
class BoxFrame(val origin: SnippetOrigin, val line: Int, val element: StackTraceElement) {
    override fun toString(): String = "${origin.label} line $line"
}
