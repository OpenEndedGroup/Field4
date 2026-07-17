package afield.four.physics

import afield.RunsAt
import afield.newRoot
import afield.registerValuePrinter
import afield.graphics.FLine
import afield.stdlib.Quat
import afield.stdlib.Vec3
import afield.stdlib.vec
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
import com.github.stephengold.joltjni.MassProperties
import com.github.stephengold.joltjni.TempAllocatorMalloc
import com.github.stephengold.joltjni.enumerate.EActivation
import com.github.stephengold.joltjni.enumerate.EMotionType
import com.github.stephengold.joltjni.enumerate.EOverrideMassProperties
import com.github.stephengold.joltjni.enumerate.EPhysicsUpdateError
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

sealed interface JoltShape {
    data class Cuboid(val width: Double, val height: Double, val depth: Double) : JoltShape
    data class Sphere(val radius: Double) : JoltShape
    data class Capsule(val radius: Double, val height: Double) : JoltShape
    data class Plane(
        val nx: Double = 0.0,
        val ny: Double = 1.0,
        val nz: Double = 0.0,
        val constant: Double = 0.0
    ) : JoltShape
}

data class JoltBodyDef(
    val name: String,
    val shape: JoltShape,
    val position: Vec3 = Vec3(),
    val rotation: Quat = Quat(),
    val dynamic: Boolean = true,
    val friction: Double = 0.6,
    val restitution: Double = 0.0,
    val linearDamping: Double = 0.0,
    val angularDamping: Double = 0.0,
    val linearVelocity: Vec3 = Vec3(),
    val angularVelocity: Vec3 = Vec3(),
    val mass: Double? = null,
    val density: Double? = null,
    val tags: Set<String> = emptySet()
) {
    @Deprecated("Use position", ReplaceWith("position.x.toDouble()"))
    val x: Double get() = position.x.toDouble()

    @Deprecated("Use position", ReplaceWith("position.y.toDouble()"))
    val y: Double get() = position.y.toDouble()

    @Deprecated("Use position", ReplaceWith("position.z.toDouble()"))
    val z: Double get() = position.z.toDouble()

    @Deprecated("Use linearVelocity", ReplaceWith("linearVelocity.x.toDouble()"))
    val vx: Double get() = linearVelocity.x.toDouble()

    @Deprecated("Use linearVelocity", ReplaceWith("linearVelocity.y.toDouble()"))
    val vy: Double get() = linearVelocity.y.toDouble()

    @Deprecated("Use linearVelocity", ReplaceWith("linearVelocity.z.toDouble()"))
    val vz: Double get() = linearVelocity.z.toDouble()

    @Deprecated("Use angularVelocity", ReplaceWith("angularVelocity.x.toDouble()"))
    val wx: Double get() = angularVelocity.x.toDouble()

    @Deprecated("Use angularVelocity", ReplaceWith("angularVelocity.y.toDouble()"))
    val wy: Double get() = angularVelocity.y.toDouble()

    @Deprecated("Use angularVelocity", ReplaceWith("angularVelocity.z.toDouble()"))
    val wz: Double get() = angularVelocity.z.toDouble()

    @Deprecated("Use Vector3f fields: position, linearVelocity, and angularVelocity.")
    constructor(
        name: String,
        shape: JoltShape,
        x: Double,
        y: Double,
        z: Double,
        rotation: Quat = Quat(),
        dynamic: Boolean = true,
        friction: Double = 0.6,
        restitution: Double = 0.0,
        linearDamping: Double = 0.0,
        angularDamping: Double = 0.0,
        vx: Double = 0.0,
        vy: Double = 0.0,
        vz: Double = 0.0,
        wx: Double = 0.0,
        wy: Double = 0.0,
        wz: Double = 0.0,
        mass: Double? = null,
        density: Double? = null,
        tags: Set<String> = emptySet()
    ) : this(
        name = name,
        shape = shape,
        position = vec(x, y, z),
        rotation = rotation,
        dynamic = dynamic,
        friction = friction,
        restitution = restitution,
        linearDamping = linearDamping,
        angularDamping = angularDamping,
        linearVelocity = vec(vx, vy, vz),
        angularVelocity = vec(wx, wy, wz),
        mass = mass,
        density = density,
        tags = tags
    )
}

class JoltBodyBuilder(
    var name: String,
    var shape: JoltShape? = null
) {
    var position: Vec3 = Vec3()
    var rotation: Quat = Quat()
    var dynamic: Boolean = true
    var friction: Double = 0.6
    var restitution: Double = 0.0
    var linearDamping: Double = 0.0
    var angularDamping: Double = 0.0
    var velocity: Vec3 = Vec3()
    var linearVelocity: Vec3
        get() = velocity
        set(value) {
            velocity = Vec3(value)
        }
    var angularVelocity: Vec3 = Vec3()
    var mass: Double? = null
    var density: Double? = null
    var tags: MutableSet<String> = LinkedHashSet()

    fun position(x: Number, y: Number, z: Number) {
        position = vec(x, y, z)
    }

    fun velocity(x: Number, y: Number, z: Number) {
        velocity = vec(x, y, z)
    }

    fun linearVelocity(x: Number, y: Number, z: Number) {
        velocity = vec(x, y, z)
    }

    fun angularVelocity(x: Number, y: Number, z: Number) {
        angularVelocity = vec(x, y, z)
    }

    fun tag(vararg values: String) {
        tags.addAll(values.filter { it.isNotBlank() })
    }

    fun tags(values: Iterable<String>) {
        tags.addAll(values.filter { it.isNotBlank() })
    }

    fun cuboid(width: Number, height: Number, depth: Number) {
        shape = JoltShape.Cuboid(width.toDouble(), height.toDouble(), depth.toDouble())
    }

    fun sphere(radius: Number) {
        shape = JoltShape.Sphere(radius.toDouble())
    }

    fun capsule(radius: Number, height: Number) {
        shape = JoltShape.Capsule(radius.toDouble(), height.toDouble())
    }

    fun plane(
        normal: Vec3 = vec(0, 1, 0),
        constant: Number = 0.0
    ) {
        shape = JoltShape.Plane(
            nx = normal.x.toDouble(),
            ny = normal.y.toDouble(),
            nz = normal.z.toDouble(),
            constant = constant.toDouble()
        )
        dynamic = false
    }

    fun planeComponents(
        nx: Number,
        ny: Number,
        nz: Number,
        constant: Number = 0.0
    ) {
        shape = JoltShape.Plane(nx.toDouble(), ny.toDouble(), nz.toDouble(), constant.toDouble())
        dynamic = false
    }

    fun build(): JoltBodyDef {
        val resolvedShape = shape ?: error("Jolt body '$name' needs a shape. Call cuboid(...), sphere(...), capsule(...), plane(...), or set shape.")
        return JoltBodyDef(
            name = name,
            shape = resolvedShape,
            position = Vec3(position),
            rotation = Quat(rotation),
            dynamic = dynamic,
            friction = friction,
            restitution = restitution,
            linearDamping = linearDamping,
            angularDamping = angularDamping,
            linearVelocity = Vec3(velocity),
            angularVelocity = Vec3(angularVelocity),
            mass = mass,
            density = density,
            tags = tags.toSet()
        )
    }
}

data class JoltBodyState(
    val name: String,
    val position: Vec3,
    val rotation: Quat,
    val linearVelocity: Vec3,
    val angularVelocity: Vec3,
    val bodyId: Int
)

data class JoltBodyPick(
    val name: String,
    val bodyId: Int,
    val queryPoint: Vec3,
    val surfacePoint: Vec3,
    val signedDistanceToSurface: Double
)

/**
 * Rigid-body physics world backed by the Jolt physics engine via the
 * stephengold jolt-jni bindings. This is the single entry point of the
 * `afield.four.physics` Jolt integration; the whole public surface
 * ([JoltShape], [JoltBodyDef], [JoltBodyState], [JoltBodyPick], the
 * [JoltBodyBuilder] DSL and this class) lives in this one file.
 *
 * Responsibilities:
 * - Create and remove named rigid bodies ([createBody] / [removeBody]).
 * - Advance the simulation and read back body states ([step]).
 * - Apply forces, impulses, torque, transforms and velocities.
 * - Point-near-surface picking ([pickBodyNearPoint]) and a kinematic
 *   rigid-grab workflow for interactive control ([pickAndBeginRigidGrab]).
 * - Emit debug wireframe [FLine]s with optional local axes ([debugWireLines3D]).
 *
 * Setup and constants:
 * - Native init is lazy inside the constructor via
 *   [JoltPhysicsBootstrap.ensureInitialized]; callers never bootstrap directly.
 * - Simulation limits are hardcoded in the private companion (MAX_BODIES,
 *   MAX_BODY_PAIRS, MAX_CONTACTS, mutex count).
 * - Collision filtering uses exactly two object layers, moving
 *   (OBJECT_LAYER_MOVING) and non-moving (OBJECT_LAYER_NON_MOVING); two
 *   non-moving bodies never collide.
 *
 * Naming migration: prefer the unprefixed names above. Deprecated `*3D`
 * typealiases (JoltShape3D, JoltBodyDef3D, JoltBodyState3D, JoltBodyPick3D,
 * JoltPhysicsWorld3D) and the older aliases (ShapeDef3D, BodyDef3D,
 * BodyState3D, BodyPick3D, SimplePhysics3D) are defined at the bottom of this
 * file and forward to the current types. [JoltBodyDef]'s x/y/z Double
 * secondary constructor is likewise @Deprecated — use `position: Vec3`
 * (e.g. `position = vec(0.0, 3.0, 0.0)`) or [JoltBodyBuilder]'s `position(x, y, z)`.
 *
 * Minimal usage:
 * ```kotlin
 * JoltPhysicsWorld().use { world ->
 *     world.createBody(JoltBodyDef(
 *         name = "ground",
 *         shape = JoltShape.Plane(ny = 1.0, constant = 0.0),
 *         position = vec(0.0, 0.0, 0.0),
 *         dynamic = false,
 *     ))
 *     world.cuboid("box", 1.0, 1.0, 1.0) { position(0.0, 3.0, 0.0) } // JoltBodyBuilder DSL
 *     repeat(120) { world.step(1.0 / 60.0) }
 * }
 * ```
 *
 * A worked example is in `afield.examples.JoltPhysicsExample`.
 */
class JoltPhysicsWorld(
    gravityX: Double = 0.0,
    gravityY: Double = -9.8,
    gravityZ: Double = 0.0
) : AutoCloseable {
    val bodyIdsByName: MutableMap<String, Int> = LinkedHashMap()
    val grabbedBodyName: String?
        get() = activeGrab?.name

    private val shapeByName = LinkedHashMap<String, JoltShape>()
    private val dynamicByName = HashMap<String, Boolean>()
    private val massByName = HashMap<String, Double>()
    private val tagsByName = HashMap<String, Set<String>>()
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

    fun createBody(def: JoltBodyDef): Int {
        removeBody(def.name)

        val isDynamic = def.dynamic && def.shape !is JoltShape.Plane
        val motionType = if (isDynamic) EMotionType.Dynamic else EMotionType.Static
        val objectLayer = if (isDynamic) OBJECT_LAYER_MOVING else OBJECT_LAYER_NON_MOVING

        val shape = buildShape(def.shape)
        val settings = BodyCreationSettings()
            .setShape(shape)
            .setMotionType(motionType)
            .setObjectLayer(objectLayer)
            .setPosition(def.position.x.toDouble(), def.position.y.toDouble(), def.position.z.toDouble())
            .setRotation(def.rotation.toJoltQuat())
            .setFriction(def.friction.toFloat())
            .setRestitution(def.restitution.toFloat())
            .setLinearDamping(def.linearDamping.toFloat())
            .setAngularDamping(def.angularDamping.toFloat())
            .setLinearVelocity(def.linearVelocity.x, def.linearVelocity.y, def.linearVelocity.z)
            .setAngularVelocity(def.angularVelocity.x, def.angularVelocity.y, def.angularVelocity.z)
        val effectiveMass = applyMassProperties(def, settings)

        val activation = if (isDynamic) EActivation.Activate else EActivation.DontActivate
        val bodyId = bodyInterface.createAndAddBody(settings, activation)
        check(bodyId >= 0) { "Failed to create body '${def.name}'." }

        bodyIdsByName[def.name] = bodyId
        shapeByName[def.name] = def.shape
        dynamicByName[def.name] = isDynamic
        tagsByName[def.name] = def.tags
        if (effectiveMass != null) {
            massByName[def.name] = effectiveMass
        }
        nameByBodyId[bodyId] = def.name

        return bodyId
    }

    fun body(
        name: String,
        shape: JoltShape? = null,
        configure: JoltBodyBuilder.() -> Unit = {}
    ): JoltBodyDef = JoltBodyBuilder(name, shape).apply(configure).build()

    fun createBody(
        name: String,
        shape: JoltShape? = null,
        configure: JoltBodyBuilder.() -> Unit = {}
    ): Int = createBody(body(name, shape, configure))

    fun cuboid(
        name: String,
        width: Number,
        height: Number,
        depth: Number,
        configure: JoltBodyBuilder.() -> Unit = {}
    ): Int = createBody(name, JoltShape.Cuboid(width.toDouble(), height.toDouble(), depth.toDouble()), configure)

    fun sphere(
        name: String,
        radius: Number,
        configure: JoltBodyBuilder.() -> Unit = {}
    ): Int = createBody(name, JoltShape.Sphere(radius.toDouble()), configure)

    fun capsule(
        name: String,
        radius: Number,
        height: Number,
        configure: JoltBodyBuilder.() -> Unit = {}
    ): Int = createBody(name, JoltShape.Capsule(radius.toDouble(), height.toDouble()), configure)

    fun plane(
        name: String,
        normal: Vec3 = vec(0, 1, 0),
        constant: Number = 0.0,
        configure: JoltBodyBuilder.() -> Unit = {}
    ): Int = createBody(name, JoltShape.Plane(normal.x.toDouble(), normal.y.toDouble(), normal.z.toDouble(), constant.toDouble())) {
        dynamic = false
        configure()
    }

    fun planeComponents(
        name: String,
        nx: Number,
        ny: Number,
        nz: Number,
        constant: Number = 0.0,
        configure: JoltBodyBuilder.() -> Unit = {}
    ): Int = createBody(name, JoltShape.Plane(nx.toDouble(), ny.toDouble(), nz.toDouble(), constant.toDouble())) {
        dynamic = false
        configure()
    }

    fun removeBody(name: String): Boolean {
        val bodyId = bodyIdsByName.remove(name) ?: return false
        if (activeGrab?.name == name) {
            activeGrab = null
        }

        shapeByName.remove(name)
        dynamicByName.remove(name)
        massByName.remove(name)
        tagsByName.remove(name)
        nameByBodyId.remove(bodyId)

        if (bodyInterface.isAdded(bodyId)) {
            bodyInterface.removeBody(bodyId)
        }
        bodyInterface.destroyBody(bodyId)
        return true
    }

    fun isDynamic(name: String): Boolean = dynamicByName[name] == true

    fun mass(name: String): Double? = massByName[name]

    fun tags(name: String): Set<String> = tagsByName[name].orEmpty()

    fun activateBody(name: String): Boolean {
        val bodyId = bodyIdsByName[name] ?: return false
        if (!bodyInterface.isAdded(bodyId)) return false
        bodyInterface.activateBody(bodyId)
        return true
    }

    fun activateBodies(
        dynamicOnly: Boolean = true,
        includeTagRegex: Regex? = null,
        excludeTagRegex: Regex? = null
    ): Int {
        var activated = 0
        for ((name, bodyId) in bodyIdsByName) {
            if (!bodyInterface.isAdded(bodyId)) continue
            if (!shouldMatchBody(name, dynamicOnly, includeTagRegex, excludeTagRegex)) continue
            bodyInterface.activateBody(bodyId)
            activated++
        }
        return activated
    }

    fun clearBodies() {
        activeGrab = null
        if (bodyIdsByName.isNotEmpty()) {
            physicsSystem.destroyAllBodies()
        }
        bodyIdsByName.clear()
        shapeByName.clear()
        dynamicByName.clear()
        massByName.clear()
        tagsByName.clear()
        nameByBodyId.clear()
    }

    fun step(dt: Double): List<JoltBodyState> {
        require(dt > 0.0) { "dt must be > 0, got $dt" }
        applyActiveGrabTarget(dt.toFloat())

        val updateError = physicsSystem.update(dt.toFloat(), 1, tempAllocator, jobSystem)
        check(updateError == EPhysicsUpdateError.None) {
            "Jolt update failed with error code: $updateError"
        }

        return states()
    }

    fun states(): List<JoltBodyState> {
        return bodyIdsByName.mapNotNull { (name, bodyId) ->
            if (!bodyInterface.isAdded(bodyId)) return@mapNotNull null

            val position = bodyInterface.getPosition(bodyId).toVec3f()
            val rotation = bodyInterface.getRotation(bodyId).toQuatf()
            val linearVelocity = bodyInterface.getLinearVelocity(bodyId).toVec3f()
            val angularVelocity = bodyInterface.getAngularVelocity(bodyId).toVec3f()

            JoltBodyState(
                name = name,
                position = position,
                rotation = rotation,
                linearVelocity = linearVelocity,
                angularVelocity = angularVelocity,
                bodyId = bodyId
            )
        }
    }

    fun applyForce(name: String, force: Vec3) {
        applyForce(name, force.x.toDouble(), force.y.toDouble(), force.z.toDouble())
    }

    fun applyForce(name: String, fx: Double, fy: Double, fz: Double) {
        val bodyId = requireBodyId(name)
        bodyInterface.addForce(bodyId, JoltVec3(fx.toFloat(), fy.toFloat(), fz.toFloat()))
        if (dynamicByName[name] == true) {
            bodyInterface.activateBody(bodyId)
        }
    }

    fun applyImpulse(name: String, impulse: Vec3) {
        applyImpulse(name, impulse.x.toDouble(), impulse.y.toDouble(), impulse.z.toDouble())
    }

    fun applyImpulse(name: String, ix: Double, iy: Double, iz: Double) {
        val bodyId = requireBodyId(name)
        bodyInterface.addImpulse(bodyId, JoltVec3(ix.toFloat(), iy.toFloat(), iz.toFloat()))
        if (dynamicByName[name] == true) {
            bodyInterface.activateBody(bodyId)
        }
    }

    fun applyTorque(name: String, torque: Vec3) {
        applyTorque(name, torque.x.toDouble(), torque.y.toDouble(), torque.z.toDouble())
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

    fun setBodyVelocity(name: String, linear: Vec3, angular: Vec3 = Vec3()) {
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
    ): JoltBodyPick? {
        require(maxOutsideDistance >= 0.0) { "maxOutsideDistance must be >= 0, got $maxOutsideDistance" }

        var bestPick: JoltBodyPick? = null
        var bestAbsDistance = Double.POSITIVE_INFINITY

        for ((name, bodyId) in bodyIdsByName) {
            if (!bodyInterface.isAdded(bodyId)) continue
            if (!includeStatic && dynamicByName[name] != true) continue

            val shape = shapeByName[name] ?: continue
            if (shape is JoltShape.Plane) continue

            val position = bodyInterface.getPosition(bodyId).toVec3f()
            val rotation = bodyInterface.getRotation(bodyId).toQuatf()
            val query = surfaceQuery(shape, point, position, rotation) ?: continue
            if (query.signedDistance > maxOutsideDistance) continue

            val absDistance = abs(query.signedDistance)
            if (absDistance < bestAbsDistance) {
                bestAbsDistance = absDistance
                bestPick = JoltBodyPick(
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
    ): JoltBodyPick? {
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
        axisLength: Float = 0f,
        dynamicOnly: Boolean = false,
        includeTagRegex: Regex? = null,
        excludeTagRegex: Regex? = null
    ): List<FLine> {
        val lines = ArrayList<FLine>(bodyIdsByName.size * 4)
        val sphereSegs = sphereSegments.coerceAtLeast(8)
        val capsuleSegs = capsuleSegments.coerceAtLeast(8)

        for ((name, bodyId) in bodyIdsByName) {
            if (!bodyInterface.isAdded(bodyId)) continue
            if (!shouldMatchBody(name, dynamicOnly, includeTagRegex, excludeTagRegex)) continue

            val shape = shapeByName[name] ?: continue
            val position = bodyInterface.getPosition(bodyId).toVec3f()
            val rotation = bodyInterface.getRotation(bodyId).toQuatf()

            val localShapeLines = when (shape) {
                is JoltShape.Cuboid -> wireCuboid(shape.width, shape.height, shape.depth)
                is JoltShape.Sphere -> wireSphereRings(shape.radius, sphereSegs)
                is JoltShape.Capsule -> wireCapsule(shape.radius, shape.height, capsuleSegs)
                is JoltShape.Plane -> wirePlanePatch(
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

    private fun shouldMatchBody(
        name: String,
        dynamicOnly: Boolean,
        includeTagRegex: Regex?,
        excludeTagRegex: Regex?
    ): Boolean {
        if (dynamicOnly && dynamicByName[name] != true) return false

        val tags = tagsByName[name].orEmpty()
        if (includeTagRegex != null && tags.none { includeTagRegex.containsMatchIn(it) }) {
            return false
        }
        if (excludeTagRegex != null && tags.any { excludeTagRegex.containsMatchIn(it) }) {
            return false
        }

        return true
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

    private fun buildShape(shape: JoltShape) = when (shape) {
        is JoltShape.Cuboid -> {
            require(shape.width > 0.0 && shape.height > 0.0 && shape.depth > 0.0) {
                "Cuboid dimensions must be > 0, got $shape"
            }
            BoxShape(
                (shape.width * 0.5).toFloat(),
                (shape.height * 0.5).toFloat(),
                (shape.depth * 0.5).toFloat()
            )
        }

        is JoltShape.Sphere -> {
            require(shape.radius > 0.0) { "Sphere radius must be > 0, got ${shape.radius}" }
            SphereShape(shape.radius.toFloat())
        }

        is JoltShape.Capsule -> {
            require(shape.radius > 0.0) { "Capsule radius must be > 0, got ${shape.radius}" }
            require(shape.height > 0.0) { "Capsule height must be > 0, got ${shape.height}" }
            val halfHeightCylinder = max(0.0, (shape.height - 2.0 * shape.radius) * 0.5)
            CapsuleShape(halfHeightCylinder.toFloat(), shape.radius.toFloat())
        }

        is JoltShape.Plane -> {
            val normal = JoltVec3(shape.nx.toFloat(), shape.ny.toFloat(), shape.nz.toFloat())
            val plane = JoltPlane(normal, shape.constant.toFloat())
            PlaneShape(plane)
        }
    }

    private fun applyMassProperties(def: JoltBodyDef, settings: BodyCreationSettings): Double? {
        require(def.mass == null || def.density == null) {
            "Body '${def.name}' cannot set both mass and density."
        }
        if (def.mass == null && def.density == null) return null
        require(def.shape !is JoltShape.Plane) {
            "Body '${def.name}' is a plane; mass/density are only valid for finite dynamic shapes."
        }

        val effectiveMass = def.mass ?: (shapeVolume(def.shape) * def.density!!)
        require(effectiveMass > 0.0 && effectiveMass.isFinite()) {
            "Body '${def.name}' mass must be finite and > 0, got $effectiveMass."
        }

        val massProperties = MassProperties().setMass(effectiveMass.toFloat())
        settings
            .setOverrideMassProperties(EOverrideMassProperties.CalculateInertia)
            .setMassPropertiesOverride(massProperties)

        return effectiveMass
    }

    private fun shapeVolume(shape: JoltShape): Double = when (shape) {
        is JoltShape.Cuboid -> shape.width * shape.height * shape.depth
        is JoltShape.Sphere -> (4.0 / 3.0) * PI * shape.radius * shape.radius * shape.radius
        is JoltShape.Capsule -> {
            val cylinderHeight = max(0.0, shape.height - 2.0 * shape.radius)
            PI * shape.radius * shape.radius * cylinderHeight +
                (4.0 / 3.0) * PI * shape.radius * shape.radius * shape.radius
        }
        is JoltShape.Plane -> Double.POSITIVE_INFINITY
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
        shape: JoltShape,
        pointWorld: Vec3,
        bodyPosition: Vec3,
        bodyRotation: Quat
    ): SurfaceQuery? {
        val localPoint = Quat(bodyRotation)
            .conjugate()
            .transform(Vec3(pointWorld).sub(bodyPosition))

        val localQuery = when (shape) {
            is JoltShape.Cuboid -> cuboidSurfaceQuery(shape, localPoint)
            is JoltShape.Sphere -> sphereSurfaceQuery(shape, localPoint)
            is JoltShape.Capsule -> capsuleSurfaceQuery(shape, localPoint)
            is JoltShape.Plane -> null
        } ?: return null

        val worldSurfacePoint = Quat(bodyRotation)
            .transform(localQuery.worldSurfacePoint)
            .add(bodyPosition)
        return SurfaceQuery(
            signedDistance = localQuery.signedDistance,
            worldSurfacePoint = worldSurfacePoint
        )
    }

    private fun cuboidSurfaceQuery(shape: JoltShape.Cuboid, localPoint: Vec3): SurfaceQuery {
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

    private fun sphereSurfaceQuery(shape: JoltShape.Sphere, localPoint: Vec3): SurfaceQuery {
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

    private fun capsuleSurfaceQuery(shape: JoltShape.Capsule, localPoint: Vec3): SurfaceQuery {
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

private fun JoltPhysicsWorld.summaryHtml(): String {
    val states = states()
    val dynamicCount = states.count { state -> isDynamic(state.name) }
    val grabbed = grabbedBodyName
    val sample = states.take(6).joinToString("") { state ->
        val mass = mass(state.name)?.let { " m=${fmtNumber(it)}" } ?: ""
        val tags = tags(state.name).takeIf { it.isNotEmpty() }
            ?.joinToString(prefix = " tags=", separator = ",") { escHtml(it) }
            ?: ""
        "<tr>" +
            "<td style='padding-right:8px;color:#8f8'>'${escHtml(state.name)}'</td>" +
            "<td style='padding-right:8px;color:#8cf'>${fmtVec(state.position)}</td>" +
            "<td style='color:#888'>v=${fmtVecPlain(state.linearVelocity)}$mass$tags</td>" +
        "</tr>"
    }
    val overflow = if (states.size > 6) {
        "<tr><td colspan='3' style='color:#666'>...${states.size - 6} more</td></tr>"
    } else {
        ""
    }

    return "<div style='font-family:\"Iosevka Web\",monospace;font-size:12px;color:#ccc;line-height:1.4'>" +
        "<div><span style='color:#cc8'>JoltPhysicsWorld</span> " +
        "<span style='color:#888'>${states.size} bodies, $dynamicCount dynamic</span>" +
        (if (grabbed != null) " <span style='color:#fc8'>grabbed '${escHtml(grabbed)}'</span>" else "") +
        "</div>" +
        "<table style='border-collapse:collapse;margin-top:3px;font-size:11px'>$sample$overflow</table>" +
        "</div>"
}

private fun JoltBodyState.summaryHtml(): String =
    "<div style='font-family:\"Iosevka Web\",monospace;font-size:12px;color:#ccc;line-height:1.4'>" +
        "<span style='color:#cc8'>JoltBodyState</span> <span style='color:#8f8'>'${escHtml(name)}'</span> " +
        "<span style='color:#888'>id=$bodyId</span><br/>" +
        "<span style='color:#888'>p=</span>${fmtVec(position)} " +
        "<span style='color:#888'>v=</span>${fmtVec(linearVelocity)} " +
        "<span style='color:#888'>w=</span>${fmtVec(angularVelocity)}" +
    "</div>"

private fun JoltBodyPick.summaryHtml(): String =
    "<div style='font-family:\"Iosevka Web\",monospace;font-size:12px;color:#ccc;line-height:1.4'>" +
        "<span style='color:#cc8'>JoltBodyPick</span> <span style='color:#8f8'>'${escHtml(name)}'</span> " +
        "<span style='color:#888'>id=$bodyId d=${fmtNumber(signedDistanceToSurface)}</span><br/>" +
        "<span style='color:#888'>query=</span>${fmtVec(queryPoint)} " +
        "<span style='color:#888'>surface=</span>${fmtVec(surfacePoint)}" +
    "</div>"

private fun fmtVec(v: Vec3): String =
    "<span style='color:#8cf'>(${fmtNumber(v.x.toDouble())}, ${fmtNumber(v.y.toDouble())}, ${fmtNumber(v.z.toDouble())})</span>"

private fun fmtVecPlain(v: Vec3): String =
    "(${fmtNumber(v.x.toDouble())}, ${fmtNumber(v.y.toDouble())}, ${fmtNumber(v.z.toDouble())})"

private fun fmtNumber(value: Double): String =
    "%.3f".format(java.util.Locale.US, value).trimEnd('0').trimEnd('.')

private fun escHtml(text: String): String =
    text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

@RunsAt(newRoot)
fun registerJoltPhysicsVisualizers() {
    registerValuePrinter<JoltPhysicsWorld> { it.summaryHtml() }
    registerValuePrinter<JoltBodyState> { it.summaryHtml() }
    registerValuePrinter<JoltBodyPick> { it.summaryHtml() }
}

private fun Vec3.toJoltVec3(): JoltVec3 = JoltVec3(x, y, z)

private fun Vec3.toJoltRVec3(): JoltRVec3 = JoltRVec3(x.toDouble(), y.toDouble(), z.toDouble())

private fun Quat.toJoltQuat(): JoltQuat = JoltQuat(x, y, z, w)

private fun JoltVec3.toVec3f(): Vec3 = Vec3(getX(), getY(), getZ())

private fun JoltRVec3.toVec3f(): Vec3 = Vec3(xx().toFloat(), yy().toFloat(), zz().toFloat())

private fun JoltQuat.toQuatf(): Quat = Quat(getX(), getY(), getZ(), getW())

@Deprecated("Use JoltShape", ReplaceWith("JoltShape"))
typealias JoltShape3D = JoltShape

@Deprecated("Use JoltBodyDef", ReplaceWith("JoltBodyDef"))
typealias JoltBodyDef3D = JoltBodyDef

@Deprecated("Use JoltBodyState", ReplaceWith("JoltBodyState"))
typealias JoltBodyState3D = JoltBodyState

@Deprecated("Use JoltBodyPick", ReplaceWith("JoltBodyPick"))
typealias JoltBodyPick3D = JoltBodyPick

@Deprecated("Use JoltPhysicsWorld", ReplaceWith("JoltPhysicsWorld"))
typealias JoltPhysicsWorld3D = JoltPhysicsWorld

@Deprecated("Use JoltShape", ReplaceWith("JoltShape"))
typealias ShapeDef3D = JoltShape

@Deprecated("Use JoltBodyDef", ReplaceWith("JoltBodyDef"))
typealias BodyDef3D = JoltBodyDef

@Deprecated("Use JoltBodyState", ReplaceWith("JoltBodyState"))
typealias BodyState3D = JoltBodyState

@Deprecated("Use JoltBodyPick", ReplaceWith("JoltBodyPick"))
typealias BodyPick3D = JoltBodyPick

@Deprecated("Use JoltPhysicsWorld", ReplaceWith("JoltPhysicsWorld"))
typealias SimplePhysics3D = JoltPhysicsWorld
