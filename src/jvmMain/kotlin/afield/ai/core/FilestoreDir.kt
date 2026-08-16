package afield.ai

import afield.logger.lprintln
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import kotlin.io.path.isRegularFile

/**
 * Where Field's derived completion indexes live — resolved in exactly one place.
 *
 * [KotlinSourceIndex], [afield.ai.GlslIncludeLibraryIndex] and the generated-completion-doc cache each
 * used to spell `~/.afield/filestore` for themselves. That directory is machine-global: it sits outside
 * every checkout and is rewritten by whichever Field ran last, which on this project means a docs build,
 * a sibling git worktree, or a nightly agent. `KotlinSourceIndex.runSync` indexes
 * `src/{jvmMain,personal,generatedLinalg}/kotlin` relative to `user.dir`, so two checkouts index
 * different trees into the same file and overwrite each other's answers.
 *
 * That is not only untidy. Completion reads the index — `KotlinCompletionSupport.classifyMetadata` asks
 * [KotlinSourceIndex.getCompletionCategoryForSymbol] for a candidate's category, and category is one of
 * `fieldSuggest.js`'s ranking inputs — so a test that reads through it is measuring the last Field to
 * run rather than its own input. `FieldSuggestPresentationTest`'s rank case did exactly that, and its
 * recorded ranks wobbled between runs while the candidate payload stayed at 249 rows.
 *
 * Resolution order, highest first:
 *
 *  1. `-Dafield.filestore.dir=<path>` — forwarded to Gradle test workers (the build forwards the whole
 *     `afield.` namespace), so a test can pin a fixture index.
 *  2. `AFIELD_FILESTORE_DIR=<path>` — the same, for a shell or a CI job.
 *  3. `<user.dir>/.field/filestore` — the default, and per checkout, so two checkouts cannot fight.
 *
 * On first use of (3) in a checkout that has none, the legacy `~/.afield/filestore` is copied in if it
 * exists. The caches there are expensive rather than merely large: `filestore_cache.json` maps content
 * hashes to OpenAI file IDs, and discarding it re-uploads every source file. The copy happens once, is
 * logged, and is best-effort — a failure leaves an empty directory, which is a slow first run and not a
 * wrong answer.
 *
 * See `dev_docs/HERMETIC_COMPLETION_INPUTS.md`.
 */
object FilestoreDir {

    const val PROPERTY = "afield.filestore.dir"
    const val ENV = "AFIELD_FILESTORE_DIR"

    /** Where `~/.afield/filestore` used to be, and still is for anyone who has not run this code. */
    val legacyGlobal: Path
        get() = Paths.get(System.getProperty("user.home"), ".afield", "filestore")

    @Volatile
    private var pinned: Path? = null

    @Volatile
    private var seeded: Path? = null

    /**
     * The directory itself. Read late rather than captured in a `val`, because a test that pins it
     * after some object's `<clinit>` has run must still be obeyed — a field initialised at class-load
     * time is the shape of bug this whole file exists to remove.
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
     * Deliberately not a move: another Field, or an older build, may still be reading the global copy,
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
     * Run [block] with the filestore pinned to [dir] — the seam a test needs to hand the completion
     * path a known index instead of whatever the machine last wrote.
     *
     * Restores the previous pin rather than clearing it, so nesting works and a test that pins does not
     * quietly un-pin an outer one.
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
