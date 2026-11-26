package afield.data.graph

import afield.geometry.path.FLine
import afield.math.linalg.Vec3
import java.util.Collections
import java.util.stream.IntStream
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.RestrictsSuspension
import kotlin.coroutines.createCoroutine
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The producer and input identities behind a published fact. */
data class SpatialOrigin(val process: String, val inputs: List<Long>, val condition: String? = null)

data class SpatialInput(val id: Long, val kind: String?, val live: Boolean)

/** A value snapshot suitable for retaining in a drawing's click callback. */
data class SpatialExplanation(
    val id: Long,
    val kind: String,
    val origin: SpatialOrigin,
    val inputs: List<SpatialInput>,
    val ageSeconds: Double,
    val lifetime: SpatialLifetime,
    val matchLive: Boolean?,
)

sealed class SpatialFact protected constructor(
    val id: Long,
    val kind: String,
    val origin: SpatialOrigin,
    data: Map<String, Any>,
) {
    internal var publishedAt = 0.0
    internal var lifetime = SpatialLifetime.PROCESS
    internal var activation: SpatialMatch? = null

    var alive: Boolean = false
        internal set

    var data: Map<String, Any> = Collections.unmodifiableMap(data.toMap())
        internal set

    operator fun get(key: String): Any? = data[key]
}

class SpatialPoint internal constructor(
    id: Long,
    kind: String,
    origin: SpatialOrigin,
    data: Map<String, Any>,
    internal var position: Vec3,
) : SpatialFact(id, kind, origin, data) {
    val pos: Vec3 get() = Vec3(position)
}

/** Each edge has its own identity. Endpoint order is preserved, including for parallel edges. */
class SpatialEdge internal constructor(
    id: Long,
    kind: String,
    origin: SpatialOrigin,
    data: Map<String, Any>,
    val from: SpatialPoint,
    val to: SpatialPoint,
) : SpatialFact(id, kind, origin, data)

internal data class SpatialBinding<T>(val ids: List<Long>, val value: T)

/** A query over the committed facts in one frame. Reuse a pattern to share its evaluation. */
class SpatialPattern<T> internal constructor(
    internal val query: SpatialQueries.() -> List<SpatialBinding<T>>,
) {
    fun where(predicate: (T) -> Boolean): SpatialPattern<T> {
        val upstream = this
        return SpatialPattern { matches(upstream).filter { predicate(it.value) } }
    }
}

class SpatialPoints internal constructor(internal val kind: String) {
    fun each(): SpatialPattern<SpatialPoint> = SpatialPattern {
        points(kind).map { SpatialBinding(listOf(it.id), it) }
    }

    /** Each unordered pair occurs once, with the lower point ID first. */
    fun pairsWithin(enter: Double, leave: Double = enter): SpatialPattern<Pair<SpatialPoint, SpatialPoint>> =
        proximity(this, enter, leave, unordered = true)

    /** Bind the left and right roles in order, excluding self-pairs. */
    fun near(
        other: SpatialPoints,
        enter: Double,
        leave: Double = enter,
    ): SpatialPattern<Pair<SpatialPoint, SpatialPoint>> = proximity(other, enter, leave, unordered = false)

    private fun proximity(
        other: SpatialPoints,
        enter: Double,
        leave: Double,
        unordered: Boolean,
    ): SpatialPattern<Pair<SpatialPoint, SpatialPoint>> {
        require(enter.isFinite() && enter > 0.0 && leave.isFinite() && leave >= enter)
        val spec = SpatialProximity(kind, other.kind, enter, leave, unordered)
        return SpatialPattern { proximity(spec) }
    }
}

/** A read-only index over the positions supplied to [SpatialIndexFactory]. Distances are squared.
 * Implementations used with parallel search must support concurrent reads.
 */
fun interface SpatialNeighborIndex {
    fun neighbors(index: Int, radius: Double): List<Neighbor>
}

/** Build an index for a cloud and its largest query radius. Treat the cloud as read-only. */
fun interface SpatialIndexFactory {
    fun build(positions: List<Vec3>, radius: Double): SpatialNeighborIndex
}

private val gridFactory = SpatialIndexFactory { positions, radius ->
    val grid = Vec3SpatialHash(radius).also { it.rebuild(positions) }
    SpatialNeighborIndex(grid::radiusNeighbors)
}

internal data class SpatialProximity(
    val left: String,
    val right: String,
    val enter: Double,
    val leave: Double,
    val unordered: Boolean,
)

internal class SpatialQueries(
    private val pointsByKind: Map<String, List<SpatialPoint>>,
    private val edgesByKind: Map<String, List<SpatialEdge>>,
    private val previous: Map<SpatialProximity, Set<List<Long>>>,
    private val indexFactory: SpatialIndexFactory,
    private val parallelSearch: Boolean,
) {
    private val results = mutableMapOf<SpatialPattern<*>, List<SpatialBinding<*>>>()
    private val candidates = mutableMapOf<SpatialProximity, List<SpatialBinding<Pair<SpatialPoint, SpatialPoint>>>>()
    private val grids = mutableMapOf<Triple<String, String, Double>, Pair<List<SpatialPoint>, SpatialNeighborIndex>>()

    fun points(kind: String): List<SpatialPoint> = pointsByKind[kind].orEmpty()
    fun edges(kind: String): List<SpatialEdge> = edgesByKind[kind].orEmpty()

    @Suppress("UNCHECKED_CAST")
    fun <T> matches(pattern: SpatialPattern<T>): List<SpatialBinding<T>> =
        results.getOrPut(pattern) { pattern.query(this) } as List<SpatialBinding<T>>

    fun proximity(spec: SpatialProximity): List<SpatialBinding<Pair<SpatialPoint, SpatialPoint>>> =
        candidates.getOrPut(spec) {
            val left = points(spec.left)
            val right = points(spec.right)
            if (left.isEmpty() || right.isEmpty()) return@getOrPut emptyList()

            val (cloud, grid) = grids.getOrPut(Triple(spec.left, spec.right, spec.leave)) {
                val cloud = if (spec.left == spec.right) left else left + right
                cloud to indexFactory.build(cloud.map(SpatialPoint::pos), spec.leave)
            }
            val existing = previous[spec].orEmpty()
            val enterSquared = spec.enter * spec.enter
            fun query(i: Int): List<SpatialBinding<Pair<SpatialPoint, SpatialPoint>>> {
                val a = left[i]
                return buildList {
                    for (neighbor in grid.neighbors(i, spec.leave)) {
                        val b = cloud[neighbor.index]
                        if (a === b || b.kind != spec.right || (spec.unordered && a.id >= b.id)) continue
                        val ids = listOf(a.id, b.id)
                        if (neighbor.distanceSquared <= enterSquared || ids in existing) {
                            add(SpatialBinding(ids, a to b))
                        }
                    }
                }
            }
            if (parallelSearch && left.size >= 256) {
                IntStream.range(0, left.size).parallel().mapToObj(::query).toList().flatten()
            } else {
                left.indices.flatMap(::query)
            }
        }

    fun history(): Map<SpatialProximity, Set<List<Long>>> =
        candidates.mapValues { (_, hits) -> hits.mapTo(HashSet()) { it.ids } }
}

/** Live tracks the rule predicate. The process can keep running after it becomes false. */
class SpatialMatch internal constructor() {
    var live: Boolean = true
        internal set
}

@DslMarker
annotation class SpatialRuleDsl

@SpatialRuleDsl
class SpatialRules internal constructor() {
    internal val rules = linkedMapOf<String, SpatialRule<*>>()

    fun points(kind: String): SpatialPoints = SpatialPoints(kind)

    fun edges(kind: String): SpatialPattern<SpatialEdge> = SpatialPattern {
        edges(kind).map { SpatialBinding(listOf(it.id), it) }
    }

    fun <T> onEnter(
        name: String,
        pattern: SpatialPattern<T>,
        condition: String = name,
        body: suspend SpatialProcess.(T) -> Unit,
    ) {
        require(name !in rules) { "Duplicate spatial rule: $name" }
        rules[name] = SpatialRule(pattern, condition, body)
    }
}

internal class SpatialRule<T>(
    private val pattern: SpatialPattern<T>,
    private val condition: String,
    private val body: suspend SpatialProcess.(T) -> Unit,
) {
    private var live = linkedMapOf<List<Long>, SpatialMatch>()

    fun evaluate(world: SpatialRuleWorld, group: String, name: String, queries: SpatialQueries) {
        val next = linkedMapOf<List<Long>, SpatialMatch>()
        for ((ids, value) in queries.matches(pattern)) {
            next[ids] = live[ids] ?: SpatialMatch().also { match ->
                world.launch(group, SpatialOrigin(name, Collections.unmodifiableList(ids), condition), match) { body(value) }
            }
        }
        for ((ids, match) in live) if (ids !in next) match.live = false
        live = next
    }
}

/** Match publications retract when a predicate breaks. Process publications follow the coroutine. */
enum class SpatialLifetime { MATCH, PROCESS }

@SpatialRuleDsl
@RestrictsSuspension
class SpatialProcess internal constructor(
    private val world: SpatialRuleWorld,
    val origin: SpatialOrigin,
    private val activation: SpatialMatch?,
) {
    private val owned = linkedMapOf<SpatialFact, SpatialLifetime>()
    internal var continuation: Continuation<Unit>? = null
    internal var stopping = false

    val now: Double get() = world.now
    val frame: Long get() = world.frame
    val match: SpatialMatch get() = checkNotNull(activation) { "A source has no match" }

    /** Suspend until the world has committed and matched the next frame. */
    suspend fun nextFrame(): Unit = suspendCoroutineUninterceptedOrReturn {
        checkRunning()
        continuation = it
        COROUTINE_SUSPENDED
    }

    fun point(
        kind: String,
        at: Vec3,
        data: Map<String, Any> = emptyMap(),
        lifetime: SpatialLifetime = SpatialLifetime.PROCESS,
    ): SpatialPoint {
        checkPublishing(lifetime)
        requireFinite(at)
        return SpatialPoint(world.nextId(), kind, origin, data, Vec3(at)).also {
            owned[it] = lifetime
            world.publish(it, lifetime, activation)
        }
    }

    fun edge(
        kind: String,
        from: SpatialPoint,
        to: SpatialPoint,
        data: Map<String, Any> = emptyMap(),
        lifetime: SpatialLifetime = SpatialLifetime.PROCESS,
    ): SpatialEdge {
        checkPublishing(lifetime)
        world.checkEndpoint(from)
        world.checkEndpoint(to)
        return SpatialEdge(world.nextId(), kind, origin, data, from, to).also {
            owned[it] = lifetime
            world.publish(it, lifetime, activation)
        }
    }

    fun move(point: SpatialPoint, to: Vec3) {
        checkOwned(point)
        world.move(point, to)
    }

    /** Pass null to remove the key. Use immutable values to keep frame snapshots consistent. */
    fun set(fact: SpatialFact, key: String, value: Any?) {
        checkOwned(fact)
        world.set(fact, key, value)
    }

    fun retract(fact: SpatialFact) {
        checkOwned(fact)
        owned.remove(fact)
        world.retract(fact)
    }

    /** Submit a line for this frame. Submit it again on any later frame that should draw it. */
    fun draw(line: FLine) {
        checkRunning()
        world.draw(line)
    }

    internal fun releasePublications(all: Boolean) {
        val iterator = owned.iterator()
        while (iterator.hasNext()) {
            val (fact, lifetime) = iterator.next()
            if (all || (lifetime == SpatialLifetime.MATCH && activation?.live == false)) {
                world.retract(fact)
                iterator.remove()
            } else if (!world.contains(fact)) {
                iterator.remove()
            }
        }
    }

    private fun checkPublishing(lifetime: SpatialLifetime) {
        checkRunning()
        if (lifetime == SpatialLifetime.MATCH) check(match.live) { "Match has ended" }
    }

    private fun checkRunning() {
        check(!stopping && world.running === this) { "Spatial process is not running" }
    }

    private fun checkOwned(fact: SpatialFact) {
        checkRunning()
        check(fact in owned && world.contains(fact)) { "Process does not own a live publication" }
    }
}

/**
 * Single-threaded point and multigraph rules on a caller-supplied animation clock.
 * Each step commits publication changes, evaluates the rules, then advances each process once.
 */
class SpatialRuleWorld(
    private val parallelSearch: Boolean = false,
    private val indexFactory: SpatialIndexFactory = gridFactory,
) : AutoCloseable {
    private class Publication {
        var position: Vec3? = null
        val data = linkedMapOf<String, Any?>()
    }

    private object Stopped : RuntimeException("Spatial process stopped", null, false, false)
    private data class Task(val group: String, val scope: SpatialProcess)
    private val facts = linkedMapOf<Long, SpatialFact>()
    private val pending = linkedMapOf<SpatialFact, Publication>()
    private val removed = linkedSetOf<SpatialFact>()
    private val groups = linkedMapOf<String, SpatialRules>()
    private val tasks = linkedSetOf<Task>()
    private val failures = mutableListOf<Throwable>()
    private var history = emptyMap<SpatialProximity, Set<List<Long>>>()
    private var drawing = mutableListOf<FLine>()
    private var nextIdentity = 0L
    internal var running: SpatialProcess? = null
        private set
    private var busy = false
    private var closed = false

    var now: Double = 0.0
        private set
    var frame: Long = 0
        private set

    fun points(kind: String): List<SpatialPoint> = facts.values.filterIsInstance<SpatialPoint>().filter { it.alive && it.kind == kind }
    fun edges(kind: String): List<SpatialEdge> = facts.values.filterIsInstance<SpatialEdge>().filter { it.alive && it.kind == kind }

    fun explain(fact: SpatialFact): SpatialExplanation? {
        if (facts[fact.id] !== fact || !fact.alive) return null
        return SpatialExplanation(
            fact.id,
            fact.kind,
            fact.origin,
            fact.origin.inputs.map { id ->
                val input = facts[id]?.takeIf { it.alive }
                SpatialInput(id, input?.kind, input != null)
            },
            now - fact.publishedAt,
            fact.lifetime,
            fact.activation?.live,
        )
    }

    /** Install or replace a named source, leaving the other producers running. */
    fun source(name: String, body: suspend SpatialProcess.() -> Unit) = exclusive {
        stopGroup(name)
        launch(name, SpatialOrigin(name, emptyList()), null, body)
        throwFailures()
    }

    /** Build a rule group before replacing the previous group with the same name. */
    fun rules(name: String, block: SpatialRules.() -> Unit) = exclusive {
        val replacement = SpatialRules().apply(block)
        stopGroup(name)
        groups[name] = replacement
        throwFailures()
    }

    fun stop(name: String) = exclusive {
        stopGroup(name)
        throwFailures()
    }

    fun step(seconds: Double): List<FLine> = exclusive {
        require(seconds.isFinite() && (frame == 0L || seconds >= now)) { "Expected monotonic frame time" }
        now = seconds
        frame++
        commit()
        val queries = SpatialQueries(
            facts.values.filterIsInstance<SpatialPoint>().groupBy { it.kind },
            facts.values.filterIsInstance<SpatialEdge>().groupBy { it.kind },
            history,
            indexFactory,
            parallelSearch,
        )
        for ((group, rules) in groups) {
            for ((name, rule) in rules.rules) rule.evaluate(this, group, "$group/$name", queries)
        }
        history = queries.history()
        drawing = mutableListOf()
        for ((_, process) in tasks.toList()) {
            process.releasePublications(all = false)
            val continuation = checkNotNull(process.continuation)
            process.continuation = null
            running = process
            try {
                continuation.resume(Unit)
            } finally {
                running = null
            }
        }
        throwFailures()
        drawing.toList()
    }

    override fun close() {
        if (closed) return
        exclusive {
            closed = true
            for (task in tasks.toList()) stopTask(task)
            facts.values.forEach { it.alive = false }
            facts.clear()
            pending.clear()
            removed.clear()
            groups.clear()
            history = emptyMap()
            drawing.clear()
            throwFailures()
        }
    }

    internal fun launch(
        group: String,
        origin: SpatialOrigin,
        match: SpatialMatch?,
        body: suspend SpatialProcess.() -> Unit,
    ) {
        val scope = SpatialProcess(this, origin, match)
        val task = Task(group, scope)
        tasks.add(task)
        scope.continuation = body.createCoroutine(scope, object : Continuation<Unit> {
            override val context = EmptyCoroutineContext

            override fun resumeWith(result: Result<Unit>) {
                scope.releasePublications(all = true)
                tasks.remove(task)
                result.exceptionOrNull()?.takeUnless { it === Stopped }?.let {
                    failures.add(IllegalStateException("Spatial process ${origin.process}", it))
                }
            }
        })
    }

    internal fun nextId(): Long = ++nextIdentity

    internal fun contains(fact: SpatialFact): Boolean = facts[fact.id] === fact && fact !in removed

    internal fun checkEndpoint(point: SpatialPoint) {
        require(facts[point.id] === point && (point.alive || contains(point))) { "Edge endpoints must belong to this world and still be published" }
    }

    internal fun publish(fact: SpatialFact, lifetime: SpatialLifetime, activation: SpatialMatch?) {
        fact.lifetime = lifetime
        fact.activation = activation
        facts[fact.id] = fact
        pending[fact] = Publication()
    }

    internal fun retract(fact: SpatialFact) {
        if (facts[fact.id] === fact) removed.add(fact)
    }

    internal fun move(point: SpatialPoint, to: Vec3) {
        requireFinite(to)
        pending.getOrPut(point, ::Publication).position = Vec3(to)
    }

    internal fun set(fact: SpatialFact, key: String, value: Any?) {
        pending.getOrPut(fact, ::Publication).data[key] = value
    }

    internal fun draw(line: FLine) {
        drawing.add(line)
    }

    private fun commit() {
        for ((fact, publication) in pending) {
            if (fact in removed) continue
            if (fact is SpatialPoint) publication.position?.let { fact.position = it }
            if (publication.data.isNotEmpty()) {
                fact.data = Collections.unmodifiableMap(fact.data.toMutableMap().apply {
                    for ((key, value) in publication.data) {
                        if (value == null) remove(key) else put(key, value)
                    }
                })
            }
            if (!fact.alive) fact.publishedAt = now
            fact.alive = true
        }
        pending.clear()
        for (fact in removed) {
            fact.alive = false
            facts.remove(fact.id)
        }
        removed.clear()
        facts.values.removeIf {
            val lostEndpoint = it is SpatialEdge && (!it.from.alive || !it.to.alive)
            if (lostEndpoint) it.alive = false
            lostEndpoint
        }
    }

    private fun stopGroup(name: String) {
        groups.remove(name)
        tasks.filter { it.group == name }.forEach(::stopTask)
    }

    private fun stopTask(task: Task) {
        val scope = task.scope
        scope.stopping = true
        val continuation = scope.continuation
        scope.continuation = null
        continuation?.resumeWithException(Stopped)
    }

    private fun throwFailures() {
        if (failures.isEmpty()) return
        val first = failures.first()
        failures.drop(1).forEach(first::addSuppressed)
        failures.clear()
        throw first
    }

    private inline fun <T> exclusive(block: () -> T): T {
        check(!busy && !closed) { "Spatial world is busy or closed" }
        busy = true
        try {
            return block()
        } finally {
            busy = false
        }
    }
}

private fun requireFinite(position: Vec3) {
    require(position.x.isFinite() && position.y.isFinite() && position.z.isFinite()) { "Expected a finite position" }
}
