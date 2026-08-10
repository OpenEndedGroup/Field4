package afield.four.modules

import afield.Box
import afield.stdlib.MeshView
import afield.stdlib.TriMesh
import afield.stdlib.TriMeshBuilder
import afield.stdlib.Vec2
import afield.stdlib.Vec3
import afield.stdlib.linalg.Vector3f
import afield.stdlib.meshFromFile
import afield.stdlib.showMesh
import org.lwjgl.system.MemoryUtil.memFree
import java.io.File

/**
 * The two missing edges between the loaders that read geometry off disk and the CPU renderer that can
 * *look* at it.
 *
 * Field has four things that read geometry from a file, and until this file, one of them fed the CPU
 * preview:
 *
 *  - `afield.stdlib.mesh_io.kt` reads OBJ and STL straight into [TriMesh] — the type
 *    [afield.stdlib.showMesh], [TriMesh.renderPng] and the inline `toHTML()` card all render.
 *  - [PlySupport] reads PLY (ASCII, binary, both endians, colours, normals, point clouds) into a
 *    packed 10-float GPU buffer.
 *  - [GltfSupport] reads glTF/GLB — and, being `Assimp.aiImportFile` underneath, also FBX, DAE, 3DS,
 *    OBJ, STL and PLY — into a packed 12-float GPU buffer plus materials.
 *  - `afield.four.fabricated.FabricateGltfBridge` takes that same model into the PBR `StandardRenderer`.
 *
 * Both GPU loaders produce plain host memory — `memAlloc`, not a Vulkan allocation — so nothing
 * except a missing function stopped their output from becoming a [TriMesh]. These are those
 * functions: [PlySupport.PlyModel.toTriMesh], [assimpToTriMesh] and the dispatcher
 * [meshFromAnyFile], plus a [Box.showMesh] overload that takes a [File].
 *
 * ```kotlin
 * showMesh(File("/path/to/thing.glb"))                     // straight to pixels
 * val m = meshFromAnyFile("/path/to/scan.ply")             // or keep the mesh and compute with it
 * println(m.summary())
 * ```
 *
 * They live here rather than in `afield.stdlib` on purpose: `mesh_io.kt` is deliberately
 * dependency-free, and these need LWJGL. `afield.four.modules.*` is in every box's default imports
 * (see `afield.intro`), so a box can call them without an import anyway.
 *
 * What is *not* here, because nothing in the tree can read it: **STEP / IGES / BREP**. There is no CAD
 * kernel on the classpath, and a NURBS-to-mesh tessellation is not a weekend's work — see
 * `public/docs/geometry_from_disk.md` for the honest list.
 */

/**
 * This PLY model's packed vertex buffer, unpacked into a [TriMesh].
 *
 * [PlySupport.loadPly] fills `[pos.xyz][normal.xyz][color.rgba]` — ten floats per vertex — and an
 * `IntBuffer` of triangle indices, both in ordinary host memory. This reads them back out.
 *
 * @param keepNormals PLY files without `nx`/`ny`/`nz` get `(0, 0, 1)` written for every vertex by the
 *   loader, which is a *wrong* normal rather than a missing one — it would shade the whole model as
 *   though it faced the camera. When the loader reports no normals in the file this drops them
 *   instead, and the render bridges then crease-shade the mesh for you.
 * @param keepColors PLY colours default to white when the file has none, which carries no
 *   information and only overrides the renderer's own default shading; an all-white colour array is
 *   dropped whatever this says.
 * @throws IllegalArgumentException if the model is a point cloud — there are no triangles to make a
 *   mesh out of. Check [PlySupport.PlyMeshBuffers.isPointCloud] first.
 */
fun PlySupport.PlyModel.toTriMesh(keepNormals: Boolean = true, keepColors: Boolean = true): TriMesh {
    val b = buffers
    require(!b.isPointCloud) {
        "${name} is a point cloud (${b.vertexCount} vertices, no faces); there is no mesh to build. " +
            "Use plyPointCloudHere for those, or read the positions out of buffers.vertexData yourself."
    }
    val floats = b.vertexData.asFloatBuffer()
    val useNormals = keepNormals && b.hasNormals
    val useColors = keepColors && b.hasColors
    val builder = TriMeshBuilder()
    var whiteEverywhere = true
    val staged = ArrayList<FloatArray>(b.vertexCount)
    for (v in 0 until b.vertexCount) {
        val o = v * 10
        val row = FloatArray(10) { floats.get(o + it) }
        if (useColors && (row[6] != 1f || row[7] != 1f || row[8] != 1f)) whiteEverywhere = false
        staged.add(row)
    }
    val emitColors = useColors && !whiteEverywhere
    for (row in staged) {
        builder.vertex(
            row[0], row[1], row[2],
            normal = if (useNormals) Vec3(row[3], row[4], row[5]) else null,
            color = if (emitColors) Vec3(row[6], row[7], row[8]) else null,
        )
    }
    val indices = b.indexData
    if (indices != null) {
        var t = 0
        while (t + 2 < b.indexCount) {
            val a = indices.get(t); val bb = indices.get(t + 1); val c = indices.get(t + 2)
            if (a < b.vertexCount && bb < b.vertexCount && c < b.vertexCount) builder.triangle(a, bb, c)
            t += 3
        }
    }
    return builder.build()
}

/**
 * Read any file [Assimp][org.lwjgl.assimp.Assimp] can import — glTF, GLB, FBX, DAE, 3DS, OBJ, STL,
 * PLY and the rest of its forty-odd formats — into one [TriMesh].
 *
 * Every mesh in the scene is baked through its node's world transform and concatenated, so a
 * multi-part model arrives assembled rather than as a heap of parts at the origin. Normals come from
 * the file or from Assimp's `aiProcess_GenNormals`; uvs and per-vertex colours come across when the
 * file has them.
 *
 * This is a **bridge, not a loader**: it calls [GltfSupport.loadGltfBasic] and unpacks the twelve
 * floats per vertex it already produces. Two things about that are worth knowing:
 *
 *  - It passes `loadTextures = false`, which is what makes this work outside a live renderer. The
 *    default [GltfSupport.GltfLoadConfig] has `loadTextures = true`, and that path asks the box
 *    context for a `Renderer` in order to make stub textures — so `loadGltfBasic(path)` with
 *    default arguments throws a `NullPointerException` from a plain function or test. Materials are
 *    dropped here regardless: a [TriMesh] has positions, normals, uvs and per-vertex colours and no
 *    concept of a texture. For textured PBR rendering use
 *    [afield.four.fabricated.FabricateGltfBridge] and the `StandardRenderer` instead.
 *  - Assimp's loaders allocate off-heap buffers. This frees them before returning, because it loaded
 *    them itself and nothing else can reach them afterwards.
 *
 * @param keepColors glTF meshes with no `COLOR_0` get white written for every vertex; an all-white
 *   colour array is dropped whatever this says, so the renderer's own shading shows through.
 * @throws IllegalStateException with Assimp's own message if the file cannot be read at all.
 */
fun assimpToTriMesh(path: String, keepColors: Boolean = true): TriMesh {
    val model = GltfSupport.loadGltfBasic(
        path,
        GltfSupport.GltfLoadConfig(loadTextures = false),
    )
    try {
        // Accumulated as a nullable rather than from TriMesh.empty: `plus` keeps an optional attribute
        // only when *both* sides have it, and the empty mesh has none, so starting there would throw
        // away every normal in the file.
        var out: TriMesh? = null
        for (instance in model.meshes) {
            val b = instance.buffers
            if (b.indexCount < 3) continue
            val m = instance.bindPose.toMatrix()
            val floats = b.vertexData.asFloatBuffer()
            // Two passes so the all-white test can decide before any vertex is emitted -- the builder
            // requires colours to be all-or-nothing.
            val rows = ArrayList<FloatArray>(b.vertexCount)
            var whiteEverywhere = true
            var anyUv = false
            for (v in 0 until b.vertexCount) {
                val o = v * 12
                val row = FloatArray(12) { floats.get(o + it) }
                if (row[8] != 1f || row[9] != 1f || row[10] != 1f) whiteEverywhere = false
                if (row[6] != 0f || row[7] != 0f) anyUv = true
                rows.add(row)
            }
            val emitColors = keepColors && !whiteEverywhere
            val builder = TriMeshBuilder()
            val p = Vector3f()
            val n = Vector3f()
            for (row in rows) {
                p.set(row[0], row[1], row[2]); m.transformPosition(p)
                n.set(row[3], row[4], row[5]); m.transformDirection(n)
                val len = kotlin.math.sqrt(n.x * n.x + n.y * n.y + n.z * n.z)
                builder.vertex(
                    p.x, p.y, p.z,
                    normal = if (len > 1e-12f) Vec3(n.x / len, n.y / len, n.z / len) else Vec3(0f, 1f, 0f),
                    uv = if (anyUv) Vec2(row[6], row[7]) else null,
                    color = if (emitColors) Vec3(row[8], row[9], row[10]) else null,
                )
            }
            val indices = b.indexData
            var t = 0
            while (t + 2 < b.indexCount) {
                val a = indices.get(t); val bb = indices.get(t + 1); val c = indices.get(t + 2)
                if (a < b.vertexCount && bb < b.vertexCount && c < b.vertexCount) builder.triangle(a, bb, c)
                t += 3
            }
            val part = builder.build()
            out = out?.plus(part) ?: part
        }
        return out ?: TriMesh.empty
    } finally {
        for (instance in model.meshes) {
            memFree(instance.buffers.vertexData)
            memFree(instance.buffers.indexData)
            instance.buffers.tangentData?.let { memFree(it) }
        }
    }
}

/**
 * Load a mesh from a path, in **any** format Field can read, and return it as a [TriMesh] you can
 * render, measure, weld, decimate or write back out.
 *
 * The dispatch, and why it is not just one call to Assimp:
 *
 * | extension | route | why |
 * |---|---|---|
 * | `.obj`, `.stl` | [afield.stdlib.meshFromFile] | dependency-free, and its OBJ reader emits vertices in the file's own `v` order, so a round trip is index-for-index rather than merely shape-for-shape |
 * | `.ply` | [PlySupport.loadPly] then [PlySupport.PlyModel.toTriMesh] | keeps the per-vertex colours a scan carries, which is usually the point of a PLY |
 * | anything else | [assimpToTriMesh] | glTF, GLB, FBX, DAE, 3DS, and the rest |
 *
 * An unreadable file throws rather than returning an empty mesh, so a typo in a path does not look
 * like a modelling problem.
 *
 * ```kotlin
 * val m = meshFromAnyFile("/path/to/thing.glb")
 * println(m.summary())     // counts, extent, chi, genus, watertightness, area, volume
 * showMesh(m)              // and look at it
 * ```
 */
fun meshFromAnyFile(path: String): TriMesh {
    val file = File(path)
    require(file.isFile) { "no such mesh file: $path" }
    return when (file.extension.lowercase()) {
        "obj", "stl" -> meshFromFile(path)
        "ply" -> PlySupport.loadPly(path).let { model ->
            try {
                model.toTriMesh()
            } finally {
                memFree(model.buffers.vertexData)
                model.buffers.indexData?.let { memFree(it) }
            }
        }
        else -> assimpToTriMesh(path)
    }
}

/**
 * Read a mesh file and push a rendered view of it to one of this box's output channels — the one-liner
 * for "I have a mesh on disk, show me".
 *
 * Every format [meshFromAnyFile] handles, through the same CPU z-buffer renderer
 * [afield.stdlib.showMesh] uses: perspective, back-face culling, two-light lambert, depth-tested
 * wireframe, and the mesh's measurements printed beside the picture.
 *
 * @param title defaults to the file's name, so a grid of these labels itself.
 */
fun Box.showMesh(
    file: File,
    channel: String = "main",
    view: MeshView = MeshView(),
    caption: String? = null,
    title: String? = file.name,
) {
    showMesh(meshFromAnyFile(file.path), channel, view, caption, title)
}
