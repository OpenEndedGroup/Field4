package afield.ai.knowledge

import afield.runtime.completion.KotlinSourceIndex

import afield.ai.conversation.*
import afield.ai.models.*
import afield.graphics.render.*
import afield.app.diagnostics.logging.lprintln
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import kotlin.io.path.isRegularFile

/**
 * Where Field's derived completion indexes live, resolved in one place for every index that needs it.
 *
 * [KotlinSourceIndex], [afield.ai.tools.GlslIncludeLibraryIndex] and the generated-completion-doc cache all read
 * their directory from [FilestoreDir]. The index is per checkout because `KotlinSourceIndex.runSync` indexes
 * `src/{jvmMain,personal,generatedLinalg}/kotlin` relative to `user.dir`: a single machine-wide directory
 * would let two checkouts, a docs build or a sibling worktree overwrite each other's index. Completion
 * ranking reads that index (a candidate's category comes from
 * [KotlinSourceIndex.getCompletionCategoryForSymbol]), so a shared directory would also make a test measure
 * whichever Field ran last rather than its own input.
 *
 * Resolution order, highest first:
 *
 * 1. `-Dafield.filestore.dir=<path>`, which the build forwards to Gradle test workers along with the rest
 *    of the `afield.` namespace, so a test can pin a fixture index.
 * 2. `AFIELD_FILESTORE_DIR=<path>`, the same for a shell or a CI job.
 * 3. `<user.dir>/.field/filestore`, the per-checkout default.
 *
 * On first use of (3) in a checkout that has none, the legacy `~/.afield/filestore` is copied in if it
 * exists. Those caches are expensive to rebuild: `filestore_cache.json` maps content hashes to OpenAI file
 * IDs, and discarding it re-uploads every source file. The copy happens once, is logged, and is
 * best-effort; a failure leaves an empty directory, which means a slow first run, not a wrong answer.
 * `FilestoreDirTest` exercises the default, the explicit pin and the empty-directory case. See `dev_docs/HERMETIC_COMPLETION_INPUTS.md`.
 */

/** Resolves the filestore directory: system property, then environment variable, then the checkout default. */
object FilestoreDir {

    const val PROPERTY = "afield.filestore.dir"
    const val ENV = "AFIELD_FILESTORE_DIR"

    /** The machine-wide `~/.afield/filestore`, which [FilestoreDir] copies from and never deletes. */
    val legacyGlobal: Path
        get() = Paths.get(System.getProperty("user.home"), ".afield", "filestore")

    @Volatile
    private var pinned: Path? = null

    @Volatile
    private var seeded: Path? = null

    /**
     * The directory itself. Read late rather than captured in a `val`, so that a test pinning it
     * after some object's `<clinit>` has run is still obeyed; a value captured at class-load time
     * would keep answering with whatever was resolved first.
     */
    val path: Path
        get() = pinned
            ?: System.getProperty(PROPERTY)?.trim()?.ifBlank { null }?.let(Paths::get)
            ?: System.getenv(ENV)?.trim()?.ifBlank { null }?.let(Paths::get)
            ?: perCheckout().also(::seedFromLegacyOnce)

    fun resolve(name: String): Path = path.resolve(name)

    /** Create it if it is not there, and return it. Callers that write should use this. */
    fun ensure(): Path = path.also { runCatching { Files.createDirectories(it) } }

    private fun perCheckout(): Path =
        Paths.get(System.getProperty("user.dir")).resolve(".field").resolve("filestore")

    /**
     * Copy the legacy global caches into a checkout that has none, once per JVM.
     *
     * A copy, not a move: another Field, or an older build, may still be reading the global copy,
     * and a half-migrated machine that answers slowly is better than one that answers wrongly.
     */
    private fun seedFromLegacyOnce(target: Path) {
        if (seeded == target) return
        synchronized(this) {
            if (seeded == target) return
            seeded = target
            if (Files.exists(target)) return
            val legacy = legacyGlobal
            if (!Files.isDirectory(legacy)) return
            runCatching {
                Files.createDirectories(target)
                var files = 0
                var bytes = 0L
                Files.list(legacy).use { stream ->
                    stream.filter { it.isRegularFile() }.forEach { src ->
                        Files.copy(src, target.resolve(src.fileName), StandardCopyOption.REPLACE_EXISTING)
                        files++
                        bytes += runCatching { Files.size(src) }.getOrDefault(0L)
                    }
                }
                lprintln(
                    "info,filestore",
                    "seeded $target from the legacy global filestore ($files file(s), ${bytes / 1024}KB); " +
                        "this checkout now has its own"
                )
            }.onFailure {
                lprintln("warning,filestore", "could not seed $target from $legacy: ${it.message}")
            }
        }
    }

    /**
     * Run [block] with the filestore pinned to [dir] — how a test hands the completion path a known
     * index instead of whatever the machine last wrote.
     *
     * Restores the previous pin rather than clearing it, so an inner pin leaves an outer one standing.
     */
    fun <T> withDirectory(dir: Path, block: () -> T): T {
        val was = pinned
        pinned = dir
        return try {
            block()
        } finally {
            pinned = was
        }
    }

    /** What was resolved and why, for a banner or a bug report. */
    fun describe(): String = when {
        pinned != null -> "$path (pinned in-process)"
        !System.getProperty(PROPERTY).isNullOrBlank() -> "$path (-D$PROPERTY)"
        !System.getenv(ENV).isNullOrBlank() -> "$path ($ENV)"
        else -> "$path (per-checkout default)"
    }
}
