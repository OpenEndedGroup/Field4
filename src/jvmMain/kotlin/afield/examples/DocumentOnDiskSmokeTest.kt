package afield.examples

import afield.Box
import afield.Rect
import afield.SavingContext
import java.io.File
import java.nio.file.Files

/*
FIELD_TEST
id: examples.document-on-disk.smoke
runner: jvm-main
entrypoint: afield.examples.DocumentOnDiskSmokeTest
tags: [smoke, persistence, docs]
timeoutSec: 90
expect:
  exitCode: 0
  stdoutContains:
    - "DocumentOnDiskSmokeTest ok"
*/
/**
 * Drives thirteen real [SavingContext.save] calls into a temp directory and prints what they leave.
 *
 * This is the producer for the two figures on `public/docs/document_on_disk.md`, and it is a real
 * save rather than a reconstruction: the same `save` the server calls, writing sidecars, then
 * `metadata.edn`, then `document.md`, through the same `File.safeSave` with the same per-call
 * `maxBackups` arguments. Three saves show which files exist; the retention asymmetry those
 * arguments produce only separates them past the tenth.
 *
 * `save` does its I/O on `Runloop.miscPool` and schedules only its bookkeeping through `inMain`, so
 * this runs under `jvm-main` with no runloop: every artifact the figure names is written on the pool
 * thread before the `inMain` hop that would never be pumped here. Hence the poll rather than
 * `onComplete`, which is inside that hop.
 */
object DocumentOnDiskSmokeTest {

    private const val WAIT_MS = 30_000L

    @JvmStatic
    fun main(args: Array<String>) {
        val dir = Files.createTempDirectory("afield-document-on-disk").toFile()
        val saving = SavingContext()

        val root = Box("root").apply {
            name = "Root"
            frame = Rect(0.0, 0.0, 100.0, 80.0)
        }
        val alpha = Box("alpha").apply {
            name = "alpha"
            frame = Rect(10.0, 20.0, 120.0, 90.0)
            code = "println(\"one\")\n"
        }
        val beta = Box("beta").apply {
            name = "beta"
            frame = Rect(160.0, 20.0, 120.0, 90.0)
            code = "println(\"beta\")\n"
            setPropertyRaw("fragment", "void main() { }\n")
        }
        root.children = mutableSetOf(alpha, beta)
        alpha.parents = mutableSetOf(root)
        beta.parents = mutableSetOf(root)

        val boxes = setOf(root, alpha, beta)

        // Three saves, each changing exactly one sidecar-backed property, so the backup indices are
        // predictable and the retention arguments are the only thing that varies between files.
        saveAndWait(saving, dir, boxes, "first save") { File(dir, "document.md").isFile }
        alpha.code = "println(\"two\")\n"
        saveAndWait(saving, dir, boxes, "second save") { backup(dir, "alpha_code__backup_00000.kt") }
        alpha.code = "println(\"three\")\n"
        saveAndWait(saving, dir, boxes, "third save") { backup(dir, "alpha_code__backup_00001.kt") }

        require(File(dir, "metadata.edn").isFile) { "no metadata.edn after three saves" }
        require(File(dir, "document.md").isFile) { "no document.md after three saves" }
        require(File(dir, "alpha_code_.kt").readText() == "println(\"three\")\n") {
            "the sidecar does not hold the last saved code"
        }

        // A sidecar-backed property is stored twice: as a loose file, and as an inline fallback
        // inside metadata.edn. Diverge them, and read back which one a load would use.
        File(dir, "alpha_code_.kt").writeText("println(\"edited on disk\")\n")
        val inline = saving.readInlineFileSnapshots(dir.path)
            .single { it.boxId == "alpha" && it.property == "code" }
        require(inline.inline == "println(\"three\")\n") {
            "expected metadata.edn to still hold the third save inline, got ${inline.inline}"
        }
        val stored = saving.readEdnTransfers(dir.path).single { it.id == "alpha" }.properties["code"]
        val live = saving.resolveStoredValue(stored, dir.path)
        val historical = saving.resolveStoredValue(stored, dir.path, preferInlineFiles = true)
        require(live == "println(\"edited on disk\")\n") { "a live load should read the loose file, got $live" }
        require(historical == "println(\"three\")\n") { "a history load should read the inline copy, got $historical" }

        val listingBlock = listing(dir)
        println(listingBlock)
        println()
        println("resolved code for box alpha, loose file and inline copy diverged:")
        println("  live load      -> ${live.toString().trim()}")
        println("  history load   -> ${historical.toString().trim()}")

        // Ten more saves. Three is enough to show which files exist; only a run past ten separates
        // the files whose backups are pruned from the one whose backups are not.
        for (i in 4..13) {
            alpha.code = "println(\"$i\")\n"
            saveAndWait(saving, dir, boxes, "save $i") {
                backup(dir, "alpha_code__backup_%05d.kt".format(i - 2))
            }
        }
        require(File(dir, "alpha_code_.kt").readText() == "println(\"13\")\n") {
            "the sidecar does not hold the thirteenth save"
        }

        val censusBlock = census(dir)
        println()
        println(censusBlock)

        guardPage(listingBlock, censusBlock)

        println()
        println("DocumentOnDiskSmokeTest ok")
    }

    /**
     * The page is the figure's only consumer, so this is where the figure is guarded.
     *
     * Both blocks are asserted verbatim against `public/docs/document_on_disk.md`, which makes the
     * retention argument on that page unable to go stale silently: change any of the three
     * `maxBackups` arguments in the save path and the census stops matching. Skipped rather than
     * failed when the page is not reachable, so the smoke still works from a checkout without it.
     */
    private fun guardPage(listing: String, census: String) {
        val page = File("public/docs/document_on_disk.md")
        if (!page.isFile) {
            println("(page not reachable from ${File(".").absolutePath}; figure not guarded this run)")
            return
        }
        val text = page.readText()
        listOf("listing" to listing, "census" to census).forEach { (what, block) ->
            require(block in text) {
                "public/docs/document_on_disk.md no longer contains the $what figure verbatim.\n" +
                    "Replace the stale block on that page with:\n\n$block\n"
            }
        }
        println("(figure verified against public/docs/document_on_disk.md)")
    }

    /**
     * The second half of the figure: how many backups each artifact kept after thirteen saves.
     *
     * `maxBackups` is per call site, so this table is the observable consequence of three different
     * arguments to the same function, and it is the reason a long-lived document's `backups/` is
     * mostly one filename.
     */
    fun census(dir: File): String {
        val out = StringBuilder()
        out.append("after 13 saves, backups/ by stem:\n")
        val backups = (File(dir, "backups").listFiles() ?: emptyArray()).map { it.name }
        val byStem = backups.mapNotNull { name ->
            val at = name.indexOf("_backup_")
            if (at < 0) null else name.substring(0, at)
        }.groupingBy { it }.eachCount()
        byStem.toSortedMap().forEach { (stem, n) -> out.append("  %-18s %2d\n".format(stem, n)) }
        val documentBackups = backups.count { it.startsWith("document_backup_") }
        out.append("  %-18s %2d\n".format("document", documentBackups))
        return out.toString().trimEnd()
    }

    /**
     * The figure. Names and backup indices only — no sizes and no timestamps, so the block is
     * reproducible and can be pinned verbatim by `DocumentOnDiskPageDriftGuard`.
     */
    fun listing(dir: File): String {
        val out = StringBuilder()
        out.append("after three saves of a three-box document:\n")
        (dir.listFiles() ?: emptyArray())
            .filter { it.isFile }
            .map { it.name }
            .sorted()
            .forEach { out.append("  ").append(it).append('\n') }
        out.append("  backups/\n")
        val backups = File(dir, "backups").listFiles() ?: emptyArray()
        backups.map { it.name }.sorted().forEach { out.append("    ").append(it).append('\n') }
        return out.toString().trimEnd()
    }

    private fun backup(dir: File, name: String) = File(File(dir, "backups"), name).isFile

    private fun saveAndWait(
        saving: SavingContext,
        dir: File,
        boxes: Set<Box>,
        message: String,
        done: () -> Boolean
    ) {
        saving.save(dir.path, boxes, message)
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (done()) return
            Thread.sleep(50)
        }
        error("save '$message' did not reach disk within ${WAIT_MS}ms; tree was:\n${listing(dir)}")
    }
}
