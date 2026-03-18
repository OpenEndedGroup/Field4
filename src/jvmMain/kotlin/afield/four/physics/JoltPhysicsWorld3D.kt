package afield.four.physics

import afield.graphics.FLine
import afield.stdlib.Quat
import afield.stdlib.Vec3
import com.github.stephengold.joltjni.BodyCreationSettings
import com.github.stephengold.joltjni.BodyInterface
import com.github.stephengold.joltjni.BroadPhaseLayerInterfaceTable
import com.github.stephengold.joltjni.JobSystemThreadPool
import com.github.stephengold.joltjni.Jolt
import com.github.stephengold.joltjni.ObjectLayerPairFilterTable
import com.github.stephengold.joltjni.ObjectVsBroadPhaseLayerFilterTable
import com.github.stephengold.joltjni.PhysicsSystem
import com.github.stephengold.joltjni.Plane as JoltPlane
import com.github.stephengold.joltjni.PlaneShape
import com.github.stephengold.joltjni.Quat as JoltQuat
import com.github.stephengold.joltjni.RVec3 as JoltRVec3
import com.github.stephengold.joltjni.Vec3 as JoltVec3
import com.github.stephengold.joltjni.BoxShape
import com.github.stephengold.joltjni.SphereShape
import com.github.stephengold.joltjni.CapsuleShape
import com.github.stephengold.joltjni.TempAllocatorMalloc
import com.github.stephengold.joltjni.enumerate.EActivation
import com.github.stephengold.joltjni.enumerate.EMotionType
import com.github.stephengold.joltjni.enumerate.EPhysicsUpdateError
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

sealed interface JoltShape3D {
    data class Cuboid(val width: Double, val height: Double, val depth: Double) : JoltShape3D
    data class Sphere(val radius: Double) : JoltShape3D
    data class Capsule(val radius: Double, val height: Double) : JoltShape3D
    data class Plane(
        val nx: Double = 0.0,
        val ny: Double = 1.0,
        val nz: Double = 0.0,
        val constant: Double = 0.0
    ) : JoltShape3D
}

data class JoltBodyDef3D(
    val name: String,
    val shape: JoltShape3D,
    val x: Double,
    val y: Double,
    val z: Double,
    val rotation: Quat = Quat(),
    val dynamic: Boolean = true,
    val friction: Double = 0.6,
    val restitution: Double = 0.0,
    val linearDamping: Double = 0.0,
    val angularDamping: Double = 0.0,
    val vx: Double = 0.0,
    val vy: Double = 0.0,
    val vz: Double = 0.0,
    val wx: Double = 0.0,
    val wy: Double = 0.0,
    val wz: Double = 0.0
)

data class JoltBodyState3D(
    val name: String,
    val position: Vec3,
    val rotation: Quat,
    val linearVelocity: Vec3,
    val angularVelocity: Vec3,
    val bodyId: Int
)

data class JoltBodyPick3D(
    val name: String,
    val bodyId: Int,
    val queryPoint: Vec3,
    val surfacePoint: Vec3,
    val signedDistanceToSurface: Double
)

class JoltPhysicsWorld3D(
    gravityX: Double = 0.0,
    gravityY: Double = -9.8,
    gravityZ: Double = 0.0
) : AutoCloseable {
    val bodyIdsByName: MutableMap<String, Int> = LinkedHashMap()
    val grabbedBodyName: String?
        get() = activeGrab?.name

    private val shapeByName = LinkedHashMap<String, JoltShape3D>()
    private val dynamicByName = HashMap<String, Boolean>()
    private val nameByBodyId = HashMap<Int, String>()
    private var activeGrab: ActiveGrab? = null

    private val physicsSystem: PhysicsSystem
    private val bodyInterface: BodyInterface
    private val tempAllocator: TempAllocatorMalloc
    private val jobSystem: JobSystemThreadPool

    private data class Pose3D(val position: Vec3, val rotation: Quat)

    private data class ActiveGrab(
        val name: String,
        val bodyId: Int,
        val originalMotionType: EMotionType,
        val controllerToBodyPosition: Vec3,
        val controllerToBodyRotation: Quat,
        var controllerPose: Pose3D,
        var previousTargetPose: Pose3D? = null,
        var releaseLinearVelocity: Vec3 = Vec3(),
        var releaseAngularVelocity: Vec3 = Vec3()
    )

    private data class SurfaceQuery(
        val signedDistance: Double,
        val worldSurfacePoint: Vec3
    )

    init {
        JoltPhysicsBootstrap.ensureInitialized()
        physicsSystem = createPhysicsSystem()
        bodyInterface = physicsSystem.getBodyInterface()
        physicsSystem.setGravity(gravityX.toFloat(), gravityY.toFloat(), gravityZ.toFloat())
        tempAllocator = TempAllocatorMalloc()
        val workerThreads = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        jobSystem = JobSystemThreadPool(Jolt.cMaxPhysicsJobs, Jolt.cMaxPhysicsBarriers, workerThreads)
    }

    fun createBody(def: JoltBodyDef3D): Int {
        check(def.name !in bodyIdsByName) { "Body with name '${def.name}' already exists." }

        val isDynamic = def.dynamic && def.shape !is JoltShape3D.Plane
        val motionType = if (isDynamic) EMotionType.Dynamic else EMotionType.Static
        val objectLayer = if (isDynamic) OBJECT_LAYER_MOVING else OBJECT_LAYER_NON_MOVING

        val shape = buildShape(def.shape)
        val settings = BodyCreationSettings()
            .setShape(shape)
            .setMotionType(motionType)
            .setObjectLayer(objectLayer)
            .setPosition(def.x, def.y, def.z)
            .setRotation(def.rotation.toJoltQuat())
            .setFriction(def.friction.toFloat())
            .setRestitution(def.restitution.toFloat())
            .setLinearDamping(def.linearDamping.toFloat())
            .setAngularDamping(def.angularDamping.toFloat())
            .setLinearVelocity(def.vx.toFloat(), def.vy.toFloat(), def.vz.toFloat())
            .setAngularVelocity(def.wx.toFloat(), def.wy.toFloat(), def.wz.toFloat())

        val activation = if (isDynamic) EActivation.Activate else EActivation.DontActivate
        val bodyId = bodyInterface.createAndAddBody(settings, activation)
        check(bodyId >= 0) { "Failed to create body '${def.name}'." }

        bodyIdsByName[def.name] = bodyId
        shapeByName[def.name] = def.shape
        dynamicByName[def.name] = isDynamic
        nameByBodyId[bodyId] = def.name

        return bodyId
    }

    fun removeBody(name: String): Boolean {
        val bodyId = bodyIdsByName.remove(name) ?: return false
        if (activeGrab?.name == name) {
            activeGrab = null
        }

        shapeByName.remove(name)
        dynamicByName.remove(name)
        nameByBodyId.remove(bodyId)

        if (bodyInterface.isAdded(bodyId)) {
            bodyInterface.removeBody(bodyId)
        }
        bodyInterface.destroyBody(bodyId)
        return true
    }

    fun clearBodies() {
        activeGrab = null
        if (bodyIdsByName.isNotEmpty()) {
            physicsSystem.destroyAllBodies()
        }
        bodyIdsByName.clear()
        shapeByName.clear()
        dynamicByName.clear()
        nameByBodyId.clear()
    }

    fun step(dt: Double): List<JoltBodyState3D> {
        require(dt > 0.0) { "dt must be > 0, got $dt" }
        applyActiveGrabTarget(dt.toFloat())

        val updateError = physicsSystem.update(dt.toFloat(), 1, tempAllocator, jobSystem)
        check(updateError == EPhysicsUpdateError.None) {
            "Jolt update failed with error code: $updateError"
        }

        return bodyIdsByName.mapNotNull { (name, bodyId) ->
            if (!bodyInterface.isAdded(bodyId)) return@mapNotNull null

            val position = bodyInterface.getPosition(bodyId).toVec3f()
            val rotation = bodyInterface.getRotation(bodyId).toQuatf()
            val linearVelocity = bodyInterface.getLinearVelocity(bodyId).toVec3f()
            val angularVelocity = bodyInterface.getAngularVelocity(bodyId).toVec3f()

            JoltBodyState3D(
                name = name,
                position = position,
                rotation = rotation,
                linearVelocity = linearVelocity,
                angularVelocity = angularVelocity,
                bodyId = bodyId
            )
        }
    }

    fun applyForce(name: String, fx: Double, fy: Double, fz: Double) {
        val bodyId = requireBodyId(name)
        bodyInterface.addForce(bodyId, JoltVec3(fx.toFloat(), fy.toFloat(), fz.toFloat()))
        if (dynamicByName[name] == true) {
            bodyInterface.activateBody(bodyId)
        }
    }

    fun applyImpulse(name: String, ix: Double, iy: Double, iz: Double) {
        val bodyId = requireBodyId(name)
        bodyInterface.addImpulse(bodyId, JoltVec3(ix.toFloat(), iy.toFloat(), iz.toFloat()))
        if (dynamicByName[name] == true) {
            bodyInterface.activateBody(bodyId)
        }
    }

    fun applyTorque(name: String, tx: Double, ty: Double, tz: Double) {
        val bodyId = requireBodyId(name)
        bodyInterface.addTorque(bodyId, JoltVec3(tx.toFloat(), ty.toFloat(), tz.toFloat()))
        if (dynamicByName[name] == true) {
            bodyInterface.activateBody(bodyId)
        }
    }

    fun setBodyTransform(name: String, position: Vec3, rotation: Quat) {
        val bodyId = requireBodyId(name)
        val activation = if (dynamicByName[name] == true) EActivation.Activate else EActivation.DontActivate
        bodyInterface.setPositionAndRotation(
            bodyId,
            position.toJoltRVec3(),
            rotation.toJoltQuat(),
            activation
        )
    }

    fun setBodyVelocity(name: String, linear: Vec3, angular: Vec3) {
        val bodyId = requireBodyId(name)
        bodyInterface.setLinearAndAngularVelocity(
            bodyId,
            linear.toJoltVec3(),
            angular.toJoltVec3()
        )
        if (dynamicByName[name] == true) {
            bodyInterface.activateBody(bodyId)
        }
    }

    fun pickBodyNearPoint(
        point: Vec3,
        maxOutsideDistance: Double = 0.08,
        includeStatic: Boolean = false
    ): JoltBodyPick3D? {
        require(maxOutsideDistance >= 0.0) { "maxOutsideDistance must be >= 0, got $maxOutsideDistance" }

        var bestPick: JoltBodyPick3D? = null
        var bestAbsDistance = Double.POSITIVE_INFINITY

        for ((name, bodyId) in bodyIdsByName) {
            if (!bodyInterface.isAdded(bodyId)) continue
            if (!includeStatic && dynamicByName[name] != true) continue

            val shape = shapeByName[name] ?: continue
            if (shape is JoltShape3D.Plane) continue

            val position = bodyInterface.getPosition(bodyId).toVec3f()
            val rotation = bodyInterface.getRotation(bodyId).toQuatf()
            val query = surfaceQuery(shape, point, position, rotation) ?: continue
            if (query.signedDistance > maxOutsideDistance) continue

            val absDistance = abs(query.signedDistance)
            if (absDistance < bestAbsDistance) {
                bestAbsDistance = absDistance
                bestPick = JoltBodyPick3D(
                    name = name,
                    bodyId = bodyId,
                    queryPoint = Vec3(point),
                    surfacePoint = query.worldSurfacePoint,
                    signedDistanceToSurface = query.signedDistance
                )
            }
        }

        return bestPick
    }

    fun beginRigidGrab(name: String, controllerPosition: Vec3, controllerRotation: Quat): Boolean {
        val bodyId = bodyIdsByName[name] ?: return false
        if (dynamicByName[name] != true) return false
        if (!bodyInterface.isAdded(bodyId)) return false

        releaseRigidGrab()

        val controllerPose = Pose3D(Vec3(controllerPosition), Quat(controllerRotation))
        val bodyPosition = bodyInterface.getPosition(bodyId).toVec3f()
        val bodyRotation = bodyInterface.getRotation(bodyId).toQuatf()

        val invControllerRotation = Quat(controllerPose.rotation).conjugate()
        val controllerToBodyPosition =
            invControllerRotation.transform(Vec3(bodyPosition).sub(controllerPose.position))
        val controllerToBodyRotation = invControllerRotation.mul(bodyRotation, Quat()).normalize()

        val originalMotionType = bodyInterface.getMotionType(bodyId)
        bodyInterface.setMotionType(bodyId, EMotionType.Kinematic, EActivation.Activate)

        val grab = ActiveGrab(
            name = name,
            bodyId = bodyId,
            originalMotionType = originalMotionType,
            controllerToBodyPosition = controllerToBodyPosition,
            controllerToBodyRotation = controllerToBodyRotation,
            controllerPose = controllerPose
        )
        val targetPose = composeBodyPose(
            controllerPose = controllerPose,
            controllerToBodyPosition = controllerToBodyPosition,
            controllerToBodyRotation = controllerToBodyRotation
        )
        grab.previousTargetPose = targetPose
        bodyInterface.setPositionAndRotation(
            bodyId,
            targetPose.position.toJoltRVec3(),
            targetPose.rotation.toJoltQuat(),
            EActivation.Activate
        )
        activeGrab = grab

        return true
    }

    fun pickAndBeginRigidGrab(
        queryPoint: Vec3,
        controllerPosition: Vec3,
        controllerRotation: Quat,
        maxOutsideDistance: Double = 0.08
    ): JoltBodyPick3D? {
        val pick = pickBodyNearPoint(
            point = queryPoint,
            maxOutsideDistance = maxOutsideDistance,
            includeStatic = false
        ) ?: return null
        if (!beginRigidGrab(pick.name, controllerPosition, controllerRotation)) {
            return null
        }
        return pick
    }

    fun updateRigidGrabTransform(controllerPosition: Vec3, controllerRotation: Quat): Boolean {
        val grab = activeGrab ?: return false
        grab.controllerPose = Pose3D(Vec3(controllerPosition), Quat(controllerRotation))
        return true
    }

    fun releaseRigidGrab(): String? {
        val grab = activeGrab ?: return null
        activeGrab = null
        if (!bodyInterface.isAdded(grab.bodyId)) {
            return grab.name
        }

        bodyInterface.setMotionType(grab.bodyId, grab.originalMotionType, EActivation.Activate)
        if (grab.originalMotionType == EMotionType.Dynamic) {
            bodyInterface.setLinearAndAngularVelocity(
                grab.bodyId,
                grab.releaseLinearVelocity.toJoltVec3(),
                grab.releaseAngularVelocity.toJoltVec3()
            )
            bodyInterface.activateBody(grab.bodyId)
        }

        return grab.name
    }

    fun debugWireLines3D(
        sphereSegments: Int = 24,
        capsuleSegments: Int = 24,
        axisLength: Float = 0f
    ): List<FLine> {
        val lines = ArrayList<FLine>(bodyIdsByName.size * 4)
        val sphereSegs = sphereSegments.coerceAtLeast(8)
        val capsuleSegs = capsuleSegments.coerceAtLeast(8)

        for ((name, bodyId) in bodyIdsByName) {
            if (!bodyInterface.isAdded(bodyId)) continue

            val shape = shapeByName[name] ?: continue
            val position = bodyInterface.getPosition(bodyId).toVec3f()
            val rotation = bodyInterface.getRotation(bodyId).toQuatf()

            val localShapeLines = when (shape) {
                is JoltShape3D.Cuboid -> wireCuboid(shape.width, shape.height, shape.depth)
                is JoltShape3D.Sphere -> wireSphereRings(shape.radius, sphereSegs)
                is JoltShape3D.Capsule -> wireCapsule(shape.radius, shape.height, capsuleSegs)
                is JoltShape3D.Plane -> wirePlanePatch(
                    shape.nx,
                    shape.ny,
                    shape.nz,
                    shape.constant
                )
            }

            val worldShapeLines = transformLines(localShapeLines, position, rotation)
            lines += colorizeLines(worldShapeLines, colorForBodyName(name))

            if (axisLength > 0f) {
                lines += transformLines(wireAxes(axisLength), position, rotation)
            }
        }

        return lines
    }

    override fun close() {
        clearBodies()
        jobSystem.close()
        tempAllocator.close()
        physicsSystem.close()
    }

    private fun requireBodyId(name: String): Int {
        return bodyIdsByName[name] ?: error("No body named '$name'.")
    }

    private fun createPhysicsSystem(): PhysicsSystem {
        val ovoFilter = ObjectLayerPairFilterTable(NUM_OBJECT_LAYERS).apply {
            enableCollision(OBJECT_LAYER_MOVING, OBJECT_LAYER_MOVING)
            enableCollision(OBJECT_LAYER_MOVING, OBJECT_LAYER_NON_MOVING)
            disableCollision(OBJECT_LAYER_NON_MOVING, OBJECT_LAYER_NON_MOVING)
        }

        val layerMap = BroadPhaseLayerInterfaceTable(NUM_OBJECT_LAYERS, NUM_BROADPHASE_LAYERS).apply {
            mapObjectToBroadPhaseLayer(OBJECT_LAYER_MOVING, 0)
            mapObjectToBroadPhaseLayer(OBJECT_LAYER_NON_MOVING, 0)
        }

        val ovbFilter = ObjectVsBroadPhaseLayerFilterTable(
            layerMap,
            NUM_BROADPHASE_LAYERS,
            ovoFilter,
            NUM_OBJECT_LAYERS
        )

        return PhysicsSystem().init(
            MAX_BODIES,
            NUM_BODY_MUTEXES,
            MAX_BODY_PAIRS,
            MAX_CONTACTS,
            layerMap,
            ovbFilter,
            ovoFilter
        )
    }

    private fun buildShape(shape: JoltShape3D) = when (shape) {
        is JoltShape3D.Cuboid -> {
            require(shape.width > 0.0 && shape.height > 0.0 && shape.depth > 0.0) {
                "Cuboid dimensions must be > 0, got $shape"
            }
            BoxShape(
                (shape.width * 0.5).toFloat(),
                (shape.height * 0.5).toFloat(),
                (shape.depth * 0.5).toFloat()
            )
        }

        is JoltShape3D.Sphere -> {
            require(shape.radius > 0.0) { "Sphere radius must be > 0, got ${shape.radius}" }
            SphereShape(shape.radius.toFloat())
        }

        is JoltShape3D.Capsule -> {
            require(shape.radius > 0.0) { "Capsule radius must be > 0, got ${shape.radius}" }
            require(shape.height > 0.0) { "Capsule height must be > 0, got ${shape.height}" }
            val halfHeightCylinder = max(0.0, (shape.height - 2.0 * shape.radius) * 0.5)
            CapsuleShape(halfHeightCylinder.toFloat(), shape.radius.toFloat())
        }

        is JoltShape3D.Plane -> {
            val normal = JoltVec3(shape.nx.toFloat(), shape.ny.toFloat(), shape.nz.toFloat())
            val plane = JoltPlane(normal, shape.constant.toFloat())
            PlaneShape(plane)
        }
    }

    private fun applyActiveGrabTarget(dt: Float) {
        val grab = activeGrab ?: return
        if (!bodyInterface.isAdded(grab.bodyId)) {
            activeGrab = null
            return
        }

        val targetPose = composeBodyPose(
            controllerPose = grab.controllerPose,
            controllerToBodyPosition = grab.controllerToBodyPosition,
            controllerToBodyRotation = grab.controllerToBodyRotation
        )
        val previousPose = grab.previousTargetPose
        if (previousPose != null) {
            val invDt = 1f / dt
            grab.releaseLinearVelocity = Vec3(targetPose.position).sub(previousPose.position).mul(invDt)
            grab.releaseAngularVelocity = angularVelocityBetween(
                previousRotation = previousPose.rotation,
                currentRotation = targetPose.rotation,
                dt = dt
            )
        }

        bodyInterface.moveKinematic(
            grab.bodyId,
            targetPose.position.toJoltRVec3(),
            targetPose.rotation.toJoltQuat(),
            dt
        )
        grab.previousTargetPose = targetPose
    }

    private fun composeBodyPose(
        controllerPose: Pose3D,
        controllerToBodyPosition: Vec3,
        controllerToBodyRotation: Quat
    ): Pose3D {
        val worldPosition = Quat(controllerPose.rotation)
            .transform(Vec3(controllerToBodyPosition))
            .add(controllerPose.position)
        val worldRotation = Quat(controllerPose.rotation)
            .mul(controllerToBodyRotation, Quat())
            .normalize()

        return Pose3D(worldPosition, worldRotation)
    }

    private fun angularVelocityBetween(previousRotation: Quat, currentRotation: Quat, dt: Float): Vec3 {
        val delta = Quat(previousRotation)
            .conjugate()
            .mul(currentRotation, Quat())
            .normalize()

        var qx = delta.x
        var qy = delta.y
        var qz = delta.z
        var qw = delta.w
        if (qw < 0f) {
            qx = -qx
            qy = -qy
            qz = -qz
            qw = -qw
        }

        val clampedW = qw.coerceIn(-1f, 1f).toDouble()
        val halfAngle = acos(clampedW)
        val sinHalf = sqrt(max(0.0, 1.0 - clampedW * clampedW))
        if (sinHalf < 1e-6) {
            return Vec3()
        }

        val angle = (2.0 * halfAngle).coerceIn(-PI, PI)
        val scale = (angle / dt.toDouble()).toFloat()
        return Vec3(
            (qx / sinHalf).toFloat() * scale,
            (qy / sinHalf).toFloat() * scale,
            (qz / sinHalf).toFloat() * scale
        )
    }

    private fun surfaceQuery(
        shape: JoltShape3D,
        pointWorld: Vec3,
        bodyPosition: Vec3,
        bodyRotation: Quat
    ): SurfaceQuery? {
        val localPoint = Quat(bodyRotation)
            .conjugate()
            .transform(Vec3(pointWorld).sub(bodyPosition))

        val localQuery = when (shape) {
            is JoltShape3D.Cuboid -> cuboidSurfaceQuery(shape, localPoint)
            is JoltShape3D.Sphere -> sphereSurfaceQuery(shape, localPoint)
            is JoltShape3D.Capsule -> capsuleSurfaceQuery(shape, localPoint)
            is JoltShape3D.Plane -> null
        } ?: return null

        val worldSurfacePoint = Quat(bodyRotation)
            .transform(localQuery.worldSurfacePoint)
            .add(bodyPosition)
        return SurfaceQuery(
            signedDistance = localQuery.signedDistance,
            worldSurfacePoint = worldSurfacePoint
        )
    }

    private fun cuboidSurfaceQuery(shape: JoltShape3D.Cuboid, localPoint: Vec3): SurfaceQuery {
        val hx = (shape.width * 0.5).toFloat()
        val hy = (shape.height * 0.5).toFloat()
        val hz = (shape.depth * 0.5).toFloat()

        val clamped = Vec3(
            localPoint.x.coerceIn(-hx, hx),
            localPoint.y.coerceIn(-hy, hy),
            localPoint.z.coerceIn(-hz, hz)
        )
        val outside = Vec3(localPoint).sub(clamped)
        val outsideLength = outside.length()
        if (outsideLength > 1e-6f) {
            return SurfaceQuery(
                signedDistance = outsideLength.toDouble(),
                worldSurfacePoint = clamped
            )
        }

        val dx = hx - abs(localPoint.x)
        val dy = hy - abs(localPoint.y)
        val dz = hz - abs(localPoint.z)
        val surface = Vec3(localPoint)
        val insideDistance = if (dx <= dy && dx <= dz) {
            surface.x = if (localPoint.x >= 0f) hx else -hx
            dx
        } else if (dy <= dz) {
            surface.y = if (localPoint.y >= 0f) hy else -hy
            dy
        } else {
            surface.z = if (localPoint.z >= 0f) hz else -hz
            dz
        }

        return SurfaceQuery(
            signedDistance = -insideDistance.toDouble(),
            worldSurfacePoint = surface
        )
    }

    private fun sphereSurfaceQuery(shape: JoltShape3D.Sphere, localPoint: Vec3): SurfaceQuery {
        val radius = shape.radius.toFloat()
        val distance = localPoint.length()
        val surface = if (distance > 1e-6f) {
            Vec3(localPoint).mul(radius / distance)
        } else {
            Vec3(radius, 0f, 0f)
        }

        return SurfaceQuery(
            signedDistance = (distance - radius).toDouble(),
            worldSurfacePoint = surface
        )
    }

    private fun capsuleSurfaceQuery(shape: JoltShape3D.Capsule, localPoint: Vec3): SurfaceQuery {
        val radius = shape.radius.toFloat()
        val halfHeightCylinder = max(0.0, (shape.height - 2.0 * shape.radius) * 0.5).toFloat()
        val axisPoint = Vec3(0f, localPoint.y.coerceIn(-halfHeightCylinder, halfHeightCylinder), 0f)
        val radial = Vec3(localPoint).sub(axisPoint)
        val radialLength = radial.length()

        val surface = if (radialLength > 1e-6f) {
            Vec3(axisPoint).add(radial.mul(radius / radialLength))
        } else {
            Vec3(axisPoint).add(radius, 0f, 0f)
        }

        return SurfaceQuery(
            signedDistance = (radialLength - radius).toDouble(),
            worldSurfacePoint = surface
        )
    }

    private companion object {
        const val OBJECT_LAYER_MOVING = 0
        const val OBJECT_LAYER_NON_MOVING = 1
        const val NUM_OBJECT_LAYERS = 2
        const val NUM_BROADPHASE_LAYERS = 1

        const val MAX_BODIES = 50_000
        const val NUM_BODY_MUTEXES = 0
        const val MAX_BODY_PAIRS = 65_536
        const val MAX_CONTACTS = 20_480
    }
}

private fun Vec3.toJoltVec3(): JoltVec3 = JoltVec3(x, y, z)

private fun Vec3.toJoltRVec3(): JoltRVec3 = JoltRVec3(x.toDouble(), y.toDouble(), z.toDouble())

private fun Quat.toJoltQuat(): JoltQuat = JoltQuat(x, y, z, w)

private fun JoltVec3.toVec3f(): Vec3 = Vec3(getX(), getY(), getZ())

private fun JoltRVec3.toVec3f(): Vec3 = Vec3(xx().toFloat(), yy().toFloat(), zz().toFloat())

private fun JoltQuat.toQuatf(): Quat = Quat(getX(), getY(), getZ(), getW())

@Deprecated("Use JoltShape3D", ReplaceWith("JoltShape3D"))
typealias ShapeDef3D = JoltShape3D

@Deprecated("Use JoltBodyDef3D", ReplaceWith("JoltBodyDef3D"))
typealias BodyDef3D = JoltBodyDef3D

@Deprecated("Use JoltBodyState3D", ReplaceWith("JoltBodyState3D"))
typealias BodyState3D = JoltBodyState3D

@Deprecated("Use JoltBodyPick3D", ReplaceWith("JoltBodyPick3D"))
typealias BodyPick3D = JoltBodyPick3D

@Deprecated("Use JoltPhysicsWorld3D", ReplaceWith("JoltPhysicsWorld3D"))
typealias SimplePhysics3D = JoltPhysicsWorld3D
