package afield.document.workspace

import afield.app.server.SessionInformation
import afield.app.settings.PersonalLayer
import afield.document.graph.Box
import afield.app.diagnostics.logging.lprintln
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Implement in a kit's Kotlin extension; startup/cleanup belong to the host, activation to a document. */
interface KitLifecycle {
    fun startup(context: KitHostContext) {}
    fun activate(context: KitDocumentContext) {}
    fun deactivate(context: KitDocumentContext) {}
    fun cleanup(context: KitHostContext) {}
}

/** The immutable runtime revision loaded by this process. */
data class KitHostContext(val kit: InstalledKit, val directory: File)

/** The ordinary Field document and its project's shared parent/resources/preferences. */
data class KitDocumentContext(val host: KitHostContext, val document: SessionInformation, val project: WorkspaceProjectScope)

/** Runtime lifecycle for the exact package paths captured by the launcher, independent of pending registry changes. */
object KitRuntime {
    private data class Loaded(val context: KitHostContext, val lifecycle: KitLifecycle)
    private val loaded = linkedMapOf<String, Loaded>()
    private val activeDocuments = ConcurrentHashMap<String, KitDocumentContext>()
    private val problems = ConcurrentHashMap<String, String>()
    private var started = false
    val errors: Map<String, String> get() = problems.toMap()

    @Synchronized
    fun start() {
        if (started) return
        started = true
        problems.clear()
        val directories = System.getProperty("afield.kit.packages").orEmpty().split(File.pathSeparator).filter { it.isNotBlank() }
        directories.forEach { path ->
            var id = path
            runCatching {
                val directory = File(path)
                val manifest = KitPackages.read(directory); id = manifest.id
                if (manifest.host != null) return@runCatching
                val revision = KitPackages.active()[id] ?: error("Runtime profile is missing revision for $id")
                val kit = InstalledKit(id, revision, KitPackages.runtimeRevision(directory), path, path)
                val context = KitHostContext(kit, directory)
                val lifecycle = if (manifest.runtime.lifecycle.isEmpty()) object : KitLifecycle {} else {
                    Class.forName(manifest.runtime.lifecycle, true, PersonalLayer.current).getDeclaredConstructor().newInstance() as KitLifecycle
                }
                try { lifecycle.startup(context) }
                catch (error: Throwable) { runCatching { lifecycle.cleanup(context) }; throw error }
                loaded[id] = Loaded(context, lifecycle)
            }.onFailure { problems[id] = it.message ?: it.javaClass.simpleName; lprintln("error,kits", "$id startup failed: ${problems[id]}") }
        }
        if (directories.isNotEmpty()) Runtime.getRuntime().addShutdownHook(Thread({
            val worker = Thread({ shutdown() }, "field-kit-cleanup-worker").apply { isDaemon = true }
            worker.start(); worker.join(4000)
            if (worker.isAlive) System.err.println("Field kit cleanup exceeded 4 seconds; exiting.")
        }, "field-kit-cleanup"))
    }

    @Synchronized
    fun activate(info: SessionInformation) {
        if (info.ephemeral || activeDocuments.containsKey(info.filename)) return
        val record = KitInstances.record(File(info.filename)) ?: return
        val runtime = loaded[record.kit] ?: error(problems[record.kit] ?: "Restart Field to load kit ${record.kit}")
        require(runtime.context.kit.runtimeRevision == record.runtimeRevision) {
            "Document ${info.documentName} needs kit runtime ${record.runtimeRevision.take(8)}; this host loaded ${runtime.context.kit.runtimeRevision.take(8)}. Inspect its kit update or restore its recorded kit revision."
        }
        val scope = WorkspaceProjectScopes.get(File(info.filename).parentFile, record.project)
        val context = KitDocumentContext(runtime.context, info, scope)
        try {
            Box.withBoxContextNow(info.root) { runtime.lifecycle.activate(context) }
            activeDocuments[info.filename] = context
        } catch (error: Exception) {
            runCatching { runtime.lifecycle.deactivate(context) }
            problems[record.kit] = "${info.documentName}: ${error.message}"
            throw error
        }
    }

    @Synchronized
    fun deactivate(info: SessionInformation) {
        val context = activeDocuments.remove(info.filename) ?: return
        runCatching { loaded[context.host.kit.id]?.lifecycle?.deactivate(context) }
            .onFailure { lprintln("error,kits", "${context.host.kit.id} deactivate failed: ${it.message}") }
    }

    @Synchronized
    fun shutdown() {
        activeDocuments.values.toList().forEach { deactivate(it.document) }
        loaded.values.toList().asReversed().forEach { kit ->
            runCatching { kit.lifecycle.cleanup(kit.context) }.onFailure { lprintln("error,kits", "${kit.context.kit.id} cleanup failed: ${it.message}") }
        }
        loaded.clear()
        started = false
    }

    @Synchronized
    fun launchBoxes(info: SessionInformation): List<Box>? {
        val record = KitInstances.record(File(info.filename)) ?: return null
        val runtime = loaded[record.kit] ?: error("Kit runtime is not active")
        val manifest = KitPackages.read(runtime.context.directory)
        // The seed must come from the instance's revision, even if this host's compatible runtime is newer.
        val seedManifest = KitPackages.read(File(KitPackages.directory, "packages/${record.kit}/${record.revision}"))
        val point = seedManifest.startingPoints[record.startingPoint] ?: error("Missing recorded starting point")
        require(manifest.id == record.kit)
        return point.launch.map { name ->
            info.boxes.values.filter { it.name == name && it.getPropertyRaw("_tombstone") != true }.singleOrNull()
                ?: error("Kit launch needs exactly one box named '$name'")
        }
    }
}
