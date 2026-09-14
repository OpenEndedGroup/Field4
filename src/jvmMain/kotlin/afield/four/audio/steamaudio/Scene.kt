package afield.four.audio.steamaudio

import afield.four.audio.steamaudio.raw.IPLInstancedMeshSettings
import afield.four.audio.steamaudio.raw.IPLMaterial
import afield.four.audio.steamaudio.raw.IPLSceneSettings
import afield.four.audio.steamaudio.raw.IPLStaticMeshSettings
import afield.four.audio.steamaudio.raw.IPLTriangle
import afield.four.audio.steamaudio.raw.IPLVector3
import afield.four.audio.steamaudio.raw.Phonon
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.file.Path

/** A triangle, as three indices into a mesh's vertex list. */
public data class Triangle(public val a: Int, public val b: Int, public val c: Int) {
    internal fun writeTo(segment: MemorySegment) {
        val indices = IPLTriangle.indices(segment)
        indices.setAtIndex(ValueLayout.JAVA_INT, 0L, a)
        indices.setAtIndex(ValueLayout.JAVA_INT, 1L, b)
        indices.setAtIndex(ValueLayout.JAVA_INT, 2L, c)
    }
}

/**
 * The geometry sound interacts with: the surfaces that reflect, absorb and occlude.
 *
 * A scene holds static meshes (fixed geometry) and instanced meshes (a sub-scene placed by a
 * transform, which can be moved). Both are reference-counted objects in their own right; the
 * scene does not own them, so keep them alive for as long as they are part of the scene.
 *
 * ### Committing
 *
 * Adding or removing a mesh, changing a material, or moving an instanced mesh does **not**
 * take effect until [commit] rebuilds the acceleration structure. Commit is the expensive
 * operation, so batch changes and commit once:
 *
 * ```
 * // per frame, off the audio thread:
 * for (plane in movingPlanes) plane.updateTransform(scene, plane.currentTransform())
 * scene.commit()          // one commit for all of them, not one each
 * ```
 *
 * [commit] must not be called from the audio thread, and must not run concurrently with a
 * [Simulator] reflection run against the same scene.
 */
public class Scene private constructor(
    handle: MemorySegment,
    /** Owns upcall stubs for a custom scene, if any. */
    private val callbackArena: Arena?,
) : SteamAudioObject(handle) {

    override fun releaseAction(): (MemorySegment) -> Unit = Phonon::iplSceneRelease

    override val typeName: String get() = "Scene"

    override fun onClosed() {
        callbackArena?.close()
    }

    /**
     * Rebuilds the ray-tracing acceleration structure so that pending geometry changes take
     * effect.
     *
     * Expensive, and proportional to the amount of geometry -- call it once per frame after
     * all changes, never per mesh.
     */
    public fun commit() {
        Phonon.iplSceneCommit(liveHandle)
    }

    /** Increments the reference count and returns an independently closeable wrapper. */
    public fun retain(): Scene = Scene(Phonon.iplSceneRetain(liveHandle), null)

    /** Serialises this scene into [into]. Only meaningful for non-custom scenes. */
    public fun save(into: SerializedObject) {
        Phonon.iplSceneSave(liveHandle, into.liveHandle)
    }

    /**
     * Writes the scene geometry to a Wavefront OBJ file, for inspection in a 3D viewer.
     *
     * [fileBaseName] is a path without extension; Steam Audio appends `.obj`. Invaluable for
     * checking that the geometry you think you built is the geometry Steam Audio sees.
     */
    public fun saveObj(fileBaseName: Path) {
        val handle = liveHandle
        Arena.ofConfined().use { arena ->
            Phonon.iplSceneSaveOBJ(
                handle,
                arena.allocateFrom(fileBaseName.toAbsolutePath().toString()),
            )
        }
    }

    public companion object {
        /**
         * Creates a scene using Steam Audio's built-in ray tracer.
         *
         * This is the right choice for dynamic geometry: it has no device dependency and
         * rebuilds quickly on [commit].
         */
        public operator fun invoke(context: SteamAudioContext): Scene =
            create(context, SceneType.DEFAULT, null, null, null)

        /** Creates a scene backed by Intel Embree. */
        public fun embree(context: SteamAudioContext, device: EmbreeDevice): Scene =
            create(context, SceneType.EMBREE, device, null, null)

        /** Creates a scene backed by AMD Radeon Rays. */
        public fun radeonRays(context: SteamAudioContext, device: RadeonRaysDevice): Scene =
            create(context, SceneType.RADEON_RAYS, null, device, null)

        /**
         * Creates a scene whose ray casts are delegated to application callbacks.
         *
         * Use this to reuse a physics engine's existing acceleration structure rather than
         * duplicating geometry into Steam Audio.
         *
         * Be aware of the cost: these callbacks are invoked from Steam Audio's own
         * ray-tracing threads, at very high rates, and each invocation crosses the FFM
         * boundary into the JVM. Read the guidance on [CustomSceneCallbacks] before choosing
         * this over a default scene.
         */
        public fun custom(context: SteamAudioContext, callbacks: CustomSceneCallbacks): Scene =
            create(context, SceneType.CUSTOM, null, null, callbacks)

        /** Loads a scene previously written by [Scene.save]. */
        public fun load(
            context: SteamAudioContext,
            from: SerializedObject,
            progress: ProgressCallback? = null,
        ): Scene {
            val contextHandle = context.liveHandle
            Arena.ofConfined().use { arena ->
                val settings = IPLSceneSettings.allocate(arena)
                IPLSceneSettings.type(settings, SceneType.DEFAULT.value)
                val stub = progress?.let { ProgressCallbacks.stub(it, arena) } ?: MemorySegment.NULL
                return Scene(
                    createHandle("iplSceneLoad") { out ->
                        Phonon.iplSceneLoad(
                            contextHandle,
                            settings,
                            from.liveHandle,
                            stub,
                            MemorySegment.NULL,
                            out,
                        )
                    },
                    null,
                ).also { SceneContexts.register(it, context) }
            }
        }

        private fun create(
            context: SteamAudioContext,
            type: SceneType,
            embree: EmbreeDevice?,
            radeonRays: RadeonRaysDevice?,
            callbacks: CustomSceneCallbacks?,
        ): Scene {
            val contextHandle = context.liveHandle
            var stubArena: Arena? = null
            try {
                val handle = Arena.ofConfined().use { scratch ->
                    val settings = IPLSceneSettings.allocate(scratch)
                    IPLSceneSettings.type(settings, type.value)
                    IPLSceneSettings.embreeDevice(settings, embree.handleOrNull())
                    IPLSceneSettings.radeonRaysDevice(settings, radeonRays.handleOrNull())
                    IPLSceneSettings.userData(settings, MemorySegment.NULL)

                    if (callbacks != null) {
                        val arena = Arena.ofShared()
                        stubArena = arena
                        CustomSceneCallbacks.install(callbacks, settings, arena)
                    }

                    createHandle("iplSceneCreate") { out ->
                        Phonon.iplSceneCreate(contextHandle, settings, out)
                    }
                }
                return Scene(handle, stubArena).also { SceneContexts.register(it, context) }
            } catch (failure: Throwable) {
                stubArena?.close()
                throw failure
            }
        }
    }
}

/**
 * Fixed geometry within a [Scene]: a vertex list, a triangle list, and a material per triangle.
 *
 * A static mesh's geometry cannot change after creation, but the mesh can be added to and
 * removed from its scene, and its materials can be swapped. To move geometry, use an
 * [InstancedMesh] instead.
 *
 * Steam Audio copies the vertex and triangle data during creation, so the arrays you pass in
 * are not retained.
 */
public class StaticMesh private constructor(
    handle: MemorySegment,
    /** Number of triangles, so [setMaterial] can bounds-check its index. */
    public val triangleCount: Int,
    /** Number of distinct materials in this mesh. */
    public val materialCount: Int,
) : SteamAudioObject(handle) {

    override fun releaseAction(): (MemorySegment) -> Unit = Phonon::iplStaticMeshRelease

    override val typeName: String get() = "StaticMesh"

    /**
     * Adds this mesh to [scene]. Takes effect on the next [Scene.commit].
     *
     * A mesh may only be added to the scene it was created against.
     */
    public fun addTo(scene: Scene) {
        Phonon.iplStaticMeshAdd(liveHandle, scene.liveHandle)
    }

    /** Removes this mesh from [scene]. Takes effect on the next [Scene.commit]. */
    public fun removeFrom(scene: Scene) {
        Phonon.iplStaticMeshRemove(liveHandle, scene.liveHandle)
    }

    /**
     * Replaces one of this mesh's materials. Takes effect on the next [Scene.commit].
     *
     * [index] indexes the material list the mesh was created with, so this changes the
     * acoustics of every triangle using that material at once -- handy for making a surface
     * go from stone to water, or opening an absorbing curtain.
     */
    public fun setMaterial(scene: Scene, index: Int, material: Material) {
        require(index in 0 until materialCount) {
            "material index $index outside 0..<$materialCount"
        }
        val handle = liveHandle
        Arena.ofConfined().use { arena ->
            Phonon.iplStaticMeshSetMaterial(
                handle,
                scene.liveHandle,
                material.allocate(arena),
                index,
            )
        }
    }

    /** Serialises this mesh into [into]. */
    public fun save(into: SerializedObject) {
        Phonon.iplStaticMeshSave(liveHandle, into.liveHandle)
    }

    /** Increments the reference count and returns an independently closeable wrapper. */
    public fun retain(): StaticMesh =
        StaticMesh(Phonon.iplStaticMeshRetain(liveHandle), triangleCount, materialCount)

    public companion object {
        /**
         * Creates a static mesh.
         *
         * @param vertices the vertex positions.
         * @param triangles triangles as indices into [vertices], wound counter-clockwise when
         *   seen from the side sound should reflect off.
         * @param materials the distinct materials used.
         * @param materialIndices one entry per triangle, indexing [materials].
         */
        public operator fun invoke(
            scene: Scene,
            vertices: List<Vector3>,
            triangles: List<Triangle>,
            materials: List<Material>,
            materialIndices: IntArray,
        ): StaticMesh {
            require(vertices.isNotEmpty()) { "a mesh needs at least one vertex" }
            require(triangles.isNotEmpty()) { "a mesh needs at least one triangle" }
            require(materials.isNotEmpty()) { "a mesh needs at least one material" }
            require(materialIndices.size == triangles.size) {
                "materialIndices has ${materialIndices.size} entries for ${triangles.size} triangles"
            }
            triangles.forEachIndexed { index, triangle ->
                require(
                    triangle.a in vertices.indices &&
                        triangle.b in vertices.indices &&
                        triangle.c in vertices.indices,
                ) { "triangle $index references a vertex outside 0..<${vertices.size}: $triangle" }
            }
            materialIndices.forEachIndexed { index, material ->
                require(material in materials.indices) {
                    "materialIndices[$index] = $material is outside 0..<${materials.size}"
                }
            }

            val sceneHandle = scene.liveHandle
            Arena.ofConfined().use { arena ->
                val settings = IPLStaticMeshSettings.allocate(arena)
                IPLStaticMeshSettings.numVertices(settings, vertices.size)
                IPLStaticMeshSettings.numTriangles(settings, triangles.size)
                IPLStaticMeshSettings.numMaterials(settings, materials.size)

                val vertexLayout = IPLVector3.layout()
                val vertexArray = arena.allocate(vertexLayout, vertices.size.toLong())
                vertices.forEachIndexed { index, vertex ->
                    vertex.writeTo(
                        vertexArray.asSlice(index * vertexLayout.byteSize(), vertexLayout.byteSize()),
                    )
                }
                IPLStaticMeshSettings.vertices(settings, vertexArray)

                val triangleLayout = IPLTriangle.layout()
                val triangleArray = arena.allocate(triangleLayout, triangles.size.toLong())
                triangles.forEachIndexed { index, triangle ->
                    triangle.writeTo(
                        triangleArray.asSlice(
                            index * triangleLayout.byteSize(),
                            triangleLayout.byteSize(),
                        ),
                    )
                }
                IPLStaticMeshSettings.triangles(settings, triangleArray)

                val materialLayout = IPLMaterial.layout()
                val materialArray = arena.allocate(materialLayout, materials.size.toLong())
                materials.forEachIndexed { index, material ->
                    material.writeTo(
                        materialArray.asSlice(
                            index * materialLayout.byteSize(),
                            materialLayout.byteSize(),
                        ),
                    )
                }
                IPLStaticMeshSettings.materials(settings, materialArray)

                val indexArray = arena.allocate(ValueLayout.JAVA_INT, materialIndices.size.toLong())
                MemorySegment.copy(
                    materialIndices, 0,
                    indexArray, ValueLayout.JAVA_INT, 0L,
                    materialIndices.size,
                )
                IPLStaticMeshSettings.materialIndices(settings, indexArray)

                return StaticMesh(
                    createHandle("iplStaticMeshCreate") { out ->
                        Phonon.iplStaticMeshCreate(sceneHandle, settings, out)
                    },
                    triangles.size,
                    materials.size,
                )
            }
        }

        /**
         * A single flat quad, given its four corners in order around the perimeter.
         *
         * This is the workhorse for a space built out of planes. The quad is split into two
         * triangles (`0-1-2` and `0-2-3`), both using [material].
         *
         * Steam Audio's default ray tracer treats surfaces as single-sided for reflection
         * purposes, so corner order matters: wind them counter-clockwise as seen from the side
         * the sound should bounce off. Pass `doubleSided = true` to emit both windings, which
         * is usually what you want for a plane floating in a cave that can be approached from
         * either side.
         */
        public fun plane(
            scene: Scene,
            corner0: Vector3,
            corner1: Vector3,
            corner2: Vector3,
            corner3: Vector3,
            material: Material = Material.GENERIC,
            doubleSided: Boolean = true,
        ): StaticMesh {
            val vertices = listOf(corner0, corner1, corner2, corner3)
            val triangles = if (doubleSided) {
                listOf(
                    Triangle(0, 1, 2), Triangle(0, 2, 3),
                    Triangle(2, 1, 0), Triangle(3, 2, 0),
                )
            } else {
                listOf(Triangle(0, 1, 2), Triangle(0, 2, 3))
            }
            return invoke(
                scene = scene,
                vertices = vertices,
                triangles = triangles,
                materials = listOf(material),
                materialIndices = IntArray(triangles.size),
            )
        }

        /**
         * An axis-aligned closed box, built from its six faces with inward-facing normals --
         * a room, in other words, rather than a solid object.
         *
         * Pass `inwardFacing = false` for a solid block sitting in the space instead.
         */
        public fun box(
            scene: Scene,
            bounds: Box,
            material: Material = Material.GENERIC,
            inwardFacing: Boolean = true,
        ): StaticMesh {
            val (min, max) = bounds.min to bounds.max
            val vertices = listOf(
                Vector3(min.x, min.y, min.z), // 0
                Vector3(max.x, min.y, min.z), // 1
                Vector3(max.x, max.y, min.z), // 2
                Vector3(min.x, max.y, min.z), // 3
                Vector3(min.x, min.y, max.z), // 4
                Vector3(max.x, min.y, max.z), // 5
                Vector3(max.x, max.y, max.z), // 6
                Vector3(min.x, max.y, max.z), // 7
            )
            // Faces wound counter-clockwise seen from outside the box.
            val outward = listOf(
                Triangle(0, 2, 1), Triangle(0, 3, 2), // -z
                Triangle(4, 5, 6), Triangle(4, 6, 7), // +z
                Triangle(0, 4, 7), Triangle(0, 7, 3), // -x
                Triangle(1, 2, 6), Triangle(1, 6, 5), // +x
                Triangle(0, 1, 5), Triangle(0, 5, 4), // -y
                Triangle(3, 7, 6), Triangle(3, 6, 2), // +y
            )
            val triangles = if (inwardFacing) {
                outward.map { Triangle(it.c, it.b, it.a) }
            } else {
                outward
            }
            return invoke(
                scene = scene,
                vertices = vertices,
                triangles = triangles,
                materials = listOf(material),
                materialIndices = IntArray(triangles.size),
            )
        }

        /** Loads a static mesh previously written by [StaticMesh.save]. */
        public fun load(
            scene: Scene,
            from: SerializedObject,
            progress: ProgressCallback? = null,
        ): StaticMesh {
            val sceneHandle = scene.liveHandle
            Arena.ofConfined().use { arena ->
                val stub = progress?.let { ProgressCallbacks.stub(it, arena) } ?: MemorySegment.NULL
                val handle = createHandle("iplStaticMeshLoad") { out ->
                    Phonon.iplStaticMeshLoad(
                        sceneHandle,
                        from.liveHandle,
                        stub,
                        MemorySegment.NULL,
                        out,
                    )
                }
                // The serialised form does not report its own counts back, so bounds checks on
                // setMaterial are relaxed for loaded meshes.
                return StaticMesh(handle, Int.MAX_VALUE, Int.MAX_VALUE)
            }
        }
    }
}

/**
 * A sub-scene placed into a parent scene by a transform, and **movable**.
 *
 * This is how geometry moves. The sub-scene holds the geometry once and keeps its own
 * acceleration structure; the parent scene only stores a transform, so
 * [updateTransform] is cheap compared with rebuilding a static mesh.
 *
 * For a space made of drifting planes, build one sub-scene per distinct plane shape, then
 * instance it as many times as you need:
 *
 * ```
 * val panelScene = Scene(context)
 * StaticMesh.plane(panelScene, a, b, c, d, material = stone).use { panel ->
 *     panel.addTo(panelScene)
 *     panelScene.commit()
 *
 *     val instance = InstancedMesh(worldScene, panelScene, Matrix4x4.translation(0f, 0f, -4f))
 *     instance.addTo(worldScene)
 *     worldScene.commit()
 *
 *     // later, per frame:
 *     instance.updateTransform(worldScene, nextTransform)
 *     worldScene.commit()
 * }
 * ```
 */
public class InstancedMesh private constructor(handle: MemorySegment) : SteamAudioObject(handle) {

    override fun releaseAction(): (MemorySegment) -> Unit = Phonon::iplInstancedMeshRelease

    override val typeName: String get() = "InstancedMesh"

    /** Adds this instance to [scene]. Takes effect on the next [Scene.commit]. */
    public fun addTo(scene: Scene) {
        Phonon.iplInstancedMeshAdd(liveHandle, scene.liveHandle)
    }

    /** Removes this instance from [scene]. Takes effect on the next [Scene.commit]. */
    public fun removeFrom(scene: Scene) {
        Phonon.iplInstancedMeshRemove(liveHandle, scene.liveHandle)
    }

    /**
     * Moves this instance. Takes effect on the next [Scene.commit].
     *
     * Cheap relative to commit itself, so move as many instances as you like before
     * committing once.
     */
    public fun updateTransform(scene: Scene, transform: Matrix4x4) {
        val handle = liveHandle
        Arena.ofConfined().use { arena ->
            Phonon.iplInstancedMeshUpdateTransform(
                handle,
                scene.liveHandle,
                transform.allocate(arena),
            )
        }
    }

    /** Increments the reference count and returns an independently closeable wrapper. */
    public fun retain(): InstancedMesh = InstancedMesh(Phonon.iplInstancedMeshRetain(liveHandle))

    public companion object {
        /**
         * Instances [subScene] into [scene] at [transform].
         *
         * [subScene] must already have been committed. It is not retained, so keep it alive for
         * as long as the instance exists.
         */
        public operator fun invoke(
            scene: Scene,
            subScene: Scene,
            transform: Matrix4x4 = Matrix4x4.IDENTITY,
        ): InstancedMesh {
            val sceneHandle = scene.liveHandle
            Arena.ofConfined().use { arena ->
                val settings = IPLInstancedMeshSettings.allocate(arena)
                IPLInstancedMeshSettings.subScene(settings, subScene.liveHandle)
                transform.writeTo(IPLInstancedMeshSettings.transform(settings))
                return InstancedMesh(
                    createHandle("iplInstancedMeshCreate") { out ->
                        Phonon.iplInstancedMeshCreate(sceneHandle, settings, out)
                    },
                )
            }
        }
    }
}

/** Writes this material into an `IPLMaterial` at [segment]. */
internal fun Material.writeTo(segment: MemorySegment) {
    MemorySegment.copy(
        absorption, 0,
        IPLMaterial.absorption(segment), ValueLayout.JAVA_FLOAT, 0L,
        SteamAudio.NUM_BANDS,
    )
    IPLMaterial.scattering(segment, scattering)
    MemorySegment.copy(
        transmission, 0,
        IPLMaterial.transmission(segment), ValueLayout.JAVA_FLOAT, 0L,
        SteamAudio.NUM_BANDS,
    )
}

/** Allocates a new `IPLMaterial` holding this material. */
internal fun Material.allocate(arena: Arena): MemorySegment =
    IPLMaterial.allocate(arena).also(::writeTo)
