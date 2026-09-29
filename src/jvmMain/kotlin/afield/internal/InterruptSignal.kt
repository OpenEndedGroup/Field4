package afield

import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.util.concurrent.atomic.AtomicBoolean
import sun.misc.Signal
import sun.misc.SignalHandler

/**
 * Makes `kill -INT` (and Ctrl-C) stop Field even when Field was started with SIGINT ignored.
 *
 * `field.sh` runs the backend as `java … &` from a non-interactive `/bin/sh`. POSIX gives an
 * asynchronous command in a shell without job control SIGINT and SIGQUIT set to *ignored*, and the
 * shell cannot take that back (`trap - INT` in the subshell leaves it ignored: "signals that were
 * ignored on entry cannot be trapped or reset"). The same inheritance reaches a JVM started by
 * anything that itself was backgrounded by a non-interactive shell — an agent's `./field.sh &`, a
 * Python harness launched that way, a Gradle daemon first spawned from one.
 *
 * HotSpot then declines to install its own INT handler: `java.lang.Terminator` asks for one, and
 * `JVM_RegisterSignal` answers "ignored" without installing anything. So SIGINT to the listener
 * PID did nothing at all, and every stop-with-SIGINT in the automation (agent briefs, the smoke
 * runner, the docs runner) leaked a JVM and its port until someone escalated to SIGTERM. This is
 * the "Field ignores SIGINT" of backlog N0928-11; see `dev_docs/SIGINT_SHUTDOWN.md`.
 *
 * The repair has to happen in the process: clear the inherited disposition with libc `signal()`
 * (the JVM refuses to override SIG_IGN itself), then register a handler that exits the way
 * Terminator would have — `exit(128 + 2)`, so shutdown hooks (the persist flush, the misc-pool
 * drain) run. A second SIGINT while those hooks are still running halts immediately, so a hook
 * that wedges can always be cut short from the keyboard.
 *
 * When SIGINT was *not* inherited as ignored — a plain foreground launch, `gradlew runJvm` from
 * a terminal — Terminator's handler is already in place and this puts it straight back. Nothing
 * here runs on Windows, where SIGINT is not how Field is stopped.
 */
object InterruptSignal {

    /** What [install] found and did; reported once on the startup channel. */
    enum class Outcome { ALREADY_HANDLED, RESTORED_FROM_IGNORED, UNSUPPORTED, FAILED }

    private val exiting = AtomicBoolean(false)

    private val handler = SignalHandler { sig ->
        if (!exiting.compareAndSet(false, true)) {
            // Second Ctrl-C/INT while hooks run: don't wait for them.
            Runtime.getRuntime().halt(128 + sig.number)
        }
        // Exit off the signal-dispatch thread, as Terminator effectively does, so that a hook that
        // happens to want that thread cannot deadlock the shutdown.
        Thread({ System.exit(128 + sig.number) }, "afield-sigint-exit").start()
    }

    fun install(): Outcome {
        if (System.getProperty("os.name").lowercase().contains("windows")) return Outcome.UNSUPPORTED
        return try {
            val interrupt = Signal("INT")
            // Registering a handler is the only way to *read* the current disposition: the JVM
            // returns the previous one. When INT was ignored nothing is installed natively, and the
            // previous handler comes back as SIG_IGN.
            val previous = Signal.handle(interrupt, handler)
            if (previous != SignalHandler.SIG_IGN) {
                Signal.handle(interrupt, previous)
                return Outcome.ALREADY_HANDLED
            }
            resetToDefault(interrupt.number)
            Signal.handle(interrupt, handler)
            Outcome.RESTORED_FROM_IGNORED
        } catch (t: Throwable) {
            // -Xrs, a JVM without FFM native access, an unexpected libc: fall back to today's
            // behaviour (SIGTERM still works) rather than fail startup.
            System.err.println("afield: could not restore SIGINT handling: $t")
            Outcome.FAILED
        }
    }

    /** libc `signal(sig, SIG_DFL)`; SIG_DFL is the null pointer on both macOS and Linux. */
    private fun resetToDefault(sig: Int) {
        val linker = Linker.nativeLinker()
        val signalFn = linker.defaultLookup().find("signal").orElseThrow()
        val handle = linker.downcallHandle(
            signalFn,
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS)
        )
        handle.invokeWithArguments(sig, MemorySegment.NULL)
    }
}
