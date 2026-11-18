package afield.runtime.bytecode

import afield.app.diagnostics.logging.lprintln
import afield.app.protocol.BoxCostBox
import afield.app.protocol.BoxCostFunction
import afield.app.protocol.BoxCostRequest
import afield.app.protocol.BoxCostSnapshot
import afield.app.protocol.Handled
import afield.app.protocol.Message
import afield.app.protocol.handlers
import afield.app.protocol.payload
import afield.app.server.thisSessionInfo
import afield.runtime.lifecycle.RunsAt
import afield.runtime.lifecycle.installHandlers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import org.jetbrains.org.objectweb.asm.Label
import org.jetbrains.org.objectweb.asm.MethodVisitor
import org.jetbrains.org.objectweb.asm.Opcodes
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Per-function cost: self and total wall time of each function in an opted-in box, for the
 * Profiling panel's "Box functions" tab and the stall watchdog's report.
 *
 * [BoxCostPass] is the rewrite: `LDC site; INVOKESTATIC BoxCost.enter(I)V` at method entry, the same
 * with `exit` immediately before every return, and `caught` at the first instruction of every
 * exception handler. [BoxCost] is the runtime side: a shadow stack per thread, per-site totals, the
 * run boundaries that reconcile exceptional exits and measure per-box totals, the snapshot, and the
 * `box-cost` RPC. `dev_docs/box_bytecode/BOX_COST.md` has the design and the measured cost.
 */

/**
 * The per-function cost rewrite. Off by default; wants only boxes opted in with [BoxCost.measure],
 * and takes effect at the box's next run (a module is rewritten from its originals when evaluated).
 *
 * One [Site] per method, its line the method's first line inside the snippet's own source. Not
 * instrumented: constructors and static initialisers, bridges, `$default` stubs, `access$`
 * accessors, coroutine `create` factories, and leaf methods ([MethodFacts.isLeaf]: no calls, no
 * loops), whose time is their caller's self time.
 *
 * Inserted code keeps the one rule (`BOX_BYTECODE_PASSES.md`): a constant and a static `(I)V` call,
 * at `visitCode` or immediately before an original instruction. There is no `try/finally`; an
 * exception leaving a method skips its `exit`, and the stack is reconciled later: at the handler that
 * catches it (`caught`, inserted before the handler's first instruction, which is after its frame),
 * at the next `exit` of a frame below it, or at the run boundary.
 */
object BoxCostPass : BoxBytecodePass {
    override val id = "box-cost"
    override val inserts = setOf(BoxCost.OWNER)

    /** [Site.kind] of every cost site. */
    const val KIND = "box-cost"

    override fun wants(origin: SnippetOrigin): Boolean = BoxCost.isMeasured(origin)

    /** Whether method [m] gets probes; see the class comment for what is left out and why. */
    internal fun instrumentable(m: MethodScope): Boolean {
        if (m.isInitializer || m.isBridge) return false
        if (m.facts.isLeaf) return false
        val n = m.name
        if (n.endsWith("\$default") || n.startsWith("access\$")) return false
        if (n == "create" && m.desc.endsWith("Lkotlin/coroutines/Continuation;")) return false
        return true
    }

    override fun method(cls: ClassScope, m: MethodScope, next: MethodVisitor): MethodVisitor {
        if (!instrumentable(m)) return next
        val lines = cls.origin.sourceLineCount
        val first = m.facts.lines.firstOrNull { it in 1..lines } ?: m.facts.lines.firstOrNull() ?: 0
        val site = cls.site(m, KIND, detail = BoxCost.functionName(cls.className, m.name), line = first)
        val id = site.id
        return object : MethodVisitor(Opcodes.API_VERSION, next) {
            private val handlers = HashSet<Label>()
            private var atHandler = false

            private fun call(name: String) {
                super.visitLdcInsn(id)
                super.visitMethodInsn(Opcodes.INVOKESTATIC, BoxCost.OWNER, name, "(I)V", false)
            }

            // Before an original instruction: the first one after a handler's label (and frame).
            private fun before() {
                if (atHandler) {
                    atHandler = false
                    call("caught")
                }
            }

            override fun visitCode() {
                super.visitCode()
                call("enter")
            }

            // ClassReader visits the exception table before any instruction or label.
            override fun visitTryCatchBlock(start: Label, end: Label, handler: Label, type: String?) {
                handlers += handler
                super.visitTryCatchBlock(start, end, handler, type)
            }

            override fun visitLabel(label: Label) {
                super.visitLabel(label)
                if (label in handlers) atHandler = true
            }

            override fun visitInsn(opcode: Int) {
                before()
                if (opcode in Opcodes.IRETURN..Opcodes.RETURN) call("exit")
                super.visitInsn(opcode)
            }

            override fun visitIntInsn(opcode: Int, operand: Int) { before(); super.visitIntInsn(opcode, operand) }
            override fun visitVarInsn(opcode: Int, varIndex: Int) { before(); super.visitVarInsn(opcode, varIndex) }
            override fun visitTypeInsn(opcode: Int, type: String?) { before(); super.visitTypeInsn(opcode, type) }
            override fun visitFieldInsn(opcode: Int, owner: String?, name: String?, descriptor: String?) { before(); super.visitFieldInsn(opcode, owner, name, descriptor) }
            override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) { before(); super.visitMethodInsn(opcode, owner, name, descriptor, isInterface) }
            override fun visitInvokeDynamicInsn(name: String?, descriptor: String?, bsm: org.jetbrains.org.objectweb.asm.Handle?, vararg args: Any?) { before(); super.visitInvokeDynamicInsn(name, descriptor, bsm, *args) }
            override fun visitJumpInsn(opcode: Int, label: Label) { before(); super.visitJumpInsn(opcode, label) }
            override fun visitLdcInsn(value: Any?) { before(); super.visitLdcInsn(value) }
            override fun visitIincInsn(varIndex: Int, increment: Int) { before(); super.visitIincInsn(varIndex, increment) }
            override fun visitTableSwitchInsn(min: Int, max: Int, dflt: Label, vararg labels: Label) { before(); super.visitTableSwitchInsn(min, max, dflt, *labels) }
            override fun visitLookupSwitchInsn(dflt: Label, keys: IntArray?, labels: Array<out Label>) { before(); super.visitLookupSwitchInsn(dflt, keys, labels) }
            override fun visitMultiANewArrayInsn(descriptor: String?, numDimensions: Int) { before(); super.visitMultiANewArrayInsn(descriptor, numDimensions) }
        }
    }
}

/**
 * The runtime side of [BoxCostPass].
 *
 * **Shadow stack.** Each thread that runs instrumented code has a [CostStack]: the site and start
 * time of every open frame, and how much of each frame's time its instrumented callees took. [enter]
 * pushes, [exit] pops and charges the site self time (its time minus its callees') and, for the
 * outermost activation of a recursive function only, total time. Everything a thread charges goes
 * into arrays only that thread writes, so the probes take no lock and no atomic; [snapshot] sums
 * them across threads with plain reads.
 *
 * **Exceptional exits.** An exception skips the `exit` of every frame it leaves. [exit] that finds
 * frames above its own pops them first, charging them up to now; [caught] (at a handler's first
 * instruction) pops the frames above the catching method's; and a run boundary ([begin]/[end] around
 * a snippet's evaluation and each box coroutine resume, [resetThread] after each top-level Runloop
 * tick) pops everything opened inside it. Frames left by a throw are therefore charged until the
 * catch, or until the boundary, never longer. A recursive function that throws to an outer
 * activation of itself is matched to the inner one (the stack holds no frame identity beyond the
 * site), so its time goes to the wrong activation of the same function.
 *
 * **Per-box totals.** [end] adds the boundary's wall time to the box's total when the box is
 * measured: what the box cost, including library code it called, without any rewrite.
 */
object BoxCost {
    internal const val OWNER = "afield/runtime/bytecode/BoxCost"

    private const val MAX_DEPTH = 1 shl 16

    // ---- opt-in --------------------------------------------------------------------------------

    @Volatile private var measured: Set<String> = emptySet()

    /**
     * Whether any box has ever been measured: the gate for the run boundaries, a plain read on each
     * coroutine resume and Runloop tick. Stays true once set (a thread may still hold open frames).
     */
    @JvmField @Volatile var active: Boolean = false

    /** Measure box [uid] (or stop, [on] false). Takes effect at its next run. */
    fun measure(uid: String, on: Boolean = true) {
        synchronized(this) {
            measured = if (on) measured + uid else measured - uid
            if (on) active = true
        }
        lprintln("debug,execution,bytecode", "box cost: ${if (on) "measuring" else "not measuring"} $uid")
    }

    /** The ids of every box being measured. */
    fun measuredBoxes(): Set<String> = measured

    /** Whether box [uid] is measured. */
    fun isMeasured(uid: String): Boolean = uid in measured

    /** The pass's gate: whether [origin]'s box is measured. */
    fun isMeasured(origin: SnippetOrigin): Boolean {
        val box = origin.boxId ?: return false
        val m = measured
        return m.isNotEmpty() && box in m
    }

    // ---- the shadow stack ----------------------------------------------------------------------

    /** One thread's open frames and everything it has charged, by site id. Only that thread writes. */
    internal class CostStack(thread: Thread) {
        val thread = WeakReference(thread)
        val threadName: String = thread.name

        var sites = IntArray(32)
        var starts = LongArray(32)
        var child = LongArray(32)
        var depth = 0
        var overflow = 0

        var self = LongArray(0)
        var total = LongArray(0)
        var calls = LongArray(0)
        var onStack = IntArray(0)

        /** Written when the outermost frame returns, so a reader on another thread sees the arrays. */
        @Volatile var stamp = 0

        private fun ensureSite(site: Int) {
            if (site < onStack.size) return
            val n = maxOf(site + 1, onStack.size * 2, 64)
            self = self.copyOf(n); total = total.copyOf(n); calls = calls.copyOf(n); onStack = onStack.copyOf(n)
        }

        fun push(site: Int, now: Long) {
            val d = depth
            if (d == sites.size) {
                if (d >= MAX_DEPTH) { overflow++; return }
                sites = sites.copyOf(d * 2); starts = starts.copyOf(d * 2); child = child.copyOf(d * 2)
            }
            if (site < 0) return
            ensureSite(site)
            sites[d] = site; starts[d] = now; child[d] = 0
            onStack[site]++
            depth = d + 1
        }

        fun popTop(now: Long) {
            val i = --depth
            val site = sites[i]
            val t = now - starts[i]
            self[site] += t - child[i]
            calls[site]++
            if (--onStack[site] == 0) total[site] += t
            if (i > 0) child[i - 1] += t
        }

        /** The index of the innermost open frame of [site], or -1. */
        fun find(site: Int): Int {
            var j = depth - 1
            while (j >= 0 && sites[j] != site) j--
            return j
        }

        fun pop(site: Int, now: Long) {
            if (overflow > 0) { overflow--; return }
            val k = depth - 1
            if (k < 0) return
            if (sites[k] != site) {
                val j = find(site)
                // An exit with no open frame of its site: its enter ran before a boundary reset.
                if (j < 0) return
                while (depth - 1 > j) popTop(now)
            }
            popTop(now)
            if (depth == 0) stamp++
        }

        /** Pop every frame above [to] (a depth), charging them up to [now]. */
        fun unwind(to: Int, now: Long) {
            overflow = 0
            if (depth <= to) return
            while (depth > to) popTop(now)
            if (depth == 0) stamp++
        }
    }

    // Holds a thread's stack; no initial value, so a thread that never ran measured code reads null.
    private val local = ThreadLocal<CostStack>()

    /** Every stack ever made. A stack outlives its thread so its totals stay in the snapshot. */
    private val stacks = CopyOnWriteArrayList<CostStack>()

    private fun stack(): CostStack = local.get() ?: CostStack(Thread.currentThread()).also {
        local.set(it)
        stacks += it
        active = true
    }

    /** Entry to an instrumented method. Called by rewritten box code; must stay this small. */
    @JvmStatic
    fun enter(site: Int) {
        stack().push(site, System.nanoTime())
    }

    /** Immediately before an instrumented method returns. */
    @JvmStatic
    fun exit(site: Int) {
        val s = local.get() ?: return
        s.pop(site, System.nanoTime())
    }

    /**
     * At the first instruction of an exception handler in method [site]: frames above that method's
     * innermost open frame threw and are gone; charge them up to now.
     */
    @JvmStatic
    fun caught(site: Int) {
        val s = local.get() ?: return
        val j = s.find(site)
        if (j >= 0 && j < s.depth - 1) s.unwind(j + 1, System.nanoTime())
    }

    // ---- run boundaries ------------------------------------------------------------------------

    /** What [begin] returns, for [end]: this thread's stack (or null) and its depth then. */
    class Mark internal constructor(internal val stack: CostStack?, internal val thread: Thread, internal val depth: Int, internal val startNs: Long)

    /** The start of a run of box code on this thread; null (and free) while nothing is measured. */
    fun begin(): Mark? {
        if (!active) return null
        val s = local.get()
        return Mark(s, Thread.currentThread(), s?.depth ?: 0, System.nanoTime())
    }

    /**
     * The end of the run [mark] began: frames opened inside it and left open by an exception are
     * popped and charged up to now, and the run's wall time is added to box [uid]'s total if it is
     * measured. A run that ended on another thread (a suspension) reconciles nothing.
     */
    fun end(mark: Mark?, uid: String? = null) {
        if (mark == null) return
        val now = System.nanoTime()
        val t = Thread.currentThread()
        if (mark.thread === t) (local.get())?.let { s -> if (s === mark.stack || mark.stack == null) s.unwind(mark.depth, now) }
        if (uid != null && uid in measured) {
            boxTotals.computeIfAbsent(uid) { BoxTotal() }.add(now - mark.startNs)
        }
    }

    /**
     * Pop every frame this thread holds, charging them up to now: the end of a top-level Runloop
     * tick, where no box code can still be running on the Runloop thread.
     */
    fun resetThread() {
        if (!active) return
        val s = local.get() ?: return
        if (s.depth > 0 || s.overflow > 0) s.unwind(0, System.nanoTime())
    }

    private class BoxTotal {
        var runs = 0L
        var ns = 0L
        @Synchronized fun add(d: Long) { runs++; ns += d }
        @Synchronized fun read(): Pair<Long, Long> = runs to ns
    }

    private val boxTotals = ConcurrentHashMap<String, BoxTotal>()

    // ---- reading -------------------------------------------------------------------------------

    /** Counters at the last [clear], subtracted from every snapshot. */
    private class Baseline(val self: LongArray, val total: LongArray, val calls: LongArray, val boxes: Map<String, Pair<Long, Long>>, val atNs: Long)

    @Volatile private var baseline = Baseline(LongArray(0), LongArray(0), LongArray(0), emptyMap(), System.nanoTime())

    /** One function's cost, summed over threads and over every compile of it that maps to the same box line. */
    class FunctionCost(
        val boxId: String,
        val property: String,
        val label: String,
        val function: String,
        /** The box line of the function's first statement, or -1 when it is not proven. */
        val line: Int,
        val calls: Long,
        val selfNs: Long,
        val totalNs: Long,
        /** Whether a frame of it is open on some thread now (its running time so far is included). */
        val running: Boolean,
    ) {
        override fun toString(): String = "$function (line $line) calls=$calls self=${selfNs / 1_000_000.0}ms total=${totalNs / 1_000_000.0}ms${if (running) " running" else ""}"
    }

    private class Sums(n: Int) {
        val self = LongArray(n)
        val total = LongArray(n)
        val calls = LongArray(n)
        val running = BooleanArray(n)
    }

    /** The open frames of [s] with their time so far: self (less open callees) and total. */
    private fun openFrames(s: CostStack, now: Long): List<Triple<Int, Long, Long>> {
        val d = minOf(s.depth, s.sites.size)
        if (d <= 0) return emptyList()
        val sites = s.sites.copyOf(d)
        val starts = s.starts.copyOf(d)
        val child = s.child.copyOf(d)
        val out = ArrayList<Triple<Int, Long, Long>>(d)
        var above = 0L
        for (i in d - 1 downTo 0) {
            val t = (now - starts[i]).coerceAtLeast(0)
            out += Triple(sites[i], (t - child[i] - above).coerceAtLeast(0), t)
            above = t
        }
        return out
    }

    private fun sums(includeOpen: Boolean): Sums {
        val now = System.nanoTime()
        val n = SnippetSites.size
        val out = Sums(n)
        for (s in stacks) {
            @Suppress("UNUSED_VARIABLE") val fence = s.stamp
            val self = s.self; val total = s.total; val calls = s.calls
            for (i in 0 until minOf(n, self.size, total.size, calls.size)) {
                out.self[i] += self[i]; out.total[i] += total[i]; out.calls[i] += calls[i]
            }
            if (includeOpen) {
                val open = openFrames(s, now)
                for ((site, selfNs, _) in open) if (site in 0 until n) { out.self[site] += selfNs; out.running[site] = true }
                // A site's total is its outermost open activation's (frames come innermost first).
                val first = HashSet<Int>()
                for ((site, _, totalNs) in open.asReversed()) if (site in 0 until n && first.add(site)) out.total[site] += totalNs
            }
        }
        val b = baseline
        for (i in 0 until minOf(n, b.self.size)) {
            out.self[i] -= b.self[i]; out.total[i] -= b.total[i]; out.calls[i] -= b.calls[i]
        }
        return out
    }

    /** Every measured function, summed over threads, hottest self time first. */
    fun functions(includeOpen: Boolean = true, box: ((String) -> Boolean)? = null): List<FunctionCost> {
        val sums = sums(includeOpen)
        class Acc(val boxId: String, val property: String, val label: String, val function: String, val line: Int) {
            var calls = 0L; var self = 0L; var total = 0L; var running = false
        }
        val byKey = LinkedHashMap<String, Acc>()
        for (id in sums.self.indices) {
            if (sums.calls[id] == 0L && !sums.running[id]) continue
            val site = SnippetSites[id] ?: continue
            if (site.kind != BoxCostPass.KIND) continue
            val uid = site.origin.boxId ?: continue
            if (box != null && !box(uid)) continue
            val fn = site.detail as? String ?: site.methodName
            val line = site.boxLine()
            val a = byKey.getOrPut("$uid\u001f${site.origin.property}\u001f$fn\u001f$line") { Acc(uid, site.origin.property, site.origin.label, fn, line) }
            a.calls += sums.calls[id]; a.self += sums.self[id]; a.total += sums.total[id]; a.running = a.running || sums.running[id]
        }
        return byKey.values.map { FunctionCost(it.boxId, it.property, it.label, it.function, it.line, it.calls, it.self, maxOf(it.total, it.self), it.running) }
            .sortedByDescending { it.selfNs }
    }

    /** Box [uid]'s measured runs and their total wall time in ns, since the last [clear]. */
    fun boxTotal(uid: String): Pair<Long, Long> {
        val (runs, ns) = boxTotals[uid]?.read() ?: (0L to 0L)
        val (r0, n0) = baseline.boxes[uid] ?: (0L to 0L)
        return (runs - r0) to (ns - n0)
    }

    /** Start every total again from now (open frames keep their start). */
    @Synchronized
    fun clear() {
        val s = sums(includeOpen = false)
        val b = baseline
        // [sums] already subtracted the old baseline; add it back so the new one is absolute.
        for (i in 0 until minOf(s.self.size, b.self.size)) { s.self[i] += b.self[i]; s.total[i] += b.total[i]; s.calls[i] += b.calls[i] }
        baseline = Baseline(s.self, s.total, s.calls, boxTotals.mapValues { it.value.read() }, System.nanoTime())
    }

    /** The panel's view: measured boxes (named by [nameOf]) and the functions of [box]-accepted boxes. */
    fun snapshot(nameOf: (String) -> String? = { null }, box: ((String) -> Boolean)? = null): BoxCostSnapshot {
        val fns = functions(includeOpen = true, box = box)
        val ids = LinkedHashSet<String>()
        measured.filterTo(ids) { box == null || box(it) }
        fns.mapTo(ids) { it.boxId }
        boxTotals.keys.filterTo(ids) { box == null || box(it) }
        val labels = fns.associate { it.boxId to it.label }
        val boxes = ids.map { uid ->
            val (runs, ns) = boxTotal(uid)
            BoxCostBox(uid, nameOf(uid) ?: labels[uid] ?: uid, uid in measured, runs, ns / 1e6)
        }
        return BoxCostSnapshot(
            elapsedMs = (System.nanoTime() - baseline.atNs) / 1e6,
            boxes = boxes,
            functions = fns.take(500).map { BoxCostFunction(it.boxId, it.property, it.function, it.line, it.calls, it.selfNs / 1e6, it.totalNs / 1e6, it.running) },
        )
    }

    /**
     * For the stall watchdog: the box functions open on [thread] now, and the hottest box function
     * by self time (what it has charged plus its open frames), as one line; null when [thread] holds
     * no instrumented frame and has charged nothing.
     */
    fun stallReport(thread: Thread): String? {
        val s = stacks.firstOrNull { it.thread.get() === thread } ?: return null
        val now = System.nanoTime()
        val open = openFrames(s, now)
        val selfBySite = HashMap<Int, Long>()
        for ((site, selfNs, _) in open) selfBySite.merge(site, selfNs, Long::plus)
        val charged = s.self
        for (i in 0 until minOf(charged.size, SnippetSites.size)) if (charged[i] > 0) selfBySite.merge(i, charged[i], Long::plus)
        val hottest = selfBySite.entries
            .filter { SnippetSites[it.key]?.kind == BoxCostPass.KIND }
            .maxByOrNull { it.value } ?: return null
        fun describe(id: Int): String {
            val site = SnippetSites[id] ?: return "site $id"
            val fn = site.detail as? String ?: site.methodName
            val line = site.boxLine()
            return "$fn" + (if (line > 0) " (box line $line)" else "")
        }
        val hot = SnippetSites[hottest.key]!!
        val sb = StringBuilder("hottest box function: ${hot.origin.label} ${describe(hottest.key)}, ${"%.1f".format(hottest.value / 1e6)} ms self")
        if (open.any { it.first == hottest.key }) sb.append(", running now")
        if (open.isNotEmpty()) sb.append("; open: ").append(open.take(8).joinToString(" <- ") { describe(it.first) })
        return sb.toString()
    }

    // ---- names ---------------------------------------------------------------------------------

    private val NUMERIC = Regex("\\d+")

    /**
     * How a function is named in the panel and the stall report: the method's own name, `box body`
     * for the snippet's `$$eval`, and `lambda in f` for a lambda body (a `f$lambda$0` method, or the
     * `invoke` of a lambda class `Snippet$f$1`).
     */
    fun functionName(className: String, methodName: String): String {
        fun plain(n: String) = if (n == "\$\$eval" || n.isEmpty()) "box body" else n
        val lambda = methodName.indexOf("\$lambda")
        if (lambda >= 0) return "lambda in " + plain(methodName.substring(0, lambda))
        if (methodName.startsWith("lambda\$")) return "lambda in " + plain(methodName.removePrefix("lambda\$").substringBefore('$'))
        if (methodName == "invoke" || methodName == "invokeSuspend") {
            val parts = className.substringAfterLast('/').split('$').drop(1).filter { it.isNotEmpty() && !NUMERIC.matches(it) && it != "lambda" }
            return if (parts.isEmpty()) "lambda in box body" else "lambda in " + plain(parts.last())
        }
        return plain(methodName)
    }
}

/**
 * `box-cost` ([BoxCostRequest]): the Profiling panel's "Box functions" tab opts boxes in and out and
 * polls a [BoxCostSnapshot] limited to the boxes of its document.
 */
@RunsAt(installHandlers)
fun installBoxCostHandlers() {
    handlers["box-cost"] = mutableSetOf({ message, reply ->
        val response = try {
            val req = message.payload?.let { Json.decodeFromJsonElement<BoxCostRequest>(it) } ?: BoxCostRequest()
            when (req.action) {
                "measure" -> { require(req.uid.isNotBlank()) { "No box to measure" }; BoxCost.measure(req.uid, true) }
                "unmeasure" -> BoxCost.measure(req.uid, false)
                "clear" -> BoxCost.clear()
                "snapshot" -> Unit
                else -> throw IllegalArgumentException("Unknown box-cost action: ${req.action}")
            }
            val boxes = thisSessionInfo.get()?.boxes
            BoxCost.snapshot(nameOf = { boxes?.get(it)?.name }, box = boxes?.let { b -> { uid: String -> b.containsKey(uid) } })
        } catch (e: Exception) {
            BoxCostSnapshot(ok = false, error = e.message ?: "box cost failed")
        }
        reply(Message(message.replyTo ?: "box-cost-result").payload(response))
        Handled.yes
    })
}
