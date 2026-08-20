package afield

import afield.logger.lprintln
import java.io.File
import java.net.URL
import java.net.URLClassLoader
import java.util.concurrent.atomic.AtomicInteger

/**
 * Declares Kotlin types that every box in a document can name.
 *
 * Boxes are separate compilation units: each has its own [KotlinInterface2] with its own REPL
 * history and snippet classloader, so a `data class` declared in one box's snippet is nested inside
 * that snippet's class, in that box's loader, and is invisible and unnameable everywhere else. That
 * is also why `@exported` could only ever carry such a value as `Any?` — see [ExportedTypeNames].
 *
 * The way out is not to share the snippet's class but to avoid making one: [publish] compiles the
 * declarations as *ordinary Kotlin* into a directory hung off the process-wide shared classloader.
 * Two things then follow for free, and one does not:
 *
 *  - **Runtime identity** is immediate. Every box's snippet loader is a child of the shared loader,
 *    so parent-first delegation gives all of them the same `Class`.
 *  - **Full fidelity.** These are real top-level classes with real metadata, so a `data class` keeps
 *    its named parameters, `toString`, `copy` and `componentN` in every box. That is the whole point
 *    of compiling rather than re-spelling the type as text.
 *  - **Existing engines do not see it.** Measured, not assumed — see `SharedTypesClasspathSpike`. A
 *    directory that is already on an engine's compile classpath but empty at the time stays empty as
 *    far as that engine is concerned: its compilation state indexes its classpath roots once, when
 *    it is created, and a class file appearing later is unresolvable to it. Re-deriving the script
 *    compilation configuration does not help, because the configuration was never the constraint.
 *
 * So publishing has to invalidate consuming engines, and [publish] does that by disposing them —
 * deferring the rebuild to whenever each box is next used rather than forcing it now. That is the
 * honest semantics of the feature: **changing a shared type restarts the boxes that could be using
 * it.** No in-place update exists, because a loaded class cannot be replaced.
 *
 * Generations are directories (`gen-1`, `gen-2`, …) rather than one mutable directory, so a
 * re-publish never has to overwrite a class file that a live box already loaded.
 */
object SharedTypes {

    /** Directory name under the workspace holding every generation. */
    const val ROOT_NAME = ".field-shared-types"

    /** Package the demo and docs assume; nothing here enforces it. */
    const val SUGGESTED_PACKAGE = "shared"

    private val generation = AtomicInteger(0)

    /**
     * Every type published in this process, so a re-publish can say so instead of silently not
     * taking effect. Measured behaviour, not a guess — see `SharedTypesSmokeTest.measureRepublish`.
     */
    private val publishedTypes = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** What a [publish] did, in a form a box can print. */
    data class Publication(
        val ok: Boolean,
        val generation: Int,
        val dir: File?,
        val classFiles: List<String>,
        val refreshedBoxes: List<String>,
        val log: String,
        /** Types this publish re-declared, which an earlier generation still shadows. */
        val shadowed: List<String> = emptyList(),
    ) {
        override fun toString(): String = if (ok) {
            buildString {
                append("shared types gen-$generation: ")
                append(classFiles.joinToString(", ").ifBlank { "(no classes?)" })
                if (refreshedBoxes.isNotEmpty()) {
                    append(" — restarted ${refreshedBoxes.size} box(es): ")
                    append(refreshedBoxes.joinToString(", "))
                    append(", and this one after this run")
                }
                if (shadowed.isNotEmpty()) {
                    append("\n!! NOT IN EFFECT: ")
                    append(shadowed.joinToString(", "))
                    append(" — an earlier generation of ")
                    append(if (shadowed.size == 1) "this type is" else "these types are")
                    append(" already on the shared classloader, and URL search order is first-wins. ")
                    append("A loaded class cannot be replaced: restart the document to pick this up.")
                }
            }
        } else {
            "shared types FAILED:\n$log"
        }
    }

    /**
     * Compile [source] to [outDir] as ordinary Kotlin, in a subprocess.
     *
     * Subprocess rather than in-process on purpose: the REPL is holding a live IntelliJ application
     * environment in this JVM and the CLI compilers reuse the process-wide one, which is the same
     * hazard `KotlinToJsCompiler` keeps a SUBPROCESS mode for.
     *
     * @return the compiler's combined output, and the class files produced.
     */
    fun compileTo(source: String, outDir: File, moduleName: String = "shared-types"): Pair<List<File>, String> {
        outDir.mkdirs()
        val srcDir = File(outDir.parentFile, "${outDir.name}-src").apply { mkdirs() }
        val src = File(srcDir, "SharedTypes.kt").apply { writeText(source.trimIndent()) }

        val classpath = System.getProperty("java.class.path")
        val javaBin = File(System.getProperty("java.home"), "bin/java").absolutePath
        val cmd = listOf(
            javaBin, "-cp", classpath,
            "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
            "-no-stdlib",
            "-classpath", classpath,
            "-module-name", moduleName,
            "-d", outDir.absolutePath,
            src.absolutePath,
        )
        val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val log = proc.inputStream.bufferedReader().readText()
        proc.waitFor()

        val produced = if (proc.exitValue() == 0) {
            outDir.walkTopDown().filter { it.extension == "class" }.toList()
        } else {
            emptyList()
        }
        return produced to log
    }

    /**
     * Compile [source] into a fresh generation, put it on the shared classloader, and invalidate the
     * engines that could not otherwise see it.
     *
     * @param workspace the document directory; generations live under [ROOT_NAME] inside it.
     * @param except a box to leave alone — normally the box calling this, which is mid-execution in
     *   the very engine we would otherwise dispose.
     * @param refreshOthers set false to publish without restarting anything, in which case only
     *   boxes that have not started yet will be able to name the new types.
     */
    fun publish(
        source: String,
        workspace: File,
        except: Box? = null,
        refreshOthers: Boolean = true,
        session: SessionInformation? = null,
    ): Publication {
        val root = File(workspace, ROOT_NAME)
        val gen = nextGeneration(root)
        val dir = File(root, "gen-$gen")

        val (produced, log) = compileTo(source, dir)
        if (produced.isEmpty()) {
            lprintln("error,ki", "shared types gen-$gen failed to compile:\n$log")
            return Publication(false, gen, null, emptyList(), emptyList(), log)
        }

        publishOnSharedLoader(dir)

        // Class files sit at their package-qualified path under dir, so the relative path is the FQN.
        val fqns = produced.map {
            it.relativeTo(dir).path.removeSuffix(".class").replace(File.separatorChar, '.')
        }
        val shadowed = fqns.filter { !publishedTypes.add(it) }
        if (shadowed.isNotEmpty()) {
            lprintln(
                "warning,ki",
                "shared types gen-$gen re-declares ${shadowed.joinToString(", ")}; the earlier " +
                    "generation still wins on the classloader, so this publish has no effect until restart"
            )
        }

        val refreshed = if (refreshOthers) invalidateOtherEngines(except, session) else emptyList()
        if (refreshOthers && except != null) scheduleCallerRebuild(except)
        lprintln(
            "info,ki",
            "shared types gen-$gen published to ${dir.path} " +
                "(${produced.size} class file(s), ${refreshed.size} box(es) restarted)"
        )
        return Publication(true, gen, dir, produced.map { it.name }, refreshed, log, shadowed)
    }

    /** One past the highest generation already on disk, so restarts do not collide. */
    private fun nextGeneration(root: File): Int {
        val onDisk = (root.listFiles() ?: emptyArray())
            .filter { it.isDirectory && it.name.startsWith("gen-") }
            .mapNotNull { it.name.removePrefix("gen-").toIntOrNull() }
            .maxOrNull() ?: 0
        // The process counter and the disk both move forward; take whichever is further ahead.
        return generation.updateAndGet { maxOf(it, onDisk) + 1 }
    }

    /**
     * Add [dir] to the loader every engine derives its classpath from. This is the same reflective
     * `addURL` the `@DependsOn` maven path uses; there is no public API for it.
     *
     * The directory must exist and hold class files before any engine snapshots the classpath — the
     * scripting classpath extractor silently drops entries that are neither an existing directory
     * nor a jar.
     */
    private fun publishOnSharedLoader(dir: File) {
        val loader = KotlinInterface2.sharedClassLoader
            ?: error(
                "no shared classloader yet — publish shared types from a box, after at least one " +
                    "engine exists, so the loader is the one every engine actually derives from"
            )
        val target = loader as? URLClassLoader
            ?: error("shared classloader is ${loader::class.java.name}, not a URLClassLoader; cannot publish")

        val addUrl = URLClassLoader::class.java.getDeclaredMethod("addURL", URL::class.java)
        addUrl.isAccessible = true
        addUrl.invoke(target, dir.toURI().toURL())
    }

    /**
     * Rebuild the *calling* box's engine too — deferred to the next runloop turn.
     *
     * The caller is excluded from [invalidateOtherEngines] because we are executing inside its
     * engine; disposing that inline would pull the compilation state out from under the rest of the
     * fragment and the post-run `ContextUpdater` that processes `@exported` declarations. But
     * leaving it alone permanently is worse, and produced a real failure: its engine snapshotted the
     * classpath *before* the publish, so it is the one box that can never resolve the new types. As
     * soon as a sibling `@rootExported` a value of a published type, the generated
     * `val Box._x by InheritanceFromContext<shared.Foo>()` line landed in the inherited root
     * preamble and this box — and only this box — failed to compile it.
     *
     * `inMain` queues rather than running inline (`CoroutineStart.DEFAULT` on the runloop
     * dispatcher), so the disposal lands after the current execution. [Box.disposeKi] only resets
     * the lazy, so the rebuild itself is deferred again to whenever the box is next used.
     */
    private fun scheduleCallerRebuild(caller: Box) {
        runCatching { inMain { caller.disposeKi() } }.onFailure {
            // No runloop (a bare jvm-main harness) — nothing is executing, so there is no engine to
            // protect and nothing to defer.
            runCatching { caller.disposeKi() }
        }
    }

    /**
     * Dispose every other box's engine that has actually started. Disposal rather than eager
     * rebuild: [Box.disposeKi] resets the lazy so the next use of that box builds a fresh engine,
     * which is when it will pick the new generation up off the classpath.
     */
    private fun invalidateOtherEngines(except: Box?, session: SessionInformation?): List<String> {
        if (session == null) return emptyList()
        val refreshed = mutableListOf<String>()
        for (box in session.boxes.values) {
            if (box === except) continue
            if (box.properties["_tombstone"].isTrue()) continue
            if (box.liveKi == null) continue // never started; it will build fresh anyway
            box.disposeKi()
            refreshed += box.name.ifBlank { box.id }
        }
        return refreshed
    }
}

/**
 * Declare types that every box in this document can name and use.
 *
 * ```
 * sharedTypes("""
 *     package shared
 *     data class Foo(val a: Double, val b: Double)
 * """)
 * ```
 *
 * Then, in any other box, `shared.Foo(a = 1.0, b = 2.0)` — with named arguments, `copy`,
 * destructuring and the data-class `toString`, because these are real compiled Kotlin classes and
 * not a type name re-spelled as text. A `shared.Foo` also now survives `@exported` between boxes
 * with its real type, which no box-declared class can (see [ExportedTypeNames]).
 *
 * Publishing restarts the other boxes in this document — a loaded class cannot be replaced, so
 * there is no in-place update. Boxes that have not run yet are unaffected; they build fresh anyway.
 *
 * @return a one-line summary suitable for printing.
 */
fun Box.sharedTypes(source: String, refreshOthers: Boolean = true): String {
    // Same resolution as FileManager.current(): the box's own session first, the ambient thread-local
    // only as a fallback. `thisSessionInfo` alone is null on the main runloop, which is exactly where
    // box code runs.
    val session = if (isInsideInitialized()) inside else thisSessionInfo.get()
    if (session == null) return "sharedTypes: no document session in scope"
    val workspace = File(session.filename).absoluteFile
    return SharedTypes.publish(
        source, workspace, except = this, refreshOthers = refreshOthers, session = session
    ).toString()
}
