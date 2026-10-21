package afield.runtime.lifecycle

import afield.app.diagnostics.named
import afield.app.protocol.send
import afield.data.table.div
import afield.data.table.minus
import afield.data.table.rem
import afield.data.table.times
import afield.document.graph.autorun
import afield.document.graph.nested
import afield.document.graph.preamble
import afield.runtime.execution.launches
import afield.ui.notation.with
import afield.ui.output.md
import afield.ui.output.slider

import afield.app.protocol.Execute
import afield.app.protocol.Message
import afield.app.protocol._send
import afield.app.protocol.computeIfAbsent
import afield.app.server.sessions
import afield.app.server.thisSession
import afield.app.server.thisSessionInfo
import afield.audio.synth.*
import afield.data.table.get
import afield.data.table.plus
import afield.document.graph.Box
import afield.document.graph.METAROOT_ID
import afield.document.graph.box
import afield.document.properties.PropertyDependencies
import afield.graphics.vulkan.*
import afield.media.stream.*
import afield.media.video.*
import afield.runtime.execution.KotlinExecution
import afield.runtime.execution.plus
import afield.tools.automation.FlightRecorder
import afield.ui.output.templates.filter
import afield.app.diagnostics.logging.lprintln
import afield.lang.fieldml.FieldMLExecution
import afield.runtime.macros.PREAMBLE_SELF_SUPPLIER
import afield.runtime.macros.gatherPreambleUpwardsReport
import afield.runtime.macros.preambleSupplierTag
import afield.data.collections.isTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

/**
 * Reactive properties: the runs that `propUp` pulls from upstream and `propDown` pushes downstream.
 *
 * A read that finds no value runs the box that supplies it before returning; a write that changes a
 * value reruns the boxes that read the old one. [ReactiveRuns] holds both mechanisms and their
 * guards.
 */

/**
 * The runs that `propUp` and `propDown` cause: pulling a missing value from upstream, and pushing a
 * changed one downstream.
 *
 * ## Up: a pull, inside the read
 *
 * A `propUp` read that finds nothing calls [pull] from the delegate's getter. That getter is box
 * code, running on the runloop thread *inside* the reading box's evaluation, and it cannot suspend,
 * so the pull blocks it and **pumps the runloop itself** (`Runloop.runLoop.main.frame()`) until the
 * supplier's run has finished — a nested event loop. The supplier is launched straight onto the
 * runloop rather than through the launch queue ([launchImmediately]), because the reader's own
 * launch is the queue's current entry and nothing behind it can start. When the supplier has run,
 * the getter reads again and carries on. Ordering is by construction: a supplier runs exactly when,
 * and only if, its value is asked for, and a chain C → B → A unwinds as nested pulls.
 *
 * Which boxes supply a name ([suppliersFor]), nearest first: the box the reader's gathered preamble
 * says declared it, the boxes that supplied it on earlier reads ([PropertyDependencies]), then any
 * ancestor whose code or preamble declares it.
 *
 * Guards, because re-entering the runloop from inside a box is the risky part:
 * - a box blocked in a pull is never pulled ([isBlocked]) — that is a cycle, and would re-enter its
 *   engine mid-evaluation; the read fails with the cycle named instead;
 * - a run asked of a blocked box waits for the pull to return ([awaitNotBlocked]) rather than
 *   starting inside it;
 * - within one run of the reader a supplier is pulled once per name; outside a run, not again
 *   within [PULL_REPEAT_WINDOW_MS];
 * - a pull gives up after [pullTimeoutMs];
 * - the thread's box context and session are restored after every pumped step.
 *
 * ## Down: a cascade, after the write
 *
 * A `propDown` write that changes the value is noted ([noteChange]). A change made during a run is
 * held until that run finishes successfully; one made outside any run (a slider callback) goes at
 * once. [Downstream] then reruns every box that read the value *before* it changed, one at a time,
 * in dependency order, each awaited — off the runloop, through the ordinary launch queue.
 *
 * Off with `-Dafield.reactiveProps=false` or `AFIELD_REACTIVE_PROPS=off`. Every decision is in
 * [recentEvents], which is what the tests read. Design: `dev_docs/PROPERTY_DEPENDENCIES.md`.
 */
object ReactiveRuns {

    /** Set around a pull's `launchAll`: run the launch on the runloop now, not in the launch queue. */
    val launchImmediately = ThreadLocal<Boolean>()

    @Volatile
    var enabled: Boolean = System.getProperty("afield.reactiveProps")?.lowercase() !in setOf("false", "off", "0") &&
        System.getenv("AFIELD_REACTIVE_PROPS")?.lowercase() !in setOf("false", "off", "0")

    @Volatile
    var pullTimeoutMs: Long = System.getProperty("afield.reactiveProps.pullTimeoutMs")?.toLongOrNull() ?: 120_000L

    const val PULL_REPEAT_WINDOW_MS = 2_000L
    const val MAX_RUNS_PER_CASCADE = 3
    private const val EVENTS_KEPT = 4000

    // ---------------------------------------------------------------------------------------------
    // Event log

    private val events = ConcurrentLinkedDeque<String>()
    private val eventSeq = AtomicLong()

    internal fun event(text: String) {
        events.addLast("${eventSeq.incrementAndGet()} $text")
        while (events.size > EVENTS_KEPT) events.pollFirst()
    }

    /** What the pulls and cascades did, oldest first, each line numbered. */
    fun recentEvents(): List<String> = events.toList()

    fun clearEvents() = events.clear()

    private fun label(b: Box?): String = b?.let { it.name.ifBlank { it.id } } ?: "?"

    /**
     * [box] in its document: the uid alone is not unique across open documents (a copied document
     * keeps its uids), so every per-box record here is keyed by document file and uid.
     */
    internal fun docKey(box: Box): String =
        (if (box.isInsideInitialized()) box.inside.filename else "") + "\u0000" + box.id

    // ---------------------------------------------------------------------------------------------
    // Run tracking (called by KotlinExecution.executeInBox)

    private class RunState {
        var depth = 0
        var startSeq = 0L
        val changes = ArrayList<Change>()
        val pulled = HashSet<String>()
        val waiters = ArrayList<CompletableDeferred<Boolean>>()
    }

    private val runs = ConcurrentHashMap<Box, RunState>()
    private val lastRunStart = ConcurrentHashMap<String, Long>()
    /**
     * Boxes whose code is blocked in a pull, counted: a box's own animation frame can pull while
     * the box is already blocked in another pull, and the inner one finishing must not unblock it.
     */
    private val blockedCount = ConcurrentHashMap<Box, Int>()

    private fun isBlocked(box: Box): Boolean = (blockedCount[box] ?: 0) > 0

    fun runStarted(box: Box, mode: String) {
        val st = runs.computeIfAbsent(box) { RunState() }
        synchronized(st) {
            if (st.depth++ == 0) {
                st.startSeq = PropertyDependencies.clock.incrementAndGet()
                st.pulled.clear()
                st.changes.clear()
                lastRunStart[docKey(box)] = st.startSeq
            }
        }
        event("start ${label(box)} $mode")
    }

    fun runFinished(box: Box, ok: Boolean, mode: String) {
        val st = runs[box] ?: return
        val changes: List<Change>
        val waiters: List<CompletableDeferred<Boolean>>
        synchronized(st) {
            st.depth = (st.depth - 1).coerceAtLeast(0)
            if (st.depth > 0) return
            changes = st.changes.toList()
            waiters = st.waiters.toList()
            st.changes.clear()
            st.waiters.clear()
        }
        event("end ${label(box)} ${if (ok) "ok" else "failed"}")
        waiters.forEach { it.complete(ok) }
        if (changes.isNotEmpty()) {
            if (ok) Downstream.submit(changes)
            else event("dropped ${changes.size} change(s) from failed run of ${label(box)}")
        }
    }

    /** No cascade draining and none waiting to: for tests that need the downstream reruns to be over. */
    fun isQuiet(): Boolean = !Downstream.draining && Downstream.pendingCount() == 0

    fun isRunning(box: Box): Boolean = runs[box]?.let { synchronized(it) { it.depth > 0 } } == true

    /** The next time [box] finishes a run (the one in progress, if any), true for a success. */
    private fun nextFinish(box: Box): CompletableDeferred<Boolean> {
        val done = CompletableDeferred<Boolean>()
        val st = runs.computeIfAbsent(box) { RunState() }
        synchronized(st) { st.waiters += done }
        return done
    }

    /** Hold a run of [box] while [box] is blocked in a pull, so it does not start inside its own read. */
    suspend fun awaitNotBlocked(box: Box) {
        if (!isBlocked(box)) return
        event("held run of ${label(box)} until its pull returns")
        while (isBlocked(box)) delay(5)
    }

    // ---------------------------------------------------------------------------------------------
    // Up

    class PullOutcome(val satisfied: Boolean, val tried: List<String>, val problems: List<String>) {
        fun describe(name: String): String {
            if (tried.isEmpty() && problems.isEmpty()) return ""
            return " (propUp '$name': " + (if (tried.isNotEmpty()) "ran ${tried.joinToString { "'$it'" }}; " else "") +
                problems.joinToString("; ") + ")"
        }
    }

    private val lastPull = ConcurrentHashMap<String, Long>()

    /**
     * Run the boxes that supply [name] to [asking] (read by [reader]), nearest first, until
     * [satisfied]; see the class comment for how, and for the guards.
     */
    fun pull(reader: Box, asking: Box, name: String, satisfied: () -> Boolean): PullOutcome {
        if (!enabled) return PullOutcome(false, emptyList(), listOf("reactive props are off"))
        if (Runloop.mainThread == null) return PullOutcome(false, emptyList(), listOf("no runloop is running to run a supplier on"))
        val suppliers = suppliersFor(asking, reader, name)
        if (suppliers.isEmpty()) {
            event("pull none for ${label(reader)}.$name")
            return PullOutcome(false, emptyList(), listOf("no box upstream declares '$name' or has supplied it before"))
        }
        val tried = ArrayList<String>()
        val problems = ArrayList<String>()
        for (s in suppliers) {
            if (isBlocked(s)) {
                event("cycle ${label(reader)}.$name <- ${label(s)}")
                problems += "'${label(s)}' is itself waiting for a value, so running it would be a cycle"
                continue
            }
            if (s.ke !is KotlinExecution && s.ke !is FieldMLExecution) {
                problems += "'${label(s)}' has no Kotlin or FieldML execution to pull"
                continue
            }
            // Someone else's pull of this supplier is under way (this read may be running inside its
            // pump): wait for that one rather than starting a second run.
            inFlight[s]?.let { pending ->
                event("join pull of ${label(s)} for ${label(reader)}.$name")
                tried += label(s)
                val ok = waitFor(pending, reader)
                if (satisfied()) return PullOutcome(true, tried, problems)
                problems += "'${label(s)}' was already running for another read and did not set '$name' (${ok ?: "timeout"})"
                continue
            }
            val key = "${docKey(s)}|$name"
            val st = runs[reader]
            val readerInRun = st != null && synchronized(st) { st.depth > 0 }
            if (readerInRun) {
                if (!synchronized(st) { st.pulled.add(key) }) {
                    problems += "'${label(s)}' already ran for '$name' during this run"
                    continue
                }
            } else {
                val last = lastPull[key]
                if (last != null && System.currentTimeMillis() - last < PULL_REPEAT_WINDOW_MS) {
                    problems += "'${label(s)}' ran for '$name' less than ${PULL_REPEAT_WINDOW_MS} ms ago"
                    continue
                }
            }
            lastPull[key] = System.currentTimeMillis()
            event("pull ${label(s)} for ${label(reader)}.$name")
            tried += label(s)
            val ok = runAndWait(s, reader)
            event("pulled ${label(s)} for ${label(reader)}.$name ${when (ok) { true -> "ok"; false -> "failed"; null -> "timeout" }}")
            if (satisfied()) return PullOutcome(true, tried, problems)
            problems += when (ok) {
                true -> "'${label(s)}' ran but did not set '$name'"
                false -> "'${label(s)}' ran and failed"
                null -> "'${label(s)}' did not finish within $pullTimeoutMs ms"
            }
        }
        return PullOutcome(false, tried, problems)
    }

    private val pulledOk = ConcurrentHashMap<String, Long>()

    /** The next value of the shared clock: launches stamp themselves with it when they are queued. */
    fun stamp(): Long = PropertyDependencies.clock.incrementAndGet()

    /** True when a pull ran [box] successfully, launched after [stamp]. */
    fun pulledSince(box: Box, stamp: Long): Boolean = (pulledOk[docKey(box)] ?: 0L) > stamp

    /** Pulls under way, by supplier: completed with the supplier's verdict when its run finishes. */
    private val inFlight = ConcurrentHashMap<Box, CompletableDeferred<Boolean>>()

    private fun runAndWait(s: Box, reader: Box): Boolean? {
        val done = nextFinish(s)
        inFlight[s] = done
        val launchedAt = stamp()
        try {
            if (isRunning(s)) {
                // Already evaluating (and not blocked, or we would have refused): join that run.
                event("join ${label(s)}")
            } else {
                launchNow(s)
            }
            val ok = waitFor(done, reader)
            if (ok == true) pulledOk[docKey(s)] = launchedAt
            return ok
        } finally {
            inFlight.remove(s, done)
        }
    }

    private fun launchNow(s: Box) {
        val source = s.getPropertyRaw("code") as? String ?: ""
        val execute = Execute(s.id, "launch", 1, source, false, strand = "default")
        FlightRecorder.recordLaunchEntrypoint("propUp.pull", s, execute, strand = "default")
        val previousImmediate = launchImmediately.get()
        val previousSession = thisSession.get()
        launchImmediately.set(true)
        // The supplier's output belongs to its own document's pages, not to the reader's request.
        thisSession.remove()
        try {
            s.ke.launchAll(s, execute, source, "default", documentReply(s))
        } finally {
            if (previousImmediate == null) launchImmediately.remove() else launchImmediately.set(previousImmediate)
            if (previousSession == null) thisSession.remove() else thisSession.set(previousSession)
        }
    }

    private fun documentReply(box: Box): (Message) -> Unit = { m ->
        if (box.isInsideInitialized()) box.inside.sessions.forEach { _send(it, m) }
    }

    /** Wait for [done]: on the runloop by pumping it, elsewhere by blocking. Null on timeout. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun waitFor(done: CompletableDeferred<Boolean>, reader: Box): Boolean? {
        val deadline = System.currentTimeMillis() + pullTimeoutMs
        if (!Runloop.isMainThread) {
            return runBlocking { withTimeoutOrNull(pullTimeoutMs) { done.await() } }
        }
        val savedBox = Box.context.get()
        val savedSession = thisSession.get()
        val savedInfo = thisSessionInfo.get()
        fun restore() {
            if (savedBox == null) Box.context.remove() else Box.context.set(savedBox)
            if (savedSession == null) thisSession.remove() else thisSession.set(savedSession)
            if (savedInfo == null) thisSessionInfo.remove() else thisSessionInfo.set(savedInfo)
        }
        blockedCount.merge(reader, 1, Int::plus)
        try {
            while (!done.isCompleted) {
                if (System.currentTimeMillis() > deadline) return null
                val n = Runloop.runLoop.main.frame()
                restore()
                if (n == 0) LockSupport.parkNanos(200_000)
            }
        } finally {
            blockedCount.compute(reader) { _, n -> if (n == null || n <= 1) null else n - 1 }
            restore()
        }
        return done.getCompleted()
    }

    private val declarationOf = ConcurrentHashMap<String, Regex>()
    private val propCallOf = ConcurrentHashMap<String, Regex>()

    /**
     * The boxes that supply [name] to [asking] (read by [reader]), nearest first, never including
     * [reader], the root, or a deleted box. [asking] itself comes first when it is not the reader and
     * declares [name] — `named("other").speed` is asking `other` for its own value.
     */
    fun suppliersFor(asking: Box, reader: Box, name: String): List<Box> {
        if (!asking.isInsideInitialized()) return emptyList()
        val info = asking.inside
        val out = LinkedHashSet<Box>()
        fun lookup(uid: String): Box? = info.boxes[uid] ?: asking.parents.firstOrNull { it.id == uid }
        val declaration = declarationOf.getOrPut(name) { Regex("""\b(?:val|var)\s+Box\.${Regex.escape(name)}\s+by\b""") }
        val propCall = propCallOf.getOrPut(name) {
            Regex("""\b(?:prop|propUp|propDown|propUpDown)\s*<.*>\s*\(\s*${Regex.escape(name)}\s*[,)]|\bsetProp(?:Up|UpDown)\s+"${Regex.escape(name)}"""")
        }

        fun declaresInOwnCodeOrPreamble(b: Box): Boolean {
            val own = (b.getPropertyRaw("preamble") as? String).orEmpty()
            val ownDeclares = own.lineSequence().any { line ->
                declaration.containsMatchIn(line) && preambleSupplierTag(line).let { it == null || it == PREAMBLE_SELF_SUPPLIER }
            }
            return ownDeclares || propCall.containsMatchIn(withoutComments((b.getPropertyRaw("code") as? String).orEmpty()))
        }

        // 0. the box being asked, when it is someone else's
        if (asking !== reader && declaresInOwnCodeOrPreamble(asking)) out += asking
        // 0b. a root slot: the boxes whose code declares it with rootPropUp / rootPropUpDown
        if (asking === info.root) {
            val rootCall = Regex("""\brootPropUp(?:Down)?\s*<.*>\s*\(\s*${Regex.escape(name)}\s*[,)]""")
            info.boxes.values
                .filter { it !== reader && !it.getPropertyRaw("_tombstone").isTrue() }
                .filter { rootCall.containsMatchIn(withoutComments((it.getPropertyRaw("code") as? String).orEmpty())) }
                .forEach { out += it }
        }
        // 1. who the gathered preamble says declared it
        val gathered = runCatching { gatherPreambleUpwardsReport(asking).text }.getOrDefault("")
        for (line in gathered.lineSequence()) {
            if (!declaration.containsMatchIn(line)) continue
            val tag = preambleSupplierTag(line) ?: continue
            if (tag == PREAMBLE_SELF_SUPPLIER) continue
            lookup(tag)?.let { out += it }
        }
        // 2. who supplied it before
        val store = PropertyDependencies.storeFor(info.filename)
        for (id in listOf(reader.id, asking.id).distinct()) {
            for (uid in PropertyDependencies.rememberedSuppliers(store, id, name, info.root.id)) lookup(uid)?.let { out += it }
        }
        // 3. ancestors that declare it, nearest first — in a preamble, or in code that never ran
        val seen = HashSet<Box>()
        var fringe = listOf(asking)
        while (fringe.isNotEmpty()) {
            val next = ArrayList<Box>()
            for (b in fringe) for (p in b.parents) if (seen.add(p)) {
                next += p
                if (declaresInOwnCodeOrPreamble(p)) out += p
            }
            fringe = next
        }
        return out.filter {
            it !== reader && it.id != info.root.id && it.id != METAROOT_ID &&
                !it.getPropertyRaw("_tombstone").isTrue()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Down

    /** [writer]'s code changed [name] on [holder]; [seq] orders it against reads. */
    class Change(val holder: Box, val name: String, val seq: Long, val writer: Box?)

    private val downDeclared: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private fun downKey(box: Box, name: String) = docKey(box) + "\u0000" + name

    /** [box]'s code declared [name] with `propDown` / `propUpDown`; recorded when the macro expands. */
    fun declaredDown(box: Box, name: String) {
        downDeclared.add(downKey(box, name))
    }

    /** For a root slot: the boxes whose `rootPropDown` / `rootPropUpDown` declared it, by [downKey] of the root. */
    private val rootDownDeclarers = ConcurrentHashMap<String, MutableSet<String>>()

    /** [declarer]'s code declared [name] on [root] with `rootPropDown` / `rootPropUpDown`. */
    fun declaredDownOnRoot(declarer: Box, root: Box, name: String) {
        val key = downKey(root, name)
        downDeclared.add(key)
        rootDownDeclarers.getOrPut(key) { ConcurrentHashMap.newKeySet() }.add(declarer.id)
    }

    /**
     * A write that did not go through the `propDown` delegate (a control bound with
     * `syncToProperty`, which calls `setPropertyRaw`): if [holder] declared [name] Down and the
     * value changed, its readers rerun as they would after `holder.name = v`.
     */
    fun noteRawWrite(holder: Box, name: String, old: Any?, new: Any?) {
        if (old != new && downDeclared.isNotEmpty() && isDeclaredDown(holder, name)) noteChange(holder, name)
    }

    /**
     * Whether [box] still declares [name] Down. A declaration is recorded when the macro expands
     * and is dropped here, lazily, once the box is deleted (tombstoned) or its code no longer
     * contains a `propDown` / `propUpDown` of that name — so editing the macro out, or deleting the
     * box, stops raw writes from rerunning readers.
     */
    fun isDeclaredDown(box: Box, name: String): Boolean {
        val key = downKey(box, name)
        if (!downDeclared.contains(key)) return false
        rootDownDeclarers[key]?.let { declarers ->
            // A root slot: still declared while some declaring box is alive and still says so.
            val boxes = if (box.isInsideInitialized()) box.inside.boxes else emptyMap()
            declarers.removeIf { id ->
                val d = boxes[id]
                d == null || d.getPropertyRaw("_tombstone").isTrue() ||
                    !codeDeclaresDown((d.getPropertyRaw("code") as? String).orEmpty(), name)
            }
            if (declarers.isEmpty()) { rootDownDeclarers.remove(key); downDeclared.remove(key); return false }
            return true
        }
        val code = box.getPropertyRaw("code") as? String
        val stillThere = !box.getPropertyRaw("_tombstone").isTrue() &&
            (code == null || codeDeclaresDown(code, name))
        if (!stillThere) downDeclared.remove(key)
        return stillThere
    }

    /** Whether any per-box record of [box] is still held; for tests. */
    internal fun remembers(box: Box): Boolean {
        val k = docKey(box)
        return runs.containsKey(box) || lastRunStart.containsKey(k) || pulledOk.containsKey(k) ||
            lastPull.keys.any { it.startsWith("$k|") } || downDeclared.any { it.startsWith(k + "\u0000") }
    }

    /**
     * Forgets every Down declaration of [box], and its run records (which otherwise held the
     * deleted Box for the life of the JVM); for box deletion. A run still in flight keeps its
     * state so its end is still seen.
     */
    fun forgetDown(box: Box) {
        val key = docKey(box)
        runs.computeIfPresent(box) { _, st -> if (synchronized(st) { st.depth > 0 || st.waiters.isNotEmpty() }) st else null }
        lastRunStart.remove(key)
        pulledOk.remove(key)
        lastPull.keys.removeIf { it.startsWith("$key|") }
        val prefix = key + "\u0000"
        downDeclared.removeIf { it.startsWith(prefix) }
        rootDownDeclarers.values.forEach { it.remove(box.id) }
    }

    private fun downMacroPattern(name: String) =
        Regex("""\b(?:propDown|propUpDown|rootPropDown|rootPropUpDown)\s*<[^\n]*>\s*\(\s*""" + Regex.escape(name) + """\s*[,)]""")

    /** Whether [code], outside its comments, declares [name] Down. */
    private fun codeDeclaresDown(code: String, name: String): Boolean =
        downMacroPattern(name).containsMatchIn(withoutComments(code))

    /**
     * [code] with its `//` and (nesting) `/* */` comments blanked, so a declaration commented out
     * is not one; a `//` inside a string literal is left alone. Both Kotlin and FieldML comment with
     * `//`, which is the case that matters (commenting out a line).
     */
    internal fun withoutComments(code: String): String {
        if ("//" !in code && "/*" !in code) return code
        val out = StringBuilder(code.length)
        var i = 0
        var inString = false
        var depth = 0
        while (i < code.length) {
            val c = code[i]
            val next = if (i + 1 < code.length) code[i + 1] else '\u0000'
            when {
                depth > 0 -> {
                    if (c == '*' && next == '/') { depth--; i += 2; out.append("  "); continue }
                    if (c == '/' && next == '*') { depth++; i += 2; out.append("  "); continue }
                    out.append(if (c == '\n') '\n' else ' ')
                }
                inString -> {
                    out.append(c)
                    if (c == '\\' && i + 1 < code.length) { out.append(next); i += 2; continue }
                    if (c == '"' || c == '\n') inString = false
                }
                c == '"' -> { inString = true; out.append(c) }
                c == '/' && next == '/' -> {
                    while (i < code.length && code[i] != '\n') { out.append(' '); i++ }
                    continue
                }
                c == '/' && next == '*' -> { depth = 1; i += 2; out.append("  "); continue }
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }

    /** A `propDown` value changed: hold it to the end of the writer's run, or send it on now. */
    fun noteChange(holder: Box, name: String) {
        if (!enabled) return
        val writer = Box.context.get()
        val change = Change(holder, name, PropertyDependencies.clock.incrementAndGet(), writer)
        val st = writer?.let { runs[it] }
        if (st != null) {
            val held = synchronized(st) {
                if (st.depth > 0) {
                    st.changes += change
                    true
                } else false
            }
            if (held) return
        }
        Downstream.submit(listOf(change))
    }

    /**
     * Reruns the readers of changed values, one at a time, in dependency order: a reader goes only
     * when no other box still waiting to rerun can reach it through recorded flows, so a diamond's
     * bottom runs once, after both sides. A reader is waiting while its last read of the value (and
     * its last run) began before the change; a box that ran or read since is up to date. Each box
     * reruns at most [MAX_RUNS_PER_CASCADE] times per cascade, which is what stops a cycle. Readers
     * with no order between them rerun in the order their last runs started.
     */
    internal object Downstream {
        private val pending = ConcurrentLinkedQueue<Change>()
        private val mutex = Mutex()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /** True while a cascade is draining; for tests. */
        @Volatile
        var draining = false
            private set

        fun pendingCount(): Int = pending.size + scheduled.get()

        private val scheduled = java.util.concurrent.atomic.AtomicInteger()

        fun submit(changes: List<Change>) {
            pending.addAll(changes)
            changes.forEach { event("changed ${label(it.holder)}.${it.name} by ${label(it.writer)}") }
            scheduled.incrementAndGet()
            scope.launch {
                try {
                    // Coalesce a burst of writes (a dragged slider) into one pass.
                    delay(15)
                    drain()
                } finally {
                    scheduled.decrementAndGet()
                }
            }
        }

        private suspend fun drain() = mutex.withLock {
            draining = true
            try {
                val changes = ArrayList<Change>()
                val runsHere = HashMap<Box, Int>()
                val capped = HashSet<Box>()
                while (true) {
                    while (true) changes += pending.poll() ?: break
                    val dirty = dirtyReaders(changes).filterKeys { it !in capped }
                    if (dirty.isEmpty()) return@withLock
                    val next = pickNext(dirty)
                    if ((runsHere[next] ?: 0) >= MAX_RUNS_PER_CASCADE) {
                        capped += next
                        event("cap ${label(next)}: ran $MAX_RUNS_PER_CASCADE times in one cascade (a propDown cycle?)")
                        val message = "propDown: '${label(next)}' reran $MAX_RUNS_PER_CASCADE times in one cascade; stopping there (is there a cycle?)"
                        lprintln("warning,autorun", message)
                        // The server log is not where anyone is looking: say it on the box that stopped.
                        runCatching { next.error(message) }
                        continue
                    }
                    // A reader still evaluating (or blocked in a pull) finishes first; it may be up to
                    // date by then.
                    awaitIdle(next)
                    if (next !in dirtyReaders(changes)) continue
                    runsHere[next] = (runsHere[next] ?: 0) + 1
                    event("cascade ${label(next)}")
                    val done = nextFinish(next)
                    launchQueued(next)
                    val ok = withTimeoutOrNull(pullTimeoutMs) { done.await() }
                    if (ok != true) event("cascade ${label(next)} ${if (ok == null) "timeout" else "failed"}")
                    // A failed or timed-out rerun is not retried in this cascade.
                    if (ok != true) capped += next
                }
            } finally {
                draining = false
            }
        }

        private suspend fun awaitIdle(box: Box) {
            val deadline = System.currentTimeMillis() + pullTimeoutMs
            while ((isRunning(box) || isBlocked(box)) && System.currentTimeMillis() < deadline) delay(5)
        }

        /** Relaunches [box] on the strand it is running on (its first, if several), else `default`. */
        private fun launchQueued(box: Box) {
            val source = box.getPropertyRaw("code") as? String ?: ""
            val strand = box.currentRunningStates.keys.firstOrNull { !it.startsWith("__") } ?: "default"
            val execute = Execute(box.id, "launch", 1, source, false, strand = strand)
            FlightRecorder.recordLaunchEntrypoint("propDown.cascade", box, execute, strand = strand)
            box.ke.launchAll(box, execute, source, strand, documentReply(box))
        }

        /** Each reader still to rerun, with the earliest change it has not seen. */
        fun dirtyReaders(changes: List<Change>): Map<Box, Long> {
            val out = LinkedHashMap<Box, Long>()
            for (c in changes) {
                if (!c.holder.isInsideInitialized()) continue
                val info = c.holder.inside
                val store = PropertyDependencies.storeFor(info.filename)
                for (e in store.edges.values) {
                    if (e.key.access != PropertyDependencies.Access.READ) continue
                    if (e.key.supplier != c.holder.id || e.key.name != c.name) continue
                    val reader = info.boxes[e.key.reader] ?: continue
                    if (reader === c.writer || reader.getPropertyRaw("_tombstone").isTrue()) continue
                    val seen = maxOf(e.lastSeq, lastRunStart[docKey(reader)] ?: 0L)
                    if (seen >= c.seq) continue
                    out.merge(reader, c.seq, ::minOf)
                }
            }
            // Readers rerun in the order they last ran (pickNext still puts a reader after any waiting
            // box that feeds it): a set of passes launched in pipeline order, whose modules dispatch in
            // the order they were added, keeps that order. Then by the earliest change each missed.
            return out.entries.sortedWith(compareBy({ lastRunStart[docKey(it.key)] ?: Long.MAX_VALUE }, { it.value }))
                .associate { it.key to it.value }
        }

        /** The first waiting reader that no other waiting reader can reach through recorded flows. */
        fun pickNext(dirty: Map<Box, Long>): Box {
            val boxes = dirty.keys.toList()
            val adjacency = HashMap<String, MutableSet<String>>()
            boxes.mapNotNull { b -> b.takeIf { it.isInsideInitialized() }?.inside }.distinct().forEach { info ->
                for (f in PropertyDependencies.flows(PropertyDependencies.storeFor(info.filename), info.root.id)) {
                    adjacency.getOrPut(f.from) { HashSet() } += f.to
                }
            }
            return orderNext(boxes.map { it.id }, adjacency)?.let { id -> boxes.first { it.id == id } } ?: boxes.first()
        }
    }

    /**
     * Of [waiting] (in priority order), the first that no *other* waiting id reaches through
     * [adjacency]; the first of all when every one is reachable from another (a cycle).
     */
    fun orderNext(waiting: List<String>, adjacency: Map<String, Set<String>>): String? {
        if (waiting.isEmpty()) return null
        fun reachedByAnother(target: String): Boolean {
            for (start in waiting) {
                if (start == target) continue
                val seen = HashSet<String>()
                val stack = ArrayDeque<String>()
                stack += start
                while (stack.isNotEmpty()) {
                    val at = stack.removeLast()
                    for (n in adjacency[at].orEmpty()) {
                        if (n == target) return true
                        if (seen.add(n)) stack += n
                    }
                }
            }
            return false
        }
        return waiting.firstOrNull { !reachedByAnother(it) } ?: waiting.first()
    }
}
