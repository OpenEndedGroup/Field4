package afield.runtime.kotlin

import afield.app.commands.Action
import afield.app.commands.actions
import afield.app.diagnostics.logging.lprintln
import afield.app.server.SessionInformation
import afield.app.settings.needsRerun
import afield.document.graph.Box
import afield.document.graph.isTrue
import afield.runtime.lifecycle.RunsAt
import afield.runtime.lifecycle.newRoot
import afield.runtime.lifecycle.inMain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files

/** A generation of a document's declarations. Never installed on the process-wide loader. */
class DocumentClassLoader internal constructor(dir: File, parent: ClassLoader, val generation: Int) :
    URLClassLoader("field-document-types-$generation", arrayOf(dir.toURI().toURL()), parent)

/** Kotlin source boxes are ordinary persisted boxes, distinguished by their existing box_kind. */
const val SHARED_KOTLIN_BOX = "shared-kotlin"

/** The declaration sources and class identity of one open document. */
class DocumentTypes(private val session: SessionInformation) {
    data class Result(val ok: Boolean, val message: String, val changed: Boolean = false)

    private val lock = Mutex()
    @Volatile var loader: DocumentClassLoader? = null
        private set
    private var sources: Map<String, String> = emptyMap()
    private var generation = 0
    @Volatile private var parentEpoch = 0

    /** Compile all declaration boxes as one Kotlin module. A failed build leaves the old one intact. */
    suspend fun publish(overrideBox: Box? = null, overrideSource: String? = null, accepted: () -> Boolean = { true }): Result = lock.withLock {
        val next = session.boxes.values.asSequence()
            .filter { !it.properties["_tombstone"].isTrue() && it.getPropertyRaw("box_kind") == SHARED_KOTLIN_BOX }
            .associate { it.id to (if (it === overrideBox && overrideSource != null) overrideSource else (it.getPropertyRaw("code") as? String).orEmpty()) }
            .toSortedMap()
        if (next == sources && (loader != null || next.isEmpty())) return Result(true, "Shared Kotlin declarations unchanged")

        val epoch = parentEpoch
        val parent = KotlinInterface2.sharedBaseLoader(Thread.currentThread().contextClassLoader ?: DocumentTypes::class.java.classLoader)
        MavenClasspathManager.attachResolvedTo(parent)
        val dir = Files.createTempDirectory("field-document-types-").toFile()
        val compile = withContext(Dispatchers.IO) { compileDeclarations(next, dir, parent) }
        if (!compile.ok) {
            dir.deleteRecursively()
            return compile
        }
        if (!accepted()) {
            dir.deleteRecursively()
            return Result(false, "Shared declarations run was cancelled")
        }
        if (parentEpoch != epoch || KotlinInterface2.sharedClassLoader !== parent) {
            dir.deleteRecursively()
            return Result(false, "The Kotlin classpath changed while compiling; run the declarations box again")
        }
        // A parent-first JVM loader cannot replace an existing parent class. Refuse that
        // collision before committing rather than compiling against one class and loading another.
        val conflicts = dir.walkTopDown().filter { it.isFile && it.extension == "class" && !it.path.contains("${File.separator}classes${File.separator}") }
            .map { it.relativeTo(dir).path.removeSuffix(".class").replace(File.separatorChar, '.') }
            .filter { name -> runCatching { Class.forName(name, false, parent) }.isSuccess }.toList()
        if (conflicts.isNotEmpty()) {
            dir.deleteRecursively()
            return Result(false, "Shared declarations conflict with existing classes: ${conflicts.joinToString()}")
        }
        // Retired loaders can still be used by live callbacks. Remove their files when the JVM exits,
        // not when a newer generation is installed.
        dir.walkTopDown().forEach { it.deleteOnExit() }
        val newLoader = if (next.isEmpty()) null else DocumentClassLoader(dir, parent, generation + 1)
        // Do not close the old loader: live callbacks may still hold it. Its generation remains
        // readable until those references disappear, just like the retired personal layer.
        loader = newLoader
        generation++
        sources = next
        var reset = 0
        for (box in (session.boxes.values + session.root).distinct()) {
            if (box.properties["_tombstone"].isTrue()) continue
            // Discard even when liveKi is null: an initializer may be in flight and must not
            // publish an engine that captured the previous loader after this swap.
            val hadEngine = box.liveKi != null
            box.disposeKi()
            if (hadEngine && box !== overrideBox) {
                box.needsRerun = "Shared Kotlin declarations changed — re-run this box"
                reset++
            }
        }
        Result(true, "Shared Kotlin declarations updated; $reset box(es) need re-running", changed = true)
    }

    /** Personal reload changes our parent loader too, so this document needs a new generation. */
    fun rebaseAfterPersonalReload(): List<Pair<Box, String>> {
        parentEpoch++
        val old = loader ?: return emptyList()
        val parent = KotlinInterface2.sharedBaseLoader(Thread.currentThread().contextClassLoader ?: DocumentTypes::class.java.classLoader)
        loader = DocumentClassLoader(File(old.urLs.single().toURI()), parent, ++generation)
        val reset = mutableListOf<Pair<Box, String>>()
        (session.boxes.values + session.root).distinct().forEach { box ->
            val hadEngine = box.liveKi != null
            box.disposeKi()
            if (hadEngine) {
                val reason = "shared Kotlin declarations used the previous personal layer"
                box.needsRerun = "$reason — re-run this box"
                reset += box to reason
            }
        }
        return reset
    }

    /** Reject an old-generation object before the JVM reports an opaque same-name cast error. */
    fun requireCurrent(value: Any?, consumer: Box) {
        if (loader == null && generation == 0) return
        val retired = findForeignLoader(value) ?: return
        val reason = "This value belongs to an earlier shared Kotlin generation (${retired.generation}); re-run the box that produced it"
        consumer.needsRerun = reason
        throw IllegalStateException(reason)
    }

    private fun findForeignLoader(value: Any?): DocumentClassLoader? {
        if (value == null) return null
        val own = documentLoaderOf(value.javaClass)
        if (own != null && own !== loader) return own
        // Peeling only JDK/Kotlin containers avoids calling arbitrary user collection code.
        val type = value.javaClass
        if (type.classLoader != null && !type.name.startsWith("java.") && !type.name.startsWith("kotlin.") && value !is Array<*>) return null
        val elements: Sequence<Any?> = when (value) {
            is Array<*> -> value.asSequence()
            is Collection<*> -> value.asSequence()
            is Map<*, *> -> value.entries.asSequence().flatMap { sequenceOf(it.key, it.value) }
            is Pair<*, *> -> sequenceOf(value.first, value.second)
            is Triple<*, *, *> -> sequenceOf(value.first, value.second, value.third)
            else -> emptySequence()
        }
        return elements.take(64).mapNotNull { it?.javaClass?.let(::documentLoaderOf) }.firstOrNull { it !== loader }
    }

    private fun documentLoaderOf(type: Class<*>): DocumentClassLoader? =
        generateSequence(type.classLoader) { it.parent }.filterIsInstance<DocumentClassLoader>().firstOrNull()
}

private fun compileDeclarations(sources: Map<String, String>, dir: File, parent: ClassLoader): DocumentTypes.Result {
    if (sources.isEmpty()) return DocumentTypes.Result(true, "No shared declarations")
    val src = File(dir, "source").apply { mkdirs() }
    val output = File(dir, "classes").apply { mkdirs() }
    val files = sources.entries.mapIndexed { i, (_, code) -> File(src, "Declarations$i.kt").apply { writeText(code) } }
    val roots = linkedSetOf<String>()
    System.getProperty("java.class.path").split(File.pathSeparator).filterTo(roots) { it.isNotBlank() }
    var current: ClassLoader? = parent
    while (current != null) {
        if (current is URLClassLoader) current.urLs.filter { it.protocol == "file" }.mapTo(roots) { File(it.toURI()).absolutePath }
        current = current.parent
    }
    val classpath = roots.joinToString(File.pathSeparator)
    val command = listOf(File(System.getProperty("java.home"), "bin/java").absolutePath, "-cp", System.getProperty("java.class.path"),
        "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-classpath", classpath,
        "-jvm-target", "24", "-language-version", fieldKotlinLanguageVersion, "-module-name", "field_document_types",
        "-d", output.absolutePath) + files.map { it.absolutePath }
    return try {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        var text = process.inputStream.bufferedReader().readText()
        files.forEachIndexed { i, file ->
            val name = sources.keys.elementAt(i)
            text = text.replace(file.absolutePath, "box $name").replace(file.name, "box $name")
        }
        val code = process.waitFor()
        if (code == 0) {
            // The loader root is dir itself; put the compiled package tree there.
            output.listFiles()?.forEach { it.copyRecursively(File(dir, it.name), overwrite = true) }
            DocumentTypes.Result(true, text)
        } else DocumentTypes.Result(false, text.ifBlank { "Shared Kotlin compiler exited with code $code" })
    } catch (t: Exception) {
        DocumentTypes.Result(false, "Shared Kotlin compiler failed: ${t.message}")
    }
}

/** Make selected boxes declaration boxes; their existing code remains editable and persisted. */
@RunsAt(newRoot)
fun installSharedKotlinBoxAction(root: Box) {
    root.actions["sharedKotlinBox"] = Action("Make shared Kotlin declarations box", "Compile this box's Kotlin declarations for every box in this document") { boxes ->
        boxes.filter { it.id != "root" && (it.getPropertyRaw("box_kind") == null || it.getPropertyRaw("box_kind") == "" || it.getPropertyRaw("box_kind") == SHARED_KOTLIN_BOX) }
            .forEach { it.setPropertyRaw("box_kind", SHARED_KOTLIN_BOX, doResync = true) }
    }
    root.actions["ordinaryKotlinBox"] = Action("Make ordinary Kotlin box", "Stop sharing declarations from this box") { boxes ->
        val sessions = boxes.filter { it.isInsideInitialized() && it.getPropertyRaw("box_kind") == SHARED_KOTLIN_BOX }
            .map { it.inside }.distinct()
        boxes.filter { it.getPropertyRaw("box_kind") == SHARED_KOTLIN_BOX }.forEach { it.removePropertyRaw("box_kind") }
        inMain { sessions.forEach { session ->
            val result = session.documentTypes.publish()
            if (!result.ok) lprintln("error,ki", result.message)
        } }
    }
}
