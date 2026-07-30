package afield

import java.lang.reflect.Modifier
import kotlin.system.exitProcess

object AgentJvmMainLauncher {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty()) {
            System.err.println("Usage: afield.AgentJvmMainLauncher <entrypoint> [args...]")
            exitProcess(2)
        }

        val entrypoint = args.first()
        val forwardedArgs = args.drop(1).toTypedArray()
        var exitCode = runCatching {
            invokeEntrypoint(entrypoint, forwardedArgs)
        }.getOrElse { throwable ->
            System.err.println("AgentJvmMainLauncher failed for $entrypoint: ${throwable.message}")
            throwable.printStackTrace(System.err)
            1
        }

        // A jvm-main entrypoint should let the JVM terminate on its own; the exitProcess
        // below is only a safety net. Any lingering non-daemon thread means the process
        // would otherwise hang — a latent source of CI timeouts. Surface it (and, under
        // AFIELD_JVM_MAIN_STRICT_EXIT, fail the run) so regressions can't hide.
        val lingering = lingeringNonDaemonThreads()
        if (lingering.isNotEmpty()) {
            System.err.println(
                "AgentJvmMainLauncher: $entrypoint left ${lingering.size} lingering non-daemon " +
                    "thread(s) after main returned; the JVM would not have exited naturally:"
            )
            for ((family, threads) in lingering.groupBy { threadFamily(it.name) }) {
                val exemplar = threads.first()
                val tally = if (threads.size > 1) " ×${threads.size}" else ""
                System.err.println("  - '$family'$tally state=${exemplar.state}")
                describeStack(exemplar).forEach { System.err.println("      $it") }
            }
            if (exitCode == 0 && System.getenv("AFIELD_JVM_MAIN_STRICT_EXIT")?.isNotBlank() == true) {
                exitCode = 3
            }
        }

        exitProcess(exitCode)
    }

    /**
     * Non-daemon threads still alive after the entrypoint returned, excluding this main
     * thread. A short settle lets thread pools wind down before we judge. These are the
     * threads that would keep the JVM from terminating without the exitProcess safety net.
     */
    private fun lingeringNonDaemonThreads(): List<Thread> {
        Thread.sleep(1_500)
        val self = Thread.currentThread()
        return Thread.getAllStackTraces().keys.filter { t ->
            t.isAlive && !t.isDaemon && t !== self && t.name != "DestroyJavaVM"
        }
    }

    /**
     * Collapses `pool-1-thread-7` to `pool-1-thread-N` so a pool of a dozen identical idle workers
     * reports as one entry with a count rather than a dozen indistinguishable stacks. The trailing
     * ordinal is the only thing that differs between them, and it is never the interesting part.
     */
    private fun threadFamily(name: String): String =
        name.replace(Regex("""\d+$"""), "N")

    /**
     * Frames that actually say where a thread came from.
     *
     * An idle pool worker parks inside the JDK, so its first frames are always
     * `Unsafe.park` / `LockSupport.park` / `AbstractQueuedSynchronizer` / `ForkJoinPool.managedBlock`
     * — four or five frames of pure JDK plumbing that are identical for every parked worker in every
     * pool and identify nothing. Printing only the top 5 therefore produced a dump in which no frame
     * named the code that created the pool, which is exactly the information needed to fix the leak.
     *
     * So: report the JDK preamble as a single summary line, then the first frames belonging to
     * anything else. Falls back to the raw top frames when a thread is *entirely* inside the JDK, so
     * this can only add information, never remove it.
     */
    private fun describeStack(thread: Thread, frames: Int = 8): List<String> {
        val trace = thread.stackTrace ?: return emptyList()
        if (trace.isEmpty()) return listOf("(no stack available)")

        val isPlumbing = { f: StackTraceElement ->
            f.className.startsWith("java.") || f.className.startsWith("jdk.") ||
                f.className.startsWith("sun.")
        }
        val skipped = trace.takeWhile(isPlumbing)
        val rest = trace.dropWhile(isPlumbing).take(frames)

        val lines = mutableListOf<String>()
        if (skipped.isNotEmpty()) {
            lines += "(${skipped.size} JDK frame(s) parked in ${skipped.last().className.substringAfterLast('.')}." +
                "${skipped.last().methodName})"
        }
        if (rest.isEmpty()) {
            // Entirely JDK-internal: the raw top frames are all there is, so show them.
            trace.take(frames).forEach { lines += "at $it" }
        } else {
            rest.forEach { lines += "at $it" }
        }
        return lines
    }

    private fun invokeEntrypoint(entrypoint: String, args: Array<String>): Int {
        val clazz = Class.forName(entrypoint)

        if (invokeMainIfPresent(clazz, args)) {
            return 0
        }

        val runTests = invokeRunTestsIfPresent(clazz)
        if (runTests != null) {
            return if (runTests) 0 else 1
        }

        error("No supported main() or runTests() entrypoint found on $entrypoint")
    }

    private fun invokeMainIfPresent(clazz: Class<*>, args: Array<String>): Boolean {
        val methods = clazz.methods.filter { it.name == "main" }
        for (method in methods) {
            val target = invocationTarget(clazz, method.modifiers)
            when (method.parameterCount) {
                0 -> {
                    method.invoke(target)
                    return true
                }

                1 -> {
                    val parameter = method.parameterTypes[0]
                    if (parameter.isArray && parameter.componentType == String::class.java) {
                        method.invoke(target, args)
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun invokeRunTestsIfPresent(clazz: Class<*>): Boolean? {
        val method = clazz.methods.firstOrNull {
            it.name == "runTests" && it.parameterCount == 0
        } ?: return null
        val target = invocationTarget(clazz, method.modifiers)
        val result = method.invoke(target)
        return when (result) {
            null -> true
            is Boolean -> result
            else -> true
        }
    }

    private fun invocationTarget(clazz: Class<*>, modifiers: Int): Any? {
        if (Modifier.isStatic(modifiers)) {
            return null
        }
        return clazz.getField("INSTANCE").get(null)
    }
}
