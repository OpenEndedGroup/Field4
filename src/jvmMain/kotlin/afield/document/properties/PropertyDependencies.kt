package afield.document.properties

/**
 * Records which boxes read values held by other boxes, per document, and saves that record as
 * `dependencies.edn` beside `metadata.edn`.
 *
 * [PropertyDependencies] below describes what is recorded and when it is written; the tests are
 * `PropertyDependenciesTest`.
 */

import afield.app.diagnostics.named
import afield.app.server.documents
import afield.data.table.div
import afield.data.table.minus
import afield.data.table.plus
import afield.data.table.rem
import afield.data.table.times
import afield.document.workspace.metadata
import afield.runtime.execution.plus
import afield.runtime.kotlin.exported
import afield.runtime.lifecycle.ReactiveRuns
import afield.runtime.lifecycle.loop
import afield.runtime.lifecycle.plus
import afield.runtime.lifecycle.rem
import afield.ui.notation.with
import afield.ui.output.history
import afield.ui.output.md

import afield.app.protocol.DependencyFlow
import afield.app.protocol.DependencyGraph
import afield.app.protocol.DependencyMiss
import afield.app.protocol.computeIfAbsent
import afield.app.protocol.payload
import afield.app.server.ReadOnlyDocuments
import afield.app.server.SessionInformation
import afield.audio.synth.*
import afield.data.table.get
import afield.document.context.ContextResolution
import afield.document.graph.Box
import afield.document.graph.HasHierarchy
import afield.document.graph.METAROOT_ID
import afield.document.graph.box
import afield.document.persistence.Edn
import afield.graphics.vulkan.*
import afield.media.stream.*
import afield.media.video.*
import afield.runtime.lifecycle.Runloop
import afield.runtime.lifecycle.map
import afield.ui.output.templates.filter
import afield.app.diagnostics.logging.lprintln
import afield.data.collections.isTrue
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Which boxes read values that other boxes hold — recorded as box code runs, kept per document, and
 * saved beside `metadata.edn` as `dependencies.edn`.
 *
 * Every inherited read goes through [ContextResolution.search] (`prop` / `rootProp`'s `Inheritance`,
 * `@exported`'s `InheritanceFromContext`, `fromContext` and `fromContextWithNameOrNull`, FieldML's
 * `prop`), so that walk is the one place a read is recorded ([recordResolution]). The inherited
 * delegates' setters record the other half, a box writing a value into a *different* box
 * ([recordWrite]) — that is how `rootProp` works: `root.speed = 3.0` in one box, `root.speed` read in
 * another, and the producer is the writer, not the root that holds it.
 *
 * ## What an entry is
 *
 * One [Edge] per (reader, supplier, name, mechanism, access), with a count and first/last-seen
 * times, rather than a log of every read: a box drawing every frame reads `renderer` sixty times a
 * second, and the question the record answers is "who depends on whom", not "how often".
 *
 * - **reader** — the box whose code made the access: `Box.context` (the executing box) when there
 *   is one, else the box the walk started from. They differ for `named("other").speed`.
 * - **supplier** — for a read, the box that held the value; for a write, the box written into; for a
 *   miss, empty.
 * - A read of a box's own value is not a dependency and is not recorded, nor is a write into one's
 *   own box.
 *
 * A **miss** is a non-explain walk that found nothing — `MissingInheritedPropertyException`, or a
 * nullable `prop` that came back null. `fromContextWithNameOrNull` misses are not recorded: that
 * call is an optional probe, and library code uses it as one. Misses are what upstream auto-execution needs: on startup a
 * box misses `speed`, and the remembered edges say which box supplied `speed` to it last time
 * ([flows], [DependencyMiss.rememberedSuppliers]).
 *
 * ## Persistence
 *
 * In memory per document path ([SessionInformation.filename]); loaded from `dependencies.edn` on
 * first use, written by the autosave loop ([flush]) and on shutdown. A new edge is written at the
 * next autosave tick; counts and times that only moved are written at most every
 * [COUNT_FLUSH_INTERVAL_MS]. Read-only and scratch documents record in memory and never write.
 *
 * Off with `-Dafield.dependencyTelemetry=false` or `AFIELD_DEPENDENCY_TELEMETRY=off`.
 * `dev_docs/PROPERTY_DEPENDENCIES.md` has the design and the road to Marimo-style re-execution.
 */
object PropertyDependencies {

    const val FILE_NAME = "dependencies.edn"
    const val COUNT_FLUSH_INTERVAL_MS = 60_000L

    /**
     * Orders accesses against each other and against `propDown` changes (`ReactiveRuns`): every
     * recorded access and every change takes the next value. Milliseconds are too coarse for that.
     */
    val clock = AtomicLong()

    /** When this process started recording: an edge last seen before this was remembered from disk. */
    val sessionStartedAt: Long = System.currentTimeMillis()

    @Volatile
    var enabled: Boolean = System.getProperty("afield.dependencyTelemetry")?.lowercase() !in setOf("false", "off", "0") &&
        System.getenv("AFIELD_DEPENDENCY_TELEMETRY")?.lowercase() !in setOf("false", "off", "0")

    enum class Access { READ, MISS, WRITE }

    data class Key(
        val reader: String,
        val supplier: String,
        val name: String,
        val mechanism: String,
        val access: Access,
    )

    class Edge(
        val key: Key,
        @Volatile var firstSeen: Long,
        @Volatile var lastSeen: Long,
        initialCount: Long,
        /** `PROPERTY` / `VARIABLE` / `SELF` for a read; null otherwise. */
        @Volatile
        var matchKind: String?,
        /** The property key or variable name that matched — differs from [Key.name] for a by-type query. */
        @Volatile
        var matchedName: String?,
    ) {
        val count = AtomicLong(initialCount)
        /** Accesses since this process started. */
        val sessionCount = AtomicLong(0)
        /** [clock] at the last access; 0 for an edge only remembered from disk. Not persisted. */
        @Volatile
        var lastSeq = 0L

        /**
         * The reader has finished a full run since this access without repeating it ([retireReadsBefore]):
         * kept to explain what happened, but it no longer reruns the reader. The next access revives it.
         */
        @Volatile
        var historical = false
    }

    class Store(val documentPath: String) {
        val edges = ConcurrentHashMap<Key, Edge>()

        /** A new edge since the last write: write at the next autosave tick. */
        @Volatile
        var structuralChange = false

        /** Counts or times moved since the last write. */
        @Volatile
        var countChange = false

        @Volatile
        var lastWrite = 0L

        fun clear() {
            edges.clear()
            structuralChange = true
        }
    }

    private val stores = ConcurrentHashMap<String, Store>()

    /** The record for the document at [documentPath], loading `dependencies.edn` on first use. */
    fun storeFor(documentPath: String): Store = stores.computeIfAbsent(documentPath) { path ->
        Store(path).also { load(it) }
    }

    /** The record for [box]'s document, or null for a box that is in no document. */
    fun storeFor(box: Box): Store? {
        if (!box.isInsideInitialized()) return null
        return storeFor(box.inside.filename)
    }

    /**
     * Record the outcome of one non-explain walk. Called by [ContextResolution.search]; cheap on
     * the hit path (a thread-local read, one map lookup, one increment).
     */
    internal fun recordResolution(
        asking: Box,
        resolution: ContextResolution.Resolution?,
        query: ContextResolution.Query,
        mechanism: ContextResolution.Mechanism,
    ) {
        if (!enabled) return
        val reader = Box.context.get() ?: asking
        if (resolution == null) {
            // `fromContextWithNameOrNull` is an optional probe by design, and library code uses it
            // that way (buffer and acceleration-structure lookups): its misses are not holes.
            if (mechanism != ContextResolution.Mechanism.FROM_CONTEXT_NAMED) {
                record(reader, "", query.description, mechanism.name, Access.MISS, null, null)
            }
            return
        }
        val supplier = resolution.supplier
        if (supplier === reader) return
        record(reader, supplier.id, query.description, mechanism.name, Access.READ, resolution.matchKind.name, resolution.matchedName)
    }

    /**
     * Record that [reader] found [name] in [supplier]'s properties by name, as a shader's sampler
     * binding does (`Box.findWithNameOrNull`). A `propDown` write to it then reruns [reader].
     */
    fun recordNamedRead(reader: Box, supplier: Box, name: String) {
        if (!enabled || supplier === reader) return
        record(reader, supplier.id, name, ContextResolution.Mechanism.FROM_CONTEXT_NAMED.name, Access.READ,
            ContextResolution.MatchKind.PROPERTY.name, name)
    }

    /**
     * Record that the executing box wrote [name] into [holder] through an inherited delegate. A
     * write into the executing box itself (local shadowing) is not a dependency and is skipped.
     */
    fun recordWrite(holder: HasHierarchy, name: String, mechanism: ContextResolution.Mechanism) {
        if (!enabled) return
        if (holder !is Box) return
        val writer = Box.context.get() ?: return
        if (writer === holder) return
        record(writer, holder.id, name, mechanism.name, Access.WRITE, null, null)
    }

    private fun record(
        reader: Box,
        supplier: String,
        name: String,
        mechanism: String,
        access: Access,
        matchKind: String?,
        matchedName: String?,
    ) {
        val store = storeFor(reader) ?: return
        val key = Key(reader.id, supplier, name, mechanism, access)
        val now = System.currentTimeMillis()
        val existing = store.edges[key]
        if (existing != null) {
            existing.count.incrementAndGet()
            existing.sessionCount.incrementAndGet()
            existing.lastSeen = now
            existing.lastSeq = clock.incrementAndGet()
            if (matchKind != null) existing.matchKind = matchKind
            if (matchedName != null) existing.matchedName = matchedName
            if (existing.historical) {
                existing.historical = false
                store.structuralChange = true
            }
            store.countChange = true
            return
        }
        val edge = store.edges.computeIfAbsent(key) { Edge(it, now, now, 0, matchKind, matchedName) }
        edge.count.incrementAndGet()
        edge.sessionCount.incrementAndGet()
        edge.lastSeen = now
        edge.lastSeq = clock.incrementAndGet()
        store.structuralChange = true
    }

    /**
     * [box] has just finished a full run (not a fragment) that began at [runStartSeq]: every access it
     * made before then and did not repeat during the run is now historical. A completed run replaces
     * the box's live dependencies; reads its `frames {}` loops and callbacks make later revive theirs.
     */
    fun retireReadsBefore(box: Box, runStartSeq: Long) {
        val store = storeFor(box) ?: return
        var changed = false
        for (e in store.edges.values) {
            if (e.key.reader != box.id || e.historical || e.lastSeq >= runStartSeq) continue
            e.historical = true
            changed = true
        }
        if (changed) store.structuralChange = true
    }

    // ---------------------------------------------------------------------------------------------
    // Derived views

    /**
     * One producer → consumer arrow: [from]'s value reached [to]'s code, under any of [names].
     *
     * Derived from the edges: a read from a box is a flow from it; a read from a box that some
     * *other* box wrote into (the `rootProp` pattern) is a flow from the writer. The holder still
     * counts when it is an ordinary box; the document root is never a producer.
     */
    data class Flow(
        val from: String,
        val to: String,
        val names: List<String>,
        val count: Long,
        val sessionCount: Long,
        val lastSeen: Long,
        /** Every edge behind this arrow is historical. */
        val historical: Boolean = false,
    )

    /**
     * Every producer → consumer flow in [store], merged per pair. Historical edges are left out
     * unless [includeHistorical]; then an arrow made only of them is marked [Flow.historical].
     */
    fun flows(store: Store, rootId: String? = null, includeHistorical: Boolean = false): List<Flow> {
        val edges = store.edges.values.filter { includeHistorical || !it.historical }
        // (holder, name) -> writers
        val writers = HashMap<Pair<String, String>, MutableList<Edge>>()
        for (e in edges) if (e.key.access == Access.WRITE) {
            writers.getOrPut(e.key.supplier to e.key.name) { ArrayList(1) } += e
        }

        class Acc(var count: Long = 0, var session: Long = 0, var lastSeen: Long = 0, val names: LinkedHashSet<String> = LinkedHashSet(), var live: Boolean = false)

        val merged = LinkedHashMap<Pair<String, String>, Acc>()
        fun add(from: String, to: String, name: String, e: Edge) {
            if (from == to || from.isEmpty()) return
            val acc = merged.getOrPut(from to to) { Acc() }
            acc.count += e.count.get()
            acc.session += e.sessionCount.get()
            acc.lastSeen = maxOf(acc.lastSeen, e.lastSeen)
            acc.names += name
            if (!e.historical) acc.live = true
        }
        for (e in edges) {
            if (e.key.access != Access.READ) continue
            val holder = e.key.supplier
            val name = displayName(e.key.name)
            if (holder != rootId && holder != METAROOT_ID) add(holder, e.key.reader, name, e)
            writers[holder to e.key.name]?.forEach { w -> add(w.key.reader, e.key.reader, name, e) }
        }
        return merged.map { (k, a) -> Flow(k.first, k.second, a.names.toList(), a.count, a.session, a.lastSeen, historical = !a.live) }
    }

    /** The boxes that supplied [box]'s inherited values, as far as this document has seen. */
    fun upstreamOf(box: Box): List<String> {
        val store = storeFor(box) ?: return emptyList()
        val rootId = box.inside.root.id
        return flows(store, rootId).filter { it.to == box.id }.map { it.from }.distinct()
    }

    /**
     * The boxes that supplied [name] to [reader] on any earlier read — what an upstream auto-run would
     * execute when [reader] misses [name]. Suppliers of live reads come first, historical ones last.
     */
    fun rememberedSuppliers(store: Store, reader: String, name: String, rootId: String?): List<String> {
        val out = LinkedHashSet<String>()
        for (e in store.edges.values.sortedBy { it.historical }) {
            if (e.key.access != Access.READ || e.key.reader != reader || e.key.name != name) continue
            val holder = e.key.supplier
            if (holder != rootId && holder != METAROOT_ID) out += holder
            for (w in store.edges.values) {
                if (w.key.access == Access.WRITE && w.key.supplier == holder && w.key.name == name && w.key.reader != reader) out += w.key.reader
            }
        }
        return out.toList()
    }

    /** `afield.graphics.vulkan.Renderer` → `Renderer`; a property name stays as it is. */
    fun displayName(query: String): String =
        if (query.contains('.') && query.none { it.isWhitespace() }) query.substringAfterLast('.').substringAfterLast('$') else query

    /** The wire form the dependency layer draws. */
    fun graph(info: SessionInformation): DependencyGraph {
        val store = storeFor(info.filename)
        val rootId = info.root.id
        val flows = flows(store, rootId, includeHistorical = true).map {
            DependencyFlow(it.from, it.to, it.names, it.count, it.sessionCount, it.lastSeen, historical = it.historical)
        }
        // A miss that a later read of the same name answered is history, not a hole.
        val lastRead = HashMap<Pair<String, String>, Long>()
        for (e in store.edges.values) if (e.key.access == Access.READ) {
            lastRead.merge(e.key.reader to e.key.name, e.lastSeen, ::maxOf)
        }
        val misses = store.edges.values.filter {
            it.key.access == Access.MISS && !it.historical && (lastRead[it.key.reader to it.key.name] ?: -1L) < it.lastSeen
        }.map {
            DependencyMiss(
                box = it.key.reader,
                name = displayName(it.key.name),
                count = it.count.get(),
                sessionCount = it.sessionCount.get(),
                lastSeen = it.lastSeen,
                rememberedSuppliers = rememberedSuppliers(store, it.key.reader, it.key.name, rootId),
            )
        }
        return DependencyGraph(
            now = System.currentTimeMillis(),
            sessionStartedAt = sessionStartedAt,
            enabled = enabled,
            edgeCount = store.edges.size,
            flows = flows,
            misses = misses,
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Persistence

    /**
     * Write [info]'s record if it changed: at once for a new edge, otherwise at most every
     * [COUNT_FLUSH_INTERVAL_MS]. Called from the autosave loop; [force] (shutdown) writes any change,
     * on the calling thread.
     */
    fun flush(info: SessionInformation, force: Boolean = false) {
        if (!info.writesToDisk || info.loading) return
        val store = stores[info.filename] ?: return
        val now = System.currentTimeMillis()
        val due = store.structuralChange ||
            (store.countChange && (force || now - store.lastWrite >= COUNT_FLUSH_INTERVAL_MS))
        if (!due) return
        if (ReadOnlyDocuments.blocksWritesTo(info.filename)) return
        prune(store, info)
        store.structuralChange = false
        store.countChange = false
        store.lastWrite = now
        val work = Runnable {
            try {
                synchronized(store) { write(store, File(info.filename)) }
            } catch (t: Throwable) {
                store.structuralChange = true
                lprintln("warning,context", "could not write ${FILE_NAME} for ${info.filename}: ${t.message}")
            }
        }
        // Off the main runloop, which is where the autosave loop calls from; inline on shutdown,
        // when the pool is being shut down by its own hook.
        if (force) work.run() else Runloop.miscPool.execute(work)
    }

    /** Forget edges whose reader has left the document, or whose supplier was deleted from it. */
    private fun prune(store: Store, info: SessionInformation) {
        fun gone(id: String): Boolean {
            if (id.isEmpty() || id == info.root.id) return false
            val box = info.boxes[id] ?: return true
            return box.getPropertyRaw("_tombstone").isTrue()
        }
        store.edges.keys.removeIf { k ->
            gone(k.reader) || (info.boxes[k.supplier]?.getPropertyRaw("_tombstone").isTrue())
        }
    }

    internal fun write(store: Store, documentDir: File) {
        if (!documentDir.isDirectory) return
        val edges = store.edges.values.sortedWith(compareBy({ it.key.reader }, { it.key.supplier }, { it.key.name }, { it.key.access }))
        val payload = linkedMapOf<String, Any?>(
            "version" to 1,
            "edges" to edges.map { e ->
                linkedMapOf<String, Any?>(
                    "reader" to e.key.reader,
                    "supplier" to e.key.supplier,
                    "name" to e.key.name,
                    "mechanism" to e.key.mechanism,
                    "access" to e.key.access.name,
                    "count" to e.count.get(),
                    "firstSeen" to e.firstSeen,
                    "lastSeen" to e.lastSeen,
                    "matchKind" to e.matchKind,
                    "matchedName" to e.matchedName,
                    "historical" to (if (e.historical) true else null),
                ).filterValues { it != null }
            },
        )
        val target = File(documentDir, FILE_NAME)
        val tmp = File(documentDir, "$FILE_NAME.tmp")
        tmp.writeText(Edn.write(payload))
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    private fun load(store: Store) {
        val file = File(store.documentPath, FILE_NAME)
        if (!file.isFile) return
        try {
            val root = Edn.read(file.readText()) as? Map<*, *> ?: return
            val edges = root["edges"] as? List<*> ?: return
            for (raw in edges) {
                val m = raw as? Map<*, *> ?: continue
                val access = runCatching { Access.valueOf(m["access"] as String) }.getOrNull() ?: continue
                val key = Key(
                    reader = m["reader"] as? String ?: continue,
                    supplier = m["supplier"] as? String ?: "",
                    name = m["name"] as? String ?: continue,
                    mechanism = m["mechanism"] as? String ?: "",
                    access = access,
                )
                store.edges[key] = Edge(
                    key,
                    firstSeen = (m["firstSeen"] as? Number)?.toLong() ?: 0L,
                    lastSeen = (m["lastSeen"] as? Number)?.toLong() ?: 0L,
                    initialCount = (m["count"] as? Number)?.toLong() ?: 0L,
                    matchKind = m["matchKind"] as? String,
                    matchedName = m["matchedName"] as? String,
                ).also { it.historical = m["historical"] == true }
            }
        } catch (t: Throwable) {
            lprintln("warning,context", "could not read ${file.path}: ${t.message}")
        }
    }

    /** Drop the in-memory record for [documentPath] (tests, and a document closing). */
    fun forget(documentPath: String) {
        stores.remove(documentPath)
    }

    /** Whether an in-memory record for [documentPath] is held (tests of document close). */
    internal fun holds(documentPath: String): Boolean = stores.containsKey(documentPath)
}
