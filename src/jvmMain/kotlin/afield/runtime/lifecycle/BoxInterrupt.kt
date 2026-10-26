package afield.runtime.lifecycle

import afield.app.diagnostics.logging.lprintln
import org.jetbrains.org.objectweb.asm.ClassReader
import org.jetbrains.org.objectweb.asm.ClassVisitor
import org.jetbrains.org.objectweb.asm.ClassWriter
import org.jetbrains.org.objectweb.asm.Label
import org.jetbrains.org.objectweb.asm.MethodVisitor
import org.jetbrains.org.objectweb.asm.Opcodes
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.script.experimental.api.CompiledScript
import kotlin.script.experimental.jvm.impl.KJvmCompiledModuleInMemory
import kotlin.script.experimental.jvm.impl.KJvmCompiledScript

/**
 * Box code that never returns no longer freezes Field: a stall watchdog plus a cooperative escape.
 *
 * Box code runs on the [Runloop] thread, so a `while (true) {}` in a box stops every frame, every
 * other box, and every coroutine that would have run `stop`. Java no longer offers a way to stop a
 * thread from outside (`Thread.stop` throws since JDK 20), so the escape is cooperative, and the
 * cooperation is compiled in: before a Kotlin snippet is evaluated, [instrument] rewrites its
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

    private val instrumented: MutableSet<Any> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

    /** Listeners told about every interrupt that actually fired (the UI and tests). */
    val onInterrupted = CopyOnWriteArrayList<(BoxInterruptedError) -> Unit>()

    @Volatile
    var enabled: Boolean = setting("AFIELD_BOX_INTERRUPT", "afield.boxInterrupt")?.let { it != "0" && it != "off" && it != "false" } ?: true

    /**
     * Called by instrumented box code at every method entry and loop back-edge. Must stay cheap:
     * the common path is one volatile read and a null check.
     */
    @JvmStatic
    fun poll() {
        val r = pending.get() ?: return
        if (r.thread !== Thread.currentThread()) return
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
     * Rewrite the newest snippet of [script] so its code polls. Idempotent per compiled module;
     * a module of an unknown shape is left alone (and logged once).
     */
    fun instrument(script: CompiledScript, boxLabel: String) {
        if (!enabled) return
        val k = script as? KJvmCompiledScript ?: return
        val module = k.getCompiledModule() as? KJvmCompiledModuleInMemory ?: return
        if (!instrumented.add(module)) return
        @Suppress("UNCHECKED_CAST")
        val files = module.compilerOutputFiles as? MutableMap<String, ByteArray> ?: return
        try {
            for ((name, bytes) in files.entries.toList()) {
                if (!name.endsWith(".class")) continue
                files[name] = instrumentClass(bytes, boxLabel) ?: continue
            }
        } catch (t: UnsupportedOperationException) {
            if (warnedImmutable.compareAndSet(false, true))
                lprintln("warn,execution,interrupt", "snippet module is read-only; box code will not be interruptible")
        }
    }

    private val warnedImmutable = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * [bytes] rewritten to poll, recording its class as [boxLabel]'s; null if it is not rewritable
     * (then the class runs unchanged, just not interruptibly).
     */
    fun instrumentClass(bytes: ByteArray, boxLabel: String): ByteArray? {
        return try {
            val reader = ClassReader(bytes)
            // COMPUTE_MAXS only: the inserted call has no stack effect and is never placed at a
            // label, so existing stack-map frames stay valid and no class loading is needed.
            val writer = ClassWriter(reader, ClassWriter.COMPUTE_MAXS)
            reader.accept(PollingClassVisitor(writer), 0)
            writer.toByteArray().also { classOwners[reader.className] = boxLabel }
        } catch (t: Throwable) {
            lprintln("warn,execution,interrupt", "could not instrument a snippet class: $t")
            null
        }
    }

    private const val OWNER = "afield/runtime/lifecycle/BoxInterrupt"

    private class PollingClassVisitor(next: ClassVisitor) : ClassVisitor(Opcodes.API_VERSION, next) {
        override fun visitMethod(access: Int, name: String?, descriptor: String?, signature: String?, exceptions: Array<out String>?): MethodVisitor? {
            val mv = super.visitMethod(access, name, descriptor, signature, exceptions) ?: return null
            if (access and (Opcodes.ACC_ABSTRACT or Opcodes.ACC_NATIVE) != 0) return mv
            // Constructors poll at back-edges only: a call before super() is legal bytecode, but
            // keeping <init> entry untouched keeps us clear of any verifier subtlety there.
            return PollingMethodVisitor(mv, pollAtEntry = name != "<init>" && name != "<clinit>")
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
        @Volatile var interruptAfterMs: Long = setting("AFIELD_RUNLOOP_WATCHDOG_INTERRUPT_MS", "afield.runloop.watchdog.interruptMs")?.toLongOrNull() ?: 5000L
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
                if (!interrupted && interruptAfterMs > 0 && stalledMs >= interruptAfterMs) {
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
