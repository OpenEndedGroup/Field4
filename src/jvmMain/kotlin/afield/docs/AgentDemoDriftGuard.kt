package afield.docs

import afield.SavingContext
import java.io.File
import kotlin.system.exitProcess

/**
 * Keeps loadable documents under `dev_docs/agents/demos/` honest about their loose source files.
 *
 * A `#afield/file-inline` property has two copies: the fallback embedded in `metadata.edn`, and the
 * loose file beside it. Field deliberately prefers the loose file, so an edited demo can run
 * correctly while its supposedly canonical metadata remains stale. This guard compares the two
 * byte-for-byte. `--write` refreshes the inline fallbacks through [SavingContext]'s real EDN codec.
 */
object AgentDemoDriftGuard {
    private const val DEMOS_REL = "dev_docs/agents/demos"

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.all { it == "--write" }) { "usage: AgentDemoDriftGuard [--write]" }
        val write = "--write" in args
        val root = repoRoot()
        val demosRoot = File(root, DEMOS_REL)
        require(demosRoot.isDirectory) { "no agent demo directory at ${demosRoot.absolutePath}" }
        val demos = (demosRoot.listFiles() ?: emptyArray())
            .filter { it.isDirectory && File(it, SavingContext.METADATA_EDN).isFile }
            .sortedBy { it.name }
        require(demos.isNotEmpty()) { "no Field documents under ${demosRoot.absolutePath}" }

        val saving = SavingContext()
        if (write) {
            demos.forEach { demo ->
                val refreshed = saving.refreshInlineFilesFromDisk(demo.absolutePath)
                println("[regenerate] ${demo.name}: ${refreshed.size} inline file(s)")
            }
        }

        val problems = mutableListOf<String>()
        var inlineFiles = 0
        for (demo in demos) {
            val documentRoot = demo.canonicalFile.toPath().normalize()
            val snapshots = saving.readInlineFileSnapshots(demo.absolutePath)
            if (snapshots.isEmpty()) {
                problems += "${demo.name}: metadata.edn has no #afield/file-inline properties"
                continue
            }
            inlineFiles += snapshots.size
            for (snapshot in snapshots) {
                val target = File(demo, snapshot.path).canonicalFile
                if (!target.toPath().normalize().startsWith(documentRoot)) {
                    problems += "${demo.name}/${snapshot.boxId}.${snapshot.property}: path escapes the demo (${snapshot.path})"
                    continue
                }
                if (!target.isFile) {
                    problems += "${demo.name}/${snapshot.boxId}.${snapshot.property}: missing ${snapshot.path}"
                    continue
                }
                val loose = target.readText()
                if (loose != snapshot.inline) {
                    val inlineLines = snapshot.inline.lines()
                    val looseLines = loose.lines()
                    val at = (0 until maxOf(inlineLines.size, looseLines.size))
                        .firstOrNull { inlineLines.getOrNull(it) != looseLines.getOrNull(it) } ?: 0
                    problems += "${demo.name}/${snapshot.boxId}.${snapshot.property}: ${snapshot.path} " +
                        "differs from metadata.edn at line ${at + 1}\n" +
                        "      metadata: ${inlineLines.getOrNull(at)}\n" +
                        "      loose:    ${looseLines.getOrNull(at)}"
                }
            }
        }

        if (problems.isNotEmpty()) {
            System.err.println("AgentDemoDriftGuard FAILED with ${problems.size} problem(s):")
            problems.forEach { System.err.println("  - $it") }
            System.err.println("Fix with: ./gradlew regenerateAgentDemos")
            exitProcess(1)
        }
        println("AgentDemoDriftGuard ok — ${demos.size} document(s), $inlineFiles inline file(s)")
    }

    private fun repoRoot(): File {
        var at: File? = File(System.getProperty("user.dir")).absoluteFile
        while (at != null) {
            if (File(at, "settings.gradle.kts").isFile && File(at, DEMOS_REL).isDirectory) return at
            at = at.parentFile
        }
        error("could not locate Field repo from user.dir=${System.getProperty("user.dir")}")
    }
}
