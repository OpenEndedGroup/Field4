package afield.four.physics

import afield.graphics.FLine
import afield.stdlib.Vec2
import afield.stdlib.vec
import field.graphics.FLinesAndJavaShapes
import org.dyn4j.dynamics.Body
import org.dyn4j.geometry.Convex
import org.dyn4j.geometry.Geometry
import org.dyn4j.geometry.MassType
import org.dyn4j.geometry.Vector2 as DynVec2
import org.dyn4j.geometry.decompose.Bayazit
import org.dyn4j.geometry.decompose.Decomposer
import org.dyn4j.world.World
import java.awt.geom.AffineTransform
import java.awt.geom.FlatteningPathIterator
import java.awt.geom.PathIterator
import kotlin.math.abs

/**
 * A 2D rigid-body scene: dyn4j's `World` behind a Kotlin surface that addresses bodies by handle,
 * with a converter from Field's own drawing into collision geometry.
 *
 * [SimdVerlet] has no notion of a rigid body. Its colliders push particles out of shapes, and
 * nothing a particle touches is pushed back by it. This is the other half of that, flat: bodies
 * with mass, rotation, friction and restitution, resolving contacts against one another.
 * [JoltPhysicsWorld] is the same idea in 3D, and [SimdVerletDyn4jBridge] runs a particle
 * simulation and one of these scenes in the same frame.
 *
 * #### Handles, not bodies
 *
 * [addBody] returns a [DynBodyHandle] rather than the dyn4j `Body` it made, and every accessor
 * takes one. Handle ids count up from 1 and are never reused, so a handle to a removed body
 * cannot come to mean a later one. A handle whose body has been removed resolves to nothing:
 * [getBodyState] returns `null`, [removeBody] returns `false`, and the five mutators return
 * without touching anything. Nothing throws on a stale handle, which suits a `loop { }` editing a
 * scene it is also drawing.
 *
 * [world] is public. Joints, raycasts, contact listeners and everything else dyn4j offers are
 * reached through it; what this class covers is making bodies, moving them and reading them back.
 *
 * #### Shapes
 *
 * [DynShapeDef] has four cases. `Box` and `Circle` go straight to dyn4j primitives. `Polygon`
 * must already be a convex loop, and a loop dyn4j rejects is re-reported as an `error` carrying
 * dyn4j's own complaint. `Concave` is decomposed by Bayazit into convex parts, and each part
 * becomes a fixture on the one body, so a concave body is several fixtures sharing a transform.
 *
 * Both vertex cases are sanitised first: consecutive duplicates are dropped, a repeated closing
 * point is removed, dyn4j's own `cleanse` runs, and a loop whose signed winding is negative is
 * reversed, so every loop reaching dyn4j turns the same way whichever way it was drawn. Fewer
 * than three distinct vertices fails a `require`.
 *
 * #### Mass and rest
 *
 * [DynBodyKind.STATIC] sets `MassType.INFINITE`; [DynBodyKind.DYNAMIC] takes its mass from
 * [DynBodyDef.density] and the fixture areas. Density is floored just above zero, so asking for
 * `0.0` gives a very light body rather than a massless one. dyn4j lets a body that has stopped
 * moving go to rest; every mutator here clears that flag, including the two that write
 * `body.transform` directly and so bypass dyn4j entirely.
 *
 * [DynBodyState.position] is the body's centre of mass, while [setBodyTransform] writes the
 * transform's translation. For a box or a circle, both of which dyn4j builds around the origin,
 * those coincide. For a polygon given arbitrary vertices they need not, so setting a position and
 * reading it back does not always round-trip.
 *
 * #### Stepping
 *
 * [step] is one dyn4j iteration of exactly the `dtSeconds` it is given, so the timestep belongs
 * to the caller. Gravity defaults to zero on both axes: a scene made with no arguments is
 * weightless, and gravity is changed afterwards through `world.gravity`.
 *
 * #### FLines as collision geometry
 *
 * [flattenClosedFLineLoops] turns a drawn [FLine] into vertex loops for `DynShapeDef.Concave` or
 * `Polygon`, by way of `java.awt.geom` path flattening: curves become runs of line segments at
 * the given tolerance and subdivision limit, and each subpath of three or more points comes back
 * as a loop with any duplicate closing point removed. It reads `SEG_CLOSE` off the path iterator,
 * but its final flush accepts an unterminated subpath as well, and a dyn4j polygon is closed
 * implicitly, so a line drawn without a closing node converts to the same vertices as one drawn
 * with it. `InteriorCloseNodeTest.theDyn4jLoopFlattenerWasNeverActuallyBroken` pins that.
 *
 * Nothing else in the repository constructs a [Dyn4jScene]. These are a surface for box code to
 * call, and that one test case is the only coverage the file has.
 */

/** Whether dyn4j moves a body, or it stands still and is only collided against. */
enum class DynBodyKind {
    STATIC,
    DYNAMIC
}

/**
 * The collision shape of a body, in the body's own coordinates.
 *
 * `Box` and `Circle` are dyn4j primitives centred on the origin. `Polygon` must already be a
 * convex loop; `Concave` may be any simple loop and is decomposed into convex parts. Both vertex
 * cases are cleaned up and rewound before dyn4j sees them, as the file overview above describes.
 */
sealed interface DynShapeDef {
    data class Box(val width: Double, val height: Double) : DynShapeDef
    data class Circle(val radius: Double) : DynShapeDef
    data class Polygon(val vertices: List<Vec2>) : DynShapeDef
    data class Concave(val vertices: List<Vec2>) : DynShapeDef
}

/**
 * Everything [Dyn4jScene.addBody] needs to make one body, with defaults that give a
 * unit-density, non-bouncing, undamped body at rest at the origin.
 *
 * `density` is what sets the mass, together with the shape's area; a [DynBodyKind.STATIC] body
 * ignores it and gets infinite mass instead. `bullet` is dyn4j's flag for a body fast enough to
 * need its continuous-collision check. `userData` is handed to dyn4j untouched, which is where to
 * put the identity of whatever the body stands for — it does not come back in [DynBodyState].
 */
data class DynBodyDef(
    val kind: DynBodyKind,
    val shape: DynShapeDef,
    val x: Double = 0.0,
    val y: Double = 0.0,
    val angleRad: Double = 0.0,
    val friction: Double = 0.6,
    val restitution: Double = 0.0,
    val density: Double = 1.0,
    val linearDamping: Double = 0.0,
    val angularDamping: Double = 0.0,
    val bullet: Boolean = false,
    val vx: Double = 0.0,
    val vy: Double = 0.0,
    val angularVelocity: Double = 0.0,
    val userData: Any? = null
)

/**
 * An opaque reference to a body in one [Dyn4jScene], counted up from 1 and never reused.
 *
 * A handle outlives the body it names: once [Dyn4jScene.removeBody] has taken the body out, the
 * handle goes on resolving to nothing rather than to whatever was added next.
 */
data class DynBodyHandle(val id: Long)

/**
 * A body read back out of the scene, as of the last [Dyn4jScene.step].
 *
 * `position` is the centre of mass in world coordinates, not the translation
 * [Dyn4jScene.setBodyTransform] writes; for a shape whose centroid is away from its local origin
 * those differ. `linearVelocity` is per second, unlike the frame displacement a Verlet particle
 * carries.
 */
data class DynBodyState(
    val handle: DynBodyHandle,
    val position: Vec2,
    val angleRad: Double,
    val linearVelocity: Vec2,
    val angularVelocity: Double,
    val kind: DynBodyKind
)

/**
 * A dyn4j `World` plus the handle table that keeps [DynBodyHandle]s pointing at the right bodies.
 *
 * Gravity is a constructor argument and defaults to zero on both axes, so a scene made with no
 * arguments is weightless; after construction it is [world]`.gravity` that decides. Nothing here
 * accumulates time or draws anything: [step] advances the world by the interval it is handed, and
 * [allBodyStates] is what a caller turns into `FLine`s.
 */
class Dyn4jScene(
    gravityX: Double = 0.0,
    gravityY: Double = 0.0
) {
    val world: World<Body> = World<Body>().apply {
        gravity = DynVec2(gravityX, gravityY)
    }

    private data class BodyRecord(val handle: DynBodyHandle, val kind: DynBodyKind, val body: Body)

    private var nextHandleId = 1L
    private val decomposer: Decomposer = Bayazit()
    private val bodyRecords = linkedMapOf<Long, BodyRecord>()

    /**
     * Builds the body [def] describes, adds it to [world], and returns the handle it will be
     * known by.
     *
     * A [DynShapeDef.Concave] shape arrives as several fixtures on this one body; the others as
     * one. Every fixture takes the same friction, restitution and density.
     *
     * @throws IllegalArgumentException for a shape with a non-positive dimension, or a vertex
     *   loop that cleans up to fewer than three distinct points.
     * @throws IllegalStateException for a [DynShapeDef.Polygon] that dyn4j will not accept as
     *   convex. [DynShapeDef.Concave] is the case that decomposes instead.
     */
    fun addBody(def: DynBodyDef): DynBodyHandle {
        val body = Body()
        val convexes = createConvexes(def.shape)
        val density = def.density.coerceAtLeast(1.0e-9)
        for (convex in convexes) {
            val fixture = body.addFixture(convex)
            fixture.friction = def.friction
            fixture.restitution = def.restitution
            fixture.density = density
        }

        body.transform.setTranslation(def.x, def.y)
        body.transform.setRotation(def.angleRad)
        body.setLinearVelocity(def.vx, def.vy)
        body.angularVelocity = def.angularVelocity
        body.linearDamping = def.linearDamping
        body.angularDamping = def.angularDamping
        body.isBullet = def.bullet
        body.userData = def.userData
        body.setMass(if (def.kind == DynBodyKind.STATIC) MassType.INFINITE else MassType.NORMAL)

        world.addBody(body)
        val handle = DynBodyHandle(nextHandleId++)
        bodyRecords[handle.id] = BodyRecord(handle = handle, kind = def.kind, body = body)
        return handle
    }

    /**
     * Takes the body [handle] names out of [world]. `false` if this scene has no such body, and
     * also `false` if it had one but dyn4j had already dropped it.
     */
    fun removeBody(handle: DynBodyHandle): Boolean {
        val removed = bodyRecords.remove(handle.id) ?: return false
        return world.removeBody(removed.body)
    }

    /**
     * Removes every body this scene made, so it is empty and reusable.
     *
     * Anything added to [world] behind this class's back stays; the handle table is what defines
     * "every body" here.
     */
    fun clearBodies() {
        val records = bodyRecords.values.toList()
        bodyRecords.clear()
        for (record in records) {
            world.removeBody(record.body)
        }
    }

    /** A snapshot of one body, or `null` if [handle] names nothing in this scene. */
    fun getBodyState(handle: DynBodyHandle): DynBodyState? {
        val record = bodyRecords[handle.id] ?: return null
        return record.toState()
    }

    /**
     * Every body, in the order it was added, as a fresh list of snapshots.
     *
     * This is the per-frame read a drawing loop wants: nothing in it aliases the dyn4j bodies, so
     * the next [step] cannot change a list already in hand.
     */
    fun allBodyStates(): List<DynBodyState> {
        return bodyRecords.values.map { it.toState() }
    }

    /**
     * Teleports a body: its transform is overwritten and its velocity left as it was.
     *
     * This writes the transform's translation, so for a shape whose centroid is away from its
     * local origin the [DynBodyState.position] read back afterwards will not be `x, y`. Moving a
     * body this way also skips collision detection on the way, so it can be placed inside
     * another one.
     */
    fun setBodyTransform(handle: DynBodyHandle, x: Double, y: Double, angleRad: Double) {
        val body = bodyRecords[handle.id]?.body ?: return
        body.transform.setTranslation(x, y)
        body.transform.setRotation(angleRad)
        body.setAtRest(false)
    }

    /**
     * Overwrites a body's linear and angular velocity, in units per second and radians per
     * second. Unlike an impulse this ignores the body's mass, and unlike a force it takes effect
     * without a [step].
     */
    fun setBodyVelocity(handle: DynBodyHandle, vx: Double, vy: Double, angular: Double) {
        val body = bodyRecords[handle.id]?.body ?: return
        body.setLinearVelocity(vx, vy)
        body.angularVelocity = angular
        body.setAtRest(false)
    }

    /** Adds a world-frame force to a body, to act over the next [step]. */
    fun applyForce(handle: DynBodyHandle, fx: Double, fy: Double) {
        val body = bodyRecords[handle.id]?.body ?: return
        body.applyForce(DynVec2(fx, fy))
        body.setAtRest(false)
    }

    /**
     * Adds a world-frame linear impulse to a body: a change of momentum, so the velocity it buys
     * depends on the body's mass. This is the one for a kick.
     */
    fun applyImpulse(handle: DynBodyHandle, ix: Double, iy: Double) {
        val body = bodyRecords[handle.id]?.body ?: return
        body.applyImpulse(DynVec2(ix, iy))
        body.setAtRest(false)
    }

    /** Adds a torque about a body's centre of mass, the angular counterpart of [applyForce]. */
    fun applyTorque(handle: DynBodyHandle, torque: Double) {
        val body = bodyRecords[handle.id]?.body ?: return
        body.applyTorque(torque)
        body.setAtRest(false)
    }

    /**
     * Advances the world by exactly [dtSeconds]: one dyn4j iteration, with nothing accumulated
     * between calls.
     *
     * So the timestep is the caller's to choose and to keep steady. A `loop { }` drawing at frame
     * rate usually wants a fixed value here rather than a measured frame time, since a rigid-body
     * solve at a varying timestep is not reproducible.
     *
     * @throws IllegalArgumentException if [dtSeconds] is not positive.
     */
    fun step(dtSeconds: Double) {
        require(dtSeconds > 0.0) { "dtSeconds must be > 0" }
        world.step(1, dtSeconds)
    }

    private fun createConvexes(shape: DynShapeDef): List<Convex> {
        return when (shape) {
            is DynShapeDef.Box -> {
                require(shape.width > 0.0 && shape.height > 0.0) { "Box dimensions must be > 0" }
                listOf(Geometry.createRectangle(shape.width, shape.height))
            }

            is DynShapeDef.Circle -> {
                require(shape.radius > 0.0) { "Circle radius must be > 0" }
                listOf(Geometry.createCircle(shape.radius))
            }

            is DynShapeDef.Polygon -> {
                val vertices = sanitizeVertices(shape.vertices)
                try {
                    listOf(Geometry.createPolygon(*vertices.toTypedArray()))
                } catch (t: Throwable) {
                    error("Polygon is not a valid convex loop for dyn4j: ${t.message}")
                }
            }

            is DynShapeDef.Concave -> {
                val vertices = sanitizeVertices(shape.vertices)
                val parts = decomposer.decompose(vertices)
                require(parts.isNotEmpty()) { "Concave decomposition produced no convex parts" }
                parts
            }
        }
    }

    private fun sanitizeVertices(vertices: List<Vec2>): MutableList<DynVec2> {
        val dedup = ArrayList<DynVec2>(vertices.size)
        for (v in vertices) {
            val p = DynVec2(v.x.toDouble(), v.y.toDouble())
            if (dedup.isEmpty() || !samePoint(dedup.last(), p)) {
                dedup.add(p)
            }
        }
        if (dedup.size > 1 && samePoint(dedup.first(), dedup.last())) {
            dedup.removeAt(dedup.lastIndex)
        }

        val cleansed = Geometry.cleanse(dedup)
        val result = ArrayList<DynVec2>(cleansed.size)
        for (p in cleansed) {
            if (result.isEmpty() || !samePoint(result.last(), p)) {
                result.add(DynVec2(p.x, p.y))
            }
        }
        if (result.size > 1 && samePoint(result.first(), result.last())) {
            result.removeAt(result.lastIndex)
        }

        require(result.size >= 3) { "Need at least 3 unique vertices" }
        if (Geometry.getWinding(result) < 0.0) {
            Geometry.reverseWinding(result)
        }
        return result
    }

    private fun samePoint(a: DynVec2, b: DynVec2, eps: Double = 1.0e-9): Boolean {
        return abs(a.x - b.x) <= eps && abs(a.y - b.y) <= eps
    }

    private fun BodyRecord.toState(): DynBodyState {
        val center = body.worldCenter
        val velocity = body.linearVelocity
        return DynBodyState(
            handle = handle,
            position = vec(center.x, center.y),
            angleRad = body.transform.rotationAngle,
            linearVelocity = vec(velocity.x, velocity.y),
            angularVelocity = body.angularVelocity,
            kind = kind
        )
    }

    companion object {
        /**
         * The vertex loops of [line], flattened into straight segments and ready for
         * [DynShapeDef.Concave] or [DynShapeDef.Polygon].
         *
         * The line goes through `java.awt.geom` as a shape and then through a
         * `FlatteningPathIterator`, which subdivides a curve until its control points are within
         * [tolerance] of the segments replacing it, and never more than [levels] deep. Each
         * subpath of the result is returned as its own loop, in the order drawn, with a duplicate
         * closing point dropped; a subpath of fewer than three points is discarded.
         *
         * A subpath that the path iterator never reported closed is kept as well — a dyn4j
         * polygon closes implicitly, so the vertices are the same either way, which is what
         * `InteriorCloseNodeTest.theDyn4jLoopFlattenerWasNeverActuallyBroken` asserts.
         *
         * The loops are not checked for convexity or for self-intersection. So a hand-drawn loop
         * belongs in [DynShapeDef.Concave], which decomposes it, rather than in
         * [DynShapeDef.Polygon], which requires convexity and reports a loop that is not.
         */
        fun flattenClosedFLineLoops(
            line: FLine,
            tolerance: Float = 0.75f,
            levels: Int = 10
        ): List<List<Vec2>> {
            val shape = FLinesAndJavaShapes.flineToJavaShape_notThickened(line)
            var iterator: PathIterator = shape.getPathIterator(AffineTransform.getTranslateInstance(0.0, 0.0))
            iterator = FlatteningPathIterator(iterator, tolerance.toDouble(), levels)

            val out = ArrayList<List<Vec2>>()
            val coords = DoubleArray(6)
            val current = ArrayList<Vec2>()
            var hasOpenPath = false
            var startX = 0.0
            var startY = 0.0

            fun flush(requireClosed: Boolean) {
                if (current.size < 3) {
                    current.clear()
                    return
                }

                val points = ArrayList<Vec2>(current)
                val first = points.first()
                val last = points.last()
                val closed = near(first.x.toDouble(), last.x.toDouble()) && near(first.y.toDouble(), last.y.toDouble())
                if (requireClosed && !closed) {
                    current.clear()
                    return
                }

                if (closed) {
                    points.removeAt(points.lastIndex)
                }
                if (points.size >= 3) {
                    out.add(points)
                }
                current.clear()
            }

            while (!iterator.isDone) {
                when (iterator.currentSegment(coords)) {
                    PathIterator.SEG_MOVETO -> {
                        flush(requireClosed = false)
                        current.clear()
                        startX = coords[0]
                        startY = coords[1]
                        current += vec(startX, startY)
                        hasOpenPath = true
                    }

                    PathIterator.SEG_LINETO -> {
                        if (hasOpenPath) {
                            current += vec(coords[0], coords[1])
                        }
                    }

                    PathIterator.SEG_CLOSE -> {
                        if (hasOpenPath) {
                            current += vec(startX, startY)
                            flush(requireClosed = true)
                        }
                        hasOpenPath = false
                    }
                }
                iterator.next()
            }

            flush(requireClosed = false)
            return out
        }

        private fun near(a: Double, b: Double, eps: Double = 1.0e-6): Boolean {
            return abs(a - b) <= eps
        }
    }
}
