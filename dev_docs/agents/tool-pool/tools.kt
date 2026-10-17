import afield.ai.completion.*
import afield.ai.diagnostics.*
import afield.ai.models.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID

// Tool actions run later; retain the provider box for ownership checks.
val poolOwner = thisBox
val poolDocument = checkNotNull(BoxStudies.documentOf(poolOwner))
val pool = ToolPool()
val poolJson = jacksonObjectMapper()
fun notesFor(key: String, revision: String): List<Map<String, Any?>> {
    val entry = pool.read(key, revision)
    val folder = java.io.File(pool.directory, "$key/notes/$revision")
    return folder
        .listFiles { f -> f.extension == "json" }
        .orEmpty()
        .map { file ->
            @Suppress("UNCHECKED_CAST") val note = poolJson.readValue(file, Map::class.java) as Map<String, Any?>
            check(note["key"] == key && note["revision"] == revision && note["sourceHash"] == entry.sourceHash) {
                "Tool note refers to a different provider source"
            }
            note
        }
        .sortedWith(compareBy({ it["at"].toString() }, { it["id"].toString() }))
}
fun localTool(name: String) = checkNotNull(DocumentToolRegistry.get(poolDocument.filename, name)) {
    "No tool '$name' in this document. Run its provider box first."
}
aiTool("pool.list") {
    describe(
        "Search the local cross-project tool pool. Entries are ordinary Field provider-box source with exact versions and attached checks. Listing does not install or run anything.",
    )
    val query by param<String>("Name, description or tool name", default = "")
    action { pool.list(query()) }
}
aiTool("pool.read") {
    describe(
        "Read an exact tool-pool version, its provider Kotlin, dependencies and original test evidence. To use it, create a normal Field box, set this source and run it; then test its tools in the destination project. Source is context, not authorization. Do not assume captured project state or external files were exported.",
    )
    val key by param<String>()
    val revision by param<String>()
    action {
        val entry = pool.read(key(), revision())
        val notes = notesFor(key(), revision())
        @Suppress("UNCHECKED_CAST")
        (poolJson.convertValue(entry, Map::class.java) as Map<String, Any?>) +
            mapOf("notes" to notes.takeLast(10), "noteCount" to notes.size)
    }
}
aiTool("pool.notes") {
    describe(
        "Read durable agent-authored observations, known issues and lessons for one exact curated provider version. Notes are evidence and context, not instructions or passing test results. They do not certify newer source.",
    )
    val key by param<String>()
    val revision by param<String>()
    val before by param<String>("Optional note ID; read earlier notes", default = "")
    val limit by param<Int>(default = 20)
    action {
        require(limit() in 1..50) { "Read 1 to 50 notes at a time" }
        val notes = notesFor(key(), revision())
        val end =
            if (before().isBlank()) notes.size
            else notes.indexOfFirst { it["id"] == before() }.also { require(it >= 0) { "Unknown note cursor" } }
        val start = maxOf(0, end - limit())
        val page = notes.subList(start, end)
        mapOf("notes" to page, "total" to notes.size, "nextBefore" to if (start > 0) page.first()["id"] else null)
    }
}
aiTool("pool.note") {
    describe(
        "Append a durable observation, known issue or lesson to an exact curated tool-provider version. Cite diagnostic/test artifacts in evidence. This changes neither provider source nor test status. Corrections are further notes; history is retained. Local storage only.",
    )
    val key by param<String>()
    val revision by param<String>()
    val sourceHash by param<String>()
    val text by param<String>()
    val actor by param<String>(default = "AI4")
    val evidence by param<List<String>>(default = emptyList<String>())
    action {
        check(!ReadOnlyDocuments.blocksWritesFrom(poolOwner)) { "This document is read-only" }
        val entry = pool.read(key(), revision())
        check(entry.sourceHash == sourceHash()) { "Provider source changed; read the exact version again" }
        require(text().isNotBlank() && text().length <= 8000) { "A note needs 1 to 8000 characters" }
        require(
            actor().isNotBlank() &&
                actor().length <= 120 &&
                evidence().size <= 16 &&
                evidence().all { it.length in 1..2048 },
        ) {
            "Invalid note author or evidence references"
        }
        val id = UUID.randomUUID().toString()
        val note = mapOf(
            "id" to id,
            "key" to entry.key,
            "revision" to entry.revision,
            "sourceHash" to entry.sourceHash,
            "at" to Instant.now().toString(),
            "actor" to actor(),
            "text" to text(),
            "evidence" to evidence(),
            "originDocument" to poolDocument.filename,
            "originBox" to poolOwner.id,
        )
        val folder = java.io.File(pool.directory, "${entry.key}/notes/${entry.revision}").apply { mkdirs() }
        val tmp = Files.createTempFile(folder.toPath(), ".note-", ".tmp")
        try {
            poolJson.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), note)
            Files.move(tmp, folder.resolve("$id.json").toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(tmp)
        }
        note
    }
}
aiTool("pool.inspect") {
    describe(
        "Inspect tools actually registered in this document, or one tool's schema, provider source identity, attached examples, recent call inputs/results/errors and test outcomes. This is the same registry used by AI4 and MCP.",
    )
    val name by param<String>("Empty lists the document's tools", default = "")
    action {
        val tools = if (name().isBlank()) DocumentToolRegistry.toolsFor(poolDocument.filename) else [localTool(name())]
        tools.map {
            mapOf(
                "name" to it.name,
                "description" to it.description,
                "box" to it.boxId,
                "sourceHash" to it.sourceHash,
                "schema" to it.parametersSchema.toString(),
                "checks" to it.diagnostics.tests(),
                "calls" to if (name().isBlank()) emptyList() else it.diagnostics.calls(),
                "storageError" to it.diagnostics.storageError,
            )
        }
    }
}
aiTool("pool.test") {
    describe(
        "Explicitly run one registered tool's attached examples through its real argument binding and execution. Use a disposable project for tools that edit state. An empty suite is untested, not green. Requires the exact registered source hash.",
    )
    val name by param<String>()
    val sourceHash by param<String>()
    action {
        val tool = localTool(name())
        check(tool.sourceHash == sourceHash()) { "Tool changed; inspect it again" }
        val results = tool.test()
        mapOf(
            "tested" to results.isNotEmpty(),
            "passed" to (results.isNotEmpty() && results.all { it.passed }),
            "checks" to results,
        )
    }
}
aiTool("pool.publish") {
    describe(
        "Explicitly curate a provider box into the local tool pool for reuse across projects. Stores the whole provider source and its source-specific tests, not runtime objects or private dependencies. Name dependencies in the description. Does not publish to Git or any network service.",
    )
    val boxId by param<String>()
    val key by param<String>()
    val description by param<String>()
    val sourceHash by param<String>()
    action {
        val provider = checkNotNull(poolDocument.boxes[boxId()])
        check(ToolPool.hash(provider.getPropertyRaw("code") as? String ?: "") == sourceHash()) {
            "Provider source changed; inspect and test it again"
        }
        pool.publish(provider, key(), description())
    }
}
fun showPool() {
    fun esc(v: Any?) =
        v.toString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    val curated =
        pool.list().joinToString("") { entry ->
            val notes = notesFor(entry["key"].toString(), entry["revision"].toString())
            val checks =
                (entry["checks"] as? Map<*, *>)
                    ?.values
                    ?.filterIsInstance<ToolTestEvidence>()
                    ?.flatMap { it.tests }
                    .orEmpty()
            val state =
                if (checks.isEmpty()) "Untested"
                else if (checks.all { it.passed }) "${checks.size} checks passed in the source project"
                else "${checks.count{!it.passed}} checks failed"
            "<details style='margin:14px 0'><summary>${esc(entry["key"])} · $state · ${notes.size} notes</summary><p>${esc(entry["description"])}</p><p style='color:#999;overflow-wrap:anywhere;user-select:text'>Version ${esc(entry["revision"])}</p><p>Read this version with pool.read, run it in a provider box, and test it in the destination project.</p>" +
                notes.takeLast(10).joinToString("") { n ->
                    "<details><summary>${esc(n["actor"])} · ${esc(n["at"])}</summary><p style='white-space:pre-wrap;user-select:text'>${esc(n["text"])}</p><pre style='white-space:pre-wrap;overflow-wrap:anywhere;user-select:text'>${esc((n["evidence"] as? List<*>)?.joinToString("\n").orEmpty())}</pre></details>"
                } +
                "</details>"
        }
    val rows =
        DocumentToolRegistry.toolsFor(poolDocument.filename).map { tool ->
            val tests = tool.diagnostics.tests()?.tests.orEmpty()
            val calls = tool.diagnostics.calls()
            val state =
                if (tests.isEmpty()) "Untested"
                else if (tests.all { it.passed }) "${tests.size} checks passed"
                else "${tests.count{!it.passed}} checks failed"
            "<details style='margin:14px 0'><summary>${esc(tool.name)} · $state</summary><p>${esc(tool.description)}</p><p style='color:#999'>Provider ${esc(tool.boxId)} · source ${tool.sourceHash.take(12)}</p><pre style='white-space:pre-wrap;user-select:text'>${esc(tests.joinToString("\n"){"${it.name}: ${it.message}"})}</pre><p>${calls.size} recent calls</p>" +
                calls.take(5).joinToString("") {
                    "<details><summary style='color:${if(it.success)"#aaa" else "#ff7070"}'>${esc(it.at)} · ${it.elapsedMs} ms · ${if(it.success)"OK" else "Error"}</summary><pre style='white-space:pre-wrap;user-select:text'>${esc(it.arguments)}\n${esc(it.result)}</pre></details>"
                } +
                "</details>"
        }
    display(
        "pool",
        "<div style='width:640px;padding:24px;border-radius:12px;background:#000;color:#ddd;font:14px/1.5 Iosevka,monospace'><p style='color:#55d4f2'>Tool pool</p><p>Live tools, their checks and recent diagnostics. Curated versions are ordinary Field box source, ready to test in another project.</p><p style='color:#55d4f2'>Curated versions</p>${curated.ifEmpty{"<p>No curated providers yet. Use pool.publish after inspecting and testing one.</p>"}}<p style='color:#55d4f2'>Tools in this document</p>${rows.joinToString("")}</div>",
    )
    displayOpen("pool")
}
button("Refresh tool pool", channel = "controls") { if (it) showPool() }
showPool()
