package afield.runtime.lifecycle

import afield.app.diagnostics.logging.lprintln
import afield.runtime.bytecode.BoxBytecode
import afield.runtime.bytecode.BoxBytecodePass
import afield.runtime.bytecode.ClassScope
import afield.runtime.bytecode.MethodScope
import afield.runtime.bytecode.SnippetOrigin
import org.jetbrains.org.objectweb.asm.Label
import org.jetbrains.org.objectweb.asm.MethodVisitor
import org.jetbrains.org.objectweb.asm.Opcodes
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * Getting out of box code that will not return: a stall watchdog, and the bytecode rewriting that
 * gives the interrupt somewhere to land.
 *
 * The whole public surface is [BoxInterrupt]: request an interrupt, poll for one, the rewrite pass
 * that inserts the poll ([BoxInterrupt.PollPass], run by `afield.runtime.bytecode.BoxBytecode` with
 * the other box-bytecode passes), and the nested watchdog that notices a Runloop which has stopped
 * making frames. `dev_docs/RUNAWAY_BOX_INTERRUPT.md` has the design.
 */

/**
 * A stall watchdog and a cooperative escape, for box code that never returns.
 *
 * Box code runs on the [Runloop] thread, so a `while (true) {}` in a box stops every frame, every
 * other box, and every coroutine that would have run `stop`. Java no longer offers a way to stop a
 * thread from outside (`Thread.stop` throws since JDK 20), so the escape is cooperative, and the
 * cooperation is compiled in: before a Kotlin snippet is evaluated, [PollPass] rewrites its
 * classes so that every method entry and every backward jump (the back-edge of every loop) calls
 * [poll]. [poll] is one volatile read until an interrupt has been [request]ed for the thread it runs
 * on; then it throws [BoxInterruptedError] out of the box code, and the box's run ends with an error
 * the usual way.
 *
 * An interrupt is requested by the "Interrupt Running Box" command (`interrupt-box`, dispatched on
 * the websocket's coroutine rather than the blocked Runloop), by the agent harness's RunBox timeout,
 * or by the [Watchdog] after the Runloop has made no frame for [Watchdog.interruptAfterMs]. The
 * watchdog also reports the stall, naming the box from the Runloop thread's stack (snippet class
 * names are recorded against their box when instrumented, because [RunScope.current] is
 * thread-local and cannot be read from another thread).
 *
 * What it cannot reach: a loop inside library or JDK code that box code called (a blocking
 * `Thread.sleep`, a `CountDownLatch.await`, a long native call) never polls. The watchdog still
 * reports those, and the interrupt request also calls `Thread.interrupt()`, which wakes the
 * JDK's blocking waits when box code is parked in one. See `dev_docs/RUNAWAY_BOX_INTERRUPT.md`.
 */
object BoxInterrupt {

    /** Thrown out of box code by [poll]. An [Error], so `catch (e: Exception)` in a box does not swallow it. */
    class BoxInterruptedError(val reason: String) : Error("Box interrupted: $reason") {
        // The stack is the box's own frames at the poll, which is the useful part.
    }

    private class Request(val thread: Thread, val reason: String, val frameAtRequest: Long)

    private val pending = AtomicReference<Request?>(null)

    /** Box labels by snippet class internal name (`Line_12_jsr223/...`), for naming a stall's culprit. */
    private val classOwners = ConcurrentHashMap<String, String>()

    /** Listeners told about every interrupt that actually fired (the UI and tests). */
    val onInterrupted = CopyOnWriteArrayList<(BoxInterruptedError) -> Unit>()

    /** `AFIELD_BOX_INTERRUPT` / `-Dafield.boxInterrupt`, when set; it overrides the welcome-page preference. */
    private val override: Boolean? = setting("AFIELD_BOX_INTERRUPT", "afield.boxInterrupt")?.let { it != "0" && it != "off" && it != "false" }

    /**
     * Whether newly compiled box code polls, and whether the [Watchdog] may interrupt. Off, the
     * watchdog still reports stalls. Code already loaded while it was on stays interruptible; a
     * re-evaluation of a cached snippet follows the switch as it is then.
     */
    @Volatile
    var enabled: Boolean = override ?: true

    /** Apply the persisted preference (`FieldPreferences.boxInterrupt`), unless the env/property override is set. */
    fun applyPreference(on: Boolean) {
        enabled = override ?: on
    }

    /**
     * Called by instrumented box code at every method entry and loop back-edge. Must stay cheap:
     * the common path is one opaque read and a null check. Opaque, not volatile: a volatile read on
     * every back-edge is an acquire barrier that stops the JIT vectorising the loop (`a[i] += i` over
     * an `IntArray` ran ~20x slower), while an opaque read still cannot be hoisted out of the loop, so
     * a request is seen within an iteration or two. The compare-and-set below is the real fence.
     */
    @JvmStatic
    fun poll() {
        val r = pending.getOpaque() ?: return
        if (r.thread !== Thread.currentThread()) return
        // A request outlives the box it was aimed at when that box returned on its own (or none was
        // running). The box that is stuck is stuck inside the frame it was asked in, so a request
        // from an earlier frame is stale: drop it rather than fire in whatever box runs next.
        if (Runloop.runLoop.frame != r.frameAtRequest) { pending.compareAndSet(r, null); return }
        if (!pending.compareAndSet(r, null)) return
        Thread.interrupted() // clear the flag [request] set, so it does not leak into later code
        val e = BoxInterruptedError(r.reason)
        lprintln("warn,execution,interrupt", "interrupted box code: ${r.reason}")
        onInterrupted.forEach { runCatching { it(e) } }
        throw e
    }

    /**
     * Ask [thread] (by default the Runloop thread) to leave box code at its next poll. Returns false
     * when there is no such thread. Safe to call from any thread, including while the Runloop is
     * blocked. A request is withdrawn by the [Watchdog] once the Runloop makes progress again, so a
     * request that found no box code to land in cannot fire later in an unrelated box.
     */
    fun request(reason: String, thread: Thread? = Runloop.mainThread): Boolean {
        val t = thread ?: return false
        pending.set(Request(t, reason, Runloop.runLoop.frame))
        // Wake a sleep/wait/park that box code is blocked in, so its next back-edge polls. Only then:
        // an interrupt flag left on the Runloop thread outside box code would break its idle park.
        val stack = t.stackTrace
        val boxDepth = stack.indexOfFirst { classOwners.containsKey(it.className.replace('.', '/')) }
        if (boxDepth >= 0 && stack.take(boxDepth).any { it.isBlockingWait() }) t.interrupt()
        return true
    }

    private fun StackTraceElement.isBlockingWait(): Boolean =
        (className == "java.lang.Thread" && methodName.startsWith("sleep")) ||
            (className == "java.lang.Object" && methodName == "wait0") ||
            (className == "jdk.internal.misc.Unsafe" && methodName == "park")

    /** True while a request has not yet landed or been withdrawn. */
    fun isPending(): Boolean = pending.get() != null

    internal fun withdrawIfFrameAdvanced(frame: Long) {
        val r = pending.get() ?: return
        if (frame != r.frameAtRequest) pending.compareAndSet(r, null)
    }

    /** Box label that owns the innermost snippet frame in [stack], or null. */
    fun culprit(stack: Array<StackTraceElement>): String? {
        for (e in stack) {
            val owner = classOwners[e.className.replace('.', '/')]
            if (owner != null) return owner
        }
        return null
    }

    /**
     * [bytes] rewritten to poll, recording its class as [boxLabel]'s; null if it is not rewritable
     * (then the class runs unchanged, just not interruptibly). Box snippets are rewritten through
     * [afield.runtime.bytecode.BoxBytecode], which runs [PollPass] with the other passes; this is the
     * same rewrite on its own, for tests.
     */
    fun instrumentClass(bytes: ByteArray, boxLabel: String): ByteArray? =
        BoxBytecode.rewriteClass(bytes, SnippetOrigin.detached(boxLabel), listOf(PollPass))

    private const val OWNER = "afield/runtime/lifecycle/BoxInterrupt"

    /**
     * The interrupt as a [BoxBytecodePass]: `INVOKESTATIC BoxInterrupt.poll()` at every method entry
     * (not `<init>`/`<clinit>`) and before every backward jump or switch. On while [enabled].
     */
    object PollPass : BoxBytecodePass {
        override val id = "interrupt"
        override val inserts = setOf(OWNER)

        override fun wants(origin: SnippetOrigin): Boolean = enabled

        // Constructors poll at back-edges only: a call before super() is legal bytecode, but
        // keeping <init> entry untouched keeps us clear of any verifier subtlety there.
        override fun method(cls: ClassScope, m: MethodScope, next: MethodVisitor): MethodVisitor =
            PollingMethodVisitor(next, pollAtEntry = !m.isInitializer)

        override fun classRewritten(cls: ClassScope) {
            classOwners[cls.className] = cls.origin.label
        }
    }

    private class PollingMethodVisitor(next: MethodVisitor, val pollAtEntry: Boolean) : MethodVisitor(Opcodes.API_VERSION, next) {
        private val seen = HashSet<Label>()

        private fun emitPoll() = super.visitMethodInsn(Opcodes.INVOKESTATIC, OWNER, "poll", "()V", false)

        override fun visitCode() {
            super.visitCode()
            if (pollAtEntry) emitPoll()
        }

        override fun visitLabel(label: Label) {
            seen.add(label)
            super.visitLabel(label)
        }

        override fun visitJumpInsn(opcode: Int, label: Label) {
            // A jump to a label already visited is a backward jump: the back-edge of a loop.
            // The poll goes before the jump; for a conditional jump that is before the operands
            // are consumed, which is fine because poll() has no stack effect.
            if (label in seen) emitPoll()
            super.visitJumpInsn(opcode, label)
        }

        override fun visitTableSwitchInsn(min: Int, max: Int, dflt: Label, vararg labels: Label) {
            if (dflt in seen || labels.any { it in seen }) emitPoll()
            super.visitTableSwitchInsn(min, max, dflt, *labels)
        }

        override fun visitLookupSwitchInsn(dflt: Label, keys: IntArray?, labels: Array<out Label>) {
            if (dflt in seen || labels.any { it in seen }) emitPoll()
            super.visitLookupSwitchInsn(dflt, keys, labels)
        }
    }

    internal fun setting(env: String, prop: String): String? =
        System.getProperty(prop)?.trim()?.lowercase() ?: System.getenv(env)?.trim()?.lowercase()

    /**
     * Notices that [Runloop.frame] has stopped advancing. After [reportAfterMs] it logs the stall
     * once, with the culprit box and the Runloop thread's stack; after [interruptAfterMs] (0 = never)
     * it [request]s an interrupt. Runs on its own daemon thread; never touches Runloop state beyond
     * reading the frame counter and the thread's stack.
     */
    object Watchdog {
        @Volatile var reportAfterMs: Long = setting("AFIELD_RUNLOOP_WATCHDOG_REPORT_MS", "afield.runloop.watchdog.reportMs")?.toLongOrNull() ?: 1500L
        @Volatile var interruptAfterMs: Long = setting("AFIELD_RUNLOOP_WATCHDOG_INTERRUPT_MS", "afield.runloop.watchdog.interruptMs")?.toLongOrNull() ?: 30_000L
        @Volatile var pollMs: Long = 100L

        /** Told about each stall report: (culprit box label or null, milliseconds stalled, stack). */
        val onStall = CopyOnWriteArrayList<(String?, Long, Array<StackTraceElement>) -> Unit>()

        @Volatile private var thread: Thread? = null

        @Synchronized
        fun start() {
            if (thread != null) return
            if (setting("AFIELD_RUNLOOP_WATCHDOG", "afield.runloop.watchdog") in setOf("0", "off", "false")) return
            thread = Thread(::loop, "afield-runloop-watchdog").apply { isDaemon = true; start() }
        }

        private fun loop() {
            var lastFrame = Runloop.runLoop.frame
            var since = System.nanoTime()
            var reported = false
            var interrupted = false
            while (true) {
                try { Thread.sleep(pollMs) } catch (_: InterruptedException) { return }
                val f = Runloop.runLoop.frame
                val now = System.nanoTime()
                if (f != lastFrame) {
                    if (reported) lprintln("info,runloop,watchdog", "Runloop moving again after ${(now - since) / 1_000_000} ms")
                    lastFrame = f; since = now; reported = false; interrupted = false
                    withdrawIfFrameAdvanced(f)
                    continue
                }
                val main = Runloop.mainThread ?: continue
                // An idle Runloop parks between frames but still advances; a parked/waiting main
                // thread inside our own idle strategy is not a stall worth interrupting box code for.
                val stalledMs = (now - since) / 1_000_000
                if (!reported && reportAfterMs > 0 && stalledMs >= reportAfterMs) {
                    reported = true
                    val stack = main.stackTrace
                    val who = culprit(stack)
                    val top = stack.take(12).joinToString("\n    at ")
                    lprintln("warn,runloop,watchdog", "Runloop has not made a frame for $stalledMs ms" +
                            (who?.let { "; box code from $it is running" } ?: "") + "\n    at " + top)
                    onStall.forEach { runCatching { it(who, stalledMs, stack) } }
                }
                if (enabled && !interrupted && interruptAfterMs > 0 && stalledMs >= interruptAfterMs) {
                    val who = culprit(main.stackTrace)
                    // Only box code polls, so only ask when box code is on the stack.
                    if (who != null) {
                        interrupted = true
                        request("Runloop stalled ${stalledMs} ms in $who (watchdog)", main)
                    }
                }
            }
        }
    }
}
