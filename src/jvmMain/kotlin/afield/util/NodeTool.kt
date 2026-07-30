package afield.util

import java.io.File

/**
 * Finding a `node` binary that is actually new enough to run the JavaScript this repository
 * generates.
 *
 * Eight call sites used to resolve Node themselves, each with a private copy of "first match on
 * `PATH`", and none with a version floor. That combination has a specific failure mode which cost
 * two wrong diagnoses before it was understood: this machine's non-interactive shell inherits every
 * `nvm` bin directory concatenated, with **v10.8.0 first**, so `node` resolved to a binary from
 * 2018 while v22 sat three entries further along the same `PATH`. Generated Kotlin/JS uses
 * `globalThis` (Node 12+) and optional chaining (Node 14+), so the tests failed inside a temp `.js`
 * file with a `SyntaxError` that pointed nowhere near the real cause.
 *
 * So the floor is the point, not the deduplication: [resolveNode] scans `PATH` for a candidate that
 * **meets** [minMajor] rather than taking the first one that merely exists, which fixes the
 * v10-first environment outright instead of only reporting it.
 *
 * Resolution order, first match wins:
 *  1. `AFIELD_NODE` / `NODE`, if set and executable — an explicit override always wins, and is
 *     honoured even if it fails the floor, because someone asking for a specific binary by path
 *     deserves to get it (and a clear failure if it is too old) rather than to be second-guessed.
 *  2. Every `PATH` entry, in order, whose `node` reports a major version >= [minMajor].
 *  3. The repository-local Node that Gradle installs under the `.gradle/nodejs` directory, which
 *     makes the JS-backed tests work under a deliberately minimal `PATH`.
 *
 * Returns `null` when nothing qualifies. Call sites decide whether that is a skip or a failure —
 * they legitimately differ, and [unavailableMessage] renders the reason either way.
 */
object NodeTool {

    /** Node major version required to run this repository's generated JavaScript. */
    const val DEFAULT_MIN_MAJOR: Int = 14

    /** A `node` candidate found on disk, with whatever `--version` reported. */
    data class Candidate(val path: String, val major: Int?, val version: String?)

    private val cache = HashMap<Int, Resolution>()

    private data class Resolution(val chosen: String?, val rejected: List<Candidate>)

    /**
     * Absolute path to a usable `node`, or `null` if none meets [minMajor].
     *
     * Cached per floor: resolving shells out to `node --version` once per candidate, and the
     * callers are tests that ask repeatedly.
     */
    @Synchronized
    fun resolveNode(minMajor: Int = DEFAULT_MIN_MAJOR): String? = resolution(minMajor).chosen

    /**
     * Why [resolveNode] came back `null`, phrased for a test's skip/failure message: what was
     * found, what version it reported, and what was needed. The 2026-07-27 incident was expensive
     * precisely because the failure said nothing about the binary it had picked.
     */
    @Synchronized
    fun unavailableMessage(minMajor: Int = DEFAULT_MIN_MAJOR): String {
        val rejected = resolution(minMajor).rejected
        if (rejected.isEmpty()) return "no `node` binary found on PATH, AFIELD_NODE/NODE, or .gradle/nodejs"
        val found = rejected.joinToString("; ") { c ->
            "${c.path} reports ${c.version ?: "an unreadable version"}"
        }
        return "no `node` >= v$minMajor available (needed for globalThis and optional chaining in " +
            "generated Kotlin/JS); found $found"
    }

    private fun resolution(minMajor: Int): Resolution = cache.getOrPut(minMajor) { resolve(minMajor) }

    private fun resolve(minMajor: Int): Resolution {
        val rejected = mutableListOf<Candidate>()

        // 1. explicit override, honoured verbatim
        for (name in listOf("AFIELD_NODE", "NODE")) {
            val file = System.getenv(name)?.takeIf(String::isNotBlank)?.let(::File) ?: continue
            if (file.canExecute() && file.isFile) return Resolution(file.absolutePath, rejected)
        }

        // 2. PATH, first entry that MEETS the floor
        for (file in pathCandidates()) {
            val candidate = describe(file)
            if (candidate.major != null && candidate.major >= minMajor) {
                return Resolution(file.absolutePath, rejected)
            }
            rejected += candidate
        }

        // 3. repository-local Node installed by Gradle
        for (file in managedCandidates()) {
            val candidate = describe(file)
            if (candidate.major != null && candidate.major >= minMajor) {
                return Resolution(file.absolutePath, rejected)
            }
            rejected += candidate
        }

        return Resolution(null, rejected)
    }

    private fun executableName(): String =
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "node.exe" else "node"

    private fun pathCandidates(): List<File> =
        (System.getenv("PATH") ?: "").split(File.pathSeparator)
            .filter(String::isNotBlank)
            .map { File(it, executableName()) }
            .filter { it.isFile && it.canExecute() }

    private fun managedCandidates(): List<File> =
        File(System.getProperty("user.dir"), ".gradle/nodejs").listFiles()
            .orEmpty()
            .map { File(it, "bin/${executableName()}") }
            .filter { it.isFile && it.canExecute() }

    /** `node --version` for one candidate. Never throws: an unrunnable binary is just not a match. */
    private fun describe(file: File): Candidate {
        val version = runCatching {
            val process = ProcessBuilder(file.absolutePath, "--version")
                .redirectErrorStream(true)
                .start()
            val text = process.inputStream.bufferedReader().readText().trim()
            if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly()
                null
            } else if (process.exitValue() == 0) text else null
        }.getOrNull()
        return Candidate(file.absolutePath, majorOf(version), version)
    }

    /** Major version out of a `v22.8.0`-style string. */
    internal fun majorOf(version: String?): Int? =
        version?.trim()?.removePrefix("v")?.substringBefore('.')?.toIntOrNull()
}
