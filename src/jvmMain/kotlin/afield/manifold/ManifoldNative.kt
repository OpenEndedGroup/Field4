@file:afield.CompletionCategory("Internal")

package afield.manifold

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SegmentAllocator
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_DOUBLE
import java.lang.foreign.ValueLayout.JAVA_FLOAT
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * The Panama/FFM plumbing under [Solid] and [Section]: where `libmanifoldc` is, the function
 * descriptors for the C entry points Field uses, and the two upcall stubs that let a Kotlin lambda
 * be a Manifold callback.
 *
 * Nothing here is meant to be called from a box — read [Solid] instead. It is `internal` for
 * everything except [ManifoldLibrary], which a box may reasonably ask "is this installed?".
 *
 * # Why FFM, and why no wrapper
 *
 * Manifold ships a **C** binding library (`libmanifoldc`, built from `manifoldc.h`) beside its C++
 * one, and Homebrew's bottle contains it. That changes the shape of this binding completely compared
 * to [afield.stdlib.ceres.FfmCeresBackend], the other native solver in the tree:
 *
 * | | Ceres | Manifold |
 * |---|---|---|
 * | upstream API | C++ templates only | C++ **and** a C ABI |
 * | so Field needs | a hand-written C++ wrapper (`native/ken-ceres`) | nothing |
 * | build step | `./gradlew buildCeresRedistributable`, minutes | `brew install manifold`, seconds |
 * | ABI drift | guarded by a version string we made up | upstream's own SO version |
 *
 * So there is no `native/` directory for this and no CMake: the whole binding is the Kotlin in this
 * package, calling a library someone else built. That is the case for FFM over JNI in one line — a
 * JNI binding would still need a compiled shim per platform, which is the thing we get to skip.
 *
 * # The three shapes, and what each one cost
 *
 * **1. Plain downcalls.** `size_t` is [JAVA_LONG], `int` is [JAVA_INT], every pointer is [ADDRESS].
 * Handles are looked up eagerly in [handle] so a library that is present but too old fails on the
 * first touch of this object, naming the symbol, rather than mid-pipeline.
 *
 * **2. Structs returned by value.** `manifold_box_min` returns a `ManifoldVec3` — three doubles,
 * which AArch64 returns in `v0..v2` and x86-64 splits between `xmm0` and `xmm1`. FFM does that
 * unpacking from [VEC3] with no help; what it needs in exchange is a [SegmentAllocator] as an extra
 * **leading** argument, which is why [invokeStruct] exists and why those calls look different from
 * the rest.
 *
 * **3. Upcalls — the interesting one.** `manifold_level_set` takes a `double (*)(double, double,
 * double, void*)` and marches it into a mesh; `manifold_warp` takes a `ManifoldVec3 (*)(double,
 * double, double, void*)`. [upcallSdf] and [upcallWarp] turn a Kotlin lambda into a function pointer
 * for the duration of one call.
 *
 * The reason that is more than a curiosity: Manifold is built with its parallel (TBB) backend, so
 * **it calls the lambda from its own C++ thread pool**. Measured on this machine, a unit sphere at
 * `edgeLength = 0.04` (465k samples, 40536 triangles out):
 *
 * | | wall clock | threads that entered the JVM |
 * |---|---|---|
 * | `manifold_level_set` | 82 ms | 10 |
 * | `manifold_level_set_seq` | 151 ms | 1 |
 *
 * 1.84×, from a Kotlin closure being invoked concurrently by nine threads the JVM never created.
 * That works because FFM attaches a foreign thread on entry — but it means two things this file has
 * to handle rather than hope about, and both are in [SdfBridge]:
 *
 *  - **An exception must not escape.** There are C++ frames between the lambda and the caller;
 *    unwinding through them is undefined, and FFM's default for an escaping exception is to abort
 *    the VM. So the bridge catches [Throwable], remembers the first one, returns a finite sentinel,
 *    and [Solid.levelSet] rethrows after the native call returns.
 *  - **The lambda must be thread-safe.** It is documented as such at every entry point that takes
 *    one, and [Solid.levelSet] offers `parallel = false` for a closure that is not.
 */
internal object ManifoldNative {

    private val linker: Linker = Linker.nativeLinker()

    /**
     * Resolved on first touch of this object, which is also when a missing library is reported.
     * [Arena.global] rather than a scoped arena: the library stays loaded for the life of the VM,
     * because a [Solid] can outlive any scope we could name.
     */
    private val lookup: SymbolLookup by lazy {
        val path = ManifoldLibrary.resolvedPath
            ?: throw UnsatisfiedLinkError(
                "Manifold is not installed. ${ManifoldLibrary.HOW_TO_INSTALL}\n" +
                    "Looked at: " + ManifoldLibrary.searchedPaths.joinToString(", "),
            )
        SymbolLookup.libraryLookup(path, Arena.global())
    }

    // ==========================================================================================
    // Layouts
    // ==========================================================================================

    /** `ManifoldVec2 { double x, y; }`. */
    val VEC2: MemoryLayout = MemoryLayout.structLayout(
        JAVA_DOUBLE.withName("x"),
        JAVA_DOUBLE.withName("y"),
    )

    /** `ManifoldVec3 { double x, y, z; }`. */
    val VEC3: MemoryLayout = MemoryLayout.structLayout(
        JAVA_DOUBLE.withName("x"),
        JAVA_DOUBLE.withName("y"),
        JAVA_DOUBLE.withName("z"),
    )

    /**
     * `ManifoldRayHit { uint64_t face_id; double distance; ManifoldVec3 position, normal; }`.
     *
     * 64 bytes, mixed integer and float, so unlike [VEC3] this one comes back through memory rather
     * than registers on both of Field's architectures. FFM picks that up from the layout; the only
     * reason to know it is that the field offsets below are read straight out of the returned
     * segment.
     */
    val RAY_HIT: MemoryLayout = MemoryLayout.structLayout(
        JAVA_LONG.withName("face_id"),
        JAVA_DOUBLE.withName("distance"),
        VEC3.withName("position"),
        VEC3.withName("normal"),
    )

    /** `ManifoldManifoldPair { ManifoldManifold *first, *second; }`, returned by value by the splits. */
    val PAIR: MemoryLayout = MemoryLayout.structLayout(
        ADDRESS.withName("first"),
        ADDRESS.withName("second"),
    )

    // ==========================================================================================
    // Handles
    // ==========================================================================================

    private fun handle(name: String, descriptor: FunctionDescriptor): MethodHandle {
        val symbol = lookup.find(name).orElseThrow {
            UnsatisfiedLinkError(
                "libmanifoldc has no symbol $name — this is Manifold " +
                    "${ManifoldLibrary.reportedVersion ?: "of an unknown version"}, and Field needs 3.x. " +
                    ManifoldLibrary.HOW_TO_INSTALL,
            )
        }
        return linker.downcallHandle(symbol, descriptor)
    }

    /**
     * `T* f()` — the `manifold_alloc_*` family only, which takes **no** arguments and returns malloc'd
     * memory of the right size.
     *
     * Deliberately not shared with [construct]: `manifold_alloc_manifold()` and
     * `manifold_tetrahedron(void* mem)` look alike from a distance and differ by one parameter, and
     * calling the second through the first's descriptor is a `WrongMethodTypeException` at the first
     * invocation rather than anything the linker notices.
     */
    private fun alloc(name: String) = handle(name, FunctionDescriptor.of(ADDRESS))

    /** `T* f(void* mem)` — a constructor whose only argument is where to build. */
    private fun construct(name: String) = handle(name, FunctionDescriptor.of(ADDRESS, ADDRESS))

    /** `void f(T*)` — the delete family. */
    private fun consume(name: String) = handle(name, FunctionDescriptor.ofVoid(ADDRESS))

    /** `double f(T*)` — the scalar measurements. */
    private fun measure(name: String) = handle(name, FunctionDescriptor.of(JAVA_DOUBLE, ADDRESS))

    /** `size_t f(T*)` — the counts. */
    private fun count(name: String) = handle(name, FunctionDescriptor.of(JAVA_LONG, ADDRESS))

    /** `int f(T*)` — the predicates and the status enum. */
    private fun flag(name: String) = handle(name, FunctionDescriptor.of(JAVA_INT, ADDRESS))

    /** `T* f(void* mem, T* a)` — the unary op shape that dominates this API. */
    private fun unary(name: String) = handle(name, FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS))

    /** `T* f(void* mem, T* a, T* b)` — the booleans. */
    private fun binary(name: String) =
        handle(name, FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS))

    /** `T* f(void* mem, T* a, double...)` — a transform taking [n] doubles. */
    private fun unaryWithDoubles(name: String, n: Int) = handle(
        name,
        FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, *Array(n) { JAVA_DOUBLE }),
    )

    // ---- allocation and destruction -----------------------------------------------------------
    //
    // Every constructor in this API takes the memory to build into as its first argument, so there
    // are two ways to own a Manifold object from the JVM: allocate from an FFM Arena and call
    // `manifold_destruct_*`, or take the library's own malloc via `manifold_alloc_*` and call
    // `manifold_delete_*` (destruct **and** free). This binding takes the second, for a reason worth
    // stating: a confined Arena is thread-confined and a Solid built in a box is routinely read from
    // the runloop thread, while a shared Arena costs a thread handshake to close. malloc has neither
    // problem, and `ManifoldHandle` puts a Cleaner behind it so a box that abandons a Solid does not
    // leak one.

    val allocManifold: MethodHandle by lazy { alloc("manifold_alloc_manifold") }
    val allocManifoldVec: MethodHandle by lazy { alloc("manifold_alloc_manifold_vec") }
    val allocSection: MethodHandle by lazy { alloc("manifold_alloc_cross_section") }
    val allocSectionVec: MethodHandle by lazy { alloc("manifold_alloc_cross_section_vec") }
    val allocMeshGL: MethodHandle by lazy { alloc("manifold_alloc_meshgl") }
    val allocMeshGL64: MethodHandle by lazy { alloc("manifold_alloc_meshgl64") }
    val allocBox: MethodHandle by lazy { alloc("manifold_alloc_box") }
    val allocRect: MethodHandle by lazy { alloc("manifold_alloc_rect") }
    val allocPolygons: MethodHandle by lazy { alloc("manifold_alloc_polygons") }
    val allocSimplePolygon: MethodHandle by lazy { alloc("manifold_alloc_simple_polygon") }
    val allocRayHitVec: MethodHandle by lazy { alloc("manifold_alloc_ray_hit_vec") }

    val deleteManifold: MethodHandle by lazy { consume("manifold_delete_manifold") }
    val deleteManifoldVec: MethodHandle by lazy { consume("manifold_delete_manifold_vec") }
    val deleteSection: MethodHandle by lazy { consume("manifold_delete_cross_section") }
    val deleteSectionVec: MethodHandle by lazy { consume("manifold_delete_cross_section_vec") }
    val deleteMeshGL: MethodHandle by lazy { consume("manifold_delete_meshgl") }
    val deleteMeshGL64: MethodHandle by lazy { consume("manifold_delete_meshgl64") }
    val deleteBox: MethodHandle by lazy { consume("manifold_delete_box") }
    val deleteRect: MethodHandle by lazy { consume("manifold_delete_rect") }
    val deletePolygons: MethodHandle by lazy { consume("manifold_delete_polygons") }
    val deleteSimplePolygon: MethodHandle by lazy { consume("manifold_delete_simple_polygon") }
    val deleteRayHitVec: MethodHandle by lazy { consume("manifold_delete_ray_hit_vec") }

    // ---- 3D constructors ----------------------------------------------------------------------

    val empty: MethodHandle by lazy { construct("manifold_empty") }
    val tetrahedron: MethodHandle by lazy { construct("manifold_tetrahedron") }
    val copy: MethodHandle by lazy { unary("manifold_copy") }
    val cube: MethodHandle by lazy {
        handle("manifold_cube", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_INT))
    }
    val sphere: MethodHandle by lazy {
        handle("manifold_sphere", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_DOUBLE, JAVA_INT))
    }
    val cylinder: MethodHandle by lazy {
        handle(
            "manifold_cylinder",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_INT, JAVA_INT),
        )
    }
    val extrude: MethodHandle by lazy {
        handle(
            "manifold_extrude",
            FunctionDescriptor.of(
                ADDRESS, ADDRESS, ADDRESS, JAVA_DOUBLE, JAVA_INT, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE,
            ),
        )
    }
    val revolve: MethodHandle by lazy {
        handle("manifold_revolve", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_DOUBLE))
    }
    val levelSet: MethodHandle by lazy {
        handle(
            "manifold_level_set",
            FunctionDescriptor.of(
                ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, ADDRESS,
            ),
        )
    }
    val levelSetSeq: MethodHandle by lazy {
        handle(
            "manifold_level_set_seq",
            FunctionDescriptor.of(
                ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, ADDRESS,
            ),
        )
    }

    // ---- 3D booleans and combination ----------------------------------------------------------

    val union: MethodHandle by lazy { binary("manifold_union") }
    val difference: MethodHandle by lazy { binary("manifold_difference") }
    val intersection: MethodHandle by lazy { binary("manifold_intersection") }
    val minkowskiSum: MethodHandle by lazy { binary("manifold_minkowski_sum") }
    val minkowskiDifference: MethodHandle by lazy { binary("manifold_minkowski_difference") }
    val batchBoolean: MethodHandle by lazy {
        handle("manifold_batch_boolean", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT))
    }
    val compose: MethodHandle by lazy { unary("manifold_compose") }
    val decompose: MethodHandle by lazy { unary("manifold_decompose") }
    val splitByPlane: MethodHandle by lazy {
        handle(
            "manifold_split_by_plane",
            FunctionDescriptor.of(
                PAIR, ADDRESS, ADDRESS, ADDRESS, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE,
            ),
        )
    }
    val split: MethodHandle by lazy {
        handle("manifold_split", FunctionDescriptor.of(PAIR, ADDRESS, ADDRESS, ADDRESS, ADDRESS))
    }
    val trimByPlane: MethodHandle by lazy { unaryWithDoubles("manifold_trim_by_plane", 4) }

    // ---- 3D transforms ------------------------------------------------------------------------

    val translate: MethodHandle by lazy { unaryWithDoubles("manifold_translate", 3) }
    val rotate: MethodHandle by lazy { unaryWithDoubles("manifold_rotate", 3) }
    val scale: MethodHandle by lazy { unaryWithDoubles("manifold_scale", 3) }
    val mirror: MethodHandle by lazy { unaryWithDoubles("manifold_mirror", 3) }
    val transform: MethodHandle by lazy { unaryWithDoubles("manifold_transform", 12) }
    val setTolerance: MethodHandle by lazy { unaryWithDoubles("manifold_set_tolerance", 1) }
    val simplify: MethodHandle by lazy { unaryWithDoubles("manifold_simplify", 1) }
    val refineToLength: MethodHandle by lazy { unaryWithDoubles("manifold_refine_to_length", 1) }
    val refineToTolerance: MethodHandle by lazy { unaryWithDoubles("manifold_refine_to_tolerance", 1) }
    val smoothOut: MethodHandle by lazy { unaryWithDoubles("manifold_smooth_out", 2) }
    val refine: MethodHandle by lazy {
        handle("manifold_refine", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT))
    }
    val calculateNormals: MethodHandle by lazy {
        handle(
            "manifold_calculate_normals",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_DOUBLE),
        )
    }
    val warp: MethodHandle by lazy {
        handle("manifold_warp", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS))
    }
    val hull: MethodHandle by lazy { unary("manifold_hull") }
    val hullPoints: MethodHandle by lazy {
        handle("manifold_hull_pts", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG))
    }

    // ---- 3D to 2D -----------------------------------------------------------------------------

    val slice: MethodHandle by lazy { unaryWithDoubles("manifold_slice", 1) }
    val project: MethodHandle by lazy { unary("manifold_project") }

    // ---- 3D info ------------------------------------------------------------------------------

    val volume: MethodHandle by lazy { measure("manifold_volume") }
    val surfaceArea: MethodHandle by lazy { measure("manifold_surface_area") }
    val tolerance: MethodHandle by lazy { measure("manifold_get_tolerance") }
    val epsilon: MethodHandle by lazy { measure("manifold_epsilon") }
    val numVert: MethodHandle by lazy { count("manifold_num_vert") }
    val numEdge: MethodHandle by lazy { count("manifold_num_edge") }
    val numTri: MethodHandle by lazy { count("manifold_num_tri") }
    val numProp: MethodHandle by lazy { count("manifold_num_prop") }
    val genus: MethodHandle by lazy { flag("manifold_genus") }
    val isEmpty: MethodHandle by lazy { flag("manifold_is_empty") }
    val status: MethodHandle by lazy { flag("manifold_status") }
    val originalId: MethodHandle by lazy { flag("manifold_original_id") }
    val boundingBox: MethodHandle by lazy { unary("manifold_bounding_box") }
    val minGap: MethodHandle by lazy {
        handle("manifold_min_gap", FunctionDescriptor.of(JAVA_DOUBLE, ADDRESS, ADDRESS, JAVA_DOUBLE))
    }
    val circularSegments: MethodHandle by lazy {
        handle("manifold_get_circular_segments", FunctionDescriptor.of(JAVA_INT, JAVA_DOUBLE))
    }
    val setCircularSegments: MethodHandle by lazy {
        handle("manifold_set_circular_segments", FunctionDescriptor.ofVoid(JAVA_INT))
    }
    val setMinCircularAngle: MethodHandle by lazy {
        handle("manifold_set_min_circular_angle", FunctionDescriptor.ofVoid(JAVA_DOUBLE))
    }
    val setMinCircularEdgeLength: MethodHandle by lazy {
        handle("manifold_set_min_circular_edge_length", FunctionDescriptor.ofVoid(JAVA_DOUBLE))
    }
    val resetCircularDefaults: MethodHandle by lazy {
        handle("manifold_reset_to_circular_defaults", FunctionDescriptor.ofVoid())
    }

    // ---- ray casting --------------------------------------------------------------------------

    val rayCast: MethodHandle by lazy {
        handle(
            "manifold_ray_cast",
            FunctionDescriptor.of(
                ADDRESS, ADDRESS, ADDRESS,
                JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE,
            ),
        )
    }
    val rayHitVecLength: MethodHandle by lazy { count("manifold_ray_hit_vec_length") }
    val rayHitVecGet: MethodHandle by lazy {
        handle("manifold_ray_hit_vec_get", FunctionDescriptor.of(RAY_HIT, ADDRESS, JAVA_LONG))
    }

    // ---- manifold vectors (for the batch ops) -------------------------------------------------

    val manifoldVec: MethodHandle by lazy {
        handle("manifold_manifold_vec", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG))
    }
    val manifoldVecSet: MethodHandle by lazy {
        handle("manifold_manifold_vec_set", FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, ADDRESS))
    }
    val manifoldVecLength: MethodHandle by lazy { count("manifold_manifold_vec_length") }
    val manifoldVecGet: MethodHandle by lazy {
        handle("manifold_manifold_vec_get", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG))
    }
    val batchHull: MethodHandle by lazy { unary("manifold_batch_hull") }

    // ---- MeshGL: the mesh bridge both ways ----------------------------------------------------

    val getMeshGL: MethodHandle by lazy { unary("manifold_get_meshgl") }
    val getMeshGLWithNormals: MethodHandle by lazy {
        handle("manifold_get_meshgl_w_normals", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT))
    }
    val ofMeshGL: MethodHandle by lazy { unary("manifold_of_meshgl") }
    val meshGL: MethodHandle by lazy {
        handle(
            "manifold_meshgl",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, JAVA_LONG, ADDRESS, JAVA_LONG),
        )
    }
    val meshGLNumVert: MethodHandle by lazy { count("manifold_meshgl_num_vert") }
    val meshGLNumTri: MethodHandle by lazy { count("manifold_meshgl_num_tri") }
    val meshGLNumProp: MethodHandle by lazy { count("manifold_meshgl_num_prop") }
    val meshGLVertProperties: MethodHandle by lazy { unary("manifold_meshgl_vert_properties") }
    val meshGLTriVerts: MethodHandle by lazy { unary("manifold_meshgl_tri_verts") }
    val meshGLMergeLength: MethodHandle by lazy { count("manifold_meshgl_merge_length") }
    val meshGLMergeFromVert: MethodHandle by lazy { unary("manifold_meshgl_merge_from_vert") }
    val meshGLMergeToVert: MethodHandle by lazy { unary("manifold_meshgl_merge_to_vert") }

    // MeshGL64 is the fabrication bridge. Unlike the ordinary rendering export it retains
    // Manifold's internal double precision and, crucially, the run/face identity needed to
    // reconstruct the polygonal planar faces behind the output triangulation.
    val getMeshGL64: MethodHandle by lazy { unary("manifold_get_meshgl64") }
    val ofMeshGL64: MethodHandle by lazy { unary("manifold_of_meshgl64") }
    val meshGL64: MethodHandle by lazy {
        handle(
            "manifold_meshgl64",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, JAVA_LONG, ADDRESS, JAVA_LONG),
        )
    }
    val meshGL64NumVert: MethodHandle by lazy { count("manifold_meshgl64_num_vert") }
    val meshGL64NumTri: MethodHandle by lazy { count("manifold_meshgl64_num_tri") }
    val meshGL64NumProp: MethodHandle by lazy { count("manifold_meshgl64_num_prop") }
    val meshGL64MergeLength: MethodHandle by lazy { count("manifold_meshgl64_merge_length") }
    val meshGL64RunIndexLength: MethodHandle by lazy { count("manifold_meshgl64_run_index_length") }
    val meshGL64RunOriginalIdLength: MethodHandle by lazy { count("manifold_meshgl64_run_original_id_length") }
    val meshGL64FaceIdLength: MethodHandle by lazy { count("manifold_meshgl64_face_id_length") }
    val meshGL64VertProperties: MethodHandle by lazy { unary("manifold_meshgl64_vert_properties") }
    val meshGL64TriVerts: MethodHandle by lazy { unary("manifold_meshgl64_tri_verts") }
    val meshGL64MergeFromVert: MethodHandle by lazy { unary("manifold_meshgl64_merge_from_vert") }
    val meshGL64MergeToVert: MethodHandle by lazy { unary("manifold_meshgl64_merge_to_vert") }
    val meshGL64RunIndex: MethodHandle by lazy { unary("manifold_meshgl64_run_index") }
    val meshGL64RunOriginalId: MethodHandle by lazy { unary("manifold_meshgl64_run_original_id") }
    val meshGL64FaceId: MethodHandle by lazy { unary("manifold_meshgl64_face_id") }
    val meshGL64Tolerance: MethodHandle by lazy {
        handle("manifold_meshgl64_tolerance", FunctionDescriptor.of(JAVA_DOUBLE, ADDRESS))
    }

    // ---- boxes and rects ----------------------------------------------------------------------

    val boxOf: MethodHandle by lazy {
        handle(
            "manifold_box",
            FunctionDescriptor.of(
                ADDRESS, ADDRESS,
                JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE,
            ),
        )
    }
    val boxMin: MethodHandle by lazy { handle("manifold_box_min", FunctionDescriptor.of(VEC3, ADDRESS)) }
    val boxMax: MethodHandle by lazy { handle("manifold_box_max", FunctionDescriptor.of(VEC3, ADDRESS)) }
    val rectMin: MethodHandle by lazy { handle("manifold_rect_min", FunctionDescriptor.of(VEC2, ADDRESS)) }
    val rectMax: MethodHandle by lazy { handle("manifold_rect_max", FunctionDescriptor.of(VEC2, ADDRESS)) }

    // ---- 2D: cross sections -------------------------------------------------------------------

    val sectionEmpty: MethodHandle by lazy { construct("manifold_cross_section_empty") }
    val sectionCopy: MethodHandle by lazy { unary("manifold_cross_section_copy") }
    val sectionSquare: MethodHandle by lazy {
        handle(
            "manifold_cross_section_square",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_INT),
        )
    }
    val sectionCircle: MethodHandle by lazy {
        handle("manifold_cross_section_circle", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_DOUBLE, JAVA_INT))
    }
    val sectionOfPolygons: MethodHandle by lazy {
        handle(
            "manifold_cross_section_of_polygons",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT),
        )
    }
    val sectionUnion: MethodHandle by lazy { binary("manifold_cross_section_union") }
    val sectionDifference: MethodHandle by lazy { binary("manifold_cross_section_difference") }
    val sectionIntersection: MethodHandle by lazy { binary("manifold_cross_section_intersection") }
    val sectionTranslate: MethodHandle by lazy { unaryWithDoubles("manifold_cross_section_translate", 2) }
    val sectionRotate: MethodHandle by lazy { unaryWithDoubles("manifold_cross_section_rotate", 1) }
    val sectionScale: MethodHandle by lazy { unaryWithDoubles("manifold_cross_section_scale", 2) }
    val sectionMirror: MethodHandle by lazy { unaryWithDoubles("manifold_cross_section_mirror", 2) }
    val sectionSimplify: MethodHandle by lazy { unaryWithDoubles("manifold_cross_section_simplify", 1) }
    val sectionOffset: MethodHandle by lazy {
        handle(
            "manifold_cross_section_offset",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_DOUBLE, JAVA_INT, JAVA_DOUBLE, JAVA_INT),
        )
    }
    val sectionHull: MethodHandle by lazy { unary("manifold_cross_section_hull") }
    val sectionDecompose: MethodHandle by lazy { unary("manifold_cross_section_decompose") }
    val sectionArea: MethodHandle by lazy { measure("manifold_cross_section_area") }
    val sectionNumVert: MethodHandle by lazy { count("manifold_cross_section_num_vert") }
    val sectionNumContour: MethodHandle by lazy { count("manifold_cross_section_num_contour") }
    val sectionIsEmpty: MethodHandle by lazy { flag("manifold_cross_section_is_empty") }
    val sectionBounds: MethodHandle by lazy { unary("manifold_cross_section_bounds") }
    val sectionToPolygons: MethodHandle by lazy { unary("manifold_cross_section_to_polygons") }
    val sectionVecLength: MethodHandle by lazy { count("manifold_cross_section_vec_length") }
    val sectionVecGet: MethodHandle by lazy {
        handle("manifold_cross_section_vec_get", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG))
    }
    val sectionVecOf: MethodHandle by lazy {
        handle("manifold_cross_section_vec", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG))
    }
    val sectionVecSet: MethodHandle by lazy {
        handle("manifold_cross_section_vec_set", FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, ADDRESS))
    }
    val sectionBatchBoolean: MethodHandle by lazy {
        handle("manifold_cross_section_batch_boolean", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT))
    }

    // ---- polygons -----------------------------------------------------------------------------

    val simplePolygon: MethodHandle by lazy {
        handle("manifold_simple_polygon", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG))
    }
    val polygonsOf: MethodHandle by lazy {
        handle("manifold_polygons", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG))
    }
    val polygonsLength: MethodHandle by lazy { count("manifold_polygons_length") }
    val polygonsSimpleLength: MethodHandle by lazy {
        handle("manifold_polygons_simple_length", FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG))
    }
    val polygonsGetPoint: MethodHandle by lazy {
        handle("manifold_polygons_get_point", FunctionDescriptor.of(VEC2, ADDRESS, JAVA_LONG, JAVA_LONG))
    }
    val simplePolygonSize: MethodHandle by lazy {
        handle("manifold_simple_polygon_size", FunctionDescriptor.of(JAVA_LONG))
    }
    val polygonsSize: MethodHandle by lazy {
        handle("manifold_polygons_size", FunctionDescriptor.of(JAVA_LONG))
    }

    // ==========================================================================================
    // Invocation helpers
    // ==========================================================================================

    /**
     * Invoke a handle whose C return type is a **struct**, which FFM models as an extra leading
     * [SegmentAllocator] holding the returned bytes. Kotlin cannot make a signature-polymorphic
     * `invokeExact` call cleanly, so like [afield.stdlib.ceres.FfmCeresBackend] this whole file goes
     * through [MethodHandle.invokeWithArguments]: the boxing is a rounding error next to the
     * geometry, and the argument lists stay readable.
     */
    fun invokeStruct(handle: MethodHandle, allocator: SegmentAllocator, vararg args: Any?): MemorySegment =
        handle.invokeWithArguments(allocator, *args) as MemorySegment

    fun ptr(handle: MethodHandle, vararg args: Any?): MemorySegment =
        handle.invokeWithArguments(*args) as MemorySegment

    fun double(handle: MethodHandle, vararg args: Any?): Double =
        handle.invokeWithArguments(*args) as Double

    fun long(handle: MethodHandle, vararg args: Any?): Long =
        handle.invokeWithArguments(*args) as Long

    fun int(handle: MethodHandle, vararg args: Any?): Int =
        handle.invokeWithArguments(*args) as Int

    fun unit(handle: MethodHandle, vararg args: Any?) {
        handle.invokeWithArguments(*args)
    }

    /** A fresh `ManifoldManifold*` from the library's own allocator, ready to be constructed into. */
    fun newManifold(): MemorySegment = ptr(allocManifold)

    /** A fresh `ManifoldCrossSection*`. */
    fun newSection(): MemorySegment = ptr(allocSection)

    fun vec3(segment: MemorySegment): Triple<Double, Double, Double> = Triple(
        segment.get(JAVA_DOUBLE, 0),
        segment.get(JAVA_DOUBLE, 8),
        segment.get(JAVA_DOUBLE, 16),
    )

    fun vec2(segment: MemorySegment): Pair<Double, Double> =
        segment.get(JAVA_DOUBLE, 0) to segment.get(JAVA_DOUBLE, 8)

    // ==========================================================================================
    // Upcalls
    // ==========================================================================================

    /**
     * The Kotlin side of a `ManifoldSdf`, and the reason it is a class rather than a lambda: FFM
     * needs a [MethodHandle] with the callback's exact signature, which means a real method with the
     * trailing `void* ctx` in it.
     *
     * [firstFailure] is the exception channel. Manifold calls [sample] from its own thread pool, so
     * a throw here would unwind through C++ frames — undefined behaviour, and FFM's response to an
     * escaping exception is to take the VM down. Instead the first throwable is kept and a finite
     * sentinel returned; the caller rethrows once the native call is over. The sentinel is
     * deliberately *not* NaN: a NaN sample propagates into the marching-cubes interpolation and
     * comes back as a corrupt mesh, whereas a large positive value simply reads as "far outside" and
     * leaves the shape empty, which is the honest answer for a field that failed.
     */
    class SdfBridge(private val field: (Double, Double, Double) -> Double) {
        @Volatile
        var firstFailure: Throwable? = null
            private set

        fun sample(x: Double, y: Double, z: Double, @Suppress("UNUSED_PARAMETER") ctx: MemorySegment): Double =
            try {
                field(x, y, z)
            } catch (t: Throwable) {
                if (firstFailure == null) firstFailure = t
                FAILED_SAMPLE
            }
    }

    /**
     * The Kotlin side of `manifold_warp`'s callback. Returns a struct, so [sample] hands back a
     * 24-byte [MemorySegment] whose contents FFM copies out on return.
     *
     * The segment is a per-thread scratch buffer rather than a fresh allocation, because this is
     * called once per vertex from Manifold's thread pool and an [Arena] per vertex would dominate
     * the cost of the warp itself. Reuse is safe precisely because the copy-out happens before this
     * thread can call [sample] again.
     */
    class WarpBridge(private val map: (Double, Double, Double) -> Triple<Double, Double, Double>) {
        @Volatile
        var firstFailure: Throwable? = null
            private set

        private val scratch = ThreadLocal.withInitial { Arena.ofAuto().allocate(VEC3) }

        fun sample(x: Double, y: Double, z: Double, @Suppress("UNUSED_PARAMETER") ctx: MemorySegment): MemorySegment {
            val out = scratch.get()
            val moved = try {
                map(x, y, z)
            } catch (t: Throwable) {
                if (firstFailure == null) firstFailure = t
                Triple(x, y, z)     // leave the vertex where it was rather than fold the mesh onto itself
            }
            out.set(JAVA_DOUBLE, 0, moved.first)
            out.set(JAVA_DOUBLE, 8, moved.second)
            out.set(JAVA_DOUBLE, 16, moved.third)
            return out
        }
    }

    private val SDF_DESCRIPTOR = FunctionDescriptor.of(
        JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, ADDRESS,
    )

    private val WARP_DESCRIPTOR = FunctionDescriptor.of(
        VEC3, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, ADDRESS,
    )

    private val SDF_TARGET: MethodHandle = MethodHandles.lookup().findVirtual(
        SdfBridge::class.java,
        "sample",
        MethodType.methodType(
            Double::class.javaPrimitiveType,
            Double::class.javaPrimitiveType,
            Double::class.javaPrimitiveType,
            Double::class.javaPrimitiveType,
            MemorySegment::class.java,
        ),
    )

    private val WARP_TARGET: MethodHandle = MethodHandles.lookup().findVirtual(
        WarpBridge::class.java,
        "sample",
        MethodType.methodType(
            MemorySegment::class.java,
            Double::class.javaPrimitiveType,
            Double::class.javaPrimitiveType,
            Double::class.javaPrimitiveType,
            MemorySegment::class.java,
        ),
    )

    /** A `ManifoldSdf` function pointer valid for the life of [arena]. */
    fun upcallSdf(bridge: SdfBridge, arena: Arena): MemorySegment =
        linker.upcallStub(SDF_TARGET.bindTo(bridge), SDF_DESCRIPTOR, arena)

    /** A `manifold_warp` function pointer valid for the life of [arena]. */
    fun upcallWarp(bridge: WarpBridge, arena: Arena): MemorySegment =
        linker.upcallStub(WARP_TARGET.bindTo(bridge), WARP_DESCRIPTOR, arena)

    /** What an SDF that threw reports instead. See [SdfBridge]. */
    const val FAILED_SAMPLE: Double = 1e30
}

/**
 * Where `libmanifoldc` is, and what to do when it is not there.
 *
 * Manifold is an **optional** dependency, exactly like the Ceres wrapper: a clone of Field builds,
 * tests and runs without it, and only the boxes that reach for [Solid] need it. So nothing in the
 * build downloads or vendors it, and every entry point that needs it says so in its error message.
 *
 * Resolution order, first hit wins:
 *
 *  1. `-Dafield.manifold.library=<path>` (a JVM system property)
 *  2. `AFIELD_MANIFOLD_LIBRARY=<path>` (an environment variable, which is also what the docs build
 *     reads, since it cannot see the property)
 *  3. the platform's usual places — Homebrew on both macOS architectures, `/usr/local`, the two
 *     common Linux multiarch directories
 *  4. the plain library name, which lets the dynamic loader search `DYLD_/LD_LIBRARY_PATH`
 */
object ManifoldLibrary {

    private val isWindows: Boolean
        get() = System.getProperty("os.name").lowercase().contains("win")

    private val isMac: Boolean
        get() = System.getProperty("os.name").lowercase().let { it.contains("mac") || it.contains("darwin") }

    /** `libmanifoldc.dylib` / `.so` / `manifoldc.dll`, chosen for the platform actually running. */
    val fileName: String
        get() = when {
            isWindows -> "manifoldc.dll"
            isMac -> "libmanifoldc.dylib"
            else -> "libmanifoldc.so"
        }

    /** Every path [resolvedPath] tries, in order — quoted in the "not installed" error. */
    val searchedPaths: List<Path>
        get() {
            val explicit = listOfNotNull(
                System.getProperty("afield.manifold.library")?.takeIf { it.isNotBlank() },
                System.getenv("AFIELD_MANIFOLD_LIBRARY")?.takeIf { it.isNotBlank() },
            ).map(Path::of)
            val conventional = when {
                isWindows -> listOf("C:\\Program Files\\manifold\\bin")
                isMac -> listOf("/opt/homebrew/lib", "/usr/local/lib")
                else -> listOf("/usr/local/lib", "/usr/lib", "/usr/lib/x86_64-linux-gnu", "/usr/lib/aarch64-linux-gnu")
            }.map { Path.of(it, fileName) }
            return explicit + conventional
        }

    /**
     * The first path that exists, or `null`.
     *
     * Not cached: `brew install manifold` during a live session should be picked up by the next box
     * run rather than needing a restart, and the cost is a handful of `stat` calls on a path that is
     * only consulted once per VM by [ManifoldNative.lookup] anyway.
     */
    val resolvedPath: Path?
        get() = searchedPaths.firstOrNull { it.exists() }

    /** `true` when [Solid] and [Section] will work. Cheap enough to branch on in a box. */
    val isAvailable: Boolean
        get() = resolvedPath != null

    /**
     * The upstream version, read off the `.dylib`/`.so` filename that the SO-versioned symlink
     * points at — `libmanifoldc.3.5.2.dylib` reports `3.5.2`.
     *
     * There is no `manifold_version()` in the C API to ask instead, and reading it this way is
     * honest about being a guess: `null` means the file was not laid out the way a released one is,
     * not that the library is broken.
     */
    val reportedVersion: String?
        get() {
            val path = resolvedPath ?: return null
            val real = runCatching { path.toRealPath() }.getOrNull() ?: return null
            return Regex("""(\d+\.\d+\.\d+)""").find(real.fileName.toString())?.groupValues?.get(1)
        }

    /** What to tell someone who has not got it, in the package manager they are likely to have. */
    val HOW_TO_INSTALL: String
        get() = when {
            isMac -> "Install it with `brew install manifold`, or point -Dafield.manifold.library at a libmanifoldc you built."
            isWindows -> "Build Manifold with -DMANIFOLD_CBIND=ON and point -Dafield.manifold.library at manifoldc.dll."
            else -> "Install it with your package manager (`apt install libmanifold-dev`, `pacman -S manifold`) " +
                "or build Manifold with -DMANIFOLD_CBIND=ON, then point -Dafield.manifold.library at libmanifoldc.so."
        }

    /** One line for a box or a smoke test to print: what was found, or why not. */
    fun describe(): String = resolvedPath?.let {
        "Manifold ${reportedVersion ?: "(version unknown)"} at $it"
    } ?: "Manifold not installed. $HOW_TO_INSTALL"
}

/**
 * `ManifoldError`, the status a [Solid] carries when an operation could not produce a valid solid.
 *
 * Manifold does not throw and does not return null: a failed operation gives you a solid whose
 * [Solid.status] is not [NO_ERROR], and whose volume is zero. That is a deliberate upstream choice —
 * it keeps a long boolean chain from needing a check per step — and this enum is where Field turns it
 * back into something a box can read. [Solid.checked] is the one-line version.
 */
enum class ManifoldStatus(val code: Int) {
    NO_ERROR(0),
    NON_FINITE_VERTEX(1),
    NOT_MANIFOLD(2),
    VERTEX_INDEX_OUT_OF_BOUNDS(3),
    PROPERTIES_WRONG_LENGTH(4),
    MISSING_POSITION_PROPERTIES(5),
    MERGE_VECTORS_DIFFERENT_LENGTHS(6),
    MERGE_INDEX_OUT_OF_BOUNDS(7),
    TRANSFORM_WRONG_LENGTH(8),
    RUN_INDEX_WRONG_LENGTH(9),
    FACE_ID_WRONG_LENGTH(10),
    INVALID_CONSTRUCTION(11),
    RESULT_TOO_LARGE(12),
    INVALID_TANGENTS(13),
    CANCELLED(14),
    ;

    /** `true` for everything except [NO_ERROR]. */
    val isError: Boolean get() = this != NO_ERROR

    companion object {
        /** The enum for a raw `ManifoldError`, or [INVALID_CONSTRUCTION] for a code from a newer Manifold. */
        fun of(code: Int): ManifoldStatus = entries.firstOrNull { it.code == code } ?: INVALID_CONSTRUCTION
    }
}

/** `ManifoldOpType`, the operation [Solid.batchBoolean] and [Section.batchBoolean] apply. */
enum class BooleanOp(internal val code: Int) { UNION(0), DIFFERENCE(1), INTERSECTION(2) }

/**
 * `ManifoldJoinType` — how [Section.offset] treats a convex corner.
 *
 * [ROUND] is the default because it is the one that makes an offset read as a *fillet*; [MITER]
 * keeps corners sharp up to `miterLimit` and then bevels them, [SQUARE] and [BEVEL] cut them off
 * immediately.
 */
enum class JoinType(internal val code: Int) { SQUARE(0), ROUND(1), MITER(2), BEVEL(3) }

/**
 * `ManifoldFillRule` — how a set of contours decides what is inside, when they overlap or nest.
 *
 * [POSITIVE] is Field's default rather than Clipper's [EVEN_ODD], because it is the rule that makes
 * winding *mean* something: a hole is a contour wound the other way, so a ring is two contours and
 * two overlapping same-wound outlines are one shape rather than a shape with an accidental hole.
 */
enum class FillRule(internal val code: Int) { EVEN_ODD(0), NON_ZERO(1), POSITIVE(2), NEGATIVE(3) }

/** One intersection from [Solid.raycast]. */
data class RayHit(
    /** Which triangle of the solid was hit, as Manifold's face id. */
    val faceId: Long,
    /** Distance from the ray origin, in the units the solid is in. */
    val distance: Double,
    val x: Double,
    val y: Double,
    val z: Double,
    val normalX: Double,
    val normalY: Double,
    val normalZ: Double,
)

/** Internal float/int accessors kept beside the layouts they read. */
internal fun MemorySegment.floatAt(index: Long): Float = getAtIndex(JAVA_FLOAT, index)
internal fun MemorySegment.intAt(index: Long): Int = getAtIndex(JAVA_INT, index)

internal fun MemorySegment.doubleAt(index: Long): Double = getAtIndex(JAVA_DOUBLE, index)
internal fun MemorySegment.longAt(index: Long): Long = getAtIndex(JAVA_LONG, index)
