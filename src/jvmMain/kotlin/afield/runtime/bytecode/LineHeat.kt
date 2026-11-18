package afield.runtime.bytecode

import afield.app.diagnostics.logging.lprintln
import afield.app.protocol.Handled
import afield.app.protocol.LineHeatLine
import afield.app.protocol.LineHeatUpdate
import afield.app.protocol.LineHeatWatch
import afield.app.protocol.Message
import afield.app.protocol._send
import afield.app.protocol.handlers
import afield.app.protocol.payload
import afield.app.server.sessions
import afield.app.server.thisSession
import afield.runtime.lifecycle.RunsAt
import afield.runtime.lifecycle.Runloop
import afield.runtime.lifecycle.installHandlers
import io.ktor.websocket.WebSocketSession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import org.jetbrains.org.objectweb.asm.MethodVisitor
import org.jetbrains.org.objectweb.asm.Opcodes
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Line heat: per-line execution counts for the box open in the editor, painted in its gutter as a
 * heat that fades when the line stops running.
 *
 * [LineHeatPass] is the rewrite: in a watched box's code, `LDC site; INVOKESTATIC LineHeat.hit(I)V`
 * at the start of every line of the snippet's own source. [LineHeat] is the runtime side: the
 * counters, the watch (which box the editor has open), and the Runloop-side publisher that sends
 * `line-heat` ([LineHeatUpdate]) to the editor, where `lineHeatmap.js` paints it.
 * `dev_docs/box_bytecode/LINE_HEAT.md` has the design and the measured cost.
 */

/**
 * The line-heat rewrite. Wants only a box some editor is watching ([LineHeat.watch]); every other
 * box carries nothing. Turning a watch on takes effect at the box's next run, because a module is
 * rewritten (from its originals) only when it is evaluated.
 *
 * At [BoxMethodVisitor.atLineStart] for each line in `1..`[SnippetOrigin.sourceLineCount] (lines
 * past the source are inlined library code, carried by the SMAP, and get nothing), it pushes the
 * line's site id and calls [LineHeat.hit]. There is one [Site] per class and line: a line that
 * appears in several methods of one class (a loop header revisited, a lambda body on the line that
 * creates it) shares one counter. A snippet whose source text is unknown gets no probes, because no
 * line of it could be placed in the box.
 *
 * A count is the number of times execution *entered* the line. A second `LINENUMBER` for the line
 * it is already on, reached only by falling through (no stack-map frame since the last one, so no
 * jump can land there), is not a new entry and gets no probe: kotlinc emits those around inlined
 * lambda bodies, and without this `repeat(4) { n++ }` counted 9. Coming back to a line from another
 * one (a multi-line call whose argument is on the next line, the end of inlined library code that
 * reports its own lines) is an entry and counts.
 */
object LineHeatPass : BoxBytecodePass {
    override val id = "line-heat"
    override val inserts = setOf(LineHeat.OWNER)

    /** [Site.kind] of every line-heat site. */
    const val KIND = "line-heat"

    override fun wants(origin: SnippetOrigin): Boolean = LineHeat.isWatched(origin)

    private val SNIPPET = Any()
    private val ACCESSOR = Regex("^(get|set|is)[A-Z0-9_$].*")

    /**
     * Code kotlinc generates for a K2 REPL snippet class (the class with a `$$eval` method) rather
     * than code the box wrote: its constructor, which carries only the snippet's line 1 (a comment,
     * the `//%-` of a later fragment, or whatever statement is there, which `$$eval` counts anyway),
     * and the leaf getters and setters of its top-level properties, which carry the declaration's
     * line and run when *other* snippets read the property. A box's own top-level `fun getX()` with
     * no calls in it is skipped too.
     */
    private fun isGenerated(cls: ClassScope, m: MethodScope): Boolean {
        val snippet = cls.attachment(SNIPPET) { cls.facts.keys.any { it.startsWith("\$\$eval(") } }
        if (!snippet) return false
        return m.name == "<init>" || (m.facts.isLeaf && ACCESSOR.matches(m.name))
    }

    override fun method(cls: ClassScope, m: MethodScope, next: MethodVisitor): MethodVisitor {
        val lines = cls.origin.sourceLineCount
        if (lines <= 0) return next
        if (isGenerated(cls, m)) return next
        val perLine = cls.attachment(LineHeatPass) { HashMap<Int, Site>() }
        return object : BoxMethodVisitor(next) {
            private var previous = -1
            private var merged = true

            // Every branch target and handler start carries a stack-map frame, so code with no
            // frame since the last line start is reached only by falling through from it.
            override fun visitFrame(type: Int, numLocal: Int, local: Array<out Any>?, numStack: Int, stack: Array<out Any>?) {
                merged = true
                super.visitFrame(type, numLocal, local, numStack, stack)
            }

            override fun atLineStart(line: Int) {
                val repeat = line == previous && !merged
                previous = line
                merged = false
                if (repeat || line < 1 || line > lines) return
                val site = perLine.getOrPut(line) { cls.site(m, KIND, line = line).also(LineHeat::register) }
                super.visitLdcInsn(site.id)
                super.visitMethodInsn(Opcodes.INVOKESTATIC, LineHeat.OWNER, "hit", "(I)V", false)
            }
        }
    }
}

/**
 * The runtime side of [LineHeatPass]: an execution counter per site, the watch that decides which
 * box is instrumented, and the publisher that turns counter changes into box-line updates.
 *
 * **Counters.** [hit] increments an `int` in a table indexed by site id. The table is chunked
 * (1024 counters a chunk) and chunks are never replaced, only the outer array grows, so a running
 * loop whose JIT-compiled code still holds an old outer array keeps counting into the same chunks.
 * [register] makes a site's chunk at rewrite time, so [hit]'s slow path (growing) runs only for a
 * site it never saw. The increments are plain, not atomic: box code that counts from two threads
 * at once can lose a few, which a heat display does not mind, and the probe stays a load, an add
 * and a store. A counter wraps after 2^31 hits; [publish] takes differences, which survive that.
 *
 * **Watch.** [watch] records, per key (the editor's websocket session; a test's own object), which
 * box and property it has open. [LineHeatPass.wants] is true for exactly those. One key watches one
 * box; a new watch replaces the key's old one.
 *
 * **Publisher.** A `preRun` hook on the [Runloop] calls [publish] every [periodMs] while anything
 * is watched. For each watch it diffs the counters of the box's sites against what it last saw, maps
 * each changed site to its box line ([Site.boxLine], by text), and sends one [LineHeatUpdate]
 * naming the hash of the box text it mapped onto. Box code that blocks the Runloop delays the
 * update until it returns. When the box text changes, the watch's running totals restart.
 */
object LineHeat {
    internal const val OWNER = "afield/runtime/bytecode/LineHeat"

    private const val SHIFT = 10
    private const val CHUNK = 1 shl SHIFT
    private const val MASK = CHUNK - 1

    // A plain field, read on every probe: see the gate rule in BOX_BYTECODE_PASSES.md (no volatile
    // read on a per-line path). Chunks are never replaced, so a stale outer array is still correct.
    private var chunks: Array<IntArray?> = arrayOfNulls(64)

    /** Count one execution of [site]'s line. Called by rewritten box code; must stay this small. */
    @JvmStatic
    fun hit(site: Int) {
        val t = chunks
        val i = site ushr SHIFT
        if (i < t.size) {
            val c = t[i]
            if (c != null) {
                c[site and MASK]++
                return
            }
        }
        chunkFor(site)[site and MASK]++
    }

    /** The chunk holding [site]'s counter, made (and the outer array grown) if it is not there yet. */
    @Synchronized
    private fun chunkFor(site: Int): IntArray {
        val i = site ushr SHIFT
        var t = chunks
        if (i >= t.size) {
            t = t.copyOf(maxOf(t.size * 2, i + 1))
            chunks = t
        }
        return t[i] ?: IntArray(CHUNK).also { t[i] = it }
    }

    /** Executions of [site]'s line so far (wrapping at 2^31). */
    fun count(site: Int): Int {
        val t = chunks
        val i = site ushr SHIFT
        return if (site >= 0 && i < t.size) t[i]?.get(site and MASK) ?: 0 else 0
    }

    // ---- sites by box -------------------------------------------------------------------------

    /** A growable list of site ids, appended at rewrite time and copied by the publisher. */
    private class SiteList {
        private var ids = IntArray(16)
        private var n = 0

        @Synchronized fun add(id: Int) {
            if (n == ids.size) ids = ids.copyOf(n * 2)
            ids[n++] = id
        }

        @Synchronized fun snapshot(): IntArray = ids.copyOf(n)
    }

    private val sitesByBox = ConcurrentHashMap<String, SiteList>()

    /** Called by [LineHeatPass] for each new site: makes its counter and files it under its box. */
    internal fun register(site: Site) {
        chunkFor(site.id)
        val box = site.origin.boxId ?: return
        sitesByBox.computeIfAbsent(box) { SiteList() }.add(site.id)
    }

    /** Every line-heat site made for box [uid], oldest first. */
    fun sitesOf(uid: String): List<Site> =
        sitesByBox[uid]?.snapshot()?.asList()?.mapNotNull { id -> SnippetSites[id] } ?: emptyList()

    // ---- watches ------------------------------------------------------------------------------

    private class Watch(val key: Any, val uid: String, val property: String) {
        /** Counter values this watch has accounted for, by site id. */
        var seen = IntArray(0)
        var codeHash: Int? = null
        val totals = HashMap<Int, Long>()

        fun seen(id: Int): Int = if (id < seen.size) seen[id] else 0

        fun see(id: Int, c: Int) {
            if (id >= seen.size) seen = seen.copyOf(maxOf(id + 1, seen.size * 2, 64))
            seen[id] = c
        }
    }

    private val watches = ConcurrentHashMap<Any, Watch>()

    /** `uid\u001fproperty` of every watched box: what [LineHeatPass.wants] reads at evaluation. */
    @Volatile private var watched: Set<String> = emptySet()

    private fun watchKey(uid: String, property: String) = "$uid\u001f$property"

    /**
     * [key] now watches box [uid]'s [property]: its next run is instrumented and its counts are
     * published. Executions before this call are not counted. Replaces [key]'s previous watch.
     */
    fun watch(key: Any, uid: String, property: String = "code") {
        val w = Watch(key, uid, property)
        sitesByBox[uid]?.snapshot()?.forEach { w.see(it, count(it)) }
        val old = watches.put(key, w)
        if (old == null || old.uid != uid || old.property != property)
            lprintln("debug,execution,bytecode", "line heat: watching $uid/$property")
        refreshWatched()
        ensurePublisher()
    }

    /** [key] watches nothing; a box no key watches is not instrumented from its next run. */
    fun unwatch(key: Any) {
        if (watches.remove(key) != null) refreshWatched()
    }

    private fun refreshWatched() {
        watched = watches.values.mapTo(HashSet()) { watchKey(it.uid, it.property) }
    }

    /** Whether some editor watches [origin]'s box and property (the pass's gate). */
    fun isWatched(origin: SnippetOrigin): Boolean {
        val box = origin.boxId ?: return false
        val w = watched
        return w.isNotEmpty() && watchKey(box, origin.property) in w
    }

    /** The box and property [key] watches, or null. */
    fun watchedBy(key: Any): Pair<String, String>? = watches[key]?.let { it.uid to it.property }

    // ---- publishing ---------------------------------------------------------------------------

    /** How often the Runloop publisher runs while anything is watched. */
    @Volatile var periodMs: Long = 250

    /** Told every update, with the key of the watch it is for (the editor's session, a test's key). */
    val sinks = CopyOnWriteArrayList<(Any, LineHeatUpdate) -> Unit>()

    /** Whether a watch's key is still connected; a watch whose key is gone is dropped at [publish]. */
    @Volatile var keyAlive: (Any) -> Boolean = { true }

    @Volatile private var publisherInstalled = false

    private fun ensurePublisher() {
        if (publisherInstalled) return
        synchronized(this) {
            if (publisherInstalled) return
            publisherInstalled = true
            Runloop.runLoop.preRun.setUnowned("line-heat-publisher", sequence {
                var next = 0L
                while (true) {
                    val now = System.nanoTime()
                    if (now >= next && watches.isNotEmpty()) {
                        next = now + periodMs * 1_000_000
                        try {
                            publish()
                        } catch (t: Throwable) {
                            lprintln("warn,execution,bytecode", "line heat publisher failed: $t")
                        }
                    }
                    yield(null)
                }
            })
        }
    }

    /**
     * One publisher step, now: for every watch, the box lines whose counters moved since the last
     * step, sent to [sinks] and returned. Runs on the Runloop (it reads box code); tests call it
     * directly.
     */
    fun publish(): List<Pair<Any, LineHeatUpdate>> {
        val out = ArrayList<Pair<Any, LineHeatUpdate>>()
        for (w in watches.values) {
            if (!runCatching { keyAlive(w.key) }.getOrDefault(true)) {
                unwatch(w.key)
                continue
            }
            val update = step(w) ?: continue
            out += w.key to update
            for (s in sinks) runCatching { s(w.key, update) }
                .onFailure { lprintln("warn,execution,bytecode", "line heat sink failed: $it") }
        }
        return out
    }

    private fun step(w: Watch): LineHeatUpdate? {
        val ids = sitesByBox[w.uid]?.snapshot() ?: return null
        if (ids.isEmpty()) return null
        var code: String? = null
        val deltas = HashMap<Int, Int>()
        for (id in ids) {
            val c = count(id)
            val d = c - w.seen(id)
            if (d == 0) continue
            w.see(id, c)
            val site = SnippetSites[id] ?: continue
            if (site.origin.property != w.property) continue
            if (code == null) code = site.origin.currentCode() ?: continue
            val line = boxLineOf(site, text = code)
            if (line > 0) deltas.merge(line, d, Int::plus)
        }
        val text = code ?: return null
        val hash = codeHash(text)
        if (hash != w.codeHash) {
            w.codeHash = hash
            w.totals.clear()
        }
        if (deltas.isEmpty()) return null
        val lines = deltas.entries.sortedBy { it.key }.map { (line, d) ->
            LineHeatLine(line, d, w.totals.merge(line, d.toLong(), Long::plus)!!)
        }
        return LineHeatUpdate(w.uid, w.property, hash, lines)
    }

    private const val WINDOW = 12

    /**
     * The box line [site] counts for, in the box's current [text], or -1. Proven by text
     * ([Site.boxLine]) or, for a line a macro rewrote in place (`println(` compiles as
     * `_printlnHere(4+1, `), placed between the nearest proven lines above and below it (within 12
     * lines), which must agree on one offset; at the edge of the compiled text one side is enough.
     * Never a blank or comment-only box line: those do not execute.
     */
    internal fun boxLineOf(site: Site, text: String): Int {
        var line = site.boxLine()
        if (line < 1) line = bracketed(site.origin, site.line)
        if (line < 1) return -1
        val t = lineOf(text, line)?.trim() ?: return -1
        return if (t.isEmpty() || t.startsWith("//")) -1 else line
    }

    private fun bracketed(origin: SnippetOrigin, line: Int): Int {
        val n = origin.sourceLineCount
        if (line < 1 || line > n) return -1
        if (origin.sourceText?.let { lineOf(it, line) }?.isBlank() != false) return -1
        // The offset of the nearest proven line in direction [step]: null at the edge of the text,
        // MISSING when the window holds no proven line.
        fun nearest(step: Int): Int? {
            var c = line + step
            var k = 0
            while (c in 1..n) {
                if (k++ >= WINDOW) return MISSING
                val b = origin.boxLine(c)
                if (b > 0) return b - c
                c += step
            }
            return null
        }
        val above = nearest(-1)
        val below = nearest(+1)
        if (above == MISSING || below == MISSING) return -1
        val d = when {
            above != null && below != null -> if (above == below) above else return -1
            else -> above ?: below ?: return -1
        }
        return line + d
    }

    private const val MISSING = Int.MIN_VALUE

    /** Line [line] (1-based) of [text], without its newline, or null if it has fewer lines. */
    private fun lineOf(text: String, line: Int): String? {
        var start = 0
        repeat(line - 1) {
            val nl = text.indexOf('\n', start)
            if (nl < 0) return null
            start = nl + 1
        }
        val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
        return text.substring(start, end).removeSuffix("\r")
    }

    /**
     * 32-bit FNV-1a over the UTF-16 units of [text] with `\r\n` read as `\n`; `lineHeatmap.js`
     * computes the same hash of the editor's text to decide whether an update still applies.
     */
    fun codeHash(text: String): Int {
        var h = 0x811c9dc5.toInt()
        var i = 0
        val n = text.length
        while (i < n) {
            val ch = text[i]
            if (ch == '\r' && i + 1 < n && text[i + 1] == '\n') { i++; continue }
            h = (h xor ch.code) * 16777619
            i++
        }
        return h
    }
}

/**
 * `line-heat-watch` ([LineHeatWatch]): the editor names the box it has open while line heat is
 * shown, and the publisher sends that connection `line-heat` updates.
 */
@RunsAt(installHandlers)
fun installLineHeatHandlers() {
    LineHeat.keyAlive = { k -> k !is WebSocketSession || sessions.containsKey(k) }
    LineHeat.sinks += { key, update ->
        if (key is WebSocketSession) _send(key, Message("line-heat").payload(update))
    }
    handlers["line-heat-watch"] = mutableSetOf({ message, _ ->
        val req = Json.decodeFromJsonElement<LineHeatWatch>(message.payload!!)
        val key = thisSession.get()
        if (key != null) {
            if (req.on && req.uid.isNotBlank()) LineHeat.watch(key, req.uid, req.property) else LineHeat.unwatch(key)
        }
        Handled.yes
    })
}
