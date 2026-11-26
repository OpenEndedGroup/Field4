package afield.data.graph

import afield.document.graph.Box
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
    id: Long,
    val kind: String,
    val origin: SpatialOrigin,
    data: Map<String, Any>,
) {
    /**
     * This fact's identity in its world. A fact published by a parallel process gets its ID when
     * the step that published it ends, in the same order every run; until then it reads 0.
     */
    var id: Long = id
        internal set

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
    internal var motion = Vec3()

    val pos: Vec3 get() = Vec3(position)

    /**
     * Units a second, from the last two committed positions; zero for a point that did not move
     * on the last frame, or has only just been published.
     */
    val velocity: Vec3 get() = Vec3(motion)

    /** The distance between the two committed positions. */
    fun distance(other: SpatialPoint): Double = position.distance(other.position).toDouble()
}

/** Each edge has its own identity. Endpoint order is preserved, including for parallel edges. */
class SpatialEdge internal constructor(
    id: Long,
    kind: String,
    origin: SpatialOrigin,
    data: Map<String, Any>,
    val from: SpatialPoint,
    val to: SpatialPoint,
) : SpatialFact(id, kind, origin, data) {
    /** The distance between the endpoints' committed positions. */
    val length: Double get() = from.distance(to)

    /** The position a fraction [t] of the way from [from] to [to]. */
    fun at(t: Double): Vec3 {
        val a = from.position
        val b = to.position
        val f = t.toFloat()
        return Vec3(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f, a.z + (b.z - a.z) * f)
    }

    /** The endpoint that is not [end]. */
    fun other(end: SpatialPoint): SpatialPoint = when {
        end === from -> to
        end === to -> from
        else -> throw IllegalArgumentException("Point ${end.id} is not an endpoint of edge $id")
    }
}

/**
 * A fact joining any number of points, such as a rope pinned in three places. Unlike an edge,
 * which goes when either end goes, a link stays while any of its [ends] is published and
 * retracts once the last of them has gone. [attached] is the ends still published.
 */
class SpatialLink internal constructor(
    id: Long,
    kind: String,
    origin: SpatialOrigin,
    data: Map<String, Any>,
    val ends: List<SpatialPoint>,
) : SpatialFact(id, kind, origin, data) {
    val attached: List<SpatialPoint> get() = ends.filter { it.alive }
}

internal data class SpatialBinding<T>(val ids: List<Long>, val value: T)

/** A query over the committed facts in one frame. Reuse a pattern to share its evaluation. */
class SpatialPattern<T> internal constructor(
    internal val query: SpatialQueries.() -> List<SpatialBinding<T>>,
) {
    fun where(predicate: (T) -> Boolean): SpatialPattern<T> {
        val upstream = this
        return SpatialPattern { matches(upstream).filter { predicate(it.value) } }
    }

    /**
     * Keep each binding with [probability], decided once for that binding and never again: the
     * same pair of points gets the same answer every frame, and again if it leaves and comes back.
     * A different [seed] makes a different, equally fixed, choice. (A random number in [where]
     * would be drawn anew every frame.)
     */
    fun chance(probability: Double, seed: Long = 0L): SpatialPattern<T> {
        require(probability in 0.0..1.0) { "probability must be between 0 and 1" }
        val upstream = this
        return SpatialPattern { matches(upstream).filter { unitHash(it.ids, seed) < probability } }
    }
}

/** What a [SpatialRules.computed] pattern reads: the facts committed by the start of this frame. */
class SpatialView internal constructor(private val queries: SpatialQueries) {
    fun points(kind: String): List<SpatialPoint> = queries.points(kind)
    fun edges(kind: String): List<SpatialEdge> = queries.edges(kind)
    fun links(kind: String): List<SpatialLink> = queries.links(kind)

    /** The values [pattern] binds this frame, shared with any rule that uses the same pattern. */
    fun <T> matches(pattern: SpatialPattern<T>): List<T> = queries.matches(pattern).map { it.value }
}

private fun unitHash(ids: List<Long>, seed: Long): Double {
    var h = seed xor -0x61c8864680b583ebL
    for (id in ids) {
        h = (h xor id) * -0x40a7b892e31b1a47L
        h = h xor (h ushr 31)
    }
    h = (h xor (h ushr 30)) * -0x40a7b892e31b1a47L
    h = (h xor (h ushr 27)) * -0x6b2fb644ecceee15L
    h = h xor (h ushr 31)
    return (h ushr 11).toDouble() / (1L shl 53).toDouble()
}

/**
 * Three points joined pairwise by edges. The points are in ID order, and [ab] joins [a] to [b],
 * whichever way the edge itself points.
 */
data class SpatialTriangle(
    val a: SpatialPoint,
    val b: SpatialPoint,
    val c: SpatialPoint,
    val ab: SpatialEdge,
    val bc: SpatialEdge,
    val ca: SpatialEdge,
) {
    val points: List<SpatialPoint> get() = listOf(a, b, c)
    val edges: List<SpatialEdge> get() = listOf(ab, bc, ca)

    /** The mean of the three committed positions. */
    val centroid: Vec3 get() {
        val p = a.position
        val q = b.position
        val r = c.position
        return Vec3((p.x + q.x + r.x) / 3f, (p.y + q.y + r.y) / 3f, (p.z + q.z + r.z) / 3f)
    }
}

/**
 * Each triangle the matched edges close, once, ignoring edge direction. Where parallel edges join
 * the same two points, the one with the lowest ID stands for the pair. The binding is the three
 * edges, so a triangle that loses an edge and regains it is a new match.
 */
fun SpatialPattern<SpatialEdge>.triangles(): SpatialPattern<SpatialTriangle> {
    val upstream = this
    return SpatialPattern {
        val joining = HashMap<Long, HashMap<Long, SpatialEdge>>()
        for ((_, edge) in matches(upstream)) {
            val u = edge.from
            val v = edge.to
            if (u === v) continue
            for ((x, y) in listOf(u to v, v to u)) {
                val row = joining.getOrPut(x.id, ::HashMap)
                val held = row[y.id]
                if (held == null || edge.id < held.id) row[y.id] = edge
            }
        }
        val pointsById = HashMap<Long, SpatialPoint>()
        for ((_, edge) in matches(upstream)) {
            pointsById[edge.from.id] = edge.from
            pointsById[edge.to.id] = edge.to
        }
        buildList {
            for (a in joining.keys.sorted()) {
                val fromA = joining.getValue(a)
                val higher = fromA.keys.filter { it > a }.sorted()
                for ((i, b) in higher.withIndex()) {
                    val fromB = joining[b] ?: continue
                    for (c in higher.subList(i + 1, higher.size)) {
                        val bc = fromB[c] ?: continue
                        val ab = fromA.getValue(b)
                        val ca = fromA.getValue(c)
                        val triangle = SpatialTriangle(
                            pointsById.getValue(a), pointsById.getValue(b), pointsById.getValue(c), ab, bc, ca,
                        )
                        add(SpatialBinding(listOf(ab.id, bc.id, ca.id).sorted(), triangle))
                    }
                }
            }
        }
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
    private val linksByKind: Map<String, List<SpatialLink>>,
    private val previous: Map<SpatialProximity, Set<List<Long>>>,
    private val indexFactory: SpatialIndexFactory,
    private val parallelSearch: Boolean,
) {
    private val results = mutableMapOf<SpatialPattern<*>, List<SpatialBinding<*>>>()
    private val candidates = mutableMapOf<SpatialProximity, List<SpatialBinding<Pair<SpatialPoint, SpatialPoint>>>>()
    private val grids = mutableMapOf<Triple<String, String, Double>, Pair<List<SpatialPoint>, SpatialNeighborIndex>>()

    fun points(kind: String): List<SpatialPoint> = pointsByKind[kind].orEmpty()
    fun edges(kind: String): List<SpatialEdge> = edgesByKind[kind].orEmpty()
    fun links(kind: String): List<SpatialLink> = linksByKind[kind].orEmpty()

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

    /**
     * A pattern you compute: [compute] reads this frame's facts, and the values it returned last
     * frame, and returns this frame's values. [identity] names the facts each value is about, and
     * that is what a rule keeps a process for: a value whose identity was there last frame keeps
     * its process, a new one starts one, and one that is missing ends its match. Values with the
     * same identity count once. Use it for any structure a rule should follow that no built-in
     * pattern describes, such as a minimum spanning tree over moving points.
     */
    fun <T> computed(
        identity: (T) -> List<SpatialFact>,
        compute: SpatialView.(previous: List<T>) -> Iterable<T>,
    ): SpatialPattern<T> {
        var previous = emptyList<T>()
        return SpatialPattern {
            val values = SpatialView(this).compute(previous).toList()
            previous = values
            values.map { SpatialBinding(identity(it).map(SpatialFact::id), it) }
        }
    }

    /**
     * [computed] for unordered pairs of points, such as the edges of a tree: each pair is put in
     * ID order, as [SpatialPoints.pairsWithin] does, so `a to b` and `b to a` are the same match.
     */
    fun pairs(
        compute: SpatialView.(previous: List<Pair<SpatialPoint, SpatialPoint>>) -> Iterable<Pair<SpatialPoint, SpatialPoint>>,
    ): SpatialPattern<Pair<SpatialPoint, SpatialPoint>> =
        computed({ (a, b) -> listOf(a, b) }) { previous ->
            compute(previous).map { (a, b) -> if (a.id <= b.id) a to b else b to a }
        }

    /** Each link of [kind], for as long as any of its ends is published. */
    fun links(kind: String): SpatialPattern<SpatialLink> = SpatialPattern {
        links(kind).map { SpatialBinding(listOf(it.id), it) }
    }

    /**
     * Start [body] once for each new binding of [pattern]. With [parallel], this rule's processes
     * advance at the same time on several threads, which pays off when each does real work, such
     * as a rope's physics. What they publish, move, retract and draw still lands in the same
     * order every run. Variables they share must be safe to change from several threads at once:
     * use `0.atomic` for a counter.
     */
    fun <T> onEnter(
        name: String,
        pattern: SpatialPattern<T>,
        condition: String = name,
        parallel: Boolean = false,
        body: suspend SpatialProcess.(T) -> Unit,
    ) {
        require(name !in rules) { "Duplicate spatial rule: $name" }
        rules[name] = SpatialRule(pattern, condition, parallel, body)
    }
}

internal class SpatialRule<T>(
    private val pattern: SpatialPattern<T>,
    private val condition: String,
    private val parallel: Boolean,
    private val body: suspend SpatialProcess.(T) -> Unit,
) {
    private var live = linkedMapOf<List<Long>, SpatialMatch>()

    fun evaluate(world: SpatialRuleWorld, group: String, name: String, queries: SpatialQueries) {
        val next = linkedMapOf<List<Long>, SpatialMatch>()
        for ((ids, value) in queries.matches(pattern)) {
            next[ids] = live[ids] ?: SpatialMatch().also { match ->
                world.launch(group, SpatialOrigin(name, Collections.unmodifiableList(ids), condition), match, parallel) { body(value) }
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
    internal val parallel: Boolean = false,
) {
    private val owned = linkedMapOf<SpatialFact, SpatialLifetime>()
    internal var continuation: Continuation<Unit>? = null
    internal var stopping = false
    internal var active = false
    internal var finished: Result<Unit>? = null

    /** Lines drawn this step, and, for a parallel process, what it changed, for the world to apply. */
    internal val outbox = SpatialOutbox()

    internal var startedAt = Double.NaN

    val now: Double get() = world.now
    val frame: Long get() = world.frame
    val match: SpatialMatch get() = checkNotNull(activation) { "A source has no match" }

    /** Seconds since this process first ran. */
    val age: Double get() = if (startedAt.isNaN()) 0.0 else world.now - startedAt

    /** Suspend until the world has committed and matched the next frame. */
    suspend fun nextFrame(): Unit = suspendCoroutineUninterceptedOrReturn {
        checkRunning()
        continuation = it
        COROUTINE_SUSPENDED
    }

    /** Run [body] once a frame for as long as the match holds. */
    suspend inline fun whileLive(body: () -> Unit) {
        while (match.live) {
            body()
            nextFrame()
        }
    }

    /** Wait for the match to end, keeping this process's publications in place until then. */
    suspend fun whileLive() {
        while (match.live) nextFrame()
    }

    /**
     * Run [body] once a frame for [seconds], passing the fraction of that time already gone,
     * from 0 toward 1. Use it for a fade after the match ends, or for a growth phase.
     */
    suspend inline fun during(seconds: Double, body: (progress: Double) -> Unit) {
        val start = now
        while (now - start < seconds) {
            body(((now - start) / seconds).coerceIn(0.0, 1.0))
            nextFrame()
        }
    }

    /** Let [seconds] pass. Publications stay as they are. */
    suspend fun wait(seconds: Double) {
        val start = now
        while (now - start < seconds) nextFrame()
    }

    fun point(
        kind: String,
        at: Vec3,
        data: Map<String, Any> = emptyMap(),
        lifetime: SpatialLifetime = SpatialLifetime.PROCESS,
    ): SpatialPoint {
        checkPublishing(lifetime)
        requireFinite(at)
        return SpatialPoint(newId(), kind, origin, data, Vec3(at)).also { publish(it, lifetime) }
    }

    fun edge(
        kind: String,
        from: SpatialPoint,
        to: SpatialPoint,
        data: Map<String, Any> = emptyMap(),
        lifetime: SpatialLifetime = SpatialLifetime.PROCESS,
    ): SpatialEdge {
        checkPublishing(lifetime)
        checkEndpoint(from)
        checkEndpoint(to)
        return SpatialEdge(newId(), kind, origin, data, from, to).also { publish(it, lifetime) }
    }

    /**
     * Publish a link joining [ends]. It lasts as its [lifetime] says and, beyond that, until
     * the last of its ends is retracted; losing some ends leaves it in place.
     */
    fun link(
        kind: String,
        ends: List<SpatialPoint>,
        data: Map<String, Any> = emptyMap(),
        lifetime: SpatialLifetime = SpatialLifetime.PROCESS,
    ): SpatialLink {
        checkPublishing(lifetime)
        require(ends.isNotEmpty()) { "A link needs at least one end" }
        ends.forEach(::checkEndpoint)
        return SpatialLink(newId(), kind, origin, data, ends.toList()).also { publish(it, lifetime) }
    }

    fun move(point: SpatialPoint, to: Vec3) {
        checkOwned(point)
        if (parallel) {
            requireFinite(to)
            outbox.changes.getOrPut(point, ::SpatialPublication).position = Vec3(to)
        } else {
            world.move(point, to)
        }
    }

    /** Pass null to remove the key. Use immutable values to keep frame snapshots consistent. */
    fun set(fact: SpatialFact, key: String, value: Any?) {
        checkOwned(fact)
        if (parallel) outbox.changes.getOrPut(fact, ::SpatialPublication).data[key] = value else world.set(fact, key, value)
    }

    fun retract(fact: SpatialFact) {
        checkOwned(fact)
        owned.remove(fact)
        if (parallel) outbox.retracted += fact else world.retract(fact)
    }

    /** Submit a line for this frame. Submit it again on any later frame that should draw it. */
    fun draw(line: FLine) {
        checkRunning()
        outbox.drawing += line
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

    /** A parallel process's facts are numbered when the world applies its outbox. */
    private fun newId(): Long = if (parallel) 0L else world.nextId()

    private fun publish(fact: SpatialFact, lifetime: SpatialLifetime) {
        owned[fact] = lifetime
        if (parallel) {
            fact.lifetime = lifetime
            fact.activation = activation
            outbox.published += fact
            outbox.publishedSet += fact
        } else {
            world.publish(fact, lifetime, activation)
        }
    }

    private fun checkEndpoint(point: SpatialPoint) {
        if (!(parallel && point in outbox.publishedSet)) world.checkEndpoint(point)
    }

    private fun checkPublishing(lifetime: SpatialLifetime) {
        checkRunning()
        if (lifetime == SpatialLifetime.MATCH) check(match.live) { "Match has ended" }
    }

    private fun checkRunning() {
        check(!stopping && active) { "Spatial process is not running" }
    }

    private fun checkOwned(fact: SpatialFact) {
        checkRunning()
        check(fact in owned && (world.contains(fact) || (parallel && fact in outbox.publishedSet))) {
            "Process does not own a live publication"
        }
    }
}

internal class SpatialPublication {
    var position: Vec3? = null
    val data = linkedMapOf<String, Any?>()
}

internal class SpatialOutbox {
    val drawing = mutableListOf<FLine>()
    val published = mutableListOf<SpatialFact>()
    val publishedSet = hashSetOf<SpatialFact>()
    val changes = linkedMapOf<SpatialFact, SpatialPublication>()
    val retracted = mutableListOf<SpatialFact>()
}

/**
 * Point and multigraph rules on a caller-supplied animation clock.
 * Each step commits publication changes, evaluates the rules, then advances each process once.
 * Processes of rules marked `parallel` advance together on several threads; the others advance
 * one at a time on the caller's thread.
 */
class SpatialRuleWorld(
    private val parallelSearch: Boolean = false,
    private val indexFactory: SpatialIndexFactory = gridFactory,
) : AutoCloseable {
    private object Stopped : RuntimeException("Spatial process stopped", null, false, false)
    private data class Task(val group: String, val scope: SpatialProcess)
    private val facts = linkedMapOf<Long, SpatialFact>()
    private val pending = linkedMapOf<SpatialFact, SpatialPublication>()
    private val removed = linkedSetOf<SpatialFact>()
    private val groups = linkedMapOf<String, SpatialRules>()
    private val tasks = linkedSetOf<Task>()
    private val failures = mutableListOf<Throwable>()
    private var history = emptyMap<SpatialProximity, Set<List<Long>>>()
    private var drawing = mutableListOf<FLine>()
    private var nextIdentity = 0L
    private var busy = false
    private var closed = false

    var now: Double = 0.0
        private set
    var frame: Long = 0
        private set

    fun points(kind: String): List<SpatialPoint> = facts.values.filterIsInstance<SpatialPoint>().filter { it.alive && it.kind == kind }
    fun edges(kind: String): List<SpatialEdge> = facts.values.filterIsInstance<SpatialEdge>().filter { it.alive && it.kind == kind }
    fun links(kind: String): List<SpatialLink> = facts.values.filterIsInstance<SpatialLink>().filter { it.alive && it.kind == kind }

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

    /** The live fact with this [id], if there is one. */
    fun fact(id: Long): SpatialFact? = facts[id]?.takeIf { it.alive }

    /** [explain] for the live fact with this [id], if there is one. */
    fun explain(id: Long): SpatialExplanation? = fact(id)?.let(::explain)

    /**
     * The live point or edge closest to [at], within [within] units, or null. An edge's distance
     * is to the nearest place along it. [kinds], when given, limits the search to those kinds.
     */
    fun nearest(at: Vec3, within: Double, kinds: Set<String>? = null): SpatialFact? {
        var best: SpatialFact? = null
        var bestDistance = within
        for (fact in facts.values) {
            if (!fact.alive || (kinds != null && fact.kind !in kinds)) continue
            val distance = when (fact) {
                is SpatialPoint -> fact.position.distance(at).toDouble()
                is SpatialEdge -> segmentDistance(at, fact.from.position, fact.to.position)
                is SpatialLink -> fact.attached.zipWithNext { a, b -> segmentDistance(at, a.position, b.position) }
                    .minOrNull() ?: fact.attached.minOf { it.position.distance(at).toDouble() }
            }
            if (distance <= bestDistance) {
                best = fact
                bestDistance = distance
            }
        }
        return best
    }

    /**
     * [fact] and every live fact it was made from, nearest first: a web strand, the beads it
     * joins, the bonds that made those beads, and the seeds those bonds join. Each fact appears
     * once. A source's publications end the walk, as do inputs that have since been retracted.
     */
    fun lineage(fact: SpatialFact): List<SpatialFact> {
        val seen = HashSet<Long>()
        val queue = ArrayDeque(listOf(fact.id))
        return buildList {
            while (queue.isNotEmpty()) {
                val next = fact(queue.removeFirst()) ?: continue
                if (!seen.add(next.id)) continue
                add(next)
                queue.addAll(next.origin.inputs)
            }
        }
    }

    /** Install or replace a named source, leaving the other producers running. */
    fun source(name: String, body: suspend SpatialProcess.() -> Unit) = exclusive {
        stopGroup(name)
        launch(name, SpatialOrigin(name, emptyList()), null, body = body)
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
        val elapsed = if (frame == 0L) 0.0 else seconds - now
        now = seconds
        frame++
        commit(elapsed)
        val queries = SpatialQueries(
            facts.values.filterIsInstance<SpatialPoint>().groupBy { it.kind },
            facts.values.filterIsInstance<SpatialEdge>().groupBy { it.kind },
            facts.values.filterIsInstance<SpatialLink>().groupBy { it.kind },
            history,
            indexFactory,
            parallelSearch,
        )
        for ((group, rules) in groups) {
            for ((name, rule) in rules.rules) rule.evaluate(this, group, "$group/$name", queries)
        }
        history = queries.history()
        val batch = tasks.toList()
        for ((_, process) in batch) {
            process.releasePublications(all = false)
            if (process.startedAt.isNaN()) process.startedAt = now
        }
        // Serial processes first, in order, then the parallel ones together. A parallel process
        // reads only committed state and writes only its own outbox, which is applied in task
        // order afterwards, so the result does not depend on how the threads were scheduled.
        for ((_, process) in batch) if (!process.parallel) advance(process)
        val parallel = batch.map(Task::scope).filter(SpatialProcess::parallel)
        if (parallel.size == 1) {
            advance(parallel.single())
        } else if (parallel.isNotEmpty()) {
            val box = Box.context.get()
            parallel.parallelStream().forEach { process -> withBox(box) { advance(process) } }
        }
        parallel.forEach(::deliver)
        drawing = mutableListOf()
        for (task in batch) {
            drawing += task.scope.outbox.drawing
            task.scope.outbox.drawing.clear()
            if (task.scope.finished != null) finish(task)
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
        parallel: Boolean = false,
        body: suspend SpatialProcess.() -> Unit,
    ) {
        val scope = SpatialProcess(this, origin, match, parallel)
        tasks.add(Task(group, scope))
        scope.continuation = body.createCoroutine(scope, object : Continuation<Unit> {
            override val context = EmptyCoroutineContext

            // This can run on a worker thread, so it only records the result; [finish] acts on it.
            override fun resumeWith(result: Result<Unit>) {
                scope.finished = result
            }
        })
    }

    private fun advance(process: SpatialProcess) {
        val continuation = checkNotNull(process.continuation)
        process.continuation = null
        process.active = true
        try {
            continuation.resume(Unit)
        } finally {
            process.active = false
        }
    }

    /** Apply what a parallel process published, changed and retracted during the step. */
    private fun deliver(process: SpatialProcess) {
        val outbox = process.outbox
        for (fact in outbox.published) {
            fact.id = nextId()
            publish(fact, fact.lifetime, fact.activation)
        }
        for ((fact, change) in outbox.changes) {
            val publication = pending.getOrPut(fact, ::SpatialPublication)
            change.position?.let { publication.position = it }
            publication.data.putAll(change.data)
        }
        outbox.retracted.forEach(::retract)
        outbox.published.clear()
        outbox.publishedSet.clear()
        outbox.changes.clear()
        outbox.retracted.clear()
    }

    private fun finish(task: Task) {
        val scope = task.scope
        val result = checkNotNull(scope.finished)
        scope.releasePublications(all = true)
        tasks.remove(task)
        result.exceptionOrNull()?.takeUnless { it === Stopped }?.let {
            failures.add(IllegalStateException("Spatial process ${scope.origin.process}", it))
        }
    }

    private inline fun withBox(box: Box?, block: () -> Unit) {
        val previous: Box? = Box.context.get()
        if (box == null) Box.context.remove() else Box.context.set(box)
        try {
            block()
        } finally {
            if (previous == null) Box.context.remove() else Box.context.set(previous)
        }
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
        pending[fact] = SpatialPublication()
    }

    internal fun retract(fact: SpatialFact) {
        if (facts[fact.id] === fact) removed.add(fact)
    }

    internal fun move(point: SpatialPoint, to: Vec3) {
        requireFinite(to)
        pending.getOrPut(point, ::SpatialPublication).position = Vec3(to)
    }

    internal fun set(fact: SpatialFact, key: String, value: Any?) {
        pending.getOrPut(fact, ::SpatialPublication).data[key] = value
    }

    private fun commit(elapsed: Double) {
        for (fact in facts.values) if (fact is SpatialPoint) fact.motion = Vec3()
        for ((fact, publication) in pending) {
            if (fact in removed) continue
            if (fact is SpatialPoint) publication.position?.let {
                if (fact.alive && elapsed > 0.0) {
                    val scale = (1.0 / elapsed).toFloat()
                    fact.motion = Vec3((it.x - fact.position.x) * scale, (it.y - fact.position.y) * scale, (it.z - fact.position.z) * scale)
                }
                fact.position = it
            }
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
            val lostEndpoint = (it is SpatialEdge && (!it.from.alive || !it.to.alive)) ||
                (it is SpatialLink && it.ends.none(SpatialPoint::alive))
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
        if (scope.finished != null) finish(task)
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

private fun segmentDistance(p: Vec3, a: Vec3, b: Vec3): Double {
    val abx = (b.x - a.x).toDouble()
    val aby = (b.y - a.y).toDouble()
    val abz = (b.z - a.z).toDouble()
    val lengthSquared = abx * abx + aby * aby + abz * abz
    val t = if (lengthSquared == 0.0) 0.0 else
        (((p.x - a.x) * abx + (p.y - a.y) * aby + (p.z - a.z) * abz) / lengthSquared).coerceIn(0.0, 1.0)
    val dx = a.x + abx * t - p.x
    val dy = a.y + aby * t - p.y
    val dz = a.z + abz * t - p.z
    return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
}

private fun requireFinite(position: Vec3) {
    require(position.x.isFinite() && position.y.isFinite() && position.z.isFinite()) { "Expected a finite position" }
}
