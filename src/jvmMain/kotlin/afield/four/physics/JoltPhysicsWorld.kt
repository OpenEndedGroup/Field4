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
import afield.md
import afield.Table
import afield.FieldMarkdown
import afield.CellRole

/**
 * Rigid bodies in three dimensions, from the Jolt physics engine: the 3D counterpart to
 * [Dyn4jScene], and a different tool from [SimdVerlet], which has no notion of a rigid body at all.
 *
 * A body here is a **name**. `createBody` takes a [JoltBodyDef] carrying a string name, a
 * [JoltShape] and a pose, and everything afterwards addresses that body by the same string:
 * `applyImpulse("crate", …)`, `mass("crate")`, `removeBody("ground")`. Names are unique, and
 * creating a body over a name that already exists removes the old one first, so re-running a box
 * that builds a scene rebuilds it rather than stacking a second copy on top. `step` hands back one
 * [JoltBodyState] per body, named the same way.
 *
 * Nothing from the jolt-jni bindings reaches any of those signatures. Shapes, poses and velocities
 * are Field's own `Vec3` and `Quat`, the four cases of [JoltShape] are the whole shape vocabulary,
 * and the conversions both ways are private at the bottom of this file. The native library loads
 * itself on the first world built ([JoltPhysicsBootstrap]).
 *
 * Two facts about what counts as static: a `JoltShape.Plane` is static whatever its `dynamic` flag
 * says, since an infinite plane has no volume to take a mass from, and collision filtering has
 * exactly two object layers, moving and non-moving, with the non-moving pair disabled. So "static"
 * means either `dynamic = false` or a plane, and nothing static is ever tested against anything
 * else static.
 *
 * `pickBodyNearPoint` asks Jolt nothing. It walks the named bodies and solves point-to-surface in
 * closed form for a cuboid, a sphere and a capsule, so the distance it reports is *signed*, with
 * negative meaning the query point is inside the body. Planes are skipped, which is usually what
 * you want: a click near the ground finds the crate on it and not the ground. `pickAndBeginRigidGrab`
 * is that search and `beginRigidGrab` in one call, for a controller that has just closed.
 *
 * The wireframes `debugWireLines3D` returns are built in `JoltDebugDrawing.kt`, one set of `FLine`s
 * per shape kind. Evaluating a world, a [JoltBodyState] or a [JoltBodyPick] in a box shows a
 * formatted summary instead of a `toString`, which `registerJoltPhysicsVisualizers` installs.
 *
 * The `*3D`-suffixed typealiases at the bottom of the file, and [JoltBodyDef]'s `x`/`y`/`z`
 * secondary constructor, are an earlier spelling of all of this and are deprecated in place; new
 * code uses the unprefixed names and `position: Vec3`.
 */

/**
 * The four collision shapes a body can have: a cuboid, a sphere, a capsule or an infinite plane.
 *
 * Every dimension is given the way you would measure the object, not the way Jolt stores it.
 * `Cuboid` takes its full width, height and depth; `Capsule` takes a radius and the **total**
 * end-to-end height, so a capsule whose height is twice its radius has no cylinder left in the
 * middle and is a sphere. Halving is this file's job, not the caller's.
 *
 * `Plane` is the odd one. It is a normal and a constant rather than an extent, it covers everything
 * on the far side of itself, and it is always static: the default of `ny = 1`, `constant = 0` is
 * the ground at `y = 0`, solid below. Being infinite, it has no volume, which is why it takes no
 * mass and why `pickBodyNearPoint` leaves it out.
 */
sealed interface JoltShape {
    /** A box of these full extents, centred on the body's position. */
    data class Cuboid(val width: Double, val height: Double, val depth: Double) : JoltShape

    /** A sphere of this radius, centred on the body's position. */
    data class Sphere(val radius: Double) : JoltShape

    /**
     * A capsule of this radius, standing on the body's local Y axis.
     *
     * [height] is the whole thing end to end, caps included, so the cylinder in the middle is
     * `height - 2 * radius` long and is clamped at zero rather than allowed to go negative.
     */
    data class Capsule(val radius: Double, val height: Double) : JoltShape

    /**
     * The half-space `nx * x + ny * y + nz * z + constant <= 0`, whose surface is an infinite plane.
     *
     * The defaults are the ground plane at `y = 0` with solid below it, which is what most scenes
     * want for a floor and what a body's `dynamic` flag cannot override.
     */
    data class Plane(
        val nx: Double = 0.0,
        val ny: Double = 1.0,
        val nz: Double = 0.0,
        val constant: Double = 0.0
    ) : JoltShape
}

/**
 * Everything needed to create one named rigid body, and what `createBody` takes.
 *
 * [name] is the handle for the rest of the body's life, and it is what makes the body unique:
 * creating another body under the same name replaces this one. Give exactly one of [mass] and
 * [density], or neither and let Jolt derive the mass from the shape; asking for both is an error,
 * and so is asking for either on a plane. A [density] is turned into a mass through the shape's own
 * analytic volume before it reaches Jolt.
 *
 * [tags] are free strings that go nowhere near the simulation. They are what `activateBodies` and
 * `debugWireLines3D` filter on, by regex, so a scene can wake or draw a subset of itself without
 * keeping a second list of names.
 *
 * [JoltBodyBuilder] is the same thing with `var`s and shape helpers, for when the call reads better
 * as a block than as a long list of named arguments.
 */
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

/**
 * Builds a [JoltBodyDef] a statement at a time, which is what the `world.cuboid(…) { … }` helpers
 * configure.
 *
 * Everything [JoltBodyDef] holds is a `var` here, plus two conveniences: `position(x, y, z)` and
 * friends take loose numbers instead of a vector, and `cuboid`/`sphere`/`capsule`/`plane` set the
 * shape by name. `plane` and `planeComponents` also set `dynamic = false`, matching the fact that a
 * plane is static however it is asked for.
 *
 * `build` is what turns it into a def, and it fails if no shape was ever set. The vectors are copied
 * on the way out, so holding on to a builder and building twice gives two independent defs.
 */
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

/**
 * One body's pose and velocity, read back out of Jolt: what `step` and `states` return, one per body.
 *
 * A snapshot rather than a view. The vectors are fresh copies of what Jolt held at the moment of the
 * read, so a list of these from one step can be kept and compared against the next one.
 *
 * [bodyId] is Jolt's own integer handle. It is here because the debug and picking paths carry it
 * around, but the rest of this file is addressed by [name] and so should anything built on it be:
 * an id is only good until that body is removed, whereas the name survives being recreated.
 */
data class JoltBodyState(
    val name: String,
    val position: Vec3,
    val rotation: Quat,
    val linearVelocity: Vec3,
    val angularVelocity: Vec3,
    val bodyId: Int
)

/**
 * The body `pickBodyNearPoint` chose, and how far outside it the query point was.
 *
 * [signedDistanceToSurface] is negative when [queryPoint] is inside the body, zero on its surface
 * and positive outside, and it is the quantity the search minimises in absolute value: the winner is
 * the body whose surface the point is nearest to, inside or out, not the body whose centre is
 * closest. [surfacePoint] is the point on that surface, in world space.
 */
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
    /**
     * Every body in the world, name to Jolt id, in creation order.
     *
     * Public and mutable because it is also the world's iteration order and its membership test, and
     * reading it is how you enumerate a scene. Writing to it does not create or destroy anything, so
     * an entry added by hand names a body that is not there.
     */
    val bodyIdsByName: MutableMap<String, Int> = LinkedHashMap()

    /** The name of the body currently held by a rigid grab, or null if nothing is held. */
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

    /**
     * Creates the body [def] describes, replacing any body already using its name, and returns
     * Jolt's id for it.
     *
     * The replacement is unconditional: the old body is removed before the new one is built, so a
     * box that defines a scene can be re-run without clearing it first. A dynamic body is created
     * activated and a static one asleep, and a plane is static however [def] asked.
     */
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

    /**
     * Describes a body through a [JoltBodyBuilder] block, without creating it.
     *
     * The entry point of the block-shaped half of this API: the `createBody(name) { … }` overload is
     * this followed by `createBody`, and `cuboid`, `sphere`, `capsule`, `plane` and
     * `planeComponents` are that again with the shape already chosen. Use this one to build a def
     * you want to keep, name differently, or create more than once.
     */
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

    /**
     * Removes the body called [name], and reports whether there was one.
     *
     * If this was the grabbed body the grab is dropped rather than released: the body is about to be
     * destroyed, so there is no motion type to restore and no velocity to hand it. Its shape, mass
     * and tags are forgotten here too, which is why `mass` and `tags` answer for live bodies only.
     */
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

    /**
     * Whether [name] is a body being moved by the simulation.
     *
     * False for a static body, for a plane whatever it asked for, and for a name that is not a body
     * at all, which are three different situations reported the same way.
     */
    fun isDynamic(name: String): Boolean = dynamicByName[name] == true

    /**
     * The mass [name] was given, or null if it was left to Jolt.
     *
     * This answers with what was asked for, not with what Jolt is using: a body created with neither
     * a mass nor a density has a mass derived from its shape, and that number is not read back here.
     * A density is reported as the mass it worked out to.
     */
    fun mass(name: String): Double? = massByName[name]

    /** The tags [name] was created with, or an empty set for an untagged or unknown body. */
    fun tags(name: String): Set<String> = tagsByName[name].orEmpty()

    /**
     * Wakes one body by name, and reports whether there was one to wake.
     *
     * False for a name that is not a body and for a body not currently added to the world. Waking a
     * body that is already awake, or one that is static, is allowed and does nothing worth noticing;
     * `activateBodies` is the filtered version for a whole scene.
     */
    fun activateBody(name: String): Boolean {
        val bodyId = bodyIdsByName[name] ?: return false
        if (!bodyInterface.isAdded(bodyId)) return false
        bodyInterface.activateBody(bodyId)
        return true
    }

    /**
     * Wakes a chosen set of bodies and returns how many were woken.
     *
     * Jolt puts a body that has come to rest to sleep, and a sleeping body does not notice that the
     * world changed underneath it. Moving a static body by hand is the case that needs this: the
     * floor moves, nothing resting on it is being simulated, and the scene stays wrong until
     * something wakes it. [JoltCameraFrustumPlanes] calls this for exactly that reason.
     *
     * [includeTagRegex] keeps a body only if one of its tags matches; [excludeTagRegex] drops a body
     * if any of them does. Both are substring matches rather than anchored ones, and a body carrying
     * no tags passes an include filter only if there is none.
     */
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

    /**
     * Destroys every body, leaving an empty world that is still usable.
     *
     * The Jolt system, allocator and job threads survive, so this is the cheap way to rebuild a
     * scene from scratch without paying for a new world. Any live grab is forgotten.
     */
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

    /**
     * Advances the world by [dt] seconds and returns every body's new state.
     *
     * One Jolt collision step per call, so [dt] is the whole step and not a budget to be subdivided:
     * a large one is a large one, with the tunnelling that implies. Calling this at a fixed [dt]
     * from a frame loop, rather than at the measured frame time, is what makes a scene repeatable.
     *
     * A grabbed body is moved onto its target pose first, so the grab is resolved against the same
     * step the rest of the world sees. A Jolt update that reports an error throws.
     */
    fun step(dt: Double): List<JoltBodyState> {
        require(dt > 0.0) { "dt must be > 0, got $dt" }
        applyActiveGrabTarget(dt.toFloat())

        val updateError = physicsSystem.update(dt.toFloat(), 1, tempAllocator, jobSystem)
        check(updateError == EPhysicsUpdateError.None) {
            "Jolt update failed with error code: $updateError"
        }

        return states()
    }

    /**
     * Reads every body's pose and velocity without advancing anything.
     *
     * In creation order, which puts a body recreated under an existing name at the end rather than
     * back where it was. A body that is not currently added to the world is skipped, so the list can
     * be shorter than the name map. `step` ends by calling this.
     */
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

    /**
     * Pushes on a body, at its centre of mass, for the length of the next `step`.
     *
     * A force, not an impulse: Jolt clears it once the step has consumed it, so a sustained push is
     * this call every frame and a single call is a one-frame nudge. The body is woken if it is
     * dynamic, and an unknown name is an error rather than a no-op.
     */
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

    /**
     * Changes a body's momentum at once, rather than over a step.
     *
     * The call for a kick, a hit or a throw, where what you know is the velocity change you want
     * and not the force that would produce it. Independent of `dt`, which is what makes it the right
     * one for an event: a force applied for one frame is worth a different amount at a different
     * frame rate, and an impulse is not.
     */
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

    /**
     * Twists a body for the length of the next `step`, on the same terms as `applyForce`.
     *
     * Cleared after the step, so a sustained spin is a per-frame call. There is no torque impulse
     * here; for an instant change of spin, `setBodyVelocity` carries the angular part.
     */
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

    /**
     * Teleports a body: sets its pose outright, with no motion between where it was and where it
     * lands.
     *
     * Velocity is left alone, so a body moving at the moment you do this carries on moving from its
     * new place. Nothing sweeps the path, so a teleport through a wall lands on the far side of it.
     * A dynamic body is woken; a static one is not, which is the case `activateBodies` exists for:
     * move a static floor this way and whatever was asleep on it does not fall.
     */
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

    /**
     * Sets both velocities outright, replacing whatever the body had.
     *
     * [angular] defaults to zero, so the two-argument call stops a body spinning as well as
     * redirecting it. Wakes a dynamic body. On a static body this sets a velocity that nothing
     * reads, since a static body is not integrated.
     */
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

    /**
     * Finds the body whose surface [point] is nearest to, or null if nothing is within
     * [maxOutsideDistance] of it.
     *
     * Solved here rather than by Jolt, in closed form against the cuboid, sphere and capsule cases
     * of [JoltShape]: planes are never candidates, so a point just above the floor picks what is
     * standing on it. Static bodies are skipped too unless [includeStatic] is set.
     *
     * [maxOutsideDistance] only bounds how far *outside* a body the point may be. A point well
     * inside one is always a candidate, however deep, so a controller buried in a large body still
     * picks it; and where two candidates overlap, the one with the nearer surface wins rather than
     * the one the point is further inside.
     */
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

    /**
     * Attaches the dynamic body [name] rigidly to a controller at this pose, and reports whether it
     * took.
     *
     * The body's offset and twist relative to the controller are measured once, here, and held for
     * the life of the grab, so the body keeps the relationship it had at the moment of grabbing
     * rather than snapping to the controller. It is switched to kinematic for the duration, which
     * means it drives the bodies it meets and is not pushed back by them.
     *
     * At most one grab is live: beginning a second one releases the first. Fails, and changes
     * nothing, on a name that is not a body, on a static body, or on a body removed from the world.
     *
     * Then it is `updateRigidGrabTransform` every time the controller moves and `releaseRigidGrab`
     * when it opens; `step` is what actually moves the body.
     */
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

    /**
     * `pickBodyNearPoint` and `beginRigidGrab` in one call: grabs whatever is nearest [queryPoint],
     * and returns what it found.
     *
     * Null means nothing was picked, or that what was picked could not be grabbed; in neither case
     * does it disturb a grab that was already live. Static bodies are excluded from the search,
     * since a grab needs a dynamic body.
     * [controllerPosition] is separate from [queryPoint] so that a hand can reach for something
     * beside it rather than only for what it has already touched.
     */
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

    /**
     * Tells a live grab where the controller is now, and reports whether there was one to tell.
     *
     * Cheap and not a simulation call: it records the pose and returns. The body moves on the next
     * `step`, which is also where the release velocity is measured, so calling this more than once
     * between steps just overwrites the target.
     */
    fun updateRigidGrabTransform(controllerPosition: Vec3, controllerRotation: Quat): Boolean {
        val grab = activeGrab ?: return false
        grab.controllerPose = Pose3D(Vec3(controllerPosition), Quat(controllerRotation))
        return true
    }

    /**
     * Lets go, returning the name of the body released, or null if nothing was held.
     *
     * The body goes back to the motion type it had before the grab, and if that is dynamic it is
     * handed the velocity the grab itself was moving at: linear and angular, differenced from the
     * last two target poses `step` computed. That is what makes a thrown object carry on rather than
     * drop, and it means the throw is only as good as the controller's last two updates.
     */
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

    /**
     * Draws the collision shapes as wireframe `FLine`s, in world space, one colour per body name.
     *
     * These are the shapes Jolt is simulating, not the shapes anything is rendering, so a body whose
     * visible mesh and whose collision cuboid disagree shows up here immediately. The colour is a
     * hash of the name, floored away from black, so it is stable across runs and two bodies with the
     * same name draw the same.
     *
     * An infinite plane is drawn as a finite square patch around the point on it closest to the
     * origin, with a stub along its normal. [axisLength] above zero adds red, green and blue local
     * axes per body. [dynamicOnly] and the two tag regexes narrow which bodies are drawn, on the
     * same terms as `activateBodies`.
     */
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

    /**
     * Releases the native side of the world: its bodies, job threads, temporary allocator and
     * physics system, in that order.
     *
     * All of it is native memory and native threads, and this is the point at which they go back
     * deterministically rather than whenever jolt-jni's cleaner gets to them. Prefer
     * `JoltPhysicsWorld().use { … }`. It leaves the world unusable rather than empty, so
     * `clearBodies` is the one to reach for between scenes.
     */
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
    // maxRows does the truncation Table already knows how to report, so the hand-written
    // "...N more" row goes away; `position` is HTML from fmtVec, so that column is formatted.
    val bodies = Table(listOf("body", "position", "state"))
        .rows(
            states.map { state ->
                val mass = mass(state.name)?.let { " m=${fmtNumber(it)}" } ?: ""
                val tags = tags(state.name).takeIf { it.isNotEmpty() }
                    ?.joinToString(prefix = " tags=", separator = ",") { it }
                    ?: ""
                listOf("'${state.name}'", fmtVec(state.position), "v=${fmtVecPlain(state.linearVelocity)}$mass$tags")
            }
        )
        .format("position") { it?.toString() ?: "" }
        .role("state", CellRole.MUTED)
    bodies.maxRows = 6

    return FieldMarkdown.card(
        """
        {accent:JoltPhysicsWorld} {muted:${states.size} bodies, $dynamicCount dynamic}""" +
            (if (grabbed != null) """ {warn:grabbed '${grabbed.md}'}""" else "") + """

        ${bodies.toHTML()}
        """
    )
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
