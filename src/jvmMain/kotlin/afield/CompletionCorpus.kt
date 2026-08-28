package afield

import afield.macros.gatherPreambleUpwards
import afield.stdlib.normalizePreambleText
import java.io.File
import java.security.MessageDigest

/**
 * The completion corpus: real boxes, with the preamble they have AT RUNTIME.
 *
 * Completion resolves a box against its *inherited preamble*, and in Field a large part of that preamble
 * is not authored — it is written by macro expansion. `prop<Int>(sgL)` becomes
 * `var Box.sgL by Inheritance<Int>()`, `moduleHere { }` becomes the `/*supermap*/ class MappedStructure` +
 * `fun Mesh.set(…, builder: MappedStructure.() -> Unit)` pair, `@exported` republishes a box's own values.
 * None of that exists in a document as materialised on disk; all of it exists the moment the box runs.
 *
 * So a corpus read straight off disk measures a context no user ever has. `CompletionLoweringProbe`'s
 * first run said as much: its most-unresolved names were `renderer` (33), `window` (28) and the `sg*`
 * family — every one of them a macro expansion that hadn't happened.
 *
 * Harvesting fixes that without needing to *run* anything: [harvest] expands each box's macros through
 * the engine's own `applyMacros(previewOnly = false)`, which is the same pass a real run makes and which
 * writes the generated segments onto the box, but does not evaluate a line of the box's code. The result
 * is checked in ([writeFixtures]) so the measurement is reproducible, reviewable as a diff, and
 * independent of macro expansion at measure time — and [readFixtures] verifies each entry still belongs
 * to the code it was harvested from, so a corpus edit can't silently invalidate the numbers.
 *
 * What this deliberately does NOT capture: `@exported` republication, which only happens after real
 * evaluation of a parent box. Those names stay unresolved, and the probe's snippet-region error histogram
 * is where that shows up.
 */
object CompletionCorpus {

    /** Where the bundled starting points and templates live, relative to the repo root. */
    val defaultDirs: List<String> = listOf("public/docs/assets/agent_transcripts", "templates")

    /** Checked-in harvest output. */
    const val FIXTURE_DIR = "tools/completion-corpus/boxes"

    /** One box, as stored in a `document.md`: its parents and its named text properties. */
    data class BoxRec(val id: String, val parents: List<String>, val props: MutableMap<String, String> = mutableMapOf())

    data class DocRec(val name: String, val dir: File, val boxes: LinkedHashMap<String, BoxRec>)

    /** One corpus entry: a box's code, and the preamble it has once its macros have expanded. */
    data class Case(
        val doc: String,
        val box: String,
        val code: String,
        val preamble: String,
        val receivers: List<String> = listOf("afield.Box"),
    ) {
        val label: String get() = "$doc/$box"
        /** Stable file-name stem for this box's fixture files. */
        val stem: String get() = "${doc}__${box}".replace(Regex("""[^A-Za-z0-9_.-]"""), "_")
    }

    private val FENCE = Regex("""^```(\w*)\s*\[field-(box|property)\s+([^]]*)]\s*$""")
    private val ATTR = Regex("""(\w+)="([^"]*)"""")
    private val PARENTS = Regex(""""parents"\s*\[([^]]*)]""")

    fun sha256(s: String): String = MessageDigest.getInstance("SHA-256")
        .digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    /**
     * Parse a `document.md` — the machine-readable mirror Field writes beside `metadata.edn`. Only what
     * this corpus needs is read: each box's parent list (for `gatherPreambleUpwards`) and its property
     * texts. Deliberately not an EDN parser; the mirror exists so tools don't need one.
     */
    fun parseDocument(file: File): DocRec? {
        val lines = runCatching { file.readLines() }.getOrNull() ?: return null
        val boxes = LinkedHashMap<String, BoxRec>()
        var i = 0
        while (i < lines.size) {
            val m = FENCE.find(lines[i])
            if (m == null) { i++; continue }
            val kind = m.groupValues[2]
            val attrs = ATTR.findAll(m.groupValues[3]).associate { it.groupValues[1] to it.groupValues[2] }
            val body = StringBuilder()
            i++
            while (i < lines.size && !lines[i].startsWith("```")) { body.append(lines[i]).append('\n'); i++ }
            i++ // closing fence
            when (kind) {
                "box" -> {
                    val id = attrs["id"] ?: continue
                    val parents = PARENTS.find(body)?.groupValues?.get(1).orEmpty()
                        .split('"').map { it.trim() }.filter { it.isNotEmpty() && it != "#afield/box" }
                    boxes[id] = BoxRec(id, parents)
                }
                "property" -> {
                    val id = attrs["box"] ?: continue
                    val name = attrs["name"] ?: continue
                    boxes[id]?.props?.put(name, body.toString().trimEnd('\n'))
                }
            }
        }
        return if (boxes.isEmpty()) null else DocRec(file.parentFile.name, file.parentFile, boxes)
    }

    /** Every document under [dirs] (repo-relative or absolute), sorted, for determinism. */
    fun documents(dirs: List<String> = defaultDirs): List<DocRec> =
        dirs.map(::File).filter { it.isDirectory }
            .flatMap { d -> d.listFiles()?.toList().orEmpty() }
            .filter { it.isDirectory }.sortedBy { it.name }
            .mapNotNull { parseDocument(File(it, "document.md")) }

    /**
     * Reconstruct a document's box graph as detached [Box]es carrying its text properties. Enough for
     * `gatherPreambleUpwards` (which walks `parents` and reads `preamble`) and for macro expansion
     * (which reads `vertex`/`fragment`/… and writes `preamble`). Nothing is registered anywhere, so
     * these boxes are garbage once the caller drops them.
     */
    fun boxGraph(doc: DocRec): Map<String, Box> {
        val boxes = doc.boxes.mapValues { (id, _) -> Box(id) }
        doc.boxes.forEach { (id, rec) ->
            rec.parents.forEach { p -> boxes[p]?.let { boxes[id]!!.parents.add(it) } }
        }
        doc.boxes.forEach { (id, rec) ->
            val box = boxes[id]!!
            // Text properties only — that is all a `document.md` fence carries, and all macro expansion
            // reads. Typed properties (`frame`, `children`) live in the EDN and matter to neither.
            rec.props.forEach { (name, value) -> box.setPropertyRaw(name, value, doResync = false) }
        }
        return boxes
    }

    /** Boxes whose `code` is Kotlin — the only ones this corpus is about. */
    fun kotlinBoxes(doc: DocRec): List<BoxRec> = doc.boxes.values.filter { rec ->
        (rec.props["code_language"] ?: "kotlin") == "kotlin" && !rec.props["code"].isNullOrBlank()
    }

    /**
     * Expand every box's macros and read back its inherited preamble.
     *
     * [applyMacros] with `previewOnly = false` is the engine's own expansion pass: it rewrites the code
     * (return value discarded — the corpus keeps the code the author wrote, which is what an editor holds)
     * and, as a side effect, merges each macro's generated segments into the box's preamble through
     * `applyInsertToPreamble`. Nothing is evaluated, so this needs no window, no GPU and no runloop, and
     * a box that would loop forever at runtime harvests in milliseconds.
     *
     * @param onProblem called with (label, throwable) for a box whose expansion throws; harvesting
     *        continues, and that box keeps whatever preamble it had on disk.
     */
    fun harvest(
        ki: KotlinInterface2,
        dirs: List<String> = defaultDirs,
        onProblem: (String, Throwable) -> Unit = { _, _ -> },
    ): List<Case> {
        val out = mutableListOf<Case>()
        for (doc in documents(dirs)) {
            val boxes = boxGraph(doc)
            for (rec in kotlinBoxes(doc)) {
                val box = boxes[rec.id]!!
                val code = rec.props["code"]!!
                runCatching { ki.applyMacros(box, code, emptySet(), false) }
                    .onFailure { onProblem("${doc.name}/${rec.id}", it) }
                val preamble = runCatching { normalizePreambleText(gatherPreambleUpwards(box)) }.getOrDefault("")
                out += Case(doc.name, rec.id, code, preamble)
            }
        }
        return out
    }

    // ── self-consistency ────────────────────────────────────────────────────────────────────────────

    /** `var Box.window by Inheritance<Window>()` — the declaration a `prop<T>(name)` macro writes. */
    private val PROP_DECL = Regex("""\bby\s+(?:Inheritance|Onceing)<([A-Za-z_][A-Za-z0-9_.]*)""")

    /**
     * The types this preamble *declares a property of* but never brings into scope.
     *
     * Should always be empty, because the macro that writes `by Inheritance<T>()` writes T's import in
     * the same [afield.macros.Insert]. It is checked anyway because the two halves are separable by
     * hand — twice now a fixture has been "fixed" by deleting the import line and leaving the
     * declaration, which produces a preamble in which T does not resolve, and a completion corpus whose
     * whole purpose is measuring what resolves cannot afford to be measuring that.
     *
     * A type counts as in scope if it is fully qualified, default-imported, imported explicitly,
     * declared in the preamble itself (the `/*supermap*/` and `/*graphics-structure-type*/` forms
     * declare classes inline), or lives in a package the preamble star-imports — the last resolved through
     * [ImportResolution]'s class index rather than guessed, so `import afield.four.buffers.*` excuses
     * `FrameBufferGroup` and does not excuse `Window`.
     */
    fun typesDeclaredWithoutAnImport(preamble: String): List<String> {
        val importLines = preamble.lines().map { it.trim() }.filter { it.startsWith("import ") }
            .map { it.removePrefix("import ").trim() }
        val explicit = importLines.filterNot { it.endsWith(".*") }.map { it.substringAfterLast('.') }.toSet()
        val wildcards = importLines.filter { it.endsWith(".*") }.map { it.removeSuffix(".*") }.toSet()
        return PROP_DECL.findAll(preamble).map { it.groupValues[1] }
            .distinct()
            .filterNot { declared ->
                val t = declared.substringAfterLast('.')
                '.' in declared ||
                    isDefaultImportedTypeName(t) ||
                    t in explicit ||
                    Regex("""\bclass\s+$t\b""").containsMatchIn(preamble) ||
                    // Guarded: resolving a star-import means a whole-classpath scan, and a preamble
                    // with no star import needs no scan to be judged.
                    (wildcards.isNotEmpty() &&
                        ImportResolution.index()[t].orEmpty().any { fq -> fq.substringBeforeLast('.') in wildcards })
            }
            .map { it.substringAfterLast('.') }
            .sorted()
            .toList()
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────────────────────

    /**
     * Write [cases] as reviewable fixtures: one manifest line per box, and one `.preamble.kt` file per box
     * that has a preamble. The preamble is the harvested part — the part no static read of the corpus can
     * produce — so it is what gets checked in; the code is left where it already lives and referenced by
     * a content hash, so there is exactly one copy of it in the repo and a corpus edit is detectable.
     */
    fun writeFixtures(cases: List<Case>, dir: File = File(FIXTURE_DIR)): Int {
        dir.mkdirs()
        dir.listFiles()?.filter { it.name.endsWith(".preamble.kt") }?.forEach { it.delete() }
        var written = 0
        val manifest = StringBuilder("[\n")
        cases.sortedBy { it.label }.forEachIndexed { index, c ->
            val preambleFile = if (c.preamble.isBlank()) "" else "${c.stem}.preamble.kt"
            if (preambleFile.isNotEmpty()) {
                File(dir, preambleFile).writeText(c.preamble.trimEnd() + "\n")
                written++
            }
            if (index > 0) manifest.append(",\n")
            manifest.append("""  {"doc": "${c.doc}", "box": "${c.box}", "codeSha256": "${sha256(c.code)}", """)
                .append(""""codeLines": ${c.code.lines().size}, "receivers": ["${c.receivers.joinToString("\", \"")}"], """)
                .append(""""preamble": "$preambleFile"}""")
        }
        manifest.append("\n]\n")
        File(dir, "manifest.json").writeText(manifest.toString())
        return written
    }

    /** A fixture that no longer matches the corpus it was harvested from. */
    data class Drift(val label: String, val what: String)

    /**
     * Read the checked-in fixtures back, joined to the code they were harvested from. The join is
     * verified: a box that has moved, vanished or been edited shows up in [Drift] rather than quietly
     * measuring a preamble that belongs to different code.
     */
    fun readFixtures(dir: File = File(FIXTURE_DIR), dirs: List<String> = defaultDirs): Pair<List<Case>, List<Drift>> {
        val manifest = File(dir, "manifest.json")
        if (!manifest.isFile) return emptyList<Case>() to listOf(Drift(dir.path, "no manifest.json — run the harvest"))
        val entry = Regex(
            """\{"doc": "([^"]*)", "box": "([^"]*)", "codeSha256": "([^"]*)", "codeLines": (\d+), """ +
                """"receivers": \[([^]]*)], "preamble": "([^"]*)"}"""
        )
        val onDisk = documents(dirs).associate { doc ->
            doc.name to kotlinBoxes(doc).associate { it.id to it.props["code"]!! }
        }
        val cases = mutableListOf<Case>()
        val drift = mutableListOf<Drift>()
        val seen = mutableSetOf<String>()
        for (m in entry.findAll(manifest.readText())) {
            val (doc, box, sha, _, recvRaw, preambleFile) = m.destructured
            val label = "$doc/$box"
            seen += label
            val code = onDisk[doc]?.get(box)
            if (code == null) { drift += Drift(label, "in the fixtures but not in the corpus"); continue }
            if (sha256(code) != sha) { drift += Drift(label, "code changed since it was harvested"); continue }
            val preamble = if (preambleFile.isBlank()) "" else
                File(dir, preambleFile).takeIf { it.isFile }?.readText().orEmpty()
                    .ifBlank { drift += Drift(label, "preamble file $preambleFile is missing"); "" }
            cases += Case(doc, box, code, preamble, recvRaw.split(',').map { it.trim().trim('"') }.filter { it.isNotEmpty() })
        }
        onDisk.forEach { (doc, boxes) ->
            boxes.keys.forEach { box ->
                if ("$doc/$box" !in seen) drift += Drift("$doc/$box", "in the corpus but not in the fixtures")
            }
        }
        return cases to drift
    }
}
