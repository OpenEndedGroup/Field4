package afield.runtime.bytecode

import afield.app.diagnostics.logging.lprintln
import afield.runtime.lifecycle.BoxInterrupt
import afield.runtime.lifecycle.Runloop
import org.jetbrains.org.objectweb.asm.MethodVisitor
import org.jetbrains.org.objectweb.asm.Opcodes
import org.jetbrains.org.objectweb.asm.Type
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Thread-ownership guards: a report, naming the box line, when box code calls API that belongs to
 * one thread (a synth graph's `render`, which the audio thread owns; the renderer's frame calls,
 * which the Runloop owns) from another.
 *
 * [ThreadOwned] marks the API. [ThreadOwnershipPass] finds every call to it in box code and inserts
 * `LDC site; INVOKESTATIC ThreadOwnership.check(I)V` immediately before the call, or, when the
 * annotation asks for the last argument (`Signal.render`'s `SynthContext`), `DUP` of that argument
 * first and `check(Ljava/lang/Object;I)V`. The library itself checks in `Signal.render`'s cache-miss
 * branch, which is how a graph sampled by library code (the `GraphBindingRuntime` sampler that once
 * advanced a `Param.modulate(SineOsc(...))` oscillator from the Runloop) is caught too: no box-code
 * rewrite sees that call. Both report through [ThreadOwnership.report], once per site.
 * `dev_docs/box_bytecode/THREAD_OWNERSHIP.md` has the design and the allow/deny list.
 */

/** Which thread a [ThreadOwned] function belongs to. */
enum class ThreadOwner {
    /** The Runloop thread (`Runloop.mainThread`). With no Runloop running (a jvm-main entry), nothing is checked. */
    RUNLOOP,

    /**
     * The thread that owns the [ThreadOwnedContext] passed as the last argument (a live
     * `FieldAudioEngine`'s audio thread for its `SynthContext`). A context with no owner (an offline
     * render's own context) is not checked. Without a context argument, any thread that currently owns
     * a context passes.
     */
    AUDIO,
}

/**
 * Marks a function that must be called on its [owner]'s thread. [ThreadOwnershipPass] guards every
 * call to it in box code; read from class files only (`BINARY`), through [CallTargets], so an
 * override inherits it from the declaration it overrides.
 *
 * With [checkLastArgument] the guard also sees the call's last argument: when that is a
 * [ThreadOwnedContext] with an owner, that owner is the thread the call must be on. The argument must
 * be a single-slot reference; a primitive (and a wide `long`/`double`) last argument, or a call through
 * a default-arguments stub, is refused at rewrite time and guarded by [owner] alone.
 *
 * Annotate what touches thread-confined state, never a builder: constructing a graph, setting a
 * `Param`, adding a module to a renderer must stay callable from any thread.
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
annotation class ThreadOwned(val owner: ThreadOwner, val checkLastArgument: Boolean = false)

/**
 * Something that is used by one thread at a time and knows which: a `SynthContext` owned by the
 * audio thread that renders with it. [ownerThread] is null while nothing owns it (an offline render
 * builds and drives its own), and then nothing is checked against it. Read as a plain field.
 */
interface ThreadOwnedContext {
    /** The thread that owns this context now, or null. */
    val ownerThread: Thread?
}

/**
 * What a thread-guard [Site] carries: the guarded target (`Signal.render`), its [owner], and whether
 * the inserted code passes the last argument ([lastArgument]). [refused] names why the annotation
 * asked for the argument and the pass did not pass it (`wide`, `primitive`, `no-arguments`,
 * `default-stub`), or is null.
 */
class ThreadGuard(val target: String, val owner: ThreadOwner, val lastArgument: Boolean, val refused: String?) {
    // Plain fields, read on every guarded call.
    @JvmField internal val runloop = owner == ThreadOwner.RUNLOOP
    @JvmField internal var warned = false

    /** `Signal.render AUDIO(last)`, or `Renderer.render RUNLOOP`. */
    override fun toString(): String = "$target $owner" + (if (lastArgument) "(last)" else "") + (refused?.let { " refused:$it" } ?: "")
}

/**
 * One thread-ownership violation, as reported: [target] was called on [thread] but belongs to
 * [ownerThread]. [box] is the innermost box-code frame with a proven line (null when no box code is
 * on the stack, as for the `GraphBindingRuntime` sampler); [caller] is then the first frame outside
 * the guarded API, so a library culprit is named too.
 */
class ThreadOwnershipReport(
    val owner: ThreadOwner,
    val target: String,
    val thread: String,
    val ownerThread: String,
    val box: BoxFrame?,
    val site: Site?,
    val caller: StackTraceElement?,
    val message: String,
) {
    override fun toString(): String = message
}

/**
 * The runtime side of the thread-ownership guards: [check] (called by rewritten box code), the
 * library check `Signal.render` makes on a cache miss ([offThread]), and [report], the one place
 * both report through.
 *
 * Every read on the checked path is a plain field ([enabled], the guard table, the cached Runloop
 * thread, a context's owner): never volatile, by the interrupt's measured rule. Only a mismatch
 * takes the slow path, which re-reads `Runloop.mainThread` and, once per site, walks the stack.
 */
object ThreadOwnership {
    /** Internal name, for the inserted `INVOKESTATIC`. */
    const val OWNER = "afield/runtime/bytecode/ThreadOwnership"

    /**
     * Whether guards are inserted into newly compiled box code and whether any check reports. On by
     * default; `AFIELD_BOX_THREAD_GUARDS=off` or `-Dafield.boxBytecode.threadGuards=off` turns it off.
     * A plain field: a change reaches running code eventually, which is all a diagnostic needs.
     */
    @JvmField
    var enabled: Boolean = BoxInterrupt.setting("AFIELD_BOX_THREAD_GUARDS", "afield.boxBytecode.threadGuards")
        .let { it == null || (it != "0" && it != "off" && it != "false") }

    /** Guards by site id; plain, grown at rewrite time. A reader that sees an older, shorter array skips. */
    @JvmField internal var guards: Array<ThreadGuard?> = arrayOfNulls(256)

    // The Runloop thread as last read; a mismatch re-reads `Runloop.mainThread` (volatile) off the fast path.
    @JvmField internal var runloop: Thread? = null

    // Threads that own a context now, for an AUDIO guard with no context argument. Copy-on-write.
    @JvmField internal var audioThreads: Array<Thread> = emptyArray()

    /** Violations seen, reported or not (warn-once suppresses repeats). Racy count. */
    @JvmField var violations = 0L

    /** Of [violations], those found by the library check in `Signal.render`. Racy count. */
    @JvmField var libraryViolations = 0L

    // The library check walks the stack at most this often; a sustained violation is still caught.
    private const val LIBRARY_WALK_NS = 250_000_000L
    @JvmField internal var lastLibraryWalk = 0L

    /** Keys of the compiled lines (or library callers) already reported: warn once per site. */
    private val warnedKeys: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val recent = ArrayDeque<ThreadOwnershipReport>()
    private const val RECENT = 32

    /** Told about every report (each site once). */
    val onReport = CopyOnWriteArrayList<(ThreadOwnershipReport) -> Unit>()

    /** The last few reports, oldest first. */
    fun recentReports(): List<ThreadOwnershipReport> = synchronized(recent) { recent.toList() }

    @Synchronized
    internal fun register(site: Site, guard: ThreadGuard) {
        var g = guards
        if (site.id >= g.size) g = g.copyOf(maxOf(g.size * 2, site.id + 1))
        g[site.id] = guard
        guards = g
    }

    /** [thread] owns a context from now (`FieldAudioEngine`'s audio thread at start). */
    @Synchronized
    fun ownerStarted(thread: Thread) {
        if (audioThreads.none { it === thread }) audioThreads = audioThreads + thread
    }

    /** [thread] no longer owns a context. */
    @Synchronized
    fun ownerEnded(thread: Thread) {
        audioThreads = audioThreads.filter { it !== thread }.toTypedArray()
    }

    /** Inserted by [ThreadOwnershipPass] before a call to a [ThreadOwned] target. */
    @JvmStatic
    fun check(site: Int) {
        if (!enabled) return
        val g = guards
        if (site < 0 || site >= g.size) return
        val guard = g[site] ?: return
        val t = Thread.currentThread()
        if (guard.runloop) {
            if (t !== runloop) runloopMiss(site, guard, t)
        } else {
            audioMiss(site, guard, t)
        }
    }

    /**
     * Inserted by [ThreadOwnershipPass] before a call whose annotation asks for the last argument,
     * [value] (a `DUP` of it). A [ThreadOwnedContext] with an owner names the thread the call must be
     * on; otherwise the guard's own owner decides, as in [check].
     */
    @JvmStatic
    fun check(value: Any?, site: Int) {
        if (!enabled) return
        val g = guards
        if (site < 0 || site >= g.size) return
        val guard = g[site] ?: return
        val t = Thread.currentThread()
        if (value is ThreadOwnedContext) {
            val owner = value.ownerThread
            if (owner != null) {
                if (owner !== t) violation(site, guard, t, owner)
                return
            }
            // An unowned context: an offline render, nothing to hold an AUDIO call to.
            if (!guard.runloop) return
        }
        if (guard.runloop) {
            if (t !== runloop) runloopMiss(site, guard, t)
        } else {
            audioMiss(site, guard, t)
        }
    }

    private fun runloopMiss(site: Int, guard: ThreadGuard, t: Thread) {
        val main = Runloop.mainThread
        runloop = main
        if (main == null || main === t) return
        violation(site, guard, t, main)
    }

    private fun audioMiss(site: Int, guard: ThreadGuard, t: Thread) {
        val owners = audioThreads
        if (owners.isEmpty()) return
        for (o in owners) if (o === t) return
        // Off the fast path now. A thread that died without giving its context up owns nothing.
        val live = owners.firstOrNull { it.isAlive } ?: return
        violation(site, guard, t, live)
    }

    private fun violation(site: Int, guard: ThreadGuard, t: Thread, owner: Thread) {
        violations++
        if (guard.warned) return
        guard.warned = true
        val s = SnippetSites[site]
        val key = s?.let { "${it.className}:${it.line}:${guard.owner}" } ?: "site:$site"
        if (!warnedKeys.add(key)) return
        val stack = Thread.currentThread().stackTrace
        // The guarded call's own line, when it is proven; else the innermost proven box frame.
        val line = s?.boxLine() ?: -1
        val frame = if (s != null && line > 0) {
            BoxFrame(s.origin, line, StackTraceElement(s.className.replace('/', '.'), s.methodName, null, s.line))
        } else SnippetClasses.boxFrame(stack)
        report(guard.owner, guard.target, t, owner, frame, s, null)
    }

    /**
     * The library check: [what] (a `Signal`) is being rendered on [t] while [owner] owns it, found in
     * `Signal.render`'s cache-miss branch. Reports once per box line (or, with no box code on the
     * stack, per signal class and calling frame), and walks the stack at most four times a second, so
     * a violation that recurs every block costs a counter increment.
     */
    @JvmStatic
    fun offThread(what: Any, target: String, t: Thread, owner: Thread) {
        if (!enabled) return
        // An owner that has gone (an engine that stopped without releasing its context) owns nothing.
        if (!owner.isAlive) return
        violations++
        libraryViolations++
        val now = System.nanoTime()
        val last = lastLibraryWalk
        if (last != 0L && now - last < LIBRARY_WALK_NS) return
        lastLibraryWalk = now
        val stack = Thread.currentThread().stackTrace
        val frame = SnippetClasses.boxFrame(stack)
        val caller = callerOf(stack)
        val key = if (frame != null) "${frame.element.className.replace('.', '/')}:${frame.element.lineNumber}:${ThreadOwner.AUDIO}"
        else "lib:${what.javaClass.name}:${caller?.className}.${caller?.methodName}"
        if (!warnedKeys.add(key)) return
        report(ThreadOwner.AUDIO, "${what.javaClass.simpleName.ifEmpty { what.javaClass.name }} ($target)", t, owner, frame, null, caller)
    }

    // The first frame below the guarded API: past `getStackTrace`, this object, and the render/generate
    // frames of the synth graph itself.
    private fun callerOf(stack: Array<StackTraceElement>): StackTraceElement? = stack.firstOrNull { e ->
        e.className != "java.lang.Thread" && !e.className.startsWith("afield.runtime.bytecode.ThreadOwnership") &&
            !(e.className.startsWith("afield.audio.synth.") && (e.methodName == "render" || e.methodName == "generate" || e.methodName == "checkRenderOwner"))
    }

    /**
     * The one place a violation is reported: a `warning,execution` log line naming the box and line
     * (or the library caller), the [recentReports] list and the [onReport] listeners.
     */
    internal fun report(owner: ThreadOwner, target: String, t: Thread, ownerThread: Thread, box: BoxFrame?, site: Site?, caller: StackTraceElement?) {
        val where = box?.let { "${it.origin.label} line ${it.line}: " }
            ?: site?.let { "${it.origin.label} (compiled line ${it.line}, not proven in the box's code): " }
            ?: caller?.let { "$it: " }
            ?: ""
        val tName = threadName(t)
        val oName = threadName(ownerThread)
        val message = when (owner) {
            ThreadOwner.RUNLOOP -> "$where$target called on thread $tName, but it belongs to the Runloop thread $oName; " +
                "move the call into the box's frame (or `inMain { }`)"
            ThreadOwner.AUDIO -> "$where$target rendered on thread $tName, but the graph belongs to the audio thread $oName; " +
                "rendering it from two threads advances its oscillators, filters and envelopes twice. Publish a snapshot from the render path instead"
        }
        val r = ThreadOwnershipReport(owner, target, tName, oName, box, site, caller, message)
        synchronized(recent) {
            recent.addLast(r)
            while (recent.size > RECENT) recent.removeFirst()
        }
        lprintln("warning,execution", "thread ownership: $message")
        onReport.forEach { runCatching { it(r) } }
    }

    private fun threadName(t: Thread): String = "'${t.name}'" + (if (t === Runloop.mainThread) " (the Runloop)" else "")

    /** Forget what has been reported and the cached Runloop thread, for tests. */
    internal fun resetForTests() {
        warnedKeys.clear()
        synchronized(recent) { recent.clear() }
        runloop = null
        lastLibraryWalk = 0L
        guards.forEach { it?.warned = false }
    }

}

/**
 * The thread-guard pass: before every call in box code whose target [CallTargets] reports as
 * [ThreadOwned], `LDC site; INVOKESTATIC ThreadOwnership.check(I)V`, or `DUP; LDC site;
 * INVOKESTATIC ThreadOwnership.check(Ljava/lang/Object;I)V` when the annotation asks for the last
 * argument and that argument is a single-slot reference. Inserted from `visitMethodInsn` only, so it is
 * after the instruction's frame; it adds no jump, local or frame, and leaves the stack as it found it.
 *
 * In the chain it comes after the interrupt poll and before the call-site anchor, so a call that is
 * both guarded and anchored runs the guard's check, then the anchor's arm, then the call.
 */
object ThreadOwnershipPass : BoxBytecodePass {
    /** `threads`, in logs, dumps and a module's record of its passes. */
    override val id = "threads"

    /** Only [ThreadOwnership]: its two `check`s are the calls this pass inserts. */
    override val inserts = setOf(ThreadOwnership.OWNER)

    internal const val THREAD_OWNED = "Lafield/runtime/bytecode/ThreadOwned;"

    /** While [ThreadOwnership.enabled], for any snippet whose loader is alive (annotations are read through it). */
    override fun wants(origin: SnippetOrigin): Boolean = ThreadOwnership.enabled && origin.classLoader != null

    private val warnedRefusals: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * The guard for a call to [f] annotated [a]: its owner, and whether the last argument can be passed.
     * A `$default` stub's top of stack is its marker argument, a wide value cannot be `DUP`ed by the one
     * single-slot instruction the rule allows, and a primitive is not an `Object`: all three are refused
     * here, at rewrite time, and the call is guarded by its owner alone.
     */
    internal fun guardFor(f: CallFact, a: CallTargets.Annotation): ThreadGuard {
        val owner = (a.values["owner"] as? String)?.let { n -> ThreadOwner.entries.firstOrNull { it.name == n } } ?: ThreadOwner.RUNLOOP
        val target = "${f.owner.substringAfterLast('/')}.${f.name.removeSuffix("\$default")}"
        if (a.values["checkLastArgument"] != true) return ThreadGuard(target, owner, false, null)
        val args = Type.getArgumentTypes(f.desc)
        val refused = when {
            f.name.endsWith("\$default") -> "default-stub"
            args.isEmpty() -> "no-arguments"
            args.last().size == 2 -> "wide"
            args.last().sort != Type.OBJECT && args.last().sort != Type.ARRAY -> "primitive"
            else -> null
        }
        if (refused != null && warnedRefusals.add("$target$refused"))
            lprintln("warn,execution,bytecode", "thread guard for $target cannot pass its last argument ($refused); guarding by owner only")
        return ThreadGuard(target, owner, refused == null, refused)
    }

    override fun method(cls: ClassScope, m: MethodScope, next: MethodVisitor): MethodVisitor {
        val loader = cls.origin.classLoader ?: return next
        val calls = m.facts.calls
        if (calls.isEmpty()) return next
        var guarded: HashMap<CallFact, ThreadGuard>? = null
        for (f in calls) {
            if (cls.isInserted(f.owner)) continue
            val a = CallTargets.annotation(loader, f.owner, f.name, f.desc, THREAD_OWNED) ?: continue
            (guarded ?: HashMap<CallFact, ThreadGuard>().also { guarded = it })[f] = guardFor(f, a)
        }
        val found = guarded ?: return next
        return object : MethodVisitor(Opcodes.API_VERSION, next) {
            val cursor = m.callCursor()

            override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
                if (!cls.isInserted(owner)) {
                    val g = cursor.next(owner, name, descriptor)?.let { found[it] }
                    if (g != null) {
                        val site = cls.site(m, "thread-guard", g)
                        ThreadOwnership.register(site, g)
                        if (g.lastArgument) {
                            super.visitInsn(Opcodes.DUP)
                            super.visitLdcInsn(site.id)
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, ThreadOwnership.OWNER, "check", "(Ljava/lang/Object;I)V", false)
                        } else {
                            super.visitLdcInsn(site.id)
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, ThreadOwnership.OWNER, "check", "(I)V", false)
                        }
                    }
                }
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
            }
        }
    }
}
