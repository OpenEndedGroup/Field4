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

enum class DynBodyKind {
    STATIC,
    DYNAMIC
}

sealed interface DynShapeDef {
    data class Box(val width: Double, val height: Double) : DynShapeDef
    data class Circle(val radius: Double) : DynShapeDef
    data class Polygon(val vertices: List<Vec2>) : DynShapeDef
    data class Concave(val vertices: List<Vec2>) : DynShapeDef
}

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

data class DynBodyHandle(val id: Long)

data class DynBodyState(
    val handle: DynBodyHandle,
    val position: Vec2,
    val angleRad: Double,
    val linearVelocity: Vec2,
    val angularVelocity: Double,
    val kind: DynBodyKind
)

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

    fun removeBody(handle: DynBodyHandle): Boolean {
        val removed = bodyRecords.remove(handle.id) ?: return false
        return world.removeBody(removed.body)
    }

    fun clearBodies() {
        val records = bodyRecords.values.toList()
        bodyRecords.clear()
        for (record in records) {
            world.removeBody(record.body)
        }
    }

    fun getBodyState(handle: DynBodyHandle): DynBodyState? {
        val record = bodyRecords[handle.id] ?: return null
        return record.toState()
    }

    fun allBodyStates(): List<DynBodyState> {
        return bodyRecords.values.map { it.toState() }
    }

    fun setBodyTransform(handle: DynBodyHandle, x: Double, y: Double, angleRad: Double) {
        val body = bodyRecords[handle.id]?.body ?: return
        body.transform.setTranslation(x, y)
        body.transform.setRotation(angleRad)
        body.setAtRest(false)
    }

    fun setBodyVelocity(handle: DynBodyHandle, vx: Double, vy: Double, angular: Double) {
        val body = bodyRecords[handle.id]?.body ?: return
        body.setLinearVelocity(vx, vy)
        body.angularVelocity = angular
        body.setAtRest(false)
    }

    fun applyForce(handle: DynBodyHandle, fx: Double, fy: Double) {
        val body = bodyRecords[handle.id]?.body ?: return
        body.applyForce(DynVec2(fx, fy))
        body.setAtRest(false)
    }

    fun applyImpulse(handle: DynBodyHandle, ix: Double, iy: Double) {
        val body = bodyRecords[handle.id]?.body ?: return
        body.applyImpulse(DynVec2(ix, iy))
        body.setAtRest(false)
    }

    fun applyTorque(handle: DynBodyHandle, torque: Double) {
        val body = bodyRecords[handle.id]?.body ?: return
        body.applyTorque(torque)
        body.setAtRest(false)
    }

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
