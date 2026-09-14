package afield.four.audio.steamaudio

import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Maps a 2D game world onto the 3D world Steam Audio simulates in.
 *
 * Steam Audio is always a 3D simulation -- there is no 2D mode -- so a 2D game's geometry has
 * to be given a height. This type fixes the convention, so walls, sources and the listener all
 * agree.
 *
 * ### The game frame this assumes
 *
 * The standard 2D convention: **geometry lies in the xy-plane**, `+x` is right and `+y` is up
 * the screen, `+z` points out of the screen, and the listener **translates in xy and rotates
 * about z**. Both ears therefore lie in the plane of the geometry, and the top of the head
 * points along `+z`. The frame is right-handed: `x cross y = z`.
 *
 * ### The mapping, and why it is not the obvious one
 *
 * Game `(gx, gy, gz)` becomes world `(gx, gz, -gy)`.
 *
 * Steam Audio's frame is `+x` right, `+y` **up**, `-z` ahead. The game's up axis is `+z`, so
 * that has to become world `+y` -- which leaves only one choice for the remaining axis if the
 * mapping is to stay right-handed: game `+y` must map to world **`-z`**, not `+z`.
 *
 * The obvious-looking mapping `(gx, gy) -> (gx, height, gy)` is orientation-**reversing** under
 * these conventions, and it mirrors the listener's ears. That failure is quiet: levels, reverb
 * and distance all stay correct, and only left and right are swapped. [World2DHandednessTest]
 * exists to catch exactly this.
 *
 * So:
 *
 * | game | world |
 * |---|---|
 * | `+x` right | `+x` |
 * | `+y` up the screen | `-z` (ahead, in Steam Audio terms) |
 * | `+z` out of the screen | `+y` (up) |
 *
 * A listener facing game `+y` has a world `ahead` of `(0, 0, -1)`. Use [listenerFacing] and
 * [point] rather than converting by hand.
 *
 * ### Why walls need a height
 *
 * A 2D game's walls are line segments. Extruded along game `+z` they become quads -- but quads
 * alone form an **open-ended box**, and rays escape through the gap, which quietly destroys the
 * reverb. So [WallSet] caps the space at both ends by default. If a room sounds far deader
 * than its materials suggest, a missing cap is the first thing to check.
 *
 * ### One consequence worth designing around
 *
 * With both ears in the plane of the geometry, every source is at zero elevation. The HRTF's
 * elevation dimension goes unused, and **front/back confusion becomes the dominant
 * localisation error**: a source directly ahead and one directly behind produce almost
 * identical interaural time and level differences, which is why the cave demo measures roughly
 * 0 dB for both. Real listeners resolve this with small involuntary head movements. If the
 * player's facing turns in discrete steps, or the camera is fixed, that cue is absent and
 * sources behind the listener will often be heard as in front.
 *
 * Practical mitigations: let facing rotate smoothly rather than snapping, lean on
 * distinguishing reverb and occlusion rather than direction alone, and consider a deliberate
 * timbral cue for what is behind.
 *
 * @property floorDepth game z of one cap -- the "floor" of the extruded space.
 * @property ceilingDepth game z of the other cap. The gap between the two is the acoustic
 *   thickness of the space, and it matters: a taller extrusion reverberates longer than a
 *   shallow one of the same footprint.
 * @property earDepth game z at which the listener's ears and the sources sit, normally midway.
 */
public data class World2D(
    public val floorDepth: Float = -1.75f,
    public val ceilingDepth: Float = 1.75f,
    public val earDepth: Float = 0f,
) {
    init {
        require(ceilingDepth > floorDepth) {
            "ceilingDepth ($ceilingDepth) must be greater than floorDepth ($floorDepth)"
        }
        require(earDepth in floorDepth..ceilingDepth) {
            "earDepth ($earDepth) must lie between floorDepth and ceilingDepth"
        }
    }

    /** The acoustic thickness of the extruded space. */
    public val thickness: Float get() = ceilingDepth - floorDepth

    /** World y of the floor cap. */
    internal val floorWorldY: Float get() = floorDepth

    /** World y of the ceiling cap. */
    internal val ceilingWorldY: Float get() = ceilingDepth

    /** Converts a 2D game point to a world position at [earDepth]. */
    public fun point(x: Float, y: Float): Vector3 = Vector3(x, earDepth, -y)

    /** Converts a game point with an explicit z to a world position. */
    public fun point(x: Float, y: Float, z: Float): Vector3 = Vector3(x, z, -y)

    /**
     * Builds a listener (or directional source) frame from a 2D position and a 2D facing
     * direction, both in game coordinates.
     *
     * [facingX] and [facingY] need not be normalised. A facing of `(0, 1)` -- up the screen --
     * becomes a world `ahead` of `(0, 0, -1)`, which is Steam Audio's own forward.
     *
     * Use this rather than assembling a [CoordinateSpace3] by hand; the sign that makes it
     * right-handed is not the one you would guess.
     */
    public fun listenerFacing(
        x: Float,
        y: Float,
        facingX: Float,
        facingY: Float,
    ): CoordinateSpace3 {
        val length = hypot(facingX, facingY)
        require(length > 0f) { "facing direction cannot be zero" }
        return CoordinateSpace3.lookingAt(
            origin = point(x, y),
            // Game +y maps to world -z; see the mapping table above.
            ahead = Vector3(facingX / length, 0f, -facingY / length),
            up = Vector3.UP,
        )
    }

    /**
     * The transform placing a wall quad of [quadLength] -- centred on the origin and running
     * along world +x, as [MovableWall] builds it -- onto the game segment `(x0, y0)`-`(x1, y1)`.
     *
     * Lives here, and is called by [MovableWall.moveTo] rather than reimplemented there, because
     * the signs in it are precisely what mirrors a scene when they are wrong, and
     * `World2DHandednessTest` asserts them. A second copy is a second place for them to drift,
     * and a test asserting the copy proves nothing about the code that runs -- which is how the
     * mapping and the transform came to disagree in the first place.
     */
    public fun segmentTransform(
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        quadLength: Float,
    ): Matrix4x4 {
        val segmentLength = hypot(x1 - x0, y1 - y0)
        require(segmentLength > 0f) { "a wall segment needs non-zero length" }
        require(quadLength > 0f) { "quadLength must be positive, got $quadLength" }

        // Scale the unit quad to the segment's length, rotate it about the vertical (world y)
        // axis, then translate to the segment's midpoint under the game-to-world mapping.
        // rotationY(angle) sends world +x to (cos, 0, -sin), which is exactly the image of the
        // game direction (cos, sin), so the angle needs no sign correction.
        val angle = atan2(y1 - y0, x1 - x0)
        val midpoint = point((x0 + x1) * 0.5f, (y0 + y1) * 0.5f, 0f)
        return Matrix4x4.translation(midpoint.x, midpoint.y, midpoint.z) *
            Matrix4x4.rotationY(angle) *
            Matrix4x4.scale(segmentLength / quadLength, 1f, 1f)
    }

    /**
     * A listener frame from a facing angle in radians, measured **counter-clockwise from game
     * +x** -- the natural reading of a rotation about `+z`.
     */
    public fun listenerAtAngle(x: Float, y: Float, radians: Float): CoordinateSpace3 =
        listenerFacing(x, y, kotlin.math.cos(radians), kotlin.math.sin(radians))
}

/**
 * A wall as a 2D line segment, the geometric primitive of a 2D game.
 *
 * Extruded to a vertical quad between the world's floor and ceiling when built into a scene.
 */
public data class WallSegment(
    public val x0: Float,
    public val y0: Float,
    public val x1: Float,
    public val y1: Float,
    public val material: Material = Material.ROCK,
) {
    /** Length of the segment in the 2D plane. */
    public val length: Float get() = hypot(x1 - x0, y1 - y0)

    /** Midpoint in the 2D plane. */
    public val midX: Float get() = (x0 + x1) * 0.5f

    /** Midpoint in the 2D plane. */
    public val midY: Float get() = (y0 + y1) * 0.5f

    /** Angle of the segment, counter-clockwise from game +x, in radians. */
    public val angle: Float get() = atan2(y1 - y0, x1 - x0)

    init {
        require(length > 0f) { "a wall segment needs non-zero length: ($x0,$y0)-($x1,$y1)" }
    }
}

/**
 * Builds acoustic geometry from 2D wall segments.
 *
 * The typical arrangement for a 2D game with enclosed areas:
 *
 * ```
 * val world = World2D(floorHeight = 0f, ceilingHeight = 3.5f, earHeight = 1.6f)
 *
 * // Static walls: built once when the area loads, not when it becomes visible.
 * val walls = WallSet(world, area.wallSegments.map { WallSegment(it.x0, it.y0, it.x1, it.y1, Material.ROCK) })
 * val mesh = walls.build(scene, bounds = area.bounds)
 * mesh.addTo(scene)
 * scene.commit()
 *
 * // Moving segments: one instance each, transform updated per frame.
 * val door = MovableWall(scene, world, length = 2.0f, material = Material.WOOD)
 * door.moveTo(x0, y0, x1, y1)
 * scene.commit()          // once per frame, covering every moved segment
 * ```
 *
 * Measured cost, for context: an enclosed area of a few hundred segments is a few hundred
 * triangles, where the initial build is under 2 ms and per-frame commits are at the timer
 * floor. The per-frame budget in a 2D game goes almost entirely to
 * [Simulator.runReflections], not to geometry.
 */
public class WallSet(
    /** The 2D-to-3D convention to build against. */
    public val world: World2D,
    /** The wall segments making up this area. */
    public val segments: List<WallSegment>,
) {
    /**
     * Builds the walls -- and, unless disabled, a floor and ceiling -- as a single static mesh.
     *
     * @param bounds the 2D extent to cap with a floor and ceiling, as
     *   `(minX, minY, maxX, maxY)`. Required when [capped] is true. If omitted, it is derived
     *   from the segments' own extent, expanded slightly.
     * @param capped add a floor and ceiling. **Leave this on.** Without caps the space is an
     *   open-topped box and rays escape vertically, which makes a reverberant room sound dead.
     * @param doubleSided emit both windings for each wall, so segments reflect from either
     *   side. Correct for a 2D game, where "inside" and "outside" of a segment is rarely
     *   meaningful.
     * @param floorMaterial material for the floor and ceiling caps; defaults to the material
     *   of the first segment.
     */
    public fun build(
        scene: Scene,
        bounds: FloatArray? = null,
        capped: Boolean = true,
        doubleSided: Boolean = true,
        floorMaterial: Material? = null,
    ): StaticMesh {
        require(segments.isNotEmpty()) { "a WallSet needs at least one segment" }

        val vertices = ArrayList<Vector3>(segments.size * 4 + 8)
        val triangles = ArrayList<Triangle>(segments.size * 4 + 4)
        val materials = ArrayList<Material>()
        val materialIndices = ArrayList<Int>(triangles.size)

        // Distinct materials, so each triangle can index one rather than duplicating.
        val materialIndexOf = HashMap<Material, Int>()
        fun materialIndex(material: Material): Int = materialIndexOf.getOrPut(material) {
            materials += material
            materials.size - 1
        }

        val floor = world.floorWorldY
        val ceiling = world.ceilingWorldY

        for (segment in segments) {
            val base = vertices.size
            // Game (x, y) maps to world (x, z, -y); the quad spans game z from floor to ceiling.
            vertices += Vector3(segment.x0, floor, -segment.y0)
            vertices += Vector3(segment.x1, floor, -segment.y1)
            vertices += Vector3(segment.x1, ceiling, -segment.y1)
            vertices += Vector3(segment.x0, ceiling, -segment.y0)

            val index = materialIndex(segment.material)
            triangles += Triangle(base, base + 1, base + 2)
            materialIndices += index
            triangles += Triangle(base, base + 2, base + 3)
            materialIndices += index
            if (doubleSided) {
                triangles += Triangle(base + 2, base + 1, base)
                materialIndices += index
                triangles += Triangle(base + 3, base + 2, base)
                materialIndices += index
            }
        }

        if (capped) {
            val extent = bounds ?: deriveBounds()
            require(extent.size == 4) { "bounds must be (minX, minY, maxX, maxY)" }
            val (minX, minY, maxX, maxY) = extent
            val capMaterial = floorMaterial ?: segments.first().material
            val capIndex = materialIndex(capMaterial)

            // Both caps are emitted double-sided. Getting a cap's winding backwards makes it
            // invisible to reflections from inside, which is the same failure as omitting it.
            for (capY in floatArrayOf(floor, ceiling)) {
                val base = vertices.size
                vertices += Vector3(minX, capY, -minY)
                vertices += Vector3(maxX, capY, -minY)
                vertices += Vector3(maxX, capY, -maxY)
                vertices += Vector3(minX, capY, -maxY)
                triangles += Triangle(base, base + 1, base + 2)
                materialIndices += capIndex
                triangles += Triangle(base, base + 2, base + 3)
                materialIndices += capIndex
                triangles += Triangle(base + 2, base + 1, base)
                materialIndices += capIndex
                triangles += Triangle(base + 3, base + 2, base)
                materialIndices += capIndex
            }
        }

        return StaticMesh(
            scene = scene,
            vertices = vertices,
            triangles = triangles,
            materials = materials,
            materialIndices = materialIndices.toIntArray(),
        )
    }

    /** Number of triangles [build] will produce, for budgeting. */
    public fun triangleCount(capped: Boolean = true, doubleSided: Boolean = true): Int =
        segments.size * (if (doubleSided) 4 else 2) + (if (capped) 8 else 0)

    /** The 2D extent of the segments, expanded slightly so caps fully enclose the walls. */
    private fun deriveBounds(): FloatArray {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (segment in segments) {
            minX = minOf(minX, segment.x0, segment.x1)
            minY = minOf(minY, segment.y0, segment.y1)
            maxX = maxOf(maxX, segment.x0, segment.x1)
            maxY = maxOf(maxY, segment.y0, segment.y1)
        }
        val margin = 0.05f
        return floatArrayOf(minX - margin, minY - margin, maxX + margin, maxY + margin)
    }

    private operator fun FloatArray.component1() = this[0]
    private operator fun FloatArray.component2() = this[1]
    private operator fun FloatArray.component3() = this[2]
    private operator fun FloatArray.component4() = this[3]
}

/**
 * A wall segment that can move, slide, rotate or disappear.
 *
 * Wraps an [InstancedMesh] over a unit wall quad, so repositioning it is a transform update
 * rather than a geometry rebuild. Measured at the timer floor even with tens of thousands of
 * static triangles in the same scene.
 *
 * ```
 * val shutter = MovableWall(scene, world, length = 3f, material = Material.METAL)
 * shutter.addTo(scene)
 * // per frame:
 * shutter.moveTo(x0, y0, x1, y1)
 * scene.commit()               // one commit for all moved walls
 * ```
 *
 * The unit quad lives in a sub-scene this object owns, so [close] releases both. Several
 * movable walls of the same length can share one sub-scene -- see [sharing] -- which is worth
 * doing when there are many.
 */
public class MovableWall private constructor(
    private val world: World2D,
    /** The length the underlying quad was built at. */
    public val length: Float,
    private val instance: InstancedMesh,
    /** Owned only when this wall created its own sub-scene. */
    private val ownedScene: Scene?,
    private val ownedMesh: StaticMesh?,
) : AutoCloseable {

    /** Adds this wall to [scene]. Takes effect on the next [Scene.commit]. */
    public fun addTo(scene: Scene) {
        instance.addTo(scene)
    }

    /** Removes this wall from [scene]. Takes effect on the next [Scene.commit]. */
    public fun removeFrom(scene: Scene) {
        instance.removeFrom(scene)
    }

    /**
     * Places this wall along the 2D segment from `(x0, y0)` to `(x1, y1)`.
     *
     * The segment's length is allowed to differ from [length]; the quad is scaled to fit. Takes
     * effect on the next [Scene.commit].
     */
    public fun moveTo(scene: Scene, x0: Float, y0: Float, x1: Float, y1: Float) {
        instance.updateTransform(scene, world.segmentTransform(x0, y0, x1, y1, length))
    }

    /** Places this wall along [segment]. */
    public fun moveTo(scene: Scene, segment: WallSegment) {
        moveTo(scene, segment.x0, segment.y0, segment.x1, segment.y1)
    }

    /**
     * Moves this wall far outside the world, so it stops affecting the simulation.
     *
     * Cheaper and simpler than removing and re-adding, and measured at the same cost as any
     * other transform update. Takes effect on the next [Scene.commit].
     */
    public fun hide(scene: Scene) {
        instance.updateTransform(scene, Matrix4x4.translation(0f, -1_000_000f, 0f))
    }

    override fun close() {
        instance.close()
        ownedMesh?.close()
        ownedScene?.close()
    }

    public companion object {
        /**
         * Creates a movable wall with its own sub-scene.
         *
         * @param length the segment length the quad is built at. [moveTo] scales to fit, so
         *   this is only a reference length; `1.0` is a fine default.
         */
        public operator fun invoke(
            scene: Scene,
            world: World2D,
            length: Float = 1.0f,
            material: Material = Material.ROCK,
        ): MovableWall {
            require(length > 0f) { "length must be positive, got $length" }
            val context = SceneContexts.contextOf(scene)
            val subScene = Scene(context)
            try {
                val mesh = unitWall(subScene, world, length, material)
                mesh.addTo(subScene)
                subScene.commit()
                val instance = InstancedMesh(scene, subScene, Matrix4x4.IDENTITY)
                return MovableWall(world, length, instance, subScene, mesh)
            } catch (failure: Throwable) {
                subScene.close()
                throw failure
            }
        }

        /**
         * Creates several movable walls that share one sub-scene and one quad.
         *
         * Preferable when there are many movable segments: the geometry exists once and each
         * wall is just a transform. The returned walls must all be closed, and [sharedScene]
         * must outlive them -- it is returned so the caller can close it last.
         */
        public fun sharing(
            scene: Scene,
            world: World2D,
            count: Int,
            length: Float = 1.0f,
            material: Material = Material.ROCK,
        ): SharedWalls {
            require(count > 0) { "count must be positive, got $count" }
            val context = SceneContexts.contextOf(scene)
            val subScene = Scene(context)
            try {
                val mesh = unitWall(subScene, world, length, material)
                mesh.addTo(subScene)
                subScene.commit()
                val walls = List(count) {
                    MovableWall(
                        world,
                        length,
                        InstancedMesh(scene, subScene, Matrix4x4.IDENTITY),
                        ownedScene = null,
                        ownedMesh = null,
                    )
                }
                return SharedWalls(walls, subScene, mesh)
            } catch (failure: Throwable) {
                subScene.close()
                throw failure
            }
        }

        /** A wall quad of [length], centred on the origin and running along +x. */
        private fun unitWall(
            scene: Scene,
            world: World2D,
            length: Float,
            material: Material,
        ): StaticMesh {
            val half = length * 0.5f
            return StaticMesh.plane(
                scene,
                Vector3(-half, world.floorWorldY, 0f),
                Vector3(half, world.floorWorldY, 0f),
                Vector3(half, world.ceilingWorldY, 0f),
                Vector3(-half, world.ceilingWorldY, 0f),
                material,
                doubleSided = true,
            )
        }
    }
}

/**
 * A group of [MovableWall]s sharing one sub-scene.
 *
 * Close this, not the individual walls: [close] releases the walls and then the shared geometry
 * in the right order.
 */
public class SharedWalls internal constructor(
    /** The walls, each independently positionable. */
    public val walls: List<MovableWall>,
    private val sharedScene: Scene,
    private val sharedMesh: StaticMesh,
) : AutoCloseable {

    /** The wall at [index]. */
    public operator fun get(index: Int): MovableWall = walls[index]

    /** How many walls this group holds. */
    public val size: Int get() = walls.size

    /** Adds every wall to [scene]. */
    public fun addAllTo(scene: Scene) {
        walls.forEach { it.addTo(scene) }
    }

    override fun close() {
        walls.forEach { it.close() }
        sharedMesh.close()
        sharedScene.close()
    }
}

/**
 * Recovers the [SteamAudioContext] a [Scene] belongs to.
 *
 * Steam Audio has no API for asking a scene which context created it, so scenes record it here
 * at construction. This exists so that helpers like [MovableWall] can create a sub-scene
 * without the caller having to pass the context in again.
 */
internal object SceneContexts {
    private val contexts = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<Scene, SteamAudioContext>(),
    )

    fun register(scene: Scene, context: SteamAudioContext) {
        contexts[scene] = context
    }

    fun contextOf(scene: Scene): SteamAudioContext = contexts[scene]
        ?: throw IllegalStateException(
            "this Scene was not created through the Kotlin API, so its context is unknown",
        )
}
