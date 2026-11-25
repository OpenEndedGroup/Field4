package afield.runtime.kotlin

import afield.app.commands.Action
import afield.app.commands.actions
import afield.app.diagnostics.logging.lprintln
import afield.app.server.SessionInformation
import afield.app.settings.needsRerun
import afield.document.graph.Box
import afield.document.graph.isTrue
import afield.runtime.execution.begin
import afield.runtime.lifecycle.Runloop
import afield.runtime.lifecycle.RunsAt
import afield.runtime.lifecycle.inMain
import afield.runtime.lifecycle.newRoot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.script.experimental.api.CompiledScript
import kotlin.script.experimental.jvm.impl.KJvmCompiledModuleInMemory
import kotlin.script.experimental.jvm.impl.KJvmCompiledScript

/**
 * Shared Kotlin boxes: a box whose classes every Kotlin box in its document can name.
 *
 * A shared box is an ordinary Kotlin box. It compiles in its own K2 REPL engine, with the same
 * imports, preamble, macros and error reporting as any other box, so code moved into it from a
 * normal box compiles as it did there. What differs is where its classes live. Every class its
 * engine compiles is handed to the document's [DocumentClassLoader] before it is evaluated, and
 * because class loading asks the parent first, that loader defines it. So the class a shared box
 * evaluates and the class every other box sees are the same `Class`, and a value passes between
 * boxes with its type intact.
 *
 * Other boxes name those classes through compilation, not loading. A class declared in a snippet
 * is a static nested class of that snippet's class (`<prefix>__code__3$Flower`, with ordinary
 * Kotlin metadata), so after a successful run the shared box's class files are written to a fresh
 * folder, and every later compile in the document is given that folder and an import for each class
 * (`import <prefix>__code__3.Flower`) by [DocumentTypes.compileView], through the engine's
 * `beforeCompiling` refinement. Two things were measured before this was built (2026-11-25, scratch
 * backdoor sessions): an engine that has already compiled picks up a classpath root added at
 * refinement time, and keeps it for its later snippets; and an `import` in one snippet does *not*
 * reach the next, so the imports go into every compile instead. No engine is rebuilt when a shared
 * box runs.
 *
 * Each run makes a new snippet class with a new name, so a changed class never has to replace a
 * loaded one: old values keep their old class and [DocumentTypes.requireCurrent] says which box to
 * re-run, and boxes whose code names a changed class are marked to re-run.
 *
 * Only classes, interfaces, objects and enums are shared. A top-level `fun` or `val` in a shared
 * box is a member of its snippet instance, which no other box holds; the run says so. Bytecode
 * instrumentation applies to a shared class as it was on that class's first evaluation.
 */
const val SHARED_KOTLIN_BOX = "shared-kotlin"

/**
 * The classes a document's shared Kotlin boxes have compiled, defined once for the whole document.
 *
 * It sits beneath every box engine in the document, in front of the process-wide loader, and holds
 * class files rather than a classpath, so nothing is ever added to a URL loader and a Maven jar
 * still has one identity across documents. It never forgets a class: retired callbacks may still
 * hold one.
 */
class DocumentClassLoader internal constructor(parent: ClassLoader, internal val types: DocumentTypes, val generation: Int) :
    ClassLoader("field-document-types-$generation", parent) {
    private val entries = ConcurrentHashMap<String, ByteArray>()

    internal fun register(files: Map<String, ByteArray>) {
        for ((path, bytes) in files) if (path.endsWith(".class")) entries.putIfAbsent(path, bytes)
    }

    override fun findClass(name: String): Class<*> {
        val bytes = entries[name.replace('.', '/') + ".class"] ?: throw ClassNotFoundException(name)
        return defineClass(name, bytes, 0, bytes.size)
    }

    override fun getResourceAsStream(name: String): InputStream? =
        entries[name]?.let(::ByteArrayInputStream) ?: super.getResourceAsStream(name)
}

/** The shared Kotlin boxes of one open document: what they publish, and the loader that holds it. */
class DocumentTypes(private val session: SessionInformation) {
    data class Result(val ok: Boolean, val message: String, val changed: Boolean = false)

    /** What one shared box offers: the classes it declared, their qualified names, and where their class files are. */
    data class Publication(val boxId: String, val names: List<String>, val imports: List<String>, val root: File)

    /** What a compile in this document is given: the folders to read and the names to import. */
    data class CompileView(val roots: List<File>, val imports: List<String>)

    private val generations = AtomicInteger()

    @Volatile private var current: DocumentClassLoader? = null
    private val retiredLoaders = java.util.Collections.newSetFromMap(java.util.WeakHashMap<DocumentClassLoader, Boolean>())

    /** The loader beneath every box engine in this document. Stable until a personal reload replaces its parent. */
    val loader: DocumentClassLoader
        get() = current ?: synchronized(this) {
            current ?: DocumentClassLoader(baseLoader(), this, generations.incrementAndGet()).also { current = it }
        }

    // Class files by engine prefix, everything that engine has registered: a shared class can refer
    // to the engine's earlier snippets, so a consumer's compile needs them too.
    private val engineFiles = ConcurrentHashMap<String, MutableMap<String, ByteArray>>()

    // The `code` snippets each shared box's engine evaluated in the current run, as (engine, class name).
    private val runSnippets = ConcurrentHashMap<String, MutableList<Pair<String, String>>>()

    // Snippet class -> the shared box that compiled it.
    private val snippetBox = ConcurrentHashMap<String, String>()

    // Snippet classes that are no longer any box's publication.
    private val retiredSnippets = ConcurrentHashMap.newKeySet<String>()

    @Volatile private var publications: Map<String, Publication> = emptyMap()

    // The class folder of every run ever published in this document, retired ones included. An
    // earlier run's class can still be named after its box re-runs: `@rootExported` writes
    // `val Box._x by InheritanceFromContext<…__code__1.Banana>()` into the root preamble every box
    // inherits, the shared box's own included, and that line has to keep compiling until the box
    // that exported it runs again. The classes are still in [loader]; [requireCurrent] then says
    // which box to re-run.
    private val everyRoot = java.util.concurrent.CopyOnWriteArrayList<File>()

    /** Changes whenever what a compile in this document sees changes; part of every rerun-cache key. */
    @Volatile var stamp = 0
        private set

    private val waiters = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    // Completed unless the shared boxes are being restored after the document opened.
    @Volatile private var initial = CompletableDeferred(Unit)

    private val prefixes = AtomicInteger()

    /** A new engine prefix for [box]: every engine of a shared box gets its own, so their snippet classes never share a name. */
    fun publisherPrefix(box: Box): String = "${box.id}_shared${prefixes.incrementAndGet()}"

    fun isShared(box: Box): Boolean = box.getPropertyRaw("box_kind") == SHARED_KOTLIN_BOX

    /** The names each shared box currently shares, by box id; for tests and the backdoor. */
    fun published(): Map<String, List<String>> = publications.mapValues { it.value.names }

    private fun sharedBoxes(): List<Box> = session.boxes.values
        .filter { !it.properties["_tombstone"].isTrue() && isShared(it) }
        .sortedWith(compareBy({ runCatching { it.frame.y }.getOrDefault(0.0) }, { runCatching { it.frame.x }.getOrDefault(0.0) }, { it.id }))

    /**
     * Called by a shared box's engine for each snippet, after instrumentation and just before it is
     * evaluated, so that this document's loader, not the engine's, defines its classes.
     */
    internal fun registerSnippet(engineKey: String, boxId: String?, sourceProperty: String, script: CompiledScript) {
        val compiled = script as? KJvmCompiledScript ?: return
        val module = compiled.getCompiledModule() as? KJvmCompiledModuleInMemory ?: return
        val files = module.compilerOutputFiles.filterKeys { it.endsWith(".class") }
        loader.register(files)
        engineFiles.getOrPut(engineKey) { ConcurrentHashMap() }.putAll(files)
        if (boxId == null) return
        snippetBox[compiled.scriptClassFQName] = boxId
        if (sourceProperty == "code") {
            runSnippets.getOrPut(boxId) { java.util.Collections.synchronizedList(mutableListOf()) } += engineKey to compiled.scriptClassFQName
        }
    }

    /**
     * Before a shared box runs: forget the snippets of its last run, and drop an engine that was not
     * built to share (the box was made shared after it last ran), so the run builds one that is.
     */
    fun beforeRun(box: Box) {
        if (!isShared(box)) return
        runSnippets.remove(box.id)
        val engine = box.liveKi
        if (engine != null && (engine as? KotlinInterface2)?.publishesToDocument != true) box.disposeKi()
    }

    /**
     * After a shared box's code ran without error: share its classes. Returns what to tell the
     * person, or null when [box] is not shared. A refused publication leaves the previous one in force.
     */
    fun afterSuccessfulRun(box: Box): Result? {
        if (!isShared(box)) return null
        val snippets = runSnippets.remove(box.id).orEmpty().toList()
        if (snippets.isEmpty()) return Result(false, "This shared box compiled no code to share; run the whole box")
        val declared = linkedMapOf<String, String>()
        val notShared = linkedSetOf<String>()
        for ((_, snippet) in snippets) {
            val type = runCatching { Class.forName(snippet, false, loader) }.getOrElse {
                return Result(false, "Shared Kotlin: the compiled snippet $snippet could not be loaded: ${it.message}")
            }
            type.declaredClasses
                .filter { !it.isSynthetic && !it.isAnonymousClass && Modifier.isStatic(it.modifiers) && it.isAnnotationPresent(Metadata::class.java) }
                .forEach { declared[it.simpleName] = "${type.name}.${it.simpleName}" }
            notShared += topLevelMembers(type)
        }
        val others = publications.filterKeys { it != box.id }
        for (name in declared.keys) {
            val owner = others.values.firstOrNull { name in it.names } ?: continue
            val ownerName = session.boxes[owner.boxId]?.name?.ifBlank { null } ?: owner.boxId
            return Result(false, "`$name` is already shared by the box '$ownerName'. Rename one of them, or keep both in one shared box.")
        }
        val root = Files.createTempDirectory("field-shared-kotlin-").toFile()
        for (engine in snippets.map { it.first }.distinct()) for ((path, bytes) in engineFiles[engine].orEmpty()) {
            File(root, path).apply { parentFile.mkdirs(); writeBytes(bytes) }
        }
        root.walkTopDown().forEach { it.deleteOnExit() }
        val previous = publications[box.id]
        val publication = Publication(box.id, declared.keys.toList(), declared.values.toList(), root)
        synchronized(this) {
            previous?.imports?.forEach { retiredSnippets += it.substringBeforeLast('.') }
            retiredSnippets -= publication.imports.map { it.substringBeforeLast('.') }.toSet()
            publications = publications + (box.id to publication)
            everyRoot.addIfAbsent(root)
            stamp++
        }
        val marked = if (previous != null) markUsers(box, previous.names + publication.names) else 0
        val message = buildString {
            append(if (declared.isEmpty()) "This shared box declared no classes to share"
                else "Shared with every box in this document: ${declared.keys.joinToString(", ")}")
            if (marked > 0) append(". $marked box(es) that use them need re-running")
            if (notShared.isNotEmpty()) append(".\nNot shared (only classes, interfaces and objects reach other boxes): ${notShared.joinToString(", ")}")
        }
        return Result(true, message, changed = true)
    }

    /** A shared box's run has ended, however it ended; wakes [restoreAfterLoad]. */
    fun runFinished(box: Box, succeeded: Boolean) {
        waiters.remove(box.id)?.complete(succeeded)
    }

    /** [box] is no longer shared, or is gone: withdraw what it published. */
    fun withdraw(box: Box) {
        val previous = synchronized(this) {
            val p = publications[box.id] ?: return
            p.imports.forEach { retiredSnippets += it.substringBeforeLast('.') }
            publications = publications - box.id
            stamp++
            p
        }
        markUsers(box, previous.names)
    }

    // Boxes whose code names one of [names]: what they compiled or hold is from an earlier run.
    private fun markUsers(publisher: Box, names: List<String>): Int {
        if (names.isEmpty()) return 0
        val pattern = Regex("\\b(" + names.distinct().joinToString("|") { Regex.escape(it) } + ")\\b")
        var marked = 0
        for (box in session.boxes.values) {
            if (box === publisher || box.properties["_tombstone"].isTrue() || box.liveKi == null) continue
            val code = box.getPropertyRaw("code") as? String ?: continue
            if (!pattern.containsMatchIn(code)) continue
            val who = publisher.name.ifBlank { publisher.id }
            box.needsRerun = "The shared Kotlin box '$who' changed — re-run this box"
            marked++
        }
        return marked
    }

    // A snippet's own top-level functions and properties, which stay with its instance.
    private fun topLevelMembers(type: Class<*>): List<String> = runCatching {
        type.declaredMethods.asSequence()
            .filter { !it.isSynthetic && !it.isBridge && !Modifier.isStatic(it.modifiers) && '$' !in it.name }
            .map { m ->
                val n = m.name
                if (n.length > 3 && (n.startsWith("get") || n.startsWith("set")) && n[3].isUpperCase()) n[3].lowercase() + n.substring(4) else n
            }
            .filter { !Regex("res\\d+").matches(it) }
            .distinct().toList()
    }.getOrDefault(emptyList())

    /**
     * What a compile for [forBox] is given: the imports of every other shared box, and the class
     * folder of every run published here, earlier ones and its own included (see [everyRoot]). Waits,
     * off the runloop, for the shared boxes this document is restoring after it opened; a shared
     * box's own engine never waits, since the restore is waiting for it.
     */
    fun compileView(forBox: Box?, publishing: Boolean): CompileView {
        if (!publishing && !initial.isCompleted && !Runloop.isMainThread) {
            runBlocking { withTimeoutOrNull(120_000) { initial.await() } }
        }
        val visible = publications.values.filter { it.boxId != forBox?.id }
        return CompileView(everyRoot.toList(), visible.flatMap { it.imports })
    }

    /**
     * Run every shared box once, in sheet order (top to bottom), when the document opens or after a
     * personal reload: their classes exist only once they have run. Compiles elsewhere in the
     * document wait for this ([compileView]), and so does autorun ([awaitRestored]).
     */
    @OptIn(DelicateCoroutinesApi::class)
    fun restoreAfterLoad() {
        val shared = sharedBoxes()
        if (shared.isEmpty()) return
        val gate = CompletableDeferred<Unit>()
        initial = gate
        GlobalScope.launch {
            try {
                for (box in shared) {
                    val done = CompletableDeferred<Boolean>()
                    waiters[box.id] = done
                    inMain { box.begin("shared-kotlin") }
                    val ok = withTimeoutOrNull(180_000) { done.await() }
                    if (ok != true) lprintln("warning,ki", "shared Kotlin box '${box.name.ifBlank { box.id }}' did not share its classes when the document opened (${if (ok == null) "timed out" else "it has errors"})")
                }
            } finally {
                gate.complete(Unit)
            }
        }
    }

    /** Wait for [restoreAfterLoad]; autorun calls this before running anything. */
    suspend fun awaitRestored() {
        withTimeoutOrNull(180_000) { initial.await() }
    }

    /**
     * Personal reload replaced our parent loader, so this document needs a new loader, and every
     * engine built on the old one goes. The shared boxes run again by themselves.
     */
    fun rebaseAfterPersonalReload(): List<Pair<Box, String>> {
        val hadShared = synchronized(this) {
            current?.let { retiredLoaders += it }
            current = null
            publications.values.forEach { p -> p.imports.forEach { retiredSnippets += it.substringBeforeLast('.') } }
            val had = publications.isNotEmpty()
            publications = emptyMap()
            stamp++
            had
        }
        engineFiles.clear()
        runSnippets.clear()
        everyRoot.clear()
        val reset = mutableListOf<Pair<Box, String>>()
        (session.boxes.values + session.root).distinct().forEach { box ->
            val hadEngine = box.liveKi != null
            box.disposeKi()
            if (hadEngine) {
                val reason = "the document's Kotlin classes were built on the previous personal layer"
                box.needsRerun = "$reason — re-run this box"
                reset += box to reason
            }
        }
        if (hadShared) restoreAfterLoad()
        return reset
    }

    /** Reject a value from an earlier run of a shared box before the JVM reports an opaque same-name cast error. */
    fun requireCurrent(value: Any?, consumer: Box) {
        if (retiredSnippets.isEmpty() && retiredLoaders.isEmpty()) return
        val stale = findStale(value) ?: return
        val owner = snippetBox[outermost(stale).name]?.let { session.boxes[it] }
        val who = owner?.let { " ('${it.name.ifBlank { it.id }}')" }.orEmpty()
        val reason = "This ${stale.simpleName} comes from an earlier run of its shared Kotlin box$who; re-run the box that made it"
        consumer.needsRerun = reason
        throw IllegalStateException(reason)
    }

    /**
     * Whether [a] and [b] are the same declaration from different runs of one shared box: the
     * classes `fromContext` should treat as one type, so that [requireCurrent] can explain the mismatch.
     */
    fun sameDeclaration(a: Class<*>, b: Class<*>): Boolean {
        if (a === b) return true
        if (a.classLoader !is DocumentClassLoader || b.classLoader !is DocumentClassLoader) return false
        val boxA = snippetBox[outermost(a).name] ?: return false
        return boxA == snippetBox[outermost(b).name] && a.name.substringAfter('$') == b.name.substringAfter('$')
    }

    private fun outermost(type: Class<*>): Class<*> = generateSequence(type) { it.enclosingClass }.last()

    private fun isStale(type: Class<*>): Boolean {
        val owner = type.classLoader as? DocumentClassLoader ?: return false
        return owner !== current || outermost(type).name in retiredSnippets
    }

    private fun findStale(value: Any?): Class<*>? {
        if (value == null) return null
        val type = value.javaClass
        if (isStale(type)) return type
        // Peeling only JDK/Kotlin containers avoids calling arbitrary user collection code.
        if (type.classLoader != null && !type.name.startsWith("java.") && !type.name.startsWith("kotlin.") && value !is Array<*>) return null
        val elements: Sequence<Any?> = when (value) {
            is Array<*> -> value.asSequence()
            is Collection<*> -> value.asSequence()
            is Map<*, *> -> value.entries.asSequence().flatMap { sequenceOf(it.key, it.value) }
            is Pair<*, *> -> sequenceOf(value.first, value.second)
            is Triple<*, *, *> -> sequenceOf(value.first, value.second, value.third)
            else -> emptySequence()
        }
        return elements.take(64).mapNotNull { it?.javaClass }.firstOrNull(::isStale)
    }

    private fun baseLoader(): ClassLoader {
        val parent = KotlinInterface2.sharedBaseLoader(Thread.currentThread().contextClassLoader ?: DocumentTypes::class.java.classLoader)
        MavenClasspathManager.attachResolvedTo(parent)
        return parent
    }
}

/** Make selected boxes shared Kotlin boxes, or ordinary ones again; their code stays as it is. */
@RunsAt(newRoot)
fun installSharedKotlinBoxAction(root: Box) {
    root.actions["sharedKotlinBox"] = Action("Make shared Kotlin declarations box", "Share this box's classes with every box in this document") { boxes ->
        boxes.filter { it.id != "root" && (it.getPropertyRaw("box_kind") == null || it.getPropertyRaw("box_kind") == "" || it.getPropertyRaw("box_kind") == SHARED_KOTLIN_BOX) }
            .forEach {
                it.setPropertyRaw("box_kind", SHARED_KOTLIN_BOX, doResync = true)
                // Its engine was not built to share; the next run builds one that is.
                it.disposeKi()
                it.needsRerun = "Run this box to share its classes"
            }
    }
    root.actions["ordinaryKotlinBox"] = Action("Make ordinary Kotlin box", "Stop sharing this box's classes") { boxes ->
        boxes.filter { it.getPropertyRaw("box_kind") == SHARED_KOTLIN_BOX }.forEach { box ->
            box.removePropertyRaw("box_kind")
            if (box.isInsideInitialized()) box.inside.documentTypes.withdraw(box)
            box.disposeKi()
        }
    }
}
