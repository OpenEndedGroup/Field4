@file:afield.CompletionCategory("Workspace")

package afield.ai.core

import afield.Box
import afield.ReadOnlyDocuments
import afield.stdlib.BoxStudies
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/** An executable example attached to an ordinary [Box.aiTool] definition. */
class BoxToolTest(val name: String, val arguments: String, internal val verify: (Any) -> Unit) {
    internal var failureContains: String? = null
        private set

    internal constructor(name: String, arguments: String, failureContains: String) : this(name, arguments, {}) {
        this.failureContains = failureContains
    }
}

/** Evaluate the declared outcome without treating cancellation or unexpected errors as a pass. */
internal suspend fun evaluateToolTest(
    example: BoxToolTest,
    call: suspend () -> Any,
    verify: suspend (Any) -> Unit,
): ToolTestOutcome {
    val started = System.nanoTime()
    fun outcome(passed: Boolean, message: String) = ToolTestOutcome(
        example.name, passed, ToolDiagnostics.bounded(message), (System.nanoTime() - started) / 1_000_000,
    )
    val failure = try {
        val result = call()
        if (ToolDiagnostics.succeeded(result)) {
            if (example.failureContains != null) {
                return outcome(false, "Expected a failure containing '${example.failureContains}', but the tool succeeded")
            }
            verify(result)
            return outcome(true, "Passed")
        }
        ToolDiagnostics.summary(result)
    } catch (e: java.util.concurrent.CancellationException) {
        throw e
    } catch (e: Exception) {
        e.message ?: e.javaClass.simpleName
    }
    val expected = example.failureContains
    return when {
        expected == null -> outcome(false, failure)
        failure.contains(expected) -> outcome(true, "Rejected as expected: $failure")
        else -> outcome(false, "Expected a failure containing '$expected'; received: $failure")
    }
}

/** A test verdict for one exact registered source revision. */
data class ToolTestOutcome(val name: String, val passed: Boolean, val message: String, val elapsedMs: Long)

/** Bounded, local diagnostics for a call. Images stay in the existing image/transcript path. */
data class ToolCallEvidence(val id: String = UUID.randomUUID().toString(), val at: String = Instant.now().toString(),
    val tool: String, val sourceHash: String, val caller: String, val arguments: String,
    val success: Boolean, val result: String, val elapsedMs: Long)

/** A saved suite result; source identity prevents an edited tool inheriting an old green result. */
data class ToolTestEvidence(val sourceHash: String, val at: String = Instant.now().toString(), val tests: List<ToolTestOutcome>)

/**
 * Diagnostics attached to a registered tool, rather than a second execution engine.
 * Every entry point uses the same recorder. Records live with the document and survive reopening.
 * Capture is bounded and best effort: a full disk must not turn a successful edit into a reported failure.
 */
class ToolDiagnostics internal constructor(private val tool: BoxTool) {
    private val recent = ArrayDeque<ToolCallEvidence>()
    var storageError: String? = null
        private set
    private fun directory(): File? = BoxStudies.documentOf(tool.box)?.let {
        File(File(it.filename, ".tool-evidence"), ToolPool.hash(tool.name) + "/" + tool.sourceHash)
    }

    internal suspend fun record(arguments: String, caller: String, operation: suspend () -> Any): Any {
        val started = System.nanoTime()
        try {
            val result = operation()
            retain(ToolCallEvidence(tool=tool.name, sourceHash=tool.sourceHash, caller=caller,
                arguments=bounded(arguments), success=succeeded(result), result=summary(result), elapsedMs=(System.nanoTime()-started)/1_000_000))
            return result
        } catch (e: Exception) {
            retain(ToolCallEvidence(tool=tool.name, sourceHash=tool.sourceHash, caller=caller,
                arguments=bounded(arguments), success=false, result=bounded(e.message ?: e.javaClass.simpleName), elapsedMs=(System.nanoTime()-started)/1_000_000))
            throw e
        }
    }

    @Synchronized private fun retain(evidence: ToolCallEvidence) {
        recent.addLast(evidence)
        while(recent.size > 40) recent.removeFirst()
        runCatching {
            directory()?.let { dir ->
                writeAtomic(File(dir, "call-${evidence.at.replace(':','-')}-${evidence.id}.json"), evidence)
                dir.listFiles { f -> f.name.startsWith("call-") }?.sortedByDescending { it.name }?.drop(40)?.forEach { it.delete() }
            }
        }.onFailure { storageError=it.message ?: it.javaClass.simpleName }
    }

    /** Recent calls for this tool source, including diagnostics retained before a restart. */
    @Synchronized
    fun calls(): List<ToolCallEvidence> {
        val disk = directory()?.listFiles { f -> f.name.startsWith("call-") }?.mapNotNull {
            runCatching { aiToolMapper.readValue<ToolCallEvidence>(it) }.getOrNull()
        }.orEmpty()
        return (disk+recent).distinctBy { it.id }.sortedByDescending { it.at }.take(40)
    }

    internal fun recordTests(outcomes: List<ToolTestOutcome>) {
        lastTests = ToolTestEvidence(tool.sourceHash, tests=outcomes)
        runCatching { directory()?.let { writeAtomic(File(it,"tests.json"), lastTests!!) } }
            .onFailure { storageError=it.message ?: it.javaClass.simpleName }
    }
    private var lastTests: ToolTestEvidence? = null
    /** Null means untested, never a pass. An empty suite is also untested. */
    fun tests(): ToolTestEvidence? = lastTests ?: directory()?.let {
        runCatching { aiToolMapper.readValue<ToolTestEvidence>(File(it,"tests.json")) }.getOrNull()
    }

    companion object {
        internal fun bounded(text: String) = if(text.length<=8000) text else text.take(8000)+"\n[truncated]"
        internal fun summary(value: Any): String = bounded(when(value) {
            is SummarisesForToolLog -> value.toolLogSummary()
            is CarriesToolImages -> "${value.javaClass.simpleName}: ${value.toolImages.size} attached image(s)"
            else -> runCatching { aiToolMapper.writeValueAsString(value) }.getOrElse { value.toString() }
        })
        internal fun succeeded(value: Any): Boolean {
            if(value is BoxToolError) return false
            val node = runCatching { aiToolMapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(value) }.getOrNull() ?: return true
            fun populated(n: com.fasterxml.jackson.databind.JsonNode?): Boolean = when {
                n == null || n.isNull -> false
                n.isBoolean -> n.asBoolean()
                n.isTextual -> n.asText().isNotBlank()
                n.isContainerNode -> n.size() > 0
                else -> false
            }
            // Inspect only the declared outcome. A successful history/query may contain older
            // failures in its payload; those must not turn the query itself into a failure.
            return node.get("success")?.asBoolean(true)!=false && node.get("ok")?.asBoolean(true)!=false &&
                !populated(node.get("error")) && !populated(node.get("errors")) &&
                node.get("status")?.asText()?.lowercase() !in setOf("error", "failed", "held") &&
                !(node.get("tested")?.asBoolean() == true && node.get("passed")?.asBoolean() == false)
        }
    }
}

/** Description of one tool supplied by a pool entry. The source remains ordinary box Kotlin. */
data class PooledTool(val name: String, val description: String, val schema: String, val tests: List<String>)

/** An immutable, explicit snapshot of a tool-provider box, its attached checks and provenance. */
data class ToolPoolEntry(val key: String, val revision: String, val description: String, val sourceHash: String,
    val source: String, val tools: List<PooledTool>, val checks: Map<String, ToolTestEvidence>,
    val originDocument: String, val originBox: String, val created: String = Instant.now().toString())

/**
 * A local, cross-project library of box-authored tools. This stores source; it is not a tool registry.
 * Curating is explicit. Loading an entry never runs it or imports the originating project's state.
 * Run the returned source in a normal provider box to register its tools, then test in that project.
 * Keep project-specific dependencies explicit in the provider source and entry description.
 */
class ToolPool(val directory: File = File(System.getProperty("user.home"), ".afield/tool-pool")) {
    companion object {
        fun hash(source: String): String = MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        private fun key(key: String) { require(key.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,99}"))) { "Pool key must be a simple name, not a path" } }
    }

    /** Discover immutable versions without reading executable source into the caller's context. */
    fun list(query: String = ""): List<Map<String, Any>> = directory.listFiles()?.filter { it.isDirectory }?.flatMap { dir ->
        dir.listFiles { f -> f.extension=="json" }?.mapNotNull { file ->
            runCatching { read(dir.name,file.nameWithoutExtension) }.getOrNull()
        }.orEmpty()
    }.orEmpty().filter { (it.key+" "+it.description+" "+it.tools.joinToString { t->t.name }).contains(query,true) }
        .sortedByDescending { it.created }.map { mapOf("key" to it.key,"revision" to it.revision,"description" to it.description,
            "tools" to it.tools,"checks" to it.checks,"created" to it.created) }

    /** Read an exact curated version; modified source is refused. */
    fun read(key: String, revision: String): ToolPoolEntry {
        key(key); require(revision.matches(Regex("[0-9a-f]{64}"))) { "Use an exact pool revision" }
        val entry=aiToolMapper.readValue<ToolPoolEntry>(File(directory,"$key/$revision.json"))
        require(entry.key==key && entry.revision==revision && hash(entry.source)==entry.sourceHash && identity(entry)==revision) { "Pool entry changed on disk" }
        return entry
    }

    /** Explicitly curate this provider's registered source, retaining its exact source-specific checks. */
    fun publish(box: Box, key: String, description: String): ToolPoolEntry {
        check(!ReadOnlyDocuments.blocksWritesFrom(box)) { "This document is read-only" }
        val document=checkNotNull(BoxStudies.documentOf(box)) { "Tool provider must belong to a document" }
        val tools=DocumentToolRegistry.toolsFor(document.filename).filter { it.boxId==box.id }
        require(tools.isNotEmpty()) { "Run a box with aiTool definitions before curating it" }
        val source=box.getPropertyRaw("code") as? String ?: ""
        require(source.isNotBlank() && tools.all { it.source==source }) { "Provider source changed since registration; run and test it again" }
        return store(key,description,source,tools.map { PooledTool(it.name,it.description,it.parametersSchema.toString(),it.tests.map { t->t.name }) },
            tools.mapNotNull { t->t.diagnostics.tests()?.let { t.name to it } }.toMap(),document.filename,box.id)
    }

    internal fun store(key: String, description: String, source: String, tools: List<PooledTool>, checks: Map<String,ToolTestEvidence>, origin: String, box: String): ToolPoolEntry {
        key(key); require(description.isNotBlank()) { "Describe the tool's purpose and dependencies" }
        val candidate=ToolPoolEntry(key,"",description,hash(source),source,tools,checks,origin,box)
        val entry=candidate.copy(revision=identity(candidate))
        val file=File(directory,"$key/${entry.revision}.json")
        if(file.exists()) return read(key,entry.revision)
        writeAtomic(file,entry)
        return entry
    }
    private fun identity(entry: ToolPoolEntry) = hash(aiToolMapper.writeValueAsString(entry.copy(revision="",created="")))
}

private fun writeAtomic(file: File, value: Any) {
    file.parentFile.mkdirs()
    val tmp=Files.createTempFile(file.parentFile.toPath(),".tool-",".tmp")
    try {
        aiToolMapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(),value)
        Files.move(tmp,file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
    } finally { Files.deleteIfExists(tmp) }
}
